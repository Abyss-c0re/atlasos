package com.titanus2.atlas;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/** Atlas host prefs (DeviceDefault settings surface — not Cube chrome). */
public final class AtlasPrefs {
    private static final String P = "atlas_host";

    // Default Termux-like: white on black
    public static final int DEFAULT_FG = 0xFFFFFFFF;
    public static final int DEFAULT_BG = 0xFF000000;
    public static final int DEFAULT_CURSOR = 0xFFFFFFFF;

    private AtlasPrefs() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    public static int fontSp(Context c) {
        return Math.max(8, Math.min(32, p(c).getInt("font_sp", 13)));
    }

    public static void setFontSp(Context c, int sp) {
        p(c).edit().putInt("font_sp", Math.max(8, Math.min(32, sp))).apply();
    }

    public static int fgColor(Context c) {
        return p(c).getInt("fg_color", DEFAULT_FG);
    }

    public static void setFgColor(Context c, int argb) {
        p(c).edit().putInt("fg_color", argb | 0xFF000000).apply();
    }

    public static int bgColor(Context c) {
        return p(c).getInt("bg_color", DEFAULT_BG);
    }

    public static void setBgColor(Context c, int argb) {
        p(c).edit().putInt("bg_color", argb | 0xFF000000).apply();
    }

    public static int cursorColor(Context c) {
        return p(c).getInt("cursor_color", DEFAULT_CURSOR);
    }

    public static void setCursorColor(Context c, int argb) {
        p(c).edit().putInt("cursor_color", argb | 0xFF000000).apply();
    }

    /** Preset themes: dark, light, green, amber, cyan */
    public static void applyThemePreset(Context c, String name) {
        if (name == null) name = "dark";
        switch (name) {
            case "light":
                setFgColor(c, 0xFF212121);
                setBgColor(c, 0xFFFAFAFA);
                setCursorColor(c, 0xFF000000);
                break;
            case "green":
                setFgColor(c, 0xFF33FF33);
                setBgColor(c, 0xFF0A0A0A);
                setCursorColor(c, 0xFF33FF33);
                break;
            case "amber":
                setFgColor(c, 0xFFFFB000);
                setBgColor(c, 0xFF1A1200);
                setCursorColor(c, 0xFFFFB000);
                break;
            case "cyan":
                setFgColor(c, 0xFFB2EBF2);
                setBgColor(c, 0xFF0D1B1E);
                setCursorColor(c, 0xFF00E5FF);
                break;
            case "dark":
            default:
                setFgColor(c, DEFAULT_FG);
                setBgColor(c, DEFAULT_BG);
                setCursorColor(c, DEFAULT_CURSOR);
                break;
        }
        p(c).edit().putString("theme_preset", name).apply();
    }

    public static String themePreset(Context c) {
        return p(c).getString("theme_preset", "dark");
    }

    public static String colorHex(int argb) {
        return String.format("#%06X", (0xFFFFFF & argb));
    }

    public static int parseColor(String hex, int fallback) {
        if (hex == null) return fallback;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        try {
            if (s.length() == 6) {
                return 0xFF000000 | Integer.parseInt(s, 16);
            }
            if (s.length() == 8) {
                return (int) Long.parseLong(s, 16);
            }
        } catch (Exception ignored) {
        }
        try {
            return Color.parseColor(hex.startsWith("#") ? hex : "#" + hex);
        } catch (Exception e) {
            return fallback;
        }
    }

    public static boolean keepScreenOn(Context c) {
        return p(c).getBoolean("keep_screen_on", true);
    }

    public static void setKeepScreenOn(Context c, boolean on) {
        p(c).edit().putBoolean("keep_screen_on", on).apply();
    }

    /**
     * When true, new sessions prefer Debian hybrid plane (if enterable).
     * Default <b>false</b> — product rootless-first: Android shell works without
     * app su. Deb is opt-in (Settings / And↔Deb). System init may prepare mounts
     * (not Deb session autostart). Kernel may stay rooted on lab; app must not
     * require Superuser for open.
     */
    public static boolean privilegedHybrid(Context c) {
        return p(c).getBoolean("privileged_hybrid", false);
    }

    public static void setPrivilegedHybrid(Context c, boolean on) {
        p(c).edit().putBoolean("privileged_hybrid", on).apply();
        // MainActivity restarts shell on resume so android↔debian applies without manual ↻
        requestSessionRestart(c);
    }

    /** One-shot: MainActivity should rebuild the visible shell (mode switch / ensure). */
    public static void requestSessionRestart(Context c) {
        // Never queue a shell restart while biometric auth UI is up / just closed —
        // that killed live Deb PTYs mid `apt` / `sudo` after finger OK.
        if (isAuthUiQuietPeriod(c)) return;
        p(c).edit().putBoolean("restart_session_once", true).apply();
    }

    /** Drop a pending auto-restart (auth resume / live Deb keep). */
    public static void clearSessionRestart(Context c) {
        p(c).edit().putBoolean("restart_session_once", false).commit();
    }

