package com.titanus2.nanobot;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Locate binary, start/stop peer, read peer token. Native app — no web UI. */
public final class NanobotRuntime {
    private static final String TAG = "NanobotRuntime";
    public static final int PORT = 8787;
    /** Shared lab home (shell + app) when writable — keeps Grok session stable. */
    public static final String SHARED_HOME = "/data/local/tmp/nanobot_home";

    private NanobotRuntime() {}

    /**
     * Always prefer shared lab home so cloud session + peer_token stay stable.
     * Falling back to app-private home breaks signed-in cloud sessions (different workdir).
     */
    public static File homeDir(Context c) {
        File shared = new File(SHARED_HOME);
        try {
            if (!shared.exists()) //noinspection ResultOfMethodCallIgnored
                shared.mkdirs();
            File probe = new File(shared, ".w");
            try (FileOutputStream o = new FileOutputStream(probe)) {
                o.write(1);
            }
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
        } catch (Exception e) {
            Log.w(TAG, "shared home not fully writable: " + e.getMessage()
                + " — still using SHARED_HOME for session continuity");
        }
        return shared;
    }

    /** True if a peer is already up and reporting the shared workdir (or any workdir). */
    public static boolean peerUsesSharedHome() {
        // best-effort: if listening, assume attach-only; session is on SHARED_HOME
        return isPortListening();
    }

