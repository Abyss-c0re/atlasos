package com.titanus2.atlas;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Magisk-style access: allow / ask / deny for Android commands and paths.
 * Published to the auth plane so Deb {@code atlas-android} and hybrid bind
 * honor the same file.
 */
public final class AtlasPolicy {
    public static final int ALLOW = 0;
    public static final int ASK = 1;
    public static final int DENY = 2;

    public static final String[] COMMANDS = {
        "screencap", "am", "pm", "settings", "input", "wm",
        "setprop", "service", "content", "cmd",
        "dumpsys", "getprop", "logcat",
        "cat", "write", "ls",
    };

    private AtlasPolicy() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("atlas_host", 0);
    }

    public static String modeLabel(int m) {
        if (m == ALLOW) return "allow";
        if (m == DENY) return "deny";
        return "ask";
    }

    public static int parseMode(String s) {
        if (s == null) return ASK;
        if ("allow".equals(s) || "1".equals(s)) return ALLOW;
        if ("deny".equals(s) || "0".equals(s) || "block".equals(s)) return DENY;
        return ASK;
    }

    public static int nextMode(int m) {
        if (m == ALLOW) return ASK;
        if (m == ASK) return DENY;
        return ALLOW;
    }

    public static int defaultCmd(String name) {
        if ("getprop".equals(name) || "dumpsys".equals(name)
                || "logcat".equals(name)) return ALLOW;
        return ASK;
    }

    public static int cmdMode(Context c, String name) {
        if (name == null) return ASK;
        String key = "pol_cmd_" + name;
        if (!p(c).contains(key)) return defaultCmd(name);
        return p(c).getInt(key, defaultCmd(name));
    }

    public static void setCmdMode(Context c, String name, int mode) {
        p(c).edit().putInt("pol_cmd_" + name, mode).apply();
        publish(c);
    }

    public static boolean isBuiltinCmd(String name) {
        if (name == null) return false;
        for (String n : COMMANDS) {
            if (n.equals(name)) return true;
        }
        return false;
    }

    public static java.util.List<String> extraCommands(Context c) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        java.util.Set<String> s = p(c).getStringSet("pol_extra_cmds",
            java.util.Collections.emptySet());
        if (s != null) {
            out.addAll(s);
            java.util.Collections.sort(out);
        }
        return out;
    }

    /**
     * Add a name or absolute path to Access. Returns null on success.
     * Deb ELFs are also installed as managed wraps (symlink via atlas-auth).
     */
    public static String addCommand(Context c, String spec) {
        if (spec == null) return "empty";
        spec = spec.trim();
        if (spec.isEmpty()) return "empty";
        String name = AtlasAuth.sanitizeScope(spec);
        if (name.isEmpty() || "ask".equals(name)) return "empty";
        if (isBuiltinCmd(name)) return "already listed";
        if (AtlasPrefs.isReservedManaged(name)) return "reserved";
        java.util.LinkedHashSet<String> next = new java.util.LinkedHashSet<>();
        java.util.Set<String> cur = p(c).getStringSet("pol_extra_cmds",
            java.util.Collections.emptySet());
        if (cur != null) next.addAll(cur);
        next.add(name);
        p(c).edit().putStringSet("pol_extra_cmds", next).apply();
        if (!p(c).contains("pol_cmd_" + name)) {
            p(c).edit().putInt("pol_cmd_" + name, ASK).apply();
        }
        publish(c);
        String wrap = AtlasPrefs.addManagedBin(c, spec);
        if (wrap != null && !"not found".equals(wrap) && !"already managed".equals(wrap)) {
            return wrap;
        }
        return null;
    }

    public static void removeCommand(Context c, String name) {
        name = AtlasAuth.sanitizeScope(name);
        if (name.isEmpty() || isBuiltinCmd(name)) return;
        java.util.LinkedHashSet<String> next = new java.util.LinkedHashSet<>();
        java.util.Set<String> cur = p(c).getStringSet("pol_extra_cmds",
            java.util.Collections.emptySet());
        if (cur != null) {
            for (String n : cur) {
                if (n != null && !n.equals(name)) next.add(n);
            }
        }
        p(c).edit().putStringSet("pol_extra_cmds", next)
            .remove("pol_cmd_" + name).apply();
        AtlasPrefs.removeManagedBin(c, name);
        publish(c);
    }

    /** Shared storage bind into Deb: allow=bind, ask/deny=no bind. */
    public static int storageMode(Context c) {
        return p(c).getInt("pol_storage", ASK);
    }

    public static void setStorageMode(Context c, int mode) {
        p(c).edit().putInt("pol_storage", mode).apply();
        publish(c);
    }

    public static void publish(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("# atlas-auth policy  allow|ask|deny\n");
        sb.append("# Deb→Android: only `android <tool>`\n");
        sb.append("default=ask\n");
        sb.append("storage=").append(modeLabel(storageMode(c))).append('\n');
        sb.append("bridge=android\n");
        for (String n : COMMANDS) {
            sb.append("cmd.").append(n).append('=')
                .append(modeLabel(cmdMode(c, n))).append('\n');
        }
        for (String n : extraCommands(c)) {
            if (isBuiltinCmd(n)) continue;
            sb.append("cmd.").append(n).append('=')
                .append(modeLabel(cmdMode(c, n))).append('\n');
        }
        String text = sb.toString();
        String[] roots = {
            "/data/local/tmp",
            "/data/misc/titan2",
            NativeBin.AUTH_ON_LP,
        };
        for (String root : roots) {
            writeFile(new java.io.File(root, "policy"), text);
        }
        writeFile(new java.io.File("/data/local/tmp/titan2_atlas_storage"),
            modeLabel(storageMode(c)) + "\n");
        writeFile(new java.io.File(NativeBin.AUTH_ON_LP, "titan2_atlas_storage"),
            modeLabel(storageMode(c)) + "\n");
    }

    private static void writeFile(java.io.File f, String body) {
        if (f == null || body == null) return;
        try {
            java.io.File dir = f.getParentFile();
            if (dir != null) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            w.write(body);
            w.flush();
            w.close();
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
        } catch (Exception ignored) {
        }
    }
}
