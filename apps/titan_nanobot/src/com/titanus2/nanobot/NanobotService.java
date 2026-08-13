package com.titanus2.nanobot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Product peer service (loopback HTTP for Cube / tools + Grok session).
 * CLI still owns device-code auth; peer serves Cube on 127.0.0.1 only (no LAN
 * unless shareLan). Cube Stability: never bind 0.0.0.0 by default.
 *
 * <p>1.7.8 residual: ensureLoopbackPeer slept 3–5s on the main thread
 * (watchdog + onStartCommand) → Input dispatch ANR → Nanobot "crashing"
 * and Cube looking dead. All peer ensure work is off-main only.
 *
 * <p>1.8.2 residual: spawn used {@link NanobotCli#productHome} which fell back
 * to app-private when shared write failed; :8787 then reported signed_in=false
 * while {@code /data/local/tmp/nanobot_home/session} still held Grok. Always
 * spawn on SHARED_HOME; heal wrong-home peers already listening.
 */
public class NanobotService extends Service {
    private static final String TAG = "NanobotService";
    private static final String CH = "titan2_nanobot";
    public static final String ACTION_STOP = "com.titanus2.nanobot.STOP";
    /** Keep device-code poll alive while browser is foreground (app may be paused). */
    public static final String ACTION_AUTH_WATCH = "com.titanus2.nanobot.AUTH_WATCH";
    /** Cube / product: ensure 127.0.0.1:8787 with sealed Grok home. */
    public static final String ACTION_ENSURE_LOOPBACK = "com.titanus2.nanobot.ENSURE_LOOPBACK";
    private Process proc;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Handler bg = new Handler(Looper.getMainLooper());
    private final Object peerLock = new Object();
    private volatile boolean peerEnsureRunning;
    private volatile long lastPeerFailMs;
    private volatile int peerFailStreak;
    private int authWatchLeft;
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            try {
                if (!PrivacyPrefs.serviceEnabled(NanobotService.this)) {
                    Log.i(TAG, "service disabled — stopping self");
                    stopSelf();
                    return;
                }
                // Never block main: isPortListening + spawn only on worker.
                scheduleEnsureLoopback("watchdog");
            } catch (Throwable t) {
                Log.e(TAG, "watchdog: " + t.getMessage());
            }
            // Back off while failing so we don't ANR/heat the device.
            long delay = peerFailStreak > 3 ? 45000 : 15000;
            h.postDelayed(this, delay);
        }
    };
    private final Runnable authWatch = new Runnable() {
        @Override public void run() {
            if (authWatchLeft <= 0) return;
            authWatchLeft--;
            new Thread(() -> {
                try {
                    org.json.JSONObject a = NanobotCli.authPoll(NanobotService.this);
                    if (a != null && a.optBoolean("signed_in", false)) {
                        Log.i(TAG, "auth watch CLI: signed_in");
                        authWatchLeft = 0;
                        try {
                            getSharedPreferences("grok_device_auth", MODE_PRIVATE)
                                .edit().clear().apply();
                        } catch (Exception ignored) {}
                        return;
                    }
                    if (a != null && !a.optBoolean("login_pending", false)
                            && !a.optBoolean("signed_in", false)) {
                        authWatchLeft = Math.min(authWatchLeft, 3);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "auth watch: " + t.getMessage());
                } finally {
                    if (authWatchLeft > 0) bg.postDelayed(this, 5000);
                }
            }, "nanobot-auth-watch").start();
        }
    };

    /** Ask service to poll /api/auth while user is in browser (~10 min). */
    public static void requestAuthWatch(android.content.Context c) {
        startAction(c, ACTION_AUTH_WATCH);
    }

    /** Cube / product: bring up 127.0.0.1:8787 (not LAN). */
    public static void requestEnsureLoopback(android.content.Context c) {
        startAction(c, ACTION_ENSURE_LOOPBACK);
    }

    private static void startAction(android.content.Context c, String action) {
        try {
            Intent i = new Intent(c, NanobotService.class);
            i.setAction(action);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startAction " + action + ": " + t.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                    CH, "Titan Nanobot", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("On-device agent peer");
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(ch);
            }
        } catch (Throwable t) {
            Log.e(TAG, "channel: " + t.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            PrivacyPrefs.setServiceEnabled(this, false);
            h.removeCallbacks(watchdog);
            bg.removeCallbacks(authWatch);
            authWatchLeft = 0;
            stopBinary();
            NanobotRuntime.stopPeer(this);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean authOnly = intent != null && ACTION_AUTH_WATCH.equals(intent.getAction());
        boolean ensurePeer = intent != null
            && ACTION_ENSURE_LOOPBACK.equals(intent.getAction());
        // Cube may request peer even if user toggled "service" off briefly —
        // still require service pref for long-run; ensure forces one start.
        if (!PrivacyPrefs.serviceEnabled(this) && !authOnly && !ensurePeer) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            promoteForeground();
        } catch (Throwable t) {
            Log.e(TAG, "foreground failed (continuing): " + t.getMessage());
        }
        // Never sleep/spawn on main — ANR "Nanobot isn't responding" freezes Cube.
        scheduleEnsureLoopback(ensurePeer ? "ensure" : "start");
        if (authOnly) {
            authWatchLeft = 120; // ~10 min at 5s
            bg.removeCallbacks(authWatch);
            bg.post(authWatch);
            Log.i(TAG, "auth watch armed (CLI poll)");
        }
        h.removeCallbacks(watchdog);
        if (PrivacyPrefs.serviceEnabled(this) || ensurePeer) {
            h.postDelayed(watchdog, 10000);
        }
        return START_STICKY;
    }

    /** Off-main peer ensure (Cube Stability: no main-thread sleep). */
    private void scheduleEnsureLoopback(String reason) {
        if (peerEnsureRunning) return;
        long now = System.currentTimeMillis();
        // Cool-down after repeated spawn failures (port/SELinux).
        if (peerFailStreak >= 2 && (now - lastPeerFailMs) < 12000L) {
            return;
        }
        peerEnsureRunning = true;
        new Thread(() -> {
            try {
                ensureLoopbackPeer(reason);
            } finally {
                peerEnsureRunning = false;
            }
        }, "nanobot-peer-ensure").start();
    }

    private void promoteForeground() {
        Intent open = new Intent(this, MainActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        boolean lan = PrivacyPrefs.shareLan(this);
        String text = lan
            ? "Agent :" + NanobotRuntime.PORT + " (LAN share ON)"
            : "Agent 127.0.0.1:" + NanobotRuntime.PORT + " (Cube/Grok loopback)";

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CH)
            : new Notification.Builder(this);
        b.setContentTitle("Nanobot agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pi)
            .setOngoing(true);
        Notification n = b.build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(4701, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(4701, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE);
        } else {
            startForeground(4701, n);
        }
    }

    /**
     * Product peer: 127.0.0.1 only. Prefer existing shell peer (SHARED_HOME Grok).
     * MUST run off main thread only.
     */
    private void ensureLoopbackPeer(String reason) {
        synchronized (peerLock) {
            if (NanobotRuntime.isPortListening()) {
                if (peerOnSharedHome()) {
                    peerFailStreak = 0;
                    Log.i(TAG, "loopback peer already up SHARED (" + reason + ")");
                    return;
                }
                // 1.8.2: wrong-home peer masks Grok — re-kick SHARED_HOME.
                Log.w(TAG, "wrong-home peer on :8787 — heal SHARED (" + reason + ")");
                stopBinary();
                NanobotRuntime.stopPeer(this);
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                // fall through to start path
            } else {
                // Double-check after short settle (busy peer can false-negative once).
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                if (NanobotRuntime.isPortListening() && peerOnSharedHome()) {
                    peerFailStreak = 0;
                    Log.i(TAG, "loopback peer up after settle (" + reason + ")");
                    return;
                }
            }
            Log.w(TAG, "loopback peer down — ensure (" + reason + ")");
            // 1) Product init shell peer (shared Grok session) — may no-op for tip
            NanobotRuntime.requestInitPeerStart();
            for (int i = 0; i < 12 && !NanobotRuntime.isPortListening(); i++) {
                try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            }
            if (NanobotRuntime.isPortListening() && peerOnSharedHome()) {
                peerFailStreak = 0;
                Log.i(TAG, "loopback peer via init/shell SHARED");
                return;
            }
            if (NanobotRuntime.isPortListening() && !peerOnSharedHome()) {
                Log.w(TAG, "init/shell left wrong-home peer — force SHARED spawn");
                stopBinary();
                NanobotRuntime.stopPeer(this);
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }
            // 2) App-owned spawn — system/app binary only (never tip SELinux).
            // 1.8.2: always SHARED_HOME (peerHome), never empty app-private.
            stopBinary();
            try {
                String bin = NanobotCli.findBinary(this);
                if (bin == null) {
                    Log.w(TAG, "no executable nanobot binary for loopback peer");
                    notePeerFail();
                    return;
                }
                java.io.File home = NanobotCli.peerHome(this);
                java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
                cmd.add(bin);
                cmd.add("--home");
                cmd.add(home.getAbsolutePath());
                cmd.add("--port");
                cmd.add(String.valueOf(NanobotRuntime.PORT));
                if (PrivacyPrefs.shareLan(this)) {
                    cmd.add("--lan"); // opt-in only
                }
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.environment().put("NANOBOT_HOME", home.getAbsolutePath());
                pb.environment().put("HOME", home.getAbsolutePath());
                pb.environment().put("NANOBOT_SHARED_SECRETS", "1");
                java.io.File tmp = new java.io.File(getCacheDir(), "ng_peer_tmp");
                //noinspection ResultOfMethodCallIgnored
                tmp.mkdirs();
                pb.environment().put("TMPDIR", tmp.getAbsolutePath());
                java.io.File log = new java.io.File(getFilesDir(), "peer_spawn.log");
                pb.redirectErrorStream(true);
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
                proc = pb.start();
                for (int i = 0; i < 20 && !NanobotRuntime.isPortListening(); i++) {
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                    // Early exit if child died
                    try {
                        int code = proc.exitValue();
                        Log.w(TAG, "peer child exit " + code + " bin=" + bin);
                        break;
                    } catch (IllegalThreadStateException stillAlive) { /* ok */ }
                }
                boolean up = NanobotRuntime.isPortListening();
                Log.i(TAG, "loopback peer spawned home=" + home
                    + " bin=" + bin + " up=" + up
                    + " lan=" + PrivacyPrefs.shareLan(this)
                    + " shared_ok=" + peerOnSharedHome());
                if (up) peerFailStreak = 0;
                else notePeerFail();
            } catch (Throwable t) {
                Log.e(TAG, "ensureLoopbackPeer: " + t.getMessage());
                notePeerFail();
            }
        }
    }

    /** True if :8787 peer reports workdir under SHARED_HOME (or no info yet). */
    private boolean peerOnSharedHome() {
        if (!NanobotRuntime.isPeerHttpAlive()) return false;
        try {
            org.json.JSONObject info = new PeerClient(this).info();
            if (info == null) return false;
            String wd = info.optString("workdir", "");
            if (wd.isEmpty()) {
                org.json.JSONObject a = new PeerClient(this).authStatus();
                wd = a != null ? a.optString("workdir", "") : "";
            }
            if (wd.isEmpty()) return false;
            return wd.equals(NanobotRuntime.SHARED_HOME)
                || wd.startsWith(NanobotRuntime.SHARED_HOME + "/");
        } catch (Throwable t) {
            Log.w(TAG, "peerOnSharedHome: " + t.getMessage());
            // If we cannot read info, keep peer only when no shared session
            // (avoid thrash). When shared Grok exists, treat as wrong until proven.
            return !NanobotCli.sharedHasSession();
        }
    }

    private void notePeerFail() {
        lastPeerFailMs = System.currentTimeMillis();
        peerFailStreak = Math.min(peerFailStreak + 1, 20);
    }

    private void startBinary() {
        scheduleEnsureLoopback("startBinary");
    }

    private void stopBinary() {
        if (proc != null) {
            try { proc.destroy(); } catch (Throwable ignored) {}
            proc = null;
        }
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(watchdog);
        bg.removeCallbacks(authWatch);
        authWatchLeft = 0;
        stopBinary();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
