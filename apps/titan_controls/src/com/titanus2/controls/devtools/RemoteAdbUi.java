package com.titanus2.controls.devtools;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.titanus2.controls.AgentBridge;
import com.titanus2.controls.ui.UiKit;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Product Remote ADB control.
 * <p>
 * Display SoT: {@code /data/local/tmp/remote_adb.status} (and legacy mirror).<br>
 * Engine: {@code titan2-remote-adb.sh} via root / pad-agent.
 * <p>
 * Toggle rules (stabilize):
 * <ul>
 *   <li>User gesture always accepted when idle</li>
 *   <li>While working: switch disabled (no ghost taps / no paint fight)</li>
 *   <li>OFF always forces work even if a prior op stalled</li>
 *   <li>Paint never moves the switch while working</li>
 *   <li>Status string only updates banner when it changes</li>
 * </ul>
 */
final class RemoteAdbUi {
    private static final String TAG = "RemoteAdbUi";
    static final int REQ_BIO_ON = 0xADB0;
    static final int REQ_BIO_PAIR = 0xADB1;
    private static final int PORT = 5555;
    private static final long WORK_TIMEOUT_MS = 45_000L;
    private static final String STATUS = "/data/local/tmp/remote_adb.status";
    private static final String STATUS_LEGACY = "/data/local/tmp/titan2_wireless_adb_status";
    private static final String[] REMOTE_BINS = {
        "/data/local/tmp/titan2-remote-adb.sh",
        "/system/bin/titan2-remote-adb.sh",
    };
    private static final String[] DEV_BINS = {
        "/data/local/tmp/titan2-dev-action-live.sh",
        "/system/bin/titan2-dev-action.sh",
    };

    private enum Work {
        IDLE, BIO_ON, BIO_PAIR, APPLY_ON, APPLY_OFF, APPLY_PAIR, APPLY_CANCEL
    }

    private final Activity act;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView banner;
    private TextView pinPanel;
    private TextView detail;
    private TextView clientsView;
    private TextView cancelBtn;
    private UiKit.Toggle toggle;
    private static final String CLIENTS = "/data/local/tmp/remote_adb.clients";

    private volatile Work work = Work.IDLE;
    private volatile long workSince;
    private final AtomicInteger gen = new AtomicInteger(0);
    /** Human hit Cancel — block late bio/pair apply from re-arming PIN. */
    private volatile boolean pairAbort;
    /**
     * Instant product chrome: switch/banner follow this while backend catches up.
     * null = paint strictly from disk status.
     */
    private volatile Boolean optimisticOn;
    private String lastBanner = "\0";
    private String lastPin = "\0";
    private String lastDetail = "\0";
    private String lastPaintedStatus = "\0";

    private final Runnable workWatchdog = new Runnable() {
        @Override public void run() {
            if (work != Work.IDLE && workSince > 0
                    && System.currentTimeMillis() - workSince > WORK_TIMEOUT_MS) {
                Log.w(TAG, "work timeout " + work);
                finishWork(readStatus(), "timeout");
            } else if (work != Work.IDLE) {
                h.postDelayed(this, 2000);
            }
        }
    };

    RemoteAdbUi(Activity act) {
        this.act = act;
    }

