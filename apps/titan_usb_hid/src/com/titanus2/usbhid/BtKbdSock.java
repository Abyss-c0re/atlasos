package com.titanus2.usbhid;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import java.io.FileDescriptor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Receives the bridge's USB keyboard report and hands those 8 bytes to BT.
 * Blocking recv: no poll gap, and the payload is the report (not a wake).
 */
final class BtKbdSock {
    private static final String TAG = "BtKbdSock";
    static final String NAME = "titan2_bt_kbd";
    private final AtomicBoolean run = new AtomicBoolean(false);
    private volatile LocalSocket sock;
    private Thread thr;

    void start() {
        if (run.getAndSet(true)) return;
        thr = new Thread(this::loop, "titan-bt-kbd-sock");
        thr.setDaemon(true);
        thr.setPriority(Thread.MAX_PRIORITY);
        thr.start();
    }

    void stop() {
        run.set(false);
        LocalSocket s = sock;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
        if (thr != null) {
            try { thr.interrupt(); } catch (Exception ignored) {}
            thr = null;
        }
    }

    private void loop() {
        byte[] buf = new byte[8];
        while (run.get()) {
            LocalSocket ls = null;
            try {
                ls = new LocalSocket(LocalSocket.SOCKET_DGRAM);
                ls.bind(new LocalSocketAddress(NAME, LocalSocketAddress.Namespace.ABSTRACT));
                sock = ls;
                FileDescriptor fd = ls.getFileDescriptor();
                Log.i(TAG, "bound " + NAME + " usb report");
                while (run.get()) {
                    int n;
                    try {
                        n = Os.recvfrom(fd, buf, 0, buf.length, 0, null);
                    } catch (ErrnoException ee) {
                        if (!run.get()) break;
                        continue;
                    }
                    if (n != 8) continue;
                    byte[] r = new byte[]{
                        buf[0], buf[1], buf[2], buf[3], buf[4], buf[5], buf[6], buf[7]
                    };
                    BluetoothHidClient.get().submitReport(r);
                }
            } catch (Exception e) {
                if (run.get()) Log.w(TAG, "sock: " + e.getMessage());
                try { Thread.sleep(80); } catch (InterruptedException ie) { break; }
            } finally {
                if (ls != null) {
                    try { ls.close(); } catch (Exception ignored) {}
                    if (sock == ls) sock = null;
                }
            }
        }
    }
}
