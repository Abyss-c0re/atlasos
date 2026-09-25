package com.titanus2.atlas;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.titanus2.api.Titan2ApiContract;

import java.io.File;
import java.io.FileInputStream;

/** Controls side-key wheel → the desk seat. Ignored when the desk is not in front. */
public final class DeskSideReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !deskFocused()) return;
        int wheel = intent.getIntExtra(Titan2ApiContract.EXTRA_MOUSE_WHEEL, 0);
        if (wheel == 0) {
            String action = intent.getStringExtra(Titan2ApiContract.EXTRA_REMOTE_ACTION);
            if (Titan2ApiContract.ACT_MOUSE_SCROLL_UP.equals(action)) wheel = 1;
            else if (Titan2ApiContract.ACT_MOUSE_SCROLL_DOWN.equals(action)) wheel = -1;
        }
        if (wheel == 0) return;
        if (!DeskClient.load(context)) return;
        DeskClient.pointer(DeskSession.inputSock(context), 0, 0, 0, wheel, 0);
    }

    private static boolean deskFocused() {
        File f = new File("/data/local/tmp/atlas-virgl/desk-focus");
        try (FileInputStream in = new FileInputStream(f)) {
            return in.read() == '1';
        } catch (Exception e) {
            return false;
        }
    }
}
