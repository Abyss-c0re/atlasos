package com.titanus2.controls.notifled;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persistent prefs for keyboard-as-notification-LED.
 * Mirrored to agent control files by {@link NotifLedController}.
 */
public final class NotifLedPrefs {
    public static final String PREFS = "titan2_notif_led";

    public static final String MODE_SOLID = "solid";
    public static final String MODE_BLINK = "blink";
    public static final String MODE_BREATHE = "breathe";

    private NotifLedPrefs() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        // Default OFF — notif LED PWM can glitch USB gadget on Titan 2 while plugged in.
        return p(c).getBoolean("enabled", false);
    }

    public static void setEnabled(Context c, boolean on) {
        p(c).edit().putBoolean("enabled", on).apply();
    }

    public static String getMode(Context c) {
        String m = p(c).getString("mode", MODE_BLINK);
        if (MODE_SOLID.equals(m) || MODE_BLINK.equals(m) || MODE_BREATHE.equals(m)) return m;
        return MODE_BLINK;
    }

    public static void setMode(Context c, String mode) {
        p(c).edit().putString("mode", mode).apply();
    }

    /** Peak brightness 1–7 (sysfs keyled scale). */
    public static int getBrightness(Context c) {
        return clamp(p(c).getInt("brightness", 5), 1, 7);
    }

    public static void setBrightness(Context c, int v) {
        p(c).edit().putInt("brightness", clamp(v, 1, 7)).apply();
    }

    /** Full cycle period in ms (200–5000). */
    public static int getPeriodMs(Context c) {
        return clamp(p(c).getInt("period_ms", 1000), 200, 5000);
    }

    public static void setPeriodMs(Context c, int v) {
        p(c).edit().putInt("period_ms", clamp(v, 200, 5000)).apply();
    }

    /**
     * On-duration within each cycle (ms). 0 = solid (stay on for whole cycle).
     * Capped to period. Used by blink; breathe uses period only.
     */
    public static int getOnMs(Context c) {
        int period = getPeriodMs(c);
        int on = p(c).getInt("on_ms", period / 2);
        if (on < 0) on = 0;
        if (on > period) on = period;
        return on;
    }

    public static void setOnMs(Context c, int v) {
        int period = getPeriodMs(c);
        if (v < 0) v = 0;
        if (v > period) v = period;
        p(c).edit().putInt("on_ms", v).apply();
    }

    /** Apply gaming-keyboard style preset. */
    public static void applyPreset(Context c, String preset) {
        switch (preset) {
            case "solid":
                setMode(c, MODE_SOLID);
                setOnMs(c, 0);
                setPeriodMs(c, 1000);
                break;
            case "blink":
                setMode(c, MODE_BLINK);
                setPeriodMs(c, 800);
                setOnMs(c, 400);
                break;
            case "breathe":
                setMode(c, MODE_BREATHE);
                setPeriodMs(c, 2400);
                setOnMs(c, 0);
                break;
            default:
                break;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
