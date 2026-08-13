package com.titanus2.cubecontact;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Select which kernel/virtual sensors feed the OpenGL lattice.
 * Virtual sensors can be added/edited (nanobot-managed plane).
 *
 * 1.38 residual: after 1.37 edge multi-pull, Sensors open still quiet-promoted
 * async while rebuild only sampled 900/2200/3500ms — multi-pull success after
 * ~1.8s+ left the list seed until re-open. Manual "Sync nanobot" still used a
 * single {@code pullFromNanobot} → TCP-open HTTP-not-ready toasted fail and
 * dual virtual.tsv stayed seed (no EDGE multi-pull / kick). Quiet rebuild belt
 * covers multi-pull + first edge; Sync uses pullPeerWhileSeedSync while seed.
 *
 * 1.39 residual: after 1.38 multi-pull + kick, Sync still toasted
 * "Virtual sensors updated" and rebuilt once even when multi-pull left seed
 * (HTTP-not-ready exhausted). Promote wave (3s/12s/40s) could land later while
 * the Sensors list stayed seed until Refresh/re-open. Honest toast when still
 * seed + shared rebuild belt covering early wave slots; open quiet belt same.
 *
 * 1.40 residual: 1.39 belt treated RETRY delays as absolute (12s/40s) and ended
 * at 45s — wave sleeps are cumulative (3+12+40 → ~15s/~55s) plus TAIL 90/180
 * (~145s/~325s). List stayed seed after 45s until Refresh while wave still
 * promoted. Cumulative wave-cover belt + one promote toast when energy lands;
 * summary mono fact LAW seed|live.
 *
 * 1.41 residual: 1.40 wave-cover kept posting rebuilds through 340s after
 * energy already live (open while promoted, or promote mid-belt) — thrash
 * rebuilt the full checkbox list and wiped unsaved toggles mid-edit. Also
 * stacked a second full belt on Sync without canceling the open belt.
 * Stop remaining belt on promote; short cover when already live; cancel prior
 * belt before arm; preserve unsaved checkbox state across rebuild.
 *
 * 1.42 residual: 1.41 still only sampled promote on sparse cumulative ticks
 * (56s → 100s → 150s → 200s → 340s). Wave promote between ticks left Sensors
 * list + "LAW seed" fact for up to ~140s after dual virtual.tsv was already
 * live (intent≠result). Dense promote-watch (2.5s) while seed detects land
 * without re-arming 340s thrash rebuilds; early multi-pull cover kept.
 *
 * 1.43 residual: 1.42 hard-stopped promote-watch at 340s (wave+tail horizon)
 * while seed rearm still loops (45s→20m, forever while seed). Peer rising
 * after horizon left Sensors "LAW seed" + stale virt until Refresh/re-open.
 * Also no onResume re-arm after stop/background past cancel. Open-forever
 * promote-watch while seed + onResume ensure; early multi-pull cover kept.
 *
 * 1.44 residual: 1.43 open-forever watch only *detected* dual virtual.tsv
 * energy — never re-kicked promote while Sensors stayed open. If LawSeedWake
 * failed to start / died (LMK mid-seed) or onResume only re-armed detect,
 * peer up left "LAW seed" until Sync/Refresh. Periodic open-pull + onResume
 * promote re-kick while seed (detect kept; no list thrash between lands).
 *
 * 1.45 residual: Sensors open-pull fixed; front Neural Cube lattice still
 * onResume-only — see CubeContactActivity FRONT_SEED_PULL_EVERY statusTick.
 *
 * 1.46 residual: front + Sensors open-pull fixed; rear lattice still onCreate
 * one-shot — see RearCubeActivity REAR_SEED_PULL_EVERY lawPoll.
 *
 * 1.47 residual: front + Sensors + rear open-pull fixed; SubdisplayCubeService
 * still onStart one-shot — see SUBDISP_SEED_PULL_EVERY seedPoll.
 *
 * 1.51 residual: 1.50 wake open-dense multi-pulls every 2.5s while peer open+seed,
 * but Sensors still open-pulled only every PROMOTE_WATCH_PULL_EVERY=4 detect ticks
 * (~10s). Wake dead / LMK + human on Sensors left dual virtual.tsv seed ≤10s after
 * peer HTTP-ready. Open-pull every detect tick (2.5s) — same SoT as wake PEER_EDGE.
 *
 * 1.55 residual: 1.54 Settings dim stamp left live wallpaper dim 0.92 — onResume
 * re-applies cmd set-dim. Dialog seal after show (pre-show window flag residual).
 *
 * 1.54 residual: 1.53 sealed front chat soft-IME only — Sensors EditText dialogs
 * still created AlertDialog windows with default soft input (LatinIME fillxfill
 * gray block). sealHwDialog + activity ALWAYS_HIDDEN + opaque window.
 */
