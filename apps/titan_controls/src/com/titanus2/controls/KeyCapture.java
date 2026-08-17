package com.titanus2.controls;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keys settings capture plane.
 * Single-key: one DOWN. Chord: 2 or 3 identified scans held together
 * (commit on first release after at least two were down).
 * Remaps do not fire while armed. Keys UI open alone does not block layouts.
 */
public final class KeyCapture {
    public interface Listener {
        void onKey(int scan, int keyCode);
    }

    public interface PairListener {
        void onPair(int scanA, int scanB);
    }

    public interface ChordListener {
        /** {@code ids} sorted, length 2 or 3. */
        void onChord(int[] ids);
    }

    private static volatile boolean uiOpen;
    private static volatile boolean armed;
    private static volatile boolean pairMode;
    private static volatile Listener listener;
    private static volatile PairListener pairListener;
    private static volatile ChordListener chordListener;
    private static final Set<Integer> pairDown = new HashSet<>();
    private static final List<Integer> maxHeld = new ArrayList<>();

    private KeyCapture() {}

    public static void setUiOpen(boolean open) {
        uiOpen = open;
        if (!open) disarm();
    }

    public static boolean isUiOpen() {
        return uiOpen;
    }

    /**
     * Swallow remaps only while Keys is on screen <b>and</b> a capture is
     * armed. Armed-but-Keys-closed used to eat Back/Recents after install.
     */
    public static boolean blockActions() {
        return armed && uiOpen;
    }

    public static void arm(Listener l) {
        disarm();
        listener = l;
        pairMode = false;
        armed = l != null;
    }

    public static void armPair(PairListener l) {
        armChord(l == null ? null : ids -> {
            if (ids != null && ids.length >= 2) l.onPair(ids[0], ids[1]);
        });
    }

    public static void armChord(ChordListener l) {
        disarm();
        chordListener = l;
        pairMode = l != null;
        armed = l != null;
    }

    public static void disarm() {
        armed = false;
        pairMode = false;
        listener = null;
        pairListener = null;
        chordListener = null;
        synchronized (pairDown) {
            pairDown.clear();
            maxHeld.clear();
        }
    }

    public static boolean isArmed() {
        return armed;
    }

    /** Single-key capture. Disarms after one identified scan. */
    public static boolean offer(int scan, int keyCode) {
        if (!armed || pairMode) return false;
        Listener l = listener;
        armed = false;
        listener = null;
        if (l == null) return false;
        try {
            l.onKey(scan, keyCode);
        } catch (Exception ignored) {}
        return true;
    }

    /**
     * Chord capture: accumulate held scans (max 3).
     * Does not finish on 2-down — a third key can still join.
     * Scan 0 is ignored (mtk-kpd CAMERA residual).
     */
    public static boolean offerDown(int scan, int keyCode) {
        if (!armed) return false;
        if (!pairMode) return offer(scan, keyCode);
        if (scan <= 0) return false;
        synchronized (pairDown) {
            if (pairDown.size() < PairChordPrefs.MAX) pairDown.add(scan);
            if (pairDown.size() > maxHeld.size()) {
                maxHeld.clear();
                maxHeld.addAll(pairDown);
            }
        }
        return false;
    }

    /**
     * First release after 2 or 3 keys were held together commits the chord
     * (the largest set seen, capped at 3).
     */
    public static void offerUp(int scan) {
        if (!armed || !pairMode || scan <= 0) return;
        ChordListener cl;
        int[] ids = null;
        synchronized (pairDown) {
            pairDown.remove(scan);
            if (maxHeld.size() >= 2) {
                ids = toIdArray(maxHeld);
                cl = chordListener;
                armed = false;
                pairMode = false;
                pairListener = null;
                chordListener = null;
                pairDown.clear();
                maxHeld.clear();
            } else {
                return;
            }
        }
        if (cl == null || ids == null || ids.length < 2) return;
        try {
            cl.onChord(ids);
        } catch (Exception ignored) {}
    }

    private static int[] toIdArray(List<Integer> src) {
        int[] n = new int[src.size()];
        int i = 0;
        for (Integer v : src) {
            if (v != null && v > 0) n[i++] = v;
        }
        if (i != n.length) {
            int[] t = new int[i];
            System.arraycopy(n, 0, t, 0, i);
            n = t;
        }
        return PairChordPrefs.norm(n);
    }
}
