package com.titanus2.nanobot;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Boot belt + optional user GGUF on userdata. Never reads /system models.
 */
public final class OfflineBootstrap {
    private static final String TAG = "OfflineBootstrap";
    public static final String BUNDLED_MODEL = "gemma-3-270m-it-Q4_K_M.gguf";
    public static final String SHARED_MODEL_DIR = "/data/local/tmp/nanobot_models";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile String LAST_STATUS = "idle";

    private OfflineBootstrap() {}

    public static String lastStatus() {
        return LAST_STATUS;
    }

    /**
     * Prefer tip with 1.9 leave-alone marker over stale system oneshot.
     * Cool land stages {@code /data/local/tmp/titan2-offline-nanobot.sh};
     * hybrid flash later aligns /system.
     */
    static File pickOfflineScript(File tip, File system) {
        boolean tipOk = tip != null && tip.isFile();
        boolean sysOk = system != null && system.isFile();
        if (tipOk && scriptHasLeaveAlone(tip)) return tip;
        if (sysOk && scriptHasLeaveAlone(system)) return system;
        if (tipOk) return tip;
        if (sysOk) return system;
        return null;
    }

    private static boolean scriptHasLeaveAlone(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            if (n <= 0) return false;
            String s = new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
            return s.contains("leave product env") || s.contains("1.9 residual");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Fire-and-forget from BootReceiver / NanobotService. */
    public static void ensureAsync(Context c) {
        if (c == null) return;
        final Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        if (!RUNNING.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                ensureOfflineReady(app);
            } catch (Throwable t) {
                LAST_STATUS = "error: " + t.getMessage();
                Log.e(TAG, "ensure: " + t.getMessage());
                AccessLog.record(app, "offline_boot_fail", LAST_STATUS);
            } finally {
                RUNNING.set(false);
            }
        }, "offline-bootstrap").start();
    }

    /**
     * Blocking: make offline chat work (Wi‑Fi off). Returns null on success.
     */
    public static String ensureOfflineReady(Context c) {
        LAST_STATUS = "extract_engine";
        appendBootLog(c, "offline bootstrap start");

        // Prefer tip offline script when it carries product-env leave-alone (1.9).
        // Residual: always preferred /system first so hybrid 1.8- clobber script
        // won over staged /data/local/tmp tip after cool land (Grok env race).
        try {
            File off = new File("/system/bin/titan2-offline-nanobot.sh");
            File offTmp = new File("/data/local/tmp/titan2-offline-nanobot.sh");
            File script = pickOfflineScript(offTmp, off);
            if (script != null) {
                ProcessBuilder pb = new ProcessBuilder("sh", script.getAbsolutePath());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.waitFor();
                appendBootLog(c, "ran " + script.getAbsolutePath() + " rc=" + p.exitValue());
                if (LlamaRuntime.isServerUp() && NanobotRuntime.isPortListening()) {
                    LAST_STATUS = "ready via offline script " + script.getName();
                    appendBootLog(c, LAST_STATUS);
                    return null;
                }
            }
        } catch (Throwable t) {
            appendBootLog(c, "system offline script: " + t.getMessage());
        }

        // 1) Engine: prefer system/tmp (already on flash path) — skip slow re-extract
        long t0 = System.currentTimeMillis();
        LlamaRuntime.Probe eng = LlamaRuntime.probeFast(c);
        if (!eng.present) {
            LlamaRuntime.extractBundledEngine(c);
            eng = LlamaRuntime.probe(c);
        }
        appendBootLog(c, "engine " + (eng.present ? eng.source + " " + eng.binary : "MISSING")
            + " " + (System.currentTimeMillis() - t0) + "ms");
        if (!eng.present) {
            LAST_STATUS = "engine_missing";
            return "Offline engine missing";
        }

        // 2) Model: shared tmp / system / app
        LAST_STATUS = "ensure_model";
        File model = ensureBundledModel(c);
        if (model == null || !LlamaManager.isComplete(model)) {
            LAST_STATUS = "model_missing";
            appendBootLog(c, "model missing — expected " + BUNDLED_MODEL);
            return "Offline model missing (" + BUNDLED_MODEL + ")";
        }
        PrivacyPrefs.setLocalLlamaEnabled(c, true);
        PrivacyPrefs.setSelectedLocalModelPath(c, model.getAbsolutePath());
        PrivacyPrefs.setServiceEnabled(c, true);
        appendBootLog(c, "model " + model.getAbsolutePath() + " (" + model.length() + " bytes)");

        // 3) Start llama-server if needed (lean ctx for 270M)
        LAST_STATUS = "start_llama";
        t0 = System.currentTimeMillis();
        String running = LlamaRuntime.isServerUp() ? LlamaRuntime.runningModelPath() : null;
        boolean need = !LlamaRuntime.isServerUp()
            || running == null
            || !(running.contains(model.getName()) || running.equals(model.getAbsolutePath()));
        if (need) {
            String err = LlamaRuntime.start(c, model, LlamaRuntime.recommendedCtx(model, 512));
            if (err != null && !LlamaRuntime.isServerUp()) {
                LAST_STATUS = "llama_fail: " + err;
                appendBootLog(c, LAST_STATUS);
                return err;
            }
        }
        // Health poll 100ms — Gemma 270M loads in ~2–3s on Titan
        for (int i = 0; i < 80 && !LlamaRuntime.isServerUp(); i++) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        appendBootLog(c, "llama "
            + (LlamaRuntime.isServerUp() ? "UP" : "DOWN")
            + " " + (System.currentTimeMillis() - t0) + "ms");
        if (!LlamaRuntime.isServerUp()) {
            LAST_STATUS = "llama_timeout";
            return "llama-server not ready";
        }

        // 4) Peer offline backend
        LAST_STATUS = "start_peer";
        NanobotRuntime.ensureSharedPeer(c);
        try {
            PeerClient peer = new PeerClient(c);
            // Attach backend even if peer just came up
            for (int i = 0; i < 30 && !NanobotRuntime.isPortListening(); i++) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
            if (NanobotRuntime.isPortListening()) {
                LlamaRuntime.markTinyModelNoTools(c, model.getAbsolutePath());
                LlamaRuntime.applyAsNanobotBackend(c, peer, model);
                // Seed default provider to local
                ProviderProfile loc = ProviderStore.get(c, "llama_local");
                if (loc != null) {
                    loc.enabled = true;
                    loc.model = model.getAbsolutePath();
                    loc.baseUrl = LlamaRuntime.baseUrl();
                    loc.roleDefault = true; // primary when offline / boot
                    loc.localOnly = true;
                    ProviderStore.upsert(c, loc);
                }
            }
        } catch (Exception e) {
            appendBootLog(c, "peer backend: " + e.getMessage());
        }

        LAST_STATUS = "ready offline model=" + model.getName();
        appendBootLog(c, LAST_STATUS);
        AccessLog.record(c, "offline_boot_ok", LAST_STATUS);
        return null;
    }

    /**
     * User-installed GGUF on userdata only. Never {@code /system} — GGUF is not ROM.
     */
    public static File ensureBundledModel(Context c) {
        File shared = new File(SHARED_MODEL_DIR, BUNDLED_MODEL);
        if (LlamaManager.isComplete(shared)) return shared;

        File app = new File(c.getFilesDir(), "models/" + BUNDLED_MODEL);
        if (LlamaManager.isComplete(app)) return app;

        for (File f : LlamaManager.listLocalGguf(c)) {
            if (LlamaManager.isComplete(f)) return f;
        }
        return null;
    }

    public static void appendBootLog(Context c, String line) {
        try {
            File f = new File(c.getFilesDir(), "offline_boot.log");
            String row = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
                + " " + line + "\n";
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(row.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
        Log.i(TAG, line);
    }
}
