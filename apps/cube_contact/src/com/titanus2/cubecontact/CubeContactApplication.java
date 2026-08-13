package com.titanus2.cubecontact;

import android.app.Application;
import android.util.Log;

/**
 * Process-level LAW SoT bind (1.14 residual).
 *
 * <p><b>CUBE STABILITY LAW:</b> THE CUBE SHALL BE STABLE AT ALL COST.
 * See {@code CUBE_STABILITY_LAW.md} / {@link CubeStability}.
 * BootReceiver only runs on BOOT_COMPLETED; rear/service/Activity bind left a
 * cold process window where resolveAppContext could race first kernel-matrix
 * pull before ensureDefaultVirtual seeded virt_law_*. Bind + seed in onCreate
 * so every process start has Application Context + file SoT before first frame.
 *
 * 1.15 residual: bind+seed alone left virt_law_*=0 until rear GL pull or
 * Sensors manual "Sync nanobot" — peer-up cold process / boot / Sensors open
 * showed zeros while braincube energy already flowed. Kick one-shot background
 * VirtualSensorSync.pullFromNanobot (fail-closed) so dual virtual.tsv promotes
 * peer LAW without waiting for GL or a button.
 *
 * 1.16 residual: one-shot at cold start often hit peer-down (nanobot lands
 * later) — kickBootPull now arms delayed retries until law energy promotes.
 *
 * 1.17 residual: wave ended with seed zeros when peer rose later — re-armable
 * wave + tail delays + host LAW_PULL after ensure_nanobot_peer listens.
 *
 * 1.18 residual: Magisk late_start peer had no LAW_PULL (host cool land only);
 * LawPullReceiver now goAsync+sync pull; Magisk service pokes after listen.
 *
 * 1.20 residual: naked sleep wave died with empty process after 30s belt —
 * kickBootPull starts LawPromoteService (process hold through tail).
 *
 * 1.21 residual: sticky service + belt through ~330s covers LMK mid-tail
 * after 180s external belt ended (1.20 residual).
 *
 * 1.22 residual: after wave+belt end, AlarmManager seed rearm keeps late
 * peer promote alive without Sensors/GL (rootless residual).
 *
 * 1.23 residual: rearm chain (single allow-while-idle) + no rearm full-wave.
 *
 * 1.24 residual: rearm loops last delay while seed (chain exhaust residual).
 *
 * 1.25 residual: USER_PRESENT / USER_UNLOCKED re-kick while seed (Doze deferred
 * 20m loop left dual virtual.tsv seed until Sensors/GL).
 *
 * 1.26 residual: POWER_CONNECTED re-kick while seed (USB plug after unlock left
 * dual virtual.tsv seed until 20m rearm — present is unlock-edge only).
 *
 * 1.27 residual: continuous power + already-unlocked left seed (no present or
 * power edge) — LawSeedWakeService SCREEN_ON / idle-exit while seed.
 *
 * 1.28 residual: continuous interactive left seed when peer rose later —
 * LawSeedWakeService TIME_TICK + peer :8787 poll while seed.
 *
 * 1.29 residual: mid-wave sleep left seed while peer open — LawSeedWakeService
 * direct pullFromNanobot on peer-open + denser edge poll.
 *
 * 1.30 residual: human edges (screen/present/power) use promotePeerWhileSeed
 * mid-wave-safe path (1.29 left those on kickBootPull only).
 *
 * 1.31 residual: rear / Sensors / subdisplay UI also promotePeerWhileSeed
 * (1.16 kickBootPull-only mid-wave residual). Application still kickBootPull
 * (process start arms wave; no sWaveRunning yet).
 *
 * 1.32 residual: main CubeContactActivity front lattice also promotePeerWhileSeed
 * while seed + concurrent edge-pull pending (1.31 left front pull-only/60s).
 *
 * 1.33 residual: Application still kickBootPull only — startService fail
 * (background restrict / cool lab) left no direct pull until rearm/wake.
 * Process start now promotePeerWhileSeed (direct pull + kick/rearm/wake).
 *
 * 1.34 residual: Magisk/host LAW_PULL belt multi-pull + kickBootPull (1.33
 * still single goAsync pull + bare startService residual).
 *
 * 1.35 residual: Alarm rearm multi-pull (1.34 left rearm single-pull residual).
 *
 * 1.36 residual: promote wave entry + each retry/tail slot multi-pull (1.35
 * left LawPromoteService single pullFromNanobot per delay residual).
 *
 * 1.37 residual: promotePeerWhileSeed still single pullFromNanobot per entry
 * (1.36 left edge/UI/Application multi-pull residual while wave multi-pulled).
 *
 * 1.38 residual: Sensors manual Sync still single pullFromNanobot after 1.37
 * edge multi-pull — see SensorsActivity (EDGE multi-pull + rebuild belt).
 *
 * 1.39 residual: Sync multi-pull fail still false "updated" toast + one rebuild
 * while wave promoted later — see SensorsActivity (honest toast + wave belt).
 *
 * 1.40 residual: 1.39 absolute 12/40/45 belt missed cumulative wave+tail —
 * see SensorsActivity scheduleSensorsRebuildBelt wave-cover + promote toast.
 *
 * 1.41 residual: 1.40 Sensors belt 340s thrash after promote / open-while-live
 * + Sync stack — see SensorsActivity cancelBelt + short live cover + toggle preserve.
 *
 * 1.42 residual: 1.41 sparse cumulative ticks left Sensors "LAW seed" up to
 * ~140s after dual virtual.tsv live — see SensorsActivity promote-watch 2.5s.
 *
 * 1.43 residual: 1.42 340s hard stop left Sensors seed after rearm promote —
 * see SensorsActivity open-forever promote-watch + onResume re-arm.
 *
 * 1.44 residual: 1.43 open detect-only left Sensors seed when wake dead —
 * see SensorsActivity open-pull + onResume promote re-kick.
 *
 * 1.45 residual: 1.44 Sensors open-pull only — front Neural Cube lattice still
 * onResume-only promote → seed while open when wake dead; see CubeContactActivity
 * front open-pull on statusTick cadence.
 *
 * 1.46 residual: front + Sensors open-pull only — rear lattice still onCreate
 * one-shot → seed while open when wake dead; see RearCubeActivity lawPoll.
 *
 * 1.47 residual: front + Sensors + rear open-pull only — SubdisplayCubeService
 * still onStart one-shot → seed while sticky viz on when wake dead; see
 * SubdisplayCubeService SUBDISP_SEED_PULL_EVERY.
 *
 * 1.48 residual: LawSeedWake dense-then-45s while peer stayed open + seed left
 * dual virtual.tsv seed mid-gap (wake live, no UI) — see PEER_STEADY_POLL_MS.
 *
 * 1.49 residual: EDGE multi-pull 4×450ms left seed mid dense/steady gap after
 * 1.48 10s open poll — stretch multi-pull + immediate first wake poll; tip
 * upgrade force-stop so process reloads (see install_to_device).
 *
 * 1.50 residual: after dense exhaust, wake still PEER_STEADY 10s while peer
 * open+seed — multi-pull stretch still left ≤10s dual virtual.tsv seed mid-gap.
 * LawSeedWake open-forever dense (PEER_EDGE while open; Sensors promoteWatch SoT).
 *
 * 1.51 residual: 1.50 wake dense 2.5s, but Sensors/UI open-pull still ~10s —
 * wake dead + UI open left dual virtual.tsv seed ≤10s. Sensors PULL_EVERY=1;
 * front/rear/subdisp EVERY=3 (~2.4s) match PEER_EDGE SoT.
 *
 * 1.54 residual: 1.53 sealed front chat + hybrid cube-ux dim source, but
 * Sensors/Privilege/Rear dialogs still summoned soft LatinIME and rootless
 * boot re-ran old /system cube-ux 0.92. apply CubeSurfacePrefs on process start.
 *
 * 1.55 residual: 1.54 Settings wallpaper_dim=0.15 left live
 * cmd wallpaper get-dim-amount at 0.92 (Settings key ≠ visual). cmd set-dim
 * + delayed belt so old cube-ux boot race cannot re-crush gray after open.
 *
 * 1.56 residual: 1.55 multi-UID + 100s belt left gray after late polish /
 * system cube-ux 0.92 re-run past belt; longer belt + LawSeedWake reassert.
 *
 * 1.57 residual: 1.56 belt ends + LawSeedWake stops after promote — late
 * polish/system cube-ux re-crush with no wake shell. Permanent 120s guard.
 *
 * 1.58 residual: 1.57 Handler guard dies with process — AlarmManager dim
 * chain + pad-agent main-loop belt survive LMK / force-stop residual.
 *
 * 1.59 residual: 1.58 left post-promote hole (LawSeedWake stop) — sticky
 * DimGuardService keeps SCREEN_ON/TIME_TICK dim edges after LAW promote.
 *
 * 1.60 residual: Doze deferred non-exact alarm ~9m after death — exact chain.
 *
 * 1.61 residual: DimGuard process death left app=null + no PendingIntent —
 * dual exact+backup alarm + onDestroy re-arm + install/agent poke.
 *
 * 1.62 residual: force-stop stopped=true + non-exported DimGuard shell DENY —
 * export + goAsync + agent start-service unstop (no open UI).
 */
