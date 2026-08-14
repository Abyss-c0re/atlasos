package com.titanus2.netfw;

import java.io.File;
import java.util.Map;

/** /system/bin/titan2-openwrt.sh — mount LP, LuCI, 192.168.6.1, under/above VPN. */
public final class OpenWrt {
    private static final String[] BINS = {
        "/system/bin/titan2-openwrt.sh",
        "/data/local/tmp/titan2-openwrt.sh",
    };

    private OpenWrt() {}

    public static String bin() {
        for (String b : BINS) {
            if (new File(b).isFile()) return b;
        }
        return null;
    }

    public static String run(String... args) {
        String b = bin();
        if (b == null) return "error: titan2-openwrt.sh missing";
        String out = Fw.exec(false, b, args);
        if (out.startsWith("error:") || out.contains("need root") || out.contains("Permission")) {
            String su = Fw.exec(true, b, args);
            if (!su.startsWith("error:")) return su;
            return su;
        }
        return out;
    }

    public static Map<String, String> status() {
        return Fw.kv(run("status"));
    }

    public static boolean aboveVpn() {
        return "above".equals(status().get("wan"));
    }

    /** Atlas biometric gate once per session before LuCI admin. */
    public static void ensureAuth() {
        File ticket = new File("/data/misc/titan2/openwrt.auth");
        if (ticket.isFile()) return;
        String a = Fw.exec(false, "/system/bin/atlas-auth", "request", "-t", "60",
            "OpenWrt admin");
        if (a.startsWith("error:") || a.contains("need") || a.contains("denied")) {
            a = Fw.exec(true, "/system/bin/atlas-auth", "request", "-t", "60",
                "OpenWrt admin");
        }
        if (a != null && (a.contains("ok") || a.contains("granted") || a.contains("ticket"))) {
            Fw.exec(true, "/system/bin/sh", "-c",
                "mkdir -p /data/misc/titan2; echo 1 > /data/misc/titan2/openwrt.auth");
        }
    }

    public static String bar() {
        Map<String, String> st = status();
        String wan = nz(st.get("wan"), "under");
        String ap = nz(st.get("ap"), "none");
        String luci = nz(st.get("uhttpd"), "down");
        String lp = nz(st.get("lp"), "?");
        return "gw=192.168.6.1  wan=" + wan + "  ap=" + ap + "  luci=" + luci + "  lp=" + lp;
    }

    private static String nz(String s, String d) {
        return s == null || s.isEmpty() ? d : s;
    }
}