    public static boolean consumeSessionRestart(Context c) {
        if (!p(c).getBoolean("restart_session_once", false)) return false;
        // Still holding auth quiet window → leave flag for a later resume
        if (isAuthUiQuietPeriod(c)) return false;
        p(c).edit().putBoolean("restart_session_once", false).apply();
        return true;
    }

    /**
     * AuthPromptActivity is open or just finished. MainActivity must not rebuild
     * the PTY when returning from biometrics (apt/sudo path).
     * Use commit() so onResume never races SharedPreferences.apply().
     * Quiet after close is long enough for biometric sheet teardown + task switch.
     */
    public static void markAuthUi(Context c, boolean showing) {
        long until = showing
            ? Long.MAX_VALUE / 4
            : System.currentTimeMillis() + 30_000L;
        p(c).edit()
            .putBoolean("auth_ui_showing", showing)
            .putLong("auth_ui_quiet_until", until)
            .commit();
        if (!showing) {
            // Auth just finished — never let a deferred ensure-flag kill the shell.
            clearSessionRestart(c);
        }
    }

    public static boolean isAuthUiQuietPeriod(Context c) {
        if (p(c).getBoolean("auth_ui_showing", false)) return true;
        return System.currentTimeMillis() < p(c).getLong("auth_ui_quiet_until", 0L);
    }

    /** @deprecated use privilegedHybrid */
    public static boolean autoPriv(Context c) {
        return privilegedHybrid(c);
    }

    /** @deprecated use setPrivilegedHybrid */
    public static void setAutoPriv(Context c, boolean on) {
        setPrivilegedHybrid(c, on);
    }

    public static int hybridSizeG(Context c) {
        return Math.max(2, Math.min(32, p(c).getInt("hybrid_size_g", 8)));
    }

    public static void setHybridSizeG(Context c, int g) {
        p(c).edit().putInt("hybrid_size_g", Math.max(2, Math.min(32, g))).apply();
    }

    /**
     * Product: Authentication Agent FGS stays up without open terminals so
     * Remote ADB / hybrid sudo biometrics work with Wi‑Fi off (Tailscale/LTE).
     */
    public static boolean authAgentAlways(Context c) {
        return p(c).getBoolean("auth_agent_always", true);
    }

    public static void setAuthAgentAlways(Context c, boolean on) {
        p(c).edit().putBoolean("auth_agent_always", on).apply();
    }

    /**
     * Master biometric enforcement. Default <b>off</b> — privileges are granted
     * by the Privilege section; bio is optional enforcement on top.
     * When false, agent auto-grants auth reqs (lab / agent screencap heal).
     */
    public static boolean biometricAuth(Context c) {
        return p(c).getBoolean("biometric_auth", false);
    }

    public static void setBiometricAuth(Context c, boolean on) {
        p(c).edit().putBoolean("biometric_auth", on).apply();
        publishBioPlane(c);
        publishPrivilegePlane(c);
    }

    /**
     * Privilege: Debian may run Android bins (screencap, am, pm via android-exec).
     * Default <b>on</b> when hybrid is on — this is access, not bio.
     */
    public static boolean privAndroidAccess(Context c) {
        return p(c).getBoolean("priv_android_access", true);
    }

    public static void setPrivAndroidAccess(Context c, boolean on) {
        p(c).edit().putBoolean("priv_android_access", on).apply();
        publishPrivilegePlane(c);
    }

    /** Raw bio toggle for Android access (UI); effective only if biometricAuth. */
    public static boolean bioAndroidAccessPref(Context c) {
        return p(c).getBoolean("bio_android_access", false);
    }

    /**
     * Bio when Debian runs Android bins. Only applies if {@link #biometricAuth}.
     * Default <b>off</b>.
     */
    public static boolean bioAndroidAccess(Context c) {
        return biometricAuth(c) && bioAndroidAccessPref(c);
    }

    public static void setBioAndroidAccess(Context c, boolean on) {
        p(c).edit().putBoolean("bio_android_access", on).apply();
        publishBioPlane(c);
    }

    /**
     * Privilege: Debian-plane sudo/su (elevate inside Deb). Default on with hybrid.
     */
    public static boolean privDebianSudo(Context c) {
        return p(c).getBoolean("priv_debian_sudo", true);
    }

    public static void setPrivDebianSudo(Context c, boolean on) {
        p(c).edit().putBoolean("priv_debian_sudo", on).apply();
        publishPrivilegePlane(c);
    }

    public static boolean bioDebianSudoPref(Context c) {
        return p(c).getBoolean("bio_debian_sudo", false);
    }

    /**
     * Bio for Debian-plane sudo/su. Only if {@link #biometricAuth}. Default off.
     * apt never uses this path for package ops.
     */
    public static boolean bioDebianSudo(Context c) {
        return biometricAuth(c) && bioDebianSudoPref(c);
    }

    public static void setBioDebianSudo(Context c, boolean on) {
        p(c).edit().putBoolean("bio_debian_sudo", on).apply();
        publishBioPlane(c);
    }

