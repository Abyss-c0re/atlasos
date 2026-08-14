package com.titanus2.netfw;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Live neighbors from /proc/net/arp + desire file. */
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
        Map<String, String> desire = Fw.clientDesire();
        LinkedHashMap<String, Neigh> byMac = new LinkedHashMap<String, Neigh>();
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/net/arp"));
            String line = r.readLine(); // header
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 6) continue;
                String ip = p[0];
                String flags = p[2];
                String mac = Fw.normMac(p[3]);
                String dev = p[5];
                if (mac.length() != 12 || "000000000000".equals(mac)) continue;
                if ("0x0".equals(flags)) continue;
                String pol = desire.containsKey(mac) ? desire.get(mac).split("\\s+")[0] : "allow";
                byMac.put(mac, new Neigh(mac, ip, dev, pol));
            }
            r.close();
        } catch (Exception ignored) {}
        for (Map.Entry<String, String> e : desire.entrySet()) {
            if (byMac.containsKey(e.getKey())) continue;
            String[] p = e.getValue().split("\\s+");
            byMac.put(e.getKey(), new Neigh(e.getKey(), p.length > 1 ? p[1] : "", "", p[0]));
        }
        return new ArrayList<Neigh>(byMac.values());
    }

    public String label() {
        String m = Fw.colonMac(mac);
        if (!ip.isEmpty() && !dev.isEmpty()) {
            return String.format(Locale.US, "%s  %s  %s", m, ip, dev);
        }
        if (!ip.isEmpty()) return m + "  " + ip;
        return m;
    }
}
