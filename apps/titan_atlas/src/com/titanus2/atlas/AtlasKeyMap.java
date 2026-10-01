package com.titanus2.atlas;

/**
 * US layout shared by the terminal bar and the desk seat.
 * Linux codes are evdev KEY_* values. Shift is 1 when the glyph is the
 * shifted character on that key.
 */
public final class AtlasKeyMap {
    private AtlasKeyMap() {}

    /** @return {linuxCode, shift} or null when the character has no US key. */
    public static int[] glyph(char c) {
        return com.titanus2.api.KeyGlyphs.linuxFor(c);
    }

    /** Named bar keys. 0 means this name is a modifier or a glyph, not a tap. */
    public static int named(String key) {
        if (key == null) return 0;
        switch (key) {
            case "ESC": return 1;
            case "1": return 2;
            case "2": return 3;
            case "3": return 4;
            case "4": return 5;
            case "5": return 6;
            case "6": return 7;
            case "7": return 8;
            case "8": return 9;
            case "9": return 10;
            case "0": return 11;
            case "BKSP": return 14;
            case "TAB": return 15;
            case "ENTER": return 28;
            case "HOME": return 102;
            case "END": return 107;
            case "PGUP": return 104;
            case "PGDN": return 109;
            case "↑": return 103;
            case "↓": return 108;
            case "←": return 105;
            case "→": return 106;
            case "INS": return 110;
            case "DEL": return 111;
            case "PRT": return 99;
            case "PAUSE": return 119;
            case "F1": return 59;
            case "F2": return 60;
            case "F3": return 61;
            case "F4": return 62;
            case "F5": return 63;
            case "F6": return 64;
            case "F7": return 65;
            case "F8": return 66;
            case "F9": return 67;
            case "F10": return 68;
            case "F11": return 87;
            case "F12": return 88;
            case "/": return 53;
            default: return 0;
        }
    }

    /** Apply a sticky Shift to an unshifted glyph. An already-shifted glyph stays. */
    public static char withShift(char c, boolean shift) {
        if (!shift) return c;
        if (c >= 'a' && c <= 'z') return (char) (c - 32);
        switch (c) {
            case '1': return '!';
            case '2': return '@';
            case '3': return '#';
            case '4': return '$';
            case '5': return '%';
            case '6': return '^';
            case '7': return '&';
            case '8': return '*';
            case '9': return '(';
            case '0': return ')';
            case '-': return '_';
            case '=': return '+';
            case '[': return '{';
            case ']': return '}';
            case '\\': return '|';
            case ';': return ':';
            case '\'': return '"';
            case '`': return '~';
            case ',': return '<';
            case '.': return '>';
            case '/': return '?';
            default: return c;
        }
    }
}
