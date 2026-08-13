package com.titanus2.cubecontact;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Optional Titan 2 rear-panel / subdisplay continuous viz flag.
 * Actual panel blit may be pad-agent / subdisplay service; we set plane keys
 * and keep a lightweight pulse so state core stays visible when enabled.
 *
 * 1.47 residual: 1.46 rear + 1.45 front + 1.44 Sensors open-pull while seed,
 * but this sticky service only promoted once in onStartCommand. Human leaves
 * subdisplay viz on with wake shell dead / LMK mid-seed → peer up later left
 * dual virtual.tsv seed until process restart or Sensors/front/rear open.
 * seedPoll open-pulls on Sensors cadence (~10s) while seed; stop when promoted.
 *
 * 1.49 residual: EDGE multi-pull stretch after 1.48 open-steady — see
 * VirtualSensorSync EDGE_PULL_DRAIN_MAX / LawSeedWake first-poll immediate.
 *
 * 1.50 residual: LawSeedWake open-forever dense while peer open+seed (1.49
 * multi-pull still left ≤10s PEER_STEADY gap after dense exhaust).
 *
 * 1.48 residual: UI/subdisp open-pull ~10s; LawSeedWake after dense used 45s
 * while peer stayed open + seed (no UI) — see PEER_STEADY_POLL_MS.
 *
 * 1.51 residual: 1.50 wake open-dense 2.5s, but subdisp still open-pulled every
 * ~10s (12×800ms). Wake dead + sticky viz left dual virtual.tsv seed ≤10s after
 * peer HTTP-ready. SUBDISP_SEED_PULL_EVERY=3 (~2.4s) matches wake PEER_EDGE.
 */
public class SubdisplayCubeService extends Service {
    private static final String TAG = "CubeSubdisplay";
    /**
     * 1.47: re-kick promote every N seedPoll ticks while sticky+seed.
     * 1.47 used 12×800ms ≈10s. 1.51: 3×800ms ≈2.4s matches wake PEER_EDGE.
     */
    private static final int SUBDISP_SEED_PULL_EVERY = 3;
    private static final long SEED_POLL_MS = 800L;
    private Handler seedHandler;
    private Runnable seedPoll;
    private int subdispSeedPullTicks;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "subdisplay cube viz ON mode=" + CubePalette.mode(this));
        // 1.13: bind so rear-only service path has Application Context for LAW SoT.
        try { StateMatrix.bindAppContext(this); } catch (Exception ignored) {}
        // 1.7: merge-seed virt_law_* before rear resident so file SoT is warm.
        try { SensorPrefs.ensureDefaultVirtual(this); } catch (Exception ignored) {}
        // 1.16: rear-only process may start before main UI — promote peer LAW
        // into dual virtual.tsv without waiting for Sensors or rear GL.
        // 1.31 residual: kickBootPull only was no-op mid LawPromoteService sleep
        // → dual virtual.tsv seed until poll; promotePeerWhileSeed direct pull.
        // 1.47: onStart one-shot kept; seedPoll open-pull while seed.
        subdispSeedPullTicks = 0;
        try { VirtualSensorSync.promotePeerWhileSeed(this); } catch (Exception ignored) {}
        try {
            android.provider.Settings.Global.putInt(
                getContentResolver(), "titan2_cube_viz_on", 1);
        } catch (Exception e) {
            Log.w(TAG, "secure put: " + e.getMessage());
        }
        armSeedOpenPull();
        return START_STICKY;
    }

    /**
     * 1.47 residual: onStartCommand-only promote left dual virtual.tsv seed when
     * subdisplay viz stayed sticky with wake dead / peer rose later. Open-pull
     * on Sensors/front/rear cadence (~10s) while seed; no HTTP thrash every 800ms.
     * Stop poll when promoted (no HUD; wake/UI own live path).
     */
    private void armSeedOpenPull() {
        if (seedHandler == null) {
            seedHandler = new Handler(Looper.getMainLooper());
        }
        // Re-arm after sticky restart or second startCommand: drop prior poll.
        if (seedPoll != null) {
            try { seedHandler.removeCallbacks(seedPoll); } catch (Exception ignored) {}
            seedPoll = null;
        }
        try {
            if (VirtualSensorSync.isLawEnergyPromoted(this)) {
                subdispSeedPullTicks = 0;
                return;
            }
        } catch (Exception ignored) {}
        seedPoll = new Runnable() {
            @Override public void run() {
                try {
                    if (!VirtualSensorSync.isLawEnergyPromoted(SubdisplayCubeService.this)) {
                        subdispSeedPullTicks++;
                        if (subdispSeedPullTicks >= SUBDISP_SEED_PULL_EVERY) {
                            subdispSeedPullTicks = 0;
                            try {
                                VirtualSensorSync.promotePeerWhileSeed(
                                    SubdisplayCubeService.this);
                            } catch (Exception ignored) {}
                        }
                        if (seedHandler != null && seedPoll != null) {
                            // 1.67: thermal/screen-off → statusTickMs (not fixed 800ms).
                            long d = SEED_POLL_MS;
                            try {
                                d = CubeStability.statusTickMs(
                                    SubdisplayCubeService.this, true);
                            } catch (Exception ignored) {}
                            if (d < SEED_POLL_MS) d = SEED_POLL_MS;
                            seedHandler.postDelayed(this, d);
                        }
                    } else {
                        subdispSeedPullTicks = 0;
                        seedPoll = null;
                    }
                } catch (Exception ignored) {
                    if (seedHandler != null && seedPoll != null) {
                        long d = SEED_POLL_MS;
                        try {
                            d = CubeStability.statusTickMs(
                                SubdisplayCubeService.this, true);
                        } catch (Exception ignored2) {}
                        if (d < SEED_POLL_MS) d = SEED_POLL_MS;
                        seedHandler.postDelayed(this, d);
                    }
                }
            }
        };
        seedHandler.post(seedPoll);
    }

    @Override public void onDestroy() {
        if (seedHandler != null && seedPoll != null) {
            try { seedHandler.removeCallbacks(seedPoll); } catch (Exception ignored) {}
        }
        seedHandler = null;
        seedPoll = null;
        subdispSeedPullTicks = 0;
        try {
            android.provider.Settings.Global.putInt(
                getContentResolver(), "titan2_cube_viz_on", 0);
        } catch (Exception ignored) {}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
