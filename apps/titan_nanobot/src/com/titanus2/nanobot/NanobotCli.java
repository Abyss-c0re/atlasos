package com.titanus2.nanobot;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Product path: Grok auth + chat.
 * <p>1.11 residual: findBinary delegates to {@link NanobotRuntime#findBinary}
 * (system-first CLI list left chat/auth on stale module).
 * <p>1.7.7 residual: priv_app cannot exec tip {@code /data/local/tmp/nanobot}
 * (shell_data_file). When loopback peer is up (shell/init), auth uses HTTP
 * peer on SHARED_HOME — same sealed session as Cube. CLI uses system/bin or
 * app-extracted binary only (never tip).
 * <p>1.8.2 residual: {@link #productHome} fell back to app-private when shared
 * write probe failed; {@link NanobotService} then bound :8787 on empty home and
 * UI trusted peer {@code signed_in=false} while SHARED_HOME still held Grok.
 * Shared session is SoT; peer spawn always uses {@link #peerHome}.
 */
public final class NanobotCli {
    private static final String TAG = "NanobotCli";
    /** App-private home — SELinux-writable by priv_app when shared is blocked. */
    public static final String APP_HOME_NAME = "nanobot_home";

    private NanobotCli() {}

    /**
     * Product Grok home: prefer SHARED_HOME (peer + shell session) when
     * writable <b>or</b> a sealed session already lives there. App-private is
     * last resort only (no shared session + shared not writable).
     */
    public static File productHome(Context c) {
        return homeDir(c);
    }

    /**
     * Loopback peer home — always SHARED_HOME when possible.
     * Never start a product peer on empty app-private home.
     */
    public static File peerHome(Context c) {
        File shared = new File(NanobotRuntime.SHARED_HOME);
        try {
            if (!shared.exists()) {
                //noinspection ResultOfMethodCallIgnored
                shared.mkdirs();
            }
        } catch (Exception ignored) {}
        if (sharedCanUse(shared) || sharedHasSession(shared)) {
            if (sharedCanUse(shared)) ensurePeerToken(shared);
            return shared;
        }
        // Last resort: private home with migrate (shared unusable + no session).
        return homeDir(c);
    }

    public static File homeDir(Context c) {
        File shared = new File(NanobotRuntime.SHARED_HOME);
        // 1.8.2: sealed session on SHARED is SoT even if app write probe fails
        // (shell-owned lab home). Auth/CLI still target that path.
        if (sharedCanUse(shared) || sharedHasSession(shared)) {
            if (sharedCanUse(shared)) ensurePeerToken(shared);
            return shared;
        }
        File home = new File(c.getFilesDir(), APP_HOME_NAME);
        //noinspection ResultOfMethodCallIgnored
        home.mkdirs();
        // Migrate BEFORE minting a new peer_token — session is sealed under the
        // token that created it. Token-first broke "approved but never signed in".
        maybeMigrateSession(c, home);
        ensurePeerToken(home);
        return home;
    }

    /** True if lab shared home already has a sealed Grok session. */
    public static boolean sharedHasSession() {
        return sharedHasSession(new File(NanobotRuntime.SHARED_HOME));
    }

    private static boolean sharedHasSession(File shared) {
        try {
            File sess = new File(shared, "session");
            File tok = new File(shared, "peer_token");
            return sess.isFile() && sess.length() > 32
                && tok.isFile() && tok.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** True if shared home is usable for session (world-rw lab path). */
    private static boolean sharedCanUse(File shared) {
        try {
            if (!shared.exists()) {
                //noinspection ResultOfMethodCallIgnored
                shared.mkdirs();
            }
            File probe = new File(shared, ".app_w");
            try (FileOutputStream o = new FileOutputStream(probe, true)) {
                o.write('a');
            }
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "shared home unusable for app: " + e.getMessage());
            return false;
        }
    }

    /**
     * Trust peer /api/auth only when workdir is SHARED_HOME, or peer is signed
     * in (migrated private home). Wrong-home empty peer must not mask CLI.
     */
    static boolean peerAuthTrusted(JSONObject a) {
        if (a == null) return false;
        String wd = a.optString("workdir", "");
        if (wd.isEmpty()) wd = a.optString("home", "");
        if (wd.equals(NanobotRuntime.SHARED_HOME)
                || wd.startsWith(NanobotRuntime.SHARED_HOME + "/")) {
            return true;
        }
        // Signed-in on any home is authoritative (private migrate OK).
        return a.optBoolean("signed_in", false);
    }

    public static String findBinary(Context c) {
        return NanobotRuntime.findBinary(c);
    }

    /**
     * Agent usable: executable binary (system/app) and/or loopback peer up.
     * Tip-only without peer is NOT available (SELinux cannot exec tip).
     */
    public static boolean available(Context c) {
        if (NanobotRuntime.isPortListening()) return true;
        return findBinary(c) != null;
    }

    /**
     * Auth status: prefer loopback peer when it is on SHARED_HOME (or signed
     * in). 1.8.2: wrong-home peer with signed_in=false falls through to CLI.
     */
    public static JSONObject authStatus(Context c) throws Exception {
        // 1.7.13: only use peer when HTTP health works (not TCP-wedge).
        if (NanobotRuntime.isPeerHttpAlive()) {
            try {
                JSONObject a = new PeerClient(c).authStatus();
                if (peerAuthTrusted(a)) return a;
                Log.w(TAG, "peer authStatus untrusted workdir="
                    + a.optString("workdir", "?")
                    + " signed_in=" + a.optBoolean("signed_in", false)
                    + " — CLI SHARED_HOME");
            } catch (Exception e) {
                Log.w(TAG, "peer authStatus: " + e.getMessage());
            }
        }
        return runJson(c, 20000, "--auth-status");
    }

    public static JSONObject authStart(Context c, boolean force) throws Exception {
        if (NanobotRuntime.isPeerHttpAlive()) {
            try {
                JSONObject a = new PeerClient(c).authStart(force);
                // Accept peer result only on SHARED (or already signed / pending).
                if (peerAuthTrusted(a)
                        || a.optBoolean("login_pending", false)
                        || a.optBoolean("signed_in", false)) {
                    return a;
                }
                Log.w(TAG, "peer authStart untrusted workdir="
                    + a.optString("workdir", "?") + " — CLI");
            } catch (Exception e) {
                Log.w(TAG, "peer authStart: " + e.getMessage());
            }
        }
        if (force) return runJson(c, 60000, "--auth-start", "--force");
        return runJson(c, 60000, "--auth-start");
    }

    public static JSONObject authPoll(Context c) throws Exception {
        // Peer advances device-login on every /api/auth GET.
        // 1.7.13: prefer --auth-status after peer fail so sealed session is not missed.
        if (NanobotRuntime.isPeerHttpAlive()) {
            try {
                JSONObject a = new PeerClient(c).authStatus();
                if (peerAuthTrusted(a) || a.optBoolean("login_pending", false)) {
                    return a;
                }
                Log.w(TAG, "peer authPoll untrusted — CLI");
            } catch (Exception e) {
                Log.w(TAG, "peer authPoll: " + e.getMessage());
            }
        }
        try {
            JSONObject st = runJson(c, 20000, "--auth-status");
            if (st != null && st.optBoolean("signed_in", false)) return st;
        } catch (Exception e) {
            Log.w(TAG, "cli auth-status: " + e.getMessage());
        }
        return runJson(c, 45000, "--auth-poll");
    }

    /** Persist Grok cloud backend for subsequent CLI invocations. */
    public static void setRemoteGrok(Context c, String model) throws Exception {
        File home = homeDir(c);
        String m = (model == null || model.isEmpty()) ? "grok-4.5" : model;
        writeEnv(home,
            "NANOBOT_BASE_URL=https://cli-chat-proxy.grok.com/v1\n"
                + "NANOBOT_MODEL=" + m + "\n");
    }

    /** Point CLI agent at local llama (OpenAI-compatible). No browser. */
    public static void setLocalBackend(Context c, String baseUrl, String model) throws Exception {
        File home = homeDir(c);
        String base = (baseUrl == null || baseUrl.isEmpty())
            ? LlamaRuntime.baseUrl() : baseUrl;
        String m = (model == null || model.isEmpty()) ? "local" : model;
        writeEnv(home,
            "NANOBOT_BASE_URL=" + base + "\n"
                + "NANOBOT_MODEL=" + m + "\n");
    }

    /** 1.7.11: CLI process for cancelActiveChat (peer path uses PeerClient). */
    private static volatile Process sActiveChatProc;

    /**
     * Abort peer HTTP stream + CLI chat process (1.7.11 after 1.7.10 UI-only unstick).
     */
    public static void cancelActiveChat() {
        PeerClient.cancelActiveChat();
        Process p = sActiveChatProc;
        sActiveChatProc = null;
        if (p != null) {
            try { p.destroyForcibly(); } catch (Exception ignored) {}
        }
    }

    /**
     * One-shot chat via CLI. Streams stdout chunks to listener (best-effort).
     * Does not open a peer port.
     */
    public static void chat(Context c, String prompt, PeerClient.StreamListener listener)
            throws Exception {
        if (listener == null) return;
        // Prefer healthy loopback peer — same Grok session as auth/Cube; avoids
        // SELinux exec of tip binary. TCP-only is not enough (1.7.13 wedge).
        if (NanobotRuntime.isPeerHttpAlive()) {
            try {
                new PeerClient(c).chatStream(prompt, listener);
                return;
            } catch (Exception e) {
                Log.w(TAG, "peer chat: " + e.getMessage());
                // fall through to CLI if we have a runnable binary
            }
        }
        String bin = findBinary(c);
        if (bin == null) {
            listener.onError(new Exception(
                "Agent offline (no loopback peer, no executable binary)"));
            return;
        }
        File home = homeDir(c);
        List<String> cmd = new ArrayList<>();
        cmd.add(bin);
        cmd.add("--home");
        cmd.add(home.getAbsolutePath());
        cmd.add("-p");
        cmd.add(prompt != null ? prompt : "");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        env.put("NANOBOT_HOME", home.getAbsolutePath());
        env.put("HOME", home.getAbsolutePath());
        env.put("NANOBOT_SHARED_SECRETS", "1");
        //noinspection ResultOfMethodCallIgnored
        new File(c.getCacheDir(), "ng_tmp").mkdirs();
        env.put("TMPDIR", new File(c.getCacheDir(), "ng_tmp").getAbsolutePath());
        applySslEnv(env, home);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        sActiveChatProc = p;
        StringBuilder full = new StringBuilder();
        try {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[512];
                int n;
                while ((n = br.read(buf)) > 0) {
                    if (Thread.currentThread().isInterrupted() || sActiveChatProc != p) {
                        throw new PeerClient.ChatCancelledException();
                    }
                    String chunk = new String(buf, 0, n);
                    full.append(chunk);
                    listener.onDelta(chunk);
                }
            }
            // drain stderr (limits/backend lines) without treating as reply
            try (BufferedReader er = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = er.readLine()) != null) Log.i(TAG, "cli: " + line);
            }
            // 1.7.11: 3m max CLI chat (was 10m) — cancelActiveChat destroys earlier.
            boolean finished = p.waitFor(3, TimeUnit.MINUTES);
            if (!finished) {
                p.destroyForcibly();
                listener.onError(new Exception("CLI chat timeout"));
                return;
            }
            if (sActiveChatProc != p) {
                listener.onError(new PeerClient.ChatCancelledException());
                return;
            }
            int code = p.exitValue();
            String out = full.toString().trim();
            if (code != 0 && out.isEmpty()) {
                listener.onError(new Exception("nanobot exit " + code
                    + " (need --login or local backend?)"));
                return;
            }
            if (out.toLowerCase().contains("no grok session")
                    || out.toLowerCase().contains("need connect")) {
                listener.onError(new PeerClient.NeedLoginException(out));
                return;
            }
            listener.onDone(out);
        } catch (PeerClient.ChatCancelledException e) {
            try { p.destroyForcibly(); } catch (Exception ignored) {}
            listener.onError(e);
        } finally {
            if (sActiveChatProc == p) sActiveChatProc = null;
        }
    }

    public static String shell(Context c, String command) throws Exception {
        // CLI shell: nanobot -p '@! cmd' — no HTTP
        String bin = findBinary(c);
        if (bin == null) throw new Exception("nanobot binary missing");
        File home = homeDir(c);
        ProcessBuilder pb = new ProcessBuilder(
            bin, "--home", home.getAbsolutePath(),
            "--no-stream", "-p", "@! " + (command != null ? command : "true"));
        pb.environment().put("NANOBOT_HOME", home.getAbsolutePath());
        pb.environment().put("HOME", home.getAbsolutePath());
        applySslEnv(pb.environment(), home);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        p.waitFor(120, TimeUnit.SECONDS);
        return sb.toString();
    }

    /**
     * GSI: /system/etc/security/cacerts often missing (certs in apex).
     * curl (60) unable to get local issuer without this.
     */
    static void applySslEnv(Map<String, String> env, File home) {
        if (env == null) return;
        String pem = null;
        for (String p : new String[] {
                "/data/local/ssl/cacert.pem",
                "/data/local/tmp/cacert.pem",
                home != null ? new File(home, "cacert.pem").getAbsolutePath() : null,
                "/data/user/0/com.titanus2.atlas/files/cacert.pem",
        }) {
            if (p == null) continue;
            File f = new File(p);
            if (f.isFile() && f.length() > 50_000L) {
                pem = f.getAbsolutePath();
                break;
            }
        }
        if (pem != null) {
            env.put("SSL_CERT_FILE", pem);
            env.put("CURL_CA_BUNDLE", pem);
            env.put("REQUESTS_CA_BUNDLE", pem);
        }
        File apex = new File("/apex/com.android.conscrypt/cacerts");
        if (apex.isDirectory()) {
            env.put("SSL_CERT_DIR", apex.getAbsolutePath());
            env.put("CURL_CA_PATH", apex.getAbsolutePath());
        }
    }

    // --- process helpers ---

    private static JSONObject runJson(Context c, long timeoutMs, String... args)
            throws Exception {
        String raw = runCapture(c, timeoutMs, args);
        String json = extractJsonObject(raw);
        if (json == null) {
            throw new Exception("CLI no JSON: "
                + (raw.length() > 200 ? raw.substring(0, 200) : raw));
        }
        return new JSONObject(json);
    }

    private static String runCapture(Context c, long timeoutMs, String... args)
            throws Exception {
        String bin = findBinary(c);
        if (bin == null) throw new Exception("nanobot binary missing on ROM");
        File home = homeDir(c);
        List<String> cmd = new ArrayList<>();
        cmd.add(bin);
        cmd.add("--home");
        cmd.add(home.getAbsolutePath());
        for (String a : args) if (a != null) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("NANOBOT_HOME", home.getAbsolutePath());
        pb.environment().put("HOME", home.getAbsolutePath());
        // App-owned home is single-UID; keep seal files readable for next CLI.
        pb.environment().put("NANOBOT_SHARED_SECRETS", "1");
        File tmp = new File(c.getCacheDir(), "ng_tmp");
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();
        pb.environment().put("TMPDIR", tmp.getAbsolutePath());
        // GSI: system curl has empty CApath without apex bind — force PEM/dir
        applySslEnv(pb.environment(), home);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        boolean ok = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!ok) {
            p.destroyForcibly();
            throw new Exception("CLI timeout: " + args[0]);
        }
        return sb.toString();
    }

    /** Last '{'…'}' object in mixed stderr/stdout. */
    private static String extractJsonObject(String raw) {
        if (raw == null) return null;
        int last = raw.lastIndexOf('{');
        if (last < 0) return null;
        int depth = 0;
        for (int i = last; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return raw.substring(last, i + 1);
            }
        }
        return null;
    }

    private static void writeEnv(File home, String body) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        home.mkdirs();
        File env = new File(home, "env");
        try (FileOutputStream out = new FileOutputStream(env)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void ensurePeerToken(File home) {
        File pt = new File(home, "peer_token");
        if (pt.isFile()) return;
        try {
            byte[] rnd = new byte[16];
            new java.security.SecureRandom().nextBytes(rnd);
            StringBuilder hx = new StringBuilder();
            for (byte b : rnd) hx.append(String.format("%02x", b));
            try (FileOutputStream o = new FileOutputStream(pt)) {
                o.write(("token=" + hx + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "peer_token: " + e.getMessage());
        }
    }

    /**
     * Best-effort copy sealed session + sealing key from shell lab home.
     * Must copy peer_token with session or decrypt will fail forever.
     * <p>1.8.2: SHARED is SoT — if app minted an orphan peer_token without a
     * session, overwrite token+session from shared (previous logic blocked
     * migrate forever and left UI "not signed in").
     */
    private static void maybeMigrateSession(Context c, File appHome) {
        File dest = new File(appHome, "session");
        if (dest.isFile() && dest.length() > 32) return;
        File src = new File(NanobotRuntime.SHARED_HOME, "session");
        File tokSrc = new File(NanobotRuntime.SHARED_HOME, "peer_token");
        if (!src.isFile() || !tokSrc.isFile()) return;
        File tokDst = new File(appHome, "peer_token");
        try {
            // Token first so any concurrent CLI seals under the migrated key.
            copyFile(tokSrc, tokDst);
            copyFile(src, dest);
            File dlSrc = new File(NanobotRuntime.SHARED_HOME, "device_login");
            File dlDst = new File(appHome, "device_login");
            if (dlSrc.isFile() && (!dlDst.isFile() || dlDst.length() == 0)) {
                copyFile(dlSrc, dlDst);
            }
            Log.i(TAG, "migrated session+token → app home (shared SoT)");
        } catch (Exception e) {
            Log.w(TAG, "session migrate: " + e.getMessage());
        }
    }

    private static void copyFile(File from, File to) throws Exception {
        byte[] b = new byte[(int) Math.min(from.length(), 65536)];
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            int n = in.read(b);
            if (n > 0) out.write(b, 0, n);
        }
    }

    /** Absolute path of app CLI home (for debug UI). */
    public static String homePath(Context c) {
        return homeDir(c).getAbsolutePath();
    }
}
