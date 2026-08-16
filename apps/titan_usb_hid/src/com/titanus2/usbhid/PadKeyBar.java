package com.titanus2.usbhid;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.titanus2.usbhid.ui.UiKit;

/**
 * Pad chrome key bars: F-row, Atlas-style extra keys (arrows on the right),
 * modifiers + symbols from the Titan HW specials layer.
 */
final class PadKeyBar {
    interface Sink {
        void tap(int mod, int hidUsage);
    }

    static final int U_GRAVE = 0x35; /* ` / ~ */
    static final int U_ESC = 0x29;
    static final int U_TAB = 0x2b;
    static final int U_BKSP = 0x2a;
    static final int U_SLASH = 0x38;
    static final int U_HOME = 0x4a;
    static final int U_END = 0x4d;
    static final int U_PGUP = 0x4b;
    static final int U_PGDN = 0x4e;
    static final int U_UP = 0x52;
    static final int U_DOWN = 0x51;
    static final int U_LEFT = 0x50;
    static final int U_RIGHT = 0x4f;
    static final int U_F1 = 0x3a;
    static final int U_PRTSCR = 0x46;
    static final int U_SCRLK = 0x47;
    static final int U_PAUSE = 0x48;
    static final int U_INS = 0x49;
    static final int U_DEL = 0x4c;
    static final int U_CAPS = 0x39;
    static final int MOD_CTRL = 0x01;
    static final int MOD_SHIFT = 0x02;
    static final int MOD_ALT = 0x04;
    static final int MOD_META = 0x08; /* Left GUI — Win / Super / Cmd */

    /** Titan Alt/specials layer — same glyphs as Controls HostLayoutController. */
    static final String[] HW_SYMBOLS = {
        "0", "1", "2", "3", "(", ")", "_", "-", "/", ":",
        "@", "4", "5", "6", "*", "#", "+", "\"", "'",
        "!", "7", "8", "9", ".", ",", "?",
        "`", "~", "[", "]", "{", "}", "\\", "|", ";", "<", ">", "=",
    };

    private final Context ctx;
    private final Sink sink;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean stickyCtrl;
    private boolean stickyAlt;
    private boolean stickyShift;
    private boolean stickyMeta;
    private boolean capsOn;
    /** Finger currently down on a virtual modifier (multitouch Alt+Tab). */
    private boolean heldCtrl;
    private boolean heldAlt;
    private boolean heldShift;
    private boolean heldMeta;
    private boolean usedCtrl;
    private boolean usedAlt;
    private boolean usedShift;
    private boolean usedMeta;
    private TextView btnCtrl;
    private TextView btnAlt;
    private TextView btnShift;
    private TextView btnMeta;
    private TextView btnCaps;
    private TextView btnCtrl2;
    private TextView btnAlt2;
    private TextView btnMeta2;

    PadKeyBar(Context ctx, Sink sink) {
        this.ctx = ctx;
        this.sink = sink;
    }

    int currentMod() {
        int m = 0;
        if (stickyCtrl || heldCtrl) m |= MOD_CTRL;
        if (stickyAlt || heldAlt) m |= MOD_ALT;
        if (stickyShift || heldShift || capsOn) m |= MOD_SHIFT;
        if (stickyMeta || heldMeta) m |= MOD_META;
        return m;
    }

    private void markModsUsed() {
        if (heldCtrl || stickyCtrl) usedCtrl = true;
        if (heldAlt || stickyAlt) usedAlt = true;
        if (heldShift || stickyShift) usedShift = true;
        if (heldMeta || stickyMeta) usedMeta = true;
    }