    /**
     * Privilege: Android-plane su/sudo (host elevate). Default on.
     */
    public static boolean privAndroidSu(Context c) {
        return p(c).getBoolean("priv_android_su", true);
    }

    public static void setPrivAndroidSu(Context c, boolean on) {
        p(c).edit().putBoolean("priv_android_su", on).apply();
        publishPrivilegePlane(c);
    }

    public static boolean bioAndroidSuPref(Context c) {
        return p(c).getBoolean("bio_android_su", false);
    }

    /**
     * Bio for Android-plane su/sudo. Only if {@link #biometricAuth}. Default off
     * (product: privileges primary; bio optional — unattended lab heal).
     */
    public static boolean bioAndroidSu(Context c) {
        return biometricAuth(c) && bioAndroidSuPref(c);
    }

    public static void setBioAndroidSu(Context c, boolean on) {
        p(c).edit().putBoolean("bio_android_su", on).apply();
        publishBioPlane(c);
    }

    /**
     * Publish privilege plane (what is allowed) — independent of bio enforcement.
     * Deb android-exec reads titan2_atlas_priv_android_access: 1=may run Android bins.
     */
    public static void publishPrivilegePlane(Context c) {
        String a = privAndroidAccess(c) ? "1" : "0";
        String d = privDebianSudo(c) ? "1" : "0";
        String s = privAndroidSu(c) ? "1" : "0";
        String[][] keys = {
            { "titan2_atlas_priv_android_access", a },
            { "titan2_atlas_priv_debian_sudo", d },
            { "titan2_atlas_priv_android_su", s },
        };
        String[] roots = {
            "/data/local/tmp",
            "/data/misc/titan2",
            NativeBin.AUTH_ON_LP,
        };
        for (String root : roots) {
            java.io.File dir = new java.io.File(root);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            for (String[] kv : keys) {
                writePlaneLine(new java.io.File(dir, kv[0]), kv[1]);
            }
        }
        writePlaneLine(new java.io.File("/data/local/tmp/titan2_atlas_priv_plane"),
            "android_access=" + a + "\ndebian_sudo=" + d + "\nandroid_su=" + s + "\n");
        // Keep bio plane in sync so android_auth never stays stale "1" after privilege publish
        publishBioPlane(c);
    }

    /**
     * Publish bio toggles for Deb/shell. android_auth=1 only when master bio AND
     * bio Android access — otherwise Deb executes Android bins without finger
     * (privilege plane still gates allow/deny).
     */
    public static void publishBioPlane(Context c) {
        String master = biometricAuth(c) ? "1" : "0";
        String a = bioAndroidAccess(c) ? "1" : "0";
        String d = bioDebianSudo(c) ? "1" : "0";
        String s = bioAndroidSu(c) ? "1" : "0";
        // Effective: bio for android-exec only if privilege also on
        String authAndroid = (privAndroidAccess(c) && bioAndroidAccess(c)) ? "1" : "0";
        String[][] keys = {
            { "titan2_atlas_bio_master", master },
            { "titan2_atlas_bio_android_access", a },
            { "titan2_atlas_bio_debian_sudo", d },
            { "titan2_atlas_bio_android_su", s },
            { "titan2_atlas_android_auth", authAndroid },
        };
        String[] roots = {
            "/data/local/tmp",
            "/data/misc/titan2",
            NativeBin.AUTH_ON_LP,
        };
        for (String root : roots) {
            java.io.File dir = new java.io.File(root);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            for (String[] kv : keys) {
                writePlaneLine(new java.io.File(dir, kv[0]), kv[1]);
            }
        }
        // Env-friendly single file
        writePlaneLine(new java.io.File("/data/local/tmp/titan2_atlas_bio_plane"),
            "android_access=" + a + "\ndebian_sudo=" + d + "\nandroid_su=" + s + "\n");
    }

    private static void writePlaneLine(java.io.File f, String body) {
        try {
            java.io.File parent = f.getParentFile();
            if (parent != null) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                out.write((body.endsWith("\n") ? body : body + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
        } catch (Exception ignored) {
        }
    }

    public static boolean keepAlive(Context c) {
        return p(c).getBoolean("keep_alive", true);
    }

    public static void setKeepAlive(Context c, boolean on) {
        p(c).edit().putBoolean("keep_alive", on).apply();
    }

    public static int liveSessionCount(Context c) {
        return Math.max(0, p(c).getInt("live_sessions", 0));
    }

    public static void setLiveSessionCount(Context c, int n) {
        p(c).edit().putInt("live_sessions", Math.max(0, n)).apply();
    }

    public static String lastSeat(Context c) {
        return p(c).getString("last_seat", "atlas");
    }

    public static void setLastSeat(Context c, String name) {
        if (name == null || name.isEmpty()) return;
        p(c).edit().putString("last_seat", name).apply();
    }

    public static String lastSnap(Context c) {
        return p(c).getString("last_snap", "");
    }

    public static void setLastSnap(Context c, String snap) {
        p(c).edit().putString("last_snap", snap != null ? snap : "").apply();
    }
}
