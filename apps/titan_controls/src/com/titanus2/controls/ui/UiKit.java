package com.titanus2.controls.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
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
 * OS Material / DeviceDefault helpers for Titan Controls.
 * <p>
 * Product 2026-07-16: purge Cube black/cyan tile chrome. Apps match system
 * Settings look; global square/night polish lives in RROs + SystemUI inject.
 * No marketing copy. Short labels only.
 * <p>
 * Builds against platform {@code android.jar} only (no Material Components AAR).
 */
public final class UiKit {
    /** Hard solids — never 0-alpha (scroll trails / multi-layer ghosts). */
    public static final int BG_NIGHT = 0xFF121212;
    public static final int BG_DAY = 0xFFFAFAFA;
    public static final int PANEL = 0xFF121212;
    public static final int TILE = 0xFF1E1E1E;
    public static final int TILE_ON = 0xFF263238;
    public static final int BORDER = 0xFF424242;
    public static final int BORDER_ON = 0xFF80CBC4;
    /** Resolved at runtime from theme when possible; fallbacks for static fields. */
    public static final int TEXT = 0xFF212121;
    public static final int TEXT_NIGHT = 0xFFF5F5F5;
    public static final int MUTED = 0xFF757575;
    public static final int MUTED_NIGHT = 0xFFB0B0B0;
    public static final int ACCENT = 0xFF26A69A;
    public static final int LIVE = 0xFF26A69A;
    public static final int WARN = 0xFFEF5350;
    public static final int OK = 0xFF66BB6A;

    public static final int PAD_H = 14;
    public static final int PAD_V = 8;
    public static final int GAP = 4;
    public static final int TILE_MIN_H_DP = 40;
    /**
     * Product geometry: always 0. Selection/focus must stay square
     * (QA-010 / CLIENT demo — rounded selected chrome frustrated clients).
     */
    public static final float CORNER_DP = 0f;

    private static final int TAG_SELECTED = 0x70C05E1;

    private UiKit() {}

    /** True when Configuration night bit is set (ignore flaky theme attrs). */
    public static boolean isNight(Context c) {
        if (c == null) return true;
        try {
            int ui = c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
            return ui == Configuration.UI_MODE_NIGHT_YES;
        } catch (Exception ignored) {
            return true;
        }
    }

    public static int liveAccent(Context c) {
        int a = resolveAttr(c, android.R.attr.colorAccent, ACCENT);
        // Force fully opaque accent (translucent accent → "frames" / faint chips).
        return 0xFF000000 | (a & 0x00FFFFFF);
    }

    /**
     * Solid body fill. Theme {@code colorBackground} can be translucent on
     * embedded Settings panes → SurfaceFlinger keeps prior frames while
     * scrolling (lab: multi-layer white ghost text in Titan Controls).
     */
    public static int liveBody(Context c) {
        return isNight(c) ? BG_NIGHT : BG_DAY;
    }

    public static int textColor(Context c) {
        if (isNight(c)) return TEXT_NIGHT;
        int t = resolveAttr(c, android.R.attr.textColorPrimary, TEXT);
        return 0xFF000000 | (t & 0x00FFFFFF);
    }

    public static int mutedColor(Context c) {
        // Night secondary often too dim (#757575 on #121212) — force readable.
        if (isNight(c)) return MUTED_NIGHT;
        int t = resolveAttr(c, android.R.attr.textColorSecondary, MUTED);
        return 0xFF000000 | (t & 0x00FFFFFF);
    }

