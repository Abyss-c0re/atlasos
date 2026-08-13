package com.titanus2.atlas;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Thin DeviceDefault helpers for Atlas chrome + Settings.
 * PRODUCT_UX: short labels, mono facts, no marketing, no Cube tile chrome.
 * Terminal body colors stay in {@link AtlasPrefs} / {@link TermTheme}.
 */
public final class AtlasUi {
    public static final int WARN = 0xFFFFAB40;
    public static final int OK = 0xFF66BB6A;
    public static final int MUTED_ON_DARK = 0xFFB0BEC5;
    public static final int MUTED_ON_LIGHT = 0xFF546E7A;

    private AtlasUi() {}

    public static boolean isNight(Context c) {
        if (c == null) return true;
        int ui = c.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK;
        return ui == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int resolveAttr(Context c, int attr, int fallback) {
        if (c == null) return fallback;
        try {
            TypedValue tv = new TypedValue();
            if (c.getTheme().resolveAttribute(attr, tv, true)) {
                if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return 0xFF000000 | (tv.data & 0x00FFFFFF);
                }
                if (tv.resourceId != 0) {
                    return 0xFF000000 | (c.getColor(tv.resourceId) & 0x00FFFFFF);
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    public static int textPrimary(Context c) {
        return resolveAttr(c, android.R.attr.textColorPrimary,
            isNight(c) ? 0xFFF5F5F5 : 0xFF212121);
    }

    public static int textSecondary(Context c) {
        return resolveAttr(c, android.R.attr.textColorSecondary,
            isNight(c) ? 0xFFB0B0B0 : 0xFF757575);
    }

    public static int accent(Context c) {
        return resolveAttr(c, android.R.attr.colorAccent, 0xFF26A69A);
    }

    /** Chrome text on terminal background (not Cube cyan). */
    public static int chromeOnTerm(Context c) {
        int bg = AtlasPrefs.bgColor(c);
        int sum = Color.red(bg) + Color.green(bg) + Color.blue(bg);
        return sum > 400 ? MUTED_ON_LIGHT : MUTED_ON_DARK;
    }

    /** Plane line color: ready Deb = accent, waiting = warn, Android = chrome. */
    public static int planeColor(Context c, boolean wantDeb, boolean ready) {
        if (wantDeb && ready) return accent(c);
        if (wantDeb) return WARN;
        return chromeOnTerm(c);
    }

    /**
     * Short mono status — no version spam.
     * e.g. {@code DEBIAN · s1/2 · live} or {@code hybrid↓ · s1/1 · wait 12}
     */
    public static String statusLine(String plane, int sessionIdx1, int sessionCount,
                                    String live, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append(plane != null ? plane : "?");
        sb.append(" · s").append(Math.max(sessionIdx1, 0)).append('/').append(sessionCount);
        if (live != null && !live.isEmpty()) sb.append(" · ").append(live);
        if (note != null && !note.isEmpty()) sb.append(" · ").append(note);
        return sb.toString();
    }

    public static String planeLabel(boolean wantDeb, boolean ready) {
        if (wantDeb && ready) return "DEBIAN";
        if (wantDeb) return "hybrid↓";
        return "ANDROID";
    }

    public static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static void section(LinearLayout root, String name) {
        Context c = root.getContext();
        TextView t = new TextView(c);
        t.setText(name.toUpperCase());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(textSecondary(c));
        t.setPadding(0, dp(c, 18), 0, dp(c, 8));
        root.addView(t, match());
    }

    public static TextView monoFact(Context c, String text) {
        TextView t = new TextView(c);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(textSecondary(c));
        t.setText(text != null ? text : "");
        t.setTextIsSelectable(true);
        return t;
    }

    public static TextView body(Context c, String text) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(textPrimary(c));
        t.setText(text != null ? text : "");
        return t;
    }

    public static Switch settingsSwitch(LinearLayout root, String title, boolean on,
                                        BoolConsumer listener) {
        Context c = root.getContext();
        Switch sw = new Switch(c);
        sw.setText(title);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sw.setTextColor(textPrimary(c));
        sw.setMinHeight(dp(c, 48));
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((b, v) -> listener.accept(v));
        LinearLayout.LayoutParams lp = match();
        lp.bottomMargin = dp(c, 4);
        root.addView(sw, lp);
        return sw;
    }

    public static void actionBtn(LinearLayout root, String label, Runnable action) {
        Context c = root.getContext();
        Button b = new Button(c, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(accent(c));
        b.setMinHeight(dp(c, 48));
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setPadding(dp(c, 4), dp(c, 8), dp(c, 4), dp(c, 8));
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = match();
        lp.topMargin = dp(c, 2);
        lp.bottomMargin = dp(c, 2);
        root.addView(b, lp);
    }

    public static TextView prefRow(LinearLayout root, String title, String value) {
        Context c = root.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 6), 0, dp(c, 6));
        TextView left = body(c, title);
        row.addView(left, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView right = new TextView(c);
        right.setText(value);
        right.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        right.setTextColor(textSecondary(c));
        row.addView(right);
        root.addView(row, match());
        return right;
    }

    public static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    public interface BoolConsumer {
        void accept(boolean v);
    }
}
