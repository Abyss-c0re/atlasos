package com.titanus2.atlas;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Product: Atlas Authentication Agent is an OS service, not a terminal accessory.
 * Starts the FGS on boot so biometrics work for sudo/apt and Remote ADB arm
 * without the user opening Atlas first (Tailscale / Wi‑Fi-off path).
 *
 * <p>Also kicks hybrid ensure — reboot leaves {@code need-fsck} + overlay down
 * while the image is still present; user must not have to adb ensure again.
 */
public class AtlasAuthAgentReceiver extends BroadcastReceiver {
    private static final String TAG = "AtlasAuthAgent";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String a = intent.getAction();
        if (a == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || Intent.ACTION_USER_UNLOCKED.equals(a)) {
            Log.i(TAG, "ensure auth agent action=" + a);
            Context app = context.getApplicationContext();
            AtlasSessionService.ensureAuthAgent(app);
            // Hybrid remount after boot / APK replace (need-fsck recovery).
            // LOCKED_BOOT: skip heavy ensure until USER_UNLOCKED (FBE).
            if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)) {
                HybridEnsure.kickAfterBoot(app);
            }
        }
    }
}