    private static int resolveAttr(Context c, int attr, int fallback) {
        if (c == null) return fallback;
        try {
            TypedValue tv = new TypedValue();
            if (c.getTheme().resolveAttribute(attr, tv, true)) {
                if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                        && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return tv.data;
                }
                if (tv.resourceId != 0) {
                    return c.getResources().getColor(tv.resourceId, c.getTheme());
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    /**
     * Square selectable/focus chrome — do not use stock
     * {@code selectableItemBackground} (Material rounded ripple mask).
     * PRODUCT_UX + QA-010: selected state must not look like a rounded pill.
     * <p>
     * 15.28: content is SOLID body color (not TRANSPARENT). Transparent ripple
     * content left compositor trails / "frames over elements" while scrolling
     * inside Settings two-pane (user Gallery 2026-07-22).
     */
    private static android.graphics.drawable.Drawable selectableItemBgDrawable(Context c) {
        int body = liveBody(c);
        int ripple = isNight(c) ? 0x44FFFFFF : 0x33212121;
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(0f);
        mask.setColor(Color.WHITE);
        GradientDrawable content = new GradientDrawable();
        content.setShape(GradientDrawable.RECTANGLE);
        content.setCornerRadius(0f);
        content.setColor(body);
        return new RippleDrawable(
            ColorStateList.valueOf(ripple),
            content,
            mask);
    }

    /** @deprecated use {@link #applySelectableBg(View)} — stock res is rounded. */
    private static int selectableItemBg(Context c) {
        return 0;
    }

    private static void applySelectableBg(View v) {
        if (v == null) return;
        v.setBackground(selectableItemBgDrawable(v.getContext()));
    }

    public static void screen(LinearLayout root) {
        root.setOrientation(LinearLayout.VERTICAL);
        // 15.28: hard opaque body (night #121212 / day #FAFAFA). Never theme
        // colorBackground — translucent attrs → multi-layer ghost text while
        // scrolling in Settings embed (user screenshots 16:59 / 18:27).
        Context c = root.getContext();
        int bg = liveBody(c);
        root.setBackgroundColor(bg);
        root.setPadding(dp(root, PAD_H), dp(root, PAD_V), dp(root, PAD_H), dp(root, PAD_V + 16));
        root.setClipToPadding(true);
        root.setClipChildren(true);
        root.setLayerType(View.LAYER_TYPE_NONE, null);
        // No drawing cache — stale bitmaps looked like "frames over elements".
        root.setDrawingCacheEnabled(false);
    }

    /** Solid opaque window + content bg (anti-ghost compositor trails). */
    public static void applyOpaqueWindow(Activity a) {
        if (a == null) return;
        try {
            int bg = liveBody(a);
            android.view.Window w = a.getWindow();
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));
            // Embedded Settings pane can still composite translucent — force flags.
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            try {
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            } catch (Exception ignored) {}
            w.setStatusBarColor(bg);
            w.setNavigationBarColor(bg);
            View decor = w.getDecorView();
            if (decor != null) {
                decor.setBackgroundColor(bg);
                decor.setLayerType(View.LAYER_TYPE_NONE, null);
                if (decor instanceof ViewGroup) {
                    ((ViewGroup) decor).setClipChildren(true);
                    ((ViewGroup) decor).setClipToPadding(true);
                }
            }
        } catch (Exception ignored) {}
    }

    /** Opaque scroll host for Settings two-pane (no overscroll glow ghosts). */
    public static void prepareScroll(android.widget.ScrollView scroll) {
        if (scroll == null) return;
        Context c = scroll.getContext();
        int bg = liveBody(c);
        scroll.setBackgroundColor(bg);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setLayerType(View.LAYER_TYPE_NONE, null);
        scroll.setClipToPadding(true);
        scroll.setClipChildren(true);
        scroll.setVerticalFadingEdgeEnabled(false);
        scroll.setHorizontalFadingEdgeEnabled(false);
        scroll.setDrawingCacheEnabled(false);
    }

    public static int dp(View v, int dp) {
        return Math.round(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, v.getResources().getDisplayMetrics()));
    }

    /** No-op chrome: Material widgets use theme backgrounds. */
    public static android.graphics.drawable.GradientDrawable square(int fill) {
        return square(fill, 0);
    }

    public static android.graphics.drawable.GradientDrawable square(int fill, int border) {
        android.graphics.drawable.GradientDrawable d =
            new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setCornerRadius(0f);
        d.setColor(fill == 0 ? 0x00000000 : fill);
        if (border != 0) d.setStroke(1, border);
        return d;
    }

    public static void title(LinearLayout root, String t) {
        title(root, t, 20f);
    }

    public static void title(LinearLayout root, String t, float sz) {
        TextView tv = new TextView(root.getContext());
        tv.setText(t);
        tv.setTextSize(sz);
        tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        tv.setTextColor(textColor(root.getContext()));
        tv.setPadding(0, 4, 0, 12);
        root.addView(tv);
    }

    public static void section(LinearLayout root, String t) {
        TextView tv = new TextView(root.getContext());
        tv.setText(t);
        tv.setTextSize(12f);
        tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        tv.setTextColor(mutedColor(root.getContext()));
        tv.setAllCaps(true);
        tv.setLetterSpacing(0.04f);
        // 15.27: tighter section spacing — was sparse/broken looking
        tv.setPadding(0, dp(tv, 12), 0, dp(tv, 4));
        root.addView(tv);
    }

    public static void note(LinearLayout root, String t) {
        if (t == null || t.isEmpty()) return;
        TextView tv = new TextView(root.getContext());
        tv.setText(t);
        tv.setTextSize(13f);
        tv.setTextColor(mutedColor(root.getContext()));
        tv.setPadding(0, 0, 0, 4);
        root.addView(tv);
    }

