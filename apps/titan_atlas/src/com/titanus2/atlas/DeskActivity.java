package com.titanus2.atlas;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Debian graphic session.
 * Keyboard scan codes go to the seat. The mouse is captured the way
 * Moonlight captures it: the Android pointer is taken, and only the
 * desk pointer moves. The glass is still an absolute finger. Two fingers scroll.
 */
public class DeskActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService input = Executors.newSingleThreadExecutor();
    /** Controls binder. The desk pump owns {@code io}, so this must be its own thread. */
    private final ExecutorService binder = Executors.newSingleThreadExecutor();
    private final AtomicBoolean run = new AtomicBoolean(true);
    private FrameLayout wrap;
    private LinearLayout chrome;
    private TextView status;
    private Button fullBtn;
    private Button restartBtn;
    private FrameLayout orbDock;
    private View orbBall;
    private Button orbExit;
    private Button orbRestart;
    private Button orbKeys;
    private Button orbBack;
    private LinearLayout orbPanel;
    private LinearLayout orbCluster;
    private LinearLayout ringTop;
    private LinearLayout ringLeft;
    private LinearLayout ringRight;
    private LinearLayout ringBottom;
    private Button keysBtn;
    private Button backBtn;
    private boolean orbOpen;
    private boolean orbParked;
    private boolean orbHeld;
    private boolean orbOnBall;
    private boolean orbDragging;
    private float orbDownX;
    private float orbDownY;
    private float orbStartX;
    private float orbStartY;
    private int chromeDown = -1;
    private volatile boolean restarting;
    private ExtraKeysView keys;
    private final DeskSoftKeys softKeys = new DeskSoftKeys(this);
    private View deskRoot;
    private ImageView image;
    private Bitmap bitmap;
    private int[] pixels = new int[64 * 64];
    /** One reused frame. A queued show holds it; the pump does not allocate another. */
    private int[] frameBuf;
    private volatile boolean frameQueued;
    private int[] meta = new int[4];
    private boolean full;
    private int buttons;
    private float lastTouchX = -1f;
    private float lastTouchY = -1f;
    private float lastPinchY = -1f;
    private volatile boolean readKeys;
    private volatile boolean physAlt;
    private volatile boolean physSym;
    private Process keyProc;
    private Process padProc;
    private long homeDownAt;
    private boolean leaveRegistered;
    private static final String DESK_FOCUS = "/data/local/tmp/atlas-virgl/desk-focus";
    private static final String DESK_PAD = "/data/local/tmp/atlas-virgl/desk-pad";
    /** Keyboard grab. Stops when the window loses focus so Home still works. */
    private final Runnable focusTick = new Runnable() {
        @Override public void run() {
            if (!hasWindowFocus()) return;
            writeFlag(DESK_FOCUS, "desk-focus.next", true);
            main.postDelayed(this, 2000);
        }
    };
    /** Trackpad grab. Stays while this activity is resumed, even if window
     *  focus has not arrived yet. */
    private final Runnable padTick = new Runnable() {
        @Override public void run() {
            if (isFinishing()) return;
            writeFlag(DESK_PAD, "desk-pad.next", true);
            main.postDelayed(this, 2000);
        }
    };
    private final BroadcastReceiver deskLeave = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            leaveDesk();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        int barBg = AtlasPrefs.bgColor(this);
        status = new TextView(this);
        status.setTextColor(AtlasUi.chromeOnTerm(this));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTypeface(Typeface.MONOSPACE);
        status.setSingleLine(true);
        status.setMinHeight(dp(28));
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(6), dp(2), dp(6), dp(2));
        status.setBackgroundColor(barBg);
        status.setText("starting desk…");
        root.addView(status, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        chrome = new LinearLayout(this);
        chrome.setOrientation(LinearLayout.HORIZONTAL);
        chrome.setGravity(Gravity.CENTER_VERTICAL);
        chrome.setBackgroundColor(barBg);
        chrome.setPadding(dp(2), 0, dp(2), dp(2));
        backBtn = AtlasUi.chromeButton(this, "Back", v -> leaveDesk());
        fullBtn = AtlasUi.chromeButton(this, "Full screen", v -> setFull(!full));
        restartBtn = AtlasUi.chromeButton(this, "Restart", v -> reloadView());
        keysBtn = AtlasUi.chromeButton(this, "Keys", v -> toggleExtraKeys());
        chrome.addView(backBtn, AtlasUi.chromeSlot(this));
        chrome.addView(fullBtn, AtlasUi.chromeSlot(this));
        chrome.addView(restartBtn, AtlasUi.chromeSlot(this));
        chrome.addView(keysBtn, AtlasUi.chromeSlot(this));
        root.addView(chrome, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        image = new ImageView(this);
        /* Uniform scale. FIT_XY smeared the square glass into a wide picture. */
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(0xFF000000);
        root.addView(image, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        keys = new ExtraKeysView(this, new ExtraKeysView.Listener() {
            @Override public void onExtraKey(String key) {
                if ("RST".equals(key)) {
                    reloadView();
                    return;
                }
                softKeys.handle(key, input);
                if (keys != null) keys.refreshModifiers();
            }
            @Override public boolean isCtrlOn() { return softKeys.isCtrl(); }
            @Override public boolean isAltOn() { return softKeys.isAlt(); }
            @Override public boolean isShiftOn() { return softKeys.isShift(); }
            @Override public boolean isMetaOn() { return softKeys.isMeta(); }
            @Override public boolean isCapsOn() { return softKeys.isCaps(); }
        });
        root.addView(keys, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        keys.setVisibility(AtlasPrefs.extraKeysOpen(this)
            ? View.VISIBLE : View.GONE);
        wrap = new FrameLayout(this);
        wrap.addView(root);
        wrap.setFitsSystemWindows(true);
        buildOrb();
        setContentView(wrap);
        View content = findViewById(android.R.id.content);
        View.OnApplyWindowInsetsListener edge = (v, insets) -> {
            applyBarPadding();
            return full ? WindowInsets.CONSUMED : insets;
        };
        if (content != null) content.setOnApplyWindowInsetsListener(edge);
        wrap.setOnApplyWindowInsetsListener(edge);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(true);
        }
        root.requestFocus();
        deskRoot = root;
        setFull(false);
        wrap.post(this::parkOrb);
        image.post(this::fitDeskToView);

        if (!DeskClient.load(this)) {
            status.setText("viewer missing — rebuild Atlas");
            return;
        }
        io.execute(() -> {
            DeskSideKeys.ensureScrollDefault(DeskActivity.this);
            String started = DeskSession.start(this);
            if (started != null && !started.contains("STARTED")
                    && !started.contains("restarting")) {
                main.post(() -> {
                    if (status != null) status.setText(oneLine(started));
                });
            }
            pump();
        });
    }

    private void toggleExtraKeys() {
        boolean on = !AtlasPrefs.extraKeysOpen(this);
        AtlasPrefs.setExtraKeysOpen(this, on);
        if (full) {
            /* Keys stay on the glass. They do not drop the desktop out of full screen. */
            if (keys != null) keys.setVisibility(View.GONE);
            if (on && !orbOpen) setOrbOpen(true);
            else {
                showRing(on);
                anchorGrip();
            }
            return;
        }
        showRing(false);
        if (keys != null) {
            keys.mountBottom();
            keys.setVisibility(on ? View.VISIBLE : View.GONE);
        }
    }

    private void setFull(boolean on) {
        full = on;
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attrs = w.getAttributes();
            attrs.layoutInDisplayCutoutMode = on
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            w.setAttributes(attrs);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(!on);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (on) c.hide(WindowInsets.Type.systemBars());
                else c.show(WindowInsets.Type.systemBars());
            }
        }
        if (on) {
            w.setStatusBarColor(0x00000000);
            w.setNavigationBarColor(0x00000000);
            w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            w.setStatusBarColor(0xFF000000);
            w.setNavigationBarColor(0xFF000000);
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            w.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        }
        if (wrap != null) wrap.setFitsSystemWindows(!on);
        applyBarPadding();
        chrome.setVisibility(on ? View.GONE : View.VISIBLE);
        if (status != null) status.setVisibility(on ? View.GONE : View.VISIBLE);
        if (keys != null) {
            if (on) {
                keys.setVisibility(View.GONE);
            } else {
                showRing(false);
                keys.mountBottom();
                keys.setVisibility(AtlasPrefs.extraKeysOpen(this)
                    ? View.VISIBLE : View.GONE);
            }
        }
        if (orbDock != null) {
            orbDock.setVisibility(on ? View.VISIBLE : View.GONE);
            if (!on) orbParked = false;
            /* Collapsed until the ball is tapped, unless Keys is already open. */
            if (on && AtlasPrefs.extraKeysOpen(this)) setOrbOpen(true);
            else setOrbOpen(false);
        }
        fullBtn.setText("Full screen");
        if (wrap != null) wrap.post(this::applyBarPadding);
        if (image != null) image.post(this::fitDeskToView);
    }

    /** Windowed content stays between the status bar and the navigation bar. */
    private void applyBarPadding() {
        if (wrap == null) return;
        View content = findViewById(android.R.id.content);
        if (full) {
            wrap.setPadding(0, 0, 0, 0);
            if (content != null) content.setPadding(0, 0, 0, 0);
            return;
        }
        int top = 0, bottom = 0, left = 0, right = 0;
        WindowInsets insets = wrap.getRootWindowInsets();
        if (insets != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets b = insets.getInsets(WindowInsets.Type.systemBars());
                top = b.top;
                bottom = b.bottom;
                left = b.left;
                right = b.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
        }
        if (top <= 0) top = barPx("status_bar_height", 84);
        if (bottom <= 0) bottom = barPx("navigation_bar_height", 84);
        wrap.setPadding(left, top, right, bottom);
        if (content != null) content.setPadding(0, 0, 0, 0);
    }

    private int barPx(String name, int fallback) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        if (id <= 0) return fallback;
        int v = getResources().getDimensionPixelSize(id);
        return v > 0 ? v : fallback;
    }

    private int fittedW;
    private int fittedH;

    /** Full screen is the glass. Do not shrink the pointer range to the inset hole. */
    private void fitDeskToView() {
        if (!full) return;
        int w = 1440;
        int h = 1440;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect bounds = getWindowManager().getMaximumWindowMetrics().getBounds();
            if (bounds.width() >= 640 && bounds.height() >= 480) {
                w = bounds.width();
                h = bounds.height();
            }
        }
        if (w == fittedW && h == fittedH) return;
        fittedW = w;
        fittedH = h;
        final int fw = w;
        final int fh = h;
        new Thread(() -> {
            try {
                java.nio.file.Files.write(
                    java.nio.file.Path.of("/data/local/atlas-linux/home/atlas/atlas-x/size"),
                    (fw + " " + fh + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            } catch (Exception ignored) {}
        }, "desk-size").start();
    }

    /** Full-screen panel. Keys open around it. The glass stays full screen. */
    private void buildOrb() {
        orbDock = new FrameLayout(this);
        orbDock.setVisibility(View.GONE);
        TextView handle = new TextView(this);
        handle.setText("☰");
        handle.setGravity(Gravity.CENTER);
        handle.setTextColor(AtlasUi.chromeOnTerm(this));
        orbBall = handle;
        orbBack = orbAction("Back", v -> leaveDesk());
        orbExit = orbAction("Exit", v -> setFull(false));
        orbRestart = orbAction("Restart", v -> reloadView());
        orbKeys = orbAction("Keys", v -> toggleExtraKeys());
        ringTop = ringBox();
        ringLeft = ringBox();
        ringRight = ringBox();
        ringBottom = ringBox();
        orbPanel = new LinearLayout(this);
        orbPanel.setGravity(Gravity.CENTER);
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setGravity(Gravity.CENTER);
        mid.addView(ringLeft);
        mid.addView(orbPanel);
        mid.addView(ringRight);
        orbCluster = new LinearLayout(this);
        orbCluster.setOrientation(LinearLayout.VERTICAL);
        orbCluster.setGravity(Gravity.CENTER);
        orbCluster.addView(ringTop);
        orbCluster.addView(mid);
        orbCluster.addView(ringBottom);
        orbCluster.setVisibility(View.GONE);
        orbDock.addView(orbCluster, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER));
        orbDock.addView(orbBall, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        wrap.addView(orbDock, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.START));
        applyFloatChrome();
    }

    private LinearLayout ringBox() {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER);
        box.setVisibility(View.GONE);
        return box;
    }

    private Button orbAction(String label, View.OnClickListener click) {
        Button b = AtlasUi.chromeButton(this, label, click);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    /** Restyle the floating panel from Settings. Safe when the desk is not full screen. */
    private void applyFloatChrome() {
        if (orbPanel == null) return;
        int size = AtlasPrefs.orbSize(this);
        int opacity = AtlasPrefs.orbOpacity(this);
        int alpha = opacity * 255 / 100;
        int bg = AtlasPrefs.bgColor(this);
        int cell = Math.max(dp(32), dp(40) * size / 100);
        GradientDrawable sheet = new GradientDrawable();
        sheet.setColor(Color.argb(alpha, Color.red(bg), Color.green(bg), Color.blue(bg)));
        orbPanel.setBackground(sheet);
        orbPanel.setPadding(dp(2), dp(2), dp(2), dp(2));
        for (Button b : new Button[] { orbBack, orbExit, orbRestart, orbKeys }) {
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(10f, 12f * size / 100f));
            b.setMinimumHeight(cell);
            b.setMinHeight(cell);
        }
        int ball = Math.max(dp(44), dp(56) * size / 100);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.argb(alpha, Color.red(bg), Color.green(bg), Color.blue(bg)));
        circle.setStroke(dp(2), AtlasUi.chromeOnTerm(this));
        orbBall.setBackground(circle);
        if (orbBall instanceof TextView) {
            ((TextView) orbBall).setTextSize(TypedValue.COMPLEX_UNIT_SP,
                Math.max(14f, 18f * size / 100f));
        }
        ViewGroup.LayoutParams blp = orbBall.getLayoutParams();
        if (blp != null) {
            blp.width = ball;
            blp.height = ball;
            orbBall.setLayoutParams(blp);
        }
        layoutOrbPanel(cell);
        if (orbOpen) {
            orbCluster.setVisibility(View.VISIBLE);
            if (full && AtlasPrefs.extraKeysOpen(this)) showRing(true);
        } else {
            collapseGrip();
        }
    }

    private void layoutOrbPanel(int cell) {
        String style = AtlasPrefs.orbStyle(this);
        orbPanel.removeAllViews();
        detach(orbBall);
        if ("bar".equals(style)) {
            orbPanel.setOrientation(LinearLayout.HORIZONTAL);
            addPanel(orbBall, cell, cell);
            addPanel(orbBack, LinearLayout.LayoutParams.WRAP_CONTENT, cell);
            addPanel(orbExit, LinearLayout.LayoutParams.WRAP_CONTENT, cell);
            addPanel(orbRestart, LinearLayout.LayoutParams.WRAP_CONTENT, cell);
            addPanel(orbKeys, LinearLayout.LayoutParams.WRAP_CONTENT, cell);
            return;
        }
        if ("stack".equals(style)) {
            orbPanel.setOrientation(LinearLayout.VERTICAL);
            addPanel(orbBall, cell, cell);
            addPanel(orbBack, LinearLayout.LayoutParams.MATCH_PARENT, cell);
            addPanel(orbExit, LinearLayout.LayoutParams.MATCH_PARENT, cell);
            addPanel(orbRestart, LinearLayout.LayoutParams.MATCH_PARENT, cell);
            addPanel(orbKeys, LinearLayout.LayoutParams.MATCH_PARENT, cell);
            return;
        }
        orbPanel.setOrientation(LinearLayout.VERTICAL);
        addPanel(orbBack, LinearLayout.LayoutParams.WRAP_CONTENT, cell);
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setGravity(Gravity.CENTER);
        addInto(mid, orbKeys, LinearLayout.LayoutParams.WRAP_CONTENT, cell);
        addInto(mid, orbBall, cell, cell);
        addInto(mid, orbExit, LinearLayout.LayoutParams.WRAP_CONTENT, cell);
        orbPanel.addView(mid, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addPanel(orbRestart, LinearLayout.LayoutParams.WRAP_CONTENT, cell);
    }

    private void addPanel(View v, int w, int h) {
        addInto(orbPanel, v, w, h);
    }

    private void addInto(LinearLayout host, View v, int w, int h) {
        detach(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        int m = dp(1);
        lp.setMargins(m, m, m, m);
        host.addView(v, lp);
    }

    private void detach(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) {
            ((ViewGroup) v.getParent()).removeView(v);
        }
    }

    private int cell() {
        return Math.max(dp(32), dp(40) * AtlasPrefs.orbSize(this) / 100);
    }

    private void setOrbOpen(boolean on) {
        orbOpen = on;
        if (orbDock == null) return;
        if (on) {
            layoutOrbPanel(cell());
            orbCluster.setVisibility(View.VISIBLE);
            showRing(AtlasPrefs.extraKeysOpen(this));
        } else {
            showRing(false);
            collapseGrip();
        }
        anchorGrip();
    }

    /** Only the round handle. The panel and the keys are hidden. */
    private void collapseGrip() {
        if (orbCluster != null) orbCluster.setVisibility(View.GONE);
        if (orbBall == null || orbDock == null) return;
        detach(orbBall);
        int ball = Math.max(dp(44), dp(56) * AtlasPrefs.orbSize(this) / 100);
        orbDock.addView(orbBall, new FrameLayout.LayoutParams(ball, ball, Gravity.CENTER));
    }

    private void showRing(boolean on) {
        if (ringTop == null) return;
        if (on && full && keys != null) {
            keys.mountRing(ringTop, ringLeft, ringRight, ringBottom,
                cell(), AtlasPrefs.orbOpacity(this));
        }
        int vis = on && full ? View.VISIBLE : View.GONE;
        ringTop.setVisibility(vis);
        ringLeft.setVisibility(vis);
        ringRight.setVisibility(vis);
        ringBottom.setVisibility(vis);
    }

    /** Keep the handle where the finger left it when the panel grows. */
    private void anchorGrip() {
        if (orbDock == null || orbBall == null) return;
        if (!orbParked) {
            if (orbDock.getVisibility() == View.VISIBLE)
                orbDock.post(() -> orbDock.post(this::parkOrb));
            return;
        }
        int[] before = new int[2];
        orbBall.getLocationOnScreen(before);
        final int cx = before[0] + Math.max(orbBall.getWidth(), 1) / 2;
        final int cy = before[1] + Math.max(orbBall.getHeight(), 1) / 2;
        orbDock.post(() -> orbDock.post(() -> {
            int[] now = new int[2];
            orbBall.getLocationOnScreen(now);
            float dx = cx - (now[0] + orbBall.getWidth() / 2f);
            float dy = cy - (now[1] + orbBall.getHeight() / 2f);
            placeOrb(orbDock.getX() + dx, orbDock.getY() + dy);
        }));
    }

    private void parkOrb() {
        if (wrap == null || orbDock == null) return;
        if (orbDock.getVisibility() != View.VISIBLE) return;
        int w = wrap.getWidth();
        if (w <= 0) return;
        orbParked = true;
        int dockW = orbDock.getWidth();
        if (dockW <= 0) dockW = dp(220);
        int top = dp(72);
        WindowInsets insets = wrap.getRootWindowInsets();
        if (insets != null && Build.VERSION.SDK_INT >= 29) {
            top = Math.max(top, insets.getSystemGestureInsets().top + dp(12));
        }
        placeOrb(w - dockW - dp(12), top);
    }

    private void placeOrb(float x, float y) {
        if (wrap == null || orbDock == null) return;
        int pw = wrap.getWidth();
        int ph = wrap.getHeight();
        int w = orbDock.getWidth();
        int h = orbDock.getHeight();
        if (w <= 0) w = dp(56);
        if (h <= 0) h = w;
        float maxX = Math.max(0f, pw - w);
        float maxY = Math.max(0f, ph - h);
        if (x < 0f) x = 0f;
        if (y < 0f) y = 0f;
        if (x > maxX) x = maxX;
        if (y > maxY) y = maxY;
        orbDock.setX(x);
        orbDock.setY(y);
    }

    /** Drag the handle. Button and key presses are delivered to the views. */
    private boolean orbTouch(MotionEvent e) {
        if (!full || orbDock == null || orbDock.getVisibility() != View.VISIBLE) return false;
        if (hitFloatButton(e)) return false;
        int act = e.getActionMasked();
        boolean onBall = hitView(orbBall, e);
        boolean onDock = onBall || hitView(orbDock, e);
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN) {
            if (!onDock) return false;
            orbHeld = true;
            orbDragging = false;
            orbOnBall = onBall;
            orbDownX = e.getRawX();
            orbDownY = e.getRawY();
            orbStartX = orbDock.getX();
            orbStartY = orbDock.getY();
            return true;
        }
        if (!orbHeld) return false;
        if (act == MotionEvent.ACTION_MOVE) {
            float dx = e.getRawX() - orbDownX;
            float dy = e.getRawY() - orbDownY;
            int dragSlop = dp(8);
            if (!orbDragging && dx * dx + dy * dy > (float) dragSlop * dragSlop) orbDragging = true;
            if (orbDragging && orbOnBall) placeOrb(orbStartX + dx, orbStartY + dy);
            return true;
        }
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            boolean drag = orbDragging;
            boolean ball = orbOnBall;
            orbHeld = false;
            orbDragging = false;
            orbOnBall = false;
            if (act == MotionEvent.ACTION_CANCEL || drag) return true;
            if (ball) {
                Log.i("AtlasDesk", orbOpen ? "orb close" : "orb open");
                setOrbOpen(!orbOpen);
            }
            return true;
        }
        return orbHeld;
    }

    private boolean hitFloatButton(MotionEvent e) {
        if (!full) return false;
        for (Button b : new Button[] { orbBack, orbExit, orbRestart, orbKeys }) {
            if (hitView(b, e)) return true;
        }
        if (keys != null && keys.isRing()) {
            for (Button b : keys.buttons()) {
                if (hitView(b, e)) return true;
            }
        }
        return false;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Returns true when this event was a press on a desk control. */
    private boolean chromePress(MotionEvent e) {
        int act = e.getActionMasked();
        Button[] row = { backBtn, fullBtn, restartBtn, keysBtn };
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN) {
            chromeDown = -1;
            for (int i = 0; i < row.length; i++) {
                if (hitView(row[i], e)) {
                    chromeDown = i;
                    return true;
                }
            }
            return false;
        }
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_POINTER_UP) {
            int i = chromeDown;
            chromeDown = -1;
            if (i < 0 || i >= row.length) return false;
            Button b = row[i];
            if (!hitView(b, e)) return true;
            Log.i("AtlasDesk", "chrome " + b.getText());
            b.performClick();
            return true;
        }
        if (act == MotionEvent.ACTION_CANCEL) {
            chromeDown = -1;
            return false;
        }
        return chromeDown >= 0;
    }

    private boolean hitView(View v, MotionEvent e) {
        return hitView(v, e, 0);
    }

    /**
     * A GONE parent keeps its child's old rectangle. Full screen hides the
     * windowed bar that way, and a tap on Exit was landing on Side.
     */
    private boolean hitView(View v, MotionEvent e, int slop) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        if (v.getWidth() <= 0 || v.getHeight() <= 0) return false;
        for (android.view.ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
            if (((View) p).getVisibility() != View.VISIBLE) return false;
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float x = e.getRawX();
        float y = e.getRawY();
        return x >= loc[0] - slop && x < loc[0] + v.getWidth() + slop
            && y >= loc[1] - slop && y < loc[1] + v.getHeight() + slop;
    }

    /** Stop Plasma and KWin, then start that session again. The glass stays up. */
    private void reloadView() {
        if (restarting) return;
        restarting = true;
        if (status != null) status.setText("restarting session…");
        new Thread(() -> {
            String msg = DeskSession.restart(DeskActivity.this);
            main.post(() -> {
                restarting = false;
                if (status != null && msg != null && !msg.contains("restarting")) {
                    status.setText(oneLine(msg));
                }
            });
        }, "desk-restart").start();
    }

    private void pump() {
        String sock = DeskSession.presentSock(this);
        while (run.get()) {
            int rc = DeskClient.pull(sock, pixels, meta);
            if (rc == 1) {
                int w = Math.max(1, meta[0]);
                int h = Math.max(1, meta[1]);
                pixels = new int[w * h];
                continue;
            }
            if (rc == 0 && !frameQueued) {
                int w = meta[0];
                int h = meta[1];
                int gen = meta[2];
                long need = (long) w * (long) h;
                if (w >= 1 && h >= 1 && w <= 2160 && h <= 2160 && need <= 2160L * 2160L
                        && pixels.length >= need) {
                    if (frameBuf == null || frameBuf.length < need) {
                        frameBuf = new int[(int) need];
                    }
                    System.arraycopy(pixels, 0, frameBuf, 0, (int) need);
                    frameQueued = true;
                    main.post(() -> {
                        try {
                            show(frameBuf, w, h, gen);
                        } finally {
                            frameQueued = false;
                        }
                    });
                }
            }
            try {
                /* A repeated frame comes back without a copy. Keep the poll short
                 * so a new frame is still picked up promptly. */
                Thread.sleep(16L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void show(int[] px, int w, int h, int gen) {
        if (isFinishing() || isDestroyed()) return;
        try {
            if (bitmap == null || bitmap.isRecycled()
                    || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                Bitmap next = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Bitmap old = bitmap;
                bitmap = next;
                image.setImageBitmap(next);
                if (old != null && !old.isRecycled()) old.recycle();
            }
            if (bitmap == null || bitmap.isRecycled()) return;
            bitmap.setPixels(px, 0, w, 0, 0, w, h);
            image.invalidate();
        } catch (RuntimeException ignored) {
            return;
        }
        if (!full && !restarting) {
            status.setText(w + "×" + h + "  gen " + gen
                + (AtlasPrefs.deskCompose(this) ? "  gpu" : "  flat"));
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        /* Full-screen chrome sits on top of the picture. Give that strip to
         * the real buttons before the glass or the trackpad eats the tap. */
        if (hitFloatButton(e)) return super.dispatchTouchEvent(e);
        if (orbTouch(e)) return true;
        /* The key panel is touchscreen too. Without this, every tap is
         * sent to the glass and the keys never receive it. */
        if (keys != null && keys.getVisibility() == View.VISIBLE && hitView(keys, e)) {
            return super.dispatchTouchEvent(e);
        }
        /* A finger on the bar must hit the buttons. The glass path used to
         * eat that tap, which is why Side and Restart did nothing. */
        if ((isGlass(e) || isPad(e)) && chromePress(e)) return true;
        if (isPad(e)) {
            onPad(e);
            return true;
        }
        if (isGlass(e)) {
            if (mapGlass(e) == null) return super.dispatchTouchEvent(e);
            onGlass(e);
            return true;
        }
        return super.dispatchTouchEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (isPad(e)) {
            /* The virtual mouse is grabbed and fed to Debian. If that grab
             * missed, fall back to the Android motion event. */
            onPad(e);
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private static void writePtrFile(int x, int y) {
        try {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(8)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            buf.putInt(x);
            buf.putInt(y);
            Files.write(java.nio.file.Path.of("/data/local/tmp/atlas-virgl/desk-ptr"), buf.array());
        } catch (Exception ignored) {
        }
    }

    private void startPadReader() {
        stopPadReader();
        try {
            NativeBin.ensureExtracted(this);
        } catch (Exception e) {
            Log.w("AtlasDesk", "pad extract: " + e.getMessage());
        }
        File dir = new File("/data/local/tmp/atlas-virgl");
        File bin = new File(dir, "atlas-desk-pad");
        File src = new File(NativeBin.binDir(this), "atlas-desk-pad");
        try {
            if (!dir.isDirectory()) dir.mkdirs();
            if (src.isFile() && src.length() > 0) {
                try {
                    Files.copy(src.toPath(), bin.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception copyErr) {
                    Log.w("AtlasDesk", "pad copy: " + copyErr.getMessage());
                }
            }
            if (!bin.isFile()) {
                Log.w("AtlasDesk", "pad binary missing");
                return;
            }
            bin.setReadable(true, false);
            bin.setExecutable(true, false);
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), DeskSession.inputSock(this));
            pb.redirectErrorStream(true);
            pb.redirectOutput(new File(dir, "pad.log"));
            padProc = pb.start();
            Log.i("AtlasDesk", "pad " + bin.length());
        } catch (Exception e) {
            Log.w("AtlasDesk", "pad: " + e.getMessage());
        }
    }

    private void stopPadReader() {
        Process p = padProc;
        padProc = null;
        if (p != null) p.destroyForcibly();
    }

    /** 1 while this window is focused. The keyboard reader uses this file. */
    private void writeDeskFocus(boolean on) {
        writeFlag(DESK_FOCUS, "desk-focus.next", on);
    }

    /** 1 while this activity is resumed. The trackpad reader uses this file. */
    private void writePadHold(boolean on) {
        writeFlag(DESK_PAD, "desk-pad.next", on);
    }

    private void writeFlag(String path, String tmpName, boolean on) {
        try {
            File dir = new File("/data/local/tmp/atlas-virgl");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File f = new File(path);
            File next = new File(dir, tmpName);
            Files.write(next.toPath(), (on ? "1\n" : "0\n").getBytes());
            next.setReadable(true, false);
            next.setWritable(true, false);
            try {
                Files.move(next.toPath(), f.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomic) {
                Files.move(next.toPath(), f.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Log.w("AtlasDesk", "focus: " + e.getMessage());
        }
    }

    /** Trackpad stays for the whole time the desk is open. */
    private void setPadHeld(boolean on) {
        main.removeCallbacks(padTick);
        writePadHold(on);
        if (!on) return;
        main.postDelayed(padTick, 2000);
        if (!alive(padProc)) startPadReader();
    }

    private static boolean alive(Process p) {
        return p != null && p.isAlive();
    }

    /**
     * Focus loss only clears the file. The same readers stay up and let go
     * of the devices, then take them again when the desk is focused.
     */
    private void setDeskFocused(boolean on) {
        main.removeCallbacks(focusTick);
        writeDeskFocus(on);
        if (!on) return;
        main.postDelayed(focusTick, 2000);
        if (!alive(keyProc)) startKeyReader();
        if (!alive(padProc)) startPadReader();
    }

    /** Keyboard touchpad and the virtual mouse titan2-touchpadd injects. */
    private static boolean isPad(MotionEvent e) {
        int s = e.getSource();
        return (s & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
            || (s & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD
            || (s & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE;
    }

    private static boolean isGlass(MotionEvent e) {
        int s = e.getSource();
        return (s & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
            || (s & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS;
    }

    private void onPad(MotionEvent e) {
        float dx = e.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
        float dy = e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
        int hist = e.getHistorySize();
        for (int i = 0; i < hist; i++) {
            dx += e.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, i);
            dy += e.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, i);
        }
        int btn = buttonMask(e);
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_BUTTON_PRESS || act == MotionEvent.ACTION_DOWN) {
            btn = buttonMask(e);
            if (btn == 0 && act == MotionEvent.ACTION_DOWN) btn = 1;
        }
        if (act == MotionEvent.ACTION_BUTTON_RELEASE || act == MotionEvent.ACTION_UP
            || act == MotionEvent.ACTION_CANCEL) {
            if (act != MotionEvent.ACTION_BUTTON_RELEASE) btn = 0;
        }
        float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
        float h = e.getAxisValue(MotionEvent.AXIS_HSCROLL);
        int wheel = 0;
        if (v != 0f) wheel = v > 0f ? -1 : 1;
        else if (h != 0f) wheel = h > 0f ? -1 : 1;
        if (e.getPointerCount() >= 2) {
            float y = (e.getY(0) + e.getY(1)) * 0.5f;
            if (lastPinchY >= 0f) {
                float d = y - lastPinchY;
                if (Math.abs(d) > 8f) {
                    wheel = d > 0f ? 1 : -1;
                    lastPinchY = y;
                }
            } else {
                lastPinchY = y;
            }
            btn = 0;
        } else {
            lastPinchY = -1f;
        }
        /* titan2-orient-mouse is grabbed and is the only pointer. A second
         * relative delta from Android shoves the KDE cursor under the panel. */
        return;
    }

    /** Glass tap lands on that desktop pixel. Two fingers scroll. */
    private void onGlass(MotionEvent e) {
        int act = e.getActionMasked();
        if (e.getPointerCount() >= 2) {
            float y = (e.getY(0) + e.getY(1)) * 0.5f;
            if (act == MotionEvent.ACTION_POINTER_DOWN) lastPinchY = y;
            if (lastPinchY >= 0f && act == MotionEvent.ACTION_MOVE) {
                float d = y - lastPinchY;
                if (Math.abs(d) > 12f) {
                    int[] xy = mapGlass(e);
                    if (xy != null) sendPtr(xy[0], xy[1], 0, d > 0f ? 1 : -1, 1);
                    lastPinchY = y;
                }
            }
            if (act == MotionEvent.ACTION_POINTER_UP || act == MotionEvent.ACTION_UP) {
                lastPinchY = -1f;
            }
            return;
        }
        int[] xy = mapGlass(e);
        if (xy == null) return;
        if (act == MotionEvent.ACTION_DOWN) {
            lastTouchX = xy[0];
            lastTouchY = xy[1];
            buttons = 1;
            writePtrFile(xy[0], xy[1]);
            sendPtr(xy[0], xy[1], 1, 0, 1);
            return;
        }
        if (act == MotionEvent.ACTION_MOVE) {
            writePtrFile(xy[0], xy[1]);
            sendPtr(xy[0], xy[1], buttons == 0 ? 1 : buttons, 0, 1);
            return;
        }
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            buttons = 0;
            sendPtr(xy[0], xy[1], 0, 0, 1);
            lastTouchX = -1f;
            lastTouchY = -1f;
        }
    }

    /** Uniform FIT_CENTER. Touches in the side bars are not on the desk. */
    private int[] mapGlass(MotionEvent e) {
        int bw = bitmap != null ? bitmap.getWidth() : AtlasPrefs.deskW(this);
        int bh = bitmap != null ? bitmap.getHeight() : AtlasPrefs.deskH(this);
        int vw = Math.max(1, image.getWidth());
        int vh = Math.max(1, image.getHeight());
        float scale = Math.min(vw / (float) bw, vh / (float) bh);
        if (scale <= 0f) return null;
        float dw = bw * scale;
        float dh = bh * scale;
        float ox = (vw - dw) * 0.5f;
        float oy = (vh - dh) * 0.5f;
        int[] loc = new int[2];
        image.getLocationOnScreen(loc);
        float x = e.getRawX() - loc[0] - ox;
        float y = e.getRawY() - loc[1] - oy;
        if (x < 0f || y < 0f || x >= dw || y >= dh) return null;
        int px = Math.max(0, Math.min(bw - 1, Math.round(x / scale)));
        int py = Math.max(0, Math.min(bh - 1, Math.round(y / scale)));
        return new int[] { px, py };
    }

    private static int buttonMask(MotionEvent e) {
        int st = e.getButtonState();
        int b = 0;
        if ((st & MotionEvent.BUTTON_PRIMARY) != 0) b |= 1;
        if ((st & MotionEvent.BUTTON_SECONDARY) != 0) b |= 2;
        if ((st & MotionEvent.BUTTON_TERTIARY) != 0) b |= 4;
        return b;
    }

    private void sendPtr(int dx, int dy, int btn, int wheel, int abs) {
        if (!DeskClient.load(this)) return;
        String sock = DeskSession.inputSock(this);
        input.execute(() -> DeskClient.pointer(sock, dx, dy, btn, wheel, abs));
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyFloatChrome();
        if (keys != null && !full) keys.reload();
        binder.execute(() -> DeskSideKeys.publish(DeskActivity.this));
        if (deskRoot != null) deskRoot.requestFocus();
        if (!leaveRegistered) {
            IntentFilter filter = new IntentFilter("com.titanus2.atlas.DESK_LEAVE");
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(deskLeave, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(deskLeave, filter);
            }
            leaveRegistered = true;
        }
        /* Keyboard only while this window is focused, so Home still works.
         * The trackpad stays for the whole time the desk is open. */
        setPadHeld(true);
        setDeskFocused(hasWindowFocus());
        DeskSession.startAudio(this);
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 41);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        /* Focus loss gives TitanKey back so scan 580 reaches Controls.
         * The trackpad grab is not cleared here. */
        setDeskFocused(hasFocus);
    }

    @Override
    protected void onPause() {
        setPadHeld(false);
        setDeskFocused(false);
        binder.execute(() -> DeskSideKeys.release(DeskActivity.this));
        if (leaveRegistered) {
            try {
                unregisterReceiver(deskLeave);
            } catch (Exception ignored) {
            }
            leaveRegistered = false;
        }
        super.onPause();
    }

    /** Back returns to the Debian shell under this screen. The shell
     *  session stays up. Enter is a seat key and does not come here. */
    private void leaveDesk() {
        if (isFinishing()) return;
        setPadHeld(false);
        setDeskFocused(false);
        try {
            Intent shell = new Intent(this, MainActivity.class);
            shell.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(shell);
        } catch (Exception ignored) {
        }
        finish();
    }

    /**
     * Home / Recents from the Controls keymap plane
     * ({@code titan2_km_recents_short} / {@code titan2_km_recents_long}).
     * Fired through Controls KEY_FIRE, which is GLOBAL_ACTION_*.
     */
    private void fireNav(boolean heldLong) {
        String action = plane(heldLong ? "titan2_km_recents_long" : "titan2_km_recents_short",
                heldLong ? "recents" : "home");
        /* Drop the keyboard grab and this window. Otherwise Home and Recents
         * run underneath the desk and look like the key did nothing. */
        setPadHeld(false);
        setDeskFocused(false);
        leaveDesk();
        Intent fire = new Intent("com.titanus2.controls.KEY_FIRE");
        fire.setPackage("com.titanus2.controls");
        fire.putExtra("action", action);
        fire.putExtra("scan", 580);
        sendBroadcast(fire);
        if (!heldLong && "home".equals(action)) {
            try {
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(home);
            } catch (Exception ignored) {
            }
        }
    }

    private static String plane(String name, String fallback) {
        String[] dirs = { "/data/misc/titan2/", "/data/local/tmp/" };
        for (String dir : dirs) {
            try {
                String s = Files.readString(java.nio.file.Path.of(dir + name)).trim();
                if (s.isEmpty()) continue;
                s = s.split("\\s+")[0];
                if (s.isEmpty() || "null".equals(s) || "none".equals(s) || "default".equals(s))
                    continue;
                return s;
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return super.dispatchKeyEvent(event);
        }
        int key = event.getKeyCode();
        /* Back returns to the Debian shell. Enter is scan 28 and stays
         * on the seat. A focused chrome button must not see it. */
        if (key == KeyEvent.KEYCODE_BACK) {
            /* A mouse right-click is delivered as Back. Do not leave the desk. */
            int src = event.getSource();
            if (src == InputDevice.SOURCE_MOUSE
                    || src == InputDevice.SOURCE_MOUSE_RELATIVE) {
                return true;
            }
            if (action == KeyEvent.ACTION_UP) leaveDesk();
            return true;
        }
        /* Home always runs. Short and hold follow the Controls keymap. */
        if (key == KeyEvent.KEYCODE_HOME || key == KeyEvent.KEYCODE_APP_SWITCH
                || key == KeyEvent.KEYCODE_F24) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                homeDownAt = android.os.SystemClock.uptimeMillis();
            }
            if (action == KeyEvent.ACTION_UP) {
                long held = homeDownAt == 0 ? 0
                        : android.os.SystemClock.uptimeMillis() - homeDownAt;
                homeDownAt = 0;
                boolean longHold = held >= 700;
                fireNav(longHold);
            }
            return true;
        }
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
            return true;
        }
        /* atlas-desk-keys is the only seat writer while it is up. A second
         * write from this activity was XKB AltGr, or a stray Enter. */
        if (readKeys) return true;
        InputDevice dev = event.getDevice();
        if (dev != null && "TitanKey".equals(dev.getName()) && keyProc != null) {
            return true;
        }
        int raw = event.getScanCode();
        if (raw <= 0) raw = linuxKey(key);
        boolean down = action == KeyEvent.ACTION_DOWN;
        if (raw == 100) physAlt = down;
        if (raw == 222 || raw == 253) {
            physSym = down;
            return true;
        }
        int code = seatCode(raw);
        int synthShift = 0;
        if (physSym) {
            int[] layer = specialsLayer(raw);
            if (layer != null) {
                code = layer[0];
                synthShift = layer[1];
            }
        }
        if (code > 0 && DeskClient.load(this)) {
            int linux = code;
            int shift = synthShift;
            int rawScan = raw;
            String sock = DeskSession.inputSock(this);
            input.execute(() -> {
                /* Fallback only. Missed Left Alt must not stick. Sym is not AltGr. */
                if (down && !modifierScan(rawScan) && !physAlt)
                    DeskClient.key(sock, 56, 0);
                if (shift != 0 && down) DeskClient.key(sock, 42, 1);
                DeskClient.key(sock, linux, down ? 1 : 0);
                if (shift != 0 && !down) DeskClient.key(sock, 42, 0);
            });
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** TitanKey evdev → seat. The helper grabs the node so Android does not eat the same keys. */
    private void startKeyReader() {
        stopKeyReader();
        String dev = titanKeyDev();
        if (dev == null) {
            Log.w("AtlasDesk", "TitanKey not found");
            return;
        }
        try {
            NativeBin.ensureExtracted(this);
        } catch (Exception e) {
            Log.w("AtlasDesk", "keys extract: " + e.getMessage());
        }
        killOrphanKeys();
        File dir = new File("/data/local/tmp/atlas-virgl");
        File bin = new File(dir, "atlas-desk-keys");
        File src = new File(NativeBin.binDir(this), "atlas-desk-keys");
        try {
            if (!dir.isDirectory()) dir.mkdirs();
            if (src.isFile() && src.length() > 0) {
                try {
                    Files.copy(src.toPath(), bin.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception copyErr) {
                    Log.w("AtlasDesk", "keys copy: " + copyErr.getMessage());
                }
            }
            if (!bin.isFile()) {
                Log.w("AtlasDesk", "keys binary missing");
                return;
            }
            bin.setReadable(true, false);
            bin.setExecutable(true, false);
            ProcessBuilder pb = new ProcessBuilder(
                    bin.getAbsolutePath(), dev, DeskSession.inputSock(this));
            pb.redirectErrorStream(true);
            pb.redirectOutput(new File(dir, "keys.log"));
            keyProc = pb.start();
            readKeys = true;
            Log.i("AtlasDesk", "keys " + dev);
        } catch (Exception e) {
            readKeys = false;
            Log.w("AtlasDesk", "keys: " + e.getMessage());
        }
    }

    private void stopKeyReader() {
        readKeys = false;
        Process p = keyProc;
        keyProc = null;
        if (p != null) {
            p.destroy();
            try {
                if (!p.waitFor(400, TimeUnit.MILLISECONDS)) p.destroyForcibly();
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        killOrphanKeys();
    }

    /** A previous reader keeps EVIOCGRAB after this activity lost keyProc. */
    private static void killOrphanKeys() {
        try {
            Process p = Runtime.getRuntime().exec(new String[] {
                "/system/bin/sh", "-c",
                "kill $(pidof atlas-desk-keys) 2>/dev/null; exit 0"
            });
            if (!p.waitFor(300, TimeUnit.MILLISECONDS)) p.destroyForcibly();
        } catch (Exception ignored) {
        }
    }

    private static String titanKeyDev() {
        File dir = new File("/sys/class/input");
        File[] events = dir.listFiles();
        if (events == null) return null;
        for (File ev : events) {
            if (!ev.getName().startsWith("event")) continue;
            File name = new File(ev, "device/name");
            try (InputStream in = new FileInputStream(name)) {
                byte[] buf = in.readAllBytes();
                String n = new String(buf).trim();
                if ("TitanKey".equals(n)) return "/dev/input/" + ev.getName();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** Match atlas-desk-keys: free Alt is Left Alt, Fn is Left Ctrl.
     *  Sym is the printed layer, not XKB AltGr, so 222/253 are not seat keys. */
    private static int seatCode(int scan) {
        switch (scan) {
            case 100: return 56;
            case 183:
            case 251: return 29;
            case 222:
            case 253: return 0;
            default: return scan;
        }
    }

    /**
     * Printed Sym glyphs as US linux keys. {key, shift}.
     * titan2-keys is the map. This runs only while atlas-desk-keys is down.
     */
    private static int[] specialsLayer(int code) {
        int android = com.titanus2.api.KeyGlyphs.androidLetterForLinuxScan(code);
        if (android <= 0) return null;
        String g = com.titanus2.api.KeyGlyphs.specialForAndroidKey(android);
        if (g == null || g.length() != 1) return null;
        return com.titanus2.api.KeyGlyphs.linuxFor(g.charAt(0));
    }

    private static boolean modifierScan(int scan) {
        switch (scan) {
            case 29: case 42: case 54: case 56: case 97:
            case 100: case 125: case 126: case 183:
            case 222: case 251: case 253:
                return true;
            default: return false;
        }
    }

    /** Evdev code when the kernel did not supply a scan code. */
    private static int linuxKey(int key) {
        if (key >= KeyEvent.KEYCODE_A && key <= KeyEvent.KEYCODE_Z) {
            return 30 + (key - KeyEvent.KEYCODE_A);
        }
        if (key >= KeyEvent.KEYCODE_1 && key <= KeyEvent.KEYCODE_9) {
            return 2 + (key - KeyEvent.KEYCODE_1);
        }
        if (key == KeyEvent.KEYCODE_0) return 11;
        if (key >= KeyEvent.KEYCODE_F1 && key <= KeyEvent.KEYCODE_F10) {
            return 59 + (key - KeyEvent.KEYCODE_F1);
        }
        if (key == KeyEvent.KEYCODE_F11) return 87;
        if (key == KeyEvent.KEYCODE_F12) return 88;
        switch (key) {
            case KeyEvent.KEYCODE_ESCAPE: return 1;
            case KeyEvent.KEYCODE_MINUS: return 12;
            case KeyEvent.KEYCODE_EQUALS: return 13;
            case KeyEvent.KEYCODE_DEL: return 14;
            case KeyEvent.KEYCODE_TAB: return 15;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return 28;
            case KeyEvent.KEYCODE_CTRL_LEFT: return 29;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return 97;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return 42;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return 54;
            case KeyEvent.KEYCODE_ALT_LEFT: return 56;
            case KeyEvent.KEYCODE_ALT_RIGHT: return 100;
            case KeyEvent.KEYCODE_SPACE: return 57;
            case KeyEvent.KEYCODE_CAPS_LOCK: return 58;
            case KeyEvent.KEYCODE_META_LEFT: return 125;
            case KeyEvent.KEYCODE_META_RIGHT: return 126;
            case KeyEvent.KEYCODE_DPAD_UP: return 103;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 105;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 106;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 108;
            case KeyEvent.KEYCODE_PAGE_UP: return 104;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 109;
            case KeyEvent.KEYCODE_MOVE_HOME: return 102;
            case KeyEvent.KEYCODE_MOVE_END: return 107;
            case KeyEvent.KEYCODE_INSERT: return 110;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 111;
            case KeyEvent.KEYCODE_SYSRQ: return 99;
            case KeyEvent.KEYCODE_SCROLL_LOCK: return 70;
            case KeyEvent.KEYCODE_BREAK: return 119;
            case KeyEvent.KEYCODE_GRAVE: return 41;
            case KeyEvent.KEYCODE_LEFT_BRACKET: return 26;
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return 27;
            case KeyEvent.KEYCODE_BACKSLASH: return 43;
            case KeyEvent.KEYCODE_SEMICOLON: return 39;
            case KeyEvent.KEYCODE_APOSTROPHE: return 40;
            case KeyEvent.KEYCODE_COMMA: return 51;
            case KeyEvent.KEYCODE_PERIOD: return 52;
            case KeyEvent.KEYCODE_SLASH: return 53;
            default: return 0;
        }
    }

    @Override
    protected void onDestroy() {
        run.set(false);
        setPadHeld(false);
        setDeskFocused(false);
        stopPadReader();
        stopKeyReader();
        io.shutdownNow();
        input.shutdownNow();
        binder.shutdownNow();
        if (image != null) image.setImageDrawable(null);
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = null;
        super.onDestroy();
    }

    private static String oneLine(String s) {
        if (s == null || s.isEmpty()) return "desk";
        int nl = s.indexOf('\n');
        String line = nl >= 0 ? s.substring(0, nl) : s;
        return line.length() > 80 ? line.substring(0, 80) : line;
    }
}
