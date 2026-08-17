package com.titanus2.controls;

import android.content.Context;
import android.provider.Settings;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Keyboard backlight via system daemon (titan2-pad-agent).
 *
 * The app only writes "want" values to control files (/data/misc/titan2 (OS plane)).
 * The bundled ROM daemon handles:
 *  - turning on for physical key presses (system-wide, any key)
 *  - idle timeout after last key
 *  - notification pulsing when screen off (even with app not running)
 *  - forced off on screen sleep
 *
 * Brightness request: titan2_keyled_brightness  (0..7)
 * Idle timeout (seconds, independent of display timeout): titan2_keyled_timeout
 *   0 = always on while screen on
 *   agent keeps on for N seconds after last key/wake/setting change
 */
public final class KeyboardLed {
    public static final String REQUEST_FILE = "titan2_keyled_brightness";
    public static final String TIMEOUT_FILE = "titan2_keyled_timeout";
    public static final String SETTINGS_KEY = "titan2_keyled_brightness";
    public static final String TIMEOUT_SETTINGS_KEY = "titan2_keyled_timeout";
    public static final String STATUS_PATH = "/data/local/tmp/titan2_led_status";
    public static final int DEFAULT_LEVEL = 3;
    /**
     * Idle seconds after last key while screen on.
     * 0 = always on while awake (explicit user choice via hub O / Always tile).
     * Product default is 30s — Cube/NEXUS: do not leave pad glowing forever.
     */
    public static final int DEFAULT_TIMEOUT_SEC = 30;

    private KeyboardLed() {}

