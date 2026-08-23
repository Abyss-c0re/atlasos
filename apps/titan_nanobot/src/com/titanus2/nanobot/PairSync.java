package com.titanus2.nanobot;

import android.content.Context;
import android.content.Intent;

import com.titanus2.api.AtlasAuthPlane;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Atlas-gated sync of this device nanobot with the host pair. Receipt is URL + fingerprint only. */
public final class PairSync {
    public static final String CLIENT_NAME = "host-pair";
    public static final String RECEIPT_HOME = NanobotRuntime.SHARED_HOME + "/pair.json";
    public static final String RECEIPT_TMP = "/data/local/tmp/titan2_nanobot_pair.json";
    public static final String RECEIPT_MISC = "/data/misc/titan2/titan2_nanobot_pair.json";
    public static final String GRANT = NanobotRuntime.SHARED_HOME + "/pair_grant";
    private static final long GRANT_TTL_MS = 90_000L;

    private PairSync() {}

    public static JSONObject status(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("schema", "nanobot.pair.v1");
            o.put("atlas_auth", PrivacyPrefs.atlasAuth(c));
            o.put("plane_ready", AtlasAuthPlane.planeReady());
            o.put("service", PrivacyPrefs.serviceEnabled(c));
            o.put("share_lan", PrivacyPrefs.shareLan(c));
            o.put("peer_up", NanobotRuntime.isPortListening());
            o.put("host", lanIp());
            o.put("port", NanobotRuntime.PORT);
            o.put("url", "http://" + lanIp() + ":" + NanobotRuntime.PORT);
            o.put("client", existingClientId(c));
            JSONObject rec = readReceipt();
            if (rec != null) {
                o.put("receipt_ts", rec.optString("ts", ""));
                o.put("receipt_via", rec.optString("via", ""));
                o.put("token_fp", rec.optString("token_fp", ""));
                o.put("ok", rec.optBoolean("ok", false));
            } else {
                o.put("ok", false);
            }
        } catch (Exception ignored) {}
        return o;
    }

    public static JSONObject sync(Context c, boolean askAtlas) {
        JSONObject dest = new JSONObject();
        try {
            String via = "";
            if (askAtlas) {
                AtlasAuthPlane.Result r = AtlasAuthPlane.request(
                    c, "nanobot", "Sync nanobot pair", "pair",
                    AtlasAuthPlane.DEFAULT_TIMEOUT_SEC);
                if (!r.ok) {
                    dest.put("ok", false);
                    dest.put("error", "atlas-auth: " + r.error);
                    dest.put("via", r.via);
                    writeReceipt(dest);
                    return dest;
                }
                via = r.via;
            } else if (!consumeGrant()) {
                dest.put("ok", false);
                dest.put("error", "no atlas grant");
                dest.put("via", "deny");
                return dest;
            } else {
                via = "grant";
            }

            PrivacyPrefs.setAtlasAuth(c, true);
            PrivacyPrefs.setServiceEnabled(c, true);
            PrivacyPrefs.setShareLan(c, true);
            PrivacyPrefs.setAllowNetworkAgents(c, true);
            PrivacyPrefs.publishAll(c);
            startAgent(c);

            String clientId = existingClientId(c);
            if (clientId.isEmpty()) {
                McpClients.Client cl = McpClients.approve(c, CLIENT_NAME);
                clientId = cl.id;
            }

            String tok = NanobotRuntime.readPeerToken(c);
            if (tok != null) SecureStore.cachePeerToken(c, tok);

            dest.put("ok", true);
            dest.put("schema", "nanobot.pair.v1");
            dest.put("ts", utcNow());
            dest.put("via", via);
            dest.put("host", lanIp());
            dest.put("port", NanobotRuntime.PORT);
            dest.put("url", "http://" + lanIp() + ":" + NanobotRuntime.PORT);
            dest.put("peer_up", NanobotRuntime.isPortListening());
            dest.put("client", CLIENT_NAME);
            dest.put("client_id", clientId);
            dest.put("token_fp", tokenFp(tok));
            dest.put("atlas_auth", true);
            writeReceipt(dest);
            AccessLog.record(c, "nanobot_pair_sync",
                "host-pair via=" + via + " fp=" + dest.optString("token_fp"));
        } catch (Exception e) {
            try {
                dest.put("ok", false);
                dest.put("error", e.getMessage() != null ? e.getMessage() : "sync failed");
            } catch (Exception ignored) {}
        }
        return dest;
    }

    public static void writeGrant() {
        writeFile(new File(GRANT), String.valueOf(System.currentTimeMillis()));
    }

    public static boolean consumeGrant() {
        File f = new File(GRANT);
        if (!f.isFile()) return false;
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            long t = Long.parseLong(new String(b, StandardCharsets.UTF_8).trim());
            f.delete();
            return t > 0 && (System.currentTimeMillis() - t) < GRANT_TTL_MS;
        } catch (Exception e) {
            f.delete();
            return false;
        }
    }

    public static JSONObject readReceipt() {
        for (String p : new String[]{RECEIPT_HOME, RECEIPT_TMP, RECEIPT_MISC}) {
            File f = new File(p);
            if (!f.isFile()) continue;
            try {
                byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                return new JSONObject(new String(b, StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static void writeReceipt(JSONObject o) {
        String body = o.toString();
        writeFile(new File(RECEIPT_HOME), body);
        writeFile(new File(RECEIPT_TMP), body);
        writeFile(new File(RECEIPT_MISC), body);
    }

    private static void writeFile(File f, String body) {
        try {
            File p = f.getParentFile();
            if (p != null) p.mkdirs();
            try (FileOutputStream o = new FileOutputStream(f, false)) {
                o.write((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
            }
            f.setReadable(true, false);
        } catch (Exception ignored) {}
    }

    private static void startAgent(Context c) {
        try {
            Intent svc = new Intent(c, NanobotService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(svc);
            else c.startService(svc);
        } catch (Exception ignored) {}
        try {
            if (!NanobotRuntime.isPortListening()) NanobotRuntime.startPeer(c);
        } catch (Exception ignored) {}
    }

    private static String existingClientId(Context c) {
        try {
            for (McpClients.Client cl : McpClients.list(c)) {
                if (cl.active && CLIENT_NAME.equals(cl.name)) return cl.id != null ? cl.id : "";
            }
        } catch (Exception ignored) {}
        return "";
    }

    static String lanIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress())
                        return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }

    static String tokenFp(String tok) {
        if (tok == null || tok.isEmpty()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(tok.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format(Locale.US, "%02x", d[i] & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String utcNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }
}
