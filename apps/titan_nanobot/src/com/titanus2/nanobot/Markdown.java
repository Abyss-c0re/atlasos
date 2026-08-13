package com.titanus2.nanobot;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

/**
 * Small Markdown subset for chat bubbles (no external deps).
 * Supports: **bold**, *italic*, `code`, ```blocks```, # headings, - lists, [text](url).
 */
public final class Markdown {
    private Markdown() {}

    public static CharSequence render(String src) {
        if (src == null || src.isEmpty()) return "";
        // Normalize newlines
        String s = src.replace("\r\n", "\n");
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = s.split("\n", -1);
        boolean inCode = false;
        StringBuilder codeBuf = new StringBuilder();
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            if (line.trim().startsWith("```")) {
                if (!inCode) {
                    inCode = true;
                    codeBuf.setLength(0);
                } else {
                    inCode = false;
                    int start = out.length();
                    out.append(codeBuf.toString());
                    if (out.length() > start) {
                        out.setSpan(new TypefaceSpan("monospace"), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new RelativeSizeSpan(0.92f), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    if (li < lines.length - 1) out.append('\n');
                }
                continue;
            }
            if (inCode) {
                if (codeBuf.length() > 0) codeBuf.append('\n');
                codeBuf.append(line);
                continue;
            }
            int lineStart = out.length();
            // headings
            int heading = 0;
            String body = line;
            if (line.startsWith("### ")) { heading = 3; body = line.substring(4); }
            else if (line.startsWith("## ")) { heading = 2; body = line.substring(3); }
            else if (line.startsWith("# ")) { heading = 1; body = line.substring(2); }
            boolean bullet = false;
            if (body.startsWith("- ") || body.startsWith("* ")) {
                bullet = true;
                body = body.substring(2);
            }
            appendInline(out, body);
            int lineEnd = out.length();
            if (heading > 0 && lineEnd > lineStart) {
                float size = heading == 1 ? 1.25f : heading == 2 ? 1.15f : 1.08f;
                out.setSpan(new RelativeSizeSpan(size), lineStart, lineEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new StyleSpan(Typeface.BOLD), lineStart, lineEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (bullet && lineEnd > lineStart) {
                out.setSpan(new BulletSpan(16), lineStart, lineEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (li < lines.length - 1) out.append('\n');
        }
        if (inCode && codeBuf.length() > 0) {
            int start = out.length();
            out.append(codeBuf.toString());
            out.setSpan(new TypefaceSpan("monospace"), start, out.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    private static void appendInline(SpannableStringBuilder out, String s) {
        int i = 0;
        while (i < s.length()) {
            // link [text](url)
            if (s.charAt(i) == '[') {
                int close = s.indexOf(']', i);
                if (close > i && close + 1 < s.length() && s.charAt(close + 1) == '(') {
                    int uend = s.indexOf(')', close + 2);
                    if (uend > close) {
                        String text = s.substring(i + 1, close);
                        String url = s.substring(close + 2, uend);
                        int st = out.length();
                        out.append(text);
                        out.setSpan(new URLSpan(url), st, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = uend + 1;
                        continue;
                    }
                }
            }
            // code `...`
            if (s.charAt(i) == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i) {
                    int st = out.length();
                    out.append(s.substring(i + 1, end));
                    out.setSpan(new TypefaceSpan("monospace"), st, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            // bold **...**
            if (i + 1 < s.length() && s.charAt(i) == '*' && s.charAt(i + 1) == '*') {
                int end = s.indexOf("**", i + 2);
                if (end > i) {
                    int st = out.length();
                    out.append(s.substring(i + 2, end));
                    out.setSpan(new StyleSpan(Typeface.BOLD), st, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            // italic *...*
            if (s.charAt(i) == '*') {
                int end = s.indexOf('*', i + 1);
                if (end > i) {
                    int st = out.length();
                    out.append(s.substring(i + 1, end));
                    out.setSpan(new StyleSpan(Typeface.ITALIC), st, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            out.append(s.charAt(i));
            i++;
        }
    }
}
