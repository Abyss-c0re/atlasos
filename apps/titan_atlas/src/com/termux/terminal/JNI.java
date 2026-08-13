package com.termux.terminal;

/**
 * Native methods for creating and managing pseudoterminal subprocesses. C code is in jni/termux.c.
 */
final class JNI {

    static {
        // Prefer APK jniLibs; on priv-app loadLibrary often fails. NativeBin may
        // already have System.load()'d app_lib — treat that as success.
        try {
            System.loadLibrary("atlaspty");
        } catch (UnsatisfiedLinkError e) {
            if (!loadAtlasPtyFallback()) {
                throw e;
            }
        }
    }

    private static boolean tryLoadPath(String p) {
        try {
            java.io.File f = new java.io.File(p);
            if (!f.isFile() || f.length() < 1000) return false;
            System.load(f.getAbsolutePath());
            return true;
        } catch (UnsatisfiedLinkError e) {
            // Already loaded by Application/NativeBin via absolute path
            String m = e.getMessage();
            if (m != null && (m.contains("already loaded") || m.contains("already been loaded"))) {
                return true;
            }
            return false;
        }
    }

    private static boolean loadAtlasPtyFallback() {
        // Context.getDir("lib") → …/app_lib (product path, no root)
        String[] candidates = {
            "/data/user/0/com.titanus2.atlas/app_lib/libatlaspty.so",
            "/data/data/com.titanus2.atlas/app_lib/libatlaspty.so",
            "/data/data/com.titanus2.atlas/files/bin/libatlaspty.so",
            "/data/user/0/com.titanus2.atlas/files/bin/libatlaspty.so",
            "/data/user/0/com.titanus2.atlas/code_cache/lib/libatlaspty.so",
            "/data/data/com.titanus2.atlas/code_cache/lib/libatlaspty.so",
            "/system/priv-app/TitanAtlas/lib/arm64/libatlaspty.so",
            "/system/priv-app/TitanAtlas/lib/arm64-v8a/libatlaspty.so",
        };
        for (String p : candidates) {
            if (tryLoadPath(p)) return true;
        }
        try {
            java.io.File dataApp = new java.io.File("/data/app");
            java.io.File[] roots = dataApp.listFiles();
            if (roots != null) {
                for (java.io.File r : roots) {
                    if (r == null || r.getName() == null) continue;
                    if (!r.getName().contains("com.titanus2.atlas")) continue;
                    for (String rel : new String[] {
                        "lib/arm64/libatlaspty.so",
                        "lib/arm64-v8a/libatlaspty.so"
                    }) {
                        if (tryLoadPath(new java.io.File(r, rel).getAbsolutePath())) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // NativeBin already loaded — probe by re-loading short name fail is OK if
        // we find the file was mmap'd: try absolute load of app_lib again as truth.
        return tryLoadPath("/data/user/0/com.titanus2.atlas/app_lib/libatlaspty.so")
            || tryLoadPath("/data/data/com.titanus2.atlas/app_lib/libatlaspty.so");
    }

    /**
     * Create a subprocess. Differs from {@link ProcessBuilder} in that a pseudoterminal is used to communicate with the
     * subprocess.
     * <p/>
     * Callers are responsible for calling {@link #close(int)} on the returned file descriptor.
     *
     * @param cmd       The command to execute
     * @param cwd       The current working directory for the executed command
     * @param args      An array of arguments to the command
     * @param envVars   An array of strings of the form "VAR=value" to be added to the environment of the process
     * @param processId A one-element array to which the process ID of the started process will be written.
     * @return the file descriptor resulting from opening /dev/ptmx master device. The sub process will have opened the
     * slave device counterpart (/dev/pts/$N) and have it as stdint, stdout and stderr.
     */
    public static native int createSubprocess(String cmd, String cwd, String[] args, String[] envVars, int[] processId, int rows, int columns, int cellWidth, int cellHeight);

    /** Set the window size for a given pty, which allows connected programs to learn how large their screen is. */
    public static native void setPtyWindowSize(int fd, int rows, int cols, int cellWidth, int cellHeight);

    /**
     * Causes the calling thread to wait for the process associated with the receiver to finish executing.
     *
     * @return if >= 0, the exit status of the process. If < 0, the signal causing the process to stop negated.
     */
    public static native int waitFor(int processId);

    /** Close a file descriptor through the close(2) system call. */
    public static native void close(int fileDescriptor);

}
