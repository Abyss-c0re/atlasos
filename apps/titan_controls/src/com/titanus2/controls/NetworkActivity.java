package com.titanus2.controls;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.titanus2.controls.network.VpnHotspotHeal;
import com.titanus2.controls.ui.UiKit;

/**
 * Tweaks hub — Cube flow: wrap TrebleApp for GSI/IMS/misc, keep Titan-only pieces here.
 * <p>
 * Do not reimplement TrebleApp IMS/telephony/BT panels. Open Treble as hidden UI;
 * focus Controls on missing product surface (Wi‑Fi shortcuts, HW keyboard IME,
 * optional VPN-over-hotspot heal).
 */
public class NetworkActivity extends Activity {
    private static final String PREFS_TWEAKS = "titan2_tweaks";
    private static final String KEY_IME_BACKUP = "enabled_imes_backup";
    private static final String KEY_SWITCHER_HIDDEN = "ime_switcher_hidden";

    private final Handler h = new Handler(Looper.getMainLooper());

    private TextView trebleStatus;
    private TextView vpnHotspotStatus;
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

        // --- TrebleApp only for IMS/misc/vendor (no reimplemented panels) ---
        UiKit.section(root, "Treble / IMS");
        trebleStatus = UiKit.mono(root);
        UiKit.button(root, "Open Treble settings", this::openTrebleSettings);
        TextView trebleHint = UiKit.mono(root);
        trebleHint.setText(
            "Full TrebleApp UI — IMS, VoLTE/WFC, misc, vendor quirks.\n"
                + "Controls does not duplicate those panels.");

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
        kbHint.setText("T Treble · V VPN hotspot · W soft KB · K switcher · R · Esc");

        setContentView(sc);
        TrebleAppBridge.hideFromSettings(this);
        refreshTrebleStatus();
        refreshVpnHotspotStatus();
        if (tVpnHotspot != null && tVpnHotspot.row != null) {
            tVpnHotspot.row.post(() -> {
                try { tVpnHotspot.row.requestFocus(); } catch (Exception ignored) {}
            });
        } else if (tSoftIme != null && tSoftIme.row != null) {
            tSoftIme.row.post(() -> {
                try { tSoftIme.row.requestFocus(); } catch (Exception ignored) {}
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        TrebleAppBridge.hideFromSettings(this);
        refreshTrebleStatus();
        refreshVpnHotspotStatus();
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
            if (kc == KeyEvent.KEYCODE_R) {
                refreshTrebleStatus();
                refreshVpnHotspotStatus();
                UiKit.toast(this, "Status refreshed");
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
            trebleStatus.setText("TrebleApp: installed (hidden; open for IMS / misc)");
        } else {
            trebleStatus.setText("TrebleApp: missing on this ROM");
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