    public static TextView summary(LinearLayout root) {
        TextView tv = new TextView(root.getContext());
        tv.setTextSize(14f);
        tv.setTextColor(mutedColor(root.getContext()));
        tv.setPadding(0, 0, 0, dp(tv, 8));
        root.addView(tv);
        return tv;
    }

    public static TextView stateLine(LinearLayout root) {
        Context ctx = root.getContext();
        TextView tv = new TextView(ctx);
        tv.setTextSize(13f);
        tv.setTextColor(textColor(ctx));
        int p = dp(tv, 12);
        tv.setPadding(p, p - 2, p, p - 2);
        // 15.28: solid body only — selectable ripple on status lines looked like
        // empty grey "cubes/frames" next to text.
        tv.setBackgroundColor(liveBody(ctx));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = GAP;
        tv.setLayoutParams(lp);
        root.addView(tv);
        return tv;
    }

    public static TextView mono(LinearLayout root) {
        TextView tv = stateLine(root);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(11f);
        tv.setTextColor(mutedColor(root.getContext()));
        return tv;
    }

    public static LinearLayout row(LinearLayout root) {
        LinearLayout row = new LinearLayout(root.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = GAP;
        row.setLayoutParams(lp);
        root.addView(row);
        return row;
    }

    public static TextView button(LinearLayout parent, String text, Runnable r) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("UiKit.button requires non-empty label");
        }
        Button b = makeButton(parent.getContext(), text, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = GAP;
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> r.run());
        parent.addView(b);
        return b;
    }

    public static TextView flexButton(LinearLayout row, String text, Runnable r) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("UiKit.flexButton requires non-empty label");
        }
        Button b = makeButton(row.getContext(), text, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, 0, GAP, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> r.run());
        row.addView(b);
        return b;
    }

    private static Button makeButton(Context ctx, String text, boolean compact) {
        Button b = new Button(ctx, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(compact ? 13f : 14f);
        b.setMinHeight(dp(b, TILE_MIN_H_DP));
        b.setFocusable(true);
        b.setClickable(true);
        b.setTag(TAG_SELECTED, Boolean.FALSE);
        b.setOnFocusChangeListener((v, has) -> applySelectedChrome((TextView) v));
        applySelectedChrome(b);
        return b;
    }

    public static LinearLayout navRow(LinearLayout parent, String title, String summary,
                                     Runnable r) {
        Context ctx = parent.getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setFocusableInTouchMode(false);
        int ph = dp(row, 4);
        int pv = dp(row, 12);
        row.setPadding(ph, pv, ph, pv);
        row.setMinimumHeight(dp(row, TILE_MIN_H_DP));
        applySelectableBg(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 2;
        row.setLayoutParams(lp);
        row.setOnClickListener(v -> r.run());

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextColor(textColor(ctx));
        t.setTextSize(16f);
        textCol.addView(t);

        TextView s = new TextView(ctx);
        s.setText(summary == null ? "" : summary);
        s.setTextColor(mutedColor(ctx));
        s.setTextSize(13f);
        s.setPadding(0, 2, 0, 0);
        s.setTag("summary");
        if (summary == null || summary.isEmpty()) s.setVisibility(View.GONE);
        textCol.addView(s);

        TextView chev = new TextView(ctx);
        chev.setText("›");
        chev.setTextColor(mutedColor(ctx));
        chev.setTextSize(22f);
        chev.setPadding(dp(chev, 8), 0, 0, 0);

        row.addView(textCol);
        row.addView(chev);
        parent.addView(row);
        return row;
    }

    public static void setNavSummary(LinearLayout navRow, String summary) {
        if (navRow == null) return;
        for (int i = 0; i < navRow.getChildCount(); i++) {
            View c = navRow.getChildAt(i);
            if (!(c instanceof LinearLayout)) continue;
            LinearLayout col = (LinearLayout) c;
            for (int j = 0; j < col.getChildCount(); j++) {
                View v = col.getChildAt(j);
                if (v instanceof TextView && "summary".equals(v.getTag())) {
                    TextView s = (TextView) v;
                    if (summary == null || summary.isEmpty()) {
                        s.setText("");
                        s.setVisibility(View.GONE);
                    } else {
                        s.setText(summary);
                        s.setVisibility(View.VISIBLE);
                    }
                    return;
                }
            }
        }
    }

    public static TextView listRow(LinearLayout parent, String primary, String secondary,
                                   Runnable r) {
        Context ctx = parent.getContext();
        TextView b = new TextView(ctx);
        if (secondary == null || secondary.isEmpty()) {
            b.setText(primary);
        } else {
            String full = primary + "\n" + secondary;
            SpannableString ss = new SpannableString(full);
            int start = primary.length() + 1;
            ss.setSpan(new ForegroundColorSpan(mutedColor(ctx)), start, full.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ss.setSpan(new RelativeSizeSpan(0.86f), start, full.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setText(ss);
        }
        b.setTextColor(textColor(ctx));
        b.setTextSize(16f);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setClickable(true);
        b.setFocusable(true);
        int ph = dp(b, 4);
        int pv = dp(b, 14);
        b.setPadding(ph, pv, ph, pv);
        b.setMinHeight(dp(b, TILE_MIN_H_DP));
        applySelectableBg(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 2;
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> r.run());
        parent.addView(b);
        return b;
    }

    private static void applySelectedChrome(TextView tile) {
        if (tile == null) return;
        boolean on = Boolean.TRUE.equals(tile.getTag(TAG_SELECTED));
        tile.setActivated(on);
        tile.setSelected(on);
        tile.setTypeface(Typeface.SANS_SERIF, on ? Typeface.BOLD : Typeface.NORMAL);
        Context c = tile.getContext();
        if (on) {
            // 15.28: solid filled segment — NO stroke (stroke = "odd frames over
            // elements"). High-contrast text on accent for night visibility.
            int accent = liveAccent(c);
            GradientDrawable chip = new GradientDrawable();
            chip.setShape(GradientDrawable.RECTANGLE);
            chip.setCornerRadius(0f);
            chip.setColor(accent);
            tile.setBackground(chip);
            // Dark text on teal reads on both day/night.
            tile.setTextColor(0xFF00332E);
        } else {
            tile.setTextColor(textColor(c));
            // Unselected: solid body + light ripple (no transparent hole).
            GradientDrawable idle = new GradientDrawable();
            idle.setShape(GradientDrawable.RECTANGLE);
            idle.setCornerRadius(0f);
            idle.setColor(isNight(c) ? 0xFF1E1E1E : 0xFFE8E8E8);
            tile.setBackground(idle);
        }
    }

    public static void setSelected(TextView tile, boolean on) {
        if (tile == null) return;
        tile.setTag(TAG_SELECTED, on);
        applySelectedChrome(tile);
    }

    public static final class Toggle {
        public final LinearLayout row;
        public final TextView label;
        /** Legacy tile fields — null when using Switch. */
        public final TextView offTile;
        public final TextView onTile;
        public final Switch sw;
        public final boolean[] suppress = new boolean[]{false};
        private boolean checked;
        private BoolListener listener;

        private Toggle(LinearLayout row, TextView label, Switch sw, boolean initial) {
            this.row = row;
            this.label = label;
            this.offTile = null;
            this.onTile = null;
            this.sw = sw;
            this.checked = initial;
        }

        public void setChecked(boolean on) {
            if (checked == on) return;
            checked = on;
            if (sw != null) {
                suppress[0] = true;
                try { sw.setChecked(on); } finally { suppress[0] = false; }
            }
        }

        public boolean isChecked() { return checked; }

        public void setEnabled(boolean en) {
            if (row != null) row.setEnabled(en);
            if (sw != null) sw.setEnabled(en);
            if (label != null) {
                label.setEnabled(en);
                label.setTextColor(en ? textColor(row.getContext()) : mutedColor(row.getContext()));
            }
        }

        private void apply(boolean on) {
            if (suppress[0] || (row != null && !row.isEnabled())) return;
            if (checked == on) return;
            checked = on;
            if (listener != null) listener.onChanged(on);
        }
    }

    public interface BoolListener {
        void onChanged(boolean on);
    }

    /** Material Switch row — label left, switch right. */
    public static Toggle toggle(LinearLayout root, String label, boolean initial,
                                BoolListener listener) {
        if (label == null || label.trim().isEmpty()) {
            throw new IllegalArgumentException("UiKit.toggle requires non-empty label");
        }
        Context ctx = root.getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        int ph = dp(row, 4);
        int pv = dp(row, 10);
        row.setPadding(ph, pv, ph, pv);
        row.setMinimumHeight(dp(row, TILE_MIN_H_DP));
        applySelectableBg(row);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = GAP;
        row.setLayoutParams(rlp);

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(textColor(ctx));
        tv.setTextSize(16f);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(ctx);
        sw.setChecked(initial);
        Toggle tgl = new Toggle(row, tv, sw, initial);
        tgl.listener = listener;
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> tgl.apply(isChecked));
        row.setOnClickListener(v -> sw.toggle());

        row.addView(tv);
        row.addView(sw);
        root.addView(row);
        return tgl;
    }

    public static TextView sliderLabel(LinearLayout root, String initial) {
        TextView tv = new TextView(root.getContext());
        tv.setText(initial);
        tv.setTextColor(mutedColor(root.getContext()));
        tv.setTextSize(13f);
        tv.setPadding(0, 6, 0, 2);
        root.addView(tv);
        return tv;
    }

    public static final class Step {
        public final LinearLayout row;
        public final TextView minus;
        public final TextView valueTile;
        public final TextView plus;
        private int value;
        private int max;
        private final int min;
        private IntListener listener;
        private String displayOverride;

        private Step(LinearLayout row, TextView minus, TextView valueTile, TextView plus,
                     int min, int max, int initial) {
            this.row = row;
            this.minus = minus;
            this.valueTile = valueTile;
            this.plus = plus;
            this.min = min;
            this.max = Math.max(min, max);
            this.value = clamp(initial);
            paint();
        }

        private int clamp(int v) {
            if (v < min) return min;
            if (v > max) return max;
            return v;
        }

        private void paint() {
            if (valueTile != null) {
                valueTile.setText(displayOverride != null
                    ? displayOverride : String.valueOf(value));
            }
            if (minus != null) minus.setEnabled(value > min);
            if (plus != null) plus.setEnabled(value < max);
        }

        public int getValue() { return value; }
        public int getMax() { return max; }

        public void setMax(int m) {
            max = Math.max(min, m);
            value = clamp(value);
            paint();
        }

        public void setDisplay(String text) {
            displayOverride = text;
            paint();
        }

        public void clearDisplay() {
            displayOverride = null;
            paint();
        }

        public void setValue(int v) {
            int n = clamp(v);
            if (n == value) {
                paint();
                return;
            }
            value = n;
            paint();
        }

        public void stepBy(int delta) {
            if (delta == 0 || row == null || !row.isEnabled()) return;
            int n = clamp(value + delta);
            if (n == value) return;
            value = n;
            paint();
            if (listener != null) listener.onChanged(value);
        }
    }

    public interface IntListener {
        void onChanged(int value);
    }

    public static Step step(LinearLayout root, int max, int progress, IntListener listener) {
        return step(root, null, 0, max, progress, listener);
    }

    public static Step step(LinearLayout root, String label, int min, int max, int progress,
                            IntListener listener) {
        if (max < min) max = min;
        Context ctx = root.getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        int ph = dp(row, 4);
        int pv = dp(row, 6);
        row.setPadding(ph, pv, ph, pv);
        row.setMinimumHeight(dp(row, TILE_MIN_H_DP));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = GAP;
        row.setLayoutParams(rlp);

        if (label != null && !label.trim().isEmpty()) {
            TextView lab = new TextView(ctx);
            lab.setText(label);
            lab.setTextColor(textColor(ctx));
            lab.setTextSize(16f);
            lab.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(lab);
        }

        Button minus = makeButton(ctx, "−", true);
        Button mid = makeButton(ctx, String.valueOf(progress), true);
        Button plus = makeButton(ctx, "+", true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.45f);
        blp.setMargins(0, 0, GAP, 0);
        minus.setLayoutParams(blp);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f);
        mlp.setMargins(0, 0, GAP, 0);
        mid.setLayoutParams(mlp);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.45f);
        plus.setLayoutParams(plp);
        mid.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        mid.setClickable(false);
        mid.setFocusable(false);

        Step st = new Step(row, minus, mid, plus, min, max, progress);
        st.listener = listener;
        minus.setOnClickListener(v -> st.stepBy(-1));
        plus.setOnClickListener(v -> st.stepBy(1));
        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == android.view.KeyEvent.KEYCODE_MINUS) {
                st.stepBy(-1);
                return true;
            }
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == android.view.KeyEvent.KEYCODE_EQUALS
                    || keyCode == android.view.KeyEvent.KEYCODE_PLUS) {
                st.stepBy(1);
                return true;
            }
            return false;
        });

        row.addView(minus);
        row.addView(mid);
        row.addView(plus);
        root.addView(row);
        return st;
    }

    public static SeekBar slider(LinearLayout root, int max, int progress,
                                 SeekBar.OnSeekBarChangeListener l) {
        SeekBar sb = new SeekBar(root.getContext());
        sb.setMax(max);
        sb.setProgress(progress);
        sb.setPadding(4, 8, 4, 8);
        sb.setOnSeekBarChangeListener(l);
        root.addView(sb);
        return sb;
    }

    public static void toast(Activity a, String m) {
        Toast.makeText(a, m, Toast.LENGTH_SHORT).show();
    }
}
