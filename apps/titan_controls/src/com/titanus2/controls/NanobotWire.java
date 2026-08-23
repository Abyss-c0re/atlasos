package com.titanus2.controls;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Thin loopback client to the on-device nanobot peer (same port as TitanNanobot). */
public final class NanobotWire {
    private static final String TAG = "NanobotWire";
    public static final String PEER = "http://127.0.0.1:8787";

    private NanobotWire() {}

    public static boolean peerUp() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(PEER + "/peer/v1/health").openConnection();
            c.setConnectTimeout(400);
            c.setReadTimeout(600);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public static String token() {
        for (String p : new String[]{
            "/data/local/tmp/nanobot_home/peer_token",
            "/data/adb/titan2/peer_token"
        }) {
            File f = new File(p);
            if (!f.isFile()) continue;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line = br.readLine();
                if (line != null && !line.isEmpty()) {
                    if (line.startsWith("token=")) line = line.substring(6);
                    return line.trim();
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    /** Queue a short sanitized report on the peer so host nanobot can start. */
    public static boolean queueReport(String title, String kind, String comment) {
        if (!peerUp()) return false;
        HttpURLConnection c = null;
        try {
            JSONObject body = new JSONObject();
            String prompt = "AtlasOS maintainer queue. User report (" + kind
                + "): " + title + ". Comment: " + (comment == null ? "" : comment)
                + ". Plan a fix in AtlasOS. No user PII.";
            body.put("prompt", prompt);
            byte[] raw = body.toString().getBytes(StandardCharsets.UTF_8);
            c = (HttpURLConnection) new URL(PEER + "/peer/v1/prompt").openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(4000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            String tok = token();
            if (!tok.isEmpty()) c.setRequestProperty("X-Nanobot-Peer-Token", tok);
            try (OutputStream os = c.getOutputStream()) {
                os.write(raw);
            }
            int code = c.getResponseCode();
            // drain
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                        code >= 400 ? c.getErrorStream() : c.getInputStream(),
                        StandardCharsets.UTF_8))) {
                while (br.readLine() != null) { /* drain */ }
            } catch (Exception ignored) {}
            Log.i(TAG, "queueReport code=" + code);
            return code >= 200 && code < 300;
        } catch (Exception e) {
            Log.w(TAG, "queueReport", e);
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public static org.json.JSONObject pairReceipt() {
        for (String p : new String[]{
            "/data/local/tmp/nanobot_home/pair.json",
            "/data/local/tmp/titan2_nanobot_pair.json",
            "/data/misc/titan2/titan2_nanobot_pair.json"
        }) {
            File f = new File(p);
            if (!f.isFile()) continue;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                if (sb.length() > 0) return new JSONObject(sb.toString());
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Tell on-device Nanobot to finish host-pair after Atlas grant cookie. */
    public static boolean requestPairSync(android.content.Context ctx) {
        if (ctx == null) return false;
        try {
            android.content.Intent i = new android.content.Intent("com.titanus2.nanobot.OPS");
            i.setPackage("com.titanus2.nanobot");
            i.putExtra("op", "pair_sync");
            ctx.getApplicationContext().sendBroadcast(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "requestPairSync", e);
            return false;
        }
    }

}
