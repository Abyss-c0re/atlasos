package com.titanus2.controls;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.View;
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
 * Settings hub → Changelog. Renders the stamped markdown (headings, lists, emphasis).
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
        renderMarkdown(root, loadChangelog(), vn, vc);

        setContentView(scroll);
    }

    /** Settings-like views for the stamped CHANGELOG.md subset. */
    private void renderMarkdown(LinearLayout root, String md, String vn, int vc) {
        TextView ver = UiKit.summary(root);
        ver.setText(vn + " (" + vc + ")");

        if (md == null || md.isEmpty()) {
            UiKit.note(root, "(empty changelog)");
            return;
        }
        String[] lines = md.split("\n", -1);
        boolean started = false;
        for (String raw : lines) {
            String line = raw.replace("\r", "");
            String trim = line.trim();
            if (trim.isEmpty()) continue;
            if (trim.equals("---")) {
                if (started) divider(root);
                continue;
            }
            /* Action bar is the title. LAW blurb is agent stamp, not UI. */
            if (trim.startsWith("# ")) continue;
            String plain = inlinePlain(trim);
            if (plain.startsWith("LAW:") || plain.startsWith("Do not ship")) continue;
            if (trim.startsWith("## ")) {
                started = true;
                UiKit.section(root, inlinePlain(trim.substring(3)));
                continue;
            }
            if (trim.startsWith("### ")) {
                subhead(root, inlinePlain(trim.substring(4)));
                continue;
            }
            if (trim.startsWith("- ") || trim.startsWith("* ")) {
                SpannableStringBuilder b = new SpannableStringBuilder("· ");
                b.append(inlineSpans(trim.substring(2)));
                bullet(root, b);
                continue;
            }
            paragraph(root, inlineSpans(trim));
        }
    }

    private void divider(LinearLayout root) {
        View d = new View(this);
        d.setBackgroundColor(UiKit.mutedColor(this) & 0x33FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, UiKit.dp(d, 1)));
        lp.topMargin = UiKit.dp(d, 8);
        lp.bottomMargin = UiKit.dp(d, 4);
        d.setLayoutParams(lp);
        root.addView(d);
    }

    private void subhead(LinearLayout root, String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        tv.setTextColor(UiKit.textColor(this));
        tv.setPadding(0, UiKit.dp(tv, 8), 0, UiKit.dp(tv, 2));
        root.addView(tv);
    }

    private void bullet(LinearLayout root, CharSequence t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTextColor(UiKit.textColor(this));
        tv.setLineSpacing(0f, 1.15f);
        int ind = UiKit.dp(tv, 12);
        tv.setPadding(ind, 0, 0, UiKit.dp(tv, 6));
        tv.setTextIsSelectable(true);
        root.addView(tv);
    }

    private void paragraph(LinearLayout root, CharSequence t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTextColor(UiKit.mutedColor(this));
        tv.setLineSpacing(0f, 1.2f);
        tv.setPadding(0, 0, 0, UiKit.dp(tv, 6));
        tv.setTextIsSelectable(true);
        root.addView(tv);
    }

    /** Strip ** and ` for heading labels. */
    private static String inlinePlain(String s) {
        if (s == null) return "";
        return s.replace("**", "").replace("`", "").trim();
    }

    /** **bold** and `mono` only — changelog does not ship links or images. */
    private static CharSequence inlineSpans(String s) {
        if (s == null) return "";
        SpannableStringBuilder out = new SpannableStringBuilder();
        int i = 0;
        while (i < s.length()) {
            if (s.startsWith("**", i)) {
                int end = s.indexOf("**", i + 2);
                if (end > i + 2) {
                    int start = out.length();
                    out.append(s, i + 2, end);
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (s.charAt(i) == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i + 1) {
                    int start = out.length();
                    out.append(s, i + 1, end);
                    out.setSpan(new TypefaceSpan("monospace"), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            out.append(s.charAt(i));
            i++;
        }
        return out;
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
