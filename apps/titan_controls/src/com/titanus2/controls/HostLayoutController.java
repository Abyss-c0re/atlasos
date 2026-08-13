package com.titanus2.controls;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;
import com.titanus2.controls.layouts.CustomLayoutStore;
import com.titanus2.controls.layouts.LayoutNotif;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Active keyboard layout controller (built-in Specials / Arrows + custom).
 * <p>
 * Priority: hold (layout modifier long) &gt; sticky toggle (double) &gt; per-app
 * default &gt; global default.
 * <p>
 * Mode string is a layout id ({@code specials}, {@code arrows}, {@code c_…})
 * or {@link #MODE_OFF}.
 * <p>
 * <b>Ownership (no dual-type):</b> HID exclusive + layout on → host only;
 * HID off + layout on → phone inject only; layout off + exclusive → keys=1.
 */
public final class HostLayoutController {
    private static final String TAG = "HostLayout";
    public static final String MODE_OFF = "off";
    public static final String MODE_SPECIALS = "specials";
    public static final String MODE_ARROWS = "arrows";
    public static final String MODE_INHERIT = "inherit";

    public static final String FILE_LAYOUT = "titan2_host_layout";
    /** Durable flag: we forced keys=0 for layout (survives process death). */
    public static final String FILE_KEYS_PAUSE = "titan2_host_layout_keys_pause";

    private static volatile String sticky = MODE_OFF;
    private static volatile String holdMode = MODE_OFF;
    private static volatile int holdScan = 0;
    /** Global default when sticky is off (persisted). */
    private static volatile String globalDefault = MODE_OFF;
    private static volatile boolean keysPausedForLayout;
    private static volatile String keysBeforePause;
    /**
     * App context for hold end from KEY_FIRE / pad-agent when no Activity is
     * in hand — {@link #endHold} used to call {@code publish(null)} and skip
     * plane writes (B2 sticky Specials after side long release).
     */
    private static volatile Context appCtx;
    /** Last LAYOUT_PLANE notify — skip redundant startService thrash (12.14). */
    private static volatile String lastPublishNotify = "";
    private static volatile long lastPublishNotifyElapsed;

    private HostLayoutController() {}

    /** Cache application context for screen-off / KEY_FIRE hold paths. */
    public static void bindApp(Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();
    }

    public static void loadGlobalDefault(Context ctx) {
        bindApp(ctx);
        try {
            String g = new KeyMapPrefs(ctx).getHostLayoutDefault();
            if (g != null) globalDefault = normalize(g);
        } catch (Exception ignored) {}
        // 12.13: NEVER restore sticky Specials/Arrows from plane on a11y bind.
        // Ghost plane=specials (crash / thrash / wipe) made every letter emit
        // multi-glyph spam in launcher search (QA screen-20260717-163224).
        sticky = MODE_OFF;
        holdMode = MODE_OFF;
        holdScan = 0;
        healGhostPhoneLayout(ctx);
    }

    /**
     * 13.20/13.25: phone screen-on + not exclusive → force layout plane/sticky/hold off.
     * Stuck plane=specials left every key in "sym layout" and broke Termux Sym inject
     * (layout remap swallowed letters before inject / dual path).
     * <p>
     * Bench 2026-07-18: plane stayed {@code specials} while HID session=0 after
     * side hold end / thrash — writePlane must always force {@code off} (not only
     * garbage strings).
     */
    public static void healGhostPhoneLayout(Context ctx) {
        if (ctx == null) return;
        bindApp(ctx);
        try {
            if (!isDeviceInteractive(ctx)) return;
            if (isHidExclusiveLiveFast(ctx)) return;
        } catch (Exception e) {
            return;
        }
        // Active side hold owns the plane until endHold
        if (isHoldActive()) return;
        sticky = MODE_OFF;
        // Clear stuck hold (missed side UP) — next long-press re-arms only when
        // no live holdScan (endHold owns real release)
        if (holdScan <= 0) {
            holdMode = MODE_OFF;
            holdScan = 0;
        }
        try {
            String plane = readPlane(ctx, FILE_LAYOUT);
            boolean bad = false;
            if (plane != null && !plane.isEmpty()) {
                String t = plane.trim();
                String n = normalize(t);
                // any non-off plane on phone interactive is ghost (sticky banned)
                if (!MODE_OFF.equals(n) && !MODE_INHERIT.equals(n)) bad = true;
                if (t.length() > 12) bad = true;
            }
            if (bad) {
                Log.w(TAG, "healGhostPhoneLayout plane=" + plane + " → off");
                writePlane(ctx, FILE_LAYOUT, MODE_OFF);
            }
            writePlane(ctx, FILE_KEYS_PAUSE, "0");
            try {
                com.titanus2.api.InputPlane.put(ctx, INJECT_PAUSE, "0");
            } catch (Exception ignored) {}
            try { TrackpadAccessService.clearLayoutKeyOwnership(); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    public static String getSticky() { return sticky == null ? MODE_OFF : sticky; }
    public static String getGlobalDefault() {
        return globalDefault == null ? MODE_OFF : globalDefault;
    }

    public static void setGlobalDefault(Context ctx, String mode) {
        globalDefault = normalize(mode);
        try { new KeyMapPrefs(ctx).setHostLayoutDefault(globalDefault); } catch (Exception ignored) {}
        publish(ctx);
    }

    /**
     * Effective mode for foreground package.
     * hold &gt; sticky toggle &gt; per-app &gt; global default.
     * <p>
     * Screen-off (QA 12.48): a11y {@code onKeyEvent} is blind — pad-agent
     * KEY_FIRE is the sole letter owner. Honor sticky / plane layout even
     * without exclusive HID so Specials/Arrows hold and sticky work dozing.
     * Screen-on phone typing still uses OEM Sym/Fn (sticky inject banned).
     */
    public static String effective(Context ctx) {
        if (holdMode != null && !MODE_OFF.equals(holdMode)) return holdMode;
        final boolean screenOff = ctx != null && !isDeviceInteractive(ctx);
        // Sticky Specials/Arrows: exclusive HID (host) OR screen-off KEY_FIRE.
        // Phone screen-on: OEM hold Sym/Fn + KCM — sticky must not activate
        // a11y letter inject (multi-glyph thrash).
        if (sticky != null && !MODE_OFF.equals(sticky)) {
            if (ctx == null || isHidExclusiveLive(ctx) || screenOff) return sticky;
        }
        // Screen-off: plane is source of truth when sticky was cleared (process
        // death) but user left Specials on before sleep.
        if (screenOff) {
            try {
                String plane = readPlane(ctx, FILE_LAYOUT);
                if (plane != null && !plane.isEmpty()) {
                    String n = normalize(plane);
                    if (!MODE_OFF.equals(n) && !MODE_INHERIT.equals(n)) return n;
                }
            } catch (Exception ignored) {}
        }
        String pkg = TrackpadAccessService.foregroundPkg();
        if (ctx != null && pkg != null) {
            try {
                String per = new KeyMapProfiles(ctx).getLayoutMode(pkg);
                if (per != null && !MODE_INHERIT.equals(per) && !per.isEmpty()) {
                    // Per-app sticky layout on phone only if exclusive (same rule)
                    String n = normalize(per);
                    if (!MODE_OFF.equals(n) && !isHidExclusiveLive(ctx) && !screenOff) {
                        /* phone: ignore per-app specials default for inject */
                    } else {
                        return n;
                    }
                }
            } catch (Exception ignored) {}
        }
        // Do NOT read plane here for screen-on — sticky is session-only.
        String g = globalDefault == null ? MODE_OFF : globalDefault;
        if (!MODE_OFF.equals(g) && ctx != null && !isHidExclusiveLive(ctx) && !screenOff) {
            return MODE_OFF;
        }
        return g;
    }

    public static String effective() {
        return effective(null);
    }

    public static boolean isActive(Context ctx) {
        // Sync sticky ← plane only when plane says off. Never rehydrate sticky on
        // from plane here: that raced double-tap toggle-off (sticky cleared, plane
        // still "specials" for one frame → re-arm → layout locked on). Full restore
        // after a11y restart is loadGlobalDefault only.
        if (ctx != null) {
            try {
                String plane = readPlane(ctx, FILE_LAYOUT);
                if (plane != null && !plane.isEmpty()) {
                    String n = normalize(plane);
                    if (MODE_OFF.equals(n) || MODE_INHERIT.equals(n)) {
                        if (!MODE_OFF.equals(sticky)) {
                            sticky = MODE_OFF;
                            Log.i(TAG, "isActive clear sticky from CE off");
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return !MODE_OFF.equals(effective(ctx));
    }

    public static boolean isActive() {
        return isActive(null);
    }

    public static String statusLine(Context ctx) {
        String e = effective(ctx);
        boolean held = holdMode != null && !MODE_OFF.equals(holdMode);
        boolean st = sticky != null && !MODE_OFF.equals(sticky);
        String src = held ? "held" : (st ? "toggle" : "default");
        if (MODE_OFF.equals(e)) return "Layout off";
        String name = e;
        try {
            if (ctx != null) name = new CustomLayoutStore(ctx).nameOf(e);
        } catch (Exception ignored) {}
        return name + " · " + src;
    }

    public static String statusLine() {
        return statusLine(null);
    }

    public static boolean isHoldActive() {
        return holdMode != null && !MODE_OFF.equals(holdMode);
    }

    public static boolean isStickyActive() {
        return sticky != null && !MODE_OFF.equals(sticky);
    }

    public static boolean hasNonOffDefault(Context ctx) {
        String g = globalDefault == null ? MODE_OFF : globalDefault;
        if (!MODE_OFF.equals(g)) return true;
        if (ctx == null) return false;
        try {
            String pkg = TrackpadAccessService.foregroundPkg();
            if (pkg == null) return false;
            String per = new KeyMapProfiles(ctx).getLayoutMode(pkg);
            return per != null && !MODE_OFF.equals(per) && !MODE_INHERIT.equals(per);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Force sticky layout on (no toggle). Used by layout editor Use action —
     * intent=result: save then this layout is active.
     */
    public static void activate(Context ctx, String layoutId) {
        if (layoutId == null || layoutId.isEmpty() || MODE_OFF.equals(layoutId)) {
            sticky = MODE_OFF;
            holdMode = MODE_OFF;
            holdScan = 0;
            toastAlways(ctx, "Layout off");
            publish(ctx);
            afterExclusiveLayoutRelease(ctx);
            return;
        }
        sticky = normalize(layoutId);
        toastAlways(ctx, layoutName(ctx, sticky) + " on · symbols");
        publish(ctx);
        // 11.70: sticky Specials on under exclusive — arm host drain + clear phone pause
        afterExclusiveLayoutArm(ctx);
    }

    public static void applyAction(Context ctx, String action) {
        if (action == null) return;
        // layout:off
        if (KeyMapPrefs.ACT_LAYOUT_OFF.equals(action) || "layout:off".equals(action)) {
            sticky = MODE_OFF;
            holdMode = MODE_OFF;
            holdScan = 0;
            toastAlways(ctx, "Layout off");
            publish(ctx);
            afterExclusiveLayoutRelease(ctx);
            return;
        }
        // layout:toggle:<id> or legacy specials/arrows toggle
        String toggleId = KeyMapPrefs.layoutToggleId(action);
        if (toggleId != null) {
            // Normalize both sides so "specials" vs stale sticky never sticks on
            String cur = normalize(sticky == null ? MODE_OFF : sticky);
            String want = normalize(toggleId);
            if (want.equals(cur)) {
                // Force full off: sticky + hold + plane write before any isActive read
                sticky = MODE_OFF;
                holdMode = MODE_OFF;
                holdScan = 0;
                writePlane(ctx, FILE_LAYOUT, MODE_OFF);
            } else {
                sticky = want;
                // New sticky wins over any leftover hold
                holdMode = MODE_OFF;
                holdScan = 0;
            }
            toastAlways(ctx, MODE_OFF.equals(sticky) ? "Layout off"
                : layoutName(ctx, sticky) + " on · symbols");
            publish(ctx);
            // 11.70: double-tap toggle under exclusive — arm or release host path
            if (MODE_OFF.equals(sticky)) {
                afterExclusiveLayoutRelease(ctx);
            } else {
                afterExclusiveLayoutArm(ctx);
            }
            return;
        }
        // layout:hold:<id> — no-op for fire-on-up; beginHold handles live hold
        if (KeyMapPrefs.layoutHoldId(action) != null) {
            return;
        }
        // legacy exacts
        if (KeyMapPrefs.ACT_LAYOUT_SPECIALS_TOGGLE.equals(action)) {
            applyAction(ctx, KeyMapPrefs.layoutToggleAction(MODE_SPECIALS));
            return;
        }
        if (KeyMapPrefs.ACT_LAYOUT_ARROWS_TOGGLE.equals(action)) {
            applyAction(ctx, KeyMapPrefs.layoutToggleAction(MODE_ARROWS));
            return;
        }
        publish(ctx);
    }

    public static void beginHold(Context ctx, int scan, String action) {
        bindApp(ctx);
        String id = KeyMapPrefs.layoutHoldId(action);
        if (id == null) {
            if (KeyMapPrefs.ACT_LAYOUT_SPECIALS_HOLD.equals(action)) id = MODE_SPECIALS;
            else if (KeyMapPrefs.ACT_LAYOUT_ARROWS_HOLD.equals(action)) id = MODE_ARROWS;
        }
        if (id == null || MODE_OFF.equals(id)) return;
        // Single layout owner: hold replaces sticky of the other layout so
        // specials + arrows never both apply (dual-fire lag).
        holdMode = id;
        holdScan = scan;
        if (sticky != null && !MODE_OFF.equals(sticky) && !id.equals(normalize(sticky))) {
            sticky = MODE_OFF;
        }
        // 11.73: typing-cursor freeze blocks pad while Specials hold is active
        try { TypingCursorLock.clear(ctx); } catch (Exception ignored) {}
        // 12.52: drop stale inject backlog BEFORE arm so first glyph is clean
        // (a11y + KEY_FIRE both enter here — single owner flush, no dual thrash).
        try { KeyActions.clearAgentKeyQueue(ctx); } catch (Exception ignored) {}
        try { KeyActions.clearRemoteHidQueues(ctx); } catch (Exception ignored) {}
        try { TrackpadAccessService.clearLayoutKeyOwnership(); } catch (Exception ignored) {}
        toast(ctx, layoutName(ctx, id) + " · held");
        publish(ctx);
        // B2 12.18: always seed remote_q before first glyph (phone + exclusive)
        try { KeyActions.ensureSpecialsQueuesLocal(); } catch (Exception ignored) {}
        // 12.39: pause host phys keys before first specials glyph (was only on
        // hostRemoteOnly emit — first key could dual-type phone+host)
        try { ensureKeysPausedForExclusiveSpecials(ctx); } catch (Exception ignored) {}
        // B2 11.68: side long Specials/Arrows — arm remote_q drain immediately
        try { KeyActions.pokeHidDrain(ctx); } catch (Exception ignored) {}
    }

    public static void endHold(int scan) {
        if (holdScan == scan || scan <= 0) {
            holdMode = MODE_OFF;
            holdScan = 0;
            // 13.25: force plane off before publish (phone ghost specials residual)
            try {
                if (appCtx != null && !isHidExclusiveLiveFast(appCtx)
                        && isDeviceInteractive(appCtx)) {
                    writePlane(appCtx, FILE_LAYOUT, MODE_OFF);
                }
            } catch (Exception ignored) {}
            // 11.54: always publish with app context so keys_pause clears when
            // side long-hold ends (KEY_FIRE path has no Activity context).
            publish(appCtx);
            // 11.69: exclusive still live after Specials release — clear phone
            // local_input residue so host TitanKey returns immediately.
            afterExclusiveLayoutRelease(appCtx);
            // 12.34: a11y must stop owning physical scans after hold end
            try { TrackpadAccessService.clearLayoutKeyOwnership(); } catch (Exception ignored) {}
        }
    }

    public static void endHoldAll() {
        holdMode = MODE_OFF;
        holdScan = 0;
        try {
            if (appCtx != null && !isHidExclusiveLiveFast(appCtx)
                    && isDeviceInteractive(appCtx)) {
                writePlane(appCtx, FILE_LAYOUT, MODE_OFF);
            }
        } catch (Exception ignored) {}
        publish(appCtx);
        afterExclusiveLayoutRelease(appCtx);
        try { TrackpadAccessService.clearLayoutKeyOwnership(); } catch (Exception ignored) {}
    }

    /** After layout hold/toggle off under exclusive: host keys, not phone pause. */
    private static void afterExclusiveLayoutRelease(Context ctx) {
        if (ctx == null) return;
        // 12.31/12.32: drop stale agent + HID remote queues on layout end
        try { KeyActions.clearAgentKeyQueue(ctx); } catch (Exception ignored) {}
        try { KeyActions.clearRemoteHidQueues(ctx); } catch (Exception ignored) {}
        try {
            // 12.37 B2: always clear keys_pause on layout end — even if exclusive
            // already died (FGS force-stop). Old path returned early and left
            // keys_pause=1 → dead host TitanKey / dual-type ghost until re-toggle.
            writePlane(ctx, FILE_KEYS_PAUSE, "0");
            writePlane(ctx, "titan2_usb_hid_keys_pause", "0");
            // 13.21 B2: also drop inject_pause residual from pre-13.21 Specials arm
            // (inject∧grab kept HID keys forced 0 while layout=off).
            try {
                com.titanus2.api.InputPlane.put(ctx, INJECT_PAUSE, "0");
            } catch (Exception ignored) {}
            writePlane(ctx, INJECT_PAUSE, "0");
            keysPausedForLayout = false;
            keysBeforePause = null;
            if (!isHidExclusiveLive(ctx)) return;
            clearLocalInputForExclusive(ctx);
            try { TypingCursorLock.clear(ctx); } catch (Exception ignored) {}
            // restore phys keys on host while exclusive still live
            writePlane(ctx, "titan2_usb_hid_keys", "1");
        } catch (Exception ignored) {}
    }

    /** Sticky/hold Specials on: seed queues; if exclusive, host drain + no phone local_input. */
    private static void afterExclusiveLayoutArm(Context ctx) {
        if (ctx == null) return;
        try {
            // 12.55: double-tap Specials/Arrows toggle arm — drop inject backlog
            // first (parity with beginHold 12.52) so sticky path is not multi-glyph.
            try { KeyActions.clearAgentKeyQueue(ctx); } catch (Exception ignored) {}
            try { KeyActions.clearRemoteHidQueues(ctx); } catch (Exception ignored) {}
            try { TrackpadAccessService.clearLayoutKeyOwnership(); } catch (Exception ignored) {}
            // 12.19: double-tap Specials toggle must seed remote_q before first glyph
            // (hold path already does this in beginHold).
            try { KeyActions.ensureSpecialsQueuesLocal(); } catch (Exception ignored) {}
            // 12.39: sticky/toggle arm — pause phys keys before first glyph (B2)
            try { ensureKeysPausedForExclusiveSpecials(ctx); } catch (Exception ignored) {}
            try { KeyActions.pokeHidDrain(ctx); } catch (Exception ignored) {}
            if (!isHidExclusiveLive(ctx)) return;
            clearLocalInputForExclusive(ctx);
            // 11.74: sticky Specials arm also unfreezes pad (typing cooldown)
            try { TypingCursorLock.clear(ctx); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    /**
     * Force layout completely off (sticky + hold + plane). Escape hatch when
     * double-tap toggle appears stuck after wipe / HID exclusive.
     */
    public static void forceOff(Context ctx) {
        sticky = MODE_OFF;
        holdMode = MODE_OFF;
        holdScan = 0;
        publish(ctx);
        // 11.71: escape hatch must restore exclusive host keys like hold/toggle off
        afterExclusiveLayoutRelease(ctx);
        // 12.31: belt also runs clearAgentKeyQueue via afterExclusiveLayoutRelease
        // 12.34: free physical keys stuck in a11y layoutHeldScans
        try { TrackpadAccessService.clearLayoutKeyOwnership(); } catch (Exception ignored) {}
        toastAlways(ctx, "Layout off");
    }

    private static String layoutName(Context ctx, String id) {
        try {
            if (ctx != null) return new CustomLayoutStore(ctx).nameOf(id);
        } catch (Exception ignored) {}
        return id;
    }

    /** Call when foreground app changes so per-app default can apply. */
    public static void onForegroundChanged(Context ctx, String pkg) {
        publish(ctx);
    }

    /**
     * Display interactive (on / awake for input). False when off or dozing —
     * a11y key filter is blind then; KEY_FIRE owns remaps.
     */
    private static boolean isDeviceInteractive(Context ctx) {
        if (ctx == null) return true;
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Exception e) {
            return true;
        }
    }

    /** Ghost FGS probe — never run dumpsys on every keystroke (13.13 lag QA). */
    private static volatile long lastGhostSessionProbeElapsed;
    private static volatile boolean lastGhostSessionLive;

    /** True when HID FGS/session plane says session is on <b>and</b> looks live. */
    public static boolean isHidSessionLive(Context ctx) {
        // Primary: session plane + corroboration (prop / process / recent mtime)
        if (planeOn(ctx, "titan2_usb_hid_session") || globalHidSessionOn(ctx)) {
            return hidSessionCorroborated(ctx);
        }
        // 13.13: plane+Global off → idle phone. Do NOT dumpsys/pidof on the a11y
        // key hot path (was ~400ms per key → laggy typing). Ghost FGS recover
        // at most once / 2s.
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastGhostSessionProbeElapsed < 2_000L) {
            return lastGhostSessionLive;
        }
        lastGhostSessionProbeElapsed = now;
        // Plane glitch: session=0/empty while HidSessionService FGS still owns
        // the session (share or exclusive). Lab saw notification "USB · share"
        // with tmp/Global session=0 after Controls heal wrote 0 — bridge
        // stopped; pad/keys stuck on phone. Reassert and treat as live.
        if (hidSessionServiceAlive(ctx)) {
            try { writePlane(ctx, "titan2_usb_hid_session", "1"); } catch (Exception ignored) {}
            lastGhostSessionLive = true;
            return true;
        }
        // Legacy: exclusive grab + package process (pre-FGS class check)
        if (usbHidProcessAlive(ctx) && planeOn(ctx, "titan2_usb_hid_grab")
                && !planeOn(ctx, "titan2_usb_hid_local_input")) {
            try { writePlane(ctx, "titan2_usb_hid_session", "1"); } catch (Exception ignored) {}
            lastGhostSessionLive = true;
            return true;
        }
        lastGhostSessionLive = false;
        return false;
    }

    /**
     * 13.13: key-hot-path exclusive check — plane/Global only, no dumpsys.
     * Full {@link #isHidExclusiveLive} remains for layout/Specials arm.
     */
    public static boolean isHidExclusiveLiveFast(Context ctx) {
        try {
            if (com.titanus2.api.InputPlane.isExclusive(ctx)) return true;
        } catch (Throwable ignored) {}
        // Classic exclusive: session + grab, not phone-local
        if (planeOn(ctx, "titan2_usb_hid_session")
                && planeOn(ctx, "titan2_usb_hid_grab")
                && !planeOn(ctx, "titan2_usb_hid_local_input")) {
            return true;
        }
        // 13.52: keys_pause / inject_pause arm lags grab one frame on exclusive
        // Start — still treat as host Specials owner so Sym does not fall through
        // to phone Termux inject (user: exclusive inject dead / dual).
        if (planeOn(ctx, "titan2_usb_hid_session")
                && (planeOn(ctx, FILE_KEYS_PAUSE)
                    || planeOn(ctx, "titan2_usb_hid_keys_pause")
                    || planeOn(ctx, INJECT_PAUSE))
                && !planeOn(ctx, "titan2_usb_hid_local_input")) {
            return true;
        }
        // Global mirrors (tmp SELinux deny)
        try {
            if (ctx != null) {
                String s = android.provider.Settings.Global.getString(
                    ctx.getContentResolver(), "titan2_usb_hid_session");
                String g = android.provider.Settings.Global.getString(
                    ctx.getContentResolver(), "titan2_usb_hid_grab");
                boolean sess = "1".equals(s != null ? s.trim() : "")
                    || "true".equalsIgnoreCase(s != null ? s.trim() : "");
                boolean grab = "1".equals(g != null ? g.trim() : "");
                if (sess && grab) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Exclusive host keyboard path: session live + grab (exclusive) + not phone
     * soft-local pause. Share mode (grab=0) keeps TitanKey on phone.
     * <p>
     * Stale {@code local_input=1} after share/Type races used to force phone-only
     * Specials while exclusive grab was still live — heal and treat as exclusive.
     */
    public static boolean isHidExclusiveLive(Context ctx) {
        try {
            if (com.titanus2.api.InputPlane.isExclusive(ctx)) {
                // Heal local_input phone-only pause while exclusive owns host
                if (planeOn(ctx, "titan2_usb_hid_local_input")
                        && usbHidProcessAlive(ctx)) {
                    try {
                        com.titanus2.api.InputPlane.put(ctx,
                            com.titanus2.api.Titan2ApiContract.FILE_HID_LOCAL_INPUT, "0");
                    } catch (Exception ignored) {}
                }
                return true;
            }
        } catch (Throwable ignored) {}
        if (!isHidSessionLive(ctx) || !planeOn(ctx, "titan2_usb_hid_grab")) {
            return false;
        }
        if (planeOn(ctx, "titan2_usb_hid_local_input")) {
            if (usbHidProcessAlive(ctx)) {
                try { writePlane(ctx, "titan2_usb_hid_local_input", "0"); }
                catch (Exception ignored) {}
                return true;
            }
            return false;
        }
        return true;
    }

    /**
     * B2 11.66: force local_input=0 while exclusive Specials emit so Magisk
     * bridge / dual plane never leave host glyphs on the phone.
     */
    public static void clearLocalInputForExclusive(Context ctx) {
        if (ctx == null) return;
        try {
            writePlane(ctx, "titan2_usb_hid_local_input", "0");
        } catch (Exception ignored) {}
    }

    /**
     * B2 11.77 / 12.81: exclusive host specials glyphs need keys_pause + keys=0
     * so phys TitanKey does not also stream QWERTY while remote_q drains glyphs.
     * <p>
     * 12.81: no longer requires layout sticky/hold {@link #isActive} — Sym
     * <b>inject method</b> (CHAR_MOD + letter) also needs this under exclusive HID.
     * <p>
     * 13.21 B2: layout specials use {@code keys_pause} only; {@code inject_pause}
     * is Sym-only. Arming inject_pause for Specials left exclusive keys stuck at 0
     * after layout release (HID {@code isPhysKeysPaused} inject∧grab → keys=0 while
     * layout=off — C-DUAL-TYPE residual).
     */
    public static final String INJECT_PAUSE =
        com.titanus2.api.Titan2ApiContract.FILE_INJECT_PAUSE;

    public static void ensureKeysPausedForExclusiveSpecials(Context ctx) {
        if (ctx == null) return;
        try {
            if (!isHidExclusiveLive(ctx)) return;
            clearLocalInputForExclusive(ctx);
            try { TypingCursorLock.clear(ctx); } catch (Exception ignored) {}
            boolean wasPaused = com.titanus2.api.InputPlane.isPhysKeysPaused(ctx);
            // Layout hold/sticky/plane on → keys_pause only (never inject_pause).
            // Sym inject (no layout owner) → arm inject+keys pause.
            boolean layoutSpecials = isHoldActive() || isStickyActive()
                || isActive(ctx);
            if (layoutSpecials) {
                com.titanus2.api.InputPlane.setKeysPausedForLayout(ctx, true);
            } else {
                com.titanus2.api.InputPlane.armInjectKeysPause(ctx);
            }
            keysPausedForLayout = true;
            // Drop sticky host Alt if Sym leaked before pause (Mac Option thrash)
            try { KeyActions.clearHostKeyboardMods(ctx); } catch (Exception ignored) {}
            if (!wasPaused) {
                try { KeyActions.pokeHidDrain(ctx); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    /**
     * Release inject keys_pause after Sym ends if no layout hold/sticky.
     */
    public static void releaseKeysPauseIfInjectOnly(Context ctx) {
        if (ctx == null) return;
        try {
            if (isHoldActive()) return;
            if (isStickyActive()) return;
            String plane = readPlane(ctx, FILE_LAYOUT);
            if (plane != null) {
                String n = normalize(plane);
                if (!MODE_OFF.equals(n) && !MODE_INHERIT.equals(n)) {
                    // Layout still on — only drop inject marker
                    com.titanus2.api.InputPlane.put(ctx, INJECT_PAUSE, "0");
                    return;
                }
            }
            com.titanus2.api.InputPlane.releaseInjectKeysPause(ctx);
            keysPausedForLayout = false;
        } catch (Exception ignored) {}
    }

    /**
     * Where layout emits should go: host (REMOTE only) vs phone (inject only).
     * Never both — that was the dual-type split-brain.
     * <p>
     * Only <b>exclusive</b> HID owns the host keyboard. Share mode, phone
     * local_input pause, or no live exclusive session → phone inject so the
     * HW keyboard never goes silent when Specials/Arrows is toggled.
     */
    public static boolean emitToHost(Context ctx) {
        return isHidExclusiveLive(ctx);
    }

    /**
     * Corroborate session=1 so a leftover plane file cannot steal the phone
     * keyboard. Prefer Global mirror + pidof (FGS APIs miss cross-package).
     * <p>
     * Never zero the session plane on a soft fail — that detached pure HID
     * mid-typing (host USB disconnect). Ghost cleanup is healStaleHidPlane
     * after pidof confirms the package is gone.
     */
    private static boolean hidSessionCorroborated(Context ctx) {
        // 1) FGS / dumpsys / pidof — real owners
        if (hidSessionServiceAlive(ctx)) return true;
        if (pidofPackage("com.titanus2.usbhid")) return true;
        // 2) Settings.Global mirror written by HidControl.setSession (works rootless)
        try {
            String g = android.provider.Settings.Global.getString(
                ctx.getContentResolver(), "titan2_usb_hid_session");
            if (g != null) {
                g = g.trim();
                if ("1".equals(g) || "true".equalsIgnoreCase(g) || "on".equalsIgnoreCase(g))
                    return true;
            }
        } catch (Exception ignored) {}
        // 3) Sysprop (best-effort; often stuck 0 without privileged set)
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class)
                .invoke(null, "sys.titanus2.usb_hid.session", "0");
            if ("1".equals(String.valueOf(v))) return true;
        } catch (Exception ignored) {}
        // 4) Other AM probes (may miss)
        if (usbHidProcessAlive(ctx)) return true;
        // Soft fail: report not live for exclusive emit, but do NOT zero the
        // session plane here (that detached USB mid-typing). healStaleHidPlane
        // wipes only after pidof confirms the package is gone.
        Log.w(TAG, "session plane=1 not corroborated — exclusive=off, plane kept");
        return false;
    }

    /**
     * HidSessionService FGS is the true session owner (share + exclusive + Type).
     * Package process alone can stay after Stop (MainActivity) with session=0.
     */
    private static boolean hidSessionServiceAlive(Context ctx) {
        if (ctx == null) return false;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            for (android.app.ActivityManager.RunningServiceInfo si
                    : am.getRunningServices(64)) {
                if (si == null || si.service == null) continue;
                if (!"com.titanus2.usbhid".equals(si.service.getPackageName())) continue;
                String cls = si.service.getClassName();
                if (cls != null && cls.endsWith(".HidSessionService")) return true;
            }
        } catch (Exception ignored) {}
        // Cross-package getRunningServices is empty on many builds. Only run
        // dumpsys when the package is actually up (pidof) to limit cost.
        if (pidofPackage("com.titanus2.usbhid")) {
            return hidServiceViaDumpsys();
        }
        return false;
    }

    /**
     * True if {@code com.titanus2.usbhid} has a process. Prefer {@code pidof}
     * (works across packages). getRunningAppProcesses often only returns the
     * caller's process and falsely reported "no HID" → heal zeroed session
     * mid-typing and tore down the USB gadget.
     */
    private static boolean usbHidProcessAlive(Context ctx) {
        if (ctx == null) return false;
        if (pidofPackage("com.titanus2.usbhid")) return true;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            for (android.app.ActivityManager.RunningServiceInfo si
                    : am.getRunningServices(64)) {
                if (si == null || si.service == null) continue;
                if ("com.titanus2.usbhid".equals(si.service.getPackageName())) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            for (android.app.ActivityManager.RunningAppProcessInfo p
                    : am.getRunningAppProcesses()) {
                if (p == null || p.processName == null) continue;
                if (p.processName.equals("com.titanus2.usbhid")
                        || p.processName.startsWith("com.titanus2.usbhid:")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** {@code pidof com.titanus2.usbhid} — reliable cross-UID process probe. */
    private static boolean pidofPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"/system/bin/pidof", pkg});
            if (!p.waitFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return false;
            }
            if (p.exitValue() != 0) return false;
            java.io.InputStream in = p.getInputStream();
            byte[] b = new byte[64];
            int n = in.read(b);
            if (n <= 0) return false;
            String out = new String(b, 0, n).trim();
            return !out.isEmpty() && Character.isDigit(out.charAt(0));
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) {}
        }
    }

    /**
     * dumpsys may list HidSessionService when ActivityManager APIs do not.
     * Require a <b>live</b> process binding — zombie ServiceRecords after
     * LAYOUT_PLANE startForeground DENIED leave {@code app=null} and used to
     * look "alive" forever (ghost session=1, bridge stuck nohidg).
     */
    private static boolean hidServiceViaDumpsys() {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{
                "/system/bin/sh", "-c",
                "timeout 0.4 dumpsys activity services com.titanus2.usbhid 2>/dev/null"
                + " | grep -A25 'HidSessionService' "
                + "| grep -qE 'app=ProcessRecord|isForeground=true|startForegroundCount=[1-9]'"
            });
            if (!p.waitFor(600, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) {}
        }
    }

    /**
     * Soft-only ghost: session claimed but no phys keys/mouse/grab and no real
     * FGS. Type soft Compose uses FGS park/start — without FGS this is leftover
     * Global/plane from failed LAYOUT_PLANE or lab shell put.
     */
    private static boolean isGhostSoftHalfSession(Context ctx) {
        if (!planeOn(ctx, "titan2_usb_hid_session") && !globalHidSessionOn(ctx))
            return false;
        if (planeOn(ctx, "titan2_usb_hid_keys")
                || planeOn(ctx, "titan2_usb_hid_mouse")
                || planeOn(ctx, "titan2_usb_hid_grab"))
            return false;
        // Real Type soft / phys session always has FGS (or park). Zombie
        // LAYOUT_PLANE records are not FGS.
        return !hidSessionServiceAlive(ctx);
    }

    /**
     * Publish mode to control plane and reconcile HID keys ownership.
     * Only pause keys when layout is on <em>and</em> exclusive HID is live
     * (so a11y can remap to host). Never leave keys=0 after layout off under exclusive.
     */
    public static void publish(Context ctx) {
        if (ctx != null) bindApp(ctx);
        Context c = ctx != null ? ctx.getApplicationContext() : appCtx;
        if (c == null) {
            Log.w(TAG, "publish skipped — no app context (bindApp never called)");
            return;
        }
        // Ghost exclusive: grab=1 / session leftovers with no HID process leave
        // plane looking exclusive while keys stay on phone — confuses specials.
        healStaleHidPlane(c);
        // 13.20: clear ghost specials plane on phone (Termux stuck-in-sym residual)
        healGhostPhoneLayout(c);
        String mode = effective(c);
        // Phone interactive: never persist sticky layout as plane=specials
        if (!isHidExclusiveLiveFast(c) && isDeviceInteractive(c)
                && !isHoldActive()) {
            mode = MODE_OFF;
        }
        writePlane(c, FILE_LAYOUT, mode);
        boolean layoutOn = !MODE_OFF.equals(mode);
        boolean exclusive = isHidExclusiveLive(c);
        if (layoutOn && exclusive) {
            pauseHidKeysForLayout(c);
        } else {
            // Layout off, or HID not exclusive: release any pause + heal stuck keys=0
            restoreHidKeysAfterLayout(c, exclusive);
        }
        try { LayoutNotif.sync(c != null ? c : ctx); } catch (Exception ignored) {}
        // Wake HID FGS — throttle identical notifies (a11y ensure waves used to
        // startService every few seconds → plane noise / dual-type races).
        String notifyKey = mode + "|" + (exclusive ? "1" : "0");
        long now = android.os.SystemClock.elapsedRealtime();
        boolean notify = !notifyKey.equals(lastPublishNotify)
            || (now - lastPublishNotifyElapsed) > 8_000L
            || (layoutOn && exclusive); // exclusive Specials arm latency
        if (notify) {
            lastPublishNotify = notifyKey;
            lastPublishNotifyElapsed = now;
            // Only poke HID when a real session is live. startService while
            // idle left zombie ServiceRecords (app=null, startForeground DENIED)
            // and ghost session=1 / stuck nohidg bridge (12.88 follow-up).
            boolean sessLive = planeOn(c, "titan2_usb_hid_session")
                || globalHidSessionOn(c)
                || exclusive;
            if (sessLive) {
                try {
                    Intent i = new Intent("com.titanus2.controls.action.LAYOUT_PLANE");
                    i.setClassName("com.titanus2.usbhid",
                        "com.titanus2.usbhid.HidSessionService");
                    i.putExtra("layout", mode);
                    i.putExtra("exclusive", exclusive);
                    c.startService(i);
                } catch (Exception e) {
                    try {
                        Intent b = new Intent("com.titanus2.controls.action.LAYOUT_PLANE");
                        b.setPackage("com.titanus2.usbhid");
                        b.putExtra("layout", mode);
                        c.sendBroadcast(b);
                    } catch (Exception ignored) {}
                }
            }
        }
        Log.i(TAG, "publish mode=" + mode + " exclusive=" + exclusive
            + " keysPaused=" + keysPausedForLayout
            + " notify=" + notify
            + " keys=" + readPlane(c, "titan2_usb_hid_keys")
            + " emitHost=" + emitToHost(c));
    }

    /**
     * When Titan USB HID process is gone, clear exclusive session leftovers so
     * phone typing and specials emit are not stuck in a half-exclusive plane
     * (session empty, grab=1, keys=1) seen after force-stop / crash.
     * <p>
     * When HID <b>is</b> alive but {@code titan2_usb_hid_session} is missing/empty
     * (bench shows {@code session=?}), write 0/1 from grab so specials + bench
     * never see an ambiguous plane.
     */
    /** Settings.Global session mirror (HidControl writes this rootless). */
    private static boolean globalHidSessionOn(Context ctx) {
        if (ctx == null) return false;
        try {
            String g = android.provider.Settings.Global.getString(
                ctx.getContentResolver(), "titan2_usb_hid_session");
            if (g == null) return false;
            g = g.trim();
            return "1".equals(g) || "true".equalsIgnoreCase(g) || "on".equalsIgnoreCase(g);
        } catch (Exception e) {
            return false;
        }
    }

    public static void healStaleHidPlane(Context ctx) {
        if (ctx == null) return;
        // LIVE SESSION: plane or Global says on. Never wipe for "no process" —
        // AM/pidof from Controls often miss the HID package (SELinux / API
        // isolation) and used to tear down pure HID mid-typing.
        boolean planeSess = planeOn(ctx, "titan2_usb_hid_session");
        boolean globalSess = globalHidSessionOn(ctx);
        boolean sessionClaimed = planeSess || globalSess;
        boolean pkgAlive = pidofPackage("com.titanus2.usbhid")
            || usbHidProcessAlive(ctx);
        // Ghost soft half-session (session=1 keys=mouse=grab=0, no real FGS):
        // clear so Magisk bridge stops and phone keyboard stays sole owner.
        if (sessionClaimed && isGhostSoftHalfSession(ctx)) {
            Log.i(TAG, "healStaleHidPlane: clear ghost soft half-session"
                + " planeSess=" + planeSess + " globalSess=" + globalSess);
            try {
                writePlane(ctx, "titan2_usb_hid_session", "0");
                writePlane(ctx, "titan2_usb_hid_grab", "0");
                writePlane(ctx, "titan2_usb_hid_keys", "0");
                writePlane(ctx, "titan2_usb_hid_mouse", "0");
                writePlane(ctx, FILE_KEYS_PAUSE, "0");
                writePlane(ctx, "titan2_usb_hid_keys_pause", "0");
                // 13.04: Sym inject pause sticky after Type/soft death → dead HW kb
                writePlane(ctx, "titan2_specials_inject_pause", "0");
                writePlane(ctx, "titan2_usb_hid_local_input", "0");
                keysPausedForLayout = false;
                keysBeforePause = null;
                try { KeyActions.clearAgentKeyQueue(ctx); } catch (Exception ignored) {}
                try { KeyActions.clearRemoteHidQueues(ctx); } catch (Exception ignored) {}
                try { TrackpadAccessService.clearLayoutKeyOwnership(); } catch (Exception ignored) {}
            } catch (Exception ignored) {}
            return;
        }
        boolean alive = sessionClaimed || pkgAlive;
        if (alive) {
            // FGS / plane owns the session — never zero grab/keys/mouse here.
            normalizeHidSessionPlane(ctx);
            // keys_pause / inject_pause only valid while exclusive is live
            if (!isHidExclusiveLive(ctx)
                    && (planeOn(ctx, FILE_KEYS_PAUSE)
                        || planeOn(ctx, "titan2_usb_hid_keys_pause")
                        || planeOn(ctx, "titan2_specials_inject_pause"))) {
                try {
                    writePlane(ctx, FILE_KEYS_PAUSE, "0");
                    writePlane(ctx, "titan2_usb_hid_keys_pause", "0");
                    writePlane(ctx, "titan2_specials_inject_pause", "0");
                    keysPausedForLayout = false;
                    keysBeforePause = null;
                    try { TrackpadAccessService.clearLayoutKeyOwnership(); }
                    catch (Exception ignored) {}
                } catch (Exception ignored) {}
            }
            // Session off (plane) but process still up: clear local_input only
            if (!planeOn(ctx, "titan2_usb_hid_session")
                    && !globalHidSessionOn(ctx)
                    && planeOn(ctx, "titan2_usb_hid_local_input")) {
                try {
                    writePlane(ctx, "titan2_usb_hid_local_input", "0");
                } catch (Exception ignored) {}
            }
            return;
        }
        boolean dirty = planeOn(ctx, "titan2_usb_hid_session")
            || planeOn(ctx, "titan2_usb_hid_grab")
            || planeOn(ctx, FILE_KEYS_PAUSE)
            || planeOn(ctx, "titan2_usb_hid_local_input");
        // Empty session file with grab=1 is also dirty (bench plane showed this)
        if (!dirty) {
            String grab = readPlane(ctx, "titan2_usb_hid_grab");
            if ("1".equals(grab) || "true".equalsIgnoreCase(grab)) dirty = true;
        }
        // Missing session file alone is dirty (session=?) — seed 0 after process death
        if (!dirty) {
            String sess = readPlane(ctx, "titan2_usb_hid_session");
            if (sess == null || sess.isEmpty()) dirty = true;
        }
        // Session clearly off (plane + Global): re-seed idle phys plane.
        // Never leave keys=1 sticky with session=0 — B2 dual-type / bench lies.
        try {
            writePlane(ctx, "titan2_usb_hid_session", "0");
            writePlane(ctx, "titan2_usb_hid_grab", "0");
            writePlane(ctx, "titan2_usb_hid_keys", "0");
            writePlane(ctx, "titan2_usb_hid_mouse", "0");
            writePlane(ctx, FILE_KEYS_PAUSE, "0");
            writePlane(ctx, "titan2_usb_hid_keys_pause", "0");
            writePlane(ctx, "titan2_specials_inject_pause", "0");
            writePlane(ctx, "titan2_usb_hid_local_input", "0");
            keysPausedForLayout = false;
            keysBeforePause = null;
            // 12.41: force-stop HID mid-specials left remote_q + agent inject backlog
            try { KeyActions.clearAgentKeyQueue(ctx); } catch (Exception ignored) {}
            try { KeyActions.clearRemoteHidQueues(ctx); } catch (Exception ignored) {}
            try { TrackpadAccessService.clearLayoutKeyOwnership(); } catch (Exception ignored) {}
            if (dirty) {
                Log.i(TAG, "healStaleHidPlane: cleared exclusive leftovers (session off)");
            } else {
                Log.i(TAG, "healStaleHidPlane: seed idle plane session/keys/mouse/grab=0");
            }
        } catch (Exception ignored) {}
    }

    /**
     * Ensure {@code titan2_usb_hid_session} exists and is mirrored to shared paths.
     * HidSessionService FGS alive → always "1" (share/exclusive/Type).
     * Empty/missing without FGS: grab=1 → "1", else "0". Non-empty: rewrite so
     * /data/local/tmp stays in sync (SELinux often drops prior tmp writes).
     */
    private static void normalizeHidSessionPlane(Context ctx) {
        String sess = readPlane(ctx, "titan2_usb_hid_session");
        boolean grabOn = planeOn(ctx, "titan2_usb_hid_grab");
        boolean fgs = hidSessionServiceAlive(ctx);
        boolean globalOn = globalHidSessionOn(ctx);
        String want;
        if (fgs || globalOn) {
            // FGS or Global mirror says live — never write session=0 (that used
            // to clobber a live USB HID session when tmp/misc files lagged at 0).
            want = "1";
        } else if (sess == null || sess.isEmpty()) {
            want = grabOn ? "1" : "0";
        } else if ("1".equals(sess) || "true".equalsIgnoreCase(sess)) {
            want = "1";
        } else {
            want = "0";
        }
        // Never downgrade Global/plane live → 0 from normalize alone.
        if ("0".equals(want) && globalOn) want = "1";
        try {
            writePlane(ctx, "titan2_usb_hid_session", want);
            if (sess == null || sess.isEmpty() || !want.equals(sess.trim())) {
                Log.i(TAG, "normalizeHidSessionPlane: session "
                    + (sess == null || sess.isEmpty() ? "empty" : sess)
                    + " → " + want
                    + (fgs ? " (fgs)" : globalOn ? " (global)" : grabOn ? " (grab)" : ""));
            }
        } catch (Exception ignored) {}
    }

    private static void pauseHidKeysForLayout(Context ctx) {
        String prev = readPlane(ctx, "titan2_usb_hid_keys");
        if (!keysPausedForLayout && !"1".equals(readPlane(ctx, FILE_KEYS_PAUSE))) {
            if (prev == null || prev.isEmpty()) prev = "1";
            // Soft-type already has keys=0 with mouse=0 — don't treat as exclusive pause restore=1
            if ("0".equals(prev) && !planeOn(ctx, "titan2_usb_hid_mouse")
                    && !planeOn(ctx, "titan2_usb_hid_grab")) {
                keysBeforePause = "0";
            } else {
                keysBeforePause = "1"; // exclusive host wants keys after layout
            }
        }
        keysPausedForLayout = true;
        writePlane(ctx, FILE_KEYS_PAUSE, "1");
        // 12.47: twin plane (pad-agent / RootlessPlane name) stays in sync
        writePlane(ctx, "titan2_usb_hid_keys_pause", "1");
        writePlane(ctx, "titan2_usb_hid_keys", "0");
        // Immediate softCompose clear + remote_q drain on exclusive Specials arm
        // (do not wait for HID 4–12 ms loop — first glyph dual-type race).
        try { KeyActions.pokeHidDrain(ctx); } catch (Exception ignored) {}
    }

    /**
     * Restore keys after layout. Always heal exclusive session stuck at keys=0
     * when layout is off (process death used to leave keysPaused=false and keys=0).
     */
    private static void restoreHidKeysAfterLayout(Context ctx, boolean exclusiveLive) {
        boolean wasPaused = keysPausedForLayout
            || "1".equals(readPlane(ctx, FILE_KEYS_PAUSE))
            || planeOn(ctx, "titan2_usb_hid_keys_pause");
        String keysNow = readPlane(ctx, "titan2_usb_hid_keys");
        boolean stuckExclusiveNokeys = exclusiveLive && "0".equals(keysNow);

        if (wasPaused || stuckExclusiveNokeys) {
            String restore = keysBeforePause != null ? keysBeforePause : "1";
            // Soft Type tab: mouse=0 grab=0 keys=0 — leave alone
            boolean softType = isHidSessionLive(ctx)
                && !planeOn(ctx, "titan2_usb_hid_mouse")
                && !planeOn(ctx, "titan2_usb_hid_grab");
            if (softType) {
                Log.i(TAG, "restore skip soft-type keys plane");
            } else if (isHidSessionLive(ctx) || wasPaused) {
                // Exclusive host: always keys=1 when layout off
                if (exclusiveLive || "1".equals(restore) || stuckExclusiveNokeys) {
                    writePlane(ctx, "titan2_usb_hid_keys", "1");
                    Log.i(TAG, "restore keys=1 exclusive=" + exclusiveLive
                        + " wasPaused=" + wasPaused + " stuck=" + stuckExclusiveNokeys);
                } else if (wasPaused) {
                    writePlane(ctx, "titan2_usb_hid_keys", restore);
                }
            }
        }
        keysPausedForLayout = false;
        keysBeforePause = null;
        writePlane(ctx, FILE_KEYS_PAUSE, "0");
        writePlane(ctx, "titan2_usb_hid_keys_pause", "0");
    }

    private static boolean planeOn(Context ctx, String name) {
        String v = readPlane(ctx, name);
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    /**
     * Pure modifier / lock keys: never intercept while layout is active so
     * physical Shift/Ctrl/Alt/Meta stay live and merge into layout emits.
     */
    public static boolean isModifierKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
            case KeyEvent.KEYCODE_SYM:
            case KeyEvent.KEYCODE_FUNCTION:
            case KeyEvent.KEYCODE_CAPS_LOCK:
            case KeyEvent.KEYCODE_NUM_LOCK:
            case KeyEvent.KEYCODE_SCROLL_LOCK:
                return true;
            default:
                return false;
        }
    }

    /**
     * Handle a physical key while layout is active.
     * @return true if this key is a layout cell / edit key (caller must swallow
     *         physical — never pass through after wouldHandle, dual letter+arrow).
     *         Emit is best-effort; false only when key is not part of the layout.
     */
    public static boolean handleKey(Context ctx, int keyCode, boolean down) {
        return handleKey(ctx, keyCode, down, 0);
    }

    /**
     * @param extraMeta {@link KeyEvent#getMetaState()} from the physical key
     *                  (Shift/Ctrl/Alt/Meta held on the HW keyboard). Merged into
     *                  every layout emit so chords match non-layout typing.
     */
    public static boolean handleKey(Context ctx, int keyCode, boolean down, int extraMeta) {
        if (!down) return true; // still consume UP (we swallowed DOWN)
        // Never steal pure modifiers — system tracks meta; we merge on letter press.
        if (isModifierKey(keyCode)) return false;
        String mode = effective(ctx);
        if (MODE_OFF.equals(mode)) return false;

        // Edit keys while layout on (space / enter / backspace) — allow repeats.
        if (isHostEditKey(keyCode)) {
            emitHostEdit(ctx, keyCode, extraMeta);
            return true; // always swallow — never letter then arrow
        }

        if (!wouldHandle(ctx, keyCode)) return false;
        // Best-effort emit; still consume so OS does not print the letter first.
        emitLayoutCell(ctx, mode, keyCode, extraMeta);
        return true;
    }

    /**
     * Resolve one cell from the active layout store.
     * @return true only if emit succeeded (caller may pass physical key otherwise).
     */
    private static boolean emitLayoutCell(Context ctx, String layoutId, int keyCode,
            int extraMeta) {
        if (ctx == null || layoutId == null) {
            // Fallback legacy specials/arrows without store
            if (MODE_SPECIALS.equals(layoutId)) {
                String ch = specialsChar(null, keyCode);
                if (ch != null) return emitSpecials(ctx, ch.charAt(0), extraMeta);
            } else if (MODE_ARROWS.equals(layoutId)) {
                Integer kc = arrowsKey(null, keyCode);
                if (kc != null) {
                    String host = arrowHost(kc);
                    if (host != null) return emitOwned(ctx, host, kc, extraMeta);
                    return emitLocalKey(ctx, kc, extraMeta);
                }
            }
            return false;
        }
        try {
            CustomLayoutStore.Layout lay = new CustomLayoutStore(ctx).get(layoutId);
            if (lay == null) return false;
            // Per-key overrides on specials still apply for specials id
            if (MODE_SPECIALS.equals(layoutId)) {
                String ov = new KeyMapPrefs(ctx).getSpecialsOverride(keyCode);
                if (ov != null) {
                    if (ov.isEmpty() || "-".equals(ov)) return false;
                    return emitSpecials(ctx, ov.charAt(0), extraMeta);
                }
            }
            String cell = lay.cells.get(keyCode);
            if (cell == null || cell.isEmpty() || "-".equals(cell)) return false;
            if (cell.startsWith("@host:")) {
                return emitOwned(ctx, "host:" + cell.substring(6), 0, extraMeta);
            }
            if (cell.startsWith("@key:")) {
                try {
                    int kc = Integer.parseInt(cell.substring(5).trim());
                    String host = arrowHost(kc);
                    if (host != null) return emitOwned(ctx, host, kc, extraMeta);
                    return emitLocalKey(ctx, kc, extraMeta);
                } catch (Exception e) {
                    return false;
                }
            }
            // Glyph
            return emitSpecials(ctx, cell.charAt(0), extraMeta);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Emit one specials glyph.
     * Exclusive HID → host US chord only (in-bridge map on device).
     * Phone → a11y SET_TEXT for editables; Termux → pad-agent {@code t } text
     * (locked SoT — see KeyActions.injectSpecialsGlyphPhone / NO_CIRCLES).
     */
    public static boolean emitSpecials(Context ctx, char ch) {
        return emitSpecials(ctx, ch, 0);
    }

    /**
     * @param extraMeta physical HW modifiers merged into the US host chord
     */
    public static boolean emitSpecials(Context ctx, char ch, int extraMeta) {
        // Glyph already is the specials-layer result. Do NOT merge physical
        // ALT/SYM — that re-applies KCM specials on inject and can produce
        // garbage like "ffff6" instead of "6" (QA 2026-07-16 hold-to-sym).
        // Keep Shift/Ctrl/Meta for chords (e.g. Ctrl+specials digit).
        int meta = specialsSafeMeta(extraMeta);
        // 12.93: drop stale pad-agent keyevent backlog before every glyph so
        // prior inject-fail residue cannot stack with this emit (Termux 5×).
        try { KeyActions.clearAgentKeyQueue(ctx); } catch (Exception ignored) {}
        String host = hostChordForChar(ch);
        // 13.57: Termux/Moonlight phone specials must not dual with host remote_q when
        // ghost exclusive plane is sticky (session=1 grab=1 but keys still 1 / no pause).
        // Real exclusive Sym arms keys_pause + keys=0. Ghost left multi agent text + dead kb.
        boolean toHost = emitToHost(ctx);
        try {
            if (toHost && KeyActions.isAgentTextSpecialsForegroundPublic(ctx)) {
                boolean keysOpen = planeOn(ctx, "titan2_usb_hid_keys");
                boolean paused = planeOn(ctx, FILE_KEYS_PAUSE)
                    || planeOn(ctx, "titan2_usb_hid_keys_pause")
                    || planeOn(ctx, INJECT_PAUSE);
                if (keysOpen && !paused) {
                    toHost = false;
                }
            }
        } catch (Exception ignored) {}
        if (toHost) {
            // B2 11.81 / 12.81: exclusive specials — pause phys keys + unfreeze pad
            // 13.51: host remote_q only — never phone a11y/agent text (dual with HID).
            try { ensureKeysPausedForExclusiveSpecials(ctx); } catch (Exception ignored) {}
            try { TypingCursorLock.clear(ctx); } catch (Exception ignored) {}
            if (host != null) {
                KeyActions.hostRemoteOnly(ctx, host, meta);
                try { KeyActions.pokeHidDrain(ctx); } catch (Exception ignored) {}
                return true;
            }
            int kc = specialsKeyCode(ch);
            if (kc > 0) {
                String h2 = hostChordForKeyCode(kc);
                if (h2 != null) {
                    KeyActions.hostRemoteOnly(ctx, h2, meta);
                    try { KeyActions.pokeHidDrain(ctx); } catch (Exception ignored) {}
                    return true;
                }
            }
            return false;
        }
        // Phone: ONE glyph. Termux = agent text (inside injectSpecialsGlyphPhone).
        // Non-terminal a11y first; agent fallback only if a11y returns false.
        // Never dual a11y+key. Debounce same glyph only.
        long now = android.os.SystemClock.uptimeMillis();
        if (ch == sLastPhoneSpecialsChar
                && sLastPhoneSpecialsEmitMs > 0
                && (now - sLastPhoneSpecialsEmitMs) < phoneSpecialsDebounceMs()) {
            Log.i(TAG, "phone specials debounce drop ch=" + ch);
            return true;
        }
        sLastPhoneSpecialsEmitMs = now;
        sLastPhoneSpecialsChar = ch;
        boolean ok = KeyActions.injectSpecialsGlyphPhone(ctx, ch);
        if (!ok) {
            // No focused editable / SET_TEXT refused / terminal detect miss —
            // pad-agent input text (13.47: injectInputEvent denied on adb-updated).
            ok = KeyActions.injectSpecialsGlyphKeyFallback(ctx, ch);
            Log.i(TAG, "phone specials ch=" + ch + " ok=" + ok + " path=agent-fallback");
        } else {
            Log.i(TAG, "phone specials ch=" + ch + " ok=true path=phone-inject");
        }
        // 13.57: never leave inject_pause / keys_pause after phone path (dead Termux kb)
        try { releaseKeysPauseIfInjectOnly(ctx); } catch (Exception ignored) {}
        // Still swallow physical Sym letter either way (no dual QWERTY leak).
        return true;
    }

    private static long sLastPhoneSpecialsEmitMs;
    private static char sLastPhoneSpecialsChar;
    /** 13.35: dual residual only — was 400ms and blocked intentional re-tap. */
    /**
     * 13.61: dual residual only — SoT
     * {@link com.titanus2.api.KeyInputTiming#dualResidualDebounceMs()}.
     * Field kept as fallback if API class missing at runtime.
     */
    private static final long PHONE_SPECIALS_DEBOUNCE_MS = 40L;

    private static long phoneSpecialsDebounceMs() {
        try {
            return com.titanus2.api.KeyInputTiming.dualResidualDebounceMs();
        } catch (Throwable t) {
            return PHONE_SPECIALS_DEBOUNCE_MS;
        }
    }

    /**
     * Meta allowed when emitting a specials glyph. Strip ALT/SYM so inject does
     * not stack a second specials layer on top of the mapped character.
     */
    public static int specialsSafeMeta(int extraMeta) {
        int m = KeyActions.usefulMeta(extraMeta);
        return m & ~(
            KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON | KeyEvent.META_ALT_RIGHT_ON
                | KeyEvent.META_SYM_ON
        );
    }

    /** Map specials glyph → Android keycode (closest real key for intercept apps). */
    public static int specialsKeyCode(char ch) {
        if (ch >= '0' && ch <= '9') return KeyEvent.KEYCODE_0 + (ch - '0');
        switch (ch) {
            case '@': return KeyEvent.KEYCODE_AT;
            case '*': return KeyEvent.KEYCODE_STAR;
            case '#': return KeyEvent.KEYCODE_POUND;
            case '+': return KeyEvent.KEYCODE_PLUS;
            case '-':
            case '_': return KeyEvent.KEYCODE_MINUS;
            case '/':
            case '?': return KeyEvent.KEYCODE_SLASH;
            case '.': return KeyEvent.KEYCODE_PERIOD;
            case ',': return KeyEvent.KEYCODE_COMMA;
            case '(': return KeyEvent.KEYCODE_LEFT_BRACKET;
            case ')': return KeyEvent.KEYCODE_RIGHT_BRACKET;
            case '"':
            case '\'': return KeyEvent.KEYCODE_APOSTROPHE;
            case '!': return KeyEvent.KEYCODE_1;
            case ':':
            case ';': return KeyEvent.KEYCODE_SEMICOLON;
            case ' ': return KeyEvent.KEYCODE_SPACE;
            case '=': return KeyEvent.KEYCODE_EQUALS;
            default: return 0;
        }
    }

    private static String hostChordForKeyCode(int keyCode) {
        // Best-effort host: action for exclusive HID when no glyph chord
        switch (keyCode) {
            case KeyEvent.KEYCODE_AT: return "host:shift+2";
            case KeyEvent.KEYCODE_STAR: return "host:shift+8";
            case KeyEvent.KEYCODE_POUND: return "host:shift+3";
            case KeyEvent.KEYCODE_PLUS: return "host:shift+equal";
            default: return null;
        }
    }

    /** Phone: one keycode for the expected symbol (INJECT_EVENTS or agent). */
    private static boolean emitKeyCodePhone(Context ctx, int keyCode, int extraMeta) {
        return KeyActions.emitLayoutKeyCode(ctx, keyCode, extraMeta);
    }

    /**
     * Single-owner emit: exclusive HID → host action; phone → keycode inject.
     */
    private static boolean emitOwned(Context ctx, String hostAction, int localKeyCode,
            int extraMeta) {
        if (emitToHost(ctx)) {
            if (hostAction != null) KeyActions.hostRemoteOnly(ctx, hostAction, extraMeta);
            return true;
        }
        if (localKeyCode > 0) return emitKeyCodePhone(ctx, localKeyCode, extraMeta);
        return false;
    }

    private static boolean emitLocalKey(Context ctx, int keyCode, int extraMeta) {
        if (emitToHost(ctx)) {
            String host = arrowHost(keyCode);
            if (host != null) {
                KeyActions.hostRemoteOnly(ctx, host, extraMeta);
                return true;
            }
            return false;
        }
        return emitKeyCodePhone(ctx, keyCode, extraMeta);
    }

    /**
     * Screen-off path: pad-agent getevent → KEY_FIRE with Linux EV_KEY scan.
     * A11y {@code onKeyEvent} is not delivered while the display is off / dozing
     * (Android filter-key-events limitation) — this is the privileged fallback.
     *
     * @return true if consumed
     */
    public static boolean handleLinuxScan(Context ctx, int linuxScan, boolean down) {
        return handleLinuxScan(ctx, linuxScan, down, 0);
    }

    /**
     * @param extraMeta physical modifiers if pad-agent tracked them (else 0)
     */
    public static boolean handleLinuxScan(Context ctx, int linuxScan, boolean down,
            int extraMeta) {
        if (ctx == null || linuxScan <= 0) return false;
        int keyCode = linuxScanToKeyCode(linuxScan);
        if (keyCode <= 0 || keyCode == KeyEvent.KEYCODE_UNKNOWN) return false;
        if (isModifierKey(keyCode)) return false;
        if (!wouldHandle(ctx, keyCode)) return false;
        return handleKey(ctx, keyCode, down, extraMeta);
    }

    /**
     * Linux input-event-codes → Android KeyEvent keycode (US QWERTY TitanKey).
     * Covers letters, digits, and common edit keys used by specials/arrows.
     */
    public static int linuxScanToKeyCode(int linuxScan) {
        switch (linuxScan) {
            case 1: return KeyEvent.KEYCODE_ESCAPE;
            case 2: return KeyEvent.KEYCODE_1;
            case 3: return KeyEvent.KEYCODE_2;
            case 4: return KeyEvent.KEYCODE_3;
            case 5: return KeyEvent.KEYCODE_4;
            case 6: return KeyEvent.KEYCODE_5;
            case 7: return KeyEvent.KEYCODE_6;
            case 8: return KeyEvent.KEYCODE_7;
            case 9: return KeyEvent.KEYCODE_8;
            case 10: return KeyEvent.KEYCODE_9;
            case 11: return KeyEvent.KEYCODE_0;
            case 14: return KeyEvent.KEYCODE_DEL;
            case 15: return KeyEvent.KEYCODE_TAB;
            case 16: return KeyEvent.KEYCODE_Q;
            case 17: return KeyEvent.KEYCODE_W;
            case 18: return KeyEvent.KEYCODE_E;
            case 19: return KeyEvent.KEYCODE_R;
            case 20: return KeyEvent.KEYCODE_T;
            case 21: return KeyEvent.KEYCODE_Y;
            case 22: return KeyEvent.KEYCODE_U;
            case 23: return KeyEvent.KEYCODE_I;
            case 24: return KeyEvent.KEYCODE_O;
            case 25: return KeyEvent.KEYCODE_P;
            case 28: return KeyEvent.KEYCODE_ENTER;
            case 30: return KeyEvent.KEYCODE_A;
            case 31: return KeyEvent.KEYCODE_S;
            case 32: return KeyEvent.KEYCODE_D;
            case 33: return KeyEvent.KEYCODE_F;
            case 34: return KeyEvent.KEYCODE_G;
            case 35: return KeyEvent.KEYCODE_H;
            case 36: return KeyEvent.KEYCODE_J;
            case 37: return KeyEvent.KEYCODE_K;
            case 38: return KeyEvent.KEYCODE_L;
            case 39: return KeyEvent.KEYCODE_SEMICOLON;
            case 40: return KeyEvent.KEYCODE_APOSTROPHE;
            case 41: return KeyEvent.KEYCODE_GRAVE;
            case 44: return KeyEvent.KEYCODE_Z;
            case 45: return KeyEvent.KEYCODE_X;
            case 46: return KeyEvent.KEYCODE_C;
            case 47: return KeyEvent.KEYCODE_V;
            case 48: return KeyEvent.KEYCODE_B;
            case 49: return KeyEvent.KEYCODE_N;
            case 50: return KeyEvent.KEYCODE_M;
            case 51: return KeyEvent.KEYCODE_COMMA;
            case 52: return KeyEvent.KEYCODE_PERIOD;
            case 53: return KeyEvent.KEYCODE_SLASH;
            case 57: return KeyEvent.KEYCODE_SPACE;
            case 102: return KeyEvent.KEYCODE_HOME;
            case 103: return KeyEvent.KEYCODE_DPAD_UP;
            case 105: return KeyEvent.KEYCODE_DPAD_LEFT;
            case 106: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case 107: return KeyEvent.KEYCODE_MOVE_END;
            case 108: return KeyEvent.KEYCODE_DPAD_DOWN;
            case 111: return KeyEvent.KEYCODE_FORWARD_DEL;
            case 158: return KeyEvent.KEYCODE_BACK;
            default: return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    public static boolean wouldHandle(Context ctx, int keyCode) {
        if (isModifierKey(keyCode)) return false;
        String mode = effective(ctx);
        if (MODE_OFF.equals(mode)) return false;
        if (isHostEditKey(keyCode)) return true;
        // Only keys that have a layout cell / builtin map — never all letters.
        // Intercepting every letter and re-injecting killed the HW keyboard when
        // inject failed or exclusive host emit went nowhere.
        if (ctx != null) {
            try {
                CustomLayoutStore.Layout lay = new CustomLayoutStore(ctx).get(mode);
                if (lay != null) {
                    String cell = lay.cells.get(keyCode);
                    if (cell != null && !cell.isEmpty() && !"-".equals(cell)) return true;
                }
            } catch (Exception ignored) {}
        }
        if (MODE_SPECIALS.equals(mode)) {
            return specialsChar(ctx, keyCode) != null;
        }
        if (MODE_ARROWS.equals(mode)) {
            return arrowsKey(ctx, keyCode) != null;
        }
        return false;
    }

    public static boolean wouldHandle(int keyCode) {
        return wouldHandle(null, keyCode);
    }

    public static String specialsCharPublic(int keyCode) {
        return specialsChar(null, keyCode);
    }

    public static boolean wouldHandleSpecials(int keyCode) {
        return specialsChar(null, keyCode) != null;
    }

    public static boolean isLayoutHoldAction(String action) {
        if (action == null) return false;
        if (KeyMapPrefs.ACT_LAYOUT_SPECIALS_HOLD.equals(action)
                || KeyMapPrefs.ACT_LAYOUT_ARROWS_HOLD.equals(action)) return true;
        return KeyMapPrefs.layoutHoldId(action) != null;
    }

    public static boolean isHostEditKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_ESCAPE:
                return true;
            default:
                return false;
        }
    }

    public static boolean isLetterKey(int keyCode) {
        return keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z;
    }

    /** @return true if edit key was delivered. */
    public static boolean emitHostEdit(Context ctx, int keyCode) {
        return emitHostEdit(ctx, keyCode, 0);
    }

    public static boolean emitHostEdit(Context ctx, int keyCode, int extraMeta) {
        String host = null;
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE: host = "host:space"; break;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: host = "host:enter"; break;
            case KeyEvent.KEYCODE_DEL: host = "host:backspace"; break;
            case KeyEvent.KEYCODE_FORWARD_DEL: host = "host:delete"; break;
            case KeyEvent.KEYCODE_TAB: host = "host:tab"; break;
            case KeyEvent.KEYCODE_ESCAPE: host = "host:esc"; break;
            default: break;
        }
        if (host == null) return false;
        return emitOwned(ctx, host, keyCode, extraMeta);
    }

    public static void emitHostLetter(Context ctx, int keyCode) {
        emitHostLetter(ctx, keyCode, 0);
    }

    public static void emitHostLetter(Context ctx, int keyCode, int extraMeta) {
        if (!isLetterKey(keyCode)) return;
        char c = (char) ('a' + (keyCode - KeyEvent.KEYCODE_A));
        String host = hostChordForChar(c);
        if (host != null) emitOwned(ctx, host, keyCode, extraMeta);
        else if (!emitToHost(ctx)) {
            if (KeyActions.usefulMeta(extraMeta) != 0) {
                KeyActions.emitLayoutKeyCode(ctx, keyCode, extraMeta);
            } else {
                KeyActions.injectKeyCode(ctx, keyCode);
            }
        }
    }

    public static String modeLabel(String mode) {
        if (mode == null || mode.isEmpty()) return "Off";
        mode = mode.trim();
        if (MODE_OFF.equalsIgnoreCase(mode)) return "Off";
        if (MODE_INHERIT.equalsIgnoreCase(mode)) return "Inherit";
        if (MODE_SPECIALS.equalsIgnoreCase(mode)) return "Specials";
        if (MODE_ARROWS.equalsIgnoreCase(mode)) return "Arrows";
        return mode;
    }

    /**
     * Normalize layout id. Accepts off / inherit / specials / arrows / custom c_*.
     */
    public static String normalize(String mode) {
        if (mode == null || mode.isEmpty()) return MODE_OFF;
        mode = mode.trim();
        String low = mode.toLowerCase();
        if (MODE_OFF.equals(low) || MODE_INHERIT.equals(low)
                || MODE_SPECIALS.equals(low) || MODE_ARROWS.equals(low)) {
            return low;
        }
        // Custom layout ids (preserve case for c_… but store lower-safe)
        if (mode.startsWith("c_") || mode.startsWith("C_")) return mode;
        // Unknown legacy → off
        return MODE_OFF;
    }

    /** Titan Alt/specials layer + optional user overrides. */
    private static String specialsChar(Context ctx, int keyCode) {
        if (ctx != null) {
            String o = new KeyMapPrefs(ctx).getSpecialsOverride(keyCode);
            if (o != null) {
                if (o.isEmpty() || "-".equals(o)) return null; // user cleared
                // One printable glyph only — reject corrupt prefs (e.g. hex junk).
                o = sanitizeSpecialsGlyph(o);
                if (o == null) return null;
                return o;
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_Q: return "0";
            case KeyEvent.KEYCODE_W: return "1";
            case KeyEvent.KEYCODE_E: return "2";
            case KeyEvent.KEYCODE_R: return "3";
            case KeyEvent.KEYCODE_T: return "(";
            case KeyEvent.KEYCODE_Y: return ")";
            // Product map: U = underscore (was bare "-"; I keeps hyphen)
            case KeyEvent.KEYCODE_U: return "_";
            case KeyEvent.KEYCODE_I: return "-";
            case KeyEvent.KEYCODE_O: return "/";
            case KeyEvent.KEYCODE_P: return ":";
            case KeyEvent.KEYCODE_A: return "@";
            case KeyEvent.KEYCODE_S: return "4";
            case KeyEvent.KEYCODE_D: return "5";
            case KeyEvent.KEYCODE_F: return "6";
            case KeyEvent.KEYCODE_G: return "*";
            case KeyEvent.KEYCODE_H: return "#";
            case KeyEvent.KEYCODE_J: return "+";
            case KeyEvent.KEYCODE_K: return "\"";
            case KeyEvent.KEYCODE_L: return "'";
            case KeyEvent.KEYCODE_Z: return "!";
            case KeyEvent.KEYCODE_X: return "7";
            case KeyEvent.KEYCODE_C: return "8";
            case KeyEvent.KEYCODE_V: return "9";
            case KeyEvent.KEYCODE_B: return ".";
            case KeyEvent.KEYCODE_N: return ",";
            case KeyEvent.KEYCODE_M: return "?";
            default: return null;
        }
    }

    /** Single BMP printable glyph for specials emit; reject multi-char / hex junk. */
    public static String sanitizeSpecialsGlyph(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty() || "-".equals(t)) return null;
        // Corrupt override looking like hex dump (QA: "ffff6")
        if (t.length() > 1 && t.matches("(?i)[0-9a-f]{3,8}")) return null;
        if (t.length() == 1) {
            char c = t.charAt(0);
            if (c < 0x20 || c == 0x7f) return null;
            return t;
        }
        // Allow short multi-char custom only if all printable (rare); else first char
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 0x20 || c == 0x7f) return null;
        }
        if (t.length() <= 3) return t;
        return String.valueOf(t.charAt(0));
    }

    private static Integer arrowsKey(Context ctx, int keyCode) {
        if (ctx != null) {
            Integer o = new KeyMapPrefs(ctx).getArrowsOverride(keyCode);
            if (o != null) {
                if (o == 0) return null; // cleared
                return o;
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_W:
            case KeyEvent.KEYCODE_I: return KeyEvent.KEYCODE_DPAD_UP;
            case KeyEvent.KEYCODE_A:
            case KeyEvent.KEYCODE_J: return KeyEvent.KEYCODE_DPAD_LEFT;
            case KeyEvent.KEYCODE_S:
            // QA sketch: x = down (not s-only WASD conflict with specials)
            case KeyEvent.KEYCODE_X:
            case KeyEvent.KEYCODE_N:
            case KeyEvent.KEYCODE_K: return KeyEvent.KEYCODE_DPAD_DOWN;
            case KeyEvent.KEYCODE_D:
            case KeyEvent.KEYCODE_L: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case KeyEvent.KEYCODE_H: return KeyEvent.KEYCODE_MOVE_HOME;
            case KeyEvent.KEYCODE_SEMICOLON: return KeyEvent.KEYCODE_MOVE_END;
            case KeyEvent.KEYCODE_U:
            case KeyEvent.KEYCODE_Q: return KeyEvent.KEYCODE_PAGE_UP;
            case KeyEvent.KEYCODE_O:
            case KeyEvent.KEYCODE_E: return KeyEvent.KEYCODE_PAGE_DOWN;
            case KeyEvent.KEYCODE_1: return KeyEvent.KEYCODE_F1;
            case KeyEvent.KEYCODE_2: return KeyEvent.KEYCODE_F2;
            case KeyEvent.KEYCODE_3: return KeyEvent.KEYCODE_F3;
            case KeyEvent.KEYCODE_4: return KeyEvent.KEYCODE_F4;
            case KeyEvent.KEYCODE_5: return KeyEvent.KEYCODE_F5;
            case KeyEvent.KEYCODE_6: return KeyEvent.KEYCODE_F6;
            case KeyEvent.KEYCODE_7: return KeyEvent.KEYCODE_F7;
            case KeyEvent.KEYCODE_8: return KeyEvent.KEYCODE_F8;
            case KeyEvent.KEYCODE_9: return KeyEvent.KEYCODE_F9;
            case KeyEvent.KEYCODE_0: return KeyEvent.KEYCODE_F10;
            default: return null;
        }
    }

    /** Default specials map for UI listing: letter keyCode → glyph. */
    public static java.util.LinkedHashMap<Integer, String> defaultSpecialsMap() {
        java.util.LinkedHashMap<Integer, String> m = new java.util.LinkedHashMap<>();
        int[] keys = {
            KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_R,
            KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_I,
            KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S,
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H,
            KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_Z,
            KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_B,
            KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_M
        };
        for (int k : keys) {
            String ch = specialsChar(null, k);
            if (ch != null) m.put(k, ch);
        }
        return m;
    }

    public static String letterLabel(int keyCode) {
        if (isLetterKey(keyCode)) {
            return String.valueOf((char) ('A' + (keyCode - KeyEvent.KEYCODE_A)));
        }
        return "Key" + keyCode;
    }

    private static String arrowHost(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return "host:up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "host:down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "host:left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "host:right";
            case KeyEvent.KEYCODE_MOVE_HOME: return "host:home";
            case KeyEvent.KEYCODE_MOVE_END: return "host:end";
            case KeyEvent.KEYCODE_PAGE_UP: return "host:pageup";
            case KeyEvent.KEYCODE_PAGE_DOWN: return "host:pagedown";
            case KeyEvent.KEYCODE_F1: return "host:f1";
            case KeyEvent.KEYCODE_F2: return "host:f2";
            case KeyEvent.KEYCODE_F3: return "host:f3";
            case KeyEvent.KEYCODE_F4: return "host:f4";
            case KeyEvent.KEYCODE_F5: return "host:f5";
            case KeyEvent.KEYCODE_F6: return "host:f6";
            case KeyEvent.KEYCODE_F7: return "host:f7";
            case KeyEvent.KEYCODE_F8: return "host:f8";
            case KeyEvent.KEYCODE_F9: return "host:f9";
            case KeyEvent.KEYCODE_F10: return "host:f10";
            default: return null;
        }
    }

    public static String hostChordForChar(char c) {
        int[] ku = usHidForChar(c);
        if (ku == null) return null;
        String name = hidUsageName(ku[1]);
        if (name == null) return null;
        if (ku[0] != 0) return "host:shift+" + name;
        return "host:" + name;
    }

    public static int[] usHidForChar(char c) {
        final int SH = 0x02;
        if (c >= 'a' && c <= 'z') return new int[]{0, 0x04 + (c - 'a')};
        if (c >= 'A' && c <= 'Z') return new int[]{SH, 0x04 + (c - 'A')};
        if (c >= '1' && c <= '9') return new int[]{0, 0x1e + (c - '1')};
        if (c == '0') return new int[]{0, 0x27};
        switch (c) {
            case ' ': return new int[]{0, 0x2c};
            case '-': return new int[]{0, 0x2d};
            case '=': return new int[]{0, 0x2e};
            case '[': return new int[]{0, 0x2f};
            case ']': return new int[]{0, 0x30};
            case '\\': return new int[]{0, 0x31};
            case ';': return new int[]{0, 0x33};
            case '\'': return new int[]{0, 0x34};
            case '`': return new int[]{0, 0x35};
            case ',': return new int[]{0, 0x36};
            case '.': return new int[]{0, 0x37};
            case '/': return new int[]{0, 0x38};
            case '!': return new int[]{SH, 0x1e};
            case '@': return new int[]{SH, 0x1f};
            case '#': return new int[]{SH, 0x20};
            case '$': return new int[]{SH, 0x21};
            case '%': return new int[]{SH, 0x22};
            case '^': return new int[]{SH, 0x23};
            case '&': return new int[]{SH, 0x24};
            case '*': return new int[]{SH, 0x25};
            case '(': return new int[]{SH, 0x26};
            case ')': return new int[]{SH, 0x27};
            case '_': return new int[]{SH, 0x2d};
            case '+': return new int[]{SH, 0x2e};
            case '{': return new int[]{SH, 0x2f};
            case '}': return new int[]{SH, 0x30};
            case '|': return new int[]{SH, 0x31};
            case ':': return new int[]{SH, 0x33};
            case '"': return new int[]{SH, 0x34};
            case '~': return new int[]{SH, 0x35};
            case '<': return new int[]{SH, 0x36};
            case '>': return new int[]{SH, 0x37};
            case '?': return new int[]{SH, 0x38};
            default: return null;
        }
    }

    private static String hidUsageName(int usage) {
        if (usage >= 0x04 && usage <= 0x1d) {
            return String.valueOf((char) ('a' + (usage - 0x04)));
        }
        if (usage >= 0x1e && usage <= 0x26) {
            return String.valueOf((char) ('1' + (usage - 0x1e)));
        }
        if (usage == 0x27) return "0";
        switch (usage) {
            case 0x2c: return "space";
            case 0x2d: return "minus";
            case 0x2e: return "equal";
            case 0x2f: return "lbracket";
            case 0x30: return "rbracket";
            case 0x31: return "backslash";
            case 0x33: return "semicolon";
            case 0x34: return "quote";
            case 0x35: return "grave";
            case 0x36: return "comma";
            case 0x37: return "period";
            case 0x38: return "slash";
            default: return null;
        }
    }

    /**
     * Write control plane. App CE ({@code getFilesDir}) is authoritative for
     * layout: SELinux often blocks priv_app write to /data/local/tmp and
     * /data/misc/titan2. pad-agent (root) reads CE first for host_layout.
     */
    private static void writePlane(Context ctx, String name, String value) {
        String v = value == null ? "" : value;
        // 12.01: skip no-op writes. Rewriting titan2_host_layout every ensure/publish
        // bumped mtime → pad-agent re-read + InputReader TitanKey rebind thrash
        // (Generation 60+) → heat + multi-letter spam + letter variation popup.
        // 1) App private — always first (works without shared SELinux)
        if (ctx != null) {
            try {
                File cef = new File(ctx.getFilesDir(), name);
                if (!sameFileContent(cef, v)) {
                    java.io.FileOutputStream fos = ctx.openFileOutput(name, Context.MODE_PRIVATE);
                    fos.write(v.getBytes(StandardCharsets.UTF_8));
                    fos.close();
                }
            } catch (Exception e) {
                Log.w(TAG, "writePlane CE failed " + name + ": " + e);
            }
        }
        // 2) AgentBridge (CE + best-effort shared) — put itself should prefer stable
        try {
            if (ctx != null) AgentBridge.put(ctx, name, v);
        } catch (Exception ignored) {}
        // 3) Settings.Global mirror for unrooted lab/bench (tmp often SELinux-deny).
        // Key = plane name as-is (e.g. titan2_usb_hid_session). Needs WRITE_SECURE_SETTINGS
        // when priv-app; userdebug adb can still `settings get global <name>`.
        if (ctx != null && isGlobalPlaneName(name)) {
            try {
                String cur = android.provider.Settings.Global.getString(
                    ctx.getContentResolver(), name);
                if (cur == null || !v.equals(cur.trim())) {
                    android.provider.Settings.Global.putString(
                        ctx.getContentResolver(), name, v);
                }
            } catch (Exception ignored) {
                try {
                    android.provider.Settings.System.putString(
                        ctx.getContentResolver(), name, v);
                } catch (Exception ignored2) {}
            }
        }
        // 4) Shared paths best-effort (may SELinux-deny; non-fatal).
        // 13.41: skip /data/misc/titan2 while AgentBridge OS-plane backoff is armed
        // so exclusive checks + heartbeats do not flood avc: denied.
        byte[] data = v.getBytes(StandardCharsets.UTF_8);
        java.util.ArrayList<String> dirs = new java.util.ArrayList<>(3);
        dirs.add("/data/local/tmp");
        if (AgentBridge.osCtrlAllowed()) {
            dirs.add("/data/misc/titan2");
        }
        dirs.add("/data/user/0/com.titanus2.usbhid/files");
        for (String d : dirs) {
            try {
                writeFile(new File(d, name), data);
            } catch (Exception e) {
                if ("/data/misc/titan2".equals(d)) AgentBridge.noteOsCtrlDenied();
            }
        }
    }

    /** True when file exists and UTF-8 body equals want (trim trailing newline). */
    private static boolean sameFileContent(File f, String want) {
        if (f == null || !f.isFile() || want == null) return false;
        try {
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                StandardCharsets.UTF_8).trim();
            return want.trim().equals(s);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isGlobalPlaneName(String name) {
        if (name == null) return false;
        switch (name) {
            case "titan2_usb_hid_session":
            case "titan2_usb_hid_keys":
            case "titan2_usb_hid_grab":
            case "titan2_usb_hid_mouse":
            case "titan2_usb_hid_local_input":
            case FILE_LAYOUT:
            case FILE_KEYS_PAUSE:
                return true;
            default:
                return name.startsWith("titan2_km_") || name.startsWith("titan2_host_");
        }
    }

    /**
     * Merge control plane by file mtime (same rule as {@link AgentBridge#get}).
     * Layout / keys_pause still bias Controls CE when its mtime ties or leads —
     * SELinux-stale shared tmp must not beat a fresh CE write.
     * <p>
     * Settings.Global is <b>fallback only</b> when no file has a value. Preferring
     * Global first left exclusive Specials dual-typing: stale Global {@code session=0}
     * / {@code grab=0} beat fresher {@code /data/local/tmp} after HID arm (B2).
     */
    private static String readPlane(Context ctx, String name) {
        long bestMt = -1;
        String best = null;
        boolean layoutPlane = FILE_LAYOUT.equals(name) || FILE_KEYS_PAUSE.equals(name);
        // Layout: CE first pass with slight mtime boost so app write wins ties
        if (layoutPlane && ctx != null) {
            try {
                File f = new File(ctx.getFilesDir(), name);
                if (f.isFile()) {
                    String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        StandardCharsets.UTF_8).trim();
                    if (!s.isEmpty()) {
                        // CE authoritative for layout — return immediately when present
                        return s;
                    }
                }
            } catch (Exception ignored) {}
        }
        java.util.List<File> files = new java.util.ArrayList<>();
        // tmp before OS plane — rootless lab: Global/tmp are live; misc is denied
        files.add(new File("/data/local/tmp", name));
        if (AgentBridge.osCtrlAllowed()) {
            files.add(new File("/data/misc/titan2", name));
        }
        if (ctx != null) {
            files.add(new File(ctx.getFilesDir(), name));
            try {
                File ext = ctx.getExternalFilesDir(null);
                if (ext != null) files.add(new File(ext, name));
            } catch (Exception ignored) {}
        }
        // HID app CE (Controls may not write here under SELinux)
        files.add(new File("/data/user/0/com.titanus2.usbhid/files", name));
        files.add(new File("/data/data/com.titanus2.usbhid/files", name));
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            try {
                String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8).trim();
                if (s.isEmpty()) continue;
                long mt = f.lastModified();
                if (mt >= bestMt) {
                    bestMt = mt;
                    best = s;
                }
            } catch (Exception e) {
                String p = f.getPath();
                if (p != null && p.startsWith(AgentBridge.OS_CTRL)) {
                    AgentBridge.noteOsCtrlDenied();
                }
            }
        }
        if (best != null) return best;
        // Global/System only when files missing (unrooted lab SELinux deny)
        if (ctx != null && isGlobalPlaneName(name)) {
            try {
                String g = android.provider.Settings.Global.getString(
                    ctx.getContentResolver(), name);
                if (g != null && !g.isEmpty()) return g.trim();
            } catch (Exception ignored) {}
            try {
                String g = android.provider.Settings.System.getString(
                    ctx.getContentResolver(), name);
                if (g != null && !g.isEmpty()) return g.trim();
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static void writeFile(File f, byte[] data) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        // 12.01: no-op if body already matches — preserve mtime (pad-agent + InputReader)
        if (f.isFile() && data != null) {
            try {
                byte[] cur = java.nio.file.Files.readAllBytes(f.toPath());
                String a = new String(cur, StandardCharsets.UTF_8).trim();
                String b = new String(data, StandardCharsets.UTF_8).trim();
                if (a.equals(b)) return;
            } catch (Exception ignored) {}
        }
        // 11.97: atomic replace — in-place FileOutputStream truncates to empty first,
        // so concurrent shell/pad-agent cat can see plane="" (B1 feel flake).
        File tmp = new File(parent != null ? parent : f.getAbsoluteFile().getParentFile(),
            f.getName() + ".tmp." + android.os.Process.myPid());
        try (FileOutputStream out = new FileOutputStream(tmp, false)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            // Cross-filesystem or SELinux: fall back to in-place (still best-effort).
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                out.write(data);
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        f.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        f.setWritable(true, false);
    }

    private static void toast(Context ctx, String msg) {
        if (ctx == null || msg == null) return;
        // Hold arming — optional under Developer → Debug (default off).
        if (!DebugPrefs.layoutToasts(ctx)) return;
        toastAlways(ctx, msg);
    }

    /** Toggle/off feedback — always show so sticky layout is not silent. */
    private static void toastAlways(Context ctx, String msg) {
        if (ctx == null || msg == null) return;
        try {
            Toast.makeText(ctx.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }
}
