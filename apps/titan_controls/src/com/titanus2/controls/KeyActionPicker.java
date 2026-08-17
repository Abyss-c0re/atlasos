package com.titanus2.controls;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
import com.titanus2.controls.layouts.CustomLayoutStore;
import java.util.ArrayList;
import java.util.List;

/**
 * Two-step action assign dialog — categories first, then a short list.
 */
public final class KeyActionPicker {
    public interface Listener {
        void onPicked(String action);
        /** User chose “Open app…” — host shows package picker. */
        void onPickApp();
        /** Explicit remove / clear override (optional). */
        default void onRemove() {}
        /**
         * Layout-modifier mode for a whole key (short=none, long=hold, double=toggle).
         * Optional — if unimplemented, only single hold/toggle actions are used.
         */
        default void onLayoutModifier(String layoutId) {}
    }

    private KeyActionPicker() {}

    /**
     * @param chord hold+key chords skip “System default”
     * @param allowRemove show Remove at the end (edit existing)
     * @param pressKind SHORT / LONG / DOUBLE / null — shapes layout-modifier choices
     */
    public static void show(Activity activity, String title, boolean chord,
                            boolean allowRemove, Listener listener) {
        show(activity, title, chord, allowRemove, null, false, listener);
    }

    public static void show(Activity activity, String title, boolean chord,
                            boolean allowRemove, KeyMapPrefs.Press pressKind,
                            Listener listener) {
        show(activity, title, chord, allowRemove, pressKind, false, listener);
    }

