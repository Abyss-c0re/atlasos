package com.titanus2.controls.layouts;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.titanus2.controls.HostLayoutController;
import com.titanus2.controls.ui.UiKit;
import java.util.HashMap;
import java.util.Map;

/**
 * Visualize / edit a layout key map (QWERTY grid).
 * Keyboard-first: letter selects cell · Enter edits · Del clear ·
 * Ctrl+S save · Ctrl+Enter use · Esc discard. Never steals EditText compose.
 */
public class CustomLayoutEditActivity extends Activity {
    public static final String EXTRA_ID = "layout_id";

    private CustomLayoutStore store;
    private CustomLayoutStore.Layout layout;
    private LinearLayout gridHost;
    private TextView titleState;
    private TextView focusState;
    private int focusedKc = -1;
    private final Map<Integer, TextView> tiles = new HashMap<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new CustomLayoutStore(this);
        String id = getIntent() != null ? getIntent().getStringExtra(EXTRA_ID) : null;
        layout = store.get(id);
        if (layout == null) {
            finish();
            return;
        }
        // Editable copy
        layout = layout.copy();

        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        UiKit.screen(root);
        sc.addView(root);

        titleState = UiKit.summary(root);
        focusState = UiKit.summary(root);
        refreshTitle();
        refreshFocus();

        if (!layout.builtin) {
            UiKit.button(root, "Rename", this::rename);
        }

        UiKit.section(root, "Map");
        gridHost = new LinearLayout(this);
        gridHost.setOrientation(LinearLayout.VERTICAL);
        root.addView(gridHost);
        rebuildGrid();

        LinearLayout saveRow = UiKit.row(root);
        UiKit.flexButton(saveRow, "Save", this::saveOnly);
        UiKit.flexButton(saveRow, "Use", this::saveAndUse);
        UiKit.flexButton(saveRow, "Discard", this::finish);

        TextView kbHint = UiKit.mono(root);
        kbHint.setText("Letter select · Enter edit · Del clear · Ctrl+S save · Ctrl+Enter use · Esc");

