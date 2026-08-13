package com.titanus2.atlas;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

/**
 * Special-key panel under the terminal (Termux-style).
 * Must NOT be clickable itself — steals child taps.
 * Colors follow terminal prefs, not Cube chrome.
 */
public final class ExtraKeysView extends LinearLayout {
    public interface Listener {
        void onExtraKey(String key);
        boolean isCtrlOn();
        boolean isAltOn();
    }

    private final Listener listener;
    private Button ctrlBtn;
    private Button altBtn;
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
            {"ESC", "BKSP", "/", "HOME", "↑", "END", "PGUP"},
            {"TAB", "CTRL", "ALT", "←", "↓", "→", "PGDN"},
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

    /** Re-tint when user returns from Settings. */
    public void applyTermChrome(Context c) {
        int bg = AtlasPrefs.bgColor(c);
        int fg = AtlasPrefs.fgColor(c);
        // Panel slightly lifted from terminal bg
        int r = Math.min(255, Color.red(bg) + 18);
        int g = Math.min(255, Color.green(bg) + 18);
        int b = Math.min(255, Color.blue(bg) + 18);
        setBackgroundColor(Color.rgb(r, g, b));
        keyBg = Color.rgb(
            Math.min(255, r + 20),
            Math.min(255, g + 20),
            Math.min(255, b + 20));
        keyFg = fg;
        keyOnBg = AtlasUi.accent(c);
        for (int i = 0; i < getChildCount(); i++) {
            ViewGroup line = (ViewGroup) getChildAt(i);
            for (int j = 0; j < line.getChildCount(); j++) {
                if (line.getChildAt(j) instanceof Button) {
                    Button btn = (Button) line.getChildAt(j);
                    if (btn != ctrlBtn && btn != altBtn) {
                        btn.setBackgroundColor(keyBg);
                        btn.setTextColor(keyFg);
                    }
                }
            }
        }
        refreshModifiers();
    }

    private Button makeKey(Context c, String key) {
        Button b = new Button(c, null, android.R.attr.borderlessButtonStyle);
        b.setText(key);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTypeface(Typeface.MONOSPACE);
        b.setTextColor(keyFg);
        b.setMinHeight(0);
        b.setMinimumHeight(dp(36));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(2), dp(6), dp(2), dp(6));
        b.setBackgroundColor(keyBg);
        b.setClickable(true);
        b.setFocusable(false);
        b.setOnClickListener(v -> {
            if (listener != null) listener.onExtraKey(key);
            refreshModifiers();
        });
        return b;
    }

    public void refreshModifiers() {
        if (listener == null) return;
        styleToggle(ctrlBtn, listener.isCtrlOn());
        styleToggle(altBtn, listener.isAltOn());
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
