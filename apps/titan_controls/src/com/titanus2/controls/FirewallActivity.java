package com.titanus2.controls;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.titanus2.controls.fw.FwNative;
import com.titanus2.controls.ui.UiKit;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Firewall — Titan Controls page only (no separate launcher app).
 * <p>
 * Product path (15.53+ / 15.56 seq39):
 * <ul>
 *   <li>ON/OFF/Reset → Atlas Authentication Agent biometrics</li>
 *   <li>Root apply → pad-agent {@code titan2_fw_action} (no Controls su required)</li>
 *   <li>State → world-readable desire + status files (no su for paint)</li>
 *   <li>Optimistic chrome while bio + agent catch up (Remote ADB pattern)</li>
 *   <li>Live section → {@link FwNative#observeOnce} (C NDJSON; 1–2s poll)</li>
 *   <li>System entities → catalog incl. nanobot / atlas / pad-agent</li>
 * </ul>
 * Engine: netfilter owner-uid under {@code fw_OUTPUT} (under any VPN; no VpnService).
 * Fail-open until enable.
 */
public class FirewallActivity extends Activity {
    private static final String TAG = "FirewallUi";
    private static final int REQ_BIO_ENABLE = 7101;
    private static final int REQ_BIO_DISABLE = 7102;
    private static final int REQ_BIO_RESET = 7103;
    /** Free-flow: impulse fw apply; short wait only for UI confirm. */
    private static final long APPLY_WAIT_MS = 1_200L;
    private static final long WORK_TIMEOUT_MS = 45_000L;
    private static final long LIVE_POLL_MS = 1500L;

    private static final String DESIRE_EN = "/data/misc/titan2/fw.enabled";
    private static final String DESIRE_DENY = "/data/misc/titan2/fw.deny";
    private static final String DESIRE_BINS = "/data/misc/titan2/fw.deny.bins";
    private static final String DESIRE_SVCS = "/data/misc/titan2/fw.deny.svcs";
    private static final String STATUS_TMP = "/data/local/tmp/titan2_fw.status";
    private static final String STATUS_MISC = "/data/misc/titan2/titan2_fw.status";
    private static final String LOG_TMP = "/data/local/tmp/titan2_fw.log";

    private static final String[] CLI_BINS = {
        "/system/bin/titan2-fw.sh",
        "/system/bin/titan2-fw",
        "/data/local/tmp/titan2-fw.sh",
        "/data/adb/modules/titan2_fw/system/bin/titan2-fw.sh",
        "/data/adb/modules/titan2_fw/system/bin/titan2-fw",
    };

    private enum Work {
        IDLE, BIO_ENABLE, BIO_DISABLE, BIO_RESET, APPLY_ENABLE, APPLY_DISABLE, APPLY_RESET, APPLY_DENY
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicInteger gen = new AtomicInteger(0);

    private UiKit.Toggle master;
    private TextView banner;
    private TextView statusLine;
    private TextView engineLine;
    private TextView liveBox;
    private LinearLayout entityList;
    private LinearLayout appList;
    private TextView emptyHint;

    private volatile Work work = Work.IDLE;
    private volatile long workSince;
    /** Instant chrome while backend catches up. null = paint from disk. */
    private volatile Boolean optimisticOn;
    private boolean suppressToggle;
    private boolean livePolling;

    private final Runnable workWatchdog = new Runnable() {
        @Override public void run() {
            if (work != Work.IDLE && workSince > 0
                    && System.currentTimeMillis() - workSince > WORK_TIMEOUT_MS) {
                Log.w(TAG, "work timeout " + work);
                optimisticOn = null;
                finishWork("timeout");
            } else if (work != Work.IDLE) {
                h.postDelayed(this, 2000);
            }
        }
    };

    private final Runnable livePoll = new Runnable() {
        @Override public void run() {
            if (!livePolling || isFinishing()) return;
            ex.execute(() -> {
                List<FwNative.Flow> flows = FwNative.observeOnce(40);
                StringBuilder sb = new StringBuilder();
                if (flows.isEmpty()) {
                    sb.append("(no live flows — need observe tip / root)");
                } else {
                    int n = Math.min(12, flows.size());
                    for (int i = 0; i < n; i++) {
                        if (i > 0) sb.append("\n\n");
                        sb.append(flows.get(i).summary());
                    }
                    if (flows.size() > n) {
                        sb.append("\n… +").append(flows.size() - n).append(" more");
                    }
                }
                final String text = sb.toString();
                h.post(() -> {
                    if (liveBox != null) liveBox.setText(text);
                    if (livePolling && !isFinishing()) {
                        h.postDelayed(livePoll, LIVE_POLL_MS);
                    }
                });
            });
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        UiKit.applyOpaqueWindow(this);
        ScrollView sc = new ScrollView(this);
        UiKit.prepareScroll(sc);
        LinearLayout root = new LinearLayout(this);
        UiKit.screen(root);
        sc.addView(root);

        UiKit.title(root, "Firewall");
        UiKit.note(root,
            "Blocks apps (uid), services, and binaries (netfilter). "
                + "Works under any VPN. ON/OFF needs Atlas biometrics. "
                + "Live = C observe cube.");

        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        banner = new TextView(this);
        banner.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        banner.setPadding(pad, pad, pad, pad);
        root.addView(banner, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        UiKit.section(root, "Wall");
        master = UiKit.toggle(root, "Firewall on", isEnabledOnDisk(), this::onMasterChanged);
        statusLine = UiKit.stateLine(root);
        statusLine.setText("Loading…");
        engineLine = UiKit.summary(root);
        engineLine.setText("Engine: pad-agent root apply");

        LinearLayout row = UiKit.row(root);
        UiKit.flexButton(row, "Refresh", this::refreshAll);
        UiKit.flexButton(row, "Reset all", this::userReset);

        // seq39: Live flows (from → to)
        UiKit.section(root, "Live");
        UiKit.note(root, "src:sport → dst:dport · poll ~1.5s · C titan2-fw-observe");
        liveBox = new TextView(this);
        liveBox.setTypeface(Typeface.MONOSPACE);
        liveBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        liveBox.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        liveBox.setText("Starting live…");
        root.addView(liveBox, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // System services / binaries — Deny/Allow → deny-svc|deny-bin (engine SoT)
        UiKit.section(root, "System / services / binaries");
        UiKit.note(root,
            "Deny writes desire fw.deny.svcs / fw.deny.bins. "
                + "System uid (0/1000/1001/2000) = desire-only / privacy-belt — no netfilter DROP. "
                + "Non-system expands to live owner REJECT. "
                + "[protect] needs FORCE — not offered for nanobot/atlas/adbd/pad.");
        entityList = new LinearLayout(this);
        entityList.setOrientation(LinearLayout.VERTICAL);
        root.addView(entityList, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        paintEntities();

        UiKit.section(root, "Apps (third-party)");
        UiKit.note(root, "Deny blocks internet for that app uid. Allow clears the block.");
        emptyHint = UiKit.summary(root);
        emptyHint.setText("Loading packages…");
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        root.addView(appList, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(sc);
        paintFromDisk(true);
        refreshAll();
    }

    private void paintEntities() {
        if (entityList == null) return;
        entityList.removeAllViews();
        Set<String> deniedBins = readDeniedLines(DESIRE_BINS);
        Set<String> deniedSvcs = readDeniedLines(DESIRE_SVCS);
        Set<Integer> deniedUids = readDeniedUids();
        for (String[] row : FwNative.SYSTEM_ENTITIES) {
            if (row == null || row.length < 3) continue;
            final String kind = row[0];
            final String key = row[1];
            final String label = row[2];
            boolean protect = isProtectedEntity(key);
            boolean blocked;
            final String denyAct;
            final String allowAct;
            if ("svc".equals(kind)) {
                blocked = deniedSvcs.contains(key);
                denyAct = "deny-svc";
                allowAct = "allow-svc";
            } else if ("bin".equals(kind)) {
                blocked = deniedBins.contains(key);
                denyAct = "deny-bin";
                allowAct = "allow-bin";
            } else if ("pkg".equals(kind)) {
                int uid = uidForPackage(key);
                blocked = uid > 0 && deniedUids.contains(uid);
                denyAct = "deny-uid";
                allowAct = "allow-uid";
            } else {
                continue;
            }
            LinearLayout rowV = UiKit.row(entityList);
            TextView tv = new TextView(this);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTextColor(UiKit.textColor(this));
            tv.setText(kind + "  " + label
                + (blocked ? "  [DENIED]" : "")
                + (protect ? "  [protect]" : "")
                + "\n    " + key);
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            rowV.addView(tv);
            if (protect) {
                // No Deny button — engine rejects without FORCE=1
                TextView lock = new TextView(this);
                lock.setText("—");
                lock.setTextColor(UiKit.textColor(this));
                rowV.addView(lock);
            } else {
                String btn = blocked ? "Allow" : "Deny";
                final boolean blockedF = blocked;
                UiKit.flexButton(rowV, btn, () -> {
                    if ("pkg".equals(kind)) {
                        int uid = uidForPackage(key);
                        if (uid <= 0) {
                            UiKit.toast(this, "pkg not installed: " + key);
                            return;
                        }
                        if (blockedF) {
                            queueDenyAllow(allowAct, uid, label);
                        } else {
                            queueDenyAllow(denyAct, uid, label);
                        }
                    } else {
                        if (blockedF) {
                            queueEntityAction(allowAct, key, label);
                        } else {
                            queueEntityAction(denyAct, key, label);
                        }
                    }
                });
            }
        }
    }

    private static boolean isProtectedEntity(String key) {
        if (key == null) return true;
        return "nanobot".equals(key) || key.startsWith("nanobot")
            || "atlas".equals(key) || "adbd".equals(key)
            || "titan2-pad-agent".equals(key)
            || "com.titanus2.atlas".equals(key)
            || "com.titanus2.nanobot".equals(key)
            || "com.titanus2.controls".equals(key);
    }

    private int uidForPackage(String pkg) {
        try {
            return getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Deny/allow svc or bin by name (pad-agent → titan2-fw). */
    private void queueEntityAction(String action, String key, String label) {
        if (work != Work.IDLE && work != Work.APPLY_DENY) {
            UiKit.toast(this, "busy");
            return;
        }
        beginWork(Work.APPLY_DENY);
        setWorking(true);
        if (statusLine != null) {
            statusLine.setText((action.startsWith("deny") ? "Denying " : "Allowing ")
                + label + "…");
        }
        final int my = gen.incrementAndGet();
        final String[] a = new String[]{action, key};
        busy.set(true);
        ex.execute(() -> {
            String payload = joinArgs(a) + " " + System.currentTimeMillis();
            AgentBridge.put(FirewallActivity.this, AgentBridge.FW_ACTION, payload);
            try {
                android.provider.Settings.Global.putString(
                    getContentResolver(), AgentBridge.FW_ACTION, payload);
            } catch (Exception ignored) {
            }
            execFwBestEffort(a);
            long t0 = System.currentTimeMillis();
            boolean ok = false;
            String path = action.contains("svc") ? DESIRE_SVCS : DESIRE_BINS;
            while (System.currentTimeMillis() - t0 < APPLY_WAIT_MS) {
                Set<String> lines = readDeniedLines(path);
                if (action.startsWith("deny") && lines.contains(key)) {
                    ok = true;
                    break;
                }
                if (action.startsWith("allow") && !lines.contains(key)) {
                    ok = true;
                    break;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    break;
                }
            }
            final boolean okF = ok;
            h.post(() -> {
                busy.set(false);
                if (my != gen.get()) return;
                paintEntities();
                paintFromDisk(true);
                UiKit.toast(FirewallActivity.this, okF
                    ? ((action.startsWith("deny") ? "Denied " : "Allowed ") + label)
                    : "error: " + action + " " + key + " did not stick");
                finishWork(okF ? "ok" : "fail");
                setWorking(false);
            });
        });
    }

    private static Set<String> readDeniedLines(String path) {
        Set<String> out = new HashSet<>();
        String blob = readFile(path);
        if (blob == null || blob.isEmpty()) {
            // tip mirrors
            if (DESIRE_SVCS.equals(path)) {
                blob = readFile("/data/local/tmp/titan2_fw.deny.svcs");
            } else if (DESIRE_BINS.equals(path)) {
                blob = readFile("/data/local/tmp/titan2_fw.deny.bins");
            }
        }
        if (blob == null) return out;
        for (String line : blob.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            // strip optional comment
            int sp = line.indexOf(' ');
            if (sp > 0) line = line.substring(0, sp);
            int tab = line.indexOf('\t');
            if (tab > 0) line = line.substring(0, tab);
            out.add(line.trim());
        }
        return out;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (work == Work.IDLE && optimisticOn == null) {
            paintFromDisk(false);
        }
        refreshAll();
        livePolling = true;
        h.removeCallbacks(livePoll);
        h.post(livePoll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        livePolling = false;
        h.removeCallbacks(livePoll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        livePolling = false;
        h.removeCallbacks(workWatchdog);
        h.removeCallbacks(livePoll);
        ex.shutdownNow();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_BIO_ENABLE && requestCode != REQ_BIO_DISABLE
                && requestCode != REQ_BIO_RESET) {
            return;
        }
        if (resultCode != RESULT_OK) {
            optimisticOn = null;
            finishWork("bio denied");
            UiKit.toast(this, "biometrics denied");
            return;
        }
        if (requestCode == REQ_BIO_ENABLE) {
            beginWork(Work.APPLY_ENABLE);
            paintOptimisticOn();
            queueApply(new String[]{"enable"}, true);
        } else if (requestCode == REQ_BIO_DISABLE) {
            beginWork(Work.APPLY_DISABLE);
            paintOptimisticOff();
            queueApply(new String[]{"disable"}, false);
        } else {
            beginWork(Work.APPLY_RESET);
            optimisticOn = false;
            paintOptimisticOff();
            queueApply(new String[]{"reset"}, false);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && !event.isAltPressed() && !event.isCtrlPressed()) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_ESCAPE:
                    finish();
                    return true;
                case KeyEvent.KEYCODE_R:
                    refreshAll();
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void onMasterChanged(boolean wantOn) {
        if (suppressToggle) return;
        if (wantOn) {
            userWantsOn();
        } else {
            userWantsOff();
        }
    }

    private void userWantsOn() {
        if (isEnabledOnDisk() && optimisticOn == null) {
            snapToggle(true);
            paintFromDisk(true);
            return;
        }
        // Instant product chrome — switch stays ON while bio + agent run
        optimisticOn = true;
        beginWork(Work.BIO_ENABLE);
        snapToggle(true);
        paintOptimisticOn();
        setWorking(true);
        launchBio(REQ_BIO_ENABLE, "Enable Titan firewall");
    }

    private void userWantsOff() {
        optimisticOn = false;
        beginWork(Work.BIO_DISABLE);
        snapToggle(false);
        paintOptimisticOff();
        setWorking(true);
        launchBio(REQ_BIO_DISABLE, "Disable Titan firewall");
    }

    private void userReset() {
        optimisticOn = false;
        beginWork(Work.BIO_RESET);
        snapToggle(false);
        paintOptimisticOff();
        if (statusLine != null) statusLine.setText("Reset — confirm biometrics…");
        setWorking(true);
        launchBio(REQ_BIO_RESET, "Reset Titan firewall (clear denies)");
    }

    private void launchBio(int req, String reason) {
        wakeAtlas();
        try {
            Intent i = new Intent();
            i.setClassName("com.titanus2.atlas", "com.titanus2.atlas.AuthPromptActivity");
            i.putExtra("auth_id", "fw-" + req + "-" + System.currentTimeMillis());
            i.putExtra("auth_reason", reason);
            i.putExtra("auth_for_result", true);
            //noinspection deprecation
            startActivityForResult(i, req);
        } catch (Exception e) {
            Log.w(TAG, "atlas auth", e);
            optimisticOn = null;
            finishWork("no atlas");
            UiKit.toast(this, "Atlas auth missing — install Atlas");
        }
    }

    private void wakeAtlas() {
        try {
            Intent wake = new Intent();
            wake.setClassName("com.titanus2.atlas", "com.titanus2.atlas.AtlasSessionService");
            wake.setAction("com.titanus2.atlas.ENSURE_AUTH_AGENT");
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(wake);
            } else {
                startService(wake);
            }
        } catch (Exception ignored) {
        }
    }

    private void queueApply(String[] args, boolean wantOn) {
        if (!busy.compareAndSet(false, true)) {
            // Already applying — still honour latest desire via new gen
            Log.i(TAG, "queue while busy: " + joinArgs(args));
        }
        final int my = gen.incrementAndGet();
        final String[] a = args;
        final boolean targetOn = wantOn;
        if (statusLine != null) {
            statusLine.setText(targetOn ? "Arming wall…" : "Opening wall…");
        }
        ex.execute(() -> {
            // Product path: pad-agent root queue (no Controls Magisk grant required).
            String joined = joinArgs(a);
            String nonce = String.valueOf(System.currentTimeMillis());
            // Format: action [args] <epoch> — agent strips trailing 10+ digit nonce
            String payload = joined + " " + nonce;
            boolean wrote = AgentBridge.put(FirewallActivity.this,
                AgentBridge.FW_ACTION, payload);
            try {
                android.provider.Settings.Global.putString(
                    getContentResolver(), AgentBridge.FW_ACTION, payload);
            } catch (Exception ignored) {
            }
            // Optional direct su (if Magisk already granted Controls)
            execFwBestEffort(a);

            boolean ok = waitDesire(targetOn, a[0], APPLY_WAIT_MS);
            if (my != gen.get()) {
                busy.set(false);
                return;
            }
            FwSnapshot snap = snapshot();
            h.post(() -> {
                busy.set(false);
                if (my != gen.get()) return;
                optimisticOn = null;
                paintSnapshot(snap);
                if (ok) {
                    String msg;
                    if ("enable".equals(a[0])) {
                        msg = snap.denyCount > 0
                            ? ("Firewall ON · " + snap.denyCount + " denied")
                            : "Firewall ON · deny apps below to block";
                    } else if ("disable".equals(a[0])) {
                        msg = "Firewall OFF (fail-open)";
                    } else if ("reset".equals(a[0])) {
                        msg = "Firewall reset";
                    } else {
                        msg = "OK";
                    }
                    UiKit.toast(FirewallActivity.this, msg);
                    finishWork(ok ? "ok" : "done");
                } else {
                    String why = diagnoseFail(wrote, snap);
                    if (statusLine != null) statusLine.setText(why);
                    UiKit.toast(FirewallActivity.this, why);
                    finishWork("fail");
                }
                setWorking(false);
            });
        });
    }

    private String diagnoseFail(boolean wrote, FwSnapshot snap) {
        AgentBridge.AgentLive live = AgentBridge.agentLive();
        if (!live.ok) {
            return "error: pad-agent not live — reboot or reinstall hybrid";
        }
        if (findCli() == null) {
            return "error: titan2-fw missing on device";
        }
        if (!wrote) {
            return "error: could not queue action (plane write failed)";
        }
        return "error: wall did not stick (agent live, state="
            + (snap.enabled ? "on" : "off") + " denies=" + snap.denyCount + ")";
    }

    private void refreshAll() {
        ex.execute(() -> {
            FwSnapshot snap = snapshot();
            h.post(() -> {
                if (work != Work.IDLE && optimisticOn != null) {
                    // Keep optimistic chrome; only refresh app list
                    paintApps(snap.apps, snap.denied);
                    if (engineLine != null) engineLine.setText(engineLabel(snap.cli));
                    return;
                }
                paintSnapshot(snap);
            });
        });
    }

    private void paintFromDisk(boolean force) {
        FwSnapshot snap = snapshot();
        paintSnapshot(snap);
    }

    private void paintSnapshot(FwSnapshot snap) {
        snapToggle(snap.enabled);
        paintBanner(snap.enabled, snap.denyCount);
        if (statusLine != null) {
            if (snap.enabled) {
                statusLine.setText(snap.denyCount > 0
                    ? ("ON · " + snap.denyCount + " denied · " + snap.vpn)
                    : ("ON · armed (0 denied) · Deny apps below · " + snap.vpn));
            } else {
                statusLine.setText("OFF (fail-open) · " + snap.denyCount
                    + " in list · " + snap.vpn);
            }
        }
        if (engineLine != null) engineLine.setText(engineLabel(snap.cli));
        paintEntities();
        paintApps(snap.apps, snap.denied);
    }

    private void paintOptimisticOn() {
        paintBanner(true, -1);
        if (statusLine != null) statusLine.setText("ON — confirm biometrics / applying…");
    }

    private void paintOptimisticOff() {
        paintBanner(false, -1);
        if (statusLine != null) statusLine.setText("OFF — confirm biometrics / applying…");
    }

    private void paintBanner(boolean on, int denyCount) {
        if (banner == null) return;
        if (on) {
            String body = denyCount < 0
                ? "ON\napplying…"
                : (denyCount > 0
                    ? ("ON\n" + denyCount + " app" + (denyCount == 1 ? "" : "s") + " denied")
                    : "ON\narmed — deny apps below");
            banner.setText(body);
            banner.setTextColor(0xFFE8F5E9);
            banner.setBackgroundColor(0xFF1B5E20);
        } else {
            banner.setText(denyCount < 0 ? "OFF\napplying…" : "OFF\nfail-open");
            banner.setTextColor(0xFFFFEBEE);
            banner.setBackgroundColor(0xFFB71C1C);
        }
    }

    private String engineLabel(String cli) {
        AgentBridge.AgentLive live = AgentBridge.agentLive();
        String agent = live.ok ? ("agent live · " + live.ageSec + "s")
            : ("agent STALE · " + live.lineSnippet);
        if (cli == null) {
            return "Engine: NOT FOUND\n" + agent;
        }
        if (cli.startsWith("/system/bin")) {
            return "Engine: " + cli + "\n" + agent;
        }
        if (cli.contains("/data/local/tmp")) {
            return "Engine: tip " + cli + "\n" + agent;
        }
        return "Engine: " + cli + "\n" + agent;
    }

    private void paintApps(List<AppRow> apps, Set<Integer> denied) {
        if (appList == null) return;
        appList.removeAllViews();
        if (apps.isEmpty()) {
            if (emptyHint != null) {
                emptyHint.setVisibility(View.VISIBLE);
                emptyHint.setText("No third-party apps found");
            }
            return;
        }
        if (emptyHint != null) emptyHint.setVisibility(View.GONE);
        for (AppRow a : apps) {
            boolean blocked = denied.contains(a.uid);
            LinearLayout row = UiKit.row(appList);
            TextView label = new TextView(this);
            label.setText(a.label + "\n" + a.pkg + "  uid " + a.uid);
            label.setTextSize(14f);
            label.setTextColor(UiKit.textColor(this));
            label.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(label);
            String btn = blocked ? "Allow" : "Deny";
            UiKit.flexButton(row, btn, () -> {
                if (blocked) {
                    queueDenyAllow("allow-uid", a.uid, a.label);
                } else {
                    queueDenyAllow("deny-uid", a.uid, a.label);
                }
            });
        }
    }

    private void queueDenyAllow(String action, int uid, String label) {
        if (work != Work.IDLE && work != Work.APPLY_DENY) {
            UiKit.toast(this, "busy");
            return;
        }
        beginWork(Work.APPLY_DENY);
        setWorking(true);
        if (statusLine != null) {
            statusLine.setText(("deny-uid".equals(action) ? "Denying " : "Allowing ")
                + label + "…");
        }
        final int my = gen.incrementAndGet();
        final String[] a = new String[]{action, String.valueOf(uid)};
        busy.set(true);
        ex.execute(() -> {
            String payload = joinArgs(a) + " " + System.currentTimeMillis();
            AgentBridge.put(FirewallActivity.this, AgentBridge.FW_ACTION, payload);
            try {
                android.provider.Settings.Global.putString(
                    getContentResolver(), AgentBridge.FW_ACTION, payload);
            } catch (Exception ignored) {
            }
            execFwBestEffort(a);
            // Wait until desire list matches
            long t0 = System.currentTimeMillis();
            boolean ok = false;
            while (System.currentTimeMillis() - t0 < APPLY_WAIT_MS) {
                Set<Integer> denied = readDeniedUids();
                if ("deny-uid".equals(action) && denied.contains(uid)) {
                    ok = true;
                    break;
                }
                if ("allow-uid".equals(action) && !denied.contains(uid)) {
                    ok = true;
                    break;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    break;
                }
            }
            FwSnapshot snap = snapshot();
            final boolean okF = ok;
            h.post(() -> {
                busy.set(false);
                if (my != gen.get()) return;
                paintSnapshot(snap);
                UiKit.toast(FirewallActivity.this, okF
                    ? (("deny-uid".equals(action) ? "Denied " : "Allowed ") + label)
                    : "error: " + action + " did not stick");
                finishWork(okF ? "ok" : "fail");
                setWorking(false);
            });
        });
    }

    private void beginWork(Work w) {
        work = w;
        workSince = System.currentTimeMillis();
        h.removeCallbacks(workWatchdog);
        h.postDelayed(workWatchdog, 2000);
    }

    private void finishWork(String why) {
        Log.i(TAG, "finishWork " + why + " was=" + work);
        work = Work.IDLE;
        workSince = 0;
        h.removeCallbacks(workWatchdog);
        if (optimisticOn != null) {
            // leave until paint settles
            optimisticOn = null;
        }
        setWorking(false);
        paintFromDisk(true);
    }

    private void setWorking(boolean on) {
        if (master != null) {
            // Keep toggle interactive for OFF supersede; disable only mid-bio feels harsh.
            // Remote ADB disables; we keep enabled so OFF can interrupt.
            master.setEnabled(true);
        }
    }

    private void snapToggle(boolean on) {
        if (master == null) return;
        suppressToggle = true;
        try {
            master.setChecked(on);
        } finally {
            suppressToggle = false;
        }
    }

    /** Wait until desire/status matches target. */
    private boolean waitDesire(boolean wantOn, String action, long maxMs) {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < maxMs) {
            boolean on = isEnabledOnDisk();
            if ("reset".equals(action)) {
                // reset → off + empty deny
                if (!on && readDeniedUids().isEmpty()) return true;
            } else if (wantOn && on) {
                return true;
            } else if (!wantOn && !on) {
                return true;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                break;
            }
        }
        boolean on = isEnabledOnDisk();
        if ("reset".equals(action)) return !on;
        return wantOn == on;
    }

    private static boolean isEnabledOnDisk() {
        // Desire file is world-readable (666) — no su needed
        String v = readFirstLine(DESIRE_EN);
        if (v != null) {
            v = v.trim().toLowerCase();
            if ("on".equals(v) || "1".equals(v) || "true".equals(v) || "yes".equals(v)) {
                return true;
            }
            if ("off".equals(v) || "0".equals(v) || "false".equals(v) || "no".equals(v)) {
                return false;
            }
        }
        // Fall back to status file
        String st = readStatusBlob();
        return parseEnabled(st);
    }

    private FwSnapshot snapshot() {
        FwSnapshot s = new FwSnapshot();
        s.enabled = isEnabledOnDisk();
        s.denied = readDeniedUids();
        s.denyCount = s.denied.size();
        s.cli = findCli();
        s.vpn = "no VPN iface";
        String st = readStatusBlob();
        if (st != null) {
            for (String line : st.split("\n")) {
                if (line.startsWith("vpn_iface=")) {
                    s.vpn = line.substring("vpn_iface=".length()).trim();
                    break;
                }
            }
            // also accept short engine form "enforcing enabled=on denies=N"
            if (st.contains("enabled=on") || st.startsWith("enforcing")) {
                s.enabled = true;
            }
        }
        s.apps = loadThirdPartyApps();
        return s;
    }

    private static final class FwSnapshot {
        boolean enabled;
        int denyCount;
        String vpn = "no VPN iface";
        String cli;
        Set<Integer> denied = new HashSet<>();
        List<AppRow> apps = new ArrayList<>();
    }

    private static boolean parseEnabled(String status) {
        if (status == null) return false;
        for (String line : status.split("\n")) {
            String t = line.trim();
            if (t.startsWith("enabled=on") || t.contains(" enabled=on")
                    || t.startsWith("enforcing")) {
                return true;
            }
        }
        return false;
    }

    private static String readStatusBlob() {
        for (String path : new String[]{STATUS_TMP, STATUS_MISC}) {
            String s = readFile(path);
            if (s != null && !s.isEmpty()) return s;
        }
        return null;
    }

    private Set<Integer> readDeniedUids() {
        Set<Integer> set = new HashSet<>();
        for (String path : new String[]{DESIRE_DENY, "/data/local/tmp/titan2_fw.deny"}) {
            File f = new File(path);
            if (!f.isFile()) continue;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.replaceAll("#.*", "").trim();
                    if (line.isEmpty()) continue;
                    String[] p = line.split("\\s+");
                    try {
                        set.add(Integer.parseInt(p[0]));
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return set;
    }

    private List<AppRow> loadThirdPartyApps() {
        List<AppRow> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps;
        try {
            apps = pm.getInstalledApplications(0);
        } catch (Exception e) {
            return out;
        }
        for (ApplicationInfo ai : apps) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            String pkg = ai.packageName;
            if (pkg == null) continue;
            if (pkg.startsWith("com.titanus2.")) continue;
            if ("com.tailscale.ipn".equals(pkg)) continue; // never brick VPN
            int uid = ai.uid;
            CharSequence lab = ai.loadLabel(pm);
            AppRow r = new AppRow();
            r.pkg = pkg;
            r.uid = uid;
            r.label = lab != null ? lab.toString() : pkg;
            out.add(r);
        }
        out.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        if (out.size() > 80) {
            return new ArrayList<>(out.subList(0, 80));
        }
        return out;
    }

    private static final class AppRow {
        String pkg;
        String label;
        int uid;
    }

    private static String joinArgs(String[] a) {
        if (a == null || a.length == 0) return "";
        StringBuilder sb = new StringBuilder(a[0]);
        for (int i = 1; i < a.length; i++) sb.append(' ').append(a[i]);
        return sb.toString();
    }

    /** CubalC free-flow: run titan2-fw NOW (not wait pad-agent poll). */
    private void execFwBestEffort(String[] args) {
        if (args == null || args.length == 0) return;
        // Impulse first — agent re-assert only
        try {
            ImpulseSnap.fw(args);
        } catch (Exception e) {
            Log.w(TAG, "fw impulse", e);
        }
        // Keep legacy direct path as second breath
        String bin = findCli();
        if (bin == null) return;
        StringBuilder sb = new StringBuilder();
        // titan2-fw is executable, not a shell script path for `sh`
        if (bin.endsWith(".sh")) {
            sb.append("sh ").append(bin);
        } else {
            sb.append(bin);
        }
        for (String a : args) {
            sb.append(' ').append(shellQuote(a));
        }
        runProcess(new String[]{"su", "-c", sb.toString()});
    }

    private static String shellQuote(String s) {
        if (s == null) return "''";
        if (s.matches("^[A-Za-z0-9_./:=+-]+$")) return s;
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String findCli() {
        for (String p : CLI_BINS) {
            if (new File(p).isFile()) return p;
        }
        return null;
    }

    private static String readFirstLine(String path) {
        String s = readFile(path);
        if (s == null) return null;
        int n = s.indexOf('\n');
        return n >= 0 ? s.substring(0, n) : s;
    }

    private static String readFile(String path) {
        File f = new File(path);
        if (!f.isFile()) return null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            String s = sb.toString().trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private static String runProcess(String[] cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int n = 0;
                while ((line = br.readLine()) != null && n < 300) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                    n++;
                }
            }
            boolean finished = p.waitFor(20, TimeUnit.SECONDS);
            if (!finished) {
                p.destroy();
                return "error: timeout";
            }
            String body = sb.toString().trim();
            if (p.exitValue() != 0 && body.isEmpty()) {
                return "error: exit " + p.exitValue();
            }
            return body.isEmpty() ? "(ok)" : body;
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }
}
