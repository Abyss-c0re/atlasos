package com.titanus2.usbhid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.titanus2.api.Titan2ApiContract;
import com.titanus2.api.Titan2Client;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HID ↔ Titan Controls remapper harmony — <b>Controls per-app profile is SoT</b>.
 * <p>
 * On physical session start:
 * <ol>
 *   <li>Ensure Controls profile {@link Titan2ApiContract#HID_HOST_PKG}
 *       ("USB HID host") so Keys UI shows a real app profile.</li>
 *   <li>Seed side-key defaults into that profile once (from legacy
 *       {@link SideKeyHostPrefs} if present).</li>
 *   <li>{@link Titan2Client#silenceKeyRemaps} — phone chrome off; computer pins keep.</li>
 *   <li>Push full profile snapshot as {@link Titan2ApiContract#LAYER_HID_HOST}
 *       (API layer wins over silence). Any key/combo/volume/side/magic that
 *       Controls can map can be set here via API or the Controls Keys app.</li>
 * </ol>
 * Stop pops host + silence layers. Permanent global maps unchanged.
 * <p>
 * <b>FB-HID-1:</b> Back / Recents stay unsilenced; exclusive phone-nav owns them.
 */
public final class HidKeyMapSession {
    private static final String TAG = "HidKeyMapSession";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long REPUBLISH_MS = 400L;
    private static volatile boolean layerPushed;
    private static volatile boolean hostLayerPushed;
    private static Runnable pendingRepublish;

    private HidKeyMapSession() {}

    public static void onSessionStart(Context ctx) {
        try {
            HidControl.ensureSpecialsQueues(ctx);
        } catch (Exception e) {
            Log.w(TAG, "specials queues: " + e.getMessage());
        }
        publishHostLayoutPlane(ctx);
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                Titan2Client api = PadModeClient.api(app);
                bindHidHostProfile(api, app);
                boolean ok = api.silenceKeyRemaps(Titan2ApiContract.LAYER_HID_SESSION);
                layerPushed = ok;
                Log.i(TAG, "silence Controls remaps for HID session ok=" + ok);
                hostLayerPushed = pushHostProfileLayer(api);
                Log.i(TAG, "HID host profile layer ok=" + hostLayerPushed);
            } catch (Exception e) {
                Log.w(TAG, "silence/host profile: " + e.getMessage());
            }
        }, "hid-km-start").start();
        cancelPendingRepublish();
        pendingRepublish = () -> {
            pendingRepublish = null;
            try {
                HidControl.ensureSpecialsQueues(app);
            } catch (Exception ignored) {}
            publishHostLayoutPlane(app);
            new Thread(() -> {
                try {
                    Titan2Client api = PadModeClient.api(app);
                    hostLayerPushed = pushHostProfileLayer(api);
                    Log.i(TAG, "HID host re-push ok=" + hostLayerPushed);
                } catch (Exception e) {
                    Log.w(TAG, "host re-push: " + e.getMessage());
                }
            }, "hid-km-republish").start();
        };
        MAIN.postDelayed(pendingRepublish, REPUBLISH_MS);
    }

    public static void onSessionStop(Context ctx) {
        cancelPendingRepublish();
        layerPushed = false;
        hostLayerPushed = false;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                Titan2Client api = PadModeClient.api(app);
                try {
                    api.popTempKeyMap(Titan2ApiContract.LAYER_HID_HOST);
                } catch (Exception ignored) {}
                // Legacy alias layer id (same string as LAYER_HID_HOST now)
                try {
                    api.popTempKeyMap(Titan2ApiContract.LAYER_HID_SIDE_KEYS);
                } catch (Exception ignored) {}
                boolean ok = api.popTempKeyMap(Titan2ApiContract.LAYER_HID_SESSION);
                Log.i(TAG, "restore Controls remaps after HID session ok=" + ok);
            } catch (Exception e) {
                Log.w(TAG, "restore remaps: " + e.getMessage());
            }
        }, "hid-km-restore").start();
        publishHostLayoutPlane(ctx);
    }

    /**
     * Re-push host profile while session is live (settings / Controls edit).
     * Prefer {@link Titan2Client#refreshHidHostLayer} when available.
     */
    public static void republishSideKeys(Context ctx) {
        republishHostProfile(ctx);
    }

    public static void republishHostProfile(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                Titan2Client api = PadModeClient.api(app);
                hostLayerPushed = pushHostProfileLayer(api);
                Log.i(TAG, "host profile live update ok=" + hostLayerPushed);
            } catch (Exception e) {
                Log.w(TAG, "host live update: " + e.getMessage());
            }
        }, "hid-km-host-live").start();
    }

    /**
     * Set one HID-host remap via API → Controls profile (visible in Keys UI).
     * Re-pushes the live layer when a session is up.
     */
    public static boolean setHostKeyAction(Context ctx, String slot, String action) {
        if (ctx == null || slot == null) return false;
        try {
            Titan2Client api = PadModeClient.api(ctx.getApplicationContext());
            bindHidHostProfile(api, ctx.getApplicationContext());
            boolean ok = api.setKeyAction(slot, action, Titan2ApiContract.HID_HOST_PKG);
            if (ok && (layerPushed || hostLayerPushed)) {
                pushHostProfileLayer(api);
            }
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "setHostKeyAction: " + e.getMessage());
            return false;
        }
    }

    private static void bindHidHostProfile(Titan2Client api, Context app) {
        api.ensureKeymapProfile(
            Titan2ApiContract.HID_HOST_PKG, Titan2ApiContract.HID_HOST_LABEL);
        seedProfileFromLegacySides(api, app);
    }

    /** One-shot: HID host profile inherits Controls / legacy side maps. */
    private static void seedProfileFromLegacySides(Titan2Client api, Context app) {
        Map<String, String> existing = api.getProfileMap(Titan2ApiContract.HID_HOST_PKG);
        String[] slots = {
            Titan2ApiContract.SLOT_SIDE_SHORT,
            Titan2ApiContract.SLOT_SIDE_LONG,
            Titan2ApiContract.SLOT_SIDE_DOUBLE,
            Titan2ApiContract.SLOT_SIDE2_SHORT,
            Titan2ApiContract.SLOT_SIDE2_LONG,
            Titan2ApiContract.SLOT_SIDE2_DOUBLE,
        };
        for (String slot : slots) {
            if (existing.containsKey(slot)) continue;
            String a = SideKeyHostPrefs.get(app, slot);
            if (!isComputerAction(a)) {
                try { a = api.getKeyAction(slot); } catch (Exception ignored) { a = null; }
            }
            if (!isComputerAction(a)) continue;
            api.setKeyAction(slot, a, Titan2ApiContract.HID_HOST_PKG);
        }
    }

    /** Controls computer actions — same pins silence keeps for the guest. */
    private static boolean isComputerAction(String a) {
        if (a == null) return false;
        String t = a.trim();
        if (t.isEmpty() || "default".equals(t) || "none".equals(t) || "-".equals(t))
            return false;
        return t.startsWith("host:") || t.startsWith("mouse:");
    }

    private static boolean pushHostProfileLayer(Titan2Client api) {
        Map<String, String> snap = api.getProfileMap(Titan2ApiContract.HID_HOST_PKG);
        // Always push (even empty) so layer id exists for refresh after first edit
        return api.pushTempKeyMap(Titan2ApiContract.LAYER_HID_HOST, snap);
    }

    private static void cancelPendingRepublish() {
        if (pendingRepublish != null) {
            MAIN.removeCallbacks(pendingRepublish);
            pendingRepublish = null;
        }
    }

    public static void republishHostLayout(Context ctx) {
        publishHostLayoutPlane(ctx);
    }

    private static void publishHostLayoutPlane(Context ctx) {
        if (ctx == null) return;
        try {
            android.content.Intent i = new android.content.Intent(
                "com.titanus2.controls.action.PUBLISH_KM");
            i.setPackage("com.titanus2.controls");
            i.addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND);
            ctx.sendBroadcast(i);
        } catch (Exception e) {
            Log.w(TAG, "PUBLISH_KM failed: " + e.getMessage());
        }
    }

    public static boolean isLayerPushed() {
        return layerPushed;
    }

    public static boolean isHostLayerPushed() {
        return hostLayerPushed;
    }
}
