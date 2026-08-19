package com.titanus2.nanobot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Provider settings: Grok web (device-code) sign-in lives here — not on the chat
 * screen. Local GGUF / OpenAI-compatible are separate cards. Agent peer is
 * infrastructure for tools/API; it is not "looking for a local LLM server".
 *
 * Product path: {@link NanobotCli} (no HTTP peer for Grok).
 * HTTP PeerClient is only for optional MCP list/save when LAN share is on.
 */
public class ProvidersActivity extends Activity {
    private static final int C_BG = 0xFF0B0B0F;
    private static final int C_PANEL = 0xFF14141A;
    private static final int C_ACCENT = 0xFF00E5FF;
    private static final int C_FG = 0xFFF2F2F5;
    private static final int C_MUT = 0xFF8B8B9A;
    private static final int C_LINE = 0xFF22222C;
    private static final int C_OK = 0xFF0E2A1C;
    private static final int C_WARN = 0xFF2A2410;
    private static final int C_GROK = 0xFF121A22;
    private static final String PREF_AUTH = "grok_device_auth";
    private static final String K_URI = "pending_uri";
    private static final String K_CODE = "pending_code";
    private static final int AUTH_POLL_MS = 4000;
    private static final int AUTH_POLL_MAX = 120; // ~8 min

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Separate from io so status refreshes never starve device-code polls. */
    private final ExecutorService authIo = Executors.newSingleThreadExecutor();
    private PeerClient peer;
    private LinearLayout list;
    private TextView routeStatus;
    private TextView modeHint;
    private TextView grokStatus;
    private TextView grokAuthHint;
    private Button grokSignInBtn;
    private Button grokRegenBtn;
    private Button grokOpenLinkBtn;
    private Button grokCopyCodeBtn;
    private Runnable authPoll;
    private String pendingAuthUri = "";
    private String pendingUserCode = "";
    private volatile boolean destroyed;
    private volatile boolean authPolling;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        peer = new PeerClient(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(12), dp(12), dp(12));
        head.setBackgroundColor(C_PANEL);
        TextView title = new TextView(this);
        title.setText("Providers");
        title.setTextColor(C_FG);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button back = pill("Back", false);
        back.setOnClickListener(v -> finish());
        head.addView(back);
        root.addView(head);

