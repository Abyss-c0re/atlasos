package com.titanus2.controls;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import com.titanus2.controls.subdisplay.SubDisplayFaceOverlay;
import com.titanus2.controls.subdisplay.SubDisplayPrefs;

/**
 * Accessibility hub: trackpad whitelist + hardware key remap
 * (side + upper keys; short / long / double) + magic hold chords.
 * <p>
 * Printed specials (! @ #) are <b>not</b> handled here — pad-agent maps the
 * chosen specials key to {@code ALT_RIGHT} and TitanKey.kcm {@code alt:} types
 * them. This service must not consume that key (that broke Specials=Sym).
 * While Keys settings is open, only identify keys — never run actions.
 */
public class TrackpadAccessService extends AccessibilityService {
    private static TrackpadAccessService instance;

    /** True only while this service is bound. Listed-but-crashed is false. */
    public static boolean isBound() {
        return instance != null;
    }
    private String lastPkg;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Map<Integer, Long> downAt = new HashMap<>();
    private final Map<Integer, Boolean> longFired = new HashMap<>();
    private final Map<Integer, Runnable> longTasks = new HashMap<>();
    private final Map<Integer, Runnable> shortPending = new HashMap<>();
    private final Map<Integer, Long> lastShortUp = new HashMap<>();
    private static final long LONG_MS = 550;
    /** Layout hold acts as a true modifier — arm quickly, not after 550ms. */
    private static final long LAYOUT_HOLD_MS = 70;
    /** Letter / upper-key double window. */
    private static final long DOUBLE_MS = 320;
    /**
     * Side keys (gpio / ff_key): slightly longer double window — physical side
     * buttons are slower than TitanKey letters; 320ms missed human doubles.
     */
    private static final long SIDE_DOUBLE_MS = 480;
    private static volatile String lastSeen = "";
    /** Throttle a11y_live plane writes (12.62). */
    private static volatile long lastA11yLiveStamp;
    /**
     * 12.70: idle heartbeat so pad-agent 1.63 can treat stale a11y_live as dead
     * (listed-but-unbound left sides inert forever).
     */
    /** 13.39: 4s (was 8s) so pad-agent sees live sooner after rebind/doze. */
    private static final long A11Y_LIVE_HEARTBEAT_MS = 4_000L;
    /** Throttle dens residual pin so heartbeat does not thrash Settings. */
    private long lastTaskbarPinMs;
    /**
     * 13.42: agent plane side long/double can be wiped to none while CE prefs
     * stay factory (C-SIDE-PUBLISH-WIPE). Periodic re-publish ≤60s.
     * 13.43: also re-mirror on the next heartbeat (≤4s) when plane diverges
     * from CE prefs — doze wipe must not leave sides dead for a full minute.
     */
    private long lastSidePlaneBeltMs;
    private static final long SIDE_PLANE_BELT_MS = 60_000L;
    /**
     * 13.44 B2: session-off ghost layout / keys_pause / remote_q residual.
     * pad-agent also heals; Controls belt covers rootless Global-only thrash.
     * 13.45: edge (exclusive→idle) + dirty-plane fast path ≤4s, not only 15s.
     */
    private long lastB2IdleBeltMs;
    private static final long B2_IDLE_BELT_MS = 15_000L;
    /** Last heartbeat exclusive sample — edge-trigger B2 heal on stop. */
    private boolean lastB2ExclusiveLive;
    private final Runnable a11yLiveHeartbeat = new Runnable() {
        @Override public void run() {
            if (instance != TrackpadAccessService.this) return;
            try {
                AgentBridge.put(TrackpadAccessService.this, AgentBridge.A11Y_LIVE, "1");
                lastA11yLiveStamp = android.os.SystemClock.elapsedRealtime();
                // Belt: Global when tmp SELinux-deny (assert races live=0 after rebind)
                try {
                    android.provider.Settings.Global.putString(
                        getContentResolver(), AgentBridge.A11Y_LIVE, "1");
                } catch (Exception ignored2) {}
            } catch (Exception ignored) {}
            // P0 Key a11y master switch: wipe/import can leave km_enabled=0 while
            // a11y is bound — pad-agent KEY_FIRE sides go silent (B1).
            try {
                KeyMapPrefs kp = new KeyMapPrefs(TrackpadAccessService.this);
                if (!kp.isEnabled()) {
                    kp.setEnabled(true);
                    kp.publishToAgent(TrackpadAccessService.this);
                } else {
                    AgentBridge.put(TrackpadAccessService.this, "titan2_km_enabled", "1");
                }
                // 13.48: do NOT re-force layout:specials_hold (hold Sym owns specials).
                // Belt: chrome→none once (migrate v4), publish CE prefs if plane
                // has chrome/empty only — never fight user none or custom binds.
                long nowBelt = android.os.SystemClock.elapsedRealtime();
                if (nowBelt - lastSidePlaneBeltMs > SIDE_PLANE_BELT_MS) {
                    lastSidePlaneBeltMs = nowBelt;
                    try { kp.migrateSideDefaultsNone(); } catch (Exception ignored2) {} // v4 one-shot
                    try { kp.healSideChromeToFactory(); } catch (Exception ignored2) {}
                    try {
                        if (AgentBridge.get(TrackpadAccessService.this,
                                AgentBridge.SPECIALS_METHOD, null) == null
                                || AgentBridge.get(TrackpadAccessService.this,
                                AgentBridge.SPECIALS_METHOD, "").isEmpty()) {
                            KeyMapPrefs.setSpecialsMethod(TrackpadAccessService.this,
                                KeyMapPrefs.SPECIALS_METHOD_KCM);
                        }
                    } catch (Exception ignored2) {}
                    // Publish only when plane has chrome or empty (not when user none)
                    try {
                        if (kp.sideAgentPlaneNeedsChromeHeal(
                                TrackpadAccessService.this)) {
                            kp.publishSidesAndSpecialsMethod(
                                TrackpadAccessService.this);
                        }
                    } catch (Exception ignored2) {}
                }
            } catch (Exception ignored) {}
            // 13.39: session-off Sym inject residual — clear sticky inject_pause
            // while a11y is bound and exclusive HID is not live.
            boolean exclusiveLive = false;
            try {
                exclusiveLive = HostLayoutController.isHidExclusiveLiveFast(
                        TrackpadAccessService.this);
            } catch (Exception ignored) {}
            try {
                if (!exclusiveLive) {
                    com.titanus2.api.InputPlane.put(TrackpadAccessService.this,
                        com.titanus2.api.Titan2ApiContract.FILE_INJECT_PAUSE, "0");
                }
            } catch (Exception ignored) {}
            // 13.44/13.45 B2: session-off ghost specials plane / keys_pause / remote_q.
            // Fast path: exclusive→idle edge or dirty plane (≤ heartbeat); belt 15s quiet.
            try {
                if (!exclusiveLive) {
                    long nowB2 = android.os.SystemClock.elapsedRealtime();
                    boolean edgeStop = lastB2ExclusiveLive;
                    boolean dirty = false;
                    try {
                        dirty = sessionOffB2PlaneDirty(TrackpadAccessService.this);
                    } catch (Exception ignored2) {}
                    boolean periodicB2 = (nowB2 - lastB2IdleBeltMs > B2_IDLE_BELT_MS);
                    if (edgeStop || dirty || periodicB2) {
                        lastB2IdleBeltMs = nowB2;
                        try {
                            HostLayoutController.healGhostPhoneLayout(
                                TrackpadAccessService.this);
                        } catch (Exception ignored2) {}
                        try {
                            HostLayoutController.healStaleHidPlane(
                                TrackpadAccessService.this);
                        } catch (Exception ignored2) {}
                        try {
                            if (sessionOffRemoteQDirty()) {
                                KeyActions.clearRemoteHidQueues(
                                    TrackpadAccessService.this);
                                KeyActions.clearAgentKeyQueue(
                                    TrackpadAccessService.this);
                            }
                        } catch (Exception ignored2) {}
                    }
                }
            } catch (Exception ignored) {}
            lastB2ExclusiveLive = exclusiveLive;
            // P0 taskbar residual dens strip re-raise (Launcher3 after unlock)
            try {
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastTaskbarPinMs > 45_000L) {
                    lastTaskbarPinMs = now;
                    TaskbarPin.pinOff(TrackpadAccessService.this);
                }
            } catch (Exception ignored) {}
            // 13.92 FB-SEC-1 residual: privacy-on edge force-stop left later
            // camera/mic opens streaming under unlock dialog — reassert while
            // blocked (throttled inside enforcer; cool, no exclusive thrash).
            try {
                SensorPrivacyEnforcer.reassertBlockedSensors(
                    TrackpadAccessService.this);
            } catch (Exception ignored) {}
            try { h.postDelayed(this, A11Y_LIVE_HEARTBEAT_MS); } catch (Exception ignored) {}
        }
    };

    /**
     * B2 13.44: leftover exclusive Specials bytes with HID session off.
     * Empty queues are zero-length files — only flush when dirty.
     */
    private static boolean sessionOffRemoteQDirty() {
        String[] paths = new String[] {
            "/data/local/tmp/titan2_remote_hid.q",
            "/data/local/tmp/titan2_hid_remote_q",
            "/data/misc/titan2/titan2_remote_hid.q",
            "/data/misc/titan2/titan2_hid_remote_q",
        };
        for (String p : paths) {
            try {
                java.io.File f = new java.io.File(p);
                if (f.isFile() && f.length() > 0) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * B2 13.45: any session-off dual-type residual worth immediate heal.
     * Ghost host_layout (specials/arrows), sticky keys_pause, or non-empty remote_q.
     */
    private static boolean sessionOffB2PlaneDirty(android.content.Context ctx) {
        if (sessionOffRemoteQDirty()) return true;
        try {
            String layout = AgentBridge.get(ctx, "titan2_host_layout", null);
            if (layout != null) {
                String n = layout.trim().toLowerCase(java.util.Locale.US);
                if (!n.isEmpty() && !"off".equals(n) && !"inherit".equals(n)
                        && !"0".equals(n) && !"none".equals(n)) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        try {
            String kp = AgentBridge.get(ctx, "titan2_host_layout_keys_pause", null);
            if (kp != null) {
                String t = kp.trim();
                if ("1".equals(t) || "true".equalsIgnoreCase(t)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean magicHeld;
    private boolean magicUsedChord;
    private final Set<Integer> magicChordKeys = new HashSet<>();
    /** 12.77: specials key held for inject method (Sym/Alt → letter → glyph). */
    private boolean specialsInjectHeld;
    /** 13.01: uptime when Sym inject armed — auto-clear if UP missed (dead HW kb). */
    private long specialsInjectHeldAt;
    /**
     * 13.62: last real specials-mod DOWN only (not letter commits).
     * Letter inject used to refresh specialsInjectHeldAt → stuck Sym mode for
     * 120s → every key became specials (cursor jump / random glyphs).
     */
    private long specialsModDownAt;
    /**
     * 13.65: missed-UP safety for Sym inject hold (not a chord deadline).
     * Letter commits must never refresh specialsModDownAt (13.62 stuck-mode lock).
     * OS Sym autorepeat still refreshes the clock (13.63). A hard 2.5s cut killed
     * mid-chord inject when OEM never auto-repeats Sym and when user disables
     * key repeat (new Pad setting) — hold died before the letter.
     * Real Sym UP still clears immediately; this only covers missed UP.
     */
    private static final long SPECIALS_INJECT_HELD_MAX_MS = 30_000L;
    /** When key-repeat is off, OS will not refresh specialsModDownAt at all. */
    private static final long SPECIALS_INJECT_HELD_MAX_NO_REPEAT_MS = 120_000L;
    private final Set<Integer> specialsInjectLetterScans = new HashSet<>();
    /**
     * 13.27/13.35/13.40: last emitted specials glyph + time — dual residual only.
     * Must NOT block intentional second press of same letter while Sym held
     * (user: hold Sym, tap C twice → need "88", not one "8" until Sym release).
     */
    private char lastSpecialsInjectGlyph;
    private long lastSpecialsInjectGlyphAt;
    private int lastSpecialsInjectKeyCode;
    /**
     * 13.61: dual residual only — timing from
     * {@link com.titanus2.api.KeyInputTiming#dualResidualDebounceMs()} (shared SoT).
     * First glyph is immediate. Re-tap after UP is free.
     */
    private static final long SPECIALS_LETTER_STALE_MS = 280L;
    /** Letter currently held for specials (single + hold-repeat). */
    private int specialsHoldKeyCode;
    private int specialsHoldCan;
    private int specialsHoldCanRaw;
    private char specialsHoldGlyph;
    private int specialsHoldMeta;
    private boolean specialsHoldFired;
    private Runnable specialsHoldRepeatTask;
    /**
     * 13.07–13.10: idle phone typing — EventHub residual after TitanKey Generation
     * thrash re-delivers the same letter DOWN N× per physical press (5× spam).
     * Hold-set until UP kills dual even when spaced &gt;100ms; time belt for
     * DOWN-UP-DOWN flapping. Idle only (no layout/Sym inject/exclusive).
     */
    private final Set<Integer> idleLetterKeysDown = new HashSet<>();
    /** 13.11: also hold by Linux scan (OEM dual-scan same press, different keyCode). */
    private final Set<Integer> idleLetterScansDown = new HashSet<>();
    private int lastIdleLetterKeyCode;
    private long lastIdleLetterDownAt;
    /** After UP — kills EventHub DOWN-UP-DOWN residual without blocking hold-repeat. */
    private long lastIdleLetterUpAt;
    /**
     * T-013 (Atlas ISSUES via nanobot): Shift = momentary hold only.
     * Caps only on <b>double bare-tap Shift</b> (product latch).
     * 15.58: never blind-inject CAPS (toggle while off → permanent uppercase).
     * 15.59: double-tap window + productCapsLatched so intentional Caps sticks.
     */
    private boolean hwShiftDown;
    private long hwShiftDownAt;
    private boolean hwShiftSawLetter;
    /** Last CAPS meta seen on a real TitanKey/system event (not our inject). */
    private boolean lastKnownCapsOn;
    /**
     * Intentional Caps from double bare-tap Shift (Atlas T-013).
     * Residual framework CAPS without this flag is cleared on letter.
     */
    private boolean productCapsLatched;
    /** Uptime of last bare Shift UP (no letter chord) for double-tap Caps. */
    private long lastBareShiftUpAt;
    private static final long DOUBLE_SHIFT_CAPS_MS = 420L;
    private final Runnable hwShiftStickyRelease = () -> {
        if (!hwShiftDown) return;
        hwShiftDown = false;
        injectShiftReleaseAndClearCaps();
    };
    /**
     * Fallback only — prefer {@link com.titanus2.api.KeyInputTiming#dualResidualDebounceMs()}.
     * 15.32 used 120ms (blocked intentional double letters — 2899db25).
     * 15.74 restores post-UP dual kill at dualResidualDebounceMs (≈20–40ms).
     * REG-L: multi-char / letter-menu must never ship without this residual path.
     */
    private static final long IDLE_LETTER_DEBOUNCE_MS = 40L;
    /**
     * Physical keys currently owned by host-layout (scan → keyCode).
     * Titan HW often re-delivers auto-repeat as DOWN with repeatCount=0 —
     * without this, Specials injects once per repeat and floods characters.
     */
    private final Map<Integer, Integer> layoutHeldScans = new HashMap<>();

    public static TrackpadAccessService get() { return instance; }
    public static String lastSeenKey() { return lastSeen; }
    public static String foregroundPkg() {
        TrackpadAccessService s = instance;
        return s == null ? null : s.lastPkg;
    }

    /**
     * 12.34: drop layout-owned physical scans when hold/sticky ends.
     * Stuck entries swallow letter keys after Specials release (no inject, dead typing)
     * or pair with OS re-delivery as multi-glyph when remap re-arms.
     */
    public static void clearLayoutKeyOwnership() {
        TrackpadAccessService s = instance;
        if (s != null) {
            try { s.layoutHeldScans.clear(); } catch (Exception ignored) {}
            // 13.01: also free stuck Sym inject hold (was total HW keyboard death)
            try {
                s.specialsInjectHeld = false;
                s.specialsInjectHeldAt = 0;
                s.specialsModDownAt = 0;
                s.specialsInjectLetterScans.clear();
                s.lastSpecialsInjectGlyph = 0;
                s.lastSpecialsInjectGlyphAt = 0;
                s.lastSpecialsInjectKeyCode = 0;
                s.cancelSpecialsArm();
                s.idleLetterKeysDown.clear();
                s.idleLetterScansDown.clear();
                s.lastIdleLetterKeyCode = 0;
                s.lastIdleLetterDownAt = 0;
                s.lastIdleLetterUpAt = 0;
                // 15.57+: residual sticky Shift; keep productCapsLatched
                s.hwShiftDown = false;
                s.hwShiftSawLetter = false;
                s.lastBareShiftUpAt = 0L;
                s.h.removeCallbacks(s.hwShiftStickyRelease);
                if (!s.productCapsLatched) {
                    s.clearCapsIfOn();
                }
            } catch (Exception ignored) {}
            try { s.magicHeld = false; s.magicUsedChord = false; s.magicChordKeys.clear(); }
            catch (Exception ignored) {}
        }
    }

    /**
     * Missed-UP safety window for Sym inject hold.
     * Key-repeat off / OEM no Sym autorepeat → longer belt (no mod-clock refresh path).
     */
    private long specialsInjectHeldMaxMs() {
        try {
            if (!com.titanus2.api.KeyInputTiming.isKeyRepeatEnabled(this)) {
                return SPECIALS_INJECT_HELD_MAX_NO_REPEAT_MS;
            }
            // At least 8× first-repeat timeout so slow chords work if OS never
            // auto-repeats the Sym key itself (common on Titan OEM).
            int t = com.titanus2.api.KeyInputTiming.keyRepeatTimeoutMs(this);
            long fromRepeat = (long) t * 8L;
            return Math.max(SPECIALS_INJECT_HELD_MAX_MS, fromRepeat);
        } catch (Throwable ignored) {
            return SPECIALS_INJECT_HELD_MAX_MS;
        }
    }

    /** Clear stuck Sym inject if UP never arrived (QA: no HW input at all). */
    private boolean specialsInjectStillHeld() {
        if (!specialsInjectHeld) return false;
        long now = android.os.SystemClock.uptimeMillis();
        // Prefer mod-down clock (13.62); fall back to arm clock for older paths
        long maxMs = specialsInjectHeldMaxMs();
        long anchor = specialsModDownAt > 0 ? specialsModDownAt : specialsInjectHeldAt;
        long age = anchor > 0 ? (now - anchor) : maxMs + 1;
        if (age > maxMs) {
            specialsInjectHeld = false;
            specialsInjectHeldAt = 0;
            specialsModDownAt = 0;
            specialsInjectLetterScans.clear();
            lastSpecialsInjectGlyph = 0;
            lastSpecialsInjectGlyphAt = 0;
            lastSpecialsInjectKeyCode = 0;
            cancelSpecialsArm();
            try { HostLayoutController.releaseKeysPauseIfInjectOnly(this); }
            catch (Exception ignored) {}
            return false;
        }
        return true;
    }

    /** Free letter ownership for re-tap (physical or scan=0 UP). */
    private void releaseSpecialsLetterOwnership(int keyCode, int can, int canRaw) {
        if (keyCode > 0) specialsInjectLetterScans.remove(keyCode);
        if (can > 0) specialsInjectLetterScans.remove(can);
        if (canRaw > 0) specialsInjectLetterScans.remove(canRaw);
        if (keyCode > 0 && keyCode == lastSpecialsInjectKeyCode) {
            // Allow immediate re-press of same key after real UP (debounce still
            // covers OEM dual DOWN-UP-DOWN within KeyInputTiming dual residual).
            lastSpecialsInjectKeyCode = 0;
        }
    }

    @Override public void onServiceConnected() {
        instance = this;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            | AccessibilityEvent.TYPE_VIEW_FOCUSED
            | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            | AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        setServiceInfo(info);

        // 12.60 B1: pad-agent screen-on side fallback uses this plane
        try { AgentBridge.put(this, AgentBridge.A11Y_LIVE, "1"); } catch (Exception ignored) {}
        lastA11yLiveStamp = android.os.SystemClock.elapsedRealtime();
        // 12.70/13.37: run heartbeat immediately (was 8s delayed → a11y_live=0
        // window after rebind; pad-agent treated Key a11y as dead → dual sides).
        try {
            h.removeCallbacks(a11yLiveHeartbeat);
            h.post(a11yLiveHeartbeat);
        } catch (Exception ignored) {}
        // Cube dens residual: full taskbar pin belt whenever a11y binds
        try { TaskbarPin.pinOff(this); } catch (Exception ignored) {}
        // 12.56/13.01: rebind — free layout ownership + stuck Sym inject hold
        // (stuck specialsInjectHeld swallowed every letter → no HW keyboard).
        try { clearLayoutKeyOwnership(); } catch (Exception ignored) {}
        try { KeyActions.clearAgentKeyQueue(this); } catch (Exception ignored) {}
        try { KeyActions.clearRemoteHidQueues(this); } catch (Exception ignored) {}
        try {
            HostLayoutController.releaseKeysPauseIfInjectOnly(this);
        } catch (Exception ignored) {}
        // Soft IME vs HW keyboard + HW accent/language letter-menu polish
        try { ImeHwPrefs.applyStored(this); } catch (Exception ignored) {}
        try { KeyRepeatPrefs.publish(this); } catch (Exception ignored) {}
        // Typing-lock plane can stick at 1 after crash/rebind — unfreeze pad/HID mouse
        try { TypingCursorLock.clear(this); } catch (Exception ignored) {}
        // 11.71: first a11y bind after wipe/install — QS/setup before hub open
        try { SetupWizardHeal.heal(this); } catch (Exception ignored) {}
        try { PadQsDefaults.ensureDefaultTile(this); } catch (Exception ignored) {}
        try { HostLayoutController.healStaleHidPlane(this); } catch (Exception ignored) {}
        // 12.10: rootless specials queues + idle plane (no Magisk)
        try { RootlessPlane.seed(this); } catch (Exception ignored) {}

        try { HostLayoutController.bindApp(this); } catch (Exception ignored) {}
        try { HostLayoutController.loadGlobalDefault(this); } catch (Exception ignored) {}
        try { HostLayoutController.healGhostPhoneLayout(this); } catch (Exception ignored) {}
        try { HostLayoutController.publish(this); } catch (Exception ignored) {}
        // 13.36: phone Sym inject readiness after rebind — re-mirror method plane
        // and clear sticky inject_pause when not exclusive (session-off dual residual).
        try {
            String meth = KeyMapPrefs.getSpecialsMethod(this);
            if (meth != null && !meth.isEmpty()) {
                AgentBridge.put(this, AgentBridge.SPECIALS_METHOD, meth);
            }
            if (!HostLayoutController.isHidExclusiveLiveFast(this)) {
                try {
                    com.titanus2.api.InputPlane.put(this,
                        com.titanus2.api.Titan2ApiContract.FILE_INJECT_PAUSE, "0");
                } catch (Exception ignored2) {}
                try {
                    AgentBridge.put(this, "titan2_specials_inject_pause", "0");
                } catch (Exception ignored2) {}
            }
        } catch (Exception ignored) {}
        // 13.37: final a11y_live stamp after heals (bind path can race put=1)
        try {
            AgentBridge.put(this, AgentBridge.A11Y_LIVE, "1");
            lastA11yLiveStamp = android.os.SystemClock.elapsedRealtime();
        } catch (Exception ignored) {}

        TrackpadPrefs p = new TrackpadPrefs(this);
        if (TrackpadPrefs.MODE_WHITELIST.equals(p.getMode())) {
            TrackpadPolicy.apply(this, null);
        }
        try {
            KeyMapPrefs kp = new KeyMapPrefs(this);
            TempKeyMapStack stack = new TempKeyMapStack(this);
            // Orphaned hid_session (offline death) → all slots stuck at none
            stack.clearStaleHidSilence(this, kp);
            // 11.89: B1 heal side chrome before first plane publish after bind
            kp.healSideChromeToFactory();
            kp.publishToAgent(this);
            // Live session: re-pin computer actions after reinstall / a11y restart
            stack.refreshSilenceIfActive(this, kp, null);
        } catch (Exception ignored) {}
        // P0 B1: after wipe, pad-agent / tmp plane may not be ready at first bind.
        // Re-publish factory sides + taskbar at 1s/5s/15s while service is live.
        schedulePostBindHeal();
        // Screen-off: a11y will not receive keys — keep pad-agent plane fresh.
        try {
            registerScreenBridge();
        } catch (Exception ignored) {}
        try { DisplayPlane.onForeground(this, "com.android.launcher3"); } catch (Exception ignored) {}
        try {
            if (SubDisplayPrefs.getMode(this) == SubDisplayPrefs.Mode.CUSTOM) {
                SubDisplayFaceOverlay.show(this);
            } else {
                SubDisplayFaceOverlay.hide(this);
            }
        } catch (Exception ignored) {}
        try {
            // Retry — WM / SettingsProvider late right after a11y bind (11.48)
            h.postDelayed(() -> {
                try { TaskbarPin.pinOff(this); } catch (Exception ignored) {}
                try { HostLayoutController.healStaleHidPlane(this); } catch (Exception ignored) {}
            }, 800);
            h.postDelayed(() -> {
                try { TaskbarPin.pinOff(this); } catch (Exception ignored) {}
                try { HostLayoutController.healStaleHidPlane(this); } catch (Exception ignored) {}
                try { AccessServiceHelper.ensureDefaultEnabled(this); } catch (Exception ignored) {}
            }, 2500);
        } catch (Exception ignored) {}
    }

    private boolean allDefault(KeyMapPrefs prefs, int scan) {
        int c = KeyMapPrefs.canonicalizeScan(scan);
        for (KeyMapPrefs.Press pr : KeyMapPrefs.Press.values()) {
            KeyMapPrefs.Slot sl = KeyMapPrefs.slotByScan(c, pr);
            if (sl == null) continue;
            if (!KeyMapPrefs.ACT_DEFAULT.equals(effectiveAction(prefs, sl.id))) return false;
        }
        return true;
    }


    /**
     * Effective shortcut: temp layers → per-app profile (foreground) → global.
     * HID silence uses temp; app profiles beat global while that app is open.
     */
    private String effectiveAction(KeyMapPrefs prefs, String slotId) {
        if (slotId == null) return KeyMapPrefs.ACT_DEFAULT;
        return new TempKeyMapStack(this).getEffective(prefs, slotId);
    }

    /** True when short/long/double all none or system-default (no real shortcut). */
    private boolean noActiveRemap(KeyMapPrefs prefs, int scan) {
        int c = KeyMapPrefs.canonicalizeScan(scan);
        for (KeyMapPrefs.Press pr : KeyMapPrefs.Press.values()) {
            KeyMapPrefs.Slot sl = KeyMapPrefs.slotByScan(c, pr);
            if (sl == null) continue;
            String a = effectiveAction(prefs, sl.id);
            if (a != null
                    && !KeyMapPrefs.ACT_DEFAULT.equals(a)
                    && !KeyMapPrefs.ACT_NONE.equals(a)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Leave Fn to keylayout (Ctrl / specials layer) only when there is no
     * shortcut action. OEM Home / Assistant on Fn must always win — previously
     * FN_MODE=ctrl or CHAR_MOD=fn skipped remaps and Home never fired.
     */
    private boolean skipFnWhenCtrlMode(KeyMapPrefs prefs, int scan) {
        if (!KeyMapPrefs.isFnScan(scan)) return false;
        if (!noActiveRemap(prefs, scan)) return false; // shortcut wins
        String cm = AgentBridge.get(this, AgentBridge.CHAR_MOD, "sym");
        if ("fn".equalsIgnoreCase(cm) || "function".equalsIgnoreCase(cm)) return true;
        String fn = AgentBridge.get(this, AgentBridge.FN_MODE, "stock");
        return "ctrl".equalsIgnoreCase(fn);
    }

    /**
     * Specials-owner key (Sym by default) must always reach keylayout as
     * ALT_RIGHT for OS KCM specials (Sym+letter → !@#). Remapping that key
     * via short/long/double broke long-press / hold-as-modifier on Sym.
     * <p>
     * Layout hold/toggle may still be armed in parallel without consuming
     * (see {@link #trackSpecialsModParallel}).
     */
    private boolean skipCharModKey(KeyMapPrefs prefs, int scan) {
        return KeyMapPrefs.isCharModScan(this, scan);
    }

    /**
     * 12.77 product specials: intercept active Sym/Alt (CHAR_MOD) + letter and
     * inject glyph via {@link HostLayoutController#emitSpecials} — the path that
     * works. Returns true if event fully consumed.
     * <p>
     * 12.80: also arm from keyCode SYM/ALT and from meta (isAlt/isSym) so the
     * first letter after a flaky scan match still glyphs (QA: multi-press).
     * <p>
     * 13.55: non-physical (scan=0) used to early-return before keyCode arm and
     * swallow letter DOWNs while held without emit → silent inject. Now:
     * arm/disarm Sym by keyCode even when scan flaky; emit letter by keyCode
     * while held; hold safety window 120s (OEM often no Sym auto-repeat).
     */
    private boolean trySpecialsInjectMethod(KeyEvent event, int scan, int rawScan,
            int keyCode, int action) {
        KeyMapProfiles profiles = new KeyMapProfiles(this);
        // 13.23: kcm method normally passes ALT_RIGHT to TitanKey.kcm.
        // After inject→kcm, EventHub can stay on BUTTON_2 until pad-agent hard
        // rebind — Sym then prints nothing while free Alt still works. While
        // specials owner arrives as BUTTON_2, fall back to inject glyphs so
        // the KCM UI choice is never silent.
        if (!profiles.isSpecialsInject(lastPkg)) {
            if (!isStaleKcmButton2Specials(event, scan, rawScan, keyCode)) {
                return false;
            }
        }
        int can = KeyMapPrefs.canonicalizeScan(scan);
        int canRaw = KeyMapPrefs.canonicalizeScan(rawScan);
        int spScan = KeyMapPrefs.resolveSpecialsScan(this);
        if (spScan <= 0) return false;
        // Only the configured specials owner (scan), not every Alt/Sym keyCode —
        // free Alt must stay menus; stuck isAltPressed must not force inject.
        // 13.15/13.55: also arm by keyCode when scan is 0/flaky (BUTTON_2 / ALT_RIGHT).
        boolean isMod = KeyMapPrefs.matchesSpecialsScan(can, spScan)
            || KeyMapPrefs.matchesSpecialsScan(canRaw, spScan)
            || KeyMapPrefs.isCharModScan(this, can)
            || KeyMapPrefs.isCharModScan(this, canRaw)
            || isInjectSpecialsModKeyCode(keyCode, spScan, can, canRaw);
        boolean physical = isPhysicalLayoutKeyEvent(event, rawScan, scan);
        // 12.98: while Sym inject held, swallow non-physical re-entry — but first
        // allow keyCode mod arm/disarm and letter emit (scan often 0 on OEM).
        // 12.94 passed inject through → KeyEvent multi-print in Termux.
        if (!physical) {
            if (isMod) {
                return handleSpecialsInjectMod(event, action);
            }
            if (!specialsInjectStillHeld()) return false;
            if (action == KeyEvent.ACTION_UP) {
                // 13.59: stop hold-repeat; free for re-tap
                if (keyCode <= 0 || specialsHoldKeyCode == keyCode
                        || specialsHoldKeyCode == 0) {
                    cancelSpecialsArm();
                }
                releaseSpecialsLetterOwnership(keyCode, can, canRaw);
                return true;
            }
            // 13.55: letter DOWN with scan=0 while Sym held — still inject
            if (action == KeyEvent.ACTION_DOWN
                    && keyCode > 0
                    && isIdlePhoneTypingLetter(keyCode)) {
                return emitSpecialsInjectLetter(event, keyCode, can, canRaw);
            }
            // residual while hold fresh
            return true;
        }
        if (isMod) {
            return handleSpecialsInjectMod(event, action);
        }
        // 12.98/13.01: only fresh Sym hold — auto-clear if UP missed (dead HW kb)
        if (!specialsInjectStillHeld()) return false;
        if (HostLayoutController.isModifierKey(keyCode)) return false;
        if (action == KeyEvent.ACTION_UP) {
            // 13.59: end letter-down — stop hold-repeat spam
            if (specialsHoldKeyCode == keyCode || keyCode <= 0) {
                cancelSpecialsArm();
            }
            releaseSpecialsLetterOwnership(keyCode, can, canRaw);
            return true;
        }
        if (action != KeyEvent.ACTION_DOWN) return false;
        return emitSpecialsInjectLetter(event, keyCode, can, canRaw);
    }

    /** Arm / refresh / release Sym inject hold (physical or flaky scan=0). */
    private boolean handleSpecialsInjectMod(KeyEvent event, int action) {
        if (action == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                // 13.27: only clear letter/glyph dedupe on first arm.
                // OEM dual Sym DOWN (rep=0 twice) used to clear mid-hold so
                // Sym+C re-emitted "8" → number spam (user report inject mode).
                boolean firstArm = !specialsInjectHeld;
                specialsInjectHeld = true;
                long nowMod = android.os.SystemClock.uptimeMillis();
                specialsInjectHeldAt = nowMod;
                specialsModDownAt = nowMod; // only mod DOWN anchors hold (13.62)
                if (firstArm) {
                    specialsInjectLetterScans.clear();
                    lastSpecialsInjectGlyph = 0;
                    lastSpecialsInjectGlyphAt = 0;
                    lastSpecialsInjectKeyCode = 0;
                    idleLetterKeysDown.clear();
                    idleLetterScansDown.clear();
                    cancelSpecialsArm();
                    // 12.81 exclusive HID: pause phys TitanKey so host gets glyphs
                    // from remote_q only (not raw Sym+letter QWERTY).
                    try {
                        HostLayoutController.ensureKeysPausedForExclusiveSpecials(this);
                    } catch (Exception ignored) {}
                }
            } else {
                // 13.63: OS Sym autorepeat proves mod still down — refresh both
                // clocks. 13.62 only refreshed specialsInjectHeldAt while
                // specialsInjectStillHeld preferred specialsModDownAt → hold
                // died at 2.5s mid-chord even with OS still repeating Sym.
                long nowRep = android.os.SystemClock.uptimeMillis();
                specialsInjectHeldAt = nowRep;
                specialsModDownAt = nowRep;
            }
            return true; // swallow mod — do not feed ALT to KCM (dual/flaky)
        }
        if (action == KeyEvent.ACTION_UP) {
            specialsInjectHeld = false;
            specialsInjectHeldAt = 0;
            specialsModDownAt = 0;
            specialsInjectLetterScans.clear();
            lastSpecialsInjectGlyph = 0;
            lastSpecialsInjectGlyphAt = 0;
            lastSpecialsInjectKeyCode = 0;
            cancelSpecialsArm();
            try {
                HostLayoutController.releaseKeysPauseIfInjectOnly(this);
            } catch (Exception ignored) {}
            return true;
        }
        return true;
    }

    /** Cancel hold-repeat + letter-down state (Sym up / rebind / letter UP). */
    private void cancelSpecialsArm() {
        if (specialsHoldRepeatTask != null) {
            try { h.removeCallbacks(specialsHoldRepeatTask); } catch (Exception ignored) {}
            specialsHoldRepeatTask = null;
        }
        specialsHoldKeyCode = 0;
        specialsHoldCan = 0;
        specialsHoldCanRaw = 0;
        specialsHoldGlyph = 0;
        specialsHoldMeta = 0;
        specialsHoldFired = false;
    }

    /**
     * Specials letter under Sym inject — <b>one glyph per physical letter press</b>.
     * <p>
     * 13.62: removed self-driven hold-repeat. It re-fired specials every
     * keyRepeatDelay while letter ownership was sticky (missed UP / dual residual)
     * → random glyphs + caret jumps during normal typing after a Sym chord.
     * Hold Sym + re-tap letters for multi-glyph; dual residual still swallowed.
     */
    private boolean emitSpecialsInjectLetter(KeyEvent event, int keyCode,
            int can, int canRaw) {
        if (!specialsInjectStillHeld()) return false;
        String ch = HostLayoutController.specialsCharPublic(keyCode);
        if (ch == null || ch.isEmpty()) {
            return false;
        }
        if (keyCode <= 0) return false;
        // OS autorepeat: swallow (no multi-glyph flood). Re-tap after UP re-emits.
        if (event.getRepeatCount() > 0) {
            return true;
        }
        char glyph = ch.charAt(0);
        int meta = event.getMetaState()
            & ~(KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON
                | KeyEvent.META_ALT_RIGHT_ON | KeyEvent.META_SYM_ON);
        long now = android.os.SystemClock.uptimeMillis();

        boolean trackedDown = specialsInjectLetterScans.contains(keyCode)
            || (can > 0 && specialsInjectLetterScans.contains(can))
            || (canRaw > 0 && specialsInjectLetterScans.contains(canRaw));
        // Dual residual / same physical press — one glyph only
        if (trackedDown) {
            return true;
        }
        long dualMs = com.titanus2.api.KeyInputTiming.dualResidualDebounceMs(this);
        boolean recentSame = lastSpecialsInjectGlyphAt > 0
            && (now - lastSpecialsInjectGlyphAt) < dualMs
            && (glyph == lastSpecialsInjectGlyph
                || keyCode == lastSpecialsInjectKeyCode);
        if (recentSame) {
            return true;
        }

        specialsInjectLetterScans.add(keyCode);
        if (can > 0) specialsInjectLetterScans.add(can);
        if (canRaw > 0) specialsInjectLetterScans.add(canRaw);
        // Do NOT refresh specialsModDownAt here — that trapped typing in Sym mode
        lastSpecialsInjectGlyph = glyph;
        lastSpecialsInjectGlyphAt = now;
        lastSpecialsInjectKeyCode = keyCode;
        try {
            if (HostLayoutController.isHidExclusiveLiveFast(this)) {
                HostLayoutController.ensureKeysPausedForExclusiveSpecials(this);
            }
        } catch (Exception ignored) {}
        // Typing lock only when global option on (shared plane)
        try { TypingCursorLock.pulse(this); } catch (Exception ignored) {}
        try {
            HostLayoutController.emitSpecials(this, glyph, meta);
        } catch (Exception ignored) {}
        return true;
    }

    /**
     * Non-consuming tracker for the specials modifier (Sym).
     * Allows layout:specials_hold on long without swallowing ALT for KCM.
     */
    private void trackSpecialsModParallel(KeyMapPrefs prefs, int scan,
                                          int action, int repeat) {
        if (!KeyMapPrefs.isCharModScan(this, scan)) return;
        KeyMapPrefs.Slot longSlot = KeyMapPrefs.slotByScan(
            KeyMapPrefs.canonicalizeScan(scan), KeyMapPrefs.Press.LONG);
        String longAct = longSlot != null
            ? effectiveAction(prefs, longSlot.id) : KeyMapPrefs.ACT_DEFAULT;
        boolean layoutHold = HostLayoutController.isLayoutHoldAction(longAct);
        KeyMapPrefs.Slot dblSlot = KeyMapPrefs.slotByScan(
            KeyMapPrefs.canonicalizeScan(scan), KeyMapPrefs.Press.DOUBLE);
        String dblAct = dblSlot != null
            ? effectiveAction(prefs, dblSlot.id) : KeyMapPrefs.ACT_DEFAULT;
        boolean hasDbl = dblAct != null
            && !KeyMapPrefs.ACT_DEFAULT.equals(dblAct)
            && !KeyMapPrefs.ACT_NONE.equals(dblAct);

        if (action == KeyEvent.ACTION_DOWN && repeat == 0) {
            long now = SystemClock.uptimeMillis();
            Long lastUp = lastShortUp.get(scan);
            if (hasDbl && lastUp != null && now - lastUp < DOUBLE_MS) {
                lastShortUp.remove(scan);
                Runnable old = longTasks.remove(scan);
                if (old != null) h.removeCallbacks(old);
                HostLayoutController.endHold(scan);
                // 11.75: double-tap Specials/Arrows toggle — unfreeze pad first
                try { TypingCursorLock.clear(this); } catch (Exception ignored) {}
                KeyActions.run(this, dblAct);
                longFired.put(scan, true);
                return;
            }
            if (!layoutHold && !hasDbl) return; // pure KCM modifier, no parallel work
            downAt.put(scan, now);
            longFired.put(scan, false);
            Runnable old = longTasks.remove(scan);
            if (old != null) h.removeCallbacks(old);
            if (!layoutHold) return;
            final int sc = scan;
            final String act = longAct;
            Runnable task = () -> {
                if (!Boolean.FALSE.equals(longFired.get(sc))) return;
                if (!downAt.containsKey(sc)) return;
                longFired.put(sc, true);
                HostLayoutController.beginHold(TrackpadAccessService.this, sc, act);
            };
            longTasks.put(sc, task);
            h.postDelayed(task, LAYOUT_HOLD_MS);
            return;
        }
        if (action == KeyEvent.ACTION_UP) {
            Runnable old = longTasks.remove(scan);
            if (old != null) h.removeCallbacks(old);
            downAt.remove(scan);
            boolean wasLong = Boolean.TRUE.equals(longFired.remove(scan));
            HostLayoutController.endHold(scan);
            if (!wasLong) lastShortUp.put(scan, SystemClock.uptimeMillis());
        }
    }

    /** Cancel pending short/long so nothing fires after leaving Keys UI. */
    private void clearPendingRemaps() {
        for (Runnable r : longTasks.values()) {
            if (r != null) h.removeCallbacks(r);
        }
        longTasks.clear();
        for (Runnable r : shortPending.values()) {
            if (r != null) h.removeCallbacks(r);
        }
        shortPending.clear();
        downAt.clear();
        longFired.clear();
        lastShortUp.clear();
        magicHeld = false;
        magicUsedChord = false;
        magicChordKeys.clear();
    }

    private android.content.BroadcastReceiver screenBridge;
    private final java.util.List<Runnable> postBindHeals = new java.util.ArrayList<>();

    /**
     * P0 B1/taskbar: after wipe, /data/local/tmp and Settings may lag the first
     * a11y bind. Re-publish factory side km + pin taskbar while we own keys.
     */
    private void schedulePostBindHeal() {
        for (Runnable r : postBindHeals) {
            try { h.removeCallbacks(r); } catch (Exception ignored) {}
        }
        postBindHeals.clear();
        // 12.02: fewer post-bind waves — 4× publish/km was plane thrash fuel.
        // 1s: a11y race; 15s: late SettingsProvider. Skip 5s/45s spam.
        long[] delays = { 1_000L, 15_000L };
        for (long d : delays) {
            Runnable r = () -> {
                if (instance != this) return;
                try { TaskbarPin.pinOff(this); } catch (Exception ignored) {}
                // 12.64: post-bind waves re-stamp a11y_live (tmp heal after agent seed)
                try { AgentBridge.put(this, AgentBridge.A11Y_LIVE, "1"); } catch (Exception ignored) {}
                // QA pad QS: SystemUI parks custom tiles at end — re-front on heal
                try { PadQsDefaults.ensureDefaultTile(this); } catch (Exception ignored) {}
                // 11.72: late post-bind waves also setup/IME/typing (wipe residual)
                try { SetupWizardHeal.heal(this); } catch (Exception ignored) {}
                try { ImeHwPrefs.applyStored(this); } catch (Exception ignored) {}
                try { TypingCursorLock.clear(this); } catch (Exception ignored) {}
                try {
                    KeyMapPrefs km = new KeyMapPrefs(this);
                    // 11.90: late waves also B1 side chrome heal before plane write
                    km.healSideChromeToFactory();
                    km.publishToAgent(this);
                } catch (Exception ignored) {}
                try {
                    HostLayoutController.healStaleHidPlane(this);
                    // publish is no-op when plane body unchanged (12.01+)
                    HostLayoutController.publish(this);
                } catch (Exception ignored) {}
                // B8: pad restart only when exclusive HID is off (13.51 — HID owns pad)
                try {
                    if (!HostLayoutController.isHidExclusiveLiveFast(this)) {
                        String pm = PadModeController.getMode(this);
                        if (PadModeController.MOUSE.equals(pm)
                                || PadModeController.TRACKPAD.equals(pm)) {
                            PadModeController.ensureTouchpaddProcess();
                        } else {
                            PadModeController.stopTouchpaddProcess();
                        }
                    }
                } catch (Exception ignored) {}
            };
            postBindHeals.add(r);
            h.postDelayed(r, d);
        }
    }

    private void registerScreenBridge() {
        if (screenBridge != null) return;
        screenBridge = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                if (i == null || i.getAction() == null) return;
                final String act = i.getAction();
                final TrackpadAccessService self = TrackpadAccessService.this;
                // Re-publish maps when display sleeps so pad-agent KEY_FIRE path
                // has titan2_km_* + screen_off=1 (a11y onKeyEvent stops here).
                try {
                    HostLayoutController.bindApp(self);
                    KeyMapPrefs km = new KeyMapPrefs(self);
                    // 11.90: screen-off path heals side chrome before agent read
                    km.healSideChromeToFactory();
                    km.publishToAgent(self);
                } catch (Exception ignored) {}
                try {
                    HostLayoutController.publish(self);
                } catch (Exception ignored) {}
                // 12.50: display off — free a11y layoutHeld (no UP while dozing),
                // heal B2 keys_pause twins when exclusive is dead, unstick pad freeze.
                // Keep holdMode for pad-agent KEY_FIRE specials (do not endHoldAll).
                if (android.content.Intent.ACTION_SCREEN_OFF.equals(act)) {
                    try { clearLayoutKeyOwnership(); } catch (Exception ignored) {}
                    try { HostLayoutController.healStaleHidPlane(self); } catch (Exception ignored) {}
                    try { TypingCursorLock.clear(self); } catch (Exception ignored) {}
                    try { KeyActions.clearAgentKeyQueue(self); } catch (Exception ignored) {}
                    // B8: pad off while dozing — kill orphan touchpadd (heat)
                    try {
                        String pm = PadModeController.getMode(self);
                        if (!PadModeController.MOUSE.equals(pm)
                                && !PadModeController.TRACKPAD.equals(pm)) {
                            PadModeController.stopTouchpaddProcess();
                        }
                    } catch (Exception ignored) {}
                }
                // 11.55: screen-on / unlock — taskbar residual + typing unstick + B2 plane
                if (android.content.Intent.ACTION_SCREEN_ON.equals(act)
                        || android.content.Intent.ACTION_USER_PRESENT.equals(act)) {
                    // 15.55: dual-display thrash can leave main ON-but-black; floor
                    // only when already interactive — never force-wakeup / fight sleep.
                    try {
                        com.titanus2.controls.subdisplay.SubDisplayPower.healMainGlass(
                            self, "a11y-" + act);
                    } catch (Exception ignored) {}
                    // 12.49/12.53: Key a11y P0 — sleep/doze can leave service listed-but-dead;
                    // force full B1/taskbar/pad belt (not throttled 30s ensure).
                    try { AccessServiceHelper.forceUnlockBelt(self); } catch (Exception ignored) {}
                    try { TaskbarPin.pinOff(self); } catch (Exception ignored) {}
                    try { TypingCursorLock.clear(self); } catch (Exception ignored) {}
                    try { HostLayoutController.healStaleHidPlane(self); } catch (Exception ignored) {}
                    // 12.45: screen-off interrupted specials leave layoutHeldScans
                    // swallowing letters after unlock — free ownership map only
                    // (do not forceOff sticky; user may want specials toggle on).
                    try { clearLayoutKeyOwnership(); } catch (Exception ignored) {}
                    // 11.67: unlock heal matches boot/rebind belt (QS/setup/IME)
                    try { SetupWizardHeal.heal(self); } catch (Exception ignored) {}
                    try { PadQsDefaults.ensureDefaultTile(self); } catch (Exception ignored) {}
                    try { ImeHwPrefs.applyStored(self); } catch (Exception ignored) {}
                    try {
                        // 13.51: exclusive HID owns touchpadd — do not restart/kill
                        if (!HostLayoutController.isHidExclusiveLiveFast(self)) {
                            String pm = PadModeController.getMode(self);
                            if (PadModeController.MOUSE.equals(pm)
                                    || PadModeController.TRACKPAD.equals(pm)) {
                                PadModeController.ensureTouchpaddProcess();
                            } else {
                                PadModeController.stopTouchpaddProcess();
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(android.content.Intent.ACTION_SCREEN_OFF);
        f.addAction(android.content.Intent.ACTION_SCREEN_ON);
        f.addAction(android.content.Intent.ACTION_USER_PRESENT);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenBridge, f, android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenBridge, f);
            }
        } catch (Exception e) {
            screenBridge = null;
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event == null) return false;
        // Ignore synthetic / injected events so layout inject cannot re-enter
        // (that caused endless character flood when Specials was on).
        int flags = event.getFlags();
        // FLAG_INJECTED is @hide (0x01000000) but set by our inject helpers.
        if ((flags & 0x01000000) != 0) return false;
        if (event.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD) return false;
        if ((flags & KeyEvent.FLAG_FROM_SYSTEM) == 0
                && event.getDeviceId() <= 0) {
            return false;
        }
        if ((flags & KeyEvent.FLAG_CANCELED) != 0) return false;

        // FB-HID-1: exclusive grab re-injects Back/Home/Recents via
        // titan2-phone-nav uinput. Never remap/swallow those — pass to OS chrome.
        if (isPhoneNavUinputDevice(event)) {
            return false;
        }

        // 15.61: NEVER filter POWER / WAKE — sleep/wake is PhoneWindowManager only.
        // Swallowing here = single press ignored, double-press still hits camera gesture.
        int earlyCode = event.getKeyCode();
        if (earlyCode == KeyEvent.KEYCODE_POWER
                || earlyCode == KeyEvent.KEYCODE_WAKEUP
                || earlyCode == KeyEvent.KEYCODE_SLEEP
                || earlyCode == KeyEvent.KEYCODE_SOFT_SLEEP) {
            return false;
        }

        AgentBridge.bumpKeyActivity(this);

        int rawScan = event.getScanCode();
        int scan = KeyMapPrefs.canonicalizeScan(rawScan);
        if (scan <= 0) scan = rawScan;
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        // Terminal / SSH: bare Shift must not act as Caps Lock.
        // Track physical Shift; release sticky; clear CAPS if short-tap toggled it.
        trackTerminalShift(event, keyCode, action);

        // 12.62: heartbeat a11y_live so pad-agent never treats Bound service as dead
        // (stale tmp=0 after agent boot seed / clear-token merge). Throttled 5s.
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            long nowLive = android.os.SystemClock.elapsedRealtime();
            if (nowLive - lastA11yLiveStamp > 5_000L) {
                lastA11yLiveStamp = nowLive;
                try { AgentBridge.put(this, AgentBridge.A11Y_LIVE, "1"); } catch (Exception ignored) {}
            }
        }

        lastSeen = "scan=" + scan + " raw=" + rawScan + " keyCode=" + keyCode
            + (action == KeyEvent.ACTION_DOWN ? " DOWN" : " UP")
            + " rep=" + event.getRepeatCount();

        // KL maps Titan Home/Recents (scan 580) to F24 so PWM never sees
        // APP_SWITCH hold-preview. Controls still owns the slot — fold F24
        // (or leftover APP_SWITCH) back onto scan 580. Do not swallow.
        if (!KeyMapPrefs.isRecentsScan(scan) && !KeyMapPrefs.isRecentsScan(rawScan)
                && isTitanKeyDevice(event)
                && !isSideInputDevice(event)
                && (keyCode == KeyEvent.KEYCODE_F24
                    || keyCode == KeyEvent.KEYCODE_APP_SWITCH)) {
            scan = KeyMapPrefs.SCAN_APP_SWITCH;
            if (rawScan <= 0) rawScan = scan;
        }

        // ---- Keys settings open / capture: identify only, never act ----
        if (KeyCapture.blockActions()) {
            clearPendingRemaps();
            HostLayoutController.endHoldAll();
            layoutHeldScans.clear();
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                KeyCapture.offer(rawScan > 0 ? rawScan : scan, keyCode);
            }
            return true;
        }

        // 13.15 COEXIST: Sym inject MUST run before idle OS-letter fast path.
        // 13.14 returned letters to OS first → specialsInjectHeld never saw
        // the letter (or Sym arm lost the race) and inject "stopped working"
        // whenever lag fixes were on. Order: inject → layout → idle OS.
        if (trySpecialsInjectMethod(event, scan, rawScan, keyCode, action)) {
            return true;
        }

        // 13.07–13.14: idle phone letters — dual residual out; hold-repeat in;
        // fast path returns to OS without remap/tree (typing lag). Never when
        // Sym inject is held (handled above).
        final boolean idleOsLetter = keyCode > 0
                && isIdlePhoneTypingLetter(keyCode)
                && isPhysicalLayoutKeyEvent(event, rawScan, scan)
                && !specialsInjectStillHeld()
                && !HostLayoutController.isHoldActive()
                && !HostLayoutController.isHidExclusiveLiveFast(this)
                && !KeyMapPrefs.isManagedScan(scan)
                && !KeyMapPrefs.isManagedScan(rawScan)
                && !KeyMapPrefs.isSideScan(scan)
                && !KeyMapPrefs.isSideScan(rawScan)
                && !isSideInputDevice(event)
                && !magicHeld;
        if (idleOsLetter) {
            final int letterScan = scan > 0 ? scan : rawScan;
            if (action == KeyEvent.ACTION_UP) {
                idleLetterKeysDown.remove(keyCode);
                if (letterScan > 0) idleLetterScansDown.remove(letterScan);
                // Keep key identity + time so a residual DOWN after this UP is killed.
                lastIdleLetterKeyCode = keyCode;
                lastIdleLetterUpAt = android.os.SystemClock.uptimeMillis();
                return false;
            }
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() > 0) {
                    idleLetterKeysDown.add(keyCode);
                    if (letterScan > 0) idleLetterScansDown.add(letterScan);
                    // 13.66/13.67: AOSP CharacterPicker on rep>0 for PICKER_SETS.
                    // Swallow + plain re-insert when letter variations off.
                    if (LetterVariationsPrefs.suppressCharacterPickerOnRepeat(this)
                            && LetterVariationsPrefs.isAospPickerChar(
                                idleLetterUnicode(event, keyCode))) {
                        emitIdleLetterRepeatPlain(event, keyCode);
                        return true;
                    }
                    return false; // non-picker: native hold-repeat
                }
                // Dual residual: second DOWN before UP (hold-set / dual path)
                if (idleLetterKeysDown.contains(keyCode)) {
                    return true;
                }
                if (letterScan > 0 && idleLetterScansDown.contains(letterScan)) {
                    return true;
                }
                final long nowDeb = android.os.SystemClock.uptimeMillis();
                // REG-L / Controls 15.32 + 15.74: kill EventHub DOWN-UP-DOWN multi-char.
                // 15.32 used IDLE_LETTER_DEBOUNCE_MS=120 which also ate intentional
                // double letters ("ll","ss") when typing fast (2899db25 removed it).
                // Product SoT: KeyInputTiming.dualResidualDebounceMs() ≈ 20–40ms —
                // short enough for human re-tap, long enough for OEM dual residual.
                long dualMs = IDLE_LETTER_DEBOUNCE_MS;
                try {
                    dualMs = com.titanus2.api.KeyInputTiming.dualResidualDebounceMs(this);
                } catch (Throwable ignored) {}
                if (dualMs < 20L) dualMs = 20L;
                if (dualMs > 50L) dualMs = 50L;
                if (keyCode == lastIdleLetterKeyCode
                        && lastIdleLetterUpAt > 0
                        && (nowDeb - lastIdleLetterUpAt) < dualMs) {
                    return true; // post-UP dual residual (multi-char)
                }
                // True redelivery: rep=0 DOWN while previous DOWN never got UP
                if (keyCode == lastIdleLetterKeyCode
                        && lastIdleLetterDownAt > 0
                        && idleLetterKeysDown.isEmpty()
                        && (nowDeb - lastIdleLetterDownAt) < dualMs
                        && lastIdleLetterUpAt < lastIdleLetterDownAt) {
                    return true;
                }
                idleLetterKeysDown.add(keyCode);
                if (letterScan > 0) idleLetterScansDown.add(letterScan);
                lastIdleLetterKeyCode = keyCode;
                lastIdleLetterDownAt = nowDeb;
                try { TypingCursorLock.pulse(this); } catch (Exception ignored) {}
                return false; // first DOWN → OS once
            }
            return false;
        }
        // Non-letter / layout / sides: selection clear throttled only.
        // 13.24: NEVER collapse on DEL/FORWARD_DEL — Ctrl+A then Delete must
        // erase the whole selection (launcher Search…). Collapsing first left
        // only the last character deleted.
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && !HostLayoutController.isModifierKey(keyCode)
                && keyCode != KeyEvent.KEYCODE_DEL
                && keyCode != KeyEvent.KEYCODE_FORWARD_DEL) {
            if (!HostLayoutController.isHoldActive()) {
                try { TypingCursorLock.pulse(this); } catch (Exception ignored) {}
            }
            if (!event.isShiftPressed() && !event.isCtrlPressed()
                    && !event.isMetaPressed()) {
                try { TypingSelectionClear.collapseIfRangeSelectedThrottled(this); }
                catch (Exception ignored) {}
            }
        }

        // Side buttons (gpio_key-func / ff_key / mtk-kpd): always swallow so
        // system never gets Home/Recents/CAMERA. pad-agent getevent owns the
        // screen-off KEY_FIRE path; when agent/Magisk is missing (post-wipe),
        // a11y must still run short/long/double remaps for screen-on use.
        // Factory: bottom long=specials hold, double=specials toggle; top = arrows.
        boolean sideKey = KeyMapPrefs.isSideScan(scan) || KeyMapPrefs.isSideScan(rawScan)
            || isSideInputDevice(event);
        if (sideKey) {
            scan = KeyMapPrefs.canonicalizeScan(
                KeyMapPrefs.isSideScan(scan) ? scan : rawScan);
            // 13.09: if scan still unknown but device is side rail, keep side ownership
            if (scan <= 0 || !KeyMapPrefs.isSideScan(scan)) {
                if (KeyMapPrefs.isSideScan(rawScan)) {
                    scan = KeyMapPrefs.canonicalizeScan(rawScan);
                }
            }
        }
        // B1: stock mtk-kpd maps 249/250 → KEYCODE_CAMERA; bare CAMERA (no side
        // scan) is never a product key on Titan — only mis-mapped sides.
        if (keyCode == KeyEvent.KEYCODE_CAMERA && !sideKey) {
            return true;
        }
        // 13.09: side rail device reported HOME/APP_SWITCH/MENU but scan lost —
        // swallow chrome only (do not ban TitanKey Recents/Back managed keys).
        if (sideKey && isBareSystemChromeKey(keyCode)
                && (scan <= 0 || !KeyMapPrefs.isSideScan(scan))) {
            return true;
        }

        // Layout letter remap: ONLY while side-hold is live OR exclusive HID.
        // OEM phone typing = hold Sym/Fn (ALT_RIGHT) + KCM — never a11y inject.
        // Sticky Specials with a11y inject on phone dual-fired with OS keys →
        // 5× glyphs per press (user report). 12.20 restores OEM split.
        // 13.14: Fast exclusive check on key path (no dumpsys)
        boolean layoutRemap = HostLayoutController.isHoldActive()
            || HostLayoutController.isHidExclusiveLiveFast(this);
        if (layoutRemap
                && HostLayoutController.isActive(this)
                && !KeyMapPrefs.isManagedScan(scan)
                && !KeyCapture.isUiOpen()
                && !HostLayoutController.isModifierKey(keyCode)) {
            // 12.72: pad-agent `input keyevent` (inject-fail fallback) re-enters
            // a11y with scan=0 / virtual source → second emitSpecials (Termux
            // multi-glyph). Only physical TitanKey/gpio events own layout remap.
            if (!isPhysicalLayoutKeyEvent(event, rawScan, scan)) {
                return true; // swallow re-entry; do not re-emit
            }
            if (action == KeyEvent.ACTION_UP) {
                if (layoutHeldScans.containsKey(scan)) {
                    layoutHeldScans.remove(scan);
                    return true;
                }
            } else if (action == KeyEvent.ACTION_DOWN) {
                // 12.77: backspace/enter hold must re-emit on OS autorepeat
                if (event.getRepeatCount() > 0) {
                    if (HostLayoutController.isHostEditKey(keyCode)
                            && HostLayoutController.wouldHandle(this, keyCode)) {
                        HostLayoutController.handleKey(this, keyCode, true,
                            event.getMetaState());
                    }
                    return true;
                }
                if (HostLayoutController.wouldHandle(this, keyCode)) {
                    int meta = event.getMetaState();
                    // Always swallow mapped layout keys (no letter-then-arrow).
                    HostLayoutController.handleKey(this, keyCode, true, meta);
                    layoutHeldScans.put(scan, keyCode);
                    return true;
                }
            }
        } else if (action == KeyEvent.ACTION_UP) {
            layoutHeldScans.remove(scan);
        }

        // Long-hold letter → specials: REMOVED (12.04). Use Sym/Alt inject or KCM.

        // kcm method: specials key → ALT_RIGHT + TitanKey.kcm (do not consume).

        MagicKeyPrefs magic = new MagicKeyPrefs(this);
        KeyMapProfiles appProfiles = new KeyMapProfiles(this);
        int magicScan = appProfiles.resolveMagicScan(lastPkg, magic.getScan());

        // --- Magic key hold (chords + optional layers; per-app hold/chords) ---
        if (magicScan > 0 && scan == magicScan) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() > 0) return true;
                magicHeld = true;
                magicUsedChord = false;
                magicChordKeys.clear();
                return true;
            }
            if (action == KeyEvent.ACTION_UP) {
                boolean used = magicUsedChord;
                magicHeld = false;
                magicChordKeys.clear();
                magicUsedChord = false;
                if (!used) fireMagicTap(scan);
                return true;
            }
            return true;
        }

        if (magicHeld && magicScan > 0) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                applyMagic(magic, appProfiles, scan, keyCode);
                magicUsedChord = true;
                magicChordKeys.add(scan);
                return true;
            }
            if (action == KeyEvent.ACTION_UP) {
                if (magicChordKeys.contains(scan) || magicUsedChord) return true;
            }
        }

        // --- Standard remaps ---
        KeyMapPrefs prefs = new KeyMapPrefs(this);
        if (!prefs.isEnabled()) return sideKey; // sides: still swallow
        if (!KeyMapPrefs.isManagedScan(scan)) return sideKey;
        if (magicScan > 0 && scan == magicScan) return false;
        if (skipFnWhenCtrlMode(prefs, scan)) return false;
        // Sym/specials owner: kcm needs raw ALT_RIGHT; inject mode already handled.
        if (skipCharModKey(prefs, scan)) {
            trackSpecialsModParallel(prefs, scan, action, event.getRepeatCount());
            return false;
        }

        // 12.77: stock DEL/BACKSPACE autorepeat must not be swallowed when
        // managed slot is none/default — OS holds spam the delete.
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0
                && (keyCode == KeyEvent.KEYCODE_DEL
                    || keyCode == KeyEvent.KEYCODE_FORWARD_DEL
                    || keyCode == KeyEvent.KEYCODE_SPACE
                    || keyCode == KeyEvent.KEYCODE_ENTER)) {
            if (allNone(prefs, scan) || allDefault(prefs, scan)) {
                return false; // pass autorepeat to InputReader
            }
        }

        TempKeyMapStack stack = new TempKeyMapStack(this);
        // HID silence (and any temp layer) that sets slots to none must still
        // swallow the key. Side buttons are APP_SWITCH in keylayout — if we
        // pass through, Recents fires and "redirect" looks broken.
        // Exception: Sym/Alt specials + Fn layout must always reach keylayout
        // for OS KCM specials (Sym+letter → !@#). HID exclusive never hits
        // a11y anyway; swallowing here only broke phone OS typing.
        boolean tempOwns = stack.tempDefinesScan(scan);
        if (allNone(prefs, scan)) {
            if (sideKey) return true; // B1: never leak side to Camera/Home
            if (tempOwns && !KeyMapPrefs.isCharModScan(this, scan)
                    && !KeyMapPrefs.isSymScan(scan)
                    && !KeyMapPrefs.isAltScan(scan)
                    && !KeyMapPrefs.isFnScan(scan)) {
                return true; // explicit silence → consume, no action
            }
            return false; // pass to system / keylayout
        }
        // System default for all presses → do not re-implement stock Back/Recents
        // (that double-fired with keylayout APP_SWITCH).
        // Sides: factory uses hold/toggle (not all-default); still never leak.
        if (allDefault(prefs, scan)) return sideKey;

        if (action == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() > 0) return true;
            Runnable pend = shortPending.remove(scan);
            if (pend != null) h.removeCallbacks(pend);

            long now = SystemClock.uptimeMillis();
            Long lastUp = lastShortUp.get(scan);
            long dblWin = sideKey ? SIDE_DOUBLE_MS : DOUBLE_MS;
            if (lastUp != null && now - lastUp < dblWin) {
                // Double-tap: cancel any layout hold and fire double action
                lastShortUp.remove(scan);
                HostLayoutController.endHold(scan);
                KeyMapPrefs.Slot dbl = KeyMapPrefs.slotByScan(scan, KeyMapPrefs.Press.DOUBLE);
                if (dbl != null) {
                    String act = sideSafeAction(scan, effectiveAction(prefs, dbl.id));
                    if (act != null && !KeyMapPrefs.ACT_DEFAULT.equals(act)
                            && !KeyMapPrefs.ACT_NONE.equals(act)) {
                        KeyActions.run(this, act);
                    }
                }
                downAt.remove(scan);
                longFired.put(scan, true);
                return true;
            }

            downAt.put(scan, now);
            longFired.put(scan, false);
            Runnable old = longTasks.remove(scan);
            if (old != null) h.removeCallbacks(old);
            final int sc = scan;
            KeyMapPrefs.Slot longSlot = KeyMapPrefs.slotByScan(sc, KeyMapPrefs.Press.LONG);
            final String longActRaw = longSlot != null
                ? effectiveAction(prefs, longSlot.id) : KeyMapPrefs.ACT_DEFAULT;
            // B1: drop home/recents on side long (same belt as KeyFireReceiver 11.33)
            final String longAct = sideSafeAction(sc, longActRaw);
            final boolean layoutHold = longAct != null
                && HostLayoutController.isLayoutHoldAction(longAct);
            // Layout hold = true modifier: arm fast so hold+type works; do not
            // wait full LONG_MS (that conflicted with typing and short fire).
            final long delay = layoutHold ? LAYOUT_HOLD_MS : LONG_MS;
            Runnable task = () -> {
                if (!Boolean.FALSE.equals(longFired.get(sc))) return;
                if (!downAt.containsKey(sc)) return;
                if (KeyCapture.blockActions()) return;
                if (longAct == null || KeyMapPrefs.ACT_DEFAULT.equals(longAct)
                        || KeyMapPrefs.ACT_NONE.equals(longAct)) return;
                longFired.put(sc, true);
                if (layoutHold) {
                    HostLayoutController.beginHold(TrackpadAccessService.this, sc, longAct);
                } else {
                    KeyActions.run(TrackpadAccessService.this, longAct);
                }
            };
            longTasks.put(sc, task);
            h.postDelayed(task, delay);
            return true;
        }

        if (action == KeyEvent.ACTION_UP) {
            Runnable old = longTasks.remove(scan);
            if (old != null) h.removeCallbacks(old);
            downAt.remove(scan);
            boolean wasLong = Boolean.TRUE.equals(longFired.remove(scan));
            // End layout modifier on release (true hold key)
            HostLayoutController.endHold(scan);
            if (!wasLong) {
                final int sc = scan;
                final KeyMapPrefs prefs2 = prefs;
                Runnable fireShort = () -> {
                    shortPending.remove(sc);
                    lastShortUp.remove(sc);
                    if (KeyCapture.blockActions()) return;
                    // If layout hold is still active from another key, skip short
                    KeyMapPrefs.Slot shortSlot = KeyMapPrefs.slotByScan(sc, KeyMapPrefs.Press.SHORT);
                    if (shortSlot == null) return;
                    String sa = sideSafeAction(sc, effectiveAction(prefs2, shortSlot.id));
                    // Product: back_short factory is ACT_BACK. If still ACT_DEFAULT
                    // (legacy prefs), use stockShortFallback so Back/Recents fire.
                    if (sa != null && KeyMapPrefs.ACT_DEFAULT.equals(sa)) {
                        sa = stockShortFallback(sc);
                    }
                    if (sa != null && !KeyMapPrefs.ACT_NONE.equals(sa)
                            && !KeyMapPrefs.ACT_DEFAULT.equals(sa)) {
                        KeyActions.run(TrackpadAccessService.this, sa);
                    }
                };
                shortPending.put(sc, fireShort);
                lastShortUp.put(sc, SystemClock.uptimeMillis());
                KeyMapPrefs.Slot dbl = KeyMapPrefs.slotByScan(sc, KeyMapPrefs.Press.DOUBLE);
                String da = dbl != null ? effectiveAction(prefs, dbl.id) : KeyMapPrefs.ACT_DEFAULT;
                // Layout-hold long: never fire short on release after arm window
                // (wasLong covers after arm). Quick tap still short.
                if (KeyMapPrefs.ACT_DEFAULT.equals(da) || KeyMapPrefs.ACT_NONE.equals(da)) {
                    fireShort.run();
                } else {
                    long wait = KeyMapPrefs.isSideScan(sc) ? SIDE_DOUBLE_MS : DOUBLE_MS;
                    h.postDelayed(fireShort, wait);
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Letter long-press → specials without modifier.
     * Short press passes through natively (no typing lag). On long threshold:
     * DEL the just-typed letter, then emit the specials glyph as host US chord
     * + phone inject.
     */
    private final Map<Integer, Runnable> letterLongTasks = new HashMap<>();
    private final Map<Integer, Boolean> letterLongFired = new HashMap<>();

        /** Purged 12.04 — long-hold letter→specials removed (typing lag / stuck-key feel). */
    private boolean handleLetterLongSpecials(int keyCode, int action, int repeat) {
        return false;
    }


    private boolean allNone(KeyMapPrefs prefs, int scan) {
        int c = KeyMapPrefs.canonicalizeScan(scan);
        for (KeyMapPrefs.Press pr : KeyMapPrefs.Press.values()) {
            KeyMapPrefs.Slot sl = KeyMapPrefs.slotByScan(c, pr);
            if (sl == null) continue;
            if (!KeyMapPrefs.ACT_NONE.equals(effectiveAction(prefs, sl.id))) return false;
        }
        return true;
    }

    private static boolean hasStockFallback(int scan) {
        String f = stockShortFallback(scan);
        return f != null && !KeyMapPrefs.ACT_NONE.equals(f) && !KeyMapPrefs.ACT_DEFAULT.equals(f);
    }

    private void fireMagicTap(int scan) {
        if (KeyCapture.blockActions()) return;
        KeyMapPrefs prefs = new KeyMapPrefs(this);
        if (!prefs.isEnabled()) return;
        if (!KeyMapPrefs.isManagedScan(scan)) return;
        if (skipFnWhenCtrlMode(prefs, scan)) return;
        if (skipCharModKey(prefs, scan)) return;
        KeyMapPrefs.Slot shortSlot = KeyMapPrefs.slotByScan(scan, KeyMapPrefs.Press.SHORT);
        if (shortSlot == null) return;
        String sa = sideSafeAction(scan, effectiveAction(prefs, shortSlot.id));
        if (sa != null && KeyMapPrefs.ACT_DEFAULT.equals(sa)) sa = stockShortFallback(scan);
        if (sa != null && !KeyMapPrefs.ACT_NONE.equals(sa)
                && !KeyMapPrefs.ACT_DEFAULT.equals(sa)) {
            KeyActions.run(this, sa);
        }
    }

    /**
     * P0 B1: side scans never run system chrome (home/recents/camera) via a11y —
     * same rule as {@link KeyFireReceiver#sanitizeSideAction} for pad-agent path.
     */
    private static String sideSafeAction(int scan, String act) {
        if (act == null) return null;
        if (!KeyMapPrefs.isSideScan(scan)) return act;
        return KeyFireReceiver.sanitizeSideAction(scan, act);
    }

    private boolean applyMagic(MagicKeyPrefs magic, KeyMapProfiles profiles,
                               int scan, int keyCode) {
        if (KeyCapture.blockActions()) return false;
        // Exclusive HID: use USB HID host profile for magic chords (same SoT
        // as Keys → app profiles → "USB HID host").
        String profPkg = lastPkg;
        try {
            if (HostLayoutController.isHidExclusiveLiveFast(this)) {
                profPkg = com.titanus2.api.Titan2ApiContract.HID_HOST_PKG;
            }
        } catch (Exception ignored) {}
        String mode = magic.resolveMode(profPkg);
        if (MagicKeyPrefs.MODE_CHORDS.equals(mode) || mode == null) {
            // Per-app chord beats global; ACT_NONE silences a global chord here.
            String act = null;
            if (profiles != null && profPkg != null) {
                act = profiles.getChord(profPkg, scan, keyCode);
            }
            if (act == null) act = magic.getChord(scan, keyCode);
            if (act == null || KeyMapPrefs.ACT_NONE.equals(act)
                    || KeyMapPrefs.ACT_DEFAULT.equals(act)) {
                return false;
            }
            KeyActions.run(this, act);
            return true;
        }
        MagicLayers.Out out = MagicLayers.map(mode, keyCode);
        if (out == null) return false;
        KeyActions.injectLayerOut(this, out);
        return true;
    }



    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        // QA: freeze pad/mouse while typing (editable focus / text change)
        try { TypingCursorLock.onA11yEvent(this, event); } catch (Exception ignored) {}
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();
        if (pkg.equals(lastPkg)) return;
        lastPkg = pkg;
        try { HostLayoutController.onForegroundChanged(this, pkg); } catch (Exception ignored) {}
        // Dual-plane: phone SW for Launcher DeviceProfile; tablet for Settings/OS
        try { DisplayPlane.onForeground(this, pkg); } catch (Exception ignored) {}
        TrackpadPrefs prefs = new TrackpadPrefs(this);
        if (!TrackpadPrefs.MODE_WHITELIST.equals(prefs.getMode())) return;
        TrackpadPolicy.apply(this, pkg);
    }

    /**
     * Short-press when the slot still says {@link KeyMapPrefs#ACT_DEFAULT}.
     * Back / Recents only. Fn is layout (Ctrl) or user shortcut — no stock Home.
     */
    private static String stockShortFallback(int scan) {
        switch (KeyMapPrefs.canonicalizeScan(scan)) {
            case KeyMapPrefs.SCAN_BACK: return KeyMapPrefs.ACT_BACK;
            case KeyMapPrefs.SCAN_APP_SWITCH: return KeyMapPrefs.ACT_HOME;
            default: return KeyMapPrefs.ACT_NONE;
        }
    }

    /**
     * 13.15: under inject method, Sym is BUTTON_2 (not KEYCODE_SYM). Arm hold
     * from keyCode when specials owner is Sym/Alt so lag-fast-path and inject
     * coexist even if scan is 0/flaky for one event.
     * <p>
     * 13.55: also KEYCODE_ALT_RIGHT — inject KL heal maps Sym→BUTTON_2, but
     * unhealed / system KL residual leaves 222/253 as ALT_RIGHT; free Alt is
     * ALT_LEFT (scan 100). Without ALT_RIGHT arm, flaky scan=0 events never
     * start specialsInjectHeld → silent inject.
     * Free Alt scan (100) must never arm when specials owner is Sym.
     */
    private static boolean isInjectSpecialsModKeyCode(int keyCode, int specialsScan,
            int can, int canRaw) {
        if (keyCode <= 0 || specialsScan <= 0) return false;
        if (KeyMapPrefs.isSymScan(specialsScan)
                || KeyMapPrefs.canonicalizeScan(specialsScan) == KeyMapPrefs.SCAN_SYM
                || KeyMapPrefs.canonicalizeScan(specialsScan) == KeyMapPrefs.SCAN_SYM_ALT) {
            // Free Alt (scan 100) stays menus
            if (KeyMapPrefs.isAltScan(can) || KeyMapPrefs.isAltScan(canRaw)) {
                return false;
            }
            // 13.62: BUTTON_2/SYM only for flaky scan=0. ALT_RIGHT only when scan
            // is specials family (222/253) — bare ALT_RIGHT+scan0 false-armed Sym
            // hold during normal typing → random specials / caret thrash.
            if (keyCode == KeyEvent.KEYCODE_BUTTON_2
                    || keyCode == KeyEvent.KEYCODE_BUTTON_3
                    || keyCode == KeyEvent.KEYCODE_SYM) {
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
                return KeyMapPrefs.matchesSpecialsScan(can, specialsScan)
                    || KeyMapPrefs.matchesSpecialsScan(canRaw, specialsScan);
            }
            return false;
        }
        if (KeyMapPrefs.isAltScan(specialsScan)) {
            return keyCode == KeyEvent.KEYCODE_ALT_LEFT
                || keyCode == KeyEvent.KEYCODE_ALT_RIGHT
                || keyCode == KeyEvent.KEYCODE_SYM;
        }
        return false;
    }

    /**
     * 13.23: EventHub still maps specials owner → BUTTON_2 while UI method is
     * kcm (soft uevent never reopened device). True KCM needs ALT_RIGHT meta.
     */
    private boolean isStaleKcmButton2Specials(KeyEvent event, int scan, int rawScan,
            int keyCode) {
        if (keyCode != KeyEvent.KEYCODE_BUTTON_2
                && keyCode != KeyEvent.KEYCODE_BUTTON_3) {
            // Letter while Sym held as BUTTON_2: specialsInjectHeld still tracks
            // via inject path once armed — only arm path needs this probe.
            if (!specialsInjectStillHeld()) return false;
            // while held, allow letter inject even if keyCode is a letter
            if (keyCode > 0 && isIdlePhoneTypingLetter(keyCode)) return true;
            return false;
        }
        int spScan = KeyMapPrefs.resolveSpecialsScan(this);
        if (spScan <= 0) return false;
        int can = KeyMapPrefs.canonicalizeScan(scan);
        int canRaw = KeyMapPrefs.canonicalizeScan(rawScan);
        return KeyMapPrefs.matchesSpecialsScan(can, spScan)
            || KeyMapPrefs.matchesSpecialsScan(canRaw, spScan)
            || isInjectSpecialsModKeyCode(keyCode, spScan, can, canRaw);
    }

    /**
     * 13.09/13.10 B1: side rail InputDevice names only (scan may be 0 after thrash).
     * Never mtk-kpd — that node also carries Volume/Power; treating it as side
     * swallowed chrome keys and contributed dual-path typing chaos.
     */
    private static boolean isSideInputDevice(KeyEvent event) {
        if (event == null) return false;
        try {
            android.view.InputDevice dev = event.getDevice();
            if (dev == null) return false;
            String n = dev.getName();
            if (n == null) return false;
            String low = n.toLowerCase(java.util.Locale.US);
            return low.contains("gpio_key-func")
                || low.contains("gpio-key-func")
                || low.equals("ff_key")
                || low.startsWith("ff_key");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isTitanKeyDevice(KeyEvent event) {
        if (event == null) return false;
        try {
            android.view.InputDevice dev = event.getDevice();
            if (dev == null) return false;
            String n = dev.getName();
            if (n == null) return false;
            return "TitanKey".equals(n)
                || n.contains("TitanKey")
                || n.contains("Vendor_2533");
        } catch (Exception e) {
            return false;
        }
    }

    /** Chrome keys that must never come from Titan side rail / mis-map. */
    private static boolean isBareSystemChromeKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CAMERA:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_ASSIST:
                return true;
            default:
                return false;
        }
    }

    /**
     * Unicode of an idle letter key with Sym/Alt stripped. Gates AOSP PICKER_SETS.
     */
    private static char idleLetterUnicode(KeyEvent event, int keyCode) {
        if (event == null) return 0;
        try {
            int meta = KeyActions.usefulMeta(event.getMetaState());
            meta &= ~(KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON
                | KeyEvent.META_ALT_RIGHT_ON | KeyEvent.META_SYM_ON);
            int uc = event.getUnicodeChar(meta);
            if (uc == 0 && keyCode > 0) {
                uc = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                    .get(keyCode, meta);
            }
            if (uc == 0) return 0;
            if ((uc & KeyCharacterMap.COMBINING_ACCENT) != 0) return 0;
            return (char) (uc & 0xFFFF);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 13.66/13.67: plain char for OS letter autorepeat when Letter variations is
     * off and key is AOSP PICKER_SETS. rep=0 inject so QwertyKeyListener never
     * opens CharacterPicker; FLAG_INJECTED avoids onKeyEvent re-entry.
     */
    private void emitIdleLetterRepeatPlain(KeyEvent event, int keyCode) {
        if (event == null || keyCode <= 0) return;
        try {
            int meta = KeyActions.usefulMeta(event.getMetaState());
            meta &= ~(KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON
                | KeyEvent.META_ALT_RIGHT_ON | KeyEvent.META_SYM_ON);
            boolean ok;
            if (meta != 0) {
                ok = KeyActions.injectKeyChordPublic(this, meta, keyCode);
            } else {
                ok = KeyActions.injectKeyCode(this, keyCode);
            }
            if (ok) {
                try { TypingCursorLock.pulse(this); } catch (Exception ignored) {}
                return;
            }
            char c = idleLetterUnicode(event, keyCode);
            if (c == 0) return;
            String s = String.valueOf(c);
            if (KeyActions.injectCharOne(this, s, true)
                    || KeyActions.injectCharOne(this, s, false)) {
                try { TypingCursorLock.pulse(this); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    /**
     * Global HW Shift (T-013 Atlas): momentary hold; Caps only on double bare-tap.
     * Runs for all packages so SystemUI / Atlas / Termux share one policy.
     * <p>
     * Never inject CAPS unless: (a) clearing residual latched meta, or
     * (b) intentional double-tap product Caps. CAPS is a toggle.
     */
    private void trackTerminalShift(KeyEvent event, int keyCode, int action) {
        if (event != null) {
            lastKnownCapsOn = (event.getMetaState() & KeyEvent.META_CAPS_LOCK_ON) != 0;
            // Sync product latch if user/OS cleared CAPS externally.
            if (!lastKnownCapsOn) {
                productCapsLatched = false;
            }
        }
        boolean isShift = keyCode == KeyEvent.KEYCODE_SHIFT_LEFT
                || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT;
        boolean isCaps = keyCode == KeyEvent.KEYCODE_CAPS_LOCK;
        if (isCaps && action == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) {
            lastKnownCapsOn = (event.getMetaState() & KeyEvent.META_CAPS_LOCK_ON) != 0;
            // Hardware CAPS key (rare on TitanKey): treat as product toggle edge.
            productCapsLatched = lastKnownCapsOn;
            return;
        }
        if (isShift) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                hwShiftDown = true;
                hwShiftDownAt = android.os.SystemClock.uptimeMillis();
                hwShiftSawLetter = false;
                h.removeCallbacks(hwShiftStickyRelease);
                // Lost UP safety (stuck shift feels like caps) — do not arm Caps.
                h.postDelayed(hwShiftStickyRelease, 550L);
            } else if (action == KeyEvent.ACTION_UP) {
                h.removeCallbacks(hwShiftStickyRelease);
                long now = android.os.SystemClock.uptimeMillis();
                long held = now - hwShiftDownAt;
                boolean bareTap = hwShiftDown && !hwShiftSawLetter && held < 450L;
                boolean hadCaps = (event.getMetaState() & KeyEvent.META_CAPS_LOCK_ON) != 0;
                lastKnownCapsOn = hadCaps;
                hwShiftDown = false;
                if (bareTap) {
                    // T-013: double bare Shift within window → Caps toggle.
                    if (lastBareShiftUpAt > 0L
                            && (now - lastBareShiftUpAt) <= DOUBLE_SHIFT_CAPS_MS) {
                        lastBareShiftUpAt = 0L;
                        h.post(this::toggleProductCaps);
                    } else {
                        lastBareShiftUpAt = now;
                        // Single bare tap: never sticky. Clear accidental CAPS
                        // unless product double-tap already latched it.
                        if (hadCaps && !productCapsLatched) {
                            h.postDelayed(this::clearCapsIfOn, 30L);
                        }
                    }
                } else {
                    lastBareShiftUpAt = 0L;
                    // Chord release with residual CAPS noise only.
                    if (hadCaps && !productCapsLatched) {
                        h.postDelayed(this::clearCapsIfOn, 30L);
                    }
                }
            }
            return;
        }
        if (hwShiftDown && action == KeyEvent.ACTION_DOWN
                && isIdlePhoneTypingLetter(keyCode)) {
            hwShiftSawLetter = true;
            lastBareShiftUpAt = 0L; // chord cancels double-tap arm
            h.removeCallbacks(hwShiftStickyRelease);
            h.postDelayed(hwShiftStickyRelease, 550L);
        }
        // Residual CAPS without product latch → clear (post-boot sticky).
        // Intentional double-tap Caps (productCapsLatched) must stick.
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && isIdlePhoneTypingLetter(keyCode)
                && (event.getMetaState() & KeyEvent.META_CAPS_LOCK_ON) != 0
                && !hwShiftDown
                && !productCapsLatched) {
            lastKnownCapsOn = true;
            h.postDelayed(this::clearCapsIfOn, 20L);
        }
    }

    /** Atlas T-013: double bare Shift arms/disarms Caps deliberately. */
    private void toggleProductCaps() {
        try {
            KeyActions.injectKeyCode(this, KeyEvent.KEYCODE_CAPS_LOCK);
            productCapsLatched = !productCapsLatched;
            lastKnownCapsOn = productCapsLatched;
        } catch (Exception ignored) {}
    }

    /**
     * Clear CAPS only when latched and not intentional product Caps.
     * CAPS_LOCK inject is a toggle — calling while off arms permanent uppercase.
     */
    private void clearCapsIfOn() {
        if (productCapsLatched) {
            return;
        }
        if (!lastKnownCapsOn) {
            return;
        }
        try {
            KeyActions.injectKeyCode(this, KeyEvent.KEYCODE_CAPS_LOCK);
            lastKnownCapsOn = false;
        } catch (Exception ignored) {}
    }

    private void injectShiftReleaseAndClearCaps() {
        hwShiftDown = false;
        hwShiftSawLetter = false;
        // Lost Shift UP: clear residual CAPS only; never invent Caps.
        if (!productCapsLatched) {
            clearCapsIfOn();
        }
    }

    /**
     * FB-HID-1: hid_bridge exclusive phone-nav uinput device name.
     * Events from this path are already the product bypass (letters stay grabbed).
     */
    private static boolean isPhoneNavUinputDevice(KeyEvent event) {
        if (event == null) return false;
        try {
            android.view.InputDevice dev = event.getDevice();
            if (dev == null) return false;
            String n = dev.getName();
            if (n == null) return false;
            return n.toLowerCase(java.util.Locale.US).contains("titan2-phone-nav");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Printable HW typing keys that dual-fire as multi-letter (not modifiers).
     */
    private static boolean isIdlePhoneTypingLetter(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) return true;
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) return true;
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_COMMA:
            case KeyEvent.KEYCODE_PERIOD:
            case KeyEvent.KEYCODE_SLASH:
            case KeyEvent.KEYCODE_SEMICOLON:
            case KeyEvent.KEYCODE_APOSTROPHE:
            case KeyEvent.KEYCODE_LEFT_BRACKET:
            case KeyEvent.KEYCODE_RIGHT_BRACKET:
            case KeyEvent.KEYCODE_BACKSLASH:
            case KeyEvent.KEYCODE_GRAVE:
            case KeyEvent.KEYCODE_MINUS:
            case KeyEvent.KEYCODE_EQUALS:
            case KeyEvent.KEYCODE_AT:
            case KeyEvent.KEYCODE_POUND:
            case KeyEvent.KEYCODE_STAR:
            case KeyEvent.KEYCODE_PLUS:
                return true;
            default:
                return false;
        }
    }

    /**
     * Physical TitanKey key for layout remap. Reject shell/agent inject re-entry
     * (scan 0, virtual keyboard) that would re-run emitSpecials → multi-glyph.
     */
    private static boolean isPhysicalLayoutKeyEvent(android.view.KeyEvent event,
            int rawScan, int scan) {
        if (event == null) return false;
        // FLAG_INJECTED already filtered at onKeyEvent entry — belt-and-suspenders.
        if ((event.getFlags() & 0x01000000) != 0) return false;
        if (event.getDeviceId() == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD) {
            return false;
        }
        int sc = scan > 0 ? scan : rawScan;
        // agent/shell `input keyevent` often has scanCode 0 — never layout-emit those
        if (sc <= 0) return false;
        try {
            android.view.InputDevice dev = event.getDevice();
            if (dev != null) {
                String n = dev.getName();
                if (n != null) {
                    String low = n.toLowerCase();
                    if (low.contains("virtual") && !low.contains("titan")) return false;
                }
            }
        } catch (Exception ignored) {}
        return true;
    }

    @Override public void onInterrupt() {
        // 12.40: interrupt must not leave layout-owned scans swallowing keys
        try { layoutHeldScans.clear(); } catch (Exception ignored) {}
        magicHeld = false;
        magicUsedChord = false;
        try { magicChordKeys.clear(); } catch (Exception ignored) {}
        // 12.61: do NOT clear a11y_live here — interrupt is not destroy; clearing
        // made pad-agent KEY_FIRE sides while a11y still ran (dual Specials).
    }

    @Override public void onDestroy() {
        for (Runnable r : postBindHeals) {
            try { h.removeCallbacks(r); } catch (Exception ignored) {}
        }
        postBindHeals.clear();
        try { h.removeCallbacks(a11yLiveHeartbeat); } catch (Exception ignored) {}
        try {
            if (screenBridge != null) {
                unregisterReceiver(screenBridge);
                screenBridge = null;
            }
        } catch (Exception ignored) {}
        // 12.40 Key a11y: drop ownership maps + inject backlog so rebind does not
        // multi-glyph from mid-hold state or leave dead letter swallows.
        try { layoutHeldScans.clear(); } catch (Exception ignored) {}
        magicHeld = false;
        magicUsedChord = false;
        try { magicChordKeys.clear(); } catch (Exception ignored) {}
        try { KeyActions.clearAgentKeyQueue(this); } catch (Exception ignored) {}
        try { AgentBridge.put(this, AgentBridge.A11Y_LIVE, "0"); } catch (Exception ignored) {}
        instance = null;
        super.onDestroy();
    }
}
