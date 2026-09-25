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
import android.widget.LinearLayout;
import android.widget.ScrollView;

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
    private int keyFg;
    private int keyOnBg;

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
            for (String key : row) {
                Button b = makeKey(c, key);
                if ("CTRL".equals(key)) ctrlBtn = b;
                if ("ALT".equals(key)) altBtn = b;
                if ("SHIFT".equals(key)) shiftBtn = b;
                if ("META".equals(key)) metaBtn = b;
                if ("CAPS".equals(key)) capsBtn = b;
                if ("GRAVE".equals(key)) b.setText("~");
                if ("SYM".equals(key)) {
                    b.setOnClickListener(v -> showSymbols());
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(dp(1), dp(1), dp(1), dp(1));
                line.addView(b, lp);
            }
            addView(line, new LayoutParams(
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
        keyBg = Color.rgb(
            Math.min(255, r + 20),
            Math.min(255, g + 20),
            Math.min(255, b + 20));
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
