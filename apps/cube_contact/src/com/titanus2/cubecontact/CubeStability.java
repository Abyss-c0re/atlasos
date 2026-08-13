package com.titanus2.cubecontact;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * CUBE STABILITY LAW — THE CUBE SHALL BE STABLE AT ALL COST.
 *
 * @see CUBE_STABILITY_LAW.md
 */
public final class CubeStability {
    private static final String TAG = "CubeStability";

    /** Skin/CPU thermal status ≥ this → severe cool path. */
    private static final int THERMAL_SEVERE = 3; // PowerManager.THERMAL_STATUS_SEVERE
    /**
     * Cool-lab load proxy — same integer gate as pad-agent {@code _allow_dim_belt},
     * cube-ux {@code _allow_dim_shell}, host cool_park (loadavg first field ≥8).
     */
    private static final int LOAD_HEAT = 8;

    private static volatile long sLastThermalCheckMs;
    private static volatile int sCachedThermal = 0;
    private static volatile long sLastLoadCheckMs;
    private static volatile boolean sCachedLoadHeat;
    private static volatile long sLastPeerFailMs;
    private static volatile int sPeerFailStreak;

    private CubeStability() {}

    /** PowerManager thermal status (0–6); 0 if unavailable. */
    public static int thermalStatus(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (now - sLastThermalCheckMs < 3000L) return sCachedThermal;
        sLastThermalCheckMs = now;
        int st = 0;
        try {
            if (c != null && Build.VERSION.SDK_INT >= 29) {
                PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                if (pm != null) st = pm.getCurrentThermalStatus();
            }
        } catch (Throwable ignored) {}
        sCachedThermal = st;
        return st;
    }

    public static boolean isThermalSevere(Context c) {
        return thermalStatus(c) >= THERMAL_SEVERE;
    }

