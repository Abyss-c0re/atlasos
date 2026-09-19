package com.titanus2.cubecontact;

/**
 * Lattice data plane for Neural Cube.
 * <p>
 * User-facing defaults + custom. API: {@link CubePlanePrefs} /
 * {@code com.titanus2.cubecontact.SET_MATRIX_SOURCE}.
 */
public enum MatrixSource {
    /** Prefer kernel sampler, then peer, then file, then demo. */
    AUTO("Auto", "Kernel, then peer, then file"),
    /** /data/local/tmp/cubebrain_viz kernel lattice only. */
    KERNEL("Kernel", "Live device sensors"),
    /** braincube peer :8787 live/export. */
    PEER("Nanobot", "On-device peer"),
    /** LAW counters from virtual.tsv (file SoT). */
    FILE_LAW("File", "Saved lattice"),
    /** Local cells.bin each tick. EEG if the file is fresh, else CPU. No network. */
    SOT("Local file", "No network"),
    /** Local crimson demo lattice (no peer). */
    DEMO("Demo", "Built-in sample"),
    /** User custom seed from app prefs / API payload. */
    CUSTOM("Custom", "Saved in the app");

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
            if (k.contains("sot") || k.contains("eeg") || k.contains("cpu")) return SOT;
            if (k.contains("file") || k.contains("law") || k.contains("virtual")) return FILE_LAW;
            if (k.contains("demo") || k.contains("seed")) return DEMO;
            if (k.contains("custom")) return CUSTOM;
            return AUTO;
        }
    }
}
