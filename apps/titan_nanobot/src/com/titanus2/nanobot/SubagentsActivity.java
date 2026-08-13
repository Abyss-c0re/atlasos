package com.titanus2.nanobot;

import android.app.Activity;
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

import org.json.JSONObject;

/** Light subagent policy + list (Grok max 8, shared session; LLM serial for local). */
public class SubagentsActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private PeerClient peer;
    private TextView status;
    private TextView list;
    private Switch enSw;
    private Switch serialSw;
    private EditText maxEd;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        peer = new PeerClient(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFAFAFA);
        root.setPadding(dp(14), dp(12), dp(14), dp(20));

        TextView title = new TextView(this);
        title.setText("Subagents");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(0xFF212121);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("Light workers (max 8). Share peer session. "
            + "Serialize LLM: default on for local, off for Grok.");
        note.setTextColor(0xFF757575);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setPadding(0, dp(8), 0, dp(12));
        root.addView(note);

        enSw = new Switch(this);
        enSw.setText("Enable subagents");
        root.addView(enSw);

        serialSw = new Switch(this);
        serialSw.setText("Serialize LLM requests");
        root.addView(serialSw);

        maxEd = new EditText(this);
        maxEd.setHint("Max concurrent (0-8)");
        maxEd.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        maxEd.setText("8");
        root.addView(maxEd);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button save = pill("Save");
        Button ref = pill("Refresh");
        Button back = pill("Back");
        save.setOnClickListener(v -> savePolicy());
        ref.setOnClickListener(v -> refresh());
        back.setOnClickListener(v -> finish());
        row.addView(save);
        row.addView(ref);
        row.addView(back);
        root.addView(row);

        status = new TextView(this);
        status.setTextColor(0xFF424242);
        status.setPadding(0, dp(10), 0, dp(6));
        root.addView(status);

        ScrollView sc = new ScrollView(this);
        list = new TextView(this);
        list.setTextIsSelectable(true);
        list.setTypeface(android.graphics.Typeface.MONOSPACE);
        list.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        list.setTextColor(0xFF212121);
        sc.addView(list);
        root.addView(sc, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refresh();
    }

    private void refresh() {
        new Thread(() -> {
            try {
                JSONObject j = peer.subagentsList();
                h.post(() -> {
                    enSw.setChecked(j.optBoolean("enabled", false));
                    serialSw.setChecked(j.optBoolean("llm_serial", false));
                    maxEd.setText(String.valueOf(j.optInt("max", 8)));
                    status.setText("running=" + j.optInt("running", 0)
                        + " max=" + j.optInt("max", 8)
                        + " serial=" + j.optBoolean("llm_serial", false));
                    try {
                        list.setText(j.optJSONArray("subagents") != null
                            ? j.optJSONArray("subagents").toString(2)
                            : "[]");
                    } catch (Exception ignore) {
                        list.setText(String.valueOf(j.opt("subagents")));
                    }
                });
            } catch (Exception e) {
                h.post(() -> status.setText("error: " + e.getMessage()));
            }
        }).start();
    }

    private void savePolicy() {
        new Thread(() -> {
            try {
                peer.setSubagentPolicy(
                    enSw.isChecked(),
                    parseMax(maxEd.getText().toString()),
                    serialSw.isChecked());
                h.post(() -> {
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                    refresh();
                });
            } catch (Exception e) {
                h.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private int parseMax(String s) {
        try {
            int v = Integer.parseInt(s.trim());
            if (v < 0) v = 0;
            if (v > 8) v = 8;
            return v;
        } catch (Exception e) {
            return 8;
        }
    }

    private Button pill(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
