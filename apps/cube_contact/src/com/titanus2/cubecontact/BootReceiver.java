package com.titanus2.cubecontact;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Boot + unlock / present / power-plug re-kick for First Cube LAW dual file SoT.
 *
 * 1.15–1.24: BOOT_COMPLETED arms promote wave + seed rearm chain/loop.
 *
 * 1.25 residual: after wave + 20m rearm loop, Doze can still defer
 * {@code setAndAllowWhileIdle} while peer :8787 is already up. Human unlock
 * (USER_UNLOCKED / USER_PRESENT) left dual virtual.tsv seed until next rearm
 * fire (up to 20m) or Sensors/GL. Re-kick on present/unlock while seed only;
 * promote cancels rearm; kickBootPull debounces thrash.
 *
 * 1.26 residual: USER_PRESENT fires only on unlock edge. After unlock with
 * peer still down, human leaves session unlocked; peer rises later (Magisk
 * delayed / cool land); USB plug for adb (common lab path) never re-fires
 * present → dual virtual.tsv stayed seed until 20m rearm or Sensors/GL.
 * POWER_CONNECTED re-kicks while seed only (same skip-when-promoted as present).
 *
 * 1.27 residual: continuous USB (already plugged) never re-fires POWER_CONNECTED
 * while session stays unlocked. LawSeedWakeService (via kickBootPull) holds a
 * light sticky shell for SCREEN_ON + device-idle exit while seed.
 *
 * 1.28 residual: continuous interactive (screen stays on) left seed when peer
 * rose later with no screen/idle edge — wake shell also TIME_TICK + :8787 poll.
 *
 * 1.29 residual: peer-poll / TIME_TICK mid-wave-safe direct pull (kick alone
 * no-op while LawPromoteService sleeps).
 *
 * 1.30 residual: present/power still kickBootPull only → mid-wave ignore left
 * dual virtual.tsv seed until peer poll after unlock/plug. Present/power use
 * promotePeerWhileSeed (direct pull + kick); boot still kickBootPull (arms wave).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (c == null) return;
        final String action = i != null ? i.getAction() : null;
        final boolean boot = action == null
            || Intent.ACTION_BOOT_COMPLETED.equals(action);
        final boolean present = Intent.ACTION_USER_PRESENT.equals(action)
            || Intent.ACTION_USER_UNLOCKED.equals(action)
            || "android.intent.action.USER_UNLOCKED".equals(action);
        // 1.26: USB/power plug while already unlocked (no USER_PRESENT re-fire).
        final boolean power = Intent.ACTION_POWER_CONNECTED.equals(action);
        // 1.13: bind early so rear LAW ingest/persist uses real filesDir (1.12 write).
        try { StateMatrix.bindAppContext(c); } catch (Exception ignored) {}
        // 1.7: seed virt_law_* at boot so rear LAW file SoT exists before first open.
        try { SensorPrefs.ensureDefaultVirtual(c); } catch (Exception ignored) {}
        // 1.54/1.55: after boot old cube-ux may set live dim 0.92 —
        // re-assert cmd set-dim 0.15 + Settings + HW-only IME (+ belt on boot).
        // 1.58: always re-arm AlarmManager dim chain (process-death residual).
        // 1.59: sticky DimGuardService (post-promote LawSeedWake stop residual).
        // 1.60: exact alarm chain (Doze ~9m setAndAllowWhileIdle residual).
        // 1.61: dual exact+backup + sticky (app=null process-death residual).
        // 1.62: same path after force-stop unstop via exported DimGuard poke.
        try {
            if (boot) {
                CubeSurfacePrefs.applyWithBelt(c);
            } else {
                CubeSurfacePrefs.apply(c);
                CubeSurfacePrefs.armAlarm(c);
                CubeSurfacePrefs.armGuard(c);
                DimGuardService.start(c);
            }
        } catch (Exception ignored) {}
        // 1.15–1.30: promote peer LAW into dual file SoT (boot + present + power).
        // Present/power path: skip if already promoted (no service thrash).
        if ((present || power) && !boot) {
            try {
                if (VirtualSensorSync.isLawEnergyPromoted(c)) {
                    try { VirtualSensorSync.cancelSeedRearm(c); } catch (Exception ignored) {}
                    return;
                }
            } catch (Exception ignored) {}
            // 1.30: mid-wave-safe direct pull (kickBootPull alone was no-op mid-sleep).
            try { VirtualSensorSync.promotePeerWhileSeed(c); } catch (Exception ignored) {}
        } else {
            try { VirtualSensorSync.kickBootPull(c); } catch (Exception ignored) {}
        }
        // Subdisplay face only on boot (not every unlock / power plug).
        if (boot && CubePalette.subdisplayOn(c)) {
            try {
                c.startService(new Intent(c, SubdisplayCubeService.class));
            } catch (Exception ignored) {}
        }
    }
}
