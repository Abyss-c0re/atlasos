package com.titanus2.controls.layouts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.titanus2.controls.HostLayoutController;
import com.titanus2.controls.KeyMapPrefs;
import com.titanus2.controls.ui.UiKit;
import java.util.List;

/** List / create / open custom keyboard layouts. Dense Cube list. Esc finishes. */
public class CustomLayoutsActivity extends Activity {
    private CustomLayoutStore store;
    private LinearLayout list;
    private TextView activeState;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new CustomLayoutStore(this);
        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        UiKit.screen(root);
        sc.addView(root);

        setTitle("Layouts");
        UiKit.section(root, "Active");
        activeState = UiKit.summary(root);
        LinearLayout actRow = UiKit.row(root);
        UiKit.flexButton(actRow, "Off", () -> {
            HostLayoutController.applyAction(this, KeyMapPrefs.ACT_LAYOUT_OFF);
            refreshActive();
            rebuild();
        });

        UiKit.section(root, "Maps");
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        LinearLayout addRow = UiKit.row(root);
        UiKit.flexButton(addRow, "New", this::createNew);
        UiKit.flexButton(addRow, "From specials", () ->
            openEdit(store.duplicate(CustomLayoutStore.ID_SPECIALS, "Specials copy").id));
        UiKit.flexButton(addRow, "From arrows", () ->
            openEdit(store.duplicate(CustomLayoutStore.ID_ARROWS, "Arrows copy").id));

        TextView kbHint = UiKit.mono(root);
        kbHint.setText("O Off · N New · S Specials · A Arrows · 1–9 Use · Esc");

        setContentView(sc);
    }

    /**
     * TitanKey layouts list — no modifiers (leave Sym/Alt free).
     * O = layout off · N new · S/A duplicate specials/arrows · 1–9 Use by index.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
            return super.dispatchKeyEvent(event);
        }
        if (event.isAltPressed() || event.isCtrlPressed()
                || event.isMetaPressed() || event.isShiftPressed()) {
            return super.dispatchKeyEvent(event);
        }
        int kc = event.getKeyCode();
        switch (kc) {
            case KeyEvent.KEYCODE_ESCAPE:
                finish();
                return true;
            case KeyEvent.KEYCODE_O:
            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_NUMPAD_0:
                HostLayoutController.applyAction(this, KeyMapPrefs.ACT_LAYOUT_OFF);
                refreshActive();
                rebuild();
                return true;
            case KeyEvent.KEYCODE_N:
                createNew();
                return true;
            case KeyEvent.KEYCODE_S:
                openEdit(store.duplicate(CustomLayoutStore.ID_SPECIALS, "Specials copy").id);
                return true;
            case KeyEvent.KEYCODE_A:
                openEdit(store.duplicate(CustomLayoutStore.ID_ARROWS, "Arrows copy").id);
                return true;
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_7:
            case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_9:
                return useLayoutByIndex(kc - KeyEvent.KEYCODE_1);
            case KeyEvent.KEYCODE_NUMPAD_1:
            case KeyEvent.KEYCODE_NUMPAD_2:
            case KeyEvent.KEYCODE_NUMPAD_3:
            case KeyEvent.KEYCODE_NUMPAD_4:
            case KeyEvent.KEYCODE_NUMPAD_5:
            case KeyEvent.KEYCODE_NUMPAD_6:
            case KeyEvent.KEYCODE_NUMPAD_7:
            case KeyEvent.KEYCODE_NUMPAD_8:
            case KeyEvent.KEYCODE_NUMPAD_9:
                return useLayoutByIndex(kc - KeyEvent.KEYCODE_NUMPAD_1);
            default:
                break;
        }
        return super.dispatchKeyEvent(event);
    }

    /** 0-based index into {@link CustomLayoutStore#list()} → sticky Use. */
    private boolean useLayoutByIndex(int index) {
        if (store == null || index < 0) return false;
        List<CustomLayoutStore.Layout> layouts = store.list();
        if (index >= layouts.size()) return false;
        HostLayoutController.activate(this, layouts.get(index).id);
        refreshActive();
        rebuild();
        return true;
    }

    @Override protected void onResume() {
        super.onResume();
        rebuild();
        refreshActive();
    }

    private void refreshActive() {
        if (activeState == null) return;
        try {
            HostLayoutController.loadGlobalDefault(this);
            activeState.setText(HostLayoutController.statusLine(this));
        } catch (Exception e) {
            activeState.setText("Layout off");
        }
    }

    private void rebuild() {
        if (list == null) return;
        list.removeAllViews();
        String sticky = HostLayoutController.getSticky();
        String def = HostLayoutController.getGlobalDefault();
        List<CustomLayoutStore.Layout> layouts = store.list();
        for (CustomLayoutStore.Layout lay : layouts) {
            boolean on = lay.id.equals(sticky);
            boolean isDef = lay.id.equals(def);
            String sub = (lay.builtin ? "built-in · " : "")
                + lay.cells.size() + " keys"
                + (on ? " · on" : "")
                + (isDef ? " · default" : "");
            TextView row = UiKit.listRow(list, lay.name, sub, () -> openEdit(lay.id));
            if (on) {
                UiKit.setSelected(row, true);
                row.setTextColor(UiKit.liveAccent(this));
            }
            row.setOnLongClickListener(v -> {
                confirmDelete(lay);
                return true;
            });
            LinearLayout rowBtns = UiKit.row(list);
            UiKit.flexButton(rowBtns, "Use", () -> {
                HostLayoutController.activate(this, lay.id);
                refreshActive();
                rebuild();
            });
            UiKit.flexButton(rowBtns, "Toggle", () -> {
                HostLayoutController.applyAction(this,
                    KeyMapPrefs.layoutToggleAction(lay.id));
                refreshActive();
                rebuild();
            });
            UiKit.flexButton(rowBtns, "Default", () -> {
                HostLayoutController.setGlobalDefault(this, lay.id);
                refreshActive();
                rebuild();
            });
        }
    }

    private void createNew() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Name");
        input.setText("Layout");
        new AlertDialog.Builder(this)
            .setTitle("New layout")
            .setView(input)
            .setPositiveButton("Create", (d, w) -> {
                String n = input.getText() != null ? input.getText().toString().trim() : "";
                CustomLayoutStore.Layout l = store.create(n.isEmpty() ? "Layout" : n);
                openEdit(l.id);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void openEdit(String id) {
        Intent i = new Intent(this, CustomLayoutEditActivity.class);
        i.putExtra(CustomLayoutEditActivity.EXTRA_ID, id);
        startActivity(i);
    }

    private void confirmDelete(CustomLayoutStore.Layout lay) {
        String msg = lay.builtin
            ? "Reset " + lay.name + " to factory map?"
            : "Delete " + lay.name + "?";
        new AlertDialog.Builder(this)
            .setTitle(lay.builtin ? "Reset" : "Delete")
            .setMessage(msg)
            .setPositiveButton(lay.builtin ? "Reset" : "Delete", (d, w) -> {
                store.delete(lay.id);
                rebuild();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
