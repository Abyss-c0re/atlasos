package com.titanus2.atlas;

import android.content.Context;
import android.os.FileObserver;
import android.util.Log;

import java.io.File;

/**
 * Instant wake on $HOME/auth/req.* or wake file — so sudo/su does not wait on poll.
 */
public final class AuthWatch {
    private static final String TAG = "AtlasAuthWatch";
    private static FileObserver observer;

    private AuthWatch() {}

    public static synchronized void start(Context c) {
        stop();
        File dir = AtlasAuth.authDir(c);
        final Context app = c.getApplicationContext();
        try {
            //noinspection deprecation
            observer = new FileObserver(dir.getAbsolutePath(),
                FileObserver.CREATE | FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE) {
                @Override
                public void onEvent(int event, String path) {
                    if (path == null) return;
                    if (!path.startsWith("req.") && !"wake".equals(path)) return;
                    try {
                        AtlasAuth.pollOnce(app);
                    } catch (Exception e) {
                        Log.w(TAG, "poll", e);
                    }
                }
            };
            observer.startWatching();
            Log.i(TAG, "watching " + dir.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "FileObserver failed", e);
            observer = null;
        }
    }

    public static synchronized void stop() {
        if (observer != null) {
            try {
                observer.stopWatching();
            } catch (Exception ignored) {
            }
            observer = null;
        }
    }
}
