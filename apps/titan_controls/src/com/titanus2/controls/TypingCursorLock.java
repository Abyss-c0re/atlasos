package com.titanus2.controls;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Freeze pad/mouse while typing (global Controls option).
 * <p>
 * <b>SoT plane:</b> {@link com.titanus2.api.Titan2ApiContract#FILE_PAD_CURSOR_PAUSE}
 * via {@link com.titanus2.api.InputPlane}. pad-agent and USB HID both honor that
 * plane — do not invent a second freeze path for Sym inject or HID.
 * <p>
 * Call {@link #pulse} from <b>all</b> phone typing paths: idle letters, Sym
 * specials inject, and a11y text changes. Global enable + cooldown live here.
 */
public final class TypingCursorLock {
    public static final String PLANE = com.titanus2.api.Titan2ApiContract.FILE_PAD_CURSOR_PAUSE;
    private static final String PREF = "titan2_typing_cursor";
    private static final String KEY_ON = "enabled";
    private static final String KEY_COOLDOWN_MS = "cooldown_ms";
    /**
     * Fallback only when prefs empty — prefer
     * {@link com.titanus2.api.KeyInputTiming#defaultTypingLockCooldownMs()}.
     * UI Unlock delay is 100…3000 ms; agent honors the same ms (2.149).
     */
    public static final int DEFAULT_COOLDOWN_MS = 500;
    public static final int MIN_MS = 100;
    public static final int MAX_MS = 5000;
    /** Sticky cool-down SoT for pad-agent (survives pause=0 between keys). */
    public static final String COOL_PLANE = "titan2_pad_cursor_cool_ms";

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Runnable clearTask;
    /** Avoid rewriting plane on every key (was heat + pad/mouse lag). */
    private static volatile boolean planePaused;
    /** While locked, refresh TTL at most this often (every key was laggy). */
    private static final long TTL_REFRESH_MIN_MS = 150L;
    private static volatile long lastTtlRefreshMs;

    private TypingCursorLock() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** Product default on (QA requested). */
    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ON, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply();
        // Always clear plane when toggling — residual typing_lock=1 with pause=0
        // left pad inhibited / status sticky after Magisk wipe thrash (2026-07-19).
        clear(ctx);
    }

    public static int cooldownMs(Context ctx) {
        int def = DEFAULT_COOLDOWN_MS;
        try {
            def = com.titanus2.api.KeyInputTiming.defaultTypingLockCooldownMs();
        } catch (Throwable ignored) {}
        int v = prefs(ctx).getInt(KEY_COOLDOWN_MS, def);
        if (v < MIN_MS) return MIN_MS;
        if (v > MAX_MS) return MAX_MS;
        return v;
    }

    public static void setCooldownMs(Context ctx, int ms) {
        if (ms < MIN_MS) ms = MIN_MS;
        if (ms > MAX_MS) ms = MAX_MS;
        prefs(ctx).edit().putInt(KEY_COOLDOWN_MS, ms).apply();
        if (ctx == null) return;
        // Always publish sticky cool — pad-agent unlock must match UI selection.
        publishCool(ctx, ms);
        if (planePaused || isPaused(ctx)) {
            try {
                com.titanus2.api.InputPlane.put(ctx,
                    com.titanus2.api.Titan2ApiContract.FILE_PAD_CURSOR_PAUSE_MS,
                    Integer.toString(ms));
                long untilSec = (System.currentTimeMillis() + ms + 999L) / 1000L;
                com.titanus2.api.InputPlane.put(ctx,
                    com.titanus2.api.Titan2ApiContract.FILE_PAD_CURSOR_PAUSE_UNTIL,
                    Long.toString(untilSec));
            } catch (Exception e) {
                AgentBridge.put(ctx, "titan2_pad_cursor_pause_ms", Integer.toString(ms));
            }
            scheduleClear(ctx, ms);
        }
    }

    private static void publishCool(Context ctx, int ms) {
        try {
            com.titanus2.api.InputPlane.put(ctx, COOL_PLANE, Integer.toString(ms));
        } catch (Exception e) {
            AgentBridge.put(ctx, COOL_PLANE, Integer.toString(ms));
        }
    }

    public static boolean isPaused(Context ctx) {
        return planePaused || "1".equals(AgentBridge.get(ctx, PLANE, "0"));
    }

    /**
     * Arm/refresh typing lock for any phone key activity (idle typing <b>and</b>
     * Sym specials inject). Honors global enable; no-ops when exclusive HID owns mouse.
     */
    public static void pulse(Context ctx) {
        if (ctx == null || !isEnabled(ctx)) return;
        // SoT: InputPlane — never freeze pad when host owns mouse (HID exclusive /
        // intercepted mouse / Moonlight disconnect thrash).
        try {
            if (!com.titanus2.api.InputPlane.allowTypingCursorLock(ctx)) return;
        } catch (Exception ignored) {
            try {
                // 13.14: never dumpsys on typing pulse hot path
                if (HostLayoutController.isHidExclusiveLiveFast(ctx)) return;
            } catch (Exception ignored2) {}
        }
        // Honor Titan Controls "Unlock delay" exactly — no mouse/trackpad floor.
        int cool = cooldownMs(ctx);
        long now = android.os.SystemClock.uptimeMillis();
        // 12.77 / 13.63 / 15.75: first arm writes pause=1; while locked only
        // reschedule clear + refresh TTL every TTL_REFRESH_MIN_MS (not every key —
        // dual InputPlane.put per letter felt like slow terminal typing).
        if (!planePaused) {
            planePaused = true;
            lastTtlRefreshMs = now;
            publishCool(ctx, cool);
            try {
                if (!com.titanus2.api.InputPlane.tryArmCursorPause(ctx, cool)) {
                    planePaused = false;
                    return;
                }
            } catch (Exception e) {
                AgentBridge.put(ctx, PLANE, "1");
                AgentBridge.put(ctx, "titan2_pad_cursor_pause_ms", Integer.toString(cool));
            }
            scheduleClear(ctx, cool);
            return;
        }
        // Already locked: always extend Handler clear; plane TTL throttled.
        scheduleClear(ctx, cool);
        if (now - lastTtlRefreshMs < TTL_REFRESH_MIN_MS) return;
        lastTtlRefreshMs = now;
        publishCool(ctx, cool);
        try {
            com.titanus2.api.InputPlane.put(ctx,
                com.titanus2.api.Titan2ApiContract.FILE_PAD_CURSOR_PAUSE_MS,
                Integer.toString(cool));
            long untilSec = (System.currentTimeMillis() + cool + 999L) / 1000L;
            com.titanus2.api.InputPlane.put(ctx,
                com.titanus2.api.Titan2ApiContract.FILE_PAD_CURSOR_PAUSE_UNTIL,
                Long.toString(untilSec));
        } catch (Exception e) {
            AgentBridge.put(ctx, "titan2_pad_cursor_pause_ms", Integer.toString(cool));
        }
    }

    public static void clear(Context ctx) {
        if (ctx == null) return;
        if (clearTask != null) {
            H.removeCallbacks(clearTask);
            clearTask = null;
        }
        planePaused = false;
        try {
            com.titanus2.api.InputPlane.clearCursorPause(ctx);
        } catch (Exception e) {
            AgentBridge.put(ctx, PLANE, "0");
        }
    }

    private static void scheduleClear(Context ctx, int ms) {
        final Context app = ctx.getApplicationContext();
        if (clearTask != null) H.removeCallbacks(clearTask);
        clearTask = () -> {
            planePaused = false;
            try {
                com.titanus2.api.InputPlane.clearCursorPause(app);
            } catch (Exception e) {
                AgentBridge.put(app, PLANE, "0");
            }
            clearTask = null;
        };
        H.postDelayed(clearTask, ms);
    }

    /**
     * Arm freeze when focus enters an editable field (open text box → pad must
     * not stay as a moving pointer). Do <b>not</b> arm on every TEXT_CHANGED
     * (that re-pulsed forever and never unlocked — 13.63).
     * HW keys + Sym still call {@link #pulse} on each press.
     */
    public static void onA11yEvent(Context ctx, AccessibilityEvent ev) {
        if (ctx == null || ev == null) return;
        int t = ev.getEventType();
        if (t != AccessibilityEvent.TYPE_VIEW_FOCUSED
                && t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        android.view.accessibility.AccessibilityNodeInfo src = null;
        try {
            src = ev.getSource();
            if (src == null) return;
            if (!src.isEditable() && !src.isPassword()) return;
            // Focus entered a text field — freeze pad (cool-down from prefs).
            pulse(ctx);
        } catch (Exception ignored) {
        } finally {
            if (src != null) {
                try { src.recycle(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * User is actively using the trackpad/mouse — unlock immediately.
     * Shared SoT: pad-agent also TTL-expires and may call the same plane clear.
     */
    public static void onPadActivity(Context ctx) {
        if (ctx == null) return;
        if (!planePaused && !"1".equals(AgentBridge.get(ctx, PLANE, "0"))) return;
        clear(ctx);
    }
}
