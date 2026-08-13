package com.titanus2.controls;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Optional letter-variation / accent popup on hardware key hold-repeat.
 * <p>
 * <b>Root cause (AOSP):</b> {@code QwertyKeyListener} opens
 * {@code CharacterPickerDialog} on {@code getRepeatCount() > 0} when the
 * listener is not full-keyboard mode — independent of
 * {@code long_press_timeout}. Only characters in AOSP {@code PICKER_SETS}
 * (e.g. "a", "e", "1") open the grid; others (e.g. "h", "b", space) only
 * repeat. That is why hold-"a" felt broken while hold-"h" worked.
 * <p>
 * <b>Product policy (default off):</b>
 * <ul>
 *   <li>{@link TrackpadAccessService} owns OS autorepeat <b>only for
 *       PICKER_SETS chars</b> (swallow + plain re-insert) so the picker
 *       never opens; non-picker keys keep native OS hold-repeat.</li>
 *   <li>{@code long_press_timeout} is still written high (secondary; IME /
 *       soft long-press only).</li>
 * </ul>
 * Enable the hub <b>Letter variations</b> toggle when the user wants the stock
 * accent grid on hold (15.31 restores hub after Pastiera purge left a11y-only
 * kill with no intent control).
 */
public final class LetterVariationsPrefs {
    private static final String PREF = "titan2_letter_variations";
    private static final String KEY_ON = "enabled";
    /** Practical "off" — longer than any reasonable soft/IME long-press. */
    public static final int VARIATIONS_OFF_TIMEOUT_MS = 100_000;
    /** When on: stock-ish soft long-press (must stay above key_repeat_timeout). */
    public static final int VARIATIONS_ON_TIMEOUT_MS = 500;

    /**
     * AOSP {@code QwertyKeyListener.PICKER_SETS} keys (android-34). When the
     * previous caret char is one of these, hold-repeat opens CharacterPicker
     * instead of typing again. Mirror kept small and stable for a11y gate.
     * Note: AOSP omits digits 6/8/9 from sets.
     */
    private static final String AOSP_PICKER_CHARS =
        "ACDEGLINORSTUYZacdeglinorstuyz"
            + "0123457$"
            + "/%*-+()!\"?,=<>";

    private LetterVariationsPrefs() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** Default false — plain key-repeat for picker letters; no CharacterPicker. */
    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ON, false);
    }

    /**
     * When true, a11y may own idle OS autorepeat for {@link #isAospPickerChar}
     * so {@code QwertyKeyListener} never shows CharacterPicker.
     */
    public static boolean suppressCharacterPickerOnRepeat(Context ctx) {
        return ctx != null && !isEnabled(ctx);
    }

    /**
     * True when AOSP would open {@code CharacterPickerDialog} on hold-repeat
     * of this character (previous caret glyph / unicode of the key).
     */
    public static boolean isAospPickerChar(char c) {
        return c != 0 && AOSP_PICKER_CHARS.indexOf(c) >= 0;
    }

    public static void setEnabled(Context ctx, boolean on) {
        if (ctx == null) return;
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply();
        apply(ctx);
    }

    /**
     * Product apply: picker kill is a11y-only ({@link TrackpadAccessService}).
     * Do <b>not</b> rewrite Secure long_press_timeout (FB-IN-3: AOSP 400 for all
     * UI long-holds; 2800/100000 poisons menus).
     */
    public static void apply(Context ctx) {
        // no-op Secure — suppressCharacterPickerOnRepeat is preference-only
    }
}

