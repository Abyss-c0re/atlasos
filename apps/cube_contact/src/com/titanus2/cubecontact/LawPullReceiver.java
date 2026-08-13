package com.titanus2.cubecontact;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 1.17 residual: cool land {@code ensure_nanobot_peer} starts :8787 after
 * CubeContact already burned its boot retry wave (or process never woke).
 * Host fires {@code com.titanus2.cubecontact.LAW_PULL} once peer listens so
 * dual virtual.tsv promotes without Sensors/GL/rear open.
 *
 * 1.18 residual: Magisk late_start (and host poke) woke the process via this
 * receiver, but {@code onReceive} returned immediately and the empty process
 * could die before background pull finished — dual file SoT stayed seed.
 * {@code goAsync} + synchronous pull keeps the process for one promote, then
 * {@code kickBootPull} arms the re-armable wave for late energy.
 *
 * 1.20 residual: after goAsync finished, naked retry threads died with the
 * empty process once the external 30s belt ended. Start {@link LawPromoteService}
 * so the process holds through retry+tail; host belt also extends to tail.
 *
 * 1.21 residual: START_NOT_STICKY mid-tail LMK after 180s belt left seed;
 * service is sticky + host/Magisk belt covers ~330s full wave end.
 *
 * 1.22 residual: AlarmManager seed rearm also delivers this action after
 * wave+belt end so late peer still promotes without Sensors/GL.
 *
 * 1.23 residual: rearm deliveries must pull once and chain next delay —
 * must not start full promote wave (1.22 rearm→325s thrash / Doze multi-arm).
 * Host/boot LAW_PULL (no rearm extra) still starts LawPromoteService.
 *
 * 1.24 residual: chain exhaust must loop last delay (not stop forever) so
 * late peer hours later still promotes without Sensors/GL.
 *
 * 1.25 residual: user-present path (BootReceiver) also kickBootPull while
 * seed — complements rearm when Doze deferred the 20m loop.
 *
 * 1.26 residual: power-plug path (BootReceiver POWER_CONNECTED) also
 * kickBootPull while seed — USB adb after unlock with no present re-fire.
 *
 * 1.27 residual: continuous power + already-unlocked — LawSeedWakeService
 * SCREEN_ON / idle-exit while seed (no present or power edge).
 *
 * 1.28 residual: continuous interactive left seed when peer rose later —
 * LawSeedWakeService TIME_TICK + peer :8787 poll while seed.
 *
 * 1.29 residual: mid-wave sleep left seed while peer open — LawSeedWakeService
 * direct pullFromNanobot on peer-open (kickBootPull alone was no-op).
 *
 * 1.30 residual: SCREEN_ON / present / power edges also promotePeerWhileSeed
 * (direct pull + kick) so mid-wave ignore does not wait for peer poll.
 *
 * 1.31 residual: rear / Sensors / subdisplay UI also promotePeerWhileSeed
 * (1.16 kickBootPull-only mid-wave residual when human opened those paths).
 *
 * 1.32 residual: main CubeContactActivity front + edge-pull pending re-run
 * (1.31 left front pull-only/60s + concurrent promote drop while in-flight).
 *
 * 1.33 residual: Application promotePeerWhileSeed + edge pending backoff
 * (1.32 left Application kickBootPull-only / immediate pending double-fail).
 *
 * 1.34 residual: host/Magisk LAW_PULL still one pullFromNanobot then bare
 * LawPromoteService.request — TCP-open HTTP-not-ready on belt poke left dual
 * virtual.tsv seed until next belt slot; startService success skipped
 * LawSeedWakeService ensure. Sync multi-pull (EDGE backoff SoT) + kickBootPull
 * (wave + rearm + wake). Rearm path re-ensures wake after single pull seed.
 *
 * 1.35 residual: Alarm rearm still single pullFromNanobot (1.34 multi-pull
 * only on host/Magisk path) — TCP-open HTTP-not-ready on rearm fire left dual
 * virtual.tsv seed until next rearm delay (45s–20m loop). Rearm uses the same
 * pullPeerWhileSeedSync EDGE multi-pull; still no full promote wave thrash.
 *
 * 1.36 residual: host multi-pull + kickBootPull starts LawPromoteService wave
 * that still single-pulled each slot — see LawPromoteService / runRetryDelays.
 *
 * 1.37 residual: promotePeerWhileSeed edge/UI still single pull after 1.36
 * wave multi-pull — see VirtualSensorSync.promotePeerWhileSeed.
 *
 * 1.38 residual: Sensors Sync single-pull residual after 1.37 — see SensorsActivity.
 */
public class LawPullReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.titanus2.cubecontact.LAW_PULL";

    @Override
    public void onReceive(Context c, Intent i) {
        if (c == null) return;
        final boolean rearm = i != null && i.getBooleanExtra("rearm", false);
        final BroadcastReceiver.PendingResult pending = goAsync();
        final Context app;
        try {
            Context a = c.getApplicationContext();
            app = a != null ? a : c;
        } catch (Exception e) {
            try { pending.finish(); } catch (Exception ignored) {}
            return;
        }
        new Thread(() -> {
            try {
                try { StateMatrix.bindAppContext(app); } catch (Exception ignored) {}
                try { SensorPrefs.ensureDefaultVirtual(app); } catch (Exception ignored) {}
                // 1.23: Alarm rearm path — pull + chain next delay only
                // (no full promote wave thrash / Doze multi-arm).
                // 1.35: EDGE multi-pull (not single pull) before chain advance.
                if (rearm) {
                    boolean promoted = false;
                    try {
                        promoted = VirtualSensorSync.pullPeerWhileSeedSync(app);
                    } catch (Exception ignored) {
                        // Peer flaky — chain still advances.
                    }
                    if (promoted) {
                        try { VirtualSensorSync.cancelSeedRearm(app); } catch (Exception ignored) {}
                        return;
                    }
                    try {
                        VirtualSensorSync.scheduleNextSeedRearm(app);
                    } catch (Exception ignored) {}
                    // 1.34: rearm after LMK may have lost wake shell — re-ensure.
                    try {
                        LawSeedWakeService.ensure(app);
                    } catch (Exception ignored) {}
                    return;
                }
                // 1.34 host/Magisk/boot LAW_PULL: goAsync holds process for
                // EDGE multi-pull (1.18 empty-process; 1.33 async-only residual)
                // then kickBootPull arms wave + rearm + LawSeedWakeService.
                boolean promoted = false;
                try {
                    promoted = VirtualSensorSync.pullPeerWhileSeedSync(app);
                } catch (Exception ignored) {}
                if (promoted) {
                    try { VirtualSensorSync.cancelSeedRearm(app); } catch (Exception ignored) {}
                    return;
                }
                try {
                    VirtualSensorSync.kickBootPull(app);
                } catch (Exception ignored) {
                    // kickBootPull already arms rearm when startService fails.
                    try {
                        if (!VirtualSensorSync.isLawEnergyPromoted(app)) {
                            VirtualSensorSync.scheduleSeedRearm(app);
                        }
                    } catch (Exception ignored2) {}
                }
            } finally {
                try { pending.finish(); } catch (Exception ignored) {}
            }
        }, "law-pull-recv").start();
    }
}
