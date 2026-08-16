package com.titanus2.controls;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;

/**
 * Fail-closed belt while {@link PhoneCalls} is disabled. Emergency stays.
 */
public class PhoneCallBlockReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || intent.getAction() == null) return;
        if (!PhoneCalls.isDisabled(context)) return;
        String action = intent.getAction();
        if (Intent.ACTION_NEW_OUTGOING_CALL.equals(action)) {
            String number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
            if (PhoneCalls.isEmergency(context, number)) return;
            setResultData(null);
            PhoneCalls.reject(context);
            return;
        }
        if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action)
                || "android.intent.action.PHONE_STATE".equals(action)) {
            String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
            if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
                PhoneCalls.reject(context);
                return;
            }
            if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)
                    && !PhoneCalls.isEmergency(context, number)) {
                PhoneCalls.reject(context);
            }
        }
    }

}
