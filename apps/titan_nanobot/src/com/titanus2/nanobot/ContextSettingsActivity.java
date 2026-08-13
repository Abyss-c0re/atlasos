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
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Context + history management (Open WebUI / LibreChat-style controls).
 * - How much history the model sees
 * - Clear chat / memory
 * - Core system identity
 * - Local max tokens
 */
public class ContextSettingsActivity extends Activity {
    private static final int C_BG = 0xFF0B0B0F;
    private static final int C_PANEL = 0xFF14141A;
    private static final int C_ACCENT = 0xFF00E5FF;
    private static final int C_FG = 0xFFF2F2F5;
    private static final int C_MUT = 0xFF8B8B9A;
    private static final int C_BAD = 0xFF3A1515;

    private final Handler h = new Handler(Looper.getMainLooper());
    private PeerClient peer;
    private TextView stats;
    private TextView turnsVal;
    private TextView charsVal;
    private TextView sumVal;
    private TextView tokVal;
    private EditText coreEdit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        peer = new PeerClient(this);
        ChatPrefs.syncToPeer(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(12), dp(12), dp(12));
        head.setBackgroundColor(C_PANEL);
        TextView title = new TextView(this);
        title.setText("Context & history");
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
            "Like Open WebUI / LibreChat: control what the model sees, "
                + "how long history lives, and wipe chat without losing Grok login.\n\n"
                + "Auth (session/peer_token) is never touched here."));

        col.addView(section("Storage now"));
        stats = mono(ChatPrefs.statsLine(this));
        col.addView(stats);

        col.addView(section("Context window (what the model sees)"));
        col.addView(body("Recent turns kept verbatim (older → summary)."));
        turnsVal = label("Recent turns: " + ChatPrefs.recentTurns(this));
        col.addView(turnsVal);
        col.addView(seek(1, 24, ChatPrefs.recentTurns(this), (v) -> {
            ChatPrefs.setRecentTurns(this, v);
            turnsVal.setText("Recent turns: " + v);
            refreshStats();
        }));

        col.addView(body("Max characters stored per message."));
        charsVal = label("Max chars/msg: " + ChatPrefs.msgChars(this));
        col.addView(charsVal);
        col.addView(seek(120, 4000, ChatPrefs.msgChars(this), (v) -> {
            ChatPrefs.setMsgChars(this, v);
            charsVal.setText("Max chars/msg: " + v);
            refreshStats();
        }));

        col.addView(body("Summary budget (compacted older turns)."));
        sumVal = label("Summary max: " + ChatPrefs.summaryMax(this));
        col.addView(sumVal);
        col.addView(seek(200, 8000, ChatPrefs.summaryMax(this), (v) -> {
            ChatPrefs.setSummaryMax(this, v);
            sumVal.setText("Summary max: " + v);
            refreshStats();
        }));

        col.addView(sw("Include summary in system prompt", ChatPrefs.includeSummary(this), (on) -> {
            ChatPrefs.setIncludeSummary(this, on);
            toast(on ? "Summary ON" : "Summary OFF");
        }));
        col.addView(sw("Include core identity", ChatPrefs.includeCore(this), (on) -> {
            ChatPrefs.setIncludeCore(this, on);
            toast(on ? "Core ON" : "Core OFF");
        }));

        col.addView(section("Generation (local)"));
        col.addView(body("max_tokens for on-device llama completions."));
        tokVal = label("max_tokens: " + ChatPrefs.maxTokens(this));
        col.addView(tokVal);
        col.addView(seek(32, 2048, ChatPrefs.maxTokens(this), (v) -> {
            ChatPrefs.setMaxTokens(this, v);
            tokVal.setText("max_tokens: " + v);
        }));

        col.addView(section("UI stream"));
        col.addView(sw("Show thinking (spoiler)", ChatPrefs.showThinking(this),
            (on) -> ChatPrefs.setShowThinking(this, on)));
        col.addView(sw("Show tool calls", ChatPrefs.showTools(this),
            (on) -> ChatPrefs.setShowTools(this, on)));

        col.addView(section("History actions"));
        Button clearChat = pill("Clear chat (keep core)", true);
        clearChat.setOnClickListener(v -> confirm(
            "Clear chat?",
            "Wipes recent turns, summary, notes. Keeps core identity and Grok session.",
            () -> {
                ChatPrefs.clearChatViaPeer(this, peer);
                ChatPrefs.clearChatKeepCore(this);
                refreshStats();
                toast("Chat cleared");
            }));
        col.addView(pad(clearChat));

        Button clearSum = pill("Clear summary only", false);
        clearSum.setOnClickListener(v -> {
            ChatPrefs.clearSummary(this);
            try {
                peer.shell(": > '" + NanobotRuntime.SHARED_HOME + "/memory/summary.txt'");
            } catch (Exception ignored) {}
            refreshStats();
            toast("Summary cleared");
        });
        col.addView(pad(clearSum));

        Button clearAll = pill("Reset all memory…", false);
        clearAll.setOnClickListener(v -> confirm(
            "Reset ALL memory?",
            "Clears chat + profile + reloads default core. Does NOT delete Grok session/peer_token.",
            () -> {
                ChatPrefs.clearAllMemory(this);
                try {
                    peer.shell(
                        "H='" + NanobotRuntime.SHARED_HOME + "/memory'; "
                            + ": > \"$H/recent.jsonl\"; : > \"$H/summary.txt\"; "
                            + ": > \"$H/notes.jsonl\"; : > \"$H/profile.txt\"; "
                            + "chmod 666 \"$H\"/* 2>/dev/null; true");
                } catch (Exception ignored) {}
                coreEdit.setText(ChatPrefs.readCore(this));
                refreshStats();
                toast("Memory reset");
            }));
        col.addView(pad(clearAll));

        Button viewRecent = pill("View recent tail", false);
        viewRecent.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("recent.jsonl (tail)")
            .setMessage(ChatPrefs.readRecentTail(this, 20))
            .setPositiveButton("OK", null)
            .show());
        col.addView(pad(viewRecent));

        Button viewSum = pill("View summary", false);
        viewSum.setOnClickListener(v -> {
            String s = ChatPrefs.readSummary(this);
            new AlertDialog.Builder(this)
                .setTitle("summary.txt")
                .setMessage(s.isEmpty() ? "(empty)" : s)
                .setPositiveButton("OK", null)
                .show();
        });
        col.addView(pad(viewSum));

        col.addView(section("Core identity (system)"));
        col.addView(body("Always-on facts for the agent. Keep short — local models choke on bloat."));
        coreEdit = new EditText(this);
        coreEdit.setTextColor(C_FG);
        coreEdit.setHintTextColor(C_MUT);
        coreEdit.setHint("You are…");
        coreEdit.setMinLines(5);
        coreEdit.setMaxLines(12);
        coreEdit.setBackground(roundField());
        coreEdit.setPadding(dp(12), dp(10), dp(12), dp(10));
        coreEdit.setText(ChatPrefs.readCore(this));
        coreEdit.setTypeface(Typeface.MONOSPACE);
        coreEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        col.addView(coreEdit);
        Button saveCore = pill("Save core", true);
        saveCore.setOnClickListener(v -> {
            String t = coreEdit.getText() != null ? coreEdit.getText().toString() : "";
            ChatPrefs.writeCore(this, t);
            try {
                // ensure peer can read
                peer.shell("chmod 666 '" + NanobotRuntime.SHARED_HOME + "/memory/core.txt' 2>/dev/null; true");
            } catch (Exception ignored) {}
            toast("Core saved");
            refreshStats();
        });
        col.addView(pad(saveCore));

        col.addView(section("Apply"));
        Button sync = pill("Sync prefs → peer now", true);
        sync.setOnClickListener(v -> {
            ChatPrefs.syncToPeer(this);
            try {
                peer.shell("chmod 666 '" + NanobotRuntime.SHARED_HOME + "/memory/prefs' 2>/dev/null; "
                    + "cat '" + NanobotRuntime.SHARED_HOME + "/memory/prefs' 2>/dev/null | head -20");
            } catch (Exception ignored) {}
            toast("Synced");
            refreshStats();
        });
        col.addView(pad(sync));

        sc.addView(col);
        root.addView(sc, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ChatPrefs.syncToPeer(this);
        refreshStats();
    }

    private void refreshStats() {
        if (stats != null) stats.setText(ChatPrefs.statsLine(this));
    }

    private interface SeekFn { void on(int v); }
    private interface BoolFn { void on(boolean v); }
    private interface Run { void go(); }

    private SeekBar seek(int min, int max, int cur, SeekFn fn) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, cur - min));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) fn.on(progress + min);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                fn.on(seekBar.getProgress() + min);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        sb.setLayoutParams(lp);
        return sb;
    }

    private Switch sw(String label, boolean on, BoolFn fn) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(C_FG);
        s.setChecked(on);
        s.setPadding(0, dp(6), 0, dp(6));
        s.setOnCheckedChangeListener((b, v) -> fn.on(v));
        return s;
    }

    private void confirm(String title, String msg, Run ok) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Do it", (d, w) -> ok.go())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private TextView section(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(C_ACCENT);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(16), 0, dp(6));
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

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(C_FG);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
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
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        return b;
    }

    private LinearLayout pad(Button b) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(0, dp(4), 0, dp(4));
        wrap.addView(b, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    private GradientDrawable roundField() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1A1A24);
        g.setCornerRadius(dp(10));
        g.setStroke(dp(1), 0xFF2A2A36);
        return g;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
