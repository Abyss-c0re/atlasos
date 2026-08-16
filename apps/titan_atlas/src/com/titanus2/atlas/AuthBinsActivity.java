package com.titanus2.atlas;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.titanus2.atlas.ui.UiKit;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * User list of binaries wrapped by atlas-auth. Lives in Settings — not the term.
 */
public class AuthBinsActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout list;
    private TextView summary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.applyOpaqueWindow(this);

        ScrollView scroll = new ScrollView(this);
        UiKit.prepareScroll(scroll);
        LinearLayout root = new LinearLayout(this);
        UiKit.screen(root);
        scroll.addView(root, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT));

        UiKit.section(root, "Managed binaries");
        UiKit.note(root, "Deb binary becomes a symlink via atlas-auth");
        summary = UiKit.summary(root);
        UiKit.button(root, "Add", this::showAdd);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        setContentView(scroll);
        bind();
    }

    @Override
    protected void onResume() {
        super.onResume();
        bind();
    }

    private void bind() {
        list.removeAllViews();
        List<AtlasPrefs.ManagedBin> extra = AtlasPrefs.managedBins(this);
        int n = AtlasPrefs.BUILTIN_MANAGED.length + extra.size();
        summary.setText(n + " · " + extra.size() + " added");

        UiKit.section(list, "Built-in");
        for (String name : AtlasPrefs.BUILTIN_MANAGED) {
            String path = AtlasPrefs.resolveManagedPath(name);
            UiKit.listRow(list, name, path != null ? path : "built-in",
                () -> toast("built-in · cannot remove"));
        }

        UiKit.section(list, "Added");
        if (extra.isEmpty()) {
            UiKit.note(list, "none");
        } else {
            for (AtlasPrefs.ManagedBin b : extra) {
                UiKit.listRow(list, b.name, b.path, () -> showOne(b));
            }
        }
    }

    private void showAdd() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = AtlasUi.dp(this, 16);
        col.setPadding(p, AtlasUi.dp(this, 8), p, 0);

        EditText field = new EditText(this);
        field.setHint("tcpdump or /system/bin/tcpdump");
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        col.addView(field, AtlasUi.match());

        String[] tips = AtlasPrefs.MANAGED_SUGGESTIONS;
        LinearLayout row1 = UiKit.row(col);
        LinearLayout row2 = UiKit.row(col);
        for (int i = 0; i < tips.length; i++) {
            final String pick = tips[i];
            UiKit.flexButton(i < 3 ? row1 : row2, pick, () -> field.setText(pick));
        }

        new AlertDialog.Builder(this)
            .setTitle("Add binary")
            .setView(col)
            .setPositiveButton("Add", (d, w) -> {
                String spec = field.getText() != null
                    ? field.getText().toString() : "";
                runIo(() -> {
                    String err = AtlasPrefs.addManagedBin(AuthBinsActivity.this, spec);
                    main.post(() -> {
                        toast(err == null ? "added" : err);
                        bind();
                    });
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showOne(AtlasPrefs.ManagedBin b) {
        new AlertDialog.Builder(this)
            .setTitle(b.name)
            .setMessage(b.path)
            .setPositiveButton("Remove", (d, w) -> {
                AtlasPrefs.removeManagedBin(this, b.name);
                toast("removed");
                bind();
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private void runIo(Runnable r) {
        if (io == null || io.isShutdown() || io.isTerminated()) {
            io = Executors.newSingleThreadExecutor();
        }
        try {
            io.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            io = Executors.newSingleThreadExecutor();
            try {
                io.execute(r);
            } catch (Exception ignored) {
            }
        }
    }

    private void toast(String s) {
        AtlasUi.toast(this, s);
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && io != null && !io.isShutdown()) {
            io.shutdownNow();
        }
        super.onDestroy();
    }
}
