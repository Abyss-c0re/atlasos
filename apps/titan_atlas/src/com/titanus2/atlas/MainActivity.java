package com.titanus2.atlas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Atlas — Termux terminal-view + terminal-emulator (reused, simplified host).
 * Session: atlas-net (C pty via Termux JNI libatlaspty) for DNS on GSI.
 * Multi-session like Termux: + / prev / next / close; Exit leaves the app.
 */
public class MainActivity extends Activity implements AtlasTermClient.Host {
    public static final String VERSION = "1.0.8-sess";
    private static final int MAX_SESSIONS = 8;

    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private TextView strip;
    private TerminalView termView;
    /** Active session (from process-scoped SessionHub). */
    private TerminalSession session;
    private AtlasTermClient termClient;
    private ExtraKeysView extraKeys;
    /** Top-bar shell plane toggle (per-session Android vs Debian). */
    private Button shellModeBtn;
    private int padL, padT, padR, padB;
    private boolean softImeWanted;
    private Typeface termFont;
    private boolean binsReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Never resurface a leftover biometric sheet from a prior session/test.
        AtlasAuth.clearStaleRequests(this);
        try {
            NativeBin.healHomePermissions(this);
        } catch (Exception ignored) {
        }
        // Rootless bridge heal when CE files were root-owned (no KernelSU).
        if (NativeBin.needsHomeHeal(this) || !NativeBin.home(this).canWrite()) {
            new Thread(() -> {
                try {
                    AtlasBridge.healHomeNoKsu(MainActivity.this);
                } catch (Exception ignored) {
                }
            }, "atlas-bridge-heal").start();
        }
        if (AtlasPrefs.keepScreenOn(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        // Seed scale so the first launch is not treated as a DPI change.
        if (!AtlasPrefs.displayScaleChanged(this)) {
            AtlasPrefs.storeDisplayScale(this);
        }
        /*
         * Termux-known-good layout for Grok TUI:
         *   [chrome][terminal weight=1][extra-keys fixed] + SOFT_INPUT_ADJUST_RESIZE
         * Terminal height is ONLY the flex slot above the panel — never under it.
         * Do NOT pad IME height on top of ADJUST_RESIZE (double-shrink / prompt under keys).
         * Do NOT hide extra-keys on IME (size thrash left Grok rows under the panel).
         */
        getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        termFont = loadTermFont();

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AtlasPrefs.bgColor(this));
        padL = padT = padR = padB = dp(4);
        root.setPadding(padL, padT, padR, padB);
        root.setFitsSystemWindows(true);
        root.setClipChildren(true);
        root.setClipToPadding(true);
        TermTheme.applyScheme(this);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            // ADJUST_RESIZE already shrank the window; recompute PTY rows only.
            if (termView != null) {
                termView.post(() -> {
                    if (termView.getWidth() > 0 && termView.getHeight() > 0) {
                        termView.updateSize();
                    }
                });
            }
            return insets;
        });

        // Compact chrome: mono status + tool row (PRODUCT_UX short facts, no version spam)
        strip = new TextView(this);
        strip.setTextColor(AtlasUi.chromeOnTerm(this));
        strip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        strip.setTypeface(Typeface.MONOSPACE);
        strip.setText("starting…");
        strip.setSingleLine(true);
        strip.setMinHeight(dp(28));
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(6), dp(2), dp(6), dp(2));
        strip.setOnClickListener(v -> cycleSession(+1));
        root.addView(strip, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bar1 = new LinearLayout(this);
        bar1.setOrientation(LinearLayout.HORIZONTAL);
        bar1.setGravity(Gravity.CENTER_VERTICAL);
        bar1.addView(makeCompactBtn("+", v -> newSession()), barWeight());
        bar1.addView(makeCompactBtn("◀", v -> cycleSession(-1)), barWeight());
        bar1.addView(makeCompactBtn("▶", v -> cycleSession(+1)), barWeight());
        bar1.addView(makeCompactBtn("×", v -> closeCurrentSession()), barWeight());
        // Per-session shell: And / Deb (this PTY only)
        shellModeBtn = makeCompactBtn("Deb", v -> toggleSessionShellMode());
        bar1.addView(shellModeBtn, barWeight());
        bar1.addView(makeCompactBtn("Exit", v -> exitApp()), barWeight());
        bar1.addView(makeCompactBtn("Kbd", v -> toggleSoftKeyboard()), barWeight());
        bar1.addView(makeCompactBtn("Paste", v -> pasteToSession()), barWeight());
        bar1.addView(makeCompactBtn("Load", v -> loadSeatDialog()), barWeight());
        bar1.addView(makeCompactBtn("Save", v -> saveCurrentSeat()), barWeight());
        bar1.addView(makeCompactBtn("↻", v -> restartSession()), barWeight());
        bar1.addView(makeCompactBtn("⚙", v ->
            startActivity(new Intent(this, SettingsActivity.class))), barWeight());
        root.addView(bar1, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // PTY JNI MUST load before TerminalView (layout → TerminalSession → JNI.<clinit>).
        // Priv-app has no extracted lib/ next to APK — extract assets → files/bin first.
        try {
            NativeBin.ensureExtracted(this);
            binsReady = true;
        } catch (Throwable e) {
            binsReady = NativeBin.hasCoreBins(this);
            if (!binsReady) {
                strip.setText("extract fail");
                setContentView(root);
                return;
            }
        }
        if (!NativeBin.loadAtlasPty(this)) {
            strip.setText("atlaspty missing — reflash Atlas");
            setContentView(root);
            return;
        }

        termView = new TerminalView(this, null);
        termClient = new AtlasTermClient(this);
        termView.setTerminalViewClient(termClient);
        // Font size + colors from Settings (AtlasPrefs)
        TermTheme.applyToView(this, termView, root);
        // DejaVu mono: box-drawing / TUI frames (system monospace is blank → ghost bars)
        termView.setTypeface(termFont != null ? termFont : Typeface.MONOSPACE);
        termView.setFocusable(true);
        termView.setFocusableInTouchMode(true);
        // Clip terminal drawing to its slot — never paint under the key panel.
        termView.setClipToOutline(true);
        // Critical: typing must not flash the IME every keypress
        setShowSoftInputOnFocus(termView, false);
        // weight=1 → terminal ends exactly where the extra-key panel begins
        LinearLayout.LayoutParams termLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(termView, termLp);
        // Every real layout pass → PTY rows match the visible slot above the panel
        termView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int nh = b - t;
            int nw = r - l;
            int oh = ob - ot;
            int ow = or - ol;
            if (nw > 0 && nh > 0 && (nw != ow || nh != oh)) {
                termView.updateSize();
            }
        });

        // Termux-style special keys — fixed below terminal (not overlaid)
        extraKeys = new ExtraKeysView(this, new ExtraKeysView.Listener() {
            @Override
            public void onExtraKey(String key) {
                if (termClient == null) return;
                termClient.sendExtraKey(key);
                if (extraKeys != null) extraKeys.refreshModifiers();
                if (termView != null) termView.requestFocus();
            }

            @Override
            public boolean isCtrlOn() {
                return termClient != null && termClient.isStickyCtrl();
            }

            @Override
            public boolean isAltOn() {
                return termClient != null && termClient.isStickyAlt();
            }
        });
        LinearLayout.LayoutParams keysLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        keysLp.weight = 0f;
        root.addView(extraKeys, keysLp);

        setContentView(root);
        root.requestApplyInsets();

        // Background refresh of non-critical assets (bins already extracted above).
        new Thread(() -> {
            try {
                NativeBin.ensureExtracted(MainActivity.this);
                NativeBin.stageCaBundle(MainActivity.this);
            } catch (Exception ignored) {
            }
        }, "atlas-extract").start();
        try {
            NativeBin.stageCaBundle(this);
        } catch (Exception ignored) {
        }
        // chrome colors after theme load

        // Paint chrome first. Logo stick was crash-loop during sync createSession/PTY.
        updateShellModeButton();
        termView.requestFocus();
        // Product rootless: open = Android shell always. No su/ensure thrash on open.
        // System init may prepare hybrid mounts; Deb is opt-in when plane ready.
        termView.post(() -> {
            try {
                SessionHub.pruneDead();
                if (!SessionHub.sessions().isEmpty()) {
                    if (SessionHub.index() < 0
                        || SessionHub.index() >= SessionHub.sessions().size()) {
                        SessionHub.setIndex(0);
                    }
                    // If resumed Deb but plane down → attach Android, don't ensure thrash
                    if (SessionHub.MODE_DEBIAN.equals(SessionHub.currentMode())
                        && !NativeBin.hybridRootfsReady()) {
                        SessionHub.setCurrentMode(SessionHub.MODE_ANDROID);
                    }
                    attachVisibleSession();
                    updateStrip("resumed");
                } else {
                    newSession();
                }
                if (NativeBin.needsHomeHeal(MainActivity.this)) {
                    toast("home owned by root — Settings → Fix home");
                }
            } catch (Throwable t) {
                android.util.Log.e("Atlas", "session start failed", t);
                try {
                    strip.setText("start fail: "
                        + (t.getMessage() != null ? t.getMessage()
                            : t.getClass().getSimpleName()));
                } catch (Exception ignored) {
                }
            }
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Keep the live PTY. Only retarget font px + SIGWINCH the grid.
        if (AtlasPrefs.displayScaleChanged(this)) {
            applyDisplayScaleKeepSession();
        }
    }

    /** New density/fontScale: same shell, new cell size. Never finishIfRunning. */
    private void applyDisplayScaleKeepSession() {
        AtlasPrefs.storeDisplayScale(this);
        TermTheme.applyToView(this, termView, root);
        if (termView != null) {
            if (termFont != null) {
                termView.setTypeface(termFont);
            }
            termView.post(() -> {
                if (termView.getWidth() > 0 && termView.getHeight() > 0) {
                    termView.updateSize();
                }
            });
        }
        if (extraKeys != null) extraKeys.applyTermChrome(this);
        if (session != null) {
            TermTheme.applyToSession(this, session, termView);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-apply theme (user may have changed Settings)
        TermTheme.applyToView(this, termView, root);
        if (extraKeys != null) extraKeys.applyTermChrome(this);
        if (session != null) {
            TermTheme.applyToSession(this, session, termView);
        }
        // Refresh chrome contrast after theme change
        if (strip != null) {
            boolean wantDeb = SessionHub.MODE_DEBIAN.equals(SessionHub.currentMode());
            strip.setTextColor(AtlasUi.planeColor(this, wantDeb, NativeBin.hybridRootfsReady()));
        }
        updateShellModeButton();
        // Product Authentication Agent + session keep-alive (OS-wise, not tip-only)
        AtlasSessionService.ensureAuthAgent(this);
        AtlasSessionService.startOrUpdate(this, SessionHub.liveCount(), "auth-poll");
        // Privilege plane for Deb android-exec (allow Android bins when Settings allow)
        try {
            AtlasPrefs.publishPrivilegePlane(this);
        } catch (Exception ignored) {
        }
        try {
            NativeBin.ensureShellProfile(this);
        } catch (Exception ignored) {
        }
        if (!AtlasPrefs.isAuthUiQuietPeriod(this)
                && AtlasPrefs.displayScaleChanged(this)) {
            applyDisplayScaleKeepSession();
        }
        // Returning from AuthPromptActivity (apt/sudo biometrics): NEVER rebuild PTY.
        // That was the "bio auth then shell reloads" product bug.
        if (AtlasPrefs.isAuthUiQuietPeriod(this)) {
            AtlasPrefs.clearSessionRestart(this);
            SessionHub.pruneDead();
            if (session == null || !session.isRunning()) {
                attachVisibleSession();
            }
            updateStrip(null);
            if (termView != null) {
                termView.requestFocus();
                if (!softImeWanted) {
                    setShowSoftInputOnFocus(termView, false);
                }
            }
            return;
        }
        // Product: Settings hybrid on/off or ensure-complete → rebuild shell.
        // Never restart on every resume — only on explicit flag or empty hub.
        boolean needRestart = AtlasPrefs.consumeSessionRestart(this);
        SessionHub.pruneDead();
        if (AtlasPrefs.privilegedHybrid(this) && NativeBin.hybridRootfsReady()
                && androidFallbackPending) {
            needRestart = true;
            androidFallbackPending = false;
        }
        // HARD RULE: never auto-kill a live PTY (Deb or Android). User ↻ / And↔Deb
        // toggle call restartSession() directly. Auto paths (auth, ensure, promote)
        // only attach when dead or hub empty — except androidFallbackPending after
        // ensure succeeds (that was waiting for hybrid).
        boolean live = session != null && session.isRunning();
        if (needRestart && live && !androidFallbackPending) {
            needRestart = false;
            AtlasPrefs.clearSessionRestart(this);
            android.util.Log.i("Atlas", "onResume: keep live session (no auto restart)");
        }
        // Product rootless: only poll readiness (system may prepare mounts).
        // Never su-thrash from onResume.
        if (AtlasPrefs.privilegedHybrid(this) && !NativeBin.hybridRootfsReady()
            && HybridEnsure.appCanElevate()) {
            requestHybridEnsureAsync();
            startHybridReadyPoll();
        }
        // PTY not ready (lib load fail) — never NPE on null termClient/termView
        if (termView == null || termClient == null) {
            updateStrip("pty missing");
            return;
        }
        if (SessionHub.sessions().isEmpty()) {
            restartSession();
        } else if (needRestart) {
            restartSession();
        } else if (!live) {
            attachVisibleSession();
            updateStrip(null);
        } else {
            updateStrip(null);
        }
        if (termView != null) {
            termView.requestFocus();
            if (!softImeWanted) {
                setShowSoftInputOnFocus(termView, false);
            }
        }
    }

    private Button makeCompactBtn(String label, View.OnClickListener click) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTextColor(AtlasUi.chromeOnTerm(this));
        b.setMinHeight(dp(36));
        b.setMinimumHeight(dp(36));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(4), dp(4), dp(4), dp(4));
        b.setOnClickListener(click);
        return b;
    }

    /** @deprecated use makeCompactBtn */
    private Button makeBigBtn(String label, View.OnClickListener click) {
        return makeCompactBtn(label, click);
    }

    private LinearLayout.LayoutParams barWeight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(1), dp(0), dp(1), dp(0));
        return lp;
    }

    /** Box-drawing TUI font — system monospace often lacks U+2500 block on GSI. */
    private Typeface loadTermFont() {
        try {
            return Typeface.createFromAsset(getAssets(), "fonts/DejaVuSansMono.ttf");
        } catch (Exception e) {
            try {
                File f = new File(getFilesDir(), "fonts/DejaVuSansMono.ttf");
                if (f.isFile()) return Typeface.createFromFile(f);
            } catch (Exception ignored) {
            }
            return Typeface.MONOSPACE;
        }
    }

    private boolean hasHardwareKeyboard() {
        Configuration c = getResources().getConfiguration();
        return c.keyboard == Configuration.KEYBOARD_QWERTY
            && c.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES;
    }

    private void toggleSoftKeyboard() {
        if (termView == null) return;
        termView.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null) return;
        softImeWanted = !softImeWanted;
        // Always keep ADJUST_RESIZE so IME stays under extra-keys (Termux).
        getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (softImeWanted) {
            setShowSoftInputOnFocus(termView, true);
            imm.showSoftInput(termView, InputMethodManager.SHOW_IMPLICIT);
        } else {
            setShowSoftInputOnFocus(termView, false);
            imm.hideSoftInputFromWindow(termView.getWindowToken(), 0);
        }
        // size updates from onSizeChanged after resize — no forced mode thrash
    }

    /** Snapshot the current Debian moment into Backups (survives reboot). */
    private void saveCurrentSeat() {
        final String name = AtlasPrefs.lastSeat(this);
        toast("saving backup…");
        new Thread(() -> {
            String r = HybridEnsure.backupSave(this, name);
            boolean ok = r != null && (r.contains("backup=") || r.contains("saved="));
            if (ok) {
                AtlasPrefs.setLastSeat(this, name);
                String id = kvToken(r, "backup");
                if (id.isEmpty()) {
                    String path = kvToken(r, "saved");
                    int sl = path.lastIndexOf('/');
                    id = sl >= 0 ? path.substring(sl + 1) : path;
                }
                if (!id.isEmpty()) AtlasPrefs.setLastSnap(this, id);
            }
            final String idShown = AtlasPrefs.lastSnap(this);
            runOnUiThread(() -> {
                toast(r != null ? r : "save failed");
                if (ok) updateStrip("backup " + (idShown.isEmpty() ? name : idShown));
            });
        }, "atlas-save").start();
    }

    /** Pick a reboot-persistent backup and restore $HOME. */
    private void loadSeatDialog() {
        new Thread(() -> {
            List<HybridEnsure.Backup> backups = HybridEnsure.loadBackups(this);
            List<String> labels = new ArrayList<>();
            List<String[]> picks = new ArrayList<>();
            String last = AtlasPrefs.lastSeat(this);
            String lastSnap = AtlasPrefs.lastSnap(this);
            if (lastSnap != null && !lastSnap.isEmpty()) {
                labels.add("Last · " + lastSnap);
                picks.add(new String[] { last, lastSnap });
            }
            int n = 0;
            for (HybridEnsure.Backup b : backups) {
                if (n++ >= 12) break;
                if (b.id.equals(lastSnap) && labels.size() == 1) continue;
                String lab = b.title();
                if (b.note != null && !b.note.isEmpty()) {
                    String one = b.note.replace('\n', ' ').trim();
                    if (one.length() > 32) one = one.substring(0, 32) + "…";
                    lab = lab + " · " + one;
                }
                labels.add(lab);
                picks.add(new String[] { b.user, b.id });
            }
            runOnUiThread(() -> {
                if (labels.isEmpty()) {
                    toast("no backups — Save first");
                    return;
                }
                new AlertDialog.Builder(this)
                    .setTitle("Load backup")
                    .setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
                        if (which < 0 || which >= picks.size()) return;
                        applySeatLoad(picks.get(which)[0], picks.get(which)[1]);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            });
        }, "atlas-load-list").start();
    }

    private void applySeatLoad(String name, String snap) {
        toast("loading backup…");
        new Thread(() -> {
            String r = HybridEnsure.backupLoad(this, name, snap);
            boolean ok = r != null && (r.contains("loaded=") || r.contains("backup="));
            if (ok) {
                if (name != null && !name.isEmpty()) AtlasPrefs.setLastSeat(this, name);
                String id = kvToken(r, "backup");
                if (id.isEmpty()) id = snap;
                if (id != null && !id.isEmpty()) AtlasPrefs.setLastSnap(this, id);
            }
            runOnUiThread(() -> {
                toast(r != null ? r : "load failed");
                if (ok) {
                    int i = SessionHub.index();
                    if (i >= 0) SessionHub.setModeAt(i, SessionHub.MODE_DEBIAN);
                    else SessionHub.setCurrentMode(SessionHub.MODE_DEBIAN);
                    restartSession();
                    updateStrip("loaded " + (snap != null ? snap : name));
                }
            });
        }, "atlas-load").start();
    }

    private static String kvToken(String line, String key) {
        if (line == null) return "";
        String p = key + "=";
        int i = line.indexOf(p);
        if (i < 0) return "";
        int s = i + p.length();
        int e = line.indexOf(' ', s);
        return e < 0 ? line.substring(s).trim() : line.substring(s, e).trim();
    }

    /** Create a new PTY session and switch to it (Termux multi-session). */
    private void newSession() {
        if (SessionHub.sessions().size() >= MAX_SESSIONS) {
            toast("max " + MAX_SESSIONS + " sessions");
            return;
        }
        String mode = SessionHub.defaultModeFromPrefs(AtlasPrefs.privilegedHybrid(this));
        TerminalSession s = createSession(mode);
        if (s == null) return;
        SessionHub.addSession(s, mode);
        attachVisibleSession();
        updateStrip("ready");
        updateShellModeButton();
    }

    /** Rebuild shell in the current session slot (keeps per-session shell mode). */
    private void restartSession() {
        int sessionIdx = SessionHub.index();
        List<TerminalSession> sessions = SessionHub.sessions();
        if (sessionIdx < 0 || sessionIdx >= sessions.size()) {
            newSession();
            return;
        }
        String mode = SessionHub.modeAt(sessionIdx);
        TerminalSession old = sessions.get(sessionIdx);
        if (old != null) old.finishIfRunning();
        TerminalSession s = createSession(mode);
        if (s == null) return;
        SessionHub.setSessionAt(sessionIdx, s);
        attachVisibleSession();
        updateStrip("restart");
        updateShellModeButton();
        if (termView != null) termView.requestFocus();
    }

    private long lastShellToggleMs;
    private boolean shellSwitchInFlight;

    /**
     * Top-bar And/Deb: toggle this session between Android atlas shell and Debian hybrid.
     * Does not change Settings "Default new shell: Debian" for new sessions.
     *
     * Bug fixed: we used to set mode=Deb, then restart with mode forced to And, then
     * flip the label back to Deb — strip said Deb while PTY stayed Android ("forgot").
     */
    private void toggleSessionShellMode() {
        long now = System.currentTimeMillis();
        if (shellSwitchInFlight || now - lastShellToggleMs < 900L) {
            toast("switch in progress…");
            return;
        }
        lastShellToggleMs = now;
        int i = SessionHub.index();
        if (i < 0 || SessionHub.sessions().isEmpty()) {
            newSession();
            return;
        }
        String cur = SessionHub.modeAt(i);
        final String next = SessionHub.MODE_DEBIAN.equals(cur)
            ? SessionHub.MODE_ANDROID : SessionHub.MODE_DEBIAN;
        // Commit mode FIRST — restartSession reads modeAt(i)
        SessionHub.setModeAt(i, next);
        updateShellModeButton();

        if (SessionHub.MODE_DEBIAN.equals(next) && !NativeBin.hybridRootfsReady()) {
            // Product: system ensure request (no Magisk). Enter needs atlas-enter.
            HybridEnsure.requestSystemEnsure();
            if (!HybridEnsure.canEnterDeb() && !HybridEnsure.appCanElevate()) {
                SessionHub.setModeAt(i, SessionHub.MODE_ANDROID);
                toast("hybrid↓ · no enter helper");
                updateShellModeButton();
                updateStrip("hybrid↓");
                shellSwitchInFlight = false;
                return;
            }
            toast("ensuring hybrid…");
            androidFallbackPending = true;
            requestHybridEnsureAsync();
            startHybridReadyPoll();
            shellSwitchInFlight = true;
            restartSession();
            shellSwitchInFlight = false;
            updateStrip("ensuring");
            return;
        }

        if (SessionHub.MODE_DEBIAN.equals(next) && !HybridEnsure.canEnterDeb()) {
            HybridEnsure.requestEnterd();
            toast("starting enterd…");
            updateStrip("enterd↓");
            final int sessionIdxDown = i;
            if (termView != null) {
                termView.postDelayed(() -> {
                    if (HybridEnsure.canEnterDeb()) {
                        toast("→ Deb");
                        shellSwitchInFlight = true;
                        List<TerminalSession> sessions = SessionHub.sessions();
                        if (sessionIdxDown >= 0 && sessionIdxDown < sessions.size()) {
                            TerminalSession old = sessions.get(sessionIdxDown);
                            if (old != null) {
                                try { old.finishIfRunning(); } catch (Exception ignored) {}
                            }
                            try {
                                TerminalSession s = createSession(SessionHub.MODE_DEBIAN);
                                if (s != null) {
                                    SessionHub.setSessionAt(sessionIdxDown, s);
                                    attachVisibleSession();
                                    updateStrip("switched");
                                } else {
                                    updateStrip("switch failed");
                                }
                            } finally {
                                shellSwitchInFlight = false;
                                updateShellModeButton();
                            }
                        } else {
                            shellSwitchInFlight = false;
                        }
                    } else {
                        SessionHub.setModeAt(sessionIdxDown, SessionHub.MODE_ANDROID);
                        toast("enterd down — @atlasenter");
                        updateStrip("enterd↓");
                        updateShellModeButton();
                        shellSwitchInFlight = false;
                    }
                }, 900);
            } else {
                SessionHub.setModeAt(i, SessionHub.MODE_ANDROID);
                toast("enterd down — @atlasenter");
                updateShellModeButton();
                updateStrip("enterd↓");
                shellSwitchInFlight = false;
            }
            return;
        }

        toast(SessionHub.MODE_DEBIAN.equals(next) ? "→ Deb" : "→ And");
        shellSwitchInFlight = true;
        // Finish old PTY, then start new on next frame so mode sticks and old shell is gone
        int sessionIdx = i;
        List<TerminalSession> sessions = SessionHub.sessions();
        TerminalSession old = sessions.get(sessionIdx);
        if (old != null) {
            try {
                old.finishIfRunning();
            } catch (Exception ignored) {
            }
        }
        if (termView != null) {
            termView.post(() -> {
                try {
                    TerminalSession s = createSession(SessionHub.modeAt(sessionIdx));
                    if (s != null) {
                        SessionHub.setSessionAt(sessionIdx, s);
                        attachVisibleSession();
                        updateStrip("switched");
                    } else {
                        updateStrip("switch failed");
                    }
                } finally {
                    shellSwitchInFlight = false;
                    updateShellModeButton();
                    if (termView != null) termView.requestFocus();
                }
            });
        } else {
            restartSession();
            shellSwitchInFlight = false;
        }
    }

    private void updateShellModeButton() {
        if (shellModeBtn == null) return;
        String m = SessionHub.currentMode();
        boolean deb = SessionHub.MODE_DEBIAN.equals(m);
        boolean ready = NativeBin.hybridRootfsReady();
        shellModeBtn.setText(deb ? "Deb" : "And");
        // Theme accent when ready — never Cube cyan hardcode on chrome
        shellModeBtn.setTextColor(AtlasUi.planeColor(this, deb, ready));
    }

    private void cycleSession(int delta) {
        List<TerminalSession> sessions = SessionHub.sessions();
        if (sessions.isEmpty()) {
            newSession();
            return;
        }
        if (sessions.size() == 1) {
            toast("one session — tap + for another");
            return;
        }
        int sessionIdx = (SessionHub.index() + delta + sessions.size()) % sessions.size();
        SessionHub.setIndex(sessionIdx);
        attachVisibleSession();
        updateStrip(null);
        updateShellModeButton();
        if (termView != null) termView.requestFocus();
    }

    private void closeCurrentSession() {
        int sessionIdx = SessionHub.index();
        List<TerminalSession> sessions = SessionHub.sessions();
        if (sessions.isEmpty()) {
            exitApp();
            return;
        }
        TerminalSession cur = sessions.get(sessionIdx);
        if (cur != null) cur.finishIfRunning();
        SessionHub.removeAt(sessionIdx);
        if (SessionHub.sessions().isEmpty()) {
            session = null;
            newSession();
            return;
        }
        attachVisibleSession();
        updateStrip("closed");
        updateShellModeButton();
    }

    private void exitApp() {
        SessionHub.finishAll();
        session = null;
        AtlasPrefs.setLiveSessionCount(this, 0);
        // Keep Authentication Agent alive for Remote ADB / sudo (product OS service)
        if (AtlasPrefs.authAgentAlways(this)) {
            AtlasSessionService.ensureAuthAgent(this);
        } else {
            AtlasSessionService.stop(this);
        }
        finish();
    }

    private void attachVisibleSession() {
        if (termClient == null || termView == null) return;
        List<TerminalSession> sessions = SessionHub.sessions();
        int sessionIdx = SessionHub.index();
        if (sessionIdx < 0 || sessionIdx >= sessions.size()) return;
        session = sessions.get(sessionIdx);
        if (session == null) return;
        termClient.bindSession(session);
        termView.attachSession(session);
        TermTheme.applyToSession(this, session, termView);
        if (extraKeys != null) extraKeys.refreshModifiers();
        termView.post(() -> {
            if (termView.getWidth() > 0 && termView.getHeight() > 0) {
                termView.onScreenUpdated();
            }
        });
    }

    private void updateStrip(String note) {
        List<TerminalSession> sessions = SessionHub.sessions();
        int n = sessions.size();
        int i = SessionHub.index() + 1;
        String live = (session != null && session.isRunning()) ? "live" : "done";
        String mode = SessionHub.currentMode();
        boolean wantDeb = SessionHub.MODE_DEBIAN.equals(mode);
        boolean ready = NativeBin.hybridRootfsReady();
        String plane = AtlasUi.planeLabel(wantDeb, ready);
        strip.setText(AtlasUi.statusLine(plane, Math.max(i, 0), n, live, note));
        strip.setTextColor(AtlasUi.planeColor(this, wantDeb, ready));
        updateShellModeButton();
        refreshKeepAlive();
    }

    /**
     * Spawn one atlas-net (hybrid) or atlas REPL PTY. Does not attach.
     * @param shellMode {@link SessionHub#MODE_ANDROID} or {@link SessionHub#MODE_DEBIAN}
     */
    private TerminalSession createSession(String shellMode) {
        // Extract once per process (best-effort). System inject is enough to start.
        if (!binsReady) {
            try {
                NativeBin.ensureExtracted(this);
            } catch (Exception ignored) {
                // priv_app extract may fail on first CE unlock; /system/bin still works.
            }
            binsReady = NativeBin.hasCoreBins(this);
            if (!binsReady) {
                strip.setText("bin fail");
                return null;
            }
        }

        File home = NativeBin.home(this);
        File bin = NativeBin.binDir(this);
        // Product: /system/bin first. App files/bin is optional overlay only.
        File net = NativeBin.atlasNetScript(this);
        File ca = new File(home, "cacert.pem");
        String path = NativeBin.pathEnv(this);

        String shell;
        String[] args;
        if (net != null) {
            shell = "/system/bin/sh";
            args = new String[] {"sh", net.getAbsolutePath()};
        } else {
            File atlasSys = new File("/system/bin/atlas");
            File atlasBin = atlasSys.isFile() ? atlasSys : new File(bin, "atlas");
            if (!atlasBin.isFile() || atlasBin.length() < 1000) {
                strip.setText("bin fail: no atlas");
                return null;
            }
            shell = atlasBin.getAbsolutePath();
            args = new String[] {shell, "-i"};
        }

        // Per-session plane: Deb always asks atlas-net for hybrid enter (ATLAS_SESSION=hybrid).
        // atlas-net fails loud if su/hybrid missing — never fake Android under Deb label.
        boolean wantDeb = SessionHub.MODE_DEBIAN.equals(shellMode);
        boolean hybridReady = NativeBin.hybridRootfsReady();
        // priv env: Deb mode commits hybrid intent even while ensure is in flight
        boolean priv = wantDeb;
        try {
            NativeBin.writePlaneStatus(this, wantDeb, hybridReady);
        } catch (Exception ignored) {
        }
        if (wantDeb && !hybridReady) {
            HybridEnsure.requestSystemEnsure();
            if (!HybridEnsure.canEnterDeb()) {
                wantDeb = false;
                shellMode = SessionHub.MODE_ANDROID;
                strip.setText(AtlasUi.statusLine("ANDROID",
                    SessionHub.sessions().size() + 1,
                    SessionHub.sessions().size() + 1, null, "hybrid↓"));
                strip.setTextColor(AtlasUi.chromeOnTerm(this));
            } else {
                androidFallbackPending = true;
                strip.setText(AtlasUi.statusLine("hybrid↓", SessionHub.sessions().size() + 1,
                    SessionHub.sessions().size() + 1, null, "ensuring"));
                strip.setTextColor(AtlasUi.WARN);
                requestHybridEnsureAsync();
                startHybridReadyPoll();
            }
        } else if (wantDeb && hybridReady && !HybridEnsure.canEnterDeb()) {
            HybridEnsure.requestEnterd();
            wantDeb = false;
            shellMode = SessionHub.MODE_ANDROID;
            strip.setText(AtlasUi.statusLine("ANDROID",
                SessionHub.sessions().size() + 1,
                SessionHub.sessions().size() + 1, null, "enterd↓"));
            strip.setTextColor(AtlasUi.chromeOnTerm(this));
        } else if (wantDeb && hybridReady) {
            androidFallbackPending = false;
        }
        // Recompute after rootless fallback (must not keep hybrid env after wantDeb cleared)
        priv = wantDeb;
        hybridReady = NativeBin.hybridRootfsReady();
        // Auth dir chmod only (no lpctl). Mount is AtlasApp background.
        File authLp = NativeBin.authDirLp();
        // Deb HOME = product linux home (not app CE — chdir denied after hybrid enter).
        // Android plane keeps CE files for app state.
        File sessionHome = home;
        if (priv) {
            File linuxHome = new File(NativeBin.LINUX_HOME);
            //noinspection ResultOfMethodCallIgnored
            linuxHome.mkdirs();
            if (linuxHome.isDirectory()) {
                sessionHome = linuxHome;
            }
        }
        List<String> env = new ArrayList<>();
        env.add("HOME=" + sessionHome.getAbsolutePath());
        env.add("ATLAS_HOME=" + sessionHome.getAbsolutePath());
        // Privilege auth plane — super LP only (never CE files/auth).
        env.add("ATLAS_AUTH_DIR=" + authLp.getAbsolutePath());
        env.add("ATLAS_AUTH_ON_LP=" + NativeBin.AUTH_ON_LP);
        env.add("ATLAS_AUTH_IN_DEB=" + NativeBin.AUTH_IN_DEB);
        env.add("ATLAS_LINUX_MNT=" + NativeBin.LP_MNT);
        env.add("ATLAS_LINUX_HOME=" + NativeBin.LINUX_HOME);
        // User overlay only — base ELFs are on the system image.
        env.add("ATLAS_BIN=" + bin.getAbsolutePath());
        env.add("ATLAS_USER_BIN=" + bin.getAbsolutePath());
        env.add("ATLAS_SYSBIN=/system/bin");
        env.add("PATH=" + path);
        env.add("TERM=xterm-256color");
        env.add("LANG=C.UTF-8");
        env.add("COLORTERM=truecolor");
        env.add("ATLAS_SESSION_ID=" + SessionHub.nextSessionId());
        env.add("ATLAS_PRIV=" + (priv ? "1" : "0"));
        env.add("ATLAS_SESSION=" + (priv ? "hybrid" : "atlas"));
        env.add("ATLAS_PLANE=" + (priv ? "hybrid" : "android"));
        env.add("ATLAS_MODE=" + (priv ? "debian" : "android"));
        env.add("ATLAS_APP_VERSION=" + VERSION);
        env.add("ATLAS_HYBRID=" + (priv && hybridReady ? "1" : "0"));
        env.add("ATLAS_HYBRID_SIZE_G=" + AtlasPrefs.hybridSizeG(this));
        env.add("ATLAS_REPORTS=" + new File(home, "reports").getAbsolutePath());
        env.add("USER=atlas");
        env.add("LOGNAME=atlas");
        env.add("ATLAS_ROLE=atlas");
        if (ca.isFile()) {
            env.add("SSL_CERT_FILE=" + ca.getAbsolutePath());
            env.add("CURL_CA_BUNDLE=" + ca.getAbsolutePath());
        }
        File apex = new File("/apex/com.android.conscrypt/cacerts");
        if (apex.isDirectory()) {
            env.add("SSL_CERT_DIR=" + apex.getAbsolutePath());
        }
        File bashEnv = new File(home, ".bash_env");
        if (bashEnv.isFile() && bashEnv.canRead()) {
            env.add("BASH_ENV=" + bashEnv.getAbsolutePath());
        }
        env.add("INPUTRC=" + new File(home, ".inputrc").getAbsolutePath());
        // System bash (shell_exec) — not app-private static ELF.
        File bash = NativeBin.systemBash();
        if (bash != null) {
            env.add("SHELL=" + bash.getAbsolutePath());
        }

        strip.setText(AtlasUi.statusLine(
            AtlasUi.planeLabel(wantDeb, hybridReady),
            SessionHub.sessions().size() + 1,
            SessionHub.sessions().size() + 1,
            null, "spawn"));
        strip.setTextColor(AtlasUi.planeColor(this, wantDeb, hybridReady));

        try {
            return new TerminalSession(
                shell,
                home.getAbsolutePath(),
                args,
                env.toArray(new String[0]),
                3000,
                termClient);
        } catch (Throwable t) {
            android.util.Log.e("Atlas", "TerminalSession create failed", t);
            strip.setText("pty fail");
            return null;
        }
    }

    private static volatile boolean androidFallbackPending;
    private static volatile int hybridReadyPollLeft;
    private final android.os.Handler hybridReadyH =
        new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Poll readiness while ensure runs in {@link HybridEnsure}. Never blocks UI.
     */
    private void startHybridReadyPoll() {
        if (!AtlasPrefs.privilegedHybrid(this)) return;
        if (NativeBin.hybridRootfsReady()) {
            promoteToHybridIfNeeded("ready-now");
            return;
        }
        hybridReadyPollLeft = 60;
        hybridReadyH.removeCallbacks(hybridReadyPollRun);
        hybridReadyH.postDelayed(hybridReadyPollRun, 1500L);
    }

    private final Runnable hybridReadyPollRun = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (!AtlasPrefs.privilegedHybrid(MainActivity.this)) {
                androidFallbackPending = false;
                return;
            }
            if (NativeBin.hybridRootfsReady()) {
                promoteToHybridIfNeeded("poll");
                return;
            }
            // Re-kick ensure mid-poll if still down (boot path / stuck su).
            if (hybridReadyPollLeft % 10 == 0) {
                requestHybridEnsureAsync();
            }
            hybridReadyPollLeft--;
            if (hybridReadyPollLeft > 0) {
                try {
                    updateStrip("wait " + hybridReadyPollLeft);
                } catch (Exception ignored) {}
                hybridReadyH.postDelayed(this, 2000L);
            } else {
                try {
                    updateStrip("ensure retry");
                } catch (Exception ignored) {}
                // Do not give up permanently — service health tick also heals.
                requestHybridEnsureAsync();
                hybridReadyPollLeft = 30;
                hybridReadyH.postDelayed(this, 5000L);
            }
        }
    };

    private void promoteToHybridIfNeeded(String why) {
        if (!NativeBin.hybridRootfsReady()) return;
        // Only when this session wants Debian (or we flagged fallback wait).
        boolean sessionWantsDeb = SessionHub.MODE_DEBIAN.equals(SessionHub.currentMode());
        if (!sessionWantsDeb && !androidFallbackPending) return;
        if (!sessionWantsDeb && !AtlasPrefs.privilegedHybrid(this)) return;

        hybridReadyPollLeft = 0;
        hybridReadyH.removeCallbacks(hybridReadyPollRun);
        SessionHub.setCurrentMode(SessionHub.MODE_DEBIAN);
        try {
            NativeBin.writePlaneStatus(this, true, true);
        } catch (Exception ignored) {
        }

        // Waiting for hybrid (fallback flag): rebuild into Deb once overlay is up.
        // Never kill a live shell mid apt/sudo bio otherwise.
        if (session != null && session.isRunning()
                && !androidFallbackPending
                && AtlasPrefs.isAuthUiQuietPeriod(this)) {
            try {
                updateStrip("ready · keep");
            } catch (Exception ignored) {
            }
            return;
        }
        if (session != null && session.isRunning() && !androidFallbackPending
                && SessionHub.MODE_DEBIAN.equals(SessionHub.currentMode())) {
            try {
                updateStrip("ready · keep");
            } catch (Exception ignored) {
            }
            updateShellModeButton();
            return;
        }

        androidFallbackPending = false;
        try {
            updateStrip("→ DEBIAN");
            restartSession();
            updateShellModeButton();
        } catch (Exception e) {
            android.util.Log.w("Atlas", "promote hybrid: " + e.getMessage());
        }
    }

    /**
     * Best-effort: {@code atlas-hybrid.sh ensure} via su (shared {@link HybridEnsure}).
     * Handles need-fsck + remount after boot so Deb loads without manual adb.
     */
    private void requestHybridEnsureAsync() {
        if (!AtlasPrefs.privilegedHybrid(this)) return;
        // Rootless product: no elevate → mono fact only, keep Android shell
        if (!HybridEnsure.appCanElevate()) {
            updateStrip("hybrid↓");
            return;
        }
        final boolean waiting = androidFallbackPending
            || SessionHub.MODE_DEBIAN.equals(SessionHub.currentMode());
        HybridEnsure.ensureAsync(this, false, (ok, tookMs, detail) -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                if (ok) {
                    updateShellModeButton();
                    if (waiting) {
                        androidFallbackPending = true;
                        promoteToHybridIfNeeded("ensure");
                    } else {
                        updateStrip("ensure " + tookMs + "ms");
                    }
                } else {
                    updateStrip("hybrid↓");
                    // Do not infinite poll without elevate
                    if (HybridEnsure.appCanElevate()) startHybridReadyPoll();
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void refreshKeepAlive() {
        int live = SessionHub.liveCount();
        AtlasPrefs.setLiveSessionCount(this, live);
        if (AtlasPrefs.keepAlive(this) || live > 0) {
            String note = AtlasPrefs.privilegedHybrid(this)
                ? "hybrid · s" + (SessionHub.index() + 1) + "/" + SessionHub.sessions().size()
                : "user · s" + (SessionHub.index() + 1) + "/" + SessionHub.sessions().size();
            AtlasSessionService.startOrUpdate(this, live, note);
        } else if (AtlasPrefs.authAgentAlways(this)) {
            // Sessions off but agent stays for Remote ADB / hybrid privilege bio
            AtlasSessionService.ensureAuthAgent(this);
        } else {
            AtlasSessionService.stop(this);
        }
    }

    private void pasteToSession() {
        if (session == null || !session.isRunning()) {
            toast("no session");
            return;
        }
        termClient.onPasteTextFromClipboard(session);
        toast("pasted");
    }

    // Host helpers for AtlasTermClient
    @Override public boolean preferHardwareKeys() { return hasHardwareKeyboard() && !softImeWanted; }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // --- AtlasTermClient.Host ---
    @Override public Context context() { return this; }
    @Override public TerminalView terminalView() { return termView; }

    @Override
    public void onSessionFinished(int code) {
        main.post(() -> {
            // Race: old session SIGKILL (-9) after switch must not stomp live strip.
            if (session != null && session.isRunning()) {
                updateStrip(null);
                return;
            }
            updateStrip("exit " + code);
        });
    }

    @Override
    public void onAuthText(String chunk) {
        // Login is in-terminal (touch/mouse). No xAI device-code overlay.
    }

    @Override
    protected void onDestroy() {
        // Keep PTYs if keep-alive FGS holds the process (recents swipe).
        // Explicit Exit calls SessionHub.finishAll().
        if (!AtlasPrefs.keepAlive(this) || SessionHub.liveCount() == 0) {
            if (!isChangingConfigurations()) {
                // only tear down when not rotating and not keep-alive
                if (!AtlasPrefs.keepAlive(this)) {
                    SessionHub.finishAll();
                }
            }
        } else {
            refreshKeepAlive();
        }
        session = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Back hides UI but keep-alive can retain sessions; use Exit to kill all.
        if (AtlasPrefs.keepAlive(this) && SessionHub.liveCount() > 0) {
            refreshKeepAlive();
            moveTaskToBack(true);
        } else {
            exitApp();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** View.setShowSoftInputOnFocus — via reflection so old platform stubs still compile. */
    private static void setShowSoftInputOnFocus(View v, boolean show) {
        if (v == null) return;
        try {
            java.lang.reflect.Method m =
                View.class.getMethod("setShowSoftInputOnFocus", boolean.class);
            m.invoke(v, show);
        } catch (Exception ignored) {
        }
    }
}
