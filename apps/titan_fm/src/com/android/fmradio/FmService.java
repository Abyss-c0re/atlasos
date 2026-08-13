package com.android.fmradio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Foreground media service so FM survives screen-off / activity death.
 * Unrestricted battery alone is not enough without an FGS + ongoing notification.
 */
public final class FmService extends Service implements FmEngine.Listener {
    private static final String TAG = "TitanFm";
    public static final String ACTION_POWER_ON = "com.android.fmradio.action.POWER_ON";
    public static final String ACTION_POWER_OFF = "com.android.fmradio.action.POWER_OFF";
    public static final String ACTION_TOGGLE = "com.android.fmradio.action.TOGGLE";
    public static final String ACTION_TUNE = "com.android.fmradio.action.TUNE";
    public static final String EXTRA_FREQ = "freq";

    private static final String CH_ID = "fm_playback";
    private static final int NOTIF_ID = 42;

    private FmEngine mEngine;
    private NotificationManager mNm;
    private long mLastNotifMs;

    public static void powerOn(Context c, float freq) {
        Intent i = new Intent(c, FmService.class).setAction(ACTION_POWER_ON);
        i.putExtra(EXTRA_FREQ, freq);
        start(c, i);
    }

    public static void powerOff(Context c) {
        start(c, new Intent(c, FmService.class).setAction(ACTION_POWER_OFF));
    }

    public static void toggle(Context c, float freq) {
        Intent i = new Intent(c, FmService.class).setAction(ACTION_TOGGLE);
        i.putExtra(EXTRA_FREQ, freq);
        start(c, i);
    }

    public static void ensureRunning(Context c) {
        // No-op if already up; keeps process if UI is open while idle.
        start(c, new Intent(c, FmService.class));
    }

    private static void start(Context c, Intent i) {
        Context app = c.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 26) {
            app.startForegroundService(i);
        } else {
            app.startService(i);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mNm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        ensureChannel();
        mEngine = FmEngine.get(this);
        mEngine.setServiceListener(this);
        // Enter FGS immediately so short startService bursts are legal.
        promoteForeground(false);
        Log.i(TAG, "FmService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promoteForeground(mEngine != null && mEngine.isPowered());
        if (intent == null) {
            return START_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_POWER_ON.equals(action)) {
            MicCaptureGate.State mic = MicCaptureGate.check(this);
            if (mic != MicCaptureGate.State.OK) {
                Log.w(TAG, "power blocked: " + MicCaptureGate.message(mic));
                // Still open UI path if already powered; do not start silent radio.
                if (!mEngine.isPowered()) {
                    promoteForeground(false);
                    updateNotification();
                    return START_NOT_STICKY;
                }
            }
            float f = intent.getFloatExtra(EXTRA_FREQ, mEngine.getFrequency());
            mEngine.tune(f);
            // forceReset inside powerOn — safe to call after Stop / crash restart
            mEngine.powerOn();
            promoteForeground(true);
        } else if (ACTION_POWER_OFF.equals(action)) {
            // Wait for chip powerDown before tearing FGS — race was leaving HAL on.
            mEngine.powerOffSync(1500);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        } else if (ACTION_TOGGLE.equals(action)) {
            if (mEngine.isPowered()) {
                mEngine.powerOffSync(1500);
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
            float f = intent.getFloatExtra(EXTRA_FREQ, mEngine.getFrequency());
            mEngine.tune(f);
            mEngine.powerOn();
            promoteForeground(true);
        } else if (ACTION_TUNE.equals(action)) {
            float f = intent.getFloatExtra(EXTRA_FREQ, mEngine.getFrequency());
            mEngine.tune(f);
            updateNotification();
        } else {
            // keep-alive / restart after kill — re-assert FGS + re-power if flag set
            if (mEngine.isPowered()) {
                promoteForeground(true);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // Do not release engine chip here on every destroy — only stop if we powered off.
        // If system kills FGS, START_STICKY will recreate; engine singleton may still be warm.
        Log.i(TAG, "FmService destroy powered=" + (mEngine != null && mEngine.isPowered()));
        if (mEngine != null) {
            mEngine.setServiceListener(null);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onState(String line) {
        // keep notification text light
        updateNotification();
    }

    @Override
    public void onPower(boolean on) {
        if (on) {
            promoteForeground(true);
        } else {
            stopForeground(true);
        }
        updateNotification();
    }

    @Override
    public void onFrequency(float mhz) {
        updateNotification();
    }

    @Override
    public void onRds(String ps, String rt) {
        updateNotification();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(
                CH_ID, "FM Radio", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Playing FM in background");
        ch.setShowBadge(false);
        mNm.createNotificationChannel(ch);
    }

    private void promoteForeground(boolean playing) {
        Notification n = buildNotification(playing);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground", t);
            try {
                startForeground(NOTIF_ID, n);
            } catch (Throwable t2) {
                Log.e(TAG, "startForeground fallback", t2);
            }
        }
    }

    private void updateNotification() {
        if (mNm == null || mEngine == null) return;
        boolean on = mEngine.isPowered();
        if (!on) return;
        long now = System.currentTimeMillis();
        // Avoid NotificationService "enqueue rate" shedding on rapid tune/RDS
        if (now - mLastNotifMs < 400) return;
        mLastNotifMs = now;
        mNm.notify(NOTIF_ID, buildNotification(true));
    }

    private Notification buildNotification(boolean playing) {
        float freq = mEngine != null ? mEngine.getFrequency() : 0f;
        String title = playing
                ? String.format(java.util.Locale.US, "FM %.1f MHz", freq)
                : "FM Radio";
        String text = playing ? "Playing — tap to open" : "Idle";

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Intent stop = new Intent(this, FmService.class).setAction(ACTION_POWER_OFF);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CH_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (playing) {
            b.addAction(new Notification.Action.Builder(
                    null, "Stop", stopPi).build());
        }
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }
}
