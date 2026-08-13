package com.titanus2.controls.layouts;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import com.titanus2.controls.HostLayoutController;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * User + built-in keyboard layouts (specials glyphs, arrows, custom maps).
 * Cell values:
 *   plain glyph {@code (} {@code @} — US host chord / phone inject
 *   {@code @host:up} — host action without phone dual-type path decision
 *   {@code @key:19} — Android keycode inject (arrows etc.)
 *   empty / {@code -} — unmapped
 */
public final class CustomLayoutStore {
    private static final String PREFS = "titan2_custom_layouts";
    private static final String KEY_JSON = "layouts_v1";

    public static final String ID_SPECIALS = "specials";
    public static final String ID_ARROWS = "arrows";

    public static final class Layout {
        public final String id;
        public String name;
        public final boolean builtin;
        /** keyCode → cell binding */
        public final LinkedHashMap<Integer, String> cells = new LinkedHashMap<>();

        public Layout(String id, String name, boolean builtin) {
            this.id = id;
            this.name = name == null ? id : name;
            this.builtin = builtin;
        }

        public Layout copy() {
            Layout l = new Layout(id, name, builtin);
            l.cells.putAll(cells);
            return l;
        }
    }

    private final SharedPreferences p;
    private final Context app;

    public CustomLayoutStore(Context ctx) {
        app = ctx.getApplicationContext();
        p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ensureSeeded();
    }

    private void ensureSeeded() {
        if (p.contains(KEY_JSON)) return;
        List<Layout> seed = new ArrayList<>();
        seed.add(builtinSpecials());
        seed.add(builtinArrows());
        saveAll(seed);
    }

    public static Layout builtinSpecials() {
        Layout l = new Layout(ID_SPECIALS, "Specials", true);
        for (Map.Entry<Integer, String> e : HostLayoutController.defaultSpecialsMap().entrySet()) {
            l.cells.put(e.getKey(), e.getValue());
        }
        return l;
    }

    public static Layout builtinArrows() {
        Layout l = new Layout(ID_ARROWS, "Arrows", true);
        // WASD / IJKL style from HostLayoutController defaults
        putKey(l, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_DPAD_UP);
        putKey(l, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_DPAD_UP);
        putKey(l, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_DPAD_LEFT);
        putKey(l, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_DPAD_LEFT);
        putKey(l, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_DPAD_DOWN);
        putKey(l, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_DPAD_DOWN);
        putKey(l, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_DPAD_DOWN);
        putKey(l, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_DPAD_DOWN);
        putKey(l, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_DPAD_RIGHT);
        putKey(l, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_DPAD_RIGHT);
        putKey(l, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_MOVE_HOME);
        putKey(l, KeyEvent.KEYCODE_SEMICOLON, KeyEvent.KEYCODE_MOVE_END);
        putKey(l, KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_PAGE_UP);
        putKey(l, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_PAGE_UP);
        putKey(l, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_PAGE_DOWN);
        putKey(l, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_PAGE_DOWN);
        for (int i = 0; i < 9; i++) {
            putKey(l, KeyEvent.KEYCODE_1 + i, KeyEvent.KEYCODE_F1 + i);
        }
        putKey(l, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_F10);
        return l;
    }

    private static void putKey(Layout l, int from, int toKc) {
        l.cells.put(from, "@key:" + toKc);
    }

    public synchronized List<Layout> list() {
        List<Layout> out = loadAll();
        // Always ensure builtins present
        boolean hasS = false, hasA = false;
        for (Layout l : out) {
            if (ID_SPECIALS.equals(l.id)) hasS = true;
            if (ID_ARROWS.equals(l.id)) hasA = true;
        }
        if (!hasS) out.add(0, builtinSpecials());
        if (!hasA) out.add(hasS ? 1 : 0, builtinArrows());
        return out;
    }

    public synchronized Layout get(String id) {
        if (id == null || id.isEmpty() || "off".equals(id)) return null;
        for (Layout l : list()) {
            if (id.equals(l.id)) {
                // Builtin specials: refresh cells from code map so product fixes
                // (U=_, P=:) land without wipe. User overrides still win via
                // KeyMapPrefs specials override on emit path.
                if (ID_SPECIALS.equals(id) && l.builtin) {
                    Layout fresh = builtinSpecials();
                    // Only fill missing / migrate known product glyphs
                    boolean dirty = false;
                    for (Map.Entry<Integer, String> e : fresh.cells.entrySet()) {
                        String cur = l.cells.get(e.getKey());
                        if (cur == null || cur.isEmpty() || "-".equals(cur)
                                || productGlyphMigrate(e.getKey(), cur, e.getValue())) {
                            l.cells.put(e.getKey(), e.getValue());
                            dirty = true;
                        }
                    }
                    if (dirty) {
                        try { save(l); } catch (Exception ignored) {}
                    }
                }
                return l;
            }
        }
        // Legacy mode names
        if ("specials".equals(id)) return builtinSpecials();
        if ("arrows".equals(id)) return builtinArrows();
        return null;
    }

    /** True if we should overwrite a stale builtin glyph with the product map. */
    private static boolean productGlyphMigrate(int keyCode, String cur, String want) {
        if (want == null || want.equals(cur)) return false;
        // U was "-" (hyphen); product wants "_"
        if (keyCode == android.view.KeyEvent.KEYCODE_U
                && "-".equals(cur) && "_".equals(want)) return true;
        // I was "_"; product wants "-" after U took underscore
        if (keyCode == android.view.KeyEvent.KEYCODE_I
                && "_".equals(cur) && "-".equals(want)) return true;
        return false;
    }

    public synchronized String nameOf(String id) {
        Layout l = get(id);
        return l == null ? id : l.name;
    }

    public synchronized Layout create(String name) {
        String id = "c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Layout l = new Layout(id, (name == null || name.isEmpty()) ? "Layout" : name, false);
        List<Layout> all = list();
        all.add(l);
        saveAll(all);
        return l;
    }

    public synchronized Layout duplicate(String fromId, String newName) {
        Layout src = get(fromId);
        if (src == null) return create(newName);
        Layout l = create(newName != null ? newName : (src.name + " copy"));
        l.cells.clear();
        l.cells.putAll(src.cells);
        save(l);
        return l;
    }

    public synchronized void save(Layout layout) {
        if (layout == null) return;
        List<Layout> all = list();
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(layout.id)) {
                // keep builtin flag from store
                Layout prev = all.get(i);
                Layout n = new Layout(layout.id, layout.name, prev.builtin);
                n.cells.putAll(layout.cells);
                all.set(i, n);
                found = true;
                break;
            }
        }
        if (!found) all.add(layout);
        saveAll(all);
    }

    public synchronized boolean delete(String id) {
        if (id == null) return false;
        if (ID_SPECIALS.equals(id) || ID_ARROWS.equals(id)) {
            // reset builtin to factory
            List<Layout> all = list();
            for (int i = 0; i < all.size(); i++) {
                if (id.equals(all.get(i).id)) {
                    all.set(i, ID_SPECIALS.equals(id) ? builtinSpecials() : builtinArrows());
                    saveAll(all);
                    return true;
                }
            }
            return false;
        }
        List<Layout> all = list();
        Iterator<Layout> it = all.iterator();
        boolean removed = false;
        while (it.hasNext()) {
            if (id.equals(it.next().id)) {
                it.remove();
                removed = true;
            }
        }
        if (removed) saveAll(all);
        return removed;
    }

    public static String cellLabel(String cell) {
        if (cell == null || cell.isEmpty() || "-".equals(cell)) return "·";
        if (cell.startsWith("@host:")) return cell.substring(6);
        if (cell.startsWith("@key:")) {
            try {
                int kc = Integer.parseInt(cell.substring(5).trim());
                return keyName(kc);
            } catch (Exception e) {
                return cell;
            }
        }
        return cell;
    }

    public static String keyName(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return String.valueOf((char) ('A' + (keyCode - KeyEvent.KEYCODE_A)));
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return String.valueOf((char) ('0' + (keyCode - KeyEvent.KEYCODE_0)));
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return "↑";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "↓";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "←";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "→";
            case KeyEvent.KEYCODE_MOVE_HOME: return "Home";
            case KeyEvent.KEYCODE_MOVE_END: return "End";
            case KeyEvent.KEYCODE_PAGE_UP: return "PgUp";
            case KeyEvent.KEYCODE_PAGE_DOWN: return "PgDn";
            case KeyEvent.KEYCODE_SPACE: return "Space";
            case KeyEvent.KEYCODE_ENTER: return "Enter";
            case KeyEvent.KEYCODE_DEL: return "Bksp";
            case KeyEvent.KEYCODE_TAB: return "Tab";
            case KeyEvent.KEYCODE_ESCAPE: return "Esc";
            case KeyEvent.KEYCODE_F1: return "F1";
            case KeyEvent.KEYCODE_F2: return "F2";
            case KeyEvent.KEYCODE_F3: return "F3";
            case KeyEvent.KEYCODE_F4: return "F4";
            case KeyEvent.KEYCODE_F5: return "F5";
            case KeyEvent.KEYCODE_F6: return "F6";
            case KeyEvent.KEYCODE_F7: return "F7";
            case KeyEvent.KEYCODE_F8: return "F8";
            case KeyEvent.KEYCODE_F9: return "F9";
            case KeyEvent.KEYCODE_F10: return "F10";
            case KeyEvent.KEYCODE_SEMICOLON: return ";";
            default: return "K" + keyCode;
        }
    }

    /** QWERTY letter rows for editor grid. */
    public static int[][] letterRows() {
        return new int[][] {
            {
                KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_R,
                KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_I,
                KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_P
            },
            {
                KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F,
                KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K,
                KeyEvent.KEYCODE_L
            },
            {
                KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V,
                KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_M
            },
            {
                KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4,
                KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8,
                KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_0
            },
        };
    }

    private List<Layout> loadAll() {
        String raw = p.getString(KEY_JSON, null);
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        List<Layout> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                if (id.isEmpty()) continue;
                Layout l = new Layout(id, o.optString("name", id), o.optBoolean("builtin", false));
                JSONObject cells = o.optJSONObject("cells");
                if (cells != null) {
                    Iterator<String> keys = cells.keys();
                    while (keys.hasNext()) {
                        String ks = keys.next();
                        try {
                            int kc = Integer.parseInt(ks);
                            String v = cells.optString(ks, "");
                            if (v != null && !v.isEmpty()) l.cells.put(kc, v);
                        } catch (Exception ignored) {}
                    }
                }
                out.add(l);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void saveAll(List<Layout> layouts) {
        JSONArray a = new JSONArray();
        for (Layout l : layouts) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", l.id);
                o.put("name", l.name);
                o.put("builtin", l.builtin);
                JSONObject cells = new JSONObject();
                for (Map.Entry<Integer, String> e : l.cells.entrySet()) {
                    if (e.getValue() != null && !e.getValue().isEmpty()) {
                        cells.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
                o.put("cells", cells);
                a.put(o);
            } catch (Exception ignored) {}
        }
        p.edit().putString(KEY_JSON, a.toString()).apply();
    }
}
