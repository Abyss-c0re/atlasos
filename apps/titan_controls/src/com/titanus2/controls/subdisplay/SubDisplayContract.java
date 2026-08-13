package com.titanus2.controls.subdisplay;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/**
 * GSI contract between TitanControls (priv-app) and SystemUI / pad-agent.
 * <p>
 * No Magisk required when:
 * <ul>
 *   <li>TitanControls is system priv-app with WRITE_SECURE_SETTINGS</li>
 *   <li>pad-agent runs as init service (already in hybrid)</li>
 *   <li>titan2-subpanel-bl is on /system/bin</li>
 *   <li>SystemUI (optional) reads these Secure keys for rear AoD sizing</li>
 * </ul>
 *
 * Keys live in {@link Settings.Secure} so SystemUI can read without binder IPC.
 */
public final class SubDisplayContract {
    private static final String TAG = "SubDisplayContract";

    /** off | stock | custom */
    public static final String KEY_MODE = "titan2_sub_mode";
    /** 0–4 clock scale for rear (SystemUI or our face). */
    public static final String KEY_CLOCK_SCALE = "titan2_sub_clock_scale";
    /** Active brightness percent 1–100. */
    public static final String KEY_BRI = "titan2_sub_bri";
    /** Idle brightness percent 0–100. */
    public static final String KEY_DIM_BRI = "titan2_sub_dim_bri";
    /** Timeout seconds to idle; 0 = never. */
    public static final String KEY_TIMEOUT_SEC = "titan2_sub_timeout_sec";
    /** 1 = suppress SystemUI ambient on all displays (we own rear face). */
    public static final String KEY_SUPPRESS_SYSUI_AOD = "titan2_sub_suppress_sysui_aod";
    /** Target display id hint (default 2). */
    public static final String KEY_DISPLAY_ID = "titan2_sub_display_id";
    /** mono | phosphor | amber (custom). */
    public static final String KEY_THEME = "titan2_sub_theme";
    /** classic | status | minimal */
    public static final String KEY_STYLE = "titan2_sub_style";

    private SubDisplayContract() {}

    /** Publish prefs → Secure settings for SystemUI + diagnostics. */
    public static void publish(Context ctx) {
        Context app = ctx.getApplicationContext();
        SubDisplayPrefs.Mode mode = SubDisplayPrefs.getMode(app);
        put(app, KEY_MODE, mode.name().toLowerCase());
        putInt(app, KEY_CLOCK_SCALE, SubDisplayPrefs.getClockScale(app));
        putInt(app, KEY_BRI, SubDisplayPrefs.getBrightnessPct(app));
        putInt(app, KEY_DIM_BRI, SubDisplayPrefs.getDimBrightnessPct(app));
        putInt(app, KEY_TIMEOUT_SEC, SubDisplayPrefs.getTimeoutSec(app));
        // Face owns rear chrome only when Custom/Stock. Cube/Apps: suppress
        // SystemUI ambient/keyguard clocks so nothing obstructs the resident.
        boolean face = mode == SubDisplayPrefs.Mode.STOCK || mode == SubDisplayPrefs.Mode.CUSTOM;
        boolean cubeOrApps = mode == SubDisplayPrefs.Mode.CUBE || mode == SubDisplayPrefs.Mode.APPS;
        boolean suppress = cubeOrApps || (face && !SubDisplayPrefs.allowSystemUiAod(app));
        putInt(app, KEY_SUPPRESS_SYSUI_AOD, suppress ? 1 : 0);
        putInt(app, KEY_DISPLAY_ID, 2);
        put(app, KEY_THEME, SubDisplayPrefs.getFaceTheme(app).name().toLowerCase());
        put(app, KEY_STYLE, SubDisplayPrefs.getFaceStyle(app).name().toLowerCase());
        Log.i(TAG, "published mode=" + mode + " scale=" + SubDisplayPrefs.getClockScale(app));
    }

    private static void put(Context ctx, String key, String val) {
        try {
            Settings.Secure.putString(ctx.getContentResolver(), key, val);
        } catch (Exception e) {
            Log.d(TAG, "put " + key + ": " + e.getMessage());
        }
    }

    private static void putInt(Context ctx, String key, int val) {
        try {
            Settings.Secure.putInt(ctx.getContentResolver(), key, val);
        } catch (Exception e) {
            Log.d(TAG, "putInt " + key + ": " + e.getMessage());
        }
    }
}
