package com.titanus2.controls;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.media.AudioManager;
import android.provider.Settings;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.accessibility.AccessibilityManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;


/**
 * Live plane facts for incoming calls and HW nav. No test call. No flash.
 */
public final class PlaneHealth {
    public static final class Row {
        public final String name;
        public final boolean ok;
        public final String detail;
        /** OK / FAIL / OFF / INFO */
        public final String mark;
        public Row(String name, boolean ok, String detail) {
            this(name, ok, detail, ok ? "OK" : "FAIL");
        }
        public Row(String name, boolean ok, String detail, String mark) {
            this.name = name;
            this.ok = ok;
            this.detail = detail != null ? detail : "";
            this.mark = mark != null ? mark : (ok ? "OK" : "FAIL");
        }
    }

    public static final class Report {
        public final List<Row> calls = new ArrayList<Row>();
        public final List<Row> keys = new ArrayList<Row>();
        public final List<Row> sims = new ArrayList<Row>();
        public final List<Row> host = new ArrayList<Row>();
        public boolean callsOk;
        public boolean keysOk;
        public boolean simsOk;
        public String callsVerdict = "";
        public String keysVerdict = "";
        public String simsVerdict = "";
    }

    private PlaneHealth() {}

    public static Report probe(Context ctx) {
        Report r = new Report();
        probeCalls(ctx, r);
        probeKeys(ctx, r);
        probeSims(ctx, r);
        probeHost(r);
        return r;
    }

