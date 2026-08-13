package com.titanus2.usbhid;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional root / Magisk detection. Hybrid ROM ships in-ROM USB HID stack
 * ({@code /system/etc/titan2_usb_hid} + init service) so USB works rootless;
 * Magisk module remains an optional override on <b>Dev</b> builds only.
 * <p>
 * Release APKs are compiled with {@link BuildConfig#ALLOW_ROOT}{@code = false}:
 * this class never execs {@code su}, so manually installing Magisk later cannot
 * produce empty grant dialogs from the HID app.
 */
public final class Root {
    private static final AtomicInteger state = new AtomicInteger(-1); // -1 unk, 0 no, 1 yes
    private static final AtomicBoolean probed = new AtomicBoolean(false);

    private Root() {}

    /**
     * True only on ALLOW_ROOT builds when {@code su -c id} works (Magisk / phh-su).
     * Release: always false, never spawns su.
     */
    public static boolean available() {
        if (!BuildConfig.ALLOW_ROOT) {
            state.set(0);
            return false;
        }
        int s = state.get();
        if (s >= 0) return s == 1;
        if (!probed.compareAndSet(false, true)) {
            // another thread probing — wait briefly
            for (int i = 0; i < 20 && state.get() < 0; i++) {
                try { Thread.sleep(25); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return state.get() == 1;
        }
        boolean ok = false;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            // do not hang UI forever
            boolean finished = p.waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (finished && p.exitValue() == 0) {
                byte[] b = new byte[64];
                int n = p.getInputStream().read(b);
                String out = n > 0 ? new String(b, 0, n) : "";
                ok = out.contains("uid=0");
            } else if (!finished) {
                p.destroyForcibly();
            }
        } catch (Exception ignored) {
            ok = false;
        }
        state.set(ok ? 1 : 0);
        return ok;
    }

    /** Force re-probe (e.g. after Magisk install). No-op when ALLOW_ROOT is false. */
    public static void invalidate() {
        if (!BuildConfig.ALLOW_ROOT) {
            state.set(0);
            probed.set(false);
            return;
        }
        state.set(-1);
        probed.set(false);
    }

    public static boolean runSu(String cmdline) {
        if (!BuildConfig.ALLOW_ROOT || !available()) return false;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmdline});
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * USB gadget path available: in-ROM hybrid stack, Magisk module, or live
     * /dev/hidg*. Hybrid ships {@code /system/etc/titan2_usb_hid} + init service
     * so USB works rootless (service runs as root; app only writes ctrl files).
     */
    public static boolean usbGadgetAvailable() {
        if (new File("/dev/hidg0").exists() || new File("/dev/hidg1").exists()) return true;
        if (new File("/system/etc/titan2_usb_hid/enable_hid.sh").isFile()) return true;
        if (new File("/system/etc/titan2_usb_hid/hid_bridge").isFile()) return true;
        if (new File("/system/bin/titan2-usb-hid-service.sh").isFile()) return true;
        if (new File("/data/adb/modules/titan2_usb_hid").isDirectory()) return true;
        if (new File("/data/adb/modules/titan2_usb_hid_lab").isDirectory()) return true;
        return false;
    }

    /** In-ROM USB HID stack (no Magisk required). */
    public static boolean systemUsbStack() {
        return new File("/system/etc/titan2_usb_hid/enable_hid.sh").isFile()
            || new File("/system/bin/titan2-usb-hid-service.sh").isFile();
    }

    public static boolean isSystemApp(Context ctx) {
        try {
            ApplicationInfo ai = ctx.getApplicationInfo();
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Default transport for a fresh install / no saved prefs.
     * <ul>
     *   <li>In-ROM hybrid stack present → USB+BT ({@link HidControl#TRANSPORT_BOTH})
     *       so Link shows Both and Start can use either path.</li>
     *   <li>Otherwise → Bluetooth only (USB gadget not shipped).</li>
     * </ul>
     * Note: enabling the USB gadget path drops ADB while linked; user can
     * still switch Link → BT only from the HID app.
     */
    public static int defaultTransport() {
        if (systemUsbStack() || usbGadgetAvailable()) {
            return HidControl.TRANSPORT_BOTH;
        }
        return HidControl.TRANSPORT_BT;
    }

    /**
     * True when a USB host has the gadget enumerated (cable data path live).
     * Without this, hidg writes fail with "endpoint shutdown" and keys/pad
     * never reach the PC even though the session/bridge look "on".
     * <p><b>Never</b> treat {@code /dev/hidg*} alone as linked — nodes exist
     * after enable_hid even with no cable; that false-positive exclusive-grabbed
     * TitanKey and killed both host and phone keyboard.
     */
    private static volatile long hostLinkCacheMs;
    private static volatile boolean hostLinkCache;

    public static boolean usbHostLinked() {
        long now = android.os.SystemClock.uptimeMillis();
        // Cache 8s — UI refresh must never shell-out or hammer SELinux-denied sysfs.
        if (now - hostLinkCacheMs < 8000L) return hostLinkCache;
        boolean linked = probeUsbHostLinked();
        hostLinkCache = linked;
        hostLinkCacheMs = now;
        return linked;
    }

    private static boolean probeUsbHostLinked() {
        // Never call available()/su from the UI path — that blocked the main
        // thread (~800ms) and looked like HID "crashing" under Magisk.
        // Prefer best-effort sysfs; SELinux may deny — then assume linked when
        // gadget stack exists so Start is not permanently red-flagged.
        String[] states = {
            "/sys/class/android_usb/android0/state",
            "/sys/devices/virtual/android_usb/android0/state",
        };
        boolean sawDisconnected = false;
        for (String path : states) {
            try {
                java.io.File st = new java.io.File(path);
                if (!st.canRead()) continue;
                String s = readFileTrim(st);
                if (s == null) continue;
                if ("CONNECTED".equalsIgnoreCase(s) || "CONFIGURED".equalsIgnoreCase(s))
                    return true;
                if ("DISCONNECTED".equalsIgnoreCase(s)) sawDisconnected = true;
            } catch (Exception ignored) {}
        }
        if (sawDisconnected) return false;
        // UDC state (gadget bound + host enumerated)
        try {
            java.io.File udcDir = new java.io.File("/sys/class/udc");
            java.io.File[] udcs = udcDir.listFiles();
            if (udcs != null) {
                for (java.io.File u : udcs) {
                    java.io.File st = new java.io.File(u, "state");
                    if (!st.canRead()) continue;
                    String s = readFileTrim(st);
                    if (s != null && s.toLowerCase(java.util.Locale.US).contains("configured"))
                        return true;
                }
            }
        } catch (Exception ignored) {}
        // Configfs UDC non-empty often means gadget is live on the wire
        try {
            java.io.File udc = new java.io.File("/config/usb_gadget/g1/UDC");
            if (udc.canRead()) {
                String s = readFileTrim(udc);
                if (s != null && !s.isEmpty() && !"none".equalsIgnoreCase(s)) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        // Unreadable (priv_app SELinux on sysfs/configfs): if hybrid stack is
        // present, assume host can be linked — do not ANR on su probes.
        if (systemUsbStack() || usbGadgetAvailable()) return true;
        return false;
    }

    /** Root shell stdout (trimmed), or null. Never runs when ALLOW_ROOT is false. */
    public static String runSuOut(String cmdline) {
        if (!BuildConfig.ALLOW_ROOT || !available()) return null;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmdline});
            if (!p.waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            java.io.InputStream in = p.getInputStream();
            byte[] b = new byte[256];
            int n = in.read(b);
            if (n <= 0) return null;
            return new String(b, 0, n).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static String readFileTrim(java.io.File f) throws Exception {
        byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
        return new String(b).trim();
    }

    /**
     * Dense status chip — no "Magisk mode" jargon.
     * System stack is the default path; root helper is optional.
     */
    public static String modeLabel() {
        boolean r = available();
        boolean sys = systemUsbStack();
        boolean u = usbGadgetAvailable();
        if (sys) return r ? "USB ready" : "USB ready";
        if (r && u) return "USB ready";
        if (u) return "USB gadget";
        if (r) return "helper only";
        return "BT only";
    }

    /** @deprecated product UI no longer shows stack essays. */
    public static String stackDetail() {
        return modeLabel();
    }
}
