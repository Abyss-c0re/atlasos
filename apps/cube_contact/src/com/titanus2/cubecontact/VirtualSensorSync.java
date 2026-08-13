package com.titanus2.cubecontact;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publish / refresh virtual sensors from the on-device nanobot peer.
 * Values land in virtual.tsv for the kernel-cube sampler to merge each tick.
 */
public final class VirtualSensorSync {
    private static String peerBase() { StateMatrix.tryResolvePeer(); return StateMatrix.PEER; }
    /** Process-once boot/open promote — avoid thrash if Application + Boot + Sensors race. */
    private static final Object BOOT_LOCK = new Object();
    private static volatile boolean sBootPullStarted;
    /** True while a retry wave thread is alive (re-armable after finish — 1.17). */
    private static volatile boolean sRetryWaveRunning;
    private static volatile long sLastKickMs;
    /** Late peer (:8787 after BOOT) — one-shot 1.15 left seed zeros forever. */
    private static final long[] RETRY_DELAYS_MS = { 3_000L, 12_000L, 40_000L };
    /**
     * 1.17 residual: cool land / Magisk peer often lands after the 3+12+40s
     * wave already exited with seed zeros. Tail waits re-pull without
     * requiring Sensors/GL/rear open.
     */
    private static final long[] TAIL_DELAYS_MS = { 90_000L, 180_000L };
    /**
     * 1.22 residual: after sticky wave + host/Magisk belt end (~330s), peer
     * rising later (rootless tip land, cold offline, package replace) left
     * dual virtual.tsv seed until Sensors/GL. Alarm re-arms wake LAW_PULL
     * without holding a 5+ min service (LMK bait). Cancel on promote.
     * Offsets from wave end / schedule call: 45s, 2m, 5m, 10m, 20m.
     *
     * 1.23 residual: 1.22 armed *all* setAndAllowWhileIdle slots at once.
     * Doze only allows ~one allow-while-idle alarm per ~9 minutes, so early
     * 45s/2m/5m rearm often never fired while idle; each rearm also restarted
     * the full ~325s promote wave (LMK bait + thrash). Chain a single next
     * alarm; on fire pull once and advance; host/boot still use full wave.
     *
     * 1.24 residual: after chain idx exhausted (~37m), scheduleNextSeedRearm
     * stopped forever → late peer (hours later, cool offline, Magisk delayed)
     * left dual virtual.tsv seed until Sensors/GL or next boot. Loop the last
     * delay (20m) while still seed; cancel on promote. Still one alarm only.
     *
     * 1.25 residual: even with 20m loop, Doze defers allow-while-idle while
     * peer is already up; human unlock left seed until next rearm or Sensors.
     * BootReceiver USER_PRESENT / USER_UNLOCKED re-kicks while seed.
     *
     * 1.26 residual: USER_PRESENT is unlock-edge only. After unlock with peer
     * still down, USB plug (lab adb) never re-fires present while peer already
     * up → seed until 20m rearm. BootReceiver POWER_CONNECTED re-kicks while seed.
     *
     * 1.27 residual: continuous USB / already-unlocked never re-fires present or
     * POWER_CONNECTED. LawSeedWakeService sticky shell listens SCREEN_ON +
     * device-idle exit while seed (unregister/stop on promote).
     *
     * 1.28 residual: continuous interactive (screen stays on, never Doze) +
     * continuous USB left seed when peer rose later with no screen/idle edge.
     * LawSeedWakeService TIME_TICK + quiet 45s :8787 peer-port poll while seed.
     *
     * 1.29 residual: 1.28 only kickBootPull on peer-open; LawPromoteService
     * ignores duplicate starts while sWaveRunning mid long sleep (up to 180s
     * tail) → peer rising mid-wave left dual virtual.tsv seed until sleep
     * ended. LawSeedWakeService direct pullFromNanobot on peer-open + denser
     * closed→open edge poll (2.5s × 8 then 45s).
     *
     * 1.30 residual: 1.29 direct-pull only on peer-poll / TIME_TICK. SCREEN_ON,
     * idle-exit, USER_PRESENT, POWER_CONNECTED still kickBootPull only →
     * LawPromoteService mid-wave ignore left dual virtual.tsv seed until next
     * poll (≤45s) after human screen/unlock/plug while peer already open.
     * Shared promotePeerWhileSeed: direct pull then kickBootPull on all edges.
     *
     * 1.31 residual: 1.30 covered system edges only. RearCubeActivity /
     * SensorsActivity / SubdisplayCubeService still kickBootPull only → human
     * open rear HUD / Sensors / subdisplay face mid-wave left dual virtual.tsv
     * seed until next poll. Those UI paths use promotePeerWhileSeed too.
     *
     * 1.32 residual: 1.31 covered rear/Sensors/subdisplay only. Main
     * CubeContactActivity still raw pullFromNanobot once/60s (not promote) →
     * human open front lattice mid-wave or within 60s left dual virtual.tsv
     * seed until poll. Concurrent promotePeerWhileSeed while sEdgePullRunning
     * dropped follow-up edges (TCP-open HTTP-not-ready race). Front onResume
     * promotePeerWhileSeed while seed + edge-pull pending re-run.
     *
     * 1.33 residual: Application still kickBootPull only — startService fail
     * left dual virtual.tsv seed until rearm. Application promotePeerWhileSeed.
     * Concurrent pending drain re-pulled immediately (TCP-open HTTP-not-ready
     * double-fail) — short backoff before pending re-run while still seed.
     *
     * 1.34 residual: LawPullReceiver host/Magisk LAW_PULL still one sync pull
     * then bare LawPromoteService.request — TCP-open HTTP-not-ready on belt
     * poke left dual virtual.tsv seed until next belt slot (5–150s) or wave
     * sleep end; no wake-shell ensure when startService succeeded. Sync multi
     * pull (EDGE backoff) + kickBootPull (wave + rearm + LawSeedWakeService).
     * Rearm path also re-ensures wake after single pull still seed.
     *
     * 1.35 residual: Alarm rearm still single pullFromNanobot (1.34 multi-pull
     * only on host/Magisk path) — TCP-open HTTP-not-ready on rearm fire left
     * dual virtual.tsv seed until next rearm delay (45s–20m). Rearm path uses
     * pullPeerWhileSeedSync EDGE multi-pull; still no full promote wave.
     *
     * 1.36 residual: LawPromoteService wave entry + runRetryDelays still one
     * pullFromNanobot per slot after 1.34/1.35 multi-pull on belt/rearm —
     * TCP-open HTTP-not-ready on a wave fire left dual virtual.tsv seed until
     * the next delay (3s/12s/40s/90s/180s). Wave uses pullPeerWhileSeedSync
     * EDGE multi-pull at entry and after each sleep (same SoT as belt/rearm).
     *
     * 1.37 residual: promotePeerWhileSeed (edge / UI / Application / peer poll)
     * still one pullFromNanobot per worker entry after 1.36 wave multi-pull —
     * TCP-open HTTP-not-ready on SCREEN_ON / TIME_TICK / peer-open / rear open
     * left dual virtual.tsv seed until next edge (≤2.5s dense) or wave slot.
     * Edge worker uses pullPeerWhileSeedSync EDGE multi-pull (same SoT).
     *
     * 1.38 residual: Sensors manual Sync still single pullFromNanobot after
     * 1.37 edge multi-pull — TCP-open HTTP-not-ready on Sync left dual
     * virtual.tsv seed + error toast (no EDGE multi-pull / kick). Quiet open
     * rebuild 900/2200/3500 left list seed after multi-pull past 3.5s.
     * Sensors Sync uses pullPeerWhileSeedSync while seed; rebuild belt extends.
     *
     * 1.39 residual: Sync multi-pull fail still toasted "updated" + one rebuild
     * while kick-armed wave promoted later (3s/12s/40s) — Sensors list seed
     * until Refresh. Honest toast + shared rebuild belt through early wave.
     *
     * 1.40 residual: 1.39 Sensors belt absolute 12s/40s/45s missed cumulative
     * wave+tail (~55s/~145s/~325s) — see SensorsActivity scheduleSensorsRebuildBelt.
     *
     * 1.41 residual: 1.40 belt kept 340s posts after promote / open-while-live
     * and stacked Sync belts — see SensorsActivity cancelBelt + short live cover.
     *
     * 1.42 residual: 1.41 sparse cumulative ticks left Sensors "LAW seed" up to
     * ~140s after dual virtual.tsv live — see SensorsActivity promote-watch.
     *
     * 1.43 residual: 1.42 340s hard stop left Sensors seed after rearm loop
     * promoted later — see SensorsActivity open-forever watch + onResume.
     *
     * 1.44 residual: 1.43 open-forever detect-only + onResume detect-only left
     * Sensors seed when wake shell dead — see SensorsActivity open-pull.
     *
     * 1.45 residual: 1.44 Sensors open-pull only — front lattice onResume-only
     * left Neural Cube LAW E=0 while open+seed when wake dead — see
     * CubeContactActivity front open-pull.
     *
     * 1.46 residual: front + Sensors open-pull only — rear lattice onCreate
     * one-shot left rear LAW HUD E=0 while open+seed when wake dead — see
     * RearCubeActivity rear open-pull.
     *
     * 1.47 residual: front + Sensors + rear open-pull only — SubdisplayCubeService
     * onStartCommand one-shot left dual virtual.tsv seed while sticky viz on
     * when wake dead — see SubdisplayCubeService SUBDISP_SEED_PULL_EVERY.
     *
     * 1.48 residual: LawSeedWakeService dense 8×2.5s only on closed→open then
     * 45s while peer stayed open + seed — HTTP-ready after dense left dual
     * virtual.tsv seed ≤45s with wake live / no UI. See PEER_STEADY_POLL_MS.
     *
     * 1.49 residual: EDGE multi-pull still 4×450ms (~1.8s) per promote entry —
     * TCP-open HTTP-ready after that window left dual virtual.tsv seed until
     * next dense/steady poll (2.5s/10s). Stretch multi-pull (8×600ms ≈4.2s).
     *
     * 1.50 residual: after dense exhaust, wake still PEER_STEADY 10s while peer
     * open+seed — multi-pull stretch closed one entry gap but mid-gap after
     * dense left dual virtual.tsv seed ≤10s. LawSeedWake open-forever dense
     * (PEER_EDGE_POLL_MS while open; Sensors promoteWatch SoT).
     *
     * 1.51 residual: 1.50 wake multi-pulls every 2.5s while open+seed, but
     * Sensors open-pulled only every 4 detect ticks (~10s) and front/rear/
     * subdisp every 12×800ms (~10s). Wake dead + UI open left dual virtual.tsv
     * seed ≤10s after peer HTTP-ready. Sensors PULL_EVERY=1; UI EVERY=3 (~2.4s).
     */
    private static final long[] REARM_DELAYS_MS = {
        45_000L, 120_000L, 300_000L, 600_000L, 1_200_000L
    };
    /** Single PendingIntent request code — chain, do not multi-arm Doze quota. */
    private static final int REARM_REQ = 0x4C4157; // "LAW"
    private static final String PREF_REARM = "law_seed_rearm";
    private static final String KEY_REARM_IDX = "next_idx";

