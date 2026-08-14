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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sessions = seats: save/load/export/delete home snapshots for each identity.
 */
public class SessionsActivity extends Activity {
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

        UiKit.section(root, "Sessions");
        UiKit.note(root, "Sandbox = light image · shared kernel · private writable layer");
        summary = UiKit.summary(root);
        UiKit.section(root, "Saved");
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        setContentView(scroll);
        reload();
    }

    private void reload() {
        runIo(() -> {
            List<HybridEnsure.DebianUser> users =
                HybridEnsure.loadDebianUsers(SessionsActivity.this);
            List<String> facts = new ArrayList<>();
            List<List<String>> snaps = new ArrayList<>();
            if (users != null) {
                for (HybridEnsure.DebianUser u : users) {
                    facts.add(HybridEnsure.seatStatus(SessionsActivity.this, u.name));
                    snaps.add(HybridEnsure.seatSnapNames(SessionsActivity.this, u.name));
                }
            }
            main.post(() -> bind(users, facts, snaps));
        });
    }

    private void bind(List<HybridEnsure.DebianUser> users, List<String> facts,
                      List<List<String>> snaps) {
        list.removeAllViews();
        if (users == null || users.isEmpty()) {
            summary.setText("none");
            return;
        }
        int nsnap = 0;
        for (List<String> s : snaps) if (s != null) nsnap += s.size();
        summary.setText(users.size() + " sessions · " + nsnap + " snapshots");
        for (int i = 0; i < users.size(); i++) {
            HybridEnsure.DebianUser u = users.get(i);
            String st = i < facts.size() ? facts.get(i) : "";
            List<String> sl = i < snaps.size() ? snaps.get(i) : null;
            UiKit.listRow(list, u.name, seatShort(st), () -> showSession(u, st));
            if (sl != null) {
                for (String sn : sl) {
                    UiKit.listRow(list, "  " + sn, "Load · export · delete",
                        () -> showSnap(u, sn));
                }
            }
        }
    }

    private static String seatShort(String st) {
        if (st == null) return "";
        String sb = kv(st, "sandbox");
        String fr = kv(st, "frozen");
        String ly = kv(st, "layer");
        return "sandbox " + ("1".equals(sb) ? "on" : "off")
            + (ly.isEmpty() ? "" : " · layer " + ly)
            + " · frozen " + ("1".equals(fr) ? "yes" : "no");
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

    private void showSession(HybridEnsure.DebianUser u, String st) {
        boolean sand = "1".equals(kv(st, "sandbox"));
        boolean frozen = "1".equals(kv(st, "frozen"));
        new AlertDialog.Builder(this)
            .setTitle(u.name)
            .setItems(new CharSequence[] {
                "Save now",
                "Load latest",
                "Export latest",
                sand ? "Unsandbox" : "Sandbox",
                frozen ? "Thaw" : "Freeze",
                "Clone…",
                "Close"
            }, (d, which) -> {
                if (which == 0) {
                    runAuthed("Save " + u.name, () -> {
                        String r = HybridEnsure.seatSave(this, u.name);
                        if (r != null && r.contains("saved=")) {
                            AtlasPrefs.setLastSeat(this, u.name);
                        }
                        return r;
                    });
                } else if (which == 1) {
                    runAuthed("Load " + u.name, () -> {
                        String r = HybridEnsure.seatLoad(this, u.name, null);
                        if (r != null && r.contains("loaded=")) {
                            AtlasPrefs.setLastSeat(this, u.name);
                        }
                        return r;
                    });
                } else if (which == 2) {
                    runAuthed("Export " + u.name,
                        () -> HybridEnsure.seatExport(this, u.name));
                } else if (which == 3) {
                    runAuthed((sand ? "Unsandbox " : "Sandbox ") + u.name,
                        () -> HybridEnsure.seatSandbox(this, u.name, !sand));
                } else if (which == 4) {
                    runAuthed((frozen ? "Thaw " : "Freeze ") + u.name,
                        () -> HybridEnsure.seatFreeze(this, u.name, !frozen));
                } else if (which == 5) {
                    askClone(u);
                }
            })
            .show();
    }

    private void showSnap(HybridEnsure.DebianUser u, String snap) {
        new AlertDialog.Builder(this)
            .setTitle(snap)
            .setItems(new CharSequence[] {
                "Load",
                "Export",
                "Delete",
                "Close"
            }, (d, which) -> {
                if (which == 0) {
                    runAuthed("Load " + snap, () -> {
                        String r = HybridEnsure.seatLoad(this, u.name, snap);
                        if (r != null && r.contains("loaded=")) {
                            AtlasPrefs.setLastSeat(this, u.name);
                            AtlasPrefs.setLastSnap(this, snap);
                        }
                        return r;
                    });
                } else if (which == 1) {
                    runAuthed("Export " + snap,
                        () -> HybridEnsure.seatExport(this, u.name, snap));
                } else if (which == 2) {
                    confirmDeleteSnap(u, snap);
                }
            })
            .show();
    }

    private void confirmDeleteSnap(HybridEnsure.DebianUser u, String snap) {
        new AlertDialog.Builder(this)
            .setTitle("Delete snapshot?")
            .setMessage(u.name + " · " + snap)
            .setPositiveButton("Delete", (d, w) -> runAuthed("Delete " + snap,
                () -> HybridEnsure.seatDeleteSnap(this, u.name, snap)))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void askClone(HybridEnsure.DebianUser u) {
        EditText e = new EditText(this);
        e.setHint("new name");
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this)
            .setTitle("Clone " + u.name)
            .setView(e)
            .setPositiveButton("Clone", (d, w) -> {
                String n = e.getText() != null ? e.getText().toString().trim() : "";
                if (!HybridEnsure.validUserName(n)) {
                    toast("bad name");
                    return;
                }
                runAuthed("Clone " + u.name + " → " + n,
                    () -> HybridEnsure.seatClone(this, u.name, n));
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void runAuthed(String reason, java.util.concurrent.Callable<String> work) {
        toast("…");
        runIo(() -> {
            boolean ok = true;
            if (AtlasPrefs.biometricAuth(SessionsActivity.this)) {
                ok = AtlasAuth.requestBlocking(SessionsActivity.this, reason, 90);
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
    private int dp(int v) { return AtlasUi.dp(this, v); }

    @Override
    protected void onDestroy() {
        if (isFinishing() && io != null) io.shutdownNow();
        super.onDestroy();
    }
}
