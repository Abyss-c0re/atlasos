package com.titanus2.atlas;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.List;

/**
 * Special-key panel. Same keys in the terminal and on the desk.
 * Must NOT be clickable itself — that steals taps from the keys.
 */
public final class ExtraKeysView extends LinearLayout {
    public interface Listener {
        void onExtraKey(String key);
        boolean isCtrlOn();
        boolean isAltOn();
        boolean isShiftOn();
        boolean isMetaOn();
        boolean isCapsOn();
    }

    /** Same glyphs as the HID pad specials layer. */
    private static final String[] SYMBOLS = {
        "0", "1", "2", "3", "(", ")", "_", "-", "/", ":",
        "@", "4", "5", "6", "*", "#", "+", "\"", "'",
        "!", "7", "8", "9", ".", ",", "?",
        "`", "~", "[", "]", "{", "}", "\\", "|", ";", "<", ">", "=",
    };

    private final Listener listener;
    private Button ctrlBtn;
    private Button altBtn;
    private Button shiftBtn;
    private Button metaBtn;
    private Button capsBtn;
    private int keyBg;
    private int keyRgb;
    private int keyFg;
    private int keyOnBg;
    private final List<Button> made = new ArrayList<>();
    private final List<LinearLayout> homeRows = new ArrayList<>();
    private final List<Integer> homeIndex = new ArrayList<>();
    private boolean inRing;

    public ExtraKeysView(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        setOrientation(VERTICAL);
        applyTermChrome(c);
        setPadding(dp(2), dp(2), dp(2), dp(2));
        setClickable(false);
        setFocusable(false);

        String[][] rows = {
            {"SIDE", "RST"},
            {"ESC", "GRAVE", "BKSP", "/", "HOME", "↑", "END", "PGUP"},
            {"TAB", "CTRL", "ALT", "META", "←", "↓", "→", "PGDN"},
            {"SHIFT", "CAPS", "SYM", "INS", "DEL", "PRT", "PAUSE"},
            {"F1", "F2", "F3", "F4", "F5", "F6"},
            {"F7", "F8", "F9", "F10", "F11", "F12"},
        };
        for (String[] row : rows) {
            LinearLayout line = new LinearLayout(c);
            line.setOrientation(HORIZONTAL);
            line.setGravity(Gravity.CENTER);
            line.setClickable(false);
            homeRows.add(line);
            int rowIndex = homeRows.size() - 1;
            for (String key : row) {
                Button b = makeKey(c, key);
                made.add(b);
                homeIndex.add(rowIndex);
                if ("CTRL".equals(key)) ctrlBtn = b;
                if ("ALT".equals(key)) altBtn = b;
                if ("SHIFT".equals(key)) shiftBtn = b;
                if ("META".equals(key)) metaBtn = b;
                if ("CAPS".equals(key)) capsBtn = b;
                if ("GRAVE".equals(key)) b.setText("~");
                if ("SYM".equals(key)) {
                    b.setOnClickListener(v -> showSymbols());
                }
                int cell = dp(52);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    cell, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                line.addView(b, lp);
            }
            HorizontalScrollView scroller = new HorizontalScrollView(c);
            scroller.setHorizontalScrollBarEnabled(false);
            scroller.setFillViewport(false);
            scroller.addView(line, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(scroller, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        refreshModifiers();
    }

    /** Re-tint when the user returns from Settings. */
    public void applyTermChrome(Context c) {
        int bg = AtlasPrefs.bgColor(c);
        int fg = AtlasPrefs.fgColor(c);
        int r = Math.min(255, Color.red(bg) + 18);
        int g = Math.min(255, Color.green(bg) + 18);
        int b = Math.min(255, Color.blue(bg) + 18);
        setBackgroundColor(Color.rgb(r, g, b));
        keyRgb = Color.rgb(
            Math.min(255, r + 20),
            Math.min(255, g + 20),
            Math.min(255, b + 20));
        keyBg = keyRgb;
        keyFg = fg;
        int cur = AtlasPrefs.cursorColor(c);
        keyOnBg = (cur == fg || cur == bg) ? fg : cur;
        tintTree(this);
        refreshModifiers();
    }

    private void tintTree(View v) {
        if (v instanceof Button) {
            Button btn = (Button) v;
            if (btn != ctrlBtn && btn != altBtn && btn != shiftBtn
                    && btn != metaBtn && btn != capsBtn) {
                btn.setBackgroundColor(keyBg);
                btn.setTextColor(keyFg);
            }
            return;
        }
        if (v == this || v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) tintTree(g.getChildAt(i));
        }
    }

    private Button makeKey(Context c, String key) {
        Button b = new Button(c, null, android.R.attr.borderlessButtonStyle);
        b.setTag(key);
        b.setText(key);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setTypeface(Typeface.MONOSPACE);
        b.setTextColor(keyFg);
        b.setMinHeight(0);
        b.setMinimumHeight(dp(34));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(2), dp(4), dp(2), dp(4));
        b.setBackgroundColor(keyBg);
        b.setClickable(true);
        b.setFocusable(false);
        b.setOnClickListener(v -> {
            if (listener != null) listener.onExtraKey(key);
            refreshModifiers();
        });
        return b;
    }

    private void showSymbols() {
        Context c = getContext();
        ScrollView sc = new ScrollView(c);
        GridLayout grid = new GridLayout(c);
        grid.setColumnCount(6);
        int pad = dp(4);
        grid.setPadding(pad, pad, pad, pad);
        for (String glyph : SYMBOLS) {
            Button cell = new Button(c, null, android.R.attr.borderlessButtonStyle);
            cell.setText(glyph);
            cell.setAllCaps(false);
            cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            cell.setTypeface(Typeface.MONOSPACE);
            cell.setTextColor(keyFg);
            cell.setBackgroundColor(keyBg);
            cell.setMinHeight(0);
            cell.setMinimumHeight(dp(40));
            cell.setPadding(0, 0, 0, 0);
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
            glp.width = dp(56);
            glp.height = dp(48);
            glp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED);
            glp.setMargins(pad / 2, pad / 2, pad / 2, pad / 2);
            cell.setLayoutParams(glp);
            cell.setOnClickListener(v -> {
                if (listener != null) listener.onExtraKey(glyph);
                refreshModifiers();
            });
            grid.addView(cell);
        }
        sc.addView(grid);
        new AlertDialog.Builder(c)
            .setTitle("Symbols")
            .setView(sc)
            .setNegativeButton("Close", null)
            .show();
    }

