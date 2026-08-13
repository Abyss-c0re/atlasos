package com.titanus2.nanobot;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * On-device llama.cpp server (OpenAI-compatible :8080).
 *
 * Binary discovery order (check BEFORE offering GGUF downloads):
 *   1. /system/bin/llama-server          (ROM inject WITH_LLAMA=1)
 *   2. /data/adb/titan2/llama.cpp/       (Magisk / rooted)
 *   3. /data/local/tmp/llama.cpp/        (lab push)
 *   4. app filesDir
 *
 * Launch prefers peer shell when the app UID cannot exec shell_data_file.
 */
public final class LlamaRuntime {
    private static final String TAG = "LlamaRuntime";
    public static final int PORT = LlamaManager.LLAMA_PORT;
    private static final AtomicReference<Process> PROC = new AtomicReference<>();

    public static final String SYSTEM_BIN = "/system/bin/llama-server";
    public static final String SYSTEM_LIB = "/system/lib64/llama-cpp";
    public static final String MAGISK_DIR = "/data/adb/titan2/llama.cpp";
    public static final String TMP_DIR = "/data/local/tmp/llama.cpp";

    private LlamaRuntime() {}

    public static final class Probe {
        public boolean present;
        public String binary;
        public String libDir;
        public String source; // system | magisk | tmp | app | none
        public String detail;
    }

    /** First check: is the runtime present anywhere? */
    public static Probe probe(Context c) {
        Probe p = new Probe();
        String[][] candidates = {
            { SYSTEM_BIN, SYSTEM_LIB, "system" },
            { MAGISK_DIR + "/llama-server", MAGISK_DIR, "magisk" },
            { TMP_DIR + "/llama-server", TMP_DIR, "tmp" },
            { new File(c.getFilesDir(), "llama.cpp/llama-server").getAbsolutePath(),
              new File(c.getFilesDir(), "llama.cpp").getAbsolutePath(), "app" },
            { "/data/local/tmp/llama-server", "/data/local/tmp", "tmp" },
        };
        for (String[] row : candidates) {
            File bin = new File(row[0]);
            if (bin.isFile() && bin.length() > 100) {
                p.present = true;
                p.binary = row[0];
                p.libDir = row[1];
                p.source = row[2];
                // Prefer a dir that actually has libllama.so
                if (!new File(p.libDir, "libllama.so").isFile()
                    && new File(TMP_DIR, "libllama.so").isFile()) {
                    p.libDir = TMP_DIR;
                }
                if (!new File(p.libDir, "libllama.so").isFile()
                    && new File(SYSTEM_LIB, "libllama.so").isFile()) {
                    p.libDir = SYSTEM_LIB;
                }
                p.detail = "found (" + p.source + "): " + p.binary;
                return p;
            }
        }
        p.present = false;
        p.source = "none";
        p.detail = "llama-server not found on system, Magisk, or /data/local/tmp.\n"
            + "Install: packages/titan2_llama/install_to_device.sh\n"
            + "or ROM hybrid WITH_LLAMA=1";
        return p;
    }

    /** Fast alias — same as probe (no slow asset walk). */
    public static Probe probeFast(Context c) {
        return probe(c);
    }

    /**
     * Best-effort extract of bundled llama-server from app assets if present.
     * No-op when system/tmp already has a binary (hybrid WITH_LLAMA path).
     */
    public static void extractBundledEngine(Context c) {
        if (c == null) return;
        Probe existing = probe(c);
        if (existing.present) return;
        try {
            File destDir = new File(c.getFilesDir(), "llama.cpp");
            //noinspection ResultOfMethodCallIgnored
            destDir.mkdirs();
            File dest = new File(destDir, "llama-server");
            if (dest.isFile() && dest.length() > 100) return;
            String[] assetNames = { "llama-server", "llama.cpp/llama-server" };
            for (String name : assetNames) {
                try (java.io.InputStream in = c.getAssets().open(name);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    //noinspection ResultOfMethodCallIgnored
                    dest.setExecutable(true, false);
                    return;
                } catch (Exception ignored) {
                    // try next asset name
                }
            }
        } catch (Exception ignored) {}
    }

