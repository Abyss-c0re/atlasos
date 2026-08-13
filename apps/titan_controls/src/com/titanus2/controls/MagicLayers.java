package com.titanus2.controls;

import android.view.KeyEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * Hold-magic layers: Titan2 specials (Pastiera/stock Alt map), arrows, system keys.
 * Returns Android keycodes for injection, or a single character for text inject.
 */
public final class MagicLayers {
    /** Result: either keyCode (>0) or character (non-null, length 1). */
    public static final class Out {
        public final int keyCode;
        public final String ch;
        public Out(int keyCode) { this.keyCode = keyCode; this.ch = null; }
        public Out(String ch) { this.keyCode = 0; this.ch = ch; }
        public boolean isChar() { return ch != null && !ch.isEmpty(); }
    }

    private static final Map<Integer, String> LAYOUT = buildLayout();
    private static final Map<Integer, Integer> ARROWS = buildArrows();
    private static final Map<Integer, Integer> SYSTEM = buildSystem();

    private MagicLayers() {}

    public static Out map(String mode, int keyCode) {
        if (keyCode <= 0) return null;
        if (MagicKeyPrefs.MODE_LAYOUT.equals(mode)) {
            String ch = LAYOUT.get(keyCode);
            if (ch == null) return null;
            // Prefer real keycodes for digits / common punctuation
            Integer kc = charToKeyCode(ch);
            if (kc != null) return new Out(kc);
            return new Out(ch);
        }
        if (MagicKeyPrefs.MODE_ARROWS.equals(mode)) {
            Integer kc = ARROWS.get(keyCode);
            return kc == null ? null : new Out(kc);
        }
        if (MagicKeyPrefs.MODE_SYSTEM.equals(mode)) {
            Integer kc = SYSTEM.get(keyCode);
            return kc == null ? null : new Out(kc);
        }
        return null;
    }

    /** Titan2 Alt/specials layer (Pastiera titan2/alt_key_mappings.json). */
    private static Map<Integer, String> buildLayout() {
        Map<Integer, String> m = new HashMap<>();
        m.put(KeyEvent.KEYCODE_Q, "0");
        m.put(KeyEvent.KEYCODE_W, "1");
        m.put(KeyEvent.KEYCODE_E, "2");
        m.put(KeyEvent.KEYCODE_R, "3");
        m.put(KeyEvent.KEYCODE_T, "(");
        m.put(KeyEvent.KEYCODE_Y, ")");
        // Product map (match HostLayoutController): U = underscore, I = hyphen
        m.put(KeyEvent.KEYCODE_U, "_");
        m.put(KeyEvent.KEYCODE_I, "-");
        m.put(KeyEvent.KEYCODE_O, "/");
        m.put(KeyEvent.KEYCODE_P, ":");
        m.put(KeyEvent.KEYCODE_A, "@");
        m.put(KeyEvent.KEYCODE_S, "4");
        m.put(KeyEvent.KEYCODE_D, "5");
        m.put(KeyEvent.KEYCODE_F, "6");
        m.put(KeyEvent.KEYCODE_G, "*");
        m.put(KeyEvent.KEYCODE_H, "#");
        m.put(KeyEvent.KEYCODE_J, "+");
        m.put(KeyEvent.KEYCODE_K, "\"");
        m.put(KeyEvent.KEYCODE_L, "'");
        m.put(KeyEvent.KEYCODE_Z, "!");
        m.put(KeyEvent.KEYCODE_X, "7");
        m.put(KeyEvent.KEYCODE_C, "8");
        m.put(KeyEvent.KEYCODE_V, "9");
        m.put(KeyEvent.KEYCODE_B, ".");
        m.put(KeyEvent.KEYCODE_N, ",");
        m.put(KeyEvent.KEYCODE_M, "?");
        return m;
    }

    /** WASD + IJKL arrows; U/O page; H/L home/end; space center. */
    private static Map<Integer, Integer> buildArrows() {
        Map<Integer, Integer> m = new HashMap<>();
        m.put(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_DPAD_UP);
        m.put(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_DPAD_LEFT);
        m.put(KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_DPAD_DOWN);
        m.put(KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_DPAD_RIGHT);
        m.put(KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_DPAD_UP);
        m.put(KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_DPAD_LEFT);
        m.put(KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_DPAD_DOWN);
        m.put(KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_DPAD_RIGHT);
        m.put(KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_MOVE_HOME);
        m.put(KeyEvent.KEYCODE_SEMICOLON, KeyEvent.KEYCODE_MOVE_END);
        m.put(KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_PAGE_UP);
        m.put(KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_PAGE_DOWN);
        m.put(KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_PAGE_UP);
        m.put(KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_PAGE_DOWN);
        m.put(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_CENTER);
        return m;
    }

    /** Letter cluster → common system keys (hidden arrows / desktop). */
    private static Map<Integer, Integer> buildSystem() {
        Map<Integer, Integer> m = new HashMap<>();
        m.put(KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_ESCAPE);
        m.put(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_TAB);
        m.put(KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_ENTER);
        m.put(KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_DEL); // backspace
        m.put(KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_FORWARD_DEL);
        m.put(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_MOVE_HOME);
        m.put(KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_DPAD_DOWN);
        m.put(KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_MOVE_END);
        m.put(KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_PAGE_DOWN);
        m.put(KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_PAGE_UP);
        m.put(KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_DPAD_LEFT);
        m.put(KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_DPAD_RIGHT);
        m.put(KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_DPAD_UP);
        m.put(KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_BREAK);
        m.put(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_F1);
        m.put(KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_F2);
        m.put(KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_F3);
        m.put(KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_F4);
        m.put(KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_F5);
        m.put(KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_F6);
        m.put(KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_F7);
        m.put(KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_F8);
        m.put(KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_F9);
        m.put(KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_F10);
        return m;
    }

    private static Integer charToKeyCode(String ch) {
        if (ch == null || ch.length() != 1) return null;
        char c = ch.charAt(0);
        if (c >= '0' && c <= '9') return KeyEvent.KEYCODE_0 + (c - '0');
        switch (c) {
            case '.': return KeyEvent.KEYCODE_PERIOD;
            case ',': return KeyEvent.KEYCODE_COMMA;
            case '/': return KeyEvent.KEYCODE_SLASH;
            case '-': return KeyEvent.KEYCODE_MINUS;
            case '*': return KeyEvent.KEYCODE_STAR;
            case '#': return KeyEvent.KEYCODE_POUND;
            case '+': return KeyEvent.KEYCODE_PLUS;
            case '@': return KeyEvent.KEYCODE_AT;
            case '\'': return KeyEvent.KEYCODE_APOSTROPHE;
            case '"': return KeyEvent.KEYCODE_APOSTROPHE; // best effort
            case '!': return null; // needs shift+1 — inject as char
            case '?': return null;
            case '(': return null;
            case ')': return null;
            case '_': return null;
            case ':': return null;
            default: return null;
        }
    }
}
