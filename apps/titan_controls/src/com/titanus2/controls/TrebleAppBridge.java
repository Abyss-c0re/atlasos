package com.titanus2.controls;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * TrebleApp ({@code me.phh.treble.app}) owns GSI IMS/misc/vendor quirk call paths.
 * Titan Controls wraps entry only — do not reimplement those panels here.
 * <p>
 * Cube product: Treble stays on system (priv-app) but is hidden from Settings IA;
 * open {@link #openSettings} from Tweaks.
 */
public final class TrebleAppBridge {
    private static final String TAG = "TitanControls";
    public static final String PKG = "me.phh.treble.app";
    public static final String SETTINGS = "me.phh.treble.app.SettingsActivity";
    public static final String TOP_LEVEL = "me.phh.treble.app.TopLevelSettingsActivity";

    private TrebleAppBridge() {}

    public static boolean isInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Disable Settings system-category tile so only Controls wraps Treble. */
    public static void hideFromSettings(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ComponentName top = new ComponentName(PKG, TOP_LEVEL);
            pm.setComponentEnabledSetting(
                top,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.w(TAG, "hide Treble Settings entry: " + e.getMessage());
        }
    }

    public static boolean openSettings(Context ctx) {
        hideFromSettings(ctx);
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(PKG, SETTINGS));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "open Treble settings: " + e.getMessage());
            return false;
        }
    }
}
