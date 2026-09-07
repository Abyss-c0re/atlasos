package com.titanus2.cubecontact;

import java.io.File;

/** Same cells.bin SoT as libcubeviz (cube_viz_path.c). */
public final class CubeVizPath {
    private CubeVizPath() {}
    public static final String ANDROID_DIR = "/data/local/tmp/cubebrain_viz";
    public static final String TMP_DIR = "/tmp/cubebrain_viz";

    public static File dir() {
        File found = findCells();
        File parent = found.getParentFile();
        return parent != null ? parent : new File(ANDROID_DIR);
    }

    public static File cells() { return new File(dir(), "cells.bin"); }
    public static File nodes() { return new File(dir(), "nodes.tsv"); }
    public static File virtual() { return new File(dir(), "virtual.tsv"); }

    public static File findCells() {
        File[] cands = {
            new File(ANDROID_DIR, "cells.bin"),
            new File(TMP_DIR, "cells.bin"),
        };
        File best = null;
        long mt = -1L;
        for (File f : cands) {
            if (f.isFile() && f.lastModified() >= mt) {
                mt = f.lastModified();
                best = f;
            }
        }
        return best != null ? best : new File(ANDROID_DIR, "cells.bin");
    }
}
