package com.titanus2.controls;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

/**
 * Soft IME vs Titan hardware keyboard + product fallback IME (AOSP LatinIME).
 * <p>
 * Secure {@code show_ime_with_hard_keyboard}: 0 = hide soft panel when HW
 * keyboard is present (product default); 1 = allow soft IME while using HW.
 * Toggle: Tweaks → Keyboard → Soft keyboard.
 * <p>
 * <b>User IME choice is sacred.</b> LatinIME is only the empty/broken default
 * heal — never wipe {@code enabled_input_methods} or force default away from
 * Gboard/Pastiera/any sideload. (2026-08-03: removed sole-LatinIME pin.)
 */
public final class ImeHwPrefs {
    public static final String SECURE_SHOW_IME_WITH_HW = "show_ime_with_hard_keyboard";
    private static final String PREF = "titan2_ime_hw";
    /** User allows soft IME with HW keyboard. Default false (hide). */
    private static final String KEY_WANT_SOFT_WITH_HW = "want_soft_ime_with_hw";

    /** Soft multi-press timeout (ms). */
    public static final int MULTI_PRESS_TIMEOUT_MS = 400;

    public static final String LATIN_IME_PACKAGE = "com.android.inputmethod.latin";
    public static final String LATIN_IME_RRO =
        "com.android.inputmethod.latin.auto_generated_rro_product__";
    public static final String LATIN_IME =
        "com.android.inputmethod.latin/.LatinIME";

