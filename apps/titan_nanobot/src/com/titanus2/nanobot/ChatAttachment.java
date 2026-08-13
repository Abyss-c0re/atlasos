package com.titanus2.nanobot;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Process chat attachments like ChatGPT/Claude Android UIs:
 * images → JPEG base64 for vision; documents → extracted text for the prompt.
 */
public final class ChatAttachment {
    public static final int MAX_ATTACHMENTS = 6;
    public static final int MAX_DOC_CHARS = 48_000;
    public static final int MAX_PDF_PAGES_AS_IMAGES = 3;
    public static final int MAX_TOTAL_B64 = 3_500_000;

    public enum Kind { IMAGE, DOCUMENT, UNSUPPORTED }

    public static final class Item {
        public Kind kind = Kind.UNSUPPORTED;
        public String name = "file";
        public String mime = "application/octet-stream";
        /** Vision payload (JPEG base64). */
        public String imageBase64;
        public String imageMime = "image/jpeg";
        public int width;
        public int height;
        /** Extracted document text (or summary of failure). */
        public String text;
        public String detail; // UI chip line
        public boolean ok;
        public String error;
    }

    private ChatAttachment() {}

    public static String displayName(Context c, Uri uri) {
        String name = null;
        try (Cursor cur = c.getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = cur.getString(i);
            }
        } catch (Exception ignored) {}
        if (name == null || name.isEmpty()) {
            String p = uri.getLastPathSegment();
            name = p != null ? p : "file";
        }
        return name;
    }

    public static String resolveMime(Context c, Uri uri, String name) {
        String mime = null;
        try {
            mime = c.getContentResolver().getType(uri);
        } catch (Exception ignored) {}
        if (mime == null || mime.isEmpty() || "application/octet-stream".equals(mime)) {
            String ext = "";
            int d = name.lastIndexOf('.');
            if (d >= 0) ext = name.substring(d + 1).toLowerCase(Locale.US);
            String guess = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (guess != null) mime = guess;
            else if ("md".equals(ext) || "markdown".equals(ext)) mime = "text/markdown";
            else if ("json".equals(ext)) mime = "application/json";
            else if ("csv".equals(ext)) mime = "text/csv";
            else if ("log".equals(ext)) mime = "text/plain";
            else if ("docx".equals(ext)) mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            else mime = "application/octet-stream";
        }
        return mime;
    }

    public static Item process(Context c, Uri uri) {
        Item it = new Item();
        if (c == null || uri == null) {
            it.error = "empty uri";
            return it;
        }
        it.name = displayName(c, uri);
        it.mime = resolveMime(c, uri, it.name);
        String lower = it.name.toLowerCase(Locale.US);
        String mime = it.mime.toLowerCase(Locale.US);

        try {
            if (mime.startsWith("image/") || looksImage(lower)) {
                ImageAttach.Encoded enc = ImageAttach.encodeUri(c, uri);
                it.kind = Kind.IMAGE;
                it.imageBase64 = enc.base64;
                it.imageMime = enc.mime;
                it.width = enc.width;
                it.height = enc.height;
                it.ok = true;
                it.detail = "🖼 " + it.name + " · " + enc.width + "×" + enc.height
                    + " · " + (enc.bytesJpeg / 1024) + " KB";
                return it;
            }
            if (mime.equals("application/pdf") || lower.endsWith(".pdf")) {
                return processPdf(c, uri, it);
            }
            if (mime.contains("wordprocessingml") || lower.endsWith(".docx")) {
                return processDocx(c, uri, it);
            }
            if (isTexty(mime, lower)) {
                return processText(c, uri, it);
            }
            // Unknown: try as text first, then fail clearly
            Item asText = processText(c, uri, it);
            if (asText.ok && asText.text != null && asText.text.trim().length() > 20) {
                return asText;
            }
            it.kind = Kind.UNSUPPORTED;
            it.error = "Unsupported type (" + it.mime + "). Use PDF, DOCX, text, or image.";
            it.detail = "⚠ " + it.name;
            return it;
        } catch (Exception e) {
            it.ok = false;
            it.error = e.getMessage() != null ? e.getMessage() : "process failed";
            it.detail = "⚠ " + it.name + " · " + it.error;
            return it;
        }
    }

    private static boolean looksImage(String lower) {
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
            || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp")
            || lower.endsWith(".heic") || lower.endsWith(".heif");
    }

    private static boolean isTexty(String mime, String lower) {
        if (mime.startsWith("text/")) return true;
        if (mime.contains("json") || mime.contains("xml") || mime.contains("javascript")) return true;
        String[] exts = {
            ".txt", ".md", ".markdown", ".json", ".csv", ".tsv", ".log", ".xml", ".html", ".htm",
            ".css", ".js", ".ts", ".py", ".c", ".h", ".cpp", ".java", ".kt", ".go", ".rs", ".sh",
            ".yaml", ".yml", ".toml", ".ini", ".cfg", ".conf", ".sql", ".r", ".rb", ".php"
        };
        for (String e : exts) if (lower.endsWith(e)) return true;
        return false;
    }

    private static Item processText(Context c, Uri uri, Item it) throws Exception {
        it.kind = Kind.DOCUMENT;
        byte[] raw = readBytes(c, uri, 2_000_000);
        String text = decodeText(raw);
        text = text.replace("\u0000", "");
        if (text.length() > MAX_DOC_CHARS) {
            text = text.substring(0, MAX_DOC_CHARS) + "\n…[truncated at " + MAX_DOC_CHARS + " chars]";
        }
        it.text = text;
        it.ok = text.trim().length() > 0;
        if (!it.ok) it.error = "empty text";
        it.detail = "📄 " + it.name + " · " + text.length() + " chars";
        return it;
    }

    private static Item processDocx(Context c, Uri uri, Item it) throws Exception {
        it.kind = Kind.DOCUMENT;
        StringBuilder sb = new StringBuilder();
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             ZipInputStream zis = new ZipInputStream(in)) {
            if (in == null) throw new Exception("cannot open docx");
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(e.getName())) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    int total = 0;
                    while ((n = zis.read(buf)) > 0 && total < 3_000_000) {
                        bos.write(buf, 0, n);
                        total += n;
                    }
                    String xml = bos.toString(StandardCharsets.UTF_8.name());
                    sb.append(stripXml(xml));
                    break;
                }
            }
        }
        String text = sb.toString().replaceAll("[ \\t]{2,}", " ").trim();
        if (text.length() > MAX_DOC_CHARS) {
            text = text.substring(0, MAX_DOC_CHARS) + "\n…[truncated]";
        }
        it.text = text;
        it.ok = text.length() > 0;
        if (!it.ok) it.error = "no text in DOCX";
        it.detail = "📄 " + it.name + " · DOCX · " + text.length() + " chars";
        return it;
    }

    private static Item processPdf(Context c, Uri uri, Item it) throws Exception {
        it.kind = Kind.DOCUMENT;
        ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) throw new Exception("cannot open pdf");
        StringBuilder text = new StringBuilder();
        try (PdfRenderer renderer = new PdfRenderer(pfd)) {
            int pages = renderer.getPageCount();
            int limit = Math.min(pages, MAX_PDF_PAGES_AS_IMAGES);
            // Prefer first page as vision image (often more useful than broken PDF text)
            if (pages > 0) {
                PdfRenderer.Page page = renderer.openPage(0);
                int w = Math.max(page.getWidth(), 512);
                int h = Math.max(page.getHeight(), 512);
                float scale = 1f;
                int le = Math.max(w, h);
                if (le > 1280) scale = 1280f / le;
                int tw = Math.max(32, Math.round(w * scale));
                int th = Math.max(32, Math.round(h * scale));
                Bitmap bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                page.close();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos);
                bmp.recycle();
                byte[] jpeg = bos.toByteArray();
                it.imageBase64 = Base64.encodeToString(jpeg, Base64.NO_WRAP);
                it.imageMime = "image/jpeg";
                it.width = tw;
                it.height = th;
                // also mark as dual: document + image
                it.kind = Kind.IMAGE; // send as vision; add text note about pages
                it.text = "(PDF \"" + it.name + "\", " + pages + " page(s); first page rendered as image"
                    + (pages > 1 ? "; remaining pages not attached" : "") + ")";
                it.ok = true;
                it.detail = "📕 " + it.name + " · PDF " + pages + "p · page1 image "
                    + tw + "×" + th;
                // free remaining pages unused
                return it;
            }
            text.append("(empty PDF)");
        } finally {
            try { pfd.close(); } catch (Exception ignored) {}
        }
        it.text = text.toString();
        it.ok = true;
        it.detail = "📕 " + it.name + " · PDF";
        return it;
    }

    private static String stripXml(String xml) {
        if (xml == null) return "";
        // rough: tags → space, collapse whitespace
        String t = xml.replaceAll("(?s)<w:tab[^/]*/>", "\t");
        t = t.replaceAll("(?s)</w:p>", "\n");
        t = t.replaceAll("(?s)<[^>]+>", " ");
        t = t.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .replace("&quot;", "\"").replace("&apos;", "'");
        t = t.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        t = t.replaceAll(" *\\n *", "\n");
        return t.trim();
    }

    private static byte[] readBytes(Context c, Uri uri, int max) throws Exception {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("cannot open");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0 && total < max) {
                int take = Math.min(n, max - total);
                bos.write(buf, 0, take);
                total += take;
            }
            return bos.toByteArray();
        }
    }

    private static String decodeText(byte[] raw) {
        // skip UTF-8 BOM
        int off = 0;
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF)
            off = 3;
        // try UTF-8, fallback ISO-8859-1
        String s = new String(raw, off, raw.length - off, StandardCharsets.UTF_8);
        if (s.indexOf('\uFFFD') >= 0 && raw.length - off < 500_000) {
            s = new String(raw, off, raw.length - off, Charset.forName("ISO-8859-1"));
        }
        return s;
    }

    /** Build user message text: caption + document bodies. */
    public static String buildPrompt(String caption, List<Item> items) {
        StringBuilder sb = new StringBuilder();
        List<Item> docs = new ArrayList<>();
        int images = 0;
        if (items != null) {
            for (Item it : items) {
                if (it == null || !it.ok) continue;
                if (it.imageBase64 != null && !it.imageBase64.isEmpty()) images++;
                if (it.text != null && !it.text.isEmpty()
                    && (it.kind == Kind.DOCUMENT
                        || (it.kind == Kind.IMAGE && it.text.startsWith("(PDF")))) {
                    docs.add(it);
                }
            }
        }
        if (!docs.isEmpty()) {
            sb.append("Attached documents (").append(docs.size()).append("):\n");
            for (Item it : docs) {
                sb.append("\n===== ").append(it.name).append(" (").append(it.mime).append(") =====\n");
                sb.append(it.text).append("\n");
            }
            sb.append("\n");
        }
        if (images > 0) {
            sb.append("(").append(images).append(" image(s) attached for vision)\n");
        }
        String cap = caption != null ? caption.trim() : "";
        if (cap.isEmpty()) {
            if (images > 0 && docs.isEmpty()) cap = "What is in this image?";
            else if (!docs.isEmpty()) cap = "Please summarize and answer based on the attached files.";
            else cap = "";
        }
        if (!cap.isEmpty()) {
            if (sb.length() > 0) sb.append("User request:\n");
            sb.append(cap);
        }
        return sb.toString().trim();
    }

    /** JSON array of vision images for peer. */
    public static JSONArray imagesJson(List<Item> items) {
        JSONArray arr = new JSONArray();
        long total = 0;
        if (items == null) return arr;
        for (Item it : items) {
            if (it == null || it.imageBase64 == null || it.imageBase64.isEmpty()) continue;
            if (total + it.imageBase64.length() > MAX_TOTAL_B64) break;
            try {
                JSONObject o = new JSONObject();
                o.put("base64", it.imageBase64);
                o.put("mime", it.imageMime != null ? it.imageMime : "image/jpeg");
                o.put("name", it.name);
                arr.put(o);
                total += it.imageBase64.length();
            } catch (Exception ignored) {}
        }
        return arr;
    }
}
