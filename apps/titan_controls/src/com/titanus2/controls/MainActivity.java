package com.titanus2.controls;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.titanus2.controls.devtools.DevToolsActivity;
import com.titanus2.controls.subdisplay.SubDisplayActivity;
import com.titanus2.controls.subdisplay.SubDisplayPrefs;
import com.titanus2.controls.subdisplay.SubDisplayService;
import com.titanus2.controls.notifled.NotifLedActivity;
import com.titanus2.controls.notifled.NotifLedController;
import com.titanus2.controls.notifled.NotifLedPrefs;
import com.titanus2.controls.notifled.NotifLedService;
import com.titanus2.controls.ui.UiKit;

/**
 * Settings hub — hardware you touch every day.
 * Modes as segments; destinations as list rows; no agent dumps.
 * <p>
 * Keyboard-first: letter keys open destinations; 0/1/2 set pad mode;
 * L cycles keyboard light level; O cycles idle timeout;
 * TAB/arrows use system focus ({@link com.titanus2.controls.ui.UiKit}).
 * USB HID is a <b>separate launcher app</b> ({@code com.titanus2.usbhid}) —
 * never nest it under this Settings hub (product; do not re-add a HID row).
 * <p>
 * QS long-press ({@code QS_TILE_PREFERENCES}) and
 * {@link #ACTION_OPEN_PAD} open the Trackpad section at the top.
 */
public class MainActivity extends Activity {
    /** Keyboard light steps (product tiles Off/1/3/5/Max). */
    private static final int[] LED_LEVELS = {0, 1, 3, 5, 7};
    /** Idle timeout seconds (Always / 10 / 30 / 60). */
    private static final int[] LED_TIMEOUTS = {0, 10, 30, 60};
    /** Deep link / QS long-press: open Trackpad section. */
    public static final String ACTION_OPEN_PAD = "com.titanus2.controls.action.OPEN_PAD";
    public static final String EXTRA_SECTION = "section";
    public static final String SECTION_PAD = "pad";

    private TextView padSummary, ledSummary;
    private TextView bOff, bTrack, bMouse;
    private TextView bTap, bFollow;
    private UiKit.Step ledStep;
    private TextView bIdleTimeout;
    private TextView bLedTest;

