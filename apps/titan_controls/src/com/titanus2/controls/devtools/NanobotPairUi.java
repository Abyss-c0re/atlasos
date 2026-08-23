package com.titanus2.controls.devtools;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.titanus2.api.AtlasAuthPlane;
import com.titanus2.controls.NanobotWire;
import com.titanus2.controls.ui.UiKit;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Nanobot auth button: Atlas grant then sync host-pair on this device. */
public final class NanobotPairUi {
    private final Activity act;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView status;
    private boolean busy;

    public NanobotPairUi(Activity act) {
        this.act = act;
    }

    public void build(LinearLayout root) {
        UiKit.section(root, "Nanobot");
        UiKit.button(root, "Nanobot auth", this::userWantsSync);
        status = UiKit.mono(root);
        refresh();
    }

    public void onResumeTick() {
        if (!busy) refresh();
    }

    private void userWantsSync() {
        if (busy) {
            UiKit.toast(act, "Nanobot auth running");
            return;
        }
        busy = true;
        if (status != null) status.setText("Asking Atlas…");
        new Thread(() -> {
            AtlasAuthPlane.Result r = AtlasAuthPlane.request(
                act, "nanobot", "Sync nanobot pair", "pair",
                AtlasAuthPlane.DEFAULT_TIMEOUT_SEC);
            if (!r.ok) {
                h.post(() -> {
                    busy = false;
                    if (status != null) status.setText("denied via=" + r.via + " " + r.error);
                    UiKit.toast(act, "Atlas denied nanobot pair");
                });
                return;
            }
            writeGrant();
            boolean sent = NanobotWire.requestPairSync(act);
            if (!sent) writeStub(r.via);
            // give OpsReceiver a moment
            try { Thread.sleep(700); } catch (InterruptedException ignored) {}
            JSONObject rec = NanobotWire.pairReceipt();
            h.post(() -> {
                busy = false;
                refresh();
                if (rec != null && rec.optBoolean("ok", false)) {
                    UiKit.toast(act, "Nanobot pair ready");
                } else if (sent) {
                    UiKit.toast(act, "Atlas ok — starting agent");
                } else {
                    UiKit.toast(act, "Atlas ok — open Nanobot if peer is down");
                }
            });
        }, "nb-pair").start();
    }

    private void refresh() {
        if (status == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("plane=").append(AtlasAuthPlane.planeReady() ? "ready" : "missing");
        sb.append("  peer=").append(NanobotWire.peerUp() ? "up" : "down");
        JSONObject rec = NanobotWire.pairReceipt();
        if (rec != null) {
            sb.append("\nreceipt ok=").append(rec.optBoolean("ok", false));
            String via = rec.optString("via", rec.optString("receipt_via", ""));
            if (!via.isEmpty()) sb.append(" via=").append(via);
            String url = rec.optString("url", "");
            if (!url.isEmpty()) sb.append("\n").append(url);
            String fp = rec.optString("token_fp", "");
            if (!fp.isEmpty()) sb.append("\nfp=").append(fp);
        } else {
            sb.append("\nno pair receipt — tap Nanobot auth");
        }
        status.setText(sb.toString());
    }

    private static void writeGrant() {
        write("/data/local/tmp/nanobot_home/pair_grant",
            String.valueOf(System.currentTimeMillis()));
    }

    private static void writeStub(String via) {
        String body = "{\"ok\":true,\"schema\":\"nanobot.pair.v1\","
            + "\"via\":" + jsonStr(via)
            + ",\"note\":\"controls-grant\"}";
        write("/data/local/tmp/titan2_nanobot_pair.json", body);
        write("/data/local/tmp/nanobot_home/pair.json", body);
    }

    private static String jsonStr(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void write(String path, String body) {
        try {
            File f = new File(path);
            File p = f.getParentFile();
            if (p != null) p.mkdirs();
            try (FileOutputStream o = new FileOutputStream(f, false)) {
                o.write((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
            }
            f.setReadable(true, false);
        } catch (Exception ignored) {}
    }
}
