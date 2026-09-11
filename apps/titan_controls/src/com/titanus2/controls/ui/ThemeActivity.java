package com.titanus2.controls.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.titanus2.ui.ThemeSliders;

/**
 * Mode + Settings homepage icons + launcher app icons (plate / color / shape).
 * Apply writes the pick. No OS-accent control that paints nothing.
 */
public class ThemeActivity extends Activity {
    private TextView status;
    private String lastApply = "";
    private ThemeSliders.ColorField accentField;
    private ThemeSliders.ColorField navField;
    private ThemeSliders.ColorField qsField;
    private ThemeSliders.ColorField plateField;
    private ThemeSliders.ColorField glyphField;
    private ThemeSliders.ColorField settingsPlateField;
    private ThemeSliders.ColorField settingsGlyphField;
    private TextView[] glowTiles;
    private TextView[] navTiles;
    private TextView[] qsTiles;
    private TextView[] modeTiles;
    private TextView[] plateTiles;
    private TextView[] glyphTiles;
    private TextView[] shapeTiles;
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
                ThemePrefs.persistAccent(this, ThemePrefs.PRESET_COLORS[i]);
                if (accentField != null) accentField.setColor(ThemePrefs.PRESET_COLORS[i]);
                refresh();
            });
        accentField = ThemeSliders.colorField(root, "Color", ThemePrefs.accent(this), c -> {
            ThemePrefs.persistAccent(this, c);
        });
        UiKit.button(root, "Apply accent", this::applyAccentFromField);

        UiKit.section(root, "System chrome");
        navTiles = ThemeSliders.presetRow(root, ThemePrefs.ICON_GLYPH_LABELS,
            ThemeSliders.indexOf(ThemePrefs.ICON_GLYPH_COLORS, ThemePrefs.navTint(this)), i -> {
                ThemePrefs.persistNavTint(this, ThemePrefs.ICON_GLYPH_COLORS[i]);
                if (navField != null) navField.setColor(ThemePrefs.ICON_GLYPH_COLORS[i]);
                refresh();
            });
        navField = ThemeSliders.colorField(root, "Tint", ThemePrefs.navTint(this), c -> {
            ThemePrefs.persistNavTint(this, c);
        });
        UiKit.button(root, "Apply chrome", this::applyNavFromField);

        UiKit.section(root, "Shade");
        UiKit.toggle(root, "Glass", ThemePrefs.isGlass(this), on -> {
            lastApply = "queued";
            refresh();
            new Thread(() -> {
                lastApply = ThemePrefs.setGlass(this, on);
                runOnUiThread(this::refresh);
            }, "glass-apply").start();
        });
        qsTiles = ThemeSliders.presetRow(root, ThemePrefs.ICON_PLATE_LABELS,
            ThemeSliders.indexOf(ThemePrefs.ICON_PLATE_COLORS, ThemePrefs.qsBg(this)), i -> {
                ThemePrefs.persistQsBg(this, ThemePrefs.ICON_PLATE_COLORS[i]);
                if (qsField != null) qsField.setColor(ThemePrefs.ICON_PLATE_COLORS[i]);
                refresh();
            });
        qsField = ThemeSliders.colorField(root, "Background", ThemePrefs.qsBg(this), c -> {
            ThemePrefs.persistQsBg(this, c);
        });
        UiKit.button(root, "Apply QS background", this::applyQsFromField);

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
                ThemePrefs.persistSettingsOverlay(this, hex6(c),
                    ThemePrefs.settingsGlyphHex(this));
            });
        settingsGlyphField = ThemeSliders.colorField(root, "Color",
            ThemePrefs.settingsGlyph(this), c -> {
                ThemePrefs.persistSettingsOverlay(this, ThemePrefs.settingsPlateHex(this),
                    hex6(c));
            });
        UiKit.button(root, "Apply Settings icons", this::applySettingsFromFields);

        UiKit.section(root, "App icons");
        plateTiles = ThemeSliders.presetRow(root, ThemePrefs.ICON_PLATE_LABELS,
            ThemeSliders.indexOf(ThemePrefs.ICON_PLATE_COLORS, ThemePrefs.iconPlate(this)), i -> {
                ThemePrefs.persistIconOverlay(this, hex6(ThemePrefs.ICON_PLATE_COLORS[i]),
                    ThemePrefs.iconGlyphHex(this));
                if (plateField != null) plateField.setColor(ThemePrefs.ICON_PLATE_COLORS[i]);
                refresh();
            });
        plateField = ThemeSliders.colorField(root, "Background", ThemePrefs.iconPlate(this), c -> {
            ThemePrefs.persistIconOverlay(this, hex6(c), ThemePrefs.iconGlyphHex(this));
        });
        glyphTiles = ThemeSliders.presetRow(root, ThemePrefs.ICON_GLYPH_LABELS,
            ThemeSliders.indexOf(ThemePrefs.ICON_GLYPH_COLORS, ThemePrefs.iconGlyph(this)), i -> {
                ThemePrefs.persistIconOverlay(this, ThemePrefs.iconPlateHex(this),
                    hex6(ThemePrefs.ICON_GLYPH_COLORS[i]));
                if (glyphField != null) glyphField.setColor(ThemePrefs.ICON_GLYPH_COLORS[i]);
                refresh();
            });
        glyphField = ThemeSliders.colorField(root, "Color", ThemePrefs.iconGlyph(this), c -> {
            ThemePrefs.persistIconOverlay(this, ThemePrefs.iconPlateHex(this), hex6(c));
        });
        shapeTiles = ThemeSliders.presetRow(root, ThemePrefs.ICON_SHAPE_LABELS,
            ThemePrefs.iconShapeIndex(this), i -> {
                ThemePrefs.persistIconShape(this, ThemePrefs.ICON_SHAPE_IDS[i]);
                refresh();
            });
        UiKit.button(root, "Apply app icons", this::applyIconsFromFields);

        TextView note = UiKit.mono(root);
        note.setText("Glass = QS + navbar + app drawer see-through. Chrome = icons. Apply does not wait.");

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

    private int settingsLookIndex() {
        return ThemePrefs.settingsLookIndex(this);
    }

    private void applySettingsLook(int i) {
        if (i < 0 || i >= ThemePrefs.SETTINGS_LOOK_LABELS.length) return;
        ThemePrefs.persistSettingsOverlay(this,
            hex6(ThemePrefs.SETTINGS_LOOK_PLATES[i]),
            hex6(ThemePrefs.SETTINGS_LOOK_GLYPHS[i]));
        if (settingsPlateField != null) {
            settingsPlateField.setColor(ThemePrefs.SETTINGS_LOOK_PLATES[i]);
        }
        if (settingsGlyphField != null) {
            settingsGlyphField.setColor(ThemePrefs.SETTINGS_LOOK_GLYPHS[i]);
        }
        refresh();
    }

    private void applyNavFromField() {
        int c = navField != null ? navField.getColor() : ThemePrefs.navTint(this);
        lastApply = "navbar…";
        refresh();
        new Thread(() -> {
            lastApply = ThemePrefs.setNavTint(this, c);
            runOnUiThread(this::refresh);
        }, "nav-apply").start();
    }

    private void applyQsFromField() {
        int c = qsField != null ? qsField.getColor() : ThemePrefs.qsBg(this);
        lastApply = "qs…";
        refresh();
        new Thread(() -> {
            lastApply = ThemePrefs.setQsBg(this, c);
            runOnUiThread(this::refresh);
        }, "qs-apply").start();
    }

    private void applyAccentFromField() {
        int c = accentField != null ? accentField.getColor() : ThemePrefs.accent(this);
        lastApply = "accent…";
        refresh();
        new Thread(() -> {
            lastApply = ThemePrefs.setAccentOverride(this, c);
            runOnUiThread(this::refresh);
        }, "accent-apply").start();
    }

    private void applySettingsFromFields() {
        String plate = settingsPlateField != null
            ? hex6(settingsPlateField.getColor()) : ThemePrefs.settingsPlateHex(this);
        String glyph = settingsGlyphField != null
            ? hex6(settingsGlyphField.getColor()) : ThemePrefs.settingsGlyphHex(this);
        lastApply = "settings…";
        refresh();
        new Thread(() -> {
            lastApply = ThemePrefs.setSettingsOverlay(this, plate, glyph);
            runOnUiThread(this::refresh);
        }, "set-apply").start();
    }

    private void applyIconsFromFields() {
        String plate = plateField != null
            ? hex6(plateField.getColor()) : ThemePrefs.iconPlateHex(this);
        String glyph = glyphField != null
            ? hex6(glyphField.getColor()) : ThemePrefs.iconGlyphHex(this);
        lastApply = "icons…";
        refresh();
        new Thread(() -> {
            lastApply = ThemePrefs.setIconOverlay(this, plate, glyph,
                    ThemePrefs.iconShape(this));
            runOnUiThread(this::refresh);
        }, "icon-apply").start();
    }

    private void refresh() {
        if (status == null) return;
        String apply = lastApply == null || lastApply.isEmpty() ? "?" : lastApply;
        status.setText("accent " + ThemePrefs.accentHex(this)
            + " · nav #" + ThemePrefs.navTintHex(this)
            + " · qs #" + ThemePrefs.qsBgHex(this)
            + (ThemePrefs.isGlass(this) ? " glass" : " solid")
            + " · " + ThemePrefs.dayNight(this)
            + " · settings #" + ThemePrefs.settingsPlateHex(this)
            + "/#" + ThemePrefs.settingsGlyphHex(this)
            + (ThemePrefs.settingsMonoOn(this) ? " mono" : " off")
            + " · apps #" + ThemePrefs.iconPlateHex(this)
            + "/#" + ThemePrefs.iconGlyphHex(this)
            + " · " + ThemePrefs.iconShape(this)
            + " · " + apply);
        status.setTextColor(ThemePrefs.iconGlyph(this));
        if (accentField != null) accentField.setColor(ThemePrefs.accent(this));
        if (navField != null) navField.setColor(ThemePrefs.navTint(this));
        if (qsField != null) qsField.setColor(ThemePrefs.qsBg(this));
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
        ThemeSliders.selectPreset(navTiles, ThemeSliders.indexOf(
            ThemePrefs.ICON_GLYPH_COLORS, ThemePrefs.navTint(this)));
        ThemeSliders.selectPreset(qsTiles, ThemeSliders.indexOf(
            ThemePrefs.ICON_PLATE_COLORS, ThemePrefs.qsBg(this)));
        ThemeSliders.selectPreset(modeTiles, modeIndex());
        ThemeSliders.selectPreset(plateTiles, ThemeSliders.indexOf(
            ThemePrefs.ICON_PLATE_COLORS, ThemePrefs.iconPlate(this)));
        ThemeSliders.selectPreset(glyphTiles, ThemeSliders.indexOf(
            ThemePrefs.ICON_GLYPH_COLORS, ThemePrefs.iconGlyph(this)));
        ThemeSliders.selectPreset(shapeTiles, ThemePrefs.iconShapeIndex(this));
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
            if (!typing && kc >= KeyEvent.KEYCODE_1 && kc <= KeyEvent.KEYCODE_4) {
                int i = kc - KeyEvent.KEYCODE_1;
                if (i < ThemePrefs.ICON_SHAPE_IDS.length) {
                    ThemePrefs.persistIconShape(this, ThemePrefs.ICON_SHAPE_IDS[i]);
                    refresh();
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