    /**
     * @param banSideChrome P0 B1: side slots never offer Home/Recents/Camera
     *                      (stock mtk-kpd CAMERA residual / system chrome)
     */
    public static void show(Activity activity, String title, boolean chord,
                            boolean allowRemove, KeyMapPrefs.Press pressKind,
                            boolean banSideChrome, Listener listener) {
        if (activity == null || listener == null) return;
        final String rootTitle = (title == null || title.isEmpty()) ? "Action" : title;
        final boolean banChrome = banSideChrome;

        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (!chord && pressKind != KeyMapPrefs.Press.DOUBLE) {
            labels.add("Act as key…");
            actions.add(() -> showActAsKey(activity, rootTitle, chord,
                allowRemove, pressKind, banChrome, listener));
        }

        labels.add("Phone · navigation…");
        actions.add(() -> showGroup(activity, rootTitle, "Navigation",
            KeyMapPrefs.GROUP_NAV, chord, allowRemove, pressKind, banChrome, listener));

        labels.add("Phone · tools…");
        actions.add(() -> showGroup(activity, rootTitle, "Tools",
            KeyMapPrefs.GROUP_TOOLS, chord, allowRemove, pressKind, banChrome, listener));

        labels.add("Mouse…");
        actions.add(() -> showGroup(activity, rootTitle, "Mouse",
            KeyMapPrefs.GROUP_POINTER, chord, allowRemove, pressKind, banChrome, listener));

        labels.add("Computer…");
        actions.add(() -> showGroup(activity, rootTitle, "Computer",
            KeyMapPrefs.GROUP_COMPUTER, chord, allowRemove, pressKind, banChrome, listener));

        labels.add("Custom host key / combo…");
        actions.add(() -> showCustomHostChord(activity, rootTitle, listener));

        labels.add("Layout modifier…");
        actions.add(() -> showLayoutModifier(activity, rootTitle, chord,
            allowRemove, pressKind, listener));

        labels.add("Open app…");
        actions.add(listener::onPickApp);

        labels.add("Clear…");
        actions.add(() -> showGroup(activity, rootTitle, "Clear",
            KeyMapPrefs.GROUP_CLEAR, chord, allowRemove, pressKind, banChrome, listener));

        labels.add("Advanced…");
        actions.add(() -> showGroup(activity, rootTitle, "Advanced",
            KeyMapPrefs.GROUP_ADVANCED, chord, allowRemove, pressKind, banChrome, listener));

        labels.add("Phone keycode…");
        actions.add(() -> showCustomKeycode(activity, rootTitle, listener));

        if (allowRemove) {
            labels.add("Remove");
            actions.add(listener::onRemove);
        }

        new AlertDialog.Builder(activity)
            .setTitle(rootTitle)
            .setItems(labels.toArray(new String[0]), (d, which) -> {
                if (which >= 0 && which < actions.size()) actions.get(which).run();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /**
     * Remap: pick the key/button this physical key becomes.
     * Short vs long is not a choice — the remap follows the press.
     */
    public static void showActAsKey(Activity activity, String rootTitle,
                                    Listener listener) {
        showActAsKey(activity, rootTitle, false, false, null, false, listener);
    }

    public static void showActAsKey(Activity activity, String rootTitle,
                                    boolean chord, boolean allowRemove,
                                    KeyMapPrefs.Press pressKind,
                                    boolean banSideChrome, Listener listener) {
        if (activity == null || listener == null) return;
        showGroup(activity, rootTitle == null ? "Act as key" : rootTitle,
            "Act as key", KeyMapPrefs.GROUP_ACT_AS_KEY, chord, allowRemove,
            pressKind, banSideChrome, listener);
    }

    /**
     * Pick a layout then bind hold / toggle / full modifier mode.
     */
    public static void showLayoutModifier(Activity activity, String rootTitle,
                                          boolean chord, boolean allowRemove,
                                          KeyMapPrefs.Press pressKind,
                                          Listener listener) {
        CustomLayoutStore store = new CustomLayoutStore(activity);
        List<CustomLayoutStore.Layout> layouts = store.list();
        List<String> labels = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (CustomLayoutStore.Layout l : layouts) {
            labels.add(l.name + (l.builtin ? "" : " · custom"));
            ids.add(l.id);
        }
        labels.add("New layout…");
        ids.add("__new__");
        labels.add("Layout off");
        ids.add("__off__");

        new AlertDialog.Builder(activity)
            .setTitle("Layout")
            .setItems(labels.toArray(new String[0]), (d, which) -> {
                if (which < 0 || which >= ids.size()) return;
                String id = ids.get(which);
                if ("__off__".equals(id)) {
                    listener.onPicked(KeyMapPrefs.ACT_LAYOUT_OFF);
                    return;
                }
                if ("__new__".equals(id)) {
                    EditText input = new EditText(activity);
                    input.setSingleLine(true);
                    input.setHint("Name");
                    input.setText("Layout");
                    new AlertDialog.Builder(activity)
                        .setTitle("New layout")
                        .setView(input)
                        .setPositiveButton("Create", (dd, w) -> {
                            String n = input.getText() != null
                                ? input.getText().toString().trim() : "Layout";
                            CustomLayoutStore.Layout created = store.create(
                                n.isEmpty() ? "Layout" : n);
                            pickLayoutBind(activity, rootTitle, created.id,
                                pressKind, chord, allowRemove, listener);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                    return;
                }
                pickLayoutBind(activity, rootTitle, id, pressKind, chord,
                    allowRemove, listener);
            })
            .setNegativeButton("Back", (d, w) ->
                show(activity, rootTitle, chord, allowRemove, pressKind, listener))
            .show();
    }

    private static void pickLayoutBind(Activity activity, String rootTitle,
                                       String layoutId, KeyMapPrefs.Press pressKind,
                                       boolean chord, boolean allowRemove,
                                       Listener listener) {
        // Full modifier mode: hold + toggle, short cleared
        if (pressKind == null) {
            String[] opts = new String[] {
                "Modifier mode (hold + double toggle)",
                "Hold only",
                "Toggle only"
            };
            new AlertDialog.Builder(activity)
                .setTitle(new CustomLayoutStore(activity).nameOf(layoutId))
                .setItems(opts, (d, which) -> {
                    if (which == 0) listener.onLayoutModifier(layoutId);
                    else if (which == 1) {
                        listener.onPicked(KeyMapPrefs.layoutHoldAction(layoutId));
                    } else if (which == 2) {
                        listener.onPicked(KeyMapPrefs.layoutToggleAction(layoutId));
                    }
                })
                .setNegativeButton("Back", (d, w) ->
                    showLayoutModifier(activity, rootTitle, chord, allowRemove,
                        pressKind, listener))
                .show();
            return;
        }
        if (pressKind == KeyMapPrefs.Press.LONG) {
            // Offer full modifier mode or hold-only
            String[] opts = new String[] {
                "Hold while pressed",
                "Full modifier (hold + double toggle)"
            };
            new AlertDialog.Builder(activity)
                .setTitle(new CustomLayoutStore(activity).nameOf(layoutId))
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        listener.onPicked(KeyMapPrefs.layoutHoldAction(layoutId));
                    } else {
                        listener.onLayoutModifier(layoutId);
                    }
                })
                .setNegativeButton("Back", (d, w) ->
                    showLayoutModifier(activity, rootTitle, chord, allowRemove,
                        pressKind, listener))
                .show();
            return;
        }
        if (pressKind == KeyMapPrefs.Press.DOUBLE) {
            String[] opts = new String[] {
                "Toggle on double",
                "Full modifier (hold + double toggle)"
            };
            new AlertDialog.Builder(activity)
                .setTitle(new CustomLayoutStore(activity).nameOf(layoutId))
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        listener.onPicked(KeyMapPrefs.layoutToggleAction(layoutId));
                    } else {
                        listener.onLayoutModifier(layoutId);
                    }
                })
                .setNegativeButton("Back", (d, w) ->
                    showLayoutModifier(activity, rootTitle, chord, allowRemove,
                        pressKind, listener))
                .show();
            return;
        }
        // SHORT — only full modifier makes sense (short becomes none)
        listener.onLayoutModifier(layoutId);
    }

    private static void showGroup(Activity activity, String rootTitle, String groupTitle,
                                  String[][] group, boolean chord, boolean allowRemove,
                                  KeyMapPrefs.Press pressKind, boolean banSideChrome,
                                  Listener listener) {
        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (String[] a : group) {
            if (chord && KeyMapPrefs.ACT_DEFAULT.equals(a[0])) continue;
            // Drop old host-layout entries from Computer group (now Layout modifier)
            if (KeyMapPrefs.isLayoutAction(a[0])) continue;
            // B1: side rail never lists Home / Recents / Camera / power chrome
            if (banSideChrome && KeyMapPrefs.isSystemChromeAction(a[0])) continue;
            labels.add(a[1]);
            values.add(a[0]);
        }
        if (labels.isEmpty()) {
            new AlertDialog.Builder(activity)
                .setTitle(groupTitle)
                .setMessage("No actions for side keys in this group")
                .setPositiveButton("Back", (d, w) ->
                    show(activity, rootTitle, chord, allowRemove, pressKind,
                        banSideChrome, listener))
                .show();
            return;
        }
        new AlertDialog.Builder(activity)
            .setTitle(groupTitle)
            .setItems(labels.toArray(new String[0]), (d, which) ->
                listener.onPicked(values.get(which)))
            .setNegativeButton("Back", (d, w) ->
                show(activity, rootTitle, chord, allowRemove, pressKind,
                    banSideChrome, listener))
            .show();
    }

    /**
     * Freeform host chord: any {@code host:key} or {@code host:mod+mod+key}
     * (ctrl/shift/alt/meta + up/down/esc/enter/a-z/f1…). Goes to PC when HID
     * is live; phone inject when idle.
     */
    private static void showCustomHostChord(Activity activity, String rootTitle,
                                            Listener listener) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint("ctrl+shift+t  or  up  or  alt+tab");
        input.setText("ctrl+");
        new AlertDialog.Builder(activity)
            .setTitle("Host key / combo")
            .setMessage("Examples: up · pageup · ctrl+c · alt+tab · ctrl+shift+esc")
            .setView(input)
            .setPositiveButton("OK", (d, w) -> {
                String raw = input.getText() != null
                    ? input.getText().toString().trim() : "";
                if (raw.isEmpty()) return;
                if (raw.startsWith(KeyMapPrefs.ACT_HOST_PREFIX)) {
                    listener.onPicked(raw);
                } else {
                    listener.onPicked(KeyMapPrefs.ACT_HOST_PREFIX + raw.toLowerCase());
                }
            })
            .setNegativeButton("Back", (d, w) ->
                show(activity, rootTitle, false, false, null, listener))
            .show();
    }

    /** Freeform phone Android keycode inject: keycode:3 = HOME, etc. */
    private static void showCustomKeycode(Activity activity, String rootTitle,
                                          Listener listener) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint("3 = Home · 4 = Back · 19–22 = D-pad");
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(activity)
            .setTitle("Phone keycode")
            .setMessage("Android KeyEvent code number")
            .setView(input)
            .setPositiveButton("OK", (d, w) -> {
                String raw = input.getText() != null
                    ? input.getText().toString().trim() : "";
                if (raw.isEmpty()) return;
                try {
                    int code = Integer.parseInt(raw);
                    if (code <= 0) return;
                    listener.onPicked(KeyMapPrefs.ACT_KEYCODE_PREFIX + code);
                } catch (Exception ignored) {}
            })
            .setNegativeButton("Back", (d, w) ->
                show(activity, rootTitle, false, false, null, listener))
            .show();
    }
}
