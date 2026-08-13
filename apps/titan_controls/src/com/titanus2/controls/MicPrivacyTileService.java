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
 * QS tile: Microphone access On (allowed) / Off (blocked, fail-closed).
 * Uses {@link SensorPrivacyEnforcer} — not stock SystemUI mictoggle alone.
 */
public class MicPrivacyTileService extends TileService {

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };
    private boolean rxRegistered;

    @Override public void onStartListening() {
        super.onStartListening();
        if (!rxRegistered) {
            try {
                IntentFilter f = new IntentFilter(SensorPrivacyEnforcer.ACTION_CHANGED);
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(rx, f);
                }
                rxRegistered = true;
            } catch (Exception ignored) {}
        }
        refresh();
    }

    @Override public void onStopListening() {
        if (rxRegistered) {
            try { unregisterReceiver(rx); } catch (Exception ignored) {}
            rxRegistered = false;
        }
        super.onStopListening();
    }

    @Override public void onClick() {
        super.onClick();
        boolean blocked = SensorPrivacyEnforcer.isMicBlocked(this);
        SensorPrivacyEnforcer.setBlocked(this, SensorPrivacyEnforcer.SENSOR_MICROPHONE, !blocked);
        refresh();
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean blocked = SensorPrivacyEnforcer.isMicBlocked(this);
        tile.setLabel(getString(R.string.qs_mic_privacy_label));
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(blocked
                ? getString(R.string.qs_privacy_blocked)
                : getString(R.string.qs_privacy_allowed));
        }
        tile.setState(blocked ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        tile.setIcon(Icon.createWithResource(this,
            blocked ? R.drawable.ic_qs_mic_off : R.drawable.ic_qs_mic_on));
        tile.updateTile();
    }
}