    /** Lean ctx for tiny GGUFs; clamp to [256, maxCtx]. */
    public static int recommendedCtx(File model, int maxCtx) {
        int cap = maxCtx > 0 ? maxCtx : 512;
        if (model == null) return Math.min(512, cap);
        long mb = model.length() / (1024L * 1024L);
        int want = mb < 200 ? 512 : (mb < 800 ? 1024 : 2048);
        if (want > cap) want = cap;
        if (want < 256) want = 256;
        return want;
    }

    /**
     * Tiny models often fail tool-calling probes — remember path so applyAsNanobotBackend
     * can keep tools off. Best-effort SharedPreferences flag.
     */
    public static void markTinyModelNoTools(Context c, String modelPath) {
        if (c == null || modelPath == null || modelPath.isEmpty()) return;
        try {
            c.getSharedPreferences("llama_runtime", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("no_tools:" + modelPath, true)
                .apply();
        } catch (Exception ignored) {}
    }

    public static boolean isBinaryPresent(Context c) {
        return probe(c).present;
    }

    public static String findBinary(Context c) {
        Probe p = probe(c);
        return p.present ? p.binary : null;
    }

    public static String libDirFor(String binaryPath) {
        Probe dummy = new Probe();
        // re-probe is fine
        return dummy.libDir != null ? dummy.libDir : TMP_DIR;
    }

    public static boolean isServerUp() {
        for (String path : new String[] {
            "http://127.0.0.1:" + PORT + "/health",
            "http://127.0.0.1:" + PORT + "/v1/models"
        }) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(path).openConnection();
                c.setConnectTimeout(500);
                c.setReadTimeout(500);
                int code = c.getResponseCode();
                c.disconnect();
                if (code >= 200 && code < 500) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    public static String baseUrl() {
        return "http://127.0.0.1:" + PORT + "/v1";
    }

    public static String statusLine(Context c) {
        Probe p = probe(c);
        if (!PrivacyPrefs.localLlamaEnabled(c)) {
            return "On-device AI: OFF (optional)\n"
                + (p.present
                    ? "Engine ready (" + p.source + ") — turn ON in Settings to use."
                    : "Engine not installed yet.");
        }
        if (!p.present) {
            return "Engine: NOT INSTALLED\n" + p.detail
                + "\n\nDo not download models until the engine is installed.";
        }
        boolean up = isServerUp();
        return "Engine: " + (up ? "RUNNING" : "ready, not running")
            + "\nWhere: " + p.source + " · " + p.binary
            + "\nAPI: " + baseUrl();
    }

    /**
     * Start llama-server with GGUF. Uses peer shell when needed (app cannot exec shell_data).
     * @return null on success, error string otherwise
     */
    public static String start(Context c, File gguf, int ctx) {
        if (!PrivacyPrefs.localLlamaEnabled(c)) {
            return "Turn ON “On-device AI” in Settings first";
        }
        Probe p = probe(c);
        if (!p.present) {
            return "Engine missing. Install llama-server (ROM/Magisk/lab) before models.";
        }
        if (gguf == null || !LlamaManager.isComplete(gguf)) {
            return "No model file yet — download a model after the engine is ready.";
        }
        if (ctx <= 0) ctx = 4096;
        stop(c);

        String lib = p.libDir;
        if (!new File(lib, "libllama.so").isFile()) {
            if (new File(TMP_DIR, "libllama.so").isFile()) lib = TMP_DIR;
            else if (new File(SYSTEM_LIB, "libllama.so").isFile()) lib = SYSTEM_LIB;
        }

        // 1) Try peer shell (works when binary lives under shell_data_file)
        try {
            PeerClient peer = new PeerClient(c);
            if (peer.healthy() || NanobotRuntime.isPortListening()) {
                String log = "/data/local/tmp/llama-server.out";
                int nctx = ctx > 0 ? Math.min(ctx, 1024) : 1024;
                int thr = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
                String cmd = "killall -9 llama-server 2>/dev/null; true; "
                    + "cd '" + lib + "' && "
                    + "LD_LIBRARY_PATH='" + lib + "' "
                    + "nohup '" + p.binary + "' "
                    + "-m '" + gguf.getAbsolutePath() + "' "
                    + "--host 127.0.0.1 --port " + PORT + " "
                    + "-c " + nctx + " -ngl 0 --parallel 1 "
                    + "--threads " + thr + " "
                    + ">" + log + " 2>&1 & echo $!";
                JSONObject r = peer.shell(cmd);
                String out = r.optString("output", "").trim();
                Log.i(TAG, "peer start exit=" + r.optInt("exit") + " out=" + out);
            }
        } catch (Exception e) {
            Log.w(TAG, "peer start: " + e.getMessage());
        }

        // 2) Also try ProcessBuilder (works for system/app private paths)
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(p.binary);
            cmd.add("-m");
            cmd.add(gguf.getAbsolutePath());
            cmd.add("--host");
            cmd.add("127.0.0.1");
            cmd.add("--port");
            cmd.add(String.valueOf(PORT));
            cmd.add("-c");
            cmd.add(String.valueOf(ctx > 0 ? Math.min(ctx, 1024) : 1024)); // lean phone default
            cmd.add("-ngl");
            cmd.add("0");
            cmd.add("--parallel");
            cmd.add("1");
            cmd.add("--threads");
            cmd.add(String.valueOf(Math.max(2, Runtime.getRuntime().availableProcessors() / 2)));
            File log = new File(c.getFilesDir(), "llama-server.out");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(lib));
            String ld = pb.environment().get("LD_LIBRARY_PATH");
            pb.environment().put("LD_LIBRARY_PATH",
                ld == null || ld.isEmpty() ? lib : lib + ":" + ld);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
            Process proc = pb.start();
            PROC.set(proc);
        } catch (Exception e) {
            Log.w(TAG, "ProcessBuilder start: " + e.getMessage());
        }

        AccessLog.record(c, "llama_start", "model=" + gguf.getName() + " src=" + p.source);
        // Q4 1–2B models can take 30–90s to mmap on phone storage
        for (int i = 0; i < 120; i++) {
            if (isServerUp()) return null;
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }
        if (isServerUp()) return null;
        return "Engine started but still loading model (can take ~1 min on first start). Try again shortly.";
    }

