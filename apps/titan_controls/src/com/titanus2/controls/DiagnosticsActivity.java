package com.titanus2.controls;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.titanus2.controls.ui.UiKit;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pain-point status. Incoming calls without placing a call. Nav without a flash.
 */
public class DiagnosticsActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private TextView stamp;
    private boolean paused;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.applyOpaqueWindow(this);
        setTitle("Diagnostics");

        ScrollView scroll = new ScrollView(this);
        UiKit.prepareScroll(scroll);
        root = new LinearLayout(this);
        UiKit.screen(root);
        scroll.addView(root, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        paused = false;
        refresh();
    }

    @Override
    protected void onPause() {
        paused = true;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            int kc = event.getKeyCode();
            if (kc == KeyEvent.KEYCODE_ESCAPE) {
                finish();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_R) {
                refresh();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void refresh() {
        if (isFinishing()) return;
        io.execute(() -> {
            final PlaneHealth.Report rep;
            try {
                rep = PlaneHealth.probe(this);
            } catch (Throwable t) {
                h.post(() -> {
                    if (isFinishing()) return;
                    root.removeAllViews();
                    UiKit.section(root, "Diagnostics");
                    UiKit.note(root, "probe failed: " + t.getClass().getSimpleName());
                });
                return;
            }
            h.post(() -> render(rep));
        });
    }

    private void render(PlaneHealth.Report r) {
        if (isFinishing() || paused) return;
        root.removeAllViews();
        UiKit.section(root, "Pain points");
        stamp = UiKit.mono(root);
        stamp.setText("R refresh · Esc   (no test call)");

        paint(root, "Incoming calls", r.calls);
        paint(root, "Keyboard / nav", r.keys);
        paint(root, "SIMs", r.sims);
        paint(root, "Host", r.host);
        UiKit.button(root, "Refresh", this::refresh);
    }

    private void paint(LinearLayout parent, String title, List<PlaneHealth.Row> rows) {
        UiKit.section(parent, title);
        if (rows == null) return;
        for (PlaneHealth.Row row : rows) {
            String mark = row.mark != null ? row.mark : (row.ok ? "OK" : "FAIL");
            UiKit.listRow(parent, mark + "  " + row.name, row.detail, () -> { });
        }
    }
}
