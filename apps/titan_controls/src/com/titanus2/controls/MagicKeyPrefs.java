package com.titanus2.controls;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Magic key: hold-to-modify. Orthogonal to CHAR_MOD keylayout specials.
 * Modes: chords (default), layout, arrows, system. Per-app overrides by package.
 */
public final class MagicKeyPrefs {
    public static final String PREFS = "titan2_magic";

    public static final String MODE_CHORDS = "chords";
    public static final String MODE_LAYOUT = "layout";
    public static final String MODE_ARROWS = "arrows";
    public static final String MODE_SYSTEM = "system";

    public static final String[][] MODES = new String[][] {
        { MODE_CHORDS, "Chords" },
        { MODE_LAYOUT, "Layout" },
        { MODE_ARROWS, "Arrows" },
        { MODE_SYSTEM, "System" },
    };

    private final SharedPreferences p;

    public MagicKeyPrefs(Context ctx) {
        p = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int getScan() {
        return p.getInt("magic_scan", 0);
    }

    public void setScan(int scan) {
        p.edit().putInt("magic_scan", scan <= 0 ? 0 : KeyMapPrefs.canonicalizeScan(scan)).apply();
    }

    public boolean isSet() {
        return getScan() > 0;
    }

    public String getDefaultMode() {
        String m = p.getString("magic_default_mode", MODE_CHORDS);
        return m == null || m.isEmpty() ? MODE_CHORDS : m;
    }

    public void setDefaultMode(String mode) {
        p.edit().putString("magic_default_mode", normalizeMode(mode)).apply();
    }

    public String getAppMode(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        String m = p.getString("magic_app_" + pkg, null);
        return m == null || m.isEmpty() ? null : m;
    }

    public void setAppMode(String pkg, String mode) {
        if (pkg == null || pkg.isEmpty()) return;
        if (mode == null || mode.isEmpty()) {
            p.edit().remove("magic_app_" + pkg).apply();
        } else {
            p.edit().putString("magic_app_" + pkg, normalizeMode(mode)).apply();
        }
    }

    public void removeAppMode(String pkg) {
        if (pkg == null) return;
        p.edit().remove("magic_app_" + pkg).apply();
    }

    /** Foreground package → mode, else default. */
    public String resolveMode(String pkg) {
        String app = getAppMode(pkg);
        if (app != null) return app;
        return getDefaultMode();
    }

    public Map<String, String> listAppModes() {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, ?> all = p.getAll();
        if (all == null) return out;
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String k = e.getKey();
            if (k == null || !k.startsWith("magic_app_")) continue;
            Object v = e.getValue();
            if (v == null) continue;
            out.put(k.substring("magic_app_".length()), String.valueOf(v));
        }
        return out;
    }

    public int appModeCount() {
        return listAppModes().size();
    }

    /** Chord by physical scan (preferred) or keycode fallback. */
    public String getChord(int scan, int keyCode) {
        int c = KeyMapPrefs.canonicalizeScan(scan);
        if (c > 0) {
            String a = p.getString("chord_s_" + c, null);
            if (a != null && !a.isEmpty()) return a;
        }
        if (keyCode > 0) {
            String a = p.getString("chord_k_" + keyCode, null);
            if (a != null && !a.isEmpty()) return a;
        }
        return null;
    }

    public void setChordByScan(int scan, String action) {
        int c = KeyMapPrefs.canonicalizeScan(scan);
        if (c <= 0) return;
        if (action == null || action.isEmpty() || KeyMapPrefs.ACT_DEFAULT.equals(action)) {
            p.edit().remove("chord_s_" + c).apply();
        } else {
            p.edit().putString("chord_s_" + c, action).apply();
        }
    }

    public void setChordByKeyCode(int keyCode, String action) {
        if (keyCode <= 0) return;
        if (action == null || action.isEmpty() || KeyMapPrefs.ACT_DEFAULT.equals(action)) {
            p.edit().remove("chord_k_" + keyCode).apply();
        } else {
            p.edit().putString("chord_k_" + keyCode, action).apply();
        }
    }

    public void clearChord(int scan, int keyCode) {
        SharedPreferences.Editor ed = p.edit();
        int c = KeyMapPrefs.canonicalizeScan(scan);
        if (c > 0) ed.remove("chord_s_" + c);
        if (keyCode > 0) ed.remove("chord_k_" + keyCode);
        ed.apply();
    }

