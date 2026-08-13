package com.titanus2.nanobot;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Context / history prefs for a proper LLM chat surface
 * (inspired by Open WebUI / LibreChat-style controls).
 *
 * Mirrored to {@code $NANOBOT_HOME/memory/prefs} so the peer agent
 * prunes history the same way the UI intends.
 */
public final class ChatPrefs {
    private static final String P = "titan_nanobot_chat";

    private ChatPrefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    /** How many user/assistant pairs stay verbatim in recent.jsonl (1–24). */
    public static int recentTurns(Context c) {
        return clamp(sp(c).getInt("recent_turns", 6), 1, 24);
    }

    public static void setRecentTurns(Context c, int n) {
        sp(c).edit().putInt("recent_turns", clamp(n, 1, 24)).apply();
        syncToPeer(c);
    }

    /** Max characters stored per message (120–4000). */
    public static int msgChars(Context c) {
        return clamp(sp(c).getInt("msg_chars", 800), 120, 4000);
    }

    public static void setMsgChars(Context c, int n) {
        sp(c).edit().putInt("msg_chars", clamp(n, 120, 4000)).apply();
        syncToPeer(c);
    }

    /** Cap for summary.txt (200–8000). */
    public static int summaryMax(Context c) {
        return clamp(sp(c).getInt("summary_max", 1600), 200, 8000);
    }

    public static void setSummaryMax(Context c, int n) {
        sp(c).edit().putInt("summary_max", clamp(n, 200, 8000)).apply();
        syncToPeer(c);
    }

    /** Include compacted summary in system prompt. */
    public static boolean includeSummary(Context c) {
        return sp(c).getBoolean("include_summary", true);
    }

    public static void setIncludeSummary(Context c, boolean on) {
        sp(c).edit().putBoolean("include_summary", on).apply();
        syncToPeer(c);
    }

    /** Include core identity in system prompt. */
    public static boolean includeCore(Context c) {
        return sp(c).getBoolean("include_core", true);
    }

    public static void setIncludeCore(Context c, boolean on) {
        sp(c).edit().putBoolean("include_core", on).apply();
        syncToPeer(c);
    }

    /** Local llama max_tokens for completions (32–2048). */
    public static int maxTokens(Context c) {
        return clamp(sp(c).getInt("max_tokens", 256), 32, 2048);
    }

    public static void setMaxTokens(Context c, int n) {
        sp(c).edit().putInt("max_tokens", clamp(n, 32, 2048)).apply();
        syncToPeer(c);
    }

    /** Show thinking spoiler / tool tracker when peer streams them. */
    public static boolean showThinking(Context c) {
        return sp(c).getBoolean("show_thinking", true);
    }

    public static void setShowThinking(Context c, boolean on) {
        sp(c).edit().putBoolean("show_thinking", on).apply();
    }

    public static boolean showTools(Context c) {
        return sp(c).getBoolean("show_tools", true);
    }

    public static void setShowTools(Context c, boolean on) {
        sp(c).edit().putBoolean("show_tools", on).apply();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Write peer-readable prefs (agent/memory.c reads these). */
    public static void syncToPeer(Context c) {
        try {
            File dir = new File(NanobotRuntime.SHARED_HOME, "memory");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, "prefs");
            String body = "# chat context prefs — written by Nanobot app\n"
                + "RECENT_TURNS=" + recentTurns(c) + "\n"
                + "MSG_CHARS=" + msgChars(c) + "\n"
                + "SUMMARY_MAX=" + summaryMax(c) + "\n"
                + "INCLUDE_SUMMARY=" + (includeSummary(c) ? "1" : "0") + "\n"
                + "INCLUDE_CORE=" + (includeCore(c) ? "1" : "0") + "\n"
                + "MAX_TOKENS=" + maxTokens(c) + "\n";
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
        } catch (Exception ignored) {}
    }

