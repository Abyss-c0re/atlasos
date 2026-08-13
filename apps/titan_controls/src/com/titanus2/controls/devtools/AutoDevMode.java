package com.titanus2.controls.devtools;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import com.titanus2.controls.AgentBridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * AUTO DEV MODE — product plane for nanobot MCP + screenshot analysis + BlackCube peer.
 * <p>
 * Fail-closed: off until human enables via Atlas biometrics (Controls Dev or QS tile).
 * Plane files under {@code /data/local/tmp} + AgentBridge for pad-agent / nanobot.
 */
public final class AutoDevMode {
    private static final String TAG = "AutoDevMode";
    private static final String PREFS = "titan2_auto_dev";
    private static final String K_ON = "on";
    private static final String K_ANALYZE = "analyze_shots";
    private static final String K_PEER = "blackcube_peer";
    private static final String K_PEER_URL = "peer_url";
    private static final String K_PEER_LABEL = "peer_label";
    private static final String K_PAIRED = "paired";

    /** Plane keys (pad-agent / status). */
    public static final String PLANE_MODE = "titan2_auto_dev_mode"; // 0|1
    public static final String PLANE_ANALYZE = "titan2_auto_dev_analyze"; // 0|1
    public static final String PLANE_PEER = "titan2_auto_dev_peer"; // 0|1
    public static final String PLANE_PEER_URL = "titan2_auto_dev_peer_url";
    public static final String PLANE_ACTION = "titan2_auto_dev_action"; // one-shot
    public static final String STATUS = "/data/local/tmp/titan2_auto_dev.status";
    public static final String PAIR_STATE = "/data/local/tmp/titan2_auto_dev_pair";

    public static final String DEFAULT_PEER_URL = "http://<lab-ip>:18790";

    private AutoDevMode() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Source of truth = SharedPreferences only (fail-closed default false).
     * Stale /data/local/tmp plane files or Settings.Global must not re-arm
     * Auto Dev after human disable or residual root-owned tmp (15.58).
     */
    public static boolean isOn(Context c) {
        return sp(c).getBoolean(K_ON, false);
    }

    public static boolean analyzeShots(Context c) {
        return sp(c).getBoolean(K_ANALYZE, true);
    }

    public static boolean blackCubePeer(Context c) {
        return sp(c).getBoolean(K_PEER, false);
    }

    public static boolean isPaired(Context c) {
        return sp(c).getBoolean(K_PAIRED, false)
            || "paired".equals(readFirstLine(PAIR_STATE));
    }

    public static String peerUrl(Context c) {
        String u = sp(c).getString(K_PEER_URL, null);
        if (u == null || u.trim().isEmpty()) {
            u = readPlane(PLANE_PEER_URL);
        }
        if (u == null || u.trim().isEmpty()) u = DEFAULT_PEER_URL;
        return u.trim();
    }

    public static String peerLabel(Context c) {
        String l = sp(c).getString(K_PEER_LABEL, null);
        return (l == null || l.isEmpty()) ? "BlackCube nanobot" : l;
    }

    public static void setAnalyzeShots(Context c, boolean on) {
        sp(c).edit().putBoolean(K_ANALYZE, on).apply();
        publishPlane(c, PLANE_ANALYZE, on ? "1" : "0");
        writeStatus(c);
    }

    public static void setBlackCubePeer(Context c, boolean on) {
        sp(c).edit().putBoolean(K_PEER, on).apply();
        publishPlane(c, PLANE_PEER, on ? "1" : "0");
        writeStatus(c);
    }

    public static void setPeerUrl(Context c, String url) {
        if (url == null) url = "";
        url = url.trim();
        sp(c).edit().putString(K_PEER_URL, url).apply();
        publishPlane(c, PLANE_PEER_URL, url);
        writeStatus(c);
    }

    public static void setPeerLabel(Context c, String label) {
        sp(c).edit().putString(K_PEER_LABEL, label == null ? "" : label.trim()).apply();
        writeStatus(c);
    }

