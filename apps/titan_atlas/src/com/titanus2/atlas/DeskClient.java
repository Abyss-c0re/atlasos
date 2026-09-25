package com.titanus2.atlas;

import android.content.Context;

import java.io.File;

/** Present-socket viewer. Pixels are Android ARGB_8888. */
public final class DeskClient {
    private static boolean loaded;

    private DeskClient() {}

    public static boolean load(Context c) {
        if (loaded) return true;
        NativeBin.ensureNativeLibs(c);
        String[] paths = {
            new File(c.getDir("lib", Context.MODE_PRIVATE), "libatlasdesk.so").getAbsolutePath(),
            new File(NativeBin.binDir(c), "libatlasdesk.so").getAbsolutePath(),
            "/data/data/com.titanus2.atlas/files/bin/libatlasdesk.so",
        };
        for (String p : paths) {
            File f = new File(p);
            if (!f.isFile() || f.length() < 1000) continue;
            try {
                System.load(f.getAbsolutePath());
                loaded = true;
                return true;
            } catch (UnsatisfiedLinkError ignored) {
            }
        }
        return false;
    }

    /** 0 copied, 1 buffer too small (meta has w/h), 2 no frame, -1 error. */
    public static native int pull(String path, int[] pixels, int[] meta);

    public static native int key(String path, int code, int down);

    /** abs=1: dx,dy are desktop pixels. abs=0: relative pad/mouse delta. */
    public static native int pointer(String path, int dx, int dy, int buttons, int wheel, int abs);
}
