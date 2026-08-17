package com.titanus2.controls;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generic chords: 2 or 3 keys held together. Not a dedicated modifier.
 * While a chord fires, those keys do not run their own short/long/double.
 * Existing {@code pair_a_b} prefs stay valid; triples are {@code pair_a_b_c}.
 */
public final class PairChordPrefs {
    public static final String PREFS = "titan2_pair_chords";
    public static final int MAX = 3;

    public static final class Entry {
        public final int a;
        public final int b;
        /** 0 = two-key chord. */
        public final int c;
        public final String action;

        public Entry(int a, int b, String action) {
            this(a, b, 0, action);
        }

        public Entry(int a, int b, int c, String action) {
            int[] n = norm(c > 0 ? new int[] { a, b, c } : new int[] { a, b });
            this.a = n.length > 0 ? n[0] : 0;
            this.b = n.length > 1 ? n[1] : 0;
            this.c = n.length > 2 ? n[2] : 0;
            this.action = action;
        }

        public int size() {
            return c > 0 ? 3 : 2;
        }

        public int[] ids() {
            return c > 0 ? new int[] { a, b, c } : new int[] { a, b };
        }

        public boolean contains(int id) {
            int x = KeyMapPrefs.canonicalizeScan(id);
            return x == a || x == b || (c > 0 && x == c);
        }
    }

    public static final class Hit {
        public final String action;
        public final int[] ids;
        public Hit(String action, int[] ids) {
            this.action = action;
            this.ids = ids;
        }
        public int size() {
            return ids == null ? 0 : ids.length;
        }
    }

    private final SharedPreferences p;

