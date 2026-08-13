package com.titanus2.cubecontact;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Loopback / lab nanobot peer HTTP helpers.
 *
 * <p>1.63 residual: {@code getResponseCode} + {@code disconnect} without draining
 * the body left peer workers in CLOSE-WAIT (concurrent fork max 24). After ~20
 * stuck workers LAW/chat stalls. Always drain then disconnect.
 *
 * <p>1.64 residual (after 1.63 + Nanobot 1.7.2): keep-alive reuse + POST write
 * failure before {@link #finish} left half-open clients while Magisk 1.13 only
 * re-kicked at land/boot. Force {@code Connection: close} and wrap POST write
 * so disconnect always runs.
 *
 * <p>1.78 residual: 1.77 gated promote/GL/mesh/pull via {@link CubeStability#allowPeerHttp}
 * at call sites, but {@link NanobotBridge} chat/health + {@link StateMatrix}
 * getJson/postJson + any unguarded path still opened loopback HTTP under
 * {@link CubeStability#isCubeHeat} (load≥8 or thermal SEVERE) → cool-lab reheat
 * residual. Bind Application Context and refuse open under cube heat (file SoT;
 * cool path full peer). Fail-open (allow) only when context unbound.
 *
 * <p>1.93 residual (BUG-42): lab BrainCube {@code peer_http=lab_ops_only} rejects
 * unauthenticated {@code /api/braincube} with {@code peer_token_invalid} → Neural
 * eyes starve while host is live. Attach {@code X-Nanobot-Peer-Token} from tip
 * file {@code /data/local/tmp/cube_peer_token} (or prefs {@code peer_token}).
 */
public final class PeerHttp {
    private static volatile Context sAppCtx;
    private static volatile String sCachedToken;
    private static volatile long sTokenReadMs;
    private static final long TOKEN_TTL_MS = 30_000L;
    private static final String TOKEN_FILE = "/data/local/tmp/cube_peer_token";

    private PeerHttp() {}

    /**
     * Read lab peer token (BUG-42). File first, then prefs. Strips optional
     * {@code token=} prefix. Empty string if unset (caller still opens HTTP).
     */
    public static String peerToken() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (sCachedToken != null && now - sTokenReadMs < TOKEN_TTL_MS) {
            return sCachedToken;
        }
        sTokenReadMs = now;
        String tok = null;
        try {
            java.io.File f = new java.io.File(TOKEN_FILE);
            if (f.isFile() && f.length() > 0 && f.length() < 512) {
                byte[] b = new byte[(int) f.length()];
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    int n = in.read(b);
                    if (n > 0) tok = new String(b, 0, n, StandardCharsets.UTF_8).trim();
                }
            }
        } catch (Exception ignored) {}
        if ((tok == null || tok.isEmpty()) && sAppCtx != null) {
            try {
                tok = sAppCtx.getSharedPreferences("cube_contact", Context.MODE_PRIVATE)
                    .getString("peer_token", null);
                if (tok != null) tok = tok.trim();
            } catch (Exception ignored) {}
        }
        if (tok != null && tok.startsWith("token=")) {
            tok = tok.substring(6).trim();
        }
        if (tok == null) tok = "";
        sCachedToken = tok;
        return tok;
    }

    private static void attachPeerToken(HttpURLConnection c) {
        if (c == null) return;
        try {
            String tok = peerToken();
            if (tok != null && !tok.isEmpty()) {
                c.setRequestProperty("X-Nanobot-Peer-Token", tok);
            }
        } catch (Exception ignored) {}
    }

    /** Bind Application Context for cube-heat gate (idempotent). */
    public static void bindAppContext(Context c) {
        if (c == null) return;
        try {
            Context app = c.getApplicationContext();
            if (app != null) sAppCtx = app;
            else sAppCtx = c;
        } catch (Exception ignored) {
            sAppCtx = c;
        }
    }

    /**
     * True when peer HTTP must not open (cube heat). Fail-open if no bound
     * context (early static path before Application onCreate).
     */
    public static boolean isParkedForHeat() {
        Context c = sAppCtx;
        if (c == null) return false;
        try {
            return !CubeStability.allowPeerHttp(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Thrown when open refused under cube heat (callers treat as offline). */
    public static final class PeerHeatParkedException extends Exception {
        public PeerHeatParkedException() {
            super("peer HTTP parked (cube heat)");
        }
    }

    private static HttpURLConnection open(String url, String method, int connectMs, int readMs)
            throws Exception {
        return open(url, method, connectMs, readMs, false);
    }

    /**
     * @param userChat true → never refuse for cube cool/load heat (human typed message).
     */
    private static HttpURLConnection open(String url, String method, int connectMs, int readMs,
                                          boolean userChat)
            throws Exception {
        // 1.78: defense-in-depth — no Socket under cube heat for background pulls.
        // 1.85: user chat bypasses heat park.
        if (!userChat && isParkedForHeat()) {
            throw new PeerHeatParkedException();
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (method != null && !method.isEmpty()) {
            c.setRequestMethod(method);
        }
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        // 1.64: no keep-alive pool for loopback peer — health polls thrash workers.
        c.setRequestProperty("Connection", "close");
        c.setUseCaches(false);
        // 1.93 BUG-42: lab BrainCube requires peer token on LAN.
        attachPeerToken(c);
        return c;
    }

    /** GET JSON body (drains fully). Null on empty stream. */
    public static String getBody(String url, int connectMs, int readMs) throws Exception {
        HttpURLConnection c = open(url, "GET", connectMs, readMs);
        return finish(c);
    }

    /** GET for user chat health — never parked by cube cool. */
    public static String getBodyChat(String url, int connectMs, int readMs) throws Exception {
        HttpURLConnection c = open(url, "GET", connectMs, readMs, true);
        return finish(c);
    }

    /** POST JSON body (drains fully). Null on empty stream. */
    public static String postBody(String url, String jsonBody, int connectMs, int readMs)
            throws Exception {
        return postBodyInner(url, jsonBody, connectMs, readMs, false);
    }

    /** User-typed chat POST — never refused for cube cool/load heat. */
    public static String postBodyChat(String url, String jsonBody, int connectMs, int readMs)
            throws Exception {
        return postBodyInner(url, jsonBody, connectMs, readMs, true);
    }

    /**
     * BrainCube eyes (export/live lattice) — never heat-park.
     * Rear truth cube must see real matrix even under cool-lab load≥8.
     * Chat-class intent: show state, not thrash promote/dim paths.
     */
    public static String postBodyEyes(String url, String jsonBody, int connectMs, int readMs)
            throws Exception {
        return postBodyInner(url, jsonBody, connectMs, readMs, true);
    }

    public static String getBodyEyes(String url, int connectMs, int readMs) throws Exception {
        return getBodyChat(url, connectMs, readMs);
    }

    private static String postBodyInner(String url, String jsonBody, int connectMs, int readMs,
                                        boolean userChat) throws Exception {
        HttpURLConnection c = open(url, "POST", connectMs, readMs, userChat);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        byte[] raw = (jsonBody != null ? jsonBody : "").getBytes(StandardCharsets.UTF_8);
        try {
            try (OutputStream os = c.getOutputStream()) {
                os.write(raw);
            }
            return finish(c);
        } catch (Exception e) {
            // 1.64: write fail must still disconnect (finish never ran).
            try {
                c.disconnect();
            } catch (Exception ignored) {}
            throw e;
        }
    }

    /**
     * Drain response (or error) body fully, then disconnect.
     * Returns body text or null when no stream.
     */
    public static String finish(HttpURLConnection c) throws Exception {
        InputStream in = null;
        try {
            int code = c.getResponseCode();
            in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) {
                // 1.63: no stream still disconnect — half-close residual.
                return null;
            }
            return readAll(in);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {}
            }
            try {
                c.disconnect();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Status-only probe that still drains the body (health endpoints return JSON).
     * Returns HTTP code or -1 on hard failure.
     */
    public static int probeCode(String url, int connectMs, int readMs) {
        return probeCode(url, connectMs, readMs, false);
    }

    public static int probeCode(String url, int connectMs, int readMs, boolean userChat) {
        try {
            HttpURLConnection c = open(url, "GET", connectMs, readMs, userChat);
            int code = c.getResponseCode();
            // Drain even when caller only wants the code.
            InputStream in = null;
            try {
                in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in != null) {
                    byte[] buf = new byte[2048];
                    while (in.read(buf) >= 0) {
                        /* drain */
                    }
                }
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Exception ignored) {}
                }
                try {
                    c.disconnect();
                } catch (Exception ignored) {}
            }
            return code;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String readAll(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }
}
