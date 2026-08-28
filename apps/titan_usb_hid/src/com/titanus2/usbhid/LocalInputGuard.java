package com.titanus2.usbhid;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Detects phone text-field / IME focus so HID can yield TitanKey to Android.
 * Pad stays on the HID guest.
 *
 * <p><b>Works without root.</b> Primary probe is plain {@code dumpsys} via
 * {@code sh} (no {@code su}). Root is an optional fallback if the unprivileged
 * dump is empty. Magisk {@code service.sh} may also write the OS control plane;
 * the bridge ORs app-private + OS so either path can pause.
 *
 * <p>Critical: full {@code dumpsys input_method} is huge; {@code mServedView} /
 * {@code mInputShown} sit past the first 12KB. We only pull those lines.
 * Stale per-client {@code inputType=} rows must NOT count — they stay non-zero
 * forever and would leave host keys paused permanently.
 */
public final class LocalInputGuard {
    private LocalInputGuard() {}

    public static boolean isLocalTextInputActive(Context ctx) {
        // Atlas terminal is not an IME EditText. Share mode must still yield
        // TitanKey while Atlas is the focused window, then give HID the keys
        // back when Atlas loses focus.
        if (isAtlasFocused(ctx)) return true;
        String dump = dumpsysFocusLines();
        if (dump != null && !dump.isEmpty() && parseFocusDump(dump)) return true;
        return isAtlasWindowFocused();
    }

    /** Package-visible for tests / older callers. */
    static boolean parseInputMethodDump(String dump) {
        return parseFocusDump(dump);
    }

    static boolean parseFocusDump(String dump) {
        if (dump == null || dump.isEmpty()) return false;

        String served = lastRealServedView(dump);
        boolean ourPkg = served != null
            && served.toLowerCase(Locale.US).contains("com.titanus2.usbhid");

        // Soft IME up alone is not enough — stale mInputShown often stays true
        // after dismissing IME and would leave host keys/mouse paused forever.
        // Require a real served editor (or IME extract) that is not our Type field.
        if (served == null || ourPkg) return false;
        if (isEditorishView(served)) return true;
        // IME extract / fullscreen editor without a clear served class
        if ((anyTrue(dump, "mInputShown") || anyTrue(dump, "mShowRequested"))
                && dump.toLowerCase(Locale.US).contains("extractedittext")) {
            return true;
        }
        return false;
    }

    private static boolean anyTrue(String dump, String key) {
        for (String line : dump.split("\n")) {
            if (!line.contains(key)) continue;
            // only key=true lines
            if (line.contains(key + "=true") || line.contains(key + " = true"))
                return true;
        }
        return false;
    }

    /** Last non-null mServedView (skip fallback … mServedView=null). */
    private static String lastRealServedView(String dump) {
        String best = null;
        for (String line : dump.split("\n")) {
            int i = line.indexOf("mServedView=");
            if (i < 0) i = line.indexOf("mServedView =");
            if (i < 0) continue;
            String rest = line.substring(i).trim();
            if (rest.contains("mServedView=null") || rest.contains("mServedView = null"))
                continue;
            best = rest;
        }
        return best;
    }

    /**
     * True for real editors / search bars. False for DecorView, generic View,
     * RecyclerView, etc. (those appear as mServedView without being text fields).
     */
    private static boolean isEditorishView(String servedLine) {
        String low = servedLine.toLowerCase(Locale.US);
        if (low.contains("null")) return false;
        // Non-editors that often show up as served root
        if (low.contains("decorview") || low.contains("recyclerview")
                || low.contains("viewpager") || low.contains("framelayout{")
                || low.contains("linearlayout{") || low.contains("relativelayout{")
                || low.contains("constraintlayout{") || low.contains("scrollview{")
                || low.contains("nestedscrollview")) {
            // …unless the class name also clearly is a search/editor widget
            // (e.g. some custom SearchFrameLayout)
            if (!(low.contains("search") || low.contains("edit") || low.contains("input")))
                return false;
        }
        return low.contains("edittext")
            || low.contains("autocompletetextview")
            || low.contains("multiautocompletetextview")
            || low.contains("searchview")
            || low.contains("webview")
            || low.contains("extractedittext")
            || low.contains("textfield")
            || low.contains("appcompatedittext")
            || low.contains("textinputedittext")
            || low.contains("textinputlayout")
            // Atlas / Termux: custom editor, not an Android EditText.
            || low.contains("terminalview")
            || low.contains("com.titanus2.atlas")
            // Launcher all-apps search, custom search bars
            || low.contains("search")
            || low.contains("editable");
    }

    /** Atlas published plane + Settings.Global (instant path). */
    static boolean isAtlasFocused(Context ctx) {
        if (readFlag("titan2_atlas_focused")) return true;
        if (ctx == null) return false;
        try {
            String g = android.provider.Settings.Global.getString(
                ctx.getContentResolver(), "titan2_atlas_focused");
            if (g != null) {
                g = g.trim();
                if ("1".equals(g) || "true".equalsIgnoreCase(g)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** dumpsys window: Atlas MainActivity is the focused window. */
    static boolean isAtlasWindowFocused() {
        final String script =
            "timeout 0.3 dumpsys window 2>/dev/null | "
            + "grep -E 'mCurrentFocus=|mFocusedApp=' 2>/dev/null | head -n 8";
        String dump = runCapture(new String[]{"sh", "-c", script}, 400);
        if (dump == null || dump.isEmpty()) return false;
        String low = dump.toLowerCase(Locale.US);
        return low.contains("com.titanus2.atlas")
            && (low.contains("mainactivity") || low.contains("terminal"));
    }

    private static boolean readFlag(String name) {
        String[] roots = { "/data/misc/titan2/", "/data/local/tmp/" };
        for (String r : roots) {
            java.io.File f = new java.io.File(r + name);
            if (!f.isFile()) continue;
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                byte[] b = new byte[16];
                int n = in.read(b);
                if (n <= 0) continue;
                String s = new String(b, 0, n, StandardCharsets.US_ASCII).trim();
                if ("1".equals(s) || "true".equalsIgnoreCase(s)) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private static String dumpsysFocusLines() {
        // Only while share-mode FGS needs host-pause. Soft Type never calls this.
        // Cap hard: full dumpsys + IME contention froze Type binder ~1.1s/poll.
        final String script =
            "timeout 0.4 dumpsys input_method 2>/dev/null | "
            + "grep -E 'mInputShown=|mShowRequested=|mServedView=' 2>/dev/null "
            + "| head -n 20";
        // No su fallback — su+dumpsys on the HID process was catastrophic for IME
        return runCapture(new String[]{"sh", "-c", script}, 500);
    }

    private static String runCapture(String[] cmd, long timeoutMs) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            StringBuilder sb = new StringBuilder(4096);
            try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (sb.length() > 12000) break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) {}
        }
    }
}
