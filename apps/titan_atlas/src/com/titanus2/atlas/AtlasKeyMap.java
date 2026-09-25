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
        if (c >= 'a' && c <= 'z') return new int[]{30 + (c - 'a'), 0};
        if (c >= 'A' && c <= 'Z') return new int[]{30 + (c - 'A'), 1};
        if (c >= '1' && c <= '9') return new int[]{2 + (c - '1'), 0};
        if (c == '0') return new int[]{11, 0};
        switch (c) {
            case ' ': return new int[]{57, 0};
            case '\n':
            case '\r': return new int[]{28, 0};
            case '\t': return new int[]{15, 0};
            case '-': return new int[]{12, 0};
            case '=': return new int[]{13, 0};
            case '[': return new int[]{26, 0};
            case ']': return new int[]{27, 0};
            case '\\': return new int[]{43, 0};
            case ';': return new int[]{39, 0};
            case '\'': return new int[]{40, 0};
            case '`': return new int[]{41, 0};
            case ',': return new int[]{51, 0};
            case '.': return new int[]{52, 0};
            case '/': return new int[]{53, 0};
            case '!': return new int[]{2, 1};
            case '@': return new int[]{3, 1};
            case '#': return new int[]{4, 1};
            case '$': return new int[]{5, 1};
            case '%': return new int[]{6, 1};
            case '^': return new int[]{7, 1};
            case '&': return new int[]{8, 1};
            case '*': return new int[]{9, 1};
            case '(': return new int[]{10, 1};
            case ')': return new int[]{11, 1};
            case '_': return new int[]{12, 1};
            case '+': return new int[]{13, 1};
            case '{': return new int[]{26, 1};
            case '}': return new int[]{27, 1};
            case '|': return new int[]{43, 1};
            case ':': return new int[]{39, 1};
            case '"': return new int[]{40, 1};
            case '~': return new int[]{41, 1};
            case '<': return new int[]{51, 1};
            case '>': return new int[]{52, 1};
            case '?': return new int[]{53, 1};
            default: return null;
        }
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