    public static final class ChordEntry {
        public final boolean byScan;
        public final int id;
        public final String action;
        public ChordEntry(boolean byScan, int id, String action) {
            this.byScan = byScan;
            this.id = id;
            this.action = action;
        }
    }

    public List<ChordEntry> listChords() {
        List<ChordEntry> out = new ArrayList<>();
        Map<String, ?> all = p.getAll();
        if (all == null) return out;
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (k == null || v == null) continue;
            String act = String.valueOf(v);
            if (act.isEmpty()) continue;
            if (k.startsWith("chord_s_")) {
                try {
                    out.add(new ChordEntry(true, Integer.parseInt(k.substring(8)), act));
                } catch (NumberFormatException ignored) {}
            } else if (k.startsWith("chord_k_")) {
                try {
                    out.add(new ChordEntry(false, Integer.parseInt(k.substring(8)), act));
                } catch (NumberFormatException ignored) {}
            }
        }
        return out;
    }

    public static String modeLabel(String mode) {
        String m = normalizeMode(mode);
        for (String[] row : MODES) {
            if (row[0].equals(m)) return row[1];
        }
        return m;
    }

    public static String scanLabel(int scan) {
        int c = KeyMapPrefs.canonicalizeScan(scan);
        if (KeyMapPrefs.isChordKeyCodeId(c)) {
            return keyCodeLabel(KeyMapPrefs.keyCodeFromChordId(c));
        }
        switch (c) {
            case KeyMapPrefs.SCAN_SIDE_FUNC: return "Side button · bottom";
            case KeyMapPrefs.SCAN_SIDE_FUNC2: return "Side button · top";
            case KeyMapPrefs.SCAN_BACK: return "Back key";
            case KeyMapPrefs.SCAN_APP_SWITCH: return "Recents key";
            case KeyMapPrefs.SCAN_FN: return "Fn";
            case KeyMapPrefs.SCAN_SYM: return "Sym";
            case KeyMapPrefs.SCAN_ALT: return "Alt";
            case 0: return "unset";
            default:
                String letter = evdevLetter(c);
                return letter != null ? letter : ("scan " + c);
        }
    }

    /** Linux evdev KEY_* on TitanKey (US). */
    private static String evdevLetter(int scan) {
        switch (scan) {
            case 16: return "Q"; case 17: return "W"; case 18: return "E";
            case 19: return "R"; case 20: return "T"; case 21: return "Y";
            case 22: return "U"; case 23: return "I"; case 24: return "O";
            case 25: return "P";
            case 30: return "A"; case 31: return "S"; case 32: return "D";
            case 33: return "F"; case 34: return "G"; case 35: return "H";
            case 36: return "J"; case 37: return "K"; case 38: return "L";
            case 44: return "Z"; case 45: return "X"; case 46: return "C";
            case 47: return "V"; case 48: return "B"; case 49: return "N";
            case 50: return "M";
            default: return null;
        }
    }

    /**
     * Android keycode token (APP_SWITCH, UNKNOWN, …). Lab/debug only —
     * product UI prefers {@link #scanLabel(int)} for hardware keys.
     */
    public static String keyCodeLabel(int keyCode) {
        if (keyCode <= 0 || keyCode == android.view.KeyEvent.KEYCODE_UNKNOWN) {
            return "UNKNOWN";
        }
        String n = android.view.KeyEvent.keyCodeToString(keyCode);
        if (n != null && n.startsWith("KEYCODE_")) n = n.substring(8);
        return n != null ? n : ("kc " + keyCode);
    }

    private static String normalizeMode(String mode) {
        if (mode == null) return MODE_CHORDS;
        switch (mode.trim().toLowerCase()) {
            case MODE_LAYOUT:
            case "specials":
            case "alt":
                return MODE_LAYOUT;
            case MODE_ARROWS:
            case "arrow":
            case "nav":
                return MODE_ARROWS;
            case MODE_SYSTEM:
            case "sys":
                return MODE_SYSTEM;
            case MODE_CHORDS:
            case "chord":
            case "desktop":
            default:
                return MODE_CHORDS;
        }
    }
}
