package com.titanus2.nanobot;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Cross-device Android ops: launch apps/activities, SMS compose intents,
 * system binary exec (allowlisted paths), a11y bridge. All gated by
 * {@link PrivacyPrefs#deviceControl}. Silent SMS send is intentionally
 * not supported — only compose Intent.
 */
public final class DeviceOps {
    private static final String TAG = "DeviceOps";

    private DeviceOps() {}

    public static JSONObject gate(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("device_control", PrivacyPrefs.deviceControl(c));
            o.put("a11y_control", PrivacyPrefs.a11yControl(c));
            o.put("a11y_connected", NanobotA11yService.isLive());
            o.put("bin_exec", PrivacyPrefs.binExec(c));
        } catch (Exception ignored) {}
        return o;
    }

    private static boolean needControl(Context c, JSONObject o) throws Exception {
        if (!PrivacyPrefs.deviceControl(c)) {
            o.put("ok", false);
            o.put("error", "device_control OFF — enable in Nanobot Share/Settings");
            return false;
        }
        return true;
    }

    /** Launch package main activity. */
    public static JSONObject launchPackage(Context c, String pkg) {
        JSONObject o = new JSONObject();
        try {
            if (!needControl(c, o)) return o;
            if (pkg == null || pkg.isEmpty()) {
                o.put("ok", false);
                o.put("error", "empty package");
                return o;
            }
            PackageManager pm = c.getPackageManager();
            Intent i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) {
                o.put("ok", false);
                o.put("error", "no launch intent for " + pkg);
                return o;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            o.put("ok", true);
            o.put("package", pkg);
            AccessLog.record(c, "launch_pkg", pkg);
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return o;
    }

    /**
     * Start activity by action/data/component.
     * Example SMS compose: action=android.intent.action.SENDTO data=sms:+15551212 extras body=
     */
    public static JSONObject startActivity(Context c, JSONObject spec) {
        JSONObject o = new JSONObject();
        try {
            if (!needControl(c, o)) return o;
            if (spec == null) {
                o.put("ok", false);
                o.put("error", "null spec");
                return o;
            }
            Intent i = new Intent();
            String action = spec.optString("action", "");
            if (!action.isEmpty()) i.setAction(action);
            String data = spec.optString("data", "");
            if (!data.isEmpty()) i.setData(Uri.parse(data));
            String type = spec.optString("type", "");
            if (!type.isEmpty()) i.setType(type);
            String pkg = spec.optString("package", "");
            String cls = spec.optString("class", "");
            if (!pkg.isEmpty() && !cls.isEmpty()) {
                i.setComponent(new ComponentName(pkg, cls));
            } else if (!pkg.isEmpty()) {
                i.setPackage(pkg);
            }
            JSONObject extras = spec.optJSONObject("extras");
            if (extras != null) {
                JSONArray names = extras.names();
                if (names != null) {
                    for (int k = 0; k < names.length(); k++) {
                        String key = names.getString(k);
                        Object v = extras.get(key);
                        if (v instanceof Boolean) i.putExtra(key, (Boolean) v);
                        else if (v instanceof Integer) i.putExtra(key, (Integer) v);
                        else if (v instanceof Long) i.putExtra(key, (Long) v);
                        else i.putExtra(key, String.valueOf(v));
                    }
                }
            }
            // common SMS aliases
            if ("sms".equalsIgnoreCase(action) || "sms_compose".equalsIgnoreCase(action)) {
                String num = spec.optString("to", spec.optString("number", ""));
                String body = spec.optString("body", spec.optString("text", ""));
                i = new Intent(Intent.ACTION_SENDTO);
                i.setData(Uri.parse("smsto:" + num));
                if (!body.isEmpty()) i.putExtra("sms_body", body);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            o.put("ok", true);
            o.put("intent", String.valueOf(i));
            AccessLog.record(c, "start_activity", String.valueOf(i.getAction()));
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return o;
    }

    /** Open system accessibility settings for user grant. */
    public static JSONObject openA11ySettings(Context c) {
        JSONObject o = new JSONObject();
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            o.put("ok", true);
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return o;
    }

    /**
     * Exec allowlisted system/tip binary. Paths must start with /system/bin/,
     * /system/xbin/, /vendor/bin/, or /data/local/tmp/.
     */
    public static JSONObject execBinary(Context c, String path, String[] args) {
        JSONObject o = new JSONObject();
        try {
            if (!needControl(c, o)) return o;
            if (!PrivacyPrefs.binExec(c)) {
                o.put("ok", false);
                o.put("error", "bin_exec OFF");
                return o;
            }
            if (path == null || path.isEmpty()) {
                o.put("ok", false);
                o.put("error", "empty path");
                return o;
            }
            if (!pathAllowed(path)) {
                o.put("ok", false);
                o.put("error", "path not allowlisted: " + path);
                return o;
            }
            File f = new File(path);
            if (!f.isFile()) {
                o.put("ok", false);
                o.put("error", "not a file: " + path);
                return o;
            }
            // Prefer peer shell when available (SELinux tip binaries)
            if (NanobotRuntime.isPeerHttpAlive() || NanobotRuntime.isPortListening()) {
                try {
                    StringBuilder cmd = new StringBuilder();
                    cmd.append("'").append(path.replace("'", "")).append("'");
                    if (args != null) {
                        for (String a : args) {
                            if (a == null) continue;
                            cmd.append(" '").append(a.replace("'", "")).append("'");
                        }
                    }
                    JSONObject r = new PeerClient(c).shell(cmd.toString());
                    o.put("ok", r.optBoolean("ok", true));
                    o.put("via", "peer_shell");
                    o.put("result", r);
                    AccessLog.record(c, "bin_exec", path + " via=peer");
                    return o;
                } catch (Exception e) {
                    Log.w(TAG, "peer shell failed: " + e.getMessage());
                }
            }
            // Direct process
            java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
            cmd.add(path);
            if (args != null) {
                for (String a : args) if (a != null) cmd.add(a);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int n = 0;
                while ((line = br.readLine()) != null && n++ < 200) {
                    if (out.length() > 0) out.append('\n');
                    out.append(line);
                }
            }
            boolean finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            int code = finished ? p.exitValue() : -1;
            if (!finished) p.destroyForcibly();
            o.put("ok", code == 0);
            o.put("exit", code);
            o.put("output", out.toString());
            o.put("via", "direct");
            AccessLog.record(c, "bin_exec", path + " exit=" + code);
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return o;
    }

    private static boolean pathAllowed(String path) {
        String p = path.toLowerCase(Locale.US);
        return p.startsWith("/system/bin/")
            || p.startsWith("/system/xbin/")
            || p.startsWith("/vendor/bin/")
            || p.startsWith("/data/local/tmp/")
            || p.startsWith("/data/adb/modules/");
    }

    public static JSONObject a11y(Context c, String op, JSONObject args) {
        JSONObject o = new JSONObject();
        try {
            if (!needControl(c, o)) return o;
            if (!PrivacyPrefs.a11yControl(c)) {
                o.put("ok", false);
                o.put("error", "a11y_control OFF");
                return o;
            }
            NanobotA11yService svc = NanobotA11yService.get();
            if (svc == null) {
                o.put("ok", false);
                o.put("error", "Accessibility service not enabled — open settings");
                o.put("hint", DeviceOps.openA11ySettings(c));
                return o;
            }
            if (args == null) args = new JSONObject();
            if ("status".equals(op)) return svc.status();
            if ("global".equals(op)) return svc.global(args.optString("action", "back"));
            if ("click_text".equals(op)) return svc.clickText(args.optString("text", ""));
            if ("tap".equals(op)) {
                boolean ok = svc.tap((float) args.optDouble("x", 0), (float) args.optDouble("y", 0));
                o.put("ok", ok);
                return o;
            }
            if ("dump".equals(op)) return svc.dumpTree(args.optInt("max", 80));
            o.put("ok", false);
            o.put("error", "unknown a11y op: " + op);
        } catch (Exception e) {
            try { o.put("ok", false); o.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return o;
    }
}
