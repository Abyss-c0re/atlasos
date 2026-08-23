package com.titanus2.controls;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.titanus2.controls.ui.UiKit;

import org.json.JSONArray;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bug / feature report: comment + selected log keys + attached shots. */
public class ReportActivity extends Activity {
    private static final int REQ_SHOT = 71;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EditText title;
    private EditText comment;
    private CheckBox bug;
    private CheckBox feat;
    private CheckBox logCrash;
    private CheckBox logFm;
    private CheckBox logAudio;
    private CheckBox logCtrl;
    private final List<Uri> shots = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.applyOpaqueWindow(this);
        setTitle("Report");
        ScrollView scroll = new ScrollView(this);
        UiKit.prepareScroll(scroll);
        LinearLayout root = new LinearLayout(this);
        UiKit.screen(root);
        scroll.addView(root, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        UiKit.section(root, "Kind");
        bug = new CheckBox(this);
        bug.setText("Bug");
        bug.setChecked(true);
        feat = new CheckBox(this);
        feat.setText("Feature request");
        root.addView(bug);
        root.addView(feat);
        bug.setOnCheckedChangeListener((b, on) -> { if (on) feat.setChecked(false); });
        feat.setOnCheckedChangeListener((b, on) -> { if (on) bug.setChecked(false); });

        UiKit.section(root, "What happened");
        title = new EditText(this);
        title.setHint("Short title");
        title.setSingleLine(true);
        root.addView(title);
        comment = new EditText(this);
        comment.setHint("Comment");
        comment.setMinLines(4);
        comment.setGravity(android.view.Gravity.TOP);
        root.addView(comment);

        UiKit.section(root, "Logs (selected only)");
        logCrash = box(root, "Crash buffer", true);
        logFm = box(root, "FM radio", false);
        logAudio = box(root, "USB / analog audio", false);
        logCtrl = box(root, "Controls / pad", false);
        UiKit.note(root, "Cube Flasher pulls only these buffers. No contacts or gallery.");

        UiKit.section(root, "Screenshots");
        UiKit.button(root, "Attach image", this::pickShot);
        UiKit.button(root, "Submit report", this::submit);
        UiKit.note(root, "Saved for Cube Flasher. Nanobot queue starts if the peer is up.");
    }

    private CheckBox box(LinearLayout root, String label, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setChecked(on);
        root.addView(c);
        return c;
    }

    private void pickShot() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(Intent.createChooser(i, "Attach"), REQ_SHOT); }
        catch (Exception e) { UiKit.toast(this, "no picker"); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SHOT || resultCode != RESULT_OK || data == null) return;
        Uri u = data.getData();
        if (u != null) {
            shots.add(u);
            UiKit.toast(this, shots.size() + " attached");
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void submit() {
        final String kind = feat.isChecked() ? "feature" : "bug";
        final String t = title.getText() == null ? "" : title.getText().toString().trim();
        final String cmt = comment.getText() == null ? "" : comment.getText().toString().trim();
        if (t.isEmpty() && cmt.isEmpty()) {
            UiKit.toast(this, "write a title or comment");
            return;
        }
        final boolean wantCrash = logCrash.isChecked();
        final boolean wantFm = logFm.isChecked();
        final boolean wantAudio = logAudio.isChecked();
        final boolean wantCtrl = logCtrl.isChecked();
        final List<Uri> shotCopy = new ArrayList<>(shots);
        UiKit.toast(this, "saving");
        io.execute(() -> {
            String id = ReportStore.newId();
            File dir = ReportStore.openDir(this, id);
            JSONArray logNames = new JSONArray();
            JSONArray shotNames = new JSONArray();
            try {
                if (wantCrash) logNames.put("crash");
                if (wantFm) logNames.put("fm");
                if (wantAudio) logNames.put("audio");
                if (wantCtrl) logNames.put("controls");
            } catch (Exception ignored) {}
            int n = 0;
            for (Uri u : shotCopy) {
                n++;
                String name = "shot" + n + ".bin";
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    if (in != null) {
                        ReportStore.copyStream(in, new File(new File(dir, "shots"), name));
                        shotNames.put(name);
                    }
                } catch (Exception ignored) {}
            }
            String ver;
            try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { ver = "?"; }
            ReportStore.writeJson(dir, ReportStore.meta(id, kind, t, cmt, logNames, shotNames, ver));
            ReportStore.writeText(new File(dir, "comment.txt"), cmt);
            ReportStore.mirrorOs(dir);
            final boolean queued = NanobotWire.queueReport(t.isEmpty() ? id : t, kind, cmt);
            final String rid = id;
            h.post(() -> {
                UiKit.toast(this, queued ? ("queued " + rid) : ("saved " + rid));
                finish();
            });
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
