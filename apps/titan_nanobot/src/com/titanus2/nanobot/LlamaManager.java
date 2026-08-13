package com.titanus2.nanobot;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GGUF model catalog + download into app/shared models dir.
 * llama.cpp server is managed by {@link LlamaRuntime}.
 */
public final class LlamaManager {
    private static final String TAG = "LlamaManager";
    public static final int LLAMA_PORT = 8080;

    /** Curated small/medium GGUF presets (HF resolve URLs). */
    public static final class Preset {
        public final String id;
        public final String name;
        public final String filename;
        public final String url;
        public final String sizeHint;
        public final String notes;

        public Preset(String id, String name, String filename, String url,
                      String sizeHint, String notes) {
            this.id = id;
            this.name = name;
            this.filename = filename;
            this.url = url;
            this.sizeHint = sizeHint;
            this.notes = notes;
        }
    }

    public interface Progress {
        void onProgress(long downloaded, long total, String status);
        void onDone(File file);
        void onError(String msg);
    }

    private LlamaManager() {}

    /**
     * Default suggestions only: smallest / newest ≤ ~0.5B params (phone-friendly).
     * Larger models can still be added via custom URL.
     */
    public static List<Preset> presets() {
        ArrayList<Preset> p = new ArrayList<>();
        // Newest tiny defaults first — max ~0.5B
        p.add(new Preset(
            "qwen25-05b-q4",
            "Qwen2.5 0.5B Instruct (recommended)",
            "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            "~400 MB",
            "Newest small Qwen · good quality for size · offline-friendly"));
        p.add(new Preset(
            "smollm2-360m-q4",
            "SmolLM2 360M Instruct",
            "SmolLM2-360M-Instruct-Q4_K_M.gguf",
            "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q4_K_M.gguf",
            "~250 MB",
            "Smallest default · fastest load on phone"));
        p.add(new Preset(
            "gemma3-270m-q4",
            "Gemma 3 270M Instruct",
            "gemma-3-270m-it-Q4_K_M.gguf",
            "https://huggingface.co/unsloth/gemma-3-270m-it-GGUF/resolve/main/gemma-3-270m-it-Q4_K_M.gguf",
            "~180 MB",
            "Google Gemma 3 · tiny · good for tight RAM"));
        return p;
    }

    /** True if this GGUF filename is one of the curated defaults (not Custom paste). */
    public static boolean isPresetFilename(String filename) {
        if (filename == null) return false;
        String leaf = filename;
        int s = leaf.lastIndexOf('/');
        if (s >= 0) leaf = leaf.substring(s + 1);
        String low = leaf.toLowerCase(Locale.US);
        for (Preset p : presets()) {
            if (p.filename.equalsIgnoreCase(leaf) || p.filename.toLowerCase(Locale.US).equals(low))
                return true;
        }
        return false;
    }

    public static File modelsDir(Context c) {
        // Prefer shared path so shell/peer can see models
        File shared = new File("/data/local/tmp/nanobot_models");
        try {
            if (!shared.exists()) //noinspection ResultOfMethodCallIgnored
                shared.mkdirs();
            File probe = new File(shared, ".w");
            try (FileOutputStream o = new FileOutputStream(probe)) { o.write(1); }
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return shared;
        } catch (Exception e) {
            File ext = c.getExternalFilesDir("models");
            if (ext != null) {
                //noinspection ResultOfMethodCallIgnored
                ext.mkdirs();
                return ext;
            }
            File f = new File(c.getFilesDir(), "models");
            //noinspection ResultOfMethodCallIgnored
            f.mkdirs();
            return f;
        }
    }

    public static File modelFile(Context c, String filename) {
        // Prefer existing file in any known dir
        for (File dir : modelDirs(c)) {
            File f = new File(dir, filename);
            if (f.isFile()) return f;
        }
        return new File(modelsDir(c), filename);
    }

    /** All places we may store GGUF (shared + app external + private). */
    public static List<File> modelDirs(Context c) {
        ArrayList<File> dirs = new ArrayList<>();
        File shared = new File("/data/local/tmp/nanobot_models");
        dirs.add(shared);
        try {
            File ext = c.getExternalFilesDir("models");
            if (ext != null) dirs.add(ext);
        } catch (Exception ignored) {}
        dirs.add(new File(c.getFilesDir(), "models"));
        return dirs;
    }

