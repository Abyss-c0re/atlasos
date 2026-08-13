package com.android.fmradio;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Process;
import android.util.Log;

/**
 * FM software path captures RADIO_TUNER via the same HAL mic-mute path.
 * Mic privacy / denied RECORD_AUDIO / mic mute → digital silence (no hiss).
 */
public final class MicCaptureGate {
    private static final String TAG = "TitanFm";

    public enum State {
        OK,
        PERMISSION_DENIED,
        PRIVACY_ON,
        MIC_MUTED,
        APP_OPS_DENIED
    }

    private MicCaptureGate() {}

    public static State check(Context ctx) {
        Context c = ctx.getApplicationContext();
        if (c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return State.PERMISSION_DENIED;
        }
        if (isMicPrivacyOn(c)) {
            return State.PRIVACY_ON;
        }
        if (isAppOpsDenied(c)) {
            return State.APP_OPS_DENIED;
        }
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && am.isMicrophoneMute()) {
                return State.MIC_MUTED;
            }
        } catch (Throwable ignored) {
        }
        return State.OK;
    }

    public static boolean isBlocked(Context ctx) {
        return check(ctx) != State.OK;
    }

    /** Short status line for the UI. */
    public static String message(State s) {
        switch (s) {
            case PERMISSION_DENIED:
                return "Mic permission off — allow mic for FM Radio";
            case PRIVACY_ON:
                return "Mic privacy ON — turn off mictoggle (QS) for FM audio";
            case MIC_MUTED:
                return "Mic muted — unmute mic for FM audio";
            case APP_OPS_DENIED:
                return "Mic blocked (AppOps) — allow mic for FM Radio";
            default:
                return "Mic path OK";
        }
    }

    public static String message(Context ctx) {
        return message(check(ctx));
    }

    /**
     * SensorPrivacyManager APIs vary by platform jar; use reflection so we
     * compile against older android.jar and still run on API 31+.
     */
    private static boolean isMicPrivacyOn(Context c) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            Object spm = c.getSystemService("sensor_privacy");
            if (spm == null) {
                Class<?> spmClass = Class.forName("android.hardware.SensorPrivacyManager");
                spm = c.getSystemService(spmClass);
            }
            if (spm == null) return false;
            // Sensors.MICROPHONE == 1
            int mic = 1;
            try {
                Class<?> sensors = Class.forName("android.hardware.SensorPrivacyManager$Sensors");
                mic = (int) sensors.getField("MICROPHONE").get(null);
            } catch (Throwable ignored) {
            }
            try {
                return (Boolean) spm.getClass()
                        .getMethod("isSensorPrivacyEnabled", int.class)
                        .invoke(spm, mic);
            } catch (NoSuchMethodException e) {
                // Some builds: isSensorPrivacyEnabled(int toggleType, int sensor)
                return (Boolean) spm.getClass()
                        .getMethod("isSensorPrivacyEnabled", int.class, int.class)
                        .invoke(spm, /*TOGGLE_SOFTWARE*/ 1, mic);
            }
        } catch (Throwable t) {
            Log.w(TAG, "isMicPrivacyOn", t);
            return false;
        }
    }

    private static boolean isAppOpsDenied(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode;
            if (Build.VERSION.SDK_INT >= 29) {
                mode = ops.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_RECORD_AUDIO,
                        Process.myUid(),
                        c.getPackageName());
            } else {
                mode = ops.checkOpNoThrow(
                        AppOpsManager.OPSTR_RECORD_AUDIO,
                        Process.myUid(),
                        c.getPackageName());
            }
            return mode == AppOpsManager.MODE_IGNORED
                    || mode == AppOpsManager.MODE_ERRORED;
        } catch (Throwable t) {
            return false;
        }
    }
}