    public static void stop(Context c) {
        Process proc = PROC.getAndSet(null);
        if (proc != null) {
            try { proc.destroy(); } catch (Exception ignored) {}
            try { proc.destroyForcibly(); } catch (Exception ignored) {}
        }
        try {
            if (NanobotRuntime.isPortListening()) {
                new PeerClient(c).shell(
                    "pkill -f 'llama-server' 2>/dev/null; "
                        + "fuser -k " + PORT + "/tcp 2>/dev/null; true");
            }
        } catch (Exception ignored) {}
        try {
            new ProcessBuilder("sh", "-c",
                "pkill -f 'llama-server' 2>/dev/null; fuser -k " + PORT + "/tcp 2>/dev/null; true")
                .start();
        } catch (Exception ignored) {}
        AccessLog.record(c, "llama_stop", "stop");
        for (int i = 0; i < 20 && isServerUp(); i++) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
    }

    public static void applyAsNanobotBackend(Context c, PeerClient peer) throws Exception {
        applyAsNanobotBackend(c, peer, null);
    }

    /** Point peer at local llama using absolute GGUF path as model id (llama-server expects that). */
    public static void applyAsNanobotBackend(Context c, PeerClient peer, File gguf) throws Exception {
        String base = baseUrl();
        String modelId = null;
        if (gguf != null && gguf.isFile()) {
            modelId = gguf.getAbsolutePath();
            PrivacyPrefs.setSelectedLocalModelPath(c, modelId);
        } else {
            String sel = PrivacyPrefs.selectedLocalModelPath(c);
            if (sel != null && !sel.isEmpty() && new File(sel).isFile()) modelId = sel;
        }
        peer.setBackend("offline", base, modelId);
        // Tools: ON unless this model was probed and failed tool calling
        boolean toolsOn = true;
        if (modelId != null) {
            Boolean known = PrivacyPrefs.toolsSupported(c, modelId);
            if (known != null && !known) toolsOn = false;
        }
        // Only rewrite env (backend URL). NEVER touch session / peer_token (Grok auth).
        try {
            File env = new File(SHARED_HOME_ALIAS(), "env");
            String body = "# offline local — does NOT wipe Grok session file\n"
                + "NANOBOT_BASE_URL=" + base + "\n"
                + "NANOBOT_MODEL=" + (modelId != null ? modelId : "local") + "\n"
                + "NANOBOT_TOOLS=" + (toolsOn ? "1" : "0") + "\n";
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(env)) {
                out.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            env.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            env.setWritable(true, false);
        } catch (Exception ignored) {}
        AccessLog.record(c, "backend_local_llama",
            base + " model=" + modelId + " tools=" + (toolsOn ? "1" : "0"));
    }

