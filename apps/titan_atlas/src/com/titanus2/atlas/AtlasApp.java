package com.titanus2.atlas;

import android.app.Application;
import android.util.Log;

/**
 * Product Application: extract native PTY/term libs <b>before</b> any Activity
 * builds a {@code TerminalView} (JNI static load). No root required.
 *
 * Architecture (2026-08) — <b>wipe law for every change</b>:
 * <ul>
 *   <li>Atlas = terminal + atlas-auth biometry</li>
 *   <li>Debian root = super LP {@code atlas_linux} (survives wipe)</li>
 *   <li>Auth plane = {@code /data/local/atlas-linux/var/lib/atlas-auth} on that LP
 *       (survives wipe) — never app CE, never tmp</li>
 *   <li>Linux HOME = {@code /data/local/atlas-home/atlas} (wiped with Android)</li>
 * </ul>
 */
public final class AtlasApp extends Application {
    private static final String TAG = "AtlasApp";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            NativeBin.ensureUserInstallDirs(this);
            NativeBin.ensureNativeLibs(this);
            NativeBin.ensureAuthPlaneOnLp(this);
            NativeBin.healAuthDir(this);
            /* Nanobot peers: Nanobot app + Titan command plane. Not Atlas. */
            AtlasPrefs.publishPrivilegePlane(this);
            AtlasPrefs.publishBioPlane(this);
            HybridEnsure.ensureLiveUidAsync(this);
        } catch (Throwable t) {
            Log.w(TAG, "native lib / auth plane warm", t);
        }
    }
}