    public static String statsLine(Context c) {
        File mem = new File(NanobotRuntime.SHARED_HOME, "memory");
        long recent = size(new File(mem, "recent.jsonl"));
        long sum = size(new File(mem, "summary.txt"));
        long core = size(new File(mem, "core.txt"));
        long notes = size(new File(mem, "notes.jsonl"));
        long dig = size(new File(mem, "device_digest.txt"));
        return "recent=" + fmt(recent)
            + "  summary=" + fmt(sum)
            + "  core=" + fmt(core)
            + "\nnotes=" + fmt(notes)
            + "  digest=" + fmt(dig)
            + "\nkeep " + recentTurns(c) + " turns · "
            + msgChars(c) + " ch/msg · max_tokens=" + maxTokens(c);
    }

    private static long size(File f) {
        return f != null && f.isFile() ? f.length() : 0;
    }

    private static String fmt(long n) {
        if (n < 1024) return n + "B";
        return String.format(java.util.Locale.US, "%.1fK", n / 1024.0);
    }

    /** Clear recent chat turns (keeps core + optional summary). */
    public static boolean clearRecent(Context c) {
        return wipe(new File(NanobotRuntime.SHARED_HOME, "memory/recent.jsonl"));
    }

    public static boolean clearSummary(Context c) {
        return wipe(new File(NanobotRuntime.SHARED_HOME, "memory/summary.txt"));
    }

    public static boolean clearNotes(Context c) {
        boolean a = wipe(new File(NanobotRuntime.SHARED_HOME, "memory/notes.jsonl"));
        boolean b = wipe(new File(NanobotRuntime.SHARED_HOME, "memory/device_digest.txt"));
        return a || b;
    }

    /** Clear chat memory but keep core identity (Open WebUI “clear chat”). */
    public static void clearChatKeepCore(Context c) {
        clearRecent(c);
        clearSummary(c);
        clearNotes(c);
        AccessLog.record(c, "chat_clear", "recent+summary+notes");
    }

    /** Nuclear: also reset core to default seed if present. */
    public static void clearAllMemory(Context c) {
        clearChatKeepCore(c);
        wipe(new File(NanobotRuntime.SHARED_HOME, "memory/profile.txt"));
        File core = new File(NanobotRuntime.SHARED_HOME, "memory/core.txt");
        File seed = new File("/system/etc/titan2/nanobot_core.default.txt");
        try {
            if (seed.isFile()) {
                byte[] b = new byte[(int) Math.min(seed.length(), 4096)];
                try (FileInputStream in = new FileInputStream(seed)) {
                    int n = in.read(b);
                    if (n > 0) {
                        try (FileOutputStream out = new FileOutputStream(core)) {
                            out.write(b, 0, n);
                        }
                    }
                }
            } else {
                wipe(core);
            }
        } catch (Exception e) {
            wipe(core);
        }
        AccessLog.record(c, "chat_clear_all", "memory wipe");
    }

    public static String readCore(Context c) {
        return readFile(new File(NanobotRuntime.SHARED_HOME, "memory/core.txt"));
    }

    public static void writeCore(Context c, String text) {
        try {
            File dir = new File(NanobotRuntime.SHARED_HOME, "memory");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, "core.txt");
            String t = text == null ? "" : text;
            if (t.length() > 2000) t = t.substring(0, 2000);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(t.getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            AccessLog.record(c, "core_edit", t.length() + " chars");
        } catch (Exception ignored) {}
    }

    public static String readSummary(Context c) {
        return readFile(new File(NanobotRuntime.SHARED_HOME, "memory/summary.txt"));
    }

