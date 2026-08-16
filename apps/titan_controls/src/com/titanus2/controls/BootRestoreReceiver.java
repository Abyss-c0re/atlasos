package com.titanus2.controls;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.titanus2.controls.network.VpnHotspotHeal;
import com.titanus2.controls.notifled.NotifLedController;
import com.titanus2.controls.subdisplay.SubDisplayPrefs;
import com.titanus2.controls.subdisplay.SubDisplayService;

/**
 * On boot / package replace: restore control files + optional sub display face.
 * Re-enables Key a11y (TrackpadAccessService) after wipe unless the user turned
 * it off in Keys — product default for layouts/specials.
 */
public class BootRestoreReceiver extends BroadcastReceiver {
    private static final long[] A11Y_RETRY_MS = { 2_000L, 8_000L, 20_000L };

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action);
        boolean replaced = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!boot && !replaced) return;

        final Context app = context.getApplicationContext();
        pinAndHeal(app);
        // P0: Settings.Secure can lag after wipe / modular reflash — retry a11y
        // + taskbar + B1 km publish until TrackpadAccessService connects (or max).
        scheduleA11yRetries(app);
        // Package-replace: a11y + taskbar + plane only (no full pad/LED restore).
        if (replaced) return;

        try {
            int lvl = Settings.System.getInt(context.getContentResolver(),
                    KeyboardLed.SETTINGS_KEY, KeyboardLed.DEFAULT_LEVEL);
            if (lvl < 0 || lvl > 7) lvl = KeyboardLed.DEFAULT_LEVEL;
            AgentBridge.put(context, AgentBridge.LED_LEVEL, String.valueOf(lvl));
        } catch (Exception ignored) {}
        try {
            int to = Settings.System.getInt(context.getContentResolver(),
                    KeyboardLed.TIMEOUT_SETTINGS_KEY, KeyboardLed.DEFAULT_TIMEOUT_SEC);
            if (to < 0) to = KeyboardLed.DEFAULT_TIMEOUT_SEC;
            AgentBridge.put(context, AgentBridge.LED_TIMEOUT, String.valueOf(to));
        } catch (Exception ignored) {}
        try {
            KeyboardLed.restoreToControls(context);
        } catch (Exception ignored) {}
        try {
            String m = AgentBridge.get(context, AgentBridge.PAD_MODE, "off");
            AgentBridge.put(context, AgentBridge.PAD_MODE, m);
            String c = AgentBridge.get(context, AgentBridge.PAD_CLICK, "1");
            AgentBridge.put(context, AgentBridge.PAD_CLICK, c);
            String trc = AgentBridge.get(context, AgentBridge.PAD_TOP_ROW_CURSOR, "1");
            if (trc == null || trc.isEmpty()) trc = "1";
            AgentBridge.put(context, AgentBridge.PAD_TOP_ROW_CURSOR, trc);
            String tro = AgentBridge.get(context, AgentBridge.PAD_TOP_ROW_ONLY, "0");
            if (tro == null || tro.isEmpty()) tro = "0";
            AgentBridge.put(context, AgentBridge.PAD_TOP_ROW_ONLY, tro);
            String fo = AgentBridge.get(context, AgentBridge.PAD_FOLLOW_ORIENT, null);
            if (fo == null) {
                fo = new TrackpadPrefs(context).getFollowOrient() ? "1" : "0";
            }
            AgentBridge.put(context, AgentBridge.PAD_FOLLOW_ORIENT, fo);
            if ("1".equals(fo)) {
                PadModeController.publishRotation(context);
                PadOrientationService.sync(context);
            }
            String f = AgentBridge.get(context, AgentBridge.FN_MODE, "stock");
            AgentBridge.put(context, AgentBridge.FN_MODE, f);
            // Specials owner (named + optional any-key scan); default Sym
            String cm = AgentBridge.get(context, AgentBridge.CHAR_MOD, "sym");
            if (cm == null || cm.isEmpty()) cm = "sym";
            AgentBridge.put(context, AgentBridge.CHAR_MOD, cm);
            String cms = AgentBridge.get(context, AgentBridge.CHAR_MOD_SCAN, null);
            if (cms != null && !cms.isEmpty()) {
                AgentBridge.put(context, AgentBridge.CHAR_MOD_SCAN, cms);
            }
            // 12.77/13.86: product specials KCM path + unbound side defaults
            if (AgentBridge.get(context, AgentBridge.SPECIALS_METHOD, null) == null) {
                KeyMapPrefs.setSpecialsMethod(context, KeyMapPrefs.SPECIALS_METHOD_KCM);
            }
            try {
                KeyMapPrefs kmm = new KeyMapPrefs(context);
                kmm.migrateSideDefaultsNone();
            } catch (Exception ignored) {}
            // 15.0: rear is display-only always (no opt-in trackpad — dual-cursor residual).
            AgentBridge.put(context, AgentBridge.SUBTOUCH_INHIBIT, "1");
            try {
                android.provider.Settings.Global.putString(
                    context.getContentResolver(), "titan2_subtouch_inhibit", "1");
            } catch (Exception ignored) {}
            try {
                // Collapse stale surface=sub|both left from pre-15.0 cool land.
                String surf = AgentBridge.get(context, "titan2_input_surface", null);
                if (surf != null) {
                    String n = InputSurfaceController.normalize(surf);
                    AgentBridge.put(context, "titan2_input_surface", n);
                    android.provider.Settings.Global.putString(
                        context.getContentResolver(), "titan2_input_surface", n);
                }
            } catch (Exception ignored) {}
            NotifLedController.publishConfig(context);
            // Key remaps + screen-off tick for pad-agent privileged path
            try {
                new KeyMapPrefs(context).publishToAgent(context);
            } catch (Exception ignored) {}
            // Layout plane: clear stuck exclusive keys_pause after reboot/wipe
            // (report 17.05 left host keys dead until manual layout off + reconnect).
            try {
                HostLayoutController.publish(context);
            } catch (Exception ignored) {}
            // Warm Titan2 framework API for HID / third-party binds
            try {
                Intent core = new Intent(context, Titan2CoreService.class);
                context.startService(core);
            } catch (Exception ignored) {}
            // Pad mode QS tile on default panel — retries beat SystemUI late seed
            try {
                PadQsDefaults.ensureDefaultTileWithRetries(context);
            } catch (Exception ignored) {}
            // FB-SEC-1: camera/mic kill-switches + Titan privacy QS tiles
            try {
                SensorPrivacyEnforcer.restore(context);
            } catch (Exception ignored) {}
            try {
                SensorQsDefaults.ensureDefaultTilesWithRetries(context);
            } catch (Exception ignored) {}
            // Right-edge key-press labels (show_key_presses) — off unless Debug opt-in
            try {
                DebugPrefs.ensureDefaultOff(context);
            } catch (Exception ignored) {}
            // Restore selected mode as-is — never start() (that forced Face and
            // painted TitanRearFace over Cube on the rear after reboot).
            if (SubDisplayPrefs.isOn(context)) {
                try { SubDisplayService.restore(context); } catch (Exception ignored) {}
            }
            try { PhoneCalls.apply(app); } catch (Exception ignored) {}
            // Optional VPN-over-hotspot heal (Tweaks); delayed — SoftAP may come later
            try {
                if (VpnHotspotHeal.isEnabled(app)) {
                    VpnHotspotHeal.restoreIfEnabled(app);
                    new Handler(Looper.getMainLooper()).postDelayed(
                        () -> {
                            try { VpnHotspotHeal.restoreIfEnabled(app); } catch (Exception ignored) {}
                        }, 25_000L);
                }
            } catch (Exception ignored) {}
        } catch (Exception e) {
            // ignore
        }
    }

    /** Immediate P0 belt: Key a11y, taskbar residual, QS/setup, HID plane, OS Look. */
    private static void pinAndHeal(Context app) {
        // 12.03 keyboard P0: letter-menu + soft IME must never survive wipe/install
        try { DebugPrefs.ensureDefaultOff(app); } catch (Exception ignored) {}
        try {
            HostLayoutController.bindApp(app);
        } catch (Exception ignored) {}
        // B2 11.82: boot must clear ghost exclusive session prop/plane
        try {
            HostLayoutController.healStaleHidPlane(app);
        } catch (Exception ignored) {}
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class)
                .invoke(null, "sys.titanus2.usb_hid.session", "0");
        } catch (Throwable ignored) {}
        try {
            // 12.54: package-replace / boot — force full belt (a11y listed-but-dead
            // after install is common; 30s throttle left sides dead until unlock).
            AccessServiceHelper.forceUnlockBelt(app);
        } catch (Exception ignored) {}
        try {
            TaskbarPin.pinOff(app);
        } catch (Exception ignored) {}
        try {
            SetupWizardHeal.heal(app);
        } catch (Exception ignored) {}
        // Soft IME with HW keyboard — user toggle in Tweaks; default hide.
        try {
            ImeHwPrefs.applyStored(app);
        } catch (Exception ignored) {}
        // 12.16/12.97: short HW taps must not open accent/language letter menus.
        try { ImeHwPrefs.applyHwTypingPolish(app); } catch (Exception ignored) {}
        // Cube OS seed (monochrome + night) without opening Look after wipe/reflash
        try {
            com.titanus2.controls.ui.ThemePrefs.applyOsPlane(app);
        } catch (Exception ignored) {}
        try {
            HostLayoutController.healStaleHidPlane(app);
        } catch (Exception ignored) {}
        try {
            HostLayoutController.publish(app);
        } catch (Exception ignored) {}
        try {
            KeyMapPrefs km = new KeyMapPrefs(app);
            // 11.93: B1 side chrome heal on boot before plane publish
            km.healSideChromeToFactory();
            km.publishToAgent(app);
        } catch (Exception ignored) {}
        // 11.61: wipe can leave typing-lock plane stuck and QS pad tile missing
        try {
            TypingCursorLock.clear(app);
        } catch (Exception ignored) {}
        try {
            PadQsDefaults.ensureDefaultTile(app);
        } catch (Exception ignored) {}
        try {
            SensorPrivacyEnforcer.restore(app);
        } catch (Exception ignored) {}
        try {
            SensorQsDefaults.ensureDefaultTiles(app);
        } catch (Exception ignored) {}
        // B8 11.93: pad mode gate at boot — start only when wanted, else stop orphan
        try {
            String pm = PadModeController.getMode(app);
            if (PadModeController.MOUSE.equals(pm) || PadModeController.TRACKPAD.equals(pm)) {
                PadModeController.ensureTouchpaddProcess();
            } else {
                PadModeController.stopTouchpaddProcess();
            }
        } catch (Exception ignored) {}
        // 12.10: rootless B2 specials queues + idle plane after lab_rootless wipe
        try { RootlessPlane.seed(app); } catch (Exception ignored) {}
        // 12.13: ghost Specials sticky → multi-letter spam (QA 2026-07-17 screenrec)
        try { HostLayoutController.forceOff(app); } catch (Exception ignored) {}
    }

    /**
     * After wipe, ACCESSIBILITY_ENABLED / service bind can race (SettingsProvider
     * not ready). Re-assert a11y + B1 km plane a few times so side buttons never
     * fall through to stock CAMERA/Home until the human opens Controls.
     */
    private static void scheduleA11yRetries(Context app) {
        Handler h = new Handler(Looper.getMainLooper());
        for (long delay : A11Y_RETRY_MS) {
            h.postDelayed(() -> {
                try {
                    if (AccessServiceHelper.isUserDisabled(app)) return;
                    // Already filtering keys — re-pin taskbar + unstick typing/pad
                    if (AccessServiceHelper.isConnected()) {
                        try { TaskbarPin.pinOff(app); } catch (Exception ignored) {}
                        // Late SettingsProvider: re-push OS Look seed once a11y is live
                        try {
                            com.titanus2.controls.ui.ThemePrefs.applyOsPlane(app);
                        } catch (Exception ignored) {}
                        // 11.62: late waves still clear sticky typing lock after wipe
                        try { TypingCursorLock.clear(app); } catch (Exception ignored) {}
                        try { HostLayoutController.healStaleHidPlane(app); } catch (Exception ignored) {}
                        // 12.46: boot a11y-connected free layout key ownership (dead letters)
                        try { TrackpadAccessService.clearLayoutKeyOwnership(); } catch (Exception ignored) {}
                        // 11.65: boot a11y-connected retries match rebind heal belt
                        try { SetupWizardHeal.heal(app); } catch (Exception ignored) {}
                        try { PadQsDefaults.ensureDefaultTile(app); } catch (Exception ignored) {}
                        try { ImeHwPrefs.applyStored(app); } catch (Exception ignored) {}
                        // 11.94: boot retries also B1 side heal + pad mode gate
                        try {
                            KeyMapPrefs km = new KeyMapPrefs(app);
                            km.healSideChromeToFactory();
                            km.publishToAgent(app);
                        } catch (Exception ignored) {}
                        // 11.98: re-publish host_layout after package-replace so
                        // B1 feel / pad-agent never see empty plane across install.
                        try { HostLayoutController.publish(app); } catch (Exception ignored) {}
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
                    pinAndHeal(app);
                } catch (Exception ignored) {}
            }, delay);
        }
    }
}
