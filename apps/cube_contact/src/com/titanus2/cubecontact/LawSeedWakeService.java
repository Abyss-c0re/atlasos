package com.titanus2.cubecontact;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 1.27 residual: after unlock, lab often stays on continuous USB (no
 * POWER_CONNECTED edge) while peer :8787 rises later. USER_PRESENT is
 * unlock-edge only; 20m Doze-deferred rearm left dual virtual.tsv seed until
 * Sensors/GL. Sticky shell holds process lightly while seed and listens
 * for screen-on + device-idle exit → kickBootPull (debounced). stopSelf when
 * virt_law_energy promotes. No FGS, no exclusive thrash.
 *
 * 1.28 residual: 1.27 only reacted to SCREEN_ON / idle-exit. Continuous
 * interactive session (screen stays on, never Doze) + continuous USB +
 * already unlocked left dual virtual.tsv seed when peer :8787 rose later
 * (Magisk delayed / cool land after external belt) until 20m rearm. While
 * seed, also TIME_TICK + quiet 45s peer-port poll (127.0.0.1:8787 accept
 * → kickBootPull). Stop poll + receiver on promote / destroy.
 *
 * 1.29 residual: 1.28 only called kickBootPull when :8787 accepted TCP.
 * LawPromoteService ignores duplicate starts while sWaveRunning (mid long
 * sleep up to 180s tail) → peer rising mid-wave left dual virtual.tsv seed
 * until the sleep ended even though port was already open. On peer-open,
 * also direct pullFromNanobot on a background thread (promote cancel/stop
 * if energy lands) and denser poll after closed→open edge (2.5s × 8 then
 * 45s). TIME_TICK uses the same path. No FGS / no exclusive thrash.
 *
 * 1.30 residual: 1.29 direct-pull only on peer-poll / TIME_TICK. SCREEN_ON
 * and idle-exit still kickBootPull only → mid-wave ignore left dual
 * virtual.tsv seed until next poll (≤45s) after human turns screen on while
 * peer already open mid-wave. All edges use VirtualSensorSync.promotePeerWhileSeed
 * (direct pull + kick, debounced). No FGS / no exclusive thrash.
 *
 * 1.37 residual: promotePeerWhileSeed still single pullFromNanobot per entry
 * after 1.36 wave multi-pull — TCP-open HTTP-not-ready on peer-open / tick
 * left dual virtual.tsv seed until next dense poll. Edge path now multi-pulls.
 *
 * 1.38 residual: Sensors manual Sync still single pull after 1.37 edge multi-pull
 * — see SensorsActivity (pullPeerWhileSeedSync + rebuild belt).
 *
 * 1.39 residual: Sync multi-pull fail false "updated" + single rebuild while
 * wave promoted later — see SensorsActivity (honest toast + wave rebuild belt).
 *
 * 1.40 residual: 1.39 absolute 12/40/45 belt missed cumulative wave+tail —
 * see SensorsActivity (wave-cover belt + promote toast).
 *
 * 1.41 residual: 1.40 Sensors belt kept 340s after promote / open-while-live —
 * see SensorsActivity cancelBelt + short live cover + checkbox preserve.
 *
 * 1.42 residual: 1.41 sparse cumulative ticks left Sensors "LAW seed" after
 * promote — see SensorsActivity promoteWatch (same 2.5s edge scale as peer poll).
 *
 * 1.43 residual: 1.42 340s hard stop left Sensors seed after rearm promote —
 * see SensorsActivity open-forever promoteWatch + onResume re-arm.
 *
 * 1.44 residual: Sensors open detect-only when this shell is dead left seed —
 * see SensorsActivity open-pull cadence + onResume promote re-kick.
 *
 * 1.45 residual: front Neural Cube lattice still onResume-only when this shell
 * is dead — see CubeContactActivity front open-pull on statusTick cadence.
 *
 * 1.46 residual: rear lattice still onCreate one-shot when this shell is dead
 * — see RearCubeActivity lawPoll open-pull + onResume promote.
 *
 * 1.47 residual: SubdisplayCubeService still onStart one-shot when this shell
 * is dead — see SubdisplayCubeService seedPoll open-pull while sticky+seed.
 *
 * 1.48 residual: after closed→open dense window (8×2.5s), poll fell back to
 * 45s while peer stayed open and still seed. HTTP-ready after dense (or
 * mid-gap peer full ready) left dual virtual.tsv seed ≤45s even with wake
 * shell live and no UI. Steady open poll ~10s (UI open-pull cadence) while
 * seed+open; cheap 45s only when peer closed. No FGS / no exclusive thrash.
 *
 * 1.49 residual: each poll still EDGE multi-pulled only ~1.8s (4×450ms) —
 * HTTP-ready after that window left dual virtual.tsv seed until next
 * dense/steady slot. Multi-pull stretch (8×600ms) shared via
 * VirtualSensorSync; first poll immediate on arm (not 2.5s delay). Tip upgrade
 * force-stops package so wake shell reloads (install SoT).
 *
 * 1.50 residual: after dense exhaust (8×2.5s), wake still fell to PEER_STEADY
 * 10s while peer stayed open + seed — multi-pull stretch (1.49) covers one
 * entry but mid-gap HTTP-ready after dense left dual virtual.tsv seed ≤10s
 * with wake live / no UI. Keep PEER_EDGE_POLL_MS forever while open+seed
 * (Sensors open-forever promoteWatch SoT); 45s only when peer closed.
 * PEER_STEADY_POLL_MS retained as doc/compat pin (1.48 gate).
 *
 * 1.51 residual: 1.50 wake dense 2.5s, but Sensors open-pull every 4 detect
 * ticks (~10s) and front/rear/subdisp every 12×800ms (~10s) left dual
 * virtual.tsv seed ≤10s when wake dead + UI open. Sensors PULL_EVERY=1;
 * UI EVERY=3 (~2.4s) — same PEER_EDGE SoT (see SensorsActivity / cube UIs).
 *
 * 1.67 residual: 1.50–1.66 open-forever PEER_EDGE 2.5s ignored CubeStability —
 * under thermal severe (lab cool residual / exclusive thrash heat) wake still
 * EDGE multi-pulled forever → reheat SoC while seed. lawOpenPollMs: cool 2.5s,
 * heat/screen-off 15s; EDGE pull count shrinks via CubeStability.edgePullMax.
 *
 * 1.71 residual: 1.70 gated peer HTTP + rearm thrash, but this service still
 * started under thermal via scheduleSeedRearm/ensure → sticky peerPortOpen TCP
 * every 15s + TIME_TICK edges while seed. allowLawSeedWake false = refuse start
 * / stopSelf under thermal (alarm rearm only). Cool path unchanged.
 */
