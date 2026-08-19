package com.titanus2.nanobot;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** User privacy + LAN share switches (on-device, user controlled). */
public final class PrivacyPrefs {
    private static final String P = "titan_nanobot_privacy";

    private PrivacyPrefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    /** Share agent on LAN (0.0.0.0:8787). Default off. */
    public static boolean shareLan(Context c) {
        // Default OFF — open peer port heats + exposes agent; opt-in only (MCP/LAN).
        // One-shot: older builds defaulted true — force off once per install.
        android.content.SharedPreferences s = sp(c);
        if (!s.getBoolean("share_lan_default_off_v1", false)) {
            s.edit()
                .putBoolean("share_lan", false)
                .putBoolean("share_lan_default_off_v1", true)
                .apply();
        }
        return s.getBoolean("share_lan", false);
    }

    /**
     * Optional Atlas privilege plane (same as Debian {@code atlas-auth}).
     * Default OFF — chat / Grok session never require it.
     */
    public static boolean atlasAuth(Context c) {
        return sp(c).getBoolean("atlas_auth", false);
    }

    public static void setAtlasAuth(Context c, boolean v) {
        sp(c).edit().putBoolean("atlas_auth", v).apply();
        plane(c, "titan2_nanobot_atlas_auth", v ? "1" : "0");
        try {
            AccessLog.record(c, "atlas_auth_pref", v ? "on" : "off");
        } catch (Exception ignored) {}
    }

    public static boolean deviceControl(Context c) {
        return sp(c).getBoolean("device_control", false);
    }

    public static void setDeviceControl(Context c, boolean v) {
        sp(c).edit().putBoolean("device_control", v).apply();
        plane(c, "titan2_nanobot_device_control", v ? "1" : "0");
    }

    /**
     * Accessibility UI control (taps / global actions). Default OFF.
     * Requires system Accessibility grant for {@link NanobotA11yService}.
     */
    public static boolean a11yControl(Context c) {
        return sp(c).getBoolean("a11y_control", false);
    }

    public static void setA11yControl(Context c, boolean v) {
        sp(c).edit().putBoolean("a11y_control", v).apply();
        plane(c, "titan2_nanobot_a11y_control", v ? "1" : "0");
        try {
            AccessLog.record(c, "a11y_control", v ? "on" : "off");
        } catch (Exception ignored) {}
    }

    /** Allow DeviceOps.execBinary for allowlisted paths. Default ON when device_control. */
    public static boolean binExec(Context c) {
        return sp(c).getBoolean("bin_exec", true);
    }

    public static void setBinExec(Context c, boolean v) {
        sp(c).edit().putBoolean("bin_exec", v).apply();
        plane(c, "titan2_nanobot_bin_exec", v ? "1" : "0");
    }

    /**
     * How far the agent/MCP may touch personal storage (DCIM, Download, Documents, …).
     * Values: deny | read | full — enforced by host MCP bridge + plane flags.
     */
    public static String filesAcl(Context c) {
        return sp(c).getString("files_acl", "deny");
    }

    public static void setFilesAcl(Context c, String acl) {
        if (acl == null) acl = "deny";
        acl = acl.toLowerCase();
        if (!acl.equals("read") && !acl.equals("full")) acl = "deny";
        sp(c).edit().putString("files_acl", acl).apply();
        plane(c, "titan2_nanobot_files_acl", acl);
        // Peer reads $NANOBOT_HOME/files_acl (shell gate) — write all known homes.
        writeFilesAclFile(c, acl);
        try {
            AccessLog.record(c, "files_acl", labelFilesAcl(acl));
        } catch (Exception ignored) {}
    }