    /** Result of a live tool-call probe against the loaded llama-server model. */
    public static final class ToolProbeResult {
        public boolean supported;
        public boolean engineDown;
        public String detail;
        public String modelId;

        public static ToolProbeResult ok(String modelId, String detail) {
            ToolProbeResult r = new ToolProbeResult();
            r.supported = true;
            r.modelId = modelId;
            r.detail = detail != null ? detail : "Tool calling works on this model.";
            return r;
        }

        public static ToolProbeResult no(String modelId, String detail) {
            ToolProbeResult r = new ToolProbeResult();
            r.supported = false;
            r.modelId = modelId;
            r.detail = detail != null ? detail
                : "This model does not support tool calling.";
            return r;
        }

        public static ToolProbeResult down(String detail) {
            ToolProbeResult r = new ToolProbeResult();
            r.supported = false;
            r.engineDown = true;
            r.detail = detail != null ? detail : "Engine not running.";
            return r;
        }

        /** Short user-facing title for dialogs. */
        public String title() {
            if (engineDown) return "Engine not ready";
            return supported ? "Tool calling supported" : "Tool calling not supported";
        }
    }

    /**
     * Live-test whether the loaded GGUF can produce OpenAI-style tool_calls.
     * Used when the user selects a <b>custom</b> model — curated presets skip this.
     * Blocks; run off the UI thread.
     */
    public static ToolProbeResult probeToolCalling(String modelId) {
        if (!isServerUp()) {
            return ToolProbeResult.down("Engine down — start the model first.");
        }
        if (modelId == null || modelId.isEmpty()) {
            modelId = runningModelPath();
        }
        if (modelId == null || modelId.isEmpty()) {
            return ToolProbeResult.no(null, "No model id to probe.");
        }
        try {
            // Prefer tool_choice=required so models that support tools must call
            ToolProbeResult r = probeToolOnce(modelId, "required");
            if (r.supported) return r;
            // Some servers reject "required" — retry auto
            if (r.detail != null && (r.detail.toLowerCase().contains("tool_choice")
                    || r.detail.toLowerCase().contains("unknown")
                    || r.detail.toLowerCase().contains("invalid"))) {
                r = probeToolOnce(modelId, "auto");
            }
            return r;
        } catch (Exception e) {
            return ToolProbeResult.no(modelId,
                "Tool probe failed: " + (e.getMessage() != null ? e.getMessage() : e));
        }
    }

    private static ToolProbeResult probeToolOnce(String modelId, String toolChoice)
            throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", modelId);
        body.put("stream", false);
        body.put("temperature", 0);
        body.put("max_tokens", 96);
        body.put("tool_choice", toolChoice);

        JSONArray tools = new JSONArray();
        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        JSONObject fn = new JSONObject();
        fn.put("name", "run_terminal_command");
        fn.put("description", "Run one shell command. Required for this test.");
        JSONObject params = new JSONObject();
        params.put("type", "object");
        JSONObject props = new JSONObject();
        JSONObject cmd = new JSONObject();
        cmd.put("type", "string");
        cmd.put("description", "shell command");
        props.put("command", cmd);
        params.put("properties", props);
        JSONArray req = new JSONArray();
        req.put("command");
        params.put("required", req);
        fn.put("parameters", params);
        tool.put("function", fn);
        tools.put(tool);
        body.put("tools", tools);

