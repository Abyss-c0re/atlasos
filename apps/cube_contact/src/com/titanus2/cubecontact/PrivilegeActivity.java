package com.titanus2.cubecontact;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Access mode — Settings rows, current mode as a fact.
 */
public class PrivilegeActivity extends Activity {
    private TextView state;
    private LinearLayout a11yRow;
    private LinearLayout rootRow;
    private LinearLayout systemRow;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        CubeSettingsUi.applyWindow(this);

        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        CubeSettingsUi.screen(sc, root);

        CubeSettingsUi.title(root, "Access");
        state = CubeSettingsUi.stateLine(root);

        if (RomIntegration.isRomBundled(this)
                && CubePalette.mode(this) != PrivilegeMode.SYSTEM) {
            CubePalette.setMode(this, PrivilegeMode.SYSTEM);
        }

        CubeSettingsUi.section(root, "Mode");
        a11yRow = CubeSettingsUi.choiceRow(root, "Accessibility",
            "Standard install", () -> {
                CubePalette.setMode(this, PrivilegeMode.UNPRIVILEGED_A11Y);
                refresh();
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception ignored) {}
            });
        rootRow = CubeSettingsUi.choiceRow(root, "Root",
            "Shizuku or a root shell", () -> {
                CubePalette.setMode(this, PrivilegeMode.SHIZUKU_OR_ROOT);
                refresh();
                Toast.makeText(this, "Uses a root shell when one is there",
                    Toast.LENGTH_SHORT).show();
            });
        systemRow = CubeSettingsUi.choiceRow(root, "System",
            RomIntegration.isRomBundled(this)
                ? "This ROM already grants it"
                : "Needs a system install", () -> {
                CubePalette.setMode(this, PrivilegeMode.SYSTEM);
                refresh();
                Toast.makeText(this, RomIntegration.isRomBundled(this)
                    ? "System access on"
                    : "Install as a system app first", Toast.LENGTH_SHORT).show();
            });

        setContentView(sc);
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE
                    || event.getKeyCode() == KeyEvent.KEYCODE_BACK)) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void refresh() {
        PrivilegeMode m = CubePalette.mode(this);
        if (state != null) {
            String where = RomIntegration.isRomBundled(this) ? "System app" : "User install";
            state.setText(shortMode(m) + " · " + where);
        }
        CubeSettingsUi.setChosen(a11yRow, m == PrivilegeMode.UNPRIVILEGED_A11Y);
        CubeSettingsUi.setChosen(rootRow, m == PrivilegeMode.SHIZUKU_OR_ROOT);
        CubeSettingsUi.setChosen(systemRow, m == PrivilegeMode.SYSTEM);
    }

    private static String shortMode(PrivilegeMode m) {
        if (m == null) return "Access";
        switch (m) {
            case UNPRIVILEGED_A11Y: return "Accessibility";
            case SHIZUKU_OR_ROOT: return "Root";
            case SYSTEM: return "System";
            default: return m.label();
        }
    }
}
