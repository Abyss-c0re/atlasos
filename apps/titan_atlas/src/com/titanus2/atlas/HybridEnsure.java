package com.titanus2.atlas;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Product path: keep Debian hybrid overlay enterable after boot / dirty unmount.
 *
 * <p>Root cause of "hybrid not loading again": image + lower survive reboot, but
 * loop/overlay drop and {@code atlas-hybrid-need-fsck} is set. Boot service and
 * UI used to only <i>poll</i> readiness without always calling
 * {@code atlas-hybrid.sh ensure} (e2fsck + remount). This class is the single
 * ensure entry from boot receiver, session FGS, MainActivity, and Settings.
 */
public final class HybridEnsure {
    private static final String TAG = "AtlasHybrid";
    private static final long DEBOUNCE_MS = 20_000L;
    private static final long FORCE_GAP_MS = 90_000L;
    private static final long BOOT_DELAY_MS = 8_000L;

    private static final AtomicBoolean inFlight = new AtomicBoolean(false);
    private static final AtomicLong lastStartMs = new AtomicLong(0L);
    private static final AtomicLong lastOkMs = new AtomicLong(0L);
    private static final Handler mainH = new Handler(Looper.getMainLooper());

    public interface Listener {
        /** Called on main thread when ensure finishes. */
        void onDone(boolean ready, long tookMs, String detail);
    }

    private HybridEnsure() {}

    /** Resolve product hybrid script (system first, then app files/bin tip). */
    public static String resolveScript(Context c) {
        String[] paths = {
            "/system/bin/atlas-hybrid.sh",
            "/system_ext/bin/atlas-hybrid.sh",
            "/product/bin/atlas-hybrid.sh",
            "/data/local/tmp/atlas-hybrid.sh"
        };
        for (String p : paths) {
            File f = new File(p);
            if (f.isFile() && f.length() > 100) return p;
        }
        if (c != null) {
            File app = new File(NativeBin.binDir(c), "atlas-hybrid.sh");
            if (app.isFile() && app.length() > 100) return app.getAbsolutePath();
        }
        return null;
    }

    /** True when privileged hybrid is wanted and overlay is not enterable. */
    public static boolean needsEnsure(Context c) {
        if (c == null) return false;
        if (!AtlasPrefs.privilegedHybrid(c)) return false;
        return !NativeBin.hybridRootfsReady();
    }

