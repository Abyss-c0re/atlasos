package com.titanus2.controls;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * CubalC free-energy / BrainCube impulse law for <b>all</b> product toggles:
 * human desire applies in the same breath — never “write plane + sleep until
 * agent poll”. Agents re-assert only.
 * <p>
 * Torch sysfs is world-writable; cam/mic/fw need root via {@code su -c} when
 * available (lab KernelSU). Fail soft if su denied — Secure/plane still set.
 */
public final class ImpulseSnap {
    private static final String TAG = "ImpulseSnap";

    private static final String[] TORCH_PATHS = {
            "/sys/class/flashlight_core/flashlight/flashlight_torch",
            "/sys/devices/virtual/flashlight_core/flashlight/flashlight_torch",
    };

    private ImpulseSnap() {}

    // ── torch ──────────────────────────────────────────────────────────────

    /** Apply torch LED now. Returns true if sysfs accepted the write. */
    public static boolean torch(boolean on) {
        String bit = on ? "1" : "0";
        for (String path : TORCH_PATHS) {
            try (FileOutputStream fos = new FileOutputStream(path);
                 OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                w.write("0 0 0 " + bit + "\n");
                w.flush();
            } catch (Throwable e) {
                continue;
            }
            try (FileOutputStream fos = new FileOutputStream(path);
                 OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                w.write("0 1 0 " + bit + "\n");
                w.flush();
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "torch " + (on ? "ON" : "OFF") + " " + path);
            return true;
        }
        return sh("for p in /sys/class/flashlight_core/flashlight/flashlight_torch "
                + "/sys/devices/virtual/flashlight_core/flashlight/flashlight_torch; do "
                + "[ -e \"$p\" ] || continue; "
                + "printf '0 0 0 " + bit + "\\n' >\"$p\"; "
                + "printf '0 1 0 " + bit + "\\n' >\"$p\"; exit 0; done; exit 1");
    }

    // ── camera privacy nodes (fail-closed Linux plane) ─────────────────────

    /** Revoke /dev/video* now (root). Belt re-asserts. */
    public static boolean camBlock() {
        stamp("/data/local/tmp/titan2_sp_wake", "cam_block");
        // Secure plane is written by SensorPrivacyEnforcer; snap chmod needs su.
        return su("for n in /dev/video*; do [ -e \"$n\" ] && chmod 000 \"$n\" 2>/dev/null; done; "
                + "for n in /dev/video*; do fuser -k \"$n\" 2>/dev/null; done; "
                + "for n in /dev/video*; do [ -e \"$n\" ] && chmod 000 \"$n\" 2>/dev/null; done; "
                + "true");
    }

    /** Restore /dev/video* open modes now (root). */
    public static boolean camAllow() {
        stamp("/data/local/tmp/titan2_sp_wake", "cam_allow");
        return su("for n in /dev/video*; do [ -e \"$n\" ] && chmod 660 \"$n\" 2>/dev/null; done; "
                + "chown media:system /dev/video0 /dev/video1 2>/dev/null; "
                + "chown camera:system /dev/video[2-9] /dev/video[1-9][0-9]* 2>/dev/null; "
                + "true");
    }

    /**
     * Mic privacy edge: dedicated recorders only.
     * NEVER force-stop camera packages (aperture dies → camera “closes after seconds”).
     */
    public static boolean micBlock() {
        stamp("/data/local/tmp/titan2_sp_wake", "mic_block");
        return su("for p in com.google.android.apps.recorder "
                + "com.android.soundrecorder org.lineageos.recorder "
                + "com.sec.android.app.voicenote; do "
                + "am force-stop \"$p\" 2>/dev/null; done; true");
    }

    public static boolean micAllow() {
        stamp("/data/local/tmp/titan2_sp_wake", "mic_allow");
        return true;
    }

    // ── pad / agents ───────────────────────────────────────────────────────

    public static void wakePadAgent() {
        stamp("/data/local/tmp/titan2_pad_wake",
                Long.toString(System.currentTimeMillis()));
        // Non-blocking nudge
        shAsync("pid=$(pidof titan2-pad-agent.sh 2>/dev/null | awk '{print $1}'); "
                + "[ -n \"$pid\" ] && kill -USR1 \"$pid\" 2>/dev/null; true");
    }

