package com.titanus2.controls.notifled;

import android.content.Context;
import com.titanus2.controls.AgentBridge;

/**
 * Prefs → agent control files.
 * Live notif LED: agent + service only while display is off.
 */
public final class NotifLedController {
    private NotifLedController() {}

    /** Push all pattern params + enable flag to pad-agent. */
    public static void publishConfig(Context ctx) {
        boolean en = NotifLedPrefs.isEnabled(ctx);
        AgentBridge.put(ctx, AgentBridge.NOTIF_BLINK_ENABLE, en ? "1" : "0");
        AgentBridge.put(ctx, AgentBridge.NOTIF_MODE, NotifLedPrefs.getMode(ctx));
        AgentBridge.put(ctx, AgentBridge.NOTIF_BLINK_LEVEL, String.valueOf(NotifLedPrefs.getBrightness(ctx)));
        AgentBridge.put(ctx, AgentBridge.NOTIF_PERIOD_MS, String.valueOf(NotifLedPrefs.getPeriodMs(ctx)));
        AgentBridge.put(ctx, AgentBridge.NOTIF_ON_MS, String.valueOf(NotifLedPrefs.getOnMs(ctx)));
        if (!en) {
            AgentBridge.put(ctx, AgentBridge.NOTIF_BLINK, "0");
            AgentBridge.clear(ctx, AgentBridge.NOTIF_PREVIEW_UNTIL);
        }
    }

    public static void setActiveAlerts(Context ctx, boolean hasAlerts) {
        if (!NotifLedPrefs.isEnabled(ctx)) {
            AgentBridge.put(ctx, AgentBridge.NOTIF_BLINK, "0");
            return;
        }
        AgentBridge.put(ctx, AgentBridge.NOTIF_BLINK, hasAlerts ? "1" : "0");
    }

    /** Run live pattern for {@code seconds} via agent (same engine as real notifs). */
    public static void preview(Context ctx, int seconds) {
        if (seconds < 1) seconds = 1;
        if (seconds > 30) seconds = 30;
        publishConfig(ctx);
        long until = System.currentTimeMillis() / 1000L + seconds;
        AgentBridge.put(ctx, AgentBridge.NOTIF_PREVIEW_UNTIL, String.valueOf(until));
        // force active while previewing
        AgentBridge.put(ctx, AgentBridge.NOTIF_BLINK, "1");
    }
}
