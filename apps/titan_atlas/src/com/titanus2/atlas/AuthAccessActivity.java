package com.titanus2.atlas;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.titanus2.atlas.ui.UiKit;

import java.util.List;

/**
 * Magisk-style allow / ask / deny. Built-in Android cmds + user-added names.
 */
public class AuthAccessActivity extends Activity {
    private LinearLayout list;

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

        UiKit.section(root, "Access");
        UiKit.note(root, "allow · ask · deny  ·  tap to cycle");
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
        AtlasPolicy.publish(this);

        UiKit.section(list, "Android files");
        int st = AtlasPolicy.storageMode(this);
        UiKit.listRow(list, "User storage", AtlasPolicy.modeLabel(st)
                + (st == AtlasPolicy.ALLOW ? " · bound in Deb" : " · android cat|write|ls"),
            () -> {
                AtlasPolicy.setStorageMode(this, AtlasPolicy.nextMode(st));
                bind();
            });

        UiKit.section(list, "Android");
        for (String n : AtlasPolicy.COMMANDS) {
            final String name = n;
            int m = AtlasPolicy.cmdMode(this, name);
            UiKit.listRow(list, name, AtlasPolicy.modeLabel(m), () -> {
                AtlasPolicy.setCmdMode(AuthAccessActivity.this, name,
                    AtlasPolicy.nextMode(m));
                bind();
            });
        }

        List<String> extra = AtlasPolicy.extraCommands(this);
        UiKit.section(list, "Added");
        if (extra.isEmpty()) {
            UiKit.note(list, "none · Add a name or path");
        } else {
            for (String n : extra) {
                final String name = n;
                int m = AtlasPolicy.cmdMode(this, name);
                UiKit.listRow(list, name, AtlasPolicy.modeLabel(m),
                    () -> showExtra(name));
            }
        }

        UiKit.note(list, "Deb uses: android <cmd>");
        UiKit.note(list, "Files: android cat|write|ls");
    }

    private void showAdd() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = AtlasUi.dp(this, 16);
        col.setPadding(p, AtlasUi.dp(this, 8), p, 0);

        EditText field = new EditText(this);
        field.setHint("tcpdump or /usr/bin/tcpdump");
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        col.addView(field, AtlasUi.match());

        new AlertDialog.Builder(this)
            .setTitle("Add")
            .setView(col)
            .setPositiveButton("Add", (d, w) -> {
                String spec = field.getText() != null
                    ? field.getText().toString() : "";
                String err = AtlasPolicy.addCommand(this, spec);
                AtlasUi.toast(this, err == null ? "added" : err);
                bind();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showExtra(String name) {
        int m = AtlasPolicy.cmdMode(this, name);
        new AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(AtlasPolicy.modeLabel(m))
            .setPositiveButton("Cycle", (d, w) -> {
                AtlasPolicy.setCmdMode(this, name, AtlasPolicy.nextMode(m));
                bind();
            })
            .setNeutralButton("Remove", (d, w) -> {
                AtlasPolicy.removeCommand(this, name);
                AtlasUi.toast(this, "removed");
                bind();
            })
            .setNegativeButton("Close", null)
            .show();
    }
}
