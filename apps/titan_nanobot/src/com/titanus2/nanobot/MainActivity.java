package com.titanus2.nanobot;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Universal on-device agent UI. Providers (Grok/xAI, llama.cpp, OpenAI-compatible)
 * are selectable backends — not product branding.
 */
public class MainActivity extends Activity {
    private static final int C_BG = 0xFF0B0B0F;
    private static final int C_PANEL = 0xFF14141A;
    private static final int C_BUBBLE_USER = 0xFF1A1A24;
    private static final int C_BUBBLE_AI = 0xFF12121A;
    private static final int C_ACCENT = 0xFF00E5FF;
    private static final int C_FG = 0xFFF2F2F5;
    private static final int C_MUT = 0xFF8B8B9A;
    private static final int C_LINE = 0xFF22222C;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private PeerClient peer;

    private LinearLayout chatPage;
    private ScrollView settingsPage;
    private LinearLayout chatLog;
    private LinearLayout modelRow;
    private ScrollView chatScroll;
    private EditText input;
    private EditText baseUrlEdit;
    private Button send;
    private Button tabChat;
    private Button tabSettings;
    private Button connectBtn;
    private Button connectBtnSettings;
    private Button btnCloud;
    private Button btnLocal;
    private TextView status;
    private TextView providerStatus;
    private TextView modelStatus;
    private ProgressBar progress;
    private TextView authBanner;
    private TextView lanUrlView;
    private TextView tokenView;
    private TextView policySum;
    private Switch swLan;
    private Switch swDevice;
    private Switch swNetAgents;
    private Switch swReboot;
    private Switch swEnterSend;
    private Switch swService;
    private Switch swLocalLlama;
    private LinearLayout localLlamaPanel;
    private TextView serviceStatus;
    private TextView filesAclSummary;
    private Button btnAclDeny;
    private Button btnAclRead;
    private Button btnAclFull;
    private TextView routeChip;
    private Button modeAuto;
    private Button modeLocal;
    private Button modeRemote;
    private Switch swPrivacyRoute;
    private boolean sending;
    private long sendStartedMs;
    /**
     * 1.7.10: hung stream left Send dead ("chat paused") — not thermal.
     * 1.7.11: doneSend also cancels PeerClient/CLI stream (600s residual closed).
     */
    private static final long SEND_WATCHDOG_MS = 90_000L;
    /** Bumped on each send / cancel so late stream callbacks ignore after unstick. */
    private int sendGen;
    private final Runnable sendWatchdog = new Runnable() {
        @Override public void run() {
            if (!sending) return;
            long age = SystemClock.elapsedRealtime() - sendStartedMs;
            if (age < SEND_WATCHDOG_MS) {
                h.postDelayed(this, Math.max(2000L, SEND_WATCHDOG_MS - age));
                return;
            }
            Log.w("MainActivity", "send watchdog — re-enable chat + cancel stream (hung)");
            toast("Chat unstuck (timeout) — try again");
            doneSend();
        }
    };
    private boolean providerSignedIn; // cloud session when needs_browser
    private String currentBackend = "grok"; // grok | local
    private String currentModel = "";
    private Runnable authPoll;

