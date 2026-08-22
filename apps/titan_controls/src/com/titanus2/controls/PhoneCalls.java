package com.titanus2.controls;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.util.List;

/**
 * Product switch: disable phone calls without deleting the SIM.
 * Data / UICC stay. Incoming is rejected; outgoing voice is aborted
 * except emergency. Re-applied on boot.
 */
public final class PhoneCalls {
    public static final String PREF = "titan2_phone";
    public static final String KEY = "calls_enabled";
    /** Settings.Global + dumpsys: {@code 0} = disabled, {@code 1} = allowed. */
    public static final String GLOBAL = "titan2_phone_calls";

    private static final int USAGE_DEFAULT = 0;
    private static final int USAGE_DATA_CENTRIC = 2;

    private PhoneCalls() {}

    public static boolean isDisabled(Context ctx) {
        if (ctx == null) return false;
        try {
            String g = Settings.Global.getString(ctx.getContentResolver(), GLOBAL);
            if ("0".equals(g)) return true;
            if ("1".equals(g)) return false;
        } catch (Exception ignored) {}
        try {
            return !ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(KEY, true);
        } catch (Exception e) {
            return false;
        }
    }

    /** {@code disabled=true} means the feature "Disable phone calls" is on. */
    public static boolean setDisabled(Context ctx, boolean disabled) {
        if (ctx == null) return false;
        persist(ctx, disabled);
        apply(ctx);
        return true;
    }

    public static void apply(Context ctx) {
        if (ctx == null) return;
        boolean off = isDisabled(ctx);
        List<SimCards.Card> cards = SimCards.list(ctx);
        for (SimCards.Card c : cards) {
            if (c == null || !c.uicc) continue;
            setUsage(ctx, c.subId, off ? USAGE_DATA_CENTRIC : USAGE_DEFAULT);
            setImsVoice(c.subId, !off);
        }
    }

    public static String hubSummary(Context ctx) {
        return isDisabled(ctx) ? "calls Off" : "calls On";
    }

    public static void reject(Context ctx) {
        if (ctx == null || !isDisabled(ctx)) return;
        if (isEmergency(ctx, null)) return;
        try {
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            if (tm != null && Build.VERSION.SDK_INT >= 28) {
                tm.endCall();
                return;
            }
        } catch (Throwable ignored) {}
        try {
            TelephonyManager tel = ctx.getSystemService(TelephonyManager.class);
            if (tel != null) {
                tel.getClass().getMethod("endCall").invoke(tel);
            }
        } catch (Throwable ignored) {}
    }

    public static boolean isEmergency(Context ctx, String number) {
        if (number != null && !number.isEmpty()) {
            try {
                TelephonyManager tel = ctx.getSystemService(TelephonyManager.class);
                if (tel != null) {
                    Object v = tel.getClass().getMethod("isEmergencyNumber", String.class)
                        .invoke(tel, number);
                    if (Boolean.TRUE.equals(v)) return true;
                }
            } catch (Throwable ignored) {}
            try {
                Object v = TelephonyManager.class.getMethod("isEmergencyNumber", String.class)
                    .invoke(null, number);
                if (Boolean.TRUE.equals(v)) return true;
            } catch (Throwable ignored) {}
            try {
                TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
                if (tm != null) {
                    Object v = tm.getClass().getMethod("isEmergencyNumber", String.class)
                        .invoke(tm, number);
                    if (Boolean.TRUE.equals(v)) return true;
                }
            } catch (Throwable ignored) {}
        }
        try {
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            if (tm != null) {
                Object v = tm.getClass().getMethod("isInEmergencyCall").invoke(tm);
                if (Boolean.TRUE.equals(v)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static int defaultVoiceSubId(Context ctx) {
        try {
            int id = SubscriptionManager.getDefaultVoiceSubscriptionId();
            if (id > 0) return id;
        } catch (Throwable ignored) {}
        try {
            String raw = Settings.Global.getString(ctx.getContentResolver(),
                "multi_sim_voice_call");
            if (raw != null && raw.matches("[0-9]+")) {
                int id = Integer.parseInt(raw);
                if (id > 0) return id;
            }
        } catch (Exception ignored) {}
        List<SimCards.Card> cards = SimCards.list(ctx);
        for (SimCards.Card c : cards) {
            if (c != null && c.uicc) return c.subId;
        }
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    private static void persist(Context ctx, boolean disabled) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY, !disabled).apply();
        } catch (Exception ignored) {}
        try {
            Settings.Global.putString(ctx.getContentResolver(), GLOBAL, disabled ? "0" : "1");
        } catch (Exception ignored) {}
    }

    private static void setUsage(Context ctx, int subId, int usage) {
        if (subId <= 0) return;
        try {
            SubscriptionManager sm = ctx.getSystemService(SubscriptionManager.class);
            if (sm == null) return;
            sm.getClass()
                .getMethod("setUsageSetting", int.class, int.class)
                .invoke(sm, Integer.valueOf(subId), Integer.valueOf(usage));
        } catch (Throwable ignored) {}
    }

    private static void setImsVoice(int subId, boolean on) {
        if (subId <= 0) return;
        try {
            Class<?> cls = Class.forName("android.telephony.ims.ImsMmTelManager");
            Object mgr = cls.getMethod("createForSubscriptionId", int.class)
                .invoke(null, Integer.valueOf(subId));
            if (mgr == null) return;
            try {
                cls.getMethod("setAdvancedCallingSettingEnabled", boolean.class)
                    .invoke(mgr, Boolean.valueOf(on));
            } catch (Throwable ignored) {}
            try {
                cls.getMethod("setVoWiFiSettingEnabled", boolean.class)
                    .invoke(mgr, Boolean.valueOf(on));
            } catch (Throwable ignored) {}
            // Cross-SIM calling sends MO to the other tray. Empty T-Mobile
            // leftover → DIALING / ImsCallSession UNINITIALIZED until timeout.
            try {
                cls.getMethod("setCrossSimCallingEnabled", boolean.class)
                    .invoke(mgr, Boolean.FALSE);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }
}
