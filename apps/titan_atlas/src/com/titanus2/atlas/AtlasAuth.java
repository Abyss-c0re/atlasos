package com.titanus2.atlas;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Atlas Authentication Agent (host side).
 *
 * <p>Privilege wrapper, not a blanket ticket. Native {@code atlas-auth exec}
 * authenticates then runs the locked binary. Host never auto-grants leftover
 * {@code req.*} because some other command already succeeded.
 *
 * <p>Tickets are {@code ticket.<scope>} (argv0) only. {@code ticket.exec} is a
 * 15s enterd one-shot — not skip-bio. Strict mode + TTL=0 = auth every call.
 *
 * Protocol under super LP {@link NativeBin#AUTH_ON_LP}.
 */
public final class AtlasAuth {
    private static final String TAG = "AtlasAuth";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Default ticket lifetime when not strict (seconds). Prefs override. */
    public static final int TICKET_TTL_SEC = 60;
    /** enterd one-shot after a grant — not a skip-bio ticket. */
    public static final int EXEC_TOKEN_TTL_SEC = 15;

    private AtlasAuth() {}

    public static File authDir(Context c) {
        // Never mount LP here — atlas-lpctl waitFor ANRs the splash if
        // called from Application/Activity onCreate on the main thread.
        File d = NativeBin.authDirLp();
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        d.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        d.setWritable(true, false);
        //noinspection ResultOfMethodCallIgnored
        d.setExecutable(true, false);
        return d;
    }

    public static String sanitizeScope(String scope) {
        if (scope == null) return "ask";
        int slash = scope.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < scope.length()) scope = scope.substring(slash + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < scope.length(); i++) {
            char ch = scope.charAt(i);
            if (ch >= 'A' && ch <= 'Z') ch = (char) (ch - 'A' + 'a');
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '-' || ch == '_') {
                sb.append(ch);
            }
        }
        return sb.length() == 0 ? "ask" : sb.toString();
    }

    /** Glass capture + mutate never silent-grant. */
    public static boolean isCaptureOrMutateScope(String scope, String cmd) {
        String sc = sanitizeScope(scope);
        String c = cmd != null ? cmd.toLowerCase() : "";
        switch (sc) {
            case "screencap":
            case "screenshot":
            case "input":
            case "am":
            case "pm":
            case "cmd":
            case "settings":
            case "setprop":
            case "wm":
            case "sudo":
            case "su":
            case "exec":
                return true;
            default:
                break;
        }
        return c.contains("screencap") || c.contains("screenshot")
            || c.contains("nsenter") || c.contains("input ");
    }

    public static void clearStaleRequests(Context c) {
        File dir = authDir(c);
        File[] files = dir.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File f : files) {
            String n = f.getName();
            if (n == null) continue;
            if (n.startsWith("ok.") || n.startsWith("fail.") || n.startsWith("busy.")) {
                if (now - f.lastModified() > 15_000L) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
                continue;
            }
            if (n.startsWith("req.") && now - f.lastModified() > 20_000L) {
                String id = n.substring(4);
                writeResult(c, id, false, "stale", firstLine(readText(f)), "");
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** Agent poll: one prompt at a time. Never blanket-grant on a leftover ticket. */
    public static void pollOnce(Context c) {
        clearStaleRequests(c);
        if (AtlasPrefs.isAuthUiShowing(c)) return;
        File dir = authDir(c);
        File[] files = dir.listFiles();
        if (files == null) return;
        final boolean biometricsOn = AtlasPrefs.biometricAuth(c);
        for (File f : files) {
            String name = f.getName();
            if (!name.startsWith("req.")) continue;
            String id = name.substring(4);
            if (id.isEmpty()) continue;
            File ok = new File(dir, "ok." + id);
            File fail = new File(dir, "fail." + id);
            if (ok.exists() || fail.exists()) continue;
            if (System.currentTimeMillis() - f.lastModified() < 300L) continue;
            File claimed = new File(dir, "busy." + id);
            if (!f.renameTo(claimed)) continue;
            String body = readText(claimed);
            String reason = firstLine(body);
            String scope = parseField(body, "scope");
            String cmd = parseField(body, "cmd");
            if (reason == null || reason.isEmpty()) reason = "Atlas privilege";
            appendLog(c, "host-claim", scope, reason, cmd, "busy");
            /* Capture/mutate never silent-grant. Bio off still asks (Approve).
             * Silent grant when bio=off minted ticket.screencap for Grok. */
            if (!biometricsOn && !isCaptureOrMutateScope(scope, cmd)) {
                writeResult(c, id, true, scope, reason, cmd);
                continue;
            }
            launchAuthUi(c, id, reason.trim());
        }
    }

    /** @deprecated blanket ticket is heresy — use {@link #hasValidTicket(Context, String)} */
    public static boolean hasValidTicket(Context c) {
        return false;
    }

    public static boolean hasValidTicket(Context c, String scope) {
        if (AtlasPrefs.authStrict(c)) return false;
        if (AtlasPrefs.ticketTtlSec(c) <= 0) return false;
        String sc = sanitizeScope(scope);
        if ("exec".equals(sc)) return false;
        return ticketFileValid(new File(authDir(c), "ticket." + sc));
    }

    private static boolean ticketFileValid(File ticket) {
        if (ticket == null || !ticket.isFile()) return false;
        String t = readText(ticket);
        if (t == null) return false;
        t = t.trim();
        try {
            String[] p = t.split("\\s+");
            if (p.length < 2) return false;
            long exp = Long.parseLong(p[0]);
            int ttl = Integer.parseInt(p[1]);
            if (ttl <= 0) return false;
            long now = System.currentTimeMillis() / 1000L;
            return exp > now && exp <= now + ttl + 5;
        } catch (Exception e) {
            return false;
        }
    }

    public static void writeExecToken(Context c) {
        writeTicketFile(new File(authDir(c), "ticket.exec"), EXEC_TOKEN_TTL_SEC);
    }

    public static void writeTicket(Context c, String scope) {
        writeExecToken(c);
        if (AtlasPrefs.authStrict(c)) return;
        int ttl = AtlasPrefs.ticketTtlSec(c);
        if (ttl <= 0) return;
        String sc = sanitizeScope(scope);
        if ("exec".equals(sc)) return;
        writeTicketFile(new File(authDir(c), "ticket." + sc), ttl);
    }

    /** @deprecated unscoped write — use {@link #writeTicket(Context, String)} */
    public static void writeTicket(Context c, int ttlSec) {
        writeExecToken(c);
    }

    private static void writeTicketFile(File ticket, int ttlSec) {
        if (ttlSec <= 0) return;
        long exp = System.currentTimeMillis() / 1000L + ttlSec;
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(ticket), StandardCharsets.UTF_8)) {
            w.write(exp + " " + ttlSec + "\n");
        } catch (Exception e) {
            Log.w(TAG, "ticket write", e);
        }
        //noinspection ResultOfMethodCallIgnored
        ticket.setReadable(true, false);
    }

    public static void clearTicket(Context c) {
        File dir = authDir(c);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (n != null && (n.equals("ticket") || n.startsWith("ticket."))) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        new File("/data/local/tmp/atlas_auth.ticket").delete();
    }

    private static void launchAuthUi(Context c, String id, String reason) {
        if (AtlasPrefs.isAuthUiShowing(c)) return;
        Intent i = new Intent(c, AuthPromptActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        i.putExtra(AuthPromptActivity.EXTRA_ID, id);
        i.putExtra(AuthPromptActivity.EXTRA_REASON, reason);
        try {
            AtlasPrefs.markAuthUi(c, true);
            c.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "auth ui failed", e);
            AtlasPrefs.markAuthUi(c, false);
            writeResult(c, id, false);
        }
    }

    public static void writeResult(Context c, String id, boolean grant) {
        File dir = authDir(c);
        String body = readText(new File(dir, "busy." + id));
        if (body == null) body = readText(new File(dir, "req." + id));
        writeResult(c, id, grant, parseField(body, "scope"), firstLine(body),
            parseField(body, "cmd"));
    }

    public static void writeResult(Context c, String id, boolean grant,
                                   String scope, String reason, String cmd) {
        File dir = authDir(c);
        //noinspection ResultOfMethodCallIgnored
        dir.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        dir.setExecutable(true, false);
        File target = new File(dir, (grant ? "ok." : "fail.") + id);
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(target), StandardCharsets.UTF_8)) {
            w.write(grant ? "ok\n" : "fail\n");
        } catch (Exception e) {
            Log.w(TAG, "write result", e);
        }
        //noinspection ResultOfMethodCallIgnored
        target.setReadable(true, false);
        if (grant) {
            writeTicket(c, scope != null ? scope : "ask");
        }
        appendLog(c, grant ? "grant" : "deny", scope, reason, cmd, grant ? "ok" : "fail");
        //noinspection ResultOfMethodCallIgnored
        new File(dir, "busy." + id).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, "req." + id).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, "wake").delete();
    }

    public static boolean requestBlocking(Context c, String reason, int timeoutSec) {
        return requestBlocking(c, reason, timeoutSec, "settings");
    }

    public static boolean requestBlocking(Context c, String reason, int timeoutSec,
                                          String scope) {
        if (!AtlasPrefs.biometricAuth(c)) return true;
        if (hasValidTicket(c, scope)) return true;
        if (!(c instanceof Activity)) {
            return requestBlockingViaFiles(c, reason, timeoutSec, scope);
        }
        return requestBlockingViaFiles(c, reason, timeoutSec, scope);
    }

    private static boolean requestBlockingViaFiles(Context c, String reason,
                                                   int timeoutSec, String scope) {
        File authBin = new File(NativeBin.binDir(c), "atlas-auth");
        if (!authBin.isFile()) authBin = new File("/system/bin/atlas-auth");
        if (authBin.isFile()) {
            try {
                Process p = new ProcessBuilder(
                    authBin.getAbsolutePath(), "request",
                    "--scope", sanitizeScope(scope),
                    "-t", String.valueOf(Math.max(15, timeoutSec)),
                    reason != null ? reason : "Atlas privilege")
                    .redirectErrorStream(true)
                    .start();
                boolean done = p.waitFor(timeoutSec + 5L, java.util.concurrent.TimeUnit.SECONDS);
                if (!done) {
                    p.destroyForcibly();
                    return false;
                }
                return p.exitValue() == 0;
            } catch (Exception e) {
                Log.w(TAG, "atlas-auth exec", e);
            }
        }
        return false;
    }

    public static File logFile(Context c) {
        return new File(authDir(c), "log.jsonl");
    }

    public static void appendLog(Context c, String event, String scope,
                                 String reason, String cmd, String result) {
        File log = logFile(c);
        long ts = System.currentTimeMillis() / 1000L;
        String line = "{\"ts\":" + ts
            + ",\"event\":\"" + jsonEsc(event)
            + "\",\"scope\":\"" + jsonEsc(sanitizeScope(scope))
            + "\",\"reason\":\"" + jsonEsc(reason)
            + "\",\"cmd\":\"" + jsonEsc(cmd)
            + "\",\"result\":\"" + jsonEsc(result)
            + "\",\"pid\":0,\"uid\":" + android.os.Process.myUid() + "}\n";
        try (FileOutputStream out = new FileOutputStream(log, true)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "log", e);
        }
        //noinspection ResultOfMethodCallIgnored
        log.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        log.setWritable(true, false);
        if (log.length() > 512 * 1024) {
            File bak = new File(log.getAbsolutePath() + ".1");
            //noinspection ResultOfMethodCallIgnored
            bak.delete();
            //noinspection ResultOfMethodCallIgnored
            log.renameTo(bak);
        }
    }

    public static List<String> readLogTail(Context c, int maxLines) {
        List<String> out = new ArrayList<>();
        File log = logFile(c);
        File bak = new File(log.getAbsolutePath() + ".1");
        readLogFile(bak, out);
        readLogFile(log, out);
        int n = out.size();
        if (n > maxLines) {
            return new ArrayList<>(out.subList(n - maxLines, n));
        }
        return out;
    }

    public static void clearLog(Context c) {
        //noinspection ResultOfMethodCallIgnored
        logFile(c).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(logFile(c).getAbsolutePath() + ".1").delete();
    }

    public static String formatLogLine(String json) {
        if (json == null) return "";
        String ts = field(json, "ts");
        String ev = field(json, "event");
        String sc = field(json, "scope");
        String rs = field(json, "result");
        String reason = field(json, "reason");
        String cmd = field(json, "cmd");
        String when = formatTs(ts);
        String what = cmd != null && !cmd.isEmpty() ? cmd : reason;
        return when + "  " + nz(ev) + "  " + nz(sc) + "  " + nz(rs)
            + (what != null && !what.isEmpty() ? "\n  " + what : "");
    }

    private static void readLogFile(File f, List<String> out) {
        if (f == null || !f.isFile()) return;
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            String s = new String(b, StandardCharsets.UTF_8);
            for (String line : s.split("\n")) {
                if (!line.trim().isEmpty()) out.add(line.trim());
            }
        } catch (Exception ignored) {
        }
    }

    private static String formatTs(String sec) {
        try {
            long t = Long.parseLong(sec) * 1000L;
            java.text.SimpleDateFormat df =
                new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
            return df.format(new java.util.Date(t));
        } catch (Exception e) {
            return "--:--:--";
        }
    }

    private static String field(String json, String key) {
        if (json == null) return "";
        String needle = "\"" + key + "\":";
        int i = json.indexOf(needle);
        if (i < 0) return "";
        i += needle.length();
        if (i < json.length() && json.charAt(i) == '"') {
            i++;
            StringBuilder sb = new StringBuilder();
            for (; i < json.length(); i++) {
                char ch = json.charAt(i);
                if (ch == '\\' && i + 1 < json.length()) {
                    sb.append(json.charAt(i + 1));
                    i++;
                    continue;
                }
                if (ch == '"') break;
                sb.append(ch);
            }
            return sb.toString();
        }
        int j = i;
        while (j < json.length() && json.charAt(j) != ',' && json.charAt(j) != '}') j++;
        return json.substring(i, j).trim();
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    private static String jsonEsc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') sb.append('\\').append(ch);
            else if (ch == '\n') sb.append("\\n");
            else if (ch == '\r') sb.append("\\r");
            else if (ch >= 32) sb.append(ch);
        }
        return sb.toString();
    }

    private static String firstLine(String body) {
        if (body == null) return "";
        int n = body.indexOf('\n');
        return (n < 0 ? body : body.substring(0, n)).trim();
    }

    private static String parseField(String body, String key) {
        if (body == null || key == null) return "";
        String prefix = key + "=";
        for (String line : body.split("\n")) {
            line = line.trim();
            if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
        }
        return "";
    }

    private static String readText(File f) {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
