package com.titanus2.controls.ui;

import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * OS-wide plane for cube-ux (accent + day/night). App UI uses DeviceDefault.
 * Look applies the same monochromatic seed as {@code titan2-cube-ux.sh} immediately
 * (intent=result — no reboot wait).
 *
 * Plane:
 *   Settings.Global titan2_ui_accent_argb  — RRGGBB or AARRGGBB hex
 *   Settings.Global titan2_ui_day_night    — day | night | auto
 *   Settings.Secure theme_customization_overlay_packages — MONOCHROMATIC seed
 */
public final class ThemePrefs {
    private static final String PREF = "titan2_ui_theme";
    public static final String KEY_ACCENT = "accent_argb";
    public static final String KEY_MODE = "day_night";
    public static final String MODE_DAY = "day";
    public static final String MODE_NIGHT = "night";
    public static final String MODE_AUTO = "auto";

    /** Leftover OS cyan — not Cube chrome. */
    public static final int ACCENT_CYAN = 0xFF00E5FF;
    /** Cube spike — CubeUI / cubeai SoT (#FF141A). */
    public static final int ACCENT_SPIKE = 0xFFFF141A;
    public static final int ACCENT_AMBER = 0xFFFFB300;
    public static final int ACCENT_WHITE = 0xFFE8E8E8;
    public static final int ACCENT_RED = 0xFFFF1744;
    public static final int ACCENT_GREEN = 0xFF00E676;
    public static final int BODY_BLACK = 0xFF000000;
    public static final int BODY_DAY = 0xFFF2F2F2;

    private ThemePrefs() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static int accent(Context c) {
        int stored = p(c).getInt(KEY_ACCENT, ACCENT_SPIKE);
        // Leftover product cyan is not a human pick — migrate to Cube spike.
        if (stored == ACCENT_CYAN) {
            p(c).edit().putInt(KEY_ACCENT, ACCENT_SPIKE).apply();
            return ACCENT_SPIKE;
        }
        return stored;
    }

    public static void setAccent(Context c, int argb) {
        int packed = argb | 0xFF000000;
        p(c).edit().putInt(KEY_ACCENT, packed).apply();
        // 6-char RRGGBB matches cube-ux seed; 8-char also accepted by cube-ux
        mirrorGlobal(c, "titan2_ui_accent_argb", String.format("%06x", 0xFFFFFF & packed));
        applyOsPlane(c);
    }

    public static String dayNight(Context c) {
        return p(c).getString(KEY_MODE, MODE_NIGHT);
    }

    public static void setDayNight(Context c, String mode) {
        if (mode == null) mode = MODE_NIGHT;
        if (!MODE_DAY.equals(mode) && !MODE_NIGHT.equals(mode) && !MODE_AUTO.equals(mode)) {
            mode = MODE_NIGHT;
        }
        p(c).edit().putString(KEY_MODE, mode).apply();
        mirrorGlobal(c, "titan2_ui_day_night", mode);
        applyOsPlane(c);
    }

    /**
     * Push Look plane into OS chrome now (same seed as hybrid cube-ux boot script).
     * Best-effort: needs WRITE_SECURE_SETTINGS (granted for priv / lab install).
     *
     * @return short mono fact for UI, or null if context null
     */
    private static volatile long lastOsPlaneElapsed;
    private static volatile String lastOsPlaneFp = "";
    /**
     * Overlay disable is throttled (cmd overlay floods OverlayManager).
     * PRODUCT_UX 2026-07-21: square chrome DROPPED — never re-enable Titan*Square*.
     */
    private static volatile long lastOverlayDisableElapsed;

