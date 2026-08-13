package com.titanus2.controls;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Headless B1 side-plane probe for agents when the display is asleep.
 * Theme.NoDisplay activities can time out before onCreate finishes under sleep;
 * broadcast path always runs the shared {@link SideB1ProbeActivity#execute} plane.
 * <p>
 * adb:
 * {@code am broadcast -a com.titanus2.controls.action.B1_SIDE_PROBE
 *   -n com.titanus2.controls/.SideB1ProbeReceiver}
 * Then: {@code settings get global titan2_b1_side_probe_result}
 */
public final class SideB1ProbeReceiver extends BroadcastReceiver {
    private static final String TAG = "SideB1Probe";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        try {
            // goAsync so fire-path sleeps do not truncate under ordered-broadcast limits
            final PendingResult pending = goAsync();
            new Thread(() -> {
                try {
                    SideB1ProbeActivity.execute(context.getApplicationContext());
                } catch (Throwable t) {
                    Log.e(TAG, "receiver probe failed", t);
                    SideB1ProbeActivity.writePlane(
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
            }, "SideB1Probe").start();
        } catch (Throwable t) {
            Log.e(TAG, "onReceive failed", t);
        }
    }
}
