package com.titanus2.controls.network;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.util.Log;

/**
 * Re-apply VPN-hotspot heal when tether/SoftAP state changes (user toggle ON).
 */
public final class VpnHotspotReceiver extends BroadcastReceiver {
    private static final String TAG = "VpnHotspotRx";
    /** Legacy but still delivered on current GSI for tether up/down. */
    public static final String ACTION_TETHER =
        "android.net.conn.TETHER_STATE_CHANGED";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (a == null) return;
        if (!ACTION_TETHER.equals(a)
                && !ConnectivityManager.CONNECTIVITY_ACTION.equals(a)
                && !Intent.ACTION_BOOT_COMPLETED.equals(a)) {
            return;
        }
        if (!VpnHotspotHeal.isEnabled(context)) return;
        Log.i(TAG, "re-apply heal action=" + a);
        VpnHotspotHeal.restoreIfEnabled(context);
    }
}
