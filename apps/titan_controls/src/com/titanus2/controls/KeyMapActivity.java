package com.titanus2.controls;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.titanus2.controls.ui.UiKit;

/**
 * Keys settings — assign hardware shortcuts without firing them.
 *
 * Flow: Add → press key → choose short/long/double → choose action.
 * Hold modifier: one key held + another key → action.
 * While this screen is open, remaps are suspended (identify only).
 * <p>
 * Keyboard-first (no modifiers): S Specials · A Arrows · O Off · L All layouts ·
 * N new shortcut · H hold key · C hold chord · P add app · Esc cancel / finish.
 */
public class KeyMapActivity extends Activity {
    private KeyMapPrefs prefs;
    private MagicKeyPrefs magic;
    private KeyMapProfiles profiles;

    private TextView accessState;
    private UiKit.Toggle accessToggle;
    private TextView screenOffState;
    private UiKit.Toggle screenOffToggle;
    private UiKit.Toggle longPressSpecialsToggle;
    private TextView captureBanner;
    private TextView holdState;
    private TextView addShortcutBtn;
    private TextView addHoldBtn;
    private LinearLayout shortcutList;
    private LinearLayout profileList;
    private LinearLayout holdList;
    private TextView charSymBtn, charFnBtn, charAltBtn, charPickBtn;
    private TextView fnCtrlBtn, fnStockBtn;
    private TextView methInjectBtn, methKcmBtn;
    private TextView layoutState;
    private TextView hostLayoutState;
    private TextView bLaySpecials, bLayArrows, bLayOff;

    private enum CaptureKind { NONE, SHORTCUT, HOLD_KEY, HOLD_CHORD, SPECIALS_KEY }
    private CaptureKind captureKind = CaptureKind.NONE;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable captureTimeout = this::cancelCapture;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new KeyMapPrefs(this);
        magic = new MagicKeyPrefs(this);
        profiles = new KeyMapProfiles(this);

        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        UiKit.screen(root);
        sc.addView(root);

        // Action bar title is enough — no second "Keys" heading.

        // ---- Key path (a11y screen-on + pad-agent screen-off) — one section ----
        // QA 12.51: fewer section headers / option chrome; two toggles only.
        UiKit.section(root, "Key path");
        accessState = UiKit.summary(root);
        accessToggle = UiKit.toggle(root, "Key service",
            AccessServiceHelper.isListed(this), this::setKeyService);
        screenOffState = UiKit.summary(root);
        screenOffToggle = UiKit.toggle(root, "When screen off",
            prefs.isAllowScreenOff(), this::setScreenOff);

        captureBanner = UiKit.stateLine(root);
        captureBanner.setTextColor(UiKit.WARN);
        captureBanner.setVisibility(View.GONE);

        // ---- Layouts (sticky Specials / Arrows / custom) — under Keys only ----
        UiKit.section(root, "Layouts");
        hostLayoutState = UiKit.summary(root);
        LinearLayout layRow = UiKit.row(root);
        bLaySpecials = UiKit.flexButton(layRow, "Specials", () -> {
            HostLayoutController.applyAction(this,
                KeyMapPrefs.layoutToggleAction(HostLayoutController.MODE_SPECIALS));
            refreshLayoutLabels();
        });
        bLayArrows = UiKit.flexButton(layRow, "Arrows", () -> {
            HostLayoutController.applyAction(this,
                KeyMapPrefs.layoutToggleAction(HostLayoutController.MODE_ARROWS));
            refreshLayoutLabels();
        });
        bLayOff = UiKit.flexButton(layRow, "Off", () -> {
            HostLayoutController.applyAction(this, KeyMapPrefs.ACT_LAYOUT_OFF);
            refreshLayoutLabels();
        });
        LinearLayout layMore = UiKit.row(root);
        UiKit.flexButton(layMore, "All layouts…", () ->
            startActivity(new Intent(this,
                com.titanus2.controls.layouts.CustomLayoutsActivity.class)));
        TextView kbHint = UiKit.mono(root);
        kbHint.setText("S Specials · A Arrows · O Off · L layouts · N Add · H hold · C chord · P app · Esc");

        // ---- Key roles (Fn→Ctrl; OS specials = Sym/Alt/Fn) ----
        UiKit.section(root, "Key roles");
        layoutState = UiKit.summary(root);
        TextView fnLbl = UiKit.stateLine(root);
        fnLbl.setText("Fn");
        fnLbl.setTextColor(UiKit.mutedColor(this));
        LinearLayout fnRow = UiKit.row(root);
        fnCtrlBtn = UiKit.flexButton(fnRow, "Ctrl", () -> setFnMode("ctrl"));
        fnStockBtn = UiKit.flexButton(fnRow, "Stock", () -> setFnMode("stock"));

        // 12.04/12.20: no long-hold letter→specials UI (OEM: hold Sym/Fn + KCM)
        prefs.setLongPressSpecials(false);
        longPressSpecialsToggle = null;
        TextView spLbl = UiKit.stateLine(root);
        spLbl.setText("Specials key (hold + letter)");
        spLbl.setTextColor(UiKit.mutedColor(this));
        LinearLayout charRow = UiKit.row(root);
        // Product: Sym ↔ Fn only (swap like stock roles). No Alt/Other picker clutter.
        charSymBtn = UiKit.flexButton(charRow, "Sym", () -> setCharMod("sym"));
        charFnBtn = UiKit.flexButton(charRow, "Fn", () -> setCharMod("fn"));
        charAltBtn = null;
        charPickBtn = null;
        // 12.77/13.86: kcm product default; inject = a11y KeyActions (opt-in)
        TextView spMethLbl = UiKit.stateLine(root);
        spMethLbl.setText("Specials method");
        spMethLbl.setTextColor(UiKit.mutedColor(this));
        LinearLayout methRow = UiKit.row(root);
        methInjectBtn = UiKit.flexButton(methRow, "Inject",
            () -> applySpecialsMethod(KeyMapPrefs.SPECIALS_METHOD_INJECT));
        methKcmBtn = UiKit.flexButton(methRow, "KCM",
            () -> applySpecialsMethod(KeyMapPrefs.SPECIALS_METHOD_KCM));
        // ---- Shortcuts (optional press actions) ----
        UiKit.section(root, "Shortcuts");
        shortcutList = new LinearLayout(this);
        shortcutList.setOrientation(LinearLayout.VERTICAL);
        root.addView(shortcutList);
        LinearLayout scRow = UiKit.row(root);
        addShortcutBtn = UiKit.flexButton(scRow, "Add", this::startAddShortcut);
        UiKit.flexButton(scRow, "Reset", this::resetFactory);

