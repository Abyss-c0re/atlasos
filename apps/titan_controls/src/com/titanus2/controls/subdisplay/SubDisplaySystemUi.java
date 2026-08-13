package com.titanus2.controls.subdisplay;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/** Suppress ambient duplicates; optional main DT2W (settings + kernel wake_gesture). */
public final class SubDisplaySystemUi {
    private static final String TAG = "SubDisplaySystemUi";

    private SubDisplaySystemUi() {}

    public static void apply(Context ctx) {
        Context app = ctx.getApplicationContext();
        SubDisplayPrefs.Mode mode = SubDisplayPrefs.getMode(app);
        boolean dt2w = SubDisplayPrefs.dt2wEnabled(app);
        // Cube / Apps: first-class rear residents — no ambient/clock chrome.
        // Publish contract so SystemUI RROs can hide secondary keyguard clock.
        // Also when cubeOwnsRear (plane tokens) even if prefs still lag.
        if (mode == SubDisplayPrefs.Mode.CUBE || mode == SubDisplayPrefs.Mode.APPS
                || SubDisplayPrefs.cubeOwnsRear(app)) {
            putSecure(app, "doze_always_on", 0);
            putSecure(app, "doze_always_on_wallpaper_enabled", 0);
            putSecure(app, "doze_enabled", 0); // cube: no AOD ambient on lock
            putSecure(app, SubDisplayContract.KEY_SUPPRESS_SYSUI_AOD, 1);
            // DT2W is independent of rear mode — honor user toggle only.
            applyDt2wPolicy(app, dt2w);
            SubDisplayContract.publish(app);
            Log.i(TAG, "cube/apps: suppress ambient mode=" + mode + " dt2w=" + dt2w);
            return;
        }
        if (mode != SubDisplayPrefs.Mode.CUSTOM && mode != SubDisplayPrefs.Mode.STOCK) {
            applyDt2wPolicy(app, dt2w);
            SubDisplayContract.publish(app);
            return;
        }
        putSecure(app, "doze_always_on", 0);
        putSecure(app, "doze_always_on_wallpaper_enabled", 0);
        putSecure(app, "doze_enabled", 1);
        applyDt2wPolicy(app, dt2w);
        SubDisplayContract.publish(app);
        Log.i(TAG, "ambient off, DT2W " + (dt2w ? "on" : "off"));
    }

    /**
     * Apply or clear main double-tap-to-wake (settings + main digitizer wake_gesture).
     * Does <b>not</b> touch {@code wake_gesture_enabled} / ambient tilt — those are
     * lift-to-wake (TYPE_WAKE_GESTURE). Coupling them to DT2W made Lift un-disableable.
     */
    public static void applyDt2wPolicy(Context app, boolean on) {
        int v = on ? 1 : 0;
        putSecure(app, "double_tap_to_wake", v);
        putSystem(app, "double_tap_to_wake", v);
        putSecure(app, "gesture_double_tap", v);
        try {
            android.provider.Settings.Global.putString(
                app.getContentResolver(), "titan2_dt2w", on ? "1" : "0");
        } catch (Exception ignored) {}
        if (on) enableHardwareDt2w();
        else disableHardwareDt2w();
    }

    public static String status(Context ctx) {
        return "aod=" + gS(ctx, "doze_always_on")
            + " dtw=" + gS(ctx, "double_tap_to_wake")
            + " dtw_sys=" + gSys(ctx, "double_tap_to_wake");
    }

    private static void enableHardwareDt2w() {
        String sh =
            "setprop persist.sys.doubletapwake 1; "
            + "for d in /sys/class/input/input*; do "
            + "  n=$(cat \"$d/name\" 2>/dev/null) || continue; "
            + "  [ -f \"$d/wake_gesture\" ] || continue; "
            + "  case \"$n\" in "
            + "    synaptics*|fts*|goodix*|nt36*|focaltech*) echo 1 > \"$d/wake_gesture\";; "
            + "    touchPad|sub_touch) echo 0 > \"$d/wake_gesture\" 2>/dev/null;; "
            + "  esac; "
            + "done";
        shell(sh);
    }

    private static void disableHardwareDt2w() {
        String sh =
            "setprop persist.sys.doubletapwake 0; "
            + "for d in /sys/class/input/input*; do "
            + "  n=$(cat \"$d/name\" 2>/dev/null) || continue; "
            + "  [ -f \"$d/wake_gesture\" ] || continue; "
            + "  case \"$n\" in "
            + "    synaptics*|fts*|goodix*|nt36*|focaltech*|touchPad|sub_touch) "
            + "      echo 0 > \"$d/wake_gesture\" 2>/dev/null;; "
            + "  esac; "
            + "done";
        shell(sh);
    }

    private static String gS(Context ctx, String key) {
        try { return String.valueOf(Settings.Secure.getInt(ctx.getContentResolver(), key)); }
        catch (Exception e) { return "?"; }
    }
    private static String gSys(Context ctx, String key) {
        try { return String.valueOf(Settings.System.getInt(ctx.getContentResolver(), key)); }
        catch (Exception e) { return "?"; }
    }
    private static void putSecure(Context ctx, String key, int val) {
        try { Settings.Secure.putInt(ctx.getContentResolver(), key, val); } catch (Exception ignored) {}
        shell("settings put secure " + key + " " + val);
    }
    private static void putSystem(Context ctx, String key, int val) {
        try { Settings.System.putInt(ctx.getContentResolver(), key, val); } catch (Exception ignored) {}
        shell("settings put system " + key + " " + val);
    }
    private static void putGlobal(Context ctx, String key, int val) {
        try { Settings.Global.putInt(ctx.getContentResolver(), key, val); } catch (Exception ignored) {}
        shell("settings put global " + key + " " + val);
    }
    private static void shell(String cmd) {
        // no su — priv-app Settings + agent only
    }
}
