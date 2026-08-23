package com.titanus2.controls;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;

/**
 * Settings → SIMs → Calls is the only voice pin.
 * Detect broken IMS. Request heal. Never write {@code multi_sim_voice_call}.
 */
public final class ImsCalls {
    private ImsCalls() {}

    public static final String BIND_1 = "1";
    public static final String BIND_2 = "2";
    public static final String BIND_BOTH = "both";

    public static final class Detect {
        public int callsSub = -1;
        public int callsSlot = -1;
        public int wantSw = -1;
        public String vendorSw = "";
        public String titan2Sw = "";
        public String radioSw = "";
        public String capSw = "";
        public boolean split;
        public boolean callsOnIms;
        public boolean binder;
        public boolean mtk;
        public boolean imsReg;
        public boolean mmtelVoice;
        public boolean planeBinder = true;
        public boolean planeMtk = true;
        public boolean planeForce = true;
        public String bindSlots = BIND_BOTH;
        public boolean ok;
        public String verdict = "";

        public String line() {
            String tray = callsSlot >= 0 ? ("SIM " + (callsSlot + 1)) : "none";
            return "Calls=" + tray
                + " bind=" + bindLabel(bindSlots)
                + " sub=" + (callsSub > 0 ? callsSub : "—")
                + " sw=" + vendorSw
                + (split ? " SPLIT" : "")
                + " ims=" + (callsOnIms ? "1" : "0")
                + " binder=" + (binder ? "1" : "0")
                + " mmtel=" + (mmtelVoice ? "voice" : "no")
                + " " + verdict;
        }
    }

