package com.titanus2.netfw;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Neighbors from OpenWrt plane (`titan2-openwrt.sh clients`). */
public final class Neigh {
    public final String mac;
    public final String ip;
    public final String dev;
    public final String policy;

    public Neigh(String mac, String ip, String dev, String policy) {
        this.mac = mac;
        this.ip = ip == null ? "" : ip;
        this.dev = dev == null ? "" : dev;
        this.policy = policy == null || policy.isEmpty() ? "allow" : policy;
    }

    public static List<Neigh> load() {
        String blob = OpenWrt.run("clients");
        Set<String> blocked = new LinkedHashSet<String>();
        LinkedHashMap<String, Neigh> byMac = new LinkedHashMap<String, Neigh>();
        if (blob == null) return new ArrayList<Neigh>();
        for (String line : blob.split("\n")) {
            line = line.trim();
            if (line.startsWith("blocked ")) {
                String mac = Fw.normMac(line.substring(8).trim());
                if (mac.length() == 12) blocked.add(mac);
            }
        }
        for (String line : blob.split("\n")) {
            line = line.trim();
            if (!line.startsWith("client ")) continue;
            Parsed p = parseClient(line.substring(7).trim());
            if (p == null) continue;
            String pol = blocked.contains(p.mac) ? "block" : "allow";
            byMac.put(p.mac, new Neigh(p.mac, p.ip, p.dev, pol));
        }
        for (String mac : blocked) {
            if (byMac.containsKey(mac)) continue;
            byMac.put(mac, new Neigh(mac, "", "", "block"));
        }
        return new ArrayList<Neigh>(byMac.values());
    }

    private static Parsed parseClient(String line) {
        String[] p = line.split("\\s+");
        if (p.length < 1) return null;
        String ip = p[0];
        String dev = "";
        String macRaw = "";
        for (int i = 0; i < p.length; i++) {
            if ("dev".equals(p[i]) && i + 1 < p.length) dev = p[i + 1];
            if ("lladdr".equals(p[i]) && i + 1 < p.length) macRaw = p[i + 1];
        }
        String mac = Fw.normMac(macRaw);
        if (mac.length() != 12 || "000000000000".equals(mac)) return null;
        return new Parsed(mac, ip, dev);
    }

    public String label() {
        String m = Fw.colonMac(mac);
        if (!ip.isEmpty() && !dev.isEmpty()) {
            return String.format(Locale.US, "%s  %s  %s", m, ip, dev);
        }
        if (!ip.isEmpty()) return m + "  " + ip;
        return m;
    }

    private static final class Parsed {
        final String mac;
        final String ip;
        final String dev;
        Parsed(String mac, String ip, String dev) {
            this.mac = mac;
            this.ip = ip;
            this.dev = dev;
        }
    }
}
