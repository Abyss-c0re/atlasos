package com.titanus2.nanobot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Self-explanatory LAN API share + MCP pairing (biometric gated) + client list.
 */
public class ShareHubActivity extends Activity {
    private static final int C_BG = 0xFF0B0B0F;
    private static final int C_PANEL = 0xFF14141A;
    private static final int C_ACCENT = 0xFF00E5FF;
    private static final int C_FG = 0xFFF2F2F5;
    private static final int C_MUT = 0xFF8B8B9A;
    private static final int C_LINE = 0xFF22222C;
    private static final int C_OK = 0xFF0E2A1C;

    private LinearLayout clientList;
    private TextView explain;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(12), dp(12), dp(12));
        head.setBackgroundColor(C_PANEL);
        TextView title = new TextView(this);
        title.setText("Share API & MCP");
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

        col.addView(section("What this shares"));
        explain = body(
            "When LAN share is ON and the agent service is running, other devices "
                + "on the same Wi‑Fi can talk to this phone’s agent.\n\n"
                + "• Base URL — HTTP endpoint on this phone (port "
                + NanobotRuntime.PORT + ")\n"
                + "• Peer token — secret password for chat/shell/MCP (never public)\n"
                + "• MCP clients — must be paired here; pairing needs your "
                + "fingerprint / face / screen lock\n\n"
                + "Provider login (Grok etc.) stays on the phone, sealed under the peer token. "
                + "MCP clients never receive cloud passwords — only the peer token.");
        col.addView(explain);

        status = mono("…");
        col.addView(status);

        LinearLayout row1 = new LinearLayout(this);
        Button copyUrl = pill("Copy base URL", true);
        copyUrl.setOnClickListener(v -> {
            copy(baseUrl());
            AccessLog.record(this, "share_copy_url", "Copied base URL " + baseUrl());
            toast("URL copied");
        });
        Button how = pill("How to connect", false);
        how.setOnClickListener(v -> showHow());
        row1.addView(copyUrl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams g = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        g.leftMargin = dp(8);
        row1.addView(how, g);
        col.addView(pad(row1));

        col.addView(section("Peer token (secret)"));
        col.addView(body("Reveal or copy only after device authentication. "
            + "Anyone with this token can use the agent API while share is on."));
        LinearLayout tok = new LinearLayout(this);
        Button showTok = pill("Show token…", true);
        showTok.setOnClickListener(v -> revealToken(false));
        Button copyTok = pill("Copy token…", false);
        copyTok.setOnClickListener(v -> revealToken(true));
        tok.addView(showTok, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams g2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        g2.leftMargin = dp(8);
        tok.addView(copyTok, g2);
        col.addView(pad(tok));

        col.addView(section("MCP clients (pairing required)"));
        col.addView(body("New MCP / API clients must be approved with biometrics or your "
            + "screen lock. Revoke anytime. Access is logged."));
        Button pair = pill("Pair new MCP client…", true);
        pair.setOnClickListener(v -> pairClient());
        col.addView(pad(pair));
        clientList = new LinearLayout(this);
        clientList.setOrientation(LinearLayout.VERTICAL);
        col.addView(clientList);

        col.addView(section("Access history"));
        Button hist = pill("Open access history", false);
        hist.setOnClickListener(v ->
            startActivity(new Intent(this, AccessHistoryActivity.class)));
        col.addView(pad(hist));

        sc.addView(col);
        root.addView(sc, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        BiometricGate.onActivityResult(requestCode, resultCode);
    }

    private void refresh() {
        boolean svc = PrivacyPrefs.serviceEnabled(this);
        boolean lan = PrivacyPrefs.shareLan(this);
        boolean up = NanobotRuntime.isPortListening();
        int n = 0;
        for (McpClients.Client cl : McpClients.list(this)) if (cl.active) n++;
        status.setText(String.format(Locale.US,
            "Service: %s\nLAN share: %s\nPeer port: %s\nBase URL: %s\nActive MCP clients: %d\nDevice lock: %s",
            svc ? "ON" : "OFF",
            lan ? "ON" : "OFF",
            up ? "LISTENING :" + NanobotRuntime.PORT : "DOWN",
            baseUrl(),
            n,
            BiometricGate.canAuthenticate(this) ? "ready (biometric/PIN)" : "SET A SCREEN LOCK"));
        clientList.removeAllViews();
        List<McpClients.Client> clients = McpClients.list(this);
        if (clients.isEmpty()) {
            clientList.addView(body("No paired clients yet."));
        } else {
            for (McpClients.Client cl : clients) {
                clientList.addView(clientRow(cl));
            }
        }
    }

    private LinearLayout clientRow(McpClients.Client cl) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cl.active ? C_OK : 0xFF2A1A1A);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), C_LINE);
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        TextView t = new TextView(this);
        t.setTextColor(C_FG);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setText(String.format(Locale.US,
            "%s  [%s]\ncreated %s\nlast %s  ·  %s",
            cl.name, cl.id,
            cl.createdAt.isEmpty() ? "—" : cl.createdAt,
            cl.lastAccess == null || cl.lastAccess.isEmpty() ? "never" : cl.lastAccess,
            cl.active ? "ACTIVE" : "REVOKED"));
        row.addView(t);
        if (cl.active) {
            LinearLayout actions = new LinearLayout(this);
            actions.setPadding(0, dp(8), 0, 0);
            Button guide = pill("Show guide…", true);
            guide.setOnClickListener(v -> showClientGuide(cl));
            Button rev = pill("Revoke", false);
            rev.setOnClickListener(v -> revokeClient(cl));
            actions.addView(guide, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            rl.leftMargin = dp(8);
            actions.addView(rev, rl);
            row.addView(actions);
        }
        return row;
    }

    private void pairClient() {
        if (!PrivacyPrefs.serviceEnabled(this) || !NanobotRuntime.isPortListening()) {
            toast("Start the agent service first");
            return;
        }
        if (!PrivacyPrefs.shareLan(this) && !PrivacyPrefs.allowNetworkAgents(this)) {
            toast("Turn on LAN share or network clients in Settings");
        }
        BiometricGate.authenticate(this,
            "Approve MCP client",
            "Biometric or screen lock required to pair a new client",
            new BiometricGate.Callback() {
                @Override public void onAuthenticated() {
                    promptName();
                }
                @Override public void onFailed(String reason) {
                    AccessLog.record(ShareHubActivity.this, "mcp_pair_denied",
                        "Auth failed: " + reason);
                    toast("Pairing cancelled: " + reason);
                }
            });
    }

    private void promptName() {
        EditText e = new EditText(this);
        e.setHint("Client name (e.g. laptop-mcp)");
        e.setTextColor(C_FG);
        e.setHintTextColor(C_MUT);
        e.setSingleLine(true);
        e.setPadding(dp(16), dp(12), dp(16), dp(12));
        new AlertDialog.Builder(this)
            .setTitle("Pair MCP client")
            .setMessage("Name this client. You’ll see the connection guide after approval.")
            .setView(e)
            .setPositiveButton("Approve", (d, w) -> {
                try {
                    String name = e.getText() != null ? e.getText().toString() : "";
                    McpClients.Client cl = McpClients.approve(this, name);
                    // ensure token cached encrypted
                    String tok = NanobotRuntime.readPeerToken(this);
                    if (tok != null) SecureStore.cachePeerToken(this, tok);
                    refresh();
                    showClientGuide(cl);
                    toast("Client paired: " + cl.name);
                } catch (Exception ex) {
                    toast(ex.getMessage());
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showClientGuide(McpClients.Client cl) {
        BiometricGate.authenticate(this,
            "Show connection guide",
            "Unlock to reveal peer token for " + cl.name,
            new BiometricGate.Callback() {
                @Override public void onAuthenticated() {
                    String t0 = NanobotRuntime.readPeerToken(ShareHubActivity.this);
                    if (t0 == null) t0 = SecureStore.cachedPeerToken(ShareHubActivity.this);
                    final String tok = t0;
                    if (tok != null) SecureStore.cachePeerToken(ShareHubActivity.this, tok);
                    McpClients.touchAccess(ShareHubActivity.this, cl.id, "guide_shown");
                    final String guide = McpClients.connectionGuide(
                        ShareHubActivity.this, cl, baseUrl(), tok);
                    new AlertDialog.Builder(ShareHubActivity.this)
                        .setTitle("Connection — " + cl.name)
                        .setMessage(guide)
                        .setPositiveButton("Copy all", (d, w) -> {
                            copy(guide);
                            AccessLog.record(ShareHubActivity.this, "mcp_guide_copied",
                                "Guide copied", cl.id);
                            toast("Guide copied");
                        })
                        .setNeutralButton("Copy token only", (d, w) -> {
                            if (tok != null) {
                                copy(tok);
                                AccessLog.record(ShareHubActivity.this, "token_copy",
                                    "Token copied for client", cl.id);
                                toast("Token copied");
                            }
                        })
                        .setNegativeButton("Close", null)
                        .show();
                }
                @Override public void onFailed(String reason) {
                    toast("Auth failed: " + reason);
                }
            });
    }

    private void revokeClient(McpClients.Client cl) {
        BiometricGate.authenticate(this,
            "Revoke MCP client",
            "Confirm revoke " + cl.name,
            new BiometricGate.Callback() {
                @Override public void onAuthenticated() {
                    try {
                        McpClients.revoke(ShareHubActivity.this, cl.id);
                        refresh();
                        toast("Revoked " + cl.name);
                    } catch (Exception e) {
                        toast(e.getMessage());
                    }
                }
                @Override public void onFailed(String reason) {
                    toast("Auth failed");
                }
            });
    }

    private void revealToken(boolean copyOnly) {
        BiometricGate.authenticate(this,
            copyOnly ? "Copy peer token" : "Show peer token",
            "Device authentication required",
            new BiometricGate.Callback() {
                @Override public void onAuthenticated() {
                    String t0 = NanobotRuntime.readPeerToken(ShareHubActivity.this);
                    if (t0 == null) t0 = SecureStore.cachedPeerToken(ShareHubActivity.this);
                    final String t = t0;
                    if (t == null) {
                        toast("No peer token — start the agent service");
                        return;
                    }
                    SecureStore.cachePeerToken(ShareHubActivity.this, t);
                    AccessLog.record(ShareHubActivity.this,
                        copyOnly ? "token_copy" : "token_show",
                        copyOnly ? "Peer token copied" : "Peer token revealed");
                    if (copyOnly) {
                        copy(t);
                        toast("Token copied");
                    } else {
                        new AlertDialog.Builder(ShareHubActivity.this)
                            .setTitle("Peer token")
                            .setMessage(t)
                            .setPositiveButton("Copy", (d, w) -> {
                                copy(t);
                                AccessLog.record(ShareHubActivity.this, "token_copy",
                                    "Peer token copied after show");
                            })
                            .setNegativeButton("Close", null)
                            .show();
                    }
                }
                @Override public void onFailed(String reason) {
                    AccessLog.record(ShareHubActivity.this, "token_auth_fail", reason);
                    toast("Auth failed: " + reason);
                }
            });
    }

    private void showHow() {
        String msg =
            "How LAN API / MCP works\n\n"
                + "1. Settings → Agent service = ON (foreground keeps peer alive)\n"
                + "2. Settings → Share agent on Wi‑Fi = ON\n"
                + "3. Pair a client here (biometric / PIN)\n"
                + "4. On the other device, call:\n"
                + "   " + baseUrl() + "/peer/v1/health\n"
                + "5. For chat/shell/MCP add header:\n"
                + "   X-Nanobot-Peer-Token: <token>\n\n"
                + "Open Access history to see every pair, revoke, and token reveal.\n"
                + "Cloud provider credentials never leave this phone.";
        new AlertDialog.Builder(this)
            .setTitle("How to connect")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show();
    }

    private String baseUrl() {
        return "http://" + lanIp() + ":" + NanobotRuntime.PORT;
    }

    private String lanIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address)
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
        return "127.0.0.1";
    }

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("nanobot", s));
    }

    private TextView section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_ACCENT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, dp(16), 0, dp(6));
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
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_PANEL);
        g.setCornerRadius(dp(10));
        g.setStroke(dp(1), C_LINE);
        t.setBackground(g);
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
        b.setMinHeight(dp(44));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? C_ACCENT : 0xFF1A1A24);
        g.setCornerRadius(dp(16));
        g.setStroke(dp(1), C_LINE);
        b.setBackground(g);
        b.setTextColor(primary ? 0xFF00343A : C_FG);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
