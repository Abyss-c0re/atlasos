package com.titanus2.api;

import android.content.Context;
import android.provider.Settings;

/**
 * Single source of truth for Titan pad + HID keyboard/mouse ownership.
 * <p>
 * Both <b>Titan Controls</b> and <b>USB HID</b> must read/write input state here
 * (or via names in {@link Titan2ApiContract}). pad-agent polls the same files
 * under {@link Titan2ApiContract#OS_CTRL} / {@link Titan2ApiContract#TMP_CTRL}.
 * <p>
 * Ownership rules:
 * <ul>
 *   <li><b>Pad mode</b> — Controls primary; HID may request regrab, not invent mode.</li>
 *   <li><b>HID session/grab/keys/mouse</b> — HID FGS is owner while session live.</li>
 *   <li><b>keys_pause / inject_pause</b> — Controls a11y while exclusive; HID honors.</li>
 *   <li><b>pad_cursor_pause</b> — typing lock (Controls TypingCursorLock.pulse from
 *       idle letters <b>and</b> Sym specials); must not arm when host owns mouse.</li>
 *   <li><b>Key timing</b> — {@link KeyInputTiming} for stock key-repeat / dual residual
 *       (shared by specials hold-spam; do not re-hardcode 400/50 in apps).</li>
 * </ul>
 */
public final class InputPlane {
    private InputPlane() {}

