package com.titanus2.controls;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Enable / disable {@link TrackpadAccessService} via Secure settings.
 * Priv-app has WRITE_SECURE_SETTINGS — no trip through system Settings UI.
 * <p>
 * Product default: Key service on after wipe/boot (layouts, specials, side
 * remap UI). Human opt-out in Keys is remembered until they turn it back on.
 */
public final class AccessServiceHelper {
    private static final String PREF = "titan2_access";
    private static final String KEY_USER_DISABLED = "user_disabled_key_service";
    /** Min gap between hard rebinds (listed-but-dead after wipe). */
    // 13.00: 5s rebind + master 0/1 thrash felt like force-close (a11y death loop).
    private static final long REBIND_MIN_MS = 20_000L;
    /** Delayed follow-up ensures after hard rebind (SettingsProvider race). */
    private static final long[] REBIND_FOLLOWUP_MS = { 1_500L, 8_000L };
    /**
     * Min gap between full plane/taskbar/pad heal inside ensureDefaultEnabled.
     * 12.01: was every call → host_layout mtime thrash → InputReader heat/spam.
     */
    private static final long HEAVY_ENSURE_MIN_MS = 45_000L;
    private static volatile long lastRebindElapsed;
    private static volatile long lastFollowupScheduleElapsed;
    private static volatile long lastHeavyEnsureElapsed;

    private AccessServiceHelper() {}

    public static ComponentName component(Context ctx) {
        return new ComponentName(ctx, TrackpadAccessService.class);
    }

    public static String flat(Context ctx) {
        return component(ctx).flattenToString();
    }