    private ImeHwPrefs() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** True when soft IME may show while a hardware keyboard is attached. */
    public static boolean softImeWithHw(Context ctx) {
        if (ctx == null) return false;
        try {
            return Settings.Secure.getInt(ctx.getContentResolver(),
                SECURE_SHOW_IME_WITH_HW, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @param allowSoft true → show soft IME with HW; false → hide panel with HW
     */
    public static void setSoftImeWithHw(Context ctx, boolean allowSoft) {
        if (ctx == null) return;
        prefs(ctx).edit().putBoolean(KEY_WANT_SOFT_WITH_HW, allowSoft).apply();
        // Only write Secure when the value actually changes — repeated putInt
        // restarts LatinIME insets animation (IME panel flicker on scrcpy/lab).
        putShowImeWithHwIfChanged(ctx, allowSoft ? 1 : 0);
        if (!allowSoft) {
            hideSoftInput(ctx);
        }
    }

    /**
     * Write {@code show_ime_with_hard_keyboard} only if different from current.
     * Returns true when a write happened (caller may hide panel on 0).
     */
    public static boolean putShowImeWithHwIfChanged(Context ctx, int value) {
        if (ctx == null) return false;
        int want = value != 0 ? 1 : 0;
        try {
            int cur = Settings.Secure.getInt(ctx.getContentResolver(),
                SECURE_SHOW_IME_WITH_HW, -1);
            if (cur == want) return false;
            Settings.Secure.putInt(ctx.getContentResolver(),
                SECURE_SHOW_IME_WITH_HW, want);
            return true;
        } catch (Exception e) {
            try {
                Settings.Secure.putInt(ctx.getContentResolver(),
                    SECURE_SHOW_IME_WITH_HW, want);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    /**
     * Boot / hub / package-replace: re-apply stored preference + LatinIME pin.
     * Skips forcing Secure off while exclusive HID grab is live (FB-HID-2 temp
     * soft IME for phone editors while HW keys stay host-owned).
     */
    public static void applyStored(Context ctx) {
        if (ctx == null) return;
        boolean exclusiveGrab = false;
        try {
            exclusiveGrab = HostLayoutController.isHidExclusiveLiveFast(ctx);
        } catch (Exception ignored) {}
        boolean want = prefs(ctx).getBoolean(KEY_WANT_SOFT_WITH_HW, false);
        boolean wrote;
        if (exclusiveGrab) {
            // Temp allow soft IME; do not restore user hide while exclusive
            wrote = putShowImeWithHwIfChanged(ctx, 1);
        } else {
            wrote = putShowImeWithHwIfChanged(ctx, want ? 1 : 0);
            if (!want) {
                // Always hide when product wants HW-only — even if Secure already 0
                // (LatinIME can leave a half-visible insets leash after focus thrash).
                hideSoftInput(ctx);
            }
        }
        // Avoid heavy IME list rewrite on every a11y heartbeat; polish throttles.
        if (wrote || exclusiveGrab != lastExclusiveSample) {
            lastExclusiveSample = exclusiveGrab;
        }
        applyHwTypingPolish(ctx);
    }

    /** Last exclusive sample for edge-aware polish (no extra Secure writes). */
    private static volatile boolean lastExclusiveSample;

    private static volatile long lastPolishElapsed;

    /**
     * AOSP default for {@code Settings.Secure.long_press_timeout} (ms).
     * Used by {@link android.view.ViewConfiguration#getLongPressTimeout()} for
     * <b>all</b> UI long-holds (menus, lists, text), not only IME accents.
     */
    public static final int LONG_PRESS_TIMEOUT_MS = 400;

    /**
     * Product stamp that previously forced long_press_timeout (REG-L accent
     * menu). That Secure key is global UI — 2800ms made every long-hold feel
     * broken (FB-IN-3). Heal back to AOSP default; do not re-raise.
     */
    private static final int LEGACY_LONG_PRESS_POISON_MS = 2800;

    /**
     * Product: multi_press polish. Does <b>not</b> raise system long_press
     * timeout (that broke UI long-hold). Letter→special long-hold stays off.
     */
    public static void applyHwTypingPolish(Context ctx) {
        if (ctx == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        try {
            int mpt = Settings.Secure.getInt(ctx.getContentResolver(),
                "multi_press_timeout", 0);
            if (mpt != MULTI_PRESS_TIMEOUT_MS) {
                Settings.Secure.putInt(ctx.getContentResolver(),
                    "multi_press_timeout", MULTI_PRESS_TIMEOUT_MS);
            }
        } catch (Exception ignored) {}
        // Letter variations / CharacterPicker: a11y owns kill (PICKER_SETS);
        // long_press_timeout does NOT gate HW picker. Never force 2800/100000.
        try { LetterVariationsPrefs.apply(ctx); } catch (Exception ignored) {}
        // FB-IN-3: Secure long_press_timeout is ViewConfiguration for ALL UI.
        // Prior product stamp (2800) made long-hold on any UI element unusable.
        // Heal our poison; leave other user values alone; never re-raise to 2800.
        try {
            int lpt = Settings.Secure.getInt(ctx.getContentResolver(),
                "long_press_timeout", LONG_PRESS_TIMEOUT_MS);
            if (lpt == LEGACY_LONG_PRESS_POISON_MS || lpt <= 0 || lpt >= 10_000) {
                Settings.Secure.putInt(ctx.getContentResolver(),
                    "long_press_timeout", LONG_PRESS_TIMEOUT_MS);
            }
        } catch (Exception ignored) {}
        // Heavy IME list rewrite at most once per 2 minutes
        if (now - lastPolishElapsed < 120_000L) return;
        lastPolishElapsed = now;
        try {
            KeyRepeatPrefs.syncFromSystem(ctx);
        } catch (Exception ignored) {}
        try {
            int sk = Settings.Secure.getInt(ctx.getContentResolver(), "show_key_presses", 0);
            if (sk != 0) {
                Settings.Secure.putInt(ctx.getContentResolver(), "show_key_presses", 0);
            }
            Settings.System.putInt(ctx.getContentResolver(), "show_key_presses", 0);
        } catch (Exception ignored) {}
        // Do NOT force-hide IME selector / status-bar switcher — user must be
        // able to pick Gboard / Pastiera / any IME without product thrash.
        // Empty default only: ensure LatinIME exists as a usable fallback.
        try {
            String def = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
            boolean empty = def == null || def.isEmpty()
                || "null".equalsIgnoreCase(def);
            if (empty) {
                enablePackageUser(ctx, LATIN_IME_PACKAGE);
                enablePackageUser(ctx, LATIN_IME_RRO);
                String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_INPUT_METHODS);
                if (enabled == null || enabled.isEmpty()
                        || "null".equalsIgnoreCase(enabled)) {
                    Settings.Secure.putString(ctx.getContentResolver(),
                        Settings.Secure.ENABLED_INPUT_METHODS, LATIN_IME);
                } else if (!enabled.contains(LATIN_IME_PACKAGE)) {
                    // Keep user list; append LatinIME as optional fallback.
                    Settings.Secure.putString(ctx.getContentResolver(),
                        Settings.Secure.ENABLED_INPUT_METHODS,
                        enabled + ":" + LATIN_IME);
                }
                Settings.Secure.putString(ctx.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD, LATIN_IME);
            }
            // Non-empty default = user (or system) choice — leave alone.
        } catch (Exception ignored) {}
    }

    private static void enablePackageUser(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        try {
            PackageManager pm = ctx.getPackageManager();
            if (pm == null) return;
            try {
                pm.getPackageInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException e) {
                return;
            }
            int state = pm.getApplicationEnabledSetting(pkg);
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setApplicationEnabledSetting(pkg,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0);
            }
        } catch (Exception ignored) {}
    }

    /** Dismiss soft IME if a window token is available. */
    public static void hideSoftInput(Context ctx) {
        if (ctx == null) return;
        try {
            InputMethodManager imm = (InputMethodManager)
                ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return;
            if (ctx instanceof Activity) {
                View v = ((Activity) ctx).getWindow().getDecorView();
                if (v != null) {
                    imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                }
            }
        } catch (Exception ignored) {}
    }
}