    /**
     * 1.75 residual: cool-lab reheat often shows loadavg ≥8 (wallpaper/am thrash)
     * before {@link #isThermalSevere} flips. Read /proc/loadavg integer part;
     * 2s cache — same class as thermal poll. Fail-open (false) if unreadable.
     */
    public static boolean isLoadHeat() {
        long now = SystemClock.elapsedRealtime();
        if (now - sLastLoadCheckMs < 2000L) return sCachedLoadHeat;
        sLastLoadCheckMs = now;
        boolean heat = false;
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(new java.io.FileReader("/proc/loadavg"));
            String line = br.readLine();
            if (line != null && line.length() > 0) {
                int end = line.length();
                int sp = line.indexOf(' ');
                if (sp > 0) end = sp;
                int dot = line.indexOf('.');
                if (dot > 0 && dot < end) end = dot;
                String head = line.substring(0, end).trim();
                if (head.length() > 0) {
                    int load = Integer.parseInt(head);
                    heat = load >= LOAD_HEAT;
                }
            }
        } catch (Throwable ignored) {
            heat = false;
        } finally {
            if (br != null) {
                try { br.close(); } catch (Throwable ignored) {}
            }
        }
        sCachedLoadHeat = heat;
        return heat;
    }

    /**
     * Cube Stability heat: thermal SEVERE <b>or</b> loadavg ≥8.
     * 1.75 introduced load proxy for dim multi-UID only; 1.77 residual: peer HTTP /
     * promote wave / LawSeedWake / EDGE multi-pull / open-poll / GL FPS still
     * thermal-only → cool-lab reheat before thermal climbs (same class as dim).
     * 1.78 residual: 1.77 call-site gates left PeerHttp open + NanobotBridge
     * chat/health unguarded under heat — PeerHttp.bindAppContext refuse-open.
     * Cool path (load&lt;8 and not thermal) keeps full peer + multi-UID.
     */
    public static boolean isCubeHeat(Context c) {
        if (isThermalSevere(c)) return true;
        return isLoadHeat();
    }

    /**
     * Dim multi-UID / DimGuard park heat — same proxy as {@link #isCubeHeat}.
     * Kept for call-site clarity (wallpaper shell vs peer HTTP).
     */
    public static boolean isDimHeat(Context c) {
        return isCubeHeat(c);
    }

    /** True when device is interactive (screen on / not doze idle). */
    public static boolean isInteractive(Context c) {
        try {
            if (c == null) return true;
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return true;
            return pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Should GL/mesh keep animating?
     * Law: idle when not interactive or cube heat (static lattice OK).
     * 1.77: load≥8 parks animation same as thermal SEVERE (cool-lab residual).
     */
    public static boolean allowAnimation(Context c, boolean userInteracting, boolean hasImpulse) {
        if (isCubeHeat(c)) return userInteracting; // only on touch under heat
        if (!isInteractive(c)) return false;
        return userInteracting || hasImpulse;
    }

    /**
     * Peer HTTP pull interval ms (backoff under fail / cube heat).
     * 1.70 residual: 8s under thermal still reheated open lattice (GL/mesh
     * refreshFromPeer). Under cube heat return long park (file SoT);
     * callers should also gate on {@link #allowPeerHttp}.
     * 1.77: load≥8 same park as thermal (cool-lab reheat before SEVERE).
     */
    public static long peerPullIntervalMs(Context c) {
        // 1.79 residual: 120s + hard refuse left Neural Cube black forever under
        // load≥8 with no kernel cells.bin. Interactive: sparse 12s lattice paint.
        // Screen-off / doze: long park (file SoT only).
        if (isCubeHeat(c)) {
            return isInteractive(c) ? 12_000L : 120_000L;
        }
        long base = 2000L;
        if (!isInteractive(c)) base = Math.max(base, 15000L);
        int fail = sPeerFailStreak;
        if (fail <= 0) return base;
        long back = base * (1L << Math.min(fail, 4));
        return Math.min(back, 60_000L);
    }

    /**
     * 1.67 residual: LawSeedWake / Sensors open-forever still multi-pulled every
     * 2.5s under thermal severe (lab heat after exclusive thrash) — reheated SoC
     * while seed. Cool interactive keeps PEER_EDGE 2500; heat / screen-off → 15s.
     * 1.77: load≥8 also parks poll (thermal-only left peer HTTP under cool-lab load).
     */
    public static long lawOpenPollMs(Context c) {
        if (isCubeHeat(c) || !isInteractive(c)) return 15_000L;
        return 2_500L;
    }

    /**
     * 1.68 residual: 1.67 shrunk EDGE multi-pull + open poll under heat, but
     * open-pull UI / LawSeedWake / Magisk LAW_PULL still called kickBootPull →
     * LawPromoteService full wave (entry multi-pull + runRetryDelays multi-pulls
     * for minutes) reheated cool lab while seed. Process-start only skipped wave
     * (Application). Under cube heat prefer file SoT + light wake shell —
     * no promote-wave service hold until cool.
     * 1.77 residual: 1.68–1.76 still thermal-only; load≥8 left promote wave live.
     */
    public static boolean allowPromoteWave(Context c) {
        return !isCubeHeat(c);
    }

    /**
     * EDGE multi-pull count — 0 under heat (file SoT); shrink when screen-off.
     * 1.69 residual: 1.68 refused LawPromoteService wave under thermal, but
     * rearm / LawSeedWake open poll / host LAW_PULL / promotePeerWhileSeed still
     * ran EDGE multi-pull (edgePullMax=2 × 1.5s HTTP) every 15s / alarm fire →
     * reheat residual while seed. Return 0 under cube heat = file SoT only
     * (no peer HTTP multi-pull). Cool interactive keeps 8×600ms intent=result.
     * 1.77: load≥8 → 0 (same cool-lab proxy as dim).
     */
    public static int edgePullMax(Context c) {
        if (isCubeHeat(c)) return 0;
        if (!isInteractive(c)) return 3;
        return 8;
    }

    /**
     * 1.70 residual: 1.69 closed EDGE multi-pull under thermal, but CubeGL /
     * CubeMesh still HTTP refreshFromPeer every peerPullIntervalMs (8s under
     * heat) while lattice open, and promotePeerWhileSeed still spun
     * law-edge-pull + kickBootPull → scheduleSeedRearm cancel/rearm thrash
     * every 15s open-poll. Cool path full peer.
     * 1.77 residual: thermal-only left GL/mesh HTTP under load≥8 cool-lab reheat.
     * 1.79 residual: hard {@code !isCubeHeat} blacked Neural Cube forever when
     * load stuck ≥8 (nanobot ANR thrash) and kernel sampler absent — peer live
     * energy unused. Park = sparse interval + no promote/dim thrash, not total
     * peer blackout while interactive. Non-interactive under heat still parks.
     */
    public static boolean allowPeerHttp(Context c) {
        if (!isCubeHeat(c)) return true;
        // Front cube / user looking: sparse peer so lattice can paint.
        return isInteractive(c);
    }

    /**
     * User-typed nanobot chat — <b>never</b> park for cube cool/load heat.
     * 1.80 parked chat under load≥8 with message "cube cooling" while the human
     * was deliberately texting via Neural Cube — wrong. Lattice FPS / multi-pull
     * may still cool; explicit chat is product intent=result and must flow.
     * 1.85: always allow (PeerHttp open still needs interactive for other pulls).
     */
    public static boolean allowPeerChat(Context c) {
        return true;
    }

    /**
     * 1.71 residual: 1.70 gated peer HTTP + rearm thrash, but
     * {@code scheduleSeedRearm} / kickBootPull thermal still
     * {@code LawSeedWakeService.ensure} → sticky START_STICKY with
     * peerPortOpen TCP every lawOpenPollMs (15s) + TIME_TICK promote edges
     * while seed under heat (process keep + Socket connect reheat residual).
     * False under cube heat = alarm rearm only (file SoT); no sticky
     * wake shell until cool. Cool path keeps wake for late-peer intent=result.
     * 1.77 residual: thermal-only left sticky wake TCP under load≥8.
     */
    public static boolean allowLawSeedWake(Context c) {
        return !isCubeHeat(c);
    }

    /**
     * 1.72 residual: 1.71 closed LawSeedWake sticky TCP under thermal, but
     * DimGuard still setExactAndAllowWhileIdle every 30s + TIME_TICK multi-UID
     * {@code cmd wallpaper dim-with-uid} (5× Runtime.exec) + 120s process guard
     * → CPU wake + shell fork reheat residual on cool lab while severe.
     * 1.75: also false under load≥8 ({@link #isDimHeat}) so TIME_TICK parks
     * before thermal SEVERE. Cool path keeps 30s exact chain for gray crush
     * intent=result (night OLED).
     * 1.76 residual: false still means "no multi-UID / no arm rearm thrash" — DimGuard
     * TIME_TICK still calls settings-only {@code apply()} under heat (full skip
     * left wallpaper_dim plane unstamped; pad-agent 2.112 SoT).
     */
    public static boolean allowDimTick(Context c) {
        return !isDimHeat(c);
    }

    /**
     * 1.73 residual: 1.72 parked TIME_TICK + 30s exact under thermal, but
     * {@code applyWithBelt} still posted the 6-slot multi-UID wallpaper belt
     * (3s/18s/50s/100s/180s/300s → ~30 Runtime.exec shell forks in 5m) on every
     * process start / DimGuard restart under thermal severe → cool-lab reheat
     * residual after peer/wake/tick paths closed. 1.75: also false under
     * load≥8 ({@link #isDimHeat}). Cool path keeps full belt for gray-crush
     * race intent=result.
     */
    public static boolean allowDimBelt(Context c) {
        return !isDimHeat(c);
    }

    /**
     * 1.74 residual: 1.73 parked delayed BELT_MS under thermal, but
     * {@code armGuard} still posted a process-lifetime Handler that forked
     * multi-UID wallpaper apply every dimGuardMs (even 10m park left sticky
     * process reassert under DimGuard START_STICKY), and DimGuardService
     * onStartCommand always re-applied (5× Runtime.exec) on every sticky
     * restart under thermal severe → cool-lab reheat residual after belt
     * closed. 1.75: also false under load≥8 ({@link #isDimHeat}). Cool path
     * keeps 120s process guard for gray-crush intent=result.
     */
    public static boolean allowDimGuard(Context c) {
        return !isDimHeat(c);
    }

    /**
     * 1.75 residual: 1.74 closed Handler/onStart under thermal SEVERE only, but
     * {@code CubeSurfacePrefs.apply} still always forked 5× {@code cmd wallpaper
     * dim-with-uid} under load≥8 (cool-lab reheat before thermal climbs; host
     * cool_park / pad-agent / cube-ux already load-gated). False under dim heat
     * = settings-only stamp (wallpaper_dim_amount + HW-IME) without Runtime.exec
     * multi-UID. Cool path full multi-UID clear for gray crush intent=result.
     */
    public static boolean allowDimMultiUid(Context c) {
        return !isDimHeat(c);
    }

    /**
     * Dim exact AlarmManager interval ms.
     * Cool: 30s (1.60 Doze residual). Dim heat (thermal OR load≥8): 10m park.
     */
    public static long dimAlarmMs(Context c) {
        if (isDimHeat(c)) return 600_000L;
        return 30_000L;
    }

    /**
     * Dim backup inexact alarm interval ms.
     * Cool: 90s (1.61 dual chain). Dim heat: 15m.
     */
    public static long dimBackupAlarmMs(Context c) {
        if (isDimHeat(c)) return 900_000L;
        return 90_000L;
    }

    /**
     * Process-lifetime dim guard interval ms.
     * Cool: 120s (1.57). Dim heat: unused when {@link #allowDimGuard} false
     * (1.74/1.75 park); 10m only if a cool path left sGuardArmed mid-session.
     */
    public static long dimGuardMs(Context c) {
        if (isDimHeat(c)) return 600_000L;
        return 120_000L;
    }

    /** Backoff between EDGE multi-pulls. */
    public static long edgePullBackoffMs(Context c) {
        if (isCubeHeat(c)) return 1_500L;
        if (!isInteractive(c)) return 1_000L;
        return 600L;
    }

    public static void notePeerOk() {
        sPeerFailStreak = 0;
        sLastPeerFailMs = 0;
    }

    public static void notePeerFail() {
        sPeerFailStreak = Math.min(sPeerFailStreak + 1, 8);
        sLastPeerFailMs = SystemClock.elapsedRealtime();
    }

    public static int peerFailStreak() {
        return sPeerFailStreak;
    }

    /** Status / law HUD tick ms. */
    public static long statusTickMs(Context c, boolean seed) {
        if (isCubeHeat(c)) return seed ? 3000L : 5000L;
        if (!isInteractive(c)) return 8000L;
        return seed ? 1200L : 2500L;
    }

    /**
     * Frame interval for dirty-mode GL kick.
     * Cool path: ~12–20 fps. Heat: ~6–10 fps — energy flows, SoC rests.
     */
    public static long frameIntervalMs(Context c, boolean interacting) {
        if (isCubeHeat(c)) return interacting ? 100L : 160L;
        return interacting ? 55L : 83L;
    }

    public static String statusTag(Context c) {
        int t = thermalStatus(c);
        boolean load = false;
        try { load = isLoadHeat(); } catch (Throwable ignored) {}
        return "cube_stable thermal=" + t
            + " loadHeat=" + load
            + " interactive=" + isInteractive(c)
            + " peerFail=" + sPeerFailStreak;
    }
}
