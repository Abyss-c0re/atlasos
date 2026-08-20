package com.titanus2.atlas;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Foreground service: session keep-alive <b>and</b> product Authentication Agent.
 * Polls {@link AtlasAuth} for biometrics on sudo/apt <b>and</b> Remote ADB arm.
 * Runs OS-wise (boot receiver) so Wi‑Fi-off / Tailscale privilege gates work
 * without opening the terminal UI.
 */
public class AtlasSessionService extends Service {
    private static final String TAG = "AtlasSession";
    public static final String CHANNEL = "atlas_sessions";
    public static final int NOTIF_ID = 0xA71A5;
    public static final String ACTION_REFRESH = "com.titanus2.atlas.REFRESH_SESSIONS";
    public static final String ACTION_STOP = "com.titanus2.atlas.STOP_KEEPALIVE";
    public static final String ACTION_ENSURE_AUTH = "com.titanus2.atlas.ENSURE_AUTH_AGENT";
    public static final String EXTRA_COUNT = "count";
    public static final String EXTRA_NOTE = "note";

    /** Hybrid overlay health — recover dirty unmount without opening UI. */
    private static final long HYBRID_HEALTH_MS = 45_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable authPoll = new Runnable() {
        @Override
        public void run() {
            try {
                AtlasPrefs.isAuthUiShowing(AtlasSessionService.this);
                AtlasAuth.pollOnce(AtlasSessionService.this);
            } catch (Exception ignored) {
            }
            handler.postDelayed(this, 2000);
        }
    };
    private final Runnable hybridHealth = new Runnable() {
        @Override
        public void run() {
            try {
                HybridEnsure.healthTick(AtlasSessionService.this);
            } catch (Exception ignored) {
            }
            handler.postDelayed(this, HYBRID_HEALTH_MS);
        }
    };

    /**
     * Product: keep Authentication Agent alive even with zero terminal sessions.
     * Call from boot, package replace, and before Remote ADB arm.
     */
    public static void ensureAuthAgent(Context c) {
        if (c == null) return;
        Intent i = new Intent(c, AtlasSessionService.class);
        i.setAction(ACTION_ENSURE_AUTH);
        i.putExtra(EXTRA_COUNT, Math.max(0, AtlasPrefs.liveSessionCount(c)));
        i.putExtra(EXTRA_NOTE, "Authentication Agent");
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "ensureAuthAgent failed", e);
        }
    }

    public static void startOrUpdate(Context c, int sessionCount, String note) {
        // Auth agent is product-always-on; do not tear down when sessions=0.
        if (!AtlasPrefs.keepAlive(c) && sessionCount <= 0
                && !AtlasPrefs.authAgentAlways(c)) {
            stop(c);
            return;
        }
        Intent i = new Intent(c, AtlasSessionService.class);
        i.setAction(ACTION_REFRESH);
        i.putExtra(EXTRA_COUNT, sessionCount);
        i.putExtra(EXTRA_NOTE, note != null ? note : "");
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Exception ignored) {
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, AtlasSessionService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        AuthWatch.start(this);
        handler.post(authPoll);
        // First health soon after FGS up (boot may still be settling), then interval.
        handler.postDelayed(hybridHealth, 12_000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // Product: "Stop" only drops session keep-alive claim; auth agent
            // restarts on next boot / ensureAuthAgent. Do not leave TCP ADB open
            // without an agent — disarm is separate (Controls Remote ADB off).
            handler.removeCallbacks(hybridHealth);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        int count = intent != null
            ? intent.getIntExtra(EXTRA_COUNT, AtlasPrefs.liveSessionCount(this))
            : AtlasPrefs.liveSessionCount(this);
        String note = intent != null ? intent.getStringExtra(EXTRA_NOTE) : null;
        if (note == null || note.isEmpty()) {
            note = count > 0 ? "sessions" : "Authentication Agent";
        }
        // Prefer hybrid-aware note when overlay is down so user sees state in shade.
        if (AtlasPrefs.privilegedHybrid(this) && !NativeBin.hybridRootfsReady()) {
            if (note == null || note.isEmpty() || "Authentication Agent".equals(note)
                    || "auth-poll".equals(note) || "sessions".equals(note)) {
                note = "hybrid↓ ensuring…";
            }
            HybridEnsure.healthTick(this);
        }
        AtlasPrefs.setLiveSessionCount(this, count);
        startForeground(NOTIF_ID, buildNotif(count, note));
        // Sticky: auth agent must survive swipe-from-recents for Remote ADB bio
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(authPoll);
        handler.removeCallbacks(hybridHealth);
        AuthWatch.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
            CHANNEL, "Atlas sessions", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Ongoing terminal / hybrid sessions");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotif(int count, String note) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Intent stop = new Intent(this, AtlasSessionService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
            this, 1, stop,
            PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        String title = count > 0
            ? ("Atlas · " + count + " session" + (count == 1 ? "" : "s"))
            : "Atlas · Authentication Agent";
        String text = (note != null && !note.isEmpty())
            ? note
            : "Biometrics for sudo / apt / Remote ADB";

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Open", pi)
            .addAction(0, "Stop", stopPi);
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }
}
