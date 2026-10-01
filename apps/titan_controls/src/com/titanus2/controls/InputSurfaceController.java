package com.titanus2.controls;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import com.titanus2.controls.subdisplay.SubDisplayPrefs;
import com.titanus2.controls.subdisplay.SubDisplayService;

/**
 * Pointer surface for Titan 2.
 * <p>
 * Keyboard pad is {@code hw}. Rear {@code sub_touch} is a pointer only while
 * sub display mode is HID: the digitizer stays IDC-ignore (not a touchscreen
 * on the main panel) and titan2-touchpadd turns it into the one virtual mouse.
 * Outside HID, legacy sub/both still collapse to hw or none.
 */
public final class InputSurfaceController {
    private static final String TAG = "InputSurface";

    /** pad off */
    public static final String NONE = "none";
    /** main keyboard touchPad only */
    public static final String HW = "hw";
    /** rear sub_touch only — HID mouse, not a main-display touch */
    public static final String SUB = "sub";
    /** rear sub_touch plus keyboard pad, one virtual mouse */
    public static final String BOTH = "both";

    public static final String PLANE_SURFACE = "titan2_input_surface";
    public static final String PLANE_SUB_FLIP_X = "titan2_sub_touch_flip_x";
    public static final String PLANE_SUB_FLIP_Y = "titan2_sub_touch_flip_y";

    private InputSurfaceController() {}