    public void refreshModifiers() {
        if (listener == null) return;
        styleToggle(ctrlBtn, listener.isCtrlOn());
        styleToggle(altBtn, listener.isAltOn());
        styleToggle(shiftBtn, listener.isShiftOn());
        styleToggle(metaBtn, listener.isMetaOn());
        styleToggle(capsBtn, listener.isCapsOn());
    }

    private void styleToggle(Button b, boolean on) {
        if (b == null) return;
        if (on) {
            b.setBackgroundColor(keyOnBg);
            b.setTextColor(Color.WHITE);
        } else {
            b.setBackgroundColor(keyBg);
            b.setTextColor(keyFg);
        }
    }

    /** Windowed strip. Puts every key back on its original row. */
    public void mountBottom() {
        inRing = false;
        for (int i = 0; i < made.size(); i++) {
            Button b = made.get(i);
            detach(b);
            LinearLayout line = homeRows.get(homeIndex.get(i));
            int cell = dp(52);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                cell, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            line.addView(b, lp);
        }
        applyTermChrome(getContext());
    }

    /**
     * Full-screen ring. Keys leave the bottom strip and sit on the four
     * sides of the floating panel. The desktop underneath stays full screen.
     */
    public void mountRing(LinearLayout top, LinearLayout left, LinearLayout right,
                          LinearLayout bottom, int cellPx, int alphaPercent) {
        inRing = true;
        top.setOrientation(VERTICAL);
        bottom.setOrientation(VERTICAL);
        top.removeAllViews();
        left.removeAllViews();
        right.removeAllViews();
        bottom.removeAllViews();
        for (Button b : made) detach(b);
        paintAlpha(alphaPercent);
        addRow(top, cellPx, "ESC", "GRAVE", "BKSP", "/", "INS", "DEL", "PRT", "PAUSE");
        addRow(top, cellPx, "TAB", "CTRL", "ALT", "META", "SYM");
        addCol(left, cellPx, "SIDE", "RST", "SHIFT", "CAPS");
        addCol(right, cellPx, "HOME", "END", "PGUP", "PGDN", "↑", "←", "↓", "→");
        addRow(bottom, cellPx, "F1", "F2", "F3", "F4", "F5", "F6");
        addRow(bottom, cellPx, "F7", "F8", "F9", "F10", "F11", "F12");
        setVisibility(GONE);
    }

    public List<Button> buttons() {
        return made;
    }

    public boolean isRing() {
        return inRing;
    }

    private void addRow(LinearLayout host, int cellPx, String... ids) {
        LinearLayout line = new LinearLayout(getContext());
        line.setOrientation(HORIZONTAL);
        line.setGravity(Gravity.CENTER);
        for (String id : ids) addSized(line, findKey(id), cellPx, true);
        host.addView(line, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addCol(LinearLayout host, int cellPx, String... ids) {
        host.setOrientation(VERTICAL);
        host.setGravity(Gravity.CENTER);
        for (String id : ids) addSized(host, findKey(id), cellPx, false);
    }

    private void addSized(LinearLayout host, Button b, int cellPx, boolean wide) {
        if (b == null) return;
        detach(b);
        int w = wide ? Math.max(cellPx, dp(36)) : cellPx;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, cellPx);
        int m = Math.max(1, cellPx / 16);
        lp.setMargins(m, m, m, m);
        b.setMinimumWidth(0);
        b.setMinWidth(0);
        b.setMinimumHeight(cellPx);
        host.addView(b, lp);
    }

    private Button findKey(String id) {
        for (Button b : made) {
            if (id.equals(b.getTag())) return b;
        }
        return null;
    }

    private void paintAlpha(int alphaPercent) {
        int a = Math.max(30, Math.min(100, alphaPercent)) * 255 / 100;
        keyBg = Color.argb(a, Color.red(keyRgb), Color.green(keyRgb), Color.blue(keyRgb));
        tintTree(this);
        for (Button b : made) {
            if (b != ctrlBtn && b != altBtn && b != shiftBtn && b != metaBtn && b != capsBtn) {
                b.setBackgroundColor(keyBg);
                b.setTextColor(keyFg);
            }
        }
        refreshModifiers();
    }

    private void detach(View v) {
        if (v.getParent() instanceof ViewGroup) {
            ((ViewGroup) v.getParent()).removeView(v);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
