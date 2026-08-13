package com.titanus2.controls.subdisplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import android.widget.TextView;

import com.titanus2.controls.ui.UiKit;

/**
 * Rear display settings — end-user facing.
 * <p>
 * Keyboard-first (no modifiers): 0/O Off · 1 Face · 2 Apps · 3 Cube (if installed) ·
 * L Open rear home · C Classic · T Status · M Minimal · Esc finish.
 */
public class SubDisplayActivity extends Activity {
    private TextView state;
    private final TextView[] modeTiles = new TextView[4];
    private LinearLayout options;
    private SubDisplayPrefs.Mode lastBuilt;
    private TextView colorPreview;
    private boolean cubeAvailable;

    private static final int[] PALETTE = {
        0xFFF0F0F0, // White
        0xFF33FF66, // Green
        0xFFFFB000, // Amber
        SubDisplayPrefs.NIGHT_CYAN, // Cube night cyan
        0xFFFF5252, // Red
        0xFF82B1FF, // Blue
        0xFFFF80AB, // Pink
        0xFFE040FB  // Purple
    };
    private static final String[] PALETTE_NAMES = {
        "White", "Green", "Amber", "Cyan", "Red", "Blue", "Pink", "Purple"
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        UiKit.screen(root);
        sc.addView(root);

        UiKit.title(root, "Sub display");
        state = UiKit.stateLine(root);
        cubeAvailable = SubDisplayPrefs.cubeAppInstalled(this);

        LinearLayout r1 = UiKit.row(root);
        modeTiles[0] = UiKit.flexButton(r1, "Off", () -> setMode(SubDisplayPrefs.Mode.OFF));
        modeTiles[1] = UiKit.flexButton(r1, "Face", () -> setMode(SubDisplayPrefs.Mode.CUSTOM));
        modeTiles[2] = UiKit.flexButton(r1, "Apps", () -> setMode(SubDisplayPrefs.Mode.APPS));
        if (cubeAvailable) {
            modeTiles[3] = UiKit.flexButton(r1, "Cube", () -> setMode(SubDisplayPrefs.Mode.CUBE));
        } else {
            modeTiles[3] = null;
        }

        options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        root.addView(options);

        TextView kbHint = UiKit.mono(root);
        kbHint.setText(cubeAvailable
            ? "0 Off · 1 Face · 2 Apps · 3 Cube · L Open home · Esc"
            : "0 Off · 1 Face · 2 Apps · L Open home · Esc");

        setContentView(sc);

        String raw = getSharedPreferences(SubDisplayPrefs.PREFS, MODE_PRIVATE)
            .getString("mode", "");
        if ("input".equalsIgnoreCase(raw)
            || "stock".equalsIgnoreCase(raw)
            || "aod".equalsIgnoreCase(raw)
            || "system".equalsIgnoreCase(raw)) {
            SubDisplayService.applyMode(this, SubDisplayPrefs.Mode.CUSTOM);
        }
        // Honor current mode (apps keeps digitizer; face/off parks).
        SubDisplayService.applySubtouchPolicy(this);
        rebuildOptions(true);
        paintMode();
        // Land focus on Off so TAB/Enter work immediately.
        if (modeTiles[0] != null) {
            modeTiles[0].post(() -> {
                try { modeTiles[0].requestFocus(); } catch (Exception ignored) {}
            });
        }
    }

