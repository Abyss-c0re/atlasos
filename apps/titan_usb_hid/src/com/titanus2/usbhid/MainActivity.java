package com.titanus2.usbhid;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.titanus2.usbhid.ui.UiKit;

/**
 * HID (USB / Bluetooth) — pad, keys, payload type; settings behind gear.
 * Rootless hybrid: USB via in-ROM titan2-usb-hid; BT via BluetoothHidDevice.
 * Session: FGS + notif Stop; optional screen-off + wireless BT.
 * <p>
 * Keyboard-first (local TitanKey only — exclusive session routes keys to host):
 * Esc back · S Start/Stop · P Pad · K Keys · T Type · G gear ·
 * E exclusive · R share · U USB · B BT · O Both · C layouts ·
 * Pad/Type: 1/2/3 = L/R/M click.
 */
public class MainActivity extends Activity {
    private static final String TAG = "TitanUsbHid";
    /** Lab / host-side automation: adb am start … --ez lab_auto true … */
    public static final String EXTRA_LAB_AUTO = "lab_auto";
    public static final String EXTRA_LAB_ACTION = "lab_action"; // start|stop|connect
    public static final String EXTRA_HOST_MAC = "host_mac";
    public static final String EXTRA_HOST_NAME = "host_name";
    public static final String EXTRA_TRANSPORT = "transport";
    public static final String EXTRA_SCREEN_OFF = "screen_off";
    private static final int MODE_PAD = 0;
    private static final int MODE_KEYS = 1;
    private static final int MODE_TYPE = 2;
    private static final int MODE_SETTINGS = 3;
    private static final int REQ_BT = 1002;
    private static final int REQ_LOC = 1003;

