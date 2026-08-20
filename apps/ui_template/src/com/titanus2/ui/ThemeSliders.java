package com.titanus2.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Shared theme sliders — Atlas terminal Appearance and Titan Controls Theme.
 * HSV picker + font SeekBar + color field + preset tiles.
 * Platform widgets only (no Material AAR, no per-app UiKit).
 */
public final class ThemeSliders {
    public static final int FONT_MIN_SP = 8;
    public static final int FONT_MAX_SP = 32;

    private static final int TAG_SELECTED = 0x70C05E2;
    private static final int TAG_SWATCH = 0x70C05E3;
    private static final int TAG_HEX = 0x70C05E4;

    private ThemeSliders() {}

    public interface ColorPick {
        void onColor(int argb);
    }

    public interface IntPick {
        void onValue(int value);
    }

    public static String hex(int argb) {
        return String.format("#%06X", 0xFFFFFF & argb);
    }

    public static int parse(String raw, int fallback) {
        if (raw == null) return fallback;
        String s = raw.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        try {
            if (s.length() == 6) return 0xFF000000 | Integer.parseInt(s, 16);
            if (s.length() == 8) return (int) Long.parseLong(s, 16);
        } catch (Exception ignored) {}
        return fallback;
    }

    /** Font size 8–32 sp. Owns its label. */
    public static TextView fontSlider(LinearLayout root, int currentSp, IntPick onChange) {
        int cur = clamp(currentSp, FONT_MIN_SP, FONT_MAX_SP);
        TextView lab = sliderLabel(root, cur + " sp");
        SeekBar sb = new SeekBar(root.getContext());
        sb.setMax(FONT_MAX_SP - FONT_MIN_SP);
        sb.setProgress(cur - FONT_MIN_SP);
        sb.setPadding(dp(root, 4), dp(root, 8), dp(root, 4), dp(root, 8));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int sp = p + FONT_MIN_SP;
                lab.setText(sp + " sp");
                if (onChange != null) onChange.onValue(sp);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(sb);
        return lab;
    }

    public static TextView sliderLabel(LinearLayout root, String initial) {
        TextView tv = new TextView(root.getContext());
        tv.setText(initial);
        tv.setTextColor(mutedColor(root.getContext()));
        tv.setTextSize(13f);
        tv.setPadding(0, dp(tv, 6), 0, dp(tv, 2));
        root.addView(tv);
        return tv;
    }

    /** Labeled SeekBar column (Hue / Saturation / Value). */
    public static LinearLayout labeledSeek(Context c, String label, int min, int max,
                                           int value, IntPick onChange) {
        return bindSeek(c, label, min, max, value, onChange).col;
    }

    public static final class SeekBind {
        public final LinearLayout col;
        public final SeekBar bar;
        public final TextView caption;
        private int min;
        private boolean syncing;

        private SeekBind(LinearLayout col, SeekBar bar, TextView caption, int min) {
            this.col = col;
            this.bar = bar;
            this.caption = caption;
            this.min = min;
        }

        public void setValue(int value) {
            if (bar == null) return;
            syncing = true;
            int max = bar.getMax();
            bar.setProgress(clamp(value - min, 0, max));
            syncing = false;
        }

        public boolean isSyncing() {
            return syncing;
        }
    }

    public static SeekBind bindSeek(Context c, String label, int min, int max,
                                    int value, IntPick onChange) {
        if (max < min) max = min;
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(c, 4), 0, dp(c, 4));

        TextView t = new TextView(c);
        t.setText(label + "  " + value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(mutedColor(c));
        t.setTypeface(Typeface.MONOSPACE);
        col.addView(t, match());

        SeekBar sb = new SeekBar(c);
        sb.setMax(max - min);
        sb.setProgress(clamp(value - min, 0, max - min));
        SeekBind bind = new SeekBind(col, sb, t, min);
        final String base = label;
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = progress + min;
                t.setText(base + "  " + v);
                if (!bind.isSyncing() && onChange != null) onChange.onValue(v);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        col.addView(sb, match());
        return bind;
    }

