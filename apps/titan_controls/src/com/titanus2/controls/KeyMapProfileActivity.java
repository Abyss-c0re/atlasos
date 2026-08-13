package com.titanus2.controls;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import com.titanus2.controls.ui.UiKit;

/**
 * Per-app key overrides — sparse shortcuts + Hold + key chords that beat
 * global while the app is in the foreground.
 * <p>
 * Keyboard-first (no modifiers; capture path Esc-only):
 * I layout Inherit · O/0 Off · S Specials · A Arrows ·
 * L cycle long-hold special · N Add · H hold key · G use global ·
 * C hold chord · D delete · Esc cancel/finish.
 */
public class KeyMapProfileActivity extends Activity {
    public static final String EXTRA_PACKAGE = "package";

    private KeyMapProfiles profiles;
    private MagicKeyPrefs globalMagic;
    private String pkg;
    private TextView state;
    private TextView holdState;
    private TextView layoutModeState;
    private TextView longPressSpecialsState;
    private TextView captureBanner;
    private TextView addBtn;
    private TextView addHoldBtn;
    private LinearLayout list;
    private LinearLayout holdList;

    private enum CaptureKind { NONE, SHORTCUT, HOLD_KEY, HOLD_CHORD }
    private CaptureKind captureKind = CaptureKind.NONE;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable captureTimeout = this::cancelCapture;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        profiles = new KeyMapProfiles(this);
        globalMagic = new MagicKeyPrefs(this);
        pkg = getIntent() != null ? getIntent().getStringExtra(EXTRA_PACKAGE) : null;
        if (pkg == null || pkg.isEmpty()) {
            finish();
            return;
        }
        profiles.ensureProfile(pkg, profiles.resolveAppLabel(pkg));
        setTitle(profiles.getLabel(pkg));

        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        UiKit.screen(root);
        sc.addView(root);

        state = UiKit.summary(root);
        captureBanner = UiKit.stateLine(root);
        captureBanner.setTextColor(UiKit.WARN);
        captureBanner.setVisibility(View.GONE);

        // Host layout default while this app is foreground
        UiKit.section(root, "Host layout");
        LinearLayout layRow = UiKit.row(root);
        UiKit.flexButton(layRow, "Inherit", () -> setLayoutMode(HostLayoutController.MODE_INHERIT));
        UiKit.flexButton(layRow, "Off", () -> setLayoutMode(HostLayoutController.MODE_OFF));
        UiKit.flexButton(layRow, "Specials", () -> setLayoutMode(HostLayoutController.MODE_SPECIALS));
        UiKit.flexButton(layRow, "Arrows", () -> setLayoutMode(HostLayoutController.MODE_ARROWS));
        layoutModeState = UiKit.summary(root);

        // 12.20: long-hold letter→special UI removed (OEM: hold Sym/Fn + KCM only)
        longPressSpecialsState = null;

        UiKit.section(root, "Overrides");
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        LinearLayout scRow = UiKit.row(root);
        addBtn = UiKit.flexButton(scRow, "Add", this::startAddShortcut);

        UiKit.section(root, "Hold + key");
        holdState = UiKit.summary(root);
        LinearLayout holdRow = UiKit.row(root);
        UiKit.flexButton(holdRow, "Set hold key", this::startSetHoldKey);
        UiKit.flexButton(holdRow, "Use global", this::clearHoldKey);
        holdList = new LinearLayout(this);
        holdList.setOrientation(LinearLayout.VERTICAL);
        root.addView(holdList);
        addHoldBtn = UiKit.button(root, "Add hold shortcut", this::startAddHoldChord);

        UiKit.button(root, "Delete profile", this::confirmDelete);

        TextView kbHint = UiKit.mono(root);
        kbHint.setText("I/O/S/A layout · N Add · H/G/C hold · D delete · Esc");

