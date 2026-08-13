package com.titanus2.controls.subdisplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * Exclusive sub-display mode + custom face look (dense mono face, not giant GSI clock).
 * <p>
 * Cube mode is sacred: Face/AOD/keyguard clocks must never cover the rear lattice.
 */
public final class SubDisplayPrefs {
    public static final String PREFS = "titan2_subdisplay";

    public enum Mode {
        OFF, STOCK, CUSTOM, APPS, CUBE;

        public static Mode from(String s) {
            if (s == null) return OFF;
            switch (s.toUpperCase()) {
                case "STOCK": case "AOD": case "SYSTEM": case "ON":
                    // OS rear lockscreen clock is cut; always use our face.
                    return CUSTOM;
                case "CUSTOM": case "FACE": case "WIDGETS": case "REAR": return CUSTOM;
                // 15.4: independent apps-on-rear (digitizer + associate), not face clock.
                case "APPS": case "APP": case "LAUNCHER": case "TOUCH":
                case "INTERACTIVE": case "OEM":
                    return APPS;
                case "CUBE": case "BRAIN": case "LATTICE": case "NEURAL":
                    return CUBE;
                case "INPUT": case "TRACKPAD": case "PAD": return OFF; // retired
                default: return OFF;
            }
        }

        public String label() {
            switch (this) {
                case STOCK: return "OS clock";
                case CUSTOM: return "Face";
                case APPS: return "Apps";
                case CUBE: return "Cube";
                default: return "Off";
            }
        }
    }

    /** Face layout. */
    public enum FaceStyle {
        /** Time dominant, meta below — classic pager. */
        CLASSIC,
        /** Dense status strip + time. */
        STATUS,
        /** Time only. */
        MINIMAL;

        public static FaceStyle from(String s) {
            if (s == null) return CLASSIC;
            switch (s.toLowerCase()) {
                case "status": case "bb": case "dense": return STATUS;
                case "minimal": case "min": return MINIMAL;
                default: return CLASSIC;
            }
        }
    }

    /**
     * Product night cyan — matches UiKit / cube-ux seed {@code #00E5FF}.
     * Not Material TealA200 ({@code #64FFDA}).
     */
    public static final int NIGHT_CYAN = 0xFF00E5FF;

    /** Ink on pure black OLED. */
    public enum FaceTheme {
        MONO, PHOSPHOR, AMBER, CYAN, RED, BLUE, CUSTOM;

        public static FaceTheme from(String s) {
            if (s == null) return CYAN;
            switch (s.toLowerCase()) {
                case "phosphor": case "green": return PHOSPHOR;
                case "amber": case "gold": return AMBER;
                case "cyan": case "teal": case "night": return CYAN;
                case "red": case "rose": return RED;
                case "blue": case "ice": return BLUE;
                case "custom": case "user": return CUSTOM;
                case "mono": case "white": return MONO;
                default: return CYAN;
            }
        }

        public int ink() {
            switch (this) {
                case PHOSPHOR: return 0xFF33FF66;
                case AMBER: return 0xFFFFB000;
                case CYAN: return NIGHT_CYAN;
                case RED: return 0xFFFF5252;
                case BLUE: return 0xFF82B1FF;
                case CUSTOM: return NIGHT_CYAN; // resolved via customInk()
                default: return 0xFFF0F0F0;
            }
        }

        public int mutedFrom(int ink) {
            int r = (ink >> 16) & 0xFF, g = (ink >> 8) & 0xFF, b = ink & 0xFF;
            return 0xFF000000 | ((r * 55 / 100) << 16) | ((g * 55 / 100) << 8) | (b * 55 / 100);
        }

        public int dimFrom(int ink) {
            int r = (ink >> 16) & 0xFF, g = (ink >> 8) & 0xFF, b = ink & 0xFF;
            return 0xFF000000 | ((r * 25 / 100) << 16) | ((g * 25 / 100) << 8) | (b * 25 / 100);
        }

        public int muted() { return mutedFrom(ink()); }
        public int dim() { return dimFrom(ink()); }
    }

    private SubDisplayPrefs() {}

    /** Package that hosts rear live cube mesh + contact UI. */
    public static final String CUBE_PKG = "com.titanus2.cubecontact";

