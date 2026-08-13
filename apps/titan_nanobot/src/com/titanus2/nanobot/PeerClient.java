package com.titanus2.nanobot;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP client for on-device nanobot peer (provider-agnostic).
 *
 * <p>1.7.2 residual (after CubeContact 1.63 PeerHttp): chat/health used
 * {@code getResponseCode} + body read without always {@code disconnect()} in
 * finally. Half-closed clients left peer concurrent-fork workers in CLOSE-WAIT
 * (fork max 24) even after Magisk 1.12 n_all≥20 re-kick — Nanobot UI polls
 * health/auth often. Always drain then disconnect (same SoT as PeerHttp).
 *
 * <p>1.7.3 residual (after 1.7.2 + Magisk 1.13): keep-alive reuse + POST write
 * failure before {@link #readJson} left half-open clients mid-session while
 * already-tip skip never re-checked pressure. Force {@code Connection: close}
 * and wrap POST write so disconnect always runs (CubeContact 1.64 PeerHttp SoT).
 *
 * <p>1.7.11 residual (after 1.7.10 chat-unstick): UI watchdog re-enabled Send
 * at 90s but left {@link #chatStream} on a 600s read timeout + half-open peer
 * worker (lab CLOSE-WAIT pile + load≈20). Active chat is cancelable: disconnect
 * the live connection and abort the read loop so peer workers exit.
 */
public final class PeerClient {
    private final String base;
    private final Context app;

    /** 1.7.11: one in-flight chat stream (loopback peer) — cancel from UI. */
    private static final java.util.concurrent.atomic.AtomicBoolean sChatCancel =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile HttpURLConnection sActiveChat;

    public PeerClient(Context c) {
        this.app = c.getApplicationContext();
        this.base = "http://127.0.0.1:" + NanobotRuntime.PORT;
    }

    /**
     * Abort any in-flight {@link #chatStream} (1.7.11). Safe from main thread;
     * disconnect wakes a blocked read so the peer worker can leave CLOSE-WAIT.
     */
    public static void cancelActiveChat() {
        sChatCancel.set(true);
        HttpURLConnection c = sActiveChat;
        sActiveChat = null;
        if (c != null) {
            try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    private static boolean chatCancelled() {
        return sChatCancel.get() || Thread.currentThread().isInterrupted();
    }

    public boolean healthy() {
        try {
            // Short path — never use 180s chat timeout for liveness.
            JSONObject j = getQuick("/peer/v1/health");
            return j != null && j.optBoolean("ok", false);
        } catch (Exception e) {
            return false;
        }
    }

    public JSONObject authStatus() throws Exception {
        return getQuick("/api/auth");
    }

    public JSONObject info() throws Exception {
        return getQuick("/peer/v1/info");
    }

    public JSONObject authStart(boolean force) throws Exception {
        JSONObject body = new JSONObject();
        body.put("force", force);
        // Device-code start hits network once; allow a bit longer than status.
        return postTimed("/api/auth/start", body, 8000, 45000);
    }

    /** backend: grok|cloud|local|llama|offline|openai_compatible */
    public JSONObject setBackend(String backend, String baseUrl, String model) throws Exception {
        JSONObject body = new JSONObject();
        if (backend != null) body.put("backend", backend);
        if (baseUrl != null && !baseUrl.isEmpty()) body.put("base_url", baseUrl);
        if (model != null && !model.isEmpty()) body.put("model", model);
        return post("/api/settings", body);
    }

    public JSONObject setModel(String model) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        return post("/api/settings", body);
    }

    /** Outbound MCP servers config (phone → remote MCP). */
    public JSONObject mcpServersList() throws Exception {
        return get("/api/mcp/servers");
    }

    public JSONObject mcpServersSave(JSONObject configWithServers) throws Exception {
        return post("/api/mcp/servers", configWithServers);
    }

    public JSONObject mcpServerProbe(String id, String url, String auth) throws Exception {
        JSONObject body = new JSONObject();
        if (id != null && !id.isEmpty()) body.put("id", id);
        if (url != null && !url.isEmpty()) body.put("url", url);
        if (auth != null && !auth.isEmpty()) body.put("auth", auth);
        return post("/api/mcp/probe", body);
    }

    /**
     * Cloud-only offline chips — never use when mode/backend is local.
     * These are technical model ids for the optional cloud provider, not product branding.
     */
    private static final String[] CLOUD_FALLBACK_MODELS = {
        "grok-4.5", "grok-4", "grok-3", "grok-3-mini"
    };

    public ModelsResult listModels() throws Exception {
        return listModels(false);
    }

    /**
     * @param localOnly when true: do not inject cloud model fallbacks; expect local/OpenAI list only.
     */
    public ModelsResult listModels(boolean localOnly) throws Exception {
        ModelsResult r = new ModelsResult();
        r.ids = new ArrayList<>();
        r.ok = false;
        r.baseUrl = "";
        r.current = "";
        r.error = "";
        r.localContext = localOnly;
        try {
            JSONObject j = get("/api/models");
            r.ok = j.optBoolean("ok", true);
            r.baseUrl = j.optString("base_url", "");
            r.current = j.optString("model", "");
            if (j.has("error") && !j.isNull("error")) {
                Object err = j.opt("error");
                if (err instanceof String) r.error = (String) err;
                else if (err != null) r.error = err.toString();
            }
            JSONArray arr = j.optJSONArray("models");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String id = null;
                    Object el = arr.opt(i);
                    if (el instanceof String) {
                        id = (String) el;
                    } else if (el instanceof JSONObject) {
                        id = ((JSONObject) el).optString("id", "");
                        if (id.isEmpty()) id = ((JSONObject) el).optString("model", "");
                    } else if (el != null) {
                        id = String.valueOf(el);
                    }
                    if (id != null && !id.isEmpty() && isRealModelId(id)) {
                        if (localOnly && looksLikeCloudOnlyModel(id)) continue;
                        r.ids.add(id);
                    }
                }
            }
        } catch (Exception e) {
            r.error = e.getMessage() != null ? e.getMessage() : "models fetch failed";
            r.ok = false;
        }
        if (r.current == null || r.current.isEmpty()) {
            try {
                JSONObject a = authStatus();
                r.current = a.optString("model", "");
                if (r.baseUrl == null || r.baseUrl.isEmpty())
                    r.baseUrl = a.optString("base_url", "");
            } catch (Exception ignored) {}
        }
        // Only inject cloud fallbacks when NOT in local context
        if (r.ids.isEmpty() && !localOnly) {
            for (String id : CLOUD_FALLBACK_MODELS) r.ids.add(id);
            if (r.error == null || r.error.isEmpty())
                r.error = "cloud offline list (peer empty)";
            else
                r.error = r.error + " · cloud offline chips";
        }
        if (localOnly && r.current != null && looksLikeCloudOnlyModel(r.current)) {
            r.current = "";
        }
        if (r.current != null && !r.current.isEmpty() && isRealModelId(r.current)
                && !r.ids.contains(r.current)
                && !(localOnly && looksLikeCloudOnlyModel(r.current))) {
            r.ids.add(0, r.current);
        }
        r.ok = !r.ids.isEmpty() || localOnly; // local empty is valid (no GGUF yet)
        return r;
    }

    /** Cloud vendor model ids — must not appear under Local mode chips. */
    public static boolean looksLikeCloudOnlyModel(String id) {
        if (id == null) return false;
        String low = id.trim().toLowerCase(java.util.Locale.US);
        return low.startsWith("grok")
            || low.startsWith("sxs-")
            || low.contains("claude-opus")
            || low.equals("grok-build");
    }

    /**
     * Grok cli-chat-proxy /v1/models embeds reasoning *effort* tiers
     * (high/medium/low) as "id" fields. Those are not model names —
     * selecting them yields Model not found.
     */
    public static boolean isRealModelId(String id) {
        if (id == null) return false;
        String s = id.trim();
        if (s.isEmpty()) return false;
        String low = s.toLowerCase(java.util.Locale.US);
        // Absolute GGUF paths are longer than short cloud ids
        if (low.endsWith(".gguf")) return s.length() <= 512;
        if (s.length() > 96) return false;
        if (low.equals("high") || low.equals("medium") || low.equals("low")
                || low.equals("xhigh") || low.equals("xlow") || low.equals("auto")
                || low.equals("none") || low.equals("default") || low.equals("list")
                || low.equals("model") || low.equals("object") || low.equals("local")) {
            return false;
        }
        return true;
    }

    public static boolean isEffortTierId(String id) {
        if (id == null) return false;
        String low = id.trim().toLowerCase(java.util.Locale.US);
        return low.equals("high") || low.equals("medium") || low.equals("low")
                || low.equals("xhigh") || low.equals("xlow");
    }

    /** Run a shell command on the peer (same denylist as agent). */
    public JSONObject shell(String command) throws Exception {
        JSONObject body = new JSONObject();
        body.put("command", command);
        return post("/peer/v1/shell", body);
    }

    /** Peer nanobot.log tail (JSON {log: "..."}). */
    public String peerLog() throws Exception {
        JSONObject j = get("/api/log");
        if (j == null) return "";
        String log = j.optString("log", null);
        if (log == null || log.isEmpty()) log = j.optString("output", j.toString());
        return log;
    }

    /** Read memory summary / recent history via peer shell (shared home). */
    public String memoryDump() throws Exception {
        String home = NanobotRuntime.SHARED_HOME;
        JSONObject r = shell(
            "echo '=== summary ==='; cat '" + home + "/memory/summary.txt' 2>/dev/null; "
                + "echo; echo '=== recent.jsonl (tail) ==='; "
                + "tail -n 40 '" + home + "/memory/recent.jsonl' 2>/dev/null; "
                + "echo; echo '=== env ==='; cat '" + home + "/env' 2>/dev/null; "
                + "echo; echo '=== shell policy ==='; "
                + "echo -n 'allow: '; cat '" + home + "/shell_allow' 2>/dev/null; "
                + "echo -n 'deny reboot: '; grep -c '^reboot$' '" + home + "/shell_denylist' 2>/dev/null || echo 0");
        if (r == null) return "(no response)";
        String out = r.optString("output", "");
        if (out.isEmpty()) out = r.toString();
        return out;
    }

    public String chat(String message) throws Exception {
        JSONObject body = new JSONObject();
        body.put("prompt", message);
        body.put("message", message);
        JSONObject j = post("/api/chat", body);
        if (j == null) throw new Exception("empty response");
        if (j.has("error")) {
            String err = j.optString("error", "error");
            if (j.optBoolean("need_login", false)
                    || err.toLowerCase().contains("activation")
                    || err.toLowerCase().contains("login")
                    || err.toLowerCase().contains("session")) {
                throw new NeedLoginException(err);
            }
            throw new Exception(err);
        }
        String reply = j.optString("reply", null);
        if (reply == null) reply = j.optString("output", null);
        if (reply == null) reply = j.optString("text", null);
        if (reply == null) reply = j.toString();
        return reply;
    }

    // --- Braincube / cubechain / Crimson LAW (all cubes → /api/braincube) ---

    public JSONObject braincubeStatus() throws Exception {
        return post("/api/braincube", new JSONObject().put("action", "status"));
    }

    /** Live lattice + First Cube LAW scoreboard (all cubes). */
    public JSONObject braincubeLive() throws Exception {
        return post("/api/braincube", new JSONObject().put("action", "live"));
    }

    /** Crimson Cube / First Cube's LAW only. */
    public JSONObject braincubeLaw() throws Exception {
        return post("/api/braincube", new JSONObject().put("action", "law"));
    }

    /** action: enable|disable|auto_adapt|direct_io|dry_run|continuous|… */
    public JSONObject braincubeAction(String action, String value) throws Exception {
        JSONObject body = new JSONObject();
        if (action != null) body.put("action", action);
        if (value != null) body.put("value", value);
        return post("/api/braincube", body);
    }

    public JSONObject braincubeTeach(int teacher, boolean ok) throws Exception {
        JSONObject body = new JSONObject();
        body.put("action", "teach");
        body.put("want", teacher);
        body.put("teacher", teacher);
        body.put("ok", ok);
        return post("/api/braincube", body);
    }

    /** lane null = peer default tick (continuous parent owns train). */
    public JSONObject cubechainTick(Integer lane) throws Exception {
        JSONObject body = new JSONObject();
        body.put("action", "live");
        if (lane != null) {
            body.put("action", "teach");
            body.put("want", lane.intValue());
        }
        return post("/api/braincube", body);
    }

    public JSONObject cubechainReset() throws Exception {
        JSONObject body = new JSONObject();
        body.put("action", "reset");
        return post("/api/braincube", body);
    }

    // --- Subagents ---

    public JSONObject subagentsList() throws Exception {
        return get("/api/subagents");
    }

    public JSONObject setSubagentPolicy(boolean enabled, int max, boolean llmSerial)
            throws Exception {
        JSONObject body = new JSONObject();
        body.put("enabled", enabled);
        body.put("max", max);
        body.put("llm_serial", llmSerial);
        return post("/api/subagents/policy", body);
    }

    /** Live typing callback — deltas as they arrive; done with full text. */
    public interface StreamListener {
        void onDelta(String delta);
        void onDone(String full);
        void onError(Exception e);
    }

    /**
     * Streaming chat (SSE). Falls back to non-stream chat if peer has no SSE.
     */
    public void chatStream(String message, StreamListener listener) {
        chatStream(message, null, null, null, listener);
    }

    /**
     * Streaming chat with optional vision image (base64, no data: prefix).
     * Grok OpenAI-compat models accept image_url data URIs.
     */
    public void chatStream(String message, String imageBase64, String imageMime,
                           StreamListener listener) {
        chatStream(message, imageBase64, imageMime, null, listener);
    }

    /**
     * Full attachments: prompt (may include extracted document text), optional
     * first image, optional images JSONArray of {base64,mime,name}.
     */
    public void chatStream(String message, String imageBase64, String imageMime,
                           org.json.JSONArray images, StreamListener listener) {
        if (listener == null) return;
        // New stream owns cancel flag; prior cancel must not poison this call.
        sChatCancel.set(false);
        try {
            JSONObject body = new JSONObject();
            String msg = message != null ? message : "";
            body.put("prompt", msg);
            body.put("message", msg);
            body.put("stream", true);
            if (images != null && images.length() > 0) {
                body.put("images", images);
                // first image also as legacy fields for older peers
                org.json.JSONObject first = images.optJSONObject(0);
                if (first != null) {
                    body.put("image_base64", first.optString("base64", ""));
                    body.put("image_mime", first.optString("mime", "image/jpeg"));
                }
            } else if (imageBase64 != null && !imageBase64.isEmpty()) {
                body.put("image_base64", imageBase64);
                body.put("image_mime",
                    imageMime != null && !imageMime.isEmpty() ? imageMime : "image/jpeg");
            }
            HttpURLConnection c = open("POST", "/api/chat");
            sActiveChat = c;
            try {
                if (chatCancelled()) throw new ChatCancelledException();
                c.setDoOutput(true);
                // On-device CPU llama can be slow for first tokens — still cancelable.
                // 1.7.11: 180s idle read (was 600s); UI cancel disconnects earlier.
                c.setReadTimeout(180000);
                // Large vision / document payloads
                c.setChunkedStreamingMode(64 * 1024);
                byte[] raw = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) { os.write(raw); }
                if (chatCancelled()) throw new ChatCancelledException();
                int code = c.getResponseCode();
                if (chatCancelled()) throw new ChatCancelledException();
                String ct = c.getContentType() != null ? c.getContentType() : "";
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in == null) throw new Exception("HTTP " + code + " empty");
                // Non-SSE fallback
                if (!ct.contains("event-stream") && !ct.contains("text/event-stream")) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (chatCancelled()) throw new ChatCancelledException();
                        sb.append(line);
                    }
                    br.close();
                    JSONObject j = new JSONObject(sb.toString());
                    if (j.has("error")) {
                        String err = j.optString("error", "error");
                        if (j.optBoolean("need_login", false)) throw new NeedLoginException(err);
                        throw new Exception(err);
                    }
                    String reply = j.optString("reply", j.optString("output", ""));
                    if (reply != null && !reply.isEmpty()) {
                        // Simulated typing for old peers
                        int step = Math.max(1, reply.length() / 40);
                        for (int i = 0; i < reply.length(); i += step) {
                            if (chatCancelled()) throw new ChatCancelledException();
                            int end = Math.min(reply.length(), i + step);
                            listener.onDelta(reply.substring(i, end));
                            try { Thread.sleep(12); } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw new ChatCancelledException();
                            }
                        }
                    }
                    if (chatCancelled()) throw new ChatCancelledException();
                    listener.onDone(reply != null ? reply : "");
                    return;
                }
                BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                StringBuilder full = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    if (chatCancelled()) throw new ChatCancelledException();
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty() || payload.equals("[DONE]")) continue;
                    try {
                        JSONObject j = new JSONObject(payload);
                        if (j.has("error")) {
                            String err = j.optString("error", "error");
                            if (j.optBoolean("need_login", false)) throw new NeedLoginException(err);
                            throw new Exception(err);
                        }
                        if (j.has("delta") && !j.isNull("delta")) {
                            String d = j.optString("delta", "");
                            // Android optString turns JSON null into the word "null"
                            if (d != null && !d.isEmpty() && !"null".equalsIgnoreCase(d)) {
                                full.append(d);
                                listener.onDelta(d);
                            }
                        }
                        if (j.optBoolean("done", false)) {
                            String r = null;
                            if (j.has("reply") && !j.isNull("reply")) {
                                r = j.optString("reply", null);
                                if ("null".equalsIgnoreCase(r)) r = null;
                            }
                            if (r == null || r.isEmpty()) r = full.toString();
                            if (r != null && full.length() == 0 && !r.isEmpty()
                                    && !"null".equalsIgnoreCase(r)) {
                                int step = Math.max(1, r.length() / 48);
                                for (int i = 0; i < r.length(); i += step) {
                                    if (chatCancelled()) throw new ChatCancelledException();
                                    int end = Math.min(r.length(), i + step);
                                    String chunk = r.substring(i, end);
                                    full.append(chunk);
                                    listener.onDelta(chunk);
                                    try { Thread.sleep(10); } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        throw new ChatCancelledException();
                                    }
                                }
                            }
                            String out = (r != null && !r.isEmpty() && !"null".equalsIgnoreCase(r))
                                ? r : full.toString();
                            if (chatCancelled()) throw new ChatCancelledException();
                            listener.onDone(out);
                            br.close();
                            return;
                        }
                    } catch (NeedLoginException e) {
                        throw e;
                    } catch (ChatCancelledException e) {
                        throw e;
                    } catch (org.json.JSONException ignored) {}
                }
                br.close();
                if (chatCancelled()) throw new ChatCancelledException();
                listener.onDone(full.toString());
            } finally {
                // 1.7.2: always disconnect after stream/chat (CLOSE-WAIT residual).
                if (sActiveChat == c) sActiveChat = null;
                try { c.disconnect(); } catch (Exception ignored) {}
            }
        } catch (ChatCancelledException e) {
            // 1.7.11: UI cancel — silent to listener (Send already re-armed).
            try { listener.onError(e); } catch (Exception ignored) {}
        } catch (Exception e) {
            if (chatCancelled() || e.getCause() instanceof ChatCancelledException) {
                try { listener.onError(new ChatCancelledException()); } catch (Exception ignored) {}
            } else {
                listener.onError(e);
            }
        }
    }

    /** Thrown when {@link #cancelActiveChat()} aborts an in-flight stream. */
    public static final class ChatCancelledException extends Exception {
        public ChatCancelledException() { super("chat cancelled"); }
    }

    public static final class NeedLoginException extends Exception {
        public NeedLoginException(String m) { super(m); }
    }

    public static final class ModelsResult {
        public boolean ok;
        public String baseUrl;
        public String current;
        public String error;
        public List<String> ids;
        /** True when caller asked for local-only list (no cloud chips). */
        public boolean localContext;
    }

    private JSONObject get(String path) throws Exception {
        return readJson(open("GET", path, 15000, 180000));
    }

    /** Status / health / auth — must not block UI thread pool for minutes. */
    private JSONObject getQuick(String path) throws Exception {
        return readJson(open("GET", path, 4000, 12000));
    }

    private JSONObject post(String path, JSONObject body) throws Exception {
        return postTimed(path, body, 15000, 180000);
    }

    private JSONObject postTimed(String path, JSONObject body, int connectMs, int readMs)
            throws Exception {
        HttpURLConnection c = open("POST", path, connectMs, readMs);
        c.setDoOutput(true);
        byte[] raw = body.toString().getBytes(StandardCharsets.UTF_8);
        try {
            try (OutputStream os = c.getOutputStream()) { os.write(raw); }
            return readJson(c);
        } catch (Exception e) {
            // 1.7.3: write fail before readJson must still disconnect.
            try { c.disconnect(); } catch (Exception ignored) {}
            throw e;
        }
    }

    private HttpURLConnection open(String method, String path) throws Exception {
        return open(method, path, 15000, 180000);
    }

    private HttpURLConnection open(String method, String path, int connectMs, int readMs)
            throws Exception {
        URL url = new URL(base + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        // 1.7.3: no keep-alive pool for loopback peer (health/auth thrash residual).
        c.setRequestProperty("Connection", "close");
        c.setUseCaches(false);
        String token = NanobotRuntime.readPeerToken(app);
        if (token != null && !token.isEmpty()) {
            c.setRequestProperty("X-Nanobot-Peer-Token", token);
        }
        return c;
    }

    private JSONObject readJson(HttpURLConnection c) throws Exception {
        InputStream in = null;
        try {
            int code = c.getResponseCode();
            in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null && code >= 400) in = c.getErrorStream();
            if (in == null) {
                try { in = c.getInputStream(); } catch (Exception ignored) {}
            }
            if (in == null) throw new Exception("HTTP " + code + " empty body");
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            String raw = sb.toString().trim();
            if (raw.isEmpty()) throw new Exception("HTTP " + code + " empty");
            // Plain-text 404 "not found" (old peer paths) — do not feed to JSONObject
            if (raw.charAt(0) != '{' && raw.charAt(0) != '[') {
                throw new Exception("HTTP " + code + ": " + raw);
            }
            JSONObject j;
            try {
                j = new JSONObject(raw);
            } catch (Exception pe) {
                throw new Exception("HTTP " + code + " bad JSON: " + pe.getMessage());
            }
            if (code >= 400) {
                String err = j.optString("error", "HTTP " + code);
                if (err.isEmpty() && j.has("error")) {
                    Object e = j.opt("error");
                    err = e != null ? e.toString() : ("HTTP " + code);
                }
                if (j.optBoolean("need_login", false)
                        || err.toLowerCase().contains("activation")
                        || err.toLowerCase().contains("login")) {
                    throw new NeedLoginException(err);
                }
                throw new Exception(err);
            }
            return j;
        } finally {
            // 1.7.2 residual: drain path must disconnect — half-close left peer
            // workers CLOSE-WAIT after health/auth/settings polls from UI.
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
            try { c.disconnect(); } catch (Exception ignored) {}
        }
    }
}
