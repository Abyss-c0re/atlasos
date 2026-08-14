package com.titanus2.netfw;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Exec /system/bin/titan2-fw — the only iptables writer. */
public final class Fw {
    private static final String[] BINS = {
        "/system/bin/titan2-fw",
        "/system/bin/titan2-fw.sh",
        "/data/local/tmp/titan2-fw.sh",
    };

    private Fw() {}

    public static String bin() {
        for (String b : BINS) {
            if (new File(b).isFile()) return b;
        }
        return null;
    }

    public static String run(String... args) {
        String b = bin();
        if (b == null) return "error: titan2-fw missing";
        String out = exec(false, b, args);
        if (out.startsWith("error: need root") || out.contains("need root")) {
            String su = exec(true, b, args);
            if (!su.startsWith("error:")) return su;
        }
        return out;
    }

    public static Map<String, String> kv(String blob) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (blob == null) return m;
        for (String line : blob.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            if (line.startsWith("#")) continue;
            m.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return m;
    }

    public static boolean enabled() {
        String v = kv(run("status")).get("enabled");
        return "on".equals(v) || "1".equals(v) || "true".equals(v);
    }

    /** Desire rows: mac → "policy extra". */
    public static Map<String, String> clientDesire() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        String blob = run("client-list");
        if (blob == null) return m;
        for (String line : blob.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\s+");
            if (p.length < 2) continue;
            m.put(normMac(p[0]), p.length > 2 ? p[1] + " " + p[2] : p[1]);
        }
        return m;
    }

    public static String normMac(String mac) {
        if (mac == null) return "";
        String s = mac.toLowerCase(Locale.US).replace(":", "").replace("-", "").replace(" ", "");
        return s;
    }

    public static String colonMac(String raw) {
        String s = normMac(raw);
        if (s.length() != 12) return raw == null ? "" : raw;
        StringBuilder b = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) b.append(':');
            b.append(s, i, i + 2);
        }
        return b.toString();
    }

    static String exec(boolean su, String bin, String... args) {
        try {
            List<String> cmd = new ArrayList<String>();
            if (su) {
                StringBuilder sh = new StringBuilder(bin);
                if (args != null) {
                    for (String a : args) {
                        sh.append(' ').append(quote(a));
                    }
                }
                cmd.add("su");
                cmd.add("-c");
                cmd.add(sh.toString());
            } else {
                cmd.add(bin);
                if (args != null) {
                    for (String a : args) cmd.add(a);
                }
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (out.length() > 0) out.append('\n');
                out.append(line);
            }
            p.waitFor();
            return out.toString();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private static String quote(String a) {
        if (a == null) return "''";
        if (a.matches("[A-Za-z0-9._:/=+-]+")) return a;
        return "'" + a.replace("'", "'\\''") + "'";
    }
}