        setContentView(sc);
        rebuild();
    }

    /**
     * TitanKey profile shortcuts. While capture is armed, only Esc cancels —
     * never steal the key being assigned.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
            return super.dispatchKeyEvent(event);
        }
        int kc = event.getKeyCode();
        if (captureKind != CaptureKind.NONE) {
            if (kc == KeyEvent.KEYCODE_ESCAPE) {
                cancelCapture();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        if (event.isAltPressed() || event.isCtrlPressed()
                || event.isMetaPressed() || event.isShiftPressed()) {
            return super.dispatchKeyEvent(event);
        }
        switch (kc) {
            case KeyEvent.KEYCODE_ESCAPE:
                finish();
                return true;
            case KeyEvent.KEYCODE_I:
                setLayoutMode(HostLayoutController.MODE_INHERIT);
                UiKit.toast(this, "Layout inherit");
                return true;
            case KeyEvent.KEYCODE_O:
            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_NUMPAD_0:
                setLayoutMode(HostLayoutController.MODE_OFF);
                UiKit.toast(this, "Layout off");
                return true;
            case KeyEvent.KEYCODE_S:
                setLayoutMode(HostLayoutController.MODE_SPECIALS);
                UiKit.toast(this, "Layout specials");
                return true;
            case KeyEvent.KEYCODE_A:
                setLayoutMode(HostLayoutController.MODE_ARROWS);
                UiKit.toast(this, "Layout arrows");
                return true;
            case KeyEvent.KEYCODE_N:
                startAddShortcut();
                return true;
            case KeyEvent.KEYCODE_H:
                startSetHoldKey();
                return true;
            case KeyEvent.KEYCODE_G:
                clearHoldKey();
                UiKit.toast(this, "Hold from global");
                return true;
            case KeyEvent.KEYCODE_C:
                startAddHoldChord();
                return true;
            case KeyEvent.KEYCODE_D:
                confirmDelete();
                return true;
            default:
                break;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Inherit → On → Off → Inherit for this app's long-hold letter specials. */
    private void cycleLongPressSpecials() {
        Boolean ov = profiles.getLongPressSpecialsOverride(pkg);
        if (ov == null) {
            setLongPressSpecialsOverride(Boolean.TRUE);
            UiKit.toast(this, "Long-hold special on");
        } else if (ov) {
            setLongPressSpecialsOverride(Boolean.FALSE);
            UiKit.toast(this, "Long-hold special off");
        } else {
            setLongPressSpecialsOverride(null);
            UiKit.toast(this, "Long-hold special inherit");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        KeyCapture.setUiOpen(true);
        rebuild();
    }

    @Override protected void onPause() {
        h.removeCallbacks(captureTimeout);
        cancelCapture();
        KeyCapture.setUiOpen(false);
        super.onPause();
    }

    private void rebuild() {
        int nKeys = profiles.overrideCount(pkg);
        int nChords = profiles.chordCount(pkg);
        String lay = profiles.getLayoutMode(pkg);
        if (state != null) {
            boolean globalLp = new KeyMapPrefs(this).isLongPressSpecials();
            boolean lp = profiles.resolveLongPressSpecials(pkg, globalLp);
            Boolean lpOv = profiles.getLongPressSpecialsOverride(pkg);
            String lpTag = lpOv == null ? "lp inherit" : (lpOv ? "lp on" : "lp off");
            state.setText(profiles.getLabel(pkg)
                + " · " + nKeys + (nKeys == 1 ? " key" : " keys")
                + " · " + nChords + (nChords == 1 ? " hold" : " holds")
                + " · layout " + HostLayoutController.modeLabel(lay)
                + " · " + lpTag + (lp ? "·live" : "")
                + " · beats global while open");
            state.setTextColor(UiKit.mutedColor(this));
        }
        if (layoutModeState != null) {
            layoutModeState.setText(HostLayoutController.modeLabel(lay)
                + (HostLayoutController.MODE_INHERIT.equals(lay)
                    ? " · use global default / side toggle"
                    : " · auto when this app is open"));
            layoutModeState.setTextColor(UiKit.mutedColor(this));
        }
        refreshLongPressSpecials();
        rebuildShortcuts();
        rebuildHold();
        refreshCaptureBanner();
    }

    private void setLayoutMode(String mode) {
        profiles.setLayoutMode(pkg, mode);
        try { HostLayoutController.publish(this); } catch (Exception ignored) {}
        rebuild();
    }

    /** {@code null} inherit global; true/false force while this app is foreground. */
    private void setLongPressSpecialsOverride(Boolean on) {
        profiles.setLongPressSpecialsOverride(pkg, on);
        refreshLongPressSpecials();
    }

    private void refreshLongPressSpecials() {
        boolean global = new KeyMapPrefs(this).isLongPressSpecials();
        Boolean ov = profiles.getLongPressSpecialsOverride(pkg);
        boolean effective = profiles.resolveLongPressSpecials(pkg, global);
        if (longPressSpecialsState != null) {
            if (ov == null) {
                longPressSpecialsState.setText(global
                    ? "Inherit · on (global)"
                    : "Inherit · off (global)");
            } else if (ov) {
                longPressSpecialsState.setText("On · this app only");
            } else {
                longPressSpecialsState.setText("Off · this app only");
            }
            longPressSpecialsState.setTextColor(effective ? UiKit.liveAccent(this) : UiKit.mutedColor(this));
        }
    }

    private void rebuildShortcuts() {
        if (list == null) return;
        list.removeAllViews();
        List<KeyMapPrefs.ShortcutRow> rows = profiles.listVisibleRows(pkg);
        if (rows.isEmpty()) {
            TextView empty = UiKit.stateLine(list);
            empty.setText("none — Add, then press a key");
            empty.setTextColor(UiKit.mutedColor(this));
            return;
        }
        for (KeyMapPrefs.ShortcutRow row : rows) {
            if (row.isLayoutModifier()) {
                String name = layoutName(row.layoutId);
                String key = MagicKeyPrefs.scanLabel(row.scan);
                String holdAct = row.longSlot != null
                    ? profiles.getOverride(pkg, row.longSlot.id) : null;
                String togAct = row.doubleSlot != null
                    ? profiles.getOverride(pkg, row.doubleSlot.id) : null;
                String mode = KeyMapPrefs.layoutModeLine(holdAct, togAct);
                TextView tv = UiKit.listRow(list,
                    "Layout · " + name,
                    key + " · " + mode,
                    () -> editLayoutModifier(row));
                tv.setOnLongClickListener(v -> {
                    confirmRemoveLayoutModifier(row);
                    return true;
                });
            } else {
                KeyMapPrefs.Slot slot = row.single;
                String act = profiles.getOverride(pkg, slot.id);
                TextView tv = UiKit.listRow(list, formatAction(act),
                    KeyMapPrefs.keyPressLabel(slot), () -> editSlot(slot));
                tv.setOnLongClickListener(v -> {
                    confirmRemoveSlot(slot);
                    return true;
                });
            }
        }
    }

    private String layoutName(String id) {
        try {
            return new com.titanus2.controls.layouts.CustomLayoutStore(this).nameOf(id);
        } catch (Exception e) {
            return id == null ? "?" : id;
        }
    }

    private void editLayoutModifier(KeyMapPrefs.ShortcutRow row) {
        String name = layoutName(row.layoutId);
        new AlertDialog.Builder(this)
            .setTitle("Layout · " + name)
            .setItems(new String[] {
                "Change layout…",
                "Remove modifier"
            }, (d, which) -> {
                if (which == 0) {
                    pickLayoutModifierForScan(row.scan);
                } else if (which == 1) {
                    clearLayoutModifier(row.scan);
                    rebuild();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void confirmRemoveLayoutModifier(KeyMapPrefs.ShortcutRow row) {
        new AlertDialog.Builder(this)
            .setTitle("Remove")
            .setMessage(MagicKeyPrefs.scanLabel(row.scan)
                + "\nLayout · " + layoutName(row.layoutId))
            .setPositiveButton("Remove", (d, w) -> {
                clearLayoutModifier(row.scan);
                rebuild();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void clearLayoutModifier(int scan) {
        int c = KeyMapPrefs.canonicalizeScan(scan);
        for (KeyMapPrefs.Press pr : KeyMapPrefs.Press.values()) {
            KeyMapPrefs.Slot sl = KeyMapPrefs.slotByScan(c, pr);
            if (sl != null) profiles.clearOverride(pkg, sl.id);
        }
    }

    private void pickLayoutModifierForScan(int scan) {
        // Reuse global picker labels — assign full modifier on this profile
        final String[] ids = new String[] { "specials", "arrows" };
        final String[] labels = new String[] { "Specials", "Arrows" };
        new AlertDialog.Builder(this)
            .setTitle("Layout")
            .setItems(labels, (d, which) -> {
                if (which < 0 || which >= ids.length) return;
                String layoutId = ids[which];
                int c = KeyMapPrefs.canonicalizeScan(scan);
                KeyMapPrefs.Slot sh = KeyMapPrefs.slotByScan(c, KeyMapPrefs.Press.SHORT);
                KeyMapPrefs.Slot lo = KeyMapPrefs.slotByScan(c, KeyMapPrefs.Press.LONG);
                KeyMapPrefs.Slot db = KeyMapPrefs.slotByScan(c, KeyMapPrefs.Press.DOUBLE);
                if (sh != null) {
                    profiles.setOverride(pkg, sh.id, KeyMapPrefs.ACT_NONE);
                }
                if (lo != null) {
                    profiles.setOverride(pkg, lo.id,
                        KeyMapPrefs.layoutHoldAction(layoutId));
                }
                if (db != null) {
                    profiles.setOverride(pkg, db.id,
                        KeyMapPrefs.layoutToggleAction(layoutId));
                }
                rebuild();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void rebuildHold() {
        if (holdState == null) return;
        int effective = profiles.resolveMagicScan(pkg, globalMagic.getScan());
        int own = profiles.getMagicScan(pkg);
        int n = profiles.chordCount(pkg);
        if (own == KeyMapProfiles.MAGIC_INHERIT) {
            if (effective <= 0) {
                holdState.setText("Hold key from global · none set");
                holdState.setTextColor(UiKit.mutedColor(this));
            } else {
                holdState.setText("Hold " + MagicKeyPrefs.scanLabel(effective)
                    + " (global) · " + n + (n == 1 ? " shortcut" : " shortcuts"));
                holdState.setTextColor(UiKit.liveAccent(this));
            }
        } else if (own <= 0) {
            holdState.setText("Hold off in this app");
            holdState.setTextColor(UiKit.WARN);
        } else {
            holdState.setText("Hold " + MagicKeyPrefs.scanLabel(own)
                + " · " + n + (n == 1 ? " shortcut" : " shortcuts"));
            holdState.setTextColor(UiKit.liveAccent(this));
        }
        if (addHoldBtn != null) {
            boolean ok = effective > 0;
            addHoldBtn.setEnabled(ok);
            addHoldBtn.setAlpha(ok ? 1f : 0.45f);
        }
        if (holdList == null) return;
        holdList.removeAllViews();
        List<MagicKeyPrefs.ChordEntry> chords = profiles.listChords(pkg);
        if (chords.isEmpty()) {
            if (effective > 0) {
                TextView empty = UiKit.summary(holdList);
                empty.setText("No hold shortcuts yet");
                empty.setTextColor(UiKit.mutedColor(this));
            }
            return;
        }
        for (MagicKeyPrefs.ChordEntry ce : chords) {
            String key = ce.byScan
                ? MagicKeyPrefs.scanLabel(ce.id)
                : MagicKeyPrefs.keyCodeLabel(ce.id);
            String label = "hold + " + key + "  →  " + KeyMapPrefs.actionLabel(ce.action);
            final MagicKeyPrefs.ChordEntry entry = ce;
            TextView row = UiKit.button(holdList, label, () ->
                pickChordAction(entry.byScan ? entry.id : 0,
                    entry.byScan ? 0 : entry.id));
            row.setOnLongClickListener(v -> {
                profiles.clearChord(pkg, entry.byScan, entry.id);
                rebuild();
                return true;
            });
        }
    }

    private String formatAction(String act) {
        if (act == null) return "—";
        return KeyMapPrefs.actionLabel(act);
    }

    private boolean ensureKeyService() {
        if (AccessServiceHelper.isListed(this)) return true;
        AccessServiceHelper.setEnabled(this, true);
        if (AccessServiceHelper.isListed(this)) return true;
        android.widget.Toast.makeText(this,
            "Enable Key service in Keys", android.widget.Toast.LENGTH_SHORT).show();
        return false;
    }

    private void cancelCapture() {
        h.removeCallbacks(captureTimeout);
        KeyCapture.disarm();
        captureKind = CaptureKind.NONE;
        if (addBtn != null) {
            addBtn.setText("Add");
            UiKit.setSelected(addBtn, false);
        }
        if (addHoldBtn != null) {
            addHoldBtn.setText("Add hold shortcut");
            UiKit.setSelected(addHoldBtn, false);
        }
        refreshCaptureBanner();
    }

    private void armCapture(CaptureKind kind, KeyCapture.Listener listener) {
        cancelCapture();
        captureKind = kind;
        KeyCapture.arm((scan, keyCode) -> h.post(() -> {
            captureKind = CaptureKind.NONE;
            refreshCaptureBanner();
            if (addBtn != null) {
                addBtn.setText("Add");
                UiKit.setSelected(addBtn, false);
            }
            if (addHoldBtn != null) {
                addHoldBtn.setText("Add hold shortcut");
                UiKit.setSelected(addHoldBtn, false);
            }
            listener.onKey(scan, keyCode);
        }));
        refreshCaptureBanner();
        h.postDelayed(captureTimeout, 10000);
        if (kind == CaptureKind.SHORTCUT && addBtn != null) {
            addBtn.setText("Cancel");
            UiKit.setSelected(addBtn, true);
        }
        if (kind == CaptureKind.HOLD_CHORD && addHoldBtn != null) {
            addHoldBtn.setText("Cancel");
            UiKit.setSelected(addHoldBtn, true);
        }
    }

    private void refreshCaptureBanner() {
        if (captureBanner == null) return;
        if (captureKind == CaptureKind.NONE) {
            captureBanner.setVisibility(View.GONE);
            return;
        }
        captureBanner.setVisibility(View.VISIBLE);
        switch (captureKind) {
            case SHORTCUT:
                captureBanner.setText("Press the key to assign…");
                break;
            case HOLD_KEY:
                captureBanner.setText("Press the hold key for this app…");
                break;
            case HOLD_CHORD:
                captureBanner.setText("Hold key is set — press the second key…");
                break;
            default:
                captureBanner.setVisibility(View.GONE);
        }
    }

    private void startAddShortcut() {
        if (captureKind == CaptureKind.SHORTCUT) {
            cancelCapture();
            return;
        }
        if (!ensureKeyService()) return;
        armCapture(CaptureKind.SHORTCUT, (scan, keyCode) -> onShortcutKey(scan));
    }

    private void startSetHoldKey() {
        if (!ensureKeyService()) return;
        armCapture(CaptureKind.HOLD_KEY, (scan, keyCode) -> {
            int c = KeyMapPrefs.canonicalizeScan(scan);
            if (c <= 0) c = scan;
            if (c <= 0) return;
            profiles.setMagicScan(pkg, c);
            rebuild();
        });
    }

    private void clearHoldKey() {
        profiles.setMagicScan(pkg, KeyMapProfiles.MAGIC_INHERIT);
        rebuild();
    }

    private void startAddHoldChord() {
        if (captureKind == CaptureKind.HOLD_CHORD) {
            cancelCapture();
            return;
        }
        int effective = profiles.resolveMagicScan(pkg, globalMagic.getScan());
        if (effective <= 0) {
            // Need a hold key first — prefer setting per-app, else prompt global path
            if (globalMagic.getScan() <= 0) {
                startSetHoldKey();
                return;
            }
            android.widget.Toast.makeText(this,
                "Hold is off in this app — Set hold key or Use global",
                android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ensureKeyService()) return;
        armCapture(CaptureKind.HOLD_CHORD, this::onHoldChordKey);
    }

    private void onShortcutKey(int scan) {
        h.removeCallbacks(captureTimeout);
        int c = KeyMapPrefs.canonicalizeScan(scan);
        if (!KeyMapPrefs.isManagedScan(c)) {
            new AlertDialog.Builder(this)
                .setTitle("Key")
                .setMessage("Back, Recents, Fn, side keys, Sym, Alt.")
                .setPositiveButton("OK", null)
                .show();
            return;
        }
        String name = MagicKeyPrefs.scanLabel(c);
        String[] presses = new String[] { "Short press", "Long press", "Double press" };
        KeyMapPrefs.Press[] kinds = new KeyMapPrefs.Press[] {
            KeyMapPrefs.Press.SHORT, KeyMapPrefs.Press.LONG, KeyMapPrefs.Press.DOUBLE
        };
        new AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(presses, (d, which) -> {
                KeyMapPrefs.Slot slot = KeyMapPrefs.slotByScan(c, kinds[which]);
                if (slot == null) return;
                pickSlotAction(slot);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void onHoldChordKey(int scan, int keyCode) {
        h.removeCallbacks(captureTimeout);
        int c = KeyMapPrefs.canonicalizeScan(scan);
        int hold = profiles.resolveMagicScan(pkg, globalMagic.getScan());
        if (hold > 0 && c == hold) {
            new AlertDialog.Builder(this)
                .setMessage("That is the hold key. Press a different key.")
                .setPositiveButton("OK", null)
                .show();
            return;
        }
        pickChordAction(c > 0 ? c : 0, keyCode);
    }

    private void editSlot(KeyMapPrefs.Slot slot) {
        new AlertDialog.Builder(this)
            .setTitle(formatAction(profiles.getOverride(pkg, slot.id))
                + "  ·  " + KeyMapPrefs.keyPressLabel(slot))
            .setItems(new String[] { "Change action…", "Remove" }, (d, which) -> {
                if (which == 0) pickSlotAction(slot);
                else confirmRemoveSlot(slot);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void confirmRemoveSlot(KeyMapPrefs.Slot slot) {
        new AlertDialog.Builder(this)
            .setTitle("Remove")
            .setMessage(KeyMapPrefs.keyPressLabel(slot))
            .setPositiveButton("Remove", (d, w) -> {
                profiles.clearOverride(pkg, slot.id);
                rebuild();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
            .setTitle("Delete profile")
            .setMessage(profiles.getLabel(pkg))
            .setPositiveButton("Delete", (d, w) -> {
                profiles.removeProfile(pkg);
                finish();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void pickSlotAction(KeyMapPrefs.Slot slot) {
        KeyActionPicker.show(this, KeyMapPrefs.keyPressLabel(slot), false, true,
            slot.press, new KeyActionPicker.Listener() {
                @Override public void onPicked(String action) {
                    profiles.setOverride(pkg, slot.id, action);
                    afterProfileChange();
                    rebuild();
                }
                @Override public void onPickApp() {
                    pickApp(false, slot, 0, 0);
                }
                @Override public void onRemove() {
                    profiles.clearOverride(pkg, slot.id);
                    afterProfileChange();
                    rebuild();
                }
                @Override public void onLayoutModifier(String layoutId) {
                    // Per-app: set hold/toggle overrides for this scan
                    KeyMapPrefs.Slot sh = KeyMapPrefs.slotByScan(slot.scan, KeyMapPrefs.Press.SHORT);
                    KeyMapPrefs.Slot lo = KeyMapPrefs.slotByScan(slot.scan, KeyMapPrefs.Press.LONG);
                    KeyMapPrefs.Slot db = KeyMapPrefs.slotByScan(slot.scan, KeyMapPrefs.Press.DOUBLE);
                    if (sh != null) {
                        profiles.setOverride(pkg, sh.id, KeyMapPrefs.ACT_NONE);
                    }
                    if (lo != null) {
                        profiles.setOverride(pkg, lo.id,
                            KeyMapPrefs.layoutHoldAction(layoutId));
                    }
                    if (db != null) {
                        profiles.setOverride(pkg, db.id,
                            KeyMapPrefs.layoutToggleAction(layoutId));
                    }
                    afterProfileChange();
                    rebuild();
                }
            });
    }

    private void pickChordAction(int scan, int keyCode) {
        String title = "hold + " + (scan > 0
            ? MagicKeyPrefs.scanLabel(scan)
            : MagicKeyPrefs.keyCodeLabel(keyCode));
        KeyActionPicker.show(this, title, true, true, new KeyActionPicker.Listener() {
            @Override public void onPicked(String action) {
                applyChord(scan, keyCode, action);
            }
            @Override public void onPickApp() {
                pickApp(true, null, scan, keyCode);
            }
            @Override public void onRemove() {
                if (scan > 0) profiles.setChordByScan(pkg, scan, null);
                else profiles.setChordByKeyCode(pkg, keyCode, null);
                rebuild();
            }
        });
    }

    /**
     * Publish effective map + refresh HID silence pins so side→mouse assigned
     * while a session is live actually takes effect.
     */
    private void afterProfileChange() {
        try {
            new KeyMapPrefs(this).publishToAgent(this);
        } catch (Exception ignored) {}
        try {
            new TempKeyMapStack(this).refreshSilenceIfActive(
                this, new KeyMapPrefs(this), null);
        } catch (Exception ignored) {}
    }

    private void applyChord(int scan, int keyCode, String action) {
        if (scan > 0 && KeyMapPrefs.isManagedScan(scan)) {
            profiles.setChordByScan(pkg, scan, action);
        } else if (keyCode > 0) {
            profiles.setChordByKeyCode(pkg, keyCode, action);
        } else if (scan > 0) {
            profiles.setChordByScan(pkg, scan, action);
        }
        afterProfileChange();
        rebuild();
    }

    private void pickApp(boolean chord, KeyMapPrefs.Slot slot, int scan, int keyCode) {
        List<String[]> apps = KeyMapProfiles.launcherApps(this);
        List<String> labels = new ArrayList<>();
        List<String> pkgs = new ArrayList<>();
        for (String[] a : apps) {
            labels.add(a[0]);
            pkgs.add(a[1]);
        }
        new AlertDialog.Builder(this)
            .setTitle("App")
            .setItems(labels.toArray(new String[0]), (d, which) ->
                pickActivity(chord, slot, scan, keyCode, pkgs.get(which), labels.get(which)))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void pickActivity(boolean chord, KeyMapPrefs.Slot slot, int scan, int keyCode,
                              String appPkg, String appLabel) {
        PackageManager pm = getPackageManager();
        List<String> labels = new ArrayList<>();
        List<String> comps = new ArrayList<>();
        labels.add(appLabel);
        comps.add(appPkg);
        try {
            ActivityInfo[] acts = pm.getPackageInfo(appPkg, PackageManager.GET_ACTIVITIES).activities;
            if (acts != null) {
                for (ActivityInfo ai : acts) {
                    if (!ai.exported) continue;
                    String name = ai.name;
                    String shortN = name.contains(".")
                        ? name.substring(name.lastIndexOf('.') + 1) : name;
                    labels.add(shortN);
                    comps.add(appPkg + "/" + name);
                }
            }
        } catch (Exception ignored) {}
        new AlertDialog.Builder(this)
            .setTitle(appLabel)
            .setItems(labels.toArray(new String[0]), (d, which) -> {
                String action = KeyMapPrefs.ACT_APP_PREFIX + comps.get(which);
                if (chord) applyChord(scan, keyCode, action);
                else if (slot != null) {
                    profiles.setOverride(pkg, slot.id, action);
                    afterProfileChange();
                    rebuild();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    public static Intent intent(android.content.Context ctx, String packageName) {
        Intent i = new Intent(ctx, KeyMapProfileActivity.class);
        i.putExtra(EXTRA_PACKAGE, packageName);
        return i;
    }
}
