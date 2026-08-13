package com.titanus2.controls;

import android.content.Context;
import android.provider.Settings;
import android.view.InputDevice;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Keyboard capacitive surface → pointer policy.
 *
 * Product path (PeterGSI titan2-touchpadd on EEA image):
 *  - Raw pad ignored via system idc (touch.deviceType=ignore)
 *  - ON: boot agent starts /system/bin/titan2-touchpadd (uinput mouse + gestures)
 *  - OFF: agent stops daemon; keys only
 *
 * App does not need sysfs or WRITE_SETTINGS. It writes a request file that
 * phh-on-boot polls every ~1s:
 *   getExternalFilesDir()/titan2_touchpad_enabled  ("0" or "1")
 * Optional: Settings.System titan2_touchpad_enabled if canWrite.
 */
public final class TrackpadHardware {
    public static final String DEVICE_NAME = "touchPad";
    /** Written by app; applied to hardware by boot-root agent. */
    public static final String SETTINGS_KEY = "titan2_touchpad_enabled";
    public static final String REQUEST_FILE = "titan2_touchpad_enabled";

    private TrackpadHardware() {}

    /** True if Android InputManager sees the device (does not need sysfs). */
    public static boolean hardwarePresent() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && DEVICE_NAME.equals(d.getName())) return true;
        }
        return false;
    }

    public static File requestFile(Context ctx) {
        if (ctx == null) return null;
        // Prefer internal files (always writable); agent also reads this path.
        return new File(ctx.getFilesDir(), REQUEST_FILE);
    }

    /** Publish desired state for the boot agent (file always; Settings best-effort). */
    public static boolean writeRequest(Context ctx, boolean enabled) {
        boolean ok = false;
        String payload = enabled ? "1" : "0";
        try {
            FileOutputStream fos = ctx.openFileOutput(REQUEST_FILE, Context.MODE_PRIVATE);
            fos.write(payload.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();
            ok = true;
        } catch (Exception ignored) {}
        // Also external files dir for agent compatibility
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) {
                if (!ext.exists()) ext.mkdirs();
                File f = new File(ext, REQUEST_FILE);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(payload.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.close();
                ok = true;
            }
        } catch (Exception ignored) {}
        try {
            File f = new File(ctx.getFilesDir(), REQUEST_FILE);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(payload.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();
            ok = true;
        } catch (Exception ignored) {}
        try {
            if (Settings.System.canWrite(ctx)) {
                if (Settings.System.putInt(
                        ctx.getContentResolver(), SETTINGS_KEY, enabled ? 1 : 0)) {
                    ok = true;
                }
            }
        } catch (Exception ignored) {}
        return ok;
    }

    public static String findInhibitPath() {
        File input = new File("/sys/class/input");
        File[] nodes = input.listFiles();
        if (nodes == null) return null;
        for (File n : nodes) {
            if (!n.getName().startsWith("input")) continue;
            String name = readFile(new File(n, "name").getPath());
            if (name != null && DEVICE_NAME.equals(name.trim())) {
                File inh = new File(n, "inhibited");
                if (inh.exists()) return inh.getPath();
            }
        }
        return null;
    }

    /**
     * @return true/false if known, null if unknown
     */
    public static Boolean isTrackpadEnabled(Context ctx) {
        String path = findInhibitPath();
        if (path != null) {
            String v = readFile(path);
            if (v != null) return "0".equals(v.trim());
        }
        if (ctx != null) {
            File f = requestFile(ctx);
            if (f != null && f.isFile()) {
                String v = readFile(f.getPath());
                if (v != null) {
                    v = v.trim();
                    if ("1".equals(v)) return true;
                    if ("0".equals(v)) return false;
                }
            }
            try {
                int v = Settings.System.getInt(ctx.getContentResolver(), SETTINGS_KEY, -1);
                if (v == 0) return false;
                if (v == 1) return true;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Request trackpad on/off. Writes agent bridge; tries sysfs if open.
     * @return null on success (request accepted), error string otherwise
     */
    public static String setTrackpadEnabled(Context ctx, boolean enabled) {
        boolean requestOk = writeRequest(ctx, enabled);

        String path = findInhibitPath();
        if (path != null) {
            String val = enabled ? "0" : "1"; // 0 = not inhibited = ON
            try {
                FileOutputStream fos = new FileOutputStream(path);
                fos.write(val.getBytes(StandardCharsets.UTF_8));
                fos.close();
                return null;
            } catch (IOException e) {
                // Agent will apply pad inhibit from control files — no shell/su.
            }
        }

        if (requestOk) {
            if (hardwarePresent()) {
                // Agent applies within ~1s when image has pad agent in phh-on-boot
                return null;
            }
            return "Request saved but touchPad not visible to InputManager.";
        }

        if (!hardwarePresent()) {
            return "touchPad hardware not visible.";
        }
        return "Cannot save pad request (storage). Reinstall app or free storage.";
    }

    /** Legacy no-context API */
    public static String setTrackpadEnabled(boolean enabled) {
        String path = findInhibitPath();
        if (path == null) {
            return hardwarePresent()
                ? "touchPad present but sysfs path unreadable (SELinux). Use app with boot agent."
                : "touchPad not found";
        }
        String val = enabled ? "0" : "1";
        try {
            FileOutputStream fos = new FileOutputStream(path);
            fos.write(val.getBytes(StandardCharsets.UTF_8));
            fos.close();
            return null;
        } catch (Exception e) {
            return "Cannot write sysfs: " + e.getMessage();
        }
    }

    public static String statusLine(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Pad hardware: ")
            .append(hardwarePresent() ? "touchPad present" : "not seen").append("\n");
        Boolean on = isTrackpadEnabled(ctx);
        sb.append("Pad request: ")
            .append(on == null ? "default(off)" : (on ? "ON" : "OFF")).append("\n");
        if (ctx != null) {
            String st = readFile("/data/local/tmp/titan2_pad_status");
            sb.append("Pad agent: ").append(st != null ? st.trim() : "(waiting)").append("\n");
        }
        return sb.toString();
    }

    public static String statusLine() {
        return statusLine(null);
    }

    public static String readFile(String path) {
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
