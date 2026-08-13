package com.titanus2.atlas;

import java.io.File;

/** JNI front for pure-C termgrid (VT cell buffer). No WebView. */
public final class TermGrid {
    private static boolean loaded;

    private TermGrid() {}

    public static synchronized boolean ensureLoaded(File soFile) {
        if (loaded) return true;
        // 1) APK jniLibs lib/arm64-v8a/libatlasterm.so
        try {
            System.loadLibrary("atlasterm");
            loaded = true;
            return true;
        } catch (Throwable ignored) {
        }
        // 2) extracted assets/bin path
        try {
            if (soFile != null && soFile.isFile()) {
                System.load(soFile.getAbsolutePath());
                loaded = true;
                return true;
            }
        } catch (Throwable t) {
            loaded = false;
        }
        return false;
    }

    public static native void nativeInit(int cols, int rows);
    public static native void nativeFeed(byte[] data);
    public static native void nativeReset();
    public static native int nativeCols();
    public static native int nativeRows();
    public static native boolean nativeDirty();
    public static native void nativeClearDirty();
    public static native int nativeCx();
    public static native int nativeCy();
    /** [n, ch,fg,bg, ch,fg,bg, ...] */
    public static native int[] nativeRow(int row);
}
