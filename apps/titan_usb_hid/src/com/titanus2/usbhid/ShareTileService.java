package com.titanus2.usbhid;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * QS toggle for the HID session — not the pad tile.
 * Uses the last Exclusive/Share + Link + screen-off the user left in the app.
 * Long-press opens HID.
 */
public class ShareTileService extends TileService {

    @Override public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override public void onClick() {
        super.onClick();
        Context app = getApplicationContext();
        if (isHidLive(app)) {
            HidSessionService.stop(app);
        } else {
            SharedPreferences p = app.getSharedPreferences("usb_hid", MODE_PRIVATE);
            boolean exclusive = p.getBoolean("exclusive", true);
            int t = p.getInt("transport", HidControl.TRANSPORT_BT);
            boolean so = p.getBoolean("screen_off", true);
            HidControl.setTransport(t);
            HidControl.setScreenOffOk(app, so);
            if (HidSessionService.isRunning()) {
                HidSessionService.update(app, true, exclusive, true, t, so);
            } else {
                HidSessionService.start(app, true, exclusive, true, t, so);
            }
        }
        refresh();
        requestRefresh(app);
    }

    @Override public void onTileAdded() {
        super.onTileAdded();
        try { HidQsDefaults.ensureDefaultTile(this); } catch (Exception ignored) {}
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = isHidLive(this);
        boolean excl = on
            ? HidControl.isGrabPlaneExplicit(this)
            : getSharedPreferences("usb_hid", MODE_PRIVATE).getBoolean("exclusive", true);
        tile.setLabel(getString(R.string.qs_hid_label));
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(getString(excl ? R.string.qs_hid_excl : R.string.qs_hid_share));
        }
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setIcon(Icon.createWithResource(this,
            on ? R.drawable.ic_qs_hid_share : R.drawable.ic_qs_hid_share_off));
        tile.updateTile();
    }

    static boolean isHidLive(Context ctx) {
        if (HidControl.isSoftCompose()) return false;
        if (HidSessionService.isPhysLive()) return true;
        return HidControl.isSessionOn(ctx);
    }

    static void requestRefresh(Context ctx) {
        if (ctx == null) return;
        try {
            TileService.requestListeningState(ctx.getApplicationContext(),
                new ComponentName(ctx, ShareTileService.class));
        } catch (Exception ignored) {}
    }
}
