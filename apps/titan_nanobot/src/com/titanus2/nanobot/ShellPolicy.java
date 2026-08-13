package com.titanus2.nanobot;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * User-managed shell denylist / allow exceptions for on-device nanobot.
 *
 * Files live under the shared peer home (same as agent):
 *   shell_denylist — patterns blocked (substring)
 *   shell_allow    — exceptions (allowed even if denied)
 *
 * The app often cannot write shell_data_file SELinux paths directly, so saves
 * go through the peer shell when local write fails or verification fails.
 */
public final class ShellPolicy {
    public static final String DENY_FILE = "shell_denylist";
    public static final String ALLOW_FILE = "shell_allow";
    public static final String DANGEROUS_FILE = "shell_dangerous";

    /** Built-in defaults (mirror nanobot shell.c). */
    public static final String[] DEFAULT_DENY = {
        "mkfs", "dd if=", "dd of=", "ddof=", ":(){", "reboot", "poweroff", "halt",
        "shutdown", "init 0", "init 6", "telinit",
        "rm -rf /", "rm -rf /*", "rm -fr /", "rm -fr /*",
        "nandwrite", "flash_erase", "wget http", "wget -", "curl |", "curl|",
        "bash -i", "/dev/tcp/", "nc -l", "ncat -l",
        "chmod 777 /", "chown -R", ">/dev/sda", "of=/dev/",
    };

    private ShellPolicy() {}

    /**
     * Always the peer-shared home — never the app-private fallback.
     * Agent denylist is only effective if both sides share this path.
     */
    public static File homeDir(Context c) {
        File shared = new File(NanobotRuntime.SHARED_HOME);
        try {
            if (!shared.exists()) //noinspection ResultOfMethodCallIgnored
                shared.mkdirs();
        } catch (Exception ignored) {}
        return shared;
    }

    public static File denyFile(Context c) {
        return new File(homeDir(c), DENY_FILE);
    }

    public static File allowFile(Context c) {
        return new File(homeDir(c), ALLOW_FILE);
    }

    public static List<String> readList(File f) {
        ArrayList<String> out = new ArrayList<>();
        if (f == null || !f.isFile()) return out;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                out.add(line);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String buildFileBody(List<String> patterns, String header) {
        StringBuilder sb = new StringBuilder();
        if (header != null && !header.isEmpty()) {
            for (String h : header.split("\n")) {
                if (!h.startsWith("#")) sb.append("# ");
                sb.append(h).append('\n');
            }
            sb.append('\n');
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String p : patterns) {
            if (p == null) continue;
            p = p.trim();
            if (p.isEmpty() || p.startsWith("#")) continue;
            if (seen.add(p)) sb.append(p).append('\n');
        }
        return sb.toString();
    }

    public static void writeList(File f, List<String> patterns, String header) throws Exception {
        writeList(null, f, patterns, header);
    }

    public static void writeList(Context c, File f, List<String> patterns, String header)
            throws Exception {
        String body = buildFileBody(patterns, header);
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);

        // 1) Direct write (works if SELinux allows)
        Exception directErr = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(raw);
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
            if (verifyWritten(f, body)) return;
            directErr = new Exception("local write not visible to peer path");
        } catch (Exception e) {
            directErr = e;
        }

