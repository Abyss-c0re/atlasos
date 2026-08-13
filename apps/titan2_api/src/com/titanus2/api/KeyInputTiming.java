package com.titanus2.api;

import android.content.Context;
import android.provider.Settings;
import android.view.ViewConfiguration;

/**
 * Key-repeat SoT = <b>stock Android Secure settings</b> (same as Settings →
 * System → Physical keyboard → Repeat keys):
 * <ul>
 *   <li>{@code key_repeat_enabled}</li>
 *   <li>{@code key_repeat_timeout} — delay before spam</li>
 *   <li>{@code key_repeat_delay} — period between repeats</li>
 * </ul>
 * Plane files mirror for HID/pad-agent. Titan UI must not invent a second
 * repeat engine — toggle/sync Secure, deep-link to HARD_KEYBOARD_SETTINGS.
 */
public final class KeyInputTiming {
    public static final String SECURE_REPEAT_ENABLED = "key_repeat_enabled";
    public static final String SECURE_REPEAT_TIMEOUT = "key_repeat_timeout";
    public static final String SECURE_REPEAT_DELAY = "key_repeat_delay";

    public static final int FALLBACK_REPEAT_TIMEOUT_MS = 400;
    public static final int FALLBACK_REPEAT_DELAY_MS = 50;
    public static final int REPEAT_OFF_TIMEOUT_MS = 100_000;
    public static final int MIN_TIMEOUT_MS = 150;
    public static final int MAX_TIMEOUT_MS = 3000;
    public static final int MIN_DELAY_MS = 20;
    public static final int MAX_DELAY_MS = 400;

    private KeyInputTiming() {}

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    /** Stock Secure key_repeat_enabled (default on). */
    public static boolean isKeyRepeatEnabled(Context ctx) {
        if (ctx == null) return true;
        try {
            int v = Settings.Secure.getInt(ctx.getContentResolver(),
                SECURE_REPEAT_ENABLED, 1);
            return v != 0;
        } catch (Throwable t) {
            try {
                String p = ControlPlane.get(ctx,
                    Titan2ApiContract.FILE_KEY_REPEAT_ENABLED, "1");
                return p == null || p.isEmpty() || "1".equals(p.trim())
                    || "true".equalsIgnoreCase(p.trim());
            } catch (Throwable ignored) {
                return true;
            }
        }
    }

    public static int keyRepeatTimeoutMs(Context ctx) {
        if (ctx != null && !isKeyRepeatEnabled(ctx)) {
            return REPEAT_OFF_TIMEOUT_MS;
        }
        if (ctx != null) {
            try {
                int v = Settings.Secure.getInt(ctx.getContentResolver(),
                    SECURE_REPEAT_TIMEOUT, -1);
                if (v >= MIN_TIMEOUT_MS && v <= MAX_TIMEOUT_MS) return v;
            } catch (Throwable ignored) {}
            try {
                String p = ControlPlane.get(ctx,
                    Titan2ApiContract.FILE_KEY_REPEAT_TIMEOUT_MS, null);
                if (p != null && !p.trim().isEmpty()) {
                    return clamp(Integer.parseInt(p.trim()),
                        MIN_TIMEOUT_MS, MAX_TIMEOUT_MS);
                }
            } catch (Throwable ignored) {}
        }
        return keyRepeatTimeoutMs();
    }

    public static int keyRepeatDelayMs(Context ctx) {
        if (ctx != null && !isKeyRepeatEnabled(ctx)) {
            return MAX_DELAY_MS;
        }
        if (ctx != null) {
            try {
                int v = Settings.Secure.getInt(ctx.getContentResolver(),
                    SECURE_REPEAT_DELAY, -1);
                if (v >= MIN_DELAY_MS && v <= MAX_DELAY_MS) return v;
            } catch (Throwable ignored) {}
            try {
                String p = ControlPlane.get(ctx,
                    Titan2ApiContract.FILE_KEY_REPEAT_DELAY_MS, null);
                if (p != null && !p.trim().isEmpty()) {
                    return clamp(Integer.parseInt(p.trim()),
                        MIN_DELAY_MS, MAX_DELAY_MS);
                }
            } catch (Throwable ignored) {}
        }
        return keyRepeatDelayMs();
    }