public class CubeContactApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            StateMatrix.bindAppContext(this);
        } catch (Exception ignored) {}
        // 1.78: PeerHttp hard heat gate needs Application Context before any
        // bridge/chat/StateMatrix HTTP (NanobotBridge residual after 1.77).
        try {
            PeerHttp.bindAppContext(this);
        } catch (Exception ignored) {}
        try {
            SensorPrefs.ensureDefaultVirtual(this);
        } catch (Exception ignored) {}
        // CUBE STABILITY LAW — plate + gates (thermal / idle / peer backoff).
        try {
            Log.i("CubeStability", "LAW: STABLE AT ALL COST · "
                + CubeStability.statusTag(this));
        } catch (Exception ignored) {}
        // 1.55–1.62: multi-UID live dim 0.15 + belt + guard + dual alarm + sticky.
        try { CubeSurfacePrefs.applyWithBelt(this); } catch (Exception ignored) {}
        // Promote peer LAW when cool enough; under cube heat prefer file SoT
        // only (kickBootPull waves melt the phone — Stability Law P0).
        // 1.70 residual: process-start under thermal used to skip everything —
        // no rearm arm left late peer until Sensors/host. kickBootPull thermal
        // path arms rearm once (no wave / no peer HTTP thrash).
        // 1.71 residual: 1.70 still left LawSeedWake sticky TCP under thermal —
        // kickBootPull now rearm-only + stop wake (allowLawSeedWake).
        // 1.72 residual: DimGuard 30s exact + TIME_TICK wallpaper shell under
        // thermal — armAlarm/armGuard use dimAlarmMs/dimGuardMs park; TIME_TICK
        // gated by allowDimTick (SCREEN_ON still reasserts mild dim).
        // 1.73 residual: applyWithBelt 6-slot multi-UID wallpaper belt under
        // thermal (~30 shell forks / 5m) — allowDimBelt parks delayed posts.
        // 1.74 residual: armGuard Handler + DimGuard onStart apply under thermal
        // — allowDimGuard parks process-lifetime shell reassert thrash.
        // 1.77 residual: 1.70–1.76 process-start still thermal-only; load≥8 left
        // promote wave + peer HTTP live (dim already load-gated). isCubeHeat.
        // 1.78 residual: 1.77 call-site gates left PeerHttp/NanobotBridge chat
        // unguarded under heat — bind PeerHttp + hard refuse open.
        try {
            if (CubeStability.isCubeHeat(this)) {
                Log.w("CubeStability", "cube heat — skip promote wave + wake; arm rearm once; dim park; no dim belt/guard");
                VirtualSensorSync.kickBootPull(this);
            } else {
                VirtualSensorSync.promotePeerWhileSeed(this);
            }
        } catch (Exception ignored) {
            try {
                VirtualSensorSync.kickBootPull(this);
            } catch (Exception ignored2) {}
        }
    }
}
