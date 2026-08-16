package com.titanus2.atlas;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.titanus2.atlas.ui.UiKit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Privilege request log — every atlas-auth request / grant / deny / exec.
 */
public class AuthLogActivity extends Activity {
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

        UiKit.section(root, "Auth log");
        summary = UiKit.summary(root);
        UiKit.button(root, "Clear log", () -> {
            AtlasAuth.clearLog(this);
            reload();
        });
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        runIo(() -> {
            List<String> raw = AtlasAuth.readLogTail(AuthLogActivity.this, 200);
            List<String> newest = new ArrayList<>(raw);
            Collections.reverse(newest);
            main.post(() -> bind(newest));
        });
    }

    private void bind(List<String> lines) {
        list.removeAllViews();
        if (lines == null || lines.isEmpty()) {
            summary.setText("empty");
            UiKit.note(list, "no requests yet");
            return;
        }
        summary.setText(lines.size() + " · newest first");
        for (String json : lines) {
            TextView row = UiKit.mono(list);
            row.setText(AtlasAuth.formatLogLine(json));
        }
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

    @Override
    protected void onDestroy() {
        if (isFinishing() && io != null && !io.isShutdown()) {
            io.shutdownNow();
        }
        super.onDestroy();
    }
}