    /** False until CE is up. Locked-boot CE prefs crash the process. */
    public static boolean userUnlocked(Context ctx) {
        if (ctx == null) return false;
        try {
            android.os.UserManager um = ctx.getSystemService(android.os.UserManager.class);
            return um == null || um.isUserUnlocked();
        } catch (Exception e) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** True when the user turned Key service off in Titan Controls → Keys. */
    public static boolean isUserDisabled(Context ctx) {
        if (!userUnlocked(ctx)) return false;
        try {
            return prefs(ctx).getBoolean(KEY_USER_DISABLED, false);
        } catch (Exception e) {
            return false;
        }
    }

    public static void setUserDisabled(Context ctx, boolean disabled) {
        prefs(ctx).edit().putBoolean(KEY_USER_DISABLED, disabled).apply();
    }

    /** Listed in enabled_accessibility_services. */
    public static boolean isListed(Context ctx) {
        String enabled = Settings.Secure.getString(ctx.getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null || enabled.isEmpty()) return false;
        String me = flat(ctx);
        String pkgSlash = ctx.getPackageName() + "/";
        TextUtils.SimpleStringSplitter sp = new TextUtils.SimpleStringSplitter(':');
        sp.setString(enabled);
        while (sp.hasNext()) {
            String s = sp.next();
            if (s == null) continue;
            if (s.equalsIgnoreCase(me) || s.contains(pkgSlash)) return true;
        }
        return false;
    }

    /** Service process connected (can filter keys). */
    public static boolean isConnected() {
        return TrackpadAccessService.get() != null;
    }

    /** Listed and actually bound. Master-on + listed-but-crashed is not ready. */
    public static boolean isReady(Context ctx) {
        return isListed(ctx) && isConnected();
    }

    /** Stamp plane from process truth. Never write live=1 without a bound instance. */
    public static void stampLiveTruth(Context ctx) {
        boolean live = isConnected();
        try {
            AgentBridge.put(ctx, AgentBridge.A11Y_LIVE, live ? "1" : "0");
        } catch (Exception ignored) {}
        if (ctx != null) {
            try {
                Settings.Global.putString(ctx.getContentResolver(),
                    AgentBridge.A11Y_LIVE, live ? "1" : "0");
            } catch (Exception ignored) {}
        }
    }

    /**
     * 12.53: unlock / USER_PRESENT — always run full plane+taskbar+B1 belt
     * (bypass 30s heavy throttle). Doze can leave sides dead and dens taskbar
     * re-raised without waiting for the next ensure interval.
     * <p>
     * 13.38: also dens taskbar Global pin, ghost host_layout heal, B2 plane
     * heal, and re-publish side factory binds (doze wipe residual).
     */
    /** Package replace / crash: drop rebind throttle and admit the service is dead. */
    public static void forceRebindAfterReplace(Context ctx) {
        if (!userUnlocked(ctx)) return;
        lastRebindElapsed = 0L;
        lastFollowupScheduleElapsed = 0L;
        lastHeavyEnsureElapsed = 0L;
        stampLiveTruth(ctx);
        if (ctx != null) ensureDefaultEnabled(ctx);
    }

    public static boolean forceUnlockBelt(Context ctx) {
        lastHeavyEnsureElapsed = 0L;
        lastFollowupScheduleElapsed = 0L;
        boolean ok = ensureDefaultEnabled(ctx);
        // 12.61: re-assert a11y_live so pad-agent side fallback stays off when live
        try {
            if (isConnected()) {
                AgentBridge.put(ctx, AgentBridge.A11Y_LIVE, "1");
            }
        } catch (Exception ignored) {}
        // 13.03: screen-on / unlock — free stuck Sym inject + layoutHeld so HW
        // keyboard is never dead after doze with inject specials half-hold.
        try { TrackpadAccessService.clearLayoutKeyOwnership(); } catch (Exception ignored) {}
        try {
            if (ctx != null) {
                HostLayoutController.releaseKeysPauseIfInjectOnly(ctx);
            }
        } catch (Exception ignored) {}
        // 13.38 unlock dens residual + plane/side belt
        // 13.51: while exclusive HID live, only taskbar pin — do not healStale
        // (was zeroing session / fighting HID from Controls unlock belt).
        try {
            if (ctx != null) {
                TaskbarPin.pinOff(ctx);
                boolean exclusive = false;
                try {
                    exclusive = HostLayoutController.isHidExclusiveLiveFast(ctx);
                } catch (Exception ignored2) {}
                if (!exclusive) {
                    HostLayoutController.healGhostPhoneLayout(ctx);
                    HostLayoutController.healStaleHidPlane(ctx);
                    KeyMapPrefs km = new KeyMapPrefs(ctx);
                    km.migrateSideDefaultsNone();
                    km.publishSidesAndSpecialsMethod(ctx);
                }
            }
        } catch (Exception ignored) {}
        return ok;
    }

    /**
     * After wipe / first boot / exclusive HID thrash: enable Key a11y unless
     * the user previously opted out in Keys. Also forces the master
     * accessibility switch on when the service is listed but inert.
     * @return true if service is listed after this call
     */
    public static boolean ensureDefaultEnabled(Context ctx) {
        if (ctx == null) return false;
        if (isUserDisabled(ctx)) return false;
        // 13.48+/13.86: hold-Sym factory (sides none) + specials KCM onto agent plane
        try {
            KeyMapPrefs km = new KeyMapPrefs(ctx);
            km.migrateSideDefaultsNone(); // v4 → sides none (hold Sym owns specials)
            if (AgentBridge.get(ctx, AgentBridge.SPECIALS_METHOD, null) == null) {
                KeyMapPrefs.setSpecialsMethod(ctx, KeyMapPrefs.SPECIALS_METHOD_KCM);
            }
            km.publishSidesAndSpecialsMethod(ctx);
        } catch (Exception ignored) {}
        boolean listed = isListed(ctx);
        if (!listed) {
            if (!setEnabled(ctx, true)) return false;
            listed = isListed(ctx);
        }
        // Some builds leave services listed while ACCESSIBILITY_ENABLED=0 after
        // wipe / developer-option races — re-assert master switch.
        try {
            int master = Settings.Secure.getInt(ctx.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (listed && master != 1) {
                Settings.Secure.putInt(ctx.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
        } catch (Exception ignored) {}
        // P0 B1: listed but process not bound (wipe / SystemUI race) → sides
        // fall through to stock CAMERA/Home until human opens Keys. Hard rebind
        // by removing then re-adding the component (throttled) + delayed waves.
        if (listed && !isConnected()) {
            // 13.00: one soft rebind only — never toggle master accessibility 0→1
            // (that killed Bound services and looked like app force-close).
            rebindIfStale(ctx);
            listed = isListed(ctx);
            // 12.70: listed-but-unbound — mark dead so pad-agent KEY_FIRE owns sides
            // until onServiceConnected re-stamps live=1 (1.62 listed-only was not enough).
            if (!isConnected()) {
                try { AgentBridge.put(ctx, AgentBridge.A11Y_LIVE, "0"); } catch (Exception ignored) {}
            }
            // Keep master ON if anything listed (no 0/1 thrash)
            try {
                Settings.Secure.putInt(ctx.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            } catch (Exception ignored) {}
            // 12.35: a11y death left layoutHeldScans + agent inject backlog mid-hold
            try { TrackpadAccessService.clearLayoutKeyOwnership(); } catch (Exception ignored) {}
            try { KeyActions.clearAgentKeyQueue(ctx); } catch (Exception ignored) {}
            try { KeyActions.clearRemoteHidQueues(ctx); } catch (Exception ignored) {}
            scheduleRebindFollowups(ctx.getApplicationContext());
        }
        // 12.01: heavy heal belt throttled — every-ensure publish rewrote host_layout
        // mtime → pad-agent/InputReader thrash (heat + 4× letter spam).
        // Always keep a11y listed/master; only re-run plane/taskbar/pad on interval
        // or when service was just rebound (listed-but-dead path above).
        long now = android.os.SystemClock.elapsedRealtime();
        boolean forceHeal = listed && !isConnected();
        if (!forceHeal && now - lastHeavyEnsureElapsed < HEAVY_ENSURE_MIN_MS) {
            // 12.99: light polish only (idempotent long_press) — never IME thrash
            try { ImeHwPrefs.applyHwTypingPolish(ctx); } catch (Exception ignored) {}
            // 13.02: keep a11y_live fresh while Bound (pad-agent sides + heal scripts)
            if (isConnected()) {
                try { AgentBridge.put(ctx, AgentBridge.A11Y_LIVE, "1"); }
                catch (Exception ignored) {}
            }
            // 13.90 FB-SEC-1: stock cameratoggle residual — light path still
            // replaces stock QS (best-effort) + arms framework privacy hook.
            try { SensorQsDefaults.ensureDefaultTiles(ctx); } catch (Exception ignored) {}
            try { SensorPrivacyEnforcer.installStockToggleHook(ctx); } catch (Exception ignored) {}
            // 13.92: reassert force-stop while privacy stays ON (post-edge open residual)
            try { SensorPrivacyEnforcer.reassertBlockedSensors(ctx); } catch (Exception ignored) {}
            return listed || isListed(ctx);
        }
        lastHeavyEnsureElapsed = now;
        // 12.16/12.97/12.99: HW typing polish (throttled inside method)
        try { ImeHwPrefs.applyHwTypingPolish(ctx); } catch (Exception ignored) {}
        // 12.35/12.53: free stuck layout key ownership + drop inject backlog (Key a11y P0)
        try { TrackpadAccessService.clearLayoutKeyOwnership(); } catch (Exception ignored) {}
        try { KeyActions.clearAgentKeyQueue(ctx); } catch (Exception ignored) {}
        try { KeyActions.clearRemoteHidQueues(ctx); } catch (Exception ignored) {}
        // B1 factory sides + specials plane: re-publish km after thrash/wipe
        try {
            HostLayoutController.bindApp(ctx);
            KeyMapPrefs km = new KeyMapPrefs(ctx);
            // 11.87: force-heal side chrome poison then publish (B1 invariant)
            km.healSideChromeToFactory();
            km.publishToAgent(ctx);
        } catch (Exception ignored) {}
        try {
            HostLayoutController.publish(ctx);
        } catch (Exception ignored) {}
        try {
            TypingCursorLock.clear(ctx);
        } catch (Exception ignored) {}
        // Soft IME hide with HW keyboard (product default / Tweaks toggle)
        try {
            ImeHwPrefs.applyStored(ctx);
        } catch (Exception ignored) {}
        // P0 taskbar residual + OS Look seed whenever a11y belt runs
        try {
            TaskbarPin.pinOff(ctx);
        } catch (Exception ignored) {}
        // B2 11.85: Key a11y belt also heals sticky exclusive leftovers (process warm)
        try {
            HostLayoutController.healStaleHidPlane(ctx);
        } catch (Exception ignored) {}
        // 13.20: plane=specials stuck on phone → layout "sym" forever / Termux inject dead
        try {
            HostLayoutController.healGhostPhoneLayout(ctx);
        } catch (Exception ignored) {}
        // 11.60: setup-wizard disable flags leave QS/status bar dead after wipe
        try {
            SetupWizardHeal.heal(ctx);
        } catch (Exception ignored) {}
        try {
            PadQsDefaults.ensureDefaultTile(ctx);
        } catch (Exception ignored) {}
        // FB-SEC-1 residual 13.90: replace stock cameratoggle/mictoggle (best-effort
        // — SystemUI may own sysui_qs_tiles) + framework privacy-on force-stop hook.
        try {
            SensorQsDefaults.ensureDefaultTiles(ctx);
        } catch (Exception ignored) {}
        try {
            SensorPrivacyEnforcer.installStockToggleHook(ctx);
        } catch (Exception ignored) {}
        // 13.92 FB-SEC-1 residual: privacy-on edge force-stop left later opens
        // streaming under unlock dialog — reassert while blocked (throttled).
        try {
            SensorPrivacyEnforcer.reassertBlockedSensors(ctx);
        } catch (Exception ignored) {}
        // ThemePrefs.applyOsPlane is human Glow/Mode only — a11y ensure must not
        // restamp Wallpaper & style (heresy 2026-08-20).
        // B8 11.96: pad mode gate on every a11y ensure (start if wanted, stop orphan)
        try {
            String pm = PadModeController.getMode(ctx);
            if (PadModeController.MOUSE.equals(pm) || PadModeController.TRACKPAD.equals(pm)) {
                PadModeController.ensureTouchpaddProcess();
            } else {
                PadModeController.stopTouchpaddProcess();
            }
        } catch (Exception ignored) {}
        // Force letter-menu off (right-edge show_key_presses) every heavy ensure
        try { DebugPrefs.ensureDefaultOff(ctx); } catch (Exception ignored) {}
        return listed || isListed(ctx);
    }

    /**
     * 11.46: after hard rebind, SettingsProvider / AccessibilityManager may
     * still leave the service listed-but-dead for a few seconds. Re-assert
     * master switch + optional second rebind without waiting for hub open.
     */
    private static void scheduleRebindFollowups(Context app) {
        if (app == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        // Don't stampede followups from every ensure call
        if (now - lastFollowupScheduleElapsed < 4_000L) return;
        lastFollowupScheduleElapsed = now;
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        for (long delay : REBIND_FOLLOWUP_MS) {
            h.postDelayed(() -> {
                try {
                    if (isUserDisabled(app)) return;
                    if (isConnected()) {
                        try { TaskbarPin.pinOff(app); } catch (Exception ignored) {}
                        // 12.76 B1: rebind race left a11y_live stale/0 → pad-agent dual sides
                        try { AgentBridge.put(app, AgentBridge.A11Y_LIVE, "1"); } catch (Exception ignored) {}
                        // B2: a11y live after rebind race — clear sticky HID plane
                        try { HostLayoutController.healStaleHidPlane(app); } catch (Exception ignored) {}
                        // 12.36: rebind-connected — drop stuck layout ownership + inject backlog
                        try { TrackpadAccessService.clearLayoutKeyOwnership(); } catch (Exception ignored) {}
                        try { KeyActions.clearAgentKeyQueue(app); } catch (Exception ignored) {}
                        // 11.63: late rebind followups also unstick typing + QS/setup
                        try { TypingCursorLock.clear(app); } catch (Exception ignored) {}
                        try { SetupWizardHeal.heal(app); } catch (Exception ignored) {}
                        try { PadQsDefaults.ensureDefaultTile(app); } catch (Exception ignored) {}
                        try { SensorQsDefaults.ensureDefaultTiles(app); } catch (Exception ignored) {}
                        // 11.64: rebind-connected path also restores IME HW + B8 pad
                        try { ImeHwPrefs.applyStored(app); } catch (Exception ignored) {}
                        try {
                            String pm = PadModeController.getMode(app);
                            if (PadModeController.MOUSE.equals(pm)
                                    || PadModeController.TRACKPAD.equals(pm)) {
                                PadModeController.ensureTouchpaddProcess();
                            } else {
                                PadModeController.stopTouchpaddProcess();
                            }
                        } catch (Exception ignored) {}
                        return;
                    }
                    // Still dead: master + rebind + km re-publish
                    try {
                        Settings.Secure.putInt(app.getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                    } catch (Exception ignored) {}
                    if (!isListed(app)) {
                        setEnabled(app, true);
                    } else {
                        rebindIfStale(app);
                    }
                    try { TrackpadAccessService.clearLayoutKeyOwnership(); } catch (Exception ignored) {}
                    try { KeyActions.clearAgentKeyQueue(app); } catch (Exception ignored) {}
                    try { TaskbarPin.pinOff(app); } catch (Exception ignored) {}
                    try { new KeyMapPrefs(app).publishToAgent(app); } catch (Exception ignored) {}
                } catch (Exception ignored) {}
            }, delay);
        }
    }

    /**
     * Force AccessibilityManager to re-connect TrackpadAccessService when
     * Secure still lists it but {@link TrackpadAccessService#get()} is null.
     */
    private static void rebindIfStale(Context ctx) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastRebindElapsed < REBIND_MIN_MS) return;
        lastRebindElapsed = now;
        try {
            // Toggle off then on without recording user opt-out
            String me = flat(ctx);
            String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            StringBuilder others = new StringBuilder();
            if (enabled != null && !enabled.isEmpty()) {
                TextUtils.SimpleStringSplitter sp = new TextUtils.SimpleStringSplitter(':');
                sp.setString(enabled);
                while (sp.hasNext()) {
                    String s = sp.next();
                    if (s == null || s.isEmpty()) continue;
                    if (s.equalsIgnoreCase(me) || s.contains(ctx.getPackageName() + "/")) continue;
                    if (others.length() > 0) others.append(':');
                    others.append(s);
                }
            }
            // 13.00: keep ACCESSIBILITY_ENABLED=1 for the whole rebind. Dropping
            // master to 0 while swapping the list tore down other a11y + our
            // service and felt like force-close loops.
            Settings.Secure.putString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.toString());
            // Re-add immediately
            StringBuilder next = new StringBuilder(others);
            if (next.length() > 0) next.append(':');
            next.append(me);
            Settings.Secure.putString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next.toString());
            Settings.Secure.putInt(ctx.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (Exception ignored) {}
    }

    /**
     * Turn key accessibility on or off.
     * Records user opt-out when {@code on=false} so boot restore does not fight it.
     * @return true if Secure settings write succeeded
     */
    public static boolean setEnabled(Context ctx, boolean on) {
        try {
            String me = flat(ctx);
            String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            StringBuilder next = new StringBuilder();
            if (enabled != null && !enabled.isEmpty()) {
                TextUtils.SimpleStringSplitter sp = new TextUtils.SimpleStringSplitter(':');
                sp.setString(enabled);
                while (sp.hasNext()) {
                    String s = sp.next();
                    if (s == null || s.isEmpty()) continue;
                    if (s.equalsIgnoreCase(me) || s.contains(ctx.getPackageName() + "/")) continue;
                    if (next.length() > 0) next.append(':');
                    next.append(s);
                }
            }
            if (on) {
                if (next.length() > 0) next.append(':');
                next.append(me);
            }
            Settings.Secure.putString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next.toString());
            boolean any = next.length() > 0;
            Settings.Secure.putInt(ctx.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, any ? 1 : 0);
            setUserDisabled(ctx, !on);
            // Listed is not bound. live=1 before onServiceConnected eats Home
            // (key-watch skips KEY_FIRE while a11y is still crashed).
            stampLiveTruth(ctx);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
