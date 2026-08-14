package com.titanus2.netfw;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Settings → Router. DeviceDefault rows over OpenWrt plane.
 * LuCI is the full admin, not the only surface.
 */
public final class RouterActivity extends Activity {
    private TextView facts;
    private Switch wanAbove;
    private ClientsPanel clients;
    private boolean binding;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Router");
        int edge = NetUi.edge(this);
        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(edge, edge, edge, edge);

        facts = NetUi.fact(this, "");
        root.addView(facts);

        wanAbove = NetUi.sw(this, "Above VPN");
        wanAbove.setOnCheckedChangeListener((b, on) -> {
            if (binding) return;
            new Thread(() -> {
                OpenWrt.run(on ? "above" : "under");
                h.post(this::reload);
            }, "ow-wan").start();
        });
        root.addView(wanAbove);

        clients = new ClientsPanel(this, root);

        Button more = NetUi.btn(this, "More settings");
        more.setOnClickListener(v -> openLuci("http://127.0.0.1:8080/cgi-bin/luci/"));
        root.addView(more);

        sc.addView(root);
        setContentView(sc);
        new Thread(() -> {
            OpenWrt.banNetaddr();
            OpenWrt.run("start");
            h.post(this::reload);
        }, "ow-start").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        binding = true;
        wanAbove.setChecked(OpenWrt.aboveVpn());
        binding = false;
        facts.setText(OpenWrt.bar());
        clients.refresh();
    }

    private void openLuci(String url) {
        new Thread(() -> {
            OpenWrt.run("start");
            h.post(() -> {
                try {
                    Intent i = new Intent("com.titanus2.luci.OPEN");
                    i.setClassName("com.titanus2.luci", "com.titanus2.luci.LuciActivity");
                    i.putExtra("url", url);
                    startActivity(i);
                } catch (Exception e) {
                    facts.setText(OpenWrt.bar() + "\n" + e.getMessage());
                }
            });
        }, "ow-luci").start();
    }
}
