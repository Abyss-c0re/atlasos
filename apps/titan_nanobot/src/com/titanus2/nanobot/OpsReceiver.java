package com.titanus2.nanobot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

/**
 * Host/shell bridge: am broadcast -a com.titanus2.nanobot.OPS --es op ...
 * Writes result JSON to /data/local/tmp/nanobot_home/ops_last.json when possible.
 */
public class OpsReceiver extends BroadcastReceiver {
    private static final String TAG = "NanobotOpsRx";
    public static final String ACTION = "com.titanus2.nanobot.OPS";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        String op = intent.getStringExtra("op");
        if (op == null) op = "";
        JSONObject r = new JSONObject();
        try {
            switch (op) {
                case "gate":
                    r = DeviceOps.gate(context);
                    break;
                case "launch":
                    r = DeviceOps.launchPackage(context, intent.getStringExtra("package"));
                    break;
                case "sms_compose": {
                    JSONObject spec = new JSONObject();
                    spec.put("action", "sms_compose");
                    spec.put("to", intent.getStringExtra("to"));
                    spec.put("body", intent.getStringExtra("body"));
                    r = DeviceOps.startActivity(context, spec);
                    break;
                }
                case "a11y": {
                    JSONObject args = new JSONObject();
                    if (intent.hasExtra("text")) args.put("text", intent.getStringExtra("text"));
                    if (intent.hasExtra("action")) args.put("action", intent.getStringExtra("action"));
                    if (intent.hasExtra("x")) args.put("x", intent.getFloatExtra("x", 0f));
                    if (intent.hasExtra("y")) args.put("y", intent.getFloatExtra("y", 0f));
                    String aop = intent.getStringExtra("aop");
                    if (aop == null) aop = "status";
                    r = DeviceOps.a11y(context, aop, args);
                    break;
                }
                case "depth":
                    r = DepthScan.inventory(context);
                    break;
                case "probe":
                    r = DepthScan.runProbeBinary(context);
                    break;
                case "exec": {
                    String path = intent.getStringExtra("path");
                    String arg0 = intent.getStringExtra("arg0");
                    String arg1 = intent.getStringExtra("arg1");
                    java.util.ArrayList<String> args = new java.util.ArrayList<>();
                    if (arg0 != null) args.add(arg0);
                    if (arg1 != null) args.add(arg1);
                    r = DeviceOps.execBinary(context, path,
                        args.isEmpty() ? null : args.toArray(new String[0]));
                    break;
                }
                default:
                    r.put("ok", false);
                    r.put("error", "unknown op: " + op);
                    r.put("ops", "gate|launch|sms_compose|a11y|depth|probe|exec");
            }
            // best-effort result file for host pull
            try {
                java.io.File f = new java.io.File(NanobotRuntime.SHARED_HOME, "ops_last.json");
                //noinspection ResultOfMethodCallIgnored
                f.getParentFile().mkdirs();
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                    fos.write(r.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {}
            Log.i(TAG, "op=" + op + " → " + r.optBoolean("ok", false));
        } catch (Exception e) {
            Log.e(TAG, "ops failed: " + e.getMessage());
        }
    }
}
