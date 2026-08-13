package com.titanus2.cubecontact;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Append-only Commander audit (Cube channel). Dual-write: app filesDir +
 * {@code /data/local/tmp/nanobot_home/commander_audit.jsonl}.
 * <p>
 * Every high-risk override is logged with id + prev/next so soft state can be
 * reversed; destructive hardware ops are logged for forensics even if OS
 * cannot un-wipe.
 */
public final class CommanderLog {
    private static final String TAG = "CommanderLog";
    private static final String FILE = "commander_audit.jsonl";
    private static final String SHARED =
        "/data/local/tmp/nanobot_home/commander_audit.jsonl";

    private CommanderLog() {}

    public static File appFile(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    public static File sharedFile() {
        return new File(SHARED);
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static String sha256Short(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((s != null ? s : "").getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8 && i < d.length; i++) {
                sb.append(String.format(Locale.US, "%02x", d[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "na";
        }
    }

    /**
     * @param kind chat|override_request|override_ok|override_deny|chat_reply|reverse
     * @param reverseOf id of prior event this undoes (optional)
     * @param prevState soft snapshot before action (optional)
     * @param nextState soft snapshot after (optional)
     */
    public static void record(Context c, String kind, String detail, String eventId,
                              String reverseOf, String prevState, String nextState) {
        try {
            JSONObject o = new JSONObject();
            o.put("ts", isoNow());
            o.put("id", eventId != null ? eventId : newId());
            o.put("channel", "CUBE_COMMANDER");
            o.put("kind", kind != null ? kind : "event");
            if (detail != null) o.put("detail", detail);
            if (reverseOf != null) o.put("reverse_of", reverseOf);
            if (prevState != null) o.put("prev", prevState);
            if (nextState != null) o.put("next", nextState);
            o.put("host", android.os.Build.MODEL);
            o.put("pkg", "com.titanus2.cubecontact");
            String line = o.toString() + "\n";
            if (c != null) append(appFile(c), line);
            try {
                File sh = sharedFile();
                File parent = sh.getParentFile();
                if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                append(sh, line);
                //noinspection ResultOfMethodCallIgnored
                sh.setReadable(true, false);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            Log.e(TAG, "record: " + e.getMessage());
        }
    }

    public static void recordChat(Context c, String role, String text, String eventId) {
        String hash = sha256Short(text);
        String detail = role + " hash=" + hash
            + " len=" + (text != null ? text.length() : 0);
        // Store truncated text for reverse/readback (not full dump if huge)
        String clip = text != null && text.length() > 400
            ? text.substring(0, 400) + "…" : text;
        record(c, "chat_" + role, detail, eventId, null, null, clip);
    }

    public static void recordOverride(Context c, String action, boolean approved,
                                      String eventId, String prev) {
        record(c, approved ? "override_ok" : "override_deny",
            action, eventId, null, prev,
            approved ? "biometric_ok" : "biometric_fail");
    }

    private static void append(File f, String line) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static List<String> readRecent(Context c, int max) {
        List<String> all = new ArrayList<>();
        if (c != null) readInto(appFile(c), all);
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

    private static String isoNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }
}
