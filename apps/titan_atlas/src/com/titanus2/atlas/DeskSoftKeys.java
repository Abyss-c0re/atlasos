package com.titanus2.atlas;

import android.content.Context;

import java.util.ArrayList;
import java.util.concurrent.Executor;

/**
 * Soft bar → atlas-x seat. Modifiers are one-shot. Sym glyphs use the US
 * linux key, with Shift held when the glyph is the shifted character.
 * Meta is a Super tap so the launcher opens.
 */
public final class DeskSoftKeys {
    private static final int KEY_LEFTCTRL = 29;
    private static final int KEY_LEFTSHIFT = 42;
    private static final int KEY_LEFTALT = 56;
    private static final int KEY_CAPSLOCK = 58;
    private static final int KEY_LEFTMETA = 125;
    private static final int KEY_GRAVE = 41;

    private final Context context;
    private boolean ctrl;
    private boolean alt;
    private boolean shift;
    private boolean caps;

    public DeskSoftKeys(Context context) {
        this.context = context;
    }

    public boolean isCtrl() { return ctrl; }
    public boolean isAlt() { return alt; }
    public boolean isShift() { return shift; }
    public boolean isMeta() { return false; }
    public boolean isCaps() { return caps; }

    public void handle(String key, Executor io) {
        int[] ev = plan(key);
        if (ev == null || ev.length == 0 || io == null) return;
        io.execute(() -> play(ev));
    }

    private int[] plan(String key) {
        if (key == null) return null;
        switch (key) {
            case "CTRL":
                ctrl = !ctrl;
                return null;
            case "ALT":
                alt = !alt;
                return null;
            case "SHIFT":
                shift = !shift;
                return null;
            case "META":
                return tap(KEY_LEFTMETA, false);
            case "CAPS":
                caps = !caps;
                return new int[]{KEY_CAPSLOCK, 1, KEY_CAPSLOCK, 0};
            case "GRAVE":
                boolean tilde = !shift;
                shift = false;
                return chord(KEY_GRAVE, tilde);
            default:
                break;
        }
        int code = AtlasKeyMap.named(key);
        if (code != 0) return chord(code, false);
        if (key.length() == 1) {
            int[] g = AtlasKeyMap.glyph(key.charAt(0));
            if (g != null) return chord(g[0], g[1] == 1);
        }
        return null;
    }

    /** Latched Ctrl/Alt/Shift wrap one key, then the latches drop. */
    private int[] chord(int code, boolean extraShift) {
        boolean sh = shift || extraShift;
        ArrayList<Integer> ev = new ArrayList<>();
        if (ctrl) down(ev, KEY_LEFTCTRL);
        if (alt) down(ev, KEY_LEFTALT);
        if (sh) down(ev, KEY_LEFTSHIFT);
        down(ev, code);
        up(ev, code);
        if (sh) up(ev, KEY_LEFTSHIFT);
        if (alt) up(ev, KEY_LEFTALT);
        if (ctrl) up(ev, KEY_LEFTCTRL);
        ctrl = false;
        alt = false;
        shift = false;
        return flatten(ev);
    }

    private int[] tap(int code, boolean extraShift) {
        return chord(code, extraShift);
    }

    private static void down(ArrayList<Integer> ev, int code) {
        ev.add(code);
        ev.add(1);
    }

    private static void up(ArrayList<Integer> ev, int code) {
        ev.add(code);
        ev.add(0);
    }

    private static int[] flatten(ArrayList<Integer> ev) {
        int[] out = new int[ev.size()];
        for (int i = 0; i < ev.size(); i++) out[i] = ev.get(i);
        return out;
    }

    private void play(int[] ev) {
        String sock = DeskSession.inputSock(context);
        for (int i = 0; i + 1 < ev.length; i += 2) {
            DeskClient.key(sock, ev[i], ev[i + 1]);
            if (ev[i + 1] == 1) {
                try {
                    Thread.sleep(12L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
