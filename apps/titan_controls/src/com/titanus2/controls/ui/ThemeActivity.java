package com.titanus2.controls.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.titanus2.ui.ThemeSliders;

/**
 * OS accent + themed app-icon color + day-night.
 * Custom HSV/RGB/hex editor is the seed for both accent and icons.
 */
public class ThemeActivity extends Activity {
    private TextView status;
    private String lastApply = "";
    private ThemeSliders.ColorEditor colorEd;
    private ThemeSliders.ColorField plateField;
    private ThemeSliders.ColorField glyphField;
    private ThemeSliders.ColorField settingsPlateField;
    private ThemeSliders.ColorField settingsGlyphField;
    private TextView[] glowTiles;
    private TextView[] modeTiles;
    private TextView[] plateTiles;
    private TextView[] glyphTiles;
    private TextView[] settingsPresetTiles;

    private static final String[] MODE_LABELS = { "Night", "Day", "Auto" };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.applyOpaqueWindow(this);

        ScrollView scroll = new ScrollView(this);
        UiKit.prepareScroll(scroll);
        LinearLayout root = new LinearLayout(this);
        UiKit.screen(root);

        UiKit.title(root, "Theme");
        status = UiKit.mono(root);
        lastApply = "honor";

        UiKit.section(root, "Accent");
        glowTiles = ThemeSliders.presetRow(root, ThemePrefs.PRESET_LABELS,
            ThemeSliders.indexOf(ThemePrefs.PRESET_COLORS, ThemePrefs.accent(this)), i -> {
                applySeedAsync(ThemePrefs.PRESET_COLORS[i]);
            });
        colorEd = ThemeSliders.inlineEditor(root, ThemePrefs.accent(this));
        UiKit.button(root, "Apply color", () -> {
            int c = colorEd != null ? colorEd.getColor() : ThemePrefs.accent(this);
            applySeedAsync(c);
        });

        UiKit.section(root, "Mode");
        modeTiles = ThemeSliders.presetRow(root, MODE_LABELS, modeIndex(), i -> {
            ThemePrefs.setDayNight(this, modeKey(i));
            lastApply = "os";
            if (i != 2) {
                recreate();
                return;
            }
            refresh();
        });

        UiKit.section(root, "Icon plate");
        plateTiles = ThemeSliders.presetRow(root, ThemePrefs.ICON_PLATE_LABELS,
            ThemeSliders.indexOf(ThemePrefs.ICON_PLATE_COLORS, ThemePrefs.iconPlate(this)), i -> {
                applyIconsAsync(hex6(ThemePrefs.ICON_PLATE_COLORS[i]),
                    ThemePrefs.iconGlyphHex(this));
            });
        plateField = ThemeSliders.colorField(root, "Plate", ThemePrefs.iconPlate(this), c -> {
            applyIconsAsync(hex6(c), ThemePrefs.iconGlyphHex(this));
        });

        UiKit.section(root, "Icon color");
        glyphTiles = ThemeSliders.presetRow(root, ThemePrefs.ICON_GLYPH_LABELS,
            ThemeSliders.indexOf(ThemePrefs.ICON_GLYPH_COLORS, ThemePrefs.iconGlyph(this)), i -> {
                applyIconsAsync(ThemePrefs.iconPlateHex(this),
                    hex6(ThemePrefs.ICON_GLYPH_COLORS[i]));
            });
        glyphField = ThemeSliders.colorField(root, "Icons", ThemePrefs.iconGlyph(this), c -> {
            applyIconsAsync(ThemePrefs.iconPlateHex(this), hex6(c));
        });

        UiKit.section(root, "Settings icons");
        UiKit.toggle(root, "Monochrome", ThemePrefs.settingsMonoOn(this), on -> {
            lastApply = "settings…";
            refresh();
            new Thread(() -> {
                lastApply = ThemePrefs.setSettingsMono(this, on);
                runOnUiThread(this::refresh);
            }, "set-mono").start();
        });
        settingsPresetTiles = ThemeSliders.presetRow(root, ThemePrefs.SETTINGS_LOOK_LABELS,
            settingsLookIndex(), i -> {
                applySettingsLook(i);
            });
        settingsPlateField = ThemeSliders.colorField(root, "Background",
            ThemePrefs.settingsPlate(this), c -> {
                applySettingsAsync(hex6(c), ThemePrefs.settingsGlyphHex(this));
            });
        settingsGlyphField = ThemeSliders.colorField(root, "Color",
            ThemePrefs.settingsGlyph(this), c -> {
                applySettingsAsync(ThemePrefs.settingsPlateHex(this), hex6(c));
            });
        UiKit.button(root, "Apply Settings icons", () -> {
            lastApply = "settings…";
            refresh();
            new Thread(() -> {
                lastApply = ThemePrefs.setSettingsOverlay(this,
                    ThemePrefs.settingsPlateHex(this), ThemePrefs.settingsGlyphHex(this));
                runOnUiThread(this::refresh);
            }, "set-apply").start();
        });

        UiKit.section(root, "App icons");
        UiKit.toggle(root, "Cube icons", ThemePrefs.appIconsOn(this), on -> {
            lastApply = "apps…";
            refresh();
            new Thread(() -> {
                lastApply = ThemePrefs.setAppIconsOn(this, on);
                runOnUiThread(this::refresh);
            }, "cube-apps").start();
        });
        UiKit.button(root, "Apply icons", () -> {
            lastApply = "apply…";
            refresh();
            new Thread(() -> {
                lastApply = ThemePrefs.setIconOverlay(this,
                    ThemePrefs.iconPlateHex(this), ThemePrefs.iconGlyphHex(this));
                runOnUiThread(this::refresh);
            }, "cube-apply").start();
        });
        UiKit.button(root, "Match terminal", () -> {
            lastApply = "term…";
            refresh();
            new Thread(() -> {
                lastApply = ThemePrefs.matchTerminal(this);
                runOnUiThread(this::refresh);
            }, "cube-term").start();
        });

