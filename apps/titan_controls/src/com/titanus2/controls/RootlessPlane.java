package com.titanus2.controls;

import android.content.Context;
import android.provider.Settings;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Rootless product plane after wipe (lab_rootless / release — no Magisk).
 * Seeds world-writable HID specials queues and idle session plane so exclusive
 * Specials and host smokes work before the first HID Start.
 */
public final class RootlessPlane {
    private static final String TAG = "RootlessPlane";
    private RootlessPlane() {}

    /** Canonical names must match HidControl.REMOTE_Q / HW_OUT (not legacy aliases). */
    private static final String[] QUEUE_NAMES = {
        "titan2_remote_hid.q",
        "titan2_hid_hw.out",
        // legacy alias still drained nowhere — keep touch only for old pad-agent
        "titan2_hid_remote_q",
    };

    private static final String[] IDLE_PLANE = {
        "titan2_usb_hid_session",
        "titan2_usb_hid_keys",
        "titan2_usb_hid_mouse",
        "titan2_usb_hid_grab",
        "titan2_host_layout_keys_pause",
        "titan2_usb_hid_keys_pause",
        // 13.05: Sym inject pause sticky across boot → dead HW keyboard
        "titan2_specials_inject_pause",
    };

    public static void seed(Context ctx) {
        File tmp = new File("/data/local/tmp");
        // 12.33: always truncate specials queues (null/create-only left multi-glyph
        // bytes across reboot until first exclusive Specials drain).
        for (String n : QUEUE_NAMES) {
            touchWorld(new File(tmp, n), "");
        }
        // Full path set (tmp + misc) via KeyActions — same as exclusive Specials arm
        try { KeyActions.ensureSpecialsQueuesLocal(); } catch (Exception ignored) {}
        // 12.33: flush agent + remote backlog on every rootless seed (boot/a11y)
        try { KeyActions.clearAgentKeyQueue(ctx); } catch (Exception ignored) {}
        try { KeyActions.clearRemoteHidQueues(ctx); } catch (Exception ignored) {}

        // CRITICAL: never zero session/keys/mouse/grab while HID is live.
        // PublishKm / a11y connect used to call seed every few seconds and
        // forced session=0 → service enable_hid off → host USB disconnect
        // mid-typing on the HW keyboard.
        if (hidSessionLive(ctx, tmp)) {
            Log.i(TAG, "seed: skip idle plane (HID session live)");
            return;
        }
        Log.i(TAG, "seed: idle plane session/keys/mouse/grab=0");

        for (String n : IDLE_PLANE) {
            touchWorld(new File(tmp, n), "0");
        }
        touchWorld(new File(tmp, "titan2_host_layout"), "off");
        // Settings.Global mirror — pad-agent + HID readPlaneAny
        if (ctx != null) {
            try {
                android.content.ContentResolver cr = ctx.getContentResolver();
                Settings.Global.putString(cr, "titan2_usb_hid_session", "0");
                Settings.Global.putString(cr, "titan2_usb_hid_keys", "0");
                Settings.Global.putString(cr, "titan2_usb_hid_mouse", "0");
                Settings.Global.putString(cr, "titan2_usb_hid_grab", "0");
                Settings.Global.putString(cr, "titan2_host_layout", "off");
                Settings.Global.putString(cr, "titan2_host_layout_keys_pause", "0");
                Settings.Global.putString(cr, "titan2_usb_hid_keys_pause", "0");
                Settings.Global.putString(cr, "titan2_specials_inject_pause", "0");
                // 13.08/13.86: product defaults so pad-agent mtime dirty path is stable
                // Product specials method = kcm (inject is opt-in / per-app).
                seedGlobalIfEmpty(cr, "titan2_specials_method", "kcm");
                seedGlobalIfEmpty(cr, "titan2_char_mod", "sym");
                seedGlobalIfEmpty(cr, "titan2_fn_mode", "ctrl");
                // Lab sometimes left Secure mirrors (not product SoT) → confuse bench
                clearSecureHidGhost(cr);
            } catch (Exception ignored) {}
        }
        // Tmp product seeds (rootless shell can read; pad-agent prefers)
        touchWorldIfEmpty(new File(tmp, "titan2_specials_method"), "kcm");
        touchWorldIfEmpty(new File(tmp, "titan2_char_mod"), "sym");
        touchWorldIfEmpty(new File(tmp, "titan2_fn_mode"), "ctrl");
    }

    private static void seedGlobalIfEmpty(android.content.ContentResolver cr,
            String name, String value) {
        try {
            String cur = Settings.Global.getString(cr, name);
            if (cur == null || cur.trim().isEmpty() || "null".equalsIgnoreCase(cur.trim())) {
                Settings.Global.putString(cr, name, value);
            }
        } catch (Exception ignored) {}
    }

    /** Drop mistaken Secure titan2_usb_hid_* (SoT is Global + plane files). */
    private static void clearSecureHidGhost(android.content.ContentResolver cr) {
        String[] names = {
            "titan2_usb_hid_session", "titan2_usb_hid_keys", "titan2_usb_hid_mouse",
            "titan2_usb_hid_grab", "titan2_usb_hid_local_input",
            "titan2_usb_hid_keys_pause", "titan2_specials_inject_pause",
        };
        for (String n : names) {
            try {
                String s = Settings.Secure.getString(cr, n);
                if (s != null && !s.isEmpty()) {
                    Settings.Secure.putString(cr, n, "0");
                }
            } catch (Exception ignored) {}
        }
    }

    private static void touchWorldIfEmpty(File f, String content) {
        try {
            if (f.exists() && f.length() > 0) return;
            touchWorld(f, content);
        } catch (Exception ignored) {}
    }

    /** True if HID owns a phys session (Global or plane files). */
    private static boolean hidSessionLive(Context ctx, File tmp) {
        if (ctx != null) {
            try {
                String g = Settings.Global.getString(
                    ctx.getContentResolver(), "titan2_usb_hid_session");
                if (g != null) {
                    g = g.trim();
                    if ("1".equals(g) || "true".equalsIgnoreCase(g) || "on".equalsIgnoreCase(g))
                        return true;
                }
            } catch (Exception ignored) {}
        }
        try {
            File f = new File(tmp, "titan2_usb_hid_session");
            if (f.canRead()) {
                byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                String s = new String(b).trim();
                if ("1".equals(s) || "true".equalsIgnoreCase(s)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void touchWorld(File f, String content) {
        try {
            if (content != null) {
                FileOutputStream os = new FileOutputStream(f, false);
                try {
                    if (!content.isEmpty()) {
                        os.write(content.getBytes("UTF-8"));
                    }
                    // empty string → truncate to zero-length queue file
                } finally {
                    os.close();
                }
            } else if (!f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.createNewFile();
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
            try {
                Os.chmod(f.getAbsolutePath(), 0666);
            } catch (Throwable ignored) {}
        } catch (Exception ignored) {}
    }
}
