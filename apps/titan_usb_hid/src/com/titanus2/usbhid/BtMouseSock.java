package com.titanus2.usbhid;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.Os;
import android.system.StructTimeval;
import android.util.Log;
import java.io.File;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls app-private mouse mailbox (~2ms) and optional DGRAM wake from hid_bridge.
 *
 * Feeds the full mailbox sum into {@link BluetoothHidClient#queueHostMouse}.
 * Java coalesces: healthy BT preserves residual across ≤4 immediate int8
 * packets; congested Snapdragon drops residual (latest-wins, no rubber-band).
 *
 * <b>1.95:</b> consume by mailbox <i>seq</i> so pure button-up (btn=0, no
 * motion) still reaches the host — old "all zeros → skip" left stuck clicks.
 * Motion + wheel ship in one {@code queueHostMouse} (no dual sendReport).
 * <p>
 * <b>1.96:</b> zero <i>all</i> writable mailbox mirrors after take so bridge
 * cannot re-accumulate already-sent motion from a stale misc slot (B6 lag).
 * <p>
 * <b>1.97:</b> session stop zeros SoT+mirrors so reconnect does not replay
 * residual motion after host button-up (pairs with BluetoothHidClient release).
 */
final class BtMouseSock {
    private static final String TAG = "BtMouseSock";
    private static final String NAME = "titan2_bt_mouse";
    /** Primary SoT — hid_bridge 1.96+ accumulates from this path. */
    private static final File MBX =
        new File("/data/user/0/com.titanus2.usbhid/files/titan2_hid_mouse.mbx");
    /** Mirrors bridge may still write — zero after take when writable. */
    private static final File[] MBX_MIRRORS = {
        new File("/data/misc/titan2/titan2_hid_mouse.mbx"),
        new File("/data/local/tmp/titan2_hid_mouse.mbx"),
    };
    private final AtomicBoolean run = new AtomicBoolean(false);
    private Thread thr;
    /** Reused mailbox buffer — no per-poll allocation. */
    private static final byte[] MBX_BUF = new byte[16];
    private static final Object MBX_LOCK = new Object();
    /** Last consumed writer seq (buf[15]); -1 = none yet. */
    private static int lastMbxSeq = -1;

    void start() {
        if (run.getAndSet(true)) return;
        thr = new Thread(this::loop, "titan-bt-mouse-sock");
        thr.setDaemon(true);
        thr.setPriority(Thread.MAX_PRIORITY);
        thr.start();
    }

    void stop() {
        run.set(false);
        if (thr != null) {
            try { thr.interrupt(); } catch (Exception ignored) {}
            thr = null;
        }
    }

    private void loop() {
        byte[] buf = new byte[32];
        while (run.get()) {
            LocalSocket ls = null;
            try {
                ls = new LocalSocket(LocalSocket.SOCKET_DGRAM);
                ls.bind(new LocalSocketAddress(NAME, LocalSocketAddress.Namespace.ABSTRACT));
                FileDescriptor fd = ls.getFileDescriptor();
                try {
                    Os.setsockoptTimeval(fd, android.system.OsConstants.SOL_SOCKET,
                        android.system.OsConstants.SO_RCVTIMEO,
                        StructTimeval.fromMillis(2));
                } catch (Exception ignored) {}
                Log.i(TAG, "bound dgram " + NAME + " + mailbox poll (latest-wins 1pkt)");
                while (run.get()) {
                    pollMailbox();
                    try {
                        /* DGRAM is wake only — do NOT apply payload (would double) */
                        Os.recvfrom(fd, buf, 0, buf.length, 0, null);
                        pollMailbox();
                    } catch (android.system.ErrnoException ee) {
                        if (!run.get()) break;
                    }
                }
            } catch (Exception e) {
                if (run.get()) Log.w(TAG, "sock: " + e.getMessage());
                try { Thread.sleep(80); } catch (InterruptedException ie) { break; }
            } finally {
                if (ls != null) try { ls.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Take mailbox sum, zero motion slot, hand full ints to BT coalesce (B6).
     * Java may emit ≤4 immediate packets for large deltas when host is healthy.
     * Seq advances on every bridge write — pure button-up still consumes.
     */
    static void pollMailbox() {
        if (!MBX.isFile()) return;
        try {
            int dx, dy, wh, btn, seq;
            synchronized (MBX_LOCK) {
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(MBX, "rw")) {
                    if (raf.length() < 16) return;
                    raf.seek(0);
                    raf.readFully(MBX_BUF);
                    if (MBX_BUF[0] != 'M' || MBX_BUF[1] != 'B'
                            || MBX_BUF[2] != 'X' || MBX_BUF[3] != '1')
                        return;
                    ByteBuffer bb = ByteBuffer.wrap(MBX_BUF).order(ByteOrder.LITTLE_ENDIAN);
                    bb.position(4);
                    dx = bb.getInt();
                    dy = bb.getInt();
                    wh = bb.getShort();
                    btn = MBX_BUF[14] & 0x07;
                    seq = MBX_BUF[15] & 0xff;
                    // Already drained this writer generation (zeros after take)
                    if (seq == lastMbxSeq) return;
                    // Fresh seq with truly empty payload (init / race) — adopt seq
                    if (dx == 0 && dy == 0 && wh == 0 && btn == 0 && lastMbxSeq < 0) {
                        lastMbxSeq = seq;
                        return;
                    }
                    /* Zero motion/btn so native can accumulate; keep seq so
                     * re-polls with same seq are no-ops until bridge writes. */
                    MBX_BUF[4] = MBX_BUF[5] = MBX_BUF[6] = MBX_BUF[7] = 0;
                    MBX_BUF[8] = MBX_BUF[9] = MBX_BUF[10] = MBX_BUF[11] = 0;
                    MBX_BUF[12] = MBX_BUF[13] = 0;
                    MBX_BUF[14] = 0;
                    // leave MBX_BUF[15] = seq
                    raf.seek(0);
                    raf.write(MBX_BUF);
                    lastMbxSeq = seq;
                }
                // 1.96: zero misc/tmp mirrors when writable (stale re-add belt)
                zeroMbxMirrors(MBX_BUF);
            }

            BluetoothHidClient bt = BluetoothHidClient.get();
            /* Full sum in one packet (1.95) — wheel no longer a second report. */
            bt.queueHostMouse(btn, dx, dy, wh);
        } catch (Exception ignored) {}
    }

    /** Best-effort zero of bridge mirror slots (same seq, zero motion/btn). */
    private static void zeroMbxMirrors(byte[] zeroed) {
        for (File m : MBX_MIRRORS) {
            try {
                if (m == null || !m.isFile()) continue;
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(m, "rw")) {
                    if (raf.length() < 16) continue;
                    raf.seek(0);
                    raf.write(zeroed);
                }
            } catch (Exception ignored) {}
        }
    }

    /** Call on BT mouse stop so next session re-adopts seq. */
    static void resetSeq() {
        synchronized (MBX_LOCK) {
            lastMbxSeq = -1;
        }
    }

    /**
     * 1.97: zero app-private SoT + mirrors on session drop so bridge cannot
     * re-add residual motion into the next exclusive Start (B6 lag belt).
     */
    static void zeroAllMirrors() {
        synchronized (MBX_LOCK) {
            // Empty MBX1 frame: magic + zeros + seq 0
            byte[] z = new byte[16];
            z[0] = 'M'; z[1] = 'B'; z[2] = 'X'; z[3] = '1';
            try {
                if (MBX.isFile() || MBX.getParentFile() != null) {
                    try (java.io.RandomAccessFile raf =
                             new java.io.RandomAccessFile(MBX, "rw")) {
                        raf.setLength(16);
                        raf.seek(0);
                        raf.write(z);
                    }
                }
            } catch (Exception ignored) {}
            zeroMbxMirrors(z);
            lastMbxSeq = -1;
        }
    }
}
