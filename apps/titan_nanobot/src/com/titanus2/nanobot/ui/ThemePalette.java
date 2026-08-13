package com.titanus2.nanobot.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.util.TypedValue;

/**
 * Runtime theme colors for Nanobot — follow system day/night
 * (DeviceDefault.DayNight). Same contract as Controls / HID UiKit.
 * Always resolve against the Activity context theme (not application).
 */
public final class ThemePalette {
    public final int bg;
    public final int panel;
    public final int bubbleUser;
    public final int bubbleAi;
    public final int accent;
    public final int fg;
    public final int mut;
    public final int line;
    public final int onAccent;
    public final int warn;
    public final int okBg;
    public final int badBg;
    public final int warnBg;
    public final boolean night;

    private ThemePalette(Context c) {
        night = isNightMode(c);
        // Fallbacks track night so broken theme attrs still look coherent
        int fbBg = night ? 0xFF121212 : 0xFFFAFAFA;
        int fbFg = night ? 0xFFE0E0E0 : 0xFF212121;
        int fbMut = night ? 0xFFB0B0B0 : 0xFF757575;
        int fbPanel = night ? 0xFF1E1E1E : 0xFFF5F5F5;
        bg = resolve(c, android.R.attr.colorBackground, fbBg);
        panel = resolve(c, android.R.attr.colorBackgroundFloating, fbPanel);
        fg = resolve(c, android.R.attr.textColorPrimary, fbFg);
        mut = resolve(c, android.R.attr.textColorSecondary, fbMut);
        accent = resolve(c, android.R.attr.colorAccent, 0xFF018786);
        line = isDark(bg) ? 0x33FFFFFF : 0x1F000000;
        bubbleUser = blend(bg, fg, 0.08f);
        bubbleAi = blend(bg, fg, 0.04f);
        onAccent = isDark(accent) ? 0xFFFFFFFF : 0xFF00343A;
        warn = night ? 0xFFCF6679 : 0xFFB00020;
        okBg = blend(bg, 0xFF2E7D32, 0.14f);
        badBg = blend(bg, 0xFFB00020, 0.14f);
        warnBg = blend(bg, 0xFFF9A825, 0.16f);
    }

    /** Prefer Activity context so DayNight theme attrs resolve correctly. */
    public static ThemePalette of(Context c) {
        return new ThemePalette(c);
    }

    public static boolean isNightMode(Context c) {
        if (c == null) return false;
        int ui = c.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK;
        return ui == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int resolve(Context c, int attr, int fallback) {
        try {
            TypedValue tv = new TypedValue();
            if (c.getTheme().resolveAttribute(attr, tv, true)) {
                if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                        && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return tv.data;
                }
                if (tv.resourceId != 0) {
                    return c.getResources().getColor(tv.resourceId, c.getTheme());
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private static boolean isDark(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        double l = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return l < 0.55;
    }

    private static int blend(int bg, int fg, float amount) {
        if (amount <= 0f) return bg;
        if (amount >= 1f) return fg;
        int br = (bg >> 16) & 0xFF, bg_ = (bg >> 8) & 0xFF, bb = bg & 0xFF;
        int fr = (fg >> 16) & 0xFF, fg_ = (fg >> 8) & 0xFF, fb = fg & 0xFF;
        int r = Math.round(br + (fr - br) * amount);
        int g = Math.round(bg_ + (fg_ - bg_) * amount);
        int b = Math.round(bb + (fb - bb) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
