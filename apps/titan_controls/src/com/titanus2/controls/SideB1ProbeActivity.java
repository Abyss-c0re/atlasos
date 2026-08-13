package com.titanus2.controls;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.system.Os;
import android.util.Log;
import android.view.InputDevice;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Headless B1 probe: side scans 249/250 must not be mapped to CAMERA / HOME /
 * APP_SWITCH in product keylayouts; pad-agent prefs for sides must be agent-owned;
 * KEY_FIRE path for side long-hold (layout specials/arrows) must work without dual type.
 * <p>
 * Agent surface (shell-readable under scoped storage):
 * <ul>
 *   <li>{@code settings get global titan2_b1_side_probe_result} → PASS|FAIL</li>
 *   <li>{@code settings get global titan2_b1_side_probe} → one-line summary</li>
 *   <li>full report: app external files + CE + best-effort {@code /data/local/tmp}</li>
 * </ul>
 * adb activity: {@code am start -n com.titanus2.controls/.SideB1ProbeActivity}<br>
 * adb broadcast (preferred when screen off):
 * {@code am broadcast -a com.titanus2.controls.action.B1_SIDE_PROBE
 *   -n com.titanus2.controls/.SideB1ProbeReceiver}
 */
public final class SideB1ProbeActivity extends Activity {
    private static final String TAG = "SideB1Probe";
    public static final String FILE = "titan2_b1_side_probe";
    public static final String GLOBAL_RESULT = "titan2_b1_side_probe_result";
    public static final String GLOBAL_SUMMARY = "titan2_b1_side_probe";
    public static final String ACTION = "com.titanus2.controls.action.B1_SIDE_PROBE";

    private static final String[] KL_PATHS = {
        "/system/usr/keylayout/mtk-kpd.kl",
        "/system/usr/keylayout/gpio_key-func.kl",
        "/system/usr/keylayout/ff_key.kl",
        "/system/usr/keylayout/mtk-pmic-keys.kl",
        "/system/phh/unihertz-mtk-kpd.kl",
        "/mnt/phh/keylayout/mtk-kpd.kl",
        "/system/etc/titan2_keylayout/mtk-kpd.kl",
        "/system/etc/titan2_keylayout/mtk-pmic-keys.kl",
    };

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

        int mapped = 0;
        int checked = 0;
        StringBuilder bad = new StringBuilder();
        for (String path : KL_PATHS) {
            File f = new File(path);
            if (!f.isFile()) {
                sb.append("kl_missing=").append(path).append('\n');
                continue;
            }
            checked++;
            int hits = countSideMaps(f);
            sb.append("kl=").append(path).append(" side_maps=").append(hits).append('\n');
            if (hits > 0) {
                mapped += hits;
                if (bad.length() > 0) bad.append(';');
                bad.append(path).append(':').append(hits);
            }
        }
        sb.append("kl_checked=").append(checked).append(" side_maps_total=").append(mapped)
            .append('\n');
        if (bad.length() > 0) sb.append("side_map_files=").append(bad).append('\n');

        // Live input nodes that should own sides (unmapped + agent)
        boolean gpio = hasInputNamed("gpio_key-func");
        boolean ff = hasInputNamed("ff_key");
        boolean pmic = hasInputNamed("mtk-pmic-keys");
        sb.append("nodes gpio=").append(gpio ? "1" : "0")
            .append(" ff_key=").append(ff ? "1" : "0")
            .append(" pmic=").append(pmic ? "1" : "0").append('\n');

        // Agent status + side plane: AgentBridge merges Global + tmp + CE
        String b1st = readFirstLine("/data/local/tmp/titan2_b1_kl_status");
        if (b1st == null) b1st = AgentBridge.get(ctx, "titan2_b1_kl_status", null);
        sb.append("b1_kl_status=").append(b1st == null ? "?" : b1st.trim()).append('\n');
        String sideBottom = planeVal(ctx, "titan2_km_side_func_short");
        String sideTop = planeVal(ctx, "titan2_km_side_func2_short");
        String sideBottomLong = planeVal(ctx, "titan2_km_side_func_long");
        String sideTopLong = planeVal(ctx, "titan2_km_side_func2_long");
        String sideBottomDouble = planeVal(ctx, "titan2_km_side_func_double");
        String sideTopDouble = planeVal(ctx, "titan2_km_side_func2_double");
        sb.append("side_bottom_short=").append(sideBottom == null ? "?" : sideBottom.trim())
            .append(" side_top_short=").append(sideTop == null ? "?" : sideTop.trim())
            .append('\n');
        sb.append("side_bottom_long=")
            .append(sideBottomLong == null ? "?" : sideBottomLong.trim())
            .append(" side_top_long=")
            .append(sideTopLong == null ? "?" : sideTopLong.trim())
            .append('\n');
        sb.append("side_bottom_double=")
            .append(sideBottomDouble == null ? "?" : sideBottomDouble.trim())
            .append(" side_top_double=")
            .append(sideTopDouble == null ? "?" : sideTopDouble.trim())
            .append('\n');

