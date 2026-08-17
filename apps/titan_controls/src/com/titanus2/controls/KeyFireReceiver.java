package com.titanus2.controls;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/**
 * Privileged key-fire path from pad-agent (getevent) when the screen is off.
 * <p>
 * Accessibility {@code onKeyEvent} is <b>not</b> delivered while the display is
 * off/dozing (Android filter-key-events limitation). This receiver is the
 * product path: pad-agent sees EV_KEY, broadcasts here, we inject / StatusBar
 * without a11y.
 */
public class KeyFireReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.titanus2.controls.KEY_FIRE";
    public static final String EXTRA_ACTION = "action";
    /** Linux EV_KEY scan (side bottom 250 / top 249) for layout hold begin/end. */
    public static final String EXTRA_SCAN = "scan";
    /** 1 = key down, 0 = key up (host_layout letter path). */
    public static final String EXTRA_DOWN = "down";
    /**
     * Physical HW modifier mask from pad-agent getevent (Android {@code META_*} bits).
     * Merged into layout emits so Shift/Ctrl/Alt match screen-on a11y path.
     */
    public static final String EXTRA_META = "meta";

    /**
     * Action: translate Linux scan via active specials/arrows layout while
     * screen is off (a11y blind). Pad-agent only sends this when display is off
     * and {@code titan2_host_layout} is not off.
     */
    public static final String ACT_HOST_LAYOUT = "host_layout";
    /**
     * 13.09 B2: exclusive HID Start — drop phone Sym inject hold + inject queues
     * so host specials are sole owner (no dual phone/host residual).
     */
    public static final String ACT_CLEAR_PHONE_INJECT = "clear_phone_inject";
    /**
     * Lab: emit one specials glyph into the focused app (same path as Sym inject).
     * {@code adb shell am broadcast -a com.titanus2.controls.KEY_FIRE
     *   --es action emit_specials --es glyph 8}
     */
    public static final String ACT_EMIT_SPECIALS = "emit_specials";
    public static final String EXTRA_GLYPH = "glyph";

    /** Linux EV_KEY scans for physical side buttons (B1 — unmapped in kl). */
    public static boolean isSideScan(int scan) {
        return scan == 249 || scan == 250;
    }

    /**
     * P0 B1: never run system chrome from a side KEY_FIRE.
     * Stale pad-agent plane (or pre-1.7 seed) can still broadcast home/recents
     * /camera with scan 249/250; that is system Home/Camera from the left rail
     * — banned. 13.19: also drop chrome when action is pure system chrome and
     * scan is missing but pad-agent side path used EXTRA_SCAN default 0 after a
     * race — refuse camera/home/recents unless a non-side managed scan is set.
     *
     * @return action to run, or null to drop the event
     */
    static String sanitizeSideAction(int scan, String act) {
        if (act == null || act.isEmpty()) return null;
        String trimmed = act.trim();
        // B1 11.79: factory short=none must drop (never run KeyActions)
        if (KeyMapPrefs.ACT_NONE.equals(trimmed)
                || "none".equalsIgnoreCase(trimmed)
                || KeyMapPrefs.ACT_DEFAULT.equals(trimmed)) {
            return isSideScan(scan) ? null : act;
        }
        boolean side = isSideScan(scan);
        // Layout hold / letter path are product — not chrome.
        if ("layout:end_hold".equals(trimmed)
                || HostLayoutController.isLayoutHoldAction(trimmed)
                || ACT_HOST_LAYOUT.equals(trimmed)) {
            return trimmed;
        }
        if (trimmed.startsWith("layout:")) return trimmed;
        if (KeyMapPrefs.isSystemChromeAction(trimmed)) {
            // Side rail (249/250): always drop Home/Recents/Camera chrome.
            if (side) return null;
            // Scan-less camera is never a product KEY_FIRE on Titan — stock mtk-kpd
            // residual often arrives without EXTRA_SCAN. Non-side managed scans
            // (Fn/Back remaps) may still open Camera intentionally.
            if (scan <= 0 && isCameraChromeAction(trimmed)) return null;
        }
        return trimmed;
    }

    /** Camera / capture chrome aliases (stock mtk-kpd residual vocabulary). */
    private static boolean isCameraChromeAction(String act) {
        if (act == null) return false;
        String a = act.trim().toLowerCase(java.util.Locale.US);
        return KeyMapPrefs.ACT_CAMERA.equals(a)
                || "keycode_camera".equals(a) || "keycode:camera".equals(a)
                || "key_camera".equals(a) || "sys_camera".equals(a)
                || "stock_camera".equals(a) || "mtk_camera".equals(a)
                || "button_camera".equals(a) || "open_camera".equals(a)
                || "start_camera".equals(a) || "capture".equals(a)
                || "keycode:27".equals(a) || "keycode_27".equals(a);
    }

    /**
     * 12.29/12.58: a11y + pad-agent can both KEY_FIRE the same side press → dual
     * action. 120ms covers long-hold timer skew (400ms arm) races without
     * swallowing intentional double-taps (480ms window).
     */
    private static final long DEDUPE_MS = 120L;
    private static long sLastAt;
    private static int sLastSig;

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        String act = intent.getStringExtra(EXTRA_ACTION);
        if (act == null || act.isEmpty()) return;
        // B2 exclusive Start (HID): light clear — not a side chrome action
        if (ACT_CLEAR_PHONE_INJECT.equals(act)) {
            final Context app = context.getApplicationContext();
            try { TrackpadAccessService.clearLayoutKeyOwnership(); } catch (Exception ignored) {}
            try { KeyActions.clearAgentKeyQueue(app); } catch (Exception ignored) {}
            try { KeyActions.clearRemoteHidQueues(app); } catch (Exception ignored) {}
            try {
                com.titanus2.api.InputPlane.put(app,
                    com.titanus2.api.Titan2ApiContract.FILE_INJECT_PAUSE, "0");
            } catch (Exception ignored) {}
            return;
        }
        if (ACT_EMIT_SPECIALS.equals(act)) {
            final Context app = context.getApplicationContext();
            String g = intent.getStringExtra(EXTRA_GLYPH);
            if (g == null || g.isEmpty()) g = intent.getStringExtra("char");
            if (g == null || g.isEmpty()) return;
            char ch = g.charAt(0);
            try {
                // Bind a11y path — HostLayoutController.emitSpecials phone inject.
                HostLayoutController.bindApp(app);
                boolean ok = HostLayoutController.emitSpecials(app, ch, 0);
                android.util.Log.i("KeyFire", "emit_specials ch=" + ch + " ok=" + ok);
            } catch (Exception e) {
                android.util.Log.w("KeyFire", "emit_specials fail", e);
            }
            return;
        }
        final int scan = intent.getIntExtra(EXTRA_SCAN, 0);
        final boolean down = intent.getIntExtra(EXTRA_DOWN, 1) != 0;
        final int meta = intent.getIntExtra(EXTRA_META, 0);
        act = sanitizeSideAction(scan, act);
        if (act == null) return;
        // Side / layout hold: drop twin fires within 60ms (scan+action+down).
        int sig = (scan * 31 + act.hashCode()) * 31 + (down ? 1 : 0);
        long now = android.os.SystemClock.uptimeMillis();
        if (sig == sLastSig && (now - sLastAt) < DEDUPE_MS) {
            return;
        }
        sLastSig = sig;
        sLastAt = now;
        final String action = act;
        final PendingResult pr = goAsync();
        final Context app = context.getApplicationContext();
        // 11.57: screen-off KEY_FIRE must bind layout plane before begin/end hold
        try { HostLayoutController.bindApp(app); } catch (Exception ignored) {}
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                // PARTIAL keeps CPU for inject; does not light the panel.
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "titanus2:keyfire");
                wl.acquire(3000L);
            }
            // Side long-hold path (pad-agent): begin/end without a11y seeing the key.
            // 11.73: screen-off layout path — unfreeze pad cursor before Specials
            if ("layout:end_hold".equals(action)
                    || HostLayoutController.isLayoutHoldAction(action)
                    || ACT_HOST_LAYOUT.equals(action)) {
                try { TypingCursorLock.clear(app); } catch (Exception ignored) {}
            }
            if ("layout:end_hold".equals(action)) {
                HostLayoutController.endHold(scan > 0 ? scan : 0);
                // B2: release may race exclusive session stop — heal sticky pause
                try { HostLayoutController.healStaleHidPlane(app); } catch (Exception ignored) {}
                // 12.51: KEY_FIRE hold-end must drop inject backlog (agent + remote_q)
                // so next Specials press is not multi-glyph from queued leftovers.
                try { KeyActions.clearAgentKeyQueue(app); } catch (Exception ignored) {}
                try { KeyActions.clearRemoteHidQueues(app); } catch (Exception ignored) {}
                try { TrackpadAccessService.clearLayoutKeyOwnership(); } catch (Exception ignored) {}
                // 12.01: do not TaskbarPin on every KEY_FIRE — Settings thrash + heat
            } else if (HostLayoutController.isLayoutHoldAction(action)) {
                HostLayoutController.beginHold(app, scan > 0 ? scan : 1, action);
            } else if (action != null && (action.contains("toggle")
                    || action.startsWith("layout:toggle:"))) {
                // 12.57 B1: side double Specials/Arrows toggle via KEY_FIRE (screen-off)
                HostLayoutController.applyAction(app, action);
            } else if (ACT_HOST_LAYOUT.equals(action)) {
                // Specials/arrows letter keys while display off (+ physical mods)
                HostLayoutController.handleLinuxScan(app, scan, down, meta);
            } else if (KeyMapPrefs.isMouseButtonAction(action)
                    && intent.hasExtra(EXTRA_DOWN)) {
                KeyActions.mouseButton(app, action, down);
            } else {
                KeyActions.run(app, action);
            }
        } catch (Exception ignored) {
        } finally {
            if (wl != null && wl.isHeld()) {
                try { wl.release(); } catch (Exception ignored) {}
            }
            pr.finish();
        }
    }
}
