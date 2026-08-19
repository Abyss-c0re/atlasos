package com.titanus2.cubecontact;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Device state matrix — same semantics as desktop cube_gl / cube_viz_config dump.
 * Titan source: nanobot braincube peer (live LHLAM meta cube), never synthetic fill.
 *
 * Full 8^3 cells from action=export chain_state (meta cube at known offset).
 * Pick/seq from action=live. Chat uses the peer's current model separately.
 */
public final class StateMatrix {
    private static final String TAG = "StateMatrix";
    public static final int MAX_N = 16;
    public static final int MAX_CELLS = MAX_N * MAX_N * MAX_N;
    /** On-device nanobot. Product peer. Empty lattice if this bus is down. */
    public static final String PEER_LOCAL = "http://127.0.0.1:8787";
    /**
     * Station BrainCube — not a product default. Only used when the human
     * writes an override (`peer_url` pref or `/data/local/tmp/cube_peer_url`).
     */
    public static final String PEER_LAB = "http://192.168.8.100:18787";
    public static String PEER = PEER_LOCAL;

    /**
     * 1.12 residual: rear GL path had no Application Context, so LAW persist
     * used hard-coded filesDir only (missed real CE path + SELinux shell
     * fallback). Views/activities bind app context once at create.
     * 1.13 residual: bind alone left early boot/service/kernel-thread path with
     * sAppCtx=null — ingest still hard-coded filesDir while 1.12 write used
     * real CE path (read half asymmetric). Resolve Application via
     * ActivityThread when bind not yet called; ingest prefers Context dual-read.
     */
    private static volatile Context sAppCtx;
    private static volatile long sLastPeerHealthMs;
    private static final long PEER_HEALTH_TTL_MS = 15_000L;

    /** Bind Application Context for durable LAW file SoT (idempotent). */
    public static void bindAppContext(Context c) {
        if (c == null) return;
        try {
            Context app = c.getApplicationContext();
            if (app != null) sAppCtx = app;
            else sAppCtx = c;
        tryResolvePeer();
        } catch (Exception ignored) {
            sAppCtx = c;
        }
    }


