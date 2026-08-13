package com.titanus2.controls;

import android.content.Context;
import android.graphics.drawable.Icon;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

/**
 * QS Flashlight — CubalC free-flow / BrainCube impulse: apply LED on click
 * in the same breath (sysfs snap). Plane files remain for belt re-assert only.
 */
public class TorchTileService extends TileService {

    private static final String TAG = "TorchTile";
    private static final String SECURE_FLASH = "flashlight_enabled";
    public static final String TORCH_DESIRE = "titan2_torch_on";

    private final Handler main = new Handler(Looper.getMainLooper());
    /** Optimistic UI state until plane re-read (snap is source of truth). */
    private static volatile Boolean lastSnapOn;

    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onClick() {
        super.onClick();
        final boolean want = !isOn(this);
        // UI flips immediately — energy_flow: impulse not poll wait.
        lastSnapOn = want;
        refresh();
        applyTorch(this, want);
        main.postDelayed(this::refresh, 80);
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = isOn(this);
        tile.setLabel(getString(R.string.qs_torch_label));
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(on ? "On" : "Off");
        }
        tile.setContentDescription(on ? "Flashlight on" : "Flashlight off");
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setIcon(Icon.createWithResource(this,
                on ? R.drawable.ic_qs_torch_on : R.drawable.ic_qs_torch_off));
        tile.updateTile();
    }

    static boolean isOn(Context ctx) {
        if (lastSnapOn != null) return lastSnapOn;
        try {
            if (Settings.Secure.getInt(ctx.getContentResolver(), SECURE_FLASH, 0) == 1) {
                return true;
            }
        } catch (Exception ignored) {
        }
        String d = AgentBridge.get(ctx, TORCH_DESIRE, "0");
        return d != null && ("1".equals(d.trim()) || "on".equalsIgnoreCase(d.trim()));
    }

    public static void applyTorch(Context ctx, boolean on) {
        lastSnapOn = on;
        // 1) IMPULSE — LED now (BrainCube ~10ms path; no INTERVAL_S)
        boolean led = ImpulseSnap.torch(on);
        Log.i(TAG, "impulse torch=" + on + " led_ok=" + led);

        // 2) Plane for belt re-assert only (not the hot path)
        String v = on ? "1" : "0";
        try {
            Settings.Secure.putInt(ctx.getContentResolver(), SECURE_FLASH, on ? 1 : 0);
        } catch (Exception ignored) {
        }
        try {
            AgentBridge.put(ctx, TORCH_DESIRE, v);
        } catch (Exception e) {
            Log.w(TAG, "torch desire: " + e.getMessage());
        }

        // 3) Best-effort CameraManager when privacy allows
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return;
            String torchId = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash)
                        && facing != null
                        && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    torchId = id;
                    break;
                }
            }
            if (torchId == null) {
                for (String id : cm.getCameraIdList()) {
                    Boolean flash = cm.getCameraCharacteristics(id)
                            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (Boolean.TRUE.equals(flash)) {
                        torchId = id;
                        break;
                    }
                }
            }
            if (torchId != null) {
                cm.setTorchMode(torchId, on);
            }
        } catch (CameraAccessException e) {
            // expected under cam privacy — impulse already owns LED
        } catch (Exception e) {
            Log.w(TAG, "setTorchMode: " + e.getMessage());
        }
    }
}
