package com.titanus2.atlas;

import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.List;

/**
 * Process-scoped session list so PTYs survive MainActivity destroy
 * when the keep-alive foreground service holds the process.
 * Parallel shell modes: per-session Android vs Debian (hybrid) plane.
 */
public final class SessionHub {
    /** Shell plane for one PTY: android admin shell, or debian hybrid. */
    public static final String MODE_ANDROID = "android";
    public static final String MODE_DEBIAN = "debian";

    private static final List<TerminalSession> SESSIONS = new ArrayList<>();
    /** Same length as SESSIONS — shell plane for that PTY. */
    private static final List<String> MODES = new ArrayList<>();
    private static int index = -1;
    private static int nextId = 1;

    private SessionHub() {}

    public static synchronized List<TerminalSession> sessions() {
        return SESSIONS;
    }

    public static synchronized int index() {
        return index;
    }

    public static synchronized void setIndex(int i) {
        index = i;
    }

    public static synchronized TerminalSession current() {
        if (index < 0 || index >= SESSIONS.size()) return null;
        return SESSIONS.get(index);
    }

    public static synchronized int nextSessionId() {
        return nextId++;
    }

    public static synchronized int liveCount() {
        int n = 0;
        for (TerminalSession s : SESSIONS) {
            if (s != null && s.isRunning()) n++;
        }
        return n;
    }

    public static synchronized String modeAt(int i) {
        if (i < 0 || i >= MODES.size()) return MODE_ANDROID;
        String m = MODES.get(i);
        return MODE_DEBIAN.equals(m) ? MODE_DEBIAN : MODE_ANDROID;
    }

    public static synchronized String currentMode() {
        return modeAt(index);
    }

    public static synchronized void setModeAt(int i, String mode) {
        if (i < 0 || i >= MODES.size()) return;
        MODES.set(i, MODE_DEBIAN.equals(mode) ? MODE_DEBIAN : MODE_ANDROID);
    }

    public static synchronized void setCurrentMode(String mode) {
        setModeAt(index, mode);
    }

    /**
     * Default mode for a new session.
     * Product: Android unless user opted into Deb <b>and</b> hybrid plane is ready.
     * Never open Deb-by-default when overlay is down (rootless first-open).
     */
    public static String defaultModeFromPrefs(boolean privilegedHybrid) {
        if (!privilegedHybrid) return MODE_ANDROID;
        try {
            if (NativeBin.hybridRootfsReady()) return MODE_DEBIAN;
        } catch (Exception ignored) {
        }
        return MODE_ANDROID;
    }

    public static synchronized void addSession(TerminalSession s, String mode) {
        SESSIONS.add(s);
        MODES.add(MODE_DEBIAN.equals(mode) ? MODE_DEBIAN : MODE_ANDROID);
        index = SESSIONS.size() - 1;
        syncModesSize();
    }

    public static synchronized void setSessionAt(int i, TerminalSession s) {
        if (i < 0 || i >= SESSIONS.size()) return;
        SESSIONS.set(i, s);
        syncModesSize();
    }

    public static synchronized void removeAt(int i) {
        if (i < 0 || i >= SESSIONS.size()) return;
        SESSIONS.remove(i);
        if (i < MODES.size()) MODES.remove(i);
        if (SESSIONS.isEmpty()) {
            index = -1;
        } else if (index >= SESSIONS.size()) {
            index = SESSIONS.size() - 1;
        } else if (index > i) {
            index--;
        }
        syncModesSize();
    }

    private static void syncModesSize() {
        while (MODES.size() < SESSIONS.size()) {
            MODES.add(MODE_ANDROID);
        }
        while (MODES.size() > SESSIONS.size()) {
            MODES.remove(MODES.size() - 1);
        }
    }

    public static synchronized void finishAll() {
        for (TerminalSession s : SESSIONS) {
            if (s != null) s.finishIfRunning();
        }
        SESSIONS.clear();
        MODES.clear();
        index = -1;
    }

    /**
     * Drop dead PTYs (black terminal after process death / hybrid thrash).
     * Returns true if the visible session list changed.
     */
    public static synchronized boolean pruneDead() {
        boolean changed = false;
        for (int i = SESSIONS.size() - 1; i >= 0; i--) {
            TerminalSession s = SESSIONS.get(i);
            if (s == null || !s.isRunning()) {
                if (s != null) {
                    try {
                        s.finishIfRunning();
                    } catch (Exception ignored) {
                    }
                }
                SESSIONS.remove(i);
                if (i < MODES.size()) MODES.remove(i);
                changed = true;
            }
        }
        syncModesSize();
        if (SESSIONS.isEmpty()) {
            index = -1;
        } else if (index >= SESSIONS.size()) {
            index = SESSIONS.size() - 1;
        } else if (index < 0) {
            index = 0;
        }
        return changed;
    }
}