    /** Pending attachments (images + documents) — ChatGPT-style chip strip. */
    private static final int REQ_PICK_IMAGE = 9101;
    private static final int REQ_PICK_FILE = 9102;
    private final java.util.ArrayList<ChatAttachment.Item> pendingAttach =
        new java.util.ArrayList<>();
    private LinearLayout attachStrip;
    private Button attachBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        peer = new PeerClient(this);
        PrivacyPrefs.publishAll(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        root.addView(buildHeader());
        root.addView(buildTabs());
        root.addView(buildStatusRow());
        root.addView(buildAuthBanner());

        chatPage = buildChatPage();
        settingsPage = buildSettingsPage();
        root.addView(chatPage, lpFlex());
        root.addView(settingsPage, lpFlex());
        settingsPage.setVisibility(View.GONE);
        setContentView(root);

        // Seed multi-provider registry (Grok + local privacy slot)
        io.execute(() -> ProviderStore.list(this));
        addSystem("Nanobot — multi-provider swiss army knife.\n"
            + "Providers panel: Grok + OpenAI/llama.cpp servers, roles, fallbacks.\n"
            + "Mode Local/Remote/Auto · Privacy route keeps sensitive chat on local.");

        try {
            if (PrivacyPrefs.serviceEnabled(this)) {
                Intent svc = new Intent(this, NanobotService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
                else startService(svc);
            }
        } catch (Throwable ignored) {}
        if (PrivacyPrefs.serviceEnabled(this)) bootstrapPeer();
        // Warm Keystore cache of peer token when readable
        io.execute(() -> {
            try {
                String t = NanobotRuntime.readPeerToken(this);
                if (t != null) SecureStore.cachePeerToken(this, t);
            } catch (Exception ignored) {}
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        BiometricGate.onActivityResult(requestCode, resultCode);
        if (requestCode == REQ_PICK_IMAGE || requestCode == REQ_PICK_FILE) {
            if (resultCode != RESULT_OK || data == null) return;
            final java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            if (data.getClipData() != null) {
                ClipData cd = data.getClipData();
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) return;
            setStatus("Processing " + uris.size() + " file(s)…");
            io.execute(() -> {
                final java.util.ArrayList<ChatAttachment.Item> got =
                    new java.util.ArrayList<>();
                for (Uri u : uris) {
                    try {
                        got.add(ChatAttachment.process(this, u));
                    } catch (Exception e) {
                        ChatAttachment.Item bad = new ChatAttachment.Item();
                        bad.name = "file";
                        bad.error = e.getMessage();
                        bad.detail = "⚠ failed";
                        got.add(bad);
                    }
                }
                h.post(() -> {
                    for (ChatAttachment.Item it : got) addPendingAttach(it);
                    setStatus(statusLine());
                });
            });
        }
    }

    private LinearLayout.LayoutParams lpFlex() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    }

    private View buildHeader() {
        LinearLayout bar = row(C_PANEL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView brand = new TextView(this);
        brand.setText("Nanobot");
        brand.setTextColor(C_FG);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView sub = new TextView(this);
        sub.setText("local agent · multi-provider");
        sub.setTextColor(C_MUT);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        titles.addView(brand);
        titles.addView(sub);
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button providers = pill("Providers", true);
        providers.setOnClickListener(v ->
            startActivity(new Intent(this, ProvidersActivity.class)));
        bar.addView(providers);
        // Web Grok sign-in lives in Providers — not a chat-bar "Connect" that
        // looks like waiting on a local server.
        connectBtn = pill("Grok…", false);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.leftMargin = dp(6);
        connectBtn.setOnClickListener(v ->
            startActivity(new Intent(this, ProvidersActivity.class)));
        connectBtn.setOnLongClickListener(v -> {
            startActivity(new Intent(this, ProvidersActivity.class));
            return true;
        });
        bar.addView(connectBtn, cl);
        return bar;
    }

    private View buildTabs() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(C_PANEL);
        tabChat = tab("Chat", true);
        tabSettings = tab("Settings", false);
        tabChat.setOnClickListener(v -> showPage("chat"));
        tabSettings.setOnClickListener(v -> showPage("settings"));
        tabs.addView(tabChat, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tabSettings, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return tabs;
    }

    private Button tab(String label, boolean on) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        paintTab(b, on);
        return b;
    }

    private void paintTab(Button b, boolean on) {
        b.setTextColor(on ? C_ACCENT : C_MUT);
        b.setBackgroundColor(C_PANEL);
        b.setPadding(dp(8), dp(12), dp(8), dp(12));
    }

    private void showPage(String which) {
        boolean chat = "chat".equals(which);
        chatPage.setVisibility(chat ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(chat ? View.GONE : View.VISIBLE);
        paintTab(tabChat, chat);
        paintTab(tabSettings, !chat);
        if (!chat) refreshSettings();
    }

    private View buildStatusRow() {
        LinearLayout row = row(C_PANEL);
        row.setPadding(dp(14), dp(6), dp(14), dp(6));
        status = new TextView(this);
        status.setText("Starting…");
        status.setTextColor(C_MUT);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        row.addView(progress, new LinearLayout.LayoutParams(dp(22), dp(22)));
        return row;
    }

    private View buildAuthBanner() {
        authBanner = new TextView(this);
        authBanner.setVisibility(View.GONE);
        authBanner.setTextColor(C_ACCENT);
        authBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        authBanner.setPadding(dp(14), dp(10), dp(14), dp(10));
        authBanner.setBackgroundColor(0xFF0E1A1C);
        authBanner.setOnClickListener(v ->
            startActivity(new Intent(this, ProvidersActivity.class)));
        return authBanner;
    }

    private LinearLayout buildChatPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        // Two modes only: Local (llama) | Remote (Grok). No Auto/privacy hijack.
        LinearLayout modeBar = new LinearLayout(this);
        modeBar.setOrientation(LinearLayout.HORIZONTAL);
        modeBar.setPadding(dp(10), dp(6), dp(10), dp(4));
        modeBar.setBackgroundColor(C_PANEL);
        modeLocal = pill("Local", false);
        modeRemote = pill("Remote · Grok", true);
        modeAuto = null; // removed — was confusing routing
        modeLocal.setOnClickListener(v -> setProviderMode("local"));
        modeRemote.setOnClickListener(v -> setProviderMode("remote"));
        modeBar.addView(modeLocal, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams mg = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mg.leftMargin = dp(8);
        modeBar.addView(modeRemote, mg);
        page.addView(modeBar);

        LinearLayout privRow = new LinearLayout(this);
        privRow.setOrientation(LinearLayout.HORIZONTAL);
        privRow.setGravity(Gravity.CENTER_VERTICAL);
        privRow.setPadding(dp(12), dp(2), dp(12), dp(4));
        privRow.setBackgroundColor(C_PANEL);
        swPrivacyRoute = null; // removed — chat is Local|Remote only
        TextView modeHint = new TextView(this);
        modeHint.setText("Local = phone GGUF · Remote = Grok (session kept)");
        modeHint.setTextColor(C_MUT);
        modeHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        privRow.addView(modeHint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button openProv = pill("Providers", false);
        openProv.setOnClickListener(v ->
            startActivity(new Intent(this, ProvidersActivity.class)));
        privRow.addView(openProv);
        page.addView(privRow);

        routeChip = new TextView(this);
        routeChip.setTextColor(C_MUT);
        routeChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        routeChip.setTypeface(Typeface.MONOSPACE);
        routeChip.setPadding(dp(12), dp(4), dp(12), dp(6));
        routeChip.setText(ProviderRouter.statusLine(this));
        page.addView(routeChip);
        paintModeButtons();

        // model strip
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        modelRow = new LinearLayout(this);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        modelRow.setPadding(dp(10), dp(6), dp(10), dp(6));
        hs.addView(modelRow);
        page.addView(hs, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        chatScroll = new ScrollView(this);
        chatLog = new LinearLayout(this);
        chatLog.setOrientation(LinearLayout.VERTICAL);
        chatLog.setPadding(dp(14), dp(8), dp(14), dp(8));
        chatScroll.addView(chatLog, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(chatScroll, lpFlex());

        // Attachment chips (images + documents) — like ChatGPT/Claude Android
        attachStrip = new LinearLayout(this);
        attachStrip.setOrientation(LinearLayout.VERTICAL);
        attachStrip.setPadding(dp(10), dp(4), dp(10), dp(4));
        attachStrip.setVisibility(View.GONE);
        page.addView(attachStrip);

        LinearLayout box = row(C_PANEL);
        box.setPadding(dp(10), dp(10), dp(10), dp(12));
        attachBtn = pill("+", true);
        attachBtn.setContentDescription("Attach files or images");
        attachBtn.setOnClickListener(v -> showAttachMenu());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.rightMargin = dp(6);
        box.addView(attachBtn, ap);
        input = new EditText(this);
        input.setHint(R.string.hint_message);
        input.setHintTextColor(C_MUT);
        input.setTextColor(C_FG);
        input.setBackground(round(C_BUBBLE_USER, dp(22)));
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        input.setMaxLines(5);
        input.setMinHeight(dp(48));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        applyEnterAsSend(PrivacyPrefs.enterAsSend(this));
        box.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        send = pill("Send", false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = dp(8);
        send.setOnClickListener(v -> sendMessage());
        box.addView(send, sp);
        page.addView(box);
        return page;
    }

    private void showAttachMenu() {
        final String[] items = {
            "Photo / image",
            "Document (PDF, DOCX, text…)",
            "Clear all attachments"
        };
        new android.app.AlertDialog.Builder(this)
            .setTitle("Attach")
            .setItems(items, (d, which) -> {
                if (which == 0) pickContent("image/*", REQ_PICK_IMAGE, false);
                else if (which == 1) pickContent("*/*", REQ_PICK_FILE, true);
                else clearPendingAttach();
            })
            .show();
    }

    private void pickContent(String type, int req, boolean multi) {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType(type);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            if (multi) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            // Helpful MIME hints for documents
            if ("*/*".equals(type)) {
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/pdf",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "text/*",
                    "application/json",
                    "application/xml",
                    "image/*"
                });
            }
            startActivityForResult(Intent.createChooser(i, "Attach"), req);
        } catch (Exception e) {
            toast("Cannot open picker: " + e.getMessage());
        }
    }

    private void clearPendingAttach() {
        pendingAttach.clear();
        refreshAttachStrip();
    }

    private void addPendingAttach(ChatAttachment.Item it) {
        if (it == null) return;
        if (pendingAttach.size() >= ChatAttachment.MAX_ATTACHMENTS) {
            toast("Max " + ChatAttachment.MAX_ATTACHMENTS + " attachments");
            return;
        }
        if (!it.ok) {
            toast((it.name != null ? it.name : "file") + ": "
                + (it.error != null ? it.error : "failed"));
            if (it.detail != null) {
                // still show failed chip so user can clear
                pendingAttach.add(it);
                refreshAttachStrip();
            }
            return;
        }
        pendingAttach.add(it);
        refreshAttachStrip();
        toast("Attached: " + it.name);
    }

    private void refreshAttachStrip() {
        if (attachStrip == null) return;
        attachStrip.removeAllViews();
        if (pendingAttach.isEmpty()) {
            attachStrip.setVisibility(View.GONE);
            return;
        }
        attachStrip.setVisibility(View.VISIBLE);
        for (int i = 0; i < pendingAttach.size(); i++) {
            final int idx = i;
            ChatAttachment.Item it = pendingAttach.get(i);
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(10), dp(6), dp(8), dp(6));
            chip.setBackground(round(0xFF1A1A24, dp(12)));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = dp(4);
            chip.setLayoutParams(clp);
            TextView t = new TextView(this);
            t.setText(it.detail != null ? it.detail : it.name);
            t.setTextColor(it.ok ? C_ACCENT : 0xFFFF8A80);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            t.setMaxLines(2);
            t.setEllipsize(TextUtils.TruncateAt.END);
            chip.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button x = pill("✕", false);
            x.setOnClickListener(v -> {
                if (idx >= 0 && idx < pendingAttach.size()) {
                    pendingAttach.remove(idx);
                    refreshAttachStrip();
                }
            });
            chip.addView(x);
            attachStrip.addView(chip);
        }
        TextView hint = new TextView(this);
        hint.setText("Remote · Grok for images/PDF pages · docs become text in the prompt");
        hint.setTextColor(C_MUT);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setPadding(dp(4), dp(2), dp(4), dp(2));
        attachStrip.addView(hint);
    }

    /** Enter sends when enabled; Shift+Enter always inserts newline. */
    private void applyEnterAsSend(boolean on) {
        if (input == null) return;
        if (on) {
            input.setImeOptions(EditorInfo.IME_ACTION_SEND);
            input.setSingleLine(false);
            input.setMaxLines(5);
            input.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN
                        && !event.isShiftPressed())) {
                    sendMessage();
                    return true;
                }
                return false;
            });
            input.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.isShiftPressed() || event.isCtrlPressed()) {
                        // let EditText insert newline
                        return false;
                    }
                    sendMessage();
                    return true;
                }
                return false;
            });
            input.setHint("Message… (Enter send · Shift+Enter newline)");
        } else {
            input.setImeOptions(EditorInfo.IME_ACTION_NONE);
            input.setOnEditorActionListener(null);
            input.setOnKeyListener(null);
            input.setHint(R.string.hint_message);
        }
    }

    private ScrollView buildSettingsPage() {
        ScrollView sc = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(12), dp(14), dp(28));

        col.addView(section("Chat input"));
        swEnterSend = sw("Enter key sends message", PrivacyPrefs.enterAsSend(this), (b, v) -> {
            PrivacyPrefs.setEnterAsSend(this, v);
            applyEnterAsSend(v);
            toast(v ? "Enter = send (Shift+Enter = newline)" : "Enter = newline");
        });
        col.addView(swEnterSend);
        col.addView(body("Hardware keyboard: Enter sends, Shift+Enter for a new line."));

        // Providers swiss army knife
        col.addView(section("Providers (multi)"));
        col.addView(body(
            "Manage Grok, OpenAI-compatible, and llama.cpp servers. Assign Default / Privacy / Fallback. "
                + "Chat uses routing (Auto · Local · Remote) with failover."));
        Button openProviders = pill("Open Providers panel…", true);
        openProviders.setOnClickListener(v ->
            startActivity(new Intent(this, ProvidersActivity.class)));
        col.addView(pad(openProviders));
        providerStatus = body("…");
        col.addView(providerStatus);

        // Quick legacy switches (still useful)
        col.addView(body("Quick backend (or use Providers panel):"));
        LinearLayout prov = new LinearLayout(this);
        prov.setOrientation(LinearLayout.HORIZONTAL);
        btnCloud = pill("Cloud (Grok)", true);
        btnLocal = pill("Local API", false);
        btnCloud.setOnClickListener(v -> selectBackend("grok"));
        btnLocal.setOnClickListener(v -> {
            if (!PrivacyPrefs.localLlamaEnabled(this)) {
                String u = baseUrlEdit != null && baseUrlEdit.getText() != null
                    ? baseUrlEdit.getText().toString().trim() : "";
                if (u.isEmpty()) {
                    toast("Enable optional llama or set a base URL first");
                    return;
                }
            }
            selectBackend("local");
        });
        prov.addView(btnCloud, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        gap.leftMargin = dp(8);
        prov.addView(btnLocal, gap);
        col.addView(prov);

        col.addView(section("Optional: on-device llama.cpp"));
        col.addView(body(
            "Off by default. When on: download GGUF models and run llama-server on this phone. "
                + "Uses RAM/storage; does not affect cloud until you switch provider."));
        swLocalLlama = sw("Enable on-device llama.cpp", PrivacyPrefs.localLlamaEnabled(this),
            (b, v) -> {
                PrivacyPrefs.setLocalLlamaEnabled(this, v);
                applyLocalLlamaUi();
                toast(v ? "Local llama enabled — open Local models to download GGUF"
                    : "Local llama disabled (server stopped if it was running)");
            });
        col.addView(swLocalLlama);

        localLlamaPanel = new LinearLayout(this);
        localLlamaPanel.setOrientation(LinearLayout.VERTICAL);
        localLlamaPanel.addView(body(
            "Download GGUF → Start server → nanobot uses http://127.0.0.1:8080/v1"));
        Button localModels = pill("Local models (download GGUF)…", true);
        localModels.setOnClickListener(v -> {
            if (!PrivacyPrefs.localLlamaEnabled(this)) {
                toast("Turn on “Enable on-device llama.cpp” first");
                return;
            }
            startActivity(new Intent(this, LocalModelsActivity.class));
        });
        localLlamaPanel.addView(pad(localModels));
        localLlamaPanel.addView(body("Or any OpenAI-compatible base URL (LAN / external server):"));
        baseUrlEdit = new EditText(this);
        baseUrlEdit.setHint("http://127.0.0.1:8080/v1");
        baseUrlEdit.setTextColor(C_FG);
        baseUrlEdit.setHintTextColor(C_MUT);
        baseUrlEdit.setBackground(round(C_BUBBLE_AI, dp(10)));
        baseUrlEdit.setPadding(dp(12), dp(10), dp(12), dp(10));
        baseUrlEdit.setSingleLine(true);
        localLlamaPanel.addView(baseUrlEdit);
        Button applyLocal = pill("Apply local backend URL", true);
        applyLocal.setOnClickListener(v -> {
            String u = baseUrlEdit.getText() != null ? baseUrlEdit.getText().toString().trim() : "";
            if (u.isEmpty()) {
                if (PrivacyPrefs.localLlamaEnabled(this)) u = LlamaRuntime.baseUrl();
                else { toast("Enter a base URL"); return; }
            }
            applyBackend("local", u, null);
        });
        localLlamaPanel.addView(pad(applyLocal));
        col.addView(localLlamaPanel);
        applyLocalLlamaUi();

        col.addView(section("Model"));
        modelStatus = body("…");
        col.addView(modelStatus);
        Button refreshModels = pill("Refresh model list", false);
        refreshModels.setOnClickListener(v -> refreshModels());
        col.addView(pad(refreshModels));
        // model chips also on chat strip; settings gets a second row container
        LinearLayout settingsModels = new LinearLayout(this);
        settingsModels.setId(View.generateViewId());
        settingsModels.setOrientation(LinearLayout.VERTICAL);
        settingsModels.setTag("settings_models");
        col.addView(settingsModels);

        col.addView(section("Context & history"));
        col.addView(body(
            "Open WebUI-style controls: how many turns the model sees, "
                + "clear chat, edit core identity. Does not touch Grok login."));
        Button ctxBtn = pill("Context & history…", true);
        ctxBtn.setOnClickListener(v ->
            startActivity(new Intent(this, ContextSettingsActivity.class)));
        col.addView(pad(ctxBtn));
        Button clearChatBtn = pill("Clear chat (keep core)", false);
        clearChatBtn.setOnClickListener(v -> {
            ChatPrefs.clearChatViaPeer(this, peer);
            ChatPrefs.clearChatKeepCore(this);
            toast("Chat history cleared");
        });
        col.addView(pad(clearChatBtn));

        col.addView(section("Grok web sign-in"));
        col.addView(body(
            "Browser login for Grok is under Providers — not here.\n"
                + "Local mode never needs a browser."));
        connectBtnSettings = pill("Open Providers · Grok sign-in…", true);
        connectBtnSettings.setOnClickListener(v ->
            startActivity(new Intent(this, ProvidersActivity.class)));
        col.addView(pad(connectBtnSettings));

        col.addView(section("Agent service"));
        col.addView(body("On-device agent peer (port " + NanobotRuntime.PORT
            + ") for tools / API. Separate from Grok web login and from local llama."));
        serviceStatus = mono("…");
        col.addView(serviceStatus);
        swService = sw("Agent service running", PrivacyPrefs.serviceEnabled(this), (b, v) -> {
            setBusy(true, v ? "Starting service…" : "Stopping service…");
            io.execute(() -> {
                NanobotRuntime.setServiceRunning(this, v);
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                h.post(() -> {
                    setBusy(false, null);
                    refreshSettings();
                    toast(v ? "Service ON" : "Service OFF");
                });
            });
        });
        col.addView(swService);

        // LAN / MCP share hub
        col.addView(section("MCP servers (outbound)"));
        col.addView(body(
            "Connect this agent TO remote MCP tool servers (HTTP). "
                + "Enabled servers become mcp_list / mcp_call tools for Grok."));
        Button mcpServers = pill("MCP servers…", true);
        mcpServers.setOnClickListener(v ->
            startActivity(new Intent(this, McpServersActivity.class)));
        col.addView(mcpServers);

        col.addView(section("Share API & MCP"));
        col.addView(body(
            "Other devices on Wi‑Fi can use this agent only when:\n"
                + "1) Agent service is ON\n"
                + "2) LAN share is ON\n"
                + "3) They have the peer token (MCP clients must be paired with biometrics)\n\n"
                + "Provider passwords stay sealed on this phone. Open Share hub for the full guide."));
        swLan = sw("Share agent on Wi‑Fi (LAN)", PrivacyPrefs.shareLan(this), (b, v) -> {
            PrivacyPrefs.setShareLan(this, v);
            if (v && PrivacyPrefs.serviceEnabled(this) && !NanobotRuntime.isPortListening()) {
                NanobotRuntime.startPeer(this);
            }
            refreshSettings();
            toast(v ? "LAN share on — use Share hub for token/MCP" : "LAN share off");
        });
        col.addView(swLan);
        lanUrlView = mono("…");
        col.addView(lanUrlView);
        LinearLayout shareRow = new LinearLayout(this);
        Button shareHub = pill("Share hub / MCP pair…", true);
        shareHub.setOnClickListener(v ->
            startActivity(new Intent(this, ShareHubActivity.class)));
        Button accessHist = pill("Access history", false);
        accessHist.setOnClickListener(v ->
            startActivity(new Intent(this, AccessHistoryActivity.class)));
        shareRow.addView(shareHub, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams shr = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        shr.leftMargin = dp(8);
        shareRow.addView(accessHist, shr);
        col.addView(pad(shareRow));

        col.addView(section("Peer token (gated)"));
        tokenView = mono("Token hidden — use Share hub (biometric / PIN required).");
        col.addView(tokenView);
        Button openTok = pill("Reveal token in Share hub…", false);
        openTok.setOnClickListener(v ->
            startActivity(new Intent(this, ShareHubActivity.class)));
        col.addView(pad(openTok));

        col.addView(section("Privacy & shell policy"));
        col.addView(body("Agent shell is filtered by denylist + allow exceptions under NANOBOT_HOME. Manage both lists in the app."));
        policySum = mono(ShellPolicy.summary(this));
        col.addView(policySum);
        Button managePolicy = pill("Manage shell denylist…", true);
        managePolicy.setOnClickListener(v ->
            startActivity(new Intent(this, ShellPolicyActivity.class)));
        col.addView(pad(managePolicy));
        swNetAgents = sw("Allow network clients (prompt API)", PrivacyPrefs.allowNetworkAgents(this),
            (b, v) -> { PrivacyPrefs.setAllowNetworkAgents(this, v); toast(v ? "Network clients on" : "off"); });
        col.addView(swNetAgents);
        swDevice = sw("Allow device control (shell)", PrivacyPrefs.deviceControl(this),
            (b, v) -> { PrivacyPrefs.setDeviceControl(this, v); toast(v ? "Shell control ON" : "OFF"); });
        col.addView(swDevice);
        col.addView(sw("a11y UI control (taps/apps)", PrivacyPrefs.a11yControl(this),
            (b, v) -> {
                PrivacyPrefs.setA11yControl(this, v);
                toast(v ? "a11y ON — enable service in system Accessibility" : "a11y OFF");
            }));
        Button deviceOps = pill("Device ops · depth · a11y…", true);
        deviceOps.setOnClickListener(v ->
            startActivity(new Intent(this, DeviceOpsActivity.class)));
        col.addView(pad(deviceOps));
        swReboot = sw("Allow reboot (quick exception)", PrivacyPrefs.allowReboot(this),
            (b, v) -> {
                PrivacyPrefs.setAllowReboot(this, v);
                if (policySum != null) policySum.setText(ShellPolicy.summary(this));
                toast(v ? "reboot allowed (shell_allow)" : "reboot exception removed");
            });
        col.addView(swReboot);

        col.addView(section("Personal photos & files"));
        col.addView(body(
            "What may the agent (and remote MCP) do with your private folders "
                + "(Photos/DCIM, Download, Documents, WhatsApp media, …)?\n\n"
                + "This does not change shell denylist. Default is Blocked (safest)."));
        filesAclSummary = mono(PrivacyPrefs.labelFilesAcl(PrivacyPrefs.filesAcl(this)));
        col.addView(filesAclSummary);
        LinearLayout acl = new LinearLayout(this);
        acl.setOrientation(LinearLayout.VERTICAL);
        btnAclDeny = pill("🚫 Blocked (recommended)", true);
        btnAclRead = pill("👁 Read only", false);
        btnAclFull = pill("⚠️ Full access", false);
        btnAclDeny.setOnClickListener(v -> setFilesAclUi("deny"));
        btnAclRead.setOnClickListener(v -> setFilesAclUi("read"));
        btnAclFull.setOnClickListener(v -> setFilesAclUi("full"));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.bottomMargin = dp(6);
        acl.addView(btnAclDeny, al);
        acl.addView(btnAclRead, al);
        acl.addView(btnAclFull, al);
        col.addView(acl);
        paintFilesAclButtons();

        col.addView(section("Logs & history"));
        col.addView(body("Peer log, agent memory, and access (pairing / share) history."));
        LinearLayout logs = new LinearLayout(this);
        logs.setOrientation(LinearLayout.HORIZONTAL);
        Button viewLog = pill("Peer log", true);
        viewLog.setOnClickListener(v -> {
            Intent i = new Intent(this, LogViewerActivity.class);
            i.putExtra(LogViewerActivity.EXTRA_MODE, "log");
            startActivity(i);
        });
        Button viewHist = pill("Memory", false);
        viewHist.setOnClickListener(v -> {
            Intent i = new Intent(this, LogViewerActivity.class);
            i.putExtra(LogViewerActivity.EXTRA_MODE, "history");
            startActivity(i);
        });
        logs.addView(viewLog, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hl.leftMargin = dp(8);
        logs.addView(viewHist, hl);
        col.addView(pad(logs));
        Button accessHist2 = pill("API / MCP access history", false);
        accessHist2.setOnClickListener(v ->
            startActivity(new Intent(this, AccessHistoryActivity.class)));
        col.addView(pad(accessHist2));

        col.addView(section("Peer control"));
        Button startPeer = pill("Start / restart peer", true);
        startPeer.setOnClickListener(v -> {
            if (!PrivacyPrefs.serviceEnabled(this)) {
                toast("Turn Agent service ON first");
                return;
            }
            setBusy(true, "Starting peer…");
            io.execute(() -> {
                if (!NanobotRuntime.isPortListening()) NanobotRuntime.startPeer(this);
                try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                h.post(() -> {
                    setBusy(false, null);
                    bootstrapPeer();
                    toast(NanobotRuntime.isPortListening() ? "Peer up" : "Peer failed");
                });
            });
        });
        col.addView(pad(startPeer));

        sc.addView(col);
        // stash settings model container ref via tag on col
        sc.setTag(col);
        return sc;
    }

    private void applyLocalLlamaUi() {
        boolean on = PrivacyPrefs.localLlamaEnabled(this);
        if (swLocalLlama != null) swLocalLlama.setChecked(on);
        if (localLlamaPanel != null) {
            // Always show panel for URL override, but dim when fully off is confusing —
            // keep panel visible; GGUF entry checks the flag.
            localLlamaPanel.setAlpha(on ? 1f : 0.55f);
        }
        if (btnLocal != null) {
            btnLocal.setText(on ? "Local (llama)" : "Local API");
        }
    }

    private void selectBackend(String kind) {
        if ("local".equals(kind)) {
            String u = baseUrlEdit != null && baseUrlEdit.getText() != null
                ? baseUrlEdit.getText().toString().trim() : "";
            if (u.isEmpty()) {
                if (PrivacyPrefs.localLlamaEnabled(this)) u = LlamaRuntime.baseUrl();
                else u = "http://127.0.0.1:8080/v1";
            }
            applyBackend("local", u, null);
        } else {
            applyBackend("grok", null, null);
        }
    }

    private void applyBackend(String backend, String baseUrl, String model) {
        setBusy(true, "Switching provider…");
        io.execute(() -> {
            try {
                JSONObject j = peer.setBackend(backend, baseUrl, model);
                h.post(() -> {
                    setBusy(false, null);
                    currentBackend = j.optString("backend", backend);
                    currentModel = j.optString("model", "");
                    paintProviderButtons();
                    toast("Provider: " + currentBackend + (currentModel.isEmpty() ? "" : " · " + currentModel));
                    refreshSettings();
                    refreshModels();
                    updateConnectUi(!j.optBoolean("needs_browser", false) || j.optBoolean("signed_in", false));
                });
            } catch (Exception e) {
                h.post(() -> {
                    setBusy(false, null);
                    toast(e.getMessage());
                    addSystem("Provider switch failed: " + e.getMessage());
                });
            }
        });
    }

    private void paintProviderButtons() {
        boolean cloud = !"local".equals(currentBackend) && !"llama".equals(currentBackend)
            && !"offline".equals(currentBackend) && !"openai_compatible".equals(currentBackend);
        if (btnCloud != null) {
            btnCloud.setBackground(round(cloud ? C_ACCENT : C_BUBBLE_USER, dp(20)));
            btnCloud.setTextColor(cloud ? 0xFF00343A : C_FG);
        }
        if (btnLocal != null) {
            btnLocal.setBackground(round(!cloud ? C_ACCENT : C_BUBBLE_USER, dp(20)));
            btnLocal.setTextColor(!cloud ? 0xFF00343A : C_FG);
        }
    }

    private void refreshModels() {
        final boolean localUi = modelsShouldBeLocal();
        io.execute(() -> {
            try {
                List<String> localGguf = new ArrayList<>();
                if (localUi) {
                    for (java.io.File f : LlamaManager.listLocalGguf(this)) {
                        if (LlamaManager.isComplete(f)) localGguf.add(f.getName());
                    }
                }

                PeerClient.ModelsResult r = peer.listModels(localUi);
                // Drop any cloud ids that slipped through when local
                if (localUi && r.ids != null) {
                    r.ids.removeIf(PeerClient::looksLikeCloudOnlyModel);
                }
                // Stuck on effort tier → clear; only auto-fix on cloud
                if (PeerClient.isEffortTierId(r.current)) {
                    if (!localUi && r.ids != null && !r.ids.isEmpty()) {
                        String fix = r.ids.get(0);
                        try {
                            JSONObject j = peer.setModel(fix);
                            r.current = j.optString("model", fix);
                        } catch (Exception ignored) {
                            r.current = fix;
                        }
                    } else {
                        r.current = "";
                    }
                }
                // Local: chips ONLY from on-disk GGUFs (never peer path + basename = 2 chips)
                if (localUi) {
                    java.util.LinkedHashMap<String, String> byBase = new java.util.LinkedHashMap<>();
                    for (String g : localGguf) {
                        if (g == null) continue;
                        String base = g.contains("/") ? g.substring(g.lastIndexOf('/') + 1) : g;
                        if (base.toLowerCase(Locale.US).endsWith(".gguf")) byBase.put(base, base);
                    }
                    r.ids = new ArrayList<>(byBase.values());
                    r.baseUrl = LlamaRuntime.baseUrl();
                    String sel = PrivacyPrefs.selectedLocalModelPath(this);
                    if (sel != null && !sel.isEmpty()) {
                        String b = new java.io.File(sel).getName();
                        if (byBase.containsKey(b)) r.current = b;
                        else if (!byBase.isEmpty()) r.current = byBase.values().iterator().next();
                    } else if (!byBase.isEmpty()) {
                        r.current = byBase.values().iterator().next();
                    } else {
                        r.current = "";
                    }
                    r.error = LlamaRuntime.isServerUp() ? "direct llama (no agent)" : "engine DOWN — Start model";
                }
                final PeerClient.ModelsResult fr = r;
                final boolean loc = localUi;
                final boolean engineUp = loc && LlamaRuntime.isServerUp();
                h.post(() -> {
                    currentModel = fr.current != null ? fr.current : currentModel;
                    if (loc && PeerClient.looksLikeCloudOnlyModel(currentModel)) {
                        currentModel = "";
                    }
                    if (loc && currentModel != null && currentModel.contains("/")) {
                        currentModel = currentModel.substring(currentModel.lastIndexOf('/') + 1);
                    }
                    if (modelStatus != null) {
                        String scope = loc ? "LOCAL (1 model chip = 1 GGUF)" : "REMOTE / cloud models";
                        String eng = loc ? (engineUp ? "engine UP" : "engine DOWN — Start a model") : "";
                        String note = fr.error != null && !fr.error.isEmpty() ? fr.error : "ok";
                        modelStatus.setText(scope + " · " + eng + " · " + note
                            + "\nUsing: " + (currentModel.isEmpty() ? "—" : currentModel)
                            + "\nBase: " + (fr.baseUrl == null || fr.baseUrl.isEmpty() ? "—" : fr.baseUrl));
                    }
                    fillModelChips(fr.ids, currentModel, loc);
                });
            } catch (Exception e) {
                h.post(() -> {
                    if (modelStatus != null) modelStatus.setText("Models error: " + e.getMessage());
                    fillModelChips(null, currentModel, localUi);
                });
            }
        });
    }

    private void fillModelChips(List<String> ids, String current, boolean localUi) {
        modelRow.removeAllViews();
        // Scope label chip
        TextView scope = new TextView(this);
        scope.setText(localUi ? "Local:" : "Remote:");
        scope.setTextColor(C_ACCENT);
        scope.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        scope.setPadding(dp(4), dp(8), dp(8), dp(6));
        modelRow.addView(scope);

        if (ids == null || ids.isEmpty()) {
            TextView t = new TextView(this);
            if (localUi) {
                t.setText(PrivacyPrefs.localLlamaEnabled(this)
                    ? "no GGUF yet — Providers → Local models"
                    : "enable on-device llama in Settings, or add a local OpenAI URL");
            } else {
                String cur = current;
                if (PeerClient.isEffortTierId(cur) || PeerClient.looksLikeCloudOnlyModel(cur) && false)
                    cur = current;
                if (PeerClient.isEffortTierId(cur)) cur = null;
                t.setText(cur != null && !cur.isEmpty()
                    ? "model: " + cur
                    : "no remote models — Providers → connect cloud");
            }
            t.setTextColor(C_MUT);
            t.setPadding(dp(8), dp(6), dp(8), dp(6));
            modelRow.addView(t);
            return;
        }
        String cur = current;
        if (PeerClient.isEffortTierId(cur)) cur = null;
        if (localUi && PeerClient.looksLikeCloudOnlyModel(cur)) cur = null;
        for (String id : ids) {
            if (!PeerClient.isRealModelId(id)) continue;
            if (localUi && PeerClient.looksLikeCloudOnlyModel(id)) continue;
            if (!localUi && id.toLowerCase(Locale.US).endsWith(".gguf")) continue;
            final String mid = id;
            boolean on = mid.equals(cur);
            Button b = pill(shortModel(mid), on);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            b.setOnClickListener(v -> pickModel(mid, localUi));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            modelRow.addView(b, lp);
        }
    }

    private String shortModel(String id) {
        if (id == null) return "?";
        // Always label GGUF by basename so path/name never look like two models
        String leaf = id;
        if (leaf.contains("/")) leaf = leaf.substring(leaf.lastIndexOf('/') + 1);
        if (leaf.toLowerCase(Locale.US).endsWith(".gguf")) {
            String n = leaf.substring(0, leaf.length() - 5);
            if (n.length() <= 22) return n;
            return n.substring(0, 12) + "…" + n.substring(n.length() - 6);
        }
        if (leaf.length() <= 22) return leaf;
        return leaf.substring(0, 10) + "…" + leaf.substring(leaf.length() - 8);
    }

    private void pickModel(String model) {
        pickModel(model, modelsShouldBeLocal());
    }

    private void pickModel(String model, boolean localUi) {
        if (!PeerClient.isRealModelId(model) || PeerClient.isEffortTierId(model)) {
            toast("Not a selectable model");
            return;
        }
        if (localUi && PeerClient.looksLikeCloudOnlyModel(model)) {
            toast("Cloud models are not available in Local mode");
            return;
        }
        // GGUF file → start llama-server with that file
        if (localUi && model.toLowerCase(Locale.US).endsWith(".gguf")) {
            setBusy(true, "Starting local model…");
            io.execute(() -> {
                try {
                    if (!PrivacyPrefs.localLlamaEnabled(this)) {
                        throw new Exception("Enable on-device llama.cpp in Settings first");
                    }
                    java.io.File f = LlamaManager.modelFile(this, model);
                    if (!LlamaManager.isComplete(f)) throw new Exception("GGUF missing: " + model);
                    PrivacyPrefs.setSelectedLocalModelPath(this, f.getAbsolutePath());
                    final boolean custom = PrivacyPrefs.isCustomGguf(this, f.getName());
                    // Always (re)start this exact GGUF so list selection is honored
                    if (LlamaRuntime.isServerUp()) LlamaRuntime.stop(this);
                    String err = LlamaRuntime.start(this, f, 4096);
                    if (err != null && !LlamaRuntime.isServerUp()) throw new Exception(err);
                    if (custom) {
                        h.post(() -> setBusy(true, "Probing tool calling…"));
                        LlamaRuntime.ToolProbeResult probe =
                            LlamaRuntime.probeToolCalling(f.getAbsolutePath());
                        PrivacyPrefs.setToolsSupported(this, f.getAbsolutePath(), probe.supported);
                        LlamaRuntime.applyAsNanobotBackend(this, peer, f);
                        ProviderProfile loc = ProviderStore.get(this, "llama_local");
                        if (loc != null) {
                            loc.enabled = true;
                            loc.model = f.getAbsolutePath();
                            ProviderStore.upsert(this, loc);
                        }
                        final LlamaRuntime.ToolProbeResult fr = probe;
                        h.post(() -> {
                            setBusy(false, null);
                            currentModel = f.getName();
                            currentBackend = "local";
                            refreshModels();
                            setStatus(statusLine());
                            if (routeChip != null) {
                                routeChip.setText(ProviderRouter.statusLine(this)
                                    + (fr.supported
                                        ? "\nCustom: tools OK"
                                        : "\nCustom: tools NOT supported"));
                            }
                            if (fr.supported) {
                                toast("Using " + f.getName() + " · tools OK");
                            } else {
                                new android.app.AlertDialog.Builder(this)
                                    .setTitle(fr.title())
                                    .setMessage(f.getName() + "\n\n" + fr.detail
                                        + "\n\nChat still works. Tools/shell stay off for this model.")
                                    .setPositiveButton("OK", null)
                                    .show();
                                toast("Tool calling not supported");
                            }
                        });
                        return;
                    }
                    PrivacyPrefs.setToolsSupported(this, f.getAbsolutePath(), true);
                    LlamaRuntime.applyAsNanobotBackend(this, peer, f);
                    ProviderProfile loc = ProviderStore.get(this, "llama_local");
                    if (loc != null) {
                        loc.enabled = true;
                        loc.model = f.getAbsolutePath();
                        ProviderStore.upsert(this, loc);
                    }
                    h.post(() -> {
                        setBusy(false, null);
                        currentModel = f.getName();
                        currentBackend = "local";
                        toast("Using " + f.getName());
                        refreshModels();
                        setStatus(statusLine());
                        if (routeChip != null) routeChip.setText(ProviderRouter.statusLine(this));
                    });
                } catch (Exception e) {
                    h.post(() -> {
                        setBusy(false, null);
                        toast(e.getMessage());
                    });
                }
            });
            return;
        }
        setBusy(true, "Selecting " + model + "…");
        io.execute(() -> {
            try {
                JSONObject j = peer.setModel(model);
                h.post(() -> {
                    setBusy(false, null);
                    currentModel = j.optString("model", model);
                    toast("Model: " + currentModel);
                    refreshModels();
                    setStatus(statusLine());
                });
            } catch (Exception e) {
                h.post(() -> {
                    setBusy(false, null);
                    toast(e.getMessage());
                });
            }
        });
    }

    private void onConnectClicked() {
        // All web auth → Providers (device-code). Chat never owns this flow.
        startActivity(new Intent(this, ProvidersActivity.class));
    }

    /**
     * Single source of truth for chat header + settings Grok buttons + banner.
     * Providers and Main must not disagree: only peer signed_in / login_required.
     */
    private void updateConnectUi(boolean signedInOrLocalOk) {
        boolean localMode = "local".equals(ProviderStore.mode(this));
        // Local mode never needs Grok chrome; still keep peer session on disk.
        if (localMode) {
            providerSignedIn = false;
            if (connectBtn != null) connectBtn.setText("Providers");
            if (connectBtnSettings != null) {
                connectBtnSettings.setText("Providers (local needs no browser)");
            }
            return;
        }
        providerSignedIn = signedInOrLocalOk;
        if (connectBtn != null) {
            connectBtn.setText(providerSignedIn ? "Grok ✓" : "Grok…");
        }
        if (connectBtnSettings != null) {
            connectBtnSettings.setText(providerSignedIn
                ? "Grok signed in · open Providers"
                : "Open Providers · Grok sign-in…");
        }
    }

    private boolean isCloudBackend() {
        if ("local".equals(ProviderStore.mode(this))) return false;
        return "grok".equals(currentBackend) || "cloud".equals(currentBackend)
            || currentBackend == null || currentBackend.isEmpty();
    }

    /** Apply peer /api/auth JSON to Main chat + settings chrome (must match Providers). */
    private void applyAuthStatus(JSONObject a) {
        if (a == null) return;
        String be = a.optString("backend", currentBackend);
        if (be != null && !be.isEmpty()) currentBackend = be;
        String model = a.optString("model", currentModel);
        if (model != null && !model.isEmpty()) currentModel = model;
        boolean signed = a.optBoolean("signed_in", false);
        boolean loginReq = a.optBoolean("login_required", !signed);
        boolean pending = a.optBoolean("login_pending", false);
        // needs_browser is backend TYPE (always true for Grok) — never treat as "logged out".
        boolean cloudOk = signed || !isCloudBackend();
        updateConnectUi(cloudOk);
        if (authBanner != null) {
            if (isCloudBackend() && !signed && (loginReq || pending)) {
                String code = a.optString("user_code", "");
                authBanner.setVisibility(View.VISIBLE);
                authBanner.setText("Grok sign-in needed"
                    + (code.isEmpty() ? "" : " · " + code)
                    + "\nTap → Providers (web login)");
                authBanner.setTag(a.optString("verification_uri_complete",
                    a.optString("verification_uri", "")));
            } else {
                authBanner.setVisibility(View.GONE);
                authBanner.setTag(null);
            }
        }
        if (providerStatus != null && isCloudBackend()) {
            providerStatus.setText("Backend: " + currentBackend
                + "\nModel: " + (currentModel == null || currentModel.isEmpty() ? "—" : currentModel)
                + "\nBase: " + a.optString("base_url", "—")
                + "\nSession: " + (signed ? "signed in" : (pending ? "browser pending" : "needs browser login")));
        }
    }

    /** Re-read CLI auth — used on every resume so chat matches Providers. */
    private void syncAuthFromPeer() {
        io.execute(() -> {
            try {
                if (!NanobotCli.available(this)) {
                    h.post(() -> {
                        if (isCloudBackend() && authBanner != null) {
                            authBanner.setVisibility(View.VISIBLE);
                            authBanner.setText(
                                "Agent binary missing — product ROM needed.\n"
                                    + "Tap → Providers");
                        }
                        updateConnectUi(false);
                    });
                    return;
                }
                JSONObject a = NanobotCli.authStatus(this);
                h.post(() -> {
                    applyAuthStatus(a);
                    paintProviderButtons();
                    setStatus(statusLine());
                });
            } catch (Exception e) {
                h.post(() -> {
                    if (providerStatus != null) {
                        providerStatus.setText("Auth sync: " + e.getMessage());
                    }
                });
            }
        });
    }

    private void connectProvider(boolean force) {
        startActivity(new Intent(this, ProvidersActivity.class));
    }

    private void showAuthPending(String code, String uri) {
        if (authBanner == null) return;
        authBanner.setVisibility(View.VISIBLE);
        authBanner.setText("Grok sign-in needed"
            + (code == null || code.isEmpty() ? "" : " · " + code)
            + "\nTap → Providers (web login)");
        authBanner.setTag(uri);
        updateConnectUi(false);
    }

    private void startAuthPoll() {
        // Polling lives in ProvidersActivity after browser sign-in.
    }

    private void bootstrapPeer() {
        setBusy(true, "Starting…");
        io.execute(() -> {
            // CLI agent — no HTTP peer. Session sealed under app home.
            final boolean up = NanobotCli.available(this);
            JSONObject auth = null;
            if (up) {
                try {
                    if (!"local".equals(ProviderStore.mode(this))) {
                        NanobotCli.setRemoteGrok(this, "grok-4.5");
                        currentBackend = "grok";
                        currentModel = "grok-4.5";
                    }
                    auth = NanobotCli.authStatus(this);
                } catch (Exception ignored) {}
            }
            final JSONObject authF = auth;
            h.post(() -> {
                setBusy(false, null);
                if (!up) {
                    boolean remote = !"local".equals(ProviderStore.mode(this));
                    setStatus(remote
                        ? "Agent binary missing"
                        : "Local needs on-device engine");
                    if (remote) {
                        authBanner.setVisibility(View.VISIBLE);
                        authBanner.setText(
                            "Grok sign-in is in Providers (browser / CLI).\n"
                                + "No open agent port.");
                    }
                    return;
                }
                if (authF != null) {
                    applyAuthStatus(authF);
                } else if (isCloudBackend()) {
                    showAuthPending("", null);
                }
                paintProviderButtons();
                setStatus(statusLine());
                refreshSettings();
                refreshModels();
            });
        });
    }

    private String statusLine() {
        String be = currentBackend == null ? "?" : currentBackend;
        String m = currentModel == null || currentModel.isEmpty() ? "—" : currentModel;
        return be + " · " + m + " · " + lanIp() + ":" + NanobotRuntime.PORT;
    }

    private void refreshSettings() {
        if (lanUrlView == null) return;
        boolean up = NanobotRuntime.isPortListening();
        boolean svc = PrivacyPrefs.serviceEnabled(this);
        boolean lan = PrivacyPrefs.shareLan(this);
        if (serviceStatus != null) {
            serviceStatus.setText("Service pref: " + (svc ? "ON" : "OFF")
                + "\nPeer port: " + (up ? "LISTENING :" + NanobotRuntime.PORT : "DOWN")
                + "\nScreen lock for MCP: "
                + (BiometricGate.canAuthenticate(this) ? "ready" : "SET A PIN/BIOMETRIC"));
        }
        lanUrlView.setText(
            "How others connect (when service + LAN share ON):\n"
                + "  Base URL: " + lanShareUrl() + "\n"
                + "  Header:   X-Nanobot-Peer-Token: <token>\n"
                + "  Health:   GET …/peer/v1/health (no token)\n"
                + "  Chat:     POST …/api/chat  {\"prompt\":\"…\"}\n\n"
                + "Peer: " + (up ? "LISTENING" : "DOWN")
                + " · Share: " + (lan ? "ON" : "OFF")
                + " · Service: " + (svc ? "ON" : "OFF")
                + "\nIP: " + lanIp()
                + "\n\nMCP clients: pair in Share hub (biometric required).");
        if (tokenView != null) {
            tokenView.setText("Token is never shown here.\n"
                + "Use Share hub → Show token (biometric / PIN).\n"
                + "Encrypted cache: "
                + (SecureStore.cachedPeerToken(this) != null ? "yes" : "empty"));
        }
        if (swService != null) swService.setChecked(svc);
        if (swLan != null) swLan.setChecked(lan);
        if (swDevice != null) swDevice.setChecked(PrivacyPrefs.deviceControl(this));
        if (swNetAgents != null) swNetAgents.setChecked(PrivacyPrefs.allowNetworkAgents(this));
        if (swReboot != null) swReboot.setChecked(PrivacyPrefs.allowReboot(this));
        if (swEnterSend != null) swEnterSend.setChecked(PrivacyPrefs.enterAsSend(this));
        if (swLocalLlama != null) swLocalLlama.setChecked(PrivacyPrefs.localLlamaEnabled(this));
        applyLocalLlamaUi();
        paintModeButtons();
        paintFilesAclButtons();
        if (providerStatus != null) {
            providerStatus.setText(ProviderRouter.statusLine(this));
        }
        if (policySum != null) policySum.setText(ShellPolicy.summary(this));
        paintProviderButtons();
        io.execute(() -> {
            try {
                if (!NanobotCli.available(this)) {
                    h.post(() -> {
                        if (providerStatus != null) {
                            providerStatus.setText("CLI agent: binary missing");
                        }
                    });
                    return;
                }
                JSONObject a = NanobotCli.authStatus(this);
                final String baseB = a.optString("base_url", "");
                h.post(() -> {
                    applyAuthStatus(a);
                    paintProviderButtons();
                    if (baseUrlEdit != null && "local".equals(currentBackend)) {
                        if (!baseB.isEmpty() && (baseUrlEdit.getText() == null || baseUrlEdit.getText().length() == 0))
                            baseUrlEdit.setText(baseB);
                    }
                });
            } catch (Exception e) {
                final String lineErr = "Status error: " + e.getMessage();
                h.post(() -> {
                    if (providerStatus != null) providerStatus.setText(lineErr);
                });
            }
        });
    }


    private void refreshToken(boolean reveal) {
        String t = NanobotRuntime.readPeerToken(this);
        if (t == null || t.isEmpty()) {
            tokenView.setText("Token: (none — start peer)");
            return;
        }
        if (reveal) tokenView.setText("Token:\n" + t);
        else {
            String mask = t.length() <= 8 ? "••••" : t.substring(0, 4) + "…" + t.substring(t.length() - 4);
            tokenView.setText("Token (masked):\n" + mask
                + "\npersonal files=" + PrivacyPrefs.labelFilesAclShort(PrivacyPrefs.filesAcl(this))
                + "  shell=" + PrivacyPrefs.deviceControl(this)
                + "  reboot=" + PrivacyPrefs.allowReboot(this));
        }
    }

    private void setProviderMode(String mode) {
        if ("auto".equals(mode)) mode = "remote";
        ProviderStore.setMode(this, mode);
        paintModeButtons();
        if ("local".equals(mode)) {
            currentBackend = "local";
            setStatus("Local — on-device model…");
            toast("Local");
            refreshModels();
            if (routeChip != null) routeChip.setText("mode=local · starting engine…");
            io.execute(() -> {
                try {
                    // Does NOT touch session / peer_token — only llama + env backend
                    ProviderRouter.ensureLocalOffline(this, peer);
                    h.post(() -> {
                        paintProviderButtons();
                        refreshModels();
                        setStatus("Local ready");
                        if (routeChip != null) {
                            routeChip.setText("mode=local · " + LlamaRuntime.baseUrl()
                                + "\nGrok session file kept on disk");
                        }
                    });
                } catch (Exception e) {
                    h.post(() -> {
                        setStatus("Local: " + e.getMessage());
                        if (routeChip != null) routeChip.setText("mode=local · " + e.getMessage());
                        toast(e.getMessage());
                    });
                }
            });
            return;
        }
        // REMOTE = Grok only. Never wipe auth; only switch backend env.
        currentBackend = "grok";
        if (currentModel == null || currentModel.isEmpty()
                || currentModel.toLowerCase(Locale.US).endsWith(".gguf")
                || currentModel.contains("/")) {
            currentModel = "grok-4.5";
        }
        setStatus("Remote — Grok…");
        toast("Remote = Grok");
        if (routeChip != null) routeChip.setText("mode=remote · Grok…");
        io.execute(() -> {
            try {
                ensurePeerAlive();
                org.json.JSONObject st = ProviderRouter.ensureRemoteGrok(
                    this, peer, currentModel);
                // signed_in = real session. needs_browser = backend TYPE (always true for Grok).
                // Do NOT treat needs_browser as "session expired".
                final JSONObject stF = st;
                String model = st != null ? st.optString("model", "grok-4.5") : "grok-4.5";
                if (model.isEmpty() || model.endsWith(".gguf")) model = "grok-4.5";
                currentModel = model;
                currentBackend = "grok";
                h.post(() -> {
                    paintProviderButtons();
                    paintModeButtons();
                    refreshModels();
                    if (stF != null) applyAuthStatus(stF);
                    else showAuthPending("", null);
                    boolean signed = stF != null && stF.optBoolean("signed_in", false);
                    if (signed) {
                        setStatus("Grok · " + currentModel);
                        if (routeChip != null) {
                            routeChip.setText("mode=remote · Grok · signed_in\n"
                                + currentModel);
                        }
                        toast("Grok ready");
                    } else {
                        setStatus("Grok — sign in under Providers");
                        if (routeChip != null) {
                            routeChip.setText("mode=remote · Grok · need web sign-in\n"
                                + "Providers → Sign in with browser");
                        }
                        toast("Providers → Grok web sign-in");
                    }
                });
            } catch (Exception e) {
                h.post(() -> {
                    toast("Grok: " + e.getMessage());
                    setStatus("Grok error: " + e.getMessage());
                    refreshModels();
                });
            }
        });
    }

    /** Peer up on SHARED_HOME only — never deletes session/peer_token. */
    /** CLI path: binary present is enough. No HTTP peer required. */
    private void ensurePeerAlive() throws Exception {
        if (!NanobotCli.available(this)) {
            throw new Exception(NanobotRuntime.userFacingOfflineHint());
        }
    }

    private void paintModeButtons() {
        String m = ProviderStore.mode(this);
        if (modeLocal != null) paintModePill(modeLocal, "local".equals(m));
        if (modeRemote != null) paintModePill(modeRemote, "remote".equals(m));
        if (routeChip != null) routeChip.setText(ProviderRouter.statusLine(this));
    }

    private void paintModePill(Button b, boolean on) {
        if (b == null) return;
        b.setBackground(round(on ? C_ACCENT : 0xFF1A1A24, dp(16)));
        b.setTextColor(on ? 0xFF00343A : C_FG);
    }

    private void setFilesAclUi(String acl) {
        PrivacyPrefs.setFilesAcl(this, acl);
        paintFilesAclButtons();
        toast(PrivacyPrefs.labelFilesAclShort(acl));
    }

    private void paintFilesAclButtons() {
        String cur = PrivacyPrefs.filesAcl(this);
        if (filesAclSummary != null) {
            filesAclSummary.setText("Now: " + PrivacyPrefs.labelFilesAcl(cur));
        }
        paintModePill(btnAclDeny, "deny".equals(cur));
        paintModePill(btnAclRead, "read".equals(cur));
        paintModePill(btnAclFull, "full".equals(cur));
    }

    /** Model chips: Local = GGUF only; Remote = cloud Grok ids only. */
    private boolean modelsShouldBeLocal() {
        return "local".equals(ProviderStore.mode(this));
    }

    /**
     * Functional chat: binary path.
     * Local → llama (text + document text OK). Remote → Grok (+ vision images).
     * Never deletes peer_token / session.
     */
    private void sendMessage() {
        String caption = input.getText() != null ? input.getText().toString().trim() : "";
        final java.util.ArrayList<ChatAttachment.Item> attach =
            new java.util.ArrayList<>(pendingAttach);
        final boolean hasAttach = !attach.isEmpty();
        boolean hasImage = false;
        boolean hasDoc = false;
        for (ChatAttachment.Item it : attach) {
            if (it == null || !it.ok) continue;
            if (it.imageBase64 != null && !it.imageBase64.isEmpty()) hasImage = true;
            if (it.text != null && !it.text.isEmpty()
                && it.kind == ChatAttachment.Kind.DOCUMENT) hasDoc = true;
        }
        if ((caption.isEmpty() && !hasAttach) || sending) {
            // Stuck send: second tap after 15s re-arms Send + cancels stream (1.7.11).
            if (sending && sendStartedMs > 0
                    && SystemClock.elapsedRealtime() - sendStartedMs > 15_000L) {
                toast("Unsticking chat…");
                doneSend();
            }
            return;
        }
        final String prompt = ChatAttachment.buildPrompt(caption, attach);
        if (prompt.isEmpty()) return;
        input.setText("");
        String bubble = caption.isEmpty() ? "(attachments)" : caption;
        if (hasAttach) {
            StringBuilder names = new StringBuilder();
            for (ChatAttachment.Item it : attach) {
                if (it == null) continue;
                if (names.length() > 0) names.append(", ");
                names.append(it.name);
            }
            bubble = "📎 " + names + (caption.isEmpty() ? "" : "\n" + caption);
        }
        addBubble(bubble, true);
        clearPendingAttach();
        sending = true;
        sendGen++;
        final int gen = sendGen;
        sendStartedMs = SystemClock.elapsedRealtime();
        h.removeCallbacks(sendWatchdog);
        h.postDelayed(sendWatchdog, SEND_WATCHDOG_MS);
        setBusy(true, hasImage ? "Vision…" : (hasDoc ? "Reading files…" : "Thinking…"));
        send.setEnabled(false);
        final boolean local = "local".equals(ProviderStore.mode(this));
        final String sendText = prompt;
        final org.json.JSONArray images = ChatAttachment.imagesJson(attach);
        final boolean vision = images.length() > 0;
        final TextView streamBubble = addBubble("", false);
        final StringBuilder acc = new StringBuilder();
        io.execute(() -> {
            try {
                if (gen != sendGen) return;
                ensurePeerAlive();
                if (gen != sendGen) return;
                if (local) {
                    if (vision) {
                        h.post(() -> {
                            if (gen != sendGen) return;
                            streamBubble.setText(
                                "Images/PDF pages need Remote · Grok (vision).\n"
                                    + "Document text alone works on Local — remove images or switch Remote.");
                            doneSend();
                        });
                        return;
                    }
                    sendLocal(sendText, streamBubble, acc, gen);
                } else {
                    sendGrok(sendText, images, streamBubble, acc, gen);
                }
            } catch (Exception e) {
                h.post(() -> {
                    if (gen != sendGen) return;
                    streamBubble.setText("Error: " + e.getMessage());
                    doneSend();
                });
            }
        });
    }

    /** On-device: start engine if needed, chat llama direct (skip agent thrash). */
    private void sendLocal(String text, TextView streamBubble, StringBuilder acc, int gen) {
        h.post(() -> { if (gen == sendGen) setStatus("Local…"); });
        try {
            ProviderRouter.ensureLocalOffline(this, peer);
        } catch (Exception e) {
            h.post(() -> {
                if (gen != sendGen) return;
                streamBubble.setText("Local engine: " + e.getMessage()
                    + "\nOpen On-device AI → Start a GGUF.");
                doneSend();
            });
            return;
        }
        if (gen != sendGen) return;
        h.post(() -> { if (gen == sendGen) setStatus("Local thinking…"); });
        // Fast path: direct llama (no multi-turn tools). Tools: @! / titan2-nb-ops.
        LlamaRuntime.chatLocal(this, text, new PeerClient.StreamListener() {
            @Override public void onDelta(String d) {
                if (gen != sendGen) return;
                if (d == null || d.isEmpty() || "null".equalsIgnoreCase(d)) return;
                acc.append(d);
                final String snap = ChatCosplay.stripForDisplay(acc.toString());
                h.post(() -> {
                    if (gen != sendGen) return;
                    streamBubble.setText(Markdown.render(snap));
                    scrollBottom();
                    setStatus("Local typing…");
                });
            }
            @Override public void onDone(String full) {
                if (gen != sendGen) return;
                String out = (full != null && !full.isEmpty()) ? full : acc.toString();
                if (LlamaRuntime.isJunkReply(out)) out = "(no reply — try again)";
                out = ChatCosplay.stripForDisplay(out);
                final String f = out;
                h.post(() -> {
                    if (gen != sendGen) return;
                    streamBubble.setText(Markdown.render(f));
                    if (routeChip != null) routeChip.setText("mode=local · direct llama");
                    doneSend();
                });
            }
            @Override public void onError(Exception e) {
                if (gen != sendGen) return;
                if (e instanceof PeerClient.ChatCancelledException) return;
                h.post(() -> {
                    if (gen != sendGen) return;
                    streamBubble.setText("Local: "
                        + (e.getMessage() != null ? e.getMessage() : e));
                    doneSend();
                });
            }
        });
    }

    /** Cloud Grok via CLI / loopback peer. Optional vision falls back to text. */
    private void sendGrok(String text, org.json.JSONArray images,
                          TextView streamBubble, StringBuilder acc, int gen) {
        final boolean vision = images != null && images.length() > 0;
        h.post(() -> {
            if (gen == sendGen) {
                setStatus(vision ? "Grok… (text; vision via CLI later)" : "Grok…");
            }
        });
        try {
            String want = currentModel;
            if (want == null || want.isEmpty()
                    || want.toLowerCase(Locale.US).endsWith(".gguf")
                    || want.contains("/data/")
                    || want.contains("nanobot_models")) {
                want = "grok-4.5";
            }
            currentModel = want;
            currentBackend = "grok";
            NanobotCli.setRemoteGrok(this, want);
            JSONObject st = NanobotCli.authStatus(this);
            if (st != null && !st.optBoolean("signed_in", false)) {
                Log.i("MainActivity", "Grok signed_in=false — CLI chat may need Providers");
            }
        } catch (Exception e) {
            h.post(() -> {
                if (gen != sendGen) return;
                streamBubble.setText("Grok setup: " + e.getMessage());
                doneSend();
            });
            return;
        }
        if (gen != sendGen) return;
        final String prompt = text != null ? text : "";
        try {
            NanobotCli.chat(this, prompt, new PeerClient.StreamListener() {
                @Override public void onDelta(String delta) {
                    if (gen != sendGen) return;
                    if (delta == null || delta.isEmpty() || "null".equalsIgnoreCase(delta)) return;
                    acc.append(delta);
                    final String snap = ChatCosplay.stripForDisplay(acc.toString());
                    h.post(() -> {
                        if (gen != sendGen) return;
                        streamBubble.setText(Markdown.render(snap));
                        scrollBottom();
                        setStatus("Grok typing…");
                    });
                }
                @Override public void onDone(String full) {
                    if (gen != sendGen) return;
                    String f = (full != null && !full.isEmpty()) ? full : acc.toString();
                    if (LlamaRuntime.isJunkReply(f)) f = "(empty Grok reply)";
                    f = ChatCosplay.stripForDisplay(f);
                    final String out = f;
                    h.post(() -> {
                        if (gen != sendGen) return;
                        streamBubble.setText(Markdown.render(out));
                        updateConnectUi(true);
                        if (routeChip != null) {
                            routeChip.setText("mode=remote · Grok · CLI · "
                                + (currentModel.isEmpty() ? "grok-4.5" : currentModel));
                        }
                        doneSend();
                    });
                }
                @Override public void onError(Exception e) {
                    if (gen != sendGen) return;
                    // 1.7.11 cancel: UI already re-armed — no error bubble.
                    if (e instanceof PeerClient.ChatCancelledException) return;
                    h.post(() -> {
                        if (gen != sendGen) return;
                        if (e instanceof PeerClient.NeedLoginException) {
                            streamBubble.setText(
                                "Grok needs browser sign-in (Providers).\n"
                                    + "CLI auth — no open agent port.");
                            currentBackend = "grok";
                            updateConnectUi(false);
                            connectProvider(false);
                        } else {
                            streamBubble.setText("Grok: "
                                + (e.getMessage() != null ? e.getMessage() : e));
                        }
                        doneSend();
                    });
                }
            });
        } catch (Exception e) {
            h.post(() -> {
                if (gen != sendGen) return;
                streamBubble.setText("Grok CLI: " + e.getMessage());
                doneSend();
            });
        }
    }

    private void doneSend() {
        // 1.7.11: bump gen + cancel stream/CLI so peer CLOSE-WAIT does not linger
        // after 1.7.10 UI-only unstick (600s read residual under lab heat).
        sendGen++;
        NanobotCli.cancelActiveChat();
        sending = false;
        sendStartedMs = 0;
        h.removeCallbacks(sendWatchdog);
        if (send != null) send.setEnabled(true);
        setBusy(false, null);
        setStatus(statusLine());
    }

    private void addSystem(String text) {
        TextView t = new TextView(this);
        t.setText(Markdown.render(text));
        t.setTextColor(C_MUT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(dp(4), dp(8), dp(4), dp(8));
        chatLog.addView(t);
        scrollBottom();
    }

    /** @return the message TextView (for streaming updates) */
    private TextView addBubble(String text, boolean user) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(user ? Gravity.END : Gravity.START);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.topMargin = dp(8);
        wrap.setLayoutParams(wp);
        TextView who = new TextView(this);
        who.setText(user ? "You" : "Agent");
        who.setTextColor(user ? C_ACCENT : C_MUT);
        who.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        who.setPadding(dp(6), 0, dp(6), dp(4));
        wrap.addView(who);
        TextView bubble = new TextView(this);
        bubble.setText(user ? text : Markdown.render(text == null ? "" : text));
        bubble.setTextColor(C_FG);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bubble.setTextIsSelectable(true);
        bubble.setLinksClickable(true);
        int pad = dp(14);
        bubble.setPadding(pad, pad, pad, pad);
        bubble.setBackground(round(user ? C_BUBBLE_USER : C_BUBBLE_AI, dp(16)));
        bubble.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.88f));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = user ? Gravity.END : Gravity.START;
        wrap.addView(bubble, bp);
        chatLog.addView(wrap);
        scrollBottom();
        return bubble;
    }

    private void scrollBottom() {
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void setBusy(boolean busy, String msg) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (msg != null) setStatus(msg);
    }

    private void setStatus(String s) {
        if (status != null) status.setText(s != null ? s : "");
    }

    private String lanIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress())
                        return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) return Formatter.formatIpAddress(ip);
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }

    private String lanShareUrl() {
        return "http://" + lanIp() + ":" + NanobotRuntime.PORT;
    }

    private LinearLayout row(int bg) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackgroundColor(bg);
        r.setPadding(dp(12), dp(12), dp(12), dp(12));
        return r;
    }

    private TextView section(String t) {
        TextView v = new TextView(this);
        v.setText(t.toUpperCase(Locale.US));
        v.setTextColor(C_MUT);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(16), 0, dp(8));
        return v;
    }

    private TextView body(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(C_FG);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setPadding(0, 0, 0, dp(8));
        return v;
    }

    private TextView mono(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(C_ACCENT);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        v.setTextIsSelectable(true);
        v.setPadding(dp(12), dp(12), dp(12), dp(12));
        v.setBackground(round(C_BUBBLE_AI, dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
    }

    private View pad(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
    }

    private Switch sw(String label, boolean on, Switch.OnCheckedChangeListener l) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(C_FG);
        s.setChecked(on);
        s.setPadding(0, dp(10), 0, dp(10));
        s.setOnCheckedChangeListener(l);
        return s;
    }

    private Button pill(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setMinHeight(dp(42));
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setBackground(round(primary ? C_ACCENT : C_BUBBLE_USER, dp(20)));
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void copyText(String t) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("nanobot", t));
    }

    private void openBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            toast("Browser opened");
        } catch (Exception e) {
            toast(e.getMessage());
            addSystem("Open: " + url);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Hung stream left Send disabled across pause — not thermal, clear busy.
        if (sending && sendStartedMs > 0
                && SystemClock.elapsedRealtime() - sendStartedMs > 20_000L) {
            Log.w("MainActivity", "onResume unstick chat");
            doneSend();
        }
        // Peer is source of truth — chat must match Providers after browser login.
        syncAuthFromPeer();
        refreshSettings();
        refreshModels();
    }

    @Override
    protected void onDestroy() {
        if (authPoll != null) h.removeCallbacks(authPoll);
        h.removeCallbacks(sendWatchdog);
        io.shutdownNow();
        super.onDestroy();
    }
}
