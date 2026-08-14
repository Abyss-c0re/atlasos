package com.titanus2.netfw;

import android.app.Activity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/** Live OpenWrt clients. Allow / block here; LuCI for the rest. */
final class ClientsPanel {
    private final Activity ctx;
    private final LinearLayout box;
    private final TextView empty;

    ClientsPanel(Activity ctx, LinearLayout parent) {
        this.ctx = ctx;
        parent.addView(NetUi.section(ctx, "Clients"));
        empty = NetUi.fact(ctx, "No clients");
        parent.addView(empty);
        box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        parent.addView(box);
    }

    void refresh() {
        box.removeAllViews();
        List<Neigh> list = Neigh.load();
        empty.setVisibility(list.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
        for (Neigh n : list) addRow(n);
    }

    private void addRow(Neigh n) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, NetUi.dp(ctx, 6), 0, NetUi.dp(ctx, 6));
        row.addView(NetUi.fact(ctx, n.label()));
        row.addView(NetUi.fact(ctx, n.policy));
        LinearLayout acts = NetUi.row(ctx);
        addAct(acts, n, "allow", "Allow");
        addAct(acts, n, "block", "Block");
        row.addView(acts);
        box.addView(row);
    }

    private void addAct(LinearLayout acts, Neigh n, String pol, String label) {
        Button b = NetUi.btn(ctx, label);
        boolean on = n.policy.equals(pol);
        b.setEnabled(!on);
        if (on) b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setOnClickListener(v -> {
            new Thread(() -> {
                OpenWrt.run(pol, Fw.colonMac(n.mac));
                ctx.runOnUiThread(this::refresh);
            }, "ow-client").start();
        });
        NetUi.weight(acts, b, 1f);
    }
}
