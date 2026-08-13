package com.titanus2.controls.subdisplay;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;

import java.lang.reflect.Method;

/**
 * Bind rear digitizer to display 2 (stock ASSOCIATE_INPUT path without Agui).
 *
 * <p><b>A16 product note:</b> {@code ASSOCIATE_INPUT_DEVICE_TO_DISPLAY} is
 * {@code prot=signature} only (not privileged). Reflection from Titan Controls
 * always fails even as system priv-app. Product SoT is pad-agent
 * {@code 2.67-sub-associate} shell {@code service call input 43}
 * (IInputManager.addUniqueIdAssociationByDescriptor). This class remains a
 * best-effort fallback for platform-signed builds.
 */
public final class SubDisplayInput {
    private static final String TAG = "SubDisplayInput";

    private SubDisplayInput() {}

    /** @return null on success, else short reason */
    public static String associateSubTouchToRear(Context ctx) {
        try {
            InputManager im = (InputManager) ctx.getSystemService(Context.INPUT_SERVICE);
            if (im == null) return "no InputManager";
            Display rear = SubDisplayHelper.findRear(ctx);
            String unique = null;
            int displayId = 2;
            if (rear != null) {
                displayId = rear.getDisplayId();
                try {
                    Method gu = Display.class.getMethod("getUniqueId");
                    Object u = gu.invoke(rear);
                    if (u != null) unique = String.valueOf(u);
                } catch (Exception ignored) {}
            }
            if (unique == null || unique.isEmpty()) {
                unique = "local:4627039422300187651"; // lab Titan 2 rear
            }
            String desc = null;
            int devId = -1;
            for (int id : im.getInputDeviceIds()) {
                InputDevice d = im.getInputDevice(id);
                if (d == null || d.getName() == null) continue;
                if (!"sub_touch".equals(d.getName())) continue;
                desc = d.getDescriptor();
                devId = id;
                break;
            }
            if (desc == null) return "sub_touch not found";

            // Prefer descriptor → display uniqueId (AOSP InputManager)
            if (tryInvoke(im, "addUniqueIdAssociationByDescriptor",
                    new Class<?>[]{String.class, String.class}, desc, unique)) {
                Log.i(TAG, "associated desc→" + unique);
                return null;
            }
            if (tryInvoke(im, "addUniqueIdAssociation",
                    new Class<?>[]{String.class, String.class}, desc, unique)) {
                Log.i(TAG, "associated (legacy) desc→" + unique);
                return null;
            }
            // Port path: display physical port 3 on this device
            if (tryInvoke(im, "addUniqueIdAssociationByPort",
                    new Class<?>[]{String.class, String.class}, "3", "3")) {
                Log.i(TAG, "associated port 3→3");
                return null;
            }
            if (tryInvoke(im, "addPortAssociation",
                    new Class<?>[]{String.class, int.class}, "3", displayId)) {
                Log.i(TAG, "addPortAssociation 3→" + displayId);
                return null;
            }
            // A16 signature-only residual — pad-agent 2.67 owns association via
            // shell service call. Leave plane stamp for agent edge-apply.
            try {
                android.provider.Settings.Global.putString(
                    ctx.getContentResolver(), "titan2_subtouch_assoc", "pending");
            } catch (Exception ignored) {}
            Log.w(TAG, "app associate denied (A16 signature-only); pad-agent 2.67 SoT");
            return "association API denied — agent shell path";
        } catch (Exception e) {
            Log.w(TAG, "associate: " + e.getMessage());
            return e.getMessage();
        }
    }

    public static void clearAssociation(Context ctx) {
        try {
            InputManager im = (InputManager) ctx.getSystemService(Context.INPUT_SERVICE);
            if (im == null) return;
            for (int id : im.getInputDeviceIds()) {
                InputDevice d = im.getInputDevice(id);
                if (d == null || !"sub_touch".equals(d.getName())) continue;
                String desc = d.getDescriptor();
                tryInvoke(im, "removeUniqueIdAssociationByDescriptor",
                    new Class<?>[]{String.class}, desc);
                tryInvoke(im, "removeUniqueIdAssociation",
                    new Class<?>[]{String.class}, desc);
                tryInvoke(im, "removeUniqueIdAssociationByPort",
                    new Class<?>[]{String.class}, "3");
                tryInvoke(im, "removePortAssociation",
                    new Class<?>[]{String.class}, "3");
                break;
            }
        } catch (Exception e) {
            Log.d(TAG, "clearAssociation: " + e.getMessage());
        }
    }

    private static boolean tryInvoke(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method m = target.getClass().getMethod(name, types);
            m.setAccessible(true);
            m.invoke(target, args);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (Exception e) {
            Log.d(TAG, name + ": " + (e.getCause() != null ? e.getCause() : e));
            return false;
        }
    }
}
