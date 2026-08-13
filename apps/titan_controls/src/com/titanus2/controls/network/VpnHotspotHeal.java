package com.titanus2.controls.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.titanus2.controls.AgentBridge;
import com.titanus2.controls.ImpulseSnap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Optional VPN-over-hotspot heal (Tailscale / any TUN upstream).
 * <p>
 * When SoftAP tethers with upstream {@code tun*}, Android often fails to bind
 * a DNS proxy on the gateway and BPF/tc cannot attach to TUN — clients get
 * DHCP but no DNS / no internet. Product script
 * {@code /system/bin/titan2-vpn-hotspot.sh} runs dnsmasq DNS-only on the SoftAP
 * gateway, clamps MTU, disables tether HAL offload, and SNAT-belts SoftAP→TUN.
 * <p>
 * Toggle is off by default. Requires root ({@code su}) for apply/stop.
 */
public final class VpnHotspotHeal {
    private static final String TAG = "VpnHotspotHeal";
    private static final String PREFS = "titan2_vpn_hotspot";
    private static final String KEY_ENABLED = "enabled";

    /** Desire file (world-readable) — script + boot honor this. */
    public static final String DESIRE_PATH = "/data/misc/titan2/vpn_hotspot_heal";
    public static final String STATUS_PATH = "/data/local/tmp/titan2-vpn-hotspot/status";

    private static final String[] CLI = {
        "/system/bin/titan2-vpn-hotspot.sh",
        "/data/local/tmp/titan2-vpn-hotspot.sh",
        "/data/adb/modules/titan2_pad_agent/system/bin/titan2-vpn-hotspot.sh",
    };

