package com.titanus2.cubecontact;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Lineage Settings chrome for Cube Contact. Square DeviceDefault rows —
 * not Holo pills, not a second Cube theme.
 */
final class CubeSettingsUi {
    static final int BG_NIGHT = 0xFF121212;
    static final int BG_DAY = 0xFFFAFAFA;
    static final int TEXT_NIGHT = 0xFFE6E6E6;
    static final int TEXT_DAY = 0xFF212121;
    static final int MUTED_NIGHT = 0xFF9E9E9E;
    static final int MUTED_DAY = 0xFF757575;

    private CubeSettingsUi() {}

    static boolean night(Context c) {
        if (c == null) return true;
        int ui = c.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK;
        return ui == Configuration.UI_MODE_NIGHT_YES;
    }

    static int body(Context c) { return night(c) ? BG_NIGHT : BG_DAY; }
    static int text(Context c) { return night(c) ? TEXT_NIGHT : TEXT_DAY; }
    static int muted(Context c) { return night(c) ? MUTED_NIGHT : MUTED_DAY; }

    static int dp(View v, int d) {
        return Math.round(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, d, v.getResources().getDisplayMetrics()));
    }

    static void applyWindow(Activity a) {
        if (a == null) return;
        int bg = body(a);
        a.getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        try {
            Window w = a.getWindow();
            w.setBackgroundDrawable(new ColorDrawable(bg));
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            w.setStatusBarColor(bg);
            w.setNavigationBarColor(bg);
            View decor = w.getDecorView();
            if (decor != null) decor.setBackgroundColor(bg);
        } catch (Exception ignored) {}
        try { CubeSurfacePrefs.apply(a); } catch (Exception ignored) {}
    }

    static void screen(ScrollView sc, LinearLayout root) {
        Context c = root.getContext();
        int bg = body(c);
        sc.setBackgroundColor(bg);
        sc.setFillViewport(true);
        sc.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        int ph = dp(root, 16);
        root.setPadding(ph, dp(root, 8), ph, dp(root, 24));
        sc.addView(root);
    }

    static TextView title(LinearLayout root, String t) {
        TextView tv = new TextView(root.getContext());
        tv.setText(t);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        tv.setTextColor(text(root.getContext()));
        tv.setPadding(0, dp(tv, 8), 0, dp(tv, 4));
        root.addView(tv);
        return tv;
    }

    static TextView stateLine(LinearLayout root) {
        TextView tv = new TextView(root.getContext());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(muted(root.getContext()));
        tv.setPadding(0, 0, 0, dp(tv, 12));
        root.addView(tv);
        return tv;
    }

    static void section(LinearLayout root, String t) {
        TextView tv = new TextView(root.getContext());
        tv.setText(t);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        tv.setAllCaps(true);
        tv.setLetterSpacing(0.04f);
        tv.setTextColor(muted(root.getContext()));
        tv.setPadding(0, dp(tv, 16), 0, dp(tv, 4));
        root.addView(tv);
    }

    static LinearLayout navRow(LinearLayout parent, String title, String summary, Runnable act) {
        LinearLayout row = prefRow(parent);
        LinearLayout col = textCol(row, title, summary);
        TextView chev = new TextView(row.getContext());
        chev.setText("›");
        chev.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        chev.setTextColor(muted(row.getContext()));
        chev.setPadding(dp(chev, 8), 0, 0, 0);
        row.addView(col);
        row.addView(chev);
        row.setOnClickListener(v -> act.run());
        parent.addView(row);
        return row;
    }

    static LinearLayout choiceRow(LinearLayout parent, String title, String summary, Runnable act) {
        LinearLayout row = prefRow(parent);
        LinearLayout col = textCol(row, title, summary);
        TextView mark = new TextView(row.getContext());
        mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        mark.setTextColor(text(row.getContext()));
        mark.setTag("mark");
        mark.setPadding(dp(mark, 8), 0, 0, 0);
        row.addView(col);
        row.addView(mark);
        row.setOnClickListener(v -> act.run());
        parent.addView(row);
        return row;
    }

    static void setChosen(LinearLayout row, boolean on) {
        if (row == null) return;
        row.setSelected(on);
        row.setActivated(on);
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c instanceof TextView && "mark".equals(c.getTag())) {
                ((TextView) c).setText(on ? "✓" : "");
            }
        }
    }

    static Switch toggleRow(LinearLayout parent, String title, boolean initial,
                            SwitchListener listener) {
        LinearLayout row = prefRow(parent);
        LinearLayout col = textCol(row, title, null);
        Switch sw = new Switch(row.getContext());
        sw.setChecked(initial);
        tintSwitch(sw, row.getContext());
        final boolean[] suppress = {false};
        sw.setOnCheckedChangeListener((v, on) -> {
            if (!suppress[0] && listener != null) listener.onChanged(on);
        });
        row.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
        row.addView(col);
        row.addView(sw);
        parent.addView(row);
        sw.setTag(suppress);
        return sw;
    }

    static void setSwitch(Switch sw, boolean on) {
        if (sw == null) return;
        boolean[] suppress = sw.getTag() instanceof boolean[] ? (boolean[]) sw.getTag() : null;
        if (suppress != null) suppress[0] = true;
        try { sw.setChecked(on); } finally {
            if (suppress != null) suppress[0] = false;
        }
    }

    interface SwitchListener {
        void onChanged(boolean on);
    }

    private static LinearLayout prefRow(LinearLayout parent) {
        Context ctx = parent.getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(dp(row, 48));
        int pv = dp(row, 12);
        row.setPadding(0, pv, 0, pv);
        row.setBackground(selectable(ctx));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(lp);
        return row;
    }

    private static LinearLayout textCol(LinearLayout row, String title, String summary) {
        Context ctx = row.getContext();
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(text(ctx));
        col.addView(t);
        if (summary != null && !summary.isEmpty()) {
            TextView s = new TextView(ctx);
            s.setText(summary);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            s.setTextColor(muted(ctx));
            s.setPadding(0, dp(s, 2), 0, 0);
            col.addView(s);
        }
        return col;
    }

    private static RippleDrawable selectable(Context c) {
        int ripple = night(c) ? 0x44FFFFFF : 0x33212121;
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(0f);
        mask.setColor(Color.WHITE);
        GradientDrawable content = new GradientDrawable();
        content.setShape(GradientDrawable.RECTANGLE);
        content.setCornerRadius(0f);
        content.setColor(body(c));
        return new RippleDrawable(ColorStateList.valueOf(ripple), content, mask);
    }

    private static void tintSwitch(Switch sw, Context ctx) {
        try {
            int thumbOn = night(ctx) ? 0xFFBDBDBD : 0xFF616161;
            int thumbOff = night(ctx) ? 0xFF757575 : 0xFF9E9E9E;
            int trackOn = night(ctx) ? 0xFF424242 : 0xFFBDBDBD;
            int trackOff = night(ctx) ? 0xFF2A2A2A : 0xFFE0E0E0;
            int[][] st = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}
            };
            sw.setThumbTintList(new ColorStateList(st, new int[]{thumbOn, thumbOff}));
            sw.setTrackTintList(new ColorStateList(st, new int[]{trackOn, trackOff}));
        } catch (Exception ignored) {}
    }
}