    /**
     * Product Deb enter path: atlas-enter client + atlas-enterd (init root).
     * Prefer live socket; fall back to binaries present (daemon starts at boot).
     */
    public static boolean systemEnterAvailable() {
        try {
            if (new File("/data/local/tmp/atlas-enter.sock").exists()) return true;
            if (new File("/dev/socket/atlasenter").exists()) return true;
            File client = new File("/system/bin/atlas-enter");
            File daemon = new File("/system/bin/atlas-enterd");
            return client.isFile() && client.canExecute()
                && daemon.isFile() && daemon.canExecute();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Write request for init atlas-hybrid-watch / boot oneshot (rootless UI bridge).
     * Does not require app su.
     */
    public static boolean requestSystemEnsure() {
        try {
            java.io.FileWriter w = new java.io.FileWriter(
                "/data/local/tmp/atlas-hybrid-ensure-request", false);
            w.write("ts=" + System.currentTimeMillis() + " src=app\n");
            w.close();
        } catch (Exception e) {
            return false;
        }
        // Best-effort ctl.start (shell/priv may allow; ignore failures)
        try {
            new ProcessBuilder("/system/bin/setprop", "ctl.start", "atlas-hybrid-boot")
                .redirectErrorStream(true).start();
        } catch (Exception ignored) {
        }
        try {
            new ProcessBuilder("/system/bin/setprop", "sys.atlas.hybrid.ensure", "1")
                .redirectErrorStream(true).start();
        } catch (Exception ignored) {
        }
        return true;
    }

    public static boolean requestSystemReset() {
        try {
            java.io.FileWriter w = new java.io.FileWriter(
                "/data/local/tmp/atlas-hybrid-reset-request", false);
            w.write("ts=" + System.currentTimeMillis() + "\n");
            w.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Product rootless: true when Deb can be entered or ensure can be requested.
     * Prefer /system/bin/atlas-enter; Magisk/KSU is lab fallback only.
     */
    public static boolean appCanElevate() {
        if (systemEnterAvailable()) return true;
        for (String p : REAL_SU_PATHS) {
            try {
                File f = new File(p);
                if (f.isFile() && f.canExecute()) return true;
            } catch (Exception ignored) {
            }
        }
        // System request bridge always available on product ROM with hybrid-watch
        if (new File("/system/bin/atlas-hybrid-watch.sh").isFile()
                || new File("/system/bin/atlas-hybrid-boot.sh").isFile()) {
            return true;
        }
        try {
            Process p = new ProcessBuilder(
                "/data/adb/ksu/bin/su", "0", "true").start();
            boolean ok = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                && p.exitValue() == 0;
            if (ok) return true;
        } catch (Exception ignored) {
        }
        try {
            Process p = new ProcessBuilder("/system/bin/su", "0", "true").start();
            return p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** True when Deb PTY can pivot (enter helper or lab su). */
    public static boolean canEnterDeb() {
        if (systemEnterAvailable()) return true;
        for (String p : REAL_SU_PATHS) {
            try {
                File f = new File(p);
                if (f.isFile() && f.canExecute()) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * Boot / package-replace / unlock: ensure on a worker thread (not main-Handler
     * delay). Receiver processes die before postDelayed fires; sleep lives in
     * the ensure thread so hybrid remount still happens after FBE unlock.
     */
    public static void kickAfterBoot(Context c) {
        if (c == null) return;
        final Context app = c.getApplicationContext();
        Log.i(TAG, "boot kick scheduled");
        // Leave a request marker for system atlas-hybrid-boot / operators.
        try {
            java.io.FileWriter w = new java.io.FileWriter(
                "/data/local/tmp/atlas-hybrid-ensure-request", false);
            w.write("ts=" + System.currentTimeMillis() + " src=app-boot\n");
            w.close();
        } catch (Exception ignored) {
        }
        new Thread(() -> {
            try {
                Thread.sleep(BOOT_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            try {
                if (!AtlasPrefs.privilegedHybrid(app)) {
                    Log.i(TAG, "boot kick skip — hybrid opt-out");
                    return;
                }
                // Always run ensure on boot path. cmd_ensure is cheap when already up
                // ("ensure: already up"); skipping on a false-ready check was the bug
                // that left Deb dark after need-fsck / dirty unmount.
                boolean before = NativeBin.hybridRootfsReady();
                Log.i(TAG, "boot kick — ensure (ready_before=" + before + ")");
                String detail = runEnsureBlocking(app);
                boolean ok = NativeBin.hybridRootfsReady();
                if (ok) lastOkMs.set(System.currentTimeMillis());
                try {
                    NativeBin.writePlaneStatus(app,
                        AtlasPrefs.privilegedHybrid(app), ok);
                } catch (Exception ignored) {
                }
                if (ok) {
                    //noinspection ResultOfMethodCallIgnored
                    new File("/data/local/tmp/atlas-hybrid-ensure-request").delete();
                }
                Log.i(TAG, "boot kick done ok=" + ok
                    + " detail_tail=" + tail(detail, 240));
            } catch (Exception e) {
                Log.w(TAG, "boot kick failed: " + e.getMessage());
            }
        }, "atlas-hybrid-boot-kick").start();
    }

    private static String tail(String s, int n) {
        if (s == null) return "";
        if (s.length() <= n) return s.replace('\n', ' ');
        return s.substring(s.length() - n).replace('\n', ' ');
    }

    /**
     * Periodic heal from Authentication Agent FGS — recovers post-crash dirty flag
     * without requiring the user to open Atlas.
     */
    public static void healthTick(Context c) {
        if (c == null) return;
        if (!AtlasPrefs.privilegedHybrid(c)) return;
        if (NativeBin.hybridRootfsReady()) return;
        // Product: never spam su from FGS when app cannot elevate
        if (!appCanElevate()) return;
        // Avoid hammering while ensure is running
        if (inFlight.get()) return;
        long now = System.currentTimeMillis();
        if (now - lastStartMs.get() < DEBOUNCE_MS) return;
        Log.i(TAG, "health tick — hybrid down, ensure");
        ensureAsync(c.getApplicationContext(), false, null);
    }

    /**
     * @param force ignore short debounce (user toggle / explicit Settings)
     * @param listener optional UI callback (main thread)
     */
    public static void ensureAsync(Context c, boolean force, Listener listener) {
        if (c == null) {
            if (listener != null) {
                mainH.post(() -> listener.onDone(false, 0, "no-context"));
            }
            return;
        }
        final Context app = c.getApplicationContext();
        // Rootless product: if overlay already up, OK; else skip su thrash
        if (NativeBin.hybridRootfsReady()) {
            if (listener != null) {
                mainH.post(() -> listener.onDone(true, 0, "already-ready"));
            }
            return;
        }
        if (!appCanElevate()) {
            Log.i(TAG, "ensure skip — app cannot elevate (system mount or Settings)");
            if (listener != null) {
                mainH.post(() -> listener.onDone(false, 0, "no-app-root"));
            }
            return;
        }
        long now = System.currentTimeMillis();
        long gap = force ? FORCE_GAP_MS : DEBOUNCE_MS;
        if (inFlight.get() && (now - lastStartMs.get()) < gap) {
            Log.i(TAG, "ensure skip — in flight");
            if (listener != null && NativeBin.hybridRootfsReady()) {
                mainH.post(() -> listener.onDone(true, 0, "already-ready-inflight"));
            }
            return;
        }
        if (!force && NativeBin.hybridRootfsReady()) {
            lastOkMs.set(now);
            if (listener != null) {
                mainH.post(() -> listener.onDone(true, 0, "already-ready"));
            }
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            // Second concurrent caller while first still runs
            if (!force) {
                if (listener != null) {
                    mainH.post(() -> listener.onDone(
                        NativeBin.hybridRootfsReady(), 0, "concurrent"));
                }
                return;
            }
            // force: allow only if previous started long ago (stuck)
            if ((now - lastStartMs.get()) < FORCE_GAP_MS) {
                if (listener != null) {
                    mainH.post(() -> listener.onDone(
                        NativeBin.hybridRootfsReady(), 0, "force-debounced"));
                }
                return;
            }
            inFlight.set(true);
        }
        lastStartMs.set(now);
        final long start = now;
        new Thread(() -> {
            String detail;
            boolean ok = false;
            try {
                detail = runEnsureBlocking(app);
                ok = NativeBin.hybridRootfsReady();
                if (ok) lastOkMs.set(System.currentTimeMillis());
                try {
                    NativeBin.writePlaneStatus(app,
                        AtlasPrefs.privilegedHybrid(app), ok);
                } catch (Exception ignored) {
                }
                Log.i(TAG, "ensure done ok=" + ok + " detail=" + detail);
            } catch (Exception e) {
                detail = e.getMessage() != null ? e.getMessage() : "err";
                Log.w(TAG, "ensure failed: " + detail);
            } finally {
                inFlight.set(false);
            }
            final boolean ready = ok;
            final String d = detail != null ? detail : "";
            final long took = System.currentTimeMillis() - start;
            if (listener != null) {
                mainH.post(() -> listener.onDone(ready, took, d));
            }
        }, "atlas-hybrid-ensure").start();
    }

    /**
     * Blocking ensure for Settings / privilege toggle. Returns null on success,
     * short error string on failure.
     */
    public static String ensureBlocking(Context c) {
        if (c == null) return "no-context";
        if (NativeBin.hybridRootfsReady()) return null;
        if (!appCanElevate()) {
            return "hybrid↓ · plane needs system prepare (no app root)";
        }
        String detail = runEnsureBlocking(c.getApplicationContext());
        if (NativeBin.hybridRootfsReady()) return null;
        if (detail == null || detail.isEmpty()) return "ensure produced no overlay";
        if (detail.length() > 180) detail = detail.substring(detail.length() - 180);
        return detail;
    }

    /**
     * Rebuild hybrid plane.
     * @param preserve true = keep image + upper (apt installs / home in overlay);
     *                 false = destroy image and re-bootstrap from seed.
     * @return null on success, error tail otherwise.
     */
    public static String rebuildBlocking(Context c, boolean preserve) {
        if (c == null) return "no-context";
        final Context app = c.getApplicationContext();
        String script = resolveScript(app);
        if (script == null) return "atlas-hybrid.sh missing";
        File home = NativeBin.home(app);
        String homePath = home != null
            ? home.getAbsolutePath()
            : "/data/data/com.titanus2.atlas/files";
        int sizeG = AtlasPrefs.hybridSizeG(app);
        String mode = preserve ? "--preserve" : "--wipe";
        String cmd = "export HOME='" + homePath + "' ATLAS_HOME='" + homePath + "' "
            + "ATLAS_AUTO_BOOTSTRAP=1 ATLAS_HYBRID_SIZE_G=" + sizeG + "; "
            + "/system/bin/sh '" + script + "' rebuild " + mode + " 2>&1; "
            + "echo EXIT:$?; "
            + "grep -q ' /data/local/atlas-hybrid/merge ' /proc/mounts "
            + "&& test -x /data/local/atlas-hybrid/merge/bin/bash "
            + "-o -x /data/local/atlas-hybrid/merge/usr/bin/bash "
            + "&& echo HYBRID_READY || echo HYBRID_DOWN";
        StringBuilder out = new StringBuilder();
        String[] suTry = {
            "/data/adb/ksu/bin/su",
            "/debug_ramdisk/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "su"
        };
        boolean ran = false;
        for (String suBin : suTry) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    suBin, "0", "sh", "-c", cmd);
                pb.redirectErrorStream(true);
                pb.environment().put("PATH",
                    "/system/bin:/system/xbin:/vendor/bin:/data/adb/ksu/bin:/sbin");
                Process p = pb.start();
                drain(p.getInputStream(), out);
                try {
                    p.waitFor();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                ran = true;
                if (out.indexOf("HYBRID_READY") >= 0
                    || NativeBin.hybridRootfsReady()) {
                    break;
                }
                String so = out.toString();
                if (so.contains("No such file")
                    || so.contains("error=2")
                    || so.contains("inaccessible or not found")
                    || so.contains("Permission denied")
                    || so.contains("not found")) {
                    continue;
                }
                break;
            } catch (Exception e) {
                out.append("\n").append(suBin).append(": ")
                    .append(e.getMessage() != null ? e.getMessage() : "err");
            }
        }
        if (!ran || (out.indexOf("HYBRID_READY") < 0
                && !NativeBin.hybridRootfsReady())) {
            // Fallback: preserve → ensure; wipe → destroy + ensure
            String fb = preserve
                ? "export ATLAS_FORCE_FSCK=1 ATLAS_AUTO_BOOTSTRAP=1; "
                    + "/system/bin/sh '" + script + "' ensure 2>&1"
                : "export ATLAS_AUTO_BOOTSTRAP=1; "
                    + "/system/bin/sh '" + script + "' destroy 2>&1; "
                    + "/system/bin/sh '" + script + "' ensure 2>&1";
            for (String suBin : suTry) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                        suBin, "0", "sh", "-c", fb);
                    pb.redirectErrorStream(true);
                    pb.environment().put("PATH",
                        "/system/bin:/system/xbin:/vendor/bin:/data/adb/ksu/bin:/sbin");
                    Process p = pb.start();
                    drain(p.getInputStream(), out);
                    p.waitFor();
                    break;
                } catch (Exception e2) {
                    out.append("\n").append(suBin).append("-fb: ")
                        .append(e2.getMessage() != null ? e2.getMessage() : "err");
                }
            }
        }
        boolean ok = NativeBin.hybridRootfsReady()
            || out.indexOf("HYBRID_READY") >= 0;
        try {
            NativeBin.writePlaneStatus(app,
                AtlasPrefs.privilegedHybrid(app), ok);
        } catch (Exception ignored) {
        }
        if (ok) return null;
        String d = out.toString();
        if (d.length() > 200) d = d.substring(d.length() - 200);
        return d.isEmpty() ? "rebuild failed" : d;
    }

    /**
     * Grow hybrid image to prefs size (or explicit GiB). Shrink refused by script.
     * @return null on success, error tail otherwise.
     */
    public static String resizeBlocking(Context c, int sizeG) {
        if (c == null) return "no-context";
        final Context app = c.getApplicationContext();
        String script = resolveScript(app);
        if (script == null) return "atlas-hybrid.sh missing";
        int g = Math.max(2, Math.min(64, sizeG));
        File home = NativeBin.home(app);
        String homePath = home != null
            ? home.getAbsolutePath()
            : "/data/data/com.titanus2.atlas/files";
        String cmd = "export HOME='" + homePath + "' ATLAS_HOME='" + homePath + "' "
            + "ATLAS_HYBRID_SIZE_G=" + g + "; "
            + "export PATH=/system/bin:/system/xbin:/vendor/bin:/data/adb/ksu/bin:$PATH; "
            + "/system/bin/sh '" + script + "' resize " + g + " 2>&1; "
            + "echo EXIT:$?; "
            + "grep -q ' /data/local/atlas-hybrid/merge ' /proc/mounts "
            + "&& test -x /data/local/atlas-hybrid/merge/bin/bash "
            + "-o -x /data/local/atlas-hybrid/merge/usr/bin/bash "
            + "&& echo HYBRID_READY || echo HYBRID_DOWN";
        StringBuilder out = new StringBuilder();
        String su = resolveRealSu();
        try {
            ProcessBuilder pb = new ProcessBuilder(su, "0", "sh", "-c", cmd);
            pb.redirectErrorStream(true);
            pb.environment().put("PATH",
                "/system/bin:/system/xbin:/vendor/bin:/data/adb/ksu/bin:/sbin");
            Process p = pb.start();
            drain(p.getInputStream(), out);
            p.waitFor();
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : "resize su failed";
        }
        String so = out.toString();
        if (so.contains("RESIZE_REFUSE_SHRINK")) {
            return "cannot shrink — use Wipe hybrid (data loss) for smaller size";
        }
        if (so.contains("RESIZE_OK") || so.contains("RESIZE_SAME")
            || so.contains("HYBRID_READY") || NativeBin.hybridRootfsReady()) {
            return null;
        }
        if (so.length() > 240) so = so.substring(so.length() - 240);
        return so.isEmpty() ? "resize failed" : so;
    }

    /** Actual hybrid image GiB (ceil), or 0 if missing. */
    public static int actualImageSizeG() {
        File img = new File("/data/local/atlas-hybrid.img");
        if (!img.isFile()) return 0;
        long sz = img.length();
        if (sz <= 0) return 0;
        return (int) ((sz + 1073741823L) / 1073741824L);
    }

    /**
     * Absolute real root su paths. KernelSU lives under {@code /data/adb/ksu/bin/su}
     * on rootless GSI+kept-kernel; Magisk/system paths are fallbacks.
     * Never prefer app {@code files/bin/su} (Authentication Agent client — not real root).
     */
    private static final String[] REAL_SU_PATHS = {
        "/data/adb/ksu/bin/su",
        "/debug_ramdisk/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su"
    };

    /** First existing absolute real-su path, or {@code "su"} for PATH last-resort. */
    public static String resolveRealSu() {
        for (String p : REAL_SU_PATHS) {
            File f = new File(p);
            // isFile() can throw/return false under SELinux even when path is real;
            // still try execute — missing path fails fast with ENOENT.
            if (f.isFile()) return p;
        }
        // KSU often mounts su with 0700 under /data/adb — app may not stat it.
        // Prefer the KSU absolute path before bare PATH "su" (agent client trap).
        return "/data/adb/ksu/bin/su";
    }

    private static String runEnsureBlocking(Context app) {
        String script = resolveScript(app);
        if (script == null) {
            return "atlas-hybrid.sh missing";
        }
        // Product rootless: request system init ensure, poll status (no Magisk).
        StringBuilder out = new StringBuilder();
        if (requestSystemEnsure()) {
            out.append("system-request=1\n");
            for (int i = 0; i < 40; i++) {
                if (NativeBin.hybridRootfsReady()) {
                    out.append("HYBRID_READY via=system-watch i=").append(i);
                    return out.toString();
                }
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            out.append("system-poll timeout\n");
        }
        // Lab fallback: real su ensure (not product claim)
        File home = NativeBin.home(app);
        String homePath = home != null
            ? home.getAbsolutePath()
            : "/data/data/com.titanus2.atlas/files";
        int sizeG = AtlasPrefs.hybridSizeG(app);
        String cmd = "export HOME='" + homePath + "' ATLAS_HOME='" + homePath + "' "
            + "ATLAS_AUTO_BOOTSTRAP=1 ATLAS_HYBRID_SIZE_G=" + sizeG + "; "
            + "export PATH=/system/bin:/system/xbin:/vendor/bin:/data/adb/ksu/bin:$PATH; "
            + "/system/bin/sh '" + script + "' ensure 2>&1; "
            + "echo EXIT:$?; "
            + "grep -q ' /data/local/atlas-hybrid/merge ' /proc/mounts "
            + "&& test -x /data/local/atlas-hybrid/merge/bin/bash "
            + "-o -x /data/local/atlas-hybrid/merge/usr/bin/bash "
            + "&& echo HYBRID_READY || echo HYBRID_DOWN";
        String[][] attempts = {
            { "/data/adb/ksu/bin/su", "0", "sh", "-c", cmd },
            { "/debug_ramdisk/su", "0", "sh", "-c", cmd },
            { "/system/bin/su", "0", "sh", "-c", cmd },
            { "/system/xbin/su", "0", "sh", "-c", cmd },
            { "/sbin/su", "0", "sh", "-c", cmd },
            { "su", "0", "sh", "-c", cmd }
        };
        Exception last = null;
        for (String[] argv : attempts) {
            try {
                ProcessBuilder pb = new ProcessBuilder(argv);
                pb.redirectErrorStream(true);
                pb.environment().put("PATH",
                    "/system/bin:/system/xbin:/vendor/bin:/data/adb/ksu/bin:/sbin");
                Process p = pb.start();
                drain(p.getInputStream(), out);
                int code;
                try {
                    code = p.waitFor();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    code = -1;
                }
                out.append("\nsu_exit=").append(code).append(" via=").append(argv[0]);
                if (out.indexOf("HYBRID_READY") >= 0
                    || NativeBin.hybridRootfsReady()) {
                    return out.toString();
                }
                String so = out.toString();
                if (so.contains("No such file")
                    || so.contains("error=2")
                    || so.contains("inaccessible or not found")
                    || so.contains("Permission denied")
                    || so.contains("not found")
                    || so.contains("Can't execute")
                    || code == 127
                    || code == 126) {
                    continue;
                }
                return out.toString();
            } catch (Exception e) {
                last = e;
                out.append("\n").append(argv[0]).append(": ")
                    .append(e.getMessage() != null ? e.getMessage() : "err");
            }
        }
        if (last != null && out.length() == 0) {
            return last.getMessage() != null ? last.getMessage() : "su-exec-failed";
        }
        if (out.indexOf("HYBRID_READY") < 0 && !NativeBin.hybridRootfsReady()) {
            // Short mono fact — no KernelSU install essay (PRODUCT_UX / rootless product)
            out.append("\nhybrid↓ · no elevate / ensure failed");
        }
        return out.toString();
    }

    private static void drain(InputStream in, StringBuilder into) {
        if (in == null) return;
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0 && into != null) {
                    // keep last ~4k for error tails
                    into.append(new String(buf, 0, n));
                    if (into.length() > 8000) {
                        into.delete(0, into.length() - 4000);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
