package com.titanus2.controls;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings tile: cycles pad mode Off → Trackpad → Mouse → Off.
 * Refreshes on listen, click, and {@link PadModeController#ACTION_MODE}
 * (HID / Controls SET paths).
 * <p>
 * Long-press opens Titan Controls Trackpad section via
 * {@link TileService#ACTION_QS_TILE_PREFERENCES} on {@link MainActivity}
 * (not the system App info page). Tile is placed on the default QS panel
 * by {@link PadQsDefaults}.
 */
public class PadModeTileService extends TileService {

    private final BroadcastReceiver modeRx = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };
    private boolean rxRegistered;

    @Override public void onStartListening() {
        super.onStartListening();
        if (!rxRegistered) {
            try {
                IntentFilter f = new IntentFilter(PadModeController.ACTION_MODE);
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(modeRx, f, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(modeRx, f);
                }
                rxRegistered = true;
            } catch (Exception ignored) {}
        }
        refresh();
        // B8 11.59: QS listen after exclusive thrash — resurrect touchpadd if mode mouse
        try {
            String m = PadModeController.getMode(this);
            if (PadModeController.MOUSE.equals(m) || PadModeController.TRACKPAD.equals(m)) {
                PadModeController.ensureTouchpaddProcess();
            }
        } catch (Exception ignored) {}
        try { TaskbarPin.pinOff(this); } catch (Exception ignored) {}
    }

    @Override public void onStopListening() {
        if (rxRegistered) {
            try { unregisterReceiver(modeRx); } catch (Exception ignored) {}
            rxRegistered = false;
        }
        super.onStopListening();
    }

    @Override public void onClick() {
        super.onClick();
        PadModeController.cycle(this);
        // B8 11.81: QS cycle into mouse/trackpad after exclusive thrash — resurrect
        try {
            String m = PadModeController.getMode(this);
            if (PadModeController.MOUSE.equals(m)
                    || PadModeController.TRACKPAD.equals(m)) {
                PadModeController.ensureTouchpaddProcess();
            }
        } catch (Exception ignored) {}
        refresh();
    }

    /**
     * Long-press: open Titan Controls Trackpad (not App info).
     * Some SystemUI builds ignore the activity intent-filter alone; start
     * explicitly when the tile is long-pressed via preferences path.
     */
    @Override public void onTileAdded() {
        super.onTileAdded();
        // First time user adds tile — also re-pin to default panel
        try { PadQsDefaults.ensureDefaultTile(this); } catch (Exception ignored) {}
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        String mode = PadModeController.getMode(this);

        tile.setLabel(PadModeController.longLabel(mode));
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(PadModeController.description(mode));
        }

        int state;
        int iconRes;
        if (PadModeController.MOUSE.equals(mode)) {
            state = Tile.STATE_ACTIVE;
            iconRes = R.drawable.ic_qs_pad_mouse;
        } else if (PadModeController.TRACKPAD.equals(mode)) {
            state = Tile.STATE_ACTIVE;
            iconRes = R.drawable.ic_qs_pad_trackpad;
        } else {
            state = Tile.STATE_INACTIVE;
            iconRes = R.drawable.ic_qs_pad_off;
        }
        tile.setState(state);
        tile.setIcon(Icon.createWithResource(this, iconRes));
        tile.updateTile();
    }
}
