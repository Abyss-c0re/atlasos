package com.titanus2.nanobot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Load gallery/camera URI → resized JPEG → base64 for vision models (Grok OpenAI-compat).
 */
public final class ImageAttach {
    /** Max long edge (px). Keeps base64 under ~1–1.5MB typically. */
    public static final int MAX_EDGE = 1280;
    /** Grok rejects images below 512 total pixels; keep a safe floor. */
    public static final int MIN_EDGE = 32;
    public static final int JPEG_QUALITY = 78;
    /** Reject if base64 longer than this (peer also caps ~2.5MB). */
    public static final int MAX_B64_CHARS = 2_000_000;

    public static final class Encoded {
        public final String base64;
        public final String mime;
        public final int width;
        public final int height;
        public final int bytesJpeg;

        Encoded(String b64, String mime, int w, int h, int n) {
            this.base64 = b64;
            this.mime = mime;
            this.width = w;
            this.height = h;
            this.bytesJpeg = n;
        }
    }

    private ImageAttach() {}

    public static Encoded encodeUri(Context c, Uri uri) throws Exception {
        if (c == null || uri == null) throw new Exception("no image");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("cannot open image");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) throw new Exception("invalid image dimensions");

        int sample = 1;
        int longEdge = Math.max(w, h);
        while (longEdge / sample > MAX_EDGE * 2) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap raw;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("cannot open image");
            raw = BitmapFactory.decodeStream(in, null, opts);
        }
        if (raw == null) throw new Exception("decode failed");

        int rw = raw.getWidth();
        int rh = raw.getHeight();
        float scale = 1f;
        int le = Math.max(rw, rh);
        int se = Math.min(rw, rh);
        if (le > MAX_EDGE) scale = (float) MAX_EDGE / (float) le;
        // Upscale tiny images so vision APIs accept them (≥512 pixels)
        if (se * scale < MIN_EDGE) {
            scale = (float) MIN_EDGE / (float) Math.max(1, se);
        }
        if (rw * scale * rh * scale < 512f) {
            scale = (float) Math.sqrt(600.0 / Math.max(1, rw * rh));
        }
        int tw = Math.max(MIN_EDGE, Math.round(rw * scale));
        int th = Math.max(MIN_EDGE, Math.round(rh * scale));
        Bitmap scaled = raw;
        if (tw != rw || th != rh) {
            scaled = Bitmap.createScaledBitmap(raw, tw, th, true);
            if (scaled != raw) raw.recycle();
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)) {
            scaled.recycle();
            throw new Exception("JPEG compress failed");
        }
        scaled.recycle();
        byte[] jpeg = bos.toByteArray();
        String b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP);
        if (b64.length() > MAX_B64_CHARS) {
            throw new Exception("image still too large after resize (" + b64.length() + " b64 chars)");
        }
        return new Encoded(b64, "image/jpeg", tw, th, jpeg.length);
    }
}
