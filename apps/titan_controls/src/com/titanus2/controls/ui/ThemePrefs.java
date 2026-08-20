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
 *   Settings.Global titan2_ui_accent_argb  — glow seed (cube-ux)
 *   Settings.Global titan2_ui_day_night    — day | night | auto
 *   Settings.Global titan2_icon_plate_argb / titan2_icon_glyph_argb
 * Wallpaper image is never written. Color plane is Cube preset.
 */
public final class ThemePrefs {
    private static final String PREF = "titan2_ui_theme";
    public static final String KEY_ACCENT = "accent_argb";
    public static final String KEY_MODE = "day_night";
    public static final String KEY_ICON_PLATE = "icon_plate_argb";
    public static final String KEY_ICON_GLYPH = "icon_glyph_argb";
    public static final String KEY_APP_ICONS = "app_icons_cube";
    public static final String KEY_SETTINGS_MONO = "settings_mono";
    public static final String KEY_SETTINGS_PLATE = "settings_plate_argb";
    public static final String KEY_SETTINGS_GLYPH = "settings_glyph_argb";
    public static final int ICON_PLATE_VOID = 0xFF000000;
    public static final int ICON_PLATE_MESH = 0xFF140308;
    public static final int ICON_PLATE_CAGE = 0xFF8C050D;
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
        setColorSeed(c, argb);
    }

    /**
     * One seed for OS accent and themed app-icon color. Wallpaper image untouched.
     * @return short fact from icons-preset, or fail
     */
    public static String setColorSeed(Context c, int argb) {
        if (c == null) return "fail";
        Context app = c.getApplicationContext();
        int packed = argb | 0xFF000000;
        String hex = String.format("%06x", 0xFFFFFF & packed);
        p(app).edit()
            .putInt(KEY_ACCENT, packed)
            .putInt(KEY_ICON_GLYPH, packed)
            .apply();
        mirrorGlobal(app, "titan2_ui_accent_argb", hex);
        mirrorGlobal(app, "titan2_icon_glyph_argb", hex);
        persistIconOverlay(app, iconPlateHex(app), hex);
        writeThemePreset(app, hex);
        // Settings homepage is its own plane (mono + plate + glyph). Do not
        // stomp it when the human only picks OS accent.
        return "accent";
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
        applyOsPlane(c, true);
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

    /**
     * Seed-once after wipe, or force after a human Glow/Mode pick.
     * Never stomp Wallpaper &amp; style / ThemePicker (VIBRANT, color_index, wallpaper).
     */
    public static String applyOsPlane(Context c) {
        return applyOsPlane(c, false);
    }

    public static String applyOsPlane(Context c, boolean force) {
        if (c == null) return null;
        Context app = c.getApplicationContext();
        String hex = String.format("%06x", 0xFFFFFF & accent(app));
        String mode = dayNight(app);
        String fp = hex + ":" + mode + ":nosq";
        long now = android.os.SystemClock.elapsedRealtime();
        // 12.99: skip no-op full apply (was every a11y ensure → overlay thrash / FC feel)
        if (!force && fp.equals(lastOsPlaneFp) && now - lastOsPlaneElapsed < 120_000L) {
            return "cached";
        }
        lastOsPlaneElapsed = now;
        lastOsPlaneFp = fp;
        String applied = "plane";

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
            Settings.Global.putString(app.getContentResolver(), "titanus2_cube_ux", "15");
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

        // Wallpaper *image* is human SoT. Color plane JSON only here.
        // Never stamp titan2_icon_apply / never run icons-preset from plane —
        // that Magisk/pad-agent loop froze SystemUI (force-stop + 65 overlays).
        applied = "honor";
        if (force) {
            applied = writeThemePreset(app, hex);
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
        "Spike", "Cyan", "Amber", "White", "Green"
    };
    public static final int[] PRESET_COLORS = {
        ACCENT_SPIKE, ACCENT_CYAN, ACCENT_AMBER, ACCENT_WHITE, ACCENT_GREEN
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

    public static int iconPlate(Context c) {
        return p(c).getInt(KEY_ICON_PLATE, ICON_PLATE_MESH);
    }

    public static int iconGlyph(Context c) {
        return p(c).getInt(KEY_ICON_GLYPH, ACCENT_SPIKE);
    }

    public static String iconPlateHex(Context c) {
        return String.format("%06x", 0xFFFFFF & iconPlate(c));
    }

    public static String iconGlyphHex(Context c) {
        return String.format("%06x", 0xFFFFFF & iconGlyph(c));
    }

    public static void setIconPlate(Context c, int argb) {
        setIconOverlay(c, String.format("%06x", 0xFFFFFF & argb), iconGlyphHex(c));
    }

    public static void setIconGlyph(Context c, int argb) {
        setIconOverlay(c, iconPlateHex(c), String.format("%06x", 0xFFFFFF & argb));
    }

    /**
     * Overlay API: write plane then run titan2-cube-icons.sh apply (su).
     * @return short fact
     */
    public static String persistIconOverlay(Context c, String plateHex, String glyphHex) {
        if (c == null) return "fail";
        Context app = c.getApplicationContext();
        if (plateHex == null || plateHex.isEmpty()) plateHex = "140308";
        if (glyphHex == null || glyphHex.isEmpty()) glyphHex = "ff141a";
        plateHex = plateHex.replace("#", "").replace("0x", "").toLowerCase();
        glyphHex = glyphHex.replace("#", "").replace("0x", "").toLowerCase();
        if (plateHex.length() == 8) plateHex = plateHex.substring(2);
        if (glyphHex.length() == 8) glyphHex = glyphHex.substring(2);
        if (plateHex.length() != 6) plateHex = "140308";
        if (glyphHex.length() != 6) glyphHex = "ff141a";
        int plate = (int) (Long.parseLong(plateHex, 16) | 0xFF000000L);
        int glyph = (int) (Long.parseLong(glyphHex, 16) | 0xFF000000L);
        p(app).edit().putInt(KEY_ICON_PLATE, plate).putInt(KEY_ICON_GLYPH, glyph).apply();
        mirrorGlobal(app, "titan2_icon_plate_argb", plateHex);
        mirrorGlobal(app, "titan2_icon_glyph_argb", glyphHex);
        return plateHex + ":" + glyphHex;
    }

    public static String setIconOverlay(Context c, String plateHex, String glyphHex) {
        String persisted = persistIconOverlay(c, plateHex, glyphHex);
        if (persisted.startsWith("fail")) return persisted;
        return runIconOverlayApply();
    }

    public static String runIconOverlayApply() {
        return runIconScript("apply", "icons");
    }

    public static boolean appIconsOn(Context c) {
        return p(c).getBoolean(KEY_APP_ICONS, false);
    }

    public static String setAppIconsOn(Context c, boolean on) {
        p(c).edit().putBoolean(KEY_APP_ICONS, on).apply();
        mirrorGlobal(c, "titan2_icon_apps", on ? "1" : "0");
        return runIconScript(on ? "apps-on" : "apps-off", on ? "apps" : "apps-off");
    }

    public static boolean settingsMonoOn(Context c) {
        return p(c).getBoolean(KEY_SETTINGS_MONO, true);
    }

    public static int settingsPlate(Context c) {
        return p(c).getInt(KEY_SETTINGS_PLATE, ICON_PLATE_VOID);
    }

    public static int settingsGlyph(Context c) {
        return p(c).getInt(KEY_SETTINGS_GLYPH, ACCENT_SPIKE);
    }

    public static String settingsPlateHex(Context c) {
        return String.format("%06x", 0xFFFFFF & settingsPlate(c));
    }

    public static String settingsGlyphHex(Context c) {
        return String.format("%06x", 0xFFFFFF & settingsGlyph(c));
    }

    /**
     * Settings homepage tiles: one plate + one glyph. Crimson = void + spike.
     * Static cubeicon.settings RRO is hardcoded crimson — disable it so the
     * human pick wins.
     */
    public static String persistSettingsOverlay(Context c, String plateHex, String glyphHex) {
        if (c == null) return "fail";
        Context app = c.getApplicationContext();
        if (plateHex == null || plateHex.isEmpty()) plateHex = "000000";
        if (glyphHex == null || glyphHex.isEmpty()) glyphHex = "ff141a";
        plateHex = plateHex.replace("#", "").replace("0x", "").toLowerCase();
        glyphHex = glyphHex.replace("#", "").replace("0x", "").toLowerCase();
        if (plateHex.length() == 8) plateHex = plateHex.substring(2);
        if (glyphHex.length() == 8) glyphHex = glyphHex.substring(2);
        if (plateHex.length() != 6) plateHex = "000000";
        if (glyphHex.length() != 6) glyphHex = "ff141a";
        int plate = (int) (Long.parseLong(plateHex, 16) | 0xFF000000L);
        int glyph = (int) (Long.parseLong(glyphHex, 16) | 0xFF000000L);
        p(app).edit()
            .putInt(KEY_SETTINGS_PLATE, plate)
            .putInt(KEY_SETTINGS_GLYPH, glyph)
            .apply();
        mirrorGlobal(app, "titan2_settings_plate_argb", plateHex);
        mirrorGlobal(app, "titan2_settings_glyph_argb", glyphHex);
        return plateHex + ":" + glyphHex;
    }

    public static String setSettingsMono(Context c, boolean on) {
        if (c == null) return "fail";
        Context app = c.getApplicationContext();
        p(app).edit().putBoolean(KEY_SETTINGS_MONO, on).apply();
        mirrorGlobal(app, "titan2_settings_mono", on ? "1" : "0");
        if (!on) {
            return disableSettingsMono();
        }
        persistSettingsOverlay(app, settingsPlateHex(app), settingsGlyphHex(app));
        return applySettingsIcons(app);
    }

    public static String setSettingsOverlay(Context c, String plateHex, String glyphHex) {
        String persisted = persistSettingsOverlay(c, plateHex, glyphHex);
        if (persisted.startsWith("fail")) return persisted;
        // Human picked a look / pressed Apply — that is mono on.
        p(c).edit().putBoolean(KEY_SETTINGS_MONO, true).apply();
        mirrorGlobal(c, "titan2_settings_mono", "1");
        return applySettingsIcons(c);
    }

    /** Void plate + Cube spike — the crimson look, as a pick, not a force. */
    public static String applyCrimsonSettings(Context c) {
        p(c).edit().putBoolean(KEY_SETTINGS_MONO, true).apply();
        mirrorGlobal(c, "titan2_settings_mono", "1");
        persistSettingsOverlay(c, "000000", "ff141a");
        return applySettingsIcons(c);
    }

    public static String applySettingsIcons(Context c) {
        if (c == null) return "fail";
        Context app = c.getApplicationContext();
        String plate = settingsPlateHex(app);
        String glyph = settingsGlyphHex(app);
        persistSettingsOverlay(app, plate, glyph);
        // Root belt (titan2-sensor-privacy) runs cube-icons settings-on.
        // Controls is not on the KSU allowlist — su from this uid fails.
        stampSettingsWake();
        String sh = runIconScript("settings-on", "settings");
        if (sh != null && sh.startsWith("settings")) {
            return sh;
        }
        String fab = fabricateSettingsMono(glyph, plate);
        if (fab != null && fab.startsWith("settings") && !fab.startsWith("settings 0")) {
            return fab;
        }
        for (int i = 0; i < 24; i++) {
            if (leafIsPlate(plate)) {
                return "settings";
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {
                break;
            }
        }
        return "fail settings";
    }

    private static boolean leafIsPlate(String plateHex) {
        String[][] cmds = {
            {"cmd", "overlay", "lookup", "com.android.settings",
                "com.android.settings:color/m3_ref_palette_yellow90"},
            {"su", "0", "cmd", "overlay", "lookup", "com.android.settings",
                "com.android.settings:color/m3_ref_palette_yellow90"}
        };
        for (String[] cmd : cmds) {
            String out = execOut(cmd, 4);
            if (out == null) continue;
            String hex = out.replace("#", "").replace("0x", "").trim().toLowerCase();
            if (hex.endsWith(plateHex.toLowerCase())) return true;
        }
        return false;
    }

    private static String execOut(String[] cmd, int sec) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            java.io.InputStream in = p.getInputStream();
            byte[] b = new byte[128];
            if (!p.waitFor(sec, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return buf.toString("UTF-8");
        } catch (Exception e) {
            if (p != null) {
                try { p.destroyForcibly(); } catch (Exception ignored) {}
            }
            return null;
        }
    }

    private static void stampSettingsWake() {
        java.io.File[] wakes = {
            new java.io.File("/data/local/tmp/titan2_sp_wake"),
            new java.io.File("/data/misc/titan2/titan2_sp_wake")
        };
        for (java.io.File w : wakes) {
            try {
                java.io.FileWriter fw = new java.io.FileWriter(w, false);
                fw.write("settings_icons");
                fw.close();
            } catch (Exception ignored) {}
        }
    }

    public static String disableSettingsMono() {
        disableHardSettingsRro();
        int n = 0;
        for (String name : SETTINGS_FG) {
            if (disableFab("csimp_" + name)) n++;
        }
        for (String name : SETTINGS_BG) {
            if (disableFab("csimp_" + name)) n++;
        }
        String sh = runIconScript("settings-off", "settings-off");
        return n > 0 ? ("settings-off " + n) : sh;
    }

    private static void disableHardSettingsRro() {
        execOk(new String[]{"cmd", "overlay", "disable", "--user", "current",
            "com.titanus2.overlay.cubeicon.settings"}, 4);
        execOk(new String[]{"su", "0", "cmd", "overlay", "disable", "--user", "current",
            "com.titanus2.overlay.cubeicon.settings"}, 4);
    }

    private static boolean disableFab(String name) {
        String ov = "com.android.shell:" + name;
        boolean ok = execOk(new String[]{"cmd", "overlay", "disable", "--user", "current", ov}, 3);
        if (!ok) {
            ok = execOk(new String[]{"su", "0", "cmd", "overlay", "disable", "--user", "current", ov}, 3);
        }
        return ok;
    }

    public static int termColor(Context c, String key, int fallback) {
        try {
            String raw = Settings.Global.getString(c.getContentResolver(), key);
            if (raw == null || raw.isEmpty() || "null".equalsIgnoreCase(raw.trim())) {
                return fallback;
            }
            String s = raw.trim().replace("#", "").replace("0x", "");
            if (s.length() == 8) s = s.substring(2);
            if (s.length() == 6) return (int) (Long.parseLong(s, 16) | 0xFF000000L);
        } catch (Exception ignored) {}
        return fallback;
    }

    /**
     * Settings homepage fabricate + optional Cube icon RROs follow Atlas term
     * fg/bg. Never writes Wallpaper &amp; style (theme_customization_*).
     */
    public static String matchTerminal(Context c) {
        if (c == null) return "fail";
        Context app = c.getApplicationContext();
        int fg = termColor(app, "titan2_term_fg_argb", ACCENT_SPIKE);
        int bg = termColor(app, "titan2_term_bg_argb", ICON_PLATE_VOID);
        persistIconOverlay(app, String.format("%06x", 0xFFFFFF & bg),
            String.format("%06x", 0xFFFFFF & fg));
        String icons = runIconOverlayApply();
        String apps = appIconsOn(app) ? runIconScript("apps-on", "apps") : "apps-off";
        return "term " + icons + " · " + apps;
    }

    /** Monet on this GSI ignores JSON unless we also fabricate; JSON is still required. */
    private static String writeThemePreset(Context app, String hex) {
        if (hex == null || hex.length() != 6) hex = "ff141a";
        hex = hex.toUpperCase();
        long ts = System.currentTimeMillis();
        String json = "{\"_applied_timestamp\":" + ts
            + ",\"android.theme.customization.theme_style\":\"MONOCHROMATIC\""
            + ",\"android.theme.customization.color_source\":\"preset\""
            + ",\"android.theme.customization.system_palette\":\"" + hex + "\""
            + ",\"android.theme.customization.accent_color\":\"" + hex + "\"}";
        try {
            Settings.Secure.putString(app.getContentResolver(),
                "theme_customization_overlay_packages", json);
            Settings.Secure.putInt(app.getContentResolver(),
                "accessibility_force_invert_color_enabled", 0);
            Settings.Secure.putInt(app.getContentResolver(),
                "accessibility_display_inversion_enabled", 0);
            return "preset";
        } catch (Exception e) {
            return "fail json";
        }
    }

    private static volatile String lastIconHex = "";
    private static volatile long lastIconApplyElapsed;

    /** Settings homepage + icon_accent. One shot. No Magisk, no tmp, no loop. */
    private static final String[] SETTINGS_FG = {
        "homepage_about_foreground", "homepage_accessibility_foreground",
        "homepage_accounts_foreground", "homepage_apps_foreground",
        "homepage_battery_foreground", "homepage_blue_fg",
        "homepage_blue_variant_fg", "homepage_connected_device_foreground",
        "homepage_cyan_fg", "homepage_display_foreground",
        "homepage_green_fg", "homepage_grey_fg",
        "homepage_hub_mode_foreground", "homepage_location_foreground",
        "homepage_modes_foreground", "homepage_network_foreground",
        "homepage_notification_foreground", "homepage_orange_fg",
        "homepage_pink_fg", "homepage_purple_fg", "homepage_red_fg",
        "homepage_safety_foreground", "homepage_security_foreground",
        "homepage_sound_foreground", "homepage_storage_foreground",
        "homepage_supervision_foreground", "homepage_support_foreground",
        "homepage_system_foreground", "homepage_wallpaper_foreground",
        "homepage_yellow_fg", "icon_accent",
        "dark_mode_icon_color_single_tone", "light_mode_icon_color_single_tone",
        "advanced_icon_color", "message_icon_color", "settingslib_colorAccentPrimary"
    };
    private static final String[] SETTINGS_BG = {
        "homepage_about_background", "homepage_accessibility_background",
        "homepage_accounts_background", "homepage_apps_background",
        "homepage_battery_background", "homepage_blue_bg",
        "homepage_blue_variant_bg", "homepage_connected_device_background",
        "homepage_cyan_bg", "homepage_display_background",
        "homepage_generic_icon_background", "homepage_green_bg",
        "homepage_grey_bg", "homepage_hub_mode_background",
        "homepage_location_background", "homepage_modes_background",
        "homepage_network_background", "homepage_notification_background",
        "homepage_orange_bg", "homepage_pink_bg", "homepage_purple_bg",
        "homepage_red_bg", "homepage_safety_background",
        "homepage_security_background", "homepage_sound_background",
        "homepage_storage_background", "homepage_supervision_background",
        "homepage_support_background", "homepage_system_background",
        "homepage_wallpaper_background", "homepage_yellow_bg"
    };
    /**
     * homepage_*_bg/fg are aliases of these M3 leaves. getColor() follows
     * the alias, so overlaying only homepage_yellow_bg leaves Storage yellow.
     */
    private static final String[] SETTINGS_LEAF_BG = {
        "m3_ref_palette_yellow80", "m3_ref_palette_yellow90",
        "m3_ref_palette_green80", "m3_ref_palette_green90",
        "m3_ref_palette_grey80", "m3_ref_palette_grey90",
        "m3_ref_palette_orange80", "m3_ref_palette_orange90",
        "m3_ref_palette_blue80", "m3_ref_palette_blue90",
        "m3_ref_palette_blue_variant80", "m3_ref_palette_blue_variant90",
        "m3_ref_palette_cyan80", "m3_ref_palette_cyan90",
        "m3_ref_palette_pink80", "m3_ref_palette_pink90",
        "m3_ref_palette_purple80", "m3_ref_palette_purple90",
        "m3_ref_palette_red80", "m3_ref_palette_red90"
    };
    private static final String[] SETTINGS_LEAF_FG = {
        "m3_ref_palette_yellow30", "m3_ref_palette_green30",
        "m3_ref_palette_grey30", "m3_ref_palette_orange30",
        "m3_ref_palette_blue30", "m3_ref_palette_blue_variant30",
        "m3_ref_palette_cyan30", "m3_ref_palette_pink30",
        "m3_ref_palette_purple30", "m3_ref_palette_red30"
    };

    /** One fabricate per hex per 30s. Never stamps titan2_icon_apply. */
    private static String applyIconsOnce(Context c, String hex) {
        if (hex == null) hex = "ff141a";
        hex = hex.toLowerCase();
        long now = android.os.SystemClock.elapsedRealtime();
        if (hex.equals(lastIconHex) && now - lastIconApplyElapsed < 30_000L) {
            return "cached";
        }
        lastIconHex = hex;
        lastIconApplyElapsed = now;
        String plate = c != null ? iconPlateHex(c) : "140308";
        return fabricateSettingsMono(hex, plate);
    }

    /**
     * One su shell paints every Settings homepage color. Per-name exec never
     * finished the list (Apply looked like a no-op on tiles below the fold).
     */
    private static String fabricateSettingsMono(String glyphHex, String plateHex) {
        if (glyphHex == null || glyphHex.length() != 6) glyphHex = "ff141a";
        if (plateHex == null || plateHex.length() != 6) plateHex = "000000";
        glyphHex = glyphHex.toLowerCase();
        plateHex = plateHex.toLowerCase();
        StringBuilder sh = new StringBuilder(8 * 1024);
        sh.append("export PATH=/system/bin:/system/xbin:$PATH\n");
        sh.append("cmd overlay disable --user current com.titanus2.overlay.cubeicon.settings >/dev/null 2>&1 || true\n");
        sh.append("P=0xff").append(plateHex).append('\n');
        sh.append("G=0xff").append(glyphHex).append('\n');
        sh.append("ok=0\n");
        sh.append("fab() {\n");
        sh.append("  n=$1; v=$2\n");
        sh.append("  cmd overlay fabricate --target com.android.settings --name csimp_$n \\\n");
        sh.append("    com.android.settings:color/$n 0x1c $v >/dev/null 2>&1 || return 1\n");
        sh.append("  cmd overlay enable --user current com.android.shell:csimp_$n >/dev/null 2>&1 || return 1\n");
        sh.append("  ok=$((ok+1))\n");
        sh.append("}\n");
        for (String n : SETTINGS_BG) {
            sh.append("fab ").append(n).append(" $P\n");
        }
        for (String n : SETTINGS_LEAF_BG) {
            sh.append("fab ").append(n).append(" $P\n");
        }
        for (String n : SETTINGS_FG) {
            sh.append("fab ").append(n).append(" $G\n");
        }
        for (String n : SETTINGS_LEAF_FG) {
            sh.append("fab ").append(n).append(" $G\n");
        }
        sh.append("am force-stop com.android.settings >/dev/null 2>&1 || true\n");
        sh.append("echo settings_$ok\n");
        String out = execSuSh(sh.toString(), 90);
        if (out == null) return "fail settings";
        int cut = out.lastIndexOf("settings_");
        if (cut >= 0) {
            String n = out.substring(cut + 9).trim();
            if (!n.isEmpty() && !n.startsWith("0")) return "settings " + n;
            if ("0".equals(n)) return "fail settings";
            return "settings " + n;
        }
        return out.isEmpty() ? "fail settings" : out;
    }

    private static String execSuSh(String script, int sec) {
        String[][] tries = {
            {"su", "0", "sh"},
            {"su", "-c", "sh"},
            {"sh"}
        };
        for (String[] cmd : tries) {
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(cmd);
                java.io.OutputStream os = p.getOutputStream();
                os.write(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                os.close();
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                java.io.InputStream in = p.getInputStream();
                byte[] b = new byte[256];
                long dead = System.currentTimeMillis() + sec * 1000L;
                while (System.currentTimeMillis() < dead) {
                    if (in.available() > 0) {
                        int n = in.read(b);
                        if (n > 0) buf.write(b, 0, n);
                    }
                    try {
                        p.exitValue();
                        break;
                    } catch (IllegalThreadStateException running) {
                        try { Thread.sleep(40); } catch (InterruptedException ignored) {}
                    }
                }
                if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    continue;
                }
                while (in.available() > 0) {
                    int n = in.read(b);
                    if (n > 0) buf.write(b, 0, n);
                    else break;
                }
                String out = buf.toString("UTF-8").trim();
                if (p.exitValue() == 0 || out.contains("settings_")) return out;
            } catch (Exception e) {
                if (p != null) {
                    try { p.destroyForcibly(); } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    private static boolean execOk(String[] cmd, int sec) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            if (!p.waitFor(sec, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            if (p != null) {
                try { p.destroyForcibly(); } catch (Exception ignored) {}
            }
            return false;
        }
    }

    private static String runIconScript(String verb, String okWord) {
        // Product sysbin only. Magisk/tmp are not a ship path.
        String[] scripts = {
            "/system/bin/titan2-cube-icons.sh"
        };
        String script = null;
        for (String s : scripts) {
            if (new java.io.File(s).isFile()) {
                script = s;
                break;
            }
        }
        if (script == null) return "fail no-script";
        String[][] tries = {
            {"su", "0", "sh", script, verb},
            {"su", "-c", "sh " + script + " " + verb},
            {"sh", script, verb}
        };
        for (String[] cmd : tries) {
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(cmd);
                if (p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                    if (p.exitValue() == 0) return okWord;
                } else {
                    p.destroyForcibly();
                }
            } catch (Exception ignored) {
                if (p != null) {
                    try { p.destroyForcibly(); } catch (Exception ignored2) {}
                }
            }
        }
        return "fail " + verb;
    }

    public static final String[] ICON_PLATE_LABELS = {
        "Void", "Mesh", "Cage"
    };
    public static final int[] ICON_PLATE_COLORS = {
        ICON_PLATE_VOID, ICON_PLATE_MESH, ICON_PLATE_CAGE
    };
    public static final String[] ICON_GLYPH_LABELS = {
        "Spike", "Amber", "White", "Cyan", "Green"
    };
    public static final int[] ICON_GLYPH_COLORS = {
        ACCENT_SPIKE, ACCENT_AMBER, ACCENT_WHITE, ACCENT_CYAN, ACCENT_GREEN
    };

    /** Settings homepage looks: plate+glyph pairs. Crimson = black + spike. */
    public static final String[] SETTINGS_LOOK_LABELS = {
        "Crimson", "Mesh", "Amber", "White"
    };
    public static final int[] SETTINGS_LOOK_PLATES = {
        ICON_PLATE_VOID, ICON_PLATE_MESH, ICON_PLATE_VOID, ICON_PLATE_VOID
    };
    public static final int[] SETTINGS_LOOK_GLYPHS = {
        ACCENT_SPIKE, ACCENT_SPIKE, ACCENT_AMBER, ACCENT_WHITE
    };

    public static int settingsLookIndex(Context c) {
        int plate = settingsPlate(c);
        int glyph = settingsGlyph(c);
        for (int i = 0; i < SETTINGS_LOOK_LABELS.length; i++) {
            if (SETTINGS_LOOK_PLATES[i] == plate && SETTINGS_LOOK_GLYPHS[i] == glyph) {
                return i;
            }
        }
        return -1;
    }
}
