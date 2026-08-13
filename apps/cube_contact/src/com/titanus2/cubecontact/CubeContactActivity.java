package com.titanus2.cubecontact;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Lattice UI for nanobot: live matrix + selection meaning + chat on current model.
 *
 * 1.32 residual: onResume raw pullFromNanobot once/60s left dual virtual.tsv
 * (fixed: promote while seed). 1.33: Application process start same promote SoT.
 * seed when human opened front lattice mid LawPromoteService wave (or within
 * 60s of a failed pull) while peer :8787 already had First Cube energy.
 * While seed → promotePeerWhileSeed (direct pull + kick/rearm); promoted →
 * keep 60s refresh throttle only.
 *
 * 1.45 residual: 1.32/1.44 Sensors open-pull while seed, but front lattice only
 * promoted on onResume. Human stays on Neural Cube with wake shell dead / LMK
 * mid-seed → peer up left dual virtual.tsv seed + status LAW E=0 until leave/
 * return or Sensors open. statusTick now open-pulls on Sensors cadence (~10s)
 * while seed (detect + promote); promoted keeps 800ms status only.
 *
 * 1.46 residual: front + Sensors open-pull fixed; rear lattice still onCreate
 * one-shot — see RearCubeActivity REAR_SEED_PULL_EVERY lawPoll.
 *
 * 1.47 residual: front + Sensors + rear open-pull fixed; SubdisplayCubeService
 * still onStart one-shot — see SUBDISP_SEED_PULL_EVERY seedPoll.
 *
 * 1.51 residual: 1.50 wake open-dense 2.5s, but front still open-pulled every
 * ~10s (12×800ms). Wake dead + human on Neural Cube left dual virtual.tsv seed
 * ≤10s after peer HTTP-ready. FRONT_SEED_PULL_EVERY=3 (~2.4s) matches wake SoT.
 *
 * 1.52/1.53: typing gray-block. Soft LatinIME was fillxfill over the lattice
 * (not a "use soft KB" product path). HW keyboard only: STATE_ALWAYS_HIDDEN +
 * setShowSoftInputOnFocus(false) + ADJUST_NOTHING. Opaque window + RGB888 mesh
 * so wallpaper dim never show-through. cube-ux dim 0.92→0.15 in hybrid pack.
 *
 * 1.54 residual: 1.53 sealed front chat only — Sensors dialogs / Privilege /
 * Rear still default soft-IME windows; rootless boot left old cube-ux 0.92.
 * 1.55 residual: Settings dim 0.15 while live get-dim-amount stayed 0.92.
 * See SensorsActivity / CubeSurfacePrefs / install tip stamp.
 */
