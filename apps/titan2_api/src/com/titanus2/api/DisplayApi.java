package com.titanus2.api;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Bare-minimum dual-panel API for Titan 2.
 * <p>
 * Main and rear are independent: power, brightness (rear), touch (rear).
 * Writes control-plane files under {@link Titan2ApiContract#OS_CTRL} and
 * {@link Titan2ApiContract#TMP_CTRL}; pad-agent / system helpers apply hardware.
 * No Magisk required on product hybrid when pad-agent + subpanel-bl are present.
 */
public final class DisplayApi {
    private static final String TAG = "DisplayApi";

    public static final String USE_OFF = "off";
    public static final String USE_FACE = "face";
    public static final String USE_TRACKPAD = "trackpad";
    public static final String USE_RAW = "raw";

    private DisplayApi() {}

    public static void setSubOn(Context ctx, boolean on) {
        writePlane(ctx, Titan2ApiContract.FILE_DISPLAY_SUB_ON, on ? "1" : "0");
        putGlobal(ctx, Titan2ApiContract.FILE_DISPLAY_SUB_ON, on ? "1" : "0");
    }

    public static boolean isSubOn(Context ctx) {
        return "1".equals(readPlane(ctx, Titan2ApiContract.FILE_DISPLAY_SUB_ON, "0"));
    }

    public static void setSubBrightness(Context ctx, float bri01) {
        if (bri01 < 0f) bri01 = 0f;
        if (bri01 > 1f) bri01 = 1f;
        String s = String.format(java.util.Locale.US, "%.3f", bri01);
        writePlane(ctx, Titan2ApiContract.FILE_DISPLAY_SUB_BRI, s);
        putGlobal(ctx, Titan2ApiContract.FILE_DISPLAY_SUB_BRI, s);
    }

    /**
     * @param inhibit true = ignore rear touch; false = native trackpad IDC
     */
    public static void setSubTouchInhibit(Context ctx, boolean inhibit) {
        String v = inhibit ? "1" : "0";
        writePlane(ctx, Titan2ApiContract.FILE_SUBTOUCH_INHIBIT, v);
        putGlobal(ctx, Titan2ApiContract.FILE_SUBTOUCH_INHIBIT, v);
    }

    public static boolean isSubTouchInhibited(Context ctx) {
        return !"0".equals(readPlane(ctx, Titan2ApiContract.FILE_SUBTOUCH_INHIBIT, "1"));
    }

    /**
     * High-level rear role. Does not force main panel state.
     */
    public static void setSubUse(Context ctx, String use) {
        if (use == null) use = USE_OFF;
        use = use.toLowerCase(java.util.Locale.US);
        writePlane(ctx, Titan2ApiContract.FILE_DISPLAY_SUB_USE, use);
        putGlobal(ctx, Titan2ApiContract.FILE_DISPLAY_SUB_USE, use);
        switch (use) {
            case USE_TRACKPAD:
                setSubOn(ctx, true);
                setSubTouchInhibit(ctx, false);
                break;
            case USE_FACE:
            case USE_RAW:
                setSubOn(ctx, true);
                setSubTouchInhibit(ctx, true);
                break;
            case USE_OFF:
            default:
                setSubOn(ctx, false);
                setSubTouchInhibit(ctx, true);
                break;
        }
    }

    public static String getSubUse(Context ctx) {
        return readPlane(ctx, Titan2ApiContract.FILE_DISPLAY_SUB_USE, USE_OFF);
    }

    private static void writePlane(Context ctx, String name, String body) {
        if (body == null) body = "";
        for (String dir : new String[] {
                Titan2ApiContract.OS_CTRL,
                Titan2ApiContract.TMP_CTRL,
                ctx != null ? ctx.getFilesDir().getAbsolutePath() : null
        }) {
            if (dir == null) continue;
            try {
                File f = new File(dir, name);
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
                //noinspection ResultOfMethodCallIgnored
                f.setReadable(true, false);
                //noinspection ResultOfMethodCallIgnored
                f.setWritable(true, false);
            } catch (Exception e) {
                Log.d(TAG, "write " + dir + "/" + name + ": " + e.getMessage());
            }
        }
    }

    private static String readPlane(Context ctx, String name, String def) {
        for (String dir : new String[] {
                Titan2ApiContract.OS_CTRL,
                Titan2ApiContract.TMP_CTRL,
                ctx != null ? ctx.getFilesDir().getAbsolutePath() : null
        }) {
            if (dir == null) continue;
            try {
                File f = new File(dir, name);
                if (!f.isFile()) continue;
                byte[] b = new byte[(int) Math.min(f.length(), 64)];
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    int n = in.read(b);
                    if (n > 0) return new String(b, 0, n, StandardCharsets.UTF_8).trim();
                }
            } catch (Exception ignored) {}
        }
        try {
            if (ctx != null) {
                String g = Settings.Global.getString(ctx.getContentResolver(), name);
                if (g != null && !g.isEmpty()) return g.trim();
            }
        } catch (Exception ignored) {}
        return def;
    }

    private static void putGlobal(Context ctx, String key, String val) {
        if (ctx == null) return;
        try {
            Settings.Global.putString(ctx.getContentResolver(), key, val);
        } catch (Exception e) {
            Log.d(TAG, "global " + key + ": " + e.getMessage());
        }
    }
}