    public static String readRecentTail(Context c, int maxLines) {
        File f = new File(NanobotRuntime.SHARED_HOME, "memory/recent.jsonl");
        if (!f.isFile()) return "(empty)";
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 16000)];
            try (FileInputStream in = new FileInputStream(f)) {
                int n = in.read(b);
                if (n <= 0) return "(empty)";
                String s = new String(b, 0, n, StandardCharsets.UTF_8);
                String[] lines = s.split("\n");
                int from = Math.max(0, lines.length - maxLines);
                StringBuilder sb = new StringBuilder();
                for (int i = from; i < lines.length; i++) {
                    if (!lines[i].isEmpty()) sb.append(lines[i]).append('\n');
                }
                return sb.length() == 0 ? "(empty)" : sb.toString();
            }
        } catch (Exception e) {
            return "(unreadable)";
        }
    }

    /**
     * Last {@code maxMsgs} role/content pairs from recent.jsonl
     * (for local llama multi-turn context).
     */
    public static List<String[]> loadRecentTurns(Context c, int maxMsgs) {
        List<String[]> all = new ArrayList<>();
        File f = new File(NanobotRuntime.SHARED_HOME, "memory/recent.jsonl");
        if (!f.isFile() || maxMsgs <= 0) return all;
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 48000)];
            try (FileInputStream in = new FileInputStream(f)) {
                int n = in.read(b);
                if (n <= 0) return all;
                String s = new String(b, 0, n, StandardCharsets.UTF_8);
                for (String line : s.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.charAt(0) != '{') continue;
                    try {
                        JSONObject j = new JSONObject(line);
                        String role = j.optString("role", "");
                        String content = j.optString("content", "");
                        if (role.isEmpty() || content.isEmpty()) continue;
                        if ("null".equalsIgnoreCase(content)) continue;
                        all.add(new String[]{role, content});
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        if (all.size() <= maxMsgs) return all;
        return new ArrayList<>(all.subList(all.size() - maxMsgs, all.size()));
    }

    /**
     * Append user+assistant to recent.jsonl and prune to recent_turns pairs.
     * Used by direct-local path (peer agent records its own exchanges).
     */
    public static void recordExchange(Context c, String user, String assistant) {
        if (c == null) return;
        String u = user != null ? user.trim() : "";
        String a = assistant != null ? assistant.trim() : "";
        if (u.isEmpty() || a.isEmpty()) return;
        if ("null".equalsIgnoreCase(a) || "undefined".equalsIgnoreCase(a)) return;
        int maxChars = msgChars(c);
        if (u.length() > maxChars) u = u.substring(0, maxChars);
        if (a.length() > maxChars) a = a.substring(0, maxChars);
        try {
            File dir = new File(NanobotRuntime.SHARED_HOME, "memory");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, "recent.jsonl");
            List<String[]> turns = loadRecentTurns(c, 48);
            turns.add(new String[]{"user", u});
            turns.add(new String[]{"assistant", a});
            int keep = Math.max(2, recentTurns(c) * 2);
            if (turns.size() > keep) {
                turns = new ArrayList<>(turns.subList(turns.size() - keep, turns.size()));
            }
            StringBuilder sb = new StringBuilder();
            for (String[] row : turns) {
                JSONObject j = new JSONObject();
                j.put("role", row[0]);
                j.put("content", row[1]);
                sb.append(j.toString()).append('\n');
            }
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
        } catch (Exception ignored) {}
    }

    private static String readFile(File f) {
        if (f == null || !f.isFile()) return "";
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 8192)];
            try (FileInputStream in = new FileInputStream(f)) {
                int n = in.read(b);
                return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8) : "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean wipe(File f) {
        if (f == null) return false;
        try {
            File p = f.getParentFile();
            if (p != null) //noinspection ResultOfMethodCallIgnored
                p.mkdirs();
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                out.write(new byte[0]);
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
            return true;
        } catch (Exception e) {
            try {
                return f.delete();
            } catch (Exception e2) {
                return false;
            }
        }
    }

    /** Peer-shell wipe when app SELinux cannot write shell_data_file. */
    public static void clearChatViaPeer(Context c, PeerClient peer) {
        if (peer == null) {
            clearChatKeepCore(c);
            return;
        }
        String home = NanobotRuntime.SHARED_HOME;
        try {
            peer.shell(
                "mkdir -p '" + home + "/memory'; "
                    + ": > '" + home + "/memory/recent.jsonl'; "
                    + ": > '" + home + "/memory/summary.txt'; "
                    + ": > '" + home + "/memory/notes.jsonl'; "
                    + ": > '" + home + "/memory/device_digest.txt'; "
                    + "chmod 666 '" + home + "/memory/'* 2>/dev/null; true");
            AccessLog.record(c, "chat_clear", "via peer shell");
        } catch (Exception e) {
            clearChatKeepCore(c);
        }
        syncToPeer(c);
    }
}
