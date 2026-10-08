package com.titanus2.api;

import java.util.ArrayList;
import java.util.List;

/**
 * One US key and symbol catalog for Atlas, Titan Controls, and the HID pad.
 * Linux codes are evdev KEY_*. HID usages are the boot keyboard page.
 * Shift is 1 (linux) or 0x02 (HID) when the glyph is the shifted character.
 */
public final class KeyGlyphs {
    private KeyGlyphs() {}

    /**
     * Printed symbols. Includes {@code &} (Shift+7), which the old copies dropped.
     */
    public static final String[] SYMBOLS = {
        "0", "1", "2", "3", "(", ")", "_", "-", "/", ":",
        "@", "4", "5", "6", "*", "#", "+", "\"", "'",
        "!", "7", "8", "9", ".", ",", "?",
        "`", "~", "[", "]", "{", "}", "\\", "|", ";", "<", ">", "=",
        "&", "$", "%", "^",
    };

    /** @return {linuxCode, shift01} or null. */
    public static int[] linuxFor(char c) {
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

    /**
     * Printed Sym glyph for an Android keycode, or null when that key is
     * not on the Titan layer. The daemon is the source. This table is the
     * same list, used only while {@code titan2-keys} is not answering.
     */
    public static String specialForAndroidKey(int keyCode) {
        String live = TitanKeys.androidGlyph(keyCode);
        if (live != null) return live.isEmpty() ? null : live;
        return specialTable(keyCode);
    }

    /** Same pairs as titan_keys.c SPECIALS. */
    private static String specialTable(int keyCode) {
        switch (keyCode) {
            case 45: return "0"; /* Q */
            case 51: return "1"; /* W */
            case 33: return "2"; /* E */
            case 46: return "3"; /* R */
            case 48: return "("; /* T */
            case 53: return ")"; /* Y */
            case 49: return "_"; /* U */
            case 37: return "-"; /* I */
            case 43: return "/"; /* O */
            case 44: return ":"; /* P */
            case 29: return "@"; /* A */
            case 47: return "4"; /* S */
            case 32: return "5"; /* D */
            case 34: return "6"; /* F */
            case 35: return "*"; /* G */
            case 36: return "#"; /* H */
            case 38: return "+"; /* J */
            case 39: return "\""; /* K */
            case 40: return "'"; /* L */
            case 54: return "!"; /* Z */
            case 52: return "7"; /* X */
            case 31: return "8"; /* C */
            case 50: return "9"; /* V */
            case 30: return "."; /* B */
            case 42: return ","; /* N */
            case 41: return "?"; /* M */
            default: return null;
        }
    }

    /** @return {hidModifier, hidUsage}. Modifier 0x02 is Shift. */
    public static int[] hidFor(char c) {
        int[] live = TitanKeys.glyphHid(c);
        if (live != null) return TitanKeys.isMiss(live) ? null : live;
        return hidForTable(c);
    }

    /** Same chords as titan_glyph_hid(). */
    private static int[] hidForTable(char c) {
        final int sh = 0x02;
        if (c >= 'a' && c <= 'z') return new int[]{0, 0x04 + (c - 'a')};
        if (c >= 'A' && c <= 'Z') return new int[]{sh, 0x04 + (c - 'A')};
        if (c >= '1' && c <= '9') return new int[]{0, 0x1e + (c - '1')};
        if (c == '0') return new int[]{0, 0x27};
        switch (c) {
            case ' ': return new int[]{0, 0x2c};
            case '\n':
            case '\r': return new int[]{0, 0x28};
            case '\t': return new int[]{0, 0x2b};
            case '-': return new int[]{0, 0x2d};
            case '=': return new int[]{0, 0x2e};
            case '[': return new int[]{0, 0x2f};
            case ']': return new int[]{0, 0x30};
            case '\\': return new int[]{0, 0x31};
            case ';': return new int[]{0, 0x33};
            case '\'': return new int[]{0, 0x34};
            case '`': return new int[]{0, 0x35};
            case ',': return new int[]{0, 0x36};
            case '.': return new int[]{0, 0x37};
            case '/': return new int[]{0, 0x38};
            case '!': return new int[]{sh, 0x1e};
            case '@': return new int[]{sh, 0x1f};
            case '#': return new int[]{sh, 0x20};
            case '$': return new int[]{sh, 0x21};
            case '%': return new int[]{sh, 0x22};
            case '^': return new int[]{sh, 0x23};
            case '&': return new int[]{sh, 0x24};
            case '*': return new int[]{sh, 0x25};
            case '(': return new int[]{sh, 0x26};
            case ')': return new int[]{sh, 0x27};
            case '_': return new int[]{sh, 0x2d};
            case '+': return new int[]{sh, 0x2e};
            case '{': return new int[]{sh, 0x2f};
            case '}': return new int[]{sh, 0x30};
            case '|': return new int[]{sh, 0x31};
            case ':': return new int[]{sh, 0x33};
            case '"': return new int[]{sh, 0x34};
            case '~': return new int[]{sh, 0x35};
            case '<': return new int[]{sh, 0x36};
            case '>': return new int[]{sh, 0x37};
            case '?': return new int[]{sh, 0x38};
            default: return null;
        }
    }

    /**
     * On-screen rows. Each row is at most 8 names so a phone width can hold it
     * without scrolling. Empty groups are omitted.
     */
    public static String[][] rows(boolean nav, boolean mods, boolean fn, boolean edit) {
        List<String[]> out = new ArrayList<>();
        List<String> top = new ArrayList<>();
        top.add("ESC");
        top.add("GRAVE");
        top.add("BKSP");
        if (edit) top.add("/");
        if (nav) {
            top.add("HOME");
            top.add("↑");
            top.add("END");
            top.add("PGUP");
        }
        out.add(top.toArray(new String[0]));

        List<String> mid = new ArrayList<>();
        mid.add("TAB");
        if (mods) {
            mid.add("CTRL");
            mid.add("ALT");
            mid.add("META");
        }
        if (nav) {
            mid.add("←");
            mid.add("↓");
            mid.add("→");
            mid.add("PGDN");
        }
        out.add(mid.toArray(new String[0]));

        if (edit || mods) {
            List<String> low = new ArrayList<>();
            if (mods) {
                low.add("SHIFT");
                low.add("CAPS");
            }
            if (edit) {
                low.add("SYM");
                low.add("INS");
                low.add("DEL");
                low.add("PRT");
                low.add("PAUSE");
            }
            if (!low.isEmpty()) out.add(low.toArray(new String[0]));
        }
        if (fn) {
            out.add(new String[]{"F1", "F2", "F3", "F4", "F5", "F6"});
            out.add(new String[]{"F7", "F8", "F9", "F10", "F11", "F12"});
        }
        return out.toArray(new String[0][]);
    }
}
