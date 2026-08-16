package com.titanus2.controls;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
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
        public Row(String name, boolean ok, String detail) {
            this.name = name;
            this.ok = ok;
            this.detail = detail != null ? detail : "";
        }
    }

    public static final class Report {
        public final List<Row> calls = new ArrayList<Row>();
        public final List<Row> keys = new ArrayList<Row>();
        public final List<Row> host = new ArrayList<Row>();
        public boolean callsOk;
        public boolean keysOk;
        public String callsVerdict = "";
        public String keysVerdict = "";
    }

    private PlaneHealth() {}

    public static Report probe(Context ctx) {
        Report r = new Report();
        probeCalls(ctx, r);
        probeKeys(ctx, r);
        probeHost(r);
        return r;
    }

    private static void probeCalls(Context ctx, Report r) {
        boolean airplane = "1".equals(Settings.Global.getString(
            ctx.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON));
        r.calls.add(new Row("Airplane", !airplane, airplane ? "on" : "off"));

        String simProp = prop("gsm.sim.state", "");
        boolean simLoaded = simProp.contains("LOADED") || simProp.contains("READY");
        r.calls.add(new Row("SIM", simLoaded, simProp.isEmpty() ? "empty" : simProp));

        String binder = prop("persist.sys.phh.allow_binder_thread_on_incoming_calls", "");
        boolean binderOn = "1".equals(binder) || "true".equalsIgnoreCase(binder);
        r.calls.add(new Row("Binder-thread prop", binderOn,
            binder.isEmpty() ? "unset" : binder));

        String imsPath = "";
        try {
            imsPath = ctx.getPackageManager().getApplicationInfo("com.mediatek.ims", 0).sourceDir;
        } catch (Exception ignored) {}
        r.calls.add(new Row("ImsService pkg", !imsPath.isEmpty(),
            imsPath.isEmpty() ? "com.mediatek.ims missing" : imsPath));

        int voice = -1;
        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        try {
            SubscriptionManager sm = ctx.getSystemService(SubscriptionManager.class);
            if (sm != null) {
                List<SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
                if (list != null) {
                    for (SubscriptionInfo si : list) {
                        if (si != null) {
                            subId = si.getSubscriptionId();
                            break;
                        }
                    }
                }
            }
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            if (tm != null && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    && Build.VERSION.SDK_INT >= 24) {
                tm = tm.createForSubscriptionId(subId);
            }
            if (tm != null) {
                ServiceState ss = tm.getServiceState();
                if (ss != null) voice = ss.getState();
            }
        } catch (Throwable t) {
            r.calls.add(new Row("Voice radio", false, "tm: " + t.getClass().getSimpleName()));
        }
        boolean inService = voice == ServiceState.STATE_IN_SERVICE;
        r.calls.add(new Row("Voice radio", inService,
            voiceName(voice) + (subId > 0 ? " sub=" + subId : "")));

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

        String phoneDump = dump("phone", 400_000);
        boolean noIms = phoneDump.contains("NO_IMS_SERVICE_CONFIGURED");
        boolean nullIface = phoneDump.contains("null IInterface");
        boolean bound = phoneDump.contains("isBound=true");
        r.calls.add(new Row("MMTEL feature", !noIms && !nullIface,
            (noIms ? "NO_IMS_SERVICE_CONFIGURED " : "")
                + (nullIface ? "null IInterface " : "")
                + (bound ? "controller-bound" : "controller-unbound")));

        boolean visualOnly = binderOn && inService && simLoaded && (!imsReg || noIms || nullIface);
        r.callsOk = !airplane && simLoaded && inService && imsReg && !noIms && !nullIface;
        if (visualOnly) {
            r.callsVerdict = "FAIL incoming — binder-thread ON, IMS not serving MMTEL";
        } else if (r.callsOk) {
            r.callsVerdict = "OK — radio + IMS registered (no test call)";
        } else {
            r.callsVerdict = "FAIL incoming — radio/IMS not ready";
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

    private static String voiceName(int s) {
        switch (s) {
            case ServiceState.STATE_IN_SERVICE: return "IN_SERVICE";
            case ServiceState.STATE_OUT_OF_SERVICE: return "OUT_OF_SERVICE";
            case ServiceState.STATE_EMERGENCY_ONLY: return "EMERGENCY_ONLY";
            case ServiceState.STATE_POWER_OFF: return "POWER_OFF";
            default: return "unknown(" + s + ")";
        }
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