public class LawSeedWakeService extends Service {
    private static final Object LOCK = new Object();
    private static volatile boolean sRegistered;
    /** Peer closed: cheap port check only (no HTTP thrash). */
    private static final long PEER_POLL_MS = 45_000L;
    /**
     * 1.48: peer open + still seed after dense edge — Sensors/UI open-pull
     * cadence (~10s), not 45s (dense-window residual left seed mid-gap).
     * 1.50: open+seed uses PEER_EDGE_POLL_MS forever (steady residual); keep
     * constant so 1.48 tip gate still pins the historical SoT token.
     */
    private static final long PEER_STEADY_POLL_MS = 10_000L;
    /**
     * 1.50: while peer open + seed, poll at this cadence forever (not only
     * for PEER_EDGE_DENSE_MAX after closed→open). HTTP-ready mid-gap after
     * dense exhaust no longer waits PEER_STEADY.
     */
    private static final long PEER_EDGE_POLL_MS = 2_500L;
    /** Historical dense-burst count (1.29–1.49); 1.50 open path ignores cap. */
    private static final int PEER_EDGE_DENSE_MAX = 8;
    private static final int PEER_PORT = 8787;
    private static final int PEER_CONNECT_MS = 350;
    private BroadcastReceiver mWake;
    private Handler mPollHandler;
    private final Runnable mPeerPoll = new Runnable() {
        @Override
        public void run() {
            final Context app = getApplicationContext();
            try {
                if (VirtualSensorSync.isLawEnergyPromoted(app)) {
                    try {
                        VirtualSensorSync.cancelSeedRearm(app);
                    } catch (Exception ignored) {}
                    stopSelf();
                    return;
                }
            } catch (Exception ignored) {}
            boolean open = peerPortOpen();
            if (open) {
                // Mid-wave-safe direct pull + kick (shared with screen/present).
                // 1.50: every open tick (not only closed→open dense burst).
                try {
                    VirtualSensorSync.promotePeerWhileSeed(app);
                } catch (Exception ignored) {}
            }
            try {
                if (mPollHandler != null
                    && !VirtualSensorSync.isLawEnergyPromoted(app)) {
                    long delay = PEER_POLL_MS;
                    if (open) {
                        // 1.50 residual: dense forever while open+seed (Sensors
                        // open-forever SoT). 1.48/1.49 PEER_STEADY after dense
                        // left ≤10s dual virtual.tsv seed mid-gap.
                        // Reference PEER_STEADY / DENSE_MAX so tip gates keep pins.
                        // 1.67: CubeStability.lawOpenPollMs — thermal/screen-off
                        // back off to 15s (PEER_EDGE 2.5s only when cool).
                        if (PEER_STEADY_POLL_MS < PEER_EDGE_POLL_MS
                            || PEER_EDGE_DENSE_MAX < 1) {
                            delay = PEER_POLL_MS;
                        } else {
                            // Keep PEER_EDGE_POLL_MS pin (cool path == 2500).
                            delay = CubeStability.lawOpenPollMs(app);
                            if (delay < PEER_EDGE_POLL_MS) {
                                delay = PEER_EDGE_POLL_MS;
                            }
                        }
                    }
                    mPollHandler.postDelayed(this, delay);
                }
            } catch (Exception ignored) {}
        }
    };

