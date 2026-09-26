package com.titanus2.atlas;

import android.content.Context;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Debian /home is debian-home.img in this app's files directory.
 * The system LP is the OS. Wipe and Reinstall recreate this image.
 */
public final class HomeImage {
    public static final String EXPORT_PATH = "/data/media/0/Atlas/debian-home.img";

    private HomeImage() {}

    public static void publish(Context c) {
        if (c == null) return;
        AtlasPrefs.setHomeImgG(c, AtlasPrefs.homeImgG(c));
        AtlasPrefs.setSdcardRw(c, AtlasPrefs.sdcardRw(c));
    }

    public static String ensure(Context c) {
        publish(c);
        return run(c, "ensure");
    }

    public static String grow(Context c) {
        publish(c);
        return run(c, "grow " + (AtlasPrefs.homeImgG(c) * 1024));
    }

    public static String recreate(Context c) {
        publish(c);
        return run(c, "recreate " + (AtlasPrefs.homeImgG(c) * 1024));
    }

    public static String exportImage(Context c) {
        publish(c);
        return run(c, "export " + EXPORT_PATH);
    }

    public static String load(Context c, String src) {
        if (src == null || src.isEmpty()) return "no file";
        publish(c);
        return run(c, "load '" + src.replace("'", "'\\''") + "'");
    }

    public static String applySdcard(Context c) {
        publish(c);
        return run(c, "sdcard");
    }

    public static String status(Context c) {
        publish(c);
        return run(c, "status");
    }

    private static String run(Context c, String args) {
        String script = scriptPath(c);
        if (script == null) return "home helper missing";
        return root(script + " " + args);
    }

    private static String scriptPath(Context c) {
        try {
            if (c != null) NativeBin.ensureExtracted(c);
        } catch (Exception ignored) {
        }
        String[] paths = {
            "/system/bin/atlas-home-img.sh",
            c != null ? new File(NativeBin.binDir(c), "atlas-home-img.sh").getAbsolutePath() : "",
            "/data/user/0/com.titanus2.atlas/files/bin/atlas-home-img.sh",
            "/data/data/com.titanus2.atlas/files/bin/atlas-home-img.sh"
        };
        for (String p : paths) {
            if (p == null || p.isEmpty()) continue;
            File f = new File(p);
            if (f.isFile() && f.canRead()) return p;
        }
        return null;
    }

    private static String root(String cmd) {
        String[] sus = {
            "/system/bin/su",
            "/debug_ramdisk/su",
            "/system/xbin/su",
            HybridEnsure.resolveRealSu(),
            "su"
        };
        for (String su : sus) {
            if (su == null || su.isEmpty()) continue;
            try {
                Process p = new ProcessBuilder(su, "0", "sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
                boolean done = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
                InputStream in = p.getInputStream();
                byte[] buf = in.readNBytes(8192);
                if (!done) p.destroy();
                String s = new String(buf, StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) return s;
                if (done && p.exitValue() == 0) return "ok";
            } catch (Exception ignored) {
            }
        }
        return "home command failed";
    }
}
