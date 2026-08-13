package com.titanus2.cubecontact;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Device access mode picker.
 * 1.54 residual: default theme left soft-IME path open after 1.53 front-only seal.
 * 1.55: re-assert live wallpaper dim on open (Settings-only left 0.92 residual).
 */
public class PrivilegeActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 1.54: HW keyboard only + opaque (no soft LatinIME gray block).
        getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        try {
            getWindow().setFormat(android.graphics.PixelFormat.OPAQUE);
            getWindow().setBackgroundDrawableResource(android.R.color.black);
        } catch (Exception ignored) {}
        try { CubeSurfacePrefs.apply(this); } catch (Exception ignored) {}
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(40, 40, 40, 40);
        TextView t = new TextView(this);
        t.setTextColor(Color.rgb(200, 180, 180));
        t.setText("Device access\n\n"
            + "Install: " + RomIntegration.installLabel(this) + "\n"
            + "Active: " + CubePalette.mode(this).label() + "\n\n"
            + "• Accessibility — standard install, limited automation\n"
            + "• Shizuku / Root — elevated shell through on-device agent\n"
            + "• System — Hybrid ROM priv-app / Magisk (full plane)\n");
        root.addView(t);
        if (RomIntegration.isRomBundled(this)) {
            TextView tip = new TextView(this);
            tip.setTextColor(Color.rgb(180, 60, 70));
            tip.setText("\nROM build detected — System mode recommended.\n");
            root.addView(tip);
            CubePalette.setMode(this, PrivilegeMode.SYSTEM);
        }
        root.addView(btn("Accessibility", () -> {
            CubePalette.setMode(this, PrivilegeMode.UNPRIVILEGED_A11Y);
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }));
        root.addView(btn("Shizuku / Root", () -> {
            CubePalette.setMode(this, PrivilegeMode.SHIZUKU_OR_ROOT);
            Toast.makeText(this, "Uses agent shell when available", Toast.LENGTH_SHORT).show();
            finish();
        }));
        root.addView(btn("System", () -> {
            CubePalette.setMode(this, PrivilegeMode.SYSTEM);
            Toast.makeText(this, RomIntegration.isRomBundled(this)
                ? "System privilege active" : "Install Magisk module or ROM priv-app",
                Toast.LENGTH_LONG).show();
            finish();
        }));
        root.addView(btn("Back", this::finish));
        setContentView(root);
    }
    private Button btn(String s, Runnable r) {
        Button b = new Button(this);
        b.setText(s);
        b.setOnClickListener(v -> r.run());
        return b;
    }
}