        // pmic dual-emit belt: product kl should exist (missing → Generic path)
        boolean pmicKl = new File("/system/usr/keylayout/mtk-pmic-keys.kl").isFile()
            || new File("/data/system/devices/keylayout/mtk-pmic-keys.kl").isFile()
            || new File("/data/adb/modules/titan2_keychars/system/usr/keylayout/mtk-pmic-keys.kl")
                .isFile()
            || new File("/data/local/tmp/mtk-pmic-keys.kl").isFile()
            || new File("/data/local/tmp/titan2_kl/mtk-pmic-keys.kl").isFile();
        String pmicLayoutHint = pmicKeyLayoutHint();
        sb.append("pmic_kl=").append(pmicKl ? "1" : "0")
            .append(" pmic_layout=").append(pmicLayoutHint).append('\n');
        // Soft belt: product file missing while node present → Generic.kl (OK if
        // Generic omits 249/250; still want product POWER-only kl after heal/flash).
        boolean pmicBeltSoft = pmic && !new File("/system/usr/keylayout/mtk-pmic-keys.kl").isFile();
        sb.append("pmic_belt_soft=").append(pmicBeltSoft ? "1" : "0").append('\n');

        // Short side default none — never Home/Camera system path
        boolean shortsOk = isNoneOrEmpty(sideBottom) && isNoneOrEmpty(sideTop);
        boolean klOk = mapped == 0 && checked > 0;
        boolean nodesOk = gpio || ff; // at least one side node present

        // KEY_FIRE feel path (no hardware): side long → layout hold → end
        // Same owner as pad-agent broadcast → HostLayoutController (no dual type).
        FirePathResult fire = runKeyFirePath(ctx);
        sb.append("fire_path=").append(fire.detail).append('\n');
        sb.append("fire_ok=").append(fire.ok ? "1" : "0").append('\n');

        // 13.48 hold-Sym product: long/double factory is none (not layout hold).
        // Still fail on system chrome (Home/Recents/Camera) — B1 invariant.
        boolean longsOk = isSideLongOk(sideBottomLong) && isSideLongOk(sideTopLong);
        boolean doublesOk = isSideLongOk(sideBottomDouble) && isSideLongOk(sideTopDouble);
        sb.append("longs_ok=").append(longsOk ? "1" : "0")
            .append(" doubles_ok=").append(doublesOk ? "1" : "0")
            .append('\n');

        // 13.79/13.80: pad-agent status age — hung main loop left sides dead
        // while KL plane still looked factory-none (false-PASS residual).
        // 13.80: liveness = mtime + pad-agent identity (not "ok" token only —
        // mid-loop heal log clobbered ok i=N → false agent_stale).
        AgentBridge.AgentLive hb = AgentBridge.agentLive();
        sb.append("agent_status_age_s=").append(hb.ageSec)
            .append(" agent_ok=").append(hb.ok ? "1" : "0")
            .append(" agent_line=").append(hb.lineSnippet).append('\n');