    /**
     * TitanKey shortcuts — single owner, no modifiers.
     * Styles only apply when rear is on (turns on if needed).
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && !event.isAltPressed() && !event.isCtrlPressed()
                && !event.isMetaPressed() && !event.isShiftPressed()) {
            int kc = event.getKeyCode();
            switch (kc) {
                case KeyEvent.KEYCODE_ESCAPE:
                    finish();
                    return true;
                case KeyEvent.KEYCODE_O:
                case KeyEvent.KEYCODE_0:
                case KeyEvent.KEYCODE_NUMPAD_0:
                    setMode(SubDisplayPrefs.Mode.OFF);
                    return true;
                case KeyEvent.KEYCODE_1:
                case KeyEvent.KEYCODE_NUMPAD_1:
                    setMode(SubDisplayPrefs.Mode.CUSTOM);
                    return true;
                case KeyEvent.KEYCODE_2:
                case KeyEvent.KEYCODE_NUMPAD_2:
                case KeyEvent.KEYCODE_A:
                    setMode(SubDisplayPrefs.Mode.APPS);
                    return true;
                case KeyEvent.KEYCODE_3:
                case KeyEvent.KEYCODE_NUMPAD_3:
                    if (SubDisplayPrefs.cubeAppInstalled(this)) {
                        setMode(SubDisplayPrefs.Mode.CUBE);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_L:
                    // Open rear Apps home (15.6 launcher; not Settings-only blank).
                    if (SubDisplayPrefs.getMode(this) != SubDisplayPrefs.Mode.APPS) {
                        SubDisplayService.applyMode(this, SubDisplayPrefs.Mode.APPS);
                        rebuildOptions(true);
                        paintMode();
                    }
                    SubDisplayService.launchRearHome(this);
                    UiKit.toast(this, "Rear home");
                    return true;
                case KeyEvent.KEYCODE_C:
                    ensureOnThenStyle(SubDisplayPrefs.FaceStyle.CLASSIC);
                    return true;
                case KeyEvent.KEYCODE_T:
                    ensureOnThenStyle(SubDisplayPrefs.FaceStyle.STATUS);
                    return true;
                case KeyEvent.KEYCODE_M:
                    ensureOnThenStyle(SubDisplayPrefs.FaceStyle.MINIMAL);
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void ensureOnThenStyle(SubDisplayPrefs.FaceStyle style) {
        // Face styles only apply in face mode (not apps digitizer).
        if (SubDisplayPrefs.getMode(this) != SubDisplayPrefs.Mode.CUSTOM) {
            SubDisplayService.applyMode(this, SubDisplayPrefs.Mode.CUSTOM);
        }
        setStyle(style);
    }

    private void setMode(SubDisplayPrefs.Mode mode) {
        if (mode == SubDisplayPrefs.Mode.CUBE && !SubDisplayPrefs.cubeAppInstalled(this)) {
            UiKit.toast(this, "Cube app not installed");
            return;
        }
        SubDisplayService.applyMode(this, mode);
        rebuildOptions(true);
        paintMode();
        String toast;
        if (mode == SubDisplayPrefs.Mode.OFF) toast = "Rear off";
        else if (mode == SubDisplayPrefs.Mode.APPS) {
            // Intent=result: Apps mode opens rear secondary launcher (15.6).
            SubDisplayService.launchRearHome(this);
            toast = "Rear apps";
        } else if (mode == SubDisplayPrefs.Mode.CUBE) toast = "Rear cube";
        else toast = "Rear face";
        UiKit.toast(this, toast);
    }

    private void rebuildOptions(boolean force) {
        SubDisplayPrefs.Mode mode = SubDisplayPrefs.getMode(this);
        if (mode == SubDisplayPrefs.Mode.STOCK) mode = SubDisplayPrefs.Mode.CUSTOM;
        if (!force && mode == lastBuilt) return;
        lastBuilt = mode;
        options.removeAllViews();
        colorPreview = null;

        if (mode == SubDisplayPrefs.Mode.APPS) {
            briAndTimeout();
            UiKit.section(options, "Apps");
            TextView fact = UiKit.mono(options);
            fact.setText("Digitizer on rear · Home tiles (L)");
            LinearLayout openRow = UiKit.row(options);
            UiKit.flexButton(openRow, "Open home", () -> {
                SubDisplayService.launchRearHome(this);
                UiKit.toast(this, "Rear home");
            });
            UiKit.flexButton(openRow, "Settings", () -> {
                SubDisplayService.launchRearSettings(this);
                UiKit.toast(this, "Rear Settings");
            });
            UiKit.section(options, "Sleep");
            addToggle("Show while main off", SubDisplayPrefs.keepRearWhenOff(this), v -> {
                SubDisplayPrefs.setKeepRearWhenOff(this, v);
                SubDisplayService.refresh(this);
            });
            addDt2wToggle();
            // Keep digitizer live; hub/DT2W must not re-park (15.4 residual).
            SubDisplayPrefs.setRearTouchEnabled(this, false);
            SubDisplayService.applySubtouchPolicy(this);
            return;
        }

        if (mode == SubDisplayPrefs.Mode.CUBE) {
            briAndTimeout();
            UiKit.section(options, "Cube");
            TextView fact = UiKit.mono(options);
            fact.setText(
                "Subscreen cube only · separate from Titan Controls hub\n"
                    + "Touch rotates lattice on rear · long-press = cube settings\n"
                    + "Lattice source is independent of the main Neural Cube");
            LinearLayout openRow = UiKit.row(options);
            UiKit.flexButton(openRow, "Show rear cube", () -> {
                SubDisplayPower.apply(this, true,
                    Math.max(1, SubDisplayPrefs.getBrightnessPct(this)), true);
                SubDisplayService.applySubtouchPolicy(this);
                SubDisplayCubeBridge.show(this, true);
                UiKit.toast(this, "Rear cube");
            });
            UiKit.flexButton(openRow, "Cube settings", () -> {
                // Settings on MAIN display — not Controls chrome on rear.
                try {
                    Intent i = new Intent();
                    i.setClassName("com.titanus2.cubecontact",
                        "com.titanus2.cubecontact.CubeSettingsActivity");
                    i.putExtra("plane", "rear");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    UiKit.toast(this, "Cube app missing");
                }
            });
            LinearLayout row2 = UiKit.row(options);
            UiKit.flexButton(row2, "Power + refresh", () -> {
                SubDisplayPower.invalidatePowerState();
                SubDisplayPower.apply(this, true,
                    Math.max(1, SubDisplayPrefs.getBrightnessPct(this)), true);
                SubDisplayService.applySubtouchPolicy(this);
                SubDisplayCubeBridge.dismiss(this);
                SubDisplayCubeBridge.show(this, true);
                SubDisplayService.nudgeWake(this);
                UiKit.toast(this, "Rear powered");
            });
            UiKit.section(options, "Independence");
            SubDisplayPrefs.setKeepRearWhenOff(this, true);
            addToggle("Show while main off / locked", true, v -> {
                SubDisplayPrefs.setKeepRearWhenOff(this, true);
                SubDisplayService.refresh(this);
                UiKit.toast(this, "Cube always keeps rear when mode On");
            });
            addDt2wToggle();
            // Digitizer live for cube touch (applySubtouchPolicy binds sub_touch→rear).
            SubDisplayService.applySubtouchPolicy(this);
            return;
        }

        if (mode == SubDisplayPrefs.Mode.CUSTOM) {
            briAndTimeout();

            UiKit.section(options, "Sleep");
            addToggle("Show while main off", SubDisplayPrefs.keepRearWhenOff(this), v -> {
                SubDisplayPrefs.setKeepRearWhenOff(this, v);
                SubDisplayService.refresh(this);
            });
            addDt2wToggle();

            // Face: never rear trackpad / second cursor.
            SubDisplayPrefs.setRearTouchEnabled(this, false);
            SubDisplayService.applySubtouchPolicy(this);

            UiKit.section(options, "Layout");
            LinearLayout st = UiKit.row(options);
            TextView bClassic = UiKit.flexButton(st, "Classic",
                () -> setStyle(SubDisplayPrefs.FaceStyle.CLASSIC));
            TextView bStatus = UiKit.flexButton(st, "Status",
                () -> setStyle(SubDisplayPrefs.FaceStyle.STATUS));
            TextView bMin = UiKit.flexButton(st, "Minimal",
                () -> setStyle(SubDisplayPrefs.FaceStyle.MINIMAL));
            SubDisplayPrefs.FaceStyle curStyle = SubDisplayPrefs.getFaceStyle(this);
            UiKit.setSelected(bClassic, curStyle == SubDisplayPrefs.FaceStyle.CLASSIC);
            UiKit.setSelected(bStatus, curStyle == SubDisplayPrefs.FaceStyle.STATUS);
            UiKit.setSelected(bMin, curStyle == SubDisplayPrefs.FaceStyle.MINIMAL);

            clockSizeRow();
            colorPickerSection();

            UiKit.section(options, "Time");
            addToggle("24-hour clock", SubDisplayPrefs.hour24(this), v -> {
                SubDisplayPrefs.setHour24(this, v);
                faceRefresh();
            });
            addToggle("Show seconds", SubDisplayPrefs.widgetSeconds(this), v -> {
                SubDisplayPrefs.setWidgetSeconds(this, v);
                faceRefresh();
            });

            UiKit.section(options, "Show on rear");
            addToggle("Weekday", SubDisplayPrefs.widgetWeekday(this), v -> {
                SubDisplayPrefs.setWidgetWeekday(this, v);
                faceRefresh();
            });
            addToggle("Date", SubDisplayPrefs.widgetDate(this), v -> {
                SubDisplayPrefs.setWidgetDate(this, v);
                faceRefresh();
            });
            addToggle("Battery", SubDisplayPrefs.widgetBattery(this), v -> {
                SubDisplayPrefs.setWidgetBattery(this, v);
                faceRefresh();
            });
            addToggle("Notifications", SubDisplayPrefs.widgetNotifs(this), v -> {
                SubDisplayPrefs.setWidgetNotifs(this, v);
                faceRefresh();
                rebuildOptions(true);
            });
            if (SubDisplayPrefs.widgetNotifs(this)) {
                UiKit.section(options, "Notif icons");
                LinearLayout cnt = UiKit.row(options);
                for (int n = 1; n <= 6; n++) {
                    final int num = n;
                    TextView b = UiKit.flexButton(cnt, String.valueOf(num), () -> {
                        SubDisplayPrefs.setNotifMaxApps(this, num);
                        faceRefresh();
                        paintMode();
                        UiKit.toast(this, num + " app icon" + (num > 1 ? "s" : ""));
                        rebuildOptions(true);
                    });
                    UiKit.setSelected(b, SubDisplayPrefs.notifMaxApps(this) == num);
                }
                LinearLayout isz = UiKit.row(options);
                String[] labs = {"XS", "S", "M", "L", "XL"};
                for (int s = 0; s < 5; s++) {
                    final int sc = s;
                    TextView b = UiKit.flexButton(isz, labs[s], () -> {
                        SubDisplayPrefs.setNotifIconScale(this, sc);
                        faceRefresh();
                        paintMode();
                        UiKit.toast(this, "Icon " + labs[sc]);
                        rebuildOptions(true);
                    });
                    UiKit.setSelected(b, SubDisplayPrefs.notifIconScale(this) == sc);
                }
            }
        } else {
            UiKit.note(options, "Rear display is off.");
        }
    }

    /** Preset swatches + optional RGB dialog. */
    private void colorPickerSection() {
        UiKit.section(options, "Color");

        colorPreview = new TextView(this);
        colorPreview.setTextSize(14f);
        colorPreview.setGravity(Gravity.CENTER);
        colorPreview.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        int pad = UiKit.dp(colorPreview, 12);
        colorPreview.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.bottomMargin = UiKit.GAP + 4;
        colorPreview.setLayoutParams(plp);
        options.addView(colorPreview);
        updateColorPreview();

        for (int row = 0; row < 2; row++) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = UiKit.GAP;
            r.setLayoutParams(rlp);
            options.addView(r);

            for (int col = 0; col < 4; col++) {
                final int idx = row * 4 + col;
                final int color = PALETTE[idx];
                final String name = PALETTE_NAMES[idx];

                LinearLayout chip = new LinearLayout(this);
                chip.setOrientation(LinearLayout.VERTICAL);
                chip.setGravity(Gravity.CENTER_HORIZONTAL);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                clp.setMargins(UiKit.GAP / 2, 0, UiKit.GAP / 2, 0);
                chip.setLayoutParams(clp);

                View swatch = new View(this);
                int size = UiKit.dp(swatch, 48);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(size, size);
                slp.gravity = Gravity.CENTER_HORIZONTAL;
                swatch.setLayoutParams(slp);
                swatch.setBackground(colorSwatch(color, isSelectedColor(color)));

                TextView lab = new TextView(this);
                lab.setText(name);
                lab.setTextSize(11f);
                lab.setTextColor(UiKit.textColor(this));
                lab.setGravity(Gravity.CENTER);
                lab.setPadding(0, UiKit.dp(lab, 4), 0, 0);

                chip.addView(swatch);
                chip.addView(lab);
                chip.setClickable(true);
                chip.setFocusable(true);
                chip.setOnClickListener(v -> applyColor(color, name));
                r.addView(chip);
            }
        }