        // ---- Per-app overrides (beat global while that app is foreground) ----
        UiKit.section(root, "Per app");
        profileList = new LinearLayout(this);
        profileList.setOrientation(LinearLayout.VERTICAL);
        root.addView(profileList);
        UiKit.button(root, "Add app", this::startAddProfile);

        // ---- Hold modifier ----
        UiKit.section(root, "Hold + key");
        holdState = UiKit.summary(root);
        LinearLayout holdRow = UiKit.row(root);
        UiKit.flexButton(holdRow, "Set hold key", this::startSetHoldKey);
        UiKit.flexButton(holdRow, "Clear", () -> {
            magic.setScan(0);
            for (MagicKeyPrefs.ChordEntry ce : new ArrayList<>(magic.listChords())) {
                if (ce.byScan) magic.setChordByScan(ce.id, null);
                else magic.setChordByKeyCode(ce.id, null);
            }
            refreshHold();
            rebuildHoldList();
        });
        holdList = new LinearLayout(this);
        holdList.setOrientation(LinearLayout.VERTICAL);
        root.addView(holdList);
        addHoldBtn = UiKit.button(root, "Add hold shortcut", this::startAddHoldChord);

        setContentView(sc);

        prefs.publishToAgent(this);
        ensureLayoutDefaults();
        rebuildAll();
        // Land focus on Specials so TAB/Enter work without touch.
        if (bLaySpecials != null) {
            bLaySpecials.post(() -> {
                try { bLaySpecials.requestFocus(); } catch (Exception ignored) {}
            });
        }
    }

    /**
     * TitanKey Keys shortcuts — no modifiers (leave Sym/Alt free for capture).
     * While capture is armed, only Esc cancels; other keys pass to identify.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
            return super.dispatchKeyEvent(event);
        }
        int kc = event.getKeyCode();
        // Capture path: Esc only (never steal the key being assigned).
        if (captureKind != CaptureKind.NONE) {
            if (kc == KeyEvent.KEYCODE_ESCAPE) {
                cancelCapture();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        if (event.isAltPressed() || event.isCtrlPressed()
                || event.isMetaPressed() || event.isShiftPressed()) {
            return super.dispatchKeyEvent(event);
        }
        switch (kc) {
            case KeyEvent.KEYCODE_ESCAPE:
                finish();
                return true;
            case KeyEvent.KEYCODE_S:
                HostLayoutController.applyAction(this,
                    KeyMapPrefs.layoutToggleAction(HostLayoutController.MODE_SPECIALS));
                refreshLayoutLabels();
                return true;
            case KeyEvent.KEYCODE_A:
                HostLayoutController.applyAction(this,
                    KeyMapPrefs.layoutToggleAction(HostLayoutController.MODE_ARROWS));
                refreshLayoutLabels();
                return true;
            case KeyEvent.KEYCODE_O:
            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_NUMPAD_0:
                HostLayoutController.applyAction(this, KeyMapPrefs.ACT_LAYOUT_OFF);
                refreshLayoutLabels();
                return true;
            case KeyEvent.KEYCODE_L:
                startActivity(new Intent(this,
                    com.titanus2.controls.layouts.CustomLayoutsActivity.class));
                return true;
            case KeyEvent.KEYCODE_N:
                startAddShortcut();
                return true;
            case KeyEvent.KEYCODE_H:
                startSetHoldKey();
                return true;
            case KeyEvent.KEYCODE_C:
                startAddHoldChord();
                return true;
            case KeyEvent.KEYCODE_P:
                startAddProfile();
                return true;
            default:
                break;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Product default: Fn→Ctrl, specials on Sym, KCM method, sides unbound. */
    private void ensureLayoutDefaults() {
        if (AgentBridge.get(this, AgentBridge.CHAR_MOD, null) == null) {
            AgentBridge.put(this, AgentBridge.CHAR_MOD, "sym");
        }
        if (AgentBridge.get(this, AgentBridge.FN_MODE, null) == null) {
            AgentBridge.put(this, AgentBridge.FN_MODE, "ctrl");
        }
        if (AgentBridge.get(this, AgentBridge.SPECIALS_METHOD, null) == null) {
            KeyMapPrefs.setSpecialsMethod(this, KeyMapPrefs.SPECIALS_METHOD_KCM);
        }
        // Shortcuts on Fn override Ctrl — force stock so the shortcut can fire
        if (hasFnShortcut()) {
            AgentBridge.put(this, AgentBridge.FN_MODE, "stock");
        }
        try { prefs.migrateSideDefaultsNone(); } catch (Exception ignored) {}
    }

    /**
     * Apply specials method on first tap: plane files + Global + tile chrome.
     * FB-IN-2: prior path could leave UI/text ahead of pad-agent until 2nd click.
     */
    private void applySpecialsMethod(String method) {
        KeyMapPrefs.setSpecialsMethod(this, method);
        try { prefs.publishSidesAndSpecialsMethod(this); } catch (Exception ignored) {}
        // Immediate tile chrome from intent (do not wait for plane re-read).
        boolean kcm = KeyMapPrefs.SPECIALS_METHOD_KCM.equals(
            method != null ? method.trim().toLowerCase() : "");
        if (methInjectBtn != null) UiKit.setSelected(methInjectBtn, !kcm);
        if (methKcmBtn != null) UiKit.setSelected(methKcmBtn, kcm);
        refreshLayoutLabels();
    }

    private void resetFactory() {
        prefs.resetFactoryDefaults();
        AgentBridge.put(this, AgentBridge.FN_MODE, "ctrl");
        AgentBridge.put(this, AgentBridge.CHAR_MOD, "sym");
        AgentBridge.put(this, AgentBridge.CHAR_MOD_SCAN, "");
        KeyMapPrefs.setSpecialsMethod(this, KeyMapPrefs.SPECIALS_METHOD_KCM);
        prefs.publishToAgent(this);
        rebuildShortcuts();
        refreshLayoutLabels();
    }

    /** Customize specials letter → glyph map (host US intent). */
    private void editSpecialsMap() {
        java.util.LinkedHashMap<Integer, String> base =
            HostLayoutController.defaultSpecialsMap();
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Integer> keys = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Integer, String> e : base.entrySet()) {
            int kc = e.getKey();
            String def = e.getValue();
            String ov = prefs.getSpecialsOverride(kc);
            String cur = ov != null ? (ov.isEmpty() || "-".equals(ov) ? "∅" : ov) : def;
            String mark = ov != null ? " *" : "";
            labels.add(HostLayoutController.letterLabel(kc) + "  →  " + cur
                + (ov == null ? "" : "  (def " + def + ")") + mark);
            keys.add(kc);
        }
        new AlertDialog.Builder(this)
            .setTitle("Specials map · tap to edit")
            .setItems(labels.toArray(new String[0]), (d, which) -> {
                if (which < 0 || which >= keys.size()) return;
                int kc = keys.get(which);
                String def = base.get(kc);
                String ov = prefs.getSpecialsOverride(kc);
                final android.widget.EditText input = new android.widget.EditText(this);
                input.setSingleLine(true);
                input.setHint("glyph or - to clear mapping");
                input.setText(ov != null ? ov : def);
                new AlertDialog.Builder(this)
                    .setTitle(HostLayoutController.letterLabel(kc) + " → glyph")
                    .setView(input)
                    .setPositiveButton("Save", (dd, w) -> {
                        String g = input.getText() != null
                            ? input.getText().toString().trim() : "";
                        if (g.isEmpty() || g.equals(def)) {
                            prefs.setSpecialsOverride(kc, null); // default
                        } else {
                            prefs.setSpecialsOverride(kc, g);
                        }
                        android.widget.Toast.makeText(this,
                            HostLayoutController.letterLabel(kc) + " → "
                                + (g.isEmpty() ? def : g),
                            android.widget.Toast.LENGTH_SHORT).show();
                    })
                    .setNeutralButton("Default", (dd, w) ->
                        prefs.setSpecialsOverride(kc, null))
                    .setNegativeButton("Cancel", null)
                    .show();
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private void setKeyService(boolean on) {
        boolean ok = AccessServiceHelper.setEnabled(this, on);
        if (!ok) {
            // Fallback: open system screen (user grants once)
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception ignored) {}
            android.widget.Toast.makeText(this,
                "Open Accessibility and enable Titan Controls",
                android.widget.Toast.LENGTH_SHORT).show();
        }
        refreshAccess();
        // Service connect is async — recheck shortly
        h.postDelayed(this::refreshAccess, 400);
        h.postDelayed(this::refreshAccess, 1200);
    }

    private void setScreenOff(boolean on) {
        prefs.setAllowScreenOff(on);
        prefs.publishToAgent(this);
        refreshScreenOff();
    }

    @Override protected void onResume() {
        super.onResume();
        // P0 Key a11y: Keys screen re-assert after wipe / exclusive HID thrash
        // 12.61: force full belt if listed-but-dead; else ensure + a11y_live reassert
        try {
            if (!AccessServiceHelper.isConnected()) {
                AccessServiceHelper.forceUnlockBelt(this);
            } else {
                AccessServiceHelper.ensureDefaultEnabled(this);
                AgentBridge.put(this, AgentBridge.A11Y_LIVE, "1");
            }
        } catch (Exception ignored) {}
        try { TaskbarPin.pinOff(this); } catch (Exception ignored) {}
        // 12.42 B1: Keys open re-heals side chrome poison (wipe/old agent seed)
        try {
            KeyMapPrefs km = new KeyMapPrefs(this);
            km.healSideChromeToFactory();
            km.publishToAgent(this);
        } catch (Exception ignored) {}
        // 11.58: B2 plane + typing unstick when opening Keys (side Specials path)
        try { HostLayoutController.bindApp(this); } catch (Exception ignored) {}
        try { HostLayoutController.healStaleHidPlane(this); } catch (Exception ignored) {}
        try { TypingCursorLock.clear(this); } catch (Exception ignored) {}
        KeyCapture.setUiOpen(true);
        rebuildAll();
        h.post(tick);
    }

    @Override protected void onPause() {
        h.removeCallbacks(tick);
        h.removeCallbacks(captureTimeout);
        cancelCapture();
        KeyCapture.setUiOpen(false);
        super.onPause();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshAccess();
            refreshLayoutLabels();
            h.postDelayed(this, 600);
        }
    };

    private void rebuildAll() {
        rebuildShortcuts();
        rebuildProfiles();
        rebuildHoldList();
        refreshHold();
        refreshAccess();
        refreshScreenOff();
        refreshLayoutLabels();
        refreshCaptureBanner();
    }

    private void refreshAccess() {
        if (accessState == null) return;
        boolean listed = AccessServiceHelper.isListed(this);
        boolean connected = AccessServiceHelper.isConnected();
        if (listed && connected) {
            accessState.setText("On · screen-on shortcuts · pause here");
            accessState.setTextColor(UiKit.mutedColor(this));
        } else if (listed) {
            accessState.setText("Starting…");
            accessState.setTextColor(UiKit.WARN);
        } else {
            accessState.setText("Off · screen-on shortcuts need this");
            accessState.setTextColor(UiKit.WARN);
        }
        if (accessToggle != null) accessToggle.setChecked(listed);
    }

    private void refreshScreenOff() {
        if (screenOffState == null) return;
        boolean on = prefs.isAllowScreenOff();
        if (on) {
            screenOffState.setText("On · OS agent fires remaps (no a11y)");
            screenOffState.setTextColor(UiKit.mutedColor(this));
        } else {
            screenOffState.setText("Off · buttons ignored while display off");
            screenOffState.setTextColor(UiKit.WARN);
        }
        if (screenOffToggle != null) screenOffToggle.setChecked(on);
        if (longPressSpecialsToggle != null) {
            longPressSpecialsToggle.setChecked(prefs.isLongPressSpecials());
        }
    }

    private void refreshCaptureBanner() {
        if (captureBanner == null) return;
        if (captureKind == CaptureKind.NONE) {
            captureBanner.setVisibility(View.GONE);
            return;
        }
        captureBanner.setVisibility(View.VISIBLE);
        switch (captureKind) {
            case SHORTCUT:
                captureBanner.setText("Press the key to assign…");
                break;
            case HOLD_KEY:
                captureBanner.setText("Press the hold key…");
                break;
            case HOLD_CHORD:
                captureBanner.setText("Hold key is set — press the second key…");
                break;
            case SPECIALS_KEY:
                captureBanner.setText("Press the specials modifier key…");
                break;
            default:
                captureBanner.setVisibility(View.GONE);
        }
    }

    private void refreshHold() {
        if (holdState == null) return;
        if (!magic.isSet()) {
            holdState.setText("No hold key");
            holdState.setTextColor(UiKit.mutedColor(this));
        } else {
            int n = magic.listChords().size();
            holdState.setText("Hold " + MagicKeyPrefs.scanLabel(magic.getScan())
                + " + key · " + n + (n == 1 ? " shortcut" : " shortcuts"));
            holdState.setTextColor(UiKit.liveAccent(this));
        }
        if (addHoldBtn != null) {
            addHoldBtn.setEnabled(magic.isSet());
            addHoldBtn.setAlpha(magic.isSet() ? 1f : 0.45f);
        }
    }

    private void refreshLayoutLabels() {
        int sp = KeyMapPrefs.resolveSpecialsScan(this);
        String cm = AgentBridge.get(this, AgentBridge.CHAR_MOD, "sym");
        if (cm == null || cm.isEmpty()) cm = "sym";
        cm = cm.trim().toLowerCase();
        String fn = AgentBridge.get(this, AgentBridge.FN_MODE, "ctrl");
        if (fn == null || fn.isEmpty()) fn = "ctrl";
        boolean specialsIsFn = KeyMapPrefs.isFnScan(sp);
        boolean fnCtrl = "ctrl".equalsIgnoreCase(fn) && !specialsIsFn;
        boolean blocked = hasFnShortcut();
        String spLabel = labelSpecialsScan(sp);
        String meth = KeyMapPrefs.getSpecialsMethod(this);
        String spRole = "Specials = " + spLabel + " · " + meth;
        if (layoutState != null) {
            if (blocked) {
                layoutState.setText("Fn shortcut on · " + spRole);
                layoutState.setTextColor(UiKit.WARN);
            } else if (fnCtrl) {
                layoutState.setText("Fn = Ctrl · " + spRole);
                layoutState.setTextColor(UiKit.mutedColor(this));
            } else {
                layoutState.setText("Fn stock · " + spRole);
                layoutState.setTextColor(UiKit.mutedColor(this));
            }
        }
        if (hostLayoutState != null) {
            try {
                HostLayoutController.loadGlobalDefault(this);
                hostLayoutState.setText(HostLayoutController.statusLine(this)
                    + " · def " + HostLayoutController.modeLabel(
                        HostLayoutController.getGlobalDefault()));
                hostLayoutState.setTextColor(
                    HostLayoutController.isActive(this) ? UiKit.liveAccent(this) : UiKit.mutedColor(this));
            } catch (Exception e) {
                hostLayoutState.setText("Host layout");
            }
        }
        // Cyan fill on active sticky layout tile (keyboard-first feedback).
        try {
            String sticky = HostLayoutController.getSticky();
            if (sticky == null) sticky = HostLayoutController.MODE_OFF;
            sticky = sticky.trim().toLowerCase();
            boolean onSp = HostLayoutController.MODE_SPECIALS.equals(sticky);
            boolean onAr = HostLayoutController.MODE_ARROWS.equals(sticky);
            boolean onOff = HostLayoutController.MODE_OFF.equals(sticky) || sticky.isEmpty();
            // Custom sticky id: no builtin tile claimed.
            if (sticky.startsWith("c_")) {
                onSp = false;
                onAr = false;
                onOff = false;
            }
            if (bLaySpecials != null) UiKit.setSelected(bLaySpecials, onSp);
            if (bLayArrows != null) UiKit.setSelected(bLayArrows, onAr);
            if (bLayOff != null) UiKit.setSelected(bLayOff, onOff);
        } catch (Exception ignored) {}
        if (fnCtrlBtn != null) UiKit.setSelected(fnCtrlBtn, fnCtrl && !blocked);
        if (fnStockBtn != null) UiKit.setSelected(fnStockBtn, !fnCtrl || blocked);
        // Specials method tiles (product default KCM)
        boolean methKcm = KeyMapPrefs.SPECIALS_METHOD_KCM.equals(meth);
        if (methInjectBtn != null) UiKit.setSelected(methInjectBtn, !methKcm);
        if (methKcmBtn != null) UiKit.setSelected(methKcmBtn, methKcm);
        // Highlight from named CHAR_MOD (user intent), not only resolved scan —
        // avoids "Alt selected" while Sym actually owns specials.
        boolean custom = "custom".equals(cm) || "scan".equals(cm) || "other".equals(cm);
        if (!custom) {
            String rawScan = AgentBridge.get(this, AgentBridge.CHAR_MOD_SCAN, null);
            if (rawScan != null && !rawScan.isEmpty() && !AgentBridge.isClearToken(rawScan)
                    && !KeyMapPrefs.isFnScan(sp) && !KeyMapPrefs.isSymScan(sp)
                    && !KeyMapPrefs.isAltScan(sp)) {
                custom = true;
            }
        }
        if (custom) {
            // Custom/other scan: highlight neither Sym nor Fn (legacy planes)
            if (charSymBtn != null) UiKit.setSelected(charSymBtn, false);
            if (charFnBtn != null) UiKit.setSelected(charFnBtn, false);
        } else {
            boolean selSym = "sym".equals(cm) || "symbol".equals(cm)
                || (KeyMapPrefs.isSymScan(sp) && !"fn".equals(cm) && !"alt".equals(cm));
            boolean selFn = "fn".equals(cm) || "function".equals(cm)
                || (KeyMapPrefs.isFnScan(sp) && !"sym".equals(cm) && !"alt".equals(cm));
            boolean selAlt = "alt".equals(cm) || "stock".equals(cm)
                || (KeyMapPrefs.isAltScan(sp) && !"sym".equals(cm) && !"fn".equals(cm));
            // Named preference wins when set
            if ("sym".equals(cm) || "symbol".equals(cm)) {
                selSym = true; selFn = false; selAlt = false;
            } else if ("fn".equals(cm) || "function".equals(cm)) {
                selSym = false; selFn = true; selAlt = false;
            } else if ("alt".equals(cm) || "stock".equals(cm)) {
                selSym = false; selFn = false; selAlt = true;
            }
            if (charSymBtn != null) UiKit.setSelected(charSymBtn, selSym);
            if (charFnBtn != null) UiKit.setSelected(charFnBtn, selFn);
            if (charAltBtn != null) UiKit.setSelected(charAltBtn, selAlt);
            if (charPickBtn != null) UiKit.setSelected(charPickBtn, false);
        }
    }

    private static String labelSpecialsScan(int scan) {
        int c = KeyMapPrefs.canonicalizeScan(scan);
        if (KeyMapPrefs.isFnScan(c)) return "Fn";
        if (KeyMapPrefs.isSymScan(c)) return "Sym";
        if (KeyMapPrefs.isAltScan(c)) return "Alt";
        return MagicKeyPrefs.scanLabel(c > 0 ? c : scan);
    }

    private void setFnMode(String mode) {
        if ("ctrl".equalsIgnoreCase(mode)) {
            clearFnShortcuts();
            // Specials on Fn conflicts with Ctrl — move specials to Sym
            int sp = KeyMapPrefs.resolveSpecialsScan(this);
            if (KeyMapPrefs.isFnScan(sp)) {
                KeyMapPrefs.setSpecialsOwner(this, "sym", 0);
            }
        }
        AgentBridge.put(this, AgentBridge.FN_MODE, mode);
        refreshLayoutLabels();
    }

    private void setCharMod(String mode) {
        if ("fn".equalsIgnoreCase(mode)) {
            clearFnShortcuts();
        }
        KeyMapPrefs.setSpecialsOwner(this, mode, 0);
        refreshLayoutLabels();
    }

    private void startPickSpecialsKey() {
        if (!AccessServiceHelper.isListed(this)) {
            setKeyService(true);
            if (!AccessServiceHelper.isListed(this)) return;
        }
        armCapture(CaptureKind.SPECIALS_KEY, (scan, keyCode) -> {
            int raw = scan > 0 ? scan : 0;
            int c = KeyMapPrefs.canonicalizeScan(raw);
            if (c <= 0) c = raw;
            if (c <= 0) {
                android.widget.Toast.makeText(this, "No key", android.widget.Toast.LENGTH_SHORT)
                    .show();
                return;
            }
            // Prefer raw OEM scan for agent (251 Fn, 253 Sym)
            int store = raw > 0 ? raw : c;
            if (KeyMapPrefs.isFnScan(store)) {
                clearFnShortcuts();
            }
            KeyMapPrefs.setSpecialsOwner(this, null, store);
            refreshLayoutLabels();
            android.widget.Toast.makeText(this,
                "Specials = " + labelSpecialsScan(store) + " · hold + letter",
                android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    private void clearFnShortcuts() {
        if (!hasFnShortcut()) return;
        for (KeyMapPrefs.Press pr : KeyMapPrefs.Press.values()) {
            KeyMapPrefs.Slot sl = KeyMapPrefs.slotByScan(KeyMapPrefs.SCAN_FN, pr);
            if (sl != null) prefs.setAction(sl.id, KeyMapPrefs.ACT_NONE);
        }
        prefs.publishToAgent(this);
        rebuildShortcuts();
    }

    private boolean hasFnShortcut() {
        for (KeyMapPrefs.Press pr : KeyMapPrefs.Press.values()) {
            KeyMapPrefs.Slot sl = KeyMapPrefs.slotByScan(KeyMapPrefs.SCAN_FN, pr);
            if (sl == null) continue;
            String a = prefs.getAction(sl.id);
            if (a != null
                    && !KeyMapPrefs.ACT_DEFAULT.equals(a)
                    && !KeyMapPrefs.ACT_NONE.equals(a)) {
                return true;
            }
        }
        return false;
    }

    // ---------- Capture ----------

    private void cancelCapture() {
        h.removeCallbacks(captureTimeout);
        KeyCapture.disarm();
        captureKind = CaptureKind.NONE;
        if (addShortcutBtn != null) {
            addShortcutBtn.setText("Add");
            UiKit.setSelected(addShortcutBtn, false);
        }
        if (addHoldBtn != null) {
            addHoldBtn.setText("Add hold shortcut");
            UiKit.setSelected(addHoldBtn, false);
        }
        refreshCaptureBanner();
    }

    private void armCapture(CaptureKind kind, KeyCapture.Listener listener) {
        cancelCapture();
        captureKind = kind;
        KeyCapture.arm((scan, keyCode) -> h.post(() -> {
            captureKind = CaptureKind.NONE;
            refreshCaptureBanner();
            if (addShortcutBtn != null) {
                addShortcutBtn.setText("Add");
                UiKit.setSelected(addShortcutBtn, false);
            }
            if (addHoldBtn != null) {
                addHoldBtn.setText("Add hold shortcut");
                UiKit.setSelected(addHoldBtn, false);
            }
            listener.onKey(scan, keyCode);
        }));
        refreshCaptureBanner();
        h.postDelayed(captureTimeout, 10000);
        if (kind == CaptureKind.SHORTCUT && addShortcutBtn != null) {
            addShortcutBtn.setText("Cancel");
            UiKit.setSelected(addShortcutBtn, true);
        }
        if ((kind == CaptureKind.HOLD_CHORD || kind == CaptureKind.HOLD_KEY)
                && addHoldBtn != null && kind == CaptureKind.HOLD_CHORD) {
            addHoldBtn.setText("Cancel");
            UiKit.setSelected(addHoldBtn, true);
        }
    }

    private void startAddShortcut() {
        if (captureKind == CaptureKind.SHORTCUT) {
            cancelCapture();
            return;
        }
        if (!AccessServiceHelper.isListed(this)) {
            setKeyService(true);
            if (!AccessServiceHelper.isListed(this)) return;
        }
        armCapture(CaptureKind.SHORTCUT, this::onShortcutKeyPressed);
    }

    private void startSetHoldKey() {
        if (!AccessServiceHelper.isListed(this)) {
            setKeyService(true);
            if (!AccessServiceHelper.isListed(this)) return;
        }
        armCapture(CaptureKind.HOLD_KEY, (scan, keyCode) -> {
            int c = KeyMapPrefs.canonicalizeScan(scan);
            if (c <= 0) c = scan;
            magic.setScan(c);
            // Default mode: chords only (no "Mode" UI)
            magic.setDefaultMode(MagicKeyPrefs.MODE_CHORDS);
            refreshHold();
            rebuildHoldList();
        });
    }

    private void startAddHoldChord() {
        if (captureKind == CaptureKind.HOLD_CHORD) {
            cancelCapture();
            return;
        }
        if (!magic.isSet()) {
            startSetHoldKey();
            return;
        }
        if (!AccessServiceHelper.isListed(this)) {
            setKeyService(true);
            if (!AccessServiceHelper.isListed(this)) return;
        }
        armCapture(CaptureKind.HOLD_CHORD, this::onHoldChordKeyPressed);
    }

    private void onShortcutKeyPressed(int scan, int keyCode) {
        h.removeCallbacks(captureTimeout);
        int c = KeyMapPrefs.canonicalizeScan(scan);
        if (!KeyMapPrefs.isManagedScan(c)) {
            new AlertDialog.Builder(this)
                .setTitle("Key")
                .setMessage("Back, Recents, Fn, side keys, Sym, Alt.\n\n"
                    + "Letter keys: use Hold + key.")
                .setPositiveButton("OK", null)
                .show();
            return;
        }
        String name = MagicKeyPrefs.scanLabel(c);
        // Specials layer key can still be remapped (runtime honors user actions)
        if (KeyMapPrefs.isCharModScan(this, c)) {
            name = name + " (specials key)";
        }
        String[] presses = new String[] {
            "Short press", "Long press", "Double press",
            "Layout modifier (hold + toggle)"
        };
        KeyMapPrefs.Press[] kinds = new KeyMapPrefs.Press[] {
            KeyMapPrefs.Press.SHORT, KeyMapPrefs.Press.LONG, KeyMapPrefs.Press.DOUBLE
        };
        new AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(presses, (d, which) -> {
                if (which == 3) {
                    // Full layout-modifier mode: pick layout, short=none
                    pickLayoutModifierForScan(c);
                    return;
                }
                KeyMapPrefs.Slot slot = KeyMapPrefs.slotByScan(c, kinds[which]);
                if (slot == null) return;
                pickAction(slot.id, false, 0, 0, kinds[which]);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void pickLayoutModifierForScan(int scan) {
        KeyActionPicker.showLayoutModifier(this, MagicKeyPrefs.scanLabel(scan),
            false, false, null, new KeyActionPicker.Listener() {
                @Override public void onPicked(String action) {
                    // Single action only — apply to long if hold, double if toggle
                    String hid = KeyMapPrefs.layoutHoldId(action);
                    String tid = KeyMapPrefs.layoutToggleId(action);
                    if (hid != null) {
                        prefs.assignLayoutModifier(scan, hid);
                    } else if (tid != null) {
                        prefs.assignLayoutModifier(scan, tid);
                    } else if (KeyMapPrefs.ACT_LAYOUT_OFF.equals(action)) {
                        prefs.assignLayoutModifier(scan, "off");
                    }
                    prefs.publishToAgent(KeyMapActivity.this);
                    rebuildShortcuts();
                    refreshLayoutLabels();
                }
                @Override public void onPickApp() {}
                @Override public void onLayoutModifier(String layoutId) {
                    prefs.assignLayoutModifier(scan, layoutId);
                    prefs.publishToAgent(KeyMapActivity.this);
                    rebuildShortcuts();
                    refreshLayoutLabels();
                }
            });
    }

    private void onHoldChordKeyPressed(int scan, int keyCode) {
        h.removeCallbacks(captureTimeout);
        int c = KeyMapPrefs.canonicalizeScan(scan);
        // Don't bind hold key to itself
        if (magic.isSet() && c == magic.getScan()) {
            new AlertDialog.Builder(this)
                .setMessage("That is the hold key. Press a different key.")
                .setPositiveButton("OK", null)
                .show();
            return;
        }
        pickAction(null, true, c, keyCode);
    }

    // ---------- Lists ----------

    private void rebuildShortcuts() {
        if (shortcutList == null) return;
        shortcutList.removeAllViews();
        List<KeyMapPrefs.ShortcutRow> rows = prefs.listVisibleRows();
        if (rows.isEmpty()) {
            TextView empty = UiKit.stateLine(shortcutList);
            empty.setText("none — Add, then press a key");
            empty.setTextColor(UiKit.mutedColor(this));
            return;
        }
        for (KeyMapPrefs.ShortcutRow row : rows) {
            if (row.isLayoutModifier()) {
                String name = layoutName(row.layoutId);
                String key = MagicKeyPrefs.scanLabel(row.scan);
                String holdAct = row.longSlot != null
                    ? prefs.getAction(row.longSlot.id) : null;
                String togAct = row.doubleSlot != null
                    ? prefs.getAction(row.doubleSlot.id) : null;
                String mode = KeyMapPrefs.layoutModeLine(holdAct, togAct);
                TextView tv = UiKit.listRow(shortcutList,
                    "Layout · " + name,
                    key + " · " + mode,
                    () -> editLayoutModifier(row));
                tv.setOnLongClickListener(v -> {
                    confirmRemoveLayoutModifier(row);
                    return true;
                });
            } else {
                KeyMapPrefs.Slot slot = row.single;
                TextView tv = UiKit.listRow(shortcutList,
                    formatSlotAction(slot.id), pressLabel(slot), () -> editSlot(slot));
                tv.setOnLongClickListener(v -> {
                    confirmRemoveSlot(slot);
                    return true;
                });
            }
        }
    }

    private String layoutName(String id) {
        try {
            return new com.titanus2.controls.layouts.CustomLayoutStore(this).nameOf(id);
        } catch (Exception e) {
            return id == null ? "?" : id;
        }
    }

    private void editLayoutModifier(KeyMapPrefs.ShortcutRow row) {
        String name = layoutName(row.layoutId);
        new AlertDialog.Builder(this)
            .setTitle("Layout · " + name)
            .setItems(new String[] {
                "Change layout…",
                "Remove modifier"
            }, (d, which) -> {
                if (which == 0) {
                    pickLayoutModifierForScan(row.scan);
                } else if (which == 1) {
                    prefs.clearLayoutModifier(row.scan);
                    prefs.publishToAgent(this);
                    rebuildShortcuts();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void confirmRemoveLayoutModifier(KeyMapPrefs.ShortcutRow row) {
        new AlertDialog.Builder(this)
            .setTitle("Remove")
            .setMessage(MagicKeyPrefs.scanLabel(row.scan)
                + "\nLayout · " + layoutName(row.layoutId))
            .setPositiveButton("Remove", (d, w) -> {
                prefs.clearLayoutModifier(row.scan);
                prefs.publishToAgent(this);
                rebuildShortcuts();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void rebuildProfiles() {
        if (profileList == null) return;
        profileList.removeAllViews();
        List<String> pkgs = profiles.listPackages();
        if (pkgs.isEmpty()) {
            TextView empty = UiKit.stateLine(profileList);
            empty.setText("none — overrides while that app is open");
            empty.setTextColor(UiKit.mutedColor(this));
            return;
        }
        for (String pkg : pkgs) {
            int nKeys = profiles.overrideCount(pkg);
            int nHold = profiles.chordCount(pkg);
            String sub;
            if (nHold == 0) sub = nKeys + (nKeys == 1 ? " key" : " keys");
            else if (nKeys == 0) sub = nHold + (nHold == 1 ? " hold" : " holds");
            else sub = nKeys + " keys · " + nHold + " holds";
            final String p = pkg;
            TextView row = UiKit.listRow(profileList, profiles.getLabel(pkg), sub,
                () -> startActivity(KeyMapProfileActivity.intent(this, p)));
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                    .setTitle("Delete profile")
                    .setMessage(profiles.getLabel(p))
                    .setPositiveButton("Delete", (d, w) -> {
                        profiles.removeProfile(p);
                        rebuildProfiles();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
                return true;
            });
        }
    }

    private void startAddProfile() {
        List<String[]> apps = KeyMapProfiles.launcherApps(this);
        List<String> labels = new ArrayList<>();
        List<String> pkgs = new ArrayList<>();
        for (String[] a : apps) {
            labels.add(a[0]);
            pkgs.add(a[1]);
        }
        new AlertDialog.Builder(this)
            .setTitle("App")
            .setItems(labels.toArray(new String[0]), (d, which) -> {
                String pkg = pkgs.get(which);
                String label = labels.get(which);
                profiles.ensureProfile(pkg, label);
                rebuildProfiles();
                startActivity(KeyMapProfileActivity.intent(this, pkg));
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void rebuildHoldList() {
        if (holdList == null) return;
        holdList.removeAllViews();
        List<MagicKeyPrefs.ChordEntry> chords = magic.listChords();
        if (!magic.isSet()) return;
        if (chords.isEmpty()) {
            TextView empty = UiKit.summary(holdList);
            empty.setText("No hold shortcuts yet");
            empty.setTextColor(UiKit.mutedColor(this));
            return;
        }
        for (MagicKeyPrefs.ChordEntry ce : chords) {
            String key = ce.byScan
                ? MagicKeyPrefs.scanLabel(ce.id)
                : MagicKeyPrefs.keyCodeLabel(ce.id);
            String label = "hold + " + key + "  →  " + KeyMapPrefs.actionLabel(ce.action);
            final MagicKeyPrefs.ChordEntry entry = ce;
            TextView row = UiKit.button(holdList, label, () ->
                pickAction(null, true, entry.byScan ? entry.id : 0,
                    entry.byScan ? 0 : entry.id));
            row.setOnLongClickListener(v -> {
                if (entry.byScan) magic.setChordByScan(entry.id, null);
                else magic.setChordByKeyCode(entry.id, null);
                rebuildHoldList();
                refreshHold();
                return true;
            });
        }
    }

    private static String pressLabel(KeyMapPrefs.Slot slot) {
        return KeyMapPrefs.keyPressLabel(slot);
    }

    private String formatSlotAction(String slotId) {
        String act = prefs.getAction(slotId);
        if (act.startsWith(KeyMapPrefs.ACT_APP_PREFIX)) {
            String lbl = prefs.getAppLabel(slotId);
            if (!lbl.isEmpty()) return lbl;
        }
        return KeyMapPrefs.actionLabel(act);
    }

    private void editSlot(KeyMapPrefs.Slot slot) {
        boolean factory = prefs.isFactoryDefault(slot.id);
        String[] items = factory
            ? new String[] { "Change action…", "Remove" }
            : new String[] { "Change action…", "Reset default", "Remove" };
        new AlertDialog.Builder(this)
            .setTitle(formatSlotAction(slot.id) + "  ·  " + pressLabel(slot))
            .setItems(items, (d, which) -> {
                if (which == 0) {
                    pickAction(slot.id, false, 0, 0);
                } else if (!factory && which == 1) {
                    prefs.resetSlot(slot.id);
                    prefs.publishToAgent(this);
                    rebuildShortcuts();
                } else {
                    confirmRemoveSlot(slot);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void confirmRemoveSlot(KeyMapPrefs.Slot slot) {
        new AlertDialog.Builder(this)
            .setTitle("Remove")
            .setMessage(pressLabel(slot) + "\n" + formatSlotAction(slot.id))
            .setPositiveButton("Remove", (d, w) -> {
                prefs.removeSlot(slot.id);
                prefs.publishToAgent(this);
                rebuildShortcuts();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ---------- Action picker (categorized) ----------

    private void pickAction(String slotId, boolean chord, int scan, int keyCode) {
        pickAction(slotId, chord, scan, keyCode, null);
    }

    private void pickAction(String slotId, boolean chord, int scan, int keyCode,
                            KeyMapPrefs.Press pressKind) {
        String title = chord
            ? ("hold + " + (keyCode > 0
                ? MagicKeyPrefs.keyCodeLabel(keyCode)
                : MagicKeyPrefs.scanLabel(scan)))
            : (slotId != null ? slotId : "Action");
        KeyMapPrefs.Slot s = null;
        if (!chord && slotId != null) {
            for (KeyMapPrefs.Slot x : KeyMapPrefs.SLOTS) {
                if (x.id.equals(slotId)) { s = x; break; }
            }
            if (s != null) {
                title = pressLabel(s);
                if (pressKind == null) pressKind = s.press;
            }
        }
        final boolean allowRemove = !chord && slotId != null;
        final KeyMapPrefs.Slot slotRef = s;
        // P0 B1: side slots never offer Home/Recents/Camera in the picker
        final boolean banSideChrome = !chord && slotId != null
            && KeyMapPrefs.isSideSlotId(slotId);
        KeyActionPicker.show(this, title, chord, allowRemove, pressKind, banSideChrome,
            new KeyActionPicker.Listener() {
            @Override public void onPicked(String action) {
                applyAction(slotId, chord, scan, keyCode, action, null);
            }
            @Override public void onPickApp() {
                pickApp(slotId, chord, scan, keyCode);
            }
            @Override public void onRemove() {
                if (slotId != null) {
                    prefs.removeSlot(slotId);
                    prefs.publishToAgent(KeyMapActivity.this);
                    rebuildShortcuts();
                }
            }
            @Override public void onLayoutModifier(String layoutId) {
                int sc = scan;
                if (slotRef != null) sc = slotRef.scan;
                if (sc <= 0 && slotId != null) {
                    for (KeyMapPrefs.Slot x : KeyMapPrefs.SLOTS) {
                        if (x.id.equals(slotId)) { sc = x.scan; break; }
                    }
                }
                if (sc > 0) {
                    prefs.assignLayoutModifier(sc, layoutId);
                    prefs.publishToAgent(KeyMapActivity.this);
                    rebuildShortcuts();
                    refreshLayoutLabels();
                }
            }
        });
    }

    private void applyAction(String slotId, boolean chord, int scan, int keyCode,
                             String action, String appLabel) {
        if (chord) {
            if (scan > 0 && KeyMapPrefs.isManagedScan(scan)) {
                magic.setChordByScan(scan, action);
            } else if (keyCode > 0) {
                magic.setChordByKeyCode(keyCode, action);
            } else if (scan > 0) {
                magic.setChordByScan(scan, action);
            }
            rebuildHoldList();
            refreshHold();
        } else if (slotId != null) {
            prefs.setAction(slotId, action);
            if (appLabel != null) prefs.setAppLabel(slotId, appLabel);
            else if (!action.startsWith(KeyMapPrefs.ACT_APP_PREFIX)) {
                prefs.setAppLabel(slotId, "");
            }
            prefs.publishToAgent(this);
            // Mid-HID-session reassign (e.g. side → mouse) must re-pin silence
            try {
                new TempKeyMapStack(this).refreshSilenceIfActive(this, prefs, null);
            } catch (Exception ignored) {}
            rebuildShortcuts();
            // Shortcut on a layout key → free that key from layout role
            for (KeyMapPrefs.Slot s : KeyMapPrefs.SLOTS) {
                if (!s.id.equals(slotId)) continue;
                if (KeyMapPrefs.ACT_DEFAULT.equals(action)
                        || KeyMapPrefs.ACT_NONE.equals(action)) {
                    break;
                }
                if (KeyMapPrefs.isFnScan(s.scan)) {
                    AgentBridge.put(this, AgentBridge.FN_MODE, "stock");
                    if (KeyMapPrefs.isFnScan(KeyMapPrefs.resolveSpecialsScan(this))) {
                        KeyMapPrefs.setSpecialsOwner(this, "sym", 0);
                    }
                } else if (KeyMapPrefs.isCharModScan(this, s.scan)) {
                    // Shortcut wins over specials role — move specials off this key
                    if (KeyMapPrefs.isSymScan(s.scan)) {
                        KeyMapPrefs.setSpecialsOwner(this, "alt", 0);
                    } else if (KeyMapPrefs.isAltScan(s.scan)) {
                        KeyMapPrefs.setSpecialsOwner(this, "sym", 0);
                    } else {
                        KeyMapPrefs.setSpecialsOwner(this, "sym", 0);
                    }
                }
                break;
            }
            refreshLayoutLabels();
        }
    }

    private void pickApp(String slotId, boolean chord, int scan, int keyCode) {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        Collections.sort(apps, (a, b) -> a.loadLabel(pm).toString()
            .compareToIgnoreCase(b.loadLabel(pm).toString()));
        List<String> labels = new ArrayList<>();
        List<String> pkgs = new ArrayList<>();
        for (ResolveInfo ri : apps) {
            labels.add(ri.loadLabel(pm).toString());
            pkgs.add(ri.activityInfo.packageName);
        }
        new AlertDialog.Builder(this)
            .setTitle("App")
            .setItems(labels.toArray(new String[0]), (d, which) ->
                pickActivity(slotId, chord, scan, keyCode, pkgs.get(which), labels.get(which)))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void pickActivity(String slotId, boolean chord, int scan, int keyCode,
                              String pkg, String appLabel) {
        PackageManager pm = getPackageManager();
        List<String> labels = new ArrayList<>();
        List<String> comps = new ArrayList<>();
        labels.add(appLabel);
        comps.add(pkg);
        try {
            ActivityInfo[] acts = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities;
            if (acts != null) {
                for (ActivityInfo ai : acts) {
                    if (!ai.exported) continue;
                    String name = ai.name;
                    String shortN = name.contains(".")
                        ? name.substring(name.lastIndexOf('.') + 1) : name;
                    labels.add(shortN);
                    comps.add(pkg + "/" + name);
                }
            }
        } catch (Exception ignored) {}
        new AlertDialog.Builder(this)
            .setTitle(appLabel)
            .setItems(labels.toArray(new String[0]), (d, which) -> {
                applyAction(slotId, chord, scan, keyCode,
                    KeyMapPrefs.ACT_APP_PREFIX + comps.get(which),
                    labels.get(which));
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

}
