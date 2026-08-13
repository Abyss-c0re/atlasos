package com.titanus2.controls.fw;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Thin Java panel into the Linux C observe cube.
 * <p>
 * Prefer {@code titan2-fw-observe} (C) → NDJSON. Fallback: {@code titan2-fw observe}.
 * No VpnService. Engines stay in C/shell; this class only spawns and parses.
 */
public final class FwNative {
    private static final String TAG = "FwNative";

    public static final String[] OBSERVE_BINS = {
        "/system/bin/titan2-fw-observe",
        "/data/adb/modules/titan2_fw/system/bin/titan2-fw-observe",
        "/data/local/tmp/titan2-fw-observe",
        "/data/data/com.titanus2.atlas/files/bin/titan2-fw-observe",
    };

    public static final String[] FW_CLIS = {
        "/system/bin/titan2-fw.sh",
        "/system/bin/titan2-fw",
        "/data/local/tmp/titan2-fw.sh",
        "/data/adb/modules/titan2_fw/system/bin/titan2-fw.sh",
        "/data/adb/modules/titan2_fw/system/bin/titan2-fw",
    };

    /** Catalog of system services / binaries that must appear in Firewall settings. */
    public static final String[][] SYSTEM_ENTITIES = {
        // kind, key, label, resolve hint
        {"bin", "nanobot", "Nanobot peer", "nanobot"},
        {"bin", "nanobot.real", "Nanobot engine", "nanobot.real"},
        {"bin", "atlas", "Atlas hybrid", "atlas"},
        {"bin", "grok", "Grok CLI", "grok"},
        {"bin", "adbd", "ADB daemon", "adbd"},
        {"bin", "sshd", "SSH (hybrid)", "sshd"},
        {"bin", "cameraserver", "Camera server", "cameraserver"},
        {"bin", "audioserver", "Audio server", "audioserver"},
        {"svc", "titan2-pad-agent", "Pad agent", "titan2-pad-agent"},
        {"svc", "titan2-usb-hid", "USB HID", "titan2-usb-hid"},
        {"svc", "titan2-kernel-cube", "Kernel cube", "titan2-kernel-cube"},
        {"svc", "titan2-display", "Subdisplay", "titan2-display"},
        {"svc", "titan2-sensor-privacy", "Sensor privacy belt", "titan2-sensor-privacy"},
        {"svc", "titan2-power-wake", "Power wake belt", "titan2-power-wake"},
        {"svc", "camerahalserver", "Camera HAL", "camerahalserver"},
        {"svc", "titan2_volte_rcs_ua", "VoLTE RCS UA", "titan2_volte"},
        {"svc", "titan2_rcs_volte_stack", "RCS VoLTE stack", "titan2_rcs_volte"},
        {"pkg", "com.titanus2.atlas", "Atlas app", "com.titanus2.atlas"},
        {"pkg", "com.titanus2.controls", "Titan Controls", "com.titanus2.controls"},
        {"pkg", "com.titanus2.cubecontact", "Cube Contact", "com.titanus2.cubecontact"},
        {"pkg", "com.titanus2.nanobot", "Nanobot app", "com.titanus2.nanobot"},
        {"pkg", "com.titanus2.usbhid", "USB HID app", "com.titanus2.usbhid"},
        {"pkg", "com.tailscale.ipn", "Tailscale VPN", "com.tailscale.ipn"},
    };

    private FwNative() {}

    public static String findObserveBin() {
        for (String p : OBSERVE_BINS) {
            if (new java.io.File(p).canExecute() || new java.io.File(p).exists()) return p;
        }
        return null;
    }

    public static String findCli() {
        for (String p : FW_CLIS) {
            if (new java.io.File(p).exists()) return p;
        }
        return null;
    }

    public static final class Flow {
        public String proto, dir, src, dst, state, pkg, comm, exe;
        public int sport, dport, uid, pid;
        public long ino;

        public String summary() {
            String who = (pkg != null && !pkg.isEmpty()) ? pkg
                : (comm != null && !comm.isEmpty()) ? comm
                : ("uid " + uid);
            String bin = (exe != null && !exe.isEmpty())
                ? exe.substring(exe.lastIndexOf('/') + 1) : "";
            return dir + "  " + who
                + (bin.isEmpty() ? "" : (" · " + bin))
                + "\n" + src + ":" + sport + "  →  " + dst + ":" + dport
                + "  " + proto + " " + state;
        }
    }

    /**
     * Snapshot live flows via C observe cube (preferred) or shell fallback.
     */
    public static List<Flow> observeOnce(int limit) {
        List<Flow> out = new ArrayList<>();
        String bin = findObserveBin();
        if (bin != null) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    bin, "--json", "--limit", String.valueOf(limit));
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (!line.startsWith("{") || !line.contains("\"src\"")) continue;
                        try {
                            JSONObject o = new JSONObject(line);
                            if (o.has("type")) continue;
                            Flow f = new Flow();
                            f.proto = o.optString("proto", "?");
                            f.dir = o.optString("dir", "?");
                            f.src = o.optString("src", "");
                            f.dst = o.optString("dst", "");
                            f.sport = o.optInt("sport", 0);
                            f.dport = o.optInt("dport", 0);
                            f.state = o.optString("state", "");
                            f.uid = o.optInt("uid", -1);
                            f.pkg = o.optString("pkg", "");
                            f.pid = o.optInt("pid", -1);
                            f.comm = o.optString("comm", "");
                            f.exe = o.optString("exe", "");
                            f.ino = o.optLong("ino", 0);
                            out.add(f);
                        } catch (Exception ignored) {
                        }
                    }
                }
                p.waitFor(15, TimeUnit.SECONDS);
                return out;
            } catch (Exception e) {
                Log.w(TAG, "observe C failed: " + e.getMessage());
            }
        }
        // shell fallback
        String cli = findCli();
        if (cli == null) return out;
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", cli, "observe", String.valueOf(limit));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("#") || line.trim().isEmpty()) continue;
                    Flow f = new Flow();
                    f.proto = "tcp";
                    f.dir = "?";
                    f.comm = line;
                    f.src = "";
                    f.dst = "";
                    out.add(f);
                }
            }
            p.waitFor(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.w(TAG, "observe shell failed: " + e.getMessage());
        }
        return out;
    }
}