        UiKit.button(options, "Custom…", this::openRgbPicker);
    }

    private boolean isSelectedColor(int color) {
        int cur = SubDisplayPrefs.inkColor(this) | 0xFF000000;
        return (cur & 0x00FFFFFF) == (color & 0x00FFFFFF);
    }

    private GradientDrawable colorSwatch(int fill, boolean selected) {
        // Cube: square swatches only — no ovals / rounded chips.
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(UiKit.CORNER_DP);
        d.setColor(fill);
        if (selected) {
            d.setStroke(UiKit.dp(options, 3), UiKit.liveAccent(this));
        } else {
            d.setStroke(UiKit.dp(options, 1), 0xFF555555);
        }
        return d;
    }

    private void applyColor(int color, String name) {
        SubDisplayPrefs.setFaceTheme(this, SubDisplayPrefs.FaceTheme.CUSTOM);
        SubDisplayPrefs.setCustomInk(this, color);
        faceRefresh();
        paintMode();
        rebuildOptions(true);
        UiKit.toast(this, name);
    }

    /** Minimal RGB mixer — only when user asks for Custom. */
    private void openRgbPicker() {
        int cur = SubDisplayPrefs.inkColor(this) | 0xFF000000;
        final int[] rgb = {
            (cur >> 16) & 0xFF,
            (cur >> 8) & 0xFF,
            cur & 0xFF
        };

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(box, 16);
        box.setPadding(p, p, p, p / 2);

        TextView preview = new TextView(this);
        preview.setGravity(Gravity.CENTER);
        preview.setTextSize(15f);
        preview.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        int pp = UiKit.dp(preview, 14);
        preview.setPadding(pp, pp, pp, pp);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(preview, 56));
        plp.bottomMargin = UiKit.dp(preview, 12);
        preview.setLayoutParams(plp);
        box.addView(preview);

        final TextView[] valueLabs = new TextView[3];
        final String[] labels = {"Red", "Green", "Blue"};
        final int[] accents = {0xFFFF5252, 0xFF33FF66, 0xFF82B1FF};

        Runnable paintPreview = () -> {
            int c = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
            preview.setText(String.format("#%02X%02X%02X", rgb[0], rgb[1], rgb[2]));
            preview.setTextColor(isLight(c) ? 0xFF111111 : 0xFFF5F5F5);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setColor(c);
            bg.setCornerRadius(UiKit.CORNER_DP);
            bg.setStroke(UiKit.dp(preview, 1), 0xFF444444);
            preview.setBackground(bg);
            for (int i = 0; i < 3; i++) {
                if (valueLabs[i] != null) {
                    valueLabs[i].setText(labels[i] + "  " + rgb[i]);
                }
            }
        };

        for (int i = 0; i < 3; i++) {
            final int ch = i;
            TextView lab = new TextView(this);
            lab.setTextColor(accents[i]);
            lab.setTextSize(12f);
            lab.setPadding(0, UiKit.dp(lab, 6), 0, UiKit.dp(lab, 2));
            valueLabs[i] = lab;
            box.addView(lab);

            // Cube square steps — no Material SeekBar in product chrome.
            UiKit.step(box, labels[i], 0, 255, rgb[i], v -> {
                rgb[ch] = v;
                paintPreview.run();
            });
        }
        paintPreview.run();

        new AlertDialog.Builder(this)
            .setTitle("Custom color")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use color", (d, w) -> {
                int c = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
                applyColor(c, String.format("#%02X%02X%02X", rgb[0], rgb[1], rgb[2]));
            })
            .show();
    }

    private void updateColorPreview() {
        if (colorPreview == null) return;
        int ink = SubDisplayPrefs.inkColor(this) | 0xFF000000;
        String name = colorName(ink);
        colorPreview.setText("Now:  " + name);
        colorPreview.setTextColor(isLight(ink) ? 0xFF111111 : 0xFFF5F5F5);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(ink);
        bg.setCornerRadius(UiKit.CORNER_DP);
        bg.setStroke(UiKit.dp(colorPreview, 1), 0xFF444444);
        colorPreview.setBackground(bg);
    }

    private static boolean isLight(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return (r * 0.299 + g * 0.587 + b * 0.114) > 160;
    }

    private static String colorName(int argb) {
        int c = argb & 0x00FFFFFF;
        for (int i = 0; i < PALETTE.length; i++) {
            if ((PALETTE[i] & 0x00FFFFFF) == c) return PALETTE_NAMES[i];
        }
        return String.format("#%06X", c);
    }

    private void setStyle(SubDisplayPrefs.FaceStyle s) {
        SubDisplayPrefs.setFaceStyle(this, s);
        faceRefresh();
        rebuildOptions(true);
        paintMode();
    }

    private void clockSizeRow() {
        UiKit.section(options, "Size");
        LinearLayout sz = UiKit.row(options);
        String[] labs = {"XS", "S", "M", "L", "XL"};
        int cur = SubDisplayPrefs.getClockScale(this);
        for (int i = 0; i < labs.length; i++) {
            final int sc = i;
            TextView b = UiKit.flexButton(sz, labs[i], () -> setScale(sc));
            UiKit.setSelected(b, cur == sc);
        }
    }

    private void setScale(int s) {
        SubDisplayPrefs.setClockScale(this, s);
        faceRefresh();
        rebuildOptions(true);
        paintMode();
        UiKit.toast(this, "Size " + SubDisplayPrefs.clockScaleLabel(this));
    }

    private void faceRefresh() {
        SubDisplayPrefs.Mode mode = SubDisplayPrefs.getMode(this);
        if (mode != SubDisplayPrefs.Mode.CUSTOM && mode != SubDisplayPrefs.Mode.STOCK) {
            return;
        }
        SubDisplaySystemUi.apply(this);
        try {
            com.titanus2.controls.notifled.NotifLedService.refreshRearNotifs();
        } catch (Exception ignored) {}
        SubDisplayFaceOverlay.repaint(this);
        SubDisplayService.refresh(this);
    }

    private void briAndTimeout() {
        UiKit.section(options, "Brightness");
        int bri = Math.max(1, SubDisplayPrefs.getBrightnessPct(this));
        final UiKit.Step[] briHold = new UiKit.Step[1];
        briHold[0] = UiKit.step(options, "Active", 1, 100, bri, p -> {
            SubDisplayPrefs.setBrightnessPct(SubDisplayActivity.this, p);
            if (briHold[0] != null) briHold[0].setDisplay(p + "%");
            if (SubDisplayPrefs.isOn(SubDisplayActivity.this)) {
                SubDisplayPower.applyBrightness(SubDisplayActivity.this, p);
            }
            SubDisplayService.refresh(SubDisplayActivity.this);
            paintMode();
        });
        briHold[0].setDisplay(bri + "%");

        int dim = Math.max(0, SubDisplayPrefs.getDimBrightnessPct(this));
        final UiKit.Step[] dimHold = new UiKit.Step[1];
        dimHold[0] = UiKit.step(options, "Idle", 0, 100, dim, p -> {
            SubDisplayPrefs.setDimBrightnessPct(SubDisplayActivity.this, p);
            if (dimHold[0] != null) dimHold[0].setDisplay(p + "%");
            SubDisplayService.refresh(SubDisplayActivity.this);
            paintMode();
        });
        dimHold[0].setDisplay(dim + "%");

        UiKit.section(options, "Idle after");
        LinearLayout to = UiKit.row(options);
        int toSec = SubDisplayPrefs.getTimeoutSec(this);
        TextView t0 = UiKit.flexButton(to, "Never", () -> setTimeout(0));
        TextView t15 = UiKit.flexButton(to, "15s", () -> setTimeout(15));
        TextView t45 = UiKit.flexButton(to, "45s", () -> setTimeout(45));
        TextView t120 = UiKit.flexButton(to, "2m", () -> setTimeout(120));
        UiKit.setSelected(t0, toSec == 0);
        UiKit.setSelected(t15, toSec == 15);
        UiKit.setSelected(t45, toSec == 45);
        UiKit.setSelected(t120, toSec == 120);
    }

    private void setTimeout(int sec) {
        SubDisplayPrefs.setTimeoutSec(this, sec);
        SubDisplayService.refresh(this);
        rebuildOptions(true);
        paintMode();
    }

    private void addToggle(String label, boolean init, BoolFn fn) {
        // Cube: square Off|On — no Material Switch (PRODUCT_UX geometry).
        UiKit.toggle(options, label, init, fn::set);
    }

    /** Main-panel double-tap-to-wake (optional; default on). */
    private void addDt2wToggle() {
        UiKit.section(options, "Main wake");
        addToggle("Double-tap to wake (main)", SubDisplayPrefs.dt2wEnabled(this), v -> {
            SubDisplayPrefs.setDt2wEnabled(this, v);
            SubDisplaySystemUi.apply(this);
            UiKit.toast(this, v ? "DT2W on" : "DT2W off");
        });
    }

    private interface BoolFn { void set(boolean v); }

    private void paintMode() {
        SubDisplayPrefs.Mode mode = SubDisplayPrefs.getMode(this);
        if (mode == SubDisplayPrefs.Mode.STOCK) mode = SubDisplayPrefs.Mode.CUSTOM;
        UiKit.setSelected(modeTiles[0], mode == SubDisplayPrefs.Mode.OFF);
        UiKit.setSelected(modeTiles[1], mode == SubDisplayPrefs.Mode.CUSTOM);
        UiKit.setSelected(modeTiles[2], mode == SubDisplayPrefs.Mode.APPS);
        if (modeTiles[3] != null) {
            UiKit.setSelected(modeTiles[3], mode == SubDisplayPrefs.Mode.CUBE);
        }
        if (state == null) return;
        if (mode == SubDisplayPrefs.Mode.OFF) {
            state.setText("Off");
            return;
        }
        if (mode == SubDisplayPrefs.Mode.APPS) {
            state.setText("Apps · digitizer · "
                + Math.max(1, SubDisplayPrefs.getBrightnessPct(this)) + "%");
            return;
        }
        if (mode == SubDisplayPrefs.Mode.CUBE) {
            state.setText("Cube · live lattice · "
                + Math.max(1, SubDisplayPrefs.getBrightnessPct(this)) + "%");
            return;
        }
        StringBuilder sb = new StringBuilder("Face");
        sb.append(" · ")
            .append(SubDisplayPrefs.getFaceStyle(this).name().toLowerCase())
            .append(" · ")
            .append(colorName(SubDisplayPrefs.inkColor(this)))
            .append(" · ")
            .append(SubDisplayPrefs.clockScaleLabel(this));
        if (SubDisplayPrefs.widgetNotifs(this)) {
            sb.append(" · notif ")
                .append(SubDisplayPrefs.notifMaxApps(this))
                .append("×")
                .append(SubDisplayPrefs.notifIconScaleLabel(this));
        }
        state.setText(sb.toString());
        updateColorPreview();
    }

    @Override protected void onResume() {
        super.onResume();
        rebuildOptions(false);
        paintMode();
    }
}
