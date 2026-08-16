package com.titanus2.usbhid;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * HID chrome: follow system day/night, or a user override.
 * Does not force black or white.
 */
final class HidTheme {
    static final int SYSTEM = 0;
    static final int DARK = 1;
    static final int LIGHT = 2;
    private static final String PREF = "ui_theme";

    private HidTheme() {}

    static int mode(Context c) {
        if (c == null) return SYSTEM;
        int m = c.getSharedPreferences("usb_hid", Context.MODE_PRIVATE).getInt(PREF, SYSTEM);
        return (m == DARK || m == LIGHT) ? m : SYSTEM;
    }

    static void setMode(Context c, int mode) {
        if (c == null) return;
        if (mode != DARK && mode != LIGHT) mode = SYSTEM;
        c.getSharedPreferences("usb_hid", Context.MODE_PRIVATE).edit().putInt(PREF, mode).apply();
    }

    static int cycle(Context c) {
        int next = (mode(c) + 1) % 3;
        setMode(c, next);
        return next;
    }

    static String label(int mode) {
        if (mode == DARK) return "Dark";
        if (mode == LIGHT) return "Light";
        return "System";
    }

    /** Apply override before Activity.super.attachBaseContext. */
    static Context wrap(Context base) {
        if (base == null) return null;
        int mode = mode(base);
        if (mode == SYSTEM) return base;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        int night = mode == DARK
            ? Configuration.UI_MODE_NIGHT_YES
            : Configuration.UI_MODE_NIGHT_NO;
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return base.createConfigurationContext(cfg);
    }
}
