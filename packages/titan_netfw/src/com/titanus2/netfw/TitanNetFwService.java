package com.titanus2.netfw;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;

/**
 * Persistent owner of the network stack. Callers send Messenger what-codes;
 * this process only execs titan2-fw / titan2-tether.sh.
 */
public final class TitanNetFwService extends Service {
    public static final int MSG_STATUS = 1;
    public static final int MSG_ENABLE = 2;
    public static final int MSG_DISABLE = 3;
    public static final int MSG_APPLY = 4;
    public static final int MSG_CLIENT_LIST = 10;
    public static final int MSG_CLIENT_SET = 11;
    public static final int MSG_PREFIX = 12;
    public static final int MSG_TETHER_APPLY = 20;
    public static final int MSG_TETHER_STOP = 21;
    public static final int MSG_TETHER_STATUS = 22;

    private Messenger mMessenger;

    @Override
    public void onCreate() {
        super.onCreate();
        mMessenger = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                String out;
                switch (msg.what) {
                    case MSG_STATUS:
                        out = Fw.run("status");
                        break;
                    case MSG_ENABLE:
                        out = Fw.run("enable");
                        break;
                    case MSG_DISABLE:
                        out = Fw.run("disable");
                        break;
                    case MSG_APPLY:
                        out = Fw.run("apply");
                        break;
                    case MSG_CLIENT_LIST:
                        out = Fw.run("client-list");
                        break;
                    case MSG_CLIENT_SET: {
                        Bundle b = msg.getData();
                        String pol = b != null ? b.getString("policy", "allow") : "allow";
                        String mac = b != null ? b.getString("mac", "") : "";
                        String ip = b != null ? b.getString("ip", "") : "";
                        out = ip.isEmpty()
                            ? Fw.run("client-" + pol, mac)
                            : Fw.run("client-" + pol, mac, ip);
                        break;
                    }
                    case MSG_PREFIX: {
                        Bundle b = msg.getData();
                        String p = b != null ? b.getString("prefix", "") : "";
                        out = p.isEmpty() ? Fw.run("prefix") : Fw.run("prefix", p);
                        break;
                    }
                    case MSG_TETHER_APPLY:
                        out = Tether.run("apply");
                        break;
                    case MSG_TETHER_STOP:
                        out = Tether.run("stop");
                        break;
                    case MSG_TETHER_STATUS:
                        out = Tether.run("status");
                        break;
                    default:
                        out = "unknown";
                }
                if (msg.replyTo != null) {
                    Message r = Message.obtain(null, msg.what);
                    Bundle rb = new Bundle();
                    rb.putString("out", out);
                    r.setData(rb);
                    try { msg.replyTo.send(r); } catch (Exception ignored) {}
                }
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }
}
