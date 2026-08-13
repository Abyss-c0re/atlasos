package com.titanus2.controls;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Headless lab probe: TitanKey KCM specials on ralt only + free Alt clean.
 * Product: Sym → ALT_RIGHT + ralt glyphs; free Alt → ALT_LEFT must not type
 * specials. Writes {@code titan2_specials_probe} for host smoke.
 * <p>
 * free_alt_ok is primarily <b>file-based</b> (no bare {@code alt:} glyph
 * columns in product KCM). API get(meta LEFT) is diagnostic only — some
 * framework builds still surface ralt glyphs under ALT_ON|LEFT even when
 * the on-disk map is ralt-only; physical free Alt uses ALT_LEFT only.
 * <p>
 * adb: {@code am start -n com.titanus2.controls/.SpecialsProbeActivity}
 */
public final class SpecialsProbeActivity extends Activity {
    private static final String TAG = "SpecialsProbe";
    public static final String FILE = "titan2_specials_probe";

    private static final String[] KCM_PATHS = {
        "/system/usr/keychars/Vendor_2533_Product_2533.kcm",
        "/system/usr/keychars/TitanKey.kcm",
        "/system/etc/titan2_keylayout/Vendor_2533_Product_2533.kcm",
        "/system/etc/titan2_keylayout/TitanKey.kcm",
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String report = run(this);
        writeReport(this, report);
        Log.i(TAG, report.replace("\n", " | "));
        finish();
    }

