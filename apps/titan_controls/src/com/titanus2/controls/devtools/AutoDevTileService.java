package com.titanus2.controls.devtools;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

/**
 * QS tile: AUTO DEV MODE. ON requires Atlas bio (opens Dev tools bio path via unlock).
 * OFF is immediate (fail-closed). Long-press → Developer hub.
 */
public class AutoDevTileService extends TileService {
    private static final String TAG = "AutoDevTile";

    public static void requestRefresh(Context c) {
        try {
            requestListeningState(c, new ComponentName(c, AutoDevTileService.class));
        } catch (Exception e) {
            Log.w(TAG, "requestListeningState", e);
        }
    }

    @Override public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override public void onClick() {
        super.onClick();
        boolean on = AutoDevMode.isOn(this);
        if (on) {
            AutoDevMode.disable(this);
            refresh();
            return;
        }
        // Need Atlas bio — unlock + open Dev tools with arm intent
        unlockAndRun(() -> {
            try {
                Intent i = new Intent(this, DevToolsActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                i.putExtra(DevToolsActivity.EXTRA_ARM_AUTO_DEV, true);
                startActivityAndCollapse(i);
            } catch (Exception e) {
                Log.w(TAG, "open dev", e);
            }
        });
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = AutoDevMode.isOn(this);
        tile.setLabel("Auto Dev");
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(on
                ? (AutoDevMode.blackCubePeer(this) ? "on · peer" : "on")
                : "off");
        }
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setIcon(Icon.createWithResource(this,
            on ? android.R.drawable.ic_menu_compass
                : android.R.drawable.ic_menu_close_clear_cancel));
        tile.updateTile();
    }
}
