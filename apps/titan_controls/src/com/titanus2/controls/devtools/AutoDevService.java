package com.titanus2.controls.devtools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import com.titanus2.controls.AgentBridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AUTO DEV MODE runtime: watch Screenshots, queue analyze, optional BlackCube peer ping.
 * Fail-closed when mode off.
 */
public class AutoDevService extends Service {
    private static final String TAG = "AutoDevSvc";
    private static final String CH = "titan2_auto_dev";
    private static final int NID = 0xA070;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private final AtomicBoolean analyzing = new AtomicBoolean(false);
    private ContentObserver shotObserver;
    private long lastShotMs;

    public static void ensure(Context c) {
        if (!AutoDevMode.isOn(c)) {
            stop(c);
            return;
        }
        Intent i = new Intent(c, AutoDevService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "start", e);
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, AutoDevService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        startForeground(NID, buildNotif("idle"));
        registerShotWatcher();
        AutoDevMode.writeStatus(this);
        Log.i(TAG, "onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!AutoDevMode.isOn(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NID, buildNotif(statusText()));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (shotObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(shotObserver);
            } catch (Exception ignored) {
            }
            shotObserver = null;
        }
        ex.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerShotWatcher() {
        if (shotObserver != null) return;
        shotObserver = new ContentObserver(h) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                onShotMaybe(uri);
            }

            @Override public void onChange(boolean selfChange) {
                onShotMaybe(null);
            }
        };
        try {
            getContentResolver().registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true, shotObserver);
        } catch (Exception e) {
            Log.w(TAG, "shot observer", e);
        }
    }

    private void onShotMaybe(Uri uri) {
        if (!AutoDevMode.isOn(this) || !AutoDevMode.analyzeShots(this)) return;
        long now = System.currentTimeMillis();
        if (now - lastShotMs < 1500L) return; // debounce MediaStore thrash
        lastShotMs = now;
        ex.execute(() -> analyzeLatestShot(uri));
    }

    private void analyzeLatestShot(Uri uri) {
        if (!analyzing.compareAndSet(false, true)) return;
        try {
            startForeground(NID, buildNotif("analyzing…"));
            // Plane one-shot for pad-agent / nanobot on-device
            AgentBridge.put(this, AutoDevMode.PLANE_ACTION,
                "analyze_shot " + System.currentTimeMillis());
            AutoDevMode.writeLine("/data/local/tmp/titan2_auto_dev_last_shot",
                uri != null ? uri.toString() : "mediastore");

            // Optional: BlackCube peer prompt (text-only tip; image path later)
            if (AutoDevMode.blackCubePeer(this) && AutoDevMode.isPaired(this)) {
                pingBlackCube("Titan Auto Dev · screenshot");
            }

            // Local analyze via atlas-screencap + tip nanobot if available
            runLocalAnalyzeHint();
            AutoDevMode.writeStatus(this);
            startForeground(NID, buildNotif(statusText()));
        } finally {
            analyzing.set(false);
        }
    }

    private void runLocalAnalyzeHint() {
        // Best-effort: touch status for agents; heavy vision is host/BlackCube MCP.
        AutoDevMode.writeLine("/data/local/tmp/titan2_auto_dev_analyze_log",
            "ts=" + System.currentTimeMillis()
                + " peer=" + (AutoDevMode.blackCubePeer(this) ? "1" : "0")
                + " note=queued");
        // Try on-device screencap tip (no UI thrash)
        try {
            File tip = new File("/data/local/tmp/atlas-screencap.sh");
            if (!tip.isFile()) {
                tip = new File("/system/bin/atlas-screencap.sh");
            }
            if (tip.isFile()) {
                Process p = new ProcessBuilder("sh", tip.getAbsolutePath(), "tip")
                    .redirectErrorStream(true).start();
                p.waitFor();
            }
        } catch (Exception e) {
            Log.w(TAG, "screencap tip", e);
        }
    }

    private void pingBlackCube(String prompt) {
        String base = AutoDevMode.peerUrl(this);
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        HttpURLConnection conn = null;
        try {
            // Health first
            URL health = new URL(base + "/peer/v1/health");
            conn = (HttpURLConnection) health.openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            conn = null;
            if (code < 200 || code >= 300) {
                AutoDevMode.writeLine("/data/local/tmp/titan2_auto_dev_peer_log",
                    "health_fail code=" + code);
                return;
            }
            // Soft prompt (may 404 if peer has no open chat API — status only)
            URL chat = new URL(base + "/api/chat");
            conn = (HttpURLConnection) chat.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] body = ("{\"prompt\":" + jsonQuote(prompt) + "}").getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int c2 = conn.getResponseCode();
            String resp = readStream(conn);
            AutoDevMode.writeLine("/data/local/tmp/titan2_auto_dev_peer_log",
                "chat code=" + c2 + " " + (resp != null && resp.length() > 120
                    ? resp.substring(0, 120) : resp));
        } catch (Exception e) {
            AutoDevMode.writeLine("/data/local/tmp/titan2_auto_dev_peer_log",
                "err " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String jsonQuote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String readStream(HttpURLConnection conn) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream(),
                StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null && sb.length() < 400) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(line.trim());
            }
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String statusText() {
        return "Auto Dev "
            + (AutoDevMode.isOn(this) ? "on" : "off")
            + (AutoDevMode.analyzeShots(this) ? " · shots" : "")
            + (AutoDevMode.blackCubePeer(this) ? " · peer" : "");
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CH, "Auto Dev",
            NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Auto Dev runtime");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotif(String text) {
        Intent open = new Intent(this, DevToolsActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
            Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CH);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("Auto Dev")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true);
        return b.build();
    }
}
