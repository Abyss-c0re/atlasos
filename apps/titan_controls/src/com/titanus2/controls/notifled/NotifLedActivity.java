package com.titanus2.controls.notifled;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.titanus2.controls.AgentBridge;
import com.titanus2.controls.ui.UiKit;

/**
 * Keyboard LED for notifications — solid / blink / breathe.
 * <p>
 * Keyboard-first (no modifiers): E toggle · S Solid · B Blink · R Breathe ·
 * −/= rate · [/] duty · ,/. level · P Preview · A access · Esc finish.
 * Rate/duty/level use square Cube steps (no Material SeekBar).
 */
public class NotifLedActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView status;
    private UiKit.Toggle enToggle;
    private TextView bSolid, bBlink, bBreathe;
    private UiKit.Step stepHz, stepDuty, stepBright;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            h.postDelayed(this, 400);
        }
    };

    private static final float HZ_MIN = 0.5f;
    private static final float HZ_STEP = 0.25f;
    private static final int HZ_STEPS = 18;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        UiKit.screen(root);
        sc.addView(root);

        UiKit.title(root, "Notification LED");
        UiKit.section(root, "Screen off only");

        enToggle = UiKit.toggle(root, "Notification LED",
            NotifLedPrefs.isEnabled(this), on -> {
                NotifLedPrefs.setEnabled(this, on);
                NotifLedController.publishConfig(this);
                if (on && !hasAccess()) openAccess();
                refresh();
            });
        UiKit.button(root, "Notification access", this::openAccess);

        UiKit.section(root, "Mode");
        LinearLayout st = UiKit.row(root);
        bSolid = UiKit.flexButton(st, "Solid", () -> style("solid"));
        bBlink = UiKit.flexButton(st, "Blink", () -> style("blink"));
        bBreathe = UiKit.flexButton(st, "Breathe", () -> style("breathe"));

        UiKit.section(root, "Rate");
        int hzProg = hzToProgress(1000f / Math.max(200, NotifLedPrefs.getPeriodMs(this)));
        stepHz = UiKit.step(root, "Hz", 0, HZ_STEPS, hzProg, p -> {
            float hz = progressToHz(p);
            int period = Math.round(1000f / hz);
            if (period < 200) period = 200;
            if (period > 5000) period = 5000;
            NotifLedPrefs.setPeriodMs(this, period);
            float duty = dutyFromPrefs();
            NotifLedPrefs.setOnMs(this, Math.round(period * duty));
            NotifLedController.publishConfig(this);
            labels();
        });

        UiKit.section(root, "Duty");
        stepDuty = UiKit.step(root, "Duty", 0, 100, Math.round(dutyFromPrefs() * 100), p -> {
            int period = NotifLedPrefs.getPeriodMs(this);
            NotifLedPrefs.setOnMs(this, Math.round(period * (p / 100f)));
            NotifLedController.publishConfig(this);
            labels();
        });

        UiKit.section(root, "Level");
        stepBright = UiKit.step(root, "Level", 0, 6, NotifLedPrefs.getBrightness(this) - 1, p -> {
            NotifLedPrefs.setBrightness(this, p + 1);
            NotifLedController.publishConfig(this);
            labels();
        });

        LinearLayout prev = UiKit.row(root);
        UiKit.flexButton(prev, "Preview 8s", () -> {
            NotifLedController.preview(this, 8);
            refresh();
        });

        status = UiKit.mono(root);
        TextView kbHint = UiKit.mono(root);
        kbHint.setText("E · S/B/R mode · −/= rate · [/] duty · ,/. level · P · A · Esc");

        setContentView(sc);
        NotifLedController.publishConfig(this);
        labels();
        refresh();
        paintModeTiles();
        if (bSolid != null) {
            bSolid.post(() -> {
                try { bSolid.requestFocus(); } catch (Exception ignored) {}
            });
        }
    }

    /**
     * TitanKey shortcuts — single owner, no modifiers.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                && !event.isAltPressed() && !event.isCtrlPressed()
                && !event.isMetaPressed() && !event.isShiftPressed()) {
            int kc = event.getKeyCode();
            // Allow key-repeat on step keys for fast dial-in.
            boolean stepKey = kc == KeyEvent.KEYCODE_MINUS
                || kc == KeyEvent.KEYCODE_EQUALS
                || kc == KeyEvent.KEYCODE_PLUS
                || kc == KeyEvent.KEYCODE_LEFT_BRACKET
                || kc == KeyEvent.KEYCODE_RIGHT_BRACKET
                || kc == KeyEvent.KEYCODE_COMMA
                || kc == KeyEvent.KEYCODE_PERIOD;
            if (event.getRepeatCount() != 0 && !stepKey) {
                return super.dispatchKeyEvent(event);
            }
            switch (kc) {
                case KeyEvent.KEYCODE_ESCAPE:
                    if (event.getRepeatCount() == 0) {
                        finish();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_E:
                    if (event.getRepeatCount() == 0) {
                        toggleEnabled();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_S:
                    if (event.getRepeatCount() == 0) {
                        style("solid");
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_B:
                    if (event.getRepeatCount() == 0) {
                        style("blink");
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_R:
                    if (event.getRepeatCount() == 0) {
                        style("breathe");
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_P:
                    if (event.getRepeatCount() == 0) {
                        NotifLedController.preview(this, 8);
                        refresh();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_A:
                    if (event.getRepeatCount() == 0) {
                        openAccess();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_MINUS:
                    if (stepHz != null) {
                        stepHz.stepBy(-1);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_EQUALS:
                case KeyEvent.KEYCODE_PLUS:
                    if (stepHz != null) {
                        stepHz.stepBy(1);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_LEFT_BRACKET:
                    if (stepDuty != null) {
                        stepDuty.stepBy(-5);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_RIGHT_BRACKET:
                    if (stepDuty != null) {
                        stepDuty.stepBy(5);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_COMMA:
                    if (stepBright != null) {
                        stepBright.stepBy(-1);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_PERIOD:
                    if (stepBright != null) {
                        stepBright.stepBy(1);
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void toggleEnabled() {
        boolean next = !NotifLedPrefs.isEnabled(this);
        NotifLedPrefs.setEnabled(this, next);
        NotifLedController.publishConfig(this);
        if (enToggle != null) enToggle.setChecked(next);
        if (next && !hasAccess()) openAccess();
        refresh();
    }

    private void paintModeTiles() {
        String mode = NotifLedPrefs.getMode(this);
        if (mode == null) mode = "solid";
        UiKit.setSelected(bSolid, "solid".equalsIgnoreCase(mode));
        UiKit.setSelected(bBlink, "blink".equalsIgnoreCase(mode));
        UiKit.setSelected(bBreathe, "breathe".equalsIgnoreCase(mode));
    }

    @Override protected void onResume() {
        super.onResume();
        if (enToggle != null) enToggle.setChecked(NotifLedPrefs.isEnabled(this));
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override protected void onPause() {
        h.removeCallbacks(tick);
        super.onPause();
    }

    private void style(String name) {
        NotifLedPrefs.applyPreset(this, name);
        if (stepHz != null) {
            stepHz.setValue(hzToProgress(1000f / NotifLedPrefs.getPeriodMs(this)));
        }
        if (stepDuty != null) {
            stepDuty.setValue(Math.round(dutyFromPrefs() * 100));
        }
        if (stepBright != null) {
            stepBright.setValue(NotifLedPrefs.getBrightness(this) - 1);
        }
        NotifLedController.publishConfig(this);
        NotifLedController.preview(this, 8);
        labels();
        paintModeTiles();
    }

    private float dutyFromPrefs() {
        int p = NotifLedPrefs.getPeriodMs(this);
        int on = NotifLedPrefs.getOnMs(this);
        if (p <= 0) return 0.5f;
        if (on <= 0) return 0f;
        return Math.min(1f, on / (float) p);
    }

    private static float progressToHz(int p) {
        return HZ_MIN + p * HZ_STEP;
    }

    private static int hzToProgress(float hz) {
        int p = Math.round((hz - HZ_MIN) / HZ_STEP);
        if (p < 0) p = 0;
        if (p > HZ_STEPS) p = HZ_STEPS;
        return p;
    }

    private void labels() {
        int period = NotifLedPrefs.getPeriodMs(this);
        int on = NotifLedPrefs.getOnMs(this);
        float hz = 1000f / period;
        if (stepHz != null) {
            stepHz.setDisplay(String.format("%.2f Hz · %d ms", hz, period));
        }
        if (stepDuty != null) {
            if (on <= 0) {
                stepDuty.setDisplay("0% · solid");
            } else {
                stepDuty.setDisplay(String.format("%d%% · %d/%d ms",
                    Math.round(100f * on / period), on, period));
            }
        }
        if (stepBright != null) {
            stepBright.setDisplay(NotifLedPrefs.getBrightness(this) + " / 7");
        }
    }

    private void refresh() {
        labels();
        String led = AgentBridge.readStatus(AgentBridge.STATUS_LED);
        StringBuilder sb = new StringBuilder();
        sb.append(NotifLedPrefs.getMode(this))
            .append(NotifLedPrefs.isEnabled(this) ? " · on" : " · off")
            .append(hasAccess() ? "" : " · need access").append('\n');
        sb.append("period=").append(AgentBridge.get(this, AgentBridge.NOTIF_PERIOD_MS, "?"))
            .append(" on=").append(AgentBridge.get(this, AgentBridge.NOTIF_ON_MS, "?"))
            .append(" mode=").append(AgentBridge.get(this, AgentBridge.NOTIF_MODE, "?"));
        if (led != null) sb.append('\n').append(led.trim());
        if (status != null) status.setText(sb.toString());
    }

    private void openAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            UiKit.toast(this, "Settings → notification access");
        }
    }

    private boolean hasAccess() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (flat == null || flat.isEmpty()) return false;
        ComponentName me = new ComponentName(this, NotifLedService.class);
        TextUtils.SimpleStringSplitter sp = new TextUtils.SimpleStringSplitter(':');
        sp.setString(flat);
        while (sp.hasNext()) {
            ComponentName cn = ComponentName.unflattenFromString(sp.next());
            if (me.equals(cn)) return true;
        }
        return false;
    }
}