        ScrollView sc = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(10), dp(14), dp(28));

        col.addView(body(
            "Chat modes and backends live here.\n"
                + "• Remote — Grok cloud (browser sign-in below)\n"
                + "• Local — on-device GGUF / llama (no browser)\n"
                + "Web login for Grok is only on this screen — not on Chat."));

        // ---- Grok web auth (primary cloud product) ----
        col.addView(section("Grok cloud (web sign-in)"));
        LinearLayout grokCard = new LinearLayout(this);
        grokCard.setOrientation(LinearLayout.VERTICAL);
        grokCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable gbg = new GradientDrawable();
        gbg.setColor(C_GROK);
        gbg.setCornerRadius(dp(12));
        gbg.setStroke(dp(1), C_ACCENT);
        grokCard.setBackground(gbg);
        LinearLayout.LayoutParams gclp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gclp.bottomMargin = dp(12);
        grokCard.setLayoutParams(gclp);

        TextView grokTitle = new TextView(this);
        grokTitle.setText("Grok · xAI");
        grokTitle.setTextColor(C_FG);
        grokTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        grokCard.addView(grokTitle);

        grokStatus = mono("Checking sign-in…");
        grokCard.addView(grokStatus);

        grokAuthHint = body(
            "Sign in with your Grok account in the browser (device code).\n"
                + "This is not the on-device llama engine.");
        grokCard.addView(grokAuthHint);

        // Remote chat mode is Grok — no extra "use remote" button.
        grokSignInBtn = pill("Sign in…", true);
        grokSignInBtn.setOnClickListener(v -> startGrokWebAuth(false));
        grokCard.addView(pad(grokSignInBtn));

        grokRegenBtn = pill("New code", false);
        grokRegenBtn.setOnClickListener(v -> startGrokWebAuth(true));
        grokCard.addView(pad(grokRegenBtn));

        grokOpenLinkBtn = pill("Open link on this device", false);
        grokOpenLinkBtn.setVisibility(View.GONE);
        grokOpenLinkBtn.setOnClickListener(v -> {
            if (pendingAuthUri != null && !pendingAuthUri.isEmpty()) {
                openBrowser(pendingAuthUri);
            } else {
                toast("No link yet — Sign in / New code first");
            }
        });
        grokCard.addView(pad(grokOpenLinkBtn));

        grokCopyCodeBtn = pill("Copy device code", false);
        grokCopyCodeBtn.setVisibility(View.GONE);
        grokCopyCodeBtn.setOnClickListener(v -> {
            if (pendingUserCode == null || pendingUserCode.isEmpty()) {
                toast("No code yet — Sign in / New code first");
                return;
            }
            try {
                android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "Grok device code", pendingUserCode));
                    toast("Copied " + pendingUserCode);
                }
            } catch (Exception e) {
                toast(pendingUserCode);
            }
        });
        grokCard.addView(pad(grokCopyCodeBtn));
        col.addView(grokCard);

        col.addView(section("Chat mode"));
        modeHint = body("…");
        col.addView(modeHint);
        LinearLayout modes = new LinearLayout(this);
        for (String m : new String[] {"remote", "local", "auto"}) {
            Button b = pill(m, "remote".equals(m));
            final String mv = m;
            b.setOnClickListener(v -> {
                ProviderStore.setMode(this, mv);
                refresh();
                toast("Mode: " + mv);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(6);
            modes.addView(b, lp);
        }
        col.addView(modes);

        CheckBox priv = new CheckBox(this);
        priv.setText("Privacy route (all chat → privacy provider)");
        priv.setTextColor(C_FG);
        priv.setChecked(ProviderStore.privacyMode(this));
        priv.setOnCheckedChangeListener((b, v) -> {
            ProviderStore.setPrivacyMode(this, v);
            refresh();
        });
        col.addView(priv);

        routeStatus = mono("…");
        col.addView(routeStatus);

        col.addView(section("Other providers"));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);

        col.addView(section("Add local / OpenAI-compatible"));
        LinearLayout addRow = new LinearLayout(this);
        Button addOai = pill("Add OpenAI-compatible…", true);
        addOai.setOnClickListener(v -> editProvider(null, "openai"));
        Button addLlama = pill("Add llama.cpp…", false);
        addLlama.setOnClickListener(v -> editProvider(null, "llama_cpp"));
        addRow.addView(addOai, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams g = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        g.leftMargin = dp(8);
        addRow.addView(addLlama, g);
        col.addView(pad(addRow));

        Button localModels = pill("On-device GGUF models…", false);
        localModels.setOnClickListener(v -> {
            if (!PrivacyPrefs.localLlamaEnabled(this)) {
                new AlertDialog.Builder(this)
                    .setTitle("Optional llama.cpp")
                    .setMessage("Enable on-device llama in Settings first, or add a remote llama.cpp URL above.")
                    .setPositiveButton("OK", null)
                    .show();
                return;
            }
            startActivity(new Intent(this, LocalModelsActivity.class));
        });
        col.addView(pad(localModels));

        Button applyNow = pill("Apply primary to peer now", false);
        applyNow.setOnClickListener(v -> io.execute(() -> {
            try {
                ProviderRouter.Decision d = ProviderRouter.resolve(this, false);
                if (d.primary == null) throw new Exception("no primary");
                ProviderRouter.apply(this, peer, d.primary);
                h.post(() -> toast("Applied: " + d.primary.name));
            } catch (Exception e) {
                h.post(() -> toast(e.getMessage()));
            }
        }));
        col.addView(pad(applyNow));

        sc.addView(col);
        root.addView(sc, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        destroyed = false;
        loadPendingAuthPrefs();
        refresh();
        refreshGrokAuthStatus();
        // Re-arm poll if browser left us mid-flow (activity recreated or paused).
        if (!authPolling && pendingAuthUri != null && !pendingAuthUri.isEmpty()) {
            startAuthPoll();
        }
    }

    @Override
    protected void onPause() {
        // Keep polling while paused (browser on top) — only stop on destroy.
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        authPolling = false;
        if (authPoll != null) h.removeCallbacks(authPoll);
        authPoll = null;
        try { authIo.shutdownNow(); } catch (Exception ignored) {}
        try { io.shutdownNow(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private boolean uiAlive() {
        return !destroyed && !isFinishing()
            && (android.os.Build.VERSION.SDK_INT < 17 || !isDestroyed());
    }

    private void ui(Runnable r) {
        h.post(() -> {
            if (!uiAlive()) return;
            try { r.run(); } catch (Exception ignored) {}
        });
    }

    private void loadPendingAuthPrefs() {
        try {
            android.content.SharedPreferences sp =
                getSharedPreferences(PREF_AUTH, MODE_PRIVATE);
            if (pendingAuthUri == null || pendingAuthUri.isEmpty()) {
                pendingAuthUri = sp.getString(K_URI, "");
            }
            if (pendingUserCode == null || pendingUserCode.isEmpty()) {
                pendingUserCode = sp.getString(K_CODE, "");
            }
        } catch (Exception ignored) {}
    }

    private void savePendingAuthPrefs(String uri, String code) {
        pendingAuthUri = uri != null ? uri : "";
        pendingUserCode = code != null ? code : "";
        try {
            getSharedPreferences(PREF_AUTH, MODE_PRIVATE).edit()
                .putString(K_URI, pendingAuthUri)
                .putString(K_CODE, pendingUserCode)
                .apply();
        } catch (Exception ignored) {}
    }

    private void clearPendingAuthPrefs() {
        pendingAuthUri = "";
        pendingUserCode = "";
        try {
            getSharedPreferences(PREF_AUTH, MODE_PRIVATE).edit().clear().apply();
        } catch (Exception ignored) {}
    }

    private void refresh() {
        String mode = ProviderStore.mode(this);
        modeHint.setText("Current mode: " + mode
            + "\nremote = Grok cloud · local = on-device GGUF · auto = default + fallbacks");
        routeStatus.setText(ProviderRouter.statusLine(this));

        list.removeAllViews();
        List<ProviderProfile> all = ProviderStore.list(this);
        if (all.isEmpty()) {
            list.addView(body("No providers yet — Grok card above still works."));
            return;
        }
        for (ProviderProfile p : all) {
            // Grok has its own card at top
            if ("grok".equals(p.id) || p.isGrok()) continue;
            list.addView(providerCard(p));
        }
        if (list.getChildCount() == 0) {
            list.addView(body("No extra providers. Add OpenAI-compatible or llama.cpp below."));
        }
    }

    private void refreshGrokAuthStatus() {
        if (grokStatus == null || destroyed) return;
        authIo.execute(() -> {
            if (destroyed) return;
            boolean signed = false;
            boolean pending = false;
            String detail = "";
            String code = pendingUserCode;
            String uri = pendingAuthUri;
            if (!NanobotCli.available(this)) {
                detail = "Agent binary missing on ROM";
            } else {
                try {
                    JSONObject a = NanobotCli.authStatus(this);
                    signed = a.optBoolean("signed_in", false);
                    String backend = a.optString("backend", "grok");
                    String model = a.optString("model", "grok-4.5");
                    if (signed) {
                        detail = "Signed in · " + backend + " · " + model + " · CLI";
                        clearPendingAuthPrefs();
                    } else if (a.optBoolean("login_pending", false)) {
                        pending = true;
                        code = a.optString("user_code", code);
                        uri = a.optString("verification_uri_complete",
                            a.optString("verification_uri", uri));
                        if (uri != null && !uri.isEmpty()) {
                            savePendingAuthPrefs(uri, code);
                        }
                        detail = "Browser sign-in pending"
                            + (code != null && !code.isEmpty() ? " · " + code : "…");
                        if (!authPolling) {
                            ui(this::startAuthPoll);
                        }
                    } else if (a.optBoolean("session_on_disk", false)
                            || NanobotCli.sessionOnDisk()) {
                        detail = "Session on disk — loading (no new browser login)";
                    } else {
                        detail = "Not signed in — use browser below (CLI auth)";
                    }
                } catch (Exception e) {
                    detail = "Auth status failed: "
                        + (e.getMessage() != null ? e.getMessage() : "error");
                }
            }
            final boolean si = signed;
            final boolean pend = pending;
            final String d = detail;
            final String fCode = code != null ? code : "";
            final String fUri = uri != null ? uri : "";
            final boolean link = !si && fUri.length() > 0;
            ui(() -> {
                pendingUserCode = fCode;
                if (!fUri.isEmpty()) pendingAuthUri = fUri;
                grokStatus.setText(si ? "● " + d : "○ " + d);
                grokStatus.setTextColor(si ? 0xFF7DFFB3 : (pend ? C_ACCENT : C_MUT));
                if (grokSignInBtn != null) {
                    grokSignInBtn.setText(si ? "Signed in ✓" : "Sign in…");
                    grokSignInBtn.setEnabled(true);
                }
                if (grokRegenBtn != null) {
                    grokRegenBtn.setText(si ? "Sign in again (new code)" : "New code");
                    grokRegenBtn.setVisibility(View.VISIBLE);
                }
                if (grokOpenLinkBtn != null) {
                    grokOpenLinkBtn.setVisibility(link ? View.VISIBLE : View.GONE);
                }
                if (grokCopyCodeBtn != null) {
                    grokCopyCodeBtn.setVisibility(
                        !si && fCode.length() > 0 ? View.VISIBLE : View.GONE);
                }
                if (grokAuthHint != null && si) {
                    grokAuthHint.setText(
                        "Grok session is sealed on this phone.\n"
                            + "Chat → Remote uses this account. Local mode never needs it.");
                } else if (grokAuthHint != null && pend && fCode.length() > 0) {
                    grokAuthHint.setText(
                        "1) Browser should open for Grok / xAI\n"
                            + "2) Approve this device\n"
                            + "Code: " + fCode + "\n"
                            + "3) Stay on this screen or reopen link — we poll every "
                            + (AUTH_POLL_MS / 1000) + "s");
                }
            });
        });
    }

    /** No HTTP peer warm — CLI binary only. */
    private void warmPeerQuiet() {
        if (!NanobotCli.available(this)) {
            throw new RuntimeException(NanobotRuntime.userFacingOfflineHint());
        }
    }

    private void startGrokWebAuth(boolean force) {
        if (grokAuthHint != null) {
            grokAuthHint.setText(force
                ? "Starting fresh browser sign-in…"
                : "Opening Grok web sign-in…");
        }
        if (grokSignInBtn != null) grokSignInBtn.setEnabled(false);
        authIo.execute(() -> {
            try {
                ensureGrokProfile();
                if (!NanobotCli.available(this)) {
                    throw new Exception(NanobotRuntime.userFacingOfflineHint());
                }
                // Remote = Grok always for this card.
                ProviderStore.setMode(this, "remote");
                NanobotCli.setRemoteGrok(this, "grok-4.5");
                try {
                    JSONObject st0 = NanobotCli.authStatus(this);
                    if (st0.optBoolean("signed_in", false) && !force) {
                        ui(() -> {
                            if (grokSignInBtn != null) grokSignInBtn.setEnabled(true);
                            toast("Already signed in");
                            refreshGrokAuthStatus();
                        });
                        return;
                    }
                } catch (Exception ignored) {}
                // force=true → new device code (regenerate).
                JSONObject j = NanobotCli.authStart(this, force);
                ui(() -> {
                    if (grokSignInBtn != null) grokSignInBtn.setEnabled(true);
                    if (j.optBoolean("signed_in", false)) {
                        onGrokSignedInSaved();
                        return;
                    }
                    // Existing seal / live peer — do not invent a new device code.
                    if (!force && (j.optBoolean("reused_session", false)
                            || j.optBoolean("session_on_disk", false))) {
                        if (grokAuthHint != null) {
                            grokAuthHint.setText(
                                "Existing Grok session is on this phone.\n"
                                    + "Not starting a new browser login.");
                        }
                        if (grokStatus != null) {
                            grokStatus.setText("○ Session on disk — loading");
                            grokStatus.setTextColor(C_ACCENT);
                        }
                        toast("Using existing session");
                        startAuthPoll();
                        return;
                    }
                    String uri = j.optString("verification_uri_complete", "");
                    if (uri.isEmpty()) uri = j.optString("verification_uri", "");
                    String code = j.optString("user_code", "");
                    if (uri.isEmpty()) {
                        String err = j.optString("error", "sign-in failed");
                        if (grokAuthHint != null) grokAuthHint.setText(err);
                        toast(err);
                        return;
                    }
                    if (!code.isEmpty() && !uri.contains("user_code") && uri.contains("device")) {
                        uri = uri + (uri.contains("?") ? "&" : "?") + "user_code=" + code;
                    }
                    savePendingAuthPrefs(uri, code);
                    NanobotService.requestAuthWatch(this);
                    if (grokAuthHint != null) {
                        grokAuthHint.setText(crossDeviceAuthHint(code, uri));
                    }
                    if (grokOpenLinkBtn != null) grokOpenLinkBtn.setVisibility(View.VISIBLE);
                    if (grokCopyCodeBtn != null) {
                        grokCopyCodeBtn.setVisibility(
                            code.isEmpty() ? View.GONE : View.VISIBLE);
                    }
                    if (grokStatus != null) {
                        grokStatus.setText("○ Waiting for approval (any device)"
                            + (code.isEmpty() ? "" : " · " + code));
                        grokStatus.setTextColor(C_ACCENT);
                    }
                    // Complete the loop on this device — do not leave auth in another app.
                    openBrowser(uri);
                    startAuthPoll();
                });
            } catch (Exception e) {
                ui(() -> {
                    if (grokSignInBtn != null) grokSignInBtn.setEnabled(true);
                    if (grokAuthHint != null) {
                        grokAuthHint.setText("Sign-in failed: " + e.getMessage());
                    }
                    toast(e.getMessage() != null ? e.getMessage() : "sign-in failed");
                });
            }
        });
    }

    private void ensureGrokProfile() {
        ProviderProfile grok = ProviderStore.get(this, "grok");
        if (grok == null) {
            grok = new ProviderProfile();
            grok.id = "grok";
            grok.name = "Grok (cloud)";
            grok.kind = "grok";
            grok.baseUrl = "https://cli-chat-proxy.grok.com/v1";
            grok.model = "grok-4.5";
            grok.roleDefault = true;
            grok.enabled = true;
            ProviderStore.upsert(this, grok);
        } else {
            grok.enabled = true;
            if (grok.baseUrl == null || grok.baseUrl.isEmpty()) {
                grok.baseUrl = "https://cli-chat-proxy.grok.com/v1";
            }
            ProviderStore.upsert(this, grok);
        }
    }

    /** Copy for other phone/laptop — device-code is multi-device by design. */
    private String crossDeviceAuthHint(String code, String uri) {
        String c = code != null ? code : pendingUserCode;
        if (c == null) c = "";
        return "Approve on ANY device:\n"
            + "• Link on phone/laptop logged into Grok, or\n"
            + "• https://accounts.x.ai/oauth2/device + code\n"
            + (c.isEmpty() ? "" : "Code: " + c + "\n")
            + "New code = regenerate. Stay here while we poll.\n"
            + "Remote chat uses this Grok session (no extra button).";
    }

    /**
     * After browser approve (or reused seal): peer/CLI signed_in is enough.
     * Do not fail just because the app UID cannot read a 0600 session file —
     * that used to toast "try Sign in again" and mint a second device code.
     */
    private void onGrokSignedInSaved() {
        authIo.execute(() -> {
            String detail;
            boolean ok = false;
            boolean hold = false;
            try {
                ProviderStore.setMode(this, "remote");
                NanobotCli.setRemoteGrok(this, "grok-4.5");
                NanobotCli.healSharedSessionPerms();
                JSONObject a = NanobotCli.authStatus(this);
                ok = a.optBoolean("signed_in", false);
                if (!ok) {
                    JSONObject p = NanobotCli.authPoll(this);
                    ok = p.optBoolean("signed_in", false);
                    a = p;
                }
                if (ok) {
                    detail = "Signed in · " + a.optString("model", "grok-4.5");
                } else if (a.optBoolean("session_on_disk", false)
                        || NanobotCli.sessionOnDisk()) {
                    hold = true;
                    detail = "Session on disk — agent will load it (no new login)";
                } else {
                    detail = "Browser returned; waiting for session (not starting a new login)";
                    hold = true;
                }
            } catch (Exception e) {
                detail = "Auth check: " + e.getMessage();
                hold = NanobotCli.sessionOnDisk();
            }
            final boolean si = ok;
            final boolean keep = hold;
            final String d = detail;
            ui(() -> {
                if (si) {
                    clearPendingAuthPrefs();
                    if (grokOpenLinkBtn != null) grokOpenLinkBtn.setVisibility(View.GONE);
                    if (grokCopyCodeBtn != null) grokCopyCodeBtn.setVisibility(View.GONE);
                    toast("Grok signed in");
                    if (grokStatus != null) {
                        grokStatus.setText("● " + d);
                        grokStatus.setTextColor(0xFF7DFFB3);
                    }
                    if (grokAuthHint != null) {
                        grokAuthHint.setText(
                            "Grok session is live on this phone.\n"
                                + "Remote chat uses it. Local mode never needs it.");
                    }
                    if (grokSignInBtn != null) grokSignInBtn.setText("Signed in ✓");
                    if (grokRegenBtn != null) {
                        grokRegenBtn.setText("Sign in again (new code)");
                    }
                } else {
                    if (grokAuthHint != null) grokAuthHint.setText(d);
                    if (grokStatus != null) {
                        grokStatus.setText("○ " + d);
                        grokStatus.setTextColor(keep ? C_ACCENT : C_MUT);
                    }
                    if (keep && !authPolling) startAuthPoll();
                }
                refresh();
                refreshGrokAuthStatus();
            });
        });
    }

    private void startAuthPoll() {
        if (destroyed) return;
        if (authPoll != null) h.removeCallbacks(authPoll);
        authPolling = true;
        authPoll = new Runnable() {
            int n;
            @Override public void run() {
                if (destroyed || !authPolling) return;
                authIo.execute(() -> {
                    if (destroyed || !authPolling) return;
                    boolean ok = false;
                    boolean stillPending = false;
                    boolean hardExpire = false;
                    String err = null;
                    String pollState = "";
                    try {
                        JSONObject a = NanobotCli.authPoll(ProvidersActivity.this);
                        ok = a.optBoolean("signed_in", false);
                        stillPending = a.optBoolean("login_pending", false);
                        pollState = a.optString("poll_state", "");
                        err = a.optString("error", null);
                        if (err != null && err.isEmpty()) err = null;
                        // Only terminal OAuth failures are "expired".
                        // no_pending + empty prefs is not the same as cancelled mid-wait.
                        hardExpire = "expired".equals(pollState)
                            || "denied".equals(pollState)
                            || (err != null && (err.contains("expired")
                                || err.contains("denied")
                                || err.contains("access_denied")));
                        if (!ok && stillPending) {
                            String code = a.optString("user_code", pendingUserCode);
                            String uri = a.optString("verification_uri_complete",
                                a.optString("verification_uri", pendingAuthUri));
                            if (uri != null && !uri.isEmpty()) {
                                savePendingAuthPrefs(uri, code);
                            }
                        }
                        // Prefer local prefs only while OAuth truly pending.
                        // 1.7.13: never force-pending when CLI already signed_in
                        // (wedged peer left sticky prefs → eternal "Waiting for approval").
                        if (!ok && !stillPending && !hardExpire
                                && pendingUserCode != null && !pendingUserCode.isEmpty()) {
                            try {
                                JSONObject st = NanobotCli.authStatus(ProvidersActivity.this);
                                if (st.optBoolean("signed_in", false)) {
                                    ok = true;
                                    stillPending = false;
                                } else {
                                    stillPending = true;
                                }
                            } catch (Exception ignored) {
                                stillPending = true;
                            }
                        }
                    } catch (Exception e) {
                        err = e.getMessage();
                        // Transient — re-check sealed session before sticky wait.
                        try {
                            JSONObject st = NanobotCli.authStatus(ProvidersActivity.this);
                            if (st.optBoolean("signed_in", false)) {
                                ok = true;
                                stillPending = false;
                                err = null;
                            } else if (pendingUserCode != null && !pendingUserCode.isEmpty()) {
                                stillPending = true;
                            }
                        } catch (Exception ignored) {
                            if (pendingUserCode != null && !pendingUserCode.isEmpty()) {
                                stillPending = true;
                            }
                        }
                    }
                    final boolean signed = ok;
                    final boolean pend = stillPending;
                    final boolean expired = hardExpire;
                    final String eMsg = err;
                    final int step = n;
                    final String state = pollState;
                    ui(() -> {
                        if (signed) {
                            authPolling = false;
                            onGrokSignedInSaved();
                            return;
                        }
                        if (grokStatus != null) {
                            if (eMsg != null && step % 5 == 0) {
                                grokStatus.setText("○ Waiting (any device)… " + eMsg);
                            } else if (pend) {
                                grokStatus.setText("○ Waiting for approval (any device)"
                                    + (pendingUserCode != null && !pendingUserCode.isEmpty()
                                        ? " · " + pendingUserCode : ""));
                                grokStatus.setTextColor(C_ACCENT);
                            }
                        }
                        if (expired) {
                            if (grokAuthHint != null) {
                                grokAuthHint.setText(
                                    "Code expired or denied by Grok.\n"
                                        + "Tap Sign in again for a new code.\n"
                                        + "(Opening the link on another device is fine "
                                        + "— only a dead/denied code stops the wait.)");
                            }
                            authPolling = false;
                            return;
                        }
                        if (pend && grokAuthHint != null && step % 8 == 0) {
                            grokAuthHint.setText(
                                crossDeviceAuthHint(pendingUserCode, pendingAuthUri));
                        }
                        if (n++ < AUTH_POLL_MAX && authPolling && !destroyed) {
                            h.postDelayed(this, AUTH_POLL_MS);
                        } else {
                            authPolling = false;
                            if (!signed && grokAuthHint != null) {
                                grokAuthHint.setText(
                                    "Stopped polling. If you already approved on another "
                                        + "device, pull to refresh / reopen Providers.\n"
                                        + "Otherwise Sign in again for a new code.\n"
                                        + "Code was: "
                                        + (pendingUserCode != null ? pendingUserCode : "—"));
                            }
                        }
                    });
                });
            }
        };
        h.postDelayed(authPoll, AUTH_POLL_MS);
    }

    private void openBrowser(String uri) {
        if (uri == null || uri.isEmpty()) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("Open manually: " + uri);
        }
    }

    private LinearLayout providerCard(ProviderProfile p) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(p.enabled ? (p.rolePrivacy ? C_OK : C_PANEL) : C_WARN);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), C_LINE);
        card.setBackground(bg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(8);
        card.setLayoutParams(clp);

        TextView t = new TextView(this);
        t.setTextColor(C_FG);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        String roles = "";
        if (p.roleDefault) roles += "★default ";
        if (p.rolePrivacy) roles += "🔒privacy ";
        if (p.roleFallback) roles += "↩fallback ";
        if (p.localOnly) roles += "local ";
        t.setText(p.name + "  [" + p.id + "]\n"
            + p.kind + (p.enabled ? " · ON" : " · OFF") + "\n"
            + (p.baseUrl == null || p.baseUrl.isEmpty() ? "(default URL)" : p.baseUrl) + "\n"
            + "model: " + (p.model == null || p.model.isEmpty() ? "—" : p.model) + "\n"
            + roles.trim());
        card.addView(t);

        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(8), 0, 0);
        Button use = pill("Use", true);
        use.setOnClickListener(v -> io.execute(() -> {
            try {
                ProviderRouter.apply(this, peer, p);
                h.post(() -> { toast("Using " + p.name); refresh(); });
            } catch (Exception e) {
                h.post(() -> toast(e.getMessage()));
            }
        }));
        Button edit = pill("Edit", false);
        edit.setOnClickListener(v -> editProvider(p, p.kind));
        Button tog = pill(p.enabled ? "Disable" : "Enable", false);
        tog.setOnClickListener(v -> {
            p.enabled = !p.enabled;
            ProviderStore.upsert(this, p);
            refresh();
        });
        row.addView(use, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams m = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        m.leftMargin = dp(6);
        row.addView(edit, m);
        row.addView(tog, m);
        card.addView(row);

        if (!"grok".equals(p.id)) {
            Button del = pill("Delete", false);
            del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete " + p.name + "?")
                .setPositiveButton("Delete", (d, w) -> {
                    ProviderStore.delete(this, p.id);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show());
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dl.topMargin = dp(6);
            del.setLayoutParams(dl);
            card.addView(del);
        }
        return card;
    }

    private void editProvider(ProviderProfile existing, String kindDefault) {
        final ProviderProfile p = existing != null ? existing : new ProviderProfile();
        if (existing == null) {
            p.id = ProviderStore.newId();
            p.kind = kindDefault == null ? "openai" : kindDefault;
            p.name = "llama_cpp".equals(p.kind) ? "llama.cpp" : "OpenAI-compatible";
            p.baseUrl = "llama_cpp".equals(p.kind) ? LlamaRuntime.baseUrl() : "https://api.openai.com/v1";
            p.localOnly = "llama_cpp".equals(p.kind) || (p.baseUrl != null && p.baseUrl.contains("127.0.0.1"));
            p.enabled = true;
            p.roleFallback = true;
        }

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(4), dp(8), dp(4));

        EditText name = field(p.name, "Name");
        EditText url = field(p.baseUrl, "Base URL (…/v1)");
        EditText model = field(p.model, "Model id (optional)");
        EditText key = field(p.apiKey == null ? "" : p.apiKey, "API key (optional, stored encrypted)");
        key.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        CheckBox def = cb("Role: Default (normal chat)", p.roleDefault);
        CheckBox priv = cb("Role: Privacy (sensitive / local)", p.rolePrivacy);
        CheckBox fb = cb("Role: Fallback (if others fail)", p.roleFallback);
        CheckBox local = cb("Local only (on-device / LAN)", p.localOnly);

        form.addView(name);
        form.addView(url);
        form.addView(model);
        form.addView(key);
        form.addView(def);
        form.addView(priv);
        form.addView(fb);
        form.addView(local);

        new AlertDialog.Builder(this)
            .setTitle(existing == null ? "Add provider" : "Edit " + p.name)
            .setView(form)
            .setPositiveButton("Save", (d, w) -> {
                p.name = text(name, p.name);
                p.baseUrl = text(url, p.baseUrl);
                p.model = text(model, "");
                String k = text(key, "");
                p.apiKey = k.isEmpty() ? null : k;
                p.roleDefault = def.isChecked();
                p.rolePrivacy = priv.isChecked();
                p.roleFallback = fb.isChecked();
                p.localOnly = local.isChecked();
                if (p.baseUrl != null && (p.baseUrl.contains("127.0.0.1") || p.baseUrl.contains("localhost"))) {
                    p.localOnly = true;
                }
                if ("llama_cpp".equals(p.kind) || (p.baseUrl != null && p.baseUrl.contains("8080"))) {
                    // keep kind
                }
                ProviderStore.upsert(this, p);
                // sync optional llama flag if privacy local llama
                if (p.rolePrivacy && p.isLocalStack() && "llama_cpp".equals(p.kind)) {
                    PrivacyPrefs.setLocalLlamaEnabled(this, true);
                }
                refresh();
                toast("Saved " + p.name);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // --- UI helpers ---
    private String text(EditText e, String def) {
        if (e.getText() == null) return def;
        String s = e.getText().toString().trim();
        return s.isEmpty() ? def : s;
    }

    private EditText field(String value, String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(C_MUT);
        e.setTextColor(C_FG);
        if (value != null) e.setText(value);
        e.setSingleLine(true);
        e.setBackground(round(0xFF1A1A24, dp(8)));
        e.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        e.setLayoutParams(lp);
        return e;
    }

    private CheckBox cb(String label, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(C_FG);
        c.setChecked(on);
        return c;
    }

    private TextView section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_ACCENT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, dp(14), 0, dp(6));
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_MUT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private TextView mono(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_FG);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setPadding(dp(10), dp(10), dp(10), dp(10));
        t.setBackground(round(C_PANEL, dp(10)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout pad(LinearLayout row) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private LinearLayout pad(Button b) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.addView(b, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return pad(wrap);
    }

    private Button pill(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setMinHeight(dp(42));
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        b.setBackground(round(primary ? C_ACCENT : 0xFF1A1A24, dp(16)));
        b.setTextColor(primary ? 0xFF00343A : C_FG);
        return b;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        g.setStroke(dp(1), C_LINE);
        return g;
    }

    private void toast(String s) {
        if (!uiAlive() || s == null) return;
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
