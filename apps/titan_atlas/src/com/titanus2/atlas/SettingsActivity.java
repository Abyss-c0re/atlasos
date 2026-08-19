package com.titanus2.atlas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.titanus2.atlas.ui.UiKit;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Atlas settings — match OS / Controls Settings (PRODUCT_UX).
 */
public class SettingsActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService io = Executors.newSingleThreadExecutor();

    private TextView fontLab;
    private TextView[] themeTiles;
    private LinearLayout usersNav;
    private LinearLayout fgNav;
    private LinearLayout bgNav;
    private LinearLayout curNav;
    private TextView hybridStatus;
    private TextView debianUserStatus;
    private TextView sizeLab;
    private LinearLayout backupsNav;
    private TextView ttlLab;
    private LinearLayout authLogNav;
    private LinearLayout authBinsNav;

    private static final String[] THEMES = {
        "dark", "light", "green", "amber", "cyan"
    };
    private static final String[] THEME_LABELS = {
        "Dark", "Light", "Green", "Amber", "Cyan"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.applyOpaqueWindow(this);

        ScrollView scroll = new ScrollView(this);
        UiKit.prepareScroll(scroll);
        LinearLayout root = new LinearLayout(this);
        UiKit.screen(root);
        scroll.addView(root, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT));

        UiKit.section(root, "Appearance");
        fontLab = UiKit.sliderLabel(root, AtlasPrefs.fontSp(this) + " sp");
        UiKit.slider(root, 24, AtlasPrefs.fontSp(this) - 8,
            new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                    int sp = p + 8;
                    AtlasPrefs.setFontSp(SettingsActivity.this, sp);
                    fontLab.setText(sp + " sp");
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });

        LinearLayout themeRow = UiKit.row(root);
        themeTiles = new TextView[THEMES.length];
        for (int i = 0; i < THEMES.length; i++) {
            final String key = THEMES[i];
            themeTiles[i] = UiKit.flexButton(themeRow, THEME_LABELS[i], () -> {
                AtlasPrefs.applyThemePreset(this, key);
                paintThemeTiles();
                refreshColorNav();
            });
        }
        paintThemeTiles();

        fgNav = UiKit.navRow(root, "Text", AtlasPrefs.colorHex(AtlasPrefs.fgColor(this)),
            () -> openColorPicker("Text", AtlasPrefs.fgColor(this), c -> {
                AtlasPrefs.setFgColor(this, c);
                refreshColorNav();
            }));
        bgNav = UiKit.navRow(root, "Background", AtlasPrefs.colorHex(AtlasPrefs.bgColor(this)),
            () -> openColorPicker("Background", AtlasPrefs.bgColor(this), c -> {
                AtlasPrefs.setBgColor(this, c);
                refreshColorNav();
            }));
        curNav = UiKit.navRow(root, "Cursor", AtlasPrefs.colorHex(AtlasPrefs.cursorColor(this)),
            () -> openColorPicker("Cursor", AtlasPrefs.cursorColor(this), c -> {
                AtlasPrefs.setCursorColor(this, c);
                refreshColorNav();
            }));
        UiKit.toggle(root, "Keep screen on", AtlasPrefs.keepScreenOn(this),
            on -> AtlasPrefs.setKeepScreenOn(this, on));

        // ——— Privileges (what is allowed) ———
        // Primary model: manage access. Bio is optional enforcement below.
        UiKit.section(root, "Privileges");
        AtlasPrefs.publishPrivilegePlane(this);
        AtlasPrefs.publishBioPlane(this);
        UiKit.note(root,
            "observe getprop dumpsys logcat flow · capture asks Atlas");
        // Pref only: default *new* shells to Debian. Does not mount/unmount the LP.
        // Per-session And/Deb is the top bar. Off does not stop Debian.
        UiKit.toggle(root, "Default new shell: Debian", AtlasPrefs.privilegedHybrid(this), on -> {
            if (on) {
                runIo(() -> {
                    boolean ok = true;
                    if (AtlasPrefs.biometricAuth(SettingsActivity.this)) {
                        ok = AtlasAuth.requestBlocking(
                            SettingsActivity.this, "Default Debian shell", 90);
                    }
                    if (!ok) {
                        main.post(() -> {
                            toast("Denied");
                            recreate();
                        });
                        return;
                    }
                    String prep = HybridEnsure.debianLpLive()
                        ? null : ensureHybridReady();
                    boolean ready = NativeBin.hybridRootfsReady()
                        || HybridEnsure.debianLpLive();
                    if (ready && prep == null) {
                        HybridEnsure.createLiveUid(SettingsActivity.this);
                    }
                    final boolean okReady = ready && prep == null;
                    main.post(() -> {
                        AtlasPrefs.setPrivilegedHybrid(this, okReady);
                        toast(okReady
                            ? "new shells open Debian"
                            : (prep != null ? prep : "Debian not ready"));
                        if (!okReady) recreate();
                    });
                });
            } else {
                AtlasPrefs.setPrivilegedHybrid(this, false);
                toast("new shells open Android");
            }
        });
        usersNav = UiKit.navRow(root, "Users", HybridEnsure.listDebianUsers(),
            () -> startActivity(new Intent(this, UsersActivity.class)));

        UiKit.section(root, "Backups");
        backupsNav = UiKit.navRow(root, "Saved", "…",
            () -> startActivity(new Intent(this, BackupsActivity.class)));
        debianUserStatus = null;
        UiKit.toggle(root, "Android access",
            AtlasPrefs.privAndroidAccess(this),
            on -> {
                AtlasPrefs.setPrivAndroidAccess(this, on);
                toast(on ? "screencap/am/pm on" : "Android access off");
            });
        UiKit.toggle(root, "Debian sudo",
            AtlasPrefs.privDebianSudo(this),
            on -> {
                AtlasPrefs.setPrivDebianSudo(this, on);
                toast(on ? "Deb elevate on" : "Deb sudo off");
            });

        UiKit.section(root, "Biometric");
        UiKit.toggle(root, "Require biometrics",
            AtlasPrefs.biometricAuth(this),
            on -> {
                AtlasPrefs.setBiometricAuth(this, on);
                if (!on) AtlasAuth.clearTicket(this);
                toast(on ? "Bio on" : "Bio off");
            });

        UiKit.section(root, "Auth");
        AtlasPrefs.publishAuthPolicy(this);
        boolean strict = AtlasPrefs.authStrict(this);
        UiKit.toggle(root, "Strict (every call)", strict, on -> {
            AtlasPrefs.setAuthStrict(this, on);
            if (on) AtlasAuth.clearTicket(this);
            toast(on ? "auth every call" : "tickets per command");
            recreate();
        });
        if (strict) {
            UiKit.note(root, "expiration off · finger for each binary");
        } else {
            int ttl = AtlasPrefs.ticketTtlSec(this);
            ttlLab = UiKit.sliderLabel(root, "Ticket " + AtlasPrefs.ticketTtlLabel(ttl));
            UiKit.slider(root, AtlasPrefs.TICKET_TTL_CHOICES.length - 1,
                AtlasPrefs.ticketTtlChoiceIndex(ttl),
                new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                        if (!u) return;
                        int sec = AtlasPrefs.TICKET_TTL_CHOICES[
                            Math.max(0, Math.min(AtlasPrefs.TICKET_TTL_CHOICES.length - 1, p))];
                        AtlasPrefs.setTicketTtlSec(SettingsActivity.this, sec);
                        if (ttlLab != null) {
                            ttlLab.setText("Ticket " + AtlasPrefs.ticketTtlLabel(sec));
                        }
                    }
                    @Override public void onStartTrackingTouch(SeekBar s) {}
                    @Override public void onStopTrackingTouch(SeekBar s) {}
                });
            UiKit.note(root, "per command · not a blanket grant");
        }
        UiKit.navRow(root, "Access", "allow · ask · deny",
            () -> startActivity(new Intent(this, AuthAccessActivity.class)));
        authBinsNav = UiKit.navRow(root, "Managed binaries",
            AtlasPrefs.managedBinCount(this) + " added",
            () -> startActivity(new Intent(this, AuthBinsActivity.class)));
        authLogNav = UiKit.navRow(root, "Request log", "…",
            () -> startActivity(new Intent(this, AuthLogActivity.class)));

        UiKit.section(root, "Debian image");
        hybridStatus = UiKit.mono(root);
        hybridStatus.setText("…");
        refreshHybridStatus();
        boolean lp = HybridEnsure.debianLpLive();
        if (lp) {
            UiKit.note(root,
                "Wipe — user files in Debian home.\n"
                    + "Reinstall — seed image + empty home.\n"
                    + "Size is the LP partition.");
        }
        sizeLab = UiKit.sliderLabel(root, sizeTargetLabel());
        if (!lp) {
            UiKit.slider(root, 15, (AtlasPrefs.hybridSizeG(this) - 2) / 2,
                new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                        AtlasPrefs.setHybridSizeG(SettingsActivity.this, 2 + p * 2);
                        sizeLab.setText(sizeTargetLabel());
                    }
                    @Override public void onStartTrackingTouch(SeekBar s) {}
                    @Override public void onStopTrackingTouch(SeekBar s) {}
                });
        }
        LinearLayout diskRow = UiKit.row(root);
        if (!lp) {
            UiKit.flexButton(diskRow, "Apply size", this::doApplySize);
        }
        UiKit.flexButton(diskRow, "Remount", this::doRemount);
        LinearLayout diskRow2 = UiKit.row(root);
        UiKit.flexButton(diskRow2, "Reinstall", this::confirmRebuild);
        UiKit.flexButton(diskRow2, "Wipe", this::confirmWipe);
        UiKit.button(root, "Fix home ownership", this::healHome);

        UiKit.section(root, "Agent");
        UiKit.toggle(root, "Keep-alive notification", AtlasPrefs.keepAlive(this), on -> {
            AtlasPrefs.setKeepAlive(this, on);
            if (on) {
                AtlasSessionService.startOrUpdate(this, AtlasPrefs.liveSessionCount(this), "settings");
            } else if (AtlasPrefs.authAgentAlways(this)) {
                AtlasSessionService.ensureAuthAgent(this);
            } else {
                AtlasSessionService.stop(this);
            }
        });

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHybridStatus();
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
        bigGd.setCornerRadius(0f);
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

    private void paintThemeTiles() {
        if (themeTiles == null) return;
        String cur = AtlasPrefs.themePreset(this);
        for (int i = 0; i < THEMES.length && i < themeTiles.length; i++) {
            UiKit.setSelected(themeTiles[i], THEMES[i].equals(cur));
        }
    }

    private void refreshColorNav() {
        if (fgNav != null) {
            UiKit.setNavSummary(fgNav, AtlasPrefs.colorHex(AtlasPrefs.fgColor(this)));
        }
        if (bgNav != null) {
            UiKit.setNavSummary(bgNav, AtlasPrefs.colorHex(AtlasPrefs.bgColor(this)));
        }
        if (curNav != null) {
            UiKit.setNavSummary(curNav, AtlasPrefs.colorHex(AtlasPrefs.cursorColor(this)));
        }
    }

    private String sizeTargetLabel() {
        if (HybridEnsure.debianLpLive()) {
            return "LP atlas_linux";
        }
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
        boolean lp = HybridEnsure.debianLpLive();
        if (lp) {
            new AlertDialog.Builder(this)
                .setTitle("Reinstall Debian?")
                .setMessage("Puts the Atlas seed back and empties home.")
                .setPositiveButton("Reinstall", (d, w) -> doRebuild(false))
                .setNegativeButton("Cancel", null)
                .show();
            return;
        }
        final boolean[] preserve = { true };
        int want = AtlasPrefs.hybridSizeG(this);
        int act = HybridEnsure.actualImageSizeG();
        new AlertDialog.Builder(this)
            .setTitle("Reinstall")
            .setMultiChoiceItems(
                new CharSequence[] { "Keep user files" },
                new boolean[] { true },
                (d, which, checked) -> preserve[0] = checked)
            .setMessage(
                "Keep files ON: remount only · size stays " + act + "G\n"
                    + "Keep files OFF: new image at " + want + "G")
            .setPositiveButton("Reinstall", (d, w) -> doRebuild(preserve[0]))
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
            String err;
            if (!preserve && HybridEnsure.debianLpLive()) {
                CeWipe.resetLinuxHome(SettingsActivity.this);
                err = HybridEnsure.ensureBlocking(SettingsActivity.this);
            } else {
                err = HybridEnsure.rebuildBlocking(SettingsActivity.this, preserve);
            }
            final boolean ready = NativeBin.hybridRootfsReady()
                || HybridEnsure.debianLpLive();
            main.post(() -> {
                if (err == null && ready) {
                    toast(preserve ? "remounted" : "reinstalled");
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
        boolean lp = HybridEnsure.debianLpLive();
        new AlertDialog.Builder(this)
            .setTitle(lp ? "Wipe user files?" : "Wipe image?")
            .setMessage(lp
                ? "Deletes user files in Debian home. The system image stays."
                : ("Deletes the disk image and user files · new image at "
                    + AtlasPrefs.hybridSizeG(this) + "G"))
            .setPositiveButton("Wipe", (d, w) -> doWipe())
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
            String err;
            if (HybridEnsure.debianLpLive()) {
                CeWipe.resetLinuxHome(SettingsActivity.this);
                err = null;
            } else {
                err = HybridEnsure.rebuildBlocking(SettingsActivity.this, false);
            }
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
            boolean lpLive = HybridEnsure.debianLpLive();
            boolean up = NativeBin.hybridRootfsReady();
            String sizeLine = lpLive
                ? "storage=lp  atlas_linux_a  plane=" + (up ? "UP" : "DOWN")
                : ("disk=" + HybridEnsure.actualImageSizeG() + "G  plane="
                    + (up ? "UP" : "DOWN"));
            String st = shellSu(
                "atlas-hybrid.sh status 2>/dev/null | egrep 'bootstrapped|loop_mounted|overlay|img_size|lp_|linux_home|storage_model|storage=' | head -8");
            String lp = shell(
                "atlas-lpctl status 2>/dev/null | egrep 'present|mounted|home=' | head -4");
            String text = sizeLine
                + (st != null && !st.trim().isEmpty() ? "\n" + st.trim() : "")
                + (lp != null && !lp.trim().isEmpty() ? "\n" + lp.trim() : "");
            final String user = HybridEnsure.listDebianUsers();
            final String backs = HybridEnsure.backupSummary(SettingsActivity.this);
            final int logN = AtlasAuth.readLogTail(SettingsActivity.this, 500).size();
            final int bins = AtlasPrefs.managedBinCount(SettingsActivity.this);
            main.post(() -> {
                if (hybridStatus != null) hybridStatus.setText(text);
                if (sizeLab != null) sizeLab.setText(sizeTargetLabel());
                if (usersNav != null) UiKit.setNavSummary(usersNav, user);
                if (backupsNav != null) UiKit.setNavSummary(backupsNav, backs);
                if (authBinsNav != null) {
                    UiKit.setNavSummary(authBinsNav, bins + " added");
                }
                if (authLogNav != null) {
                    UiKit.setNavSummary(authLogNav, logN == 0 ? "empty" : logN + " events");
                }
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