    private static void probeCalls(Context ctx, Report r) {
        boolean disabled = PhoneCalls.isDisabled(ctx);
        r.calls.add(new Row("Disable phone calls", true,
            disabled ? "on — incoming/outgoing voice stopped" : "off",
            disabled ? "OFF" : "INFO"));

        boolean airplane = "1".equals(Settings.Global.getString(
            ctx.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON));
        r.calls.add(new Row("Airplane", !airplane, airplane ? "on" : "off"));

        String simProp = prop("gsm.sim.state", "");
        boolean simLoaded = simProp.contains("LOADED") || simProp.contains("READY");
        r.calls.add(new Row("SIM", simLoaded, simProp.isEmpty() ? "empty" : simProp));

        String binder = prop("persist.sys.phh.allow_binder_thread_on_incoming_calls", "");
        r.calls.add(new Row("Binder-thread prop", true,
            binder.isEmpty() ? "unset (not incoming)" : (binder + " (not incoming)"),
            "INFO"));

        String imsPath = "";
        try {
            imsPath = ctx.getPackageManager().getApplicationInfo("com.mediatek.ims", 0).sourceDir;
        } catch (Exception ignored) {}
        r.calls.add(new Row("ImsService pkg", !imsPath.isEmpty(),
            imsPath.isEmpty() ? "com.mediatek.ims missing" : imsPath));

        int voice = -1;
        int radioTech = -1;
        int subId = PhoneCalls.defaultVoiceSubId(ctx);
        int voiceSlot = -1;
        try {
            for (SimCards.Card c : SimCards.list(ctx)) {
                if (c != null && c.subId == subId) {
                    voiceSlot = c.slot;
                    break;
                }
            }
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            if (tm != null && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    && Build.VERSION.SDK_INT >= 24) {
                tm = tm.createForSubscriptionId(subId);
            }
            if (tm != null) {
                ServiceState ss = tm.getServiceState();
                if (ss != null) {
                    voice = ss.getState();
                    try {
                        radioTech = (Integer) ss.getClass()
                            .getMethod("getRilVoiceRadioTechnology").invoke(ss);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            r.calls.add(new Row("Voice radio", false, "tm: " + t.getClass().getSimpleName()));
        }
        boolean inService = voice == ServiceState.STATE_IN_SERVICE;
        r.calls.add(new Row("Voice radio", inService,
            voiceName(voice)
                + (subId > 0 ? " sub=" + subId : "")
                + (voiceSlot >= 0 ? " slot=" + voiceSlot : "")
                + (radioTech >= 0 ? " rat=" + radioTech : "")));

        boolean imsReg = false;
        String imsDetail = "n/a";
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            try {
                TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
                if (tm != null && Build.VERSION.SDK_INT >= 24) {
                    tm = tm.createForSubscriptionId(subId);
                }
                if (tm != null) {
                    Object v = tm.getClass().getMethod("isImsRegistered").invoke(tm);
                    imsReg = Boolean.TRUE.equals(v);
                    imsDetail = "tm.isImsRegistered=" + imsReg + " sub=" + subId;
                }
            } catch (Throwable t) {
                imsDetail = t.getClass().getSimpleName();
            }
        }
        r.calls.add(new Row("IMS registered", imsReg, imsDetail));

        boolean mmtelVoice = imsMmTelVoice(subId);
        String phoneDump = dump("phone", 400_000);
        String lastConn = lastLine(phoneDump, "addConnection, subId=" + subId + ", type=MMTEL");
        if (lastConn.isEmpty()) lastConn = lastLine(phoneDump, "type=MMTEL, conn=FeatureContainer");
        String lastBinder = lastLine(phoneDump, "addImsFeatureBinder");
        String lastCaps = lastLine(phoneDump,
            (voiceSlot >= 0 ? "[" + voiceSlot + "] notifyFeatureCapabilitiesChanged, type=MMTEL"
                : "notifyFeatureCapabilitiesChanged, type=MMTEL"));
        boolean noIms = phoneDump.contains("NO_IMS_SERVICE_CONFIGURED");
        boolean nullIface = lastBinder.contains("null IInterface");
        boolean capsVoice = lastCaps.contains("VOICE") && !lastCaps.contains("capabilities={ }");
        if (lastConn.contains("VOICE") && !lastConn.contains("EMERGENCY_OVER_MMTEL }")
                && !lastConn.contains("capabilities={ }")) {
            capsVoice = true;
        }
        if (lastConn.contains("capabilities={ }") || lastCaps.contains("capabilities={ }")) {
            capsVoice = false;
        }
        boolean bound = phoneDump.contains("isBound=true");
        boolean emptyCaps = lastConn.contains("capabilities={ }")
            || lastCaps.contains("capabilities={ }");
        boolean mmtelOk = !noIms && !nullIface && (mmtelVoice || capsVoice) && !emptyCaps;
        r.calls.add(new Row("MMTEL voice", mmtelOk,
            (noIms ? "NO_IMS_SERVICE_CONFIGURED " : "")
                + (nullIface ? "last binder=null IInterface " : "")
                + (emptyCaps ? "READY empty caps " : "")
                + (capsVoice ? "VOICE cap " : "no VOICE cap ")
                + (mmtelVoice ? "isAvailable " : "")
                + (bound ? "controller-bound" : "controller-unbound")));
        if (emptyCaps && bound) {
            r.calls.add(new Row("MMTEL theater", false,
                "listener READY / capabilities={} — looks bound, Voice not advertised",
                "HERESY"));
        }

        String sw = prop("persist.vendor.radio.simswitch", "");
        String t2 = prop("persist.radio.titan2_simswitch", "");
        String radioSw = prop("persist.radio.simswitch", "");
        String capSw = prop("persist.vendor.radio.c_capability_slot", "");
        boolean swOk = true;
        String swDetail = "vendor=" + sw;
        if (voiceSlot >= 0) {
            int want = voiceSlot + 1;
            swOk = String.valueOf(want).equals(sw);
            swDetail += " voiceSlot=" + voiceSlot + " want=" + want
                + (swOk ? "" : " (IMS major is the other slot)");
        }
        r.calls.add(new Row("IMS capability slot", swOk, swDetail));

        boolean sotSplit = false;
        if (!t2.isEmpty()) {
            if (!t2.equals(sw)) sotSplit = true;
            if (!radioSw.isEmpty() && !t2.equals(radioSw)) sotSplit = true;
            if (!capSw.isEmpty() && !t2.equals(capSw)) sotSplit = true;
        }
        String sotDetail = "Calls=" + t2 + " vendor=" + sw
            + " radio=" + radioSw + " cap=" + capSw;
        if (sotSplit) {
            r.calls.add(new Row("Calls tray vs vendor", false,
                sotDetail + " — NVRAM reset tray 1 while Settings Calls stayed",
                "HERESY"));
            swOk = false;
        } else {
            r.calls.add(new Row("Calls tray vs vendor", true, sotDetail));
        }

        boolean holdAlive = procCmdContains("titan2-ims-simswitch-hold");
        String holdPid = readFile("/data/local/tmp/titan2_ims_simswitch_hold.pid").trim();
        if (!holdAlive && holdPid.matches("[0-9]+")) {
            holdAlive = new File("/proc/" + holdPid).isDirectory();
        }
        r.calls.add(new Row("simswitch hold", holdAlive,
            holdAlive ? ("pid=" + (holdPid.isEmpty() ? "live" : holdPid))
                : "not running — vendor can drift to tray 1"));

        String regDump = dump("telephony.registry", 200_000);
        boolean phhIms = regDump.contains("[ApnSetting] PHH IMS");
        boolean titanIms = regDump.contains("[ApnSetting] Titan IMS");
        boolean imsLost = lastLine(regDump, "LOST_CONNECTION").toLowerCase().contains("ims");
        if (phhIms && titanIms) {
            r.calls.add(new Row("IMS APN", false,
                "PHH IMS + Titan IMS both present — bearer flap",
                "HERESY"));
        } else if (imsLost) {
            r.calls.add(new Row("IMS APN", false,
                "IMS APN LOST_CONNECTION in registry",
                "HERESY"));
        } else {
            r.calls.add(new Row("IMS APN", true,
                phhIms ? "PHH IMS" : (titanIms ? "Titan IMS" : "no ims APN in registry"),
                phhIms || titanIms ? "OK" : "INFO"));
        }

        String usp = prop("persist.vendor.mtk_usp_operator", "");
        String simNum = prop("gsm.sim.operator.numeric", "");
        String simAlpha = prop("gsm.sim.operator.alpha", "");
        boolean tmoLive = simNum.contains("310240") || simNum.contains("310260");
        boolean uspMismatch = "OP08".equals(usp) && !tmoLive && simNum.contains("246");
        if (uspMismatch) {
            r.calls.add(new Row("USP vs live SIM", false,
                "usp=" + usp + " sim=" + simNum + " " + simAlpha
                    + " — T-Mobile profile on non-TMO tray",
                "HERESY"));
        } else {
            r.calls.add(new Row("USP vs live SIM", true,
                "usp=" + usp + " sim=" + simNum + " " + simAlpha, "INFO"));
        }

        boolean ringAudible = true;
        String ringDetail = "n/a";
        try {
            AudioManager audio = ctx.getSystemService(AudioManager.class);
            if (audio != null) {
                int mode = audio.getRingerMode();
                int vol = audio.getStreamVolume(AudioManager.STREAM_RING);
                ringAudible = mode == AudioManager.RINGER_MODE_NORMAL && vol > 0;
                ringDetail = "mode=" + (mode == AudioManager.RINGER_MODE_NORMAL ? "normal"
                    : mode == AudioManager.RINGER_MODE_VIBRATE ? "vibrate" : "silent")
                    + " ringVol=" + vol;
            }
        } catch (Throwable ignored) {}
        String skipRing = lastLine(dump("telecom", 120_000), "SKIP_RINGING");
        if (!skipRing.isEmpty() && skipRing.contains("Inaudible")) {
            r.calls.add(new Row("Ring audible", false,
                ringDetail + " — last incoming SKIP_RINGING volume=0 (looks like incoming died)",
                "HERESY"));
        } else if (!ringAudible) {
            r.calls.add(new Row("Ring audible", false, ringDetail, "HERESY"));
        } else {
            r.calls.add(new Row("Ring audible", true, ringDetail));
        }

        boolean packetVoice = radioTech == 14 || radioTech == 20; // LTE / NR
        boolean incomingReady = !airplane && simLoaded && inService
            && (mmtelOk || (!packetVoice && inService))
            && swOk && !sotSplit;

        if (disabled) {
            r.callsOk = true;
            r.callsVerdict = "OFF — phone calls disabled";
            r.calls.add(0, new Row("Incoming", true, r.callsVerdict, "OFF"));
            return;
        }
        r.callsOk = incomingReady;
        if (!inService || airplane || !simLoaded) {
            r.callsVerdict = "FAIL incoming — radio not in service";
        } else if (sotSplit) {
            r.callsVerdict = "FAIL incoming — Settings Calls vs vendor simswitch split";
        } else if (!mmtelOk && packetVoice) {
            r.callsVerdict = "FAIL incoming — LTE/NR and MMTEL has no VOICE";
        } else if (!swOk) {
            r.callsVerdict = "FAIL incoming — IMS capability on the other SIM slot";
        } else if (r.callsOk) {
            r.callsVerdict = "OK — voice path present (no test call)";
        } else {
            r.callsVerdict = "FAIL incoming — voice path not ready";
        }
        r.calls.add(0, new Row("Incoming (no test call)", r.callsOk, r.callsVerdict));
    }

    private static void probeKeys(Context ctx, Report r) {
        boolean listed = a11yListed(ctx);
        boolean bound = TrackpadAccessService.isBound();
        r.keys.add(new Row("Keys a11y listed", listed,
            listed ? "TrackpadAccessService" : "not in enabled list"));
        r.keys.add(new Row("Keys a11y bound", bound,
            bound ? "instance live" : "listed-but-dead / crashed"));

        String kl = readFile("/system/usr/keylayout/TitanKey.kl");
        boolean f24 = kl.contains("key 580") && kl.contains("F24");
        boolean back = kl.contains("key 158") && kl.contains("BACK");
        r.keys.add(new Row("TitanKey 580", f24, f24 ? "F24 (a11y Home/Recents)" : "not F24"));
        r.keys.add(new Row("TitanKey 158", back, back ? "BACK" : "missing"));

        String watchPid = readFile("/data/local/tmp/titan2_key_watch.pid").trim();
        boolean watchAlive = false;
        if (watchPid.matches("[0-9]+")) {
            watchAlive = new File("/proc/" + watchPid).isDirectory();
        }
        String watchLog = tail("/data/local/tmp/titan2_key_watch.log", 8);
        boolean watchLoop = watchLog.contains("getevent exited");
        r.keys.add(new Row("key-watch", watchAlive && !watchLoop,
            "pid=" + (watchPid.isEmpty() ? "-" : watchPid)
                + (watchAlive ? " alive" : " dead")
                + (watchLoop ? " getevent-loop" : "")));

        int ge = countProc("getevent");
        r.keys.add(new Row("getevent count", ge <= 2, ge + " live"));

        String input = dump("input", 80_000);
        boolean titanKey = input.contains("TitanKey");
        r.keys.add(new Row("TitanKey input", titanKey,
            titanKey ? "EventHub present" : "not in dumpsys input"));

        r.keysOk = listed && bound && f24 && back && titanKey && !(watchLoop && !bound);
        if (!bound && listed) {
            r.keysVerdict = "FAIL nav — a11y listed but crashed; Home/Back need GLOBAL_ACTION";
        } else if (watchLoop && !bound) {
            r.keysVerdict = "FAIL nav — key-watch getevent loop and a11y dead";
        } else if (r.keysOk) {
            r.keysVerdict = "OK — a11y bound, TitanKey mapped";
        } else {
            r.keysVerdict = "FAIL nav — keyboard plane incomplete";
        }
        r.keys.add(0, new Row("Nav keys", r.keysOk, r.keysVerdict));
    }

    private static void probeSims(Context ctx, Report r) {
        try {
            List<SimCards.Card> cards = SimCards.list(ctx);
            int hidden = 0;
            int shown = 0;
            if (cards.isEmpty()) {
                r.sims.add(new Row("SIMs", false, "no records (Settings and Controls)"));
                r.simsOk = false;
                r.simsVerdict = "FAIL — no SIM records";
                return;
            }
            for (SimCards.Card c : cards) {
                boolean deleted = !c.inSettings && !c.uicc;
                if (deleted) hidden++;
                else shown++;
                r.sims.add(new Row(
                    (c.uicc ? "on " : "off ") + c.name,
                    true,
                    c.fact()));
            }
            r.simsOk = hidden == 0 || shown + hidden == cards.size();
            // Controls lists them; Settings hide is still a fail for OS Settings.
            boolean settingsHid = hidden > 0;
            r.simsOk = !settingsHid;
            r.simsVerdict = settingsHid
                ? ("FAIL Settings hid " + hidden + " — Controls still lists Off")
                : ("OK — " + shown + " SIM(s) listed");
            r.sims.add(0, new Row("Disable ≠ delete", !settingsHid, r.simsVerdict));
        } catch (Throwable t) {
            r.sims.add(new Row("SIMs", false, t.getClass().getSimpleName()));
            r.simsOk = false;
            r.simsVerdict = "FAIL — probe error";
        }
    }

    private static void probeHost(Report r) {
        String persist = prop("persist.sys.hostname", "");
        String live = readFile("/proc/sys/kernel/hostname").trim();
        boolean match = persist.equals(live) && !live.isEmpty();
        r.host.add(new Row("Hostname", match,
            "live=" + live + " persist=" + persist));
        String load = readFile("/proc/loadavg").trim();
        boolean loadOk = true;
        try {
            loadOk = Float.parseFloat(load.split("\\s+")[0]) < 8f;
        } catch (Exception ignored) {}
        r.host.add(new Row("Load", loadOk, load));
        r.host.add(new Row("Display", true, Build.DISPLAY != null ? Build.DISPLAY : ""));
        r.host.add(new Row("Uptime", true,
            (SystemClock.elapsedRealtime() / 1000) + "s"));
    }

    private static boolean a11yListed(Context ctx) {
        try {
            AccessibilityManager am = ctx.getSystemService(AccessibilityManager.class);
            if (am == null || !am.isEnabled()) return false;
            List<AccessibilityServiceInfo> list =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (list == null) return false;
            for (AccessibilityServiceInfo i : list) {
                if (i == null || i.getId() == null) continue;
                if (i.getId().contains("TrackpadAccessService")) return true;
            }
        } catch (Exception ignored) {}
        String raw = Settings.Secure.getString(ctx.getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return raw != null && raw.contains("TrackpadAccessService");
    }

    private static boolean imsMmTelVoice(int subId) {
        if (subId <= 0) return false;
        try {
            Class<?> cls = Class.forName("android.telephony.ims.ImsMmTelManager");
            Object mgr = cls.getMethod("createForSubscriptionId", int.class)
                .invoke(null, Integer.valueOf(subId));
            if (mgr == null) return false;
            // CAPABILITY_TYPE_VOICE = 1, REGISTRATION_TECH_LTE = 1 / NR = 3 / IWLAN = 2
            int[] techs = { 1, 3, 2, 0 };
            for (int tech : techs) {
                try {
                    Object v = mgr.getClass()
                        .getMethod("isAvailable", int.class, int.class)
                        .invoke(mgr, Integer.valueOf(1), Integer.valueOf(tech));
                    if (Boolean.TRUE.equals(v)) return true;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static String lastLine(String hay, String needle) {
        if (hay == null || needle == null || needle.isEmpty()) return "";
        int i = hay.lastIndexOf(needle);
        if (i < 0) return "";
        int start = hay.lastIndexOf('\n', i);
        start = start < 0 ? 0 : start + 1;
        int end = hay.indexOf('\n', i);
        if (end < 0) end = hay.length();
        return hay.substring(start, end);
    }

    private static String voiceName(int s) {
        switch (s) {
            case ServiceState.STATE_IN_SERVICE: return "IN_SERVICE";
            case ServiceState.STATE_OUT_OF_SERVICE: return "OUT_OF_SERVICE";
            case ServiceState.STATE_EMERGENCY_ONLY: return "EMERGENCY_ONLY";
            case ServiceState.STATE_POWER_OFF: return "POWER_OFF";
            default: return "unknown(" + s + ")";
        }
    }

    @SuppressWarnings("unchecked")
    private static List<SubscriptionInfo> invokeSubList(SubscriptionManager sm, String method) {
        try {
            Object v = sm.getClass().getMethod(method).invoke(sm);
            if (v instanceof List) return (List<SubscriptionInfo>) v;
        } catch (Throwable ignored) {}
        return null;
    }

    private static String prop(String k, String def) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            String v = (String) c.getMethod("get", String.class, String.class).invoke(null, k, def);
            return v != null ? v : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static String dump(String svc, int max) {
        Process p = null;
        try {
            p = new ProcessBuilder("dumpsys", svc).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8), 4096);
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) >= 0) {
                if (sb.length() + n > max) {
                    sb.append(buf, 0, Math.min(n, max - sb.length()));
                    break;
                }
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static String readFile(String path) {
        File f = new File(path);
        if (!f.isFile()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 64_000)];
            int n = in.read(b);
            return n <= 0 ? "" : new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String tail(String path, int lines) {
        String s = readFile(path);
        if (s.isEmpty()) return "";
        String[] a = s.split("\n");
        int from = Math.max(0, a.length - lines);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < a.length; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    private static boolean procCmdContains(String needle) {
        if (needle == null || needle.isEmpty()) return false;
        File proc = new File("/proc");
        File[] kids = proc.listFiles();
        if (kids == null) return false;
        for (File k : kids) {
            if (!k.getName().matches("[0-9]+")) continue;
            String cmd = readFile(new File(k, "cmdline").getPath());
            if (cmd != null && cmd.contains(needle)) return true;
        }
        return false;
    }

    private static int countProc(String name) {
        File proc = new File("/proc");
        File[] kids = proc.listFiles();
        if (kids == null) return 0;
        int n = 0;
        for (File k : kids) {
            if (!k.getName().matches("[0-9]+")) continue;
            String cmd = readFile(new File(k, "comm").getPath()).trim();
            if (name.equals(cmd)) n++;
        }
        return n;
    }
}