    public static List<File> requestFiles(Context ctx, String name) {
        List<File> out = new ArrayList<>();
        if (ctx == null) return out;
        out.add(new File(ctx.getFilesDir(), name));
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) out.add(new File(ext, name));
        } catch (Exception ignored) {}
        return out;
    }

    private static boolean writeName(Context ctx, String name, String payload) {
        int wrote = 0;
        try {
            FileOutputStream fos = ctx.openFileOutput(name, Context.MODE_PRIVATE);
            fos.write(payload.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();
            wrote++;
        } catch (Exception ignored) {}
        for (File f : requestFiles(ctx, name)) {
            try {
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(payload.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.close();
                wrote++;
            } catch (Exception ignored) {}
        }
        return wrote > 0;
    }

    /** After a11y rebind / install: republish want + bump idle clock. */
    public static void wakeOnBind(Context ctx) {
        if (ctx == null) return;
        try { AgentBridge.bumpKeyActivity(ctx); } catch (Exception ignored) {}
        Integer lv = getRequestedLevel(ctx);
        int n = lv != null ? lv : DEFAULT_LEVEL;
        if (n < 1) n = DEFAULT_LEVEL;
        try { AgentBridge.put(ctx, AgentBridge.LED_LEVEL, Integer.toString(n)); } catch (Exception ignored) {}
        writeName(ctx, REQUEST_FILE, Integer.toString(n));
    }

    /** @return null on success */
    public static String setLevel(Context ctx, int level) {
        if (level < 0) level = 0;
        if (level > 7) level = 7;
        boolean ok = writeName(ctx, REQUEST_FILE, String.valueOf(level));
        try {
            if (Settings.System.canWrite(ctx)) {
                Settings.System.putInt(ctx.getContentResolver(), SETTINGS_KEY, level);
                ok = true;
            }
        } catch (Exception ignored) {}
        // Direct write removed: the system daemon (titan2-pad-agent) is responsible for
        // all sysfs writes to support key-press activity, idle timeout, and notifications
        // even when the app is not running or screen is off. App only sets the "want" values.
        // tryDirectWrite(level);
        if (ok) return null;
        return "Cannot save LED brightness request";
    }

    /** Idle timeout in seconds. 0 = always on while screen on. */
    public static String setTimeoutSec(Context ctx, int sec) {
        if (sec < 0) sec = 0;
        if (sec > 600) sec = 600;
        boolean ok = writeName(ctx, TIMEOUT_FILE, String.valueOf(sec));
        try {
            if (Settings.System.canWrite(ctx)) {
                Settings.System.putInt(ctx.getContentResolver(), TIMEOUT_SETTINGS_KEY, sec);
                ok = true;
            }
        } catch (Exception ignored) {}
        if (ok) return null;
        return "Cannot save LED timeout";
    }

    public static Integer getRequestedLevel(Context ctx) {
        for (File f : requestFiles(ctx, REQUEST_FILE)) {
            if (f != null && f.isFile()) {
                String v = readLine(f.getPath());
                if (v != null) {
                    try { return Integer.parseInt(v.trim()); } catch (NumberFormatException ignored) {}
                }
            }
        }
        // Fallback to Settings.System (survives control file loss)
        try {
            int v = Settings.System.getInt(ctx.getContentResolver(), SETTINGS_KEY, -1);
            if (v >= 0 && v <= 7) return v;
        } catch (Exception ignored) {}
        return null;
    }

    public static Integer getTimeoutSec(Context ctx) {
        for (File f : requestFiles(ctx, TIMEOUT_FILE)) {
            if (f != null && f.isFile()) {
                String v = readLine(f.getPath());
                if (v != null) {
                    try { return Integer.parseInt(v.trim()); } catch (NumberFormatException ignored) {}
                }
            }
        }
        // Fallback to Settings.System
        try {
            int v = Settings.System.getInt(ctx.getContentResolver(), TIMEOUT_SETTINGS_KEY, -1);
            if (v >= 0) return v;
        } catch (Exception ignored) {}
        return null;
    }

    /** Write the persisted value (from Settings or last control) back to control files.
     *  Call this on app launch and from BootReceiver so the root agent sees the saved setting
     *  even if /data/local/tmp or other temp locations were cleared on reboot.
     */
    public static void restoreToControls(Context ctx) {
        Integer lvl = getRequestedLevel(ctx);
        if (lvl == null) lvl = DEFAULT_LEVEL;
        writeName(ctx, REQUEST_FILE, String.valueOf(lvl));
        // tryDirectWrite removed: daemon owns sysfs for key activity / notif / timeout logic
        // tryDirectWrite(lvl);
        Integer to = getTimeoutSec(ctx);
        if (to == null) to = DEFAULT_TIMEOUT_SEC;
        writeName(ctx, TIMEOUT_FILE, String.valueOf(to));
    }

    public static String statusLine(Context ctx) {
        StringBuilder sb = new StringBuilder();
        Integer req = getRequestedLevel(ctx);
        Integer to = getTimeoutSec(ctx);
        sb.append("Backlight level: ")
            .append(req == null ? "default(mid)" : String.valueOf(req)).append("\n");
        sb.append("Backlight timeout: ")
            .append(to == null ? ("default " + DEFAULT_TIMEOUT_SEC + "s")
                : (to == 0 ? "always on (screen on)" : to + "s idle"))
            .append("\n");
        String st = readLine(STATUS_PATH);
        sb.append("Backlight agent: ").append(st != null ? st.trim() : "(waiting)").append("\n");
        return sb.toString();
    }

    private static void tryDirectWrite(int level) {
        String[] paths = {
            "/sys/devices/platform/keypad_led/keyled_brightness",
            "/sys/class/misc/keypad_led/keyled_brightness",
            "/sys/devices/virtual/misc/keypad_led/keyled_brightness"
        };
        byte[] b = String.valueOf(level).getBytes(StandardCharsets.UTF_8);
        for (String p : paths) {
            try {
                FileOutputStream fos = new FileOutputStream(p);
                fos.write(b);
                fos.close();
                return;
            } catch (IOException ignored) {}
        }
    }

    static String readLine(String path) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(path));
            String line = br.readLine();
            br.close();
            return line;
        } catch (IOException e) {
            return null;
        }
    }
}