    public PairChordPrefs(Context ctx) {
        p = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int[] norm(int... raw) {
        if (raw == null) return new int[0];
        int[] tmp = new int[raw.length];
        int n = 0;
        for (int v : raw) {
            int c = KeyMapPrefs.canonicalizeScan(v);
            if (c <= 0) continue;
            boolean dup = false;
            for (int i = 0; i < n; i++) {
                if (tmp[i] == c) {
                    dup = true;
                    break;
                }
            }
            if (!dup) tmp[n++] = c;
        }
        int[] out = Arrays.copyOf(tmp, n);
        Arrays.sort(out);
        return out;
    }

    public String get(int a, int b) {
        return get(new int[] { a, b });
    }

    public String get(int a, int b, int c) {
        return get(new int[] { a, b, c });
    }

    public String get(int[] ids) {
        int[] n = norm(ids);
        if (n.length < 2 || n.length > MAX) return null;
        String act = p.getString(key(n), null);
        if (act == null || act.isEmpty() || KeyMapPrefs.ACT_NONE.equals(act)
                || KeyMapPrefs.ACT_DEFAULT.equals(act)) {
            return null;
        }
        return act;
    }

    public void set(int a, int b, String action) {
        set(new int[] { a, b }, action);
    }

    public void set(int a, int b, int c, String action) {
        set(new int[] { a, b, c }, action);
    }

    public void set(int[] ids, String action) {
        int[] n = norm(ids);
        if (n.length < 2 || n.length > MAX) return;
        if (action == null || action.isEmpty() || KeyMapPrefs.ACT_NONE.equals(action)
                || KeyMapPrefs.ACT_DEFAULT.equals(action)) {
            p.edit().remove(key(n)).apply();
        } else {
            p.edit().putString(key(n), action).apply();
        }
    }

    public void remove(int a, int b) {
        set(new int[] { a, b }, null);
    }

    public void remove(int a, int b, int c) {
        set(new int[] { a, b, c }, null);
    }

    public void remove(Entry e) {
        if (e != null) set(e.ids(), null);
    }

    public List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        Map<String, ?> all = p.getAll();
        if (all == null) return out;
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (k == null || !k.startsWith("pair_") || v == null) continue;
            String act = String.valueOf(v);
            if (act.isEmpty()) continue;
            int[] ids = parseKey(k);
            if (ids == null) continue;
            if (ids.length == 3) out.add(new Entry(ids[0], ids[1], ids[2], act));
            else out.add(new Entry(ids[0], ids[1], act));
        }
        return out;
    }

    public boolean involves(int scan) {
        int c = KeyMapPrefs.canonicalizeScan(scan);
        if (c <= 0) return false;
        for (Entry e : list()) {
            if (e.contains(c)) return true;
        }
        return false;
    }

    /**
     * Longest saved chord that includes {@code scan} and is a subset of
     * {@code held} (already includes scan and any meta partner).
     */
    public Hit bestHit(int scan, Set<Integer> held) {
        if (held == null || held.isEmpty()) return null;
        int c = KeyMapPrefs.canonicalizeScan(scan);
        if (c <= 0) return null;
        Hit best = null;
        for (Entry e : list()) {
            if (!e.contains(c)) continue;
            if (!subset(e.ids(), held)) continue;
            if (best == null || e.size() > best.size()) {
                best = new Hit(e.action, e.ids());
            }
        }
        return best;
    }

    /** True if some 3-key chord contains every id in {@code ids}. */
    public boolean extendsToTriple(int[] ids) {
        int[] n = norm(ids);
        if (n.length < 2) return false;
        for (Entry e : list()) {
            if (e.size() != 3) continue;
            if (containsAll(e.ids(), n)) return true;
        }
        return false;
    }

    /** True if {@code held} is a proper prefix of some saved 3-key chord. */
    public boolean isPrefixOfTriple(Set<Integer> held) {
        if (held == null || held.size() < 1 || held.size() > 2) return false;
        for (Entry e : list()) {
            if (e.size() != 3) continue;
            if (containsAll(e.ids(), held)) return true;
        }
        return false;
    }

    public String match(int scan, Set<Integer> down) {
        if (down == null) return null;
        Set<Integer> held = new HashSet<>(down);
        held.add(scan);
        Hit h = bestHit(scan, held);
        return h != null && h.size() == 2 ? h.action : (h != null ? h.action : null);
    }

    public String matchMeta(int scan, int metaState) {
        int mod = KeyMapPrefs.metaChordScan(metaState);
        if (mod <= 0) return null;
        Set<Integer> held = new HashSet<>();
        held.add(scan);
        held.add(mod);
        Hit h = bestHit(scan, held);
        return h != null ? h.action : null;
    }

    public boolean involvesMeta(int scan, int metaState) {
        int c = KeyMapPrefs.canonicalizeScan(scan);
        if (c <= 0) return false;
        int mod = KeyMapPrefs.metaChordScan(metaState);
        if (mod <= 0 || mod == c) return false;
        if (get(new int[] { c, mod }) != null) return true;
        for (Entry e : list()) {
            if (e.size() == 3 && e.contains(c) && e.contains(mod)) return true;
        }
        return false;
    }

    public int metaPartner(int scan, int metaState) {
        int c = KeyMapPrefs.canonicalizeScan(scan);
        if (c <= 0) return 0;
        int mod = KeyMapPrefs.metaChordScan(metaState);
        if (mod <= 0 || mod == c) return 0;
        return involvesMeta(c, metaState) ? mod : 0;
    }

    public int partner(int scan, Set<Integer> down) {
        Hit h = bestHit(scan, down == null ? new HashSet<Integer>() : down);
        if (h == null) return 0;
        int c = KeyMapPrefs.canonicalizeScan(scan);
        for (int id : h.ids) {
            if (id != c) return id;
        }
        return 0;
    }

    private static boolean subset(int[] ids, Set<Integer> held) {
        for (int id : ids) {
            if (!heldContains(held, id)) return false;
        }
        return true;
    }

    private static boolean containsAll(int[] hay, int[] needle) {
        for (int n : needle) {
            boolean ok = false;
            for (int h : hay) {
                if (h == n) {
                    ok = true;
                    break;
                }
            }
            if (!ok) return false;
        }
        return true;
    }

    private static boolean containsAll(int[] hay, Set<Integer> needle) {
        for (Integer n : needle) {
            if (n == null) continue;
            int c = KeyMapPrefs.canonicalizeScan(n);
            boolean ok = false;
            for (int h : hay) {
                if (h == c) {
                    ok = true;
                    break;
                }
            }
            if (!ok) return false;
        }
        return true;
    }

    private static boolean heldContains(Set<Integer> held, int id) {
        int c = KeyMapPrefs.canonicalizeScan(id);
        for (Integer h : held) {
            if (h != null && KeyMapPrefs.canonicalizeScan(h) == c) return true;
        }
        return false;
    }

    private static String key(int[] n) {
        if (n.length == 3) return "pair_" + n[0] + "_" + n[1] + "_" + n[2];
        return "pair_" + n[0] + "_" + n[1];
    }

    private static int[] parseKey(String k) {
        String rest = k.substring(5);
        String[] p = rest.split("_");
        if (p.length != 2 && p.length != 3) return null;
        try {
            int[] ids = new int[p.length];
            for (int i = 0; i < p.length; i++) ids[i] = Integer.parseInt(p[i]);
            return norm(ids);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