    private VpnHotspotHeal() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, false);
    }

    /**
     * Persist user choice and apply or stop. UI should not block — work is
     * async via {@link ImpulseSnap#suAsync}.
     */
    public static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply();
        try {
            AgentBridge.put(ctx, "titan2_vpn_hotspot_heal", on ? "1" : "0");
        } catch (Exception ignored) {}
        writeDesire(on ? "1" : "0");
        if (on) {
            applyAsync();
        } else {
            stopAsync();
        }
    }

    /** Boot / tether change: re-apply only if user enabled. */
    public static void restoreIfEnabled(Context ctx) {
        if (!isEnabled(ctx)) return;
        writeDesire("1");
        applyAsync();
    }

    public static void applyAsync() {
        final String bin = resolveCli();
        if (bin == null) {
            Log.w(TAG, "cli missing — push titan2-vpn-hotspot.sh");
            ImpulseSnap.suAsync(inlineApplyShell());
            return;
        }
        ImpulseSnap.suAsync("sh " + bin + " apply >/data/local/tmp/titan2-vpn-hotspot/last.log 2>&1");
    }

    public static void stopAsync() {
        final String bin = resolveCli();
        if (bin == null) {
            ImpulseSnap.suAsync(inlineStopShell());
            return;
        }
        ImpulseSnap.suAsync("sh " + bin + " stop >/data/local/tmp/titan2-vpn-hotspot/last.log 2>&1");
    }

    /** Short status for Tweaks mono line (best-effort, may be empty). */
    public static String statusLine(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(isEnabled(ctx) ? "on" : "off");
        String st = readFile(STATUS_PATH);
        if (st != null && !st.isEmpty()) {
            sb.append(" · ").append(st.trim());
        } else {
            String cli = resolveCli();
            if (cli != null) {
                String out = suCapture("sh " + cli + " status 2>/dev/null | head -2");
                if (out != null && !out.isEmpty()) {
                    sb.append(" · ").append(out.replace('\n', ' ').trim());
                }
            }
        }
        return sb.toString();
    }

    private static void writeDesire(String v) {
        // Prefer AgentBridge plane; also direct for rootless delay
        ImpulseSnap.suAsync(
            "mkdir -p /data/misc/titan2 /data/local/tmp/titan2-vpn-hotspot; "
                + "printf '%s' '" + v + "' >/data/misc/titan2/vpn_hotspot_heal; "
                + "printf '%s' '" + v + "' >/data/local/tmp/titan2_vpn_hotspot_heal; "
                + "chmod 666 /data/misc/titan2/vpn_hotspot_heal "
                + "/data/local/tmp/titan2_vpn_hotspot_heal 2>/dev/null || true");
        try {
            File f = new File("/data/local/tmp/titan2_vpn_hotspot_heal");
            // may fail without write; su path above is SoT
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(v.getBytes(StandardCharsets.UTF_8));
            fos.close();
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
        } catch (Throwable ignored) {}
    }

    private static String resolveCli() {
        for (String p : CLI) {
            if (new File(p).isFile()) return p;
        }
        return null;
    }

    private static String readFile(String path) {
        try {
            File f = new File(path);
            if (!f.isFile()) return null;
            byte[] buf = new byte[(int) Math.min(f.length(), 512)];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(buf);
            in.close();
            if (n <= 0) return null;
            return new String(buf, 0, n, StandardCharsets.UTF_8).trim();
        } catch (Throwable e) {
            return null;
        }
    }

    private static String suCapture(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            if (!p.waitFor(900, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(line.trim());
            }
            br.close();
            return sb.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    /** Fallback when script not on system image yet (tip install). */
    private static String inlineApplyShell() {
        return "settings put global tethering_allow_vpn_upstreams 1; "
            + "settings put secure tethering_allow_vpn_upstreams 1; "
            + "settings put global tether_offload_disabled 1; "
            + "echo 1 >/proc/sys/net/ipv4/ip_forward; "
            + "IFC=; for n in ap_br_ap0 softap0 ap0; do "
            + "  ip -o -4 addr show dev $n 2>/dev/null | grep -q inet && IFC=$n && break; done; "
            + "TUN=; for n in tun0 tun1 wg0; do "
            + "  [ -d /sys/class/net/$n ] && TUN=$n && break; done; "
            + "[ -z \"$IFC\" ] && exit 0; "
            + "GW=$(ip -o -4 addr show dev $IFC | awk '{for(i=1;i<=NF;i++) if($i==\"inet\"){split($(i+1),a,\"/\"); print a[1]; exit}}'); "
            + "MTU=1280; [ -n \"$TUN\" ] && MTU=$(cat /sys/class/net/$TUN/mtu 2>/dev/null); "
            + "ip link set $IFC mtu ${MTU:-1280} 2>/dev/null; "
            + "mkdir -p /data/local/tmp/titan2-vpn-hotspot; "
            + "kill $(cat /data/local/tmp/titan2-vpn-hotspot/dnsmasq.pid 2>/dev/null) 2>/dev/null; "
            + "[ -n \"$GW\" ] && command -v dnsmasq >/dev/null && dnsmasq "
            + "  --pid-file=/data/local/tmp/titan2-vpn-hotspot/dnsmasq.pid "
            + "  --conf-file=/dev/null --interface=$IFC --listen-address=$GW "
            + "  --bind-interfaces --except-interface=lo --no-dhcp-interface=* --port=53 "
            + "  --server=100.100.100.100 --server=1.1.1.1 --server=8.8.8.8 --no-resolv "
            + "  --cache-size=1000 --user=root --group=root "
            + "  --log-facility=/data/local/tmp/titan2-vpn-hotspot/dnsmasq.log; "
            + "true";
    }

    private static String inlineStopShell() {
        return "kill $(cat /data/local/tmp/titan2-vpn-hotspot/dnsmasq.pid 2>/dev/null) 2>/dev/null; "
            + "rm -f /data/local/tmp/titan2-vpn-hotspot/dnsmasq.pid; "
            + "printf '0' >/data/misc/titan2/vpn_hotspot_heal 2>/dev/null; true";
    }
}