    public static void wakeSensorBelt() {
        stamp("/data/local/tmp/titan2_sp_wake",
                Long.toString(System.currentTimeMillis()));
    }

    // ── firewall ───────────────────────────────────────────────────────────

    /**
     * Run titan2-fw now as root (enable/disable/apply/deny-uid …).
     * Does not wait for pad-agent poll.
     */
    public static boolean fw(String... args) {
        if (args == null || args.length == 0) return false;
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (a == null) continue;
            if (sb.length() > 0) sb.append(' ');
            // shell-safe: only allowlist tokens
            if (!a.matches("[A-Za-z0-9._:@+/-]+")) continue;
            sb.append(a);
        }
        if (sb.length() == 0) return false;
        String cmd = sb.toString();
        stamp("/data/local/tmp/titan2_fw_wake", cmd);
        boolean ok = su("export PATH=/system/bin:/system/xbin:$PATH; "
                + "if [ -x /system/bin/titan2-fw ]; then titan2-fw " + cmd
                + "; elif [ -x /system/bin/titan2-fw.sh ]; then titan2-fw.sh " + cmd
                + "; else exit 1; fi");
        Log.i(TAG, "fw impulse [" + cmd + "] ok=" + ok);
        return ok;
    }

    // ── keyboard LED ───────────────────────────────────────────────────────

    /** Best-effort keyled apply via peel script (root). */
    public static boolean keyled(int level) {
        int n = Math.max(0, Math.min(7, level));
        stamp("/data/local/tmp/titan2_keyled_brightness", String.valueOf(n));
        stamp("/data/misc/titan2/titan2_keyled_brightness", String.valueOf(n));
        wakePadAgent();
        return su("if [ -x /system/bin/titan2-keyled-write.sh ]; then "
                + "/system/bin/titan2-keyled-write.sh write " + n + " impulse; "
                + "elif [ -x /system/bin/titan2-keyled-write ]; then "
                + "/system/bin/titan2-keyled-write write " + n + " impulse; "
                + "fi; true");
    }

    // ── subdisplay brightness (class node is system-writable) ──────────────

    public static boolean rearBrightness(int pct) {
        int p = Math.max(0, Math.min(100, pct));
        // Map 0–100 → 0–255 class scale (best-effort)
        int hw = p <= 0 ? 0 : Math.max(1, (p * 255) / 100);
        String v = String.valueOf(hw);
        for (String path : new String[]{
                "/sys/class/leds/lcd-backlight1/brightness",
                "/sys/devices/platform/mtk-leds1/leds/lcd-backlight1/brightness",
        }) {
            try (FileOutputStream fos = new FileOutputStream(path)) {
                fos.write(v.getBytes(StandardCharsets.UTF_8));
                fos.write('\n');
                Log.i(TAG, "rear bl=" + v + " " + path);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return su("echo " + v + " > /sys/class/leds/lcd-backlight1/brightness 2>/dev/null; true");
    }

    // ── primitives ─────────────────────────────────────────────────────────

    public static void stamp(String path, String body) {
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
                fos.write('\n');
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
        } catch (Throwable e) {
            Log.w(TAG, "stamp " + path + ": " + e.getMessage());
        }
    }

    /** Non-root shell; timeout 400ms. */
    public static boolean sh(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            if (!p.waitFor(400, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Throwable e) {
            return false;
        }
    }

    public static void shAsync(String cmd) {
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        } catch (Throwable ignored) {
        }
    }

    /**
     * Root shell (KernelSU/Magisk). Short timeout so UI never hangs.
     * Lab Titan is rooted; release builds still get plane writes without su.
     */
    public static boolean su(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            if (!p.waitFor(700, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                Log.w(TAG, "su timeout");
                return false;
            }
            return p.exitValue() == 0;
        } catch (Throwable e) {
            Log.w(TAG, "su: " + e.getMessage());
            return false;
        }
    }

    /** Fire-and-forget root (never block toggle thread past spawn). */
    public static void suAsync(String cmd) {
        new Thread(() -> su(cmd), "impulse-su").start();
    }
}
