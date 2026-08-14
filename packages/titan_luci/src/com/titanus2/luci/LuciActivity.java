package com.titanus2.luci;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

/** DeviceDefault WebView over loopback LuCI. Start the plane, then load. */
public final class LuciActivity extends Activity {
    private WebView web;
    private android.widget.TextView wait;
    private View topGap;
    private View botGap;
    private String url;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("LuCI");
        url = "http://127.0.0.1:8080/cgi-bin/luci/";
        Intent in = getIntent();
        if (in != null && in.getStringExtra("url") != null) {
            url = in.getStringExtra("url");
        }
        if (in != null && in.getData() != null) {
            url = in.getData().toString();
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        topGap = new View(this);
        root.addView(topGap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, statusBarHeightPx()));

        wait = new android.widget.TextView(this);
        wait.setText("Starting OpenWrt…");
        wait.setPadding(24, 24, 24, 24);
        root.addView(wait);

        web = new WebView(this);
        web.setVisibility(android.view.View.GONE);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setWebViewClient(new WebViewClient());
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(web, webLp);

        botGap = new View(this);
        root.addView(botGap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, navBarHeightPx()));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = statusBarHeightPx();
            int bot = navBarHeightPx();
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets nav = insets.getInsets(WindowInsets.Type.navigationBars());
                top = Math.max(bars.top, top);
                bot = Math.max(nav.bottom, bot);
            } else {
                top = Math.max(insets.getSystemWindowInsetTop(), top);
                bot = Math.max(insets.getSystemWindowInsetBottom(), bot);
            }
            ViewGroup.LayoutParams tp = topGap.getLayoutParams();
            tp.height = top;
            topGap.setLayoutParams(tp);
            ViewGroup.LayoutParams bp = botGap.getLayoutParams();
            bp.height = bot;
            botGap.setLayoutParams(bp);
            return insets;
        });

        setContentView(root);
        root.requestApplyInsets();
        armThenLoad();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null && web.getUrl() != null && web.getUrl().contains("error")) {
            armThenLoad();
        }
    }

    private void armThenLoad() {
        new Thread(() -> {
            startPlane();
            for (int i = 0; i < 20; i++) {
                if (luciUp()) break;
                startPlane();
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            }
            h.post(() -> {
                if (isFinishing() || web == null) return;
                if (wait != null) wait.setVisibility(android.view.View.GONE);
                web.setVisibility(android.view.View.VISIBLE);
                web.loadUrl(url);
            });
        }, "luci-arm").start();
    }

    private static void startPlane() {
        try {
            Runtime.getRuntime().exec(new String[] {
                "su", "-c", "sh /system/bin/titan2-openwrt.sh start"
            });
        } catch (Exception ignored) {}
    }

    private static boolean luciUp() {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL("http://127.0.0.1:8080/cgi-bin/luci/").openConnection();
            c.setConnectTimeout(400);
            c.setReadTimeout(400);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            c.disconnect();
            return code > 0 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private int statusBarHeightPx() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int h = id > 0 ? getResources().getDimensionPixelSize(id) : 0;
        if (h < 1) h = (int) (28 * getResources().getDisplayMetrics().density);
        return h;
    }

    private int navBarHeightPx() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