    /** After Atlas bio success for arm / pair. */
    public static void enable(Context c) {
        sp(c).edit().putBoolean(K_ON, true).apply();
        publishPlane(c, PLANE_MODE, "1");
        publishPlane(c, PLANE_ANALYZE, analyzeShots(c) ? "1" : "0");
        publishPlane(c, PLANE_PEER, blackCubePeer(c) ? "1" : "0");
        publishPlane(c, PLANE_PEER_URL, peerUrl(c));
        queueAction(c, "enable");
        writeStatus(c);
        AutoDevService.ensure(c);
        AutoDevTileService.requestRefresh(c);
    }

    public static void disable(Context c) {
        // Fail-closed: mode off + plane zeros. Keep analyze/peer prefs for re-arm.
        sp(c).edit().putBoolean(K_ON, false).commit();
        publishPlane(c, PLANE_MODE, "0");
        publishPlane(c, PLANE_PEER, "0");
        publishPlane(c, PLANE_ANALYZE, "0");
        queueAction(c, "disable");
        writeStatus(c);
        AutoDevService.stop(c);
        AutoDevTileService.requestRefresh(c);
    }

    /**
     * Boot / CoreService: if prefs say off, force plane+service fail-closed.
     * Call when Controls comes up so residual Global/tmp never leave service on.
     */
    public static void syncFailClosed(Context c) {
        if (isOn(c)) {
            // Armed: republish plane so pad-agent matches prefs.
            publishPlane(c, PLANE_MODE, "1");
            publishPlane(c, PLANE_ANALYZE, analyzeShots(c) ? "1" : "0");
            publishPlane(c, PLANE_PEER, blackCubePeer(c) ? "1" : "0");
            writeStatus(c);
            AutoDevService.ensure(c);
            return;
        }
        publishPlane(c, PLANE_MODE, "0");
        publishPlane(c, PLANE_PEER, "0");
        publishPlane(c, PLANE_ANALYZE, "0");
        writeStatus(c);
        AutoDevService.stop(c);
        AutoDevTileService.requestRefresh(c);
    }

    /** Mark BlackCube peer paired after Atlas bio. */
    public static void markPaired(Context c, String label) {
        if (label != null && !label.isEmpty()) {
            setPeerLabel(c, label);
        }
        sp(c).edit().putBoolean(K_PAIRED, true).apply();
        writeLine(PAIR_STATE, "paired " + System.currentTimeMillis() + " " + peerLabel(c));
        queueAction(c, "pair " + peerUrl(c));
        writeStatus(c);
    }

    public static void clearPair(Context c) {
        sp(c).edit().putBoolean(K_PAIRED, false).apply();
        writeLine(PAIR_STATE, "unpaired");
        queueAction(c, "unpair");
        writeStatus(c);
    }

    public static void writeStatus(Context c) {
        String line = "auto_dev=" + (isOn(c) ? "on" : "off")
            + " analyze=" + (analyzeShots(c) ? "on" : "off")
            + " peer=" + (blackCubePeer(c) ? "on" : "off")
            + " paired=" + (isPaired(c) ? "yes" : "no")
            + " url=" + peerUrl(c)
            + " label=" + peerLabel(c).replace(' ', '_');
        writeLine(STATUS, line);
    }

    public static String statusLine(Context c) {
        String disk = readFirstLine(STATUS);
        if (disk != null && !disk.isEmpty()) return disk;
        writeStatus(c);
        return readFirstLine(STATUS);
    }

    private static void queueAction(Context c, String action) {
        String payload = action + " " + System.currentTimeMillis();
        AgentBridge.put(c, PLANE_ACTION, payload);
        try {
            Settings.Global.putString(c.getContentResolver(), PLANE_ACTION, payload);
        } catch (Exception ignored) {
        }
    }

    private static void publishPlane(Context c, String name, String value) {
        AgentBridge.put(c, name, value);
        try {
            Settings.Global.putString(c.getContentResolver(), name, value);
        } catch (Exception ignored) {
        }
        writeLine("/data/local/tmp/" + name, value);
    }

    private static String readPlane(String name) {
        String g = null;
        try {
            // no context — file only
        } catch (Exception ignored) {
        }
        String f = readFirstLine("/data/local/tmp/" + name);
        return f != null ? f : g;
    }

    static String readFirstLine(String path) {
        File f = new File(path);
        if (!f.isFile()) return null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String s = br.readLine();
            return s != null ? s.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static void writeLine(String path, String body) {
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write((body + "\n").getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
        } catch (Exception e) {
            Log.w(TAG, "write " + path, e);
        }
    }
}