    public static boolean hidMouseLive(Context ctx) {
        try {
            return SubDisplayPrefs.isHidMouse(ctx);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Effective daemon surface while sub display HID mouse is on.
     * Rear + pad shares the keyboard pad only when that pad is already in mouse mode.
     * Trackpad and off leave the pad alone and use the rear digitizer.
     */
    public static String hidSurface(Context ctx) {
        // Rear HID is always extra. Pad mouse joins the same virtual mouse.
        // Trackpad/off leave the keyboard pad to its own mode.
        boolean padMouse = PadModeController.MOUSE.equals(PadModeController.getMode(ctx));
        if (padMouse) return BOTH;
        return SUB;
    }

    /** Collapse legacy rear tokens. HID callers use {@link #normalize(String, boolean)}. */
    public static String normalize(String s) {
        return normalize(s, false);
    }

    public static String normalize(String s, boolean hidMouse) {
        if (s == null) return hidMouse ? SUB : NONE;
        s = s.trim().toLowerCase();
        if (NONE.equals(s) || "off".equals(s) || "0".equals(s)) {
            return hidMouse ? SUB : NONE;
        }
        if (hidMouse) {
            if (BOTH.equals(s) || "all".equals(s) || "dual".equals(s)) return BOTH;
            if (SUB.equals(s) || "rear".equals(s) || "sub_touch".equals(s)) return SUB;
            if (HW.equals(s) || "pad".equals(s) || "trackpad".equals(s) || "mouse".equals(s)) {
                return HW;
            }
            return SUB;
        }
        if (HW.equals(s) || SUB.equals(s) || BOTH.equals(s)
                || "pad".equals(s) || "trackpad".equals(s) || "mouse".equals(s)
                || "rear".equals(s) || "sub_touch".equals(s)
                || "all".equals(s) || "dual".equals(s)) {
            return HW;
        }
        return NONE;
    }

    public static String label(String surface) {
        if (surface == null) return "None";
        String s = surface.trim().toLowerCase();
        if (BOTH.equals(s) || "all".equals(s) || "dual".equals(s)) return "Rear + pad";
        if (SUB.equals(s) || "rear".equals(s) || "sub_touch".equals(s)) return "Rear";
        if (NONE.equals(s) || "off".equals(s) || "0".equals(s)) return "None";
        return "Keyboard pad";
    }

    /** Live pointer surface. HID mouse reads the rear source; otherwise pad mode. */
    public static String getSurface(Context ctx) {
        if (hidMouseLive(ctx)) return hidSurface(ctx);
        return derive(ctx);
    }

    /** Derive from pad mode only — rear contributes only while HID mouse is on. */
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
        return planeTruthy(AgentBridge.get(ctx, PLANE_SUB_FLIP_X, null), true);
    }

    public static void setSubFlipX(Context ctx, boolean flip) {
        AgentBridge.put(ctx, PLANE_SUB_FLIP_X, flip ? "1" : "0");
        apply(ctx);
    }

    public static boolean isSubFlipY(Context ctx) {
        return planeTruthy(AgentBridge.get(ctx, PLANE_SUB_FLIP_Y, null), true);
    }

    public static void setSubFlipY(Context ctx, boolean flip) {
        AgentBridge.put(ctx, PLANE_SUB_FLIP_Y, flip ? "1" : "0");
        apply(ctx);
    }

    /**
     * True when a USB HID session needs the keyboard pad as its mouse.
     * Rear HID mouse already feeds the virtual mouse, so it does not grab the pad.
     */
    public static boolean hidNeedsHwPad(Context ctx) {
        if (ctx == null) return false;
        if (hidMouseLive(ctx)) return false;
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
     * Set which surfaces produce pointer input.
     * While sub display HID mouse is on, the rear source owns this plane
     * and pad mode is left as the user set it.
     */
    public static void setSurface(Context ctx, String surface) {
        if (hidMouseLive(ctx)) {
            apply(ctx);
            Log.i(TAG, "setSurface ignored — sub display HID mouse owns the surface");
            return;
        }
        surface = normalize(surface);
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

        apply(ctx);
        Log.i(TAG, "setSurface=" + surface);
    }

    /** USB HID exclusive: keyboard pad, unless the rear HID mouse is already the source. */
    public static void ensureHwForHid(Context ctx) {
        if (ctx == null) return;
        if (hidMouseLive(ctx)) {
            apply(ctx);
            return;
        }
        setSurface(ctx, HW);
    }

    /** Publish the pointer surface and nudge pad-agent. Does not change pad mode. */
    public static void apply(Context ctx) {
        if (ctx == null) return;
        boolean hid = hidMouseLive(ctx);
        String surface;
        if (hid) {
            surface = hidSurface(ctx);
        } else {
            surface = derive(ctx);
            if (hidNeedsHwPad(ctx) && !HW.equals(surface)) {
                surface = HW;
                Log.i(TAG, "HID needs HW pad — surface forced HW");
            }
        }

        String pad = PadModeController.getMode(ctx);
        boolean padMouse = PadModeController.MOUSE.equals(pad);
        boolean padTrack = PadModeController.TRACKPAD.equals(pad);
        boolean hwLive;
        if (padMouse || padTrack) {
            hwLive = true;
        } else if (!hid && hidNeedsHwPad(ctx)) {
            hwLive = true;
        } else {
            hwLive = false;
        }

        AgentBridge.put(ctx, PLANE_SURFACE, surface);
        AgentBridge.put(ctx, "titan2_hw_pad_inhibit", hwLive ? "0" : "1");

        try {
            Settings.Global.putString(ctx.getContentResolver(), PLANE_SURFACE, surface);
            Settings.Global.putString(ctx.getContentResolver(),
                "titan2_hw_pad_inhibit", hwLive ? "0" : "1");
        } catch (Exception ignored) {}

        SubDisplayService.applySubtouchPolicy(ctx);
        AgentBridge.put(ctx, "titan2_input_surface_apply",
            String.valueOf(System.currentTimeMillis()));
        Log.i(TAG, "apply surface=" + surface + " hid=" + hid + " hwLive=" + hwLive);
    }

    public static void cycle(Context ctx) {
        if (hidMouseLive(ctx)) {
            apply(ctx);
            return;
        }
        setSurface(ctx, NONE.equals(getSurface(ctx)) ? HW : NONE);
    }
}
