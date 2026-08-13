package com.titanus2.nanobot;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Inventory Camera2 streams for mapping (RGB + any DEPTH_OUTPUT).
 * Quest 3: dense depth is often OpenXR-only — we record honest inventory and
 * write under nanobot_home/scan/ for host reconstruction.
 */
public final class DepthScan {
    private DepthScan() {}

    public static File scanDir(Context c) {
        File d = new File(NanobotRuntime.SHARED_HOME, "scan");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static JSONObject inventory(Context c) {
        JSONObject root = new JSONObject();
        try {
            CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            JSONArray cams = new JSONArray();
            boolean anyDepth = false;
            if (cm != null) {
                for (String id : cm.getCameraIdList()) {
                    JSONObject cam = new JSONObject();
                    cam.put("id", id);
                    CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                    Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                    cam.put("facing", facing == null ? -1 : facing);
                    float[] f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    if (f != null && f.length > 0) cam.put("focal_mm", f[0]);
                    StreamConfigurationMap map =
                        ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    JSONArray outs = new JSONArray();
                    if (map != null) {
                        int[] fmts = map.getOutputFormats();
                        if (fmts != null) {
                            for (int fmt : fmts) {
                                JSONObject fo = new JSONObject();
                                fo.put("format", fmt);
                                fo.put("name", formatName(fmt));
                                boolean depth = fmt == ImageFormat.DEPTH16
                                    || fmt == ImageFormat.DEPTH_POINT_CLOUD
                                    || (android.os.Build.VERSION.SDK_INT >= 23
                                        && fmt == ImageFormat.DEPTH_JPEG);
                                fo.put("depth", depth);
                                if (depth) anyDepth = true;
                                Size[] sizes = map.getOutputSizes(fmt);
                                JSONArray sa = new JSONArray();
                                if (sizes != null) {
                                    int n = Math.min(sizes.length, 12);
                                    for (int i = 0; i < n; i++) {
                                        sa.put(sizes[i].getWidth() + "x" + sizes[i].getHeight());
                                    }
                                }
                                fo.put("sizes", sa);
                                outs.put(fo);
                            }
                        }
                    }
                    cam.put("outputs", outs);
                    cams.put(cam);
                }
            }
            root.put("ok", true);
            root.put("cameras", cams);
            root.put("any_camera2_depth", anyDepth);
            root.put("model", android.os.Build.MODEL);
            root.put("device", android.os.Build.DEVICE);
            root.put("note", anyDepth
                ? "Camera2 depth stream(s) present — capture path can use DEPTH16/POINT_CLOUD"
                : "No Camera2 DEPTH_* streams — Quest metric depth usually needs OpenXR; "
                    + "use RGB multi-view + IMU + quest_sensor_probe for mapping");
            // persist
            File dir = scanDir(c);
            String ts = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).format(new Date());
            File out = new File(dir, "camera2_inventory_" + ts + ".json");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            File latest = new File(dir, "LATEST_CAMERA2.json");
            try (FileOutputStream fos = new FileOutputStream(latest)) {
                fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            root.put("wrote", out.getAbsolutePath());
            AccessLog.record(c, "depth_inventory", "cams=" + cams.length() + " depth=" + anyDepth);
        } catch (Exception e) {
            try {
                root.put("ok", false);
                root.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return root;
    }

    private static String formatName(int fmt) {
        switch (fmt) {
            case ImageFormat.YUV_420_888: return "YUV_420_888";
            case ImageFormat.JPEG: return "JPEG";
            case ImageFormat.RAW_SENSOR: return "RAW_SENSOR";
            case ImageFormat.DEPTH16: return "DEPTH16";
            case ImageFormat.DEPTH_POINT_CLOUD: return "DEPTH_POINT_CLOUD";
            case ImageFormat.PRIVATE: return "PRIVATE";
            default:
                if (android.os.Build.VERSION.SDK_INT >= 23 && fmt == ImageFormat.DEPTH_JPEG)
                    return "DEPTH_JPEG";
                return "fmt_" + fmt;
        }
    }

    /** Run shell quest_sensor_probe if present (Quest Magisk tip). */
    public static JSONObject runProbeBinary(Context c) {
        String[] candidates = {
            "/data/local/tmp/quest_sensor_probe",
            "/system/bin/quest_sensor_probe",
            "/data/adb/modules/titan2_nanobot/system/bin/quest_sensor_probe",
        };
        for (String p : candidates) {
            if (new File(p).isFile()) {
                return DeviceOps.execBinary(c, p, new String[]{scanDir(c).getAbsolutePath()});
            }
        }
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", "quest_sensor_probe not installed — push tip or Magisk module");
            // still write camera2 inventory
            o.put("camera2", inventory(c));
        } catch (Exception ignored) {}
        return o;
    }
}