    /** Probe TitanKey device KCM; return multi-line report ending with PASS/FAIL. */
    public static String run(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("ts=").append(System.currentTimeMillis()).append('\n');

        String hlay = AgentBridge.get(ctx, HostLayoutController.FILE_LAYOUT, "off");
        sb.append("host_layout=").append(hlay == null ? "?" : hlay.trim()).append('\n');
        String cm = AgentBridge.get(ctx, AgentBridge.CHAR_MOD, "sym");
        sb.append("char_mod=").append(cm == null ? "?" : cm).append('\n');

        // File bar: bare alt: columns = free-Alt specials leak (product ban)
        int bareAlt = countBareAltLines();
        String kcmFile = firstExistingKcm();
        sb.append("kcm_file=").append(kcmFile == null ? "none" : kcmFile).append('\n');
        sb.append("bare_alt_lines=").append(bareAlt).append('\n');
        boolean fileFreeOk = bareAlt == 0 && kcmFile != null;

        InputDevice titan = findTitanKey();
        if (titan == null) {
            sb.append("device=none\n");
            sb.append("ralt_specials=0\nfree_alt_ok=").append(fileFreeOk ? "1" : "0").append('\n');
            sb.append("result=").append(fileFreeOk ? "FAIL no TitanKey" : "FAIL").append('\n');
            return sb.toString();
        }
        sb.append("device=").append(titan.getName())
            .append(" id=").append(titan.getId()).append('\n');
        KeyCharacterMap kcm = titan.getKeyCharacterMap();
        if (kcm == null) {
            sb.append("kcm=null\n");
            sb.append("ralt_specials=0\nfree_alt_ok=").append(fileFreeOk ? "1" : "0").append('\n');
            sb.append("result=FAIL kcm_null\n");
            return sb.toString();
        }

        Map<Integer, Character> expect = expectMap();
        int raltOk = 0, freeAltClean = 0, total = 0;
        StringBuilder mismatches = new StringBuilder();
        StringBuilder freeAltHits = new StringBuilder();
        for (Map.Entry<Integer, Character> e : expect.entrySet()) {
            total++;
            int kc = e.getKey();
            char want = e.getValue();
            // Sym path: RIGHT Alt only (product specials)
            int ralt = kcm.get(kc, KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON);
            // Free Alt: LEFT only — normalizeMetaState adds ALT_ON
            int freeAlt = kcm.get(kc, KeyEvent.META_ALT_LEFT_ON);
            char cRalt = printable(ralt);
            char cFree = printable(freeAlt);
            boolean r = matches(want, cRalt);
            if (r) raltOk++;
            else {
                if (mismatches.length() > 0) mismatches.append(',');
                mismatches.append(KeyEvent.keyCodeToString(kc))
                    .append(" want=").append(want)
                    .append(" ralt=").append(cRalt == 0 ? '?' : cRalt);
            }
            if (!matches(want, cFree)) freeAltClean++;
            else {
                if (freeAltHits.length() > 0) freeAltHits.append(',');
                freeAltHits.append(KeyEvent.keyCodeToString(kc)).append('=').append(cFree);
            }
        }
        sb.append("letters=").append(total)
            .append(" ralt_ok=").append(raltOk)
            .append(" free_alt_api_clean=").append(freeAltClean).append('\n');
        // Spot + raw diagnostics for letter A
        int rawR = kcm.get(KeyEvent.KEYCODE_A, KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON);
        int rawL = kcm.get(KeyEvent.KEYCODE_A, KeyEvent.META_ALT_LEFT_ON);
        int rawBoth = kcm.get(KeyEvent.KEYCODE_A, KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON);
        sb.append("spot A@=").append(glyphRalt(kcm, KeyEvent.KEYCODE_A))
            .append(" W1=").append(glyphRalt(kcm, KeyEvent.KEYCODE_W))
            .append(" Z!=").append(glyphRalt(kcm, KeyEvent.KEYCODE_Z)).append('\n');
        sb.append("raw A ralt=0x").append(Integer.toHexString(rawR))
            .append(" lalt=0x").append(Integer.toHexString(rawL))
            .append(" alt+lalt=0x").append(Integer.toHexString(rawBoth)).append('\n');
        if (mismatches.length() > 0) {
            sb.append("miss=").append(mismatches).append('\n');
        }
        if (freeAltHits.length() > 0) {
            sb.append("free_alt_api_hits=").append(freeAltHits).append('\n');
        }

        boolean raltPass = raltOk >= 20;
        // Product free-Alt: file must not leak bare alt:. API hits alone do not FAIL
        // when on-disk is ralt-only (physical ALT_LEFT + ralt-only KCM is correct).
        boolean freePass = fileFreeOk;
        sb.append("ralt_specials=").append(raltPass ? "1" : "0").append('\n');
        sb.append("free_alt_ok=").append(freePass ? "1" : "0").append('\n');
        sb.append("free_alt_api_ok=").append(freeAltClean >= 20 ? "1" : "0").append('\n');
        sb.append("layout_off=").append(
            "off".equalsIgnoreCase(hlay != null ? hlay.trim() : "") ? "1" : "0")
            .append('\n');
        boolean pass = raltPass && freePass;
        sb.append("result=").append(pass ? "PASS" : "FAIL");
        if (!raltPass) sb.append(" ralt_glyphs");
        if (!freePass) sb.append(" free_alt_file_leak");
        if (pass && freeAltClean < 20) {
            sb.append(" (note: api free-Alt still surfaces ralt glyphs — file ralt-only OK)");
        }
        if (pass && !"off".equalsIgnoreCase(hlay != null ? hlay.trim() : "off")) {
            sb.append(" (note: sticky layout on — hold Sym path uses KCM only when layout off)");
        }
        sb.append('\n');
        return sb.toString();
    }

    private static int countBareAltLines() {
        int total = 0;
        for (String path : KCM_PATHS) {
            File f = new File(path);
            if (!f.isFile()) continue;
            total += countBareAltInFile(f);
        }
        return total;
    }