        setContentView(sc);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null) return super.dispatchKeyEvent(event);
        // Glyph/rename dialogs own compose — never steal letters from EditText.
        android.view.View focus = getCurrentFocus();
        if (focus instanceof EditText) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                focus.clearFocus();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        int action = event.getAction();
        int kc = event.getKeyCode();
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            // Ctrl+S save · Ctrl+Enter use (letters stay free for cell select)
            if (event.isCtrlPressed() || event.isMetaPressed()) {
                if (kc == KeyEvent.KEYCODE_S) {
                    saveOnly();
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_ENTER) {
                    saveAndUse();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
            if (kc == KeyEvent.KEYCODE_ESCAPE) {
                finish();
                return true;
            }
            if ((kc == KeyEvent.KEYCODE_DEL || kc == KeyEvent.KEYCODE_FORWARD_DEL)
                    && focusedKc > 0 && layout != null && !layout.builtin) {
                layout.cells.remove(focusedKc);
                rebuildGrid();
                refreshTitle();
                refreshFocus();
                return true;
            }
            if (isLetterKey(kc)) {
                // Same letter again → edit; new letter → focus only
                if (focusedKc == kc) {
                    editCell(kc);
                } else {
                    focusedKc = kc;
                    refreshFocus();
                    highlightTiles();
                }
                return true;
            }
        }
        if (action == KeyEvent.ACTION_UP && kc == KeyEvent.KEYCODE_ENTER
                && !event.isCtrlPressed() && !event.isMetaPressed()) {
            if (focusedKc > 0) {
                editCell(focusedKc);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isLetterKey(int kc) {
        return (kc >= KeyEvent.KEYCODE_A && kc <= KeyEvent.KEYCODE_Z)
            || (kc >= KeyEvent.KEYCODE_0 && kc <= KeyEvent.KEYCODE_9)
            || kc == KeyEvent.KEYCODE_SEMICOLON
            || kc == KeyEvent.KEYCODE_COMMA
            || kc == KeyEvent.KEYCODE_PERIOD
            || kc == KeyEvent.KEYCODE_SLASH
            || kc == KeyEvent.KEYCODE_APOSTROPHE;
    }

    private void refreshTitle() {
        if (titleState == null || layout == null) return;
        titleState.setText(layout.name + " · " + layout.cells.size() + " keys"
            + (layout.builtin ? " · built-in" : ""));
    }

    private void refreshFocus() {
        if (focusState == null) return;
        if (focusedKc <= 0) {
            focusState.setText("Press letter · Enter edit");
            return;
        }
        String letter = CustomLayoutStore.keyName(focusedKc);
        String cell = layout != null ? layout.cells.get(focusedKc) : null;
        String bind = CustomLayoutStore.cellLabel(cell);
        focusState.setText(letter + " → " + (bind == null || bind.isEmpty() ? "—" : bind));
    }

    private void rebuildGrid() {
        if (gridHost == null || layout == null) return;
        gridHost.removeAllViews();
        tiles.clear();
        for (int[] row : CustomLayoutStore.letterRows()) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
            gridHost.addView(r);
            for (int kc : row) {
                final int keyCode = kc;
                String letter = CustomLayoutStore.keyName(keyCode);
                String cell = layout.cells.get(keyCode);
                String bind = CustomLayoutStore.cellLabel(cell);
                String label = letter + "\n" + bind;
                TextView tile = UiKit.flexButton(r, label, () -> {
                    focusedKc = keyCode;
                    refreshFocus();
                    highlightTiles();
                    editCell(keyCode);
                });
                // denser for grid
                tile.setTextSize(10f);
                tile.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
                tiles.put(keyCode, tile);
                boolean on = cell != null && !cell.isEmpty() && !"-".equals(cell);
                boolean focus = keyCode == focusedKc;
                UiKit.setSelected(tile, on || focus);
            }
        }
        highlightTiles();
    }

    private void highlightTiles() {
        if (layout == null) return;
        for (Map.Entry<Integer, TextView> e : tiles.entrySet()) {
            int kc = e.getKey();
            TextView tile = e.getValue();
            String cell = layout.cells.get(kc);
            boolean on = cell != null && !cell.isEmpty() && !"-".equals(cell);
            boolean focus = kc == focusedKc;
            UiKit.setSelected(tile, on || focus);
            if (focus) {
                tile.setTextColor(UiKit.liveAccent(this));
            } else {
                tile.setTextColor(UiKit.textColor(this));
            }
        }
    }

    private void editCell(int keyCode) {
        focusedKc = keyCode;
        refreshFocus();
        highlightTiles();
        String cur = layout.cells.get(keyCode);
        String[] opts = new String[] {
            "Glyph…", "Arrow / system key…", "Host chord…", "Clear"
        };
        new AlertDialog.Builder(this)
            .setTitle(CustomLayoutStore.keyName(keyCode)
                + (cur != null ? " → " + CustomLayoutStore.cellLabel(cur) : ""))
            .setItems(opts, (d, which) -> {
                switch (which) {
                    case 0: setGlyph(keyCode); break;
                    case 1: setArrow(keyCode); break;
                    case 2: setHost(keyCode); break;
                    case 3:
                        layout.cells.remove(keyCode);
                        rebuildGrid();
                        refreshTitle();
                        refreshFocus();
                        break;
                    default: break;
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void setGlyph(int keyCode) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("! @ # ( ) …");
        String cur = layout.cells.get(keyCode);
        if (cur != null && !cur.startsWith("@")) input.setText(cur);
        new AlertDialog.Builder(this)
            .setTitle(CustomLayoutStore.keyName(keyCode) + " → glyph")
            .setView(input)
            .setPositiveButton("Set", (d, w) -> {
                String g = input.getText() != null ? input.getText().toString().trim() : "";
                if (g.isEmpty()) layout.cells.remove(keyCode);
                else layout.cells.put(keyCode, g.substring(0, 1));
                rebuildGrid();
                refreshTitle();
                refreshFocus();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void setArrow(int keyCode) {
        final int[] kcs = {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3,
            KeyEvent.KEYCODE_F4, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL
        };
        String[] labels = new String[kcs.length];
        for (int i = 0; i < kcs.length; i++) {
            labels[i] = CustomLayoutStore.keyName(kcs[i]);
        }
        new AlertDialog.Builder(this)
            .setTitle(CustomLayoutStore.keyName(keyCode) + " → key")
            .setItems(labels, (d, which) -> {
                if (which < 0 || which >= kcs.length) return;
                layout.cells.put(keyCode, "@key:" + kcs[which]);
                rebuildGrid();
                refreshTitle();
                refreshFocus();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void setHost(int keyCode) {
        String[] hosts = {
            "up", "down", "left", "right", "home", "end", "pageup", "pagedown",
            "esc", "tab", "enter", "backspace", "space",
            "ctrl+c", "ctrl+v", "ctrl+x", "ctrl+z", "ctrl+a", "alt+tab"
        };
        new AlertDialog.Builder(this)
            .setTitle(CustomLayoutStore.keyName(keyCode) + " → host")
            .setItems(hosts, (d, which) -> {
                if (which < 0 || which >= hosts.length) return;
                layout.cells.put(keyCode, "@host:" + hosts[which]);
                rebuildGrid();
                refreshTitle();
                refreshFocus();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void rename() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(layout.name);
        new AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("OK", (d, w) -> {
                String n = input.getText() != null ? input.getText().toString().trim() : "";
                if (!n.isEmpty()) {
                    layout.name = n;
                    refreshTitle();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void saveOnly() {
        store.save(layout);
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    /** Save and force this layout sticky-on (intent = active layout). */
    private void saveAndUse() {
        store.save(layout);
        HostLayoutController.activate(this, layout.id);
        finish();
    }
}