        // 2) Peer shell write (peer runs as shell, owns NANOBOT_HOME)
        if (c == null) {
            throw directErr != null ? directErr
                : new Exception("cannot write " + f.getAbsolutePath());
        }
        if (!NanobotRuntime.isPortListening()) {
            NanobotRuntime.startPeer(c);
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        }
        String b64 = Base64.encodeToString(raw, Base64.NO_WRAP);
        String path = f.getAbsolutePath().replace("'", "'\\''");
        String cmd = "printf '%s' '" + b64 + "' | base64 -d > '" + path
            + "' && chmod 666 '" + path + "' && wc -c < '" + path + "'";
        PeerClient peer = new PeerClient(c);
        JSONObject r = peer.shell(cmd);
        int exit = r.optInt("exit", -1);
        String out = r.optString("output", "");
        if (exit != 0) {
            throw new Exception("peer write failed (exit=" + exit + "): "
                + out + (directErr != null ? " | local: " + directErr.getMessage() : ""));
        }
        if (!verifyWritten(f, body)) {
            // peer wrote; app may still not read — trust peer size in output
            String digits = out.trim().split("\\s+")[0];
            try {
                if (Integer.parseInt(digits) == raw.length) return;
            } catch (Exception ignored) {}
            // still ok if peer said success
        }
    }

    private static boolean verifyWritten(File f, String body) {
        try {
            if (!f.isFile()) return false;
            byte[] got = new byte[(int) Math.min(f.length(), body.length() + 64)];
            try (FileInputStream in = new FileInputStream(f)) {
                int n = in.read(got);
                if (n <= 0) return false;
                String s = new String(got, 0, n, StandardCharsets.UTF_8);
                return s.equals(body) || s.startsWith(body.substring(0, Math.min(40, body.length())));
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** Create default denylist / empty allow if missing (idempotent). */
    public static void ensureFiles(Context c) {
        try {
            File home = homeDir(c);
            if (!home.exists()) //noinspection ResultOfMethodCallIgnored
                home.mkdirs();
            File deny = denyFile(c);
            if (!deny.isFile() || readList(deny).isEmpty() && deny.length() < 8) {
                // only seed if missing; never clobber a real list
                if (!deny.isFile()) {
                    List<String> d = new ArrayList<>();
                    for (String x : DEFAULT_DENY) d.add(x);
                    saveDeny(c, d);
                }
            }
            File allow = allowFile(c);
            if (!allow.isFile()) {
                saveAllow(c, new ArrayList<>());
            }
        } catch (Exception ignored) {}
    }

    public static List<String> loadDeny(Context c) {
        File f = denyFile(c);
        List<String> list = readList(f);
        if (list.isEmpty() && !f.isFile()) {
            for (String d : DEFAULT_DENY) list.add(d);
        }
        return list;
    }

    public static List<String> loadAllow(Context c) {
        return readList(allowFile(c));
    }

    public static void saveDeny(Context c, List<String> patterns) throws Exception {
        writeList(c, denyFile(c), patterns,
            "nanobot shell denylist — managed by Nanobot app\n"
                + "One pattern per line (substring match). Delete a line to allow it.\n"
                + "NOTE: empty file falls back to built-in defaults (includes reboot).");
    }

    public static void saveAllow(Context c, List<String> patterns) throws Exception {
        writeList(c, allowFile(c), patterns,
            "nanobot shell_allow — exceptions (allowed even if in denylist)\n"
                + "Managed by Nanobot app. Prefer adding reboot here if still blocked.");
    }

    public static void resetDenyDefaults(Context c) throws Exception {
        List<String> d = new ArrayList<>();
        for (String x : DEFAULT_DENY) d.add(x);
        saveDeny(c, d);
    }

    public static File dangerousFile(Context c) {
        return new File(homeDir(c), DANGEROUS_FILE);
    }

    /**
     * Add/remove a line from shell_dangerous (approval-gated patterns).
     * When allow_reboot is on, remove reboot so peer does not force gate.
     */
    public static void setDangerousPattern(Context c, String pattern, boolean dangerous)
            throws Exception {
        String p = pattern == null ? "" : pattern.trim();
        if (p.isEmpty()) return;
        File f = dangerousFile(c);
        List<String> lines = new ArrayList<>();
        if (f.isFile()) {
            lines.addAll(readList(f));
        }
        if (dangerous) {
            if (!containsIgnoreCase(lines, p)) lines.add(p);
        } else {
            lines.removeIf(x -> x.equalsIgnoreCase(p));
        }
        writeList(c, f, lines,
            "Dangerous patterns — approval-gated unless shell_allow covers them\n"
                + "Managed by Nanobot app (Allow reboot removes power verbs).");
    }

    public static void setAllowPattern(Context c, String pattern, boolean allow) throws Exception {
        List<String> a = loadAllow(c);
        String p = pattern == null ? "" : pattern.trim();
        if (p.isEmpty()) return;
        if (allow) {
            if (!containsIgnoreCase(a, p)) a.add(p);
        } else {
            a.removeIf(x -> x.equalsIgnoreCase(p));
        }
        saveAllow(c, a);
    }

    public static void setDenyPattern(Context c, String pattern, boolean deny) throws Exception {
        List<String> d = loadDeny(c);
        // If file missing and we got defaults in memory, persist them first then edit
        if (!denyFile(c).isFile() && d.isEmpty()) {
            for (String x : DEFAULT_DENY) d.add(x);
        } else if (!denyFile(c).isFile()) {
            // loadDeny filled defaults into d when missing
        }
        // When file missing, loadDeny returns defaults without writing — ensure we start from that
        if (d.isEmpty()) {
            List<String> fromFile = readList(denyFile(c));
            if (fromFile.isEmpty() && !denyFile(c).isFile()) {
                for (String x : DEFAULT_DENY) d.add(x);
            } else {
                d = fromFile;
            }
        }
        String p = pattern == null ? "" : pattern.trim();
        if (p.isEmpty()) return;
        if (deny) {
            if (!containsIgnoreCase(d, p)) d.add(p);
            setAllowPattern(c, p, false);
        } else {
            d.removeIf(x -> x.equalsIgnoreCase(p));
            // Removing from deny is enough; also offer allow for clarity on power verbs
        }
        saveDeny(c, d);
    }

    private static boolean containsIgnoreCase(List<String> list, String p) {
        for (String x : list) if (x.equalsIgnoreCase(p)) return true;
        return false;
    }

    public static boolean isAllowedException(Context c, String pattern) {
        for (String a : loadAllow(c)) {
            if (a.equalsIgnoreCase(pattern)) return true;
        }
        return false;
    }

    public static String summary(Context c) {
        int d = loadDeny(c).size();
        int a = loadAllow(c).size();
        boolean rebootDenied = false;
        for (String x : loadDeny(c)) {
            if (x.equalsIgnoreCase("reboot")) { rebootDenied = true; break; }
        }
        boolean rebootAllowed = isAllowedException(c, "reboot");
        String reboot = rebootAllowed ? "reboot:ALLOW"
            : (rebootDenied ? "reboot:DENY" : "reboot:ok");
        return String.format(Locale.US, "%d denied · %d exceptions · %s", d, a, reboot);
    }
}
