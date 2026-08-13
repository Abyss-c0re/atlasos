package com.titanus2.nanobot;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** View peer log or agent memory/history. */
public class LogViewerActivity extends Activity {
    public static final String EXTRA_MODE = "mode"; // log | history
    private static final int C_BG = 0xFF0B0B0F;
    private static final int C_PANEL = 0xFF14141A;
    private static final int C_ACCENT = 0xFF00E5FF;
    private static final int C_FG = 0xFFF2F2F5;
    private static final int C_MUT = 0xFF8B8B9A;
    private static final int C_LINE = 0xFF22222C;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView body;
    private TextView title;
    private String mode = "log";
    private PeerClient peer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        peer = new PeerClient(this);
        if (getIntent() != null && getIntent().getStringExtra(EXTRA_MODE) != null) {
            mode = getIntent().getStringExtra(EXTRA_MODE);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(12), dp(12), dp(12));
        head.setBackgroundColor(C_PANEL);
        title = new TextView(this);
        title.setText("log".equals(mode) ? "Peer log" : "Memory / history");
        title.setTextColor(C_FG);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button refresh = pill("Refresh", true);
        refresh.setOnClickListener(v -> load());
        head.addView(refresh);
        Button back = pill("Back", false);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.leftMargin = dp(8);
        back.setOnClickListener(v -> finish());
        head.addView(back, bl);
        root.addView(head);

        TextView hint = new TextView(this);
        hint.setTextColor(C_MUT);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setPadding(dp(14), dp(8), dp(14), dp(4));
        hint.setText("log".equals(mode)
            ? "Source: $NANOBOT_HOME/nanobot.log via /api/log"
            : "Source: $NANOBOT_HOME/memory/* + env + shell policy");
        root.addView(hint);

        ScrollView sc = new ScrollView(this);
        body = new TextView(this);
        body.setTextColor(C_FG);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        body.setTextIsSelectable(true);
        body.setPadding(dp(12), dp(8), dp(12), dp(24));
        body.setText("Loading…");
        sc.addView(body);
        root.addView(sc, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        load();
    }

    private void load() {
        body.setText("Loading…");
        final String m = mode;
        io.execute(() -> {
            try {
                String text;
                if ("history".equals(m)) {
                    text = peer.memoryDump();
                } else {
                    text = peer.peerLog();
                    // keep last ~200 lines for readability
                    String[] lines = text.split("\n", -1);
                    if (lines.length > 200) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("… (").append(lines.length - 200).append(" earlier lines)\n");
                        for (int i = lines.length - 200; i < lines.length; i++) {
                            sb.append(lines[i]).append('\n');
                        }
                        text = sb.toString();
                    }
                }
                final String out = text == null || text.isEmpty() ? "(empty)" : text;
                h.post(() -> body.setText(out));
            } catch (Exception e) {
                h.post(() -> {
                    body.setText("Failed: " + e.getMessage());
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private Button pill(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setMinHeight(dp(40));
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? C_ACCENT : 0xFF1A1A24);
        g.setCornerRadius(dp(16));
        g.setStroke(dp(1), C_LINE);
        b.setBackground(g);
        b.setTextColor(primary ? 0xFF00343A : C_FG);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
