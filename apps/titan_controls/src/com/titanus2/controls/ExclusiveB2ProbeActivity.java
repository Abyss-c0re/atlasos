package com.titanus2.controls;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.system.Os;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Headless B2 exclusive-plane probe for agents (screen-off safe via receiver).
 * Proves single-owner residual without arming USB HID session (HOLD_FLASH / cable ADB).
 * <p>
 * Agent surface (shell-readable under scoped storage):
 * <ul>
 *   <li>{@code settings get global titan2_b2_exclusive_probe_result} → PASS|FAIL</li>
 *   <li>{@code settings get global titan2_b2_exclusive_probe} → one-line summary</li>
 * </ul>
 * adb activity: {@code am start -n com.titanus2.controls/.ExclusiveB2ProbeActivity}<br>
 * adb broadcast (preferred when screen off):
 * {@code am broadcast -a com.titanus2.controls.action.B2_EXCLUSIVE_PROBE
 *   -n com.titanus2.controls/.ExclusiveB2ProbeReceiver}
 */
public final class ExclusiveB2ProbeActivity extends Activity {
    private static final String TAG = "ExclusiveB2Probe";
    public static final String FILE = "titan2_b2_exclusive_probe";
    public static final String GLOBAL_RESULT = "titan2_b2_exclusive_probe_result";
    public static final String GLOBAL_SUMMARY = "titan2_b2_exclusive_probe";
    public static final String ACTION = "com.titanus2.controls.action.B2_EXCLUSIVE_PROBE";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            execute(this);
        } catch (Throwable t) {
            Log.e(TAG, "probe failed", t);
            writePlane(this, "FAIL", "err=" + t.getClass().getSimpleName(),
                "ts=" + System.currentTimeMillis() + "\nresult=FAIL err="
                    + t.getClass().getSimpleName() + "\n");
        }
        finish();
    }

    /** Shared entry for Activity + BroadcastReceiver (screen-off safe). */
    public static String execute(Context ctx) {
        String report = run(ctx);
        String result = report.contains("result=PASS") ? "PASS" : "FAIL";
        String summary = oneLineSummary(report, result);
        writePlane(ctx, result, summary, report);
        Log.i(TAG, report.replace("\n", " | "));
        return report;
    }

    public static String run(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("ts=").append(System.currentTimeMillis()).append('\n');

        // 13.33: self-heal idle residual before scoring (session=0 sticky pauses
        // / ghost layout) so B2 probe is a product belt, not report-only.
        try {
            HostLayoutController.bindApp(ctx);
            HostLayoutController.healStaleHidPlane(ctx);
            HostLayoutController.healGhostPhoneLayout(ctx);
            sb.append("heal=1\n");
        } catch (Exception e) {
            sb.append("heal=0 err=").append(e.getClass().getSimpleName()).append('\n');
        }

        String session = planeVal(ctx, "titan2_usb_hid_session");
        String grab = planeVal(ctx, "titan2_usb_hid_grab");
        String localIn = planeVal(ctx, "titan2_usb_hid_local_input");
        String keysPause = planeVal(ctx, "titan2_host_layout_keys_pause");
        String twinPause = planeVal(ctx, "titan2_usb_hid_keys_pause");
        String injectPause = planeVal(ctx, "titan2_specials_inject_pause");

        sb.append("session=").append(nz(session))
            .append(" grab=").append(nz(grab))
            .append(" local_input=").append(nz(localIn)).append('\n');
        sb.append("keys_pause=").append(nz(keysPause))
            .append(" twin_pause=").append(nz(twinPause))
            .append(" inject_pause=").append(nz(injectPause)).append('\n');

        boolean sessionOn = isOn(session);
        boolean grabOn = isOn(grab);
        boolean localOn = isOn(localIn);
        boolean pauseOn = isOn(keysPause);
        boolean twinOn = isOn(twinPause);
        boolean injectOn = isOn(injectPause);

        // Idle residual: session off → no sticky pause / local_input (dual-type risk)
        boolean idleOk = evaluateIdleOk(sessionOn, pauseOn, twinOn, injectOn, localOn, sb);
        if (!idleOk && !sessionOn) {
            // Second pass after explicit plane wipe (heal may have missed Global-only)
            try {
                writeOff(ctx, "titan2_host_layout_keys_pause");
                writeOff(ctx, "titan2_usb_hid_keys_pause");
                writeOff(ctx, "titan2_specials_inject_pause");
                writeOff(ctx, "titan2_usb_hid_local_input");
                HostLayoutController.healStaleHidPlane(ctx);
            } catch (Exception ignored) {}
            keysPause = planeVal(ctx, "titan2_host_layout_keys_pause");
            twinPause = planeVal(ctx, "titan2_usb_hid_keys_pause");
            injectPause = planeVal(ctx, "titan2_specials_inject_pause");
            localIn = planeVal(ctx, "titan2_usb_hid_local_input");
            pauseOn = isOn(keysPause);
            twinOn = isOn(twinPause);
            injectOn = isOn(injectPause);
            localOn = isOn(localIn);
            sb.append("keys_pause2=").append(nz(keysPause))
                .append(" twin_pause2=").append(nz(twinPause))
                .append(" inject_pause2=").append(nz(injectPause))
                .append(" local_input2=").append(nz(localIn)).append('\n');
            idleOk = evaluateIdleOk(false, pauseOn, twinOn, injectOn, localOn, sb);
            sb.append("idle_reheal=").append(idleOk ? "1" : "0").append('\n');
        }
        sb.append("idle_ok=").append(idleOk ? "1" : "0").append('\n');

        // Exclusive dual-owner ban: grab + local_input both on = phone+host Specials
        boolean exclusiveOk = !(grabOn && localOn);
        sb.append("exclusive_ok=").append(exclusiveOk ? "1" : "0")
            .append(" grab_on=").append(grabOn ? "1" : "0")
            .append(" local_on=").append(localOn ? "1" : "0").append('\n');

        // 13.53 / hold-Sym product: side long/double factory is none (not layout
        // hold/toggle). Host exclusive Specials own remote_q + a11y inject when
        // sticky layout is on — sides must never be system chrome (B1).
        // Accept none / layout hold|toggle / any non-chrome user bind.
        String sideLong = planeVal(ctx, "titan2_km_side_func_long");
        String sideDouble = planeVal(ctx, "titan2_km_side_func_double");
        String side2Long = planeVal(ctx, "titan2_km_side_func2_long");
        String side2Double = planeVal(ctx, "titan2_km_side_func2_double");
        boolean specialsPlaneOk =
            isSideSlotOk(sideLong) && isSideSlotOk(sideDouble)
                && isSideSlotOk(side2Long) && isSideSlotOk(side2Double);
        sb.append("side_long=").append(nz(sideLong))
            .append(" side_double=").append(nz(sideDouble)).append('\n');
        sb.append("side2_long=").append(nz(side2Long))
            .append(" side2_double=").append(nz(side2Double)).append('\n');
        sb.append("specials_plane_ok=").append(specialsPlaneOk ? "1" : "0").append('\n');

        // a11y TrackpadAccessService — Specials / pad plane owner.
        // 13.81: listed-only false-PASS when service is listed-but-dead
        // (a11y_live=0 / stale after wipe package replace / heat kill).
        // SoT = Secure enabled list + a11y_live body 1 + mtime ≤45s heartbeat.
        String a11y = null;
        try {
            a11y = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        } catch (Exception ignored) {}
        boolean a11yListed = a11y != null && a11y.contains("TrackpadAccessService");
        AgentBridge.AgentLive a11yHb = AgentBridge.a11yLive();
        boolean a11yOk = a11yListed && a11yHb.ok;
        sb.append("a11y_listed=").append(a11yListed ? "1" : "0")
            .append(" a11y_live_age_s=").append(a11yHb.ageSec)
            .append(" a11y_live=").append(a11yHb.lineSnippet)
            .append(" a11y_ok=").append(a11yOk ? "1" : "0").append('\n');
        if (!a11yOk) {
            if (!a11yListed) sb.append("idle_fail=a11y_listed\n");
            else sb.append("idle_fail=a11y_dead\n");
        }

        // 13.34: session-off remote_q residual re-emits on next exclusive Start
        boolean queueOk = true;
        if (!sessionOn) {
            long qBytes = remoteQueueBytes();
            sb.append("remote_q_bytes=").append(qBytes).append('\n');
            if (qBytes > 0) {
                queueOk = false;
                sb.append("idle_fail=remote_q\n");
            }
        } else {
            sb.append("remote_q_bytes=skip\n");
        }
        sb.append("queue_ok=").append(queueOk ? "1" : "0").append('\n');

        // 13.77 B2: plane-only probe false-PASS when map-less / missing bridge
        // (rootless left tmp empty while system had map — or both map-less).
        // SoT: in-bridge specials layer on tmp (product-first) OR system product.
        // 13.78: also gate phone-nav (map-only product residual → exclusive Back
        // dead; service 0.16.9 logs "no phone-nav — exclusive Back residual").
        // Never arm HID / swap — scan binary only.
        final String mapNeedle = "specials layer in-bridge";
        final String navNeedle = "titan2-phone-nav";
        final String tmpBridge = "/data/local/tmp/hid_bridge";
        final String sysBridge = "/system/etc/titan2_usb_hid/hid_bridge";
        boolean mapTmp = binaryContains(tmpBridge, mapNeedle);
        boolean mapSys = binaryContains(sysBridge, mapNeedle);
        boolean navTmp = binaryContains(tmpBridge, navNeedle);
        boolean navSys = binaryContains(sysBridge, navNeedle);
        boolean bridgeMapOk = mapTmp || mapSys;
        boolean bridgeTmpOk = mapTmp; // product-first rootless land
        boolean bridgeNavOk = navTmp || navSys;
        sb.append("bridge_map_tmp=").append(mapTmp ? "1" : "0")
            .append(" bridge_map_sys=").append(mapSys ? "1" : "0")
            .append(" bridge_nav_tmp=").append(navTmp ? "1" : "0")
            .append(" bridge_nav_sys=").append(navSys ? "1" : "0").append('\n');
        sb.append("bridge_map_ok=").append(bridgeMapOk ? "1" : "0")
            .append(" bridge_tmp_ok=").append(bridgeTmpOk ? "1" : "0")
            .append(" bridge_nav_ok=").append(bridgeNavOk ? "1" : "0").append('\n');
        if (!bridgeMapOk) {
            sb.append("idle_fail=bridge_map\n");
        }
        if (!bridgeNavOk) {
            sb.append("idle_fail=bridge_nav\n");
        }

        // 13.79/13.80: pad-agent status age — hung agent left exclusive plane
        // heal / cool park residual while map binary still green (false-PASS).
        // 13.80: liveness = mtime + pad-agent identity (heal/log lines still live).
        AgentBridge.AgentLive hb = AgentBridge.agentLive();
        sb.append("agent_status_age_s=").append(hb.ageSec)
            .append(" agent_ok=").append(hb.ok ? "1" : "0")
            .append(" agent_line=").append(hb.lineSnippet).append('\n');
        if (!hb.ok) {
            sb.append("idle_fail=agent_stale\n");
        }

        // Never arm HID from probe — report only (cable ADB / HOLD_FLASH safe)
        sb.append("armed=0\n");

        boolean pass = idleOk && exclusiveOk && specialsPlaneOk && a11yOk
            && queueOk && bridgeMapOk && bridgeNavOk && hb.ok;
        sb.append("result=").append(pass ? "PASS" : "FAIL");
        if (!idleOk) sb.append(" idle_sticky");
        if (!exclusiveOk) sb.append(" dual_local_input");
        if (!specialsPlaneOk) sb.append(" specials_plane");
        if (!a11yOk) sb.append(" a11y");
        if (!queueOk) sb.append(" remote_q");
        if (!bridgeMapOk) sb.append(" bridge_map");
        if (!bridgeNavOk) sb.append(" bridge_nav");
        if (!hb.ok) sb.append(" agent_stale");
        sb.append('\n');
        return sb.toString();
    }

    /**
     * Scan a small on-device binary for an ASCII needle (B2 in-bridge map SoT).
     * Caps read at 2 MiB — product hid_bridge is ~46 KiB.
     */
    private static boolean binaryContains(String path, String needle) {
        if (path == null || needle == null || needle.isEmpty()) return false;
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) return false;
        byte[] want = needle.getBytes(StandardCharsets.US_ASCII);
        final int max = 2 * 1024 * 1024;
        final int chunk = 8192;
        byte[] buf = new byte[chunk + want.length];
        int carry = 0;
        long total = 0;
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            while (total < max) {
                int n = in.read(buf, carry, chunk);
                if (n <= 0) break;
                int len = carry + n;
                if (indexOf(buf, len, want) >= 0) return true;
                // keep tail for boundary-spanning matches
                if (len >= want.length) {
                    System.arraycopy(buf, len - want.length + 1, buf, 0, want.length - 1);
                    carry = want.length - 1;
                } else {
                    carry = len;
                }
                total += n;
            }
        } catch (Exception ignored) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private static int indexOf(byte[] hay, int hayLen, byte[] needle) {
        if (needle.length == 0 || hayLen < needle.length) return -1;
        outer:
        for (int i = 0; i <= hayLen - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** Bytes waiting in Specials host queues (session-off residual risk). */
    private static long remoteQueueBytes() {
        String[] paths = new String[]{
            "/data/local/tmp/titan2_remote_hid.q",
            "/data/local/tmp/titan2_hid_remote_q",
            "/data/misc/titan2/titan2_remote_hid.q",
            "/data/misc/titan2/titan2_hid_remote_q",
        };
        long total = 0;
        for (String path : paths) {
            try {
                File f = new File(path);
                if (f.isFile()) total += f.length();
            } catch (Exception ignored) {}
        }
        return total;
    }

    private static String planeVal(Context ctx, String name) {
        String v = AgentBridge.get(ctx, name, null);
        if (v != null && !v.isEmpty()) return v;
        return readFirstLine("/data/local/tmp/" + name);
    }

    private static String readFirstLine(String path) {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                    new java.io.FileInputStream(path), StandardCharsets.UTF_8));
            try {
                String line = br.readLine();
                return line == null ? null : line.trim();
            } finally {
                br.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isOn(String s) {
        if (s == null) return false;
        String t = s.trim();
        return "1".equals(t) || "true".equalsIgnoreCase(t) || "on".equalsIgnoreCase(t);
    }

    private static boolean evaluateIdleOk(boolean sessionOn, boolean pauseOn,
            boolean twinOn, boolean injectOn, boolean localOn, StringBuilder sb) {
        if (sessionOn) return true;
        boolean ok = true;
        if (pauseOn) {
            ok = false;
            sb.append("idle_fail=keys_pause\n");
        }
        if (twinOn) {
            ok = false;
            sb.append("idle_fail=twin_pause\n");
        }
        if (injectOn) {
            ok = false;
            sb.append("idle_fail=inject_pause\n");
        }
        if (localOn) {
            ok = false;
            sb.append("idle_fail=local_input\n");
        }
        return ok;
    }

    private static void writeOff(Context ctx, String name) {
        try {
            AgentBridge.put(ctx, name, "0");
        } catch (Exception ignored) {}
        try {
            Settings.Global.putString(ctx.getContentResolver(), name, "0");
        } catch (Exception ignored) {}
        try {
            File f = new File("/data/local/tmp/" + name);
            FileOutputStream fos = new FileOutputStream(f);
            try {
                fos.write('0');
            } finally {
                fos.close();
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
            try {
                Os.chmod(f.getAbsolutePath(), 0666);
            } catch (Throwable ignored) {}
        } catch (Exception ignored) {}
    }

    /**
     * Side slot OK for B2 when not system chrome. Product factory (13.48+) is
     * {@code none}; user may still bind layout hold/toggle or custom actions.
     */
    private static boolean isSideSlotOk(String s) {
        if (s == null || s.isEmpty() || "?".equals(s)) return true;
        String t = s.trim();
        if (KeyMapPrefs.ACT_NONE.equals(t) || KeyMapPrefs.ACT_DEFAULT.equals(t)
                || KeyMapPrefs.ACT_LAYOUT_OFF.equals(t)) {
            return true;
        }
        if (HostLayoutController.isLayoutHoldAction(t)
                || KeyMapPrefs.layoutToggleId(t) != null) {
            return true;
        }
        return !KeyMapPrefs.isSystemChromeAction(t);
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? "?" : s.trim();
    }

    private static String oneLineSummary(String report, String result) {
        return "result=" + result
            + " idle_ok=" + nz(extract(report, "idle_ok="))
            + " exclusive_ok=" + nz(extract(report, "exclusive_ok="))
            + " specials_plane_ok=" + nz(extract(report, "specials_plane_ok="))
            + " a11y_ok=" + nz(extract(report, "a11y_ok="))
            + " a11y_listed=" + nz(extract(report, "a11y_listed="))
            + " a11y_live=" + nz(extract(report, "a11y_live="))
            + " queue_ok=" + nz(extract(report, "queue_ok="))
            + " bridge_map_ok=" + nz(extract(report, "bridge_map_ok="))
            + " bridge_nav_ok=" + nz(extract(report, "bridge_nav_ok="))
            + " bridge_tmp_ok=" + nz(extract(report, "bridge_tmp_ok="))
            + " agent_ok=" + nz(extract(report, "agent_ok="))
            + " session=" + nz(extract(report, "session="))
            + " grab=" + nz(extract(report, "grab="));
    }

    private static String extract(String report, String key) {
        int i = report.indexOf(key);
        if (i < 0) return null;
        int s = i + key.length();
        int e = s;
        while (e < report.length()) {
            char c = report.charAt(e);
            if (c == ' ' || c == '\n' || c == '\r' || c == '|') break;
            e++;
        }
        return report.substring(s, e);
    }

    static void writePlane(Context ctx, String result, String summary, String report) {
        if (result == null) result = "FAIL";
        if (summary == null) summary = "result=" + result;
        if (report == null) report = summary + "\n";
        try {
            Settings.Global.putString(ctx.getContentResolver(), GLOBAL_RESULT, result);
            Settings.Global.putString(ctx.getContentResolver(), GLOBAL_SUMMARY, summary);
        } catch (Exception e) {
            Log.w(TAG, "global plane write failed", e);
        }
        byte[] data = report.getBytes(StandardCharsets.UTF_8);
        try {
            FileOutputStream fos = ctx.openFileOutput(FILE, Context.MODE_PRIVATE);
            fos.write(data);
            fos.close();
        } catch (Exception ignored) {}
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) {
                writeWorld(new File(ext, FILE), data);
            }
        } catch (Exception ignored) {}
        for (String d : new String[]{"/data/local/tmp", "/data/misc/titan2"}) {
            writeWorld(new File(d, FILE), data);
        }
        writeWorld(new File("/data/local/tmp", GLOBAL_RESULT),
            result.getBytes(StandardCharsets.UTF_8));
        writeWorld(new File("/data/local/tmp", GLOBAL_SUMMARY),
            summary.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeWorld(File f, byte[] data) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            FileOutputStream fos = new FileOutputStream(f);
            try {
                fos.write(data);
            } finally {
                fos.close();
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
            try {
                Os.chmod(f.getAbsolutePath(), 0666);
            } catch (Throwable ignored) {}
        } catch (Exception ignored) {}
    }
}
