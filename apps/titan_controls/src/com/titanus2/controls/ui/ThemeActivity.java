package com.titanus2.controls.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * System accent / day-night plane for cube-ux (OS-wide).
 * Glow/mode apply monochromatic OS seed immediately (no reboot).
 * Labels only — no marketing copy. App chrome is Material/DeviceDefault.
 */
public class ThemeActivity extends Activity {
    private TextView status;
    private String lastApply = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        UiKit.screen(root);

        UiKit.title(root, "Look");
        status = UiKit.mono(root);
        // Re-assert OS plane when opening Look (heal drift after wipe / other apps)
        lastApply = ThemePrefs.applyOsPlane(this);
        if (lastApply == null) lastApply = "";
        refreshStatus();

        UiKit.section(root, "Glow");
        LinearLayout row = UiKit.row(root);
        for (int i = 0; i < ThemePrefs.PRESET_LABELS.length; i++) {
            final int color = ThemePrefs.PRESET_COLORS[i];
            UiKit.flexButton(row, ThemePrefs.PRESET_LABELS[i], () -> {
                ThemePrefs.setAccent(this, color);
                lastApply = "os";
                refreshStatus();
            });
        }

        UiKit.section(root, "Mode");
        LinearLayout modes = UiKit.row(root);
        UiKit.flexButton(modes, "Night", () -> {
            ThemePrefs.setDayNight(this, ThemePrefs.MODE_NIGHT);
            lastApply = "os";
            refreshStatus();
            recreate();
        });
        UiKit.flexButton(modes, "Day", () -> {
            ThemePrefs.setDayNight(this, ThemePrefs.MODE_DAY);
            lastApply = "os";
            refreshStatus();
            recreate();
        });
        UiKit.flexButton(modes, "Auto", () -> {
            ThemePrefs.setDayNight(this, ThemePrefs.MODE_AUTO);
            lastApply = "os";
            refreshStatus();
        });

        TextView note = UiKit.mono(root);
        note.setText("1–5 glow · N night · D day · applies OS now");

        scroll.addView(root);
        setContentView(scroll);
    }

    private void refreshStatus() {
        if (status == null) return;
        String apply = lastApply == null || lastApply.isEmpty() ? "?" : lastApply;
        status.setText("glow " + ThemePrefs.accentHex(this)
            + " · " + ThemePrefs.dayNight(this)
            + " · " + apply);
        status.setTextColor(ThemePrefs.accent(this));
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
            if (kc >= KeyEvent.KEYCODE_1 && kc <= KeyEvent.KEYCODE_5) {
                int i = kc - KeyEvent.KEYCODE_1;
                if (i < ThemePrefs.PRESET_COLORS.length) {
                    ThemePrefs.setAccent(this, ThemePrefs.PRESET_COLORS[i]);
                    refreshStatus();
                    return true;
                }
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
        }
        return super.dispatchKeyEvent(event);
    }
}