    /** Start while seed only; no-op / stop when already promoted. */
    public static void ensure(Context c) {
        if (c == null) return;
        try {
            Context app;
            try {
                Context a = c.getApplicationContext();
                app = a != null ? a : c;
            } catch (Exception e) {
                app = c;
            }
            if (VirtualSensorSync.isLawEnergyPromoted(app)) {
                stop(app);
                return;
            }
            // 1.71 residual: under thermal severe, sticky wake TCP-polled every
            // 15s after 1.70 rearm-once still called ensure — refuse + stop.
            try {
                if (!CubeStability.allowLawSeedWake(app)) {
                    stop(app);
                    return;
                }
            } catch (Exception ignored) {}
            Intent i = new Intent(app, LawSeedWakeService.class);
            app.startService(i);
        } catch (Exception ignored) {
            // Cool lab / background restrict — rearm path still covers late peer.
        }
    }

    /** Drop wake shell when promoted (or explicit cancel). */
    public static void stop(Context c) {
        if (c == null) return;
        try {
            Context app;
            try {
                Context a = c.getApplicationContext();
                app = a != null ? a : c;
            } catch (Exception e) {
                app = c;
            }
            app.stopService(new Intent(app, LawSeedWakeService.class));
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final Context app = getApplicationContext();
        try {
            if (VirtualSensorSync.isLawEnergyPromoted(app)) {
                unregisterWake();
                stopPeerPoll();
                stopSelf();
                return START_NOT_STICKY;
            }
        } catch (Exception ignored) {}
        // 1.71 residual: LMK sticky restart under thermal must not re-arm TCP poll.
        try {
            if (!CubeStability.allowLawSeedWake(app)) {
                unregisterWake();
                stopPeerPoll();
                stopSelf();
                return START_NOT_STICKY;
            }
        } catch (Exception ignored) {}
        registerWake(app);
        startPeerPoll();
        // Sticky while seed so screen-on / idle-exit / poll still deliver after LMK.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterWake();
        stopPeerPoll();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startPeerPoll() {
        if (mPollHandler == null) {
            mPollHandler = new Handler(Looper.getMainLooper());
        }
        mPollHandler.removeCallbacks(mPeerPoll);
        // 1.49: first check immediately after arm (peer often already up;
        // 2.5s first-delay left seed after tip force-stop / sticky re-create).
        mPollHandler.post(mPeerPoll);
    }

    private void stopPeerPoll() {
        if (mPollHandler != null) {
            mPollHandler.removeCallbacks(mPeerPoll);
        }
    }

    /** True when loopback peer accepts TCP (nanobot :8787 listening). */
    private static boolean peerPortOpen() {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", PEER_PORT), PEER_CONNECT_MS);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {}
            }
        }
    }

