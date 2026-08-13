package com.titanus2.controls;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Cross-app pad mode API for USB HID and other clients.
 *
 * SET:  action com.titanus2.controls.action.SET_PAD_MODE  extra mode=off|trackpad|mouse
 * GET:  action com.titanus2.controls.action.GET_PAD_MODE
 *       replies with com.titanus2.controls.action.PAD_MODE  extra mode=...
 *
 * Same control plane as Settings UI / QS tile — no second source of truth.
 * {@link PadModeController#setMode} already notifies the QS tile.
 */
public class PadModeApiReceiver extends BroadcastReceiver {
    public static final String ACTION_SET = "com.titanus2.controls.action.SET_PAD_MODE";
    public static final String ACTION_GET = "com.titanus2.controls.action.GET_PAD_MODE";
    public static final String ACTION_MODE = PadModeController.ACTION_MODE;
    public static final String EXTRA_MODE = PadModeController.EXTRA_MODE;
    private static final String TAG = "PadModeApi";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String act = intent.getAction();
        if (ACTION_SET.equals(act)) {
            String mode = intent.getStringExtra(EXTRA_MODE);
            if (mode == null) mode = PadModeController.OFF;
            // setMode writes shared plane + requestListeningState + ACTION_MODE
            boolean ok = PadModeController.setMode(context, mode);
            String applied = PadModeController.getMode(context);
            Log.i(TAG, "SET mode=" + applied + " ok=" + ok);
        } else if (ACTION_GET.equals(act)) {
            PadModeController.notifyModeChanged(context, PadModeController.getMode(context));
        }
    }
}
