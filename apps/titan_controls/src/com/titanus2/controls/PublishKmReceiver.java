package com.titanus2.controls;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Lab / pad-agent / host scripts: force-publish key maps + host layout plane.
 * <p>
 * {@code adb shell am broadcast -a com.titanus2.controls.action.PUBLISH_KM \
 *   -n com.titanus2.controls/.PublishKmReceiver}
 * <p>
 * Screen-off remaps need a fresh {@code titan2_km_*} plane; a11y may be cold.
 */
public class PublishKmReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.titanus2.controls.action.PUBLISH_KM";
    private static final String TAG = "PublishKm";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        if (!ACTION.equals(intent.getAction())) return;
        final Context app = context.getApplicationContext();
        try {
            // 12.54: install/heal must force full belt (not throttled ensure)
            AccessServiceHelper.forceUnlockBelt(app);
        } catch (Exception e) {
            Log.w(TAG, "ensure a11y: " + e.getMessage());
        }
        // 12.61: after heal, stamp a11y_live if service process is up
        try {
            if (AccessServiceHelper.isConnected()) {
                AgentBridge.put(app, AgentBridge.A11Y_LIVE, "1");
            }
        } catch (Exception ignored) {}
        try {
            TaskbarPin.pinOff(app);
        } catch (Exception e) {
            Log.w(TAG, "taskbar pin: " + e.getMessage());
        }
        try {
            ImeHwPrefs.applyStored(app);
        } catch (Exception e) {
            Log.w(TAG, "ime hw: " + e.getMessage());
        }
        try {
            PadQsDefaults.ensureDefaultTile(app);
        } catch (Exception e) {
            Log.w(TAG, "qs tile: " + e.getMessage());
        }
        // 11.75: install/heal PUBLISH_KM also clears setup-wizard QS death
        try {
            SetupWizardHeal.heal(app);
        } catch (Exception e) {
            Log.w(TAG, "setup heal: " + e.getMessage());
        }
        // Unstick typing cursor pause (install/heal should not leave host mouse frozen)
        try {
            TypingCursorLock.clear(app);
        } catch (Exception e) {
            Log.w(TAG, "typing cursor: " + e.getMessage());
        }
        try {
            KeyMapPrefs km = new KeyMapPrefs(app);
            // 13.28: restore factory side layout binds before plane publish
            // (12.77 unbound left long/double=none → C-SIDE-PUBLISH-WIPE thrash)
            km.migrateSideDefaultsNone();
            // 11.88: B1 side chrome heal before plane publish (wipe / poison)
            km.healSideChromeToFactory();
            km.publishToAgent(app);
        } catch (Exception e) {
            Log.w(TAG, "publishToAgent: " + e.getMessage());
        }
        try {
            HostLayoutController.bindApp(app);
            // 11.76: clear sticky exclusive leftovers before layout publish
            HostLayoutController.healStaleHidPlane(app);
            HostLayoutController.publish(app);
        } catch (Exception e) {
            Log.w(TAG, "host layout publish: " + e.getMessage());
        }
        // B2: exclusive grab must never leave local_input=1 (phone IME pause is
        // share-only). Sticky local_input after Type/share races dual-types Specials.
        try {
            String grab = AgentBridge.get(app, "titan2_usb_hid_grab", "0");
            if ("1".equals(grab) || "true".equalsIgnoreCase(grab)) {
                AgentBridge.put(app, "titan2_usb_hid_local_input", "0");
            }
        } catch (Exception e) {
            Log.w(TAG, "exclusive local_input clear: " + e.getMessage());
        }
        // B8: pad mode mouse/trackpad with dead process after thrash/wipe;
        // 12.44: also stop orphan when pad is off (install/heal PUBLISH_KM).
        try {
            String pm = PadModeController.getMode(app);
            if (PadModeController.MOUSE.equals(pm) || PadModeController.TRACKPAD.equals(pm)) {
                PadModeController.ensureTouchpaddProcess();
            } else {
                PadModeController.stopTouchpaddProcess();
            }
        } catch (Exception e) {
            Log.w(TAG, "touchpadd ensure: " + e.getMessage());
        }
        // 12.44: install/heal also flushes specials queues + layout ownership
        try { RootlessPlane.seed(app); } catch (Exception e) {
            Log.w(TAG, "rootless seed: " + e.getMessage());
        }
        try { TrackpadAccessService.clearLayoutKeyOwnership(); } catch (Exception ignored) {}
        // 12.50: rootless pad-agent re-exec — lab stages /data/local/tmp agent;
        // agent reads CE plane. Nonce forces newest mtime over empty T2 shells.
        //   am broadcast … PUBLISH_KM --es agent_reload 1
        //   am broadcast … PUBLISH_KM --es dev_action reload_agent
        try {
            String da = intent.getStringExtra("dev_action");
            String ar = intent.getStringExtra("agent_reload");
            boolean reload = "1".equals(ar) || "true".equalsIgnoreCase(ar)
                || (da != null && da.contains("reload_agent"));
            if (reload) {
                AgentBridge.put(app, AgentBridge.DEV_ACTION,
                    "reload_agent " + System.currentTimeMillis());
                Log.i(TAG, "queued pad-agent reload_agent");
            } else if (da != null && !da.isEmpty()) {
                AgentBridge.put(app, AgentBridge.DEV_ACTION,
                    da.trim() + " " + System.currentTimeMillis());
            }
        } catch (Exception e) {
            Log.w(TAG, "dev_action: " + e.getMessage());
        }
        Log.i(TAG, "published km + host_layout plane + a11y/taskbar/os look");
    }
}