    LinearLayout buildFnRow(int keyH) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setMotionEventSplittingEnabled(true);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.bottomMargin = UiKit.GAP;
        wrap.setLayoutParams(wlp);
        LinearLayout r1 = line();
        tile(r1, "Esc", U_ESC, keyH);
        tileGrave(r1, keyH);
        for (int i = 0; i < 6; i++) tile(r1, "F" + (i + 1), U_F1 + i, keyH);
        wrap.addView(r1);
        LinearLayout r2 = line();
        for (int i = 6; i < 12; i++) tile(r2, "F" + (i + 1), U_F1 + i, keyH);
        tile(r2, "Prt", U_PRTSCR, keyH);
        tile(r2, "Pse", U_PAUSE, keyH);
        wrap.addView(r2);
        return wrap;
    }

    /**
     * Optional Atlas-like bar: two key rows on the left, inverted-T arrows
     * on the right, same total height.
     */
    LinearLayout buildExtra(int rowH) {
        LinearLayout root = new LinearLayout(ctx);
        root.setMotionEventSplittingEnabled(true);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = UiKit.GAP;
        root.setLayoutParams(rlp);

        LinearLayout left = new LinearLayout(ctx);
        left.setMotionEventSplittingEnabled(true);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 5f);
        left.setLayoutParams(llp);
        LinearLayout a = line();
        tile(a, "Esc", U_ESC, rowH);
        tileGrave(a, rowH);
        tile(a, "Bksp", U_BKSP, rowH);
        tile(a, "/", U_SLASH, rowH);
        tile(a, "Home", U_HOME, rowH);
        tile(a, "End", U_END, rowH);
        left.addView(a);
        LinearLayout b = line();
        tile(b, "Tab", U_TAB, rowH);
        btnCtrl2 = tile(b, "Ctrl", -1, rowH);
        bindMod(btnCtrl2, 0xe0, MOD_CTRL);
        btnAlt2 = tile(b, "Alt", -1, rowH);
        bindMod(btnAlt2, 0xe2, MOD_ALT);
        btnMeta2 = tile(b, "Meta", -1, rowH);
        bindMod(btnMeta2, 0xe3, MOD_META);
        tile(b, "PgUp", U_PGUP, rowH);
        tile(b, "PgDn", U_PGDN, rowH);
        left.addView(b);
        root.addView(left);

        LinearLayout arrows = new LinearLayout(ctx);
        arrows.setMotionEventSplittingEnabled(true);
        arrows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f);
        alp.leftMargin = UiKit.GAP;
        arrows.setLayoutParams(alp);
        LinearLayout up = line();
        spacer(up, rowH);
        arrowTile(up, "↑", U_UP, rowH);
        spacer(up, rowH);
        arrows.addView(up);
        LinearLayout dir = line();
        arrowTile(dir, "←", U_LEFT, rowH);
        arrowTile(dir, "↓", U_DOWN, rowH);
        arrowTile(dir, "→", U_RIGHT, rowH);
        arrows.addView(dir);
        root.addView(arrows);
        paintMods();
        return root;
    }

    LinearLayout buildMods(int keyH) {
        LinearLayout row = line();
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = UiKit.GAP;
        row.setLayoutParams(rlp);
        btnAlt = tile(row, "Alt", -1, keyH);
        bindMod(btnAlt, 0xe2, MOD_ALT);
        btnShift = tile(row, "Shift", -1, keyH);
        bindMod(btnShift, 0xe1, MOD_SHIFT);
        btnMeta = tile(row, "Meta", -1, keyH);
        bindMod(btnMeta, 0xe3, MOD_META);
        btnCaps = tile(row, "Caps", -1, keyH);
        btnCaps.setOnClickListener(v -> toggleCaps());
        tile(row, "Tab", U_TAB, keyH);
        TextView sym = tile(row, "Sym", -1, keyH);
        sym.setOnClickListener(v -> showSymbols());
        paintMods();
        return row;
    }

    private LinearLayout line() {
        LinearLayout row = new LinearLayout(ctx);
        row.setMotionEventSplittingEnabled(true);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UiKit.GAP / 2;
        row.setLayoutParams(lp);
        return row;
    }

    private TextView tile(LinearLayout row, String lab, int usage, int hPx) {
        TextView b = new TextView(ctx);
        b.setText(lab);
        b.setTextColor(UiKit.textColor(ctx));
        b.setTextSize(lab.length() > 3 ? 11f : 13f);
        b.setGravity(Gravity.CENTER);
        b.setBackground(UiKit.square(UiKit.TILE));
        b.setClickable(true);
        b.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, hPx, 1f);
        lp.setMargins(0, 0, UiKit.GAP / 2, 0);
        b.setLayoutParams(lp);
        if (usage >= 0) {
            bindKey(b, usage);
        }
        row.addView(b);
        return b;
    }

    /** Visible ` / ~ (HID 0x35). Tap = ~ ; Shift held or long-press = `. */
    private void tileGrave(LinearLayout row, int hPx) {
        TextView b = tile(row, "~", -1, hPx);
        b.setTextSize(16f);
        b.setOnTouchListener((v, ev) -> {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                v.setPressed(true);
                markModsUsed();
                boolean wantGrave = stickyShift || heldShift;
                new Thread(() -> {
                    if (!wantGrave) HidControl.keyDown(0xe1);
                    HidControl.keyDown(U_GRAVE);
                    KeyLedClient.bumpActivity();
                }).start();
                v.setTag(wantGrave ? Boolean.TRUE : Boolean.FALSE);
                return true;
            }
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
                boolean wantGrave = Boolean.TRUE.equals(v.getTag());
                new Thread(() -> {
                    HidControl.keyUp(U_GRAVE);
                    if (!wantGrave && !stickyShift && !heldShift) HidControl.keyUp(0xe1);
                }).start();
                return true;
            }
            return true;
        });
    }

    /** Hold/press with a second finger — View click cannot chord Alt+Tab. */
    private void bindKey(TextView b, int usage) {
        b.setOnTouchListener((v, ev) -> {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                v.setPressed(true);
                markModsUsed();
                new Thread(() -> {
                    HidControl.keyDown(usage);
                    KeyLedClient.bumpActivity();
                }).start();
                return true;
            }
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
                new Thread(() -> HidControl.keyUp(usage)).start();
                return true;
            }
            return true;
        });
    }

    private void bindMod(TextView b, int hidUsage, int mask) {
        b.setOnTouchListener((v, ev) -> {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                setHeld(mask, true);
                setUsed(mask, false);
                new Thread(() -> HidControl.keyDown(hidUsage)).start();
                paintMods();
                return true;
            }
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                boolean used = isUsed(mask);
                boolean latched = isSticky(mask);
                setHeld(mask, false);
                if (a == MotionEvent.ACTION_CANCEL) {
                    setSticky(mask, false);
                    new Thread(() -> HidControl.keyUp(hidUsage)).start();
                } else if (latched) {
                    setSticky(mask, false);
                    new Thread(() -> HidControl.keyUp(hidUsage)).start();
                } else if (used) {
                    new Thread(() -> HidControl.keyUp(hidUsage)).start();
                } else {
                    setSticky(mask, true);
                    // stay HID-down (latched)
                }
                paintMods();
                return true;
            }
            return true;
        });
    }

    private void setHeld(int mask, boolean on) {
        if (mask == MOD_CTRL) heldCtrl = on;
        else if (mask == MOD_ALT) heldAlt = on;
        else if (mask == MOD_SHIFT) heldShift = on;
        else if (mask == MOD_META) heldMeta = on;
    }

    private void setUsed(int mask, boolean on) {
        if (mask == MOD_CTRL) usedCtrl = on;
        else if (mask == MOD_ALT) usedAlt = on;
        else if (mask == MOD_SHIFT) usedShift = on;
        else if (mask == MOD_META) usedMeta = on;
    }

    private boolean isUsed(int mask) {
        if (mask == MOD_CTRL) return usedCtrl;
        if (mask == MOD_ALT) return usedAlt;
        if (mask == MOD_SHIFT) return usedShift;
        if (mask == MOD_META) return usedMeta;
        return false;
    }

    private boolean isSticky(int mask) {
        if (mask == MOD_CTRL) return stickyCtrl;
        if (mask == MOD_ALT) return stickyAlt;
        if (mask == MOD_SHIFT) return stickyShift;
        if (mask == MOD_META) return stickyMeta;
        return false;
    }

    private void setSticky(int mask, boolean on) {
        if (mask == MOD_CTRL) stickyCtrl = on;
        else if (mask == MOD_ALT) stickyAlt = on;
        else if (mask == MOD_SHIFT) stickyShift = on;
        else if (mask == MOD_META) stickyMeta = on;
    }

    private void arrowTile(LinearLayout row, String lab, int usage, int hPx) {
        TextView b = tile(row, lab, usage, hPx);
        b.setTextSize(18f);
        b.setTypeface(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD);
        b.setOnLongClickListener(v -> {
            fireUsage(usage);
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!v.isPressed()) return;
                    fireUsage(usage);
                    h.postDelayed(this, 70);
                }
            }, 280);
            return true;
        });
    }

    private void spacer(LinearLayout row, int hPx) {
        View s = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, hPx, 1f);
        s.setLayoutParams(lp);
        row.addView(s);
    }

    private void fireUsage(int usage) {
        markModsUsed();
        new Thread(() -> {
            HidControl.keyTapKeepMods(usage);
            KeyLedClient.bumpActivity();
        }).start();
    }

    private void toggleCaps() {
        capsOn = !capsOn;
        new Thread(() -> sink.tap(0, U_CAPS)).start();
        paintMods();
    }

    private void paintMods() {
        style(btnCtrl, stickyCtrl || heldCtrl);
        style(btnCtrl2, stickyCtrl || heldCtrl);
        style(btnAlt, stickyAlt || heldAlt);
        style(btnAlt2, stickyAlt || heldAlt);
        style(btnShift, stickyShift || heldShift);
        style(btnMeta, stickyMeta || heldMeta);
        style(btnMeta2, stickyMeta || heldMeta);
        style(btnCaps, capsOn);
    }

    private void style(TextView b, boolean on) {
        if (b == null) return;
        UiKit.setSelected(b, on);
    }

    private void showSymbols() {
        ScrollView sc = new ScrollView(ctx);
        GridLayout grid = new GridLayout(ctx);
        grid.setColumnCount(6);
        int pad = UiKit.dp(new View(ctx), 6);
        grid.setPadding(pad, pad, pad, pad);
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (String s : HW_SYMBOLS) seen.add(s);
        for (final String glyph : seen) {
            TextView cell = new TextView(ctx);
            cell.setText(glyph);
            cell.setTextSize(18f);
            cell.setGravity(Gravity.CENTER);
            cell.setBackground(UiKit.square(UiKit.TILE));
            cell.setClickable(true);
            int cellH = UiKit.dp(cell, 44);
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
            glp.width = 0;
            glp.height = cellH;
            glp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            glp.setMargins(pad / 2, pad / 2, pad / 2, pad / 2);
            cell.setLayoutParams(glp);
            cell.setOnClickListener(v -> {
                int[] ku = HidControl.charToKey(glyph.charAt(0));
                if (ku != null) {
                    markModsUsed();
                    final int extra = ku[0];
                    final int usage = ku[1];
                    new Thread(() -> {
                        if (extra != 0) HidControl.keyDown(extra == 0x02 ? 0xe1 : extra);
                        HidControl.keyTapKeepMods(usage);
                        if (extra != 0 && (currentMod() & extra) == 0)
                            HidControl.keyUp(extra == 0x02 ? 0xe1 : extra);
                        KeyLedClient.bumpActivity();
                    }).start();
                }
            });
            grid.addView(cell);
        }
        sc.addView(grid);
        new AlertDialog.Builder(ctx)
            .setTitle("HW symbols")
            .setView(sc)
            .setNegativeButton("Close", null)
            .show();
    }
}