        TextView note = UiKit.mono(root);
        note.setText("Settings icons: mono + background + color · Crimson is a pick");

        scroll.addView(root);
        setContentView(scroll);
        refresh();
    }

    private static String hex6(int argb) {
        return String.format("%06x", 0xFFFFFF & argb);
    }

    private int modeIndex() {
        String m = ThemePrefs.dayNight(this);
        if (ThemePrefs.MODE_DAY.equals(m)) return 1;
        if (ThemePrefs.MODE_AUTO.equals(m)) return 2;
        return 0;
    }

    private static String modeKey(int i) {
        if (i == 1) return ThemePrefs.MODE_DAY;
        if (i == 2) return ThemePrefs.MODE_AUTO;
        return ThemePrefs.MODE_NIGHT;
    }

    private void applySeedAsync(int argb) {
        lastApply = "color…";
        if (colorEd != null) colorEd.setColor(argb);
        refresh();
        new Thread(() -> {
            lastApply = ThemePrefs.setColorSeed(this, argb);
            runOnUiThread(this::refresh);
        }, "cube-seed").start();
    }

    private int settingsLookIndex() {
        return ThemePrefs.settingsLookIndex(this);
    }

    private void applySettingsLook(int i) {
        if (i < 0 || i >= ThemePrefs.SETTINGS_LOOK_LABELS.length) return;
        applySettingsAsync(
            hex6(ThemePrefs.SETTINGS_LOOK_PLATES[i]),
            hex6(ThemePrefs.SETTINGS_LOOK_GLYPHS[i]));
    }

    private void applySettingsAsync(String plate, String glyph) {
        lastApply = "settings…";
        refresh();
        new Thread(() -> {
            lastApply = ThemePrefs.setSettingsOverlay(this, plate, glyph);
            runOnUiThread(this::refresh);
        }, "set-icons").start();
    }

    private void applyIconsAsync(String plate, String glyph) {
        lastApply = "icons…";
        refresh();
        new Thread(() -> {
            String fact = ThemePrefs.setIconOverlay(this, plate, glyph);
            lastApply = fact;
            runOnUiThread(this::refresh);
        }, "cube-icons").start();
    }

    private void refresh() {
        if (status == null) return;
        String apply = lastApply == null || lastApply.isEmpty() ? "?" : lastApply;
        status.setText("accent " + ThemePrefs.accentHex(this)
            + " · " + ThemePrefs.dayNight(this)
            + " · settings #" + ThemePrefs.settingsPlateHex(this)
            + "/#" + ThemePrefs.settingsGlyphHex(this)
            + (ThemePrefs.settingsMonoOn(this) ? " mono" : " off")
            + " · " + apply);
        status.setTextColor(ThemePrefs.iconGlyph(this));
        if (colorEd != null && !"color…".equals(lastApply)) {
            colorEd.setColor(ThemePrefs.accent(this));
        }
        if (plateField != null) plateField.setColor(ThemePrefs.iconPlate(this));
        if (glyphField != null) glyphField.setColor(ThemePrefs.iconGlyph(this));
        if (settingsPlateField != null) {
            settingsPlateField.setColor(ThemePrefs.settingsPlate(this));
        }
        if (settingsGlyphField != null) {
            settingsGlyphField.setColor(ThemePrefs.settingsGlyph(this));
        }
        ThemeSliders.selectPreset(settingsPresetTiles, settingsLookIndex());
        ThemeSliders.selectPreset(glowTiles, ThemeSliders.indexOf(
            ThemePrefs.PRESET_COLORS, ThemePrefs.accent(this)));
        ThemeSliders.selectPreset(modeTiles, modeIndex());
        ThemeSliders.selectPreset(plateTiles, ThemeSliders.indexOf(
            ThemePrefs.ICON_PLATE_COLORS, ThemePrefs.iconPlate(this)));
        ThemeSliders.selectPreset(glyphTiles, ThemeSliders.indexOf(
            ThemePrefs.ICON_GLYPH_COLORS, ThemePrefs.iconGlyph(this)));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            int kc = event.getKeyCode();
            if (kc == KeyEvent.KEYCODE_ESCAPE || kc == KeyEvent.KEYCODE_BACK) {
                finish();
                return true;
            }
            boolean typing = getCurrentFocus() instanceof android.widget.EditText;
            if (!typing && kc >= KeyEvent.KEYCODE_1 && kc <= KeyEvent.KEYCODE_5) {
                int i = kc - KeyEvent.KEYCODE_1;
                if (i < ThemePrefs.PRESET_COLORS.length) {
                    applySeedAsync(ThemePrefs.PRESET_COLORS[i]);
                    return true;
                }
            }
            if (typing) {
                return super.dispatchKeyEvent(event);
            }
            if (kc == KeyEvent.KEYCODE_N) {
                ThemePrefs.setDayNight(this, ThemePrefs.MODE_NIGHT);
                recreate();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_D) {
                ThemePrefs.setDayNight(this, ThemePrefs.MODE_DAY);
                recreate();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_M) {
                lastApply = "term…";
                refresh();
                new Thread(() -> {
                    lastApply = ThemePrefs.matchTerminal(this);
                    runOnUiThread(this::refresh);
                }, "cube-term").start();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
