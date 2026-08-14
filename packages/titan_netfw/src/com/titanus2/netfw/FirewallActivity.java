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
import android.widget.TextView;

/** Settings → Firewall. LuCI firewall page. Same OpenWrt as Router. */
public final class FirewallActivity extends Activity {
    private static final String LUCI_FW =
        "http://127.0.0.1:8080/cgi-bin/luci/admin/network/firewall";
    private TextView facts;
    private WebView web;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Firewall");
        int edge = NetUi.edge(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(edge, edge / 2, edge, 0);
        facts = NetUi.fact(this, "OpenWrt firewall");
        root.addView(facts);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        root.addView(web, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        new Thread(() -> {
            OpenWrt.run("start");
            h.post(() -> {
                facts.setText(OpenWrt.bar());
                web.loadUrl(LUCI_FW);
            });
        }, "ow-fw").start();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
