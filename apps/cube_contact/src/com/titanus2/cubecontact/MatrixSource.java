package com.titanus2.cubecontact;

/**
 * Lattice data plane for Neural Cube.
 * <p>
 * User-facing defaults + custom. API: {@link CubePlanePrefs} /
 * {@code com.titanus2.cubecontact.SET_MATRIX_SOURCE}.
 */
public enum MatrixSource {
    /** Prefer kernel sampler, then peer, then file, then demo. */
    AUTO("Auto", "Kernel → peer → file → demo"),
    /** /data/local/tmp/cubebrain_viz kernel lattice only. */
    KERNEL("Kernel cube", "Live kernel sensors (cells.bin)"),
    /** braincube peer :8787 live/export. */
    PEER("Peer cube", "Nanobot / braincube energy"),
    /** LAW counters from virtual.tsv (file SoT). */
    FILE_LAW("File LAW", "virtual.tsv scoreboard + seed lattice"),
    /** Local crimson demo lattice (no peer). */
    DEMO("Demo lattice", "Built-in dense N=16 prophecy"),
    /** User custom seed from app prefs / API payload. */
    CUSTOM("Custom matrix", "User-defined seed cells");

    public final String label;
    public final String hint;

    MatrixSource(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    public static MatrixSource fromKey(String key) {
        if (key == null || key.isEmpty()) return AUTO;
        try {
            return valueOf(key.trim().toUpperCase(java.util.Locale.US));
        } catch (Exception e) {
            String k = key.trim().toLowerCase(java.util.Locale.US);
            if (k.contains("kernel")) return KERNEL;
            if (k.contains("peer") || k.contains("brain") || k.contains("nanobot")) return PEER;
            if (k.contains("file") || k.contains("law") || k.contains("virtual")) return FILE_LAW;
            if (k.contains("demo") || k.contains("seed")) return DEMO;
            if (k.contains("custom")) return CUSTOM;
            return AUTO;
        }
    }
}
