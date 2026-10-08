package com.titanus2.api;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client for the {@code titan2-keys} system binary.
 * The socket is abstract {@code @titan2_keys}. Four bytes in, four bytes out.
 * A miss from the daemon is authoritative. A down daemon returns null so the
 * caller can use the same table compiled into {@link KeyGlyphs}.
 */
public final class TitanKeys {
    private static final String NAME = "titan2_keys";
    private static final long DOWN_MS = 5000L;
    private static final int[] MISS = new int[0];

    private static final ConcurrentHashMap<Character, int[]> glyphs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, String> specials = new ConcurrentHashMap<>();
    private static final Object io = new Object();
    private static LocalSocket sock;
    private static long downUntil;

    private TitanKeys() {}

    /**
     * @return {@code {hidMod, hidUsage}}, an empty array when the daemon has no
     *         such glyph, or null when the daemon is not answering
     */
    public static int[] glyphHid(char c) {
        int[] hit = glyphs.get(c);
        if (hit != null) return hit;
        byte[] out = query(new byte[]{'G', (byte) c, 0, 0});
        if (out == null) return null;
        if (out[3] != 1) {
            glyphs.put(c, MISS);
            return MISS;
        }
        int[] pair = new int[]{out[0] & 0xff, out[1] & 0xff};
        glyphs.put(c, pair);
        return pair;
    }

    /**
     * Printed Titan special for an Android keycode.
     *
     * @return the glyph, {@code ""} when the daemon says this key is not on
     *         the layer, or null when the daemon is not answering
     */
    public static String androidGlyph(int keyCode) {
        String hit = specials.get(keyCode);
        if (hit != null) return hit;
        byte[] out = query(new byte[]{
            'A',
            (byte) (keyCode & 0xff),
            (byte) ((keyCode >> 8) & 0xff),
            0
        });
        if (out == null) return null;
        if (out[3] != 1 || out[2] == 0) {
            specials.put(keyCode, "");
            return "";
        }
        String g = String.valueOf((char) (out[2] & 0xff));
        specials.put(keyCode, g);
        return g;
    }

    /** True when {@code pair} is a daemon miss rather than a chord. */
    public static boolean isMiss(int[] pair) {
        return pair != null && pair.length == 0;
    }

    private static byte[] query(byte[] in) {
        synchronized (io) {
            long now = SystemClock.elapsedRealtime();
            if (now < downUntil) return null;
            try {
                if (sock == null) {
                    LocalSocket s = new LocalSocket(LocalSocket.SOCKET_DGRAM);
                    s.connect(new LocalSocketAddress(
                        NAME, LocalSocketAddress.Namespace.ABSTRACT));
                    s.setSoTimeout(20);
                    sock = s;
                }
                OutputStream os = sock.getOutputStream();
                os.write(in);
                os.flush();
                byte[] out = new byte[4];
                InputStream is = sock.getInputStream();
                int n = 0;
                while (n < 4) {
                    int r = is.read(out, n, 4 - n);
                    if (r < 0) break;
                    n += r;
                }
                if (n != 4) throw new java.io.IOException("short");
                return out;
            } catch (Exception e) {
                closeLocked();
                downUntil = now + DOWN_MS;
                glyphs.clear();
                specials.clear();
                return null;
            }
        }
    }

    private static void closeLocked() {
        LocalSocket s = sock;
        sock = null;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }
}