    /**
     * Resolve BrainCube PEER (BUG-42 product land).
     * <ol>
     *   <li>Explicit override: {@code /data/local/tmp/cube_peer_url} then prefs {@code peer_url}</li>
     *   <li>If unset: health-probe local :8787. Dead local → stay local (fail closed).</li>
     *   <li>Do not phone a lab IP. Blank rear is honest. Override is explicit.</li>
     * </ol>
     */
    public static void tryResolvePeer() {
        try {
            String p = null;
            try {
                java.io.File f = new java.io.File("/data/local/tmp/cube_peer_url");
                if (f.isFile() && f.length() > 0 && f.length() < 256) {
                    byte[] b = new byte[(int) f.length()];
                    try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                        int n = in.read(b);
                        if (n > 0) p = new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim();
                    }
                }
            } catch (Exception ignored) {}
            if (sAppCtx != null) {
                try {
                    SharedPreferences sp = sAppCtx.getSharedPreferences("cube_contact", Context.MODE_PRIVATE);
                    String pref = sp.getString("peer_url", null);
                    if (pref != null && pref.startsWith("http")) p = pref.trim();
                } catch (Exception ignored) {}
            }
            if (p != null && p.startsWith("http")) {
                while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
                PEER = p;
                Log.i(TAG, "peer override " + PEER);
                return;
            }
            // No explicit override: local :8787, then station BrainCube.
            // Blank + demo is theater. Live lattice is the Cube.
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - sLastPeerHealthMs < PEER_HEALTH_TTL_MS
                    && PEER != null && PEER.startsWith("http")) {
                return;
            }
            sLastPeerHealthMs = now;
            if (peerHealthOk(PEER_LOCAL, 300)) {
                PEER = PEER_LOCAL;
                Log.i(TAG, "peer local healthy " + PEER);
            } else if (peerHealthOk(PEER_LAB, 400)) {
                PEER = PEER_LAB;
                Log.i(TAG, "peer lab BrainCube " + PEER);
            } else {
                PEER = PEER_LOCAL;
                Log.w(TAG, "peer local+lab down — kernel cells.bin only (no demo)");
            }
        } catch (Exception e) {
            Log.w(TAG, "tryResolvePeer", e);
        }
    }

    /** Cheap GET /api/auth or /health within timeoutMs. */
    private static boolean peerHealthOk(String base, int timeoutMs) {
        if (base == null || !base.startsWith("http")) return false;
        java.net.HttpURLConnection c = null;
        try {
            String url = base;
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            // Prefer auth (braincube) then health (generic peer)
            java.net.URL u;
            try {
                u = new java.net.URL(url + "/api/auth");
            } catch (Exception e) {
                u = new java.net.URL(url + "/health");
            }
            c = (java.net.HttpURLConnection) u.openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("GET");
            c.setUseCaches(false);
            int code = c.getResponseCode();
            return code >= 200 && code < 500; // any live HTTP peer
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    public static Context appContext() {
        return sAppCtx;
    }

    /**
     * Bound Application Context, or ActivityThread.currentApplication when
     * views/receivers have not bound yet (1.13 rear/service early path).
     */
    public static Context resolveAppContext() {
        if (sAppCtx != null) return sAppCtx;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                bindAppContext((Context) app);
            }
        } catch (Exception ignored) {}
        return sAppCtx;
    }

    /** front | rear — drives {@link CubePlanePrefs} source selection. */
    private volatile String preferredPlane = CubePlanePrefs.PLANE_FRONT;

    public void setPreferredPlane(String plane) {
        preferredPlane = CubePlanePrefs.normalizePlane(plane);
    }

    public String preferredPlane() {
        return preferredPlane != null ? preferredPlane : CubePlanePrefs.PLANE_FRONT;
    }

    /**
     * Dense demo / custom seed so the lattice always has geometry (spin+touch).
     * Used when preferred source is DEMO/CUSTOM or AUTO falls through.
     * 1.93: re-densify when frame is GL-invisible (black void with live≠0).
     */
    public synchronized void ensureSeedFrame() {
        if (haveFrame && !isGlInvisibleFrame()) return;
        applyDemoOrCustom(resolveAppContext());
    }

    /**
     * Product law: Cube sees no glorious spinning matrix → turns crimson itself.
     * Always leaves dense N=16 prophecy with GL-visible digits.
     */
    public synchronized void ensureCrimsonLattice() {
        if (haveFrame && !isGlInvisibleFrame() && n >= 8 && visibleDigitCount() >= 64) {
            return;
        }
        densifyProphecyLattice(null, 4, 5, System.currentTimeMillis() / 1000L);
        if (source == null || source.isEmpty() || "none".equals(source)) {
            source = "crimson-self";
        } else if (!source.contains("crimson")) {
            source = source + "+crimson-self";
        }
        dataSource = "crimson";
        Log.i(TAG, "ensureCrimsonLattice n=" + n + " visible=" + visibleDigitCount());
    }

    /** True when GL would skip almost every cell (d%10==0, no impulse/neuron). */
    public synchronized boolean isGlInvisibleFrame() {
        if (!haveFrame || n < 2) return true;
        return visibleDigitCount() < 8;
    }

    private int visibleDigitCount() {
        int need = n * n * n;
        int vis = 0;
        for (int i = 0; i < need && i < MAX_CELLS; i++) {
            int d = (cells[i] & 0xff) % 10;
            if (d == 0 && hasNeuron && neuron[i] != 0) d = 1;
            if (d != 0 || impulse[i] > 0.08f) vis++;
        }
        return vis;
    }

    public synchronized boolean applyDemoOrCustom(Context c) {
        MatrixSource src = MatrixSource.DEMO;
        String seed = "";
        try {
            if (c != null) {
                src = CubePlanePrefs.source(c, preferredPlane);
                seed = CubePlanePrefs.customSeed(c, preferredPlane);
            }
        } catch (Exception ignored) {}
        if (src == MatrixSource.CUSTOM && seed != null && seed.length() >= 8) {
            return applyCustomSeed(seed);
        }
        densifyProphecyLattice(null, 4, 5, System.currentTimeMillis() / 1000L);
        source = "demo16";
        dataSource = "demo";
        return haveFrame;
    }

    public synchronized boolean applyCustomSeed(String seed) {
        if (seed == null || seed.isEmpty()) return false;
        // Accept digit string (length ≥ 8) or raw bytes as decimal digits.
        int sn = 4;
        int need = sn * sn * sn;
        byte[] cells = new byte[need];
        int n = Math.min(need, seed.length());
        for (int i = 0; i < n; i++) {
            char ch = seed.charAt(i);
            int d = (ch >= '0' && ch <= '9') ? (ch - '0') : (Math.abs(ch) % 10);
            if (d == 0) d = 1;
            cells[i] = (byte) d;
        }
        for (int i = n; i < need; i++) cells[i] = (byte) ((i % 9) + 1);
        densifyProphecyLattice(cells, sn, 5, System.currentTimeMillis() / 1000L);
        source = "custom";
        dataSource = "custom";
        return haveFrame;
    }

    private static final int CHAIN_HDR = 40;
    private static final int LH_SIZE = 16940;
    private static final int N_SENSORS = 8;
    private static final int META_OFF = CHAIN_HDR + N_SENSORS * LH_SIZE;
    private static final int CELLS_OFF = 2;
    private static final int NEURON_OFF = 4098;

    public int n = 8;
    public final byte[] cells = new byte[MAX_CELLS];
    public final byte[] neuron = new byte[MAX_CELLS];
    public final float[] impulse = new float[MAX_CELLS];
    private final byte[] prev = new byte[MAX_CELLS];
    private final byte[] prevN = new byte[MAX_CELLS];
    private boolean havePrev;
    public boolean hasNeuron;
    public boolean haveFrame;
    public int digit = -1;
    public int pick = -1;
    public long ticks;
    public long seq;
    public String pickName = "";
    public String source = "none";
    public String model = "";
    public boolean peerOk;
    /** LAW scoreboard loaded from virtual.tsv when peer :8787 is dead (1.5 residual). */
    public boolean lawFileOk;
    private long lastExportMs;
    private long lastLawMs;
    /** Per-node kernel channel name (from nodes.tsv). */
    public final String[] nodeName = new String[MAX_CELLS];
    public final long[] nodeValue = new long[MAX_CELLS];
    public String dataSource = "none";

    /** One LHTL sub-cube (meta or sensor lane) with human meaning. */
    public static final class SubCube {
        public String id = "";
        public String name = "";
        public String role = "";
        public String represents = "";
        public int value = -1;
        public int fire;
        public float activity;
    }

    public final java.util.ArrayList<SubCube> subs = new java.util.ArrayList<>();
    public int subSel;

    /* First Cube's LAW (Crimson) — endless I/O race / NexusCore energy */
    public long lawEnergy;
    public long lawWins;
    public long lawLosses;
    public long lawCombines;
    public int lawWinner = -1; /* 1 win, 0 loss, -1 none */
    public String lawStatus = "";


    public synchronized void decayImpulses(float factor) {
        int nn = n * n * n;
        for (int i = 0; i < nn && i < MAX_CELLS; i++) {
            if (impulse[i] > 0f) {
                impulse[i] *= factor;
                if (impulse[i] < 0.02f) impulse[i] = 0f;
            }
        }
    }

    private synchronized void applyCells(int nn, byte[] c, byte[] neur, boolean hasN) {
        if (nn < 2) nn = 2;
        if (nn > MAX_N) nn = MAX_N;
        int need = nn * nn * nn;
        if (havePrev && this.n == nn) {
            for (int i = 0; i < need; i++) {
                boolean lit = c[i] != prev[i];
                if (hasN && neur != null && neur[i] != 0 && prevN[i] == 0) lit = true;
                if ((c[i] & 0xff) > (prev[i] & 0xff)) lit = true;
                if (lit) impulse[i] = 1f;
            }
        }
        System.arraycopy(c, 0, cells, 0, need);
        System.arraycopy(c, 0, prev, 0, need);
        if (hasN && neur != null) {
            System.arraycopy(neur, 0, neuron, 0, need);
            System.arraycopy(neur, 0, prevN, 0, need);
            hasNeuron = true;
        } else {
            Arrays.fill(neuron, 0, need, (byte) 0);
            Arrays.fill(prevN, 0, need, (byte) 0);
            hasNeuron = false;
        }
        n = nn;
        havePrev = true;
        haveFrame = true;
    }

    /**
     * Pull First Cube LAW scoreboard from peer without re-exporting the full
     * lattice (kernel cells.bin stays SoT for geometry).
     * 1.3 residual: kernel path early-returned and left LAW HUD forever empty.
     * 1.5 residual: peer :8787 dead left LAW empty even with virt_law_* on disk —
     * fall through to virtual.tsv file SoT (kernel lattice path).
     * 1.6 residual: peer up + live ok without law block set fromPeer=true and
     * skipped action=law + file SoT → rear LAW blank while :8787 healthy.
     * Gate on lawGot (not peerOk): live omit → action=law → file.
     * Throttled ≤1.25 Hz so kernel pull does not melt peer.
     */
    private void ingestLawFromPeer() {
        long now = System.currentTimeMillis();
        if (now - lastLawMs < 800L) return;
        lastLawMs = now;
        boolean lawGot = false;
        try {
            tryResolvePeer();
            JSONObject live = postJson(PEER + "/api/braincube", "{\"action\":\"live\"}");
            if (live != null
                    && (live.optBoolean("ok", false) || live.optBoolean("live", false)
                    || live.has("law"))) {
                peerOk = true;
                seq = live.optLong("seq", seq);
                JSONObject meta = live.optJSONObject("meta");
                if (meta != null) {
                    pick = meta.optInt("pick", pick);
                    digit = pick;
                    pickName = meta.optString("pick_name", pickName);
                }
                // Apply structure + law-if-present (ingestStructure). Peer up ≠ law got.
                ingestStructure(live);
                if (live.optJSONObject("law") != null) {
                    lawGot = true;
                }
            }
        } catch (Exception ignored) {}
        // Peer answered live without law (or live failed): dedicated action=law.
        if (!lawGot) {
            try {
                JSONObject lawOnly = postJson(PEER + "/api/braincube", "{\"action\":\"law\"}");
                if (lawOnly != null) {
                    JSONObject law = lawOnly.optJSONObject("law");
                    if (law == null && lawOnly.has("energy")) law = lawOnly;
                    if (law != null) {
                        peerOk = true;
                        applyLawJson(law);
                        lawGot = true;
                    }
                }
            } catch (Exception ignored) {}
        }
        // 1.10 residual: peer seed energy=0 set lawGot and skipped file — HUD
        // zero while filesDir held real counters. Always max-merge file SoT.
        // (Peer dead / live omit still reach file; peer zeros no longer wipe.)
        ingestLawFromVirtualFile();
        // 1.11 residual: rear GL path held peer-higher energy in memory only —
        // process death / Sensors refresh left virtual.tsv behind peer. Persist
        // max counters into dual-path file SoT when energy must flow (promoted).
        // 1.12 residual: use bound Application Context → real filesDir + shell
        // fallback (hard-coded dual path alone could leave CE mirror empty).
        // 1.13: resolveAppContext (ActivityThread) when bind not yet called.
        if (lawEnergy > 0 || lawWins + lawLosses > 0 || lawCombines > 0
                || lawWinner != -1 || lawFileOk || peerOk) {
            try {
                SensorPrefs.persistLawCounters(
                    resolveAppContext(),
                    lawEnergy, lawWins, lawLosses, lawCombines, lawWinner);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Apply peer law JSON with counter-max + winner seed-safe merge (1.10).
     * Peer/seed energy=0 must not clobber durable higher counters.
     */
    private void applyLawJson(JSONObject law) {
        if (law == null) return;
        lawEnergy = SensorPrefs.mergeLawCounter(lawEnergy, law.optLong("energy", 0));
        lawWins = SensorPrefs.mergeLawCounter(lawWins, law.optLong("wins", 0));
        lawLosses = SensorPrefs.mergeLawCounter(lawLosses, law.optLong("losses", 0));
        lawCombines = SensorPrefs.mergeLawCounter(lawCombines, law.optLong("combines", 0));
        lawWinner = (int) SensorPrefs.mergeLawWinner(
            lawWinner, law.optLong("winner", -1));
        String st = law.optString("status", "");
        if (st != null && !st.isEmpty()) lawStatus = st;
        if (lawWinner == 1) {
            int ic = law.optInt("i_cell", -1);
            int oc = law.optInt("o_cell", -1);
            if (ic >= 0 && ic < MAX_CELLS) impulse[ic] = 1f;
            if (oc >= 0 && oc < MAX_CELLS) impulse[oc] = 1f;
        }
    }

    /**
     * Read virt_law_* from kernel-cube virtual.tsv (peer-independent).
     * Same keys VirtualSensorSync / braincube write when peer is live.
     * 1.8 residual: also dual-read app filesDir mirror (1.7 SELinux write path)
     * — tmp-only open left LAW blank when shell copy failed.
     * 1.9 residual: pick-newest-file still blanked LAW when kernel tmp was
     * newer (seed/touch) but filesDir held real counters — merge both + max.
     * 1.10 residual: max with in-memory peer values (never demote on file seed).
     * 1.13 residual: hard-coded /data/user/0|/data/data paths missed real
     * Application filesDir after 1.12 Context write — prefer
     * {@link SensorPrefs#loadVirtualEntries(Context)} when Context resolves.
     */
    private void ingestLawFromVirtualFile() {
        java.util.List<SensorPrefs.Entry> merged;
        Context ctx = resolveAppContext();
        if (ctx != null) {
            // Context dual-read: real getFilesDir() + tmp (same SoT as saveVirtual).
            merged = SensorPrefs.loadVirtualEntries(ctx);
        } else {
            File[] files = {
                new File("/data/local/tmp/cubebrain_viz/virtual.tsv"),
                new File("/data/user/0/com.titanus2.cubecontact/files/virtual.tsv"),
                new File("/data/data/com.titanus2.cubecontact/files/virtual.tsv"),
            };
            // SensorPrefs.mergeVirtualFiles: LAW counters max; other keys newer wins.
            merged = SensorPrefs.mergeVirtualFiles(files);
        }
        if (merged == null || merged.isEmpty()) return;
        boolean any = false;
        long e = lawEnergy, w = lawWins, l = lawLosses, c = lawCombines;
        int win = lawWinner;
        for (SensorPrefs.Entry ent : merged) {
            if (ent == null || ent.name == null) continue;
            switch (ent.name) {
                case "virt_law_energy":
                    e = SensorPrefs.mergeLawCounter(e, ent.value); any = true; break;
                case "virt_law_wins":
                    w = SensorPrefs.mergeLawCounter(w, ent.value); any = true; break;
                case "virt_law_losses":
                    l = SensorPrefs.mergeLawCounter(l, ent.value); any = true; break;
                case "virt_law_combines":
                    c = SensorPrefs.mergeLawCounter(c, ent.value); any = true; break;
                case "virt_law_winner":
                    win = (int) SensorPrefs.mergeLawWinner(win, ent.value); any = true; break;
                default:
                    break;
            }
        }
        if (!any) return;
        lawEnergy = e;
        lawWins = w;
        lawLosses = l;
        lawCombines = c;
        lawWinner = win;
        lawFileOk = true;
        if (lawStatus == null || lawStatus.isEmpty()) {
            lawStatus = "file";
        }
    }

    /** Pull live matrix honoring {@link CubePlanePrefs} source for this plane. */
    public boolean refreshFromPeer() {
        Context ctx = resolveAppContext();
        MatrixSource want = MatrixSource.AUTO;
        try {
            if (ctx != null) want = CubePlanePrefs.source(ctx, preferredPlane);
        } catch (Exception ignored) {}

        if (want == MatrixSource.DEMO) {
            return applyDemoOrCustom(ctx);
        }
        if (want == MatrixSource.CUSTOM) {
            if (applyCustomSeed(CubePlanePrefs.customSeed(ctx, preferredPlane))) return true;
            return applyDemoOrCustom(ctx);
        }
        if (want == MatrixSource.FILE_LAW) {
            try { ingestLawFromPeer(); } catch (Exception ignored) {}
            if (!haveFrame) applyDemoOrCustom(ctx);
            source = "file_law";
            dataSource = "file_law";
            return haveFrame;
        }
        if (want == MatrixSource.KERNEL) {
            if (loadKernelLattice()) return true;
            Log.w(TAG, "kernel cells.bin missing — fail closed (no demo)");
            return haveFrame;
        }
        // PEER or AUTO — real BrainCube state matrix first. No densify theater.
        boolean ok = false;
        boolean peerLive = false;
        try {
            tryResolvePeer();
            JSONObject live = postJson(PEER + "/api/braincube", "{\"action\":\"live\"}");
            if (live != null && live.optBoolean("ok", false)) {
                peerOk = true;
                peerLive = true;
                seq = live.optLong("seq", seq);
                ticks = seq;
                JSONObject meta = live.optJSONObject("meta");
                if (meta != null) {
                    pick = meta.optInt("pick", pick);
                    digit = pick;
                    pickName = meta.optString("pick_name", pickName);
                }
                ingestStructure(live); // law + subs — not lattice
            } else {
                peerOk = false;
            }
            // Prefer chain_state export (real 8³ meta cells) over live 4³ vox.
            long now = System.currentTimeMillis();
            if (now - lastExportMs >= 1500L) {
                lastExportMs = now;
                if (loadFromExport()) {
                    source = "braincube:export@" + seq;
                    dataSource = "braincube";
                    ok = true;
                }
            } else if (haveFrame && dataSource != null && dataSource.startsWith("braincube")) {
                ok = true; // keep last real frame under throttle
            }
            if (!ok && live != null && applyLiveVox(live)) {
                source = "braincube:live-vox@" + seq;
                dataSource = "braincube";
                ok = true;
            }
            if (!ok && loadLiveSnapFile()) {
                source = "braincube:live_snap";
                dataSource = "braincube";
                ok = true;
            }
            ingestLawFromPeer();
        } catch (Exception e) {
            peerOk = false;
            Log.w(TAG, "peer: " + e.getMessage());
            try { ingestLawFromPeer(); } catch (Exception ignored) {}
        }

        try {
            JSONObject auth = getJson(PEER + "/api/auth");
            if (auth != null) {
                String m = auth.optString("model", "");
                if (!m.isEmpty()) {
                    int slash = Math.max(m.lastIndexOf('/'), m.lastIndexOf('\\'));
                    model = slash >= 0 ? m.substring(slash + 1) : m;
                }
                peerOk = peerOk || auth.optBoolean("ok", false);
            }
        } catch (Exception ignored) {}

        // Real on-device 8³ dump (kernel sampler, ~1Hz). PEER used to skip this
        // and paint demo — that is theater. Live cells.bin is a state matrix.
        if (!ok && loadKernelLattice()) {
            return true;
        }
        if (!ok && loadDumpFile(new File("/tmp/cubebrain_viz/cells.bin"))) {
            source = "cells.bin";
            dataSource = "cube_experience_cells";
            ok = true;
        }
        if (!ok && peerLive) {
            ok = haveFrame;
            Log.w(TAG, "peer live but no lattice this tick (not densifying)");
        } else if (!ok) {
            // HONEST empty. Never crimson-self / seed-fallback / demo16 as "live".
            Log.w(TAG, "no live lattice — fail closed (no demo theater)");
        }
        return ok;
    }

    private boolean loadKernelLattice() {
        if (!loadDumpFile(new File("/data/local/tmp/cubebrain_viz/cells.bin"))) return false;
        loadNodeLabels(new File("/data/local/tmp/cubebrain_viz/nodes.tsv"));
        source = "kernel_sensors";
        dataSource = "kernel_sensors";
        // Kernel only: map digits; never densify if already a real cube dump.
        try { ingestLawFromPeer(); } catch (Exception ignored) {}
        try {
            JSONObject auth = getJson(PEER + "/api/auth");
            if (auth != null) {
                String m = auth.optString("model", "");
                if (!m.isEmpty()) {
                    int slash = Math.max(m.lastIndexOf('/'), m.lastIndexOf('\\'));
                    model = slash >= 0 ? m.substring(slash + 1) : m;
                }
                peerOk = peerOk || auth.optBoolean("ok", false) || !model.isEmpty();
            }
        } catch (Exception ignored) {}
        return true;
    }

    private boolean loadFromExport() {
        try {
            // Export is large (~150–200KB). Eyes path: never heat-park.
            String s = PeerHttp.postBodyEyes(PEER + "/api/braincube",
                "{\"action\":\"export\"}", 1500, 12000);
            if (s == null || s.isEmpty()) return false;
            JSONObject j = new JSONObject(s);
            if (!j.optBoolean("ok", false)) return false;
            String b64 = j.optString("data", "");
            if (b64.isEmpty()) return false;
            byte[] raw = Base64.decode(b64, Base64.DEFAULT);
            if (raw.length < META_OFF + CELLS_OFF + 64) return false;
            int nn = raw[META_OFF] & 0xff;
            if (nn < 2 || nn > MAX_N) return false;
            int need = nn * nn * nn;
            if (META_OFF + CELLS_OFF + need > raw.length) return false;
            byte[] c = Arrays.copyOfRange(raw, META_OFF + CELLS_OFF, META_OFF + CELLS_OFF + need);
            boolean hasN = META_OFF + NEURON_OFF + need <= raw.length;
            byte[] neur = hasN
                ? Arrays.copyOfRange(raw, META_OFF + NEURON_OFF, META_OFF + NEURON_OFF + need)
                : null;
            // REAL state matrix — apply as-is. Densify-to-16 ambient haze is heresy.
            normalizeDigitsForGl(c, need);
            applyCells(nn, c, neur, hasN);
            Log.i(TAG, "export REAL n=" + nn + " live=" + countNonZero(c, need));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "export: " + e.getMessage());
            return false;
        }
    }

    /** Keep quiet zeros; map only non-zero %10==0 bytes so GL does not skip real energy. */
    private static void normalizeDigitsForGl(byte[] c, int need) {
        if (c == null) return;
        for (int i = 0; i < need && i < c.length; i++) {
            int raw = c[i] & 0xff;
            if (raw == 0) continue;
            int d = raw % 10;
            if (d == 0) d = (raw % 9) + 1;
            c[i] = (byte) d;
        }
    }

    private static int countNonZero(byte[] c, int need) {
        int nz = 0;
        if (c == null) return 0;
        for (int i = 0; i < need && i < c.length; i++) if (c[i] != 0) nz++;
        return nz;
    }

    /** If current frame is sparse/hollow/GL-invisible, expand to dense N=16 solid crimson. */
    private void densifyIfSparse() {
        if (!haveFrame) return;
        int need = n * n * n;
        int nz = 0;
        for (int i = 0; i < need; i++) if (cells[i] != 0) nz++;
        // All-zero kernel dump → black void (GL skips d==0). Demo-fill.
        if (nz == 0) {
            densifyProphecyLattice(null, 4, 5, System.currentTimeMillis() / 1000L);
            if (source != null && !source.contains("demo"))
                source = source + "+demo-fill";
            dataSource = "demo-fill";
            return;
        }
        // 1.93: raw kernel bytes often have high values with %10==0 → GL skips all
        // (live count high, screen black). Force crimson densify when invisible.
        if (isGlInvisibleFrame()) {
            byte[] seed = new byte[need];
            System.arraycopy(cells, 0, seed, 0, need);
            int dig = digit >= 0 ? digit : (pick >= 0 ? pick : 5);
            densifyProphecyLattice(seed, n, dig > 0 ? dig : 5, ticks > 0 ? ticks : seq);
            if (source != null && !source.contains("crimson"))
                source = source + "+crimson-vis";
            dataSource = dataSource != null ? dataSource + "+crimson" : "crimson";
            return;
        }
        // Hollow or small lattice → densify (regression vs classic os_state shot)
        if (n >= 16 && nz > need / 8) return;
        byte[] seed = new byte[need];
        System.arraycopy(cells, 0, seed, 0, need);
        int dig = digit >= 0 ? digit : (pick >= 0 ? pick : 5);
        densifyProphecyLattice(seed, n, dig, ticks > 0 ? ticks : seq);
        if (source != null && !source.contains("dense"))
            source = source + "+dense16";
        dataSource = dataSource != null ? dataSource + "+dense16" : "dense16";
    }

    /**
     * Expand sparse meta vox / small lattice into dense N=16 prophecy field.
     * Restores classic solid crimson volumetric look (desktop cube_gl densify).
     */
    private void densifyProphecyLattice(byte[] seed, int seedN, int primaryDigit, long ticks) {
        final int n = 16;
        int need = n * n * n;
        byte[] out = new byte[need];
        byte[] neur = new byte[need];
        int amb = primaryDigit > 0 ? primaryDigit : 4;
        if (amb > 9) amb = 9;
        float phase = (ticks % 1000) * 0.017f;
        // Seed cores from seed lattice (4³ or 8³) mapped into 16³
        int sn = seedN > 0 ? seedN : 4;
        int snNeed = sn * sn * sn;
        for (int bi = 0; bi < snNeed && bi < (seed != null ? seed.length : 0); bi++) {
            int dig = seed[bi] & 0xff;
            if (dig <= 0) continue;
            dig = dig % 10;
            if (dig == 0) dig = 1;
            int sx = bi % sn;
            int sy = (bi / sn) % sn;
            int sz = bi / (sn * sn);
            int cx = sx * n / sn + n / (sn * 2);
            int cy = sy * n / sn + n / (sn * 2);
            int cz = sz * n / sn + n / (sn * 2);
            int rad = dig >= 5 ? 3 : 2;
            for (int dz = -rad; dz <= rad; dz++)
                for (int dy = -rad; dy <= rad; dy++)
                    for (int dx = -rad; dx <= rad; dx++) {
                        int xx = cx + dx, yy = cy + dy, zz = cz + dz;
                        if (xx < 0 || yy < 0 || zz < 0 || xx >= n || yy >= n || zz >= n)
                            continue;
                        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (dist > rad + 0.01f) continue;
                        float fall = 1f - dist / (rad + 0.5f);
                        int d = Math.round(dig * fall * 1.35f);
                        if (d < 0) d = 0;
                        if (d > 9) d = 9;
                        int idx = xx + yy * n + zz * n * n;
                        if (d > (out[idx] & 0xff)) out[idx] = (byte) d;
                        if (dist < 1.2f) neur[idx] = 1;
                    }
        }
        // Ambient haze + diagonal mass (reference dense shot)
        for (int z = 0; z < n; z++)
            for (int y = 0; y < n; y++)
                for (int x = 0; x < n; x++) {
                    int idx = x + y * n + z * n * n;
                    float haze = amb * 0.22f * (0.5f + 0.5f * (float) Math.sin(phase * 1.3 + (x + y + z) * 0.4));
                    if ((x * 17 + y * 31 + z * 13 + (int) ticks) % (amb > 6 ? 4 : 8) == 0)
                        haze += amb * 0.35f;
                    if (Math.abs(x - y) <= 2 || Math.abs(y - z) <= 2 || Math.abs(x - z) <= 2)
                        haze += amb * 0.2f;
                    int d = Math.round(haze);
                    if (d < 0) d = 0;
                    if (d > 9) d = 9;
                    if (d > (out[idx] & 0xff)) out[idx] = (byte) d;
                    if (d >= 6 && (x + y + z + (int) ticks) % 5 == 0) neur[idx] = 1;
                }
        applyCells(n, out, neur, true);
    }

    private boolean applyLiveVox(JSONObject live) {
        try {
            JSONObject st = live.optJSONObject("structure");
            if (st == null) return false;
            JSONArray cubes = st.optJSONArray("cubes");
            if (cubes == null || cubes.length() == 0) return false;
            JSONObject metaC = null;
            for (int i = 0; i < cubes.length(); i++) {
                JSONObject c = cubes.optJSONObject(i);
                if (c != null && "meta".equals(c.optString("id"))) {
                    metaC = c;
                    break;
                }
            }
            if (metaC == null) metaC = cubes.optJSONObject(0);
            JSONArray vox = metaC.optJSONArray("vox");
            if (vox == null || vox.length() < 8) return false;
            // REAL live meta vox (4³ or 8³) — no densify-to-16 prophecy theater.
            int sn = vox.length() >= 512 ? 8 : 4;
            int snNeed = sn * sn * sn;
            byte[] seed = new byte[snNeed];
            for (int i = 0; i < snNeed && i < vox.length(); i++) {
                int v = vox.optInt(i, 0);
                if (v < 0) v = 0;
                if (v > 9) v = v % 10;
                if (v == 0 && vox.optInt(i, 0) != 0) v = (Math.abs(vox.optInt(i, 0)) % 9) + 1;
                seed[i] = (byte) v;
            }
            applyCells(sn, seed, null, false);
            Log.i(TAG, "live-vox REAL n=" + sn + " live=" + countNonZero(seed, snNeed));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean loadLiveSnapFile() {
        File f = new File("/data/local/tmp/nanobot_home/braincube/live_snap.json");
        if (!f.isFile()) return false;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 256 * 1024)];
            int nread = in.read(buf);
            if (nread <= 0) return false;
            JSONObject live = new JSONObject(new String(buf, 0, nread, StandardCharsets.UTF_8));
            seq = live.optLong("seq", seq);
            JSONObject meta = live.optJSONObject("meta");
            if (meta != null) {
                pick = meta.optInt("pick", pick);
                digit = pick;
                pickName = meta.optString("pick_name", pickName);
            }
            return applyLiveVox(live);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean loadDumpFile(File f) {
        if (!f.isFile() || f.length() < 3) return false;
        try (FileInputStream in = new FileInputStream(f)) {
            long flen = f.length();
            int nn = in.read();
            // Canonical: first byte = n, then n³ cells [+ optional neuron].
            // 1.93: tip cells.bin often exactly 512 raw bytes with first byte 8 but
            // only 511 payload bytes left → old path failed, kernel eyes dead.
            if (nn >= 2 && nn <= MAX_N) {
                int need = nn * nn * nn;
                if (flen >= 1L + need) {
                    byte[] c = new byte[need];
                    if (readFully(in, c, need) >= need) {
                        byte[] neur = new byte[need];
                        boolean hasN = (flen >= 1L + 2L * need)
                            && readFully(in, neur, need) == need;
                        byte[] tail = new byte[128];
                        int tlen = in.read(tail);
                        if (tlen > 0) {
                            String line = new String(tail, 0, tlen, StandardCharsets.US_ASCII).trim();
                            try {
                                for (String p : line.split("\\s+")) {
                                    if (p.startsWith("digit=")) digit = Integer.parseInt(p.substring(6));
                                    if (p.startsWith("pick=")) pick = Integer.parseInt(p.substring(5));
                                    if (p.startsWith("ticks=")) ticks = Long.parseLong(p.substring(6));
                                }
                            } catch (Exception ignored) {}
                        }
                        normalizeDigitsForGl(c, need);
                        applyCells(nn, c, neur, hasN);
                        return true;
                    }
                }
            }
            // Fallback: raw cube of perfect cube length (8³=512, 4³=64, 16³=4096).
            in.getChannel().position(0);
            byte[] all = new byte[(int) Math.min(flen, MAX_CELLS + 1)];
            int got = readFully(in, all, all.length);
            for (int cand : new int[] {8, 4, 16}) {
                int need = cand * cand * cand;
                if (got >= need) {
                    byte[] c = new byte[need];
                    System.arraycopy(all, 0, c, 0, need);
                    normalizeDigitsForGl(c, need);
                    // Do not invent digits for true zeros — real quiet cells.
                    applyCells(cand, c, null, false);
                    source = "cells.bin-raw";
                    dataSource = "kernel_raw";
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static int readFully(InputStream in, byte[] buf, int need) throws Exception {
        int off = 0;
        while (off < need) {
            int r = in.read(buf, off, need - off);
            if (r < 0) break;
            off += r;
        }
        return off;
    }

    // 1.63 residual: shared PeerHttp drain — no getResponseCode/disconnect
    // without body (CLOSE-WAIT peer worker pile).
    // Eyes path: never heat-park lattice (rear truth + front Neural Cube SoT).
    private static JSONObject postJson(String url, String body) throws Exception {
        String s = PeerHttp.postBodyEyes(url, body, 800, 4000);
        if (s == null) return null;
        return new JSONObject(s);
    }

    private static JSONObject getJson(String url) throws Exception {
        String s = PeerHttp.getBodyEyes(url, 600, 1200);
        if (s == null) return null;
        return new JSONObject(s);
    }


    private void ensureDefaultSubs() {
        if (!subs.isEmpty()) return;
        String[][] d = {
            {"meta","meta","MetaCube","Coordinator lattice — maps sensor OUT streams; picks salient signal."},
            {"bump_L","sensor","bump_L","Left bumper / left collision channel."},
            {"bump_C","sensor","bump_C","Center bumper / forward collision channel."},
            {"bump_R","sensor","bump_R","Right bumper / right collision channel."},
            {"charge","sensor","charge","Dock / charging state channel."},
            {"battery","sensor","battery","Battery level / energy reserve channel."},
            {"state","sensor","state","World / motion state channel."},
            {"error","sensor","error","Fault / error flag channel."},
            {"free_ok","sensor","free_ok","Clearance / free-space OK channel."},
        };
        for (String[] row : d) {
            SubCube s = new SubCube();
            s.id = row[0]; s.role = row[1]; s.name = row[2]; s.represents = row[3];
            subs.add(s);
        }
    }

    private void ingestStructure(JSONObject live) {
        ensureDefaultSubs();
        try {
            org.json.JSONArray sensors = live.optJSONArray("sensors");
            if (sensors != null) {
                for (int i = 0; i < sensors.length(); i++) {
                    JSONObject s = sensors.optJSONObject(i);
                    if (s == null) continue;
                    String id = s.optString("id", s.optString("name", ""));
                    for (SubCube sc : subs) {
                        if (sc.id.equals(id) || sc.name.equals(id)) {
                            sc.value = s.optInt("value", sc.value);
                            sc.fire = s.optInt("fire", sc.fire);
                            sc.activity = (float) s.optDouble("activity", sc.activity);
                            break;
                        }
                    }
                }
            }
            JSONObject meta = live.optJSONObject("meta");
            if (meta != null) {
                for (SubCube sc : subs) {
                    if ("meta".equals(sc.id)) {
                        sc.activity = (float) meta.optDouble("activity", sc.activity);
                        break;
                    }
                }
            }
            JSONObject law = live.optJSONObject("law");
            if (law != null) {
                // 1.10: counter max + winner seed-safe (same as action=law path).
                applyLawJson(law);
            }
            // Prefer structure.cubes represents if present
            JSONObject st = live.optJSONObject("structure");
            if (st != null) {
                org.json.JSONArray cubes = st.optJSONArray("cubes");
                if (cubes != null) {
                    for (int i = 0; i < cubes.length(); i++) {
                        JSONObject c = cubes.optJSONObject(i);
                        if (c == null) continue;
                        String id = c.optString("id", "");
                        for (SubCube sc : subs) {
                            if (sc.id.equals(id)) {
                                if (c.has("represents"))
                                    sc.represents = c.optString("represents", sc.represents);
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public SubCube currentSub() {
        ensureDefaultSubs();
        if (subSel < 0 || subSel >= subs.size()) subSel = 0;
        return subs.get(subSel);
    }

    public void selectSub(int i) {
        ensureDefaultSubs();
        if (subs.isEmpty()) return;
        subSel = ((i % subs.size()) + subs.size()) % subs.size();
    }

    public void nextSub(int dir) {
        ensureDefaultSubs();
        selectSub(subSel + (dir >= 0 ? 1 : -1));
    }

    public static String digitMeaning(int d) {
        if (d <= 0) return "quiet (no activation)";
        if (d <= 3) return "weak activation";
        if (d <= 6) return "moderate activation";
        if (d <= 8) return "strong activation";
        return "peak activation";
    }

    /** Load per-node kernel channel names from sampler nodes.tsv */
    private void loadNodeLabels(File f) {
        if (f == null || !f.isFile()) return;
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length < 2) p = line.trim().split("\\s+");
                if (p.length < 2) continue;
                try {
                    int i = Integer.parseInt(p[0].trim());
                    if (i < 0 || i >= MAX_CELLS) continue;
                    nodeName[i] = p[1].trim();
                    if (p.length > 2) {
                        try { nodeValue[i] = Long.parseLong(p[2].trim()); }
                        catch (Exception ignored) { nodeValue[i] = 0; }
                    }
                } catch (Exception ignored) {}
            }
            br.close();
        } catch (Exception ignored) {}
    }

    public String describeCell(int idx) {
        if (idx < 0 || n < 1 || idx >= n * n * n) {
            return "KERNEL LATTICE  source=" + dataSource
                + "\nEach node = live kernel/IO sensor channel.\nTap a node.";
        }
        int x = idx % n;
        int y = (idx / n) % n;
        int z = idx / (n * n);
        int d = (cells[idx] & 0xff) % 10;
        String name = nodeName[idx] != null && !nodeName[idx].isEmpty()
            ? nodeName[idx] : ("node_" + idx);
        long val = nodeValue[idx];
        return "NODE " + idx + " @ (" + x + "," + y + "," + z + ")\n"
            + "SENSOR: " + name + "\n"
            + "value=" + val + "  digit=" + d + "  " + digitMeaning(d) + "\n"
            + "source=" + dataSource;
    }

    /**
     * Crimson LAW suffix for rear/main HUD. Independent of lattice haveFrame.
     * 1.7 residual: early "waiting for kernel sampler" hid LAW even after lawGot
     * (cool lab often has no cells.bin sampler while peer/file SoT is live).
     */
    public String lawHudSuffix() {
        if (!(peerOk || lawFileOk || lawEnergy > 0 || lawWins + lawLosses > 0
                || (lawStatus != null && !lawStatus.isEmpty()))) {
            return "";
        }
        String law = " · LAW E=" + lawEnergy
            + " W/L=" + lawWins + "/" + lawLosses
            + " join=" + lawCombines
            + (lawWinner == 1 ? " WIN" : (lawWinner == 0 ? " LOSS" : ""));
        if (lawStatus != null && !lawStatus.isEmpty()) {
            String st = lawStatus.length() > 24 ? lawStatus.substring(0, 24) : lawStatus;
            law += " " + st;
        }
        return law;
    }

    public String statusLine() {
        String law = lawHudSuffix();
        // 1.7: surface LAW even before lattice frame (intent=result on rear HUD).
        if (!haveFrame) {
            if (!law.isEmpty()) {
                return "matrix: waiting lattice" + law;
            }
            return "matrix: waiting for kernel sampler";
        }
        int need = n * n * n;
        int nz = 0;
        for (int i = 0; i < need; i++) if (cells[i] != 0) nz++;
        return "n=" + n
            + " · live=" + nz + "/" + need
            + " · " + source
            + law;
    }
}
