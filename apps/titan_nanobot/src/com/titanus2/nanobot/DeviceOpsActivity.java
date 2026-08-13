package com.titanus2.nanobot;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

/** User switches + one-shot probes for multi-device ops. */
public class DeviceOpsActivity extends Activity {
    private TextView out;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView sc = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        col.setPadding(p, p, p, p);
        sc.addView(col);
        setContentView(sc);

        TextView t = new TextView(this);
        t.setText("Device ops (all units)");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTextColor(0xFFE0E0E0);
        col.addView(t);

        TextView h = new TextView(this);
        h.setText("device_control + Accessibility (user grant) + bin_exec.\n"
            + "SMS = compose intent only (no silent send). Reboot blocked by shell policy.");
        h.setTextColor(0xFF9E9E9E);
        h.setPadding(0, dp(8), 0, dp(12));
        col.addView(h);

        Switch dc = new Switch(this);
        dc.setText("device_control");
        dc.setChecked(PrivacyPrefs.deviceControl(this));
        dc.setOnCheckedChangeListener((b, v) -> PrivacyPrefs.setDeviceControl(this, v));
        col.addView(dc);

        Switch ac = new Switch(this);
        ac.setText("a11y_control (taps / UI)");
        ac.setChecked(PrivacyPrefs.a11yControl(this));
        ac.setOnCheckedChangeListener((b, v) -> PrivacyPrefs.setA11yControl(this, v));
        col.addView(ac);

        Switch be = new Switch(this);
        be.setText("bin_exec (system/tip binaries)");
        be.setChecked(PrivacyPrefs.binExec(this));
        be.setOnCheckedChangeListener((b, v) -> PrivacyPrefs.setBinExec(this, v));
        col.addView(be);

        col.addView(btn("Open Accessibility settings", v ->
            show(DeviceOps.openA11ySettings(this))));
        col.addView(btn("A11y status", v ->
            show(DeviceOps.a11y(this, "status", null))));
        col.addView(btn("A11y dump UI tree", v ->
            show(DeviceOps.a11y(this, "dump", new JSONObject()))));
        col.addView(btn("Camera2 / depth inventory", v ->
            show(DepthScan.inventory(this))));
        col.addView(btn("quest_sensor_probe (+ inventory)", v ->
            show(DepthScan.runProbeBinary(this))));
        col.addView(btn("Launch Settings", v ->
            show(DeviceOps.launchPackage(this, "com.android.settings"))));
        col.addView(btn("SMS compose demo (no send)", v -> {
            try {
                JSONObject spec = new JSONObject();
                spec.put("action", "sms_compose");
                spec.put("to", "");
                spec.put("body", "Hello from Nanobot DeviceOps");
                show(DeviceOps.startActivity(this, spec));
            } catch (Exception e) {
                showErr(e);
            }
        }));
        col.addView(btn("Exec nanobot --auth-status", v ->
            show(DeviceOps.execBinary(this, "/system/bin/nanobot",
                new String[]{"--home", "/data/local/tmp/nanobot_home", "--auth-status"}))));

        out = new TextView(this);
        out.setTextColor(0xFFB0BEC5);
        out.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        out.setPadding(0, dp(16), 0, 0);
        out.setTextIsSelectable(true);
        col.addView(out);
        show(DeviceOps.gate(this));
    }

    private Button btn(String label, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private void show(JSONObject o) {
        try { out.setText(o != null ? o.toString(2) : "null"); }
        catch (Exception e) { out.setText(String.valueOf(o)); }
    }

    private void showErr(Exception e) {
        out.setText("error: " + (e.getMessage() != null ? e.getMessage() : e));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