    private LinearLayout navKeys, navSub, navNotif, navTweaks, navSims;
    private ScrollView scroll;
    private LinearLayout sectionPad;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            h.postDelayed(this, 1200);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 15.28: solid window + scroll clip — kill multi-layer ghost frames
        // (user Gallery: stacked Controls text while scrolling Settings embed).
        UiKit.applyOpaqueWindow(this);
        scroll = new ScrollView(this);
        UiKit.prepareScroll(scroll);
        LinearLayout root = new LinearLayout(this);
        UiKit.screen(root);
        scroll.addView(root, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT));

        // Action bar already says Titan Controls — no second title.

        // --- Pad ---
        sectionPad = new LinearLayout(this);
        sectionPad.setOrientation(LinearLayout.VERTICAL);
        root.addView(sectionPad, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        UiKit.section(sectionPad, "Trackpad");
        padSummary = UiKit.summary(sectionPad);
        LinearLayout pad = UiKit.row(sectionPad);
        bOff = UiKit.flexButton(pad, "Off", () -> setPad(PadModeController.OFF));
        bTrack = UiKit.flexButton(pad, "Trackpad", () -> setPad(PadModeController.TRACKPAD));
        bMouse = UiKit.flexButton(pad, "Mouse", () -> setPad(PadModeController.MOUSE));
        LinearLayout padOpts = UiKit.row(sectionPad);
        bTap = UiKit.flexButton(padOpts, "Tap to click", this::toggleTap);
        bFollow = UiKit.flexButton(padOpts, "Follow screen", this::toggleFollow);
        // Freeze pad/mouse while typing — global option + manual unlock delay.
        // Delay is published to titan2_pad_cursor_pause_ms (pad-agent TTL SoT).
        UiKit.toggle(sectionPad, "Lock cursor while typing",
            TypingCursorLock.isEnabled(this),
            on -> TypingCursorLock.setEnabled(this, on));
        // Manual delay: 100…3000 ms (step index 0..10)
        int coolIdx = typingLockMsToStep(TypingCursorLock.cooldownMs(this));
        final UiKit.Step[] coolBox = new UiKit.Step[1];
        coolBox[0] = UiKit.step(sectionPad, "Unlock delay", 0, 10, coolIdx, idx -> {
            int ms = typingLockStepToMs(idx);
            // setCooldownMs publishes plane TTL only while locked (not residual ms).
            TypingCursorLock.setCooldownMs(this, ms);
            if (coolBox[0] != null) coolBox[0].setDisplay(ms + " ms");
        });
        coolBox[0].setDisplay(typingLockStepToMs(coolIdx) + " ms");

        // Hardware keyboard — stock Settings SoT for repeat (do not reimplement).
        // Product IME: AOSP LatinIME (ImeHwPrefs). Accents: Letter variations
        // (default Off = a11y kills CharacterPicker on PICKER_SETS hold-repeat).
        // 15.30 restored kill after Pastiera purge; 15.31 restores hub intent.
        try { KeyRepeatPrefs.syncFromSystem(this); } catch (Exception ignored) {}
        try { ImeHwPrefs.applyHwTypingPolish(this); } catch (Exception ignored) {}
        try { LetterVariationsPrefs.apply(this); } catch (Exception ignored) {}
        UiKit.toggle(sectionPad, "Key repeat",
            KeyRepeatPrefs.isEnabled(this),
            on -> KeyRepeatPrefs.setEnabled(this, on));
        UiKit.navRow(sectionPad, "Physical keyboard",
            KeyRepeatPrefs.summary(this) + " · shortcuts, delay, rate",
            () -> KeyRepeatPrefs.openSystemPhysicalKeyboardSettings(this));
        // Optional accent/letter popup — default Off (hold-a repeats plain).
        UiKit.toggle(sectionPad, "Letter variations",
            LetterVariationsPrefs.isEnabled(this),
            on -> LetterVariationsPrefs.setEnabled(this, on));

        // Text caret product-off (plane forced 0 by pad-agent). No UI thrash.

        // --- Keyboard backlight ---
        // QA 2026-07-18: compact — step + one idle cycle (not 9 option buttons).
        UiKit.section(root, "Keyboard light");
        ledSummary = UiKit.summary(root);
        ledStep = UiKit.step(root, "Brightness", 0, 7, readLedLevel(), this::setLed);
        LinearLayout idleRow = UiKit.row(root);
        bIdleTimeout = UiKit.flexButton(idleRow, idleTimeoutLabel(readLedTimeout()),
            this::cycleLedTimeout);
        bLedTest = UiKit.flexButton(idleRow, "Test", this::testKeyboardLight);

        // Privacy cam/mic: QS — product Camera tile (stock cameratoggle is RemovedTiles on GSI).
        // No hub duplicates — lab sticky Secure residual from dual UI.

        // --- Destinations ---
        UiKit.section(root, "Phone");
        navKeys = UiKit.navRow(root, "Keys", "Shortcuts, layouts, Fn",
            () -> startActivity(new Intent(this, KeyMapActivity.class)));
        // USB HID = own launcher app (com.titanus2.usbhid) — not a hub row.
        navSub = UiKit.navRow(root, "Sub display", "",
            () -> startActivity(new Intent(this, SubDisplayActivity.class)));
        navNotif = UiKit.navRow(root, "Notification light", "",
            () -> startActivity(new Intent(this, NotifLedActivity.class)));
        UiKit.navRow(root, "Wi‑Fi", "Networks, internet",
            () -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
                } catch (Exception e) {
                    startActivity(new Intent(this, NetworkActivity.class));
                }
            });
        // HW stays here. TrebleApp (hidden) owns GSI IMS/misc call paths; Tweaks wraps it.
        navTweaks = UiKit.navRow(root, "Tweaks", "Treble/IMS + keyboard",
            () -> startActivity(new Intent(this, NetworkActivity.class)));
        navSims = UiKit.navRow(root, "SIMs", "On / Off — disable does not delete",
            () -> startActivity(new Intent(this, SimActivity.class)));
        UiKit.navRow(root, "Diagnostics", "Calls + nav, no test call",
            () -> startActivity(new Intent(this, DiagnosticsActivity.class)));
        // Look hub stays out — ROM chrome is cube-ux / RROs (ThemeActivity adb only).
        // Developer: always on hub. TCP ADB is human-only (no boot auto-arm).
        // Wireless ADB = classic :5555 for Tailscale/LTE/Wi‑Fi after opt-in —
        // not stock Settings "Wireless debugging".
        UiKit.navRow(root, "Developer",
            "Remote ADB · USB ADB · debug",
            () -> startActivity(new Intent(this, DevToolsActivity.class)));
        // LAW: every build stamps CHANGELOG → assets; hub must expose it.
        String verLine = "build";
        try {
            verLine = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
        } catch (Throwable ignored) {
            try {
                verLine = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) { /* keep default */ }
        }
        UiKit.navRow(root, "Changelog", verLine,
            () -> startActivity(new Intent(this, ChangelogActivity.class)));
        UiKit.navRow(root, "About", "AtlasOS · credits",
            () -> startActivity(new Intent(this, AboutActivity.class)));

        // Mono fact: HW keyboard map for this hub (not marketing).
        TextView kbHint = UiKit.mono(root);
        kbHint.setText("K Keys · S Sub · N notif · W Wi‑Fi · T Tweaks · M SIMs · I diag · D Dev · G log · "
            + "0/1/2 pad · C tap · L light · O idle");
        setContentView(scroll);
        // Inherit OS DeviceDefault theme (Cube is system-wide via cube-ux / RROs, not app paint)
        seed();
        reapply();
        try { DebugPrefs.ensureDefaultOff(this); } catch (Exception ignored) {}
        // Hide TrebleApp Settings tile — product entry is Tweaks wrap only.
        try { TrebleAppBridge.hideFromSettings(this); } catch (Exception ignored) {}
        // P0 Key a11y: re-assert after exclusive HID / wipe thrash
        try { AccessServiceHelper.ensureDefaultEnabled(this); } catch (Exception ignored) {}
        // FB-SEC-1: arm fail-closed kill (stock QS AppOps IGNORED → force-stop).
        // MANAGE_SENSOR_PRIVACY may be denied on GSI updates — kill path uses FORCE_STOP.
        try {
            SensorPrivacyEnforcer.installStockToggleHook(this);
            SensorPrivacyEnforcer.reassertBlockedSensors(this);
            SensorQsDefaults.ensureDefaultTiles(this); // strip Titan privacy tile mess
        } catch (Exception ignored) {}
        refresh();
        handleOpenIntent(getIntent());
        // Land focus on first pad control so TAB/Enter work immediately.
        if (bOff != null) {
            bOff.post(() -> {
                try { bOff.requestFocus(); } catch (Exception ignored) {}
            });
        }
    }

    /**
     * TitanKey hub shortcuts — single owner, no modifiers (leave Sym/Alt free).
     * Escape finishes; 0/1/2 = pad Off/Trackpad/Mouse.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && !event.isAltPressed() && !event.isCtrlPressed()
                && !event.isMetaPressed() && !event.isShiftPressed()) {
            int kc = event.getKeyCode();
            switch (kc) {
                case KeyEvent.KEYCODE_ESCAPE:
                    finish();
                    return true;
                case KeyEvent.KEYCODE_K:
                    startActivity(new Intent(this, KeyMapActivity.class));
                    return true;
                case KeyEvent.KEYCODE_S:
                    startActivity(new Intent(this, SubDisplayActivity.class));
                    return true;
                case KeyEvent.KEYCODE_N:
                    startActivity(new Intent(this, NotifLedActivity.class));
                    return true;
                case KeyEvent.KEYCODE_T:
                    startActivity(new Intent(this, NetworkActivity.class));
                    return true;
                case KeyEvent.KEYCODE_M:
                    startActivity(new Intent(this, SimActivity.class));
                    return true;
                case KeyEvent.KEYCODE_I:
                    startActivity(new Intent(this, DiagnosticsActivity.class));
                    return true;
                case KeyEvent.KEYCODE_W:
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
                    } catch (Exception e) {
                        startActivity(new Intent(this, NetworkActivity.class));
                    }
                    return true;
                case KeyEvent.KEYCODE_D:
                    startActivity(new Intent(this, DevToolsActivity.class));
                    return true;
                case KeyEvent.KEYCODE_G:
                    startActivity(new Intent(this, ChangelogActivity.class));
                    return true;
                case KeyEvent.KEYCODE_0:
                case KeyEvent.KEYCODE_NUMPAD_0:
                    setPad(PadModeController.OFF);
                    return true;
                case KeyEvent.KEYCODE_1:
                case KeyEvent.KEYCODE_NUMPAD_1:
                    setPad(PadModeController.TRACKPAD);
                    return true;
                case KeyEvent.KEYCODE_2:
                case KeyEvent.KEYCODE_NUMPAD_2:
                    setPad(PadModeController.MOUSE);
                    return true;
                case KeyEvent.KEYCODE_C:
                    toggleTap();
                    return true;
                case KeyEvent.KEYCODE_L:
                    cycleLedLevel();
                    return true;
                case KeyEvent.KEYCODE_O:
                    cycleLedTimeout();
                    return true;
                // Minus / Equals = step light down/up (intent=result without cycling)
                case KeyEvent.KEYCODE_MINUS:
                    stepLedLevel(-1);
                    return true;
                case KeyEvent.KEYCODE_EQUALS:
                    stepLedLevel(+1);
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpenIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        // Re-stamp opaque chrome — Settings host can re-tint window on re-embed.
        try { UiKit.applyOpaqueWindow(this); } catch (Exception ignored) {}
        try { if (scroll != null) UiKit.prepareScroll(scroll); } catch (Exception ignored) {}
        try { DebugPrefs.ensureDefaultOff(this); } catch (Exception ignored) {}
        // P0: hub open heals wipe races (taskbar residual, Key a11y, QS, B1)
        try { TaskbarPin.pinOff(this); } catch (Exception ignored) {}
        // 12.65: listed-but-dead → force belt; live → re-stamp a11y_live (B1 sides)
        try {
            if (!AccessServiceHelper.isConnected()) {
                AccessServiceHelper.forceUnlockBelt(this);
            } else {
                AccessServiceHelper.ensureDefaultEnabled(this);
                AgentBridge.put(this, AgentBridge.A11Y_LIVE, "1");
            }
        } catch (Exception ignored) {}
        try { SetupWizardHeal.heal(this); } catch (Exception ignored) {}
        try { ImeHwPrefs.applyStored(this); } catch (Exception ignored) {}
        // Stock Physical keyboard may have changed Repeat keys — mirror Secure → plane
        try { KeyRepeatPrefs.syncFromSystem(this); } catch (Exception ignored) {}
        try { LetterVariationsPrefs.apply(this); } catch (Exception ignored) {}
        // 12.43 B1: hub open re-heals side chrome (without opening Keys)
        try {
            KeyMapPrefs km = new KeyMapPrefs(this);
            km.healSideChromeToFactory();
            km.publishToAgent(this);
        } catch (Exception ignored) {}
        // 11.56: B2 plane + typing unstick + B8 touchpadd whenever hub is opened
        try { HostLayoutController.bindApp(this); } catch (Exception ignored) {}
        // 13.51: never healStale / restart pad while exclusive HID is live —
        // hub open was wiping session plane and dual-starting touchpadd (unstable HID).
        boolean exclusive = false;
        try {
            exclusive = HostLayoutController.isHidExclusiveLiveFast(this);
        } catch (Exception ignored) {}
        if (!exclusive) {
            try { HostLayoutController.healStaleHidPlane(this); } catch (Exception ignored) {}
            try {
                String pm = PadModeController.getMode(this);
                if (PadModeController.MOUSE.equals(pm)
                        || PadModeController.TRACKPAD.equals(pm)) {
                    PadModeController.ensureTouchpaddProcess();
                } else {
                    PadModeController.stopTouchpaddProcess();
                }
            } catch (Exception ignored) {}
        }
        try { TypingCursorLock.clear(this); } catch (Exception ignored) {}
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override protected void onPause() {
        h.removeCallbacks(tick);
        super.onPause();
    }

    /**
     * QS long-press ({@link TileService#ACTION_QS_TILE_PREFERENCES}) and
     * {@link #ACTION_OPEN_PAD} land on the Trackpad section (top of hub).
     */
    private void handleOpenIntent(Intent intent) {
        if (intent == null) return;
        String act = intent.getAction();
        String section = intent.getStringExtra(EXTRA_SECTION);
        boolean wantPad = ACTION_OPEN_PAD.equals(act)
            || SECTION_PAD.equals(section)
            || TileService.ACTION_QS_TILE_PREFERENCES.equals(act)
            || "android.service.quicksettings.action.QS_TILE_PREFERENCES".equals(act);
        if (!wantPad) return;
        // Trackpad is the first section — scroll to top and title the bar.
        try {
            setTitle("Trackpad");
        } catch (Exception ignored) {}
        if (scroll != null) {
            scroll.post(() -> {
                try {
                    scroll.smoothScrollTo(0, 0);
                    if (sectionPad != null) sectionPad.requestFocus();
                } catch (Exception ignored) {}
            });
        }
    }

    /** Manual unlock delay steps (ms) — user-facing Unlock delay control. */
    private static int typingLockStepToMs(int idx) {
        switch (idx) {
            case 0: return 100;
            case 1: return 150;
            case 2: return 200;
            case 3: return 300;
            case 4: return 400;
            case 5: return 600;
            case 6: return 800;
            case 7: return 1000;
            case 8: return 1500;
            case 9: return 2000;
            default: return 3000;
        }
    }

    private static int typingLockMsToStep(int ms) {
        if (ms <= 100) return 0;
        if (ms <= 150) return 1;
        if (ms <= 200) return 2;
        if (ms <= 300) return 3;
        if (ms <= 400) return 4;
        if (ms <= 600) return 5;
        if (ms <= 800) return 6;
        if (ms <= 1000) return 7;
        if (ms <= 1500) return 8;
        if (ms <= 2000) return 9;
        return 10;
    }

    private void seed() {
        if (AgentBridge.get(this, AgentBridge.LED_LEVEL, null) == null)
            AgentBridge.put(this, AgentBridge.LED_LEVEL, "3");
        if (AgentBridge.get(this, AgentBridge.LED_TIMEOUT, null) == null)
            AgentBridge.put(this, AgentBridge.LED_TIMEOUT, "30");
        // Only seed when no pad-mode control file exists anywhere (shared or private).
        // Do not treat literal "off" as missing — that stomped HID mouse mode.
        if (AgentBridge.newestMtime(this, AgentBridge.PAD_MODE) <= 0)
            AgentBridge.put(this, AgentBridge.PAD_MODE, PadModeController.OFF);
        if (AgentBridge.get(this, AgentBridge.PAD_CLICK, null) == null)
            AgentBridge.put(this, AgentBridge.PAD_CLICK, "1");
        if (AgentBridge.get(this, AgentBridge.PAD_TOP_ROW_CURSOR, null) == null)
            AgentBridge.put(this, AgentBridge.PAD_TOP_ROW_CURSOR, "1");
        if (AgentBridge.get(this, AgentBridge.PAD_TOP_ROW_ONLY, null) == null)
            AgentBridge.put(this, AgentBridge.PAD_TOP_ROW_ONLY, "0");
        if (AgentBridge.get(this, AgentBridge.PAD_FOLLOW_ORIENT, null) == null) {
            // Product default: follow screen (mouse axes track rotation)
            boolean fo = new TrackpadPrefs(this).getFollowOrient();
            AgentBridge.put(this, AgentBridge.PAD_FOLLOW_ORIENT, fo ? "1" : "0");
        }
        PadOrientationService.sync(this);
        KeyboardLed.restoreToControls(this);
        // Pad QS tile on default panel (first page), not only under edit
        try {
            PadQsDefaults.ensureDefaultTileWithRetries(this);
        } catch (Exception ignored) {}
    }

    private void reapply() {
        // Re-stamp current shared mode (PadModeController prefers OS/tmp plane).
        String pad = PadModeController.getMode(this);
        AgentBridge.put(this, AgentBridge.PAD_MODE, pad);
        AgentBridge.put(this, AgentBridge.LEGACY_PAD,
            PadModeController.MOUSE.equals(pad) ? "1" : "0");
        AgentBridge.put(this, AgentBridge.PAD_CLICK, AgentBridge.get(this, AgentBridge.PAD_CLICK, "1"));
        AgentBridge.put(this, AgentBridge.PAD_TOP_ROW_CURSOR,
            AgentBridge.get(this, AgentBridge.PAD_TOP_ROW_CURSOR, "1"));
        AgentBridge.put(this, AgentBridge.PAD_TOP_ROW_ONLY,
            AgentBridge.get(this, AgentBridge.PAD_TOP_ROW_ONLY, "0"));
        AgentBridge.put(this, AgentBridge.PAD_FOLLOW_ORIENT,
            AgentBridge.get(this, AgentBridge.PAD_FOLLOW_ORIENT, "0"));
        AgentBridge.put(this, AgentBridge.FN_MODE, AgentBridge.get(this, AgentBridge.FN_MODE, "stock"));
        AgentBridge.put(this, AgentBridge.CHAR_MOD, AgentBridge.get(this, AgentBridge.CHAR_MOD, "sym"));
        // FB-DISP-1: rear touch is prefs-driven — never hardcode inhibit=1 (hub
        // reapply used to wipe trackpad-on after Sub display toggle).
        if (PadModeController.isFollowOrient(this)) {
            PadModeController.publishRotation(this);
        }
        SubDisplayService.applySubtouchPolicy(this);
        NotifLedController.publishConfig(this);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (PadModeController.isFollowOrient(this)) {
            PadModeController.publishRotation(this);
        }
    }

    private void setPad(String m) {
        PadModeController.setMode(this, m);
        refresh();
    }

    private void toggleTap() {
        boolean on = PadModeController.isTapToClick(this);
        PadModeController.setTapToClick(this, !on);
        refresh();
    }

    private void toggleFollow() {
        boolean on = PadModeController.isFollowOrient(this);
        PadModeController.setFollowOrient(this, !on);
        String m = PadModeController.getMode(this);
        if (PadModeController.TRACKPAD.equals(m) || PadModeController.MOUSE.equals(m)) {
            PadModeController.setMode(this, m);
        }
        refresh();
    }

    private void setLed(int n) {
        if (n < 0) n = 0;
        if (n > 7) n = 7;
        AgentBridge.put(this, AgentBridge.LED_LEVEL, String.valueOf(n));
        KeyboardLed.setLevel(this, n);
        // Pad-agent applies LED only while within idle window after key activity.
        // Without a bump, slider changes looked dead (want=N but hw=0 idle_timeout).
        AgentBridge.bumpKeyActivity(this);
        // CubalC free-flow: drive LED now, do not wait heat/apply_led poll.
        try {
            ImpulseSnap.keyled(n);
        } catch (Exception ignored) {}
        refresh();
    }

    private void setTimeout(int s) {
        AgentBridge.put(this, AgentBridge.LED_TIMEOUT, String.valueOf(s));
        KeyboardLed.setTimeoutSec(this, s);
        AgentBridge.put(this, AgentBridge.LED_LEVEL, AgentBridge.get(this, AgentBridge.LED_LEVEL, "3"));
        AgentBridge.bumpKeyActivity(this);
        try {
            ImpulseSnap.wakePadAgent();
            ImpulseSnap.keyled(Integer.parseInt(
                    AgentBridge.get(this, AgentBridge.LED_LEVEL, "3")));
        } catch (Exception ignored) {}
        refresh();
    }

    /** Cycle keyboard light Off → 1 → 3 → 5 → Max → Off (hub L key). */
    private void cycleLedLevel() {
        int idx = nearestLedIdx(readLedLevel());
        int next = LED_LEVELS[(idx + 1) % LED_LEVELS.length];
        setLed(next);
        UiKit.toast(this, next == 0 ? "Light off" : "Light " + next);
    }

    /** Cycle idle timeout Always → 10s → 30s → 60s (hub O key). */
    private void cycleLedTimeout() {
        int cur = readLedTimeout();
        int idx = 0;
        for (int i = 0; i < LED_TIMEOUTS.length; i++) {
            if (LED_TIMEOUTS[i] == cur) {
                idx = i;
                break;
            }
        }
        int next = LED_TIMEOUTS[(idx + 1) % LED_TIMEOUTS.length];
        setTimeout(next);
        UiKit.toast(this, next == 0 ? "Light always" : "Light idle " + next + "s");
    }

    /** Step light along product tiles (− / = keys). */
    private void stepLedLevel(int dir) {
        int idx = nearestLedIdx(readLedLevel()) + (dir < 0 ? -1 : 1);
        if (idx < 0) idx = 0;
        if (idx >= LED_LEVELS.length) idx = LED_LEVELS.length - 1;
        setLed(LED_LEVELS[idx]);
    }

    private static int nearestLedIdx(int cur) {
        int best = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < LED_LEVELS.length; i++) {
            int d = Math.abs(LED_LEVELS[i] - cur);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private int readLedLevel() {
        try {
            return Integer.parseInt(AgentBridge.get(this, AgentBridge.LED_LEVEL, "3"));
        } catch (Exception e) {
            return 3;
        }
    }

    private int readLedTimeout() {
        try {
            return Integer.parseInt(AgentBridge.get(this, AgentBridge.LED_TIMEOUT, "30"));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String idleTimeoutLabel(int sec) {
        if (sec <= 0) return "Idle: always on";
        return "Idle: " + sec + " s";
    }

    private boolean hasNotifAccess() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (flat == null || flat.isEmpty()) return false;
        ComponentName me = new ComponentName(this, NotifLedService.class);
        TextUtils.SimpleStringSplitter sp = new TextUtils.SimpleStringSplitter(':');
        sp.setString(flat);
        while (sp.hasNext()) {
            ComponentName cn = ComponentName.unflattenFromString(sp.next());
            if (me.equals(cn)) return true;
        }
        return false;
    }

    private void refresh() {
        String pad = AgentBridge.readStatus(AgentBridge.STATUS_PAD);
        String mode = PadModeController.getMode(this);
        boolean follow = PadModeController.isFollowOrient(this);
        boolean tap = PadModeController.isTapToClick(this);
        // Product: caret plane always off (no dual REL owner).
        try {
            if (PadModeController.isTopRowCursor(this)) {
                PadModeController.setTopRowCursor(this, false);
            }
            PadModeController.setTopRowOnly(this, false);
        } catch (Exception ignored) {}
        if (follow) PadModeController.publishRotation(this);

        UiKit.setSelected(bOff, PadModeController.OFF.equals(mode));
        UiKit.setSelected(bTrack, PadModeController.TRACKPAD.equals(mode));
        UiKit.setSelected(bMouse, PadModeController.MOUSE.equals(mode));
        UiKit.setSelected(bTap, tap);
        UiKit.setSelected(bFollow, follow);

        if (padSummary != null) {
            if (PadModeController.OFF.equals(mode)) {
                padSummary.setText("Off");
            } else {
                String name = PadModeController.TRACKPAD.equals(mode) ? "Trackpad" : "Mouse";
                String bits = name;
                if (tap) bits += " · Tap to click";
                if (follow) {
                    boolean ok = pad != null && (pad.contains("orient_rel=on") || pad.contains("idc=orient"));
                    bits += ok ? " · Follows screen" : " · Follow screen…";
                } else {
                    bits += " · Fixed axes";
                }
                padSummary.setText(bits);
            }
        }

        int lvl = 3;
        try { lvl = Integer.parseInt(AgentBridge.get(this, AgentBridge.LED_LEVEL, "3")); }
        catch (Exception ignored) {}
        int idle = 30;
        try { idle = Integer.parseInt(AgentBridge.get(this, AgentBridge.LED_TIMEOUT, "30")); }
        catch (Exception ignored) {}

        if (ledStep != null) ledStep.setValue(lvl);
        if (bIdleTimeout != null) bIdleTimeout.setText(idleTimeoutLabel(idle));

        if (ledSummary != null) {
            if (lvl == 0) {
                ledSummary.setText("Off");
            } else {
                String idleS = idle == 0 ? "stays on" : "off after " + idle + " s idle";
                ledSummary.setText("Level " + lvl + " · " + idleS);
            }
        }

        // Destination summaries
        KeyMapPrefs km = new KeyMapPrefs(this);
        int n = km.listVisibleRows().size();
        int np = new KeyMapProfiles(this).listPackages().size();
        String keysSum;
        if (n == 0 && np == 0) keysSum = "Add shortcuts";
        else if (np == 0) keysSum = n + " shortcuts";
        else if (n == 0) keysSum = np + " app profiles";
        else keysSum = n + " shortcuts · " + np + " apps";
        try {
            HostLayoutController.loadGlobalDefault(this);
            String lay = HostLayoutController.statusLine(this);
            if (lay != null && !lay.isEmpty() && !"Layout off".equals(lay)) {
                keysSum = keysSum + " · " + lay;
            }
        } catch (Exception ignored) {}
        UiKit.setNavSummary(navKeys, keysSum);
        UiKit.setNavSummary(navSub, SubDisplayPrefs.getMode(this).label());
        try {
            UiKit.setNavSummary(navSims, SimCards.hubSummary(this));
        } catch (Exception ignored) {}
        if (NotifLedPrefs.isEnabled(this)) {
            UiKit.setNavSummary(navNotif,
                hasNotifAccess() ? NotifLedPrefs.getMode(this) : "Allow notification access");
        } else {
            UiKit.setNavSummary(navNotif, "Off");
        }
    }

    /** Flash keyboard light now (bumps activity + level) so Test works even if idle. */
    private void testKeyboardLight() {
        int lvl = readLedLevel();
        if (lvl <= 0) lvl = 3;
        setLed(lvl);
        AgentBridge.bumpKeyActivity(this);
        // Short preview via notif engine (screen-on allowed for preview).
        try {
            NotifLedController.preview(this, 3);
        } catch (Exception ignored) {}
        AgentBridge.put(this, AgentBridge.LED_LEVEL, String.valueOf(lvl));
        if (ledSummary != null) {
            ledSummary.setText("Test: level " + lvl + " (3s)");
        }
    }
}
