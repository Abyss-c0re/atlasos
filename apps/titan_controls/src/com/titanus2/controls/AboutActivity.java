package com.titanus2.controls;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.titanus2.controls.ui.UiKit;

/**
 * On-device subset of CREDITS.md — names, repo, third-party. Not marketing.
 */
public class AboutActivity extends Activity {
    private static final String REPO = "https://github.com/Abyss-c0re/AtlasOS";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.applyOpaqueWindow(this);
        setTitle("About");

        ScrollView scroll = new ScrollView(this);
        UiKit.prepareScroll(scroll);
        LinearLayout root = new LinearLayout(this);
        UiKit.screen(root);
        scroll.addView(root, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT));

        UiKit.section(root, "AtlasOS");
        UiKit.listRow(root, "Build", displayId(), () -> { });
        UiKit.listRow(root, "Lineage", lineageLine(), () -> { });
        UiKit.listRow(root, "Controls", controlsLine(), () -> { });
        UiKit.listRow(root, "Source", REPO, () -> open(REPO));

        UiKit.section(root, "Credits");
        UiKit.listRow(root, "Abyss-c0re", "maintainer",
            () -> open("https://github.com/Abyss-c0re"));
        UiKit.listRow(root, "NexusCore", "station / offline core", () -> { });
        UiKit.listRow(root, "Hive Mind", "lab agents under Dev/AGENTS.md", () -> { });
        UiKit.listRow(root, "Grok / Atlas agents", "implementation sessions",
            () -> open("https://x.ai"));

        UiKit.section(root, "Other projects");
        link(root, "LineageOS", "platform", "https://lineageos.org/");
        link(root, "MisterZtr GSI", "GSI recipe",
            "https://github.com/MisterZtr/LineageOS_gsi");
        link(root, "phhusson / Treble", "GSI baseline",
            "https://github.com/phhusson");
        link(root, "PeterGSI touchpadd", "original pad driver — we fork",
            "https://gitea.angry.im/PeterGSI/titan2-touchpadd");
        link(root, "PeterCxy OpenEUICC", "GPL-3.0 eSIM",
            "https://gitea.angry.im/PeterCxy/OpenEUICC");
        link(root, "Termux", "GPLv3 terminal emulator",
            "https://github.com/termux/termux-app");
        link(root, "Unihertz", "hardware + stock vendor",
            "https://www.unihertz.com/");

        UiKit.note(root, "Full map: CREDITS.md in the repo");
        setContentView(scroll);
    }

    private String displayId() {
        String atlas = prop("ro.atlasos.version", "");
        String disp = Build.DISPLAY != null ? Build.DISPLAY : "";
        if (!atlas.isEmpty() && disp.contains("AtlasOS")) return disp;
        if (!atlas.isEmpty()) return "AtlasOS-" + atlas;
        if (disp.contains("AtlasOS")) return disp;
        return disp.isEmpty() ? "AtlasOS" : disp;
    }

    private String lineageLine() {
        String v = prop("ro.lineage.version", prop("ro.lineage.display.version", ""));
        return v.isEmpty() ? "Lineage 23.2" : v;
    }

    private String controlsLine() {
        try {
            return BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
        } catch (Throwable t) {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "Controls";
            }
        }
    }

    private static String prop(String key, String fb) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String v = (String) sp.getMethod("get", String.class, String.class)
                .invoke(null, key, fb);
            return v != null ? v : fb;
        } catch (Throwable t) {
            return fb;
        }
    }

    private void link(LinearLayout root, String title, String sub, String url) {
        UiKit.listRow(root, title, sub, () -> open(url));
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }
}
