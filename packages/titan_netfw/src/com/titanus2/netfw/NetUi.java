package com.titanus2.netfw;

import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.WindowMetrics;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Titan 1440×1440 Settings rows. Measure the window, not a phone portrait.
 * No Cube chrome. Labels only.
 */
final class NetUi {
    static int windowPx(Activity a) {
        try {
            WindowMetrics wm = a.getWindowManager().getCurrentWindowMetrics();
            return Math.min(wm.getBounds().width(), wm.getBounds().height());
        } catch (Throwable t) {
            DisplayMetrics dm = a.getResources().getDisplayMetrics();
            return Math.min(dm.widthPixels, dm.heightPixels);
        }
    }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Clamp type so 360dpi 1440 square does not blow the row. */
    static float sp(Context c, float v) {
        float d = c.getResources().getDisplayMetrics().density;
        if (d >= 3.0f) return v * 0.92f;
        return v;
    }

    static int attrColor(Context c, int attr, int fb) {
        TypedArray a = c.obtainStyledAttributes(new int[]{attr});
        int col = a.getColor(0, fb);
        a.recycle();
        return col;
    }

    static int edge(Activity a) {
        int w = windowPx(a);
        return Math.max(dp(a, 8), w / 48);
    }

    static TextView title(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(c, 18));
        t.setPadding(0, 0, 0, dp(c, 4));
        return t;
    }

    static TextView section(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(c, 12));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(c, 10), 0, dp(c, 4));
        t.setTextColor(attrColor(c, android.R.attr.colorAccent, 0xFF26A69A));
        return t;
    }

    static TextView fact(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(c, 11));
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(0, dp(c, 1), 0, dp(c, 1));
        return t;
    }

    static Switch sw(Context c, String label) {
        Switch s = new Switch(c);
        s.setText(label);
        s.setPadding(0, dp(c, 6), 0, dp(c, 6));
        s.setMinHeight(dp(c, 40));
        return s;
    }

    static Button btn(Context c, String label) {
        Button b = new Button(c, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(c, 12));
        b.setMinHeight(dp(c, 36));
        b.setMinWidth(0);
        b.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
        return b;
    }

    static EditText edit(Context c, String hint, String value) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(c, 13));
        e.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
        return e;
    }

    static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setBaselineAligned(false);
        return r;
    }

    static void weight(ViewGroup parent, android.view.View v, float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
        parent.addView(v, lp);
    }
}