    void build(LinearLayout root) {
        UiKit.section(root, "Remote ADB");
        int pad = Math.round(12 * act.getResources().getDisplayMetrics().density);

        banner = new TextView(act);
        banner.setTypeface(Typeface.MONOSPACE);
        banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        banner.setPadding(pad, pad, pad, pad);
        root.addView(banner, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        toggle = UiKit.toggle(root, "Remote ADB", statusIsOn(readStatus()), wantOn -> {
            // Switch already flipped in the widget — keep that look immediately.
            // OFF always supersedes; ON stacks only if idle.
            if (!wantOn) {
                userWantsOff();
                return;
            }
            if (work != Work.IDLE && work != Work.BIO_ON) {
                // Keep visual ON; backend already busy
                snapToggle(true);
                paintOptimisticOn();
                return;
            }
            userWantsOn();
        });

        pinPanel = new TextView(act);
        pinPanel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pinPanel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        pinPanel.setGravity(android.view.Gravity.CENTER);
        pinPanel.setPadding(pad, pad * 2, pad, pad * 2);
        pinPanel.setTextColor(0xFFFFF8E1);
        pinPanel.setBackgroundColor(0xFF0D47A1);
        pinPanel.setVisibility(View.GONE);
        root.addView(pinPanel, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        UiKit.button(root, "Pair new PC", this::userWantsPair);
        cancelBtn = UiKit.button(root, "Cancel pairing", this::userWantsPairCancel);
        cancelBtn.setVisibility(View.GONE);
        UiKit.button(root, "Copy command", this::copyCmd);
        detail = UiKit.mono(root);
        TextView note = UiKit.mono(root);
        note.setText("ON needs biometrics · first new host needs Atlas auth\n"
            + "OFF drops remote TCP (USB stays) · Pair: adb pair IP:PORT PIN");

        UiKit.section(root, "Clients");
        clientsView = UiKit.mono(root);
        clientsView.setText("none");
        UiKit.button(root, "Forget all hosts", this::userWantsForgetClients);
        paintClients();

        paintFromStatus(readStatus(), true);
        setWorking(false);
    }

    void onResumeTick() {
        paintClients();
        String st = readStatus();
        // Cancel must win: never re-paint PIN chrome while cancelling
        if (work == Work.APPLY_CANCEL || pairAbort) {
            if ("cancelled".equals(readPairState()) || !statusIsPairing(st)) {
                if (work != Work.IDLE) {
                    finishWork(st, "tick-cancel-done");
                } else {
                    paintFromStatus(st, true);
                }
            }
            return;
        }
        // While waiting for pair success, always follow status (pairing → on)
        if (work == Work.APPLY_PAIR || work == Work.BIO_PAIR || statusIsPairing(st)) {
            if (statusIsOn(st) || st.startsWith("error") || st.equals("off")) {
                // Engine finished — clear work mask if still stuck
                if (work != Work.IDLE) {
                    finishWork(st, "tick-pair-done");
                    toastResult(st);
                } else {
                    paintFromStatus(st, true);
                }
            } else if (statusIsPairing(st) && !"cancelled".equals(readPairState())) {
                paintFromStatus(st, true);
            }
            return;
        }
        // Idle + no optimistic mask: sync from disk only.
        if (work == Work.IDLE && optimisticOn == null) {
            paintFromStatus(st, false);
        }
    }

    boolean onActivityResult(int requestCode, int resultCode) {
        if (requestCode != REQ_BIO_ON && requestCode != REQ_BIO_PAIR) return false;

        if (resultCode != Activity.RESULT_OK) {
            optimisticOn = null;
            pairAbort = false;
            finishWork(readStatus(), "bio denied");
            UiKit.toast(act, "biometrics denied");
            return true;
        }
        if (requestCode == REQ_BIO_ON) {
            // Stay visually ON; apply quietly
            beginWork(Work.APPLY_ON);
            paintOptimisticOn();
            applyAsync("on", PORT, "arm_wireless_adb_trusted " + PORT);
        } else {
            // Cancel during/after bio must not start a new PIN session
            if (pairAbort || work == Work.APPLY_CANCEL) {
                finishWork(readStatus(), "pair aborted before apply");
                return true;
            }
            beginWork(Work.APPLY_PAIR);
            showBanner("PAIRING…", 0xFFFFB74D, 0xFF3E2723);
            if (cancelBtn != null) cancelBtn.setVisibility(View.VISIBLE);
            applyAsync("pair", PORT, "pair_remote_adb_trusted " + PORT);
        }
        return true;
    }

    void keyOn() { userWantsOn(); }
    void keyOff() { userWantsOff(); }
    void keyCopy() { copyCmd(); }

    private void userWantsForgetClients() {
        new Thread(() -> {
            execRemote("clients_forget", -1);
            h.post(this::paintClients);
        }, "remote-adb-forget").start();
        UiKit.toast(act, "hosts forgotten");
    }

    private void paintClients() {
        if (clientsView == null) return;
        String raw = readFile(CLIENTS);
        if (raw == null || raw.trim().isEmpty()) {
            clientsView.setText("none");
            return;
        }
        StringBuilder sb = new StringBuilder();
        String[] lines = raw.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        clientsView.setText(sb.length() == 0 ? "none" : sb.toString());
    }

    private static String readFile(String path) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line.trim());
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // --- user intents ---

    private void userWantsOn() {
        String st = readStatus();
        if (statusIsOn(st)) {
            optimisticOn = null;
            snapToggle(true);
            paintFromStatus(st, true);
            return;
        }
        // Instant product chrome — switch + green banner now; engine later
        optimisticOn = true;
        beginWork(Work.BIO_ON);
        snapToggle(true);
        paintOptimisticOn();
        writeOptimisticStatus("on " + guessEndpoint());
        setWorking(true);
        launchBio(REQ_BIO_ON, "Remote ADB · TCP :" + PORT);
    }

    private void userWantsOff() {
        // Instant red OFF — Cube sees truth-shaped chrome immediately
        optimisticOn = false;
        beginWork(Work.APPLY_OFF);
        snapToggle(false);
        hidePin();
        paintOptimisticOff();
        // Must be busy so applyAsync waits for engine listen-gone, not the lie.
        writeOptimisticStatus("busy off");
        setWorking(true);
        applyAsync("off", -1, "disable_wireless_adb");
    }

    private void userWantsPair() {
        if (work != Work.IDLE && work != Work.BIO_PAIR) {
            return;
        }
        pairAbort = false;
        beginWork(Work.BIO_PAIR);
        showBanner("PAIRING…", 0xFFFFB74D, 0xFF3E2723);
        showPin("…");
        if (cancelBtn != null) cancelBtn.setVisibility(View.VISIBLE);
        setWorking(true);
        launchBio(REQ_BIO_PAIR, "Pair new ADB host · PIN");
    }

    private void userWantsPairCancel() {
        pairAbort = true;
        beginWork(Work.APPLY_CANCEL);
        hidePin();
        if (cancelBtn != null) cancelBtn.setVisibility(View.GONE);
        // Instant disk signal so waiter + ticks leave pairing even if su is slow
        writeLine("/data/local/tmp/titan2_adb_pair_state", "cancelled");
        boolean keepOn = Boolean.TRUE.equals(optimisticOn)
            || statusIsOn(readStatus())
            || "on".equals(readDesire());
        optimisticOn = keepOn;
        if (keepOn) {
            paintOptimisticOn();
            writeOptimisticStatus("on " + guessEndpoint());
        } else {
            paintOptimisticOff();
            writeOptimisticStatus("off");
        }
        setWorking(true);
        applyAsync("pair_cancel", -1, "cancel_pair_remote_adb");
    }

    /** Instant ON chrome (full green — no "wait" theater). */
    private void paintOptimisticOn() {
        String ep = guessEndpoint();
        showBanner("ON\n" + ep, 0xFFE8F5E9, 0xFF1B5E20);
        showDetail("adb connect " + ep);
        hidePin();
    }

    private void paintOptimisticOff() {
        showBanner("OFF", 0xFFFFEBEE, 0xFFB71C1C);
        showDetail("OFF");
        hidePin();
    }

    private String guessEndpoint() {
        String st = readStatus();
        if (statusIsOn(st) && st.length() > 3) return st.substring(3).trim();
        // Prefer last known / loopback until engine fills real IP
        return "127.0.0.1:" + PORT;
    }

    /** Best-effort status file so tick/other readers see the mask (tmp is world-writable). */
    private void writeOptimisticStatus(String line) {
        try {
            File f = new File(STATUS);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
        } catch (Exception ignored) {
        }
        try {
            File f = new File(STATUS_LEGACY);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
        } catch (Exception ignored) {
        }
        lastPaintedStatus = line;
    }

    private void launchBio(int req, String reason) {
        wakeAtlas();
        try {
            Intent i = new Intent();
            i.setClassName("com.titanus2.atlas", "com.titanus2.atlas.AuthPromptActivity");
            i.putExtra("auth_id", "remote-" + req + "-" + System.currentTimeMillis());
            i.putExtra("auth_reason", reason);
            i.putExtra("auth_for_result", true);
            //noinspection deprecation
            act.startActivityForResult(i, req);
        } catch (Exception e) {
            finishWork(readStatus(), "no atlas");
            UiKit.toast(act, "Atlas auth missing");
        }
    }

    // --- apply engine ---

    private void applyAsync(String remoteCmd, int port, String devAction) {
        final int my = gen.get();
        final boolean isCancel = remoteCmd != null && remoteCmd.contains("cancel");
        final boolean waitPair = remoteCmd != null && remoteCmd.startsWith("pair")
            && !isCancel;
        // Pad-agent queue (root without app su grant)
        AgentBridge.put(act, AgentBridge.DEV_ACTION, devAction + " " + System.currentTimeMillis());
        new Thread(() -> {
            // Cancel: disk flag first, then engine (pair may hold flock)
            if (isCancel) {
                writeLine("/data/local/tmp/titan2_adb_pair_state", "cancelled");
            }
            boolean ran = execRemote(remoteCmd, port);
            if (!ran) {
                execDevAction(devAction);
            }
            // Wait for terminal status.
            // Pairing: status becomes "pairing …" quickly — that is NOT terminal.
            // Keep watching until on/off/error/cancel (up to ~3 min).
            String st = readStatus();
            int max = waitPair ? 200 : (isCancel ? 24 : 48);
            for (int i = 0; i < max; i++) {
                if (gen.get() != my) return; // superseded
                if (waitPair && pairAbort) {
                    // Human cancelled mid-wait — leave for cancel thread
                    return;
                }
                st = readStatus();
                if (st == null) st = "off";
                if (waitPair) {
                    if (statusIsOn(st) || st.equals("off") || st.startsWith("off")
                            || st.startsWith("error")
                            || "cancelled".equals(readPairState())) {
                        break;
                    }
                    // still pairing — refresh chrome from disk without ending work
                    final String mid = st;
                    if (i % 2 == 0) {
                        h.post(() -> {
                            if (gen.get() != my || pairAbort) return;
                            if (statusIsPairing(mid) && !"cancelled".equals(readPairState())) {
                                paintFromStatus(mid, true);
                            }
                        });
                    }
                } else if (isCancel) {
                    // Terminal when left pairing OR pair_state cancelled + non-pairing status
                    if ("cancelled".equals(readPairState())
                            && (statusIsOn(st) || st.equals("off") || st.startsWith("error"))) {
                        break;
                    }
                    if (!statusIsPairing(st) && !st.startsWith("busy")) break;
                } else {
                    if (!st.startsWith("busy") && !statusIsPairing(st)) break;
                }
                try {
                    Thread.sleep(waitPair ? 1000 : 200);
                } catch (InterruptedException e) {
                    break;
                }
            }
            final String finalSt = st != null ? st : "off";
            h.post(() -> {
                if (gen.get() != my) return;
                if (isCancel) pairAbort = false;
                finishWork(finalSt, isCancel ? "cancel-done" : null);
                if (!isCancel) toastResult(finalSt);
                else UiKit.toast(act, "Pairing cancelled");
            });
        }, "remote-adb-apply").start();
    }

    private static String readPairState() {
        String s = readLine("/data/local/tmp/titan2_adb_pair_state");
        return s != null ? s : "";
    }

    private static String readDesire() {
        String s = readLine("/data/misc/titan2/remote_adb.desire");
        return s != null ? s.trim() : "";
    }

    private static void writeLine(String path, String body) {
        try {
            File f = new File(path);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write((body + "\n").getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, false);
        } catch (Exception ignored) {
        }
    }

    private boolean execRemote(String cmd, int port) {
        for (String bin : REMOTE_BINS) {
            if (!new File(bin).isFile()) continue;
            if (runSu(bin, cmd, port >= 0 ? String.valueOf(port) : null)) return true;
        }
        return false;
    }

    private void execDevAction(String actionLine) {
        String[] parts = actionLine.trim().split("\\s+");
        for (String bin : DEV_BINS) {
            if (!new File(bin).isFile()) continue;
            if (runSuParts(bin, parts)) return;
        }
    }

    private boolean runSu(String script, String cmd, String arg) {
        String[][] forms = arg != null
            ? new String[][]{
                {"su", "0", "sh", script, cmd, arg},
                {"su", "-c", "sh " + script + " " + cmd + " " + arg},
            }
            : new String[][]{
                {"su", "0", "sh", script, cmd},
                {"su", "-c", "sh " + script + " " + cmd},
            };
        for (String[] cmdLine : forms) {
            if (runProcess(cmdLine)) return true;
        }
        return false;
    }

    private boolean runSuParts(String script, String[] actionParts) {
        // su 0 sh script verb [args…]
        String[] cmd = new String[4 + actionParts.length];
        cmd[0] = "su";
        cmd[1] = "0";
        cmd[2] = "sh";
        cmd[3] = script;
        System.arraycopy(actionParts, 0, cmd, 4, actionParts.length);
        if (runProcess(cmd)) return true;
        // su -c 'sh script verb args'
        StringBuilder sb = new StringBuilder("sh ");
        sb.append(script);
        for (String p : actionParts) sb.append(' ').append(p);
        return runProcess(new String[]{"su", "-c", sb.toString()});
    }

    private boolean runProcess(String[] cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            drain(p.getInputStream());
            boolean finished = p.waitFor(35, TimeUnit.SECONDS);
            if (!finished) {
                p.destroy();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            Log.w(TAG, "exec fail", e);
            return false;
        }
    }

    // --- work lifecycle ---

    private void beginWork(Work w) {
        gen.incrementAndGet();
        work = w;
        workSince = System.currentTimeMillis();
        h.removeCallbacks(workWatchdog);
        h.postDelayed(workWatchdog, 2000);
    }

    private void finishWork(String status, String reason) {
        work = Work.IDLE;
        workSince = 0;
        h.removeCallbacks(workWatchdog);
        setWorking(false);
        // Drop mask only after engine reports; then paint truth
        optimisticOn = null;
        String st = status != null ? status : readStatus();
        // Stale "pairing …" after cancel must not win paint
        if (statusIsPairing(st) && ("cancelled".equals(readPairState()) || pairAbort)) {
            st = "on".equals(readDesire()) ? ("on " + guessEndpoint()) : "off";
        }
        paintFromStatus(st, true);
        if (reason != null) Log.i(TAG, "finishWork " + reason + " st=" + st);
    }

    private void setWorking(boolean working) {
        if (toggle != null) {
            toggle.setEnabled(true);
            if (toggle.sw != null) {
                // Full opacity always — no "slow" dim that tips off latency
                toggle.sw.setAlpha(1f);
            }
        }
    }

    private void snapToggleToStatus() {
        snapToggle(statusIsOn(readStatus()) || statusIsPairing(readStatus()));
    }

    private void snapToggle(boolean on) {
        if (toggle != null) toggle.setChecked(on);
    }

    // --- paint ---

    private void paintFromStatus(String st, boolean force) {
        if (st == null || st.isEmpty()) st = "off";
        // Optimistic mask wins over stale disk until finishWork clears it
        if (optimisticOn != null && !force) {
            if (optimisticOn) paintOptimisticOn();
            else paintOptimisticOff();
            if (toggle != null) snapToggle(optimisticOn);
            return;
        }
        if (!force && st.equals(lastPaintedStatus) && work == Work.IDLE
                && optimisticOn == null) {
            return;
        }
        lastPaintedStatus = st;

        // Cancelled pair_state means leave PIN mode even if status lagging
        boolean pairing = statusIsPairing(st) && !"cancelled".equals(readPairState()) && !pairAbort;
        boolean on = statusIsOn(st);
        boolean err = st.startsWith("error");

        if (cancelBtn != null) {
            cancelBtn.setVisibility(pairing ? View.VISIBLE : View.GONE);
        }

        // Only move switch when idle and not masked
        if (work == Work.IDLE && optimisticOn == null && toggle != null) {
            toggle.setChecked(on || pairing);
        }

        if (pairing) {
            String rest = st.length() > 8 ? st.substring(8).trim() : "";
            String[] bits = rest.split("\\s+");
            String pin = bits.length > 0 ? bits[0] : "";
            String host = bits.length > 1 ? bits[1] : "";
            if (pin.length() >= 4) {
                showPin("PIN\n" + pin + "\n\nadb pair " + host + " " + pin);
                showBanner("PAIRING", 0xFFFFF8E1, 0xFF0D47A1);
                showDetail("adb pair " + host + " " + pin + "\nCancel to stop");
            } else {
                showBanner("PAIRING…", 0xFFFFB74D, 0xFF3E2723);
            }
            return;
        }

        hidePin();
        if (st.startsWith("busy")) {
            // Keep intermediate banner if we already set one
            return;
        }
        if (on) {
            String ep = st.length() > 3 ? st.substring(3).trim() : "";
            showBanner(ep.isEmpty() ? "ON" : ("ON\n" + ep), 0xFFE8F5E9, 0xFF1B5E20);
            showDetail(ep.isEmpty() ? "ON" : ("adb connect " + ep));
        } else if (err) {
            showBanner(st, 0xFFFFEBEE, 0xFFB71C1C);
            showDetail(st);
        } else {
            showBanner("OFF", 0xFFFFEBEE, 0xFFB71C1C);
            showDetail("OFF");
        }
    }

    private void toastResult(String st) {
        if (st == null) return;
        if (statusIsOn(st)) {
            // Pair or ON completed for real
            String prev = lastPaintedStatus;
            if (prev != null && prev.startsWith("pairing")) {
                UiKit.toast(act, "Paired · ADB ON");
            }
        } else if (st.startsWith("pairing ")) {
            UiKit.toast(act, "PIN ready");
        } else if (st.startsWith("error")) {
            UiKit.toast(act, st);
        }
    }

    private void showBanner(String t, int fg, int bg) {
        if (banner == null) return;
        if (t.equals(lastBanner)) return;
        lastBanner = t;
        banner.setText(t);
        banner.setTextColor(fg);
        banner.setBackgroundColor(bg);
    }

    private void showPin(String t) {
        if (pinPanel == null) return;
        if (!t.equals(lastPin)) {
            lastPin = t;
            pinPanel.setText(t);
        }
        pinPanel.setVisibility(View.VISIBLE);
    }

    private void hidePin() {
        if (pinPanel != null) pinPanel.setVisibility(View.GONE);
        lastPin = "\0";
    }

    private void showDetail(String t) {
        if (detail == null) return;
        if (t.equals(lastDetail)) return;
        lastDetail = t;
        detail.setText(t);
    }

    // --- status ---

    private static boolean statusIsOn(String st) {
        return st != null && st.startsWith("on");
    }

    private static boolean statusIsPairing(String st) {
        return st != null && st.startsWith("pairing");
    }

    private static String readStatus() {
        String s = readLine(STATUS);
        if (s == null || s.isEmpty()) s = readLine(STATUS_LEGACY);
        if (s == null || s.isEmpty()) return "off";
        return s.trim();
    }

    private static String readLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            return line != null ? line.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void copyCmd() {
        String st = readStatus();
        String clip;
        if (statusIsPairing(st)) {
            String rest = st.length() > 8 ? st.substring(8).trim() : "";
            String[] bits = rest.split("\\s+");
            clip = bits.length >= 2
                ? ("adb pair " + bits[1] + " " + bits[0])
                : st;
        } else if (statusIsOn(st) && st.length() > 3) {
            clip = "adb connect " + st.substring(3).trim();
        } else {
            clip = "Remote ADB is OFF";
        }
        try {
            ClipboardManager cm = (ClipboardManager)
                act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("adb", clip));
                UiKit.toast(act, "copied");
                return;
            }
        } catch (Exception ignored) {
        }
        UiKit.toast(act, clip);
    }

    private void wakeAtlas() {
        try {
            Intent wake = new Intent();
            wake.setClassName("com.titanus2.atlas", "com.titanus2.atlas.AtlasSessionService");
            wake.setAction("com.titanus2.atlas.ENSURE_AUTH_AGENT");
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                act.startForegroundService(wake);
            } else {
                act.startService(wake);
            }
        } catch (Exception ignored) {
        }
    }

    private static void drain(InputStream in) {
        if (in == null) return;
        try {
            byte[] buf = new byte[256];
            while (in.read(buf) >= 0) { /* drain */ }
        } catch (Exception ignored) {
        }
    }
}