    /** Extract bundled arm64 nanobot (assets/nanobot.arm64) into app files — SELinux-executable. */
    public static File extractBundledBinary(Context c) {
        File out = new File(c.getFilesDir(), "nanobot");
        try {
            // refresh if missing or smaller than asset (rough update check)
            long assetLen = -1;
            try (java.io.InputStream in = c.getAssets().open("nanobot.arm64")) {
                // can't cheaply size; always extract if missing or older than 1 day stale flag
            } catch (Exception e) {
                return out.isFile() ? out : null;
            }
            File stamp = new File(c.getFilesDir(), "nanobot.arm64.stamp");
            boolean need = !out.isFile() || !stamp.isFile();
            if (!need) {
                // re-extract if stamp older than APK lastUpdateTime
                long upd = c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
                if (stamp.lastModified() < upd) need = true;
            }
            if (need) {
                try (java.io.InputStream in = c.getAssets().open("nanobot.arm64");
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
                //noinspection ResultOfMethodCallIgnored
                out.setExecutable(true, false);
                //noinspection ResultOfMethodCallIgnored
                stamp.createNewFile();
                stamp.setLastModified(System.currentTimeMillis());
                Log.i(TAG, "extracted bundled nanobot → " + out.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e(TAG, "extractBundledBinary: " + e.getMessage());
        }
        return out.isFile() ? out : null;
    }

    public static String findBinary(Context c) {
        // 1.10 residual: Magisk/ensure still tip-prefer shell peer when fresher
        // (tip_sz / host_sz). App path diverged:
        // 1.11 residual: CLI delegated here (system-first was wrong for chat/auth).
        // 1.12 residual (auth did not work): tip /data/local/tmp/nanobot is
        // shell_data_file — priv_app gets SELinux execute denials forever while
        // Java canExecute() still looks true. App must never exec tip.
        // Prefer system_file, then Magisk module, then app-private extract.
        // Shell peer still uses tip via adb/init; UI uses system or assets.
        String[] product = {
            "/system/bin/nanobot",
            "/data/adb/modules/titan2_nanobot/system/bin/nanobot",
            "/data/adb/titan2/nanobot",
        };
        String best = null;
        long bestSz = -1;
        for (String p : product) {
            File f = new File(p);
            if (!f.isFile() || !f.canExecute()) continue;
            long sz = f.length();
            if (sz > bestSz) {
                best = p;
                bestSz = sz;
            }
        }
        if (best != null) return best;
        File app = new File(c.getFilesDir(), "nanobot");
        if (app.isFile() && app.canExecute()) return app.getAbsolutePath();
        File bundled = extractBundledBinary(c);
        if (bundled != null && bundled.isFile() && bundled.canExecute()) {
            return bundled.getAbsolutePath();
        }
        return null;
    }

    /** Tip binary path for shell/init only — never for priv_app ProcessBuilder. */
    public static String tipBinaryForShell() {
        File tip = new File("/data/local/tmp/nanobot");
        if (tip.isFile() && tip.canExecute()) return tip.getAbsolutePath();
        return null;
    }

    /** Ask init to start shell-owned peer (product path). No user shell. */
    public static void requestInitPeerStart() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method set = sp.getMethod("set", String.class, String.class);
            set.invoke(null, "persist.titan2.nanobot", "1");
            // ctl.start when permitted (system/priv); no-op if denied
            try {
                set.invoke(null, "ctl.start", "titan2-nanobot");
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.w(TAG, "requestInitPeerStart: " + t.getMessage());
        }
    }

    public static String readPeerToken(Context c) {
        File[] files = {
            new File(SHARED_HOME, "peer_token"),
            new File(homeDir(c), "peer_token"),
            new File("/data/misc/titan2/nanobot/peer_token"),
            new File(c.getFilesDir(), "nanobot_home/peer_token"),
        };
        for (File f : files) {
            try {
                if (!f.isFile()) continue;
                String raw = slurp(f).trim();
                if (raw.startsWith("token=")) raw = raw.substring(6).trim();
                if (!raw.isEmpty()) return raw;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String slurp(File f) throws Exception {
        byte[] b = new byte[(int) Math.min(f.length(), 4096)];
        try (FileInputStream in = new FileInputStream(f)) {
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8) : "";
        }
    }

    /**
     * TCP accept only — can be true while peer is CLOSE_WAIT-wedged and HTTP hangs.
     * Prefer {@link #isPeerHttpAlive()} for auth/chat routing.
     */
    public static boolean isPortListening() {
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), 400);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 1.7.13: real loopback peer health. TCP-only false-positives left Grok auth
     * UI stuck on "Waiting for approval" while CLI session was already signed_in.
     */
    public static boolean isPeerHttpAlive() {
        if (!isPortListening()) return false;
        java.net.HttpURLConnection c = null;
        try {
            java.net.URL u = new java.net.URL("http://127.0.0.1:" + PORT + "/peer/v1/health");
            c = (java.net.HttpURLConnection) u.openConnection();
            c.setConnectTimeout(600);
            c.setReadTimeout(800);
            c.setUseCaches(false);
            c.setRequestProperty("Connection", "close");
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    /**
     * HTTP peer is opt-in (MCP / LAN share only). Product chat+auth uses
     * {@link NanobotCli} and must not call this for normal UX.
     * Prefer not starting peer unless PrivacyPrefs.shareLan.
     */
    public static Process startPeer(Context c) {
        if (!PrivacyPrefs.serviceEnabled(c)) {
            Log.i(TAG, "service disabled — not starting peer");
            return null;
        }
        if (!PrivacyPrefs.shareLan(c)) {
            Log.i(TAG, "share LAN off — CLI agent path, not starting HTTP peer");
            return null;
        }
        if (isPortListening()) {
            Log.i(TAG, "peer already listening on :" + PORT + " — attach only (keep session)");
            cacheToken(c);
            return null;
        }
        // Opt-in MCP: kick init if present, else spawn loopback-only peer.
        requestInitPeerStart();
        for (int i = 0; i < 15 && !isPortListening(); i++) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        if (isPortListening()) {
            cacheToken(c);
            return null;
        }
        String bin = findBinary(c);
        if (bin == null) {
            Log.e(TAG, "nanobot binary not found on ROM");
            return null;
        }
        if (bin.contains("/data/user/") || bin.contains("/data/data/")) {
            Log.w(TAG, "skip app-private binary spawn (SELinux); wait for product peer");
            return null;
        }
        File home = new File(SHARED_HOME);
        try {
            if (!home.exists()) //noinspection ResultOfMethodCallIgnored
                home.mkdirs();
        } catch (Exception ignored) {}
        File log = new File(c.getFilesDir(), "nanobot.out");
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(bin);
            // 1.8.2: explicit --home so binary never falls back to app private.
            cmd.add("--home");
            cmd.add(SHARED_HOME);
            cmd.add("--port");
            cmd.add(String.valueOf(PORT));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("NANOBOT_HOME", SHARED_HOME);
            pb.environment().put("HOME", SHARED_HOME);
            pb.environment().put("NANOBOT_SHARED_SECRETS", "1");
            try {
                File ngTmp = new File(c.getCacheDir(), "ng_tmp");
                //noinspection ResultOfMethodCallIgnored
                ngTmp.mkdirs();
                pb.environment().put("TMPDIR", ngTmp.getAbsolutePath());
            } catch (Exception ignored) {}
            NanobotCli.applySslEnv(pb.environment(), home);
            // best-effort: heal system CA path for child curl
            try {
                new ProcessBuilder("su", "0", "sh",
                    "/data/local/tmp/titan2-ssl-ca-heal.sh").start();
            } catch (Exception ignored) {}
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
            Process p = pb.start();
            Log.i(TAG, "started " + bin + " home=" + SHARED_HOME);
            cacheToken(c);
            return p;
        } catch (Exception e) {
            Log.e(TAG, "start: " + e.getMessage());
            return null;
        }
    }

    private static void cacheToken(Context c) {
        try {
            String tok = readPeerToken(c);
            if (tok != null) SecureStore.cachePeerToken(c, tok);
        } catch (Exception ignored) {}
    }

    /**
     * @deprecated Product path is {@link NanobotCli}; HTTP peer only for MCP share.
     */
    public static void ensureSharedPeer(Context c) {
        if (!PrivacyPrefs.shareLan(c)) {
            Log.i(TAG, "ensureSharedPeer skipped — CLI path (no HTTP)");
            return;
        }
        if (isPortListening()) {
            cacheToken(c);
            return;
        }
        startPeer(c);
        for (int i = 0; i < 20 && !isPortListening(); i++) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
    }

    /** User-facing agent-down text — never lab shell commands. */
    public static String userFacingOfflineHint() {
        File sys = new File("/system/bin/nanobot");
        if (!sys.isFile()) {
            return "Agent binary missing on this ROM. Flash a Nanobot product build.";
        }
        return "Use Providers → Sign in (CLI browser auth). No open agent port required.";
    }

    /**
     * Stop peer process (pid file + best-effort kill by port).
     * Does not clear provider session under NANOBOT_HOME.
     */
    public static void stopPeer(Context c) {
        File[] pidFiles = {
            new File(SHARED_HOME, "nanobot.pid"),
            new File(homeDir(c), "nanobot.pid"),
        };
        for (File pf : pidFiles) {
            try {
                if (!pf.isFile()) continue;
                String raw = slurp(pf).trim();
                if (raw.isEmpty()) continue;
                int pid = Integer.parseInt(raw.replaceAll("[^0-9]", ""));
                if (pid > 1) {
                    try {
                        new ProcessBuilder("kill", "-TERM", String.valueOf(pid)).start().waitFor();
                    } catch (Exception e) {
                        android.os.Process.killProcess(pid); // may fail cross-uid
                    }
                    Log.i(TAG, "signaled pid " + pid);
                }
            } catch (Exception e) {
                Log.w(TAG, "stopPeer pid: " + e.getMessage());
            }
        }
        // fuser / kill via sh if still up
        if (isPortListening()) {
            try {
                new ProcessBuilder("sh", "-c",
                    "pid=$(cat " + SHARED_HOME + "/nanobot.pid 2>/dev/null); "
                        + "[ -n \"$pid\" ] && kill -9 $pid 2>/dev/null; "
                        + "fuser -k " + PORT + "/tcp 2>/dev/null; true")
                    .start();
            } catch (Exception ignored) {}
        }
        // brief wait
        for (int i = 0; i < 10 && isPortListening(); i++) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        Log.i(TAG, "stopPeer done listening=" + isPortListening());
    }

    public static void setServiceRunning(Context c, boolean on) {
        PrivacyPrefs.setServiceEnabled(c, on);
        if (on) {
            Intent svc = new Intent(c, NanobotService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(svc);
            } else {
                c.startService(svc);
            }
            startPeer(c);
            AccessLog.record(c, "service_on", "Agent service enabled");
        } else {
            try {
                c.stopService(new Intent(c, NanobotService.class));
            } catch (Exception ignored) {}
            stopPeer(c);
            AccessLog.record(c, "service_off", "Agent service disabled");
        }
    }

    public static String lastLog(Context c, int maxLines) {
        File[] logs = {
            new File(c.getFilesDir(), "nanobot.out"),
            new File("/data/local/tmp/nanobot.out"),
        };
        for (File log : logs) {
            if (!log.isFile()) continue;
            try {
                BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(log), StandardCharsets.UTF_8));
                java.util.LinkedList<String> lines = new java.util.LinkedList<>();
                String line;
                while ((line = br.readLine()) != null) {
                    lines.add(line);
                    if (lines.size() > maxLines) lines.removeFirst();
                }
                br.close();
                StringBuilder sb = new StringBuilder();
                for (String l : lines) sb.append(l).append('\n');
                if (sb.length() > 0) return sb.toString();
            } catch (Exception ignored) {}
        }
        return "";
    }
}