        JSONArray messages = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content",
            "You must call the run_terminal_command tool. Never answer in plain text only.");
        messages.put(sys);
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", "Run exactly: echo toolprobe_ok");
        messages.put(user);
        body.put("messages", messages);

        HttpURLConnection conn = (HttpURLConnection)
            new URL("http://127.0.0.1:" + PORT + "/v1/chat/completions").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        byte[] raw = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) { os.write(raw); }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
        }
        conn.disconnect();
        String resp = sb.toString();
        String low = resp.toLowerCase();

        if (code >= 400) {
            if (low.contains("tool") || low.contains("function") || low.contains("not support")
                    || low.contains("unknown field") || low.contains("does not support")) {
                return ToolProbeResult.no(modelId,
                    "Server rejected tools (HTTP " + code + "). "
                        + "This custom model/engine path does not support tool calling.");
            }
            return ToolProbeResult.no(modelId,
                "Probe HTTP " + code + ": " + trunc(resp, 180));
        }

        // Success path: look for tool_calls in the JSON
        if (low.contains("\"tool_calls\"") && (low.contains("run_terminal_command")
                || low.contains("\"function\"") || low.contains("\"name\""))) {
            // Confirm it's not an empty tool_calls:[]
            if (!low.contains("\"tool_calls\":[]") && !low.contains("\"tool_calls\": []")) {
                return ToolProbeResult.ok(modelId,
                    "Custom model produced a tool call — shell/tools enabled.");
            }
        }
        // Also parse structured
        try {
            JSONObject j = new JSONObject(resp);
            JSONArray choices = j.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject ch0 = choices.optJSONObject(0);
                if (ch0 != null) {
                    JSONObject msg = ch0.optJSONObject("message");
                    if (msg != null) {
                        JSONArray tc = msg.optJSONArray("tool_calls");
                        if (tc != null && tc.length() > 0) {
                            return ToolProbeResult.ok(modelId,
                                "Custom model produced a tool call — shell/tools enabled.");
                        }
                        // finish_reason tool_calls
                        String fr = ch0.optString("finish_reason", "");
                        if ("tool_calls".equalsIgnoreCase(fr) || "function_call".equalsIgnoreCase(fr)) {
                            return ToolProbeResult.ok(modelId,
                                "Custom model requested tools — shell/tools enabled.");
                        }
                    }
                }
            }
        } catch (org.json.JSONException ignored) {}

        String contentHint = "";
        try {
            String c = extractContent(resp);
            if (c != null && !c.isEmpty()) contentHint = " Model only replied: “" + trunc(c, 80) + "”.";
        } catch (Exception ignored) {}

        return ToolProbeResult.no(modelId,
            "This custom model did not produce a tool call."
                + contentHint
                + " Chat-only mode will be used (no shell/tools). "
                + "Pick a tools-capable GGUF or use a cloud provider for tools.");
    }

    private static String trunc(String s, int n) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static String SHARED_HOME_ALIAS() {
        return NanobotRuntime.SHARED_HOME;
    }

    /** Path of the GGUF currently loaded by llama-server (from /v1/models), or null. */
    public static String runningModelPath() {
        try {
            HttpURLConnection c = (HttpURLConnection)
                new URL("http://127.0.0.1:" + PORT + "/v1/models").openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(1200);
            if (c.getResponseCode() != 200) { c.disconnect(); return null; }
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(c.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            c.disconnect();
            String j = sb.toString();
            // "model":"/path/to/file.gguf" or "name":"..."
            int i = j.indexOf("\"model\":\"");
            if (i < 0) i = j.indexOf("\"name\":\"");
            if (i < 0) return null;
            i = j.indexOf('"', i + 8) + 1;
            // find start of value more carefully
            int key = j.indexOf("\"model\":\"");
            if (key < 0) key = j.indexOf("\"id\":\"");
            if (key < 0) key = j.indexOf("\"name\":\"");
            if (key < 0) return null;
            int start = j.indexOf(':', key) + 1;
            while (start < j.length() && (j.charAt(start) == ' ' || j.charAt(start) == '"')) {
                if (j.charAt(start) == '"') { start++; break; }
                start++;
            }
            int end = j.indexOf('"', start);
            if (end <= start) return null;
            return j.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    public static String lastLog(Context c, int maxLines) {
        File[] logs = {
            new File("/data/local/tmp/llama-server.out"),
            new File(c.getFilesDir(), "llama-server.out"),
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
        return "(no log)";
    }

    /**
     * Direct chat to llama-server (no agent). Used as fallback when peer/agent
     * returns empty/"null". Prefer stream=true SSE; fall back to one-shot JSON.
     *
     * Important: llama SSE first chunk is often {@code "content": null}. Android
     * {@code JSONObject.optString} turns JSON null into the string {@code "null"} —
     * always use {@link #jsonText} which treats JSON null as missing.
     */
    public static void chatLocal(Context c, String userMessage, PeerClient.StreamListener listener) {
        if (listener == null) return;
        if (userMessage == null || userMessage.trim().isEmpty()) {
            listener.onError(new Exception("empty message"));
            return;
        }
        if (!isServerUp()) {
            listener.onError(new Exception(
                "Engine down — open On-device AI → Start model (Wi‑Fi not needed)"));
            return;
        }
        String modelId = runningModelPath();
        if (modelId == null || modelId.isEmpty()) {
            String sel = PrivacyPrefs.selectedLocalModelPath(c);
            if (sel != null && !sel.isEmpty()) modelId = sel;
            else modelId = "local";
        }
        try {
            // Prefer non-stream first: more reliable content parse on phone
            String reply = chatLocalOnce(c, modelId, userMessage);
            if (reply == null || reply.isEmpty() || isJunkReply(reply)) {
                // stream retry
                StringBuilder acc = new StringBuilder();
                if (chatLocalStream(c, modelId, userMessage, new PeerClient.StreamListener() {
                    @Override public void onDelta(String d) {
                        if (d != null) acc.append(d);
                    }
                    @Override public void onDone(String full) {
                        if (full != null && acc.length() == 0) acc.append(full);
                    }
                    @Override public void onError(Exception e) { /* handled below */ }
                }) && acc.length() > 0 && !isJunkReply(acc.toString())) {
                    reply = acc.toString();
                }
            }
            if (reply == null) reply = "";
            if (isJunkReply(reply)) {
                listener.onError(new Exception("Model returned empty/null — try again"));
                return;
            }
            // Persist turn so next local message has history (Open WebUI-style)
            ChatPrefs.recordExchange(c, userMessage, reply);
            int step = Math.max(1, reply.length() / 48);
            for (int i = 0; i < reply.length(); i += step) {
                int end = Math.min(reply.length(), i + step);
                listener.onDelta(reply.substring(i, end));
                try { Thread.sleep(8); } catch (InterruptedException ignored) {}
            }
            listener.onDone(reply);
        } catch (Exception e) {
            listener.onError(e);
        }
    }

    /** True for empty, literal "null", or pure @! echo junk from tiny models. */
    public static boolean isJunkReply(String s) {
        if (s == null) return true;
        String t = s.trim();
        if (t.isEmpty()) return true;
        if ("null".equalsIgnoreCase(t) || "(null)".equalsIgnoreCase(t)) return true;
        if ("undefined".equalsIgnoreCase(t)) return true;
        // pure @! echo of short prompts
        if (t.startsWith("@!") && t.length() < 40) return true;
        if (t.startsWith("(no reply from model")) return true;
        if (t.startsWith("no response from API")) return true;
        return false;
    }

    /**
     * Read a JSON string field; treat missing / JSON null as null.
     * Never return the literal word "null" from a null value.
     */
    public static String jsonText(JSONObject o, String key) {
        if (o == null || key == null) return null;
        if (!o.has(key) || o.isNull(key)) return null;
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return null;
        if (v instanceof String) {
            String s = (String) v;
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
            return s;
        }
        String s = String.valueOf(v);
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        return s;
    }

    private static boolean chatLocalStream(Context c, String modelId, String userMessage,
                                           PeerClient.StreamListener listener) throws Exception {
        JSONObject body = localChatBody(c, modelId, userMessage, true);
        HttpURLConnection conn = (HttpURLConnection)
            new URL("http://127.0.0.1:" + PORT + "/v1/chat/completions").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(600000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream, application/json");
        byte[] raw = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) { os.write(raw); }
        int code = conn.getResponseCode();
        String ct = conn.getContentType() != null ? conn.getContentType() : "";
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) throw new Exception("llama HTTP " + code);
        if (!ct.contains("event-stream") && !ct.contains("text/event-stream")) {
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            conn.disconnect();
            if (code >= 400) throw new Exception("llama " + code + ": " + sb);
            String reply = extractContent(sb.toString());
            if (reply != null && !reply.isEmpty() && !isJunkReply(reply)) {
                listener.onDelta(reply);
                listener.onDone(reply);
                return true;
            }
            return false;
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder full = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            if (!line.startsWith("data:")) continue;
            String payload = line.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
            try {
                JSONObject j = new JSONObject(payload);
                JSONArray choices = j.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                JSONObject ch0 = choices.optJSONObject(0);
                if (ch0 == null) continue;
                String piece = null;
                JSONObject delta = ch0.optJSONObject("delta");
                if (delta != null) piece = jsonText(delta, "content");
                if (piece == null) {
                    JSONObject msg = ch0.optJSONObject("message");
                    if (msg != null) piece = jsonText(msg, "content");
                }
                if (piece != null && !piece.isEmpty()) {
                    full.append(piece);
                    listener.onDelta(piece);
                }
            } catch (org.json.JSONException ignored) {}
        }
        br.close();
        conn.disconnect();
        String out = full.toString();
        if (isJunkReply(out)) return false;
        listener.onDone(out);
        return true;
    }

    private static String chatLocalOnce(Context c, String modelId, String userMessage)
            throws Exception {
        JSONObject body = localChatBody(c, modelId, userMessage, false);
        HttpURLConnection conn = (HttpURLConnection)
            new URL("http://127.0.0.1:" + PORT + "/v1/chat/completions").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(600000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        byte[] raw = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) { os.write(raw); }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) throw new Exception("llama HTTP " + code + " empty");
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        conn.disconnect();
        if (code >= 400) throw new Exception("llama " + code + ": " + sb);
        String reply = extractContent(sb.toString());
        return reply != null ? reply : "";
    }

    /**
     * OpenAI chat body for direct llama: max_tokens + system/summary/recent
     * from {@link ChatPrefs} (same knobs as Context & history UI / peer prefs).
     */
    private static JSONObject localChatBody(Context c, String modelId, String userMessage,
                                            boolean stream) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", modelId);
        body.put("stream", stream);
        body.put("temperature", 0.7);
        int maxTok = c != null ? ChatPrefs.maxTokens(c) : 256;
        body.put("max_tokens", maxTok);

        JSONArray messages = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", buildLocalSystem(c));
        messages.put(sys);

        // Prior turns (user/assistant pairs), capped by recent_turns
        if (c != null) {
            appendRecentHistory(c, messages);
        }

        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", userMessage != null ? userMessage : "");
        messages.put(user);
        body.put("messages", messages);
        return body;
    }

    private static String buildLocalSystem(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a helpful offline assistant on this phone. ")
            .append("Answer briefly and clearly in plain text. Never reply with null.");
        if (c == null) return sb.toString();
        if (ChatPrefs.includeCore(c)) {
            String core = ChatPrefs.readCore(c);
            if (core != null) {
                core = core.trim();
                if (!core.isEmpty()) {
                    sb.append("\n\n## Core identity\n").append(core);
                }
            }
        }
        if (ChatPrefs.includeSummary(c)) {
            String sum = ChatPrefs.readSummary(c);
            if (sum != null) {
                sum = sum.trim();
                if (!sum.isEmpty()) {
                    int cap = ChatPrefs.summaryMax(c);
                    if (sum.length() > cap) sum = sum.substring(0, cap);
                    sb.append("\n\n## Compacted earlier context\n").append(sum);
                }
            }
        }
        return sb.toString();
    }

    /** Inject last N*2 role lines from recent.jsonl into the messages array. */
    private static void appendRecentHistory(Context c, JSONArray messages) {
        int maxMsgs = Math.max(2, ChatPrefs.recentTurns(c) * 2);
        int msgChars = ChatPrefs.msgChars(c);
        List<String[]> turns = ChatPrefs.loadRecentTurns(c, maxMsgs);
        for (String[] row : turns) {
            if (row == null || row.length < 2) continue;
            String role = row[0];
            String content = row[1];
            if (role == null || content == null) continue;
            if (!"user".equals(role) && !"assistant".equals(role) && !"system".equals(role)) {
                continue;
            }
            if (content.length() > msgChars) content = content.substring(0, msgChars);
            try {
                JSONObject m = new JSONObject();
                m.put("role", role);
                m.put("content", content);
                messages.put(m);
            } catch (Exception ignored) {}
        }
    }

    private static String extractContent(String json) {
        try {
            JSONObject j = new JSONObject(json);
            JSONArray choices = j.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject ch0 = choices.optJSONObject(0);
            if (ch0 == null) return null;
            JSONObject msg = ch0.optJSONObject("message");
            if (msg != null) {
                String c = jsonText(msg, "content");
                if (c != null) return c;
            }
            JSONObject delta = ch0.optJSONObject("delta");
            if (delta != null) {
                String c = jsonText(delta, "content");
                if (c != null) return c;
            }
            return jsonText(ch0, "text");
        } catch (Exception e) {
            return null;
        }
    }
}