public class CubeContactActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private NanobotBridge bridge;
    /**
     * Cube Experience face = real OpenGL ({@code cube_gl --levitate --mono}),
     * peer-first BrainCube lattice. Canvas Mesh is heresy — never the product face.
     */
    private CubeGLView gl;
    private TextView status;
    private TextView meaning;
    private TextView chatLog;
    private EditText input;
    private boolean statusRunning;
    private long lastVirtSync;
    /**
     * 1.45: re-kick promote every N status ticks while front open+seed.
     * 1.45 used 12×800ms ≈10s (Sensors 4×2.5s). 1.51: 3×800ms ≈2.4s matches
     * wake PEER_EDGE / Sensors dense open-pull (wake-dead residual).
     */
    private static final int FRONT_SEED_PULL_EVERY = 3;
    private int frontSeedPullTicks;
    /** True while Commander chat in flight — lattice cools (render opt). */
    private volatile boolean commanderChatBusy;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 1.53: HW keyboard only. Soft IME (LatinIME fillxfill) was the gray
        // full-screen block — never summon it. Opaque window so dim wallpaper
        // cannot punch through a translucent GL hole either.
        getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        try {
            getWindow().setFormat(android.graphics.PixelFormat.OPAQUE);
            getWindow().setBackgroundDrawableResource(android.R.color.black);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
        } catch (Exception ignored) {}

        if (!getSharedPreferences("cube_viz_palette", MODE_PRIVATE).contains("privilege_mode")) {
            CubePalette.setMode(this, RomIntegration.defaultMode(this));
        }
        bridge = new NanobotBridge(this);
        int bg = Color.BLACK;
        int fg = Color.rgb(230, 210, 210);
        int mut = Color.rgb(120, 90, 90);
        int accent = Color.rgb(200, 40, 50);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(12), dp(10), dp(12), dp(12));
        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        TextView title = new TextView(this);
        title.setText("Neural Cube");
        title.setTextColor(fg);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setFocusable(false);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText(CommanderChat.uiBanner() + "\n"
            + RomIntegration.roleLine(this)
            + "\nLattice · chat = Commander via CUBE (max compliance)");
        sub.setTextColor(mut);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setFocusable(false);
        root.addView(sub);

        gl = new CubeGLView(this);
        gl.setPlane(CubePlanePrefs.PLANE_FRONT);
        gl.setLevitate(true);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.85f);
        mp.topMargin = dp(6);
        mp.bottomMargin = dp(4);
        root.addView(gl, mp);

        TextView subNote = new TextView(this);
        subNote.setTextColor(Color.rgb(255, 90, 70));
        subNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subNote.setText("OpenGL Cube Experience · levitate · mono · tap = sensor");
        subNote.setFocusable(false);
        root.addView(subNote);

        meaning = new TextView(this);
        meaning.setTextColor(Color.rgb(255, 200, 180));
        meaning.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        meaning.setText("…");
        meaning.setFocusable(false);
        meaning.setPadding(dp(6), dp(6), dp(6), dp(6));
        meaning.setBackgroundColor(Color.argb(200, 18, 4, 6));
        meaning.setMinHeight(dp(88));
        root.addView(meaning);

        gl.setSelectionListener((idx, desc) -> h.post(this::refreshMeaning));

        status = new TextView(this);
        status.setTextColor(mut);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        status.setFocusable(false);
        root.addView(status);

        ScrollView sc = new ScrollView(this);
        sc.setFocusable(false);
        chatLog = new TextView(this);
        chatLog.setTextColor(fg);
        chatLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chatLog.setText("");
        chatLog.setFocusable(false);
        chatLog.setTextIsSelectable(true);
        sc.addView(chatLog);
        root.addView(sc, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setHint("Commander → Cube → Nanobot…");
        input.setHintTextColor(mut);
        input.setTextColor(fg);
        input.setBackgroundColor(Color.rgb(18, 6, 6));
        input.setSingleLine(true);
        // Cyberdeck HW keyboard — do not request soft IME on focus.
        input.setShowSoftInputOnFocus(false);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND
            | EditorInfo.IME_FLAG_NO_EXTRACT_UI
            | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        // 1.54: some GSIs still flash LatinIME on focus despite ALWAYS_HIDDEN —
        // hard-hide IMM when the chat field gains focus.
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) return;
            try {
                InputMethodManager imm = (InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                }
            } catch (Exception ignored) {}
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                sendChat();
                return true;
            }
            return false;
        });
        row.addView(input, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button send = new Button(this);
        send.setText("Send");
        send.setTextColor(accent);
        send.setOnClickListener(v -> sendChat());
        row.addView(send);
        root.addView(row);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.addView(pill("Settings", () -> {
            Intent i = new Intent(this, CubeSettingsActivity.class);
            i.putExtra(CubeSettingsActivity.EXTRA_PLANE, CubePlanePrefs.PLANE_FRONT);
            startActivity(i);
        }));
        tools.addView(pill("Sensors", () ->
            startActivity(new Intent(this, SensorsActivity.class))));
        tools.addView(pill("Access", () ->
            startActivity(new Intent(this, PrivilegeActivity.class))));
        tools.addView(pill("Nanobot", () -> {
            try {
                Intent i = getPackageManager()
                    .getLaunchIntentForPackage("com.titanus2.nanobot");
                if (i != null) startActivity(i);
            } catch (Exception ignored) {}
        }));
        root.addView(tools);

        setContentView(root);
        refreshMeaning();
    }

    @Override protected void onResume() {
        super.onResume();
        try { if (gl != null) gl.onResume(); } catch (Exception ignored) {}
        try { StateMatrix.bindAppContext(this); } catch (Exception ignored) {}
        SensorPrefs.ensureDefaultVirtual(this);
        // 1.55: open front re-asserts live wallpaper dim (old cube-ux 0.92 residual).
        try { CubeSurfacePrefs.apply(this); } catch (Exception ignored) {}
        // 1.32: while seed always promote (mid-wave-safe); promoted → 60s refresh.
        // 1.45: also reset front open-pull cadence (statusTick re-kicks while seed).
        boolean seed = true;
        try {
            seed = !VirtualSensorSync.isLawEnergyPromoted(this);
        } catch (Exception ignored) {}
        long now = System.currentTimeMillis();
        if (seed || now - lastVirtSync > 60_000L) {
            lastVirtSync = now;
            if (seed) {
                frontSeedPullTicks = 0;
                try {
                    VirtualSensorSync.promotePeerWhileSeed(this);
                } catch (Exception ignored) {}
            } else {
                frontSeedPullTicks = 0;
                new Thread(() -> {
                    try {
                        VirtualSensorSync.pullFromNanobot(CubeContactActivity.this);
                    } catch (Exception ignored) {}
                }, "virt-boot").start();
            }
        }
        if (!statusRunning) {
            statusRunning = true;
            h.post(statusTick);
        }
    }

    @Override protected void onPause() {
        statusRunning = false;
        frontSeedPullTicks = 0;
        h.removeCallbacks(statusTick);
        try { if (gl != null) gl.onPause(); } catch (Exception ignored) {}
        super.onPause();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Don't steal keys when typing in the chat box
        if (input != null && input.hasFocus()) {
            return super.onKeyDown(keyCode, event);
        }
        if (keyCode == KeyEvent.KEYCODE_LEFT_BRACKET
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            refreshMeaning();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_RIGHT_BRACKET
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_TAB) {
            refreshMeaning();
            return true;
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_8) {
            refreshMeaning();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            if (!statusRunning) return;
            // 1.45 residual: onResume-only promote left front lattice seed when
            // human stayed open with wake dead / peer rose later. 1.51: open-pull
            // every ~2.4s (wake PEER_EDGE SoT); not every 800ms status paint.
            try {
                if (!VirtualSensorSync.isLawEnergyPromoted(CubeContactActivity.this)) {
                    frontSeedPullTicks++;
                    if (frontSeedPullTicks >= FRONT_SEED_PULL_EVERY) {
                        frontSeedPullTicks = 0;
                        try {
                            VirtualSensorSync.promotePeerWhileSeed(
                                CubeContactActivity.this);
                        } catch (Exception ignored) {}
                    }
                } else {
                    frontSeedPullTicks = 0;
                }
            } catch (Exception ignored) {}
            // No network on UI thread for status — only local matrix fields
            String m = gl != null ? gl.matrix().model : "";
            if (m == null) m = "";
            status.setText((gl != null ? gl.statusLine() : "…")
                + " · " + RomIntegration.installLabel(CubeContactActivity.this)
                + (m.isEmpty() ? "" : " · " + m));
            // refresh meaning if sub-cube live values changed
            if (input == null || !input.hasFocus()) {
                // keep selection text fresh without fighting IME cursor
            }
            // CUBE STABILITY LAW: thermal / idle → longer ticks.
            boolean seed = true;
            try {
                seed = !VirtualSensorSync.isLawEnergyPromoted(CubeContactActivity.this);
            } catch (Exception ignored) {}
            h.postDelayed(this, CubeStability.statusTickMs(CubeContactActivity.this, seed));
        }
    };

    private void sendChat() {
        String line = input.getText() != null ? input.getText().toString().trim() : "";
        if (line.isEmpty()) return;
        input.setText("");
        input.requestFocus();
        // Cool lattice while Commander talks (render opt — free SoC for peer).
        setCommanderChatBusy(true);
        if (CommanderChat.isHighRisk(line)) {
            final String risk = CommanderChat.riskSummary(line);
            final String eventId = CommanderLog.newId();
            CommanderLog.record(this, "override_request", risk, eventId, null, null, null);
            append("CUBE: high-risk — confirm override (biometric)\n");
            new AlertDialog.Builder(this)
                .setTitle("Commander override")
                .setMessage("This looks destructive or irreversible at the OS level.\n\n"
                    + "We will not lecture what is forbidden. "
                    + "Override with biometric / device lock to authorize, "
                    + "or cancel. Logged for reverse/audit.\n\n"
                    + "» " + risk)
                .setPositiveButton("Override", (d, w) -> {
                    CubeBiometricGate.authenticate(this,
                        "Commander override",
                        "Authorize via the CUBE",
                        new CubeBiometricGate.Callback() {
                            @Override public void onAuthenticated() {
                                CommanderLog.recordOverride(
                                    CubeContactActivity.this, risk, true, eventId, null);
                                dispatchCommanderChat(line, eventId, true);
                            }

                            @Override public void onFailed(String reason) {
                                CommanderLog.recordOverride(
                                    CubeContactActivity.this, risk, false, eventId, reason);
                                setCommanderChatBusy(false);
                                append("CUBE: override denied (" + reason + ")\n");
                            }
                        });
                })
                .setNegativeButton("Cancel", (d, w) -> {
                    CommanderLog.recordOverride(this, risk, false, eventId, "cancelled");
                    setCommanderChatBusy(false);
                    append("CUBE: override cancelled\n");
                })
                .setOnCancelListener(d -> {
                    CommanderLog.recordOverride(this, risk, false, eventId, "dismissed");
                    setCommanderChatBusy(false);
                })
                .show();
            return;
        }
        String eventId = CommanderLog.newId();
        dispatchCommanderChat(line, eventId, false);
    }

    private void dispatchCommanderChat(String line, String eventId, boolean overrideOk) {
        append("Commander: " + line + "\n");
        CommanderLog.recordChat(this, "commander", line, eventId);
        bridge.chatCommander(line, eventId, overrideOk, new NanobotBridge.StreamListener() {
            @Override public void onDelta(String s) {}

            @Override public void onDone(String full) {
                h.post(() -> {
                    setCommanderChatBusy(false);
                    String id = CommanderLog.newId();
                    CommanderLog.recordChat(CubeContactActivity.this, "cube", full, id);
                    append("Cube/Nanobot: " + full + "\n");
                });
            }

            @Override public void onError(Exception e) {
                h.post(() -> {
                    setCommanderChatBusy(false);
                    CommanderLog.record(CubeContactActivity.this, "chat_error",
                        e != null ? e.getMessage() : "error",
                        eventId, null, null, null);
                    append("Cube/Nanobot: " + (e != null ? e.getMessage() : "error") + "\n");
                });
            }
        });
    }

    private void setCommanderChatBusy(boolean busy) {
        commanderChatBusy = busy;
        if (gl != null) {
            try {
                // Pause auto-spin + lengthen pull while chat owns the peer.
                CubePlanePrefs.setAutoSpin(this, CubePlanePrefs.PLANE_FRONT, !busy);
                if (!busy) {
                    // restore default spin after chat
                    CubePlanePrefs.setAutoSpin(this, CubePlanePrefs.PLANE_FRONT, true);
                }
                gl.setPlane(CubePlanePrefs.PLANE_FRONT);
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        CubeBiometricGate.onActivityResult(requestCode, resultCode);
    }

    private void refreshMeaning() {
        if (meaning == null || gl == null) return;
        String head = "KERNEL LATTICE · each node = live sensor\n";
        meaning.setText(head + gl.selectionText());
    }

    private void append(String s) { chatLog.append(s); }

    private Button pill(String label, Runnable r) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setOnClickListener(v -> {
            r.run();
            if (meaning != null && gl != null)
                refreshMeaning();
        });
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