    public static int settingsCallsSubId(Context ctx) {
        try {
            String raw = Settings.Global.getString(ctx.getContentResolver(),
                "multi_sim_voice_call");
            if (raw != null && raw.matches("[1-9][0-9]*")) {
                return Integer.parseInt(raw);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    public static int slotForSub(Context ctx, int subId) {
        if (ctx == null || subId <= 0) return -1;
        for (SimCards.Card c : SimCards.list(ctx)) {
            if (c != null && c.subId == subId && (c.slot == 0 || c.slot == 1)) return c.slot;
        }
        return -1;
    }

    public static int wantSimswitch(Context ctx) {
        int slot = slotForSub(ctx, settingsCallsSubId(ctx));
        if (slot != 0 && slot != 1) return -1;
        return slot + 1;
    }

    public static boolean planeOn(Context ctx, String key) {
        String v = AgentBridge.get(ctx, key, "1");
        return v != null && ("1".equals(v) || "true".equalsIgnoreCase(v)
            || "on".equalsIgnoreCase(v));
    }

    public static void setPlane(Context ctx, String key, boolean on) {
        AgentBridge.put(ctx, key, on ? "1" : "0");
    }

    public static String bindSlots(Context ctx) {
        String v = AgentBridge.get(ctx, AgentBridge.IMS_BIND_SLOTS, BIND_BOTH);
        if (v == null) return BIND_BOTH;
        v = v.trim().toLowerCase();
        if (BIND_1.equals(v)) return SimCards.trayPresent(0) ? BIND_1 : BIND_BOTH;
        if (BIND_2.equals(v)) return SimCards.trayPresent(1) ? BIND_2 : BIND_BOTH;
        return BIND_BOTH;
    }

    public static boolean setBindSlots(Context ctx, String slots) {
        String v = BIND_BOTH;
        if (BIND_1.equals(slots) || BIND_2.equals(slots) || BIND_BOTH.equals(slots)) {
            v = slots;
        }
        if (BIND_1.equals(v) && !SimCards.trayPresent(0)) return false;
        if (BIND_2.equals(v) && !SimCards.trayPresent(1)) return false;
        AgentBridge.put(ctx, AgentBridge.IMS_BIND_SLOTS, v);
        AgentBridge.put(ctx, AgentBridge.IMS_ACTION, "rebind");
        return true;
    }

    public static String bindLabel(String slots) {
        if (BIND_1.equals(slots)) return "SIM 1";
        if (BIND_2.equals(slots)) return "SIM 2";
        return "Both";
    }

    public static void requestHeal(Context ctx) {
        AgentBridge.put(ctx, AgentBridge.IMS_ACTION, "heal");
    }

    public static Detect detect(Context ctx) {
        Detect d = new Detect();
        if (ctx == null) {
            d.verdict = "FAIL — no context";
            return d;
        }
        d.callsSub = settingsCallsSubId(ctx);
        d.callsSlot = slotForSub(ctx, d.callsSub);
        d.wantSw = wantSimswitch(ctx);
        d.vendorSw = prop("persist.vendor.radio.simswitch", "");
        d.titan2Sw = prop("persist.radio.titan2_simswitch", "");
        d.radioSw = prop("persist.radio.simswitch", "");
        d.capSw = prop("persist.vendor.radio.c_capability_slot", "");
        d.callsOnIms = isOne(prop("persist.radio.calls.on.ims", ""));
        d.binder = isTrue(prop("persist.sys.phh.allow_binder_thread_on_incoming_calls", ""));
        d.mtk = isTrue(prop("persist.sys.phh.ims.mtk", ""));
        d.planeBinder = planeOn(ctx, AgentBridge.IMS_BINDER);
        d.planeMtk = planeOn(ctx, AgentBridge.IMS_MTK);
        d.planeForce = planeOn(ctx, AgentBridge.IMS_FORCE_VOLTE);
        d.bindSlots = bindSlots(ctx);

        if (d.wantSw > 0) {
            String want = String.valueOf(d.wantSw);
            d.split = !want.equals(d.vendorSw)
                || (!d.titan2Sw.isEmpty() && !want.equals(d.titan2Sw))
                || (!d.capSw.isEmpty() && !want.equals(d.capSw));
        }

        if (d.callsSub > 0) {
            d.imsReg = isImsRegistered(ctx, d.callsSub);
            d.mmtelVoice = imsMmTelVoice(d.callsSub);
        }

        if (d.callsSub <= 0) {
            d.verdict = "FAIL — Settings Calls unset";
            d.ok = false;
        } else if (d.split) {
            d.verdict = "FAIL — Calls tray vs vendor simswitch split";
            d.ok = false;
        } else if (!d.callsOnIms) {
            d.verdict = "FAIL — calls.on.ims=0";
            d.ok = false;
        } else if (d.planeBinder && !d.binder) {
            d.verdict = "FAIL — binder thread off";
            d.ok = false;
        } else {
            d.ok = true;
            d.verdict = d.mmtelVoice || d.imsReg
                ? "OK — Calls tray aligned (no test call)"
                : "OK plane — MMTEL not advertised yet (no test call)";
        }
        return d;
    }

    private static boolean isImsRegistered(Context ctx, int subId) {
        try {
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            if (tm != null && Build.VERSION.SDK_INT >= 24) {
                tm = tm.createForSubscriptionId(subId);
            }
            if (tm == null) return false;
            Object v = tm.getClass().getMethod("isImsRegistered").invoke(tm);
            return Boolean.TRUE.equals(v);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean imsMmTelVoice(int subId) {
        if (subId <= 0) return false;
        try {
            Class<?> cls = Class.forName("android.telephony.ims.ImsMmTelManager");
            Object mgr = cls.getMethod("createForSubscriptionId", int.class)
                .invoke(null, Integer.valueOf(subId));
            if (mgr == null) return false;
            int[] techs = { 1, 3, 2, 0 };
            for (int tech : techs) {
                Object v = mgr.getClass()
                    .getMethod("isAvailable", int.class, int.class)
                    .invoke(mgr, Integer.valueOf(1), Integer.valueOf(tech));
                if (Boolean.TRUE.equals(v)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isOne(String v) {
        return "1".equals(v);
    }

    private static boolean isTrue(String v) {
        if (v == null) return false;
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v);
    }

    private static String prop(String k, String def) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            String v = (String) c.getMethod("get", String.class, String.class)
                .invoke(null, k, def);
            return v != null ? v : def;
        } catch (Exception e) {
            return def;
        }
    }
}