    /** Sync ACL into nanobot workdir so peer enforces even if Settings plane fails. */
    public static void writeFilesAclFile(Context c, String acl) {
        if (acl == null) acl = "deny";
        byte[] body = (acl + "\n").getBytes(StandardCharsets.UTF_8);
        String[] homes = {
            NanobotRuntime.SHARED_HOME,
            "/data/local/tmp/nanobot_home",
            c.getFilesDir().getAbsolutePath() + "/nanobot_home",
        };
        for (String home : homes) {
            try {
                File dir = new File(home);
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File f = new File(dir, "files_acl");
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(body);
                }
                //noinspection ResultOfMethodCallIgnored
                f.setReadable(true, false);
                //noinspection ResultOfMethodCallIgnored
                f.setWritable(true, false);
            } catch (Exception ignored) {}
        }
        // Best-effort peer shell write (SELinux may block app write)
        try {
            PeerClient peer = new PeerClient(c);
            if (NanobotRuntime.isPortListening()) {
                peer.shell(
                    "printf '%s\\n' '" + acl.replace("'", "") + "' > '"
                        + NanobotRuntime.SHARED_HOME + "/files_acl' && "
                        + "chmod 666 '" + NanobotRuntime.SHARED_HOME + "/files_acl' && "
                        + "printf '%s\\n' '" + acl.replace("'", "") + "' > "
                        + "/data/local/tmp/titan2_nanobot_files_acl && "
                        + "chmod 666 /data/local/tmp/titan2_nanobot_files_acl");
            }
        } catch (Exception ignored) {}
    }

    /** Short label for UI / toasts. */
    public static String labelFilesAcl(String acl) {
        if (acl == null) acl = "deny";
        switch (acl.toLowerCase()) {
            case "read":
                return "Read only — can view personal files, not change them";
            case "full":
                return "Full access — can read and change personal files";
            default:
                return "Blocked — cannot open personal photos or folders";
        }
    }

    public static String labelFilesAclShort(String acl) {
        if (acl == null) acl = "deny";
        switch (acl.toLowerCase()) {
            case "read": return "Read only";
            case "full": return "Full access";
            default: return "Blocked";
        }
    }

    public static boolean allowNetworkAgents(Context c) {
        android.content.SharedPreferences s = sp(c);
        if (!s.getBoolean("mcp_default_off_v1", false)) {
            s.edit()
                .putBoolean("allow_network_agents", false)
                .putBoolean("mcp_default_off_v1", true)
                .apply();
        }
        return s.getBoolean("allow_network_agents", false);
    }

    public static void setAllowNetworkAgents(Context c, boolean v) {
        sp(c).edit().putBoolean("allow_network_agents", v).apply();
        plane(c, "titan2_nanobot_mcp_on", v ? "1" : "0");
    }


    public static boolean allowReboot(Context c) {
        return sp(c).getBoolean("allow_reboot", false);
    }

    public static void setAllowReboot(Context c, boolean v) {
        sp(c).edit().putBoolean("allow_reboot", v).apply();
        plane(c, "titan2_nanobot_allow_reboot", v ? "1" : "0");
        // Merge into shell_allow — do not wipe other user exceptions.
        // Also strip from shell_dangerous so peer does not force password gate
        // (shell_gate 1.x residual: dangerous file ignored allow → always 425).
        try {
            ShellPolicy.ensureFiles(c);
            ShellPolicy.setAllowPattern(c, "reboot", v);
            ShellPolicy.setAllowPattern(c, "svc power reboot", v);
            if (v) {
                ShellPolicy.setDenyPattern(c, "reboot", false);
                ShellPolicy.setDenyPattern(c, "poweroff", false);
                ShellPolicy.setDenyPattern(c, "halt", false);
                ShellPolicy.setDenyPattern(c, "shutdown", false);
            }
            ShellPolicy.setDangerousPattern(c, "reboot", !v);
            ShellPolicy.setDangerousPattern(c, "poweroff", !v);
            ShellPolicy.setDangerousPattern(c, "halt", !v);
            ShellPolicy.setDangerousPattern(c, "shutdown", !v);
        } catch (Exception ignored) {}
    }

    /** Absolute path of last selected on-device GGUF (so Local uses the model in the list). */
    public static String selectedLocalModelPath(Context c) {
        return sp(c).getString("selected_local_gguf", "");
    }

    public static void setSelectedLocalModelPath(Context c, String absPath) {
        sp(c).edit().putString("selected_local_gguf",
            absPath == null ? "" : absPath).apply();
    }

    /** Track GGUFs installed via the Custom (paste) path — probe tools on select/start. */
    public static void markCustomGguf(Context c, String filenameOrPath) {
        if (filenameOrPath == null || filenameOrPath.isEmpty()) return;
        String leaf = filenameOrPath;
        int s = leaf.lastIndexOf('/');
        if (s >= 0) leaf = leaf.substring(s + 1);
        String set = sp(c).getString("custom_gguf_set", "");
        String token = "|" + leaf.toLowerCase() + "|";
        if (set.contains(token)) return;
        sp(c).edit().putString("custom_gguf_set", set + token).apply();
    }

    public static boolean isCustomGguf(Context c, String filenameOrPath) {
        if (filenameOrPath == null || filenameOrPath.isEmpty()) return false;
        String leaf = filenameOrPath;
        int s = leaf.lastIndexOf('/');
        if (s >= 0) leaf = leaf.substring(s + 1);
        // Not a curated preset → treat as custom if marked or unknown non-preset
        if (LlamaManager.isPresetFilename(leaf)) return false;
        String set = sp(c).getString("custom_gguf_set", "");
        String token = "|" + leaf.toLowerCase() + "|";
        if (set.contains(token)) return true;
        // Any non-preset on-disk GGUF is "custom" for tool probing
        return leaf.toLowerCase().endsWith(".gguf");
    }

    /**
     * Cached tool-call support for a model id/path.
     * @return Boolean.TRUE/FALSE if probed, null if never tested
     */
    public static Boolean toolsSupported(Context c, String modelId) {
        if (modelId == null || modelId.isEmpty()) return null;
        String key = "tools_ok:" + modelId;
        if (!sp(c).contains(key)) {
            // also try basename key
            String leaf = modelId;
            int i = leaf.lastIndexOf('/');
            if (i >= 0) leaf = leaf.substring(i + 1);
            key = "tools_ok:" + leaf;
            if (!sp(c).contains(key)) return null;
        }
        return sp(c).getBoolean(key, false);
    }

    public static void setToolsSupported(Context c, String modelId, boolean ok) {
        if (modelId == null || modelId.isEmpty()) return;
        SharedPreferences.Editor e = sp(c).edit();
        e.putBoolean("tools_ok:" + modelId, ok);
        String leaf = modelId;
        int i = leaf.lastIndexOf('/');
        if (i >= 0) leaf = leaf.substring(i + 1);
        e.putBoolean("tools_ok:" + leaf, ok);
        e.apply();
        AccessLog.record(c, ok ? "tools_probe_ok" : "tools_probe_fail", modelId);
    }

    /** Enter key sends message (Shift+Enter = newline). Default on for hardware keyboard. */
    public static boolean enterAsSend(Context c) {
        return sp(c).getBoolean("enter_as_send", true);
    }

    public static void setEnterAsSend(Context c, boolean v) {
        sp(c).edit().putBoolean("enter_as_send", v).apply();
    }

    /** Foreground agent service + peer process. Default off. */
    public static boolean serviceEnabled(Context c) {
        android.content.SharedPreferences s = sp(c);
        if (!s.getBoolean("service_default_off_v1", false)) {
            s.edit()
                .putBoolean("service_enabled", false)
                .putBoolean("service_default_off_v1", true)
                .apply();
        }
        return s.getBoolean("service_enabled", false);
    }

    public static void setServiceEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("service_enabled", v).apply();
        plane(c, "titan2_nanobot_service", v ? "1" : "0");
        plane(c, "titan2_nanobot_on", v ? "1" : "0");
    }

    /**
     * Optional on-device llama.cpp + GGUF download/run.
     * Default off — cloud/other OpenAI URLs work without this.
     */
    public static boolean localLlamaEnabled(Context c) {
        return sp(c).getBoolean("local_llama_enabled", false);
    }

    public static void setLocalLlamaEnabled(Context c, boolean v) {
        boolean prev = localLlamaEnabled(c);
        sp(c).edit().putBoolean("local_llama_enabled", v).apply();
        plane(c, "titan2_nanobot_local_llama", v ? "1" : "0");
        if (prev && !v) {
            // Turning off: stop any on-device server so it doesn't keep burning RAM
            try {
                LlamaRuntime.stop(c);
            } catch (Exception ignored) {}
            AccessLog.record(c, "local_llama_off", "On-device llama.cpp disabled");
        } else if (!prev && v) {
            AccessLog.record(c, "local_llama_on", "On-device llama.cpp enabled (optional)");
        }
    }

    public static void setShareLan(Context c, boolean v) {
        boolean prev = shareLan(c);
        sp(c).edit().putBoolean("share_lan", v).apply();
        plane(c, "titan2_nanobot_share_lan", v ? "1" : "0");
        if (prev != v) {
            AccessLog.record(c, v ? "lan_share_on" : "lan_share_off",
                v ? "LAN API share enabled" : "LAN API share disabled");
        }
    }

    public static void publishAll(Context c) {
        // do not re-fire lan log: write planes only
        plane(c, "titan2_nanobot_share_lan", shareLan(c) ? "1" : "0");
        plane(c, "titan2_nanobot_service", serviceEnabled(c) ? "1" : "0");
        plane(c, "titan2_nanobot_on", serviceEnabled(c) ? "1" : "0");
        plane(c, "titan2_nanobot_mcp_on", allowNetworkAgents(c) ? "1" : "0");
        setDeviceControl(c, deviceControl(c));
        setFilesAcl(c, filesAcl(c));
        setAllowNetworkAgents(c, allowNetworkAgents(c));
        setAllowReboot(c, allowReboot(c));
        plane(c, "titan2_nanobot_atlas_auth", atlasAuth(c) ? "1" : "0");
    }

    private static void plane(Context c, String name, String body) {
        for (String dir : new String[] {
                "/data/misc/titan2",
                "/data/local/tmp",
                c.getFilesDir().getAbsolutePath()
        }) {
            try {
                File f = new File(dir, name);
                File p = f.getParentFile();
                if (p != null && !p.exists()) p.mkdirs();
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {}
        }
        try {
            Settings.Global.putString(c.getContentResolver(), name, body);
        } catch (Exception ignored) {}
    }
}
