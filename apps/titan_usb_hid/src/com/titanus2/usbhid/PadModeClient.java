package com.titanus2.usbhid;

import android.content.Context;
import com.titanus2.api.PadModes;
import com.titanus2.api.Titan2ApiContract;
import com.titanus2.api.Titan2Client;

/**
 * Shared pad control plane via Titan2 framework API.
 * Falls back to control-plane files if CoreService is unbound.
 * Modes: off | trackpad | mouse + follow-orientation.
 */
public final class PadModeClient {
    public static final String OFF = Titan2ApiContract.MODE_OFF;
    public static final String TRACKPAD = Titan2ApiContract.MODE_TRACKPAD;
    public static final String MOUSE = Titan2ApiContract.MODE_MOUSE;

    public static final String ACTION_SET = "com.titanus2.controls.action.SET_PAD_MODE";
    public static final String ACTION_GET = "com.titanus2.controls.action.GET_PAD_MODE";
    public static final String EXTRA_MODE = "mode";
    public static final String CONTROLS_PKG = Titan2ApiContract.CONTROLS_PKG;
    public static final String FILE_MODE = Titan2ApiContract.FILE_PAD_MODE;
    public static final String FILE_LEGACY = "titan2_touchpad_enabled";
    public static final String FILE_CLICK = Titan2ApiContract.FILE_PAD_CLICK;
    public static final String FILE_FOLLOW = Titan2ApiContract.FILE_PAD_FOLLOW;
    public static final String FILE_ROTATION = Titan2ApiContract.FILE_PAD_ROTATION;

    private static volatile Titan2Client client;

    private PadModeClient() {}

    /** Obtain (and connect) the shared framework client for this process. */
    public static Titan2Client api(Context ctx) {
        Titan2Client c = client;
        if (c == null) {
            synchronized (PadModeClient.class) {
                if (client == null) {
                    client = new Titan2Client(ctx.getApplicationContext());
                    client.connect();
                }
                c = client;
            }
        }
        return c;
    }

    public static void connect(Context ctx) {
        api(ctx).connect();
    }

    public static String normalize(String mode) {
        return PadModes.normalize(mode);
    }

    public static String get(Context ctx) {
        return api(ctx).getPadMode();
    }

    public static boolean isFollowOrient(Context ctx) {
        return api(ctx).isFollowOrient();
    }

    public static boolean set(Context ctx, String mode) {
        return api(ctx).setPadMode(mode);
    }

    public static boolean setFollowOrient(Context ctx, boolean on) {
        return api(ctx).setFollowOrient(on);
    }

    public static void publishRotation(Context ctx) {
        api(ctx).publishRotation();
    }

    public static long getEpoch(Context ctx) {
        return api(ctx).getPadEpoch();
    }

    public static void requestRegrab(Context ctx) {
        api(ctx).requestPadRegrab();
    }
}
