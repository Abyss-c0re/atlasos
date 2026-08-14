package com.titanus2.netfw;

import java.io.File;
import java.util.Map;

/** Wrap /system/bin/titan2-tether.sh — Wi‑Fi / USB / Ethernet. */
public final class Tether {
    private static final String[] BINS = {
        "/system/bin/titan2-tether.sh",
        "/data/local/tmp/titan2-tether.sh",
    };

    private Tether() {}

    public static String bin() {
        for (String b : BINS) {
            if (new File(b).isFile()) return b;
        }
        return null;
    }

    public static String run(String... args) {
        String b = bin();
        if (b == null) return "error: titan2-tether.sh missing";
        String out = Fw.exec(false, b, args);
        if (out.startsWith("error:") || out.contains("need root")) {
            String su = Fw.exec(true, b, args);
            if (!su.startsWith("error:")) return su;
        }
        return out;
    }

    public static Map<String, String> status() {
        return Fw.kv(run("status"));
    }

    public static boolean vpnShareOn() {
        Map<String, String> st = status();
        String d = st.get("desire");
        return "1".equals(d) || "on".equals(d);
    }

    public static String prefix() {
        String p = status().get("prefix");
        if (p != null && !p.isEmpty()) return p;
        String f = Fw.run("prefix");
        if (f != null && f.startsWith("prefix=")) return f.substring(7).trim();
        return "10.191.207.1/24";
    }
}