    public static boolean isOn(String v) {
        if (v == null) return false;
        v = v.trim();
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v)
            || "yes".equalsIgnoreCase(v);
    }

    public static boolean isOffToken(String v) {
        if (v == null || v.isEmpty()) return true;
        v = v.trim().toLowerCase();
        return "0".equals(v) || "off".equals(v) || "false".equals(v) || "none".equals(v)
            || "inherit".equals(v);
    }

    /** Write plane file + Settings.Global mirror for HID/bench readers. */
    public static boolean put(Context ctx, String name, String value) {
        if (value == null) value = "";
        boolean ok = ControlPlane.put(ctx, name, value);
        if (ctx != null && isGlobalName(name)) {
            try {
                Settings.Global.putString(ctx.getContentResolver(), name, value);
                ok = true;
            } catch (Exception ignored) {}
        }
        return ok;
    }

    public static String get(Context ctx, String name, String def) {
        String v = ControlPlane.get(ctx, name, null);
        if (v != null && !v.isEmpty()) return v;
        if (ctx != null && isGlobalName(name)) {
            try {
                String g = Settings.Global.getString(ctx.getContentResolver(), name);
                if (g != null && !g.isEmpty()) return g.trim();
            } catch (Exception ignored) {}
        }
        return def;
    }

    private static boolean isGlobalName(String name) {
        if (name == null) return false;
        if (name.startsWith("titan2_usb_hid_")) return true;
        if (name.startsWith("titan2_host_")) return true;
        switch (name) {
            case Titan2ApiContract.FILE_PAD_MODE:
            case Titan2ApiContract.FILE_PAD_CURSOR_PAUSE:
            case Titan2ApiContract.FILE_PAD_CURSOR_PAUSE_MS:
            case Titan2ApiContract.FILE_PAD_CURSOR_PAUSE_UNTIL:
            case Titan2ApiContract.FILE_INJECT_PAUSE:
            case Titan2ApiContract.FILE_SPECIALS_METHOD:
            case Titan2ApiContract.FILE_KEYS_PAUSE:
            case Titan2ApiContract.FILE_KEYS_PAUSE_TWIN:
            case Titan2ApiContract.FILE_HOST_LAYOUT:
                return true;
            default:
                return false;
        }
    }

    // ---- Pad ----

    public static String padMode(Context ctx) {
        return PadModes.normalize(get(ctx, Titan2ApiContract.FILE_PAD_MODE,
            Titan2ApiContract.MODE_OFF));
    }

    public static void setPadMode(Context ctx, String mode) {
        mode = PadModes.normalize(mode);
        put(ctx, Titan2ApiContract.FILE_PAD_MODE, mode);
        put(ctx, "titan2_touchpad_enabled",
            Titan2ApiContract.MODE_MOUSE.equals(mode) ? "1" : "0");
    }

    public static boolean isCursorPaused(Context ctx) {
        return isOn(get(ctx, Titan2ApiContract.FILE_PAD_CURSOR_PAUSE, "0"));
    }

    public static void setCursorPaused(Context ctx, boolean on) {
        put(ctx, Titan2ApiContract.FILE_PAD_CURSOR_PAUSE, on ? "1" : "0");
    }

    /**
     * Arm typing cursor lock plane (physical pad + HID host mouse freeze).
     * Callers that own UI prefs (Controls {@code TypingCursorLock}) decide
     * <b>whether</b> to arm; this is the shared write path only.
     * Never arms when exclusive host mouse owns the pad — see
     * {@link #allowTypingCursorLock}.
     *
     * @param unlockAfterMs published for pad-agent TTL auto-expire (manual UI delay)
     */
    public static boolean tryArmCursorPause(Context ctx, int unlockAfterMs) {
        if (ctx == null || !allowTypingCursorLock(ctx)) return false;
        if (unlockAfterMs < 50) unlockAfterMs = 50;
        if (unlockAfterMs > 10_000) unlockAfterMs = 10_000;
        put(ctx, Titan2ApiContract.FILE_PAD_CURSOR_PAUSE_MS,
            Integer.toString(unlockAfterMs));
        long untilSec = (System.currentTimeMillis() + unlockAfterMs + 999L) / 1000L;
        put(ctx, Titan2ApiContract.FILE_PAD_CURSOR_PAUSE_UNTIL,
            Long.toString(untilSec));
        setCursorPaused(ctx, true);
        return true;
    }

    /** @see #tryArmCursorPause(Context, int) with default stock timeout */
    public static boolean tryArmCursorPause(Context ctx) {
        int ms = 400;
        try { ms = KeyInputTiming.defaultTypingLockCooldownMs(); } catch (Throwable ignored) {}
        return tryArmCursorPause(ctx, ms);
    }

    /**
     * Clear typing cursor lock plane (pad unfreeze).
     * 13.63: also zero pause_ms/until so pad-agent TTL residual cannot
     * re-expire or mis-sample after manual/pad-contact unlock.
     */
    public static void clearCursorPause(Context ctx) {
        if (ctx == null) return;
        setCursorPaused(ctx, false);
        put(ctx, Titan2ApiContract.FILE_PAD_CURSOR_PAUSE_MS, "0");
        put(ctx, Titan2ApiContract.FILE_PAD_CURSOR_PAUSE_UNTIL, "0");
    }

    // ---- HID session ----

    public static boolean isSessionOn(Context ctx) {
        return isOn(get(ctx, Titan2ApiContract.FILE_HID_SESSION, "0"));
    }

    public static boolean isGrabOn(Context ctx) {
        return isOn(get(ctx, Titan2ApiContract.FILE_HID_GRAB, "0"));
    }

    public static boolean isMouseOn(Context ctx) {
        return isOn(get(ctx, Titan2ApiContract.FILE_HID_MOUSE, "0"));
    }

    public static boolean isKeysPlaneOn(Context ctx) {
        return isOn(get(ctx, Titan2ApiContract.FILE_HID_KEYS, "0"));
    }

    /** Exclusive host session: session + grab (share mode is session without grab). */
    public static boolean isExclusive(Context ctx) {
        return isSessionOn(ctx) && isGrabOn(ctx);
    }

    /** Host owns physical pad/mouse stream. */
    public static boolean isHostMouseLive(Context ctx) {
        return isSessionOn(ctx) && isMouseOn(ctx);
    }

    public static String hostLayout(Context ctx) {
        String l = get(ctx, Titan2ApiContract.FILE_HOST_LAYOUT, "off");
        return l == null || l.isEmpty() ? "off" : l.trim().toLowerCase();
    }

    public static boolean isHostLayoutOn(Context ctx) {
        return !isOffToken(hostLayout(ctx));
    }

    public static boolean isInjectPause(Context ctx) {
        return isOn(get(ctx, Titan2ApiContract.FILE_INJECT_PAUSE, "0"));
    }

    public static boolean isKeysPauseFlag(Context ctx) {
        return isOn(get(ctx, Titan2ApiContract.FILE_KEYS_PAUSE, "0"))
            || isOn(get(ctx, Titan2ApiContract.FILE_KEYS_PAUSE_TWIN, "0"));
    }

    /**
     * Phys TitanKey→host should be paused (a11y/specials own letters).
     * True when exclusive and (layout sticky/hold pause, inject Sym hold, or
     * explicit keys_pause with layout on).
     */
    public static boolean isPhysKeysPaused(Context ctx) {
        if (!isSessionOn(ctx)) return false;
        if (isInjectPause(ctx) && isGrabOn(ctx)) return true;
        if (!isKeysPauseFlag(ctx)) return false;
        if (isHostLayoutOn(ctx)) return true;
        // inject_pause may lag one frame behind keys_pause during arm
        return isInjectPause(ctx);
    }

    /**
     * Typing lock must not freeze pad when host owns mouse (HID exclusive /
     * Moonlight intercepted host mouse disconnect thrash).
     */
    public static boolean allowTypingCursorLock(Context ctx) {
        if (isExclusive(ctx)) return false;
        if (isHostMouseLive(ctx)) return false;
        return true;
    }

    /** Arm Sym-inject exclusive keys pause (does not change mouse). */
    public static void armInjectKeysPause(Context ctx) {
        if (ctx == null || !isExclusive(ctx)) return;
        put(ctx, Titan2ApiContract.FILE_INJECT_PAUSE, "1");
        put(ctx, Titan2ApiContract.FILE_KEYS_PAUSE, "1");
        put(ctx, Titan2ApiContract.FILE_KEYS_PAUSE_TWIN, "1");
        put(ctx, Titan2ApiContract.FILE_HID_KEYS, "0");
        put(ctx, Titan2ApiContract.FILE_HID_LOCAL_INPUT, "0");
        // Never touch mouse / grab / session — host cursor stays continuous.
        setCursorPaused(ctx, false);
    }

    /** Release inject pause; restore keys only if layout specials not still on. */
    public static void releaseInjectKeysPause(Context ctx) {
        if (ctx == null) return;
        put(ctx, Titan2ApiContract.FILE_INJECT_PAUSE, "0");
        if (isHostLayoutOn(ctx)) return; // layout hold/sticky still owns pause
        put(ctx, Titan2ApiContract.FILE_KEYS_PAUSE, "0");
        put(ctx, Titan2ApiContract.FILE_KEYS_PAUSE_TWIN, "0");
        if (isExclusive(ctx)) {
            put(ctx, Titan2ApiContract.FILE_HID_KEYS, "1");
        }
    }

    public static void setKeysPausedForLayout(Context ctx, boolean on) {
        put(ctx, Titan2ApiContract.FILE_KEYS_PAUSE, on ? "1" : "0");
        put(ctx, Titan2ApiContract.FILE_KEYS_PAUSE_TWIN, on ? "1" : "0");
        if (isExclusive(ctx)) {
            put(ctx, Titan2ApiContract.FILE_HID_KEYS, on ? "0" : "1");
        }
    }
}
