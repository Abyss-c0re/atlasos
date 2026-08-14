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
import android.widget.Switch;
import android.widget.TextView;

import com.titanus2.atlas.ui.UiKit;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared Atlas identities: one name, Android + Debian permission bits,
 * atlas-auth for elevate. Not Android multi-user.
 */
public class UsersActivity extends Activity {
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

        UiKit.section(root, "Shared identity");
        UiKit.note(root, "Android API + Debian login · atlas-auth elevate");
        summary = UiKit.summary(root);
        UiKit.button(root, "Add user", this::showAdd);
        UiKit.section(root, "Users");
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        setContentView(scroll);
        reload();
    }

    private void reload() {
        runIo(() -> {
            List<HybridEnsure.DebianUser> users =
                HybridEnsure.loadDebianUsers(UsersActivity.this);
            main.post(() -> bind(users));
        });
    }

    private void bind(List<HybridEnsure.DebianUser> users) {
        list.removeAllViews();
        if (users == null || users.isEmpty()) {
            summary.setText("none");
            return;
        }
        summary.setText(users.size() + " · session " + android.os.Process.myUid());
        for (HybridEnsure.DebianUser u : users) {
            String sec = "A " + (u.android ? "on" : "off")
                + " · D " + (u.debian ? "on" : "off")
                + " · sudo " + (u.sudo ? "on" : "off")
                + " · pass " + (u.passSet ? "set" : "lock")
                + (u.session ? " · session" : "");
            UiKit.listRow(list, u.name + "  " + u.uid, sec, () -> showUser(u));
        }
    }

    private void showAdd() {
        LinearLayout col = formCol();
        EditText name = field(col, "username", false);
        EditText pass = field(col, "password (optional)", true);
        EditText pass2 = field(col, "confirm password", true);
        Switch android = sw(col, "Android access", true);
        Switch debian = sw(col, "Debian login", true);
        Switch sudo = sw(col, "Debian sudo (atlas-auth)", true);
        TextView fact = AtlasUi.monoFact(this,
            "Password = Deb login only\nempty = locked");
        fact.setPadding(0, dp(8), 0, 0);
        col.addView(fact, AtlasUi.match());
        new AlertDialog.Builder(this)
            .setTitle("Add user")
            .setView(col)
            .setPositiveButton("Create", (d, w) -> {
                String n = text(name);
                String p1 = text(pass);
                String p2 = text(pass2);
                if (!HybridEnsure.validUserName(n)) {
                    toast("bad name");
                    return;
                }
                if (!p1.isEmpty() && !p1.equals(p2)) {
                    toast("password mismatch");
                    return;
                }
                runAuthed("Create user " + n, () -> {
                    String r = HybridEnsure.addDebianUser(
                        UsersActivity.this, n, p1.isEmpty() ? null : p1, sudo.isChecked());
                    if (r != null && r.contains("user=")) {
                        HybridEnsure.setUserPerm(UsersActivity.this, n, "android", android.isChecked());
                        HybridEnsure.setUserPerm(UsersActivity.this, n, "debian", debian.isChecked());
                    }
                    return r;
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showUser(HybridEnsure.DebianUser u) {
        LinearLayout col = formCol();
        TextView fact = AtlasUi.monoFact(this, u.fact() + "\n" + u.home);
        col.addView(fact, AtlasUi.match());
        Switch android = sw(col, "Android access", u.android);
        Switch debian = sw(col, "Debian login", u.debian);
        Switch sudo = sw(col, "Debian sudo (atlas-auth)", u.sudo);
        if (u.session) {
            TextView s = AtlasUi.monoFact(this, "session identity — cannot delete");
            s.setPadding(0, dp(8), 0, 0);
            col.addView(s, AtlasUi.match());
        } else {
            AtlasUi.actionBtn(col, "Delete…", () -> confirmDelete(u));
        }
        new AlertDialog.Builder(this)
            .setTitle(u.name)
            .setView(col)
            .setPositiveButton("Save", (d, w) -> runAuthed("Update user " + u.name, () -> {
                HybridEnsure.setUserPerm(UsersActivity.this, u.name, "android", android.isChecked());
                HybridEnsure.setUserPerm(UsersActivity.this, u.name, "debian", debian.isChecked());
                return HybridEnsure.setUserPerm(UsersActivity.this, u.name, "sudo", sudo.isChecked());
            }))
            .setNeutralButton("Password…", (d, w) -> showPass(u))
            .setNegativeButton("Close", null)
            .show();
    }

    private void showPass(HybridEnsure.DebianUser u) {
        LinearLayout col = formCol();
        EditText p1 = field(col, "new password (empty = lock)", true);
        EditText p2 = field(col, "confirm", true);
        new AlertDialog.Builder(this)
            .setTitle("Password · " + u.name)
            .setView(col)
            .setPositiveButton("Set", (d, w) -> {
                String a = text(p1);
                String b = text(p2);
                if (!a.isEmpty() && !a.equals(b)) {
                    toast("password mismatch");
                    return;
                }
                runAuthed("Set password " + u.name, () ->
                    HybridEnsure.setUserPass(UsersActivity.this, u.name, a.isEmpty() ? null : a));
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void confirmDelete(HybridEnsure.DebianUser u) {
        final boolean[] wipe = { true };
        new AlertDialog.Builder(this)
            .setTitle("Delete " + u.name + "?")
            .setMultiChoiceItems(
                new CharSequence[] { "Delete home" },
                new boolean[] { true },
                (d, which, checked) -> wipe[0] = checked)
            .setPositiveButton("Delete", (d, w) -> runAuthed("Delete user " + u.name, () ->
                HybridEnsure.deleteDebianUser(UsersActivity.this, u.name, wipe[0])))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void runAuthed(String reason, java.util.concurrent.Callable<String> work) {
        toast("…");
        runIo(() -> {
            boolean ok = true;
            if (AtlasPrefs.biometricAuth(UsersActivity.this)) {
                ok = AtlasAuth.requestBlocking(UsersActivity.this, reason, 90);
            }
            if (!ok) {
                main.post(() -> toast("Denied"));
                return;
            }
            String r;
            try {
                r = work.call();
            } catch (Exception e) {
                r = e.getMessage() != null ? e.getMessage() : "err";
            }
            final String msg = r;
            main.post(() -> {
                toast(msg != null ? msg : "ok");
                reload();
            });
        });
    }

    private LinearLayout formCol() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        col.setPadding(p, dp(8), p, 0);
        return col;
    }

    private EditText field(LinearLayout col, String hint, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(password
            ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        col.addView(e, AtlasUi.match());
        return e;
    }

    private Switch sw(LinearLayout col, String title, boolean on) {
        Switch s = new Switch(this);
        s.setText(title);
        s.setChecked(on);
        s.setMinHeight(dp(48));
        col.addView(s, AtlasUi.match());
        return s;
    }

    private static String text(EditText e) {
        return e.getText() != null ? e.getText().toString() : "";
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

    private void toast(String s) {
        AtlasUi.toast(this, s);
    }

    private int dp(int v) {
        return AtlasUi.dp(this, v);
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && io != null) io.shutdownNow();
        super.onDestroy();
    }
}
