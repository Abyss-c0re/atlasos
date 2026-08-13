package com.titanus2.nanobot;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Connect the on-device agent TO remote MCP servers (HTTP JSON-RPC).
 * Config lives in $NANOBOT_HOME/mcp_servers.json; peer exposes mcp_list / mcp_call tools.
 */
public class McpServersActivity extends Activity {
    private static final int C_BG = 0xFF0B0B0F;
    private static final int C_PANEL = 0xFF14141A;
    private static final int C_ACCENT = 0xFF00E5FF;
    private static final int C_FG = 0xFFF2F2F5;
    private static final int C_MUT = 0xFF8B8B9A;
    private static final int C_OK = 0xFF0E2A1C;
    private static final int C_BAD = 0xFF3A1515;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private PeerClient peer;
    private LinearLayout listCol;
    private TextView status;
    private JSONArray servers = new JSONArray();

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
        title.setText("MCP servers");
        title.setTextColor(C_FG);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button back = pill("Back", false);
        back.setOnClickListener(v -> finish());
        head.addView(back);
        root.addView(head);

        ScrollView sc = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(12), dp(14), dp(28));

        col.addView(body(
            "Connect this phone’s agent to remote MCP servers (tools over HTTP).\n"
                + "Enabled servers add mcp_list / mcp_call tools for Remote Grok & local agent.\n\n"
                + "This is the opposite of Share hub: here the phone is the MCP client."));

        status = mono("Loading…");
        col.addView(status);

        Button add = pill("Add MCP server…", true);
        add.setOnClickListener(v -> showEditor(null, -1));
        col.addView(pad(add));

        Button reload = pill("Reload from peer", false);
        reload.setOnClickListener(v -> loadFromPeer());
        col.addView(pad(reload));

        Button sync = pill("Save to peer now", true);
        sync.setOnClickListener(v -> saveToPeer(true));
        col.addView(pad(sync));

        col.addView(section("Configured servers"));
        listCol = new LinearLayout(this);
        listCol.setOrientation(LinearLayout.VERTICAL);
        col.addView(listCol);

        sc.addView(col);
        root.addView(sc, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        loadFromPeer();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void loadFromPeer() {
        status.setText("Loading from peer…");
        io.execute(() -> {
            try {
                JSONObject j = peer.mcpServersList();
                JSONObject cfg = j.optJSONObject("config");
                JSONArray arr = cfg != null ? cfg.optJSONArray("servers") : j.optJSONArray("servers");
                if (arr == null) arr = new JSONArray();
                final JSONArray f = arr;
                h.post(() -> {
                    servers = f;
                    renderList();
                    status.setText("Loaded " + servers.length() + " server(s) from peer");
                });
            } catch (Exception e) {
                // fallback local cache
                try {
                    servers = McpServerStore.listArray(this);
                } catch (Exception ignored) {
                    servers = new JSONArray();
                }
                h.post(() -> {
                    renderList();
                    status.setText("Peer offline — local cache (" + e.getMessage() + ")");
                });
            }
        });
    }

    private void saveToPeer(boolean toastOk) {
        status.setText("Saving…");
        final JSONArray snap = servers;
        io.execute(() -> {
            try {
                McpServerStore.saveArray(this, snap);
                JSONObject body = new JSONObject();
                body.put("servers", snap);
                JSONObject j = peer.mcpServersSave(body);
                h.post(() -> {
                    status.setText("Saved " + snap.length() + " server(s) to peer");
                    if (toastOk) toast("MCP servers synced");
                    if (j != null) renderList();
                });
            } catch (Exception e) {
                try { McpServerStore.saveArray(this, snap); } catch (Exception ignored) {}
                h.post(() -> {
                    status.setText("Save failed: " + e.getMessage());
                    toast("Save failed — kept local cache");
                });
            }
        });
    }

    private void renderList() {
        listCol.removeAllViews();
        if (servers.length() == 0) {
            listCol.addView(body("No servers yet. Add one (LAN URL of an MCP HTTP endpoint)."));
            return;
        }
        for (int i = 0; i < servers.length(); i++) {
            final int idx = i;
            JSONObject o = servers.optJSONObject(i);
            if (o == null) continue;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setBackground(round(C_PANEL, dp(12)));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = dp(10);
            card.setLayoutParams(clp);

            String name = o.optString("name", o.optString("id", "server"));
            String id = o.optString("id", "");
            String url = o.optString("url", "");
            boolean en = o.optBoolean("enabled", true);

            TextView t = new TextView(this);
            t.setText(name + (en ? "" : " (off)"));
            t.setTextColor(C_FG);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            card.addView(t);

            TextView meta = new TextView(this);
            meta.setText("id=" + id + "\n" + url);
            meta.setTextColor(C_MUT);
            meta.setTypeface(Typeface.MONOSPACE);
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            card.addView(meta);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Switch sw = new Switch(this);
            sw.setText("On");
            sw.setTextColor(C_FG);
            sw.setChecked(en);
            sw.setOnCheckedChangeListener((b, v) -> {
                try {
                    o.put("enabled", v);
                    servers.put(idx, o);
                    saveToPeer(false);
                    renderList();
                } catch (Exception ignored) {}
            });
            row.addView(sw);

            Button test = pill("Probe", true);
            test.setOnClickListener(v -> probe(o));
            row.addView(test);

            Button edit = pill("Edit", false);
            edit.setOnClickListener(v -> showEditor(o, idx));
            row.addView(edit);

            Button del = pill("Del", false);
            del.setOnClickListener(v -> {
                JSONArray n = new JSONArray();
                for (int j = 0; j < servers.length(); j++) {
                    if (j != idx) n.put(servers.opt(j));
                }
                servers = n;
                saveToPeer(true);
                renderList();
            });
            row.addView(del);
            card.addView(row);
            listCol.addView(card);
        }
    }

    private void probe(JSONObject o) {
        status.setText("Probing " + o.optString("name", "") + "…");
        io.execute(() -> {
            try {
                JSONObject r = peer.mcpServerProbe(
                    o.optString("id", null),
                    o.optString("url", null),
                    o.optString("auth", null));
                boolean ok = r.optBoolean("ok", false);
                String raw = r.optString("raw", r.toString());
                h.post(() -> {
                    status.setText(ok ? "Probe OK" : "Probe failed");
                    status.setBackground(round(ok ? C_OK : C_BAD, dp(10)));
                    new AlertDialog.Builder(this)
                        .setTitle(ok ? "MCP probe OK" : "MCP probe failed")
                        .setMessage(raw.length() > 3500 ? raw.substring(0, 3500) + "…" : raw)
                        .setPositiveButton("OK", null)
                        .show();
                });
            } catch (Exception e) {
                h.post(() -> {
                    status.setText("Probe error: " + e.getMessage());
                    toast(e.getMessage());
                });
            }
        });
    }

    private void showEditor(JSONObject existing, int idx) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(8), dp(16), dp(8));
        EditText name = field("Name", existing != null ? existing.optString("name", "") : "");
        EditText url = field("URL (http://host:port/mcp)",
            existing != null ? existing.optString("url", "") : "http://");
        EditText auth = field("Auth header (optional, e.g. Bearer xxx)",
            existing != null ? existing.optString("auth", "") : "");
        form.addView(name);
        form.addView(url);
        form.addView(auth);
        new AlertDialog.Builder(this)
            .setTitle(existing == null ? "Add MCP server" : "Edit MCP server")
            .setView(form)
            .setPositiveButton("Save", (d, w) -> {
                try {
                    JSONObject o = existing != null ? existing : new JSONObject();
                    String id = o.optString("id", "");
                    if (id.isEmpty()) id = UUID.randomUUID().toString().substring(0, 8);
                    o.put("id", id);
                    o.put("name", name.getText().toString().trim().isEmpty()
                        ? id : name.getText().toString().trim());
                    o.put("url", url.getText().toString().trim());
                    o.put("auth", auth.getText().toString().trim());
                    o.put("enabled", o.optBoolean("enabled", true));
                    o.put("transport", "http");
                    if (idx >= 0) servers.put(idx, o);
                    else servers.put(o);
                    saveToPeer(true);
                    renderList();
                } catch (Exception e) {
                    toast(e.getMessage());
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private EditText field(String hint, String val) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(val);
        e.setTextColor(C_FG);
        e.setHintTextColor(C_MUT);
        e.setBackground(round(0xFF1A1A24, dp(8)));
        e.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        e.setLayoutParams(lp);
        return e;
    }

    private TextView section(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(C_ACCENT);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(14), 0, dp(6));
        return v;
    }

    private TextView body(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(C_MUT);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setPadding(0, 0, 0, dp(8));
        return v;
    }

    private TextView mono(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(C_FG);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        v.setPadding(dp(10), dp(10), dp(10), dp(10));
        v.setBackground(round(C_PANEL, dp(10)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
    }

    private Button pill(String t, boolean accent) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(accent ? 0xFF00343A : C_FG);
        b.setBackground(round(accent ? C_ACCENT : 0xFF1A1A24, dp(16)));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        return b;
    }

    private LinearLayout pad(Button b) {
        LinearLayout w = new LinearLayout(this);
        w.setPadding(0, dp(4), 0, dp(4));
        w.addView(b, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return w;
    }

    private GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