    /** True when Cube Contact is installed (user or system/priv-app). */
    public static boolean cubeAppInstalled(Context c) {
        if (c == null) return false;
        try {
            c.getPackageManager().getPackageInfo(CUBE_PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Mode getMode(Context c) {
        SharedPreferences sp = p(c);
        if (sp.contains("mode")) return Mode.from(sp.getString("mode", "off"));
        if (sp.getBoolean("on", false)) return Mode.STOCK;
        return Mode.OFF;
    }

    public static void setMode(Context c, Mode mode) {
        if (mode == null) mode = Mode.OFF;
        // commit() so boot races / FaceOverlay cannot re-read stale "custom"
        // after Cube was selected (apply() is async — dual-clock residual).
        p(c).edit()
            .putString("mode", mode.name().toLowerCase())
            .putBoolean("on", mode != Mode.OFF)
            .commit();
        // Mirror plane tokens for pad-agent + sacred-cube guards.
        try {
            String sm = mode == Mode.OFF ? "off"
                : (mode == Mode.APPS ? "apps"
                    : (mode == Mode.CUBE ? "cube" : "face"));
            Settings.Global.putString(c.getContentResolver(), "titan2_sub_mode", sm);
            Settings.Secure.putString(c.getContentResolver(), "titan2_sub_mode", sm);
            if (mode == Mode.CUBE || mode == Mode.APPS) {
                Settings.Secure.putInt(c.getContentResolver(),
                    SubDisplayContract.KEY_SUPPRESS_SYSUI_AOD, 1);
            }
        } catch (Exception ignored) {}
    }

    /**
     * True when rear must stay free of Face/AOD/keyguard clocks.
     * Prefs mode, Secure/Global plane, or CubeBridge "wanted" flag.
     */
    public static boolean cubeOwnsRear(Context c) {
        if (c == null) return false;
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        if (getMode(app) == Mode.CUBE) return true;
        if (SubDisplayCubeBridge.isWanted()) return true;
        try {
            String g = Settings.Global.getString(app.getContentResolver(), "titan2_sub_mode");
            if (g != null) {
                String u = g.trim().toLowerCase();
                if ("cube".equals(u) || "lattice".equals(u) || "brain".equals(u)
                        || "neural".equals(u)) {
                    return true;
                }
            }
            String s = Settings.Secure.getString(app.getContentResolver(), "titan2_sub_mode");
            if (s != null) {
                String u = s.trim().toLowerCase();
                if ("cube".equals(u) || "lattice".equals(u) || "brain".equals(u)
                        || "neural".equals(u)) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean isOn(Context c) {
        return getMode(c) != Mode.OFF;
    }

    @Deprecated
    public static void setOn(Context c, boolean on) {
        if (!on) setMode(c, Mode.OFF);
        else if (getMode(c) == Mode.OFF) setMode(c, Mode.STOCK);
    }

    public static int getBrightnessPct(Context c) {
        return Math.max(1, Math.min(100, p(c).getInt("bri", 50)));
    }

    public static void setBrightnessPct(Context c, int pct) {
        p(c).edit().putInt("bri", Math.max(1, Math.min(100, pct))).apply();
    }

    public static int getDimBrightnessPct(Context c) {
        return Math.max(0, Math.min(100, p(c).getInt("dim_bri", 30)));
    }

    public static void setDimBrightnessPct(Context c, int pct) {
        p(c).edit().putInt("dim_bri", Math.max(0, Math.min(100, pct))).apply();
    }

    public static int getTimeoutSec(Context c) {
        return Math.max(0, Math.min(600, p(c).getInt("timeout_sec", 45)));
    }

    public static void setTimeoutSec(Context c, int sec) {
        p(c).edit().putInt("timeout_sec", Math.max(0, Math.min(600, sec))).apply();
    }

    // --- face look ---
    /** Default STATUS = dense strip + time (Cube night face). */
    public static FaceStyle getFaceStyle(Context c) {
        return FaceStyle.from(p(c).getString("face_style", "status"));
    }

    public static void setFaceStyle(Context c, FaceStyle s) {
        p(c).edit().putString("face_style", s.name().toLowerCase()).apply();
    }

    /** Default CYAN = product night accent on black. */
    public static FaceTheme getFaceTheme(Context c) {
        return FaceTheme.from(p(c).getString("face_theme", "cyan"));
    }

    public static void setFaceTheme(Context c, FaceTheme t) {
        p(c).edit().putString("face_theme", t.name().toLowerCase()).apply();
    }

    /**
     * Clock size on rear: 0=XS … 4=XL. Always width-capped to the 410px panel.
     * Default 1 (S) — SystemUI stock clock was oversized on this display.
     */
    public static int getClockScale(Context c) {
        int v = p(c).getInt("clock_scale", 1);
        // migrate old 0-2 only prefs: leave as-is
        return Math.max(0, Math.min(4, v));
    }

    public static void setClockScale(Context c, int s) {
        p(c).edit().putInt("clock_scale", Math.max(0, Math.min(4, s))).apply();
    }

    public static String clockScaleLabel(Context c) {
        return new String[]{"XS", "S", "M", "L", "XL"}[getClockScale(c)];
    }

    public static boolean hour24(Context c) {
        return p(c).getBoolean("hour24", true);
    }

    public static void setHour24(Context c, boolean v) {
        p(c).edit().putBoolean("hour24", v).apply();
    }

    public static boolean widgetDate(Context c) {
        return p(c).getBoolean("w_date", true);
    }

    public static void setWidgetDate(Context c, boolean v) {
        p(c).edit().putBoolean("w_date", v).apply();
    }

    public static boolean widgetBattery(Context c) {
        return p(c).getBoolean("w_battery", true);
    }

    public static void setWidgetBattery(Context c, boolean v) {
        p(c).edit().putBoolean("w_battery", v).apply();
    }

    public static boolean widgetWeekday(Context c) {
        return p(c).getBoolean("w_weekday", true);
    }

    public static void setWidgetWeekday(Context c, boolean v) {
        p(c).edit().putBoolean("w_weekday", v).apply();
    }

    public static boolean widgetSeconds(Context c) {
        return p(c).getBoolean("w_seconds", false);
    }

    public static void setWidgetSeconds(Context c, boolean v) {
        p(c).edit().putBoolean("w_seconds", v).apply();
    }

    public static boolean widgetLine(Context c) {
        return false; // option removed from UI
    }

    public static void setWidgetLine(Context c, boolean v) {
        p(c).edit().putBoolean("w_line", v).apply();
    }

    /**
     * Plane value for pad-agent {@code titan2_subtouch_inhibit}.
     * <p>
     * 15.0 product lock: always {@code 1} — rear is display-only. Sub-as-trackpad
     * abandoned (dual-cursor / palm residual with main pad). Prefs flag kept
     * cleared so cool land never re-opens surface=sub.
     */
    public static String subtouchInhibitValue(Context c) {
        return "1";
    }

    /**
     * Always false — rear panel is never a pointer (15.0 no-rear-pointer).
     * Legacy pref key is force-cleared on set.
     */
    public static boolean rearTouchEnabled(Context c) {
        return false;
    }

    public static void setRearTouchEnabled(Context c, boolean v) {
        // Product abandon: ignore enable requests; clear sticky pref.
        p(c).edit().putBoolean("rear_touch", false).apply();
    }

    /** @deprecated use {@link #rearTouchEnabled}; kept for callers. */
    @Deprecated
    public static boolean rearTouchAsTrackpad(Context c) {
        return false;
    }

    public static void setRearTouchAsTrackpad(Context c, boolean v) {
        setRearTouchEnabled(c, false);
    }

    /**
     * When false (default with Stock/Custom face): force SystemUI ambient/AoD off
     * so the OS cyan clock does not stack on main or rear.
     * When true: leave SystemUI doze settings alone (lab / dual-experiment).
     */
    public static boolean allowSystemUiAod(Context c) {
        return p(c).getBoolean("allow_sysui_aod", false);
    }

    public static void setAllowSystemUiAod(Context c, boolean v) {
        p(c).edit().putBoolean("allow_sysui_aod", v).apply();
    }

    /** User ink ARGB when theme=CUSTOM. Default product night cyan. */
    public static int customInk(Context c) {
        return p(c).getInt("custom_ink", NIGHT_CYAN);
    }

    public static void setCustomInk(Context c, int argb) {
        p(c).edit().putInt("custom_ink", 0xFF000000 | (argb & 0x00FFFFFF)).apply();
    }

    public static int inkColor(Context c) {
        FaceTheme th = getFaceTheme(c);
        if (th == FaceTheme.CUSTOM) return customInk(c);
        return th.ink();
    }

    public static int mutedColor(Context c) {
        return getFaceTheme(c).mutedFrom(inkColor(c));
    }

    public static int dimColor(Context c) {
        return getFaceTheme(c).dimFrom(inkColor(c));
    }

    /** Custom face: notification icons strip on rear. */
    public static boolean widgetNotifs(Context c) {
        return p(c).getBoolean("w_notifs", true);
    }

    public static void setWidgetNotifs(Context c, boolean v) {
        p(c).edit().putBoolean("w_notifs", v).apply();
    }

    /**
     * Main-panel double-tap-to-wake. Optional product toggle (default on).
     * When off: settings + kernel wake_gesture left disabled; rear power path
     * still works via Controls / side keys / Cube mode.
     */
    public static boolean dt2wEnabled(Context c) {
        return p(c).getBoolean("dt2w", true);
    }

    public static void setDt2wEnabled(Context c, boolean v) {
        p(c).edit().putBoolean("dt2w", v).apply();
        try {
            // Plane for pad-agent apply_dt2w (1|0).
            android.provider.Settings.Global.putString(
                c.getContentResolver(), "titan2_dt2w", v ? "1" : "0");
            android.provider.Settings.Secure.putString(
                c.getContentResolver(), "titan2_dt2w", v ? "1" : "0");
        } catch (Exception ignored) {}
        try {
            com.titanus2.controls.AgentBridge.put(c, "titan2_dt2w", v ? "1" : "0");
        } catch (Exception ignored) {}
    }

    /**
     * After any main wake (DT2W), blank the main panel and keep rear clock only.
     * Default true — this is what DT2W dual-wake breaks without.
     */
    public static boolean blankMainOnWake(Context c) {
        return p(c).getBoolean("blank_main_on_wake", false); // NEVER default on — caused main flicker
    }

    public static void setBlankMainOnWake(Context c, boolean v) {
        p(c).edit().putBoolean("blank_main_on_wake", v).apply();
    }

    /** While main screen is off: show rear (true) or leave rear dark (false). Unlock always turns rear off. */
    public static boolean keepRearWhenOff(Context c) {
        return p(c).getBoolean("keep_rear_when_off", true);
    }

    public static void setKeepRearWhenOff(Context c, boolean v) {
        p(c).edit().putBoolean("keep_rear_when_off", v).apply();
    }

    /** How many distinct apps on the rear strip: 1–6 (default 3). */
    public static int notifMaxApps(Context c) {
        return Math.max(1, Math.min(6, p(c).getInt("notif_max_apps", 3)));
    }

    public static void setNotifMaxApps(Context c, int n) {
        p(c).edit().putInt("notif_max_apps", Math.max(1, Math.min(6, n))).apply();
    }

    /**
     * Icon size on rear: 0=XS … 4=XL.
     * Maps to fraction of panel width.
     */
    public static int notifIconScale(Context c) {
        return Math.max(0, Math.min(4, p(c).getInt("notif_icon_scale", 2)));
    }

    public static void setNotifIconScale(Context c, int s) {
        p(c).edit().putInt("notif_icon_scale", Math.max(0, Math.min(4, s))).apply();
    }

    public static String notifIconScaleLabel(Context c) {
        return new String[]{"XS", "S", "M", "L", "XL"}[notifIconScale(c)];
    }

    /** Pixel size for notif icons given panel width. */
    public static int notifIconPx(Context c, int panelW) {
        float[] fr = {0.10f, 0.12f, 0.15f, 0.18f, 0.22f};
        int px = Math.round(panelW * fr[notifIconScale(c)]);
        return Math.max(16, Math.min(panelW / 3, px));
    }

}

