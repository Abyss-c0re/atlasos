package com.titanus2.atlas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Atlas settings — DeviceDefault, short labels, mono facts.
 * PRODUCT_UX: no marketing essays; hybrid size honesty (grow vs wipe-only shrink).
 */
public class SettingsActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService io = Executors.newSingleThreadExecutor();

    private TextView preview;
    private TextView fontValue;
    private TextView themeValue;
    private TextView hybridStatus;
    private TextView sizeLab;
    private View fgSwatch;
    private View bgSwatch;
    private View cursorSwatch;

    private static final String[] THEMES = {
        "dark", "light", "green", "amber", "cyan"
    };
    private static final String[] THEME_LABELS = {
        "Dark", "Light", "Green", "Amber", "Cyan"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(android.R.style.Theme_DeviceDefault_Settings);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.addView(root);

        TextView title = AtlasUi.body(this, "Atlas");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, AtlasUi.match());
        TextView ver = AtlasUi.monoFact(this, MainActivity.VERSION);
        ver.setPadding(0, 0, 0, dp(8));
        root.addView(ver, AtlasUi.match());

        // ——— Appearance ———
        AtlasUi.section(root, "Appearance");

        FrameLayout previewCard = new FrameLayout(this);
        previewCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(8));
        cardBg.setColor(AtlasPrefs.bgColor(this));
        previewCard.setBackground(cardBg);
        preview = new TextView(this);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, AtlasPrefs.fontSp(this));
        preview.setLineSpacing(0, 1.15f);
        preview.setText("atlas$ \nAaBbCc 012345");
        previewCard.addView(preview, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams cardLp = AtlasUi.match();
        cardLp.bottomMargin = dp(8);
        root.addView(previewCard, cardLp);

        fontValue = AtlasUi.prefRow(root, "Font size", AtlasPrefs.fontSp(this) + " sp");
        SeekBar fontSeek = new SeekBar(this);
        fontSeek.setMax(24);
        fontSeek.setProgress(AtlasPrefs.fontSp(this) - 8);
        fontSeek.setPadding(dp(4), dp(4), dp(4), dp(8));
        fontSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int sp = progress + 8;
                AtlasPrefs.setFontSp(SettingsActivity.this, sp);
                fontValue.setText(sp + " sp");
                refreshPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(fontSeek, AtlasUi.match());

        themeValue = AtlasUi.prefRow(root, "Theme", themeLabel(AtlasPrefs.themePreset(this)));
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, 0, 0, dp(8));
        for (int i = 0; i < THEMES.length; i++) {
            final String key = THEMES[i];
            final String label = THEME_LABELS[i];
            Button chip = themeChip(label, key);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            clp.setMargins(dp(2), 0, dp(2), 0);
            chips.addView(chip, clp);
        }
        root.addView(chips, AtlasUi.match());

        LinearLayout fgRow = colorRow(root, "Text", AtlasPrefs.fgColor(this), c -> {
            AtlasPrefs.setFgColor(this, c);
            if (AtlasPrefs.cursorColor(this) == AtlasPrefs.DEFAULT_CURSOR
                || AtlasPrefs.cursorColor(this) == AtlasPrefs.fgColor(this)) {
                AtlasPrefs.setCursorColor(this, c);
            }
            refreshPreview();
            refreshSwatches();
        });
        fgSwatch = (View) fgRow.getTag();

        LinearLayout bgRow = colorRow(root, "Background", AtlasPrefs.bgColor(this), c -> {
            AtlasPrefs.setBgColor(this, c);
            refreshPreview();
            refreshSwatches();
        });
        bgSwatch = (View) bgRow.getTag();

        LinearLayout curRow = colorRow(root, "Cursor", AtlasPrefs.cursorColor(this), c -> {
            AtlasPrefs.setCursorColor(this, c);
            refreshPreview();
            refreshSwatches();
        });
        cursorSwatch = (View) curRow.getTag();

        AtlasUi.settingsSwitch(root, "Keep screen on", AtlasPrefs.keepScreenOn(this),
            on -> AtlasPrefs.setKeepScreenOn(this, on));

        // ——— Privileges (what is allowed) ———
        // Primary model: manage access. Bio is optional enforcement below.
        AtlasUi.section(root, "Privileges");
        AtlasPrefs.publishPrivilegePlane(this);
        AtlasPrefs.publishBioPlane(this);
        AtlasUi.settingsSwitch(root, "Debian hybrid", AtlasPrefs.privilegedHybrid(this), on -> {
            if (on) {
                runIo(() -> {
                    // Bio only if master enforcement on; else grant privilege path.
                    boolean ok = true;
                    if (AtlasPrefs.biometricAuth(SettingsActivity.this)) {
                        ok = AtlasAuth.requestBlocking(
                            SettingsActivity.this, "Enable Debian hybrid", 90);
                    }
                    if (!ok) {
                        main.post(() -> {
                            toast("Denied");
                            recreate();
                        });
                        return;
                    }
                    main.post(() -> toast("Ensuring…"));
                    String prep = ensureHybridReady();
                    boolean ready = NativeBin.hybridRootfsReady();
                    main.post(() -> {
                        if (prep == null && ready) {
                            AtlasPrefs.setPrivilegedHybrid(this, true);
                            try {
                                NativeBin.writePlaneStatus(this, true, true);
                            } catch (Exception ignored) {
                            }
                            AtlasPrefs.publishPrivilegePlane(this);
                            toast("DEBIAN up");
                            refreshHybridStatus();
                            finish();
                        } else {
                            AtlasPrefs.setPrivilegedHybrid(this, false);
                            try {
                                NativeBin.writePlaneStatus(this, false, ready);
                            } catch (Exception ignored) {
                            }
                            toast(prep != null ? prep : "hybrid↓");
                            recreate();
                        }
                    });
                });
            } else {
                AtlasPrefs.setPrivilegedHybrid(this, false);
                try {
                    NativeBin.writePlaneStatus(this, false, NativeBin.hybridRootfsReady());
                } catch (Exception ignored) {
                }
                toast("Android plane");
                finish();
            }
        });
        AtlasUi.settingsSwitch(root, "Android access (Deb→host)",
            AtlasPrefs.privAndroidAccess(this),
            on -> {
                AtlasPrefs.setPrivAndroidAccess(this, on);
                toast(on ? "screencap/am/pm allowed" : "Android access off");
            });
        AtlasUi.settingsSwitch(root, "Debian sudo",
            AtlasPrefs.privDebianSudo(this),
            on -> {
                AtlasPrefs.setPrivDebianSudo(this, on);
                toast(on ? "Deb elevate allowed" : "Deb sudo off");
            });
        AtlasUi.settingsSwitch(root, "Android su",
            AtlasPrefs.privAndroidSu(this),
            on -> {
                AtlasPrefs.setPrivAndroidSu(this, on);
                toast(on ? "host elevate allowed" : "Android su off");
            });
        TextView privFact = AtlasUi.monoFact(this,
            "Privileges = what Deb/Android may do\n"
                + "Android access: atlas-screencap / am / pm\n"
                + "apt: never gated\n"
                + "Bio below is optional enforcement only");
        privFact.setPadding(0, 0, 0, dp(8));
        root.addView(privFact, AtlasUi.match());

        // ——— Biometric enforcement (optional) ———
        AtlasUi.section(root, "Biometric (optional)");
        AtlasUi.settingsSwitch(root, "Require biometrics",
            AtlasPrefs.biometricAuth(this),
            on -> {
                AtlasPrefs.setBiometricAuth(this, on);
                if (!on) AtlasAuth.clearTicket(this);
                toast(on ? "Bio enforcement ON" : "Bio off — privilege-only");
                recreate();
            });
        if (AtlasPrefs.biometricAuth(this)) {
            AtlasUi.settingsSwitch(root, "Bio · Android access",
                AtlasPrefs.bioAndroidAccessPref(this),
                on -> {
                    AtlasPrefs.setBioAndroidAccess(this, on);
                    toast(on ? "Deb→Android: bio ON" : "Deb→Android: bio off");
                });
            AtlasUi.settingsSwitch(root, "Bio · Debian sudo",
                AtlasPrefs.bioDebianSudoPref(this),
                on -> {
                    AtlasPrefs.setBioDebianSudo(this, on);
                    toast(on ? "Deb sudo: bio ON" : "Deb sudo: bio off");
                });
            AtlasUi.settingsSwitch(root, "Bio · Android su",
                AtlasPrefs.bioAndroidSuPref(this),
                on -> {
                    AtlasPrefs.setBioAndroidSu(this, on);
                    if (!on) AtlasAuth.clearTicket(this);
                    toast(on ? "Android su: bio ON" : "Android su: bio off");
                });
        }
        TextView bioFact = AtlasUi.monoFact(this,
            "Off by default (agent heal / screencap)\n"
                + "When ON: finger for the paths toggled above\n"
                + "ticket ~90s after grant");
        bioFact.setPadding(0, 0, 0, dp(8));
        root.addView(bioFact, AtlasUi.match());

        // ——— Architecture (product lock) ———
        AtlasUi.section(root, "Architecture");
        TextView archFact = AtlasUi.monoFact(this,
            "Atlas = terminal + privilege plane\n"
                + "Deb root = super atlas_linux (survives wipe)\n"
                + "Auth plane = LP (optional bio)\n"
                + "HOME = /data/local/atlas-home/atlas\n"
                + "screencap: atlas-screencap only (binder heal)");
        archFact.setPadding(0, 0, 0, dp(8));
        root.addView(archFact, AtlasUi.match());

        // ——— Hybrid disk ———
        AtlasUi.section(root, "Hybrid disk");
        hybridStatus = AtlasUi.monoFact(this, "…");
        hybridStatus.setPadding(0, 0, 0, dp(8));
        root.addView(hybridStatus, AtlasUi.match());
        refreshHybridStatus();

        sizeLab = AtlasUi.prefRow(root, "Target size", sizeTargetLabel());
        SeekBar sizeSeek = new SeekBar(this);
        sizeSeek.setMax(15);
        sizeSeek.setProgress((AtlasPrefs.hybridSizeG(this) - 2) / 2);
        sizeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int g = 2 + p * 2;
                AtlasPrefs.setHybridSizeG(SettingsActivity.this, g);
                sizeLab.setText(sizeTargetLabel());
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                int want = AtlasPrefs.hybridSizeG(SettingsActivity.this);
                int act = HybridEnsure.actualImageSizeG();
                if (act > 0 && want != act) {
                    toast(want > act
                        ? "target " + want + "G > disk " + act + "G · Apply size"
                        : "target " + want + "G < disk " + act + "G · Wipe only");
                }
            }
        });
        root.addView(sizeSeek, AtlasUi.match());

        AtlasUi.actionBtn(root, "Apply size (grow)", this::doApplySize);
        AtlasUi.actionBtn(root, "Remount", this::doRemount);
        AtlasUi.actionBtn(root, "Rebuild…", this::confirmRebuild);
        AtlasUi.actionBtn(root, "Wipe + new image…", this::confirmWipe);
        AtlasUi.actionBtn(root, "Fix home ownership", this::healHome);
        TextView hybFact = AtlasUi.monoFact(this,
            "slider = target only · grow=Apply · shrink=Wipe · remount keeps size");
        hybFact.setPadding(0, dp(4), 0, dp(8));
        root.addView(hybFact, AtlasUi.match());

        // ——— Sessions ———
        AtlasUi.section(root, "Sessions");
        AtlasUi.settingsSwitch(root, "Keep-alive notification", AtlasPrefs.keepAlive(this), on -> {
            AtlasPrefs.setKeepAlive(this, on);
            if (on) {
                AtlasSessionService.startOrUpdate(this, AtlasPrefs.liveSessionCount(this), "settings");
            } else if (AtlasPrefs.authAgentAlways(this)) {
                AtlasSessionService.ensureAuthAgent(this);
            } else {
                AtlasSessionService.stop(this);
            }
        });

        // ——— About ———
        AtlasUi.section(root, "About");
        TextView about = AtlasUi.monoFact(this, NativeBin.home(this).getAbsolutePath());
        about.setPadding(0, 0, 0, dp(16));
        root.addView(about, AtlasUi.match());

        setContentView(scroll);
        refreshPreview();
        refreshSwatches();
    }

    private Button themeChip(String label, String key) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setMinHeight(dp(40));
        b.setOnClickListener(v -> {
            AtlasPrefs.applyThemePreset(this, key);
            themeValue.setText(themeLabel(key));
            refreshPreview();
            refreshSwatches();
        });
        int[] colors = themePreviewColors(key);
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(4));
        gd.setColor(colors[1]);
        gd.setStroke(dp(1), colors[0]);
        b.setBackground(gd);
        b.setTextColor(colors[0]);
        return b;
    }

    private static int[] themePreviewColors(String key) {
        switch (key) {
            case "light": return new int[] {0xFF212121, 0xFFFAFAFA};
            case "green": return new int[] {0xFF33FF33, 0xFF0A0A0A};
            case "amber": return new int[] {0xFFFFB000, 0xFF1A1200};
            case "cyan": return new int[] {0xFFB2EBF2, 0xFF0D1B1E};
            default: return new int[] {0xFFFFFFFF, 0xFF000000};
        }
    }

    private String themeLabel(String key) {
        for (int i = 0; i < THEMES.length; i++) {
            if (THEMES[i].equals(key)) return THEME_LABELS[i];
        }
        return key;
    }

    private LinearLayout colorRow(LinearLayout root, String title, int color,
                                  ColorConsumer onPicked) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.setClickable(true);

        TextView left = AtlasUi.body(this, title);
        row.addView(left, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView hex = new TextView(this);
        hex.setText(AtlasPrefs.colorHex(color));
        hex.setTypeface(Typeface.MONOSPACE);
        hex.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hex.setTextColor(AtlasUi.textSecondary(this));
        hex.setPadding(0, 0, dp(10), 0);
        row.addView(hex);

        View swatch = new View(this);
        GradientDrawable sw = new GradientDrawable();
        sw.setShape(GradientDrawable.OVAL);
        sw.setColor(color);
        sw.setStroke(dp(1), 0x44000000);
        swatch.setBackground(sw);
        row.addView(swatch, new LinearLayout.LayoutParams(dp(36), dp(36)));

        row.setTag(swatch);
        row.setOnClickListener(v -> openColorPicker(title, color, picked -> {
            hex.setText(AtlasPrefs.colorHex(picked));
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(picked);
            g.setStroke(dp(1), 0x44000000);
            swatch.setBackground(g);
            onPicked.accept(picked);
        }));
        root.addView(row, AtlasUi.match());
        return row;
    }

    private interface ColorConsumer {
        void accept(int color);
    }

    private void openColorPicker(String title, int initial, ColorConsumer onOk) {
        final float[] hsv = new float[3];
        Color.colorToHSV(initial | 0xFF000000, hsv);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(8));

        View big = new View(this);
        GradientDrawable bigGd = new GradientDrawable();
        bigGd.setCornerRadius(dp(8));
        bigGd.setColor(Color.HSVToColor(hsv));
        big.setBackground(bigGd);
        box.addView(big, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));

        TextView hexLab = new TextView(this);
        hexLab.setTypeface(Typeface.MONOSPACE);
        hexLab.setGravity(Gravity.CENTER);
        hexLab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        hexLab.setPadding(0, dp(10), 0, dp(8));
        hexLab.setText(AtlasPrefs.colorHex(Color.HSVToColor(hsv)));
        box.addView(hexLab, AtlasUi.match());

        final Runnable update = () -> {
            int c = Color.HSVToColor(hsv);
            bigGd.setColor(c);
            big.setBackground(bigGd);
            hexLab.setText(AtlasPrefs.colorHex(c));
        };

        box.addView(labeledSeek(box.getContext(), "Hue", 0, 360, (int) hsv[0], p -> {
            hsv[0] = p;
            update.run();
        }), AtlasUi.match());
        box.addView(labeledSeek(box.getContext(), "Saturation", 0, 100, (int) (hsv[1] * 100), p -> {
            hsv[1] = p / 100f;
            update.run();
        }), AtlasUi.match());
        box.addView(labeledSeek(box.getContext(), "Value", 0, 100, (int) (hsv[2] * 100), p -> {
            hsv[2] = p / 100f;
            update.run();
        }), AtlasUi.match());

        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Apply", (d, w) -> onOk.accept(Color.HSVToColor(hsv) | 0xFF000000))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private interface IntConsumer {
        void accept(int v);
    }

    private LinearLayout labeledSeek(Context c, String label, int min, int max, int value,
                                     IntConsumer onChange) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(4), 0, dp(4));
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(AtlasUi.textSecondary(c));
        col.addView(t, AtlasUi.match());
        SeekBar sb = new SeekBar(c);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, value - min)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                onChange.accept(progress + min);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        col.addView(sb, AtlasUi.match());
        return col;
    }

    private void refreshPreview() {
        if (preview == null) return;
        int fg = AtlasPrefs.fgColor(this);
        int bg = AtlasPrefs.bgColor(this);
        preview.setTextColor(fg);
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, AtlasPrefs.fontSp(this));
        if (preview.getParent() instanceof View) {
            View card = (View) preview.getParent();
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(dp(8));
            gd.setColor(bg);
            card.setBackground(gd);
        }
    }

    private void refreshSwatches() {
        setSwatch(fgSwatch, AtlasPrefs.fgColor(this));
        setSwatch(bgSwatch, AtlasPrefs.bgColor(this));
        setSwatch(cursorSwatch, AtlasPrefs.cursorColor(this));
        if (themeValue != null) {
            themeValue.setText(themeLabel(AtlasPrefs.themePreset(this)));
        }
    }

    private void setSwatch(View swatch, int color) {
        if (swatch == null) return;
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color | 0xFF000000);
        g.setStroke(dp(1), 0x44000000);
        swatch.setBackground(g);
    }

    private String sizeTargetLabel() {
        int want = AtlasPrefs.hybridSizeG(this);
        int act = HybridEnsure.actualImageSizeG();
        if (act <= 0) return want + "G target · no image";
        if (want == act) return want + "G (= disk)";
        if (want > act) return want + "G target · disk " + act + "G · grow";
        return want + "G target · disk " + act + "G · wipe only";
    }

    private void doApplySize() {
        int want = AtlasPrefs.hybridSizeG(this);
        int act = HybridEnsure.actualImageSizeG();
        if (act > 0 && want < act) {
            new AlertDialog.Builder(this)
                .setTitle("Cannot shrink")
                .setMessage("disk " + act + "G · target " + want + "G · use Wipe")
                .setPositiveButton("OK", null)
                .show();
            return;
        }
        if (act > 0 && want == act) {
            toast("already " + act + "G");
            doRemount();
            return;
        }
        toast(act <= 0 ? "create " + want + "G…" : "grow " + act + "G → " + want + "G…");
        runIo(() -> {
            boolean ok = AtlasAuth.requestBlocking(
                SettingsActivity.this, "Resize hybrid " + want + "G", 90);
            if (!ok) {
                main.post(() -> toast("Denied"));
                return;
            }
            String err = HybridEnsure.resizeBlocking(SettingsActivity.this, want);
            final boolean ready = NativeBin.hybridRootfsReady();
            main.post(() -> {
                if (err == null && ready) {
                    toast("disk " + HybridEnsure.actualImageSizeG() + "G");
                    AtlasPrefs.requestSessionRestart(SettingsActivity.this);
                } else {
                    toast(err != null ? err : "overlay down");
                }
                refreshHybridStatus();
            });
        });
    }

    private void doRemount() {
        toast("remount…");
        runIo(() -> {
            String err = HybridEnsure.rebuildBlocking(SettingsActivity.this, true);
            final boolean ready = NativeBin.hybridRootfsReady();
            main.post(() -> {
                if (err == null && ready) {
                    toast("remounted · " + HybridEnsure.actualImageSizeG() + "G");
                    AtlasPrefs.requestSessionRestart(SettingsActivity.this);
                } else {
                    toast(err != null ? err : "overlay down");
                }
                refreshHybridStatus();
            });
        });
    }

    private void confirmRebuild() {
        final boolean[] preserve = { true };
        int want = AtlasPrefs.hybridSizeG(this);
        int act = HybridEnsure.actualImageSizeG();
        new AlertDialog.Builder(this)
            .setTitle("Rebuild")
            .setMultiChoiceItems(
                new CharSequence[] { "Preserve data" },
                new boolean[] { true },
                (d, which, checked) -> preserve[0] = checked)
            .setMessage(
                "Preserve ON: remount only · size stays " + act + "G\n"
                    + "Preserve OFF: wipe → target " + want + "G\n"
                    + "Grow without wipe: Apply size")
            .setPositiveButton("Rebuild", (d, w) -> doRebuild(preserve[0]))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void doRebuild(boolean preserve) {
        toast(preserve ? "rebuild (preserve)…" : "rebuild (wipe)…");
        runIo(() -> {
            boolean ok = AtlasAuth.requestBlocking(
                SettingsActivity.this,
                preserve ? "Rebuild hybrid (keep data)" : "Rebuild hybrid (wipe)",
                90);
            if (!ok) {
                main.post(() -> toast("Denied"));
                return;
            }
            String err = HybridEnsure.rebuildBlocking(SettingsActivity.this, preserve);
            final boolean ready = NativeBin.hybridRootfsReady();
            main.post(() -> {
                if (err == null && ready) {
                    toast(preserve
                        ? "remounted · " + HybridEnsure.actualImageSizeG() + "G"
                        : "fresh · " + HybridEnsure.actualImageSizeG() + "G");
                    AtlasPrefs.setPrivilegedHybrid(SettingsActivity.this, true);
                    AtlasPrefs.requestSessionRestart(SettingsActivity.this);
                } else {
                    toast(err != null ? err : "overlay down");
                }
                refreshHybridStatus();
            });
        });
    }

    private void confirmWipe() {
        int want = AtlasPrefs.hybridSizeG(this);
        new AlertDialog.Builder(this)
            .setTitle("Wipe hybrid?")
            .setMessage("Deletes disk + data · new image at " + want + "G")
            .setPositiveButton("Wipe " + want + "G", (d, w) -> doWipe())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void doWipe() {
        toast("auth…");
        runIo(() -> {
            boolean ok = AtlasAuth.requestBlocking(
                SettingsActivity.this, "Wipe hybrid image", 90);
            if (!ok) {
                main.post(() -> toast("Denied"));
                return;
            }
            String err = HybridEnsure.rebuildBlocking(SettingsActivity.this, false);
            if (err != null) {
                shellSu(
                    "export ATLAS_HYBRID_SIZE_G="
                        + AtlasPrefs.hybridSizeG(SettingsActivity.this) + "; "
                        + "sh /system/bin/atlas-hybrid.sh destroy 2>&1; "
                        + "rm -f /data/local/atlas-hybrid.img; "
                        + "rm -rf /data/local/atlas-hybrid; "
                        + "echo WIPE_DONE");
            }
            main.post(() -> {
                toast(NativeBin.hybridRootfsReady()
                    ? "wiped + bootstrapped"
                    : (err == null ? "wiped" : err));
                refreshHybridStatus();
            });
        });
    }

    private void healHome() {
        toast("bridge heal…");
        runIo(() -> {
            // Product: atlas-enterd HEAL only — no KernelSU, no bio ticket.
            boolean ok = AtlasBridge.healHomeNoKsu(SettingsActivity.this);
            final String o = ok ? "home fixed (enterd)" : "heal failed — is atlas-enterd up?";
            main.post(() -> {
                toast(o);
                refreshHybridStatus();
            });
        });
    }

    private void runIo(Runnable r) {
        if (io == null || io.isShutdown() || io.isTerminated()) {
            io = Executors.newSingleThreadExecutor();
        }
        try {
            io.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            io = Executors.newSingleThreadExecutor();
            try {
                io.execute(r);
            } catch (Exception ignored) {
            }
        }
    }

    private void refreshHybridStatus() {
        runIo(() -> {
            int act = HybridEnsure.actualImageSizeG();
            int want = AtlasPrefs.hybridSizeG(SettingsActivity.this);
            boolean up = NativeBin.hybridRootfsReady();
            String sizeLine = "disk=" + (act > 0 ? act + "G" : "none")
                + "  target=" + want + "G  plane=" + (up ? "UP" : "DOWN");
            if (act > 0 && want > act) sizeLine += "  grow?";
            else if (act > 0 && want < act) sizeLine += "  shrink=wipe";
            String st = shellSu(
                "atlas-hybrid.sh status 2>/dev/null | egrep 'bootstrapped|loop_mounted|overlay|img_size|lp_|linux_home|storage_model' | head -8");
            String lp = shell(
                "atlas-lpctl status 2>/dev/null | egrep 'present|mounted|home=' | head -4");
            String text = sizeLine
                + (st != null && !st.trim().isEmpty() ? "\n" + st.trim() : "")
                + (lp != null && !lp.trim().isEmpty() ? "\n" + lp.trim() : "");
            main.post(() -> {
                if (hybridStatus != null) hybridStatus.setText(text);
                if (sizeLab != null) sizeLab.setText(sizeTargetLabel());
            });
        });
    }

    private String ensureHybridReady() {
        if (NativeBin.hybridRootfsReady()) return null;
        return HybridEnsure.ensureBlocking(this);
    }

    private String shell(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[] {"/system/bin/sh", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : "err";
        }
    }

    private String shellSu(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[] {
                "/system/bin/su", "0", "sh", "-c", cmd
            });
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader e = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            while ((line = e.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
            if (sb.length() > 0) return sb.toString();
        } catch (Exception ignored) {
        }
        return shell(cmd);
    }

    private void toast(String s) {
        AtlasUi.toast(this, s);
    }

    private int dp(int v) {
        return AtlasUi.dp(this, v);
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && io != null && !io.isShutdown()) {
            io.shutdownNow();
        }
        super.onDestroy();
    }
}
