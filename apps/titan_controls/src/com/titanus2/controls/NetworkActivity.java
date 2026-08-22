package com.titanus2.controls;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.titanus2.controls.network.VpnHotspotHeal;
import com.titanus2.controls.ui.UiKit;

/**
 * Tweaks hub. IMS plane lives here: Settings → Calls is SoT.
 * TrebleApp stays a hidden extra for vendor quirks, not the call heal path.
 */
public class NetworkActivity extends Activity {
    private static final String PREFS_TWEAKS = "titan2_tweaks";
    private static final String KEY_IME_BACKUP = "enabled_imes_backup";
    private static final String KEY_SWITCHER_HIDDEN = "ime_switcher_hidden";

    private final Handler h = new Handler(Looper.getMainLooper());

    private TextView trebleStatus;
    private TextView imsStatus;
    private TextView vpnHotspotStatus;
    private TextView dpiValue;
    private SeekBar dpiBar;
    private boolean dpiDragging;
    private UiKit.Toggle tImeSwitcher;
    private UiKit.Toggle tSoftIme;
    private UiKit.Toggle tVpnHotspot;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        UiKit.screen(root);
        sc.addView(root);

        UiKit.title(root, "Tweaks");

        UiKit.section(root, "Display size");
        dpiValue = new TextView(this);
        dpiValue.setTextSize(22f);
        dpiValue.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        dpiValue.setTextColor(UiKit.textColor(this));
        dpiValue.setPadding(0, 0, 0, UiKit.dp(dpiValue, 4));
        root.addView(dpiValue);
        int live = DisplayDensity.current(this);
        int progress = live - DisplayDensity.MIN;
        if (progress < 0) progress = 0;
        int span = DisplayDensity.MAX - DisplayDensity.MIN;
        if (progress > span) progress = span;
        dpiBar = UiKit.slider(root, span, progress, new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                paintDpi(DisplayDensity.MIN + p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                dpiDragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                dpiDragging = false;
            }
        });
        dpiBar.setFocusable(true);
        dpiBar.setFocusableInTouchMode(true);
        LinearLayout ends = UiKit.row(root);
        TextView smaller = new TextView(this);
        smaller.setText("Smaller");
        smaller.setTextColor(UiKit.mutedColor(this));
        smaller.setTextSize(13f);
        smaller.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView larger = new TextView(this);
        larger.setText("Larger");
        larger.setTextColor(UiKit.mutedColor(this));
        larger.setTextSize(13f);
        larger.setGravity(Gravity.END);
        larger.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ends.addView(smaller);
        ends.addView(larger);
        LinearLayout dpiBtns = UiKit.row(root);
        UiKit.flexButton(dpiBtns, "Apply", this::applyDpiFromBar);
        UiKit.flexButton(dpiBtns, "Reset", this::resetDpi);
        paintDpi(live);

        UiKit.section(root, "Calls");
        imsStatus = UiKit.mono(root);
        UiKit.button(root, "Open Calls", () ->
            startActivity(new android.content.Intent(this, CallsActivity.class)));
        UiKit.section(root, "Treble");
        trebleStatus = UiKit.mono(root);
        UiKit.button(root, "Open Treble settings", this::openTrebleSettings);

        // --- Optional: share VPN (Tailscale TUN) with SoftAP clients ---
        UiKit.section(root, "Hotspot + VPN");
        tVpnHotspot = UiKit.toggle(root, "Share VPN over hotspot",
            VpnHotspotHeal.isEnabled(this),
            on -> {
                VpnHotspotHeal.setEnabled(this, on);
                UiKit.toast(this, on
                    ? "VPN hotspot heal on (needs root)"
                    : "VPN hotspot heal off");
                // Script needs a moment for dnsmasq + settings
                h.postDelayed(this::refreshVpnHotspotStatus, 800);
                h.postDelayed(this::refreshVpnHotspotStatus, 2200);
            });
        vpnHotspotStatus = UiKit.mono(root);
        TextView vpnHint = UiKit.mono(root);
        vpnHint.setText(
            "Optional. When SoftAP clients have no internet while Tailscale\n"
                + "(or any TUN VPN) is up: DNS on the hotspot gateway + MTU\n"
                + "clamp + allow VPN as tether upstream. Off by default.\n"
                + "Do not advertise the SoftAP subnet as a Tailscale route\n"
                + "if clients only need exit-node internet.\n"
                + "V toggle · R refresh · Esc");
        UiKit.button(root, "Re-apply VPN hotspot heal", () -> {
            if (!VpnHotspotHeal.isEnabled(this)) {
                UiKit.toast(this, "Turn on Share VPN over hotspot first");
                return;
            }
            VpnHotspotHeal.applyAsync();
            UiKit.toast(this, "Re-applying…");
            h.postDelayed(this::refreshVpnHotspotStatus, 900);
        });

        // --- Keyboard (Titan HW only — missing from Treble) ---
        UiKit.section(root, "Keyboard");
        tSoftIme = UiKit.toggle(root, "Soft keyboard",
            ImeHwPrefs.softImeWithHw(this),
            on -> ImeHwPrefs.setSoftImeWithHw(this, on));
        boolean hidden = getSharedPreferences(PREFS_TWEAKS, MODE_PRIVATE)
            .getBoolean(KEY_SWITCHER_HIDDEN, false);
        tImeSwitcher = UiKit.toggle(root, "Hide keyboard switcher", hidden, on -> {
            if (on) hideKeyboardSwitcher();
            else showKeyboardSwitcher();
        });

        TextView kbHint = UiKit.mono(root);
        kbHint.setText("A dpi · C Calls · T Treble · V VPN · W soft KB · K switcher · R · Esc");

        setContentView(sc);
        TrebleAppBridge.hideFromSettings(this);
        refreshTrebleStatus();
        refreshIms();
        refreshVpnHotspotStatus();
        if (dpiBar != null) {
            dpiBar.post(() -> {
                try { dpiBar.requestFocus(); } catch (Exception ignored) {}
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        TrebleAppBridge.hideFromSettings(this);
        refreshTrebleStatus();
        refreshIms();
        refreshVpnHotspotStatus();
        if (!dpiDragging) refreshDpi();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && !event.isAltPressed() && !event.isCtrlPressed()
                && !event.isMetaPressed() && !event.isShiftPressed()) {
            int kc = event.getKeyCode();
            if (kc == KeyEvent.KEYCODE_ESCAPE) {
                finish();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_A) {
                applyDpiFromBar();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_R) {
                refreshTrebleStatus();
                refreshIms();
                refreshVpnHotspotStatus();
                refreshDpi();
                UiKit.toast(this, "Status refreshed");
                return true;
            }
            if (kc == KeyEvent.KEYCODE_C) {
                startActivity(new android.content.Intent(this, CallsActivity.class));
                return true;
            }
            if (kc == KeyEvent.KEYCODE_T) {
                if (!TrebleAppBridge.openSettings(this)) {
                    UiKit.toast(this, "TrebleApp missing");
                }
                return true;
            }
            if (kc == KeyEvent.KEYCODE_V) {
                if (tVpnHotspot == null) return true;
                boolean next = !tVpnHotspot.isChecked();
                tVpnHotspot.setChecked(next);
                VpnHotspotHeal.setEnabled(this, next);
                UiKit.toast(this, next ? "VPN hotspot heal on" : "VPN hotspot heal off");
                h.postDelayed(this::refreshVpnHotspotStatus, 800);
                return true;
            }
            if (kc == KeyEvent.KEYCODE_W) {
                if (tSoftIme == null) return true;
                boolean next = !tSoftIme.isChecked();
                tSoftIme.setChecked(next);
                ImeHwPrefs.setSoftImeWithHw(this, next);
                UiKit.toast(this, next ? "Soft keyboard on" : "Soft keyboard off");
                return true;
            }
            if (kc == KeyEvent.KEYCODE_K) {
                if (tImeSwitcher == null) return true;
                boolean next = !tImeSwitcher.isChecked();
                tImeSwitcher.setChecked(next);
                if (next) hideKeyboardSwitcher();
                else showKeyboardSwitcher();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void paintDpi(int dpi) {
        if (dpiValue == null) return;
        dpiValue.setText(dpi + " dpi");
    }

    private void refreshDpi() {
        int live = DisplayDensity.current(this);
        paintDpi(live);
        if (dpiBar == null) return;
        int p = live - DisplayDensity.MIN;
        if (p < 0) p = 0;
        int span = DisplayDensity.MAX - DisplayDensity.MIN;
        if (p > span) p = span;
        if (dpiBar.getProgress() != p) dpiBar.setProgress(p);
    }

    private int dpiFromBar() {
        if (dpiBar == null) return DisplayDensity.current(this);
        return DisplayDensity.MIN + dpiBar.getProgress();
    }

    private void applyDpiFromBar() {
        int dpi = dpiFromBar();
        boolean ok = DisplayDensity.apply(this, dpi);
        int live = DisplayDensity.liveOverride();
        if (live <= 0) live = DisplayDensity.current(this);
        paintDpi(ok ? dpi : live);
        String err = DisplayDensity.lastWmError;
        UiKit.toast(this, ok ? ("Applied " + dpi + " dpi")
            : ("Apply failed" + (err.isEmpty() ? "" : (": " + err))));
        if (!ok) refreshDpi();
    }

    private void resetDpi() {
        if (!DisplayDensity.reset(this)) {
            UiKit.toast(this, "Reset failed");
            refreshDpi();
            return;
        }
        h.postDelayed(this::refreshDpi, 200);
    }

    private void refreshVpnHotspotStatus() {
        if (vpnHotspotStatus == null) return;
        try {
            vpnHotspotStatus.setText("VPN hotspot: " + VpnHotspotHeal.statusLine(this));
        } catch (Exception e) {
            vpnHotspotStatus.setText("VPN hotspot: status error");
        }
        if (tVpnHotspot != null) {
            boolean en = VpnHotspotHeal.isEnabled(this);
            if (tVpnHotspot.isChecked() != en) {
                tVpnHotspot.setChecked(en);
            }
        }
    }

    private void openTrebleSettings() {
        if (!TrebleAppBridge.openSettings(this)) {
            UiKit.toast(this, "TrebleApp not installed — hybrid must keep TrebleApp");
            refreshTrebleStatus();
        }
    }

    private void refreshTrebleStatus() {
        if (trebleStatus == null) return;
        if (TrebleAppBridge.isInstalled(this)) {
            trebleStatus.setText("TrebleApp: installed (hidden)");
        } else {
            trebleStatus.setText("TrebleApp: missing on this ROM");
        }
    }

    private void refreshIms() {
        if (imsStatus == null) return;
        try {
            imsStatus.setText(ImsCalls.detect(this).line());
        } catch (Exception e) {
            imsStatus.setText("Calls: detect error");
        }
    }

    private void hideKeyboardSwitcher() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_INPUT_METHODS);
            String def = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
            if (enabled == null || enabled.isEmpty()) {
                UiKit.toast(this, "No keyboards configured");
                if (tImeSwitcher != null) tImeSwitcher.setChecked(false);
                return;
            }
            if (def == null || def.isEmpty()) {
                def = enabled.split(":")[0].split(";")[0];
            }
            String imeId = def.split(";")[0];
            String single = oneImeOneSubtype(enabled, imeId);

            getSharedPreferences(PREFS_TWEAKS, MODE_PRIVATE).edit()
                .putString(KEY_IME_BACKUP, enabled)
                .putBoolean(KEY_SWITCHER_HIDDEN, true)
                .apply();

            Settings.Secure.putString(getContentResolver(),
                Settings.Secure.ENABLED_INPUT_METHODS, single);
            Settings.Secure.putString(getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD, imeId);
            Settings.Secure.putInt(getContentResolver(),
                "input_method_selector_visibility", 2);
            try {
                Settings.System.putInt(getContentResolver(),
                    "status_bar_ime_switcher", 0);
            } catch (Exception ignored) {}
            UiKit.toast(this, "Switcher hidden");
        } catch (Exception e) {
            UiKit.toast(this, "Failed: " + e.getMessage());
            if (tImeSwitcher != null) tImeSwitcher.setChecked(false);
        }
    }

    private static String oneImeOneSubtype(String enabled, String imeId) {
        for (String entry : enabled.split(":")) {
            if (entry == null || entry.isEmpty()) continue;
            String[] parts = entry.split(";");
            if (parts.length == 0) continue;
            if (!imeId.equals(parts[0])) continue;
            if (parts.length >= 2 && parts[1] != null && !parts[1].isEmpty()) {
                return imeId + ";" + parts[1];
            }
            return imeId;
        }
        return imeId;
    }

    private void showKeyboardSwitcher() {
        try {
            String backup = getSharedPreferences(PREFS_TWEAKS, MODE_PRIVATE)
                .getString(KEY_IME_BACKUP, null);
            if (backup != null && !backup.isEmpty()) {
                Settings.Secure.putString(getContentResolver(),
                    Settings.Secure.ENABLED_INPUT_METHODS, backup);
            }
            Settings.Secure.putInt(getContentResolver(),
                "input_method_selector_visibility", 0);
            getSharedPreferences(PREFS_TWEAKS, MODE_PRIVATE).edit()
                .putBoolean(KEY_SWITCHER_HIDDEN, false)
                .apply();
            UiKit.toast(this, "Switcher restored");
        } catch (Exception e) {
            UiKit.toast(this, "Failed: " + e.getMessage());
        }
    }
}
