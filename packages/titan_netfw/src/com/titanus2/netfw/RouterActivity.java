package com.titanus2.netfw;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Settings → Router. LuCI wrapper over atlas_openwrt.
 * LAN gateway is always 192.168.6.1. SoftAP radio stays Android.
 * WAN: under Android VPN (tun0) or above it (wlan/cell).
 */
public final class RouterActivity extends Activity {
    private static final String LUCI = "http://127.0.0.1:8080/cgi-bin/luci/";
    private TextView facts;
    private Switch wanAbove;
    private WebView web;
    private boolean binding;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Router");
        int edge = NetUi.edge(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(edge, edge / 2, edge, 0);
        facts = NetUi.fact(this, "starting OpenWrt…");
        root.addView(facts);
        wanAbove = NetUi.sw(this, "Above Android VPN");
        wanAbove.setOnCheckedChangeListener((b, on) -> {
            if (binding) return;
            OpenWrt.run(on ? "above" : "under");
            reloadBar();
        });
        root.addView(wanAbove);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(web, lp);
        setContentView(root);
        new Thread(() -> {
            OpenWrt.ensureAuth();
            OpenWrt.run("start");
            h.post(this::loadLuci);
        }, "ow-start").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadBar();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private void reloadBar() {
        binding = true;
        wanAbove.setChecked(OpenWrt.aboveVpn());
        binding = false;
        facts.setText(OpenWrt.bar());
    }

    private void loadLuci() {
        reloadBar();
        if (web != null) web.loadUrl(LUCI);
    }
}