    /**
     * HSV + RGB + hex editor. Preview updates live. {@link #getColor()} is the
     * current pick. Callers commit on Apply — not on every tick.
     */
    public static final class ColorEditor {
        public final LinearLayout root;
        private final View preview;
        private final GradientDrawable previewGd;
        private final EditText hexEdit;
        private final SeekBind hue;
        private final SeekBind sat;
        private final SeekBind val;
        private final SeekBind red;
        private final SeekBind green;
        private final SeekBind blue;
        private final float[] hsv = new float[3];
        private int color;
        private boolean syncing;
        private boolean hexEditing;

        private ColorEditor(Context c, int initial) {
            color = initial | 0xFF000000;
            Color.colorToHSV(color, hsv);

            root = new LinearLayout(c);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(0, dp(c, 4), 0, dp(c, 4));

            previewGd = new GradientDrawable();
            previewGd.setCornerRadius(0f);
            previewGd.setColor(color);
            preview = new View(c);
            preview.setBackground(previewGd);
            root.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 56)));

            View hueBar = new View(c);
            GradientDrawable rainbow = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{
                    0xFFFF0000, 0xFFFFFF00, 0xFF00FF00,
                    0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000
                });
            rainbow.setCornerRadius(0f);
            hueBar.setBackground(rainbow);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 8));
            hlp.topMargin = dp(c, 8);
            hueBar.setLayoutParams(hlp);
            root.addView(hueBar);

            hexEdit = new EditText(c);
            hexEdit.setTypeface(Typeface.MONOSPACE);
            hexEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            hexEdit.setGravity(Gravity.CENTER);
            hexEdit.setSingleLine(true);
            hexEdit.setImeOptions(EditorInfo.IME_ACTION_DONE);
            hexEdit.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            hexEdit.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(7),
                (src, start, end, dest, dstart, dend) -> {
                    StringBuilder b = new StringBuilder();
                    for (int i = start; i < end; i++) {
                        char ch = src.charAt(i);
                        if (ch == '#' && dstart + b.length() == 0) {
                            b.append('#');
                        } else if ((ch >= '0' && ch <= '9')
                                || (ch >= 'A' && ch <= 'F')
                                || (ch >= 'a' && ch <= 'f')) {
                            b.append(Character.toUpperCase(ch));
                        }
                    }
                    return b.toString();
                }
            });
            hexEdit.setText(hex(color));
            hexEdit.setTextColor(textColor(c));
            hexEdit.setHint("#RRGGBB");
            hexEdit.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}
                @Override public void afterTextChanged(Editable s) {
                    if (syncing) return;
                    int parsed = parse(s == null ? null : s.toString(), 0);
                    if (parsed == 0 && (s == null || s.length() < 6)) return;
                    hexEditing = true;
                    setColor(parsed == 0 ? color : parsed);
                    hexEditing = false;
                }
            });
            root.addView(hexEdit, match());

            hue = bindSeek(c, "Hue", 0, 360, Math.round(hsv[0]), v -> {
                hsv[0] = v;
                fromHsv();
            });
            sat = bindSeek(c, "Saturation", 0, 100, Math.round(hsv[1] * 100f), v -> {
                hsv[1] = v / 100f;
                fromHsv();
            });
            val = bindSeek(c, "Value", 0, 100, Math.round(hsv[2] * 100f), v -> {
                hsv[2] = v / 100f;
                fromHsv();
            });
            red = bindSeek(c, "Red", 0, 255, Color.red(color), this::fromRgb);
            green = bindSeek(c, "Green", 0, 255, Color.green(color), this::fromRgb);
            blue = bindSeek(c, "Blue", 0, 255, Color.blue(color), this::fromRgb);
            root.addView(hue.col, match());
            root.addView(sat.col, match());
            root.addView(val.col, match());
            root.addView(red.col, match());
            root.addView(green.col, match());
            root.addView(blue.col, match());
        }

        public int getColor() {
            return color;
        }

        public void setColor(int argb) {
            int packed = argb | 0xFF000000;
            if (packed == color && !hexEditing) return;
            color = packed;
            if (syncing) {
                paint();
                return;
            }
            syncing = true;
            Color.colorToHSV(color, hsv);
            hue.setValue(Math.round(hsv[0]));
            sat.setValue(Math.round(hsv[1] * 100f));
            val.setValue(Math.round(hsv[2] * 100f));
            red.setValue(Color.red(color));
            green.setValue(Color.green(color));
            blue.setValue(Color.blue(color));
            paint();
            syncing = false;
        }

        private void fromHsv() {
            if (syncing) return;
            syncing = true;
            color = Color.HSVToColor(hsv) | 0xFF000000;
            red.setValue(Color.red(color));
            green.setValue(Color.green(color));
            blue.setValue(Color.blue(color));
            paint();
            syncing = false;
        }

        private void fromRgb(int ignored) {
            if (syncing) return;
            syncing = true;
            color = Color.rgb(red.bar.getProgress(), green.bar.getProgress(),
                blue.bar.getProgress()) | 0xFF000000;
            Color.colorToHSV(color, hsv);
            hue.setValue(Math.round(hsv[0]));
            sat.setValue(Math.round(hsv[1] * 100f));
            val.setValue(Math.round(hsv[2] * 100f));
            paint();
            syncing = false;
        }

        private void paint() {
            previewGd.setColor(color);
            preview.setBackground(previewGd);
            if (!hexEditing && hexEdit != null) {
                String h = hex(color);
                if (!h.equalsIgnoreCase(hexEdit.getText() == null
                        ? "" : hexEdit.getText().toString())) {
                    hexEdit.setText(h);
                    hexEdit.setSelection(h.length());
                }
            }
        }
    }

    public static ColorEditor inlineEditor(LinearLayout parent, int initial) {
        ColorEditor ed = new ColorEditor(parent.getContext(), initial);
        parent.addView(ed.root, match());
        return ed;
    }

    /** Dialog picker: HSV + RGB + hex. Apply commits. */
    public static void openColorPicker(Activity a, String title, int initial, ColorPick onOk) {
        if (a == null) return;
        ColorEditor ed = new ColorEditor(a, initial);
        ed.root.setPadding(dp(a, 20), dp(a, 12), dp(a, 20), dp(a, 8));
        new AlertDialog.Builder(a)
            .setTitle(title == null ? "Color" : title)
            .setView(ed.root)
            .setPositiveButton("Apply", (d, w) -> {
                if (onOk != null) onOk.onColor(ed.getColor());
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Settings row: title + hex + square swatch. Opens the HSV picker. */
    public static ColorField colorField(LinearLayout parent, String title, int color,
                                        ColorPick onOk) {
        Context ctx = parent.getContext();
        Activity act = (ctx instanceof Activity) ? (Activity) ctx : null;

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        int ph = dp(ctx, 4);
        int pv = dp(ctx, 12);
        row.setPadding(ph, pv, ph, pv);
        row.setMinimumHeight(dp(ctx, 40));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 2);
        row.setLayoutParams(lp);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextColor(textColor(ctx));
        t.setTextSize(16f);
        t.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView hexTv = new TextView(ctx);
        hexTv.setTypeface(Typeface.MONOSPACE);
        hexTv.setTextSize(13f);
        hexTv.setTextColor(mutedColor(ctx));
        hexTv.setPadding(0, 0, dp(ctx, 10), 0);
        hexTv.setTag(TAG_HEX);

        View swatch = new View(ctx);
        swatch.setTag(TAG_SWATCH);
        paintSwatch(swatch, color);
        hexTv.setText(hex(color));

        TextView chev = new TextView(ctx);
        chev.setText("›");
        chev.setTextColor(mutedColor(ctx));
        chev.setTextSize(22f);
        chev.setPadding(dp(ctx, 8), 0, 0, 0);

        row.addView(t);
        row.addView(hexTv);
        row.addView(swatch, new LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24)));
        row.addView(chev);
        parent.addView(row);

        ColorField field = new ColorField(row, hexTv, swatch, color);
        row.setOnClickListener(v -> {
            if (act == null) return;
            openColorPicker(act, title, field.color, picked -> {
                field.setColor(picked);
                if (onOk != null) onOk.onColor(picked);
            });
        });
        return field;
    }

    public static final class ColorField {
        public final LinearLayout row;
        private final TextView hexTv;
        private final View swatch;
        private int color;

        private ColorField(LinearLayout row, TextView hexTv, View swatch, int color) {
            this.row = row;
            this.hexTv = hexTv;
            this.swatch = swatch;
            this.color = color | 0xFF000000;
        }

        public int getColor() {
            return color;
        }

        public void setColor(int argb) {
            color = argb | 0xFF000000;
            if (hexTv != null) hexTv.setText(hex(color));
            paintSwatch(swatch, color);
        }
    }

    /** Horizontal preset tiles. selectedIndex -1 = none. */
    public static TextView[] presetRow(LinearLayout parent, String[] labels, int selectedIndex,
                                       IntPick onPick) {
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(parent.getContext(), 4);
        row.setLayoutParams(rlp);
        parent.addView(row);

        if (labels == null || labels.length == 0) return new TextView[0];
        TextView[] tiles = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView b = new TextView(row.getContext());
            b.setText(labels[i]);
            b.setAllCaps(false);
            b.setTextSize(13f);
            b.setGravity(Gravity.CENTER);
            b.setMinHeight(dp(b, 40));
            b.setClickable(true);
            b.setFocusable(true);
            b.setPadding(dp(b, 6), dp(b, 8), dp(b, 6), dp(b, 8));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            blp.setMargins(0, 0, dp(b, 4), 0);
            b.setLayoutParams(blp);
            b.setOnClickListener(v -> {
                selectPreset(tiles, idx);
                if (onPick != null) onPick.onValue(idx);
            });
            tiles[i] = b;
            row.addView(b);
        }
        selectPreset(tiles, selectedIndex);
        return tiles;
    }

    public static void selectPreset(TextView[] tiles, int selectedIndex) {
        if (tiles == null) return;
        for (int i = 0; i < tiles.length; i++) {
            setSelected(tiles[i], i == selectedIndex);
        }
    }

    public static int indexOf(int[] colors, int argb) {
        if (colors == null) return -1;
        int packed = argb | 0xFF000000;
        for (int i = 0; i < colors.length; i++) {
            if ((colors[i] | 0xFF000000) == packed) return i;
        }
        return -1;
    }

    public static void setSelected(TextView tile, boolean on) {
        if (tile == null) return;
        tile.setTag(TAG_SELECTED, on);
        tile.setActivated(on);
        tile.setSelected(on);
        tile.setTypeface(Typeface.SANS_SERIF, on ? Typeface.BOLD : Typeface.NORMAL);
        Context c = tile.getContext();
        GradientDrawable chip = new GradientDrawable();
        chip.setShape(GradientDrawable.RECTANGLE);
        chip.setCornerRadius(0f);
        if (on) {
            chip.setColor(accent(c));
            tile.setBackground(chip);
            tile.setTextColor(0xFF00332E);
        } else {
            chip.setColor(isNight(c) ? 0xFF1E1E1E : 0xFFE8E8E8);
            tile.setBackground(chip);
            tile.setTextColor(textColor(c));
        }
    }

    private static void paintSwatch(View swatch, int color) {
        if (swatch == null) return;
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(0f);
        g.setColor(color | 0xFF000000);
        g.setStroke(dp(swatch.getContext(), 1), 0x44000000);
        swatch.setBackground(g);
    }

    private static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static boolean isNight(Context c) {
        if (c == null) return true;
        try {
            int ui = c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
            return ui == Configuration.UI_MODE_NIGHT_YES;
        } catch (Exception ignored) {
            return true;
        }
    }

    private static int accent(Context c) {
        return 0xFF000000 | (resolveAttr(c, android.R.attr.colorAccent, 0xFFFF141A) & 0x00FFFFFF);
    }

    private static int textColor(Context c) {
        if (isNight(c)) return 0xFFF5F5F5;
        return 0xFF000000 | (resolveAttr(c, android.R.attr.textColorPrimary, 0xFF212121) & 0x00FFFFFF);
    }

    private static int mutedColor(Context c) {
        if (isNight(c)) return 0xFFB0B0B0;
        return 0xFF000000 | (resolveAttr(c, android.R.attr.textColorSecondary, 0xFF757575) & 0x00FFFFFF);
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

    private static int dp(View v, int dps) {
        return dp(v.getContext(), dps);
    }

    private static int dp(Context c, int dps) {
        return Math.round(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dps, c.getResources().getDisplayMetrics()));
    }
}