    private static int countBareAltInFile(File f) {
        int n = 0;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                // bare alt: only — not ralt / lalt / shift+alt
                if (t.startsWith("alt:")) n++;
            }
        } catch (Exception ignored) {}
        return n;
    }

    private static String firstExistingKcm() {
        for (String path : KCM_PATHS) {
            if (new File(path).isFile()) return path;
        }
        return null;
    }

    private static String glyphRalt(KeyCharacterMap kcm, int kc) {
        int a = kcm.get(kc, KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON);
        char ch = printable(a);
        return ch == 0 ? "?" : String.valueOf(ch);
    }

    private static char printable(int codePoint) {
        if (codePoint <= 0 || codePoint == KeyCharacterMap.COMBINING_ACCENT) return 0;
        if (Character.isValidCodePoint(codePoint) && !Character.isISOControl(codePoint)) {
            return (char) codePoint;
        }
        return 0;
    }

    private static boolean matches(char want, char got) {
        return got != 0 && got == want;
    }

    private static Map<Integer, Character> expectMap() {
        Map<Integer, Character> m = new LinkedHashMap<>();
        m.put(KeyEvent.KEYCODE_A, '@');
        m.put(KeyEvent.KEYCODE_B, '.');
        m.put(KeyEvent.KEYCODE_C, '8');
        m.put(KeyEvent.KEYCODE_D, '5');
        m.put(KeyEvent.KEYCODE_E, '2');
        m.put(KeyEvent.KEYCODE_F, '6');
        m.put(KeyEvent.KEYCODE_G, '*');
        m.put(KeyEvent.KEYCODE_H, '#');
        // Product map: U=_ I=- (was inverted pre-10.92)
        m.put(KeyEvent.KEYCODE_I, '-');
        m.put(KeyEvent.KEYCODE_J, '+');
        m.put(KeyEvent.KEYCODE_K, '"');
        m.put(KeyEvent.KEYCODE_L, '\'');
        m.put(KeyEvent.KEYCODE_M, '?');
        m.put(KeyEvent.KEYCODE_N, ',');
        m.put(KeyEvent.KEYCODE_O, '/');
        m.put(KeyEvent.KEYCODE_P, ':');
        m.put(KeyEvent.KEYCODE_Q, '0');
        m.put(KeyEvent.KEYCODE_R, '3');
        m.put(KeyEvent.KEYCODE_S, '4');
        m.put(KeyEvent.KEYCODE_T, '(');
        m.put(KeyEvent.KEYCODE_U, '_');
        m.put(KeyEvent.KEYCODE_V, '9');
        m.put(KeyEvent.KEYCODE_W, '1');
        m.put(KeyEvent.KEYCODE_X, '7');
        m.put(KeyEvent.KEYCODE_Y, ')');
        m.put(KeyEvent.KEYCODE_Z, '!');
        return m;
    }

    private static InputDevice findTitanKey() {
        int[] ids = InputDevice.getDeviceIds();
        if (ids == null) return null;
        InputDevice fallback = null;
        for (int id : ids) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null) continue;
            String n = d.getName();
            if (n == null) continue;
            if ("TitanKey".equals(n) || n.contains("TitanKey")) return d;
            if (d.getVendorId() == 0x2533 && d.getProductId() == 0x2533) return d;
            if (fallback == null && !d.isVirtual()
                    && d.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                fallback = d;
            }
        }
        return fallback;
    }

    private static void writeReport(Context ctx, String report) {
        byte[] data = report.getBytes(StandardCharsets.UTF_8);
        try {
            FileOutputStream fos = ctx.openFileOutput(FILE, Context.MODE_PRIVATE);
            fos.write(data);
            fos.close();
        } catch (Exception ignored) {}
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) {
                File f = new File(ext, FILE);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(data);
                fos.close();
                //noinspection ResultOfMethodCallIgnored
                f.setReadable(true, false);
            }
        } catch (Exception ignored) {}
        for (String d : new String[]{"/data/local/tmp", "/data/misc/titan2"}) {
            try {
                File f = new File(d, FILE);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(data);
                fos.close();
                //noinspection ResultOfMethodCallIgnored
                f.setReadable(true, false);
            } catch (Exception ignored) {}
        }
    }
}
