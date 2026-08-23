package com.titanus2.controls;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Persist a user-authored report for Cube Flasher / nanobot. Not gallery dump. */
public final class ReportStore {
    public static final String OS_DIR = AgentBridge.OS_CTRL + "/reports";
    public static final String TMP_DIR = "/data/local/tmp/titan2_reports";

    private ReportStore() {}

    public static File appDir(Context c) {
        File d = new File(c.getFilesDir(), "reports");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static String newId() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    public static File openDir(Context c, String id) {
        File d = new File(appDir(c), id);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        new File(d, "logs").mkdirs();
        new File(d, "shots").mkdirs();
        return d;
    }

    public static void writeText(File f, String body) {
        try {
            File p = f.getParentFile();
            if (p != null) //noinspection ResultOfMethodCallIgnored
                p.mkdirs();
            try (FileOutputStream o = new FileOutputStream(f, false)) {
                o.write((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    public static void writeJson(File dir, JSONObject obj) {
        writeText(new File(dir, "report.json"), obj.toString());
    }

    public static void copyStream(InputStream in, File dest) throws Exception {
        File p = dest.getParentFile();
        if (p != null) //noinspection ResultOfMethodCallIgnored
            p.mkdirs();
        try (FileOutputStream o = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    public static void mirrorOs(File srcDir) {
        if (srcDir == null || !srcDir.isDirectory()) return;
        for (String root : new String[]{OS_DIR, TMP_DIR}) {
            try {
                File dest = new File(root, srcDir.getName());
                copyTree(srcDir, dest);
            } catch (Exception ignored) {}
        }
    }

    private static void copyTree(File from, File to) {
        if (from.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            to.mkdirs();
            File[] kids = from.listFiles();
            if (kids == null) return;
            for (File k : kids) copyTree(k, new File(to, k.getName()));
            return;
        }
        try (java.io.FileInputStream in = new java.io.FileInputStream(from);
             FileOutputStream o = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        } catch (Exception ignored) {}
    }

    public static JSONObject meta(String id, String kind, String title, String comment,
                                  JSONArray logs, JSONArray shots, String version) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("kind", kind);
            o.put("title", title);
            o.put("comment", comment);
            o.put("logs", logs);
            o.put("shots", shots);
            o.put("version", version);
            o.put("schema", "titan.report.v1");
        } catch (Exception ignored) {}
        return o;
    }
}
