package com.titanus2.atlas;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.titanus2.atlas.ui.UiKit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Saved sessions: rename, notes, multi-select, import/export.
 */
public class BackupsActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout list;
    private LinearLayout selectBar;
    private TextView summary;
    private boolean selectMode;
    private final Set<String> selected = new LinkedHashSet<>();
    private List<HybridEnsure.Backup> cached = new ArrayList<>();

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

        UiKit.section(root, "Sessions");
        UiKit.note(root, "Rename · notes · import/export · survive reboot");
        summary = UiKit.summary(root);
        LinearLayout actions = UiKit.row(root);
        UiKit.flexButton(actions, "Save", this::saveNow);
        UiKit.flexButton(actions, "Import", this::importDialog);
        UiKit.flexButton(actions, "Select", this::toggleSelect);
        selectBar = UiKit.row(root);
        selectBar.setVisibility(android.view.View.GONE);
        UiKit.flexButton(selectBar, "Export", this::exportSelected);
        UiKit.flexButton(selectBar, "Delete", this::confirmDeleteSelected);
        UiKit.flexButton(selectBar, "All", this::selectAll);
        UiKit.section(root, "Saved");
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        setContentView(scroll);
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void toggleSelect() {
        selectMode = !selectMode;
        if (!selectMode) selected.clear();
        selectBar.setVisibility(selectMode ? android.view.View.VISIBLE : android.view.View.GONE);
        bind(cached);
    }

    private void selectAll() {
        selected.clear();
        for (HybridEnsure.Backup b : cached) selected.add(b.id);
        bind(cached);
    }

    private void reload() {
        runIo(() -> {
            List<HybridEnsure.Backup> backups =
                HybridEnsure.loadBackups(BackupsActivity.this);
            main.post(() -> bind(backups));
        });
    }

    private void bind(List<HybridEnsure.Backup> backups) {
        cached = backups != null ? backups : new ArrayList<HybridEnsure.Backup>();
        list.removeAllViews();
        if (cached.isEmpty()) {
            summary.setText("none");
            return;
        }
        String extra = selectMode ? " · " + selected.size() + " selected" : "";
        summary.setText(cached.size() + " · survive reboot" + extra);
        for (HybridEnsure.Backup b : cached) {
            addRow(b);
        }
    }

    private void addRow(HybridEnsure.Backup b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int ph = AtlasUi.dp(this, 4);
        int pv = AtlasUi.dp(this, 10);
        row.setPadding(ph, pv, ph, pv);

        if (selectMode) {
            CheckBox cb = new CheckBox(this);
            cb.setChecked(selected.contains(b.id));
            cb.setOnCheckedChangeListener((v, on) -> {
                if (on) selected.add(b.id);
                else selected.remove(b.id);
                summary.setText(cached.size() + " · survive reboot · "
                    + selected.size() + " selected");
            });
            row.addView(cb);
        }

        TextView tv = new TextView(this);
        String title = b.title();
        String sec = b.shortFact();
        if (!b.id.equals(title)) sec = b.id + " · " + sec;
        tv.setText(title + "\n" + sec);
        tv.setTextColor(UiKit.textColor(this));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tv.setOnClickListener(v -> {
            if (selectMode) {
                if (selected.contains(b.id)) selected.remove(b.id);
                else selected.add(b.id);
                bind(cached);
            } else {
                showBackup(b);
            }
        });
        row.addView(tv);
        list.addView(row);
    }

    private void saveNow() {
        final String name = AtlasPrefs.lastSeat(this);
        runAuthed("Save session " + name, () -> {
            String r = HybridEnsure.backupSave(this, name);
            if (r != null && r.contains("backup=")) {
                String id = kv(r, "backup");
                if (!id.isEmpty()) AtlasPrefs.setLastSnap(this, id);
                AtlasPrefs.setLastSeat(this, name);
            }
            return r;
        });
    }

    private void showBackup(HybridEnsure.Backup b) {
        new AlertDialog.Builder(this)
            .setTitle(b.title())
            .setItems(new CharSequence[] {
                "Load",
                "Rename",
                "Note",
                "Export",
                "Delete",
                "Close"
            }, (d, which) -> {
                if (which == 0) {
                    runAuthed("Load " + b.id, () -> {
                        String r = HybridEnsure.backupLoad(this, b.user, b.id);
                        if (r != null && (r.contains("loaded=") || r.contains("backup="))) {
                            AtlasPrefs.setLastSeat(this, b.user);
                            AtlasPrefs.setLastSnap(this, b.id);
                        }
                        return r;
                    });
                } else if (which == 1) {
                    askRename(b);
                } else if (which == 2) {
                    askNote(b);
                } else if (which == 3) {
                    runAuthed("Export " + b.id,
                        () -> HybridEnsure.backupExport(this, b.id));
                } else if (which == 4) {
                    confirmDelete(b);
                }
            })
            .show();
    }

    private void askRename(HybridEnsure.Backup b) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setText(b.title());
        e.setSelection(e.getText() != null ? e.getText().length() : 0);
        new AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(e)
            .setPositiveButton("Save", (d, w) -> {
                String n = e.getText() != null ? e.getText().toString().trim() : "";
                runAuthed("Rename " + b.id,
                    () -> HybridEnsure.backupRename(this, b.id, n));
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void askNote(HybridEnsure.Backup b) {
        EditText e = new EditText(this);
        e.setMinLines(3);
        e.setMaxLines(8);
        e.setGravity(Gravity.TOP);
        e.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setText(b.note != null ? b.note : "");
        new AlertDialog.Builder(this)
            .setTitle("Note")
            .setView(e)
            .setPositiveButton("Save", (d, w) -> {
                String n = e.getText() != null ? e.getText().toString() : "";
                runAuthed("Note " + b.id,
                    () -> HybridEnsure.backupNote(this, b.id, n));
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void importDialog() {
        toast("…");
        runIo(() -> {
            List<String> files = HybridEnsure.listExportFiles(this);
            main.post(() -> {
                if (files.isEmpty()) {
                    toast("no exports in AtlasBackups");
                    return;
                }
                String[] labels = new String[files.size()];
                for (int i = 0; i < files.size(); i++) {
                    String p = files.get(i);
                    int sl = p.lastIndexOf('/');
                    labels[i] = sl >= 0 ? p.substring(sl + 1) : p;
                }
                new AlertDialog.Builder(this)
                    .setTitle("Import")
                    .setItems(labels, (d, which) -> {
                        if (which < 0 || which >= files.size()) return;
                        final String path = files.get(which);
                        runAuthed("Import " + labels[which],
                            () -> HybridEnsure.backupImport(this, path));
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            });
        });
    }

    private void exportSelected() {
        if (selected.isEmpty()) {
            toast("none selected");
            return;
        }
        final List<String> ids = new ArrayList<>(selected);
        runAuthed("Export " + ids.size(), () -> {
            String last = "";
            int n = 0;
            for (String id : ids) {
                String r = HybridEnsure.backupExport(this, id);
                if (r != null && r.contains("export=")) n++;
                last = r;
            }
            return n + " exported · " + (last != null ? last : "");
        });
    }

    private void confirmDeleteSelected() {
        if (selected.isEmpty()) {
            toast("none selected");
            return;
        }
        final List<String> ids = new ArrayList<>(selected);
        new AlertDialog.Builder(this)
            .setTitle("Delete " + ids.size() + " sessions?")
            .setPositiveButton("Delete", (d, w) -> runAuthed("Delete " + ids.size(), () -> {
                int n = 0;
                for (String id : ids) {
                    String r = HybridEnsure.backupDelete(this, id);
                    if (r != null && r.contains("deleted=")) n++;
                }
                selected.clear();
                return n + " deleted";
            }))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void confirmDelete(HybridEnsure.Backup b) {
        new AlertDialog.Builder(this)
            .setTitle("Delete session?")
            .setMessage(b.title())
            .setPositiveButton("Delete", (d, w) -> runAuthed("Delete " + b.id,
                () -> HybridEnsure.backupDelete(this, b.id)))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private static String kv(String line, String key) {
        if (line == null) return "";
        String p = key + "=";
        int i = line.indexOf(p);
        if (i < 0) return "";
        int s = i + p.length();
        int e = line.indexOf(' ', s);
        return e < 0 ? line.substring(s).trim() : line.substring(s, e).trim();
    }

    private void runAuthed(String reason, java.util.concurrent.Callable<String> work) {
        toast("…");
        runIo(() -> {
            boolean ok = true;
            if (AtlasPrefs.biometricAuth(BackupsActivity.this)) {
                ok = AtlasAuth.requestBlocking(BackupsActivity.this, reason, 90);
            }
            if (!ok) {
                main.post(() -> toast("Denied"));
                return;
            }
            String r;
            try {
                r = work.call();
            } catch (Exception ex) {
                r = ex.getMessage() != null ? ex.getMessage() : "err";
            }
            final String msg = r;
            main.post(() -> {
                toast(msg != null ? msg : "ok");
                reload();
            });
        });
    }

    private void runIo(Runnable r) {
        if (io == null || io.isShutdown()) io = Executors.newSingleThreadExecutor();
        try {
            io.execute(r);
        } catch (Exception e) {
            io = Executors.newSingleThreadExecutor();
            try { io.execute(r); } catch (Exception ignored) {}
        }
    }

    private void toast(String s) { AtlasUi.toast(this, s); }

    @Override
    protected void onDestroy() {
        if (isFinishing() && io != null) io.shutdownNow();
        super.onDestroy();
    }
}
