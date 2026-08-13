package com.titanus2.controls;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import com.titanus2.api.DisplayApi;
import com.titanus2.controls.subdisplay.SubDisplayPrefs;
import com.titanus2.controls.subdisplay.SubDisplayService;

/**
 * Pointer surface for Titan 2 — <b>hardware keyboard pad only</b>.
 * <p>
 * Sub-display-as-trackpad/mouse abandoned 2026-07-21. Constants SUB/BOTH remain
 * for plane compatibility but always collapse to HW or NONE.
 */
public final class InputSurfaceController {
    private static final String TAG = "InputSurface";

    /** pad off */
    public static final String NONE = "none";
    /** main keyboard touchPad only */
    public static final String HW = "hw";
    /** @deprecated collapsed to HW — rear is not a pointer */
    public static final String SUB = "sub";
    /** @deprecated collapsed to HW — rear is not a pointer */
    public static final String BOTH = "both";

    public static final String PLANE_SURFACE = "titan2_input_surface";
    public static final String PLANE_SUB_FLIP_X = "titan2_sub_touch_flip_x";
    public static final String PLANE_SUB_FLIP_Y = "titan2_sub_touch_flip_y";

    private InputSurfaceController() {}

    public static String normalize(String s) {
        if (s == null) return NONE;
        s = s.trim().toLowerCase();
        if (NONE.equals(s) || "off".equals(s) || "0".equals(s)) return NONE;
        // Anything pointer-ish → HW. Legacy sub/both never mean rear cursor.
        if (HW.equals(s) || SUB.equals(s) || BOTH.equals(s)
                || "pad".equals(s) || "trackpad".equals(s) || "mouse".equals(s)
                || "rear".equals(s) || "sub_touch".equals(s)
                || "all".equals(s) || "dual".equals(s)) {
            return HW;
        }
        return NONE;
    }

    public static String label(String surface) {
        return HW.equals(normalize(surface)) ? "Keyboard pad" : "None";
    }

    /** Pad mode owns the pointer; rear never does. */
    public static String getSurface(Context ctx) {
        return derive(ctx);
    }

    /** Derive from pad mode only — rear never contributes pointer. */
    public static String derive(Context ctx) {
        String pad = PadModeController.getMode(ctx);
        if (PadModeController.TRACKPAD.equals(pad)
                || PadModeController.MOUSE.equals(pad)) {
            return HW;
        }
        return NONE;
    }

    private static boolean planeTruthy(String v, boolean defaultOn) {
        if (v == null || v.isEmpty()) return defaultOn;
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v);
    }

    public static boolean isSubFlipX(Context ctx) {
        // Default ON — rear lid often needs invert X for natural cursor.
        return planeTruthy(AgentBridge.get(ctx, PLANE_SUB_FLIP_X, null), true);
    }

    public static void setSubFlipX(Context ctx, boolean flip) {
        AgentBridge.put(ctx, PLANE_SUB_FLIP_X, flip ? "1" : "0");
        apply(ctx);
    }

    public static boolean isSubFlipY(Context ctx) {
        // Default ON — pair with flip X for 180° lid feel; toggle independently.
        return planeTruthy(AgentBridge.get(ctx, PLANE_SUB_FLIP_Y, null), true);
    }

    public static void setSubFlipY(Context ctx, boolean flip) {
        AgentBridge.put(ctx, PLANE_SUB_FLIP_Y, flip ? "1" : "0");
        apply(ctx);
    }

    /**
     * True when USB HID session needs the HW pad (touchpadd → host mouse).
     * Surface "None"/"Rear" must not starve exclusive HID.
     */
    public static boolean hidNeedsHwPad(Context ctx) {
        if (ctx == null) return false;
        try {
            String sess = AgentBridge.get(ctx, "titan2_usb_hid_session", "0");
            if (!"1".equals(sess)) return false;
            String mouse = AgentBridge.get(ctx, "titan2_usb_hid_mouse", "1");
            if ("0".equals(mouse) || "false".equalsIgnoreCase(mouse)
                    || "off".equalsIgnoreCase(mouse)) {
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Set which surfaces produce pointer input. Updates pad mode + rear touch prefs
     * to stay coherent, then publishes plane for pad-agent.
     * HID exclusive always keeps HW pad available for host mouse.
     */
    public static void setSurface(Context ctx, String surface) {
        surface = normalize(surface);
        // Collapse legacy SUB/BOTH: never rear pointer.
        if (SUB.equals(surface) || BOTH.equals(surface)) surface = HW;
        boolean wantHw = HW.equals(surface);

        if (hidNeedsHwPad(ctx) && !wantHw) {
            surface = HW;
            wantHw = true;
            Log.i(TAG, "HID needs HW pad — surface forced HW");
        }

        AgentBridge.put(ctx, PLANE_SURFACE, surface);

        String pad = PadModeController.getMode(ctx);
        if (wantHw) {
            if (!PadModeController.MOUSE.equals(pad)
                    && !PadModeController.TRACKPAD.equals(pad)) {
                String m = hidNeedsHwPad(ctx)
                    ? PadModeController.MOUSE : PadModeController.TRACKPAD;
                PadModeController.setMode(ctx, m);
            } else {
                PadModeController.setMode(ctx, pad);
            }
        } else if (!hidNeedsHwPad(ctx)) {
            if (PadModeController.MOUSE.equals(pad)
                    || PadModeController.TRACKPAD.equals(pad)) {
                PadModeController.setMode(ctx, PadModeController.OFF);
            }
        }

        // Never couple surface → rear touch / USE_TRACKPAD.
        if (SubDisplayPrefs.isOn(ctx)) {
            DisplayApi.setSubUse(ctx, DisplayApi.USE_FACE);
        } else {
            DisplayApi.setSubUse(ctx, DisplayApi.USE_OFF);
        }

        apply(ctx);
        Log.i(TAG, "setSurface=" + surface + " (rear pointer abandoned)");
    }

    /**
     * Call when HID exclusive starts: ensure HW pad is on the surface plane
     * and uninhibited for touchpadd host mouse.
     */
    public static void ensureHwForHid(Context ctx) {
        if (ctx == null) return;
        setSurface(ctx, HW);
    }

    /** Publish inhibit planes (rear always inhibited as pointer) and nudge pad-agent. */
    public static void apply(Context ctx) {
        if (ctx == null) return;
        String surface = getSurface(ctx);
        boolean wantHw = HW.equals(surface);
        if (hidNeedsHwPad(ctx)) {
            wantHw = true;
            surface = HW;
        }

        AgentBridge.put(ctx, PLANE_SURFACE, surface);
        // Rear never pointer — always inhibit sub_touch for pad-agent.
        AgentBridge.put(ctx, AgentBridge.SUBTOUCH_INHIBIT, "1");
        AgentBridge.put(ctx, "titan2_hw_pad_inhibit", wantHw ? "0" : "1");

        try {
            Settings.Global.putString(ctx.getContentResolver(), PLANE_SURFACE, surface);
            Settings.Global.putString(ctx.getContentResolver(),
                "titan2_subtouch_inhibit", "1");
            Settings.Global.putString(ctx.getContentResolver(),
                "titan2_hw_pad_inhibit", wantHw ? "0" : "1");
        } catch (Exception ignored) {}

        SubDisplayService.applySubtouchPolicy(ctx);
        AgentBridge.put(ctx, "titan2_input_surface_apply",
            String.valueOf(System.currentTimeMillis()));
    }

    public static void cycle(Context ctx) {
        setSurface(ctx, NONE.equals(getSurface(ctx)) ? HW : NONE);
    }
}