    public static int keyRepeatTimeoutMs() {
        try {
            int t = ViewConfiguration.getKeyRepeatTimeout();
            if (t >= MIN_TIMEOUT_MS && t <= MAX_TIMEOUT_MS) return t;
        } catch (Throwable ignored) {}
        return FALLBACK_REPEAT_TIMEOUT_MS;
    }

    public static int keyRepeatDelayMs() {
        try {
            int d = ViewConfiguration.getKeyRepeatDelay();
            if (d >= MIN_DELAY_MS && d <= MAX_DELAY_MS) return d;
        } catch (Throwable ignored) {}
        return FALLBACK_REPEAT_DELAY_MS;
    }

    public static int dualResidualDebounceMs() {
        return dualResidualDebounceMs(null);
    }

    public static int dualResidualDebounceMs(Context ctx) {
        int d = keyRepeatDelayMs(ctx);
        return Math.min(40, Math.max(20, d - 10));
    }

    public static int defaultTypingLockCooldownMs() {
        int t = keyRepeatTimeoutMs();
        if (t > 10000) t = FALLBACK_REPEAT_TIMEOUT_MS;
        if (t < 150) return 150;
        if (t > 3000) return 3000;
        return t;
    }

    /**
     * Write stock Secure keys (OEM path) + plane mirror for HID.
     * Values for timeout/delay are the configured rates even when disabled
     * (stock keeps sliders; enable bit gates repeat).
     */
    public static void publish(Context ctx, boolean enabled, int timeoutMs, int delayMs) {
        if (ctx == null) return;
        timeoutMs = clamp(timeoutMs, MIN_TIMEOUT_MS, MAX_TIMEOUT_MS);
        delayMs = clamp(delayMs, MIN_DELAY_MS, MAX_DELAY_MS);
        try {
            ControlPlane.put(ctx, Titan2ApiContract.FILE_KEY_REPEAT_ENABLED,
                enabled ? "1" : "0");
            ControlPlane.put(ctx, Titan2ApiContract.FILE_KEY_REPEAT_TIMEOUT_MS,
                Integer.toString(timeoutMs));
            ControlPlane.put(ctx, Titan2ApiContract.FILE_KEY_REPEAT_DELAY_MS,
                Integer.toString(delayMs));
        } catch (Throwable ignored) {}
        try {
            Settings.Secure.putInt(ctx.getContentResolver(),
                SECURE_REPEAT_ENABLED, enabled ? 1 : 0);
            Settings.Secure.putInt(ctx.getContentResolver(),
                SECURE_REPEAT_TIMEOUT, timeoutMs);
            Settings.Secure.putInt(ctx.getContentResolver(),
                SECURE_REPEAT_DELAY, delayMs);
            // System table used by some OEM InputReader paths
            Settings.System.putInt(ctx.getContentResolver(),
                SECURE_REPEAT_TIMEOUT, timeoutMs);
            Settings.System.putInt(ctx.getContentResolver(),
                SECURE_REPEAT_DELAY, delayMs);
        } catch (Throwable ignored) {}
    }

    /** Sync plane mirror from current Secure (stock UI may have changed values). */
    public static void syncFromSystem(Context ctx) {
        if (ctx == null) return;
        boolean en = isKeyRepeatEnabled(ctx);
        int t = FALLBACK_REPEAT_TIMEOUT_MS;
        int d = FALLBACK_REPEAT_DELAY_MS;
        try {
            t = Settings.Secure.getInt(ctx.getContentResolver(),
                SECURE_REPEAT_TIMEOUT, t);
            d = Settings.Secure.getInt(ctx.getContentResolver(),
                SECURE_REPEAT_DELAY, d);
        } catch (Throwable ignored) {}
        t = clamp(t, MIN_TIMEOUT_MS, MAX_TIMEOUT_MS);
        d = clamp(d, MIN_DELAY_MS, MAX_DELAY_MS);
        try {
            ControlPlane.put(ctx, Titan2ApiContract.FILE_KEY_REPEAT_ENABLED,
                en ? "1" : "0");
            ControlPlane.put(ctx, Titan2ApiContract.FILE_KEY_REPEAT_TIMEOUT_MS,
                Integer.toString(t));
            ControlPlane.put(ctx, Titan2ApiContract.FILE_KEY_REPEAT_DELAY_MS,
                Integer.toString(d));
        } catch (Throwable ignored) {}
    }

    /** Boot: ensure plane mirrors Secure (do not clobber stock with prefs). */
    public static void applyStored(Context ctx) {
        syncFromSystem(ctx);
    }
}