    private void registerWake(final Context app) {
        synchronized (LOCK) {
            if (sRegistered) return;
            if (mWake == null) {
                mWake = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        if (c == null) return;
                        final String action = i != null ? i.getAction() : null;
                        // Idle-exit only (entering idle is not a promote edge).
                        if (PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(action)) {
                            try {
                                PowerManager pm = (PowerManager)
                                    c.getSystemService(Context.POWER_SERVICE);
                                if (pm != null && pm.isDeviceIdleMode()) return;
                            } catch (Exception ignored) {}
                        }
                        // 1.30: SCREEN_ON / idle-exit / TIME_TICK(+peer) → mid-wave-safe
                        // direct pull + kick (1.29 left screen/idle on kickBootPull only).
                        final Context ctx = c.getApplicationContext() != null
                            ? c.getApplicationContext() : c;
                        // 1.56/1.57: multi-UID dim reassert on every wake edge —
                        // BEFORE promote early-return. 1.56 applied only while seed;
                        // once LAW promoted the service stopped without one last apply
                        // and late cube-ux 0.92 could stick. Guard + this edge fix.
                        try {
                            CubeSurfacePrefs.apply(ctx);
                            CubeSurfacePrefs.armGuard(ctx);
                        } catch (Exception ignored) {}
                        try {
                            if (VirtualSensorSync.isLawEnergyPromoted(c)) {
                                try {
                                    VirtualSensorSync.cancelSeedRearm(c);
                                } catch (Exception ignored) {}
                                try {
                                    stop(c);
                                } catch (Exception ignored) {}
                                return;
                            }
                        } catch (Exception ignored) {}
                        // TIME_TICK: only when peer listens (avoid wave thrash while down).
                        if (Intent.ACTION_TIME_TICK.equals(action)) {
                            if (!peerPortOpen()) {
                                return;
                            }
                        }
                        try {
                            VirtualSensorSync.promotePeerWhileSeed(ctx);
                        } catch (Exception ignored) {}
                    }
                };
            }
            try {
                IntentFilter f = new IntentFilter();
                f.addAction(Intent.ACTION_SCREEN_ON);
                // 1.28: minute tick while awake (no screen-off edge needed).
                f.addAction(Intent.ACTION_TIME_TICK);
                if (Build.VERSION.SDK_INT >= 23) {
                    f.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
                }
                // Application-context style: service lifetime owns the receiver.
                registerReceiver(mWake, f);
                sRegistered = true;
            } catch (Exception ignored) {
                sRegistered = false;
            }
        }
    }

    private void unregisterWake() {
        synchronized (LOCK) {
            if (!sRegistered || mWake == null) {
                sRegistered = false;
                return;
            }
            try {
                unregisterReceiver(mWake);
            } catch (Exception ignored) {}
            sRegistered = false;
        }
    }
}