    private VirtualSensorSync() {}

    /**
     * 1.15 residual: Application/Boot only seeded zeros; peer LAW stayed in
     * memory-only rear path until GL or manual Sensors sync. One-shot background
     * pull (fail-closed) promotes dual virtual.tsv. Debounced 2s so
     * Application + BootReceiver + Sensors open do not triple-hit peer.
     *
     * 1.16 residual: peer often not listening at BOOT_COMPLETED / cold
     * Application onCreate (ensure_nanobot_peer lands later). One-shot fail-
     * closed left virt_law_*=0 until Sensors open or rear GL. Arm delayed
     * retries (3s/12s/40s) that re-pull only while dual file SoT still has
     * no law energy (stop early when promote lands; fail-closed if peer dead).
     *
     * 1.17 residual: 1.16 armed the wave once per process (`sRetryWaveArmed`)
     * then stopped forever. Peer rising after ~55s (cool land ensure after
     * install, Magisk late start) left dual virtual.tsv seed zeros until
     * Sensors/GL. Re-arm whenever no wave is running; tail delays 90s/180s;
     * host LAW_PULL broadcast after peer listens.
     *
     * 1.18 residual: Magisk late_start / hybrid offline boot still started
     * peer with no LAW_PULL (host cool land only). Magisk service + offline
     * script poke; LawPullReceiver goAsync sync-pull so empty process does
     * not die mid-promote.
     *
     * 1.19 residual: hybrid offline + host ensure single-poke raced peer
     * cold-start (listen late / package replace). Offline + ensure + cube
     * install now multi-pass LAW_PULL belt (Magisk service 1.4 SoT).
     *
     * 1.20 residual: goAsync + naked Thread.sleep wave still died with empty
     * process after last external belt poke (30s) → dual virtual.tsv seed
     * until Sensors/GL. LawPromoteService holds process for wave; host/
     * Magisk/offline belt extends to 0/5/30/90/180s matching tail.
     *
     * 1.21 residual: START_NOT_STICKY + belt last poke 180s left LMK mid-tail
     * (~325s cumulative sleeps) with no restart and no further external poke
     * → seed until Sensors/GL. Service START_STICKY + belt 0/5/30/90/180/330
     * covers full wave end (Magisk nanobot 1.6 SoT).
     *
     * 1.22 residual: wave stopSelf + external belt end left seed when peer
     * rose later (rootless / no Magisk belt). AlarmManager seed rearm after
     * failed wave; cancel on energy promote.
     *
     * 1.23 residual: multi-arm Doze quota + rearm→full-wave thrash. Chain
     * single next alarm; rearm fire pulls once and advances (no full wave).
     *
     * 1.24 residual: chain exhaust stopped rearm forever (seed until
     * Sensors/GL/boot). Loop last delay while seed; still single-alarm.
     *
     * 1.25 residual: Doze-deferred rearm loop left seed until Sensors/GL
     * while human already unlocked and peer up — USER_PRESENT re-kick.
     *
     * 1.26 residual: post-unlock USB power plug left seed (no present re-fire)
     * — POWER_CONNECTED re-kick while seed.
     *
     * 1.27 residual: continuous power + already-unlocked left seed (no present
     * or power edge) — LawSeedWakeService SCREEN_ON / idle-exit re-kick.
     *
     * 1.28 residual: continuous interactive (no screen toggle / idle-exit) left
     * seed when peer rose later — LawSeedWakeService TIME_TICK + peer-port poll.
     *
     * 1.29 residual: 1.28 poll only kickBootPull; LawPromoteService ignores
     * duplicate starts while mid-wave sleep (up to 180s tail) → peer open
     * mid-wave left dual virtual.tsv seed until sleep ended. LawSeedWakeService
     * also direct pullFromNanobot on peer-open (bg thread) + denser edge poll.
     *
     * 1.30 residual: SCREEN_ON / idle-exit / present / power still kickBootPull
     * only — mid-wave ignore left seed until peer poll. Use
     * {@link #promotePeerWhileSeed} on those edges (direct pull + kick).
     *
     * 1.31 residual: rear / Sensors / subdisplay UI still kickBootPull only —
     * mid-wave ignore left seed until peer poll when human opens those paths.
     * Same {@link #promotePeerWhileSeed} (boot Application still kickBootPull).
     *
     * 1.32 residual: main CubeContactActivity front lattice still raw
     * pullFromNanobot (60s throttle) + concurrent edge-pull drop while
     * sEdgePullRunning — front promote + pending re-run (see promotePeerWhileSeed).
     *
     * 1.33 residual: Application process start still kickBootPull only —
     * startService fail left seed; Application now promotePeerWhileSeed.
     * Edge pending drain immediate re-pull double-failed HTTP-not-ready —
     * backoff while still seed (see promotePeerWhileSeed).
     *
     * 1.34 residual: Magisk/host LAW_PULL goAsync path still single pull +
     * bare startService — no EDGE backoff, no wake ensure on success.
     * {@link #pullPeerWhileSeedSync} + kickBootPull (see LawPullReceiver).
     *
     * 1.35 residual: Alarm rearm also uses {@link #pullPeerWhileSeedSync}
     * (1.34 left rearm on single pull — see LawPullReceiver).
     *
     * 1.36 residual: LawPromoteService wave (started by kickBootPull) still
     * single-pull entry + single-pull runRetryDelays — see runPromoteWave /
     * LawPromoteService. Host multi-pull then wait 3s–180s residual closed.
     *
     * 1.37 residual: promotePeerWhileSeed still single-pull entry — see
     * promotePeerWhileSeed (edge/UI share multi-pull SoT with belt/wave).
     *
     * 1.38 residual: Sensors manual Sync single-pull + short rebuild belt —
     * see SensorsActivity (Sync pullPeerWhileSeedSync while seed).
     *
     * 1.39 residual: Sync after kick still one rebuild — see SensorsActivity
     * scheduleSensorsRebuildBelt through wave slots.
     *
     * 1.40 residual: 1.39 absolute 12/40/45 missed cumulative wave+tail —
     * SensorsActivity belt covers ~16/56/150/340s + promote toast.
     *
     * 1.41 residual: Sensors belt stop on promote + short cover when live —
     * see SensorsActivity cancelBelt / scheduleSensorsRebuildBelt.
     *
     * 1.42 residual: Sensors dense promote-watch while seed (sparse tick gap) —
     * see SensorsActivity tickPromoteWatch.
     */
    public static void kickBootPull(Context c) {
        if (c == null) return;
        final Context app;
        try {
            Context a = c.getApplicationContext();
            app = a != null ? a : c;
        } catch (Exception e) {
            return;
        }
        long now = System.currentTimeMillis();
        // 1.68 residual: under thermal severe, open-pull / LawSeedWake / LAW_PULL
        // still entered here every few seconds and started LawPromoteService wave
        // (1.67 only shrunk EDGE multi-pull + open poll; Application process-start
        // skip alone left mid-session reheat). Prefer file SoT + light wake shell;
        // still arm seed rearm so cool later promotes without thrash now.
        // 1.70 residual: 1.69 closed EDGE multi-pull but promotePeerWhileSeed still
        // hit this path every 15s open-poll → scheduleSeedRearm cancel+rearm thrash
        // (Doze quota + CPU wake). Arm rearm once under thermal; ensure wake only.
        // 1.71 residual: 1.70 still ensure'd LawSeedWake under thermal (sticky
        // peerPortOpen TCP every 15s + TIME_TICK). Thermal path = rearm once only
        // + stop wake shell (allowLawSeedWake false).
        boolean allowWave = true;
        try {
            allowWave = CubeStability.allowPromoteWave(app);
        } catch (Exception ignored) {}
        if (!allowWave) {
            try {
                if (!isLawEnergyPromoted(app)) {
                    try {
                        if (!isSeedRearmArmed(app)) scheduleSeedRearm(app);
                    } catch (Exception ignored) {}
                    // 1.71: never sticky wake under thermal (rearm-only SoT).
                    try { LawSeedWakeService.stop(app); } catch (Exception ignored) {}
                } else {
                    try { LawSeedWakeService.stop(app); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            return;
        }
        synchronized (BOOT_LOCK) {
            boolean due = !sBootPullStarted || (now - sLastKickMs) >= 2000L;
            if (!due) return;
            sBootPullStarted = true;
            sLastKickMs = now;
        }
        // 1.20: service holds process through retry+tail (naked thread residual).
        // 1.22: if startService fails (background restrict), still arm seed rearm.
        if (!LawPromoteService.request(app)) {
            try {
                if (!isLawEnergyPromoted(app)) scheduleSeedRearm(app);
            } catch (Exception ignored) {}
        }
        // 1.27–1.33: while seed, keep light wake shell (screen/idle/tick/peer-pull).
        try {
            if (!isLawEnergyPromoted(app)) LawSeedWakeService.ensure(app);
            else LawSeedWakeService.stop(app);
        } catch (Exception ignored) {}
    }

    /**
     * 1.30 residual: human edges (SCREEN_ON, idle-exit, USER_PRESENT,
     * POWER_CONNECTED) and peer-open poll/tick used only kickBootPull in 1.29
     * partial path — LawPromoteService ignores duplicate while sWaveRunning
     * mid long sleep → dual virtual.tsv seed until next poll. Mid-wave-safe:
     * direct {@link #pullFromNanobot} (not blocked by sWaveRunning) then
     * {@link #kickBootPull} for wave/rearm/wake shell. Debounced concurrent
     * bg workers; no FGS / no exclusive thrash. Call from any thread.
     *
     * 1.31 residual: rear HUD / Sensors / SubdisplayCubeService also used
     * kickBootPull only (1.16 paths) — same mid-wave no-op while peer open.
     *
     * 1.32 residual: main CubeContactActivity still raw pull once/60s; concurrent
     * promote while sEdgePullRunning returned early (TCP-open HTTP-not-ready
     * race left seed until next poll). Front uses this path while seed; pending
     * flag re-runs once after in-flight pull finishes.
     *
     * 1.33 residual: Application process start also uses this path (kickBootPull
     * alone left seed when startService failed). Pending drain after a failed
     * pull sleeps briefly so TCP-open HTTP-not-ready does not double-fail;
     * drain capped to avoid edge thrash.
     *
     * 1.34 residual: goAsync LAW_PULL cannot rely on this async path alone
     * (1.18 empty-process death) — see {@link #pullPeerWhileSeedSync}.
     *
     * 1.37 residual: this path still used one {@link #pullFromNanobot} per
     * entry after 1.36 wave multi-pull — edge/UI mid-wave HTTP-not-ready left
     * seed until next edge. Worker now calls {@link #pullPeerWhileSeedSync}.
     */
    /**
     * 1.33: max pending drain loops per worker (edges can keep setting pending).
     * 1.49: 4×450ms (~1.8s) left seed mid dense/steady gap when HTTP ready after
     * multi-pull ended — stretch to 8 attempts (same SoT for wave/edge/Sync).
     */
    private static final int EDGE_PULL_DRAIN_MAX = 8;
    /**
     * 1.33: backoff before re-pull when still seed (peer bind lag).
     * 1.49: 600ms × 8 ≈ 4.2s covers typical nanobot TCP→HTTP cold-ready.
     */
    private static final long EDGE_PULL_BACKOFF_MS = 600L;

    /**
     * 1.34 residual: Magisk/host {@code LAW_PULL} goAsync used one
     * {@link #pullFromNanobot} then bare {@code LawPromoteService.request}.
     * Peer TCP-open / HTTP-not-ready on the belt poke left dual virtual.tsv
     * seed until the next external poke (5–150s) or promote-wave sleep ended;
     * successful startService also skipped {@link LawSeedWakeService} ensure
     * (kickBootPull path). Synchronous multi-pull with the same EDGE backoff
     * as {@link #promotePeerWhileSeed} pending drain, so goAsync holds the
     * process for real promote attempts (1.18 residual class).
     *
     * 1.35 residual: Alarm rearm also used one pull — same HTTP-not-ready
     * class left seed until next rearm delay. Call from a worker thread only.
     *
     * 1.36 residual: LawPromoteService wave entry + each runRetryDelays slot
     * also use this (single pull left seed until next 3s–180s delay).
     *
     * 1.37 residual: promotePeerWhileSeed edge worker also uses this (single
     * pull left seed until next edge while wave multi-pulled only).
     *
     * 1.38 residual: Sensors manual Sync also uses this while seed (single
     * pull left seed + toast on HTTP-not-ready). Returns true when law energy
     * already promoted.
     *
     * 1.39 residual: Sensors Sync still false-success toast + single rebuild
     * when this returns false — see SensorsActivity (honest toast + belt).
     *
     * 1.40 residual: Sensors belt after this returns false still absolute 45s —
     * see SensorsActivity cumulative wave-cover + promote toast.
     *
     * 1.41 residual: Sensors belt after promote still ticked to 340s —
     * see SensorsActivity cancelBelt on promote + Sync success.
     *
     * 1.42 residual: Sensors promote land between sparse ticks left list seed —
     * see SensorsActivity promoteWatch 2.5s while seed.
     *
     * 1.43 residual: Sensors 340s hard stop left seed after rearm promote —
     * see SensorsActivity open-forever promoteWatch + onResume re-arm.
     *
     * 1.44 residual: Sensors open detect-only / onResume detect-only left seed
     * when LawSeedWake dead — see SensorsActivity open-pull cadence + resume kick.
     *
     * 1.45 residual: front Neural Cube lattice still onResume-only promote after
     * Sensors open-pull — human stayed on main with wake dead left LAW E=0 until
     * leave/return. See CubeContactActivity FRONT_SEED_PULL_EVERY statusTick.
     *
     * 1.46 residual: rear lattice still onCreate one-shot after front/Sensors
     * open-pull — human left rear open with wake dead left LAW HUD E=0 until
     * restart. See RearCubeActivity REAR_SEED_PULL_EVERY lawPoll.
     *
     * 1.68 residual: 1.67 edgePullMax under thermal still let promotePeerWhileSeed
     * → kickBootPull start LawPromoteService multi-minute wave. kickBootPull /
     * LawPromoteService.request refuse wave when allowPromoteWave is false.
     *
     * 1.69 residual: 1.68 still left EDGE multi-pull (max=2) on rearm / wake open
     * poll / host LAW_PULL / promotePeerWhileSeed under thermal → peer HTTP
     * reheat every 15s while seed. edgePullMax 0 under severe = file SoT only
     * (no pullFromNanobot loop). Cool path unchanged.
     *
     * 1.70 residual: 1.69 still left promotePeerWhileSeed spawning law-edge-pull
     * + kickBootPull rearm thrash + CubeGL/Mesh refreshFromPeer under thermal.
     * See {@link #promotePeerWhileSeed} + CubeStability.allowPeerHttp.
     */
    public static boolean pullPeerWhileSeedSync(Context c) {
        if (c == null) return false;
        final Context app;
        try {
            Context a = c.getApplicationContext();
            app = a != null ? a : c;
        } catch (Exception e) {
            return false;
        }
        // 1.67: shrink EDGE multi-pull under thermal severe / screen-off —
        // 8×600ms stacks every 2.5s reheated cool lab while seed.
        // 1.69: max 0 under thermal = file SoT only (no peer HTTP reheat).
        int max = EDGE_PULL_DRAIN_MAX;
        long backoff = EDGE_PULL_BACKOFF_MS;
        try {
            max = CubeStability.edgePullMax(app);
            backoff = CubeStability.edgePullBackoffMs(app);
            if (max > EDGE_PULL_DRAIN_MAX) max = EDGE_PULL_DRAIN_MAX;
            if (backoff < EDGE_PULL_BACKOFF_MS) backoff = EDGE_PULL_BACKOFF_MS;
        } catch (Exception ignored) {}
        if (max <= 0) {
            try {
                if (isLawEnergyPromoted(app)) {
                    try { cancelSeedRearm(app); } catch (Exception ignored) {}
                    try { LawSeedWakeService.stop(app); } catch (Exception ignored) {}
                    return true;
                }
            } catch (Exception ignored) {}
            return false;
        }
        for (int i = 0; i < max; i++) {
            try {
                if (isLawEnergyPromoted(app)) {
                    try { cancelSeedRearm(app); } catch (Exception ignored) {}
                    try { LawSeedWakeService.stop(app); } catch (Exception ignored) {}
                    return true;
                }
            } catch (Exception ignored) {}
            try {
                pullFromNanobot(app);
            } catch (Exception ignored) {}
            try {
                if (isLawEnergyPromoted(app)) {
                    try { cancelSeedRearm(app); } catch (Exception ignored) {}
                    try { LawSeedWakeService.stop(app); } catch (Exception ignored) {}
                    return true;
                }
            } catch (Exception ignored) {}
            if (i + 1 < max) {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        try {
            return isLawEnergyPromoted(app);
        } catch (Exception e) {
            return false;
        }
    }

    public static void promotePeerWhileSeed(Context c) {
        if (c == null) return;
        final Context app;
        try {
            Context a = c.getApplicationContext();
            app = a != null ? a : c;
        } catch (Exception e) {
            return;
        }
        try {
            if (isLawEnergyPromoted(app)) {
                try { cancelSeedRearm(app); } catch (Exception ignored) {}
                try { LawSeedWakeService.stop(app); } catch (Exception ignored) {}
                return;
            }
        } catch (Exception ignored) {}
        // 1.70 residual: under thermal severe, 1.69 left this path spinning
        // law-edge-pull threads + kickBootPull rearm thrash every open-poll /
        // UI tick while edgePullMax=0 (no HTTP but still heat). File SoT only;
        // arm rearm+wake once via kickBootPull thermal path — no worker.
        try {
            if (!CubeStability.allowPeerHttp(app)) {
                try { kickBootPull(app); } catch (Exception ignored) {}
                return;
            }
        } catch (Exception ignored) {}
        synchronized (EDGE_PULL_LOCK) {
            if (sEdgePullRunning) {
                // 1.32: do not drop concurrent edges — re-run after current pull.
                sEdgePullPending = true;
                return;
            }
            sEdgePullRunning = true;
            sEdgePullPending = false;
        }
        new Thread(() -> {
            boolean restart = false;
            int drains = 0;
            try {
                do {
                    try {
                        if (isLawEnergyPromoted(app)) {
                            try { cancelSeedRearm(app); } catch (Exception ignored) {}
                            try { LawSeedWakeService.stop(app); } catch (Exception ignored) {}
                            return;
                        }
                    } catch (Exception ignored) {}
                    // 1.37: EDGE multi-pull (not one HTTP-not-ready miss).
                    // Direct file SoT — not blocked by LawPromoteService mid-sleep.
                    try {
                        if (pullPeerWhileSeedSync(app)) {
                            return;
                        }
                    } catch (Exception ignored) {}
                    // Still seed: arm/ensure promote wave + rearm + wake shell.
                    try {
                        kickBootPull(app);
                    } catch (Exception ignored) {}
                    synchronized (EDGE_PULL_LOCK) {
                        if (sEdgePullPending && drains < EDGE_PULL_DRAIN_MAX) {
                            sEdgePullPending = false;
                            drains++;
                            // 1.33: drain pending after short backoff (not immediate
                            // double-fail on TCP-open / HTTP-not-ready).
                        } else {
                            if (sEdgePullPending && drains >= EDGE_PULL_DRAIN_MAX) {
                                // Keep pending for late restart after worker exit.
                            } else {
                                sEdgePullPending = false;
                            }
                            break;
                        }
                    }
                    try {
                        Thread.sleep(EDGE_PULL_BACKOFF_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } while (true);
            } finally {
                synchronized (EDGE_PULL_LOCK) {
                    sEdgePullRunning = false;
                    // Late edge after loop exit — one restart (pending re-entry).
                    if (sEdgePullPending) {
                        sEdgePullPending = false;
                        restart = true;
                    }
                }
            }
            if (restart) {
                try {
                    Thread.sleep(EDGE_PULL_BACKOFF_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                try {
                    promotePeerWhileSeed(app);
                } catch (Exception ignored) {}
            }
        }, "law-edge-pull").start();
    }

    /** Serialize concurrent promotePeerWhileSeed workers (poll + tick + edges). */
    private static final Object EDGE_PULL_LOCK = new Object();
    private static volatile boolean sEdgePullRunning;
    /** 1.32: concurrent promote while in-flight — re-run once after pull. */
    private static volatile boolean sEdgePullPending;

    /**
     * Retry+tail re-pulls for {@link LawPromoteService}. Each step re-reads dual
     * virtual.tsv; stops when virt_law_energy already &gt; 0 (promote done).
     * Package-visible so the service can run the wave on its worker thread.
     *
     * 1.36 residual: each slot used one {@link #pullFromNanobot} after sleep —
     * TCP-open HTTP-not-ready left seed until the next long delay. Slots now
     * use {@link #pullPeerWhileSeedSync} (EDGE multi-pull SoT).
     */
    static void runPromoteWave(final Context app) {
        if (app == null) return;
        synchronized (BOOT_LOCK) {
            if (sRetryWaveRunning) return;
            sRetryWaveRunning = true;
        }
        try {
            if (isLawEnergyPromoted(app)) return;
            if (!runRetryDelays(app, RETRY_DELAYS_MS)) return;
            if (isLawEnergyPromoted(app)) return;
            runRetryDelays(app, TAIL_DELAYS_MS);
        } finally {
            synchronized (BOOT_LOCK) {
                sRetryWaveRunning = false;
            }
        }
    }

    /**
     * 1.22/1.23: after promote wave exhausts with seed, schedule one chained
     * LAW_PULL wakeup so late peer (:8787) still lands dual virtual.tsv
     * without Sensors/GL and without Magisk/host belt. Resets chain to idx 0.
     * No long-lived service hold; single allow-while-idle alarm (Doze quota).
     */
    static void scheduleSeedRearm(Context c) {
        scheduleSeedRearmAt(c, 0);
        // 1.27–1.33: rearm alone is Doze-deferred; wake shell catches screen/peer.
        // 1.71 residual: under thermal severe, ensure left START_STICKY + 15s
        // peerPortOpen TCP poll reheat while seed. Cool path keeps wake; heat
        // = alarm rearm only (file SoT) until cool.
        try {
            if (CubeStability.allowLawSeedWake(c)) {
                LawSeedWakeService.ensure(c);
            } else {
                LawSeedWakeService.stop(c);
            }
        } catch (Exception ignored) {}
    }

    /**
     * 1.70: true when seed rearm chain is already armed (pref has next_idx).
     * Under thermal severe kickBootPull must not cancel+rearm every open-poll.
     */
    private static boolean isSeedRearmArmed(Context app) {
        if (app == null) return false;
        try {
            return app.getSharedPreferences(PREF_REARM, Context.MODE_PRIVATE)
                .contains(KEY_REARM_IDX);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 1.23/1.24: after a rearm fire still has seed, advance to the next delay.
     * Does not reset index to 0. When past the last slot, re-arm at last index
     * (loop 20m) so late peer still promotes without Sensors/GL/boot (1.24).
     * Cancel on energy promote still clears the chain.
     */
    static void scheduleNextSeedRearm(Context c) {
        if (c == null) return;
        final Context app;
        try {
            Context a = c.getApplicationContext();
            app = a != null ? a : c;
        } catch (Exception e) {
            return;
        }
        try {
            if (isLawEnergyPromoted(app)) {
                cancelSeedRearm(app);
                return;
            }
        } catch (Exception ignored) {}
        int next;
        try {
            next = app.getSharedPreferences(PREF_REARM, Context.MODE_PRIVATE)
                .getInt(KEY_REARM_IDX, 0) + 1;
        } catch (Exception e) {
            next = 1;
        }
        if (next >= REARM_DELAYS_MS.length) {
            // 1.24: do not drop seed forever — loop last delay (20m) only.
            next = REARM_DELAYS_MS.length - 1;
        }
        scheduleSeedRearmAt(app, next);
    }

    /** Arm single next rearm at {@code idx} (0 = first after wave). */
    private static void scheduleSeedRearmAt(Context c, int idx) {
        if (c == null) return;
        if (idx < 0 || idx >= REARM_DELAYS_MS.length) return;
        final Context app;
        try {
            Context a = c.getApplicationContext();
            app = a != null ? a : c;
        } catch (Exception e) {
            return;
        }
        try {
            if (isLawEnergyPromoted(app)) {
                cancelSeedRearm(app);
                return;
            }
        } catch (Exception ignored) {}
        // Cancel any prior single rearm before arming next (Doze quota).
        cancelSeedRearmAlarmOnly(app);
        try {
            app.getSharedPreferences(PREF_REARM, Context.MODE_PRIVATE)
                .edit().putInt(KEY_REARM_IDX, idx).apply();
        } catch (Exception ignored) {}
        AlarmManager am;
        try {
            am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        } catch (Exception e) {
            return;
        }
        if (am == null) return;
        PendingIntent pi = rearmPending(app, idx);
        if (pi == null) return;
        long when = SystemClock.elapsedRealtime() + REARM_DELAYS_MS[idx];
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                // One allow-while-idle only — multi-arm was 1.22 Doze residual.
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
            }
        } catch (Exception ignored) {
            // Cool lab / OEM restrict — fail closed; host belt may still poke.
        }
    }

    /** Cancel seed rearm alarm + clear chain index when virt_law_energy promoted. */
    static void cancelSeedRearm(Context c) {
        if (c == null) return;
        final Context app;
        try {
            Context a = c.getApplicationContext();
            app = a != null ? a : c;
        } catch (Exception e) {
            return;
        }
        cancelSeedRearmAlarmOnly(app);
        try {
            app.getSharedPreferences(PREF_REARM, Context.MODE_PRIVATE)
                .edit().remove(KEY_REARM_IDX).apply();
        } catch (Exception ignored) {}
        // 1.27–1.30: drop screen/idle/tick/peer-pull shell when promote lands.
        try { LawSeedWakeService.stop(app); } catch (Exception ignored) {}
        // 1.22 multi-arm cleanup: cancel legacy request codes so old APK
        // leftovers cannot double-fire after upgrade.
        AlarmManager am;
        try {
            am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        } catch (Exception e) {
            return;
        }
        if (am == null) return;
        for (int i = 0; i < REARM_DELAYS_MS.length; i++) {
            try {
                PendingIntent legacy = rearmPendingLegacy(app, i);
                if (legacy != null) am.cancel(legacy);
            } catch (Exception ignored) {}
        }
    }

    private static void cancelSeedRearmAlarmOnly(Context app) {
        AlarmManager am;
        try {
            am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        } catch (Exception e) {
            return;
        }
        if (am == null) return;
        try {
            PendingIntent pi = rearmPending(app, 0);
            if (pi != null) am.cancel(pi);
        } catch (Exception ignored) {}
    }

    private static PendingIntent rearmPending(Context app, int idx) {
        try {
            Intent i = new Intent(app, LawPullReceiver.class);
            i.setAction(LawPullReceiver.ACTION);
            i.setPackage(app.getPackageName());
            i.putExtra("rearm", true);
            i.putExtra("rearm_idx", idx);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            // Single request code — chain replaces previous PI (Doze quota).
            return PendingIntent.getBroadcast(app, REARM_REQ, i, flags);
        } catch (Exception e) {
            return null;
        }
    }

    /** 1.22 multi-slot request codes — cancel on promote after upgrade. */
    private static PendingIntent rearmPendingLegacy(Context app, int idx) {
        try {
            Intent i = new Intent(app, LawPullReceiver.class);
            i.setAction(LawPullReceiver.ACTION);
            i.setPackage(app.getPackageName());
            i.putExtra("rearm", true);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            return PendingIntent.getBroadcast(app, REARM_REQ + idx, i, flags);
        } catch (Exception e) {
            return null;
        }
    }

    /** @return false if interrupted (stop outer tail); true if delays exhausted. */
    private static boolean runRetryDelays(Context app, long[] delays) {
        for (int i = 0; i < delays.length; i++) {
            try {
                Thread.sleep(delays[i]);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
            try {
                if (isLawEnergyPromoted(app)) return true;
            } catch (Exception ignored) {}
            // 1.36: EDGE multi-pull after each delay (not one HTTP-not-ready miss).
            try {
                if (pullPeerWhileSeedSync(app)) return true;
            } catch (Exception ignored) {
                // keep wave; peer may rise on next delay
            }
        }
        return true;
    }

    /** True when dual file SoT already holds non-seed law energy. */
    static boolean isLawEnergyPromoted(Context c) {
        try {
            for (SensorPrefs.Entry e : SensorPrefs.loadVirtualEntries(c)) {
                if (e != null && "virt_law_energy".equals(e.name) && e.value > 0L) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** After a successful promote, drop seed rearm alarms (1.22). */
    static void onLawEnergyMaybePromoted(Context c) {
        try {
            if (isLawEnergyPromoted(c)) cancelSeedRearm(c);
        } catch (Exception ignored) {}
    }

    /**
     * 1.29 residual: peer-poll direct pull + promote-wave pull can race
     * (stale dual-merge then saveVirtual wipe). Serialize full pull+save.
     */
    private static final Object PULL_LOCK = new Object();

    public static void pullFromNanobot(Context c) throws Exception {
        synchronized (PULL_LOCK) {
            pullFromNanobotLocked(c);
        }
    }

    private static void pullFromNanobotLocked(Context c) throws Exception {
        // 1.71 residual: defense-in-depth — any unguarded caller (promoted
        // 60s refresh / Sync while cool race / belt) must not peer-HTTP under
        // thermal severe. File SoT only; cool path full pull.
        try {
            if (c != null && !CubeStability.allowPeerHttp(c)) {
                return;
            }
        } catch (Exception ignored) {}
        Map<String, Long> m = new LinkedHashMap<>();
        // 1.9 residual: Context-less loadVirtualEntries() was tmp-only after 1.8
        // dual-path — empty/stale tmp then saveVirtual wiped filesDir LAW mirror.
        // Merge tmp+filesDir (LAW counters max) so peer-dead refresh keeps energy.
        for (SensorPrefs.Entry e : SensorPrefs.loadVirtualEntries(c)) {
            if (!e.name.startsWith("virt_nanobot")
                    && !e.name.startsWith("virt_braincube")
                    && !e.name.equals("virt_chat_last_ms")) {
                m.put(e.name, e.value);
            }
        }

        boolean up = false;
        try {
            JSONObject health = get(peerBase() + "/peer/v1/health");
            up = health != null && (health.optBoolean("ok", false)
                || "nanobot-peer".equals(health.optString("service", "")));
        } catch (Exception ignored) {}
        m.put("virt_nanobot_up", up ? 1L : 0L);

        try {
            JSONObject auth = get(peerBase() + "/api/auth");
            if (auth != null) {
                String model = auth.optString("model", "");
                m.put("virt_nanobot_model", model.isEmpty() ? 0L : 1L);
                // crude fingerprint of model path length for lattice motion
                m.put("virt_nanobot_model_len", (long) model.length());
                m.put("virt_nanobot_signed_in", auth.optBoolean("signed_in", false) ? 1L : 0L);
            }
        } catch (Exception ignored) {
            m.put("virt_nanobot_model", 0L);
        }

        JSONObject law = null;
        try {
            JSONObject live = post(peerBase() + "/api/braincube", "{\"action\":\"live\"}");
            // 1.11 residual: only live.ok gated law — flaky live (no ok) left
            // Sensors refresh without promoting peer energy to file while rear
            // StateMatrix could still see action=law. Accept live with meta/law.
            if (live != null && (live.optBoolean("ok", false)
                    || live.has("law") || live.has("meta") || live.has("sensors"))) {
                JSONObject meta = live.optJSONObject("meta");
                if (meta != null) {
                    m.put("virt_braincube_pick", (long) meta.optInt("pick", -1));
                    m.put("virt_braincube_activity",
                        Math.round(meta.optDouble("activity", 0) * 1000.0));
                    m.put("virt_braincube_hits", meta.optLong("hits", 0));
                    m.put("virt_braincube_conflict", meta.optLong("conflict", 0));
                }
                m.put("virt_braincube_seq", live.optLong("seq", 0));
                law = live.optJSONObject("law");
                JSONArray sensors = live.optJSONArray("sensors");
                if (sensors != null) {
                    for (int i = 0; i < sensors.length(); i++) {
                        JSONObject s = sensors.optJSONObject(i);
                        if (s == null) continue;
                        String id = s.optString("id", "s" + i);
                        m.put("virt_bc_" + id + "_value", s.optLong("value", 0));
                        m.put("virt_bc_" + id + "_fire", s.optLong("fire", 0));
                        m.put("virt_bc_" + id + "_act",
                            Math.round(s.optDouble("activity", 0) * 1000.0));
                    }
                }
            }
        } catch (Exception ignored) {}

        // 1.4 / 1.11: dedicated action=law even when live failed or omitted law.
        // Peer-up Sensors refresh must still promote energy into dual file SoT.
        // 1.14 residual: gated on peer /peer/v1/health only (`up`) — health flaky
        // or path-down while /api/braincube still served law left Sensors file SoT
        // behind rear StateMatrix (which always tries action=law). Always try when
        // law missing; dead peer → fail closed (catch). Match StateMatrix SoT.
        if (law == null) {
            try {
                JSONObject lawJ = post(peerBase() + "/api/braincube", "{\"action\":\"law\"}");
                if (lawJ != null) {
                    law = lawJ.optJSONObject("law");
                    if (law == null && lawJ.has("energy")) law = lawJ;
                }
            } catch (Exception ignored) {}
        }
        if (law != null) {
            // 1.10 residual: peer/seed law energy=0 clobbered dual-merge
            // filesDir counters then saveVirtual wiped both mirrors.
            // Counters only rise (max); winner never demotes real→seed -1.
            long prevE = m.containsKey("virt_law_energy") ? m.get("virt_law_energy") : 0L;
            long prevW = m.containsKey("virt_law_wins") ? m.get("virt_law_wins") : 0L;
            long prevL = m.containsKey("virt_law_losses") ? m.get("virt_law_losses") : 0L;
            long prevC = m.containsKey("virt_law_combines") ? m.get("virt_law_combines") : 0L;
            long prevWin = m.containsKey("virt_law_winner") ? m.get("virt_law_winner") : -1L;
            m.put("virt_law_energy",
                SensorPrefs.mergeLawCounter(prevE, law.optLong("energy", 0)));
            m.put("virt_law_wins",
                SensorPrefs.mergeLawCounter(prevW, law.optLong("wins", 0)));
            m.put("virt_law_losses",
                SensorPrefs.mergeLawCounter(prevL, law.optLong("losses", 0)));
            m.put("virt_law_combines",
                SensorPrefs.mergeLawCounter(prevC, law.optLong("combines", 0)));
            m.put("virt_law_winner",
                SensorPrefs.mergeLawWinner(prevWin, law.optLong("winner", -1)));
        }

        m.put("virt_sync_unix", System.currentTimeMillis() / 1000L);
        SensorPrefs.saveVirtual(c, m);
        // 1.22: drop late rearm alarms once dual file SoT holds energy.
        try {
            Long e = m.get("virt_law_energy");
            if (e != null && e > 0L) cancelSeedRearm(c);
        } catch (Exception ignored) {}
    }

    // 1.63 residual: PeerHttp always drains body before disconnect
    // (CLOSE-WAIT peer worker pile after health/pull half-close).
    private static JSONObject get(String url) throws Exception {
        String s = PeerHttp.getBody(url, 800, 2000);
        if (s == null) return null;
        return new JSONObject(s);
    }

    private static JSONObject post(String url, String body) throws Exception {
        String s = PeerHttp.postBody(url, body, 800, 2500);
        if (s == null) return null;
        return new JSONObject(s);
    }
}
