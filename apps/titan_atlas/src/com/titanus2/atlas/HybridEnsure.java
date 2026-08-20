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

    /** Tip script first — ROM copy may lack add-user. */
    public static String resolveScriptTip(Context c) {
        String[] paths = new String[0];
        if (c != null) {
            paths = new String[] {
                new File(NativeBin.binDir(c), "atlas-hybrid.sh").getAbsolutePath(),
                "/data/local/tmp/atlas-hybrid.sh",
                "/system/bin/atlas-hybrid.sh"
            };
        } else {
            paths = new String[] {
                "/data/local/tmp/atlas-hybrid.sh",
                "/system/bin/atlas-hybrid.sh"
            };
        }
        for (String p : paths) {
            File f = new File(p);
            if (f.isFile() && f.length() > 100) return p;
        }
        return resolveScript(c);
    }

    /** True when privileged hybrid is wanted and overlay is not enterable. */
    public static boolean needsEnsure(Context c) {
        if (c == null) return false;
        if (!AtlasPrefs.privilegedHybrid(c)) return false;
        return !NativeBin.hybridRootfsReady();
    }

    /**
     * True only when atlas-enterd is listening.
     * Product listen is abstract {@code @atlasenter}. Filesystem socks are extras.
     * priv_app often cannot stat {@code /data/local/tmp/atlas-enter.sock}
     * (SELinux) — treating that as "enterd down" forced Android toybox (1746Z).
     */
    public static boolean enterdListening() {
        if (abstractEnterdListed()) return true;
        try {
            if (new File("/dev/socket/atlasenter").exists()) return true;
            if (new File("/data/local/tmp/atlas-enter.sock").exists()) return true;
        } catch (Exception ignored) {
        }
        return connectAbstractEnterd();
    }

    /** {@code /proc/net/unix} lists abstract sockets as {@code @atlasenter}. */
    public static boolean abstractEnterdListed() {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.FileReader("/proc/net/unix"));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("@atlasenter")) return true;
                }
            } finally {
                br.close();
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Connect probe — last resort. Closes immediately; enterd child EOFs. */
    public static boolean connectAbstractEnterd() {
        android.net.LocalSocket s = null;
        try {
            s = new android.net.LocalSocket();
            s.connect(new android.net.LocalSocketAddress(
                "atlasenter", android.net.LocalSocketAddress.Namespace.ABSTRACT),
                250);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) {
                try { s.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Product Deb enter path: live atlas-enterd socket only.
     */
    public static boolean systemEnterAvailable() {
        return enterdListening();
    }

    /** Ask init / watch to start atlas-enterd (rootless UI bridge). */
    public static boolean requestEnterd() {
        try {
            new ProcessBuilder("/system/bin/setprop", "sys.atlas.enterd", "1")
                .redirectErrorStream(true).start();
        } catch (Exception ignored) {
        }
        try {
            new ProcessBuilder("/system/bin/setprop", "ctl.start", "atlas-enterd")
                .redirectErrorStream(true).start();
        } catch (Exception ignored) {
        }
        try {
            new ProcessBuilder("/system/bin/setprop", "ctl.start", "atlas-hybrid-watch")
                .redirectErrorStream(true).start();
        } catch (Exception ignored) {
        }
        try {
            new ProcessBuilder("/system/bin/setprop", "sys.atlas.hybrid", "1")
                .redirectErrorStream(true).start();
        } catch (Exception ignored) {
        }
        return true;
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
        requestEnterd();
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
        // Product ROM: atlas-enter talks @atlasenter. Do not require a
        // filesystem sock the app UID cannot stat.
        if (new File("/system/bin/atlas-enter").isFile()) return true;
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
                AtlasPrefs.restoreHybridAfterCeWipe(app);
                if (!AtlasPrefs.privilegedHybrid(app)
                        && !NativeBin.debianRootPresent()) {
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
                if (ok) ensureLiveUid(app);
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
    /**
     * First launch after Atlas Clear data (no reboot). Overlay / LP / home
     * survived; CE prefs did not. Remount + enterd + live uid — never bootstrap.
     */
    public static void kickAfterCeWipe(Context c) {
        if (c == null) return;
        final Context app = c.getApplicationContext();
        try {
            java.io.FileWriter w = new java.io.FileWriter(
                "/data/local/tmp/atlas-hybrid-ensure-request", false);
            w.write("ts=" + System.currentTimeMillis() + " src=app-ce-wipe\n");
            w.close();
        } catch (Exception ignored) {
        }
        requestSystemEnsure();
        requestEnterd();
        ensureLiveUidAsync(app);
        new Thread(() -> {
            try {
                Log.i(TAG, "ce-wipe kick — ensure surviving Deb");
                runEnsureBlocking(app);
                ensureLiveUid(app);
                boolean ok = NativeBin.hybridRootfsReady();
                Log.i(TAG, "ce-wipe kick done ready=" + ok
                    + " enterd=" + enterdListening());
            } catch (Exception e) {
                Log.w(TAG, "ce-wipe kick failed: " + e.getMessage());
            }
        }, "atlas-ce-wipe-kick").start();
    }

    public static void healthTick(Context c) {
        if (c == null) return;
        if (!AtlasPrefs.privilegedHybrid(c) && !NativeBin.debianRootPresent()) return;
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
                if (ok) ensureLiveUid(app);
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
    /** Super LP Debian is product. Never treat loop image wipe as legal. */
    public static boolean debianLpLive() {
        return new File("/dev/block/mapper/atlas_linux_a").exists()
            || new File(NativeBin.LP_MNT + "/etc/debian_version").isFile();
    }

    public static String rebuildBlocking(Context c, boolean preserve) {
        if (c == null) return "no-context";
        if (!preserve && debianLpLive()) return "error=lp-live";
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
            + "ATLAS_DROP_UID=" + android.os.Process.myUid() + " "
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

    /**
     * Ensure Debian passwd has the live Atlas app uid (ssh/sudo/apt).
     * Runs off the UI thread. Safe if hybrid is down (no-op).
     */
    public static void ensureLiveUidAsync(Context c) {
        if (c == null) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> ensureLiveUid(app), "atlas-ensure-uid").start();
    }

    /** Short Settings fact: {@code atlas uid=10101} or {@code missing uid=10101}. */
    public static String liveUidStatus() {
        int uid = android.os.Process.myUid();
        String row = passwdRowForUid(NativeBin.LP_MNT + "/etc/passwd", uid);
        if (row == null) {
            row = passwdRowForUid("/data/local/atlas-hybrid/merge/etc/passwd", uid);
        }
        if (row == null) return "missing uid=" + uid;
        int colon = row.indexOf(':');
        String name = colon > 0 ? row.substring(0, colon) : "atlas";
        return name + " uid=" + uid;
    }

    public static boolean liveUidPresent() {
        int uid = android.os.Process.myUid();
        return passwdHasUid(NativeBin.LP_MNT + "/etc/passwd", uid)
            || passwdHasUid("/data/local/atlas-hybrid/merge/etc/passwd", uid);
    }

    /**
     * Create Debian {@code atlas} at the Android app uid. Idempotent.
     * @return toast-sized fact ({@code atlas uid=N} or error).
     */
    public static String createLiveUid(Context c) {
        boolean ok = ensureLiveUid(c);
        String st = liveUidStatus();
        if (ok) return st;
        return "create failed · " + st;
    }

    public static boolean validUserName(String name) {
        if (name == null) return false;
        return name.matches("^[a-z_][a-z0-9_-]{0,31}$");
    }

    /** Shared Atlas identity: Debian login + Android/Deb permission flags. */
    public static final class DebianUser {
        public String name = "";
        public int uid;
        public String home = "";
        public boolean passSet;
        public boolean sudo;
        public boolean android = true;
        public boolean debian = true;
        public boolean session;
        public String fact() {
            return name + " uid=" + uid
                + (session ? " session" : "")
                + " A=" + (android ? "on" : "off")
                + " D=" + (debian ? "on" : "off")
                + " sudo=" + (sudo ? "on" : "off")
                + " pass=" + (passSet ? "set" : "lock");
        }
    }

    /** Human logins on the LP (uid ≥ 1000). */
    public static String listDebianUsers() {
        java.util.List<DebianUser> all = loadDebianUsers(null);
        if (all.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (DebianUser u : all) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(u.name).append(" uid=").append(u.uid);
        }
        return sb.toString();
    }

    public static java.util.List<DebianUser> loadDebianUsers(Context c) {
        java.util.ArrayList<DebianUser> out = new java.util.ArrayList<>();
        String raw = runUserTool(c, null, "list-users");
        if (raw != null) {
            for (String line : raw.split("\n")) {
                DebianUser u = parseUserLine(line);
                if (u != null) out.add(u);
            }
        }
        if (!out.isEmpty()) return out;
        // Fallback: read passwd without elevate
        String[] pws = {
            NativeBin.LP_MNT + "/etc/passwd",
            "/data/local/atlas-hybrid/merge/etc/passwd"
        };
        int app = android.os.Process.myUid();
        for (String pw : pws) {
            try {
                for (String line : java.nio.file.Files.readAllLines(new File(pw).toPath())) {
                    String[] p = line.split(":");
                    if (p.length < 6) continue;
                    int uid;
                    try { uid = Integer.parseInt(p[2]); } catch (Exception e) { continue; }
                    if (uid < 1000 || uid >= 65000) continue;
                    DebianUser u = new DebianUser();
                    u.name = p[0];
                    u.uid = uid;
                    u.home = p[5];
                    u.session = uid == app;
                    out.add(u);
                }
                if (!out.isEmpty()) return out;
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static DebianUser parseUserLine(String line) {
        if (line == null || !line.contains("name=")) return null;
        DebianUser u = new DebianUser();
        for (String tok : line.trim().split("\\s+")) {
            int eq = tok.indexOf('=');
            if (eq <= 0) continue;
            String k = tok.substring(0, eq);
            String v = tok.substring(eq + 1);
            switch (k) {
                case "name": u.name = v; break;
                case "uid":
                    try { u.uid = Integer.parseInt(v); } catch (Exception ignored) {}
                    break;
                case "home": u.home = v; break;
                case "pass": u.passSet = "set".equals(v); break;
                case "sudo": u.sudo = "1".equals(v) || "on".equals(v); break;
                case "android": u.android = !"0".equals(v); break;
                case "debian": u.debian = !"0".equals(v); break;
                case "session": u.session = "1".equals(v); break;
                default: break;
            }
        }
        return u.name.isEmpty() ? null : u;
    }

    /** Run atlas-hybrid user tool as root. env may be null. */
    public static String runUserTool(Context c, String[] envkv, String... args) {
        String script = resolveScriptTip(c);
        if (script == null) return null;
        java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
        cmd.add(resolveRealSu());
        cmd.add("0");
        cmd.add("env");
        if (envkv != null) {
            for (String e : envkv) {
                if (e != null && !e.isEmpty()) cmd.add(e);
            }
        }
        cmd.add("/system/bin/sh");
        cmd.add(script);
        for (String a : args) cmd.add(a);
        StringBuilder out = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            drain(p.getInputStream(), out);
            p.waitFor();
        } catch (Exception e) {
            return e.getMessage();
        }
        return out.toString();
    }

    public static String setUserPass(Context c, String name, String password) {
        if (!validUserName(name)) return "bad name";
        if (password == null || password.isEmpty()) {
            String r = runUserTool(c, null, "lock-pass", name);
            return r != null && r.contains("pass=lock") ? "pass=lock" : nz(r);
        }
        String b64 = android.util.Base64.encodeToString(
            password.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            android.util.Base64.NO_WRAP);
        String r = runUserTool(c, new String[] { "ATLAS_NEWUSER_PASS_B64=" + b64 },
            "set-pass", name);
        return r != null && r.contains("pass=set") ? "pass=set" : nz(r);
    }

    /** Debian root login password on the LP image (not Android). */
    public static String setRootPass(Context c, String password) {
        if (password == null || password.isEmpty()) return "empty";
        return setUserPass(c, "root", password);
    }

    public static String setUserPerm(Context c, String name, String key, boolean on) {
        if (!validUserName(name)) return "bad name";
        String r = runUserTool(c, null, "set-perm", name, key, on ? "1" : "0");
        return r != null && r.contains(key + "=") ? (key + "=" + (on ? "on" : "off")) : nz(r);
    }

    public static String deleteDebianUser(Context c, String name, boolean wipeHome) {
        if (!validUserName(name)) return "bad name";
        String r = runUserTool(c,
            new String[] { "ATLAS_DEL_HOME=" + (wipeHome ? "1" : "0") },
            "del-user", name);
        if (r != null && r.contains("deleted=")) return r.trim().split("\n")[0];
        if (r != null && r.contains("error=session-user")) return "session user";
        return nz(r);
    }

    /** One reboot-persistent Debian moment (home + overlay archive). */
    public static final class Backup {
        public String id = "";
        public String user = "";
        public String ts = "";
        /** leftover field from old meta; ignored. */
        public String grok = "";
        public String label = "";
        public String note = "";
        public boolean overlay;
        public long bytes;
        public String title() {
            if (label != null && !label.isEmpty()) return label;
            return id;
        }
        public String fact() {
            return id
                + (overlay ? " overlay" : "")
                + (bytes > 0 ? " " + (bytes / 1048576) + "M" : "");
        }
        public String shortFact() {
            String o = overlay ? "overlay" : "home";
            String sz = bytes > 0 ? " · " + Math.max(1, bytes / 1048576) + "M" : "";
            String n = "";
            if (note != null && !note.isEmpty()) {
                String one = note.replace('\n', ' ').trim();
                if (one.length() > 48) one = one.substring(0, 48) + "…";
                n = " · " + one;
            }
            return o + sz + n;
        }
    }

    public static String backupSave(Context c, String name) {
        if (!validUserName(name)) return "bad name";
        return nz(runUserTool(c, null, "backup-save", name));
    }

    public static String backupLoad(Context c, String name, String id) {
        if (id == null || id.isEmpty()) return backupLoadLatest(c, name);
        if (name != null && validUserName(name)) {
            return nz(runUserTool(c, null, "backup-load", name, id));
        }
        return nz(runUserTool(c, null, "backup-load", id));
    }

    public static String backupLoadLatest(Context c, String name) {
        if (name != null && validUserName(name)) {
            return nz(runUserTool(c, null, "backup-load", name));
        }
        return nz(runUserTool(c, null, "backup-load"));
    }

    public static String backupDelete(Context c, String id) {
        if (id == null || id.isEmpty()) return "bad id";
        return nz(runUserTool(c, null, "backup-rm", id));
    }

    public static String backupExport(Context c, String id) {
        if (id == null || id.isEmpty()) return "bad id";
        return nz(runUserTool(c, null, "backup-export", id));
    }

    public static String backupExportLatest(Context c, String name) {
        if (!validUserName(name)) return "bad name";
        return nz(runUserTool(c, null, "backup-export", name));
    }

    public static String backupRename(Context c, String id, String label) {
        if (id == null || id.isEmpty()) return "bad id";
        String b64 = android.util.Base64.encodeToString(
            (label != null ? label : "").getBytes(java.nio.charset.StandardCharsets.UTF_8),
            android.util.Base64.NO_WRAP);
        return nz(runUserTool(c, new String[] { "ATLAS_BACKUP_LABEL_B64=" + b64 },
            "backup-rename", id));
    }

    public static String backupNote(Context c, String id, String note) {
        if (id == null || id.isEmpty()) return "bad id";
        String b64 = android.util.Base64.encodeToString(
            (note != null ? note : "").getBytes(java.nio.charset.StandardCharsets.UTF_8),
            android.util.Base64.NO_WRAP);
        return nz(runUserTool(c, new String[] { "ATLAS_BACKUP_NOTE_B64=" + b64 },
            "backup-note", id));
    }

    public static String backupImport(Context c, String path) {
        if (path == null || path.isEmpty()) return "no file";
        return nz(runUserTool(c, null, "backup-import", path));
    }

    public static java.util.List<String> listExportFiles(Context c) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        String raw = runUserTool(c, null, "backup-exports");
        if (raw == null) return out;
        for (String line : raw.split("\n")) {
            if (!line.contains("path=")) continue;
            for (String tok : line.trim().split("\\s+")) {
                if (tok.startsWith("path=")) out.add(tok.substring(5));
            }
        }
        return out;
    }

    public static java.util.List<Backup> loadBackups(Context c) {
        return loadBackups(c, null);
    }

    public static java.util.List<Backup> loadBackups(Context c, String userFilter) {
        java.util.ArrayList<Backup> out = new java.util.ArrayList<>();
        String raw = (userFilter != null && validUserName(userFilter))
            ? runUserTool(c, null, "backup-list", userFilter)
            : runUserTool(c, null, "backup-list");
        if (raw == null) return out;
        for (String line : raw.split("\n")) {
            Backup b = parseBackupLine(line);
            if (b != null) out.add(b);
        }
        return out;
    }

    public static String backupSummary(Context c) {
        java.util.List<Backup> all = loadBackups(c);
        if (all.isEmpty()) return "none";
        return all.size() + " · survive reboot";
    }

    private static Backup parseBackupLine(String line) {
        if (line == null || !line.contains("id=")) return null;
        Backup b = new Backup();
        for (String tok : line.trim().split("\\s+")) {
            int eq = tok.indexOf('=');
            if (eq <= 0) continue;
            String k = tok.substring(0, eq);
            String v = tok.substring(eq + 1);
            switch (k) {
                case "id": b.id = v; break;
                case "user": b.user = v; break;
                case "ts": b.ts = v; break;
                case "grok": b.grok = v; break;
                case "overlay": b.overlay = "1".equals(v); break;
                case "label_b64": b.label = b64d(v); break;
                case "note_b64": b.note = b64d(v); break;
                case "bytes":
                    try { b.bytes = Long.parseLong(v); } catch (Exception ignored) {}
                    break;
                default: break;
            }
        }
        return b.id.isEmpty() ? null : b;
    }

    private static String b64d(String v) {
        if (v == null || v.isEmpty()) return "";
        try {
            return new String(android.util.Base64.decode(v, android.util.Base64.DEFAULT),
                java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String nz(String s) {
        if (s == null || s.trim().isEmpty()) return "failed";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 140 ? t.substring(t.length() - 140) : t;
    }

    /**
     * Add any Debian login. Password optional (login only). sudo uses atlas-auth.
     * @return toast fact, never the password.
     */
    public static String addDebianUser(Context c, String name, String password,
                                       boolean sudoAuth) {
        if (!validUserName(name)) return "bad name";
        switch (name) {
            case "root":
            case "daemon":
            case "nobody":
            case "sshd":
                return "reserved";
            default:
                break;
        }
        String script = resolveScriptTip(c);
        if (script == null) return "atlas-hybrid.sh missing";
        String b64 = "";
        if (password != null && !password.isEmpty()) {
            b64 = android.util.Base64.encodeToString(
                password.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP);
        }
        String su = resolveRealSu();
        StringBuilder out = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                su, "0", "env",
                "ATLAS_NEWUSER_PASS_B64=" + b64,
                "ATLAS_USER_SUDO=" + (sudoAuth ? "1" : "0"),
                "ATLAS_USER_ANDROID=1",
                "ATLAS_USER_DEBIAN=1",
                "/system/bin/sh", script, "add-user", name);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            drain(p.getInputStream(), out);
            p.waitFor();
        } catch (Exception e) {
            out.append(e.getMessage() != null ? e.getMessage() : "err");
        }
        String so = out.toString();
        if (so.contains("user=") && so.contains("auth=atlas-auth")) {
            int i = so.indexOf("user=");
            String line = so.substring(i).replace('\n', ' ');
            if (line.length() > 120) line = line.substring(0, 120);
            return line.trim();
        }
        if (so.contains("error=")) {
            int i = so.indexOf("error=");
            return so.substring(i).split("\\s+")[0];
        }
        // enterd fallback — no password (lock); atlas-auth sudo still applied
        if (elevateViaEnterSock(
            "export ATLAS_USER_SUDO=" + (sudoAuth ? "1" : "0")
                + (b64.isEmpty() ? "" : " ATLAS_NEWUSER_PASS_B64=" + b64)
                + "; /system/bin/sh '" + script + "' add-user " + name)) {
            return listDebianUsers();
        }
        if (so.length() > 160) so = so.substring(so.length() - 160);
        return so.isEmpty() ? "create failed" : so.replace('\n', ' ');
    }

    /** Blocking: create {@code atlas} at app uid via enter sock or real su. */
    public static boolean ensureLiveUid(Context c) {
        if (c == null) return false;
        if (!NativeBin.hybridRootfsReady() && !new File(NativeBin.LP_MNT + "/etc/passwd").isFile()) {
            return false;
        }
        int uid = android.os.Process.myUid();
        if (uid < 10000) return false;
        String pw = NativeBin.LP_MNT + "/etc/passwd";
        if (passwdHasUid(pw, uid)
            || passwdHasUid("/data/local/atlas-hybrid/merge/etc/passwd", uid)) {
            return true;
        }
        /* Rewrite atlas: to the live app uid. Never invent atlas10101 and
         * never fall back to a stale 10198 (overlay heresy 2026-08-21). */
        String sh =
            "uid=" + uid + "; "
                + "home=/data/local/atlas-home/atlas; "
                + "mkdir -p \"$home\"; "
                + "for root in /data/local/atlas-linux /data/local/atlas-hybrid/merge "
                + "/data/local/atlas-hybrid/lower; do "
                + "  [ -f \"$root/etc/passwd\" ] || continue; "
                + "  if grep -q '^atlas:' \"$root/etc/passwd\"; then "
                + "    sed -i \"s#^atlas:[^:]*:[^:]*:[^:]*:#atlas:x:${uid}:${uid}:#\" "
                + "      \"$root/etc/passwd\"; "
                + "  else "
                + "    echo \"atlas:x:${uid}:${uid}:Atlas:${home}:/bin/bash\" >>\"$root/etc/passwd\"; "
                + "  fi; "
                + "  if [ -f \"$root/etc/group\" ]; then "
                + "    if grep -q '^atlas:' \"$root/etc/group\"; then "
                + "      sed -i \"s#^atlas:x:[^:]*:#atlas:x:${uid}:#\" \"$root/etc/group\"; "
                + "    else "
                + "      echo \"atlas:x:${uid}:\" >>\"$root/etc/group\"; "
                + "    fi; "
                + "  fi; "
                + "  if [ -f \"$root/etc/shadow\" ] && ! grep -q \"^atlas:\" \"$root/etc/shadow\"; then "
                + "    echo \"atlas:!:19600:0:99999:7:::\" >>\"$root/etc/shadow\"; "
                + "  fi; "
                + "  mkdir -p \"$root/etc/sudoers.d\"; "
                + "  printf 'atlas ALL=(ALL) NOPASSWD:ALL\\nDefaults:%s !authenticate\\n%s ALL=(ALL) NOPASSWD:ALL\\n' "
                + "    \"$uid\" \"$uid\" >\"$root/etc/sudoers.d/atlas\"; "
                + "  chown 0:0 \"$root/etc/sudoers.d/atlas\" 2>/dev/null || true; "
                + "  chmod 0440 \"$root/etc/sudoers.d/atlas\"; "
                + "done; "
                + "grep \":x:${uid}:${uid}:\" /data/local/atlas-linux/etc/passwd "
                + "  /data/local/atlas-hybrid/merge/etc/passwd 2>/dev/null | head -1";
        if (elevateViaEnterSock(sh)) {
            Log.i(TAG, "ensure-uid via enter sock uid=" + uid);
            return passwdHasUid(pw, uid)
                || passwdHasUid("/data/local/atlas-hybrid/merge/etc/passwd", uid);
        }
        String su = resolveRealSu();
        try {
            ProcessBuilder pb = new ProcessBuilder(su, "0", "sh", "-c", sh);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            drain(p.getInputStream(), new StringBuilder());
            p.waitFor();
        } catch (Exception e) {
            Log.w(TAG, "ensure-uid su: " + e.getMessage());
            return false;
        }
        boolean ok = passwdHasUid(pw, uid)
            || passwdHasUid("/data/local/atlas-hybrid/merge/etc/passwd", uid);
        Log.i(TAG, "ensure-uid su uid=" + uid + " ok=" + ok);
        return ok;
    }

    private static boolean passwdHasUid(String path, int uid) {
        return passwdRowForUid(path, uid) != null;
    }

    private static String passwdRowForUid(String path, int uid) {
        try {
            java.util.List<String> lines =
                java.nio.file.Files.readAllLines(new File(path).toPath());
            String needle = ":x:" + uid + ":" + uid + ":";
            for (String line : lines) {
                if (line.contains(needle)) return line;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Root via atlas-enter.sock (same path Titan Grok used to create the user). */
    private static boolean elevateViaEnterSock(String cmd) {
        File sock = new File("/data/local/tmp/atlas-enter.sock");
        if (!sock.exists()) return false;
        android.net.LocalSocket ls = null;
        try {
            ls = new android.net.LocalSocket();
            ls.connect(new android.net.LocalSocketAddress(
                "/data/local/tmp/atlas-enter.sock",
                android.net.LocalSocketAddress.Namespace.FILESYSTEM));
            ls.setSoTimeout(15_000);
            java.io.OutputStream out = ls.getOutputStream();
            java.io.InputStream in = ls.getInputStream();
            String msg = "ELEVATE chroot=0 home=/data/local/atlas-home/atlas\n"
                + cmd + "\n";
            out.write(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            byte[] buf = new byte[4096];
            StringBuilder sb = new StringBuilder();
            int n;
            long end = System.currentTimeMillis() + 12_000L;
            while (System.currentTimeMillis() < end && (n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n));
                if (sb.indexOf("__ATLAS_EXIT__") >= 0) break;
            }
            Log.i(TAG, "enter-sock: " + sb.toString().replace('\n', ' '));
            return sb.indexOf("__ATLAS_EXIT__ 0") >= 0 || sb.indexOf("atlas:x:") >= 0;
        } catch (Exception e) {
            Log.w(TAG, "enter-sock: " + e.getMessage());
            return false;
        } finally {
            if (ls != null) {
                try { ls.close(); } catch (Exception ignored) {}
            }
        }
    }

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
            + "ATLAS_DROP_UID=" + android.os.Process.myUid() + " "
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