    private TextView state;
    private TextView sessBtn;
    private TextView padTab;
    private TextView keysTab;
    private TextView typeTab;
    private TextView gearBtn;
    private EditText payloadEdit;
    private CheckBox payloadEnter;
    private TextView payloadStatus;
    private LinearLayout typeChrome;
    private SoftPadView typePad;
    private boolean payloadBusy;
    /**
     * Type tab: soft-only HID — session stays up with keys=0 mouse=0 so TitanKey
     * stays on-phone, but USB/BT bridge keeps draining soft inject (Send / pad).
     * Never tear session=0 here: that detaches gadget and makes Send miss.
     */
    private boolean typeSoftOnly;
    /** Soft inject actively sending (pad gesture or Send payload). */
    private boolean typeSoftArmed;
    /**
     * Bumped whenever Type soft plane work must be abandoned (leave Type, Stop).
     * holdTypeSoftSession captures the value and no-ops if it changed — prevents
     * a late IO_BG write (keys=mouse=0) from winning after Pad/Keys restore.
     */
    private volatile int typeIoGen = 0;
    private final Runnable typeParkRunnable = this::parkTypePhysical;
    /** Re-assert soft flags while Type is open (beat FGS / pad restore races). */
    private final Runnable typeSoftWatchdog = new Runnable() {
        @Override public void run() {
            // Soft compose flag alone is enough for FGS/inject; do not re-read
            // control files or rewrite plane on a timer (that caused Type jank).
            if (screenMode != MODE_TYPE || !session || payloadBusy) return;
            h.postDelayed(this, 10000);
        }
    };
    /** Last soft-session fingerprint so we skip no-op plane rewrites (session-on lag). */
    private String lastSoftSessionFp = "";
    private UiKit.Step speedStep;
    private UiKit.Step typeSpeedStep;
    private UiKit.Step accelStep;
    private TextView bExclusive;
    private TextView bShare;
    private TextView bUsb;
    private TextView bBt;
    private TextView bBoth;
    private UiKit.Toggle screenOffToggle;
    private TextView btStatusLbl;
    private LinearLayout btHostList;
    private LinearLayout btPanel;
    private LinearLayout settingsAdvanced;
    private LinearLayout keysFavList;
    private LinearLayout keysMorePanel;
    private TextView keysMoreBtn;
    private boolean keysMoreOpen;
    private SoftPadView pad;
    private LinearLayout padChrome;
    private ScrollView keysScroll;
    private ScrollView settingsScroll;
    private FrameLayout stage;
    private boolean session;
    private boolean exclusive = true;
    /** Keep HID live with display blank (default on — no screen burn). */
    private boolean screenOff = true;
    private int transport = HidControl.TRANSPORT_BT;
    private int typingMs = HidControl.DEFAULT_TYPING_MS;
    private int speedPct = HidControl.DEFAULT_SPEED_PCT;
    private int typeSpeedPct = HidControl.DEFAULT_TYPE_SPEED_PCT;
    private int accel = HidControl.DEFAULT_ACCEL;
    private TextView[] blockTiles;
    private int screenMode = MODE_PAD;
    private int lastMainMode = MODE_PAD;
    private SharedPreferences prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final BluetoothHidClient.Listener btListener = new BluetoothHidClient.Listener() {
        @Override public void onBtStatus(String status) {
            h.post(() -> refreshState());
        }
        @Override public void onHostsChanged() {
            h.post(() -> {
                rebuildBtHosts();
                refreshState();
            });
        }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            // Type: zero background work — IME + soft pad own the main thread
            if (screenMode == MODE_TYPE) {
                h.postDelayed(this, 5000);
                return;
            }
            boolean live = HidSessionService.isRunning()
                || HidControl.isSessionOn(MainActivity.this);
            if (session && !live && !typeSoftOnly) session = false;
            else if (!session && live) {
                session = true;
                exclusive = HidControl.isGrabOn(MainActivity.this);
            }
            refreshState();
            h.postDelayed(this, 1000);
        }
    };

    private static final int K_UP = 0x52, K_DOWN = 0x51, K_LEFT = 0x50, K_RIGHT = 0x4f;
    private static final int K_HOME = 0x4a, K_END = 0x4d, K_PGUP = 0x4b, K_PGDN = 0x4e;
    private static final int K_ESC = 0x29, K_TAB = 0x2b, K_ENT = 0x28, K_BKSP = 0x2a;
    private static final int K_DEL = 0x4c, K_SPC = 0x2c, K_INS = 0x49;

    private static final int[] BLOCK_MS = new int[]{0, 300, 600, 1000};
    private static final String[] BLOCK_LAB = new String[]{"Off", "Short", "Med", "Long"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        HidAppContext.init(this);
        PadModeClient.connect(this);
        // Use full square; no soft chrome padding waste on 1440
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        prefs = getSharedPreferences("usb_hid", MODE_PRIVATE);
        exclusive = prefs.getBoolean("exclusive", true);
        // Default true: keep HID keyboard/mouse with display blank (no burn).
        // Product VR / Quest path needs this; KEEP_SCREEN_ON only when false.
        screenOff = prefs.getBoolean("screen_off", true);
        // Default BT+USB when hybrid stack present; else BT only.
        int defTransport = Root.defaultTransport();
        transport = prefs.getInt("transport", defTransport);
        if (transport == 0) transport = defTransport;
        if ((transport & HidControl.TRANSPORT_USB) != 0 && !Root.usbGadgetAvailable()) {
            transport = transport & ~HidControl.TRANSPORT_USB;
            if (transport == 0) transport = HidControl.TRANSPORT_BT;
        }
        typingMs = prefs.getInt("typing_ms", HidControl.DEFAULT_TYPING_MS);
        speedPct = prefs.getInt("speed_pct", HidControl.DEFAULT_SPEED_PCT);
        typeSpeedPct = prefs.getInt("type_speed_pct", HidControl.DEFAULT_TYPE_SPEED_PCT);
        if (typeSpeedPct < 25) typeSpeedPct = 25;
        if (typeSpeedPct > 400) typeSpeedPct = 400;
        accel = prefs.getInt("accel", HidControl.DEFAULT_ACCEL);
        HidControl.init(this);
        HidControl.setTransport(transport);
        // Heal stuck usb=0/bt=0 plane from older Stop paths (prefs still valid).
        HidControl.writeTransportEnables(this);
        HidControl.setScreenOffOk(this, screenOff);
        HidControl.setTypeSpeedPct(typeSpeedPct);
        BluetoothHidClient.get().loadPreferred(this);
        BluetoothHidClient.get().addListener(btListener);
        KeyLedClient.ensureDefaults(this);
        session = HidSessionService.isRunning() || HidControl.isSessionOn(this);
        if (session) exclusive = HidControl.isGrabOn(this);
        // Keep Send / Enter row above IME; shrink stage instead of covering chrome
        getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        applyKeepScreenFlag();
        maybeRequestNotifPerm();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // Match Settings chrome; keep stage free for pad
        int edge = UiKit.dp(root, UiKit.PAD_H);
        root.setPadding(edge, UiKit.dp(root, 12), edge, edge);
        setContentView(root);

        // Title row: name + short state
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trLp.bottomMargin = UiKit.GAP;
        titleRow.setLayoutParams(trLp);
        root.addView(titleRow);

        TextView title = new TextView(this);
        title.setText("HID");
        title.setTextSize(18f);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setTextColor(UiKit.textColor(this));
        title.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(title);

        state = new TextView(this);
        state.setTextSize(12f);
        state.setTypeface(android.graphics.Typeface.MONOSPACE);
        state.setTextColor(UiKit.mutedColor(this));
        state.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        titleRow.addView(state);

        // Toolbar: Start | Pad | Keys | Type | gear
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.bottomMargin = UiKit.GAP;
        bar.setLayoutParams(barLp);
        root.addView(bar);

        sessBtn = bigFlex(bar, "Start", this::toggleSession);
        padTab = bigFlex(bar, "Pad", () -> setMode(MODE_PAD));
        keysTab = bigFlex(bar, "Keys", () -> setMode(MODE_KEYS));
        typeTab = bigFlex(bar, "Type", () -> setMode(MODE_TYPE));
        gearBtn = bigFlex(bar, "⚙", this::toggleSettings);

        // Stage fills remaining square
        stage = new FrameLayout(this);
        stage.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(stage);

        // --- Pad ---
        padChrome = new LinearLayout(this);
        padChrome.setOrientation(LinearLayout.VERTICAL);
        padChrome.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pad = new SoftPadView(this);
        pad.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // Touch pad while off → Start (so soft mouse always works)
        pad.setActivityListeners(() -> {
            if (!session) startSession();
            KeyLedClient.bumpActivity();
        }, null);
        padChrome.addView(pad);

        LinearLayout clicks = new LinearLayout(this);
        clicks.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = UiKit.GAP;
        clicks.setLayoutParams(cLp);
        padChrome.addView(clicks);

        int clickH = clickBtnHeightPx();
        mouseBtn(clicks, "L", clickH, () -> {
            pad.clickLeft();
            KeyLedClient.bumpActivity();
        });
        mouseBtn(clicks, "R", clickH, () -> {
            pad.clickRight();
            KeyLedClient.bumpActivity();
        });
        mouseBtn(clicks, "M", clickH, () -> {
            HidControl.mouseButtons(4);
            HidControl.mouseButtons(0);
            KeyLedClient.bumpActivity();
        });
        stage.addView(padChrome);

        // --- Keys ---
        keysScroll = new ScrollView(this);
        keysScroll.setFillViewport(true);
        keysScroll.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout keysRoot = new LinearLayout(this);
        keysRoot.setOrientation(LinearLayout.VERTICAL);
        keysRoot.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        keysScroll.addView(keysRoot);
        buildKeys(keysRoot);
        stage.addView(keysScroll);

        // --- Payload type (stock IME → HID key stream) ---
        typeChrome = new LinearLayout(this);
        typeChrome.setOrientation(LinearLayout.VERTICAL);
        typeChrome.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildPayload(typeChrome);
        stage.addView(typeChrome);

        // --- Settings (hidden) ---
        settingsScroll = new ScrollView(this);
        settingsScroll.setFillViewport(true);
        settingsScroll.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout setRoot = new LinearLayout(this);
        setRoot.setOrientation(LinearLayout.VERTICAL);
        setRoot.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        settingsScroll.addView(setRoot);
        buildSettings(setRoot);
        stage.addView(settingsScroll);

        setMode(MODE_PAD);
        HidControl.setSpeedPct(this, speedPct);
        HidControl.setAccel(this, accel);
        HidControl.setTypingGuardMs(this, typingMs);
        refreshState();
    }

    /**
     * TitanKey hub shortcuts — only when <b>session is off</b> (or Settings /
     * Type compose). Live session (exclusive <b>or</b> share) must never fire
     * S/G/E/R/1/2/3 etc. — typing while controlling the PC flipped settings,
     * session, and mouse clicks (report: horrible mid-control UX).
     * Type payload EditText: Esc unfocus only (never steal compose letters).
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
            return super.dispatchKeyEvent(event);
        }
        if (event.isAltPressed() || event.isCtrlPressed()
                || event.isMetaPressed() || event.isShiftPressed()) {
            return super.dispatchKeyEvent(event);
        }
        int kc = event.getKeyCode();
        boolean typeCompose = screenMode == MODE_TYPE && payloadEdit != null
            && payloadEdit.hasFocus();
        if (typeCompose) {
            if (kc == KeyEvent.KEYCODE_ESCAPE) {
                clearPayloadFocus();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        // Any live session (excl or share): swallow hub chords — no mode thrash.
        // Esc from Settings still leaves settings; never finish() mid-session.
        boolean sessionLive = session && !typeSoftOnly
            && (HidSessionService.isRunning() || HidControl.isSessionOn(this));
        if (sessionLive && screenMode != MODE_SETTINGS) {
            if (isHubShortcutKey(kc)) return true; // swallow, no mode change
            return super.dispatchKeyEvent(event);
        }
        switch (kc) {
            case KeyEvent.KEYCODE_ESCAPE:
                if (screenMode == MODE_SETTINGS) {
                    setMode(lastMainMode == MODE_KEYS ? MODE_KEYS
                        : lastMainMode == MODE_TYPE ? MODE_TYPE : MODE_PAD);
                    return true;
                }
                // Live session: Esc must not kill the session / activity
                if (session && (HidSessionService.isRunning() || HidControl.isSessionOn(this))) {
                    return true;
                }
                finish();
                return true;
            case KeyEvent.KEYCODE_S:
                toggleSession();
                return true;
            case KeyEvent.KEYCODE_P:
                setMode(MODE_PAD);
                return true;
            case KeyEvent.KEYCODE_K:
                setMode(MODE_KEYS);
                return true;
            case KeyEvent.KEYCODE_T:
                setMode(MODE_TYPE);
                return true;
            case KeyEvent.KEYCODE_G:
                toggleSettings();
                return true;
            case KeyEvent.KEYCODE_E:
                exclusive = true;
                savePrefs();
                if (session) restartSession();
                refreshState();
                return true;
            case KeyEvent.KEYCODE_R:
                exclusive = false;
                savePrefs();
                if (session) restartSession();
                refreshState();
                return true;
            case KeyEvent.KEYCODE_U:
                setTransport(HidControl.TRANSPORT_USB);
                return true;
            case KeyEvent.KEYCODE_B:
                setTransport(HidControl.TRANSPORT_BT);
                return true;
            case KeyEvent.KEYCODE_O:
                setTransport(HidControl.TRANSPORT_BOTH);
                return true;
            case KeyEvent.KEYCODE_C:
                openControlsLayouts();
                return true;
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_NUMPAD_1:
                return softMouseClick(0);
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_NUMPAD_2:
                return softMouseClick(1);
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_NUMPAD_3:
                return softMouseClick(2);
            default:
                break;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Letters/digits that are hub chords — never fire during any live session. */
    private static boolean isHubShortcutKey(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_S:
            case KeyEvent.KEYCODE_P:
            case KeyEvent.KEYCODE_K:
            case KeyEvent.KEYCODE_T:
            case KeyEvent.KEYCODE_G:
            case KeyEvent.KEYCODE_E:
            case KeyEvent.KEYCODE_R:
            case KeyEvent.KEYCODE_U:
            case KeyEvent.KEYCODE_B:
            case KeyEvent.KEYCODE_O:
            case KeyEvent.KEYCODE_C:
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_NUMPAD_1:
            case KeyEvent.KEYCODE_NUMPAD_2:
            case KeyEvent.KEYCODE_NUMPAD_3:
            case KeyEvent.KEYCODE_ESCAPE:
                return true;
            default:
                return false;
        }
    }

    /** Soft pad L/R/M — Pad or Type stage only. */
    private boolean softMouseClick(int which) {
        if (screenMode != MODE_PAD && screenMode != MODE_TYPE) return false;
        if (screenMode == MODE_TYPE) {
            h.removeCallbacks(typeParkRunnable);
            armTypeSoftInject();
        } else if (!session) {
            startSession();
        }
        if (which == 0) {
            if (screenMode == MODE_TYPE && typePad != null) typePad.clickLeft();
            else if (pad != null) pad.clickLeft();
        } else if (which == 1) {
            if (screenMode == MODE_TYPE && typePad != null) typePad.clickRight();
            else if (pad != null) pad.clickRight();
        } else {
            HidControl.mouseButtons(4);
            HidControl.mouseButtons(0);
        }
        KeyLedClient.bumpActivity();
        if (screenMode == MODE_TYPE) h.postDelayed(typeParkRunnable, 400);
        return true;
    }

    private void clearPayloadFocus() {
        if (payloadEdit == null) return;
        payloadEdit.clearFocus();
        try {
            InputMethodManager imm = (InputMethodManager)
                getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(payloadEdit.getWindowToken(), 0);
            }
        } catch (Exception ignored) {}
        View root = getWindow() != null ? getWindow().getDecorView() : null;
        if (root != null) root.requestFocus();
    }

    private int clickBtnHeightPx() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        // ~12% of short side, clamp 64–96dp equivalent
        int side = Math.min(dm.widthPixels, dm.heightPixels);
        int h = side / 9;
        int min = UiKit.dp(new View(this), 56);
        int max = UiKit.dp(new View(this), 88);
        return Math.max(min, Math.min(max, h));
    }

    private TextView bigFlex(LinearLayout row, String text, Runnable r) {
        TextView b = UiKit.flexButton(row, text, r);
        b.setMinHeight(UiKit.dp(b, 48));
        b.setTextSize(14f);
        int pv = UiKit.dp(b, 14);
        b.setPadding(UiKit.dp(b, 6), pv, UiKit.dp(b, 6), pv);
        return b;
    }

    private void mouseBtn(LinearLayout row, String lab, int height, Runnable r) {
        TextView b = new TextView(this);
        b.setText(lab);
        b.setTextColor(UiKit.textColor(this));
        b.setTextSize(20f);
        b.setTypeface(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(UiKit.square(UiKit.TILE));
        b.setClickable(true);
        b.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, 1f);
        lp.setMargins(0, 0, UiKit.GAP, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> r.run());
        row.addView(b);
    }

    private void buildSettings(LinearLayout setRoot) {
        // Link + hosts + routing — product labels only (no ADB / stack essays)
        UiKit.section(setRoot, "Link");
        LinearLayout link = UiKit.row(setRoot);
        bUsb = UiKit.flexButton(link, "USB", () -> setTransport(HidControl.TRANSPORT_USB));
        bBt = UiKit.flexButton(link, "BT", () -> setTransport(HidControl.TRANSPORT_BT));
        bBoth = UiKit.flexButton(link, "Both", () -> setTransport(HidControl.TRANSPORT_BOTH));

        btPanel = new LinearLayout(this);
        btPanel.setOrientation(LinearLayout.VERTICAL);
        btPanel.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setRoot.addView(btPanel);

        UiKit.section(btPanel, "PC");
        btStatusLbl = UiKit.sliderLabel(btPanel, "BT off");
        btStatusLbl.setTextSize(12f);
        btStatusLbl.setTextColor(UiKit.mutedColor(this));

        LinearLayout btAct = UiKit.row(btPanel);
        UiKit.flexButton(btAct, "Enable", this::onBtEnable);
        UiKit.flexButton(btAct, "Scan", this::onBtScan);
        LinearLayout btAct2 = UiKit.row(btPanel);
        UiKit.flexButton(btAct2, "Disconnect", this::onBtDisconnect);
        UiKit.flexButton(btAct2, "BT settings", () ->
            BluetoothHidClient.get().openBluetoothSettings(this));

        UiKit.section(btPanel, "Target");
        btHostList = new LinearLayout(this);
        btHostList.setOrientation(LinearLayout.VERTICAL);
        btHostList.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        btPanel.addView(btHostList);

        UiKit.section(setRoot, "Routing");
        LinearLayout route = UiKit.row(setRoot);
        bExclusive = UiKit.flexButton(route, "Exclusive", () -> {
            exclusive = true;
            savePrefs();
            if (session) restartSession();
            refreshState();
        });
        bShare = UiKit.flexButton(route, "Share", () -> {
            exclusive = false;
            savePrefs();
            if (session) restartSession();
            refreshState();
        });

        // Open Controls layout editor (view/modify specials · arrows · custom)
        UiKit.section(setRoot, "Layouts");
        UiKit.button(setRoot, "View / edit layouts", this::openControlsLayouts);
        // Per-app profile in Controls (same map HID session uses via API)
        UiKit.button(setRoot, "HID host key map (Controls)", this::openHidHostKeyProfile);

        // L2: progressive disclosure
        final TextView[] advBtn = new TextView[1];
        settingsAdvanced = new LinearLayout(this);
        settingsAdvanced.setOrientation(LinearLayout.VERTICAL);
        settingsAdvanced.setVisibility(View.GONE);
        settingsAdvanced.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        advBtn[0] = UiKit.button(setRoot, "Mouse · session…", () -> {
            boolean open = settingsAdvanced.getVisibility() != View.VISIBLE;
            settingsAdvanced.setVisibility(open ? View.VISIBLE : View.GONE);
            advBtn[0].setText(open ? "Mouse · session ▾" : "Mouse · session…");
        });
        setRoot.addView(settingsAdvanced);

        // Black stay-awake: brightness 0 + KEEP_SCREEN_ON (true panel blank
        // kills USB/input on this SoC — product “screen off HID”).
        // Do NOT restartSession — that thrash-killed exclusive mid-use.
        screenOffToggle = UiKit.toggle(settingsAdvanced,
                "Black screen · keep keyboard", screenOff, on -> {
            screenOff = on;
            HidControl.setScreenOffOk(this, screenOff);
            applyKeepScreenFlag();
            applyBlackScreenMode();
            savePrefs();
            if (session) {
                if (HidSessionService.isRunning()) {
                    HidSessionService.update(this, effectiveMouse(), effectiveGrab(),
                            effectiveKeys(), transport, screenOff);
                }
            }
            refreshState();
        });

        UiKit.section(settingsAdvanced, "Pad gestures");
        UiKit.toggle(settingsAdvanced, "Tap click", PadGesturePrefs.tapClick(this), on -> {
            PadGesturePrefs.setTapClick(this, on);
        });
        UiKit.toggle(settingsAdvanced, "Long-press right click",
                PadGesturePrefs.longClick(this), on -> {
            PadGesturePrefs.setLongClick(this, on);
        });
        UiKit.toggle(settingsAdvanced, "Scroll", PadGesturePrefs.scroll(this), on -> {
            PadGesturePrefs.setScroll(this, on);
        });
        final TextView[] dblLbl = new TextView[1];
        dblLbl[0] = UiKit.button(settingsAdvanced,
                PadGesturePrefs.dblTapLabel(PadGesturePrefs.dblTap(this)), () -> {
            String next = PadGesturePrefs.nextDblTap(PadGesturePrefs.dblTap(this));
            PadGesturePrefs.setDblTap(this, next);
            dblLbl[0].setText(PadGesturePrefs.dblTapLabel(next));
        });

        // Cube square [−][value][+] — no Material SeekBar (PRODUCT_UX geometry).
        UiKit.section(settingsAdvanced, "Mouse speed");
        int speedProg = Math.max(0, Math.min(35, (speedPct - 25) / 5));
        speedStep = UiKit.step(settingsAdvanced, "Speed", 0, 35, speedProg, p -> {
            speedPct = 25 + p * 5;
            if (speedStep != null) speedStep.setDisplay(speedLabelText());
            HidControl.setSpeedPct(MainActivity.this, speedPct);
            savePrefs();
            refreshState();
        });
        if (speedStep != null) speedStep.setDisplay(speedLabelText());

        UiKit.section(settingsAdvanced, "Type speed");
        int typeProgSet = Math.max(0, Math.min(75, (typeSpeedPct - 25) / 5));
        typeSpeedStep = UiKit.step(settingsAdvanced, "Type", 0, 75, typeProgSet, p -> {
            typeSpeedPct = 25 + p * 5;
            if (typeSpeedStep != null) typeSpeedStep.setDisplay(typeSpeedLabelText());
            HidControl.setTypeSpeedPct(typeSpeedPct);
            savePrefs();
            refreshState();
        });
        if (typeSpeedStep != null) typeSpeedStep.setDisplay(typeSpeedLabelText());

        UiKit.section(settingsAdvanced, "Mouse acceleration");
        accelStep = UiKit.step(settingsAdvanced, "Accel", 0, 3, accel, p -> {
            accel = p;
            if (accelStep != null) accelStep.setDisplay(accelLabelText());
            HidControl.setAccel(MainActivity.this, accel);
            savePrefs();
            refreshState();
        });
        if (accelStep != null) accelStep.setDisplay(accelLabelText());

        UiKit.section(settingsAdvanced, "Block pad while typing");
        LinearLayout blockRow = UiKit.row(settingsAdvanced);
        blockTiles = new TextView[BLOCK_MS.length];
        for (int i = 0; i < BLOCK_MS.length; i++) {
            final int ms = BLOCK_MS[i];
            blockTiles[i] = UiKit.flexButton(blockRow, BLOCK_LAB[i], () -> {
                typingMs = ms;
                HidControl.setTypingGuardMs(this, typingMs);
                savePrefs();
                refreshState();
            });
        }

        // Side keys → host actions (scroll / click / arrows / chords). Tap cycles.
        UiKit.section(settingsAdvanced, "Side keys → host");
        addSideKeyRow(settingsAdvanced, "Bottom short",
            com.titanus2.api.Titan2ApiContract.SLOT_SIDE_SHORT);
        addSideKeyRow(settingsAdvanced, "Bottom long",
            com.titanus2.api.Titan2ApiContract.SLOT_SIDE_LONG);
        addSideKeyRow(settingsAdvanced, "Top short",
            com.titanus2.api.Titan2ApiContract.SLOT_SIDE2_SHORT);
        addSideKeyRow(settingsAdvanced, "Top long",
            com.titanus2.api.Titan2ApiContract.SLOT_SIDE2_LONG);
        UiKit.button(settingsAdvanced, "Open Controls key map", () -> {
            try {
                Intent i = new Intent();
                i.setClassName("com.titanus2.controls",
                    "com.titanus2.controls.KeyMapActivity");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, "Controls Keys unavailable", Toast.LENGTH_SHORT).show();
            }
        });

        UiKit.button(setRoot, "Back", () -> setMode(
            lastMainMode == MODE_KEYS ? MODE_KEYS
                : lastMainMode == MODE_TYPE ? MODE_TYPE : MODE_PAD));

        handleLabIntent(getIntent());
        // User taps Start; lab harness uses EXTRA_LAB_AUTO only.
    }

    /** @deprecated removed — open does not auto-start a session. */
    private void maybeAutoStartSession(Intent intent) {
        // no-op
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLabIntent(intent);
    }

    /**
     * PC-side harness entry (no UI taps):
     *   adb shell am start -n com.titanus2.usbhid/.MainActivity \
     *     --ez lab_auto true --es lab_action start \
     *     --es host_mac AA:BB:… --es host_name LabHost \
     *     --ei transport 2 --ez screen_off true
     */
    private void handleLabIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_LAB_AUTO, false)) return;
        String action = intent.getStringExtra(EXTRA_LAB_ACTION);
        if (action == null || action.isEmpty()) action = "start";
        action = action.toLowerCase(java.util.Locale.US);
        Log.i(TAG, "lab_auto action=" + action);

        if (intent.hasExtra(EXTRA_TRANSPORT)) {
            int t = intent.getIntExtra(EXTRA_TRANSPORT, HidControl.TRANSPORT_BT);
            if (t == 0) t = HidControl.TRANSPORT_BT;
            transport = t & HidControl.TRANSPORT_BOTH;
            if (transport == 0) transport = HidControl.TRANSPORT_BT;
            HidControl.setTransport(transport);
        }
        if (intent.hasExtra(EXTRA_SCREEN_OFF)) {
            screenOff = intent.getBooleanExtra(EXTRA_SCREEN_OFF, true);
            HidControl.setScreenOffOk(this, screenOff);
        }

        String mac = intent.getStringExtra(EXTRA_HOST_MAC);
        String name = intent.getStringExtra(EXTRA_HOST_NAME);
        if (mac != null && !mac.isEmpty()) {
            if (name == null || name.isEmpty()) name = mac;
            BluetoothHidClient.get().setPreferred(this, mac, name);
            Log.i(TAG, "lab preferred host " + name + " " + mac);
        }
        savePrefs();
        refreshState();
        rebuildBtHosts();

        final String act = action;
        // Defer so BT perms / adapter settle after cold start
        h.postDelayed(() -> {
            if ("stop".equals(act)) {
                stopSession();
                BluetoothHidClient.get().stop();
                return;
            }
            if ((transport & HidControl.TRANSPORT_BT) != 0) {
                if (!hasBtPerms()) {
                    prefs.edit().putBoolean("pending_start", true).apply();
                    maybeRequestBtPerms();
                }
                BluetoothHidClient bt = BluetoothHidClient.get();
                if (!bt.isAdapterOn()) {
                    prefs.edit().putBoolean("pending_start", true).apply();
                    bt.requestEnable(this);
                    return;
                }
                String pref = bt.preferredMac();
                if (pref != null && !pref.isEmpty()) {
                    bt.selectAndConnect(this, pref, bt.preferredName());
                } else {
                    bt.start(this);
                }
            }
            if ("connect".equals(act)) {
                // Register + connect only; do not force full session flags
                refreshState();
                rebuildBtHosts();
                return;
            }
            // start
            if (!session) startSession();
            else restartSession();
            rebuildBtHosts();
            refreshState();
        }, 400);
    }

    private String speedLabelText() { return "speed " + speedPct + "%"; }

    private String typeSpeedLabelText() { return "type " + typeSpeedPct + "%"; }


    private String accelLabelText() {
        if (accel <= 0) return "accel off";
        if (accel == 1) return "accel low";
        if (accel == 2) return "accel med";
        return "accel high";
    }

    private void buildPayload(LinearLayout root) {
        // Top: payload controls. Bottom: mini soft trackpad fills rest.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(top);

        UiKit.section(top, "Payload");
        TextView hint = UiKit.sliderLabel(top, "phys local · soft → host");
        hint.setTextSize(11f);
        hint.setTextColor(UiKit.mutedColor(this));

        LinearLayout actions = UiKit.row(top);
        UiKit.flexButton(actions, "Send", this::sendPayload);
        UiKit.flexButton(actions, "Clear", () -> {
            if (payloadEdit != null) payloadEdit.setText("");
            if (payloadStatus != null) payloadStatus.setText("");
        });

        payloadEnter = new CheckBox(this);
        payloadEnter.setText("Enter after");
        payloadEnter.setTextColor(UiKit.textColor(this));
        payloadEnter.setChecked(prefs.getBoolean("payload_enter", true));
        payloadEnter.setOnCheckedChangeListener((b, checked) ->
            prefs.edit().putBoolean("payload_enter", checked).apply());
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.bottomMargin = UiKit.GAP;
        payloadEnter.setLayoutParams(cLp);
        top.addView(payloadEnter);

        payloadStatus = UiKit.sliderLabel(top, "");
        payloadStatus.setTextSize(12f);
        payloadStatus.setTextColor(UiKit.mutedColor(this));

        payloadEdit = new EditText(this);
        payloadEdit.setHint("text to type on host");
        payloadEdit.setTextColor(UiKit.textColor(this));
        payloadEdit.setHintTextColor(UiKit.mutedColor(this));
        payloadEdit.setBackground(UiKit.square(UiKit.TILE));
        payloadEdit.setMinLines(2);
        payloadEdit.setMaxLines(3);
        payloadEdit.setLines(2);
        payloadEdit.setGravity(Gravity.TOP | Gravity.START);
        payloadEdit.setTextSize(15f);
        payloadEdit.setInputType(
            android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | android.text.InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE);
        payloadEdit.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        int padPx = UiKit.dp(payloadEdit, 8);
        payloadEdit.setPadding(padPx, padPx, padPx, padPx);
        int fieldH = UiKit.dp(payloadEdit, 72);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, fieldH);
        elp.topMargin = UiKit.GAP;
        elp.bottomMargin = UiKit.GAP;
        payloadEdit.setLayoutParams(elp);
        // Focus: soft-compose only (no plane rewrites — IME must not stall)
        payloadEdit.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && screenMode == MODE_TYPE) {
                HidControl.setSoftCompose(true);
                typeSoftOnly = true;
                typeSoftArmed = false;
            }
        });
        top.addView(payloadEdit);

        // Mini software trackpad — remaining stage height
        typePad = new SoftPadView(this);
        typePad.setCompact(true);
        typePad.setActivityListeners(
            () -> {
                h.removeCallbacks(typeParkRunnable);
                // Debounce: only arm once per gesture burst (was rewriting plane every DOWN)
                if (!typeSoftArmed) armTypeSoftInject();
            },
            () -> {
                h.removeCallbacks(typeParkRunnable);
                h.postDelayed(typeParkRunnable, 600);
            });
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        typePad.setLayoutParams(padLp);
        root.addView(typePad);

        LinearLayout clicks = new LinearLayout(this);
        clicks.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = UiKit.GAP;
        clicks.setLayoutParams(clp);
        root.addView(clicks);
        int clickH = Math.max(UiKit.dp(new View(this), 44), clickBtnHeightPx() * 2 / 3);
        mouseBtn(clicks, "L", clickH, () -> {
            h.removeCallbacks(typeParkRunnable);
            armTypeSoftInject();
            if (typePad != null) typePad.clickLeft();
            KeyLedClient.bumpActivity();
            h.postDelayed(typeParkRunnable, 400);
        });
        mouseBtn(clicks, "R", clickH, () -> {
            h.removeCallbacks(typeParkRunnable);
            armTypeSoftInject();
            if (typePad != null) typePad.clickRight();
            KeyLedClient.bumpActivity();
            h.postDelayed(typeParkRunnable, 400);
        });
        mouseBtn(clicks, "M", clickH, () -> {
            h.removeCallbacks(typeParkRunnable);
            armTypeSoftInject();
            HidControl.mouseButtons(4);
            HidControl.mouseButtons(0);
            KeyLedClient.bumpActivity();
            h.postDelayed(typeParkRunnable, 400);
        });
    }

    private void sendPayload() {
        if (payloadBusy) return;
        if (payloadEdit == null) return;
        final String text = payloadEdit.getText() != null
            ? payloadEdit.getText().toString() : "";
        if (text.isEmpty()) {
            if (payloadStatus != null) {
                payloadStatus.setText("empty");
                payloadStatus.setTextColor(UiKit.WARN);
            }
            return;
        }
        final boolean enter = payloadEnter != null && payloadEnter.isChecked();
        if (!session) session = true;
        payloadBusy = true;
        if (payloadStatus != null) {
            payloadStatus.setText("sending…");
            payloadStatus.setTextColor(UiKit.mutedColor(this));
        }
        h.removeCallbacks(typeParkRunnable);
        // Soft session keeps bridge up — arm flags then type after service poll.
        armTypeSoftInject();
        final Context app = getApplicationContext();
        final int sendGen = typeIoGen;
        new Thread(() -> {
            HidControl.setTypeSpeedPct(typeSpeedPct);
            // Magisk/in-ROM service polls ~0.5s; wait until session plane is on
            // then a short settle so hid_bridge has bound @titan2_hid.
            try {
                for (int i = 0; i < 20; i++) {
                    if (sendGen != typeIoGen) break;
                    if (HidControl.isSessionOn(app)) break;
                    Thread.sleep(50);
                }
                if (sendGen == typeIoGen) Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Keep soft-arm for the whole send only while still on Type.
            // leaveTypeMode bumps typeIoGen so this cannot rewrite soft plane on Pad.
            h.post(() -> {
                if (sendGen != typeIoGen || screenMode != MODE_TYPE) return;
                typeSoftArmed = true;
                holdTypeSoftSession(true);
            });
            // Finish inject even if user switched tabs (live plane still drains).
            int n = HidControl.typeText(text);
            if (enter) {
                HidControl.keyTap(0x28); // Enter
                n++;
            }
            KeyLedClient.bumpActivity();
            // Clear stuck mods/keys after payload (sticky Shift was a real bug)
            HidControl.releaseAllKeys();
            final int sent = n;
            h.post(() -> {
                payloadBusy = false;
                if (payloadStatus != null) {
                    payloadStatus.setText("sent " + sent + " key" + (sent == 1 ? "" : "s"));
                    payloadStatus.setTextColor(sent > 0 ? UiKit.OK : UiKit.WARN);
                }
                // Soft park only if still on Type under the same IO generation
                if (sendGen == typeIoGen && screenMode == MODE_TYPE) parkTypePhysical();
                refreshState();
            });
        }).start();
    }

    /**
     * Type idle: phys keys/pad stay on-phone (soft plane). When HID is already
     * live, <b>keep</b> session + FGS + USB/BT — only clear phys redirect.
     * Never parkSession/stop FGS on Type open (gadget teardown = lag).
     */
    private void parkTypePhysical() {
        if (screenMode != MODE_TYPE) return;
        if (payloadBusy) return;
        typeSoftOnly = true;
        typeSoftArmed = false;
        HidControl.setSoftCompose(true);
        h.removeCallbacks(typeParkRunnable);
        if (session || HidSessionService.isRunning()) {
            holdTypeSoftSession(false);
        }
    }

    /**
     * Soft inject active (Send / mini pad). Arms session if needed so bridge
     * drains inject; phys redirect stays off.
     */
    private void armTypeSoftInject() {
        if (screenMode != MODE_TYPE) return;
        typeSoftOnly = true;
        typeSoftArmed = true;
        HidControl.setSoftCompose(true);
        if (!session) session = true;
        holdTypeSoftSession(true);
    }

    /**
     * Soft Type control plane: mouse=grab=keys=0.
     * Live session: phys flags only (async). Never park/stop FGS on Type open.
     */
    private void holdTypeSoftSession(boolean startIfNeeded) {
        if (screenMode != MODE_TYPE) return;
        final int gen = typeIoGen;
        final boolean usbWant = (transport & HidControl.TRANSPORT_USB) != 0;
        final boolean btWant = (transport & HidControl.TRANSPORT_BT) != 0;
        final boolean fgs = HidSessionService.isRunning();
        final boolean live = session || fgs;
        final boolean claimUsb = (live || typeSoftArmed) && usbWant;
        final boolean claimBt = (live || typeSoftArmed) && btWant;

        final String fp = (typeSoftArmed ? "a" : "p")
            + (claimUsb ? "u" : "") + (claimBt ? "b" : "")
            + (live ? "s" : "0") + transport + (fgs ? "f" : "");
        if (fp.equals(lastSoftSessionFp) && !startIfNeeded) {
            return;
        }

        if (!live && !typeSoftArmed) {
            lastSoftSessionFp = fp;
            return;
        }

        // In-memory only on UI thread
        HidControl.setTypeSpeedPct(typeSpeedPct);
        HidControl.setTransport(transport);
        HidControl.setScreenOffOk(this, screenOff);
        lastSoftSessionFp = fp;

        final boolean arm = typeSoftArmed || startIfNeeded;
        final boolean doStart = !fgs && (startIfNeeded || typeSoftArmed);
        final Context app = getApplicationContext();
        // All file/FGS work off the UI thread
        IO_BG.execute(() -> {
            // Abandoned: user left Type / Stop bumped typeIoGen
            if (gen != typeIoGen) return;
            if (live && !arm) {
                HidControl.setPhysRedirect(app, false, false, false);
                if (gen != typeIoGen) return;
                if (fgs) {
                    HidSessionService.update(app, false, false, false, transport, screenOff);
                }
                return;
            }
            if (arm) {
                HidControl.setTypingGuardMs(app, typingMs);
                HidControl.setSpeedPct(app, speedPct);
                HidControl.setAccel(app, accel);
            }
            if (gen != typeIoGen) return;
            HidControl.setSession(app, true, false, false, false, claimUsb, claimBt);
            if (gen != typeIoGen) return;
            if (claimBt) {
                try { BluetoothHidClient.get().start(app); } catch (Exception ignored) {}
            }
            if (gen != typeIoGen) return;
            if (fgs) {
                HidSessionService.update(app, false, false, false, transport, screenOff);
            } else if (doStart) {
                HidSessionService.start(app, false, false, false, transport, screenOff);
            }
        });
    }

    /**
     * Leave Type tab: cancel soft plane IO, clear compose/local_input, release
     * stuck HID keys, restore Pad/Keys phys redirect. Must beat any late
     * holdTypeSoftSession write that would leave keys=mouse=0.
     */
    private void leaveTypeMode() {
        typeIoGen++; // invalidate in-flight Type soft plane / payload arm
        h.removeCallbacks(typeParkRunnable);
        h.removeCallbacks(typeSoftWatchdog);
        typeSoftOnly = false;
        typeSoftArmed = false;
        lastSoftSessionFp = "";
        HidControl.setSoftCompose(false);
        // Clear sticky pause from Type EditText / prior share-mode guard
        HidControl.setLocalInputPause(this, false);
        HidControl.releaseAllKeys();
        boolean live = session || HidSessionService.isRunning();
        if (live) {
            session = true;
            pushLiveSession(true);
        }
    }

    private static final java.util.concurrent.ExecutorService IO_BG =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "titan-hid-type");
            t.setDaemon(true);
            return t;
        });

    private void buildKeys(LinearLayout keysRoot) {
        int cell = arrowCellPx();
        int keyH = Math.max(UiKit.dp(new View(this), 52), cell * 2 / 3);

        // Core: arrows + edit keys
        keysRoot.addView(arrowRow(null, K_UP, "↑", null, cell));
        keysRoot.addView(arrowRow(K_LEFT, "←", null, null, K_RIGHT, "→", cell));
        keysRoot.addView(arrowRow(null, K_DOWN, "↓", null, cell));

        LinearLayout ed1 = UiKit.row(keysRoot);
        keyTile(ed1, "Esc", K_ESC, keyH);
        keyTile(ed1, "Tab", K_TAB, keyH);
        keyTile(ed1, "Ent", K_ENT, keyH);
        LinearLayout ed2 = UiKit.row(keysRoot);
        keyTile(ed2, "Bksp", K_BKSP, keyH);
        keyTile(ed2, "Spc", K_SPC, keyH);
        keyTile(ed2, "Del", K_DEL, keyH);

        // Favorites (KDE-style list)
        UiKit.section(keysRoot, "Favorites");
        keysFavList = new LinearLayout(this);
        keysFavList.setOrientation(LinearLayout.VERTICAL);
        keysRoot.addView(keysFavList);
        rebuildKeyFavorites(keyH);
        UiKit.button(keysRoot, "+ Add favorite", () -> pickFavoriteToAdd(keyH));

        // More: nav + F-keys
        keysMoreBtn = UiKit.button(keysRoot, "More…", () -> {
            keysMoreOpen = !keysMoreOpen;
            if (keysMorePanel != null) {
                keysMorePanel.setVisibility(keysMoreOpen ? View.VISIBLE : View.GONE);
            }
            if (keysMoreBtn != null) keysMoreBtn.setText(keysMoreOpen ? "More ▾" : "More…");
        });
        keysMorePanel = new LinearLayout(this);
        keysMorePanel.setOrientation(LinearLayout.VERTICAL);
        keysMorePanel.setVisibility(View.GONE);
        keysRoot.addView(keysMorePanel);

        LinearLayout nav1 = UiKit.row(keysMorePanel);
        keyTile(nav1, "Home", K_HOME, keyH);
        keyTile(nav1, "End", K_END, keyH);
        keyTile(nav1, "PgUp", K_PGUP, keyH);
        LinearLayout nav2 = UiKit.row(keysMorePanel);
        keyTile(nav2, "PgDn", K_PGDN, keyH);
        keyTile(nav2, "Ins", K_INS, keyH);
        keyTile(nav2, "—", -1, keyH);

        LinearLayout f1 = UiKit.row(keysMorePanel);
        for (int i = 0; i < 4; i++) keyTile(f1, "F" + (i + 1), 0x3a + i, keyH);
        LinearLayout f2 = UiKit.row(keysMorePanel);
        for (int i = 0; i < 4; i++) keyTile(f2, "F" + (i + 5), 0x3e + i, keyH);
        LinearLayout f3 = UiKit.row(keysMorePanel);
        for (int i = 0; i < 4; i++) keyTile(f3, "F" + (i + 9), 0x42 + i, keyH);
    }

    private static final class FavKey {
        final String label;
        final int usage;
        FavKey(String label, int usage) { this.label = label; this.usage = usage; }
    }

    private static final FavKey[] FAV_CATALOG = new FavKey[] {
        new FavKey("Home", K_HOME), new FavKey("End", K_END),
        new FavKey("PgUp", K_PGUP), new FavKey("PgDn", K_PGDN),
        new FavKey("Ins", K_INS), new FavKey("Del", K_DEL),
        new FavKey("F1", 0x3a), new FavKey("F2", 0x3b), new FavKey("F3", 0x3c),
        new FavKey("F4", 0x3d), new FavKey("F5", 0x3e), new FavKey("F6", 0x3f),
        new FavKey("F7", 0x40), new FavKey("F8", 0x41), new FavKey("F9", 0x42),
        new FavKey("F10", 0x43), new FavKey("F11", 0x44), new FavKey("F12", 0x45),
        new FavKey("Esc", K_ESC), new FavKey("Tab", K_TAB),
        new FavKey("Ent", K_ENT), new FavKey("Bksp", K_BKSP), new FavKey("Spc", K_SPC),
    };

    private java.util.List<Integer> loadFavorites() {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        String raw = prefs.getString("key_favs", "");
        if (raw == null || raw.isEmpty()) return out;
        for (String p : raw.split(",")) {
            try {
                int u = Integer.parseInt(p.trim());
                if (u > 0) out.add(u);
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private void saveFavorites(java.util.List<Integer> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(list.get(i));
        }
        prefs.edit().putString("key_favs", sb.toString()).apply();
    }

    private String favLabel(int usage) {
        for (FavKey f : FAV_CATALOG) {
            if (f.usage == usage) return f.label;
        }
        return String.format("0x%02x", usage);
    }

    private void rebuildKeyFavorites(int keyH) {
        if (keysFavList == null) return;
        keysFavList.removeAllViews();
        java.util.List<Integer> favs = loadFavorites();
        if (favs.isEmpty()) {
            TextView empty = UiKit.sliderLabel(keysFavList, "none");
            empty.setTextSize(11f);
            empty.setTextColor(UiKit.mutedColor(this));
            return;
        }
        for (int usage : favs) {
            final int u = usage;
            TextView row = UiKit.button(keysFavList, favLabel(u), () -> fireKey(u));
            row.setMinHeight(keyH);
            row.setOnLongClickListener(v -> {
                java.util.List<Integer> list = loadFavorites();
                list.remove((Integer) u);
                saveFavorites(list);
                rebuildKeyFavorites(keyH);
                return true;
            });
        }
    }

    private void pickFavoriteToAdd(int keyH) {
        java.util.List<Integer> have = loadFavorites();
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Integer> usages = new java.util.ArrayList<>();
        for (FavKey f : FAV_CATALOG) {
            if (have.contains(f.usage)) continue;
            labels.add(f.label);
            usages.add(f.usage);
        }
        if (labels.isEmpty()) return;
        new android.app.AlertDialog.Builder(this)
            .setTitle("Add favorite")
            .setItems(labels.toArray(new String[0]), (d, which) -> {
                java.util.List<Integer> list = loadFavorites();
                int u = usages.get(which);
                if (!list.contains(u)) list.add(u);
                saveFavorites(list);
                rebuildKeyFavorites(keyH);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private int arrowCellPx() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int side = Math.min(dm.widthPixels, dm.heightPixels);
        // ~18% of side — large on 1440
        int cell = side / 5;
        int min = UiKit.dp(new View(this), 72);
        int max = UiKit.dp(new View(this), 120);
        return Math.max(min, Math.min(max, cell));
    }

    private LinearLayout arrowRow(Integer leftU, String leftL, Integer midU, String midL,
                                  Integer rightU, String rightL, int cell) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = UiKit.GAP;
        row.setLayoutParams(rlp);
        row.addView(arrowCell(leftU, leftL, cell));
        row.addView(arrowCell(midU, midL, cell));
        row.addView(arrowCell(rightU, rightL, cell));
        return row;
    }

    private LinearLayout arrowRow(Integer a, Integer b, String bL, Integer c, int cell) {
        return arrowRow(a, a == null ? "" : "·", b, bL, c, c == null ? "" : "·", cell);
    }

    private View arrowCell(Integer usage, String label, int cell) {
        if (usage == null || label == null || label.isEmpty()) {
            View spacer = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell, cell);
            lp.setMargins(UiKit.GAP / 2, 0, UiKit.GAP / 2, 0);
            spacer.setLayoutParams(lp);
            return spacer;
        }
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(UiKit.textColor(this));
        b.setTextSize(28f);
        b.setTypeface(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(UiKit.square(UiKit.TILE));
        b.setClickable(true);
        b.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell, cell);
        lp.setMargins(UiKit.GAP / 2, 0, UiKit.GAP / 2, 0);
        b.setLayoutParams(lp);
        final int u = usage;
        b.setOnClickListener(v -> fireKey(u));
        b.setOnLongClickListener(v -> {
            fireKey(u);
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!v.isPressed()) return;
                    fireKey(u);
                    h.postDelayed(this, 70);
                }
            }, 280);
            return true;
        });
        return b;
    }

    private void keyTile(LinearLayout row, String label, int usage, int minH) {
        if (usage < 0) {
            TextView sp = new TextView(this);
            sp.setText("");
            sp.setLayoutParams(new LinearLayout.LayoutParams(0, minH, 1f));
            row.addView(sp);
            return;
        }
        TextView b = UiKit.flexButton(row, label, () -> fireKey(usage));
        b.setMinHeight(minH);
        b.setTextSize(15f);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        if (lp != null) {
            lp.height = minH;
            b.setLayoutParams(lp);
        }
    }

    private void fireKey(int usage) {
        new Thread(() -> {
            HidControl.keyTap(usage);
            KeyLedClient.bumpActivity();
        }).start();
    }

    private void toggleSettings() {
        if (screenMode == MODE_SETTINGS) {
            setMode(lastMainMode);
        } else {
            if (screenMode == MODE_PAD || screenMode == MODE_KEYS || screenMode == MODE_TYPE)
                lastMainMode = screenMode;
            setMode(MODE_SETTINGS);
        }
    }

    private void setMode(int mode) {
        int prev = screenMode;
        screenMode = mode;
        if (mode == MODE_PAD || mode == MODE_KEYS || mode == MODE_TYPE) lastMainMode = mode;
        padChrome.setVisibility(mode == MODE_PAD ? View.VISIBLE : View.GONE);
        keysScroll.setVisibility(mode == MODE_KEYS ? View.VISIBLE : View.GONE);
        if (typeChrome != null)
            typeChrome.setVisibility(mode == MODE_TYPE ? View.VISIBLE : View.GONE);
        settingsScroll.setVisibility(mode == MODE_SETTINGS ? View.VISIBLE : View.GONE);
        UiKit.setSelected(padTab, mode == MODE_PAD);
        UiKit.setSelected(keysTab, mode == MODE_KEYS);
        if (typeTab != null) UiKit.setSelected(typeTab, mode == MODE_TYPE);
        UiKit.setSelected(gearBtn, mode == MODE_SETTINGS);
        // Type: soft-only. Soft-compose flag FIRST so FGS stops dumpsys/hwDrain
        // before any paint — that was freezing IME ~1s/poll with HID on.
        if (mode == MODE_TYPE && prev != MODE_TYPE) {
            typeSoftOnly = true;
            typeSoftArmed = false;
            lastSoftSessionFp = "";
            HidControl.setSoftCompose(true);
            // 1.86: park phys keys NOW (not only post). Delayed park left
            // exclusive keys=1 while user typed into inject field → host multi.
            try {
                if (session || HidSessionService.isRunning()) {
                    HidControl.setPhysRedirect(this, false, false, false);
                    if (HidSessionService.isRunning()) {
                        HidSessionService.update(this, false, false, false,
                            transport, screenOff);
                    }
                }
            } catch (Exception ignored) {}
            // Re-assert soft plane off main critical path (FGS settle)
            h.post(() -> {
                if (screenMode != MODE_TYPE) return;
                parkTypePhysical();
            });
            // Minimal chrome update — no usbHostLinked / full refreshState
            if (typeChrome != null) typeChrome.setVisibility(View.VISIBLE);
            if (padChrome != null) padChrome.setVisibility(View.GONE);
            if (keysScroll != null) keysScroll.setVisibility(View.GONE);
            if (settingsScroll != null) settingsScroll.setVisibility(View.GONE);
            if (typeTab != null) UiKit.setSelected(typeTab, true);
            UiKit.setSelected(padTab, false);
            UiKit.setSelected(keysTab, false);
            UiKit.setSelected(gearBtn, false);
            return;
        } else if (prev == MODE_TYPE && mode != MODE_TYPE) {
            leaveTypeMode();
        }
        refreshState();
    }

    private boolean effectiveGrab() {
        return exclusive && !typeSoftOnly;
    }

    private boolean effectiveMouse() {
        return !typeSoftOnly;
    }

    private boolean effectiveKeys() {
        return !typeSoftOnly;
    }

    /**
     * Full live session (Pad/Keys): FGS + phys redirect per exclusive/share.
     * Not used while Type is parked/soft-armed (those use park/arm helpers).
     */
    private void pushLiveSession(boolean startIfNeeded) {
        if (screenMode == MODE_TYPE) {
            // Type path is park / arm only
            if (typeSoftArmed) armTypeSoftInject();
            else parkTypePhysical();
            return;
        }
        // Pad/Keys: clear Type softCompose before plane write (never soft-only half-session).
        typeSoftOnly = false;
        HidControl.setSoftCompose(false);
        boolean grab = effectiveGrab();
        boolean mouse = effectiveMouse();
        boolean keys = effectiveKeys();
        boolean usb = (transport & HidControl.TRANSPORT_USB) != 0;
        boolean bt = (transport & HidControl.TRANSPORT_BT) != 0;
        // 1.85: do NOT demote exclusive→share on usbHostLinked() here.
        // Pure-HID enable_hid re-enums the cable (host 1→0→1); a false
        // "unplugged" mid-Start rewrote grab=0 and killed USB host keyboard.
        // Service start_bridge already forces --nograb when host is truly gone.
        // Phys session always wants keys+mouse on the plane (share or exclusive).
        // Never arm session=1 with keys=0 mouse=0 (soft-pad-only half-session).
        if (!mouse && !keys && !grab) {
            mouse = true;
            keys = true;
        }
        HidControl.setTypingGuardMs(this, typingMs);
        HidControl.setSpeedPct(this, speedPct);
        HidControl.setAccel(this, accel);
        HidControl.setTypeSpeedPct(typeSpeedPct);
        HidControl.setTransport(transport);
        HidControl.setScreenOffOk(this, screenOff);
        if (PadModeClient.isFollowOrient(this)) {
            PadModeClient.publishRotation(this);
        }
        if (mouse) HidControl.prepareDriverPad(this);
        // FGS owns the live plane — write once via service start/update.
        // Do not double-write setSession here then again in applySession (races).
        if (HidSessionService.isRunning()) {
            HidSessionService.update(this, mouse, grab, keys, transport, screenOff);
        } else if (startIfNeeded) {
            HidSessionService.start(this, mouse, grab, keys, transport, screenOff);
        } else {
            HidControl.setSession(this, true, mouse, grab, keys, usb, bt);
        }
        // Remote ADB restore is once-per-Start in HidSessionService
        // (Controls desire only — never invent ON).
    }

    /** Push session/grab/transport to control files (+ FGS if live). */
    private void applySessionFlags() {
        if (!session) return;
        pushLiveSession(true);
    }

    private void toggleSession() {
        if (session) stopSession();
        else startSession();
    }

    /**
     * Side key → host action row. Tap cycles curated list (scroll, click, arrows,
     * chords). Live session re-pushes temp layer via {@link HidKeyMapSession}.
     */
    private void addSideKeyRow(LinearLayout parent, String title, String slot) {
        LinearLayout row = UiKit.row(parent);
        TextView lab = new TextView(this);
        lab.setText(title);
        lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        lab.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(lab);
        final TextView[] btn = new TextView[1];
        String cur = SideKeyHostPrefs.get(this, slot);
        btn[0] = UiKit.flexButton(row, SideKeyHostPrefs.labelOf(cur), () -> {
            String next = SideKeyHostPrefs.nextQuick(SideKeyHostPrefs.get(this, slot));
            SideKeyHostPrefs.set(this, slot, next);
            btn[0].setText(SideKeyHostPrefs.labelOf(next));
            if (session) {
                try { HidKeyMapSession.republishSideKeys(this); } catch (Exception ignored) {}
            }
        });
    }

    /** Titan Controls layout editor (specials / arrows / custom maps). */
    private void openControlsLayouts() {
        try {
            Intent i = new Intent("com.titanus2.controls.action.LAYOUTS");
            i.setPackage("com.titanus2.controls");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Exception ignored) {}
        try {
            Intent i = new Intent();
            i.setClassName("com.titanus2.controls",
                "com.titanus2.controls.layouts.CustomLayoutsActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent("com.titanus2.controls.action.KEY_MAP");
                i.setPackage("com.titanus2.controls");
                startActivity(i);
            } catch (Exception e2) {
                // Controls optional for layouts
            }
        }
    }

    /**
     * Open Controls Keys → per-app profile for {@link com.titanus2.api.Titan2ApiContract#HID_HOST_PKG}.
     * Same map the exclusive session pushes via the framework API.
     */
    private void openHidHostKeyProfile() {
        // Ensure profile exists before UI (API / local seed)
        new Thread(() -> {
            try {
                PadModeClient.api(getApplicationContext()).ensureKeymapProfile(
                    com.titanus2.api.Titan2ApiContract.HID_HOST_PKG,
                    com.titanus2.api.Titan2ApiContract.HID_HOST_LABEL);
            } catch (Exception ignored) {}
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent();
                    i.setClassName("com.titanus2.controls",
                        "com.titanus2.controls.KeyMapProfileActivity");
                    i.putExtra("package",
                        com.titanus2.api.Titan2ApiContract.HID_HOST_PKG);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    try {
                        Intent i = new Intent("com.titanus2.controls.action.KEY_MAP");
                        i.setPackage("com.titanus2.controls");
                        startActivity(i);
                    } catch (Exception ignored) {}
                }
            });
        }, "hid-open-profile").start();
    }

    private void savePrefs() {
        if (prefs == null) return;
        prefs.edit()
            .putBoolean("exclusive", exclusive)
            .putBoolean("screen_off", screenOff)
            .putInt("transport", transport)
            .putInt("typing_ms", typingMs)
            .putInt("speed_pct", speedPct)
            .putInt("type_speed_pct", typeSpeedPct)
            .putInt("accel", accel)
            .apply();
    }

    private void setTransport(int mask) {
        transport = mask & HidControl.TRANSPORT_BOTH;
        if (transport == 0) transport = HidControl.TRANSPORT_BT;
        // Drop USB only when neither hybrid stack nor Magisk/hidg is present.
        if ((transport & HidControl.TRANSPORT_USB) != 0 && !Root.usbGadgetAvailable()) {
            transport = HidControl.TRANSPORT_BT;
        }
        HidControl.setTransport(transport);
        // Write plane first so service can rebind before we touch BT UI work.
        HidControl.writeTransportEnables(this);
        savePrefs();
        if ((transport & HidControl.TRANSPORT_BT) != 0) {
            // Keep / re-arm HID profile immediately (do not wait 10s reconnect tick).
            try {
                BluetoothHidClient bt = BluetoothHidClient.get();
                bt.start(getApplicationContext());
                String pref = bt.preferredMac();
                if (pref != null && !pref.isEmpty() && !bt.isReady()) {
                    bt.selectAndConnect(getApplicationContext(), pref, bt.preferredName());
                }
            } catch (Exception ignored) {
                prepareBluetooth(false);
            }
        }
        if (session) {
            // Live plane switch without full Stop/Start UX — push flags now.
            if (screenMode == MODE_TYPE) {
                lastSoftSessionFp = ""; // force soft session rewrite
                if (typeSoftArmed) armTypeSoftInject();
                else parkTypePhysical();
            } else {
                pushLiveSession(true);
            }
        }
        refreshState();
        rebuildBtHosts();
    }

    /** Perms → enable dialog → register HID profile (optional full session). */
    private void prepareBluetooth(boolean startSessionAfter) {
        if (!hasBtPerms()) {
            maybeRequestBtPerms();
            return;
        }
        if (!hasLocationPerm()) maybeRequestLocationPerm();
        BluetoothHidClient bt = BluetoothHidClient.get();
        bt.loadPreferred(this);
        if (!bt.isAdapterOn()) {
            bt.requestEnable(this);
            return;
        }
        // Warm profile even before Start so pair/connect UI works
        bt.start(this);
        // Auto-scan once so user sees targets without hunting for Scan
        if (!bt.isScanning() && (bt.preferredMac() == null || bt.preferredMac().isEmpty())) {
            bt.startScan(this);
        }
        rebuildBtHosts();
        if (startSessionAfter) startSession();
    }

    private void onBtEnable() {
        if (!hasBtPerms()) {
            maybeRequestBtPerms();
            return;
        }
        BluetoothHidClient bt = BluetoothHidClient.get();
        if (bt.isAdapterOn()) {
            bt.start(this);
            rebuildBtHosts();
            refreshState();
            return;
        }
        bt.requestEnable(this);
    }

    private void onBtScan() {
        if (!hasBtPerms()) {
            maybeRequestBtPerms();
            return;
        }
        // Some stacks (MTK) still gate classic inquiry on location
        if (!hasLocationPerm()) {
            maybeRequestLocationPerm();
            // continue anyway after dialog; first attempt may use neverForLocation
        }
        BluetoothHidClient bt = BluetoothHidClient.get();
        if (!bt.isAdapterOn()) {
            bt.requestEnable(this);
            return;
        }
        // Warm HID profile so connect works after pick — but scan first path
        if (!bt.isRegistered()) bt.start(this);
        if (bt.isScanning()) {
            bt.stopScan();
        } else {
            bt.startScan(this);
        }
        rebuildBtHosts();
        refreshState();
    }

    private void onBtDisconnect() {
        BluetoothHidClient.get().disconnectHost();
        rebuildBtHosts();
        refreshState();
    }

    private void rebuildBtHosts() {
        if (btHostList == null) return;
        btHostList.removeAllViews();
        if ((transport & HidControl.TRANSPORT_BT) == 0) return;
        if (!hasBtPerms()) {
            TextView need = UiKit.sliderLabel(btHostList, "Allow Bluetooth permission");
            need.setTextSize(12f);
            return;
        }
        BluetoothHidClient bt = BluetoothHidClient.get();
        // Always show current target first
        String pref = bt.preferredName();
        if (pref != null && !pref.isEmpty() && !bt.preferredMac().isEmpty()) {
            String tgt = bt.isReady()
                ? "Target: " + bt.connectedName() + " (live)"
                : "Target: " + pref + " (tap list to connect)";
            TextView t = UiKit.sliderLabel(btHostList, tgt);
            t.setTextSize(12f);
            t.setTextColor(bt.isReady() ? UiKit.OK : UiKit.mutedColor(this));
        }
        java.util.List<BluetoothHidClient.HostInfo> hosts = bt.listHosts(this);
        if (hosts.isEmpty()) {
            TextView empty = UiKit.sliderLabel(btHostList,
                bt.isScanning()
                    ? "Scanning…"
                    : "No devices yet. Tap Scan (PC Bluetooth on & discoverable).");
            empty.setTextSize(12f);
            return;
        }
        // Prefer likely hosts; still show others at bottom
        for (BluetoothHidClient.HostInfo host : hosts) {
            if (!host.likelyHost && !host.preferred && !host.connected) continue;
            addHostRow(host);
        }
        boolean anyOther = false;
        for (BluetoothHidClient.HostInfo host : hosts) {
            if (host.likelyHost || host.preferred || host.connected) continue;
            if (!anyOther) {
                TextView sep = UiKit.sliderLabel(btHostList, "Other (may not accept keyboard)");
                sep.setTextSize(11f);
                anyOther = true;
            }
            addHostRow(host);
        }
    }

    private void addHostRow(BluetoothHidClient.HostInfo host) {
        final String mac = host.mac;
        final String name = host.name;
        TextView row = UiKit.button(btHostList, host.label(), () -> {
            // This is the control target — pair if needed, then HID connect
            BluetoothHidClient.get().selectAndConnect(this, mac, name);
            if (session) restartSession();
            else {
                // Warm session path without forcing Start
                BluetoothHidClient.get().start(this);
            }
            rebuildBtHosts();
            refreshState();
        });
        UiKit.setSelected(row, host.connected || host.preferred);
        if (!host.likelyHost && !host.connected) {
            row.setTextColor(UiKit.mutedColor(this));
        }
    }

    private boolean hasBtPerms() {
        if (Build.VERSION.SDK_INT < 31) {
            // Pre-12: discovery needs location on many devices
            return hasLocationPerm()
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                || Build.VERSION.SDK_INT < 23;
        }
        try {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasLocationPerm() {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    private void maybeRequestLocationPerm() {
        if (Build.VERSION.SDK_INT < 23) return;
        if (hasLocationPerm()) return;
        try {
            requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_LOC);
        } catch (Exception ignored) {}
    }

    private String btHintText() {
        BluetoothHidClient bt = BluetoothHidClient.get();
        if ((transport & HidControl.TRANSPORT_BT) == 0) return "";
        if (!hasBtPerms()) return "Bluetooth permission";
        if (!bt.isAdapterOn()) return "Bluetooth off";
        if (bt.isReady()) return bt.connectedName();
        if (bt.isScanning()) return "Scanning…";
        if (bt.preferredMac().isEmpty()) return "Scan for a host";
        return "Connecting…";
    }

    /**
     * Product “screen off HID”: keep the panel interactive (USB + keys live)
     * but black the display. True POWER blank suspends UDC/input on MTK Titan.
     * When {@code screenOff} is false, still KEEP_SCREEN_ON during session so
     * lab typing does not time out; user can disable via OS timeout if wanted.
     */
    private void applyKeepScreenFlag() {
        // Always keep screen "on" (interactive) during HID session paths.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyBlackScreenMode();
    }

    private int savedBrightness = -1;

    private void applyBlackScreenMode() {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (screenOff && session) {
                if (savedBrightness < 0) {
                    try {
                        savedBrightness = android.provider.Settings.System.getInt(
                            getContentResolver(),
                            android.provider.Settings.System.SCREEN_BRIGHTNESS);
                    } catch (Exception e) {
                        savedBrightness = 80;
                    }
                }
                lp.screenBrightness = 0.01f; // nearly black, still interactive
                getWindow().setAttributes(lp);
            } else {
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                getWindow().setAttributes(lp);
            }
        } catch (Exception ignored) {}
    }

    private String transportLabel() {
        if (transport == HidControl.TRANSPORT_BOTH) return "usb+bt";
        if (transport == HidControl.TRANSPORT_BT) return "bt";
        return "usb";
    }

    private void maybeRequestNotifPerm() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        } catch (Exception ignored) {}
    }

    private void maybeRequestBtPerms() {
        try {
            java.util.ArrayList<String> need = new java.util.ArrayList<>();
            if (Build.VERSION.SDK_INT >= 31) {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED)
                    need.add(Manifest.permission.BLUETOOTH_CONNECT);
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                        != PackageManager.PERMISSION_GRANTED)
                    need.add(Manifest.permission.BLUETOOTH_ADVERTISE);
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                        != PackageManager.PERMISSION_GRANTED)
                    need.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            // Location helps classic inquiry on MTK even with BLUETOOTH_SCAN
            if (Build.VERSION.SDK_INT >= 23 && !hasLocationPerm()) {
                need.add(Manifest.permission.ACCESS_FINE_LOCATION);
                need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
            if (!need.isEmpty()) {
                requestPermissions(need.toArray(new String[0]), REQ_BT);
            }
        } catch (Exception ignored) {}
    }

    private void restartSession() {
        typeSoftOnly = (screenMode == MODE_TYPE);
        typeSoftArmed = false;
        if (typeSoftOnly) parkTypePhysical();
        else pushLiveSession(true);
    }

    private void startSession() {
        maybeRequestNotifPerm();
        HidControl.ensureStack(this);
        Root.invalidate();

        // Honor user Link selection only — never auto-OR USB.
        boolean wantUsb = (transport & HidControl.TRANSPORT_USB) != 0;
        boolean wantBt = (transport & HidControl.TRANSPORT_BT) != 0;
        // Heal a plane left with usb=0 bt=0 from older Stop / Type park bugs.
        HidControl.setTransport(transport);
        HidControl.writeTransportEnables(this);
        if (wantUsb && !Root.usbGadgetAvailable()) {
            wantUsb = false;
            transport = wantBt ? HidControl.TRANSPORT_BT : 0;
            if (transport == 0) transport = HidControl.TRANSPORT_BT;
            wantBt = true;
            HidControl.setTransport(transport);
            savePrefs();
        }

        // BT is best-effort — never block USB Start on perms / adapter
        if (wantBt) {
            if (!hasBtPerms()) {
                maybeRequestBtPerms();
                // continue with USB
            } else {
                BluetoothHidClient btClient = BluetoothHidClient.get();
                if (!btClient.isAdapterOn()) {
                    // Request enable but do not return — USB proceeds now
                    prefs.edit().putBoolean("pending_start", false).apply();
                    try { btClient.requestEnable(this); } catch (Exception ignored) {}
                } else {
                    try { btClient.start(this); } catch (Exception ignored) {}
                }
            }
        }

        // If nothing can work, surface it
        if (!wantUsb && !wantBt) {
            transport = Root.defaultTransport();
            HidControl.setTransport(transport);
            wantUsb = (transport & HidControl.TRANSPORT_USB) != 0;
            wantBt = (transport & HidControl.TRANSPORT_BT) != 0;
        }

        if (HidSessionService.isRunning() || HidControl.isSessionOn(this)) {
            session = true;
            restartSession();
            refreshState();
            return;
        }
        session = true;
        PadGesturePrefs.publish(this);
        applyKeepScreenFlag();
        prefs.edit().putBoolean("pending_start", false).apply();
        if (!HidControl.hasPadRestore(this)) {
            String before = PadModeClient.get(this);
            if (PadModeClient.MOUSE.equals(before)) {
                HidControl.savePadRestore(this, PadModeClient.OFF);
            } else {
                HidControl.savePadRestore(this, before);
            }
        }
        KeyLedClient.ensureDefaults(this);
        KeyLedClient.bumpActivity();
        // Clear sticky local-input pause that blocked TitanKey → host
        HidControl.setLocalInputPause(this, false);
        // USB-only with no data cable: session can arm, but host gets nothing.
        if (wantUsb && !wantBt && !Root.usbHostLinked()) {
            UiKit.toast(this, "USB unplugged");
        }
        typeSoftOnly = (screenMode == MODE_TYPE);
        typeSoftArmed = false;
        if (typeSoftOnly) parkTypePhysical();
        else pushLiveSession(true);
        savePrefs();
        refreshState();
        rebuildBtHosts();
    }

    private void stopSession() {
        session = false;
        typeIoGen++; // drop any pending Type soft plane writes
        typeSoftOnly = (screenMode == MODE_TYPE);
        typeSoftArmed = false;
        if (!typeSoftOnly) HidControl.setSoftCompose(false);
        h.removeCallbacks(typeParkRunnable);
        h.removeCallbacks(typeSoftWatchdog);
        HidControl.releaseAllKeys();
        HidSessionService.stop(this);
        // Always clear session/on + phys redirect; never zero USB/BT enables —
        // those are Link prefs. Zeroing both left the plane stuck and broke
        // the next Start. Do NOT re-seed keys/mouse=1 here: sticky keys with
        // session=0 lied to bridge/Controls (B2). Next Start passes UI state.
        HidControl.endSession(this);
        HidControl.setTransport(transport);
        HidControl.writeTransportEnables(this);
        refreshState();
    }

    private void refreshState() {
        if (speedStep != null) {
            speedStep.setValue(Math.max(0, Math.min(35, (speedPct - 25) / 5)));
            speedStep.setDisplay(speedLabelText());
        }
        if (typeSpeedStep != null) {
            typeSpeedStep.setValue(Math.max(0, Math.min(75, (typeSpeedPct - 25) / 5)));
            typeSpeedStep.setDisplay(typeSpeedLabelText());
        }
        if (accelStep != null) {
            accelStep.setValue(accel);
            accelStep.setDisplay(accelLabelText());
        }
        // Compact mono state for title bar
        String mode = screenMode == MODE_SETTINGS ? "set"
            : screenMode == MODE_KEYS ? "keys"
            : screenMode == MODE_TYPE ? "type" : "pad";
        String so = screenOff ? " · so" : "";
        String live;
        if (!session) live = "off";
        else if (typeSoftOnly && typeSoftArmed) live = "type+";
        else if (typeSoftOnly) live = "type";
        else live = "on";
        String route = typeSoftOnly ? "soft"
            : (exclusive ? "excl" : "share");
        String btBit = "";
        if ((transport & HidControl.TRANSPORT_BT) != 0) {
            btBit = BluetoothHidClient.get().isReady() ? " · bt:live" : " · bt:…";
        }
        String pause = "";
        if (session && !typeSoftOnly && HidControl.isLocalInputPaused(this)) {
            pause = " · local";
        }
        // USB host link — without cable, session "on" still sends nothing.
        // Skip probe on Type tab (session-on open path must stay snappy).
        String usbBit = "";
        boolean wantUsb = (transport & HidControl.TRANSPORT_USB) != 0;
        boolean hostOk = true;
        if (wantUsb && screenMode != MODE_TYPE) {
            hostOk = Root.usbHostLinked();
            if (session && !hostOk) usbBit = " · usb off";
            else if (session && hostOk) usbBit = " · usb";
        }
        // Dense facts only — no stack/ADB essays in the title chip
        state.setText(live
            + " · " + transportLabel()
            + " · " + route
            + btBit
            + usbBit
            + pause
            + so
            + " · " + mode);
        // Red when session claims USB but host is not linked
        if (session && wantUsb && !hostOk) {
            state.setTextColor(UiKit.WARN);
        } else {
            state.setTextColor(session ? UiKit.OK : UiKit.mutedColor(this));
        }
        if (sessBtn != null) {
            sessBtn.setText(session ? "Stop" : "Start");
            UiKit.setSelected(sessBtn, session);
        }
        if (bExclusive != null) UiKit.setSelected(bExclusive, exclusive);
        if (bShare != null) UiKit.setSelected(bShare, !exclusive);
        if (bUsb != null) UiKit.setSelected(bUsb, transport == HidControl.TRANSPORT_USB);
        if (bBt != null) UiKit.setSelected(bBt, transport == HidControl.TRANSPORT_BT);
        if (bBoth != null) UiKit.setSelected(bBoth, transport == HidControl.TRANSPORT_BOTH);
        if (screenOffToggle != null) screenOffToggle.setChecked(screenOff);
        boolean wantBt = (transport & HidControl.TRANSPORT_BT) != 0;
        if (btPanel != null) btPanel.setVisibility(wantBt ? View.VISIBLE : View.GONE);
        if (btStatusLbl != null) {
            if (wantBt) {
                String st = BluetoothHidClient.get().status();
                if (BluetoothHidClient.get().isReady()) {
                    st = "connected " + BluetoothHidClient.get().connectedName();
                }
                btStatusLbl.setText(st);
                btStatusLbl.setTextColor(
                    BluetoothHidClient.get().isReady() ? UiKit.OK : UiKit.mutedColor(this));
            } else {
                btStatusLbl.setText("BT off");
            }
        }
        if (blockTiles != null) {
            for (int i = 0; i < blockTiles.length; i++)
                UiKit.setSelected(blockTiles[i], typingMs == BLOCK_MS[i]);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BluetoothHidClient.REQ_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                BluetoothHidClient.get().start(this);
                rebuildBtHosts();
                if (prefs.getBoolean("pending_start", false)) {
                    prefs.edit().putBoolean("pending_start", false).apply();
                    startSession();
                }
            } else {
                BluetoothHidClient.get().openBluetoothSettings(this);
            }
            refreshState();
        } else if (requestCode == BluetoothHidClient.REQ_DISCOVERABLE) {
            // RESULT_OK or discoverable duration seconds
            BluetoothHidClient.get().start(this);
            rebuildBtHosts();
            refreshState();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT || requestCode == REQ_LOC) {
            if (hasBtPerms()) {
                prepareBluetooth(prefs.getBoolean("pending_start", false));
            }
            // Auto-retry scan once perms granted
            if (hasBtPerms() && (transport & HidControl.TRANSPORT_BT) != 0) {
                BluetoothHidClient bt = BluetoothHidClient.get();
                if (bt.isAdapterOn() && !bt.isScanning()) {
                    bt.startScan(this);
                }
            }
            refreshState();
            rebuildBtHosts();
        }
    }

    @Override
    public void onBackPressed() {
        if (screenMode == MODE_SETTINGS) {
            setMode(lastMainMode);
            return;
        }
        if (session) {
            moveTaskToBack(true);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyKeepScreenFlag();
        session = HidSessionService.isRunning() || HidControl.isSessionOn(this);
        if (PadModeClient.isFollowOrient(this)) {
            PadModeClient.publishRotation(this);
        }
        if ((transport & HidControl.TRANSPORT_BT) != 0 && hasBtPerms()) {
            // Keep profile warm while settings open
            BluetoothHidClient bt = BluetoothHidClient.get();
            if (session || bt.isWantRunning() || bt.isAdapterOn()) {
                if (session || bt.isWantRunning()) bt.start(this);
            }
            rebuildBtHosts();
        }
        h.post(tick);
        refreshState();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (PadModeClient.isFollowOrient(this)) {
            PadModeClient.publishRotation(this);
        }
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        BluetoothHidClient.get().removeListener(btListener);
        // B2 1.32: UI gone without FGS — clear Type softCompose and idle phys plane
        // so Controls heal does not see ghost exclusive after force-stop / crash.
        try { HidControl.setSoftCompose(false); } catch (Exception ignored) {}
        if (!HidSessionService.isRunning()) {
            try {
                if (session) {
                    HidControl.endSessionAndRestore(this);
                } else {
                    HidControl.parkSession(this);
                }
            } catch (Exception ignored) {}
            session = false;
        }
        super.onDestroy();
    }
}
