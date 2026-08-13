package com.titanus2.nanobot;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Append-only access / admin history (pairing, share toggles, token reveals, service).
 * Dual-write: app private + shared NANOBOT_HOME when possible.
 */
public final class AccessLog {
    private static final String TAG = "AccessLog";
    private static final String FILE = "access_history.jsonl";

    private AccessLog() {}

    public static File appFile(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    public static File sharedFile() {
        return new File(NanobotRuntime.SHARED_HOME, FILE);
    }

    public static void record(Context c, String kind, String detail) {
        record(c, kind, detail, null);
    }

    public static void record(Context c, String kind, String detail, String clientId) {
        try {
            JSONObject o = new JSONObject();
            o.put("ts", isoNow());
            o.put("kind", kind == null ? "event" : kind);
            if (detail != null) o.put("detail", detail);
            if (clientId != null) o.put("client", clientId);
            o.put("host", android.os.Build.MODEL);
            String line = o.toString() + "\n";
            append(appFile(c), line);
            try {
                File sh = sharedFile();
                File parent = sh.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                append(sh, line);
                //noinspection ResultOfMethodCallIgnored
                sh.setReadable(true, false);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            Log.e(TAG, "record: " + e.getMessage());
        }
    }

    private static void append(File f, String line) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static List<String> readRecent(Context c, int max) {
        List<String> all = new ArrayList<>();
        readInto(appFile(c), all);
        if (all.isEmpty()) readInto(sharedFile(), all);
        if (all.size() <= max) return all;
        return all.subList(all.size() - max, all.size());
    }

    private static void readInto(File f, List<String> out) {
        if (f == null || !f.isFile()) return;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) out.add(line.trim());
            }
        } catch (Exception ignored) {}
    }

    public static String formatPretty(Context c, int max) {
        List<String> lines = readRecent(c, max);
        if (lines.isEmpty()) {
            return "(no access events yet)\n\n"
                + "Events are logged when you:\n"
                + "• turn the agent service on/off\n"
                + "• enable/disable LAN API share\n"
                + "• pair or revoke an MCP client (after biometric)\n"
                + "• show or copy the peer token\n"
                + "• rotate credentials\n";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = lines.size() - 1; i >= 0; i--) {
            try {
                JSONObject o = new JSONObject(lines.get(i));
                sb.append(o.optString("ts", "?"))
                    .append("  ")
                    .append(o.optString("kind", "?"))
                    .append("\n  ")
                    .append(o.optString("detail", ""))
                    .append(o.has("client") ? "  [" + o.optString("client") + "]" : "")
                    .append("\n\n");
            } catch (Exception e) {
                sb.append(lines.get(i)).append("\n\n");
            }
        }
        return sb.toString();
    }

    private static String isoNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }
}
