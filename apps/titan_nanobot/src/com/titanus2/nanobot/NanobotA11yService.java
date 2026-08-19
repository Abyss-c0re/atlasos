package com.titanus2.nanobot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * User-gated Android UI control (taps, scroll, global actions, find-by-text).
 * Must be enabled in system Accessibility settings. Honors
 * {@link PrivacyPrefs#deviceControl} and {@link PrivacyPrefs#a11yControl}.
 */
public class NanobotA11yService extends AccessibilityService {
    private static final String TAG = "NanobotA11y";
    private static final AtomicReference<NanobotA11yService> LIVE = new AtomicReference<>();

    public static NanobotA11yService get() { return LIVE.get(); }

    public static boolean isLive() { return LIVE.get() != null; }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        LIVE.set(this);
        Log.i(TAG, "accessibility connected");
        try { AccessLog.record(this, "a11y", "service connected"); } catch (Exception ignored) {}
    }

    @Override public void onDestroy() {
        LIVE.compareAndSet(this, null);
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* passive */ }

    @Override public void onInterrupt() { /* no-op */ }

    /** Prefs only — Atlas gate lives in {@link DeviceOps#a11y} (observe vs input). */
    private boolean allowed() {
        return PrivacyPrefs.deviceControl(this) && PrivacyPrefs.a11yControl(this);
    }

    public JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            o.put("connected", true);
            o.put("device_control", PrivacyPrefs.deviceControl(this));
            o.put("a11y_control", PrivacyPrefs.a11yControl(this));
            o.put("allowed", allowed());
        } catch (Exception ignored) {}
        return o;
    }

    public JSONObject global(String action) {
        JSONObject o = new JSONObject();
        try {
            if (!allowed()) {
                o.put("ok", false);
                o.put("error", "device_control and a11y_control must be ON (Settings → Nanobot)");
                return o;
            }
            int a = -1;
            if ("back".equalsIgnoreCase(action)) a = GLOBAL_ACTION_BACK;
            else if ("home".equalsIgnoreCase(action)) a = GLOBAL_ACTION_HOME;
            else if ("recents".equalsIgnoreCase(action) || "app_switch".equalsIgnoreCase(action))
                a = GLOBAL_ACTION_RECENTS;
            else if ("notifications".equalsIgnoreCase(action)) a = GLOBAL_ACTION_NOTIFICATIONS;
            else if ("quick_settings".equalsIgnoreCase(action)) a = GLOBAL_ACTION_QUICK_SETTINGS;
            else if ("power".equalsIgnoreCase(action) && Build.VERSION.SDK_INT >= 21)
                a = GLOBAL_ACTION_POWER_DIALOG;
            else {
                o.put("ok", false);
                o.put("error", "unknown action: " + action);
                return o;
            }
            boolean ok = performGlobalAction(a);
            o.put("ok", ok);
            o.put("action", action);
            AccessLog.record(this, "a11y_global", action + " ok=" + ok);
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return o;
    }

    public JSONObject clickText(String text) {
        JSONObject o = new JSONObject();
        try {
            if (!allowed()) {
                o.put("ok", false);
                o.put("error", "a11y/device_control off");
                return o;
            }
            if (text == null || text.isEmpty()) {
                o.put("ok", false);
                o.put("error", "empty text");
                return o;
            }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                o.put("ok", false);
                o.put("error", "no window root");
                return o;
            }
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
            boolean clicked = false;
            String hit = null;
            if (nodes != null) {
                for (AccessibilityNodeInfo n : nodes) {
                    if (n == null) continue;
                    if (clickNode(n)) {
                        clicked = true;
                        CharSequence d = n.getText();
                        if (d == null) d = n.getContentDescription();
                        hit = d != null ? d.toString() : text;
                        break;
                    }
                }
            }
            o.put("ok", clicked);
            o.put("text", text);
            o.put("hit", hit != null ? hit : JSONObject.NULL);
            if (!clicked) o.put("error", "no clickable node for text");
            AccessLog.record(this, "a11y_click", text + " ok=" + clicked);
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return o;
    }

    private boolean clickNode(AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (n.isClickable()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        AccessibilityNodeInfo p = n.getParent();
        int guard = 0;
        while (p != null && guard++ < 12) {
            if (p.isClickable()) return p.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            AccessibilityNodeInfo next = p.getParent();
            p = next;
        }
        // gesture fallback to center of bounds
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.width() > 0 && r.height() > 0) {
            return tap(r.centerX(), r.centerY());
        }
        return false;
    }

    public boolean tap(float x, float y) {
        if (!allowed()) return false;
        if (Build.VERSION.SDK_INT < 24) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, 50);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, null, null);
    }

    public JSONObject dumpTree(int maxNodes) {
        JSONObject o = new JSONObject();
        try {
            if (!allowed()) {
                o.put("ok", false);
                o.put("error", "a11y/device_control off");
                return o;
            }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            JSONArray arr = new JSONArray();
            if (root != null) collect(root, arr, maxNodes <= 0 ? 80 : maxNodes, 0);
            o.put("ok", true);
            o.put("nodes", arr);
            o.put("count", arr.length());
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return o;
    }

    private void collect(AccessibilityNodeInfo n, JSONArray arr, int max, int depth) {
        if (n == null || arr.length() >= max || depth > 24) return;
        try {
            JSONObject j = new JSONObject();
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            CharSequence id = n.getViewIdResourceName();
            if (t != null) j.put("text", t.toString());
            if (d != null) j.put("desc", d.toString());
            if (id != null) j.put("id", id.toString());
            j.put("clickable", n.isClickable());
            j.put("enabled", n.isEnabled());
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            j.put("bounds", r.flattenToString());
            if (j.has("text") || j.has("desc") || j.has("id") || n.isClickable()) {
                arr.put(j);
            }
            for (int i = 0; i < n.getChildCount() && arr.length() < max; i++) {
                collect(n.getChild(i), arr, max, depth + 1);
            }
        } catch (Exception ignored) {}
    }
}