    public static String applyOsPlane(Context c) {
        if (c == null) return null;
        Context app = c.getApplicationContext();
        String hex = String.format("%06x", 0xFFFFFF & accent(app));
        String mode = dayNight(app);
        String fp = hex + ":" + mode + ":nosq";
        long now = android.os.SystemClock.elapsedRealtime();
        // 12.99: skip no-op full apply (was every a11y ensure → overlay thrash / FC feel)
        if (fp.equals(lastOsPlaneFp) && now - lastOsPlaneElapsed < 120_000L) {
            return "cached";
        }
        lastOsPlaneElapsed = now;
        lastOsPlaneFp = fp;
        String applied = "pending";

        try {
            String curA = Settings.Global.getString(app.getContentResolver(),
                "titan2_ui_accent_argb");
            if (curA == null || !hex.equalsIgnoreCase(curA.trim())) {
                Settings.Global.putString(app.getContentResolver(),
                    "titan2_ui_accent_argb", hex);
            }
            String curM = Settings.Global.getString(app.getContentResolver(),
                "titan2_ui_day_night");
            if (curM == null || !mode.equals(curM.trim())) {
                Settings.Global.putString(app.getContentResolver(),
                    "titan2_ui_day_night", mode);
            }
            // Tip pin so install_latest / probes match cube-ux v10 (no square seed).
            Settings.Global.putString(app.getContentResolver(), "titanus2_cube_ux", "10");
        } catch (Exception ignored) {}

        // Night / day / auto via UiModeManager (no shell)
        try {
            UiModeManager um = app.getSystemService(UiModeManager.class);
            if (um != null) {
                if (MODE_DAY.equals(mode)) {
                    um.setNightMode(UiModeManager.MODE_NIGHT_NO);
                    Settings.Secure.putInt(app.getContentResolver(), "ui_night_mode", 1);
                } else if (MODE_AUTO.equals(mode)) {
                    um.setNightMode(UiModeManager.MODE_NIGHT_AUTO);
                    Settings.Secure.putInt(app.getContentResolver(), "ui_night_mode", 0);
                } else {
                    um.setNightMode(UiModeManager.MODE_NIGHT_YES);
                    Settings.Secure.putInt(app.getContentResolver(), "ui_night_mode", 2);
                }
            }
        } catch (Exception ignored) {}

        // Monochromatic black + glow (cube-ux v15) — Cube spike #FF141A.
        // No adaptive_icon_shape / TitanIconShape seed (FGS pill NPE residual).
        String themeJson = "{"
            + "\"android.theme.customization.theme_style\":\"MONOCHROMATIC\","
            + "\"android.theme.customization.color_source\":\"preset\","
            + "\"android.theme.customization.system_palette\":\"" + hex + "\","
            + "\"android.theme.customization.accent_color\":\"" + hex + "\""
            + "}";
        try {
            String got = Settings.Secure.getString(app.getContentResolver(),
                "theme_customization_overlay_packages");
            boolean staleShape = got != null && (
                got.contains("adaptive_icon_shape")
                    || got.contains("com.titanus2.overlay.iconshape")
                    || got.contains("settings_square")
                    || got.contains("systemui_square"));
            if (got == null || !got.contains(hex) || !got.contains("MONOCHROMATIC")
                    || staleShape) {
                Settings.Secure.putString(app.getContentResolver(),
                    "theme_customization_overlay_packages", themeJson);
                got = Settings.Secure.getString(app.getContentResolver(),
                    "theme_customization_overlay_packages");
            }
            if (got != null && got.contains(hex) && !got.contains("adaptive_icon_shape")) {
                applied = "os";
            } else if (got != null && got.contains("MONOCHROMATIC")
                    && !got.contains("adaptive_icon_shape")) {
                applied = "os-mono";
            } else {
                themeJson = themeJson.replace("MONOCHROMATIC", "TONAL_SPOT");
                Settings.Secure.putString(app.getContentResolver(),
                    "theme_customization_overlay_packages", themeJson);
                applied = "os-tonal";
            }
        } catch (Exception e) {
            applied = "denied";
        }

        // PRODUCT_UX drop: disable Titan*Square* leftovers (never enable — FGS residual).
        if (now - lastOverlayDisableElapsed > 600_000L) {
            lastOverlayDisableElapsed = now;
            for (String ov : new String[]{
                "com.titanus2.overlay.iconshape",
                "com.titanus2.overlay.settings_square",
                "com.titanus2.overlay.systemui_square"
            }) {
                try {
                    Runtime.getRuntime().exec(new String[]{
                        "cmd", "overlay", "disable", "--user", "current", ov
                    });
                } catch (Exception ignored) {}
            }
        }

        // Color invert fights monochromatic night cyan (lab saw force_invert=1)
        try {
            Settings.Secure.putInt(app.getContentResolver(),
                "accessibility_force_invert_color_enabled", 0);
            Settings.Secure.putInt(app.getContentResolver(),
                "accessibility_display_inversion_enabled", 0);
        } catch (Exception ignored) {}

        try {
            Settings.Global.putString(app.getContentResolver(), "titanus2_cube_ux_apply",
                System.currentTimeMillis() + ":" + hex + ":" + mode + ":nosq");
        } catch (Exception ignored) {}

        return applied;
    }

    public static int bodyColor(Context c) {
        String m = dayNight(c);
        if (MODE_DAY.equals(m)) return BODY_DAY;
        return BODY_BLACK;
    }

    public static int textPrimary(Context c) {
        return MODE_DAY.equals(dayNight(c)) ? 0xFF111111 : 0xFFF5F5F5;
    }

    public static int textMuted(Context c) {
        return MODE_DAY.equals(dayNight(c)) ? 0xFF555555 : 0xFF9E9E9E;
    }

    /** Preset table for dense UI: label → ARGB */
    public static final String[] PRESET_LABELS = {
        "Cyan", "Amber", "White", "Red", "Green"
    };
    public static final int[] PRESET_COLORS = {
        ACCENT_CYAN, ACCENT_AMBER, ACCENT_WHITE, ACCENT_RED, ACCENT_GREEN
    };

    public static int presetIndex(Context c) {
        int a = accent(c);
        for (int i = 0; i < PRESET_COLORS.length; i++) {
            if (PRESET_COLORS[i] == a) return i;
        }
        return 0;
    }

    private static void mirrorGlobal(Context c, String key, String val) {
        try {
            Settings.Global.putString(c.getContentResolver(), key, val);
        } catch (Exception ignored) {}
    }

    public static String accentHex(Context c) {
        return String.format("#%06X", (0xFFFFFF & accent(c)));
    }
}
