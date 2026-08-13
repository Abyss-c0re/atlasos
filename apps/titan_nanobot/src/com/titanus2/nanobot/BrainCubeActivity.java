package com.titanus2.nanobot;

import com.titanus2.nanobot.ui.ThemePalette;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Monitor & manage BrainCube (LHLAM) learning plugin on the peer.
 * Day/night via ThemePalette — follows system theme.
 */
public class BrainCubeActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private PeerClient peer;
    private TextView status;
    private TextView stats;
    private Switch enSw;
    private Switch adaptSw;
    private Switch dioSw;
    private Switch drySw;
    private int C_BG, C_PANEL, C_FG, C_MUT, C_ACCENT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemePalette tp = ThemePalette.of(this);
        C_BG = tp.bg;
        C_PANEL = tp.panel;
        C_FG = tp.fg;
        C_MUT = tp.mut;
        C_ACCENT = tp.accent;
        peer = new PeerClient(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(14), dp(12), dp(14), dp(20));

        TextView title = new TextView(this);
        title.setText("Crimson Cube · Commander");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(0xFFFF3344); /* crimson */
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("ALL CUBES: 8 algocubes + Meta + Crimson LAW.\n"
            + "Endless I/O race — impulse must exit O before plug cube claims it.\n"
            + "Energy MUST flow (NexusCore). Meta stays small (n=8).\n"
            + "Commander plane: pad · HID · subdisplay · a11y · dt2w.");
        note.setTextColor(C_MUT);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setPadding(0, dp(8), 0, dp(12));
        root.addView(note);

        enSw = sw("Enable brain plugin");
        adaptSw = sw("Auto-adapt (sample plane)");
        dioSw = sw("Direct I/O (titan2 plane roots)");
        drySw = sw("Dry-run (no plane writes)");
        drySw.setChecked(true);
        root.addView(enSw);
        root.addView(adaptSw);
        root.addView(dioSw);
        root.addView(drySw);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(pill("Save", this::save));
        row.addView(pill("Refresh", this::refresh));
        row.addView(pill("Tick", () -> post("decide")));
        row.addView(pill("Back", this::finish));
        root.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(8), 0, 0);
        row2.addView(pill("Teach ✓", () -> teach(1, true)));
        row2.addView(pill("Teach ✗", () -> teach(0, false)));
        row2.addView(pill("Self-test", () -> post("selftest")));
        row2.addView(pill("Reset", () -> post("reset")));
        root.addView(row2);

        TextView chainTitle = new TextView(this);
        chainTitle.setText("CubeChain demo (direction)");
        chainTitle.setTextColor(C_ACCENT);
        chainTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        chainTitle.setPadding(0, dp(14), 0, dp(4));
        root.addView(chainTitle);

        TextView chainNote = new TextView(this);
        chainNote.setText("FIRST CUBE LAW: every cube has IN+OUT. "
            + "I and O race — energy flows only when I→O wins. "
            + "LAW button = scoreboard. Live = all cubes lattice.");
        chainNote.setTextColor(0xFFFF8899);
        chainNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        root.addView(chainNote);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setPadding(0, dp(6), 0, 0);
        row3.addView(pill("LAW", this::pullLaw));
        row3.addView(pill("Live all", this::chainTick));
        row3.addView(pill("Want 1", () -> chainTeach(1)));
        row3.addView(pill("Want 2", () -> chainTeach(2)));
        row3.addView(pill("↺", this::chainReset));
        root.addView(row3);

        status = new TextView(this);
        status.setTextColor(C_MUT);
        status.setPadding(0, dp(12), 0, dp(6));
        root.addView(status);

        ScrollView sc = new ScrollView(this);
        stats = new TextView(this);
        stats.setTextIsSelectable(true);
        stats.setTypeface(android.graphics.Typeface.MONOSPACE);
        stats.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        stats.setTextColor(C_FG);
        sc.addView(stats);
        root.addView(sc, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refresh();
    }

    private void refresh() {
        new Thread(() -> {
            try {
                /* Prefer live (all cubes + law) over bare status */
                JSONObject j;
                try { j = peer.braincubeLive(); }
                catch (Exception e) { j = peer.braincubeStatus(); }
                JSONObject fj = j;
                h.post(() -> apply(fj));
            } catch (Exception e) {
                h.post(() -> status.setText("error: " + e.getMessage()));
            }
        }).start();
    }

    private void pullLaw() {
        new Thread(() -> {
            try {
                JSONObject j = peer.braincubeLaw();
                h.post(() -> {
                    apply(j);
                    Toast.makeText(this, "First Cube LAW", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                h.post(() -> status.setText("law: " + e.getMessage()));
            }
        }).start();
    }

    private void apply(JSONObject j) {
        if (j == null) {
            status.setText("no response");
            return;
        }
        boolean avail = j.optBoolean("available", false) || j.optBoolean("live", false);
        if (j.has("enabled")) enSw.setChecked(j.optBoolean("enabled", false));
        if (j.has("auto_adapt")) adaptSw.setChecked(j.optBoolean("auto_adapt", false));
        if (j.has("direct_io")) dioSw.setChecked(j.optBoolean("direct_io", false));
        if (j.has("dry_run")) drySw.setChecked(j.optBoolean("dry_run", true));
        String chainLine = "";
        JSONObject meta = j.optJSONObject("meta");
        if (meta != null) {
            chainLine = "  meta→" + meta.optString("pick_name", "?")
                + " agree=" + meta.optInt("agree", 0)
                + " conflict=" + meta.optInt("conflict", 0);
        }
        JSONObject ch = j.optJSONObject("cubechain");
        if (ch != null) {
            chainLine = "  chain pick=" + ch.optInt("pick", -1)
                + " agree=" + ch.optInt("agree", 0)
                + " conflict=" + ch.optInt("conflict", 0);
        }
        if (j.has("pick_name")) {
            chainLine = "  chain→" + j.optString("pick_name", "?")
                + " agree=" + j.optInt("agree", 0)
                + " conflict=" + j.optInt("conflict", 0);
        }
        String lawLine = "";
        JSONObject law = j.optJSONObject("law");
        if (law != null) {
            lawLine = "\nLAW E=" + law.optLong("energy", 0)
                + " W/L=" + law.optLong("wins", 0) + "/" + law.optLong("losses", 0)
                + " join=" + law.optLong("combines", 0)
                + " path=" + law.optInt("path_len", -1)
                + (law.optInt("winner", -1) == 1 ? " WIN" :
                    (law.optInt("winner", -1) == 0 ? " LOSS" : ""))
                + "\n" + law.optString("status", "");
        }
        status.setText(
            (avail || j.optBoolean("ok", false) ? "ALL CUBES LIVE" : "NOT LINKED")
                + "  seq=" + j.optLong("seq", 0)
                + chainLine
                + lawLine);
        try {
            stats.setText(j.toString(2));
        } catch (Exception e) {
            stats.setText(String.valueOf(j));
        }
    }

    private void chainTick() {
        new Thread(() -> {
            try {
                JSONObject j = peer.cubechainTick(null);
                h.post(() -> apply(j));
            } catch (Exception e) {
                h.post(() -> status.setText("chain: " + e.getMessage()));
            }
        }).start();
    }

    private void chainTeach(int wantLane) {
        new Thread(() -> {
            try {
                JSONObject j = peer.cubechainTick(wantLane);
                h.post(() -> {
                    Toast.makeText(this, "chain teach want=" + wantLane,
                        Toast.LENGTH_SHORT).show();
                    apply(j);
                });
            } catch (Exception e) {
                h.post(() -> Toast.makeText(this, e.getMessage(),
                    Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void chainReset() {
        new Thread(() -> {
            try {
                JSONObject j = peer.cubechainReset();
                h.post(() -> {
                    Toast.makeText(this, "chain reset", Toast.LENGTH_SHORT).show();
                    apply(j);
                });
            } catch (Exception e) {
                h.post(() -> status.setText("chain: " + e.getMessage()));
            }
        }).start();
    }

    private void save() {
        new Thread(() -> {
            try {
                peer.braincubeAction(enSw.isChecked() ? "enable" : "disable", null);
                peer.braincubeAction("auto_adapt", adaptSw.isChecked() ? "1" : "0");
                peer.braincubeAction("direct_io", dioSw.isChecked() ? "1" : "0");
                peer.braincubeAction("dry_run", drySw.isChecked() ? "1" : "0");
                JSONObject j = peer.braincubeStatus();
                h.post(() -> {
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                    apply(j);
                });
            } catch (Exception e) {
                h.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void post(String action) {
        new Thread(() -> {
            try {
                JSONObject j = peer.braincubeAction(action, null);
                h.post(() -> apply(j));
            } catch (Exception e) {
                h.post(() -> status.setText("error: " + e.getMessage()));
            }
        }).start();
    }

    private void teach(int teacher, boolean ok) {
        new Thread(() -> {
            try {
                JSONObject j = peer.braincubeTeach(teacher, ok);
                h.post(() -> {
                    Toast.makeText(this, ok ? "Taught OK" : "Taught fail", Toast.LENGTH_SHORT).show();
                    apply(j);
                });
            } catch (Exception e) {
                h.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private Switch sw(String label) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(C_FG);
        s.setPadding(0, dp(6), 0, dp(6));
        return s;
    }

    private Button pill(String t, Runnable r) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(C_FG);
        b.setBackgroundColor(C_PANEL);
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
