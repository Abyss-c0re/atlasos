package com.titanus2.controls;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.titanus2.controls.ui.UiKit;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Product changelog — every build stamps CHANGELOG.md → assets/changelog.txt.
 * Settings hub → Changelog. No marketing; plain build history.
 */
public class ChangelogActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.applyOpaqueWindow(this);
        setTitle("Changelog");

        ScrollView scroll = new ScrollView(this);
        UiKit.prepareScroll(scroll);
        LinearLayout root = new LinearLayout(this);
        UiKit.screen(root);
        scroll.addView(root, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        UiKit.title(root, "Changelog");
        TextView ver = UiKit.summary(root);
        String vn = "unknown";
        int vc = 0;
        try {
            vn = BuildConfig.VERSION_NAME;
            vc = BuildConfig.VERSION_CODE;
        } catch (Throwable ignored) {
            try {
                vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                vc = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            } catch (Exception e) {
                /* keep defaults */
            }
        }
        ver.setText("This build: " + vn + " (" + vc + ")");
        ver.setTypeface(Typeface.MONOSPACE);

        TextView body = new TextView(this);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        body.setTextColor(UiKit.textColor(this));
        body.setTextIsSelectable(true);
        int pad = UiKit.dp(body, 4);
        body.setPadding(0, pad, 0, pad);
        body.setText(loadChangelog());
        root.addView(body, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(scroll);
    }

    private String loadChangelog() {
        StringBuilder sb = new StringBuilder();
        // Prefer assets (build-stamped). Fallback res/raw if ever present.
        try (InputStream in = getAssets().open("changelog.txt");
             BufferedReader br = new BufferedReader(
                 new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            try (InputStream in = getResources().openRawResource(
                    getResources().getIdentifier("changelog", "raw", getPackageName()));
                 BufferedReader br = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } catch (Exception e2) {
                return "changelog missing — rebuild Titan Controls (build.sh stamps assets).\n"
                    + "Error: " + e.getMessage();
            }
        }
        if (sb.length() == 0) {
            return "(empty changelog)";
        }
        return sb.toString();
    }
}
