package com.titanus2.cubecontact;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * 1.20 residual: LAW promote used naked {@code Thread.sleep} waves under
 * {@code goAsync} / Application. Empty process died after last host/Magisk
 * belt poke (30s) → dual virtual.tsv stayed seed zeros until Sensors/GL
 * even when peer :8787 already had First Cube energy.
 *
 * Started service holds the process through retry+tail delays (same SoT as
 * VirtualSensorSync wave). stopSelf when energy promotes or delays exhaust.
 * No FGS / no exclusive thrash.
 *
 * 1.21 residual: START_NOT_STICKY + external belt last poke at 180s left a
 * window where LMK mid-tail (wave sleeps through ~325s cumulative) killed
 * the process with no restart and no further belt poke → seed until
 * Sensors/GL. START_STICKY re-arms promote after system kill; host/Magisk
 * belt extends to ~330s covering full wave end.
 *
 * 1.22 residual: clean stopSelf after seed wave + external belt end left
 * dual virtual.tsv seed when peer rose later (rootless tip / no Magisk).
 * After failed wave, schedule AlarmManager seed rearm (LAW_PULL) so app is
 * self-sufficient without a long-lived service hold.
 *
 * 1.23 residual: rearm is a single chained alarm (not multi-arm Doze quota);
 * wave-end still calls scheduleSeedRearm (idx 0). Rearm fires do not re-enter
 * this service (LawPullReceiver rearm path).
 *
 * 1.24 residual: after chain end, rearm loops last delay while seed (no
 * permanent give-up until Sensors/GL/boot).
 *
 * 1.25 residual: USER_PRESENT / USER_UNLOCKED also request() while seed so
 * Doze-deferred rearm does not leave dual virtual.tsv seed after unlock.
 *
 * 1.26 residual: POWER_CONNECTED also request() while seed so USB plug after
 * unlock (no present re-fire) still promotes when peer already up.
 *
 * 1.27 residual: continuous power + already-unlocked — LawSeedWakeService
 * (arm via scheduleSeedRearm / kickBootPull) covers SCREEN_ON / idle-exit.
 *
 * 1.28 residual: continuous interactive (screen stays on) left seed when peer
 * rose later with no screen/idle edge — LawSeedWakeService TIME_TICK + :8787 poll.
 *
 * 1.29 residual: mid-wave sleep ignored kickBootPull while peer already up —
 * LawSeedWakeService direct pullFromNanobot on peer-open (not startService only).
 *
 * 1.30 residual: SCREEN_ON / present / power also mid-wave-safe via
 * VirtualSensorSync.promotePeerWhileSeed (not kickBootPull alone).
 *
 * 1.31 residual: rear / Sensors / SubdisplayCubeService also promotePeerWhileSeed
 * (1.16 kickBootPull-only left seed mid-wave when human opened UI).
 *
 * 1.32 residual: main CubeContactActivity front + concurrent edge-pull pending
 * (1.31 left front raw pull/60s + dropped concurrent promote while in-flight).
 *
 * 1.33 residual: Application promotePeerWhileSeed + edge pending backoff
 * (1.32 left Application kickBootPull-only / immediate pending double-fail).
 *
 * 1.34 residual: LawPullReceiver host belt multi-pull + kickBootPull (1.33
 * single pull + bare request left seed / no wake ensure on success).
 *
 * 1.35 residual: Alarm rearm multi-pull (1.34 left rearm single-pull residual).
 *
 * 1.36 residual: promote wave entry + runRetryDelays still single pullFromNanobot
 * after 1.34/1.35 multi-pull on belt/rearm — TCP-open HTTP-not-ready on each
 * wave slot left dual virtual.tsv seed until next delay (3s–180s). Wave uses
 * pullPeerWhileSeedSync EDGE multi-pull at entry and after each sleep.
 *
 * 1.37 residual: promotePeerWhileSeed edge/UI still single pull after 1.36
 * wave multi-pull — see VirtualSensorSync.promotePeerWhileSeed.
 *
 * 1.38 residual: Sensors Sync single-pull residual after 1.37 — see SensorsActivity.
 *
 * 1.68 residual: 1.67 poll/EDGE back-off left open-pull + LAW_PULL still starting
 * this service under thermal severe (Application process-start skip only).
 * request() refuses wave when CubeStability.allowPromoteWave is false — prefer
 * file SoT + LawSeedWake light poll until cool (no multi-minute multi-pull hold).
 */
public class LawPromoteService extends Service {
    private static final Object LOCK = new Object();
    private static volatile boolean sWaveRunning;

    /**
     * Start or re-arm promote wave (debounced inside VirtualSensorSync).
     * @return false if startService failed or thermal refuses wave
     *         (caller may schedule seed rearm / light wake only).
     */
    public static boolean request(Context c) {
        if (c == null) return false;
        try {
            Context app;
            try {
                Context a = c.getApplicationContext();
                app = a != null ? a : c;
            } catch (Exception e) {
                app = c;
            }
            // 1.68: thermal severe — no promote-wave service (reheat residual).
            try {
                if (!CubeStability.allowPromoteWave(app)) {
                    return false;
                }
            } catch (Exception ignored) {}
            Intent i = new Intent(app, LawPromoteService.class);
            app.startService(i);
            return true;
        } catch (Exception ignored) {
            // Cool lab / background restrict: fail closed; rearm or host belt.
            return false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final Context app = getApplicationContext();
        // Already promoted — nothing to hold; do not thrash.
        try {
            if (VirtualSensorSync.isLawEnergyPromoted(app)) {
                try { VirtualSensorSync.cancelSeedRearm(app); } catch (Exception ignored) {}
                stopSelf();
                return START_NOT_STICKY;
            }
        } catch (Exception ignored) {}
        boolean start;
        synchronized (LOCK) {
            start = !sWaveRunning;
            if (start) sWaveRunning = true;
        }
        if (!start) {
            // Already waving — leave running instance; ignore duplicate start.
            // START_STICKY so a later system kill still redelivers / restarts.
            return START_STICKY;
        }
        new Thread(() -> {
            try {
                try { StateMatrix.bindAppContext(app); } catch (Exception ignored) {}
                try { SensorPrefs.ensureDefaultVirtual(app); } catch (Exception ignored) {}
                // 1.36: EDGE multi-pull at entry (not single pullFromNanobot —
                // TCP-open HTTP-not-ready would wait full first retry delay).
                // Then retry+tail wave (1.15–1.17 SoT) also multi-pulls each slot.
                try {
                    VirtualSensorSync.pullPeerWhileSeedSync(app);
                } catch (Exception ignored) {}
                if (!VirtualSensorSync.isLawEnergyPromoted(app)) {
                    VirtualSensorSync.runPromoteWave(app);
                }
            } finally {
                synchronized (LOCK) {
                    sWaveRunning = false;
                }
                try {
                    if (VirtualSensorSync.isLawEnergyPromoted(app)) {
                        VirtualSensorSync.cancelSeedRearm(app);
                    } else {
                        // 1.22: wave exhausted seed — late peer rearm without
                        // holding service (LMK bait) or depending on Magisk belt.
                        VirtualSensorSync.scheduleSeedRearm(app);
                    }
                } catch (Exception ignored) {}
                try {
                    // stopSelf after work; sticky only covers mid-wave LMK.
                    stopSelf();
                } catch (Exception ignored) {}
            }
        }, "law-promote-svc").start();
        // 1.21: re-create after LMK mid-tail (NOT_STICKY residual after 1.20).
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
