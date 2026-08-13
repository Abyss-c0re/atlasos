package com.titanus2.controls;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Headless B2 exclusive-plane probe for agents when the display is asleep.
 * Broadcast path always runs shared {@link ExclusiveB2ProbeActivity#execute}.
 * <p>
 * adb:
 * {@code am broadcast -a com.titanus2.controls.action.B2_EXCLUSIVE_PROBE
 *   -n com.titanus2.controls/.ExclusiveB2ProbeReceiver}
 * Then: {@code settings get global titan2_b2_exclusive_probe_result}
 */
public final class ExclusiveB2ProbeReceiver extends BroadcastReceiver {
    private static final String TAG = "ExclusiveB2Probe";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        try {
            final PendingResult pending = goAsync();
            new Thread(() -> {
                try {
                    ExclusiveB2ProbeActivity.execute(context.getApplicationContext());
                } catch (Throwable t) {
                    Log.e(TAG, "receiver probe failed", t);
                    ExclusiveB2ProbeActivity.writePlane(
                        context.getApplicationContext(),
                        "FAIL",
                        "result=FAIL err=" + t.getClass().getSimpleName(),
                        "ts=" + System.currentTimeMillis() + "\nresult=FAIL err="
                            + t.getClass().getSimpleName() + "\n");
                } finally {
                    try {
                        pending.finish();
                    } catch (Exception ignored) {}
                }
            }, "ExclusiveB2Probe").start();
        } catch (Throwable t) {
            Log.e(TAG, "onReceive failed", t);
        }
    }
}
