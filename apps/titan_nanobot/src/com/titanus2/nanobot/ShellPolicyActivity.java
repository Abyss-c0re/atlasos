package com.titanus2.nanobot;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Manage shell denylist + allow exceptions from the app. */
public class ShellPolicyActivity extends Activity {
    private static final int C_BG = 0xFF0B0B0F;
    private static final int C_PANEL = 0xFF14141A;
    private static final int C_ACCENT = 0xFF00E5FF;
    private static final int C_FG = 0xFFF2F2F5;
    private static final int C_MUT = 0xFF8B8B9A;
    private static final int C_LINE = 0xFF22222C;
    private static final int C_BAD = 0xFF3A1515;
    private static final int C_OK = 0xFF0E2A1C;

    private LinearLayout denyList;
    private LinearLayout allowList;
    private EditText addDeny;
    private EditText addAllow;
    private TextView summary;
    private boolean showDeny = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(12), dp(12), dp(12));
        head.setBackgroundColor(C_PANEL);
        TextView title = new TextView(this);
        title.setText("Shell policy");
        title.setTextColor(C_FG);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button back = pill("Back", false);
        back.setOnClickListener(v -> finish());
        head.addView(back);
        root.addView(head);

        summary = new TextView(this);
        summary.setTextColor(C_MUT);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        summary.setPadding(dp(14), dp(8), dp(14), dp(4));
        root.addView(summary);

        TextView help = new TextView(this);
        help.setText("Denylist blocks agent shell (substring match).\n"
            + "To allow reboot: Remove it from Denylist OR add to Allow.\n"
            + "Saves go to the shared peer home (same files the agent uses).");
        help.setTextColor(C_MUT);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        help.setPadding(dp(14), dp(4), dp(14), dp(8));
        root.addView(help);

        // tabs
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(C_PANEL);
        Button tDeny = pill("Denylist", true);
        Button tAllow = pill("Allow", false);
        tDeny.setOnClickListener(v -> {
            showDeny = true;
            tDeny.setBackground(round(C_ACCENT, dp(16)));
            tDeny.setTextColor(0xFF00343A);
            tAllow.setBackground(round(0xFF1A1A24, dp(16)));
            tAllow.setTextColor(C_FG);
            refresh();
        });
        tAllow.setOnClickListener(v -> {
            showDeny = false;
            tAllow.setBackground(round(C_ACCENT, dp(16)));
            tAllow.setTextColor(0xFF00343A);
            tDeny.setBackground(round(0xFF1A1A24, dp(16)));
            tDeny.setTextColor(C_FG);
            refresh();
        });
        tabs.setPadding(dp(10), dp(8), dp(10), dp(8));
        tabs.addView(tDeny, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams g = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        g.leftMargin = dp(8);
        tabs.addView(tAllow, g);
        root.addView(tabs);

        ScrollView sc = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(8), dp(12), dp(20));

        denyList = new LinearLayout(this);
        denyList.setOrientation(LinearLayout.VERTICAL);
        allowList = new LinearLayout(this);
        allowList.setOrientation(LinearLayout.VERTICAL);
        body.addView(denyList);
        body.addView(allowList);

        // add row
        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        addDeny = field("Add denylist pattern…");
        addAllow = field("Add allow exception…");
        addAllow.setVisibility(View.GONE);
        addRow.addView(addDeny, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addRow.addView(addAllow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button addBtn = pill("Add", true);
        addBtn.setOnClickListener(v -> onAdd());
        LinearLayout.LayoutParams ab = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ab.leftMargin = dp(8);
        addRow.addView(addBtn, ab);
        body.addView(addRow);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(12), 0, 0);
        Button reset = pill("Reset denylist defaults", false);
        reset.setOnClickListener(v -> {
            try {
                ShellPolicy.resetDenyDefaults(this);
                toast("Denylist reset to defaults");
                refresh();
            } catch (Exception e) {
                toast(e.getMessage());
            }
        });
        Button reload = pill("Reload files", false);
        reload.setOnClickListener(v -> refresh());
        actions.addView(reset, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rl.leftMargin = dp(8);
        actions.addView(reload, rl);
        body.addView(actions);

        TextView path = new TextView(this);
        path.setTextColor(C_MUT);
        path.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        path.setTypeface(Typeface.MONOSPACE);
        path.setPadding(0, dp(12), 0, 0);
        path.setText("Files:\n" + ShellPolicy.denyFile(this).getAbsolutePath()
            + "\n" + ShellPolicy.allowFile(this).getAbsolutePath());
        body.addView(path);

        sc.addView(body);
        root.addView(sc, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        ShellPolicy.ensureFiles(this);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void onAdd() {
        try {
            if (showDeny) {
                String p = addDeny.getText() != null ? addDeny.getText().toString().trim() : "";
                if (p.isEmpty()) { toast("Enter a pattern"); return; }
                ShellPolicy.setDenyPattern(this, p, true);
                addDeny.setText("");
                toast("Denied: " + p);
            } else {
                String p = addAllow.getText() != null ? addAllow.getText().toString().trim() : "";
                if (p.isEmpty()) { toast("Enter a pattern"); return; }
                ShellPolicy.setAllowPattern(this, p, true);
                addAllow.setText("");
                toast("Allowed: " + p);
            }
            refresh();
        } catch (Exception e) {
            toast("Save failed: " + e.getMessage());
        }
    }

    private void refresh() {
        summary.setText(ShellPolicy.summary(this));
        denyList.removeAllViews();
        allowList.removeAllViews();
        denyList.setVisibility(showDeny ? View.VISIBLE : View.GONE);
        allowList.setVisibility(showDeny ? View.GONE : View.VISIBLE);
        addDeny.setVisibility(showDeny ? View.VISIBLE : View.GONE);
        addAllow.setVisibility(showDeny ? View.GONE : View.VISIBLE);

        if (showDeny) {
            List<String> d = ShellPolicy.loadDeny(this);
            if (d.isEmpty()) {
                denyList.addView(empty("Denylist empty — agent shell unrestricted (except floor)."));
            } else {
                for (String p : d) denyList.addView(rowItem(p, true));
            }
        } else {
            List<String> a = ShellPolicy.loadAllow(this);
            if (a.isEmpty()) {
                allowList.addView(empty("No exceptions. Add e.g. reboot to allow agent reboot."));
            } else {
                for (String p : a) allowList.addView(rowItem(p, false));
            }
        }
    }

    private View rowItem(String pattern, boolean isDeny) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable bg = round(isDeny ? C_BAD : C_OK, dp(10));
        row.setBackground(bg);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(6);
        row.setLayoutParams(rlp);

        TextView t = new TextView(this);
        t.setText(pattern);
        t.setTextColor(C_FG);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextIsSelectable(true);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (isDeny) {
            Button allow = pill("Allow", true);
            allow.setOnClickListener(v -> {
                try {
                    // remove from deny AND add to allow for clarity
                    ShellPolicy.setDenyPattern(this, pattern, false);
                    ShellPolicy.setAllowPattern(this, pattern, true);
                    toast("Allowed: " + pattern);
                    refresh();
                } catch (Exception e) {
                    toast(e.getMessage());
                }
            });
            row.addView(allow);
            Button rm = pill("Remove", false);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ml.leftMargin = dp(6);
            rm.setOnClickListener(v -> {
                try {
                    ShellPolicy.setDenyPattern(this, pattern, false);
                    toast("Removed from denylist");
                    refresh();
                } catch (Exception e) {
                    toast(e.getMessage());
                }
            });
            row.addView(rm, ml);
        } else {
            Button rm = pill("Remove", false);
            rm.setOnClickListener(v -> {
                try {
                    ShellPolicy.setAllowPattern(this, pattern, false);
                    toast("Exception removed");
                    refresh();
                } catch (Exception e) {
                    toast(e.getMessage());
                }
            });
            row.addView(rm);
        }
        return row;
    }

    private TextView empty(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_MUT);
        t.setPadding(dp(4), dp(12), dp(4), dp(12));
        return t;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(C_MUT);
        e.setTextColor(C_FG);
        e.setSingleLine(true);
        e.setBackground(round(0xFF1A1A24, dp(10)));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    private Button pill(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setMinHeight(dp(40));
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
