package com.titanus2.atlas;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Rootless Atlas bridge — <b>atlas-enterd only</b>, no KernelSU / Magisk su.
 *
 * <p>TCP {@code 127.0.0.1:17999}:
 * <ul>
 *   <li>{@code HEAL} / {@code BRIDGE} — chown package + linux home (no bio ticket)</li>
 *   <li>{@code ELEVATE …} + {@code CMD …} — after auth ticket (optional bio toggles)</li>
 * </ul>
 *
 * Deb↔Android plane: android-exec for Android bins; enterd for rootless elevate.
 */
public final class AtlasBridge {
    private static final String TAG = "AtlasBridge";
    public static final String ENTERD_HOST = "127.0.0.1";
    public static final int ENTERD_PORT = 17999;

    private AtlasBridge() {}

    /** True if enterd TCP is accepting (product rootless plane). */
    public static boolean enterdLive() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ENTERD_HOST, ENTERD_PORT), 400);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Heal CE files + product linux home ownership via enterd (no KSU, no ticket).
     * @return true if OK_BRIDGE_HEAL seen
     */
    public static boolean healHomeNoKsu(Context c) {
        String out = transact("HEAL\n", 12_000);
        if (out != null && out.contains("OK_BRIDGE_HEAL")) {
            try {
                NativeBin.healHomePermissions(c);
            } catch (Exception ignored) {
            }
            Log.i(TAG, "heal ok: " + out.replace('\n', ' '));
            return true;
        }
        Log.w(TAG, "heal failed: " + (out == null ? "null" : out));
        return false;
    }

    /**
     * Run a root command on Android plane via enterd elevate (needs auth ticket
     * unless Settings bio Android su is off and client set skip — enterd still
     * requires ticket file for ELEVATE). Prefer {@link #healHomeNoKsu} for home.
     */
    public static String elevateAndroid(String shellCmd, int timeoutMs) {
        if (shellCmd == null || shellCmd.isEmpty()) return "ERR empty";
        String body = "ELEVATE chroot=0 home=" + NativeBin.LINUX_HOME + "\n"
            + "CMD " + shellCmd + "\n";
        return transact(body, timeoutMs);
    }

    private static String transact(String request, int timeoutMs) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(ENTERD_HOST, ENTERD_PORT), 800);
            s.setSoTimeout(Math.max(2000, timeoutMs));
            OutputStream os = s.getOutputStream();
            os.write(request.getBytes(StandardCharsets.UTF_8));
            os.flush();
            BufferedReader br = new BufferedReader(
                new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
                if (line.startsWith("__ATLAS_EXIT__")) break;
                if (line.startsWith("ERR ")) break;
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "transact", e);
            return "ERR " + e.getMessage();
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