public class SensorsActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<CheckBox> boxes = new ArrayList<>();
    private final List<SensorPrefs.Entry> entries = new ArrayList<>();
    private LinearLayout list;
    private TextView summary;
    private String filterGroup = "all";
    /** After seed Sync/open: toast once when promote wave lands dual virtual.tsv. */
    private boolean awaitWavePromote;
    /** Named so cancelBelt() can drop pending wave-cover posts (1.41). */
    private final Runnable beltTick = this::rebuildFromWaveBelt;
    /**
     * 1.42–1.44: dense promote detect while seed (not sparse 56s/100s/… rebuilds).
     * Only rebuilds when energy lands — no mid-edit thrash between ticks.
     * 1.43: no hard horizon — keep while open+seed (rearm past wave+tail).
     * 1.44: periodic promote re-kick while open+seed (detect-only residual).
     */
    private final Runnable promoteWatch = this::tickPromoteWatch;
    /** Match LawSeedWakeService peer edge poll — human-scale intent=result. */
    private static final long PROMOTE_WATCH_MS = 2_500L;
    /**
     * Historical wave+tail cover (~325s). 1.42 used as hard stop; 1.43 keeps
     * the name for assert/docs but open Sensors watches past rearm forever.
     */
    private static final long PROMOTE_WATCH_HORIZON_MS = 340_000L;
    /**
     * 1.44: re-kick promote every N detect ticks while open+seed.
     * 1.44 used 4 (~10s) to avoid EDGE multi-pull thrash every 2.5s.
     * 1.51: wake already multi-pulls every PEER_EDGE (2.5s) while open+seed —
     * UI open-pull matches that SoT (PULL_EVERY=1) so wake-dead residual is not
     * a 10s mid-gap class. promotePeerWhileSeed serializes concurrent edges.
     */
    private static final int PROMOTE_WATCH_PULL_EVERY = 1;
    /** Ticks since last open-pull re-kick (reset on arm / land / pull). */
    private int promoteWatchPullTicks;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 1.54: HW keyboard only + opaque (1.53 sealed front chat only residual).
        getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        try {
            getWindow().setFormat(android.graphics.PixelFormat.OPAQUE);
            getWindow().setBackgroundDrawableResource(android.R.color.black);
        } catch (Exception ignored) {}
        // 1.13: Sensors refresh path must share Application Context with rear LAW.
        try { StateMatrix.bindAppContext(this); } catch (Exception ignored) {}
        SensorPrefs.ensureDefaultVirtual(this);
        // 1.15: open Sensors without "Sync nanobot" left virt_law_* at seed zeros
        // while rear GL already promoted peer. Quiet boot-pull + rebuild when done.
        int bg = Color.BLACK;
        int fg = Color.rgb(230, 210, 210);
        int mut = Color.rgb(120, 90, 90);
        int accent = Color.rgb(200, 40, 50);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(12), dp(10), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("Lattice sensors");
        title.setTextColor(fg);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("Each cube node maps to a selected channel.\n"
            + "Kernel = /proc + sysfs. Virtual = nanobot/app managed.");
        help.setTextColor(mut);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        root.addView(help);

        summary = new TextView(this);
        summary.setTextColor(accent);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        summary.setPadding(0, dp(6), 0, dp(4));
        root.addView(summary);

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        for (String g : new String[]{"all", "power", "cpu", "irq", "mem", "net", "led", "plane", "input", "virtual"}) {
            Button b2 = pill(g, () -> { filterGroup = g; rebuild(); });
            filters.addView(b2);
        }
        ScrollView fsc = new ScrollView(this);
        fsc.setHorizontalScrollBarEnabled(true);
        // horizontal strip
        LinearLayout fwrap = new LinearLayout(this);
        fwrap.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < filters.getChildCount(); ) {
            // already added below differently
            break;
        }
        // redo filters as row
        LinearLayout frow = new LinearLayout(this);
        frow.setOrientation(LinearLayout.HORIZONTAL);
        frow.setPadding(0, dp(4), 0, dp(4));
        String[] groups = {"all", "power", "cpu", "irq", "mem", "net", "virtual", "plane", "input", "led"};
        for (String g : groups) {
            final String gg = g;
            Button fb = new Button(this);
            fb.setText(g);
            fb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            fb.setPadding(dp(6), dp(2), dp(6), dp(2));
            fb.setOnClickListener(v -> { filterGroup = gg; rebuild(); });
            frow.addView(fb);
        }
        ScrollView fscroll = new ScrollView(this);
        // Use horizontal LinearLayout in horizontal ScrollView via nested
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.addView(frow);
        root.addView(hs);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(pill("Save", this::save));
        actions.addView(pill("All", this::selectAll));
        actions.addView(pill("None", this::selectNone));
        actions.addView(pill("Clear file", () -> {
            SensorPrefs.clearSelection(this);
            Toast.makeText(this, "Using all sensors", Toast.LENGTH_SHORT).show();
            rebuild();
        }));
        root.addView(actions);

        LinearLayout virtRow = new LinearLayout(this);
        virtRow.setOrientation(LinearLayout.HORIZONTAL);
        virtRow.addView(pill("+ Virtual", this::addVirtualDialog));
        virtRow.addView(pill("Sync nanobot", this::syncNanobotVirtual));
        virtRow.addView(pill("Refresh", this::rebuild));
        root.addView(virtRow);

        ScrollView sc = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sc.addView(list);
        root.addView(sc, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button back = new Button(this);
        back.setText("Back");
        back.setOnClickListener(v -> finish());
        root.addView(back);

        setContentView(root);
        rebuild();
        // 1.15: auto promote peer LAW/file SoT (no toast spam; manual Sync still available).
        quietBootPullThenRebuild();
    }

    /**
     * 1.15 residual: Sensors open must not wait on manual Sync for virt_law_*.
     * 1.31 residual: kickBootPull only was no-op mid LawPromoteService sleep
     * (sWaveRunning) while peer already open → dual virtual.tsv seed until
     * next peer poll. promotePeerWhileSeed direct-pulls then kick/rearm.
     * 1.37 residual: promote multi-pulls (EDGE SoT); 1.38 residual: rebuild
     * belt must cover multi-pull window (~1.8s + kick) so list shows energy
     * without re-open or manual Sync.
     * 1.39 residual: belt also covers promote-wave first slots after multi-pull
     * fail + kick — list no longer stuck seed past 8s.
     * 1.40 residual: belt uses cumulative wave+tail windows (not absolute 12/40
     * ending 45s) + promote toast when energy lands.
     * 1.41 residual: stop remaining posts on promote; short cover when live;
     * cancel prior belt before arm (Sync stack residual).
     * 1.42 residual: sparse cumulative ticks left list seed up to ~140s after
     * dual virtual.tsv live — dense promote-watch while seed.
     * 1.43 residual: 340s horizon left seed after rearm; onResume re-arms.
     * 1.44 residual: open watch detect-only; periodic open-pull + onResume kick.
     */
    private void quietBootPullThenRebuild() {
        VirtualSensorSync.promotePeerWhileSeed(this);
        // Async EDGE multi-pull (~4×450ms + HTTP) then kick; peer may be dead.
        scheduleSensorsRebuildBelt();
    }

    /** Drop pending wave-cover + promote-watch (promote land / re-arm / destroy). */
    private void cancelBelt() {
        try {
            h.removeCallbacks(beltTick);
            h.removeCallbacks(promoteWatch);
        } catch (Exception ignored) {}
    }

    /**
     * Rebuild list through EDGE multi-pull + promote-wave detect.
     * Shared by open quiet path and Sync while seed after kick (1.39/1.40).
     *
     * Wave SoT ({@code RETRY_DELAYS_MS} + {@code TAIL_DELAYS_MS}) sleeps between
     * pulls — cumulative from wave start after entry multi-pull:
     * 3s → 15s → 55s → 145s → 325s. 1.39 absolute 12s/40s/45s missed 55s+.
     *
     * 1.41: cancel prior belt first; when already live only short multi-pull
     * cover (no 340s thrash).
     * 1.42: seed path keeps early multi-pull rebuild cover, then dense
     * promote-watch (2.5s) — not sparse 56s/100s/… list thrash that still
     * missed promote between ticks (intent≠result "LAW seed").
     * 1.43: watch stays while open+seed (no 340s hard stop; rearm past horizon).
     * 1.44: watch also re-kicks promote on a cadence while open+seed.
     */
    private void scheduleSensorsRebuildBelt() {
        cancelBelt();
        boolean seed = true;
        try {
            seed = !VirtualSensorSync.isLawEnergyPromoted(this);
        } catch (Exception ignored) {}
        // Only toast on later promote when still seed (not every open while live).
        awaitWavePromote = seed;
        promoteWatchPullTicks = 0;
        if (seed) {
            // Early multi-pull window rebuilds (value land without re-open).
            long[] early = new long[] { 900L, 2200L, 3500L, 5500L, 8000L };
            for (long d : early) {
                h.postDelayed(beltTick, d);
            }
            // Dense promote detect + open-pull while Sensors open + seed (1.42–1.44).
            // PROMOTE_WATCH_HORIZON_MS is wave+tail docs only — not a hard stop.
            if (PROMOTE_WATCH_HORIZON_MS > 0L) {
                h.postDelayed(promoteWatch, PROMOTE_WATCH_MS);
            }
        } else {
            // Already live: brief cover for quiet multi-pull value refresh only.
            for (long d : new long[] { 900L, 2200L, 3500L }) {
                h.postDelayed(beltTick, d);
            }
        }
    }

    /**
     * 1.42–1.44: while seed, poll promote every 2.5s without rebuilding the list
     * each tick (1.41 checkbox preserve still needed on land/rebuild only).
     * 1.43: no wave-cover hard stop — seed rearm continues past 340s; open
     * Sensors must keep intent=result until promote or destroy.
     * 1.44: every PROMOTE_WATCH_PULL_EVERY ticks re-kick promotePeerWhileSeed
     * (detect-only left seed when wake shell dead / onResume detect-only).
     * 1.51: PULL_EVERY=1 → re-kick every 2.5s detect tick (wake PEER_EDGE SoT).
     * 1.67: delay = CubeStability.lawOpenPollMs (15s under thermal/screen-off;
     * cool still PROMOTE_WATCH_MS 2.5s). Dense EDGE under heat reheats SoC.
     */
    private void tickPromoteWatch() {
        if (isFinishing() || !awaitWavePromote) return;
        try {
            if (VirtualSensorSync.isLawEnergyPromoted(this)) {
                onPromoteLanded();
                return;
            }
        } catch (Exception ignored) {}
        // 1.44: open-pull cadence while seed (not every detect tick).
        promoteWatchPullTicks++;
        if (promoteWatchPullTicks >= PROMOTE_WATCH_PULL_EVERY) {
            promoteWatchPullTicks = 0;
            try {
                VirtualSensorSync.promotePeerWhileSeed(this);
            } catch (Exception ignored) {}
        }
        try {
            long d = PROMOTE_WATCH_MS;
            try {
                d = CubeStability.lawOpenPollMs(this);
            } catch (Exception ignored) {}
            // Keep PROMOTE_WATCH_MS pin for cool path assert (2.5s).
            if (d < PROMOTE_WATCH_MS) d = PROMOTE_WATCH_MS;
            h.postDelayed(promoteWatch, d);
        } catch (Exception ignored) {}
    }

    /** One promote land: toast + rebuild + drop remaining belt/watch. */
    private void onPromoteLanded() {
        if (!awaitWavePromote) return;
        awaitWavePromote = false;
        promoteWatchPullTicks = 0;
        cancelBelt();
        rebuild();
        try {
            Toast.makeText(this, "Virtual sensors updated from nanobot",
                Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    /** Belt tick: refresh list; one promote toast when dual virtual.tsv lands. */
    private void rebuildFromWaveBelt() {
        if (isFinishing()) return;
        if (awaitWavePromote) {
            try {
                if (VirtualSensorSync.isLawEnergyPromoted(this)) {
                    // 1.41–1.44: land path rebuilds once (no double rebuild).
                    onPromoteLanded();
                    return;
                }
            } catch (Exception ignored) {}
        }
        rebuild();
    }

    /**
     * 1.43 residual: after stop/background cancel or 1.42 horizon stop, return
     * to Sensors while still seed left list stuck until Refresh. Re-check
     * promote immediately; re-arm open-forever watch when still seed.
     * 1.44 residual: 1.43 only re-armed detect — no promote re-kick; peer up
     * while wake dead left seed until Sync. Resume also promotePeerWhileSeed.
     */
    @Override protected void onResume() {
        super.onResume();
        // 1.55: live wallpaper dim cmd (Settings-only left 0.92 residual).
        try { CubeSurfacePrefs.apply(this); } catch (Exception ignored) {}
        boolean seed = true;
        try {
            seed = !VirtualSensorSync.isLawEnergyPromoted(this);
        } catch (Exception ignored) {}
        if (!seed) {
            if (awaitWavePromote) {
                awaitWavePromote = false;
                promoteWatchPullTicks = 0;
                cancelBelt();
                rebuild();
            }
            return;
        }
        // Seed: re-kick promote (1.44) + ensure open-forever watch (1.43).
        awaitWavePromote = true;
        promoteWatchPullTicks = 0;
        try {
            VirtualSensorSync.promotePeerWhileSeed(this);
        } catch (Exception ignored) {}
        try {
            h.removeCallbacks(promoteWatch);
            h.post(promoteWatch);
        } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() {
        awaitWavePromote = false;
        try {
            cancelBelt();
            h.removeCallbacksAndMessages(null);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void rebuild() {
        // 1.41: belt rebuild must not wipe unsaved checkbox toggles mid-edit.
        Map<String, Boolean> toggles = new LinkedHashMap<>();
        for (CheckBox prev : boxes) {
            if (prev == null || prev.getTag() == null) continue;
            try {
                toggles.put(prev.getTag().toString(), prev.isChecked());
            } catch (Exception ignored) {}
        }
        entries.clear();
        entries.addAll(SensorPrefs.loadCatalog(this));
        boxes.clear();
        list.removeAllViews();
        int shown = 0, on = 0;
        String lastG = "";
        for (SensorPrefs.Entry e : entries) {
            if (!"all".equals(filterGroup) && !filterGroup.equals(e.group)) continue;
            if (!e.group.equals(lastG)) {
                lastG = e.group;
                TextView gh = new TextView(this);
                gh.setText("— " + e.group.toUpperCase() + " —");
                gh.setTextColor(Color.rgb(255, 90, 70));
                gh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                gh.setPadding(0, dp(10), 0, dp(4));
                list.addView(gh);
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            CheckBox cb = new CheckBox(this);
            if (toggles.containsKey(e.name)) {
                Boolean want = toggles.get(e.name);
                cb.setChecked(want != null && want);
            } else {
                cb.setChecked(e.enabled);
            }
            cb.setTag(e.name);
            boxes.add(cb);
            row.addView(cb);
            TextView tv = new TextView(this);
            tv.setTextColor(Color.rgb(220, 200, 200));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            String mark = e.virtual ? " [virt]" : "";
            tv.setText(e.name + mark + "  = " + e.value);
            tv.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
            if (e.virtual) {
                tv.setOnLongClickListener(v -> {
                    editVirtualDialog(e.name, e.value);
                    return true;
                });
            }
            row.addView(tv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            list.addView(row);
            shown++;
            if (e.enabled) on++;
        }
        Set<String> sel = SensorPrefs.loadSelectedSet();
        // Mono fact: seed vs live (intent=result while wave still running).
        String lawFact = " · LAW seed";
        try {
            if (VirtualSensorSync.isLawEnergyPromoted(this)) {
                lawFact = " · LAW live";
            }
        } catch (Exception ignored) {}
        summary.setText("Showing " + shown + " · selected file "
            + (sel.isEmpty() ? "ALL (no filter)" : sel.size() + " names")
            + " · group=" + filterGroup + lawFact);
    }

    private void save() {
        Set<String> names = new HashSet<>();
        for (CheckBox cb : boxes) {
            if (cb.isChecked() && cb.getTag() != null)
                names.add(cb.getTag().toString());
        }
        if (names.isEmpty()) {
            Toast.makeText(this, "Select at least one sensor", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            SensorPrefs.saveSelected(this, names);
            Toast.makeText(this, "Saved " + names.size() + " sensors → lattice", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        rebuild();
    }

    private void selectAll() {
        for (CheckBox cb : boxes) cb.setChecked(true);
    }

    private void selectNone() {
        for (CheckBox cb : boxes) cb.setChecked(false);
    }

    private void addVirtualDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        EditText name = new EditText(this);
        name.setHint("name (e.g. virt_my_metric)");
        name.setTextColor(Color.WHITE);
        name.setHintTextColor(Color.GRAY);
        sealHwEditText(name);
        EditText val = new EditText(this);
        val.setHint("value (integer)");
        val.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        val.setTextColor(Color.WHITE);
        val.setHintTextColor(Color.GRAY);
        sealHwEditText(val);
        box.addView(name);
        box.addView(val);
        AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle("Add virtual sensor")
            .setView(box)
            .setPositiveButton("Add", (d, w) -> {
                String n = name.getText() != null ? name.getText().toString().trim() : "";
                String vs = val.getText() != null ? val.getText().toString().trim() : "0";
                if (n.isEmpty()) return;
                if (!n.startsWith("virt_")) n = "virt_" + n;
                long v = 0;
                try { v = Long.parseLong(vs); } catch (Exception ignored) {}
                SensorPrefs.putVirtual(this, n, v);
                // auto-include in selection if a selection file exists
                Set<String> sel = SensorPrefs.loadSelectedSet();
                if (!sel.isEmpty()) {
                    sel.add(n);
                    SensorPrefs.saveSelected(this, sel);
                }
                Toast.makeText(this, "Virtual " + n + "=" + v, Toast.LENGTH_SHORT).show();
                rebuild();
            })
            .setNegativeButton("Cancel", null)
            .create();
        sealHwDialog(dlg);
        dlg.show();
        // 1.55: some GSI dialogs re-apply default soft-input after show.
        sealHwDialog(dlg);
    }

    private void editVirtualDialog(String name, long cur) {
        EditText val = new EditText(this);
        val.setText(Long.toString(cur));
        val.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        val.setTextColor(Color.BLACK);
        sealHwEditText(val);
        AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle(name)
            .setView(val)
            .setPositiveButton("Set", (d, w) -> {
                long v = 0;
                try { v = Long.parseLong(val.getText().toString().trim()); } catch (Exception ignored) {}
                SensorPrefs.putVirtual(this, name, v);
                rebuild();
            })
            .setNeutralButton("Delete", (d, w) -> {
                SensorPrefs.removeVirtual(this, name);
                rebuild();
            })
            .setNegativeButton("Cancel", null)
            .create();
        sealHwDialog(dlg);
        dlg.show();
        sealHwDialog(dlg);
    }

    /** 1.54: HW-only EditText — no soft LatinIME gray fillxfill. */
    private void sealHwEditText(EditText e) {
        if (e == null) return;
        e.setShowSoftInputOnFocus(false);
        e.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI
            | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        e.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) return;
            try {
                InputMethodManager imm = (InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                }
            } catch (Exception ignored) {}
        });
    }

    /**
     * 1.54: dialog window inherits soft-IME policy (activity flag alone missed).
     * 1.55: call again after show() — GSI AlertDialog can reset soft-input mode.
     */
    private void sealHwDialog(AlertDialog dlg) {
        if (dlg == null) return;
        try {
            Window w = dlg.getWindow();
            if (w != null) {
                w.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            }
        } catch (Exception ignored) {}
    }

    private void syncNanobotVirtual() {
        Toast.makeText(this, "Syncing nanobot…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                // 1.38 residual: while seed, single pullFromNanobot left dual
                // virtual.tsv seed on TCP-open HTTP-not-ready (edge/wave already
                // multi-pull). EDGE multi-pull + kick matches belt/rearm/wave SoT.
                // 1.39 residual: multi-pull fail still toasted "updated" + one
                // rebuild — wave promote later left Sensors list seed. Honest
                // toast + rebuild belt when still seed after kick.
                // 1.40 residual: 1.39 belt absolute 12/40/45 missed cumulative
                // ~55s/~145s/~325s wave+tail — scheduleSensorsRebuildBelt covers.
                // 1.41 residual: schedule cancels prior belt; promote stops rest.
                // 1.42 residual: seed fail path arms dense promote-watch (not
                // sparse 56s/100s gaps that left LAW seed fact after promote).
                // 1.43 residual: watch open-forever while seed (not 340s stop).
                // 1.44 residual: open-pull cadence + onResume promote re-kick.
                boolean seed = true;
                try {
                    seed = !VirtualSensorSync.isLawEnergyPromoted(this);
                } catch (Exception ignored) {}
                boolean promoted = !seed;
                if (seed) {
                    try {
                        promoted = VirtualSensorSync.pullPeerWhileSeedSync(this);
                    } catch (Exception ignored) {}
                    if (!promoted) {
                        try {
                            VirtualSensorSync.kickBootPull(this);
                        } catch (Exception ignored) {}
                    }
                } else {
                    VirtualSensorSync.pullFromNanobot(this);
                    try {
                        promoted = VirtualSensorSync.isLawEnergyPromoted(this);
                    } catch (Exception ignored) {}
                }
                final boolean ok = promoted;
                h.post(() -> {
                    if (ok) {
                        // 1.41–1.43: live Sync must not leave open seed belt/watch.
                        awaitWavePromote = false;
                        cancelBelt();
                        Toast.makeText(this, "Virtual sensors updated from nanobot",
                            Toast.LENGTH_SHORT).show();
                        rebuild();
                    } else {
                        // Intent=result: still seed → mono fact, not false success.
                        Toast.makeText(this, "Peer lag · wave armed",
                            Toast.LENGTH_SHORT).show();
                        rebuild();
                        scheduleSensorsRebuildBelt();
                    }
                });
            } catch (Exception e) {
                h.post(() -> Toast.makeText(this, "Nanobot: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "virt-sync").start();
    }

    private Button pill(String label, Runnable r) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
