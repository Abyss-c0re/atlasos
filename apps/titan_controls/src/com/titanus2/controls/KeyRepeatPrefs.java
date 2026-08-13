package com.titanus2.controls;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import com.titanus2.api.KeyInputTiming;

/**
 * Thin product facade over <b>stock</b> Physical keyboard → Repeat keys.
 * <p>
 * Does not reimplement delay/rate UI — those live in
 * {@code Settings$PhysicalKeyboardActivity}. Titan only:
 * <ul>
 *   <li>toggles {@code key_repeat_enabled} (synced with system)</li>
 *   <li>deep-links to HARD_KEYBOARD_SETTINGS for shortcuts / delay / rate</li>
 *   <li>mirrors Secure → plane for HID via {@link KeyInputTiming}</li>
 * </ul>
 */
public final class KeyRepeatPrefs {
    /** Stock Settings activity action. */
    public static final String ACTION_HARD_KEYBOARD =
        "android.settings.HARD_KEYBOARD_SETTINGS";

    private KeyRepeatPrefs() {}

    public static boolean isEnabled(Context ctx) {
        return KeyInputTiming.isKeyRepeatEnabled(ctx);
    }

    /** Enable/disable stock key repeat (Secure key_repeat_enabled + plane). */
    public static void setEnabled(Context ctx, boolean on) {
        if (ctx == null) return;
        // Keep current timeout/delay from system when flipping enable
        KeyInputTiming.syncFromSystem(ctx);
        int t = KeyInputTiming.keyRepeatTimeoutMs(ctx);
        int d = KeyInputTiming.keyRepeatDelayMs(ctx);
        if (t > KeyInputTiming.MAX_TIMEOUT_MS) {
            t = KeyInputTiming.FALLBACK_REPEAT_TIMEOUT_MS;
        }
        KeyInputTiming.publish(ctx, on, t, d);
    }

    /** Re-read Secure → plane (call when hub resumes / a11y binds). */
    public static void syncFromSystem(Context ctx) {
        KeyInputTiming.syncFromSystem(ctx);
    }

    /** Publish current Secure values to plane (alias). */
    public static void publish(Context ctx) {
        syncFromSystem(ctx);
    }

    /** Open stock Physical keyboard settings (shortcuts, modifiers, repeat). */
    public static void openSystemPhysicalKeyboardSettings(Context ctx) {
        if (ctx == null) return;
        try {
            Intent i = new Intent(ACTION_HARD_KEYBOARD);
            i.setPackage("com.android.settings");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception e2) {
                try {
                    Intent i = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Exception ignored) {}
            }
        }
    }

    /** Summary line for hub (e.g. "On · 200 ms / 25 ms"). */
    public static String summary(Context ctx) {
        if (ctx == null) return "";
        if (!isEnabled(ctx)) return "Off";
        int t = KeyInputTiming.keyRepeatTimeoutMs(ctx);
        int d = KeyInputTiming.keyRepeatDelayMs(ctx);
        if (t > 10000) t = KeyInputTiming.FALLBACK_REPEAT_TIMEOUT_MS;
        return "On · " + t + " ms / " + d + " ms";
    }
}
