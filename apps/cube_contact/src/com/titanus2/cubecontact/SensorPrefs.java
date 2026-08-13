package com.titanus2.cubecontact;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sensor selection + virtual sensor registry for the kernel lattice.
 * On-disk (world-readable for sampler):
 *   /data/local/tmp/cubebrain_viz/selected.txt
 *   /data/local/tmp/cubebrain_viz/virtual.tsv
 * Catalog from sampler:
 *   /data/local/tmp/cubebrain_viz/catalog.tsv  name\tvalue\tgroup\tenabled
 */
public final class SensorPrefs {
    public static final String VIZ_DIR = "/data/local/tmp/cubebrain_viz";
    public static final String SELECTED = VIZ_DIR + "/selected.txt";
    public static final String VIRTUAL = VIZ_DIR + "/virtual.tsv";
    public static final String CATALOG = VIZ_DIR + "/catalog.tsv";

    public static final class Entry {
        public String name;
        public long value;
        public String group;
        public boolean enabled;
        public boolean virtual;
    }

    private SensorPrefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("cube_sensors", Context.MODE_PRIVATE);
    }

    /** Load catalog; merge selection + virtual markers. */
    public static List<Entry> loadCatalog(Context c) {
        Map<String, Entry> map = new LinkedHashMap<>();
        File cat = new File(CATALOG);
        if (cat.isFile()) {
            try (BufferedReader br = new BufferedReader(new FileReader(cat))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] p = line.split("\t");
                    if (p.length < 1) continue;
                    Entry e = new Entry();
                    e.name = p[0].trim();
                    if (e.name.isEmpty()) continue;
                    try { e.value = p.length > 1 ? Long.parseLong(p[1].trim()) : 0; }
                    catch (Exception ex) { e.value = 0; }
                    e.group = p.length > 2 ? p[2].trim() : "other";
                    e.enabled = p.length < 4 || !"0".equals(p[3].trim());
                    e.virtual = "virtual".equals(e.group);
                    map.put(e.name, e);
                }
            } catch (Exception ignored) {}
        }
        // Virtual file may have sensors not yet in catalog tick (1.8 dual-read).
        for (Entry v : loadVirtualEntries(c)) {
            Entry e = map.get(v.name);
            if (e == null) {
                v.enabled = true;
                v.virtual = true;
                v.group = "virtual";
                map.put(v.name, v);
            } else {
                e.virtual = true;
                e.group = "virtual";
                e.value = v.value;
            }
        }
        // Apply app selection overlay if selected.txt exists
        Set<String> sel = loadSelectedSet();
        if (!sel.isEmpty()) {
            for (Entry e : map.values()) e.enabled = sel.contains(e.name);
        }
        List<Entry> out = new ArrayList<>(map.values());
        Collections.sort(out, (a, b) -> {
            int g = a.group.compareToIgnoreCase(b.group);
            if (g != 0) return g;
            return a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    public static Set<String> loadSelectedSet() {
        Set<String> s = new HashSet<>();
        File f = new File(SELECTED);
        if (!f.isFile() || f.length() == 0) return s;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                s.add(line.split("\\s+")[0]);
            }
        } catch (Exception ignored) {}
        return s;
    }

    public static void saveSelected(Context c, Set<String> names) {
        Exception err = null;
        try {
            File dir = new File(VIZ_DIR);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(SELECTED);
            try (BufferedWriter w = new BufferedWriter(new FileWriter(f))) {
                w.write("# Cube lattice selection — one channel name per line\n");
                w.write("# Empty file or missing = all sensors\n");
                List<String> sorted = new ArrayList<>(names);
                Collections.sort(sorted);
                for (String n : sorted) {
                    if (n == null || n.isEmpty()) continue;
                    w.write(n);
                    w.write('\n');
                }
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
            sp(c).edit().putInt("sel_count", names.size()).apply();
            return;
        } catch (Exception e) {
            err = e;
        }
        // Fallback: app files dir + shell copy (when SELinux blocks /data/local/tmp)
        try {
            File local = new File(c.getFilesDir(), "selected.txt");
            try (BufferedWriter w = new BufferedWriter(new FileWriter(local))) {
                for (String n : names) {
                    if (n == null || n.isEmpty()) continue;
                    w.write(n);
                    w.write('\n');
                }
            }
            Runtime.getRuntime().exec(new String[]{
                "sh", "-c",
                "cat '" + local.getAbsolutePath() + "' > '" + SELECTED
                    + "' 2>/dev/null; chmod 666 '" + SELECTED + "' 2>/dev/null"
            }).waitFor();
            sp(c).edit().putInt("sel_count", names.size()).apply();
        } catch (Exception e2) {
            throw new RuntimeException(
                "Cannot write selection (" + (err != null ? err.getMessage() : "?")
                    + " / " + e2.getMessage() + ")");
        }
    }

    public static void clearSelection(Context c) {
        try {
            File f = new File(SELECTED);
            if (f.isFile()) //noinspection ResultOfMethodCallIgnored
                f.delete();
            sp(c).edit().putInt("sel_count", -1).apply();
        } catch (Exception ignored) {}
    }

    /** App-private durable mirror of virtual.tsv (SELinux-safe). */
    public static File localVirtualFile(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "virtual.tsv");
    }

    /**
     * Parse one virtual.tsv body into entries. Empty / missing → empty list.
     */
    public static List<Entry> parseVirtualFile(File f) {
        List<Entry> list = new ArrayList<>();
        if (f == null || !f.isFile() || f.length() < 1) return list;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("[\\t ]+");
                if (p.length < 1) continue;
                Entry e = new Entry();
                e.name = p[0].trim();
                if (e.name.isEmpty()) continue;
                e.virtual = true;
                e.group = "virtual";
                e.enabled = true;
                try { e.value = p.length > 1 ? Long.parseLong(p[1].trim()) : 0; }
                catch (Exception ex) { e.value = 0; }
                list.add(e);
            }
        } catch (Exception ignored) {}
        return list;
    }

    /**
     * Load virtual sensors from kernel tmp path only (no app Context).
     * Prefer {@link #loadVirtualEntries(Context)} when Context is available —
     * 1.9 merges tmp + app filesDir (1.8 pick-one-file residual).
     */
    public static List<Entry> loadVirtualEntries() {
        return parseVirtualFile(new File(VIRTUAL));
    }

    /** True for First Cube LAW counters that only rise (energy must flow). */
    static boolean isLawCounterKey(String name) {
        return "virt_law_energy".equals(name)
                || "virt_law_wins".equals(name)
                || "virt_law_losses".equals(name)
                || "virt_law_combines".equals(name);
    }

    /**
     * 1.10 residual: peer/seed law JSON can return energy=0 while filesDir holds
     * real counters — never let a lower incoming wipe a higher durable value.
     */
    public static long mergeLawCounter(long existing, long incoming) {
        return Math.max(existing, incoming);
    }

    /**
     * Winner: prefer a real 0|1 over seed -1; never replace a real winner with -1.
     */
    public static long mergeLawWinner(long existing, long incoming) {
        if (incoming != -1L) return incoming;
        if (existing != -1L) return existing;
        return -1L;
    }

    /**
     * Known dual-path virtual.tsv locations (StateMatrix / rear path without Context).
     * Order: kernel tmp first, then CE user filesDir mirrors.
     */
    public static File[] virtualFileCandidates() {
        return new File[] {
            new File(VIRTUAL),
            new File("/data/user/0/com.titanus2.cubecontact/files/virtual.tsv"),
            new File("/data/data/com.titanus2.cubecontact/files/virtual.tsv"),
        };
    }

    /**
     * 1.11 residual: StateMatrix rear path max-merged peer law in memory only —
     * process death / Sensors refresh / peer seed re-zero left file SoT behind
     * peer energy (energy must flow into durable virtual.tsv). Max-merge counters
     * into dual files and rewrite both mirrors when any counter rose or winner
     * became real. No-op when incoming does not promote durable state.
     * 1.12 residual: Context-less hard-coded filesDir paths missed real
     * {@link #localVirtualFile} + proven SELinux shell fallback in
     * {@link #saveVirtual} — rear promote could leave durable CE mirror empty
     * while tmp-only write failed silently. Prefer Context overload when bound.
     *
     * @return true if any file was rewritten
     */
    public static boolean persistLawCounters(
            long energy, long wins, long losses, long combines, long winner) {
        return persistLawCounters(null, energy, wins, losses, combines, winner);
    }

    /**
     * Context-aware LAW persist (1.12). Uses dual-read load + {@link #saveVirtual}
     * when Context is non-null; falls back to hard-coded candidates otherwise.
     */
    public static boolean persistLawCounters(
            Context c,
            long energy, long wins, long losses, long combines, long winner) {
        Map<String, Long> m = new LinkedHashMap<>();
        if (c != null) {
            for (Entry e : loadVirtualEntries(c)) {
                if (e.name != null && !e.name.isEmpty()) m.put(e.name, e.value);
            }
        } else {
            for (Entry e : mergeVirtualFiles(virtualFileCandidates())) {
                if (e.name != null && !e.name.isEmpty()) m.put(e.name, e.value);
            }
        }
        long prevE = m.containsKey("virt_law_energy") ? m.get("virt_law_energy") : 0L;
        long prevW = m.containsKey("virt_law_wins") ? m.get("virt_law_wins") : 0L;
        long prevL = m.containsKey("virt_law_losses") ? m.get("virt_law_losses") : 0L;
        long prevC = m.containsKey("virt_law_combines") ? m.get("virt_law_combines") : 0L;
        long prevWin = m.containsKey("virt_law_winner") ? m.get("virt_law_winner") : -1L;
        long nextE = mergeLawCounter(prevE, energy);
        long nextW = mergeLawCounter(prevW, wins);
        long nextL = mergeLawCounter(prevL, losses);
        long nextC = mergeLawCounter(prevC, combines);
        long nextWin = mergeLawWinner(prevWin, winner);
        if (nextE == prevE && nextW == prevW && nextL == prevL
                && nextC == prevC && nextWin == prevWin
                && m.containsKey("virt_law_energy")) {
            return false;
        }
        m.put("virt_law_energy", nextE);
        m.put("virt_law_wins", nextW);
        m.put("virt_law_losses", nextL);
        m.put("virt_law_combines", nextC);
        m.put("virt_law_winner", nextWin);
        // Seed keys if file was empty so rear path can create durable SoT.
        if (!m.containsKey("virt_nanobot_up")) m.put("virt_nanobot_up", 0L);
        if (c != null) {
            try {
                saveVirtual(c, m);
                return true;
            } catch (Exception ignored) {
                // Fall through to hard-coded dual write.
            }
        }
        return writeVirtualMap(m);
    }

    /**
     * Write virtual.tsv map to tmp + filesDir mirrors (Context-less).
     * Same body layout as {@link #saveVirtual(Context, Map)}.
     */
    static boolean writeVirtualMap(Map<String, Long> values) {
        if (values == null || values.isEmpty()) return false;
        StringBuilder body = new StringBuilder();
        body.append("# Virtual sensors (nanobot / app). name value\n");
        List<String> keys = new ArrayList<>(values.keySet());
        Collections.sort(keys);
        for (String k : keys) {
            Long v = values.get(k);
            if (v == null || k == null || k.isEmpty()) continue;
            body.append(k).append('\t').append(v).append('\n');
        }
        String text = body.toString();
        boolean any = false;
        // App filesDir first (durable SELinux-safe).
        for (File local : new File[] {
            new File("/data/user/0/com.titanus2.cubecontact/files/virtual.tsv"),
            new File("/data/data/com.titanus2.cubecontact/files/virtual.tsv"),
        }) {
            try {
                File parent = local.getParentFile();
                if (parent != null && !parent.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                try (BufferedWriter w = new BufferedWriter(new FileWriter(local))) {
                    w.write(text);
                }
                any = true;
            } catch (Exception ignored) {}
        }
        // Kernel tmp mirror for sampler.
        try {
            File dir = new File(VIZ_DIR);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            try (BufferedWriter w = new BufferedWriter(new FileWriter(VIRTUAL))) {
                w.write(text);
            }
            //noinspection ResultOfMethodCallIgnored
            new File(VIRTUAL).setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            new File(VIRTUAL).setWritable(true, false);
            any = true;
        } catch (Exception ignored) {
            // Shell copy from first local that exists.
            try {
                File src = null;
                for (File f : virtualFileCandidates()) {
                    if (f != null && f.isFile() && f.length() > 0
                            && !VIRTUAL.equals(f.getAbsolutePath())) {
                        src = f;
                        break;
                    }
                }
                if (src != null) {
                    Runtime.getRuntime().exec(new String[]{
                        "sh", "-c",
                        "mkdir -p '" + VIZ_DIR + "' 2>/dev/null; "
                            + "cat '" + src.getAbsolutePath() + "' > '" + VIRTUAL
                            + "' 2>/dev/null; chmod 666 '" + VIRTUAL + "' 2>/dev/null"
                    }).waitFor();
                    any = true;
                }
            } catch (Exception ignored2) {}
        }
        return any;
    }

    /**
     * 1.9 residual: 1.8 pick-newest-file left LAW blank when kernel tmp was
     * newer (seed/touch) but filesDir held real virt_law_* counters, and
     * VirtualSensorSync merge from tmp-only then saveVirtual wiped the mirror.
     * Merge both paths: non-LAW keys → newer file wins; LAW counters → max
     * (energy must flow); winner → prefer non -1, else newer.
     */
    public static List<Entry> mergeVirtualFiles(File... files) {
        Map<String, Long> values = new LinkedHashMap<>();
        Map<String, Long> keyMtime = new LinkedHashMap<>();
        if (files != null) {
            // Older file first so newer overwrites non-LAW keys; counters max.
            File[] ordered = files.clone();
            java.util.Arrays.sort(ordered, (a, b) -> {
                long am = (a != null && a.isFile()) ? a.lastModified() : -1L;
                long bm = (b != null && b.isFile()) ? b.lastModified() : -1L;
                return Long.compare(am, bm);
            });
            for (File f : ordered) {
                if (f == null || !f.isFile() || f.length() < 1) continue;
                long fm = f.lastModified();
                for (Entry e : parseVirtualFile(f)) {
                    if (e.name == null || e.name.isEmpty()) continue;
                    Long prev = values.get(e.name);
                    Long prevMt = keyMtime.get(e.name);
                    if (prev == null) {
                        values.put(e.name, e.value);
                        keyMtime.put(e.name, fm);
                        continue;
                    }
                    if (isLawCounterKey(e.name)) {
                        // Counters only rise — never let a newer seed zero clobber.
                        if (e.value > prev) {
                            values.put(e.name, e.value);
                            keyMtime.put(e.name, fm);
                        }
                    } else if ("virt_law_winner".equals(e.name)) {
                        // Prefer a real winner over seed -1; else newer file.
                        if (prev == -1L && e.value != -1L) {
                            values.put(e.name, e.value);
                            keyMtime.put(e.name, fm);
                        } else if (e.value != -1L || prev == -1L) {
                            if (fm >= (prevMt != null ? prevMt : -1L)) {
                                values.put(e.name, e.value);
                                keyMtime.put(e.name, fm);
                            }
                        }
                    } else if (fm >= (prevMt != null ? prevMt : -1L)) {
                        values.put(e.name, e.value);
                        keyMtime.put(e.name, fm);
                    }
                }
            }
        }
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, Long> kv : values.entrySet()) {
            Entry e = new Entry();
            e.name = kv.getKey();
            e.value = kv.getValue() != null ? kv.getValue() : 0L;
            e.virtual = true;
            e.group = "virtual";
            e.enabled = true;
            out.add(e);
        }
        return out;
    }

    /**
     * 1.8 residual: dual-path tmp + filesDir (SELinux write-only left LAW in
     * filesDir only). 1.9: merge both (not pick-one-file by mtime).
     */
    public static List<Entry> loadVirtualEntries(Context c) {
        File tmp = new File(VIRTUAL);
        File local = c != null ? localVirtualFile(c) : null;
        if (local == null) return parseVirtualFile(tmp);
        return mergeVirtualFiles(tmp, local);
    }

    /** Rewrite virtual.tsv from map name→value. */
    public static void saveVirtual(Context c, Map<String, Long> values) {
        // 1.7 residual: SELinux often blocks app write to /data/local/tmp
        // (saveSelected already had shell fallback; virtual.tsv silent-fail left
        // file SoT empty → LAW blank after peer-dead / no-lattice path).
        // 1.8 residual: always keep app-files mirror so dual-read recovers LAW
        // even when shell copy to tmp fails (1.7 write-only fallback).
        StringBuilder body = new StringBuilder();
        body.append("# Virtual sensors (nanobot / app). name value\n");
        List<String> keys = new ArrayList<>(values.keySet());
        Collections.sort(keys);
        for (String k : keys) {
            Long v = values.get(k);
            if (v == null || k == null || k.isEmpty()) continue;
            body.append(k).append('\t').append(v).append('\n');
        }
        String text = body.toString();
        // Always durable mirror first (app sandbox — never SELinux-deny).
        try {
            File local = localVirtualFile(c);
            try (BufferedWriter w = new BufferedWriter(new FileWriter(local))) {
                w.write(text);
            }
        } catch (Exception ignored) {}
        try {
            File dir = new File(VIZ_DIR);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            try (BufferedWriter w = new BufferedWriter(new FileWriter(VIRTUAL))) {
                w.write(text);
            }
            //noinspection ResultOfMethodCallIgnored
            new File(VIRTUAL).setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            new File(VIRTUAL).setWritable(true, false);
            return;
        } catch (Exception ignored) {}
        // Shell copy fallback when direct tmp write fails (same SoT as saveSelected).
        try {
            File local = localVirtualFile(c);
            if (!local.isFile()) {
                try (BufferedWriter w = new BufferedWriter(new FileWriter(local))) {
                    w.write(text);
                }
            }
            Runtime.getRuntime().exec(new String[]{
                "sh", "-c",
                "mkdir -p '" + VIZ_DIR + "' 2>/dev/null; "
                    + "cat '" + local.getAbsolutePath() + "' > '" + VIRTUAL
                    + "' 2>/dev/null; chmod 666 '" + VIRTUAL + "' 2>/dev/null"
            }).waitFor();
        } catch (Exception ignored) {}
    }

    public static void putVirtual(Context c, String name, long value) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Entry e : loadVirtualEntries(c)) m.put(e.name, e.value);
        m.put(name, value);
        saveVirtual(c, m);
    }

    public static void removeVirtual(Context c, String name) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Entry e : loadVirtualEntries(c)) {
            if (!e.name.equals(name)) m.put(e.name, e.value);
        }
        saveVirtual(c, m);
    }

    /**
     * Merge-seed default virtual sensors including Crimson LAW keys.
     * 1.5 residual: early-return when file non-empty left virt_law_* missing forever
     * (peer-dead cool lab → rear LAW HUD blank).
     * 1.8: dual-read load so filesDir-only virt_law_* is not re-seeded over.
     */
    public static void ensureDefaultVirtual(Context c) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Entry e : loadVirtualEntries(c)) {
            if (e.name != null && !e.name.isEmpty()) m.put(e.name, e.value);
        }
        boolean changed = false;
        String[] names = {
            "virt_nanobot_up",
            "virt_nanobot_model",
            "virt_braincube_pick",
            "virt_braincube_activity",
            "virt_chat_last_ms",
            "virt_user_intent",
            "virt_law_energy",
            "virt_law_wins",
            "virt_law_losses",
            "virt_law_combines",
            "virt_law_winner",
        };
        long[] vals = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1};
        for (int i = 0; i < names.length; i++) {
            if (!m.containsKey(names[i])) {
                m.put(names[i], vals[i]);
                changed = true;
            }
        }
        if (changed || m.isEmpty()) {
            saveVirtual(c, m);
        }
    }
}
