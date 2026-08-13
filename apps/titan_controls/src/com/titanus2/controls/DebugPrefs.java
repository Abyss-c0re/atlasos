package com.titanus2.controls;

import android.content.Context;
import android.content.SharedPreferences;

import android.provider.Settings;

/**
 * Lab-only debug feedback. Default <b>off</b>.
 * <p>Key-press popup = system {@code show_key_presses} (right-edge labels like
 * APP_SWITCH / UNKNOWN during lab fixing) — <b>not</b> a second toast path.
 * Toggle under Titan Controls → Dev → Debug.
 */
public final class DebugPrefs {
    public static final String PREFS = "titan2_debug";
    /** Android System setting name (AOSP/Lineage show-key-presses overlay). */
    public static final String SHOW_KEY_PRESSES = "show_key_presses";
    private static final String KEY_LAYOUT_TOASTS = "layout_toasts";
    private static final String KEY_UNKNOWN_ACTION_TOASTS = "unknown_action_toasts";

    private final Context app;
    private final SharedPreferences p;

    public DebugPrefs(Context ctx) {
        app = ctx.getApplicationContext();
        p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Right-edge system key-press labels (show_key_presses). Default off.
     * @deprecated name kept for callers; prefer {@link #keyPressPopup()}.
     */
    public boolean keyNameToasts() {
        return keyPressPopup();
    }

    public void setKeyNameToasts(boolean on) {
        setKeyPressPopup(on);
    }

    /** Right-edge pressed-key popup (system). */
    public boolean keyPressPopup() {
        try {
            return Settings.System.getInt(app.getContentResolver(),
                SHOW_KEY_PRESSES, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    public void setKeyPressPopup(boolean on) {
        try {
            Settings.System.putInt(app.getContentResolver(),
                SHOW_KEY_PRESSES, on ? 1 : 0);
        } catch (Exception ignored) {}
        // Some builds also honor Secure namespace for the same flag.
        try {
            Settings.Secure.putInt(app.getContentResolver(),
                SHOW_KEY_PRESSES, on ? 1 : 0);
        } catch (Exception ignored) {}
        // Remember intent so boot can re-apply if something else flips the setting
        p.edit().putBoolean("want_key_press_popup", on).apply();
    }

    /**
     * Force system key-press labels off unless user opted in.
     * When on, every physical key shows a right-edge “letter menu” label and
     * typing is effectively unusable (lab 2026-07-16).
     */
    public static void ensureDefaultOff(Context ctx) {
        if (ctx == null) return;
        DebugPrefs d = new DebugPrefs(ctx);
        if (!d.p.getBoolean("want_key_press_popup", false)) {
            d.setKeyPressPopup(false);
        }
    }

    /** Layout held / off toast. Default off. */
    public boolean layoutToasts() {
        return p.getBoolean(KEY_LAYOUT_TOASTS, false);
    }

    public void setLayoutToasts(boolean on) {
        p.edit().putBoolean(KEY_LAYOUT_TOASTS, on).apply();
    }

    /** "Unknown: …" action toast. Default off. */
    public boolean unknownActionToasts() {
        return p.getBoolean(KEY_UNKNOWN_ACTION_TOASTS, false);
    }

    public void setUnknownActionToasts(boolean on) {
        p.edit().putBoolean(KEY_UNKNOWN_ACTION_TOASTS, on).apply();
    }

    public static boolean keyNameToasts(Context ctx) {
        return ctx != null && new DebugPrefs(ctx).keyPressPopup();
    }

    public static boolean layoutToasts(Context ctx) {
        return ctx != null && new DebugPrefs(ctx).layoutToasts();
    }

    public static boolean unknownActionToasts(Context ctx) {
        return ctx != null && new DebugPrefs(ctx).unknownActionToasts();
    }
}