        boolean staticPass = klOk && nodesOk && shortsOk && longsOk && doublesOk;
        boolean pass = staticPass && fire.ok && hb.ok;
        sb.append("kl_ok=").append(klOk ? "1" : "0")
            .append(" nodes_ok=").append(nodesOk ? "1" : "0")
            .append(" shorts_ok=").append(shortsOk ? "1" : "0")
            .append(" static_ok=").append(staticPass ? "1" : "0")
            .append('\n');
        sb.append("result=").append(pass ? "PASS" : "FAIL");
        if (!klOk) sb.append(" side_mapped");
        if (!nodesOk) sb.append(" no_side_nodes");
        if (!shortsOk) sb.append(" side_short_not_none");
        if (!longsOk) sb.append(" side_long_chrome");
        if (!doublesOk) sb.append(" side_double_chrome");
        if (!fire.ok) sb.append(" fire_path");
        if (!hb.ok) sb.append(" agent_stale");
        if (pmicBeltSoft) sb.append(" pmic_generic_soft");
        sb.append('\n');
        return sb.toString();
    }

    private static String planeVal(Context ctx, String name) {
        String v = AgentBridge.get(ctx, name, null);
        if (v != null && !v.isEmpty()) return v;
        return readFirstLine("/data/local/tmp/" + name);
    }

    private static String oneLineSummary(String report, String result) {
        String fire = extract(report, "fire_ok=");
        String shorts = extract(report, "shorts_ok=");
        String longs = extract(report, "longs_ok=");
        String doubles = extract(report, "doubles_ok=");
        String kl = extract(report, "kl_ok=");
        String nodes = extract(report, "nodes_ok=");
        String agent = extract(report, "agent_ok=");
        return "result=" + result
            + " fire_ok=" + (fire != null ? fire : "?")
            + " shorts_ok=" + (shorts != null ? shorts : "?")
            + " longs_ok=" + (longs != null ? longs : "?")
            + " doubles_ok=" + (doubles != null ? doubles : "?")
            + " kl_ok=" + (kl != null ? kl : "?")
            + " nodes_ok=" + (nodes != null ? nodes : "?")
            + " agent_ok=" + (agent != null ? agent : "?");
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

    /**
     * Shell-readable agent plane: Settings.Global always; files best-effort.
     * Scoped storage blocks reliable adb stat of app-external; Global is SoT for agents.
     */
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
        // Best-effort shell plane (may fail SELinux on shell_data_file)
        for (String d : new String[]{"/data/local/tmp", "/data/misc/titan2"}) {
            writeWorld(new File(d, FILE), data);
        }
        // Compact one-liners for pad-agent style readers
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

    private static final class FirePathResult {
        final boolean ok;
        final String detail;
        FirePathResult(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }
    }

    /**
     * Simulate pad-agent side long-hold KEY_FIRE without hardware:
     * bottom long → specials hold → end; top long → arrows hold → end.
     * Restores prior sticky layout after.
     */
    private static FirePathResult runKeyFirePath(Context ctx) {
        StringBuilder d = new StringBuilder();
        String saved = HostLayoutController.getSticky();
        try {
            HostLayoutController.endHoldAll();
            HostLayoutController.applyAction(ctx, KeyMapPrefs.ACT_LAYOUT_OFF);
            SystemClock.sleep(40);

            // Bottom side long = specials_hold (scan 250)
            HostLayoutController.beginHold(ctx, KeyMapPrefs.SCAN_SIDE_FUNC,
                KeyMapPrefs.ACT_LAYOUT_SPECIALS_HOLD);
            SystemClock.sleep(40);
            String afterBottom = HostLayoutController.effective(ctx);
            boolean bottomOk = HostLayoutController.MODE_SPECIALS.equals(afterBottom)
                && HostLayoutController.isHoldActive();
            d.append("bottom_hold=").append(afterBottom)
                .append(bottomOk ? "(ok)" : "(fail)");

            HostLayoutController.endHold(KeyMapPrefs.SCAN_SIDE_FUNC);
            SystemClock.sleep(40);
            String afterEnd1 = HostLayoutController.effective(ctx);
            boolean end1Ok = !HostLayoutController.isHoldActive()
                && (HostLayoutController.MODE_OFF.equals(afterEnd1)
                    || HostLayoutController.MODE_OFF.equals(HostLayoutController.getSticky())
                    || !HostLayoutController.MODE_SPECIALS.equals(afterEnd1));
            d.append(" end1=").append(afterEnd1).append(end1Ok ? "(ok)" : "(fail)");

            HostLayoutController.applyAction(ctx, KeyMapPrefs.ACT_LAYOUT_OFF);
            SystemClock.sleep(40);

            // Top side long = arrows_hold (scan 249)
            HostLayoutController.beginHold(ctx, KeyMapPrefs.SCAN_SIDE_FUNC2,
                KeyMapPrefs.ACT_LAYOUT_ARROWS_HOLD);
            SystemClock.sleep(40);
            String afterTop = HostLayoutController.effective(ctx);
            boolean topOk = HostLayoutController.MODE_ARROWS.equals(afterTop)
                && HostLayoutController.isHoldActive();
            d.append(" top_hold=").append(afterTop).append(topOk ? "(ok)" : "(fail)");

            HostLayoutController.endHold(KeyMapPrefs.SCAN_SIDE_FUNC2);
            SystemClock.sleep(40);
            String afterEnd2 = HostLayoutController.effective(ctx);
            boolean end2Ok = !HostLayoutController.isHoldActive();
            d.append(" end2=").append(afterEnd2).append(end2Ok ? "(ok)" : "(fail)");

            // none action must be a no-op (short default)
            String beforeNone = HostLayoutController.effective(ctx);
            KeyActions.run(ctx, KeyMapPrefs.ACT_NONE);
            String afterNone = HostLayoutController.effective(ctx);
            boolean noneOk = beforeNone.equals(afterNone);
            d.append(" none=").append(noneOk ? "ok" : "mutated");

            boolean ok = bottomOk && end1Ok && topOk && end2Ok && noneOk;
            return new FirePathResult(ok, d.toString());
        } catch (Exception e) {
            d.append(" err=").append(e.getClass().getSimpleName());
            return new FirePathResult(false, d.toString());
        } finally {
            try {
                HostLayoutController.endHoldAll();
                if (saved != null && !HostLayoutController.MODE_OFF.equals(saved)
                        && !saved.isEmpty()) {
                    HostLayoutController.activate(ctx, saved);
                } else {
                    HostLayoutController.applyAction(ctx, KeyMapPrefs.ACT_LAYOUT_OFF);
                }
            } catch (Exception ignored) {}
        }
    }

    private static boolean isNoneOrEmpty(String s) {
        if (s == null || s.isEmpty() || "?".equals(s)) return true;
        return "none".equalsIgnoreCase(s.trim());
    }

    /**
     * 13.48: long/double OK when none (hold Sym specials) or any non-chrome action.
     * Fail only on system Home/Recents/Camera chrome (B1).
     */
    private static boolean isSideLongOk(String s) {
        if (s == null || s.isEmpty() || "?".equals(s)) return true; // treat empty as none
        String t = s.trim();
        if (KeyMapPrefs.ACT_NONE.equals(t) || KeyMapPrefs.ACT_DEFAULT.equals(t)
                || KeyMapPrefs.ACT_LAYOUT_OFF.equals(t)) {
            return true;
        }
        return !KeyMapPrefs.isSystemChromeAction(t);
    }

    /** @deprecated 13.48 — layout hold no longer factory. */
    @Deprecated
    private static boolean isFactorySideLong(String s) {
        return isSideLongOk(s);
    }

    /** @deprecated 13.48 */
    @Deprecated
    private static boolean isFactorySideDouble(String s) {
        return isSideLongOk(s);
    }

    /** Count keylayout lines that map Linux scan 249/250 to any Android key. */
    private static int countSideMaps(File f) {
        int n = 0;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                // key 249 … / key 250 …
                if (t.matches("(?i)key\\s+249(\\s|$).*")
                        || t.matches("(?i)key\\s+250(\\s|$).*")) {
                    n++;
                }
            }
        } catch (Exception ignored) {}
        return n;
    }

    private static boolean hasInputNamed(String name) {
        try {
            int[] ids = InputDevice.getDeviceIds();
            if (ids == null) return false;
            for (int id : ids) {
                InputDevice d = InputDevice.getDevice(id);
                if (d == null) continue;
                if (name.equals(d.getName())) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Best-effort: dumpsys-style path not available; report file presence. */
    private static String pmicKeyLayoutHint() {
        if (new File("/system/usr/keylayout/mtk-pmic-keys.kl").isFile()) {
            return "product_kl";
        }
        if (new File("/data/adb/modules/titan2_keychars/system/usr/keylayout/mtk-pmic-keys.kl")
                .isFile()) {
            return "magisk_staged";
        }
        if (new File("/data/local/tmp/mtk-pmic-keys.kl").isFile()
                || new File("/data/local/tmp/titan2_kl/mtk-pmic-keys.kl").isFile()) {
            return "tmp_staged";
        }
        return "generic_or_missing";
    }

    private static String readFirstLine(String path) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
            return br.readLine();
        } catch (Exception e) {
            return null;
        }
    }
}