    public static List<File> listLocalGguf(Context c) {
        ArrayList<File> out = new ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (File dir : modelDirs(c)) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String n = f.getName().toLowerCase(Locale.US);
                if (!(n.endsWith(".gguf") || n.endsWith(".gguf.partial"))) continue;
                if (seen.add(f.getAbsolutePath())) out.add(f);
            }
        }
        return out;
    }

    public static boolean isComplete(File f) {
        return f != null && f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".gguf")
            && f.length() > 1024 * 1024; // >1MB
    }

    /** Download GGUF (supports resume via .partial). Runs on caller thread. */
    public static void download(Context c, String url, String filename, Progress cb) {
        if (!PrivacyPrefs.localLlamaEnabled(c)) {
            if (cb != null) cb.onError("On-device AI is OFF — turn it on in Settings first");
            return;
        }
        // Engine must exist before burning bandwidth on multi‑GB models
        if (!LlamaRuntime.isBinaryPresent(c)) {
            if (cb != null) cb.onError(
                "Engine not installed. Install llama-server first "
                    + "(system / Magisk / install_to_device.sh), then download models.");
            return;
        }
        File finalFile = modelFile(c, filename);
        File partial = new File(finalFile.getAbsolutePath() + ".partial");
        HttpURLConnection conn = null;
        try {
            //noinspection ResultOfMethodCallIgnored
            modelsDir(c).mkdirs();
            long existing = partial.exists() ? partial.length() : 0;
            if (cb != null) cb.onProgress(existing, -1, "connecting…");

            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("User-Agent", "Nanobot/1.0");
            if (existing > 0) {
                conn.setRequestProperty("Range", "bytes=" + existing + "-");
            }
            int code = conn.getResponseCode();
            // follow one redirect manually if needed
            if (code == 301 || code == 302 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                conn = (HttpURLConnection) new URL(loc).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(120000);
                conn.setRequestProperty("User-Agent", "Nanobot/1.0");
                if (existing > 0) conn.setRequestProperty("Range", "bytes=" + existing + "-");
                code = conn.getResponseCode();
            }
            if (code == 416) {
                // range not satisfiable — already complete?
                if (partial.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    partial.renameTo(finalFile);
                }
                if (cb != null) cb.onDone(finalFile);
                return;
            }
            if (code != 200 && code != 206) {
                throw new Exception("HTTP " + code);
            }
            long contentLen = conn.getContentLengthLong();
            long total = contentLen > 0
                ? (code == 206 ? existing + contentLen : contentLen)
                : -1;
            boolean append = (code == 206 && existing > 0);
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(partial, append)) {
                byte[] buf = new byte[64 * 1024];
                long got = existing;
                int n;
                long lastUi = 0;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    got += n;
                    if (cb != null && System.currentTimeMillis() - lastUi > 250) {
                        lastUi = System.currentTimeMillis();
                        String st = total > 0
                            ? String.format(Locale.US, "%.1f / %.1f MB",
                                got / 1e6, total / 1e6)
                            : String.format(Locale.US, "%.1f MB", got / 1e6);
                        cb.onProgress(got, total, st);
                    }
                }
                out.flush();
            }
            if (finalFile.exists()) //noinspection ResultOfMethodCallIgnored
                finalFile.delete();
            if (!partial.renameTo(finalFile)) {
                // copy fallback
                try (InputStream in = new java.io.FileInputStream(partial);
                     FileOutputStream out = new FileOutputStream(finalFile)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                }
                //noinspection ResultOfMethodCallIgnored
                partial.delete();
            }
            // World r/w so app + shell peer can delete/move later (tmp is often shell-owned)
            try {
                //noinspection ResultOfMethodCallIgnored
                finalFile.setReadable(true, false);
                //noinspection ResultOfMethodCallIgnored
                finalFile.setWritable(true, false);
            } catch (Exception ignored) {}
            try {
                if (NanobotRuntime.isPortListening()) {
                    String esc = finalFile.getAbsolutePath().replace("'", "'\\''");
                    new PeerClient(c).shell("chmod 666 '" + esc + "' 2>/dev/null; true");
                }
            } catch (Exception ignored) {}
            AccessLog.record(c, "gguf_download", "Downloaded " + filename
                + " (" + finalFile.length() + " bytes)");
            if (cb != null) cb.onDone(finalFile);
        } catch (Exception e) {
            Log.e(TAG, "download: " + e.getMessage());
            if (cb != null) cb.onError(e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Delete a GGUF (and .partial). Files under /data/local/tmp are often
     * shell-owned (660) so app {@link File#delete()} fails — fall back to peer shell rm.
     */
    /**
     * Resolve a pasted Hugging Face link or model id into a direct GGUF download.
     * Accepts:
     *  - full URL …/file.gguf
     *  - https://huggingface.co/org/repo[/tree/main/…]
     *  - org/repo  or  org/repo-GGUF
     * Picks Q4_K_M when listing a repo (else first .gguf).
     */
    public static final class ResolvedDownload {
        public String url;
        public String filename;
        public String note;
    }

    public static ResolvedDownload resolveCustomInput(String raw) throws Exception {
        if (raw == null) throw new Exception("Paste a Hugging Face link or model name");
        String s = raw.trim();
        if (s.isEmpty()) throw new Exception("Paste a Hugging Face link or model name");

        // Direct .gguf URL
        if (s.startsWith("http://") || s.startsWith("https://")) {
            if (s.toLowerCase(Locale.US).contains(".gguf")) {
                // strip query for filename
                String path = s;
                int q = path.indexOf('?');
                if (q > 0) path = path.substring(0, q);
                int slash = path.lastIndexOf('/');
                String fn = slash >= 0 ? path.substring(slash + 1) : "model.gguf";
                if (!fn.toLowerCase(Locale.US).endsWith(".gguf")) fn = fn + ".gguf";
                // Prefer resolve/main form
                String url = s;
                if (s.contains("huggingface.co/") && !s.contains("/resolve/")) {
                    // convert blob/main/x.gguf → resolve/main/x.gguf
                    url = s.replace("/blob/", "/resolve/");
                }
                ResolvedDownload r = new ResolvedDownload();
                r.url = url;
                r.filename = fn;
                r.note = "Direct GGUF URL";
                return r;
            }
            // HF page URL → org/repo
            String repo = parseHfRepo(s);
            if (repo != null) return resolveRepoToGguf(repo);
            throw new Exception("URL is not a .gguf file or Hugging Face model page");
        }

        // Plain model id: org/name
        if (s.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            return resolveRepoToGguf(s);
        }
        // Bare name → try common GGUF repo suffixes
        if (s.matches("[A-Za-z0-9_.-]+")) {
            throw new Exception("Use org/name form, e.g. Qwen/Qwen2.5-0.5B-Instruct-GGUF");
        }
        throw new Exception("Could not parse. Examples:\n"
            + "• Qwen/Qwen2.5-0.5B-Instruct-GGUF\n"
            + "• https://huggingface.co/…/file.gguf");
    }

    private static String parseHfRepo(String url) {
        // https://huggingface.co/org/repo ...
        try {
            String u = url;
            int i = u.indexOf("huggingface.co/");
            if (i < 0) return null;
            String rest = u.substring(i + "huggingface.co/".length());
            String[] parts = rest.split("/");
            if (parts.length < 2) return null;
            String org = parts[0];
            String repo = parts[1];
            if (org.isEmpty() || repo.isEmpty()) return null;
            if (org.equals("datasets") || org.equals("spaces")) return null;
            return org + "/" + repo;
        } catch (Exception e) {
            return null;
        }
    }

    private static ResolvedDownload resolveRepoToGguf(String repo) throws Exception {
        String id = repo;
        // If user pasted non-GGUF instruct repo, try -GGUF sibling
        ArrayList<String> candidates = new ArrayList<>();
        candidates.add(id);
        if (!id.toUpperCase(Locale.US).contains("GGUF")) {
            candidates.add(id + "-GGUF");
            candidates.add(id + "-gguf");
        }
        Exception last = null;
        for (String rid : candidates) {
            try {
                ResolvedDownload r = listRepoPickGguf(rid);
                if (r != null) return r;
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) throw last;
        throw new Exception("No .gguf files found in " + repo);
    }

    private static ResolvedDownload listRepoPickGguf(String repoId) throws Exception {
        // HF tree API
        String api = "https://huggingface.co/api/models/" + repoId + "/tree/main";
        HttpURLConnection conn = (HttpURLConnection) new URL(api).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Nanobot/1.0");
        conn.setRequestProperty("Accept", "application/json");
        int code = conn.getResponseCode();
        if (code == 301 || code == 302 || code == 307 || code == 308) {
            String loc = conn.getHeaderField("Location");
            conn.disconnect();
            conn = (HttpURLConnection) new URL(loc).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "Nanobot/1.0");
            code = conn.getResponseCode();
        }
        if (code != 200) {
            throw new Exception("HF repo " + repoId + " → HTTP " + code);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        conn.disconnect();
        String json = sb.toString();
        // Collect .gguf paths (simple scan — no full JSON parser dependency)
        ArrayList<String> ggufs = new ArrayList<>();
        int idx = 0;
        while (true) {
            int p = json.indexOf("\"path\":\"", idx);
            if (p < 0) break;
            p += 8;
            int e = json.indexOf('"', p);
            if (e < 0) break;
            String path = json.substring(p, e);
            if (path.toLowerCase(Locale.US).endsWith(".gguf")) ggufs.add(path);
            idx = e + 1;
        }
        if (ggufs.isEmpty()) throw new Exception("No GGUF in " + repoId);

        String pick = pickBestGguf(ggufs);
        ResolvedDownload r = new ResolvedDownload();
        r.filename = pick.contains("/") ? pick.substring(pick.lastIndexOf('/') + 1) : pick;
        r.url = "https://huggingface.co/" + repoId + "/resolve/main/" + pick;
        r.note = "From " + repoId + " · picked " + r.filename;
        return r;
    }

    /** Prefer Q4_K_M, then Q4_0 / Q5_K_M, avoid huge Q8/Q6 when smaller exist. */
    private static String pickBestGguf(List<String> ggufs) {
        String[] prefer = {
            "q4_k_m", "Q4_K_M", "q4_k_s", "Q4_K_S", "q4_0", "Q4_0",
            "q5_k_m", "Q5_K_M", "q3_k_m", "Q3_K_M", "iq4", "IQ4"
        };
        for (String tag : prefer) {
            for (String g : ggufs) {
                if (g.contains(tag)) return g;
            }
        }
        // Prefer shortest name among small-looking files
        String best = ggufs.get(0);
        for (String g : ggufs) {
            String low = g.toLowerCase(Locale.US);
            if (low.contains("q8") || low.contains("f16") || low.contains("f32")) continue;
            if (g.length() < best.length()) best = g;
        }
        return best;
    }

    public static boolean deleteModel(Context c, File f) {
        if (f == null) return false;
        String name = f.getName();
        String path = f.getAbsolutePath();
        // Collect every twin path (shared tmp + app external + private)
        ArrayList<String> paths = new ArrayList<>();
        paths.add(path);
        File part = new File(path + (path.endsWith(".partial") ? "" : ".partial"));
        if (!part.getAbsolutePath().equals(path)) paths.add(part.getAbsolutePath());
        for (File dir : modelDirs(c)) {
            File twin = new File(dir, name);
            if (!paths.contains(twin.getAbsolutePath())) paths.add(twin.getAbsolutePath());
            File twinPart = new File(dir, name.endsWith(".partial") ? name : name + ".partial");
            if (!paths.contains(twinPart.getAbsolutePath())) paths.add(twinPart.getAbsolutePath());
        }

        boolean anyGone = false;
        StringBuilder failed = new StringBuilder();
        for (String p : paths) {
            File file = new File(p);
            if (!file.exists()) continue;
            // 1) direct delete (works for app-owned files)
            if (file.delete()) {
                anyGone = true;
                continue;
            }
            // 2) chmod + delete (sometimes helps on group-writable)
            try {
                //noinspection ResultOfMethodCallIgnored
                file.setWritable(true, false);
                if (file.delete()) {
                    anyGone = true;
                    continue;
                }
            } catch (Exception ignored) {}
            // 3) peer shell rm (shell-owned under /data/local/tmp)
            try {
                if (NanobotRuntime.isPortListening()) {
                    PeerClient peer = new PeerClient(c);
                    // quote path safely for shell
                    String esc = p.replace("'", "'\\''");
                    org.json.JSONObject r = peer.shell(
                        "chmod 666 '" + esc + "' 2>/dev/null; rm -f '" + esc + "'; "
                            + "if [ ! -e '" + esc + "' ]; then echo DELETED; else echo FAIL; fi");
                    String out = r.optString("output", "");
                    if (out.contains("DELETED") || !file.exists()) {
                        anyGone = true;
                        continue;
                    }
                    failed.append(p).append(";");
                } else {
                    failed.append(p).append("(no peer);");
                }
            } catch (Exception e) {
                failed.append(p).append("(").append(e.getMessage()).append(");");
            }
        }
        // success if primary path is gone
        boolean ok = !f.exists() || anyGone && !new File(path).exists();
        if (!ok && anyGone) {
            // partial cleanup only
            ok = !f.exists();
        }
        if (ok || !f.exists()) {
            AccessLog.record(c, "gguf_delete", name);
            return true;
        }
        Log.w(TAG, "deleteModel failed: " + failed);
        return false;
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
