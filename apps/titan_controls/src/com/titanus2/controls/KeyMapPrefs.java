package com.titanus2.controls;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hardware key remap — Titan 2 programmable shortcuts + layout roles.
 *
 * Shortcuts (this class): optional actions on a press (app, Home, flashlight…).
 * Factory list is only upper-row nav that is already a key (Back, Recents).
 * Fn is not a factory shortcut — layout maps it (Fn→Ctrl via AgentBridge FN_MODE).
 *
 * Layout (KeyMapActivity + pad-agent keylayouts):
 *   · FN_MODE stock|ctrl — Fn as Ctrl (default ctrl matches product kl)
 *   · CHAR_MOD sym|fn|alt — which key drives printed specials (! @ #)
 *
 * Explicit shortcut actions always win over layout for that key.
 */
public final class KeyMapPrefs {
    public static final String PREFS = "titan2_keymap";

    public static final int SCAN_SIDE_FUNC = 250;
    public static final int SCAN_SIDE_FUNC2 = 249;
    /** Upper specials (TitanKey / stock_oem ROW1). */
    public static final int SCAN_BACK = 158;
    public static final int SCAN_APP_SWITCH = 580;
    public static final int SCAN_FN = 183;       // BUTTON_1 stock Fn
    public static final int SCAN_FN_ALT = 251;   // FUNC3 / alt report of Fn
    public static final int SCAN_SYM = 222;      // BUTTON_2 / SYM-ish
    public static final int SCAN_SYM_ALT = 253;
    public static final int SCAN_ALT = 100;      // ALT_RIGHT

    public static final String ACT_DEFAULT = "default";
    public static final String ACT_NONE = "none";
    public static final String ACT_HOME = "home";
    public static final String ACT_BACK = "back";
    public static final String ACT_RECENTS = "recents";
    public static final String ACT_NOTIFICATIONS = "notifications";
    public static final String ACT_QUICK_SETTINGS = "qs";
    public static final String ACT_POWER_DIALOG = "power";
    public static final String ACT_ASSIST = "assist";
    public static final String ACT_FLASHLIGHT = "flashlight";
    public static final String ACT_CAMERA = "camera";
    public static final String ACT_SCREENSHOT = "screenshot";
    public static final String ACT_VOICE = "voice";
    public static final String ACT_SUBDISPLAY = "subdisplay_toggle";
    /**
     * Launch Settings (or last rear app) on display 2 — stock “sub screen launcher”
     * without Agui. Needs panel powered; input association still Face/Apps gap.
     */
    public static final String ACT_SUB_APPS = "subdisplay_apps";
    /** Telecom — OEM space-bar / shortcut parity (no Agui). */
    public static final String ACT_ANSWER_CALL = "answer_call";
    public static final String ACT_END_CALL = "end_call";
    public static final String ACT_MUTE = "mute_toggle";
    /**
     * Computer input — for USB keyboard apps, remote desktop (Moonlight, …), etc.
     * Not phone UI. Values: mouse:left|right|middle, host:ctrl+f1, host:esc, …
     */
    public static final String ACT_MOUSE_PREFIX = "mouse:";
    public static final String ACT_MOUSE_LEFT = "mouse:left";
    public static final String ACT_MOUSE_RIGHT = "mouse:right";
    public static final String ACT_MOUSE_MIDDLE = "mouse:middle";
    /** Host wheel notches (HID session) / page nav on phone when idle. */
    public static final String ACT_MOUSE_SCROLL_UP = "mouse:scroll_up";
    public static final String ACT_MOUSE_SCROLL_DOWN = "mouse:scroll_down";
    /**
     * Explicit wheel steps: {@code mouse:wheel:+3} / {@code mouse:wheel:-1}
     * (sign = direction, abs = notches, clamped 1–15).
     */
    public static final String ACT_MOUSE_WHEEL_PREFIX = "mouse:wheel:";
    /** Keyboard / chords for computer: host:ctrl+f1, host:esc, host:ctrl+w */
    public static final String ACT_HOST_PREFIX = "host:";
    /** Launch app package (launcher activity) or component: app:pkg or app:pkg/cls */
    public static final String ACT_APP_PREFIX = "app:";
    /** Inject Android keycode: keycode:3 (HOME) etc. */
    public static final String ACT_KEYCODE_PREFIX = "keycode:";
    /** Remap to another managed key short-press behavior / inject scan */
    public static final String ACT_SCAN_PREFIX = "scan:";
    /**
     * Layout layers. New form: {@code layout:hold:&lt;id&gt;} / {@code layout:toggle:&lt;id&gt;}.
     * Legacy: specials_hold, specials_toggle, arrows_hold, arrows_toggle.
     */
    public static final String ACT_LAYOUT_PREFIX = "layout:";
    public static final String ACT_LAYOUT_HOLD_PREFIX = "layout:hold:";
    public static final String ACT_LAYOUT_TOGGLE_PREFIX = "layout:toggle:";
    public static final String ACT_LAYOUT_SPECIALS_HOLD = "layout:specials_hold";
    public static final String ACT_LAYOUT_SPECIALS_TOGGLE = "layout:specials_toggle";
    public static final String ACT_LAYOUT_ARROWS_HOLD = "layout:arrows_hold";
    public static final String ACT_LAYOUT_ARROWS_TOGGLE = "layout:arrows_toggle";
    public static final String ACT_LAYOUT_OFF = "layout:off";

    public static String layoutHoldAction(String layoutId) {
        if (layoutId == null || layoutId.isEmpty() || "off".equals(layoutId)) {
            return ACT_LAYOUT_OFF;
        }
        // Keep legacy strings for built-ins (pad-agent / old prefs)
        if ("specials".equals(layoutId)) return ACT_LAYOUT_SPECIALS_HOLD;
        if ("arrows".equals(layoutId)) return ACT_LAYOUT_ARROWS_HOLD;
        return ACT_LAYOUT_HOLD_PREFIX + layoutId;
    }

    public static String layoutToggleAction(String layoutId) {
        if (layoutId == null || layoutId.isEmpty() || "off".equals(layoutId)) {
            return ACT_LAYOUT_OFF;
        }
        if ("specials".equals(layoutId)) return ACT_LAYOUT_SPECIALS_TOGGLE;
        if ("arrows".equals(layoutId)) return ACT_LAYOUT_ARROWS_TOGGLE;
        return ACT_LAYOUT_TOGGLE_PREFIX + layoutId;
    }

    /** Layout id from hold action, or null. */
    public static String layoutHoldId(String action) {
        if (action == null) return null;
        if (ACT_LAYOUT_SPECIALS_HOLD.equals(action)) return "specials";
        if (ACT_LAYOUT_ARROWS_HOLD.equals(action)) return "arrows";
        if (action.startsWith(ACT_LAYOUT_HOLD_PREFIX)) {
            String id = action.substring(ACT_LAYOUT_HOLD_PREFIX.length()).trim();
            return id.isEmpty() ? null : id;
        }
        return null;
    }

    /** Layout id from toggle action, or null. */
    public static String layoutToggleId(String action) {
        if (action == null) return null;
        if (ACT_LAYOUT_SPECIALS_TOGGLE.equals(action)) return "specials";
        if (ACT_LAYOUT_ARROWS_TOGGLE.equals(action)) return "arrows";
        if (action.startsWith(ACT_LAYOUT_TOGGLE_PREFIX)) {
            String id = action.substring(ACT_LAYOUT_TOGGLE_PREFIX.length()).trim();
            return id.isEmpty() ? null : id;
        }
        return null;
    }

    /**
     * Assign layout-modifier mode on a physical key: long = hold, double = toggle,
     * short = none (disables other shortcut kinds for that scan).
     */
    /**
     * Bind a whole-key remap (act as key). Long is forced none so the UI
     * cannot split short vs long. Double tap is left alone.
     */
    public void assignActAsKey(int scan, String action) {
        if (action == null || ACT_NONE.equals(action) || ACT_DEFAULT.equals(action)) {
            int c = canonicalizeScan(scan);
            Slot sh = slotByScan(c, Press.SHORT);
            Slot lo = slotByScan(c, Press.LONG);
            SharedPreferences.Editor ed = p.edit();
            if (sh != null) {
                ed.putString("slot_" + sh.id, ACT_NONE);
                ed.remove("slot_" + sh.id + "_label");
            }
            if (lo != null) {
                ed.putString("slot_" + lo.id, ACT_NONE);
                ed.remove("slot_" + lo.id + "_label");
            }
            ed.apply();
            return;
        }
        if (!isActAsKeyAction(action)) return;
        int c = canonicalizeScan(scan);
        Slot sh = slotByScan(c, Press.SHORT);
        Slot lo = slotByScan(c, Press.LONG);
        SharedPreferences.Editor ed = p.edit();
        if (sh != null) {
            String a = action;
            if (isSideSlotId(sh.id) && isSystemChromeAction(a)) {
                a = factoryDefault(sh.id);
            }
            ed.putString("slot_" + sh.id, a);
            ed.remove("slot_" + sh.id + "_label");
        }
        if (lo != null) {
            ed.putString("slot_" + lo.id, ACT_NONE);
            ed.remove("slot_" + lo.id + "_label");
        }
        ed.apply();
    }

    public boolean isActAsKeyScan(int scan) {
        Slot sh = slotByScan(canonicalizeScan(scan), Press.SHORT);
        return sh != null && isActAsKeyAction(getAction(sh.id));
    }

    public void assignLayoutModifier(int scan, String layoutId) {
        int c = canonicalizeScan(scan);
        Slot sh = slotByScan(c, Press.SHORT);
        Slot lo = slotByScan(c, Press.LONG);
        Slot db = slotByScan(c, Press.DOUBLE);
        SharedPreferences.Editor ed = p.edit();
        if (sh != null) ed.putString("slot_" + sh.id, ACT_NONE);
        if (lo != null) ed.putString("slot_" + lo.id, layoutHoldAction(layoutId));
        if (db != null) ed.putString("slot_" + db.id, layoutToggleAction(layoutId));
        ed.apply();
    }

    public enum Press { SHORT, LONG, DOUBLE }

    public static final class Slot {
        public final String id;
        public final String label;
        public final int scan;
        public final Press press;
        public Slot(String id, String label, int scan, Press press) {
            this.id = id; this.label = label; this.scan = scan; this.press = press;
        }
        public boolean isLong() { return press == Press.LONG; }
        public boolean isDouble() { return press == Press.DOUBLE; }
    }

    private static Slot s(String id, String label, int scan, Press p) {
        return new Slot(id, label, scan, p);
    }

    public static final Slot[] SLOTS = buildSlots();

    private static Slot[] buildSlots() {
        List<Slot> list = new ArrayList<>();
        // Upper row nav (physical Back / Recents keys)
        list.add(s("back_short", "Back", SCAN_BACK, Press.SHORT));
        list.add(s("back_long", "Back · long", SCAN_BACK, Press.LONG));
        list.add(s("back_double", "Back · double", SCAN_BACK, Press.DOUBLE));
        list.add(s("recents_short", "Recents", SCAN_APP_SWITCH, Press.SHORT));
        list.add(s("recents_long", "Recents · long", SCAN_APP_SWITCH, Press.LONG));
        list.add(s("recents_double", "Recents · double", SCAN_APP_SWITCH, Press.DOUBLE));
        // Fn — no factory shortcut; use Layout → Fn as Ctrl (or Add)
        list.add(s("fn_short", "Fn · short", SCAN_FN, Press.SHORT));
        list.add(s("fn_long", "Fn · long", SCAN_FN, Press.LONG));
        list.add(s("fn_double", "Fn · double", SCAN_FN, Press.DOUBLE));
        // Side programmable (OEM key_func1 / key_func2) — empty until user adds
        // Phone side buttons (both on left edge: top = 249 ff_key, bottom = 250 gpio)
        // Not host mouse L/R — those are mouse:left / mouse:right.
        list.add(s("side_func_short", "Side · bottom · short", SCAN_SIDE_FUNC, Press.SHORT));
        list.add(s("side_func_long", "Side · bottom · long", SCAN_SIDE_FUNC, Press.LONG));
        list.add(s("side_func_double", "Side · bottom · double", SCAN_SIDE_FUNC, Press.DOUBLE));
        list.add(s("side_func2_short", "Side · top · short", SCAN_SIDE_FUNC2, Press.SHORT));
        list.add(s("side_func2_long", "Side · top · long", SCAN_SIDE_FUNC2, Press.LONG));
        list.add(s("side_func2_double", "Side · top · double", SCAN_SIDE_FUNC2, Press.DOUBLE));
        // Modifiers — system default unless remapped
        list.add(s("sym_short", "Sym · short", SCAN_SYM, Press.SHORT));
        list.add(s("sym_long", "Sym · long", SCAN_SYM, Press.LONG));
        list.add(s("sym_double", "Sym · double", SCAN_SYM, Press.DOUBLE));
        list.add(s("alt_short", "Alt · short", SCAN_ALT, Press.SHORT));
        list.add(s("alt_long", "Alt · long", SCAN_ALT, Press.LONG));
        list.add(s("alt_double", "Alt · double", SCAN_ALT, Press.DOUBLE));
        return list.toArray(new Slot[0]);
    }

    /**
     * Flat catalog for labels / lookup. UI uses {@link KeyActionPicker} groups
     * so the assign dialog is not one long messy list.
     */
    public static final String[][] ACTIONS = new String[][] {
        { ACT_HOME, "Home" },
        { ACT_BACK, "Back" },
        { ACT_RECENTS, "Recents" },
        { ACT_NOTIFICATIONS, "Notifications" },
        { ACT_QUICK_SETTINGS, "Quick settings" },
        { ACT_FLASHLIGHT, "Flashlight" },
        { ACT_SCREENSHOT, "Screenshot" },
        { ACT_CAMERA, "Camera" },
        { ACT_POWER_DIALOG, "Power menu" },
        { ACT_ASSIST, "Assistant" },
        { ACT_VOICE, "Voice assist" },
        { ACT_SUBDISPLAY, "Sub display toggle" },
        { ACT_SUB_APPS, "Open on rear display" },
        { ACT_ANSWER_CALL, "Answer call" },
        { ACT_END_CALL, "End call" },
        { ACT_MUTE, "Mute / unmute" },
        { ACT_MOUSE_LEFT, "Left mouse button" },
        { ACT_MOUSE_RIGHT, "Right mouse button" },
        { ACT_MOUSE_MIDDLE, "Middle mouse button" },
        { ACT_MOUSE_SCROLL_UP, "Scroll up" },
        { ACT_MOUSE_SCROLL_DOWN, "Scroll down" },
        { ACT_HOST_PREFIX + "up", "Arrow up" },
        { ACT_HOST_PREFIX + "down", "Arrow down" },
        { ACT_HOST_PREFIX + "left", "Arrow left" },
        { ACT_HOST_PREFIX + "right", "Arrow right" },
        { ACT_HOST_PREFIX + "pageup", "Page up" },
        { ACT_HOST_PREFIX + "pagedown", "Page down" },
        { ACT_HOST_PREFIX + "home", "Line home" },
        { ACT_HOST_PREFIX + "end", "Line end" },
        { ACT_HOST_PREFIX + "ctrl+f1", "Ctrl + F1" },
        { ACT_HOST_PREFIX + "ctrl+f2", "Ctrl + F2" },
        { ACT_HOST_PREFIX + "ctrl+f3", "Ctrl + F3" },
        { ACT_HOST_PREFIX + "ctrl+f4", "Ctrl + F4" },
        { ACT_HOST_PREFIX + "ctrl+w", "Ctrl + W" },
        { ACT_HOST_PREFIX + "ctrl+t", "Ctrl + T" },
        { ACT_HOST_PREFIX + "ctrl+c", "Ctrl + C" },
        { ACT_HOST_PREFIX + "ctrl+v", "Ctrl + V" },
        { ACT_HOST_PREFIX + "ctrl+z", "Ctrl + Z" },
        { ACT_HOST_PREFIX + "alt+tab", "Alt + Tab" },
        { ACT_HOST_PREFIX + "esc", "Escape" },
        { ACT_HOST_PREFIX + "enter", "Enter" },
        { ACT_HOST_PREFIX + "tab", "Tab" },
        { ACT_HOST_PREFIX + "space", "Space" },
        { ACT_HOST_PREFIX + "backspace", "Backspace" },
        { ACT_LAYOUT_SPECIALS_TOGGLE, "Specials layout toggle" },
        { ACT_LAYOUT_ARROWS_TOGGLE, "Arrows layout toggle" },
        { ACT_LAYOUT_SPECIALS_HOLD, "Specials while held" },
        { ACT_LAYOUT_ARROWS_HOLD, "Arrows while held" },
        { ACT_LAYOUT_OFF, "Layout off" },
        { ACT_APP_PREFIX, "Open app…" },
        { ACT_NONE, "Do nothing" },
        { ACT_DEFAULT, "System default" },
        { "keycode:82", "Menu" },
        { "keycode:84", "Search" },
        { "keycode:57", "Alt" },
        { "keycode:113", "Ctrl" },
        { "keycode:63", "Sym" },
        { ACT_SCAN_PREFIX + SCAN_FN, "As Fn" },
        { ACT_SCAN_PREFIX + SCAN_SYM, "As Sym" },
        { ACT_SCAN_PREFIX + SCAN_ALT, "As Alt" },
        { ACT_SCAN_PREFIX + SCAN_SIDE_FUNC, "As side button · bottom" },
        { ACT_SCAN_PREFIX + SCAN_SIDE_FUNC2, "As side button · top" },
    };

    /** Grouped actions for the assign dialog (category → items). */
    public static final String[][] GROUP_NAV = new String[][] {
        { ACT_HOME, "Home" },
        { ACT_BACK, "Back" },
        { ACT_RECENTS, "Recents" },
        { ACT_NOTIFICATIONS, "Notifications" },
        { ACT_QUICK_SETTINGS, "Quick settings" },
    };
    public static final String[][] GROUP_TOOLS = new String[][] {
        { ACT_FLASHLIGHT, "Flashlight" },
        { ACT_SCREENSHOT, "Screenshot" },
        { ACT_CAMERA, "Camera" },
        { ACT_POWER_DIALOG, "Power menu" },
        { ACT_ASSIST, "Assistant" },
        { ACT_VOICE, "Voice assist" },
        { ACT_SUBDISPLAY, "Sub display" },
        { ACT_SUB_APPS, "Open on rear display" },
        { ACT_ANSWER_CALL, "Answer call" },
        { ACT_END_CALL, "End call" },
        { ACT_MUTE, "Mute / unmute" },
    };
    /**
     * Remap: physical key <em>is</em> this key/button for the whole press.
     * No short/long split — follow down/up. Double tap stays a separate type.
     */
    public static final String[][] GROUP_ACT_AS_KEY = new String[][] {
        { ACT_MOUSE_LEFT, "Left mouse button" },
        { ACT_MOUSE_RIGHT, "Right mouse button" },
        { ACT_MOUSE_MIDDLE, "Middle mouse button" },
    };
    public static final String[][] GROUP_POINTER = new String[][] {
        { ACT_MOUSE_SCROLL_UP, "Scroll up" },
        { ACT_MOUSE_SCROLL_DOWN, "Scroll down" },
        { ACT_MOUSE_LEFT, "Left mouse button" },
        { ACT_MOUSE_RIGHT, "Right mouse button" },
        { ACT_MOUSE_MIDDLE, "Middle mouse button" },
    };
    /**
     * Computer — clicks & keys for the machine you are controlling
     * (USB keyboard app, Moonlight, any remote desktop). Not phone UI.
     */
    public static final String[][] GROUP_COMPUTER = new String[][] {
        { ACT_MOUSE_SCROLL_UP, "Scroll up" },
        { ACT_MOUSE_SCROLL_DOWN, "Scroll down" },
        { ACT_MOUSE_LEFT, "Left mouse button" },
        { ACT_MOUSE_RIGHT, "Right mouse button" },
        { ACT_MOUSE_MIDDLE, "Middle mouse button" },
        { ACT_HOST_PREFIX + "up", "Arrow up" },
        { ACT_HOST_PREFIX + "down", "Arrow down" },
        { ACT_HOST_PREFIX + "left", "Arrow left" },
        { ACT_HOST_PREFIX + "right", "Arrow right" },
        { ACT_HOST_PREFIX + "pageup", "Page up" },
        { ACT_HOST_PREFIX + "pagedown", "Page down" },
        { ACT_HOST_PREFIX + "home", "Line home" },
        { ACT_HOST_PREFIX + "end", "Line end" },
        { ACT_HOST_PREFIX + "esc", "Escape" },
        { ACT_HOST_PREFIX + "enter", "Enter" },
        { ACT_HOST_PREFIX + "tab", "Tab" },
        { ACT_HOST_PREFIX + "space", "Space" },
        { ACT_HOST_PREFIX + "backspace", "Backspace" },
        { ACT_HOST_PREFIX + "ctrl+c", "Ctrl + C" },
        { ACT_HOST_PREFIX + "ctrl+v", "Ctrl + V" },
        { ACT_HOST_PREFIX + "ctrl+z", "Ctrl + Z" },
        { ACT_HOST_PREFIX + "ctrl+w", "Ctrl + W" },
        { ACT_HOST_PREFIX + "ctrl+t", "Ctrl + T" },
        { ACT_HOST_PREFIX + "ctrl+f1", "Ctrl + F1" },
        { ACT_HOST_PREFIX + "ctrl+f2", "Ctrl + F2" },
        { ACT_HOST_PREFIX + "ctrl+f3", "Ctrl + F3" },
        { ACT_HOST_PREFIX + "ctrl+f4", "Ctrl + F4" },
        { ACT_HOST_PREFIX + "alt+tab", "Alt + Tab" },
        { ACT_LAYOUT_SPECIALS_TOGGLE, "Specials layout toggle" },
        { ACT_LAYOUT_ARROWS_TOGGLE, "Arrows layout toggle" },
        { ACT_LAYOUT_SPECIALS_HOLD, "Specials while held" },
        { ACT_LAYOUT_ARROWS_HOLD, "Arrows while held" },
        { ACT_LAYOUT_OFF, "Layout off" },
    };
    /** @deprecated use {@link #GROUP_COMPUTER} */
    public static final String[][] GROUP_MOUSE = GROUP_COMPUTER;
    public static final String[][] GROUP_CLEAR = new String[][] {
        { ACT_NONE, "Do nothing" },
        { ACT_DEFAULT, "System default" },
    };
    /**
     * Advanced only: modifiers + side aliases. No second Home/Back/Recents
     * (those live under Navigation as working global actions).
     */
    public static final String[][] GROUP_ADVANCED = new String[][] {
        { "keycode:82", "Menu" },
        { "keycode:84", "Search" },
        { "keycode:57", "Alt" },
        { "keycode:113", "Ctrl" },
        { "keycode:63", "Sym" },
        { ACT_SCAN_PREFIX + SCAN_FN, "As Fn" },
        { ACT_SCAN_PREFIX + SCAN_SYM, "As Sym" },
        { ACT_SCAN_PREFIX + SCAN_ALT, "As Alt" },
        { ACT_SCAN_PREFIX + SCAN_SIDE_FUNC, "As side button · bottom" },
        { ACT_SCAN_PREFIX + SCAN_SIDE_FUNC2, "As side button · top" },
    };

    public static final String[][] GROUP_HOST_LAYOUT = new String[][] {
        { ACT_LAYOUT_SPECIALS_HOLD, "Specials while held" },
        { ACT_LAYOUT_SPECIALS_TOGGLE, "Specials toggle" },
        { ACT_LAYOUT_ARROWS_HOLD, "Arrows while held" },
        { ACT_LAYOUT_ARROWS_TOGGLE, "Arrows toggle" },
        { ACT_LAYOUT_OFF, "Layout off" },
    };

    /** True when any press on this scan is a layout hold/toggle (modifier mode). */
    public boolean isLayoutModifierScan(int scan) {
        int c = canonicalizeScan(scan);
        for (Press pr : Press.values()) {
            Slot sl = slotByScan(c, pr);
            if (sl == null) continue;
            String a = getAction(sl.id);
            if (layoutHoldId(a) != null || layoutToggleId(a) != null) return true;
        }
        return false;
    }

    public static boolean isLayoutAction(String action) {
        return action != null && action.startsWith(ACT_LAYOUT_PREFIX);
    }

    public static boolean isMouseAction(String action) {
        return action != null && action.startsWith(ACT_MOUSE_PREFIX);
    }

    /** Left/right/middle — follow physical key hold. Scroll is still a pulse. */
    public static boolean isMouseButtonAction(String action) {
        return ACT_MOUSE_LEFT.equals(action)
            || ACT_MOUSE_RIGHT.equals(action)
            || ACT_MOUSE_MIDDLE.equals(action);
    }

    /**
     * Act-as-key remap: the physical key <em>is</em> this button/key.
     * Short and long are not separate — down/up follow the hardware press.
     * Double tap is still allowed on the same scan.
     */
    public static boolean isActAsKeyAction(String action) {
        return isMouseButtonAction(action);
    }

    public static boolean isHostAction(String action) {
        return action != null && action.startsWith(ACT_HOST_PREFIX);
    }

    /**
     * Mouse click or host chord for the PC — kept during HID silence.
     * Layout actions are separate ({@link #isLayoutAction}); they stay available
     * via {@link TempKeyMapStack#buildSilenceMap} but are not "computer I/O".
     */
    public static boolean isComputerAction(String action) {
        return isMouseAction(action) || isHostAction(action);
    }

    private final SharedPreferences p;

    public KeyMapPrefs(Context ctx) {
        p = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() { return p.getBoolean("enabled", true); }
    public void setEnabled(boolean v) { p.edit().putBoolean("enabled", v).apply(); }

    /**
     * Long-hold a letter key → Titan specials character without Sym/Alt.
     * Default on. Disable if it fights apps that use long-press.
     */
    /** Default off: IME language long-press steals letter holds when this is on. */
    /**
     * Long-hold letter → specials. <b>Purged 12.04</b> — always off.
     * Timers + DEL-replace fought normal typing (felt like key-hold / stuck keys).
     */
    public boolean isLongPressSpecials() {
        // Force clear sticky prefs from older builds
        if (p.getBoolean("long_press_specials", false)) {
            p.edit().putBoolean("long_press_specials", false).apply();
        }
        return false;
    }
    public void setLongPressSpecials(boolean v) {
        // Ignored — feature removed until a non-intercept path exists
        p.edit().putBoolean("long_press_specials", false).apply();
    }

    /**
     * Global default host layout when sticky toggle is off and per-app inherits.
     * Values: off | specials | arrows
     */
    /**
     * Product default: {@link #SPECIALS_METHOD_KCM} (TitanKey alt: layer).
     * {@link #SPECIALS_METHOD_INJECT} = a11y + KeyActions (clipboard path) —
     * opt-in / per-app override only.
     */
    public static final String SPECIALS_METHOD_INJECT = "inject";
    public static final String SPECIALS_METHOD_KCM = "kcm";

    public static String getSpecialsMethod(Context ctx) {
        String m = AgentBridge.get(ctx, AgentBridge.SPECIALS_METHOD, SPECIALS_METHOD_KCM);
        if (m == null || m.isEmpty()) return SPECIALS_METHOD_KCM;
        m = m.trim().toLowerCase();
        if (SPECIALS_METHOD_INJECT.equals(m)) return SPECIALS_METHOD_INJECT;
        if (SPECIALS_METHOD_KCM.equals(m)) return SPECIALS_METHOD_KCM;
        return SPECIALS_METHOD_KCM;
    }

    public static void setSpecialsMethod(Context ctx, String method) {
        // Product default KCM; only explicit "inject" selects clipboard/a11y path.
        String m = SPECIALS_METHOD_KCM;
        if (method != null && SPECIALS_METHOD_INJECT.equals(method.trim().toLowerCase())) {
            m = SPECIALS_METHOD_INJECT;
        } else if (method != null
                && SPECIALS_METHOD_KCM.equals(method.trim().toLowerCase())) {
            m = SPECIALS_METHOD_KCM;
        }
        AgentBridge.put(ctx, AgentBridge.SPECIALS_METHOD, m);
        // InputPlane SoT + Global so pad-agent rewrites KL (inject → no ALT_RIGHT)
        try {
            com.titanus2.api.InputPlane.put(ctx,
                com.titanus2.api.Titan2ApiContract.FILE_SPECIALS_METHOD, m);
        } catch (Exception ignored) {}
        try {
            ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("specials_method", m).commit();
        } catch (Exception ignored) {}
        // 13.22/13.23/13.86: force pad-agent apply_fn + TitanKey HARD rebind.
        // Soft uevent left EventHub on BUTTON_2 → KCM Sym silent, only free Alt.
        // Always re-put method + char_mod so first UI tap bumps mtime (no 2nd click).
        try {
            AgentBridge.put(ctx, AgentBridge.SPECIALS_METHOD, m);
            // Touch char_mod mtime so agent fn_dirty even if method file matched
            String cm = AgentBridge.get(ctx, AgentBridge.CHAR_MOD, "sym");
            if (cm == null || cm.isEmpty()) cm = "sym";
            AgentBridge.put(ctx, AgentBridge.CHAR_MOD, cm);
            // One-shot: ensure Global mirror (AgentBridge plane list omitted method)
            try {
                android.provider.Settings.Global.putString(
                    ctx.getContentResolver(), AgentBridge.SPECIALS_METHOD, m);
            } catch (Exception ignored2) {}
        } catch (Exception ignored) {}
    }

    public static boolean isSpecialsInjectMethod(Context ctx) {
        return SPECIALS_METHOD_INJECT.equals(getSpecialsMethod(ctx));
    }

    public String getHostLayoutDefault() {
        return p.getString("host_layout_default", HostLayoutController.MODE_OFF);
    }
    public void setHostLayoutDefault(String mode) {
        p.edit().putString("host_layout_default",
            HostLayoutController.normalize(mode)).apply();
    }

    /** User override glyph for specials layout, or null = Titan default. Empty/"-" = unmapped. */
    public String getSpecialsOverride(int keyCode) {
        if (!p.contains("sp_" + keyCode)) return null;
        return p.getString("sp_" + keyCode, null);
    }
    public void setSpecialsOverride(int keyCode, String glyph) {
        if (glyph == null) {
            p.edit().remove("sp_" + keyCode).apply();
        } else {
            p.edit().putString("sp_" + keyCode, glyph).apply();
        }
    }
    public void clearSpecialsOverrides() {
        SharedPreferences.Editor e = p.edit();
        for (String k : p.getAll().keySet()) {
            if (k != null && k.startsWith("sp_")) e.remove(k);
        }
        e.apply();
    }

    /**
     * Arrows override: null = default, 0 = unmapped, else Android keycode to emit.
     */
    public Integer getArrowsOverride(int keyCode) {
        if (!p.contains("ar_" + keyCode)) return null;
        return p.getInt("ar_" + keyCode, 0);
    }
    public void setArrowsOverride(int keyCode, Integer outKeyCode) {
        if (outKeyCode == null) {
            p.edit().remove("ar_" + keyCode).apply();
        } else {
            p.edit().putInt("ar_" + keyCode, outKeyCode).apply();
        }
    }
    public void clearArrowsOverrides() {
        SharedPreferences.Editor e = p.edit();
        for (String k : p.getAll().keySet()) {
            if (k != null && k.startsWith("ar_")) e.remove(k);
        }
        e.apply();
    }

    /**
     * When true, pad-agent fires remaps while the screen is off (privileged path;
     * a11y does not receive keys with the display off). Default on.
     */
    public boolean isAllowScreenOff() { return p.getBoolean("allow_screen_off", true); }
    public void setAllowScreenOff(boolean v) {
        p.edit().putBoolean("allow_screen_off", v).apply();
    }

    public String getAction(String slotId) {
        String a = p.getString("slot_" + slotId, factoryDefault(slotId));
        // P0 B1: sides must never be system Home/Recents/Back (stock or bad import).
        if (isSideSlotId(slotId) && isSystemChromeAction(a)) {
            String fix = factoryDefault(slotId);
            try {
                p.edit().putString("slot_" + slotId, fix).apply();
            } catch (Exception ignored) {}
            return fix;
        }
        return a;
    }

    public void setAction(String slotId, String action) {
        if (isSideSlotId(slotId) && isSystemChromeAction(action)) {
            action = factoryDefault(slotId);
        }
        if (isActAsKeyAction(action) && slotId != null) {
            for (Slot s : SLOTS) {
                if (s.id.equals(slotId) && s.press != Press.DOUBLE) {
                    assignActAsKey(s.scan, action);
                    return;
                }
            }
        }
        p.edit().putString("slot_" + slotId, action == null ? ACT_DEFAULT : action).apply();
    }

    /**
     * B1 11.87: rewrite any side slot still holding system chrome (or empty
     * poison) to factory defaults. Called from a11y ensure belt / wipe heal.
     */
    public void healSideChromeToFactory() {
        SharedPreferences.Editor ed = p.edit();
        boolean dirty = false;
        for (Slot s : SLOTS) {
            if (!isSideSlotId(s.id)) continue;
            String cur = p.getString("slot_" + s.id, factoryDefault(s.id));
            if (cur == null || cur.isEmpty() || isSystemChromeAction(cur)) {
                ed.putString("slot_" + s.id, factoryDefault(s.id));
                dirty = true;
            }
        }
        if (dirty) {
            try { ed.apply(); } catch (Exception ignored) {}
        }
    }

    /** Side · bottom/top short/long/double — never system chrome (B1 invariant). */
    public static boolean isSideSlotId(String slotId) {
        return slotId != null && (slotId.startsWith("side_func_")
            || slotId.startsWith("side_func2_"));
    }

    /**
     * Home / Recents / Back / Assistant / Power / Camera — not allowed on
     * physical side buttons (B1). Stock mtk-kpd maps 249/250 → CAMERA; poison
     * plane or old prefs must never open Camera/Home from the side rail.
     * Accepts pad-agent / stock aliases (app_switch, power_dialog).
     */
    public static boolean isSystemChromeAction(String action) {
        if (action == null || action.isEmpty()) return false;
        String a = action.trim().toLowerCase(java.util.Locale.US);
        switch (a) {
            case ACT_HOME:
            case ACT_RECENTS:
            case ACT_BACK:
            case ACT_ASSIST:
            case ACT_POWER_DIALOG:
            case ACT_CAMERA:
            case "power_dialog":
            case "app_switch":
            case "appswitch":
            case "app-switch":
            case "recent":
            case "overview":
            case "keycode_camera":
            case "keycode:camera":
            case "key_camera":
            // B1 11.52: stock/pad-agent KEYCODE_* and nav aliases (poison plane)
            case "keycode_home":
            case "keycode:home":
            case "key_home":
            case "sys_home":
            case "nav_home":
            case "gesture_home":
            case "keycode_app_switch":
            case "keycode:app_switch":
            case "keycode_back":
            case "keycode:back":
            case "key_back":
            case "keycode_assistant":
            case "keycode:assistant":
            case "keycode_power":
            case "keycode:power":
            case "key_power":
            // B1 12.30: stock/mtk plane aliases still seen post-wipe / old agent seed
            case "sys_camera":
            case "stock_camera":
            case "mtk_camera":
            case "button_camera":
            // B1 13.19: pad-agent / stock poison still seen as open_camera etc.
            case "open_camera":
            case "start_camera":
            case "capture":
            case "button_1":
            case "button_2":
            case "button_3":
            case "button_4":
            case "keycode_button_1":
            case "keycode_button_2":
            case "keycode_menu":
            case "keycode:menu":
            case "key_menu":
            case "menu":
            case "sys_recents":
            case "sys_overview":
                return true;
            default:
                // keycode:NNN numeric chrome (HOME=3, BACK=4, APP_SWITCH=187,
                // POWER=26, CAMERA=27)
                if (a.startsWith("keycode:") || a.startsWith("keycode_")) {
                    String n = a.contains(":")
                        ? a.substring(a.indexOf(':') + 1)
                        : a.substring("keycode_".length());
                    if ("3".equals(n) || "4".equals(n) || "187".equals(n)
                            || "26".equals(n) || "27".equals(n)) {
                        return true;
                    }
                }
                return false;
        }
    }

    /**
     * Factory default for a slot.
     * Recents physical key (scan 580): short=Home, long=Recents — owned by
     * Controls a11y GLOBAL_ACTION (hybrid KL is F24, not APP_SWITCH).
     * Back short → nav Back (Controls inject) — product default Titan keys.
     * Fn stays off the list (layout: Fn as Ctrl).
     */
    public static String factoryDefault(String slotId) {
        if (slotId == null) return ACT_DEFAULT;
        switch (slotId) {
            case "back_short":
                return ACT_BACK; // short Back = system nav Back (not keylayout-only pass-through)
            case "recents_short":
                return ACT_HOME; // short = Home (launcher)
            case "recents_long":
                return ACT_RECENTS; // long-press = Recents
            case "back_long":
            case "back_double":
            case "recents_double": return ACT_NONE;
            // Side · all none (13.48 product): specials = hold Sym (CHAR_MOD), not
            // side-rail layout holds. 13.28 forced layout:specials_hold on long —
            // that fought hold-Sym and lifecycle belts re-published it forever.
            // Short stays none so sides never system Home (B1).
            case "side_func_long":
            case "side_func_double":
            case "side_func2_long":
            case "side_func2_double":
            case "side_func_short":
            case "side_func2_short": return ACT_NONE;
            // Fn / Sym / Alt — not factory shortcuts
            case "fn_short":
            case "fn_long":
            case "fn_double": return ACT_NONE;
            default: return ACT_DEFAULT;
        }
    }

    public boolean isFactoryDefault(String slotId) {
        String act = getAction(slotId);
        return factoryDefault(slotId).equals(act);
    }

    /**
     * Side plane migrate (name kept for call sites).
     * <p>
     * 13.48 {@code side_defaults_sym_hold_v4}: product specials = <b>hold Sym</b>
     * (inject), not side layout holds. One-shot: strip 13.28 factory layout
     * binds we forced (specials/arrows hold+toggle) back to {@code none}.
     * User custom non-factory actions are left alone. Chrome → none via
     * {@link #healSideChromeToFactory()}.
     */
    /**
     * 15.25: product short Back = nav Back. Prior factory was ACT_DEFAULT
     * (pass-through only) so exclusive HID / unmapped KL left Back dead.
     * One-shot: upgrade unset or still-default to {@link #ACT_BACK}; leave
     * user custom actions alone.
     */
    public void migrateBackShortNavDefault() {
        if (p.getBoolean("back_short_nav_v1", false)) return;
        SharedPreferences.Editor ed = p.edit();
        String cur = p.getString("slot_back_short", null);
        if (cur == null || cur.isEmpty() || ACT_DEFAULT.equals(cur)) {
            ed.putString("slot_back_short", ACT_BACK);
        }
        ed.putBoolean("back_short_nav_v1", true);
        try { ed.apply(); } catch (Exception ignored) {}
    }

    public void migrateSideDefaultsNone() {
        migrateBackShortNavDefault();
        SharedPreferences.Editor ed = p.edit();
        boolean dirty = false;
        // v4: undo our own 13.28 layout factory if still stock factory binds
        if (!p.getBoolean("side_defaults_sym_hold_v4", false)) {
            String[][] factoryLayout = {
                { "side_func_long", ACT_LAYOUT_SPECIALS_HOLD },
                { "side_func_double", ACT_LAYOUT_SPECIALS_TOGGLE },
                { "side_func2_long", ACT_LAYOUT_ARROWS_HOLD },
                { "side_func2_double", ACT_LAYOUT_ARROWS_TOGGLE },
            };
            for (String[] pair : factoryLayout) {
                String cur = p.getString("slot_" + pair[0], null);
                if (cur == null || cur.isEmpty() || pair[1].equals(cur)) {
                    ed.putString("slot_" + pair[0], ACT_NONE);
                    dirty = true;
                }
            }
            for (String id : new String[] { "side_func_short", "side_func2_short" }) {
                String cur = p.getString("slot_" + id, null);
                if (cur == null || cur.isEmpty() || isSystemChromeAction(cur)) {
                    ed.putString("slot_" + id, ACT_NONE);
                    dirty = true;
                }
            }
            ed.putBoolean("side_defaults_none_v1", true);
            ed.putBoolean("side_defaults_none_v2", true);
            ed.putBoolean("side_defaults_layout_v3", true); // mark old migrate done
            ed.putBoolean("side_defaults_sym_hold_v4", true);
            dirty = true;
        }
        // Always strip chrome poison (B1)
        for (Slot s : SLOTS) {
            if (!isSideSlotId(s.id)) continue;
            String cur = p.getString("slot_" + s.id, ACT_NONE);
            if (cur != null && isSystemChromeAction(cur)) {
                ed.putString("slot_" + s.id, ACT_NONE);
                dirty = true;
            }
        }
        if (dirty) {
            try { ed.apply(); } catch (Exception ignored) {}
        }
    }

    /**
     * Publish only side slots + specials method (cheap post-migrate plane heal).
     */
    public void publishSidesAndSpecialsMethod(Context ctx) {
        if (ctx == null) return;
        String[] sides = {
            "side_func_short", "side_func_long", "side_func_double",
            "side_func2_short", "side_func2_long", "side_func2_double"
        };
        for (String id : sides) {
            try {
                AgentBridge.put(ctx, "titan2_km_" + id, getAction(id));
            } catch (Exception ignored) {}
        }
        try {
            AgentBridge.put(ctx, AgentBridge.SPECIALS_METHOD, getSpecialsMethod(ctx));
        } catch (Exception ignored) {}
    }

    /**
     * 13.48: only heal when plane has chrome poison or empty — never treat
     * user {@code none} vs old layout seed as “must re-force specials_hold”.
     */
    public boolean sideAgentPlaneNeedsChromeHeal(Context ctx) {
        if (ctx == null) return false;
        String[] sides = {
            "side_func_short", "side_func_long", "side_func_double",
            "side_func2_short", "side_func2_long", "side_func2_double"
        };
        for (String id : sides) {
            String key = "titan2_km_" + id;
            String have = null;
            try {
                have = AgentBridge.get(ctx, key, null);
            } catch (Exception ignored) {}
            if (have == null || have.isEmpty() || isSystemChromeAction(have)) {
                return true;
            }
            try {
                String g = android.provider.Settings.Global.getString(
                    ctx.getContentResolver(), key);
                if (g != null && isSystemChromeAction(g.trim())) return true;
            } catch (Exception ignored) {}
        }
        try {
            String sm = AgentBridge.get(ctx, AgentBridge.SPECIALS_METHOD, null);
            if (sm == null || sm.isEmpty()) return true;
        } catch (Exception ignored) {
            return true;
        }
        return false;
    }

    /**
     * @deprecated 13.48 — use {@link #sideAgentPlaneNeedsChromeHeal}; full CE/plane
     * diverge re-publish re-forced layout specials over hold-Sym product.
     */
    @Deprecated
    public boolean sideAgentPlaneDiverged(Context ctx) {
        return sideAgentPlaneNeedsChromeHeal(ctx);
    }

    /**
     * Shortcuts list: any press with a real action (not none, not pure system
     * default). Factory upper-row nav (Back / Recents / Home / Assistant)
     * appears until removed. Side keys appear only after the user assigns them.
     */
    public List<Slot> listVisibleSlots() {
        List<Slot> out = new ArrayList<>();
        for (Slot s : SLOTS) {
            String act = getAction(s.id);
            if (ACT_NONE.equals(act) || ACT_DEFAULT.equals(act)) continue;
            out.add(s);
        }
        return out;
    }

    /**
     * One UI row for a layout-modifier key (all presses on that scan collapsed).
     * {@code single} is set for normal shortcuts; layoutId set for modifiers.
     */
    public static final class ShortcutRow {
        public final int scan;
        public final String layoutId; // null = normal
        public final Slot single;     // null when layout modifier
        public final Slot longSlot;
        public final Slot doubleSlot;
        public ShortcutRow(int scan, String layoutId, Slot longSlot, Slot doubleSlot) {
            this.scan = scan;
            this.layoutId = layoutId;
            this.single = null;
            this.longSlot = longSlot;
            this.doubleSlot = doubleSlot;
        }
        public ShortcutRow(Slot single) {
            this.scan = single.scan;
            this.layoutId = null;
            this.single = single;
            this.longSlot = null;
            this.doubleSlot = null;
        }
        public boolean isLayoutModifier() {
            return layoutId != null;
        }
    }

    /**
     * Human mode line from resolved hold/toggle actions (B3).
     * full match → "hold + double"; hold-only / toggle-only otherwise.
     */
    public static String layoutModeLine(String holdAct, String toggleAct) {
        String hid = layoutHoldId(holdAct);
        String tid = layoutToggleId(toggleAct);
        if (hid != null && tid != null) {
            if (hid.equals(tid)) return "hold + double";
            return "hold + double · mixed";
        }
        if (hid != null) return "hold";
        if (tid != null) return "double";
        return "modifier";
    }

    /**
     * Resolve layout id for a scan from hold (preferred) or toggle action.
     */
    public static String layoutIdFromActions(String holdAct, String toggleAct) {
        String hid = layoutHoldId(holdAct);
        if (hid != null) return hid;
        return layoutToggleId(toggleAct);
    }

    /**
     * Visible shortcut rows with <b>one row per layout-modifier scan</b> (B3).
     * Any hold and/or toggle layout action on a physical key collapses to a
     * single "Layout · name" entry — never separate long + double tiles.
     */
    public List<ShortcutRow> listVisibleRows() {
        List<ShortcutRow> rows = new ArrayList<>();
        java.util.HashSet<Integer> layoutScans = new java.util.HashSet<>();
        java.util.LinkedHashSet<Integer> order = new java.util.LinkedHashSet<>();
        // Collect scans that own any layout hold/toggle
        for (Slot s : SLOTS) {
            String act = getAction(s.id);
            if (layoutHoldId(act) != null || layoutToggleId(act) != null) {
                order.add(canonicalizeScan(s.scan));
            }
        }
        for (int c : order) {
            Slot lo = slotByScan(c, Press.LONG);
            Slot db = slotByScan(c, Press.DOUBLE);
            String holdAct = lo != null ? getAction(lo.id) : null;
            String togAct = db != null ? getAction(db.id) : null;
            String id = layoutIdFromActions(holdAct, togAct);
            if (id == null) continue;
            Slot loOut = (lo != null && layoutHoldId(holdAct) != null) ? lo : null;
            Slot dbOut = (db != null && layoutToggleId(togAct) != null) ? db : null;
            layoutScans.add(c);
            rows.add(new ShortcutRow(c, id, loOut, dbOut));
        }
        // Non-layout shortcuts (short/long/double that are not layout actions).
        // Act-as-key remaps occupy short; long is blocked — do not list it.
        for (Slot s : SLOTS) {
            int c = canonicalizeScan(s.scan);
            if (layoutScans.contains(c)) continue;
            String act = getAction(s.id);
            if (ACT_NONE.equals(act) || ACT_DEFAULT.equals(act)) continue;
            if (layoutHoldId(act) != null || layoutToggleId(act) != null) continue;
            if (s.press == Press.LONG) {
                Slot sh = slotByScan(c, Press.SHORT);
                if (sh != null && isActAsKeyAction(getAction(sh.id))) continue;
            }
            rows.add(new ShortcutRow(s));
        }
        return rows;
    }

    /** Clear layout-modifier mode (short/long/double) for a scan. */
    public void clearLayoutModifier(int scan) {
        int c = canonicalizeScan(scan);
        SharedPreferences.Editor ed = p.edit();
        for (Press pr : Press.values()) {
            Slot sl = slotByScan(c, pr);
            if (sl == null) continue;
            // Reset to factory (sides → none / layout defaults, etc.)
            ed.remove("slot_" + sl.id);
        }
        ed.apply();
    }

    public static boolean isSideScan(int scan) {
        int c = canonicalizeScan(scan);
        return c == SCAN_SIDE_FUNC || c == SCAN_SIDE_FUNC2;
    }

    public static boolean isUpperNavScan(int scan) {
        int c = canonicalizeScan(scan);
        return c == SCAN_BACK || c == SCAN_APP_SWITCH || c == SCAN_FN;
    }

    /** Physical key + press, secondary line under action-first labels. */
    public static String keyPressLabel(Slot slot) {
        if (slot == null) return "";
        String key;
        switch (canonicalizeScan(slot.scan)) {
            case SCAN_SIDE_FUNC: key = "Side button · bottom"; break;
            case SCAN_SIDE_FUNC2: key = "Side button · top"; break;
            case SCAN_BACK: key = "Back key"; break;
            case SCAN_APP_SWITCH: key = "Recents key"; break;
            case SCAN_FN: key = "Fn"; break;
            case SCAN_SYM: key = "Sym"; break;
            case SCAN_ALT: key = "Alt"; break;
            default: key = "Key " + slot.scan; break;
        }
        String pr;
        switch (slot.press) {
            case LONG: pr = "long press"; break;
            case DOUBLE: pr = "double tap"; break;
            default: pr = "press"; break;
        }
        return key + " · " + pr;
    }

    /** Same as {@link #keyPressLabel} but “act as key” when short is a remap. */
    public String slotPressLabel(Slot slot) {
        if (slot != null && slot.press == Press.SHORT
                && isActAsKeyAction(getAction(slot.id))) {
            return keyPressLabel(slot).replace(" · press", " · act as key");
        }
        return keyPressLabel(slot);
    }

    /** Remove shortcut: do nothing on that press (user can re-add later). */
    public void removeSlot(String slotId) {
        p.edit()
            .putString("slot_" + slotId, ACT_NONE)
            .remove("slot_" + slotId + "_label")
            .apply();
    }

    /** Restore Titan factory default for this slot. */
    public void resetSlot(String slotId) {
        p.edit().remove("slot_" + slotId).remove("slot_" + slotId + "_label").apply();
    }

    public boolean isRemoved(String slotId) {
        return ACT_NONE.equals(getAction(slotId));
    }

    public String getAppLabel(String slotId) {
        return p.getString("slot_" + slotId + "_label", "");
    }

    public void setAppLabel(String slotId, String label) {
        p.edit().putString("slot_" + slotId + "_label", label == null ? "" : label).apply();
    }

    public static String actionLabel(String action) {
        if (action == null || ACT_DEFAULT.equals(action)) return "System default";
        if (action.startsWith(ACT_APP_PREFIX)) {
            String rest = action.substring(ACT_APP_PREFIX.length());
            return "App: " + rest;
        }
        if (isMouseAction(action)) {
            switch (action) {
                case ACT_MOUSE_LEFT: return "Left mouse button";
                case ACT_MOUSE_RIGHT: return "Right mouse button";
                case ACT_MOUSE_MIDDLE: return "Middle mouse button";
                case ACT_MOUSE_SCROLL_UP: return "Scroll up";
                case ACT_MOUSE_SCROLL_DOWN: return "Scroll down";
                default:
                    if (action.startsWith(ACT_MOUSE_WHEEL_PREFIX)) {
                        return "Wheel " + action.substring(ACT_MOUSE_WHEEL_PREFIX.length()).trim();
                    }
                    return "Click";
            }
        }
        if (isLayoutAction(action)) {
            if (ACT_LAYOUT_OFF.equals(action)) return "Layout off";
            String hid = layoutHoldId(action);
            if (hid != null) {
                if ("specials".equals(hid)) return "Layout hold · Specials";
                if ("arrows".equals(hid)) return "Layout hold · Arrows";
                return "Layout hold · " + hid;
            }
            String tid = layoutToggleId(action);
            if (tid != null) {
                if ("specials".equals(tid)) return "Layout toggle · Specials";
                if ("arrows".equals(tid)) return "Layout toggle · Arrows";
                return "Layout toggle · " + tid;
            }
            for (String[] a : GROUP_HOST_LAYOUT) {
                if (a[0].equals(action)) return a[1];
            }
            return "Layout";
        }
        if (isHostAction(action)) {
            String rest = action.substring(ACT_HOST_PREFIX.length()).trim();
            if (rest.isEmpty()) return "Computer key";
            // host:ctrl+f1 → Ctrl + F1
            String[] parts = rest.split("\\+");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) sb.append(" + ");
                String p = parts[i].trim();
                if (p.isEmpty()) continue;
                if (p.length() == 1) sb.append(p.toUpperCase());
                else if (p.length() == 2 && p.regionMatches(true, 0, "f", 0, 1)
                        && Character.isDigit(p.charAt(1))) {
                    sb.append("F").append(p.substring(1));
                } else if (p.length() > 2 && p.regionMatches(true, 0, "f", 0, 1)) {
                    boolean allDigit = true;
                    for (int j = 1; j < p.length(); j++) {
                        if (!Character.isDigit(p.charAt(j))) { allDigit = false; break; }
                    }
                    if (allDigit) sb.append("F").append(p.substring(1));
                    else sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
                } else {
                    sb.append(Character.toUpperCase(p.charAt(0)));
                    if (p.length() > 1) sb.append(p.substring(1));
                }
            }
            return sb.length() > 0 ? sb.toString() : rest;
        }
        if (action.startsWith(ACT_KEYCODE_PREFIX)) {
            return "Key " + action.substring(ACT_KEYCODE_PREFIX.length());
        }
        if (action.startsWith(ACT_SCAN_PREFIX)) {
            return "Scan " + action.substring(ACT_SCAN_PREFIX.length());
        }
        for (String[] a : ACTIONS) {
            if (a[0].equals(action)) return a[1];
        }
        return action;
    }

    public static Slot slotByScan(int scan, Press press) {
        int canon = canonicalizeScan(scan);
        for (Slot s : SLOTS) {
            if (canonicalizeScan(s.scan) == canon && s.press == press) return s;
        }
        return null;
    }

    /** Map alternate Fn/Sym reports to primary. */
    public static int canonicalizeScan(int scan) {
        if (scan == SCAN_FN_ALT) return SCAN_FN;
        if (scan == SCAN_SYM_ALT) return SCAN_SYM;
        return scan;
    }

    /**
     * Chord / capture identity. Prefer Linux scan. When a11y delivers
     * scan 0 (letters often), use {@code 10000 + keyCode} so Alt+letter
     * can be saved and matched. Never collide with Titan scans (≤580).
     */
    public static final int CHORD_KEYCODE_BASE = 10_000;

    public static int chordId(int scan, int keyCode) {
        // Letters/digits: always keyCode so scan-0 a11y and TitanKey evdev match.
        if (isLetterOrDigitKeyCode(keyCode)) {
            return CHORD_KEYCODE_BASE + keyCode;
        }
        int c = canonicalizeScan(scan);
        if (c > 0) return c;
        if (keyCode > 0 && keyCode != android.view.KeyEvent.KEYCODE_UNKNOWN) {
            return CHORD_KEYCODE_BASE + keyCode;
        }
        return 0;
    }

    public static boolean isLetterOrDigitKeyCode(int keyCode) {
        return (keyCode >= android.view.KeyEvent.KEYCODE_A
                && keyCode <= android.view.KeyEvent.KEYCODE_Z)
            || (keyCode >= android.view.KeyEvent.KEYCODE_0
                && keyCode <= android.view.KeyEvent.KEYCODE_9);
    }

    public static boolean isChordKeyCodeId(int id) {
        return id >= CHORD_KEYCODE_BASE;
    }

    public static int keyCodeFromChordId(int id) {
        return isChordKeyCodeId(id) ? id - CHORD_KEYCODE_BASE : 0;
    }

    /** Implied partner scan from KeyEvent meta (Alt/Sym/Ctrl). 0 if none. */
    public static int metaChordScan(int metaState) {
        if ((metaState & android.view.KeyEvent.META_ALT_ON) != 0) return SCAN_ALT;
        if ((metaState & android.view.KeyEvent.META_SYM_ON) != 0) return SCAN_SYM;
        if ((metaState & android.view.KeyEvent.META_CTRL_ON) != 0) return SCAN_FN;
        return 0;
    }

    public static boolean isManagedScan(int scan) {
        int c = canonicalizeScan(scan);
        for (Slot s : SLOTS) if (canonicalizeScan(s.scan) == c) return true;
        return false;
    }

    public static boolean isFnScan(int scan) {
        int c = canonicalizeScan(scan);
        return c == SCAN_FN;
    }

    public static boolean isSymScan(int scan) {
        return canonicalizeScan(scan) == SCAN_SYM;
    }

    public static boolean isAltScan(int scan) {
        return canonicalizeScan(scan) == SCAN_ALT;
    }

    public static boolean isBackScan(int scan) {
        return canonicalizeScan(scan) == SCAN_BACK;
    }

    public static boolean isRecentsScan(int scan) {
        return canonicalizeScan(scan) == SCAN_APP_SWITCH;
    }

    /**
     * Resolve specials-owner scan (OEM dual reports expanded by
     * {@link #matchesSpecialsScan}).
     * <p>Named {@link AgentBridge#CHAR_MOD} (alt|sym|fn) wins unless
     * char_mod is {@code custom}, or a numeric {@link AgentBridge#CHAR_MOD_SCAN}
     * is newer than char_mod (Other… pick). Stale scan leftovers (e.g. 251)
     * must not keep specials on Fn after the user picks Alt.
     */
    public static int resolveSpecialsScan(Context ctx) {
        String cm = AgentBridge.get(ctx, AgentBridge.CHAR_MOD, "sym");
        if (cm == null) cm = "sym";
        cm = cm.trim().toLowerCase();

        String raw = AgentBridge.get(ctx, AgentBridge.CHAR_MOD_SCAN, null);
        int scan = 0;
        if (raw != null) {
            raw = raw.trim();
            if (!raw.isEmpty() && !AgentBridge.isClearToken(raw)) {
                try {
                    int s = Integer.parseInt(raw);
                    if (s > 0) scan = s;
                } catch (NumberFormatException ignored) {}
            }
        }

        if (scan > 0) {
            if ("custom".equals(cm) || "scan".equals(cm) || "other".equals(cm)) {
                return scan;
            }
            long scMt = AgentBridge.newestMtime(ctx, AgentBridge.CHAR_MOD_SCAN);
            long cmMt = AgentBridge.newestMtime(ctx, AgentBridge.CHAR_MOD);
            // Other… wrote scan after named preset → honor custom key
            if (scMt > cmMt) return scan;
        }

        switch (cm) {
            case "fn":
            case "function":
                return SCAN_FN_ALT; // OEM primary Fn report
            case "alt":
            case "stock":
                return SCAN_ALT;
            case "custom":
            case "scan":
            case "other":
                return scan > 0 ? scan : SCAN_SYM_ALT;
            case "sym":
            case "symbol":
            default:
                return SCAN_SYM_ALT; // OEM Sym
        }
    }

    /** True if scan is the specials key (incl. Fn/Sym dual reports). */
    public static boolean matchesSpecialsScan(int scan, int specialsScan) {
        if (specialsScan <= 0) return false;
        int c = canonicalizeScan(scan);
        int s = canonicalizeScan(specialsScan);
        if (c == s || scan == specialsScan) return true;
        // Family match only when specials is a known family member
        if (isFnScan(specialsScan) && isFnScan(scan)) return true;
        if (isSymScan(specialsScan) && isSymScan(scan)) return true;
        return false;
    }

    /**
     * True when this scan is the active special-character modifier
     * (must not be stolen by press-shortcuts).
     */
    public static boolean isCharModScan(Context ctx, int scan) {
        return matchesSpecialsScan(scan, resolveSpecialsScan(ctx));
    }

    /**
     * Persist specials owner: named preset and/or raw scan.
     * Named presets clear CHAR_MOD_SCAN so a stale scan (e.g. leftover 251)
     * cannot keep specials on Fn after the user picks Alt/Sym.
     */
    public static void setSpecialsOwner(Context ctx, String named, int scan) {
        // Minimize control-plane writes: each put/clear bumps mtime and used to
        // fire multiple pad-agent rebinds (UI restart under multi-agent).
        // Clear scan only when a real custom scan is present; write CHAR_MOD last.
        String prev = AgentBridge.get(ctx, AgentBridge.CHAR_MOD, "sym");
        if (prev == null) prev = "sym";
        prev = prev.trim().toLowerCase();

        String existingScan = AgentBridge.get(ctx, AgentBridge.CHAR_MOD_SCAN, null);
        boolean hasScan = existingScan != null && !existingScan.isEmpty()
            && !AgentBridge.isClearToken(existingScan);

        if (scan > 0) {
            if (isFnScan(scan)) {
                if (hasScan) AgentBridge.clear(ctx, AgentBridge.CHAR_MOD_SCAN);
                AgentBridge.put(ctx, AgentBridge.FN_MODE, "stock");
                AgentBridge.put(ctx, AgentBridge.CHAR_MOD, "fn");
            } else if (isAltScan(scan)) {
                if (hasScan) AgentBridge.clear(ctx, AgentBridge.CHAR_MOD_SCAN);
                if ("fn".equals(prev) || "function".equals(prev)) {
                    AgentBridge.put(ctx, AgentBridge.FN_MODE, "ctrl");
                }
                AgentBridge.put(ctx, AgentBridge.CHAR_MOD, "alt");
            } else if (isSymScan(scan)) {
                if (hasScan) AgentBridge.clear(ctx, AgentBridge.CHAR_MOD_SCAN);
                if ("fn".equals(prev) || "function".equals(prev)) {
                    AgentBridge.put(ctx, AgentBridge.FN_MODE, "ctrl");
                }
                AgentBridge.put(ctx, AgentBridge.CHAR_MOD, "sym");
            } else {
                AgentBridge.put(ctx, AgentBridge.CHAR_MOD_SCAN, String.valueOf(scan));
                AgentBridge.put(ctx, AgentBridge.CHAR_MOD, "custom");
            }
        } else if (named != null) {
            String n = named.trim().toLowerCase();
            if (hasScan) AgentBridge.clear(ctx, AgentBridge.CHAR_MOD_SCAN);
            if ("fn".equals(n) || "function".equals(n)) {
                AgentBridge.put(ctx, AgentBridge.FN_MODE, "stock");
                AgentBridge.put(ctx, AgentBridge.CHAR_MOD, "fn");
            } else if ("alt".equals(n) || "stock".equals(n)) {
                if ("fn".equals(prev) || "function".equals(prev)) {
                    AgentBridge.put(ctx, AgentBridge.FN_MODE, "ctrl");
                }
                AgentBridge.put(ctx, AgentBridge.CHAR_MOD, "alt");
            } else {
                if ("fn".equals(prev) || "function".equals(prev)) {
                    AgentBridge.put(ctx, AgentBridge.FN_MODE, "ctrl");
                }
                AgentBridge.put(ctx, AgentBridge.CHAR_MOD, "sym");
            }
        }
    }

    public Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Slot s : SLOTS) m.put(s.id, getAction(s.id));
        return m;
    }

    /**
     * Export maps for pad-agent privileged key path (screen-off remaps).
     * Publishes all slots so Back/Recents/Fn/sides work with display off.
     * Honors {@link TempKeyMapStack} so HID/third-party temp layers stay on top.
     */
    public void publishToAgent(Context ctx) {
        // Heal side slots before pad-agent / a11y read the plane (wipe imports).
        for (Slot s : SLOTS) {
            if (isSideSlotId(s.id)) getAction(s.id);
        }
        try {
            new TempKeyMapStack(ctx).publishEffective(ctx, this);
        } catch (Exception e) {
            for (Slot s : SLOTS) {
                AgentBridge.put(ctx, "titan2_km_" + s.id, getAction(s.id));
            }
            AgentBridge.put(ctx, "titan2_km_enabled", isEnabled() ? "1" : "0");
            AgentBridge.put(ctx, "titan2_km_screen_off", isAllowScreenOff() ? "1" : "0");
        }
    }

    /** Reset all slots to OEM factory defaults (upper-row nav). */
    public void resetFactoryDefaults() {
        SharedPreferences.Editor ed = p.edit();
        for (Slot s : SLOTS) {
            ed.remove("slot_" + s.id).remove("slot_" + s.id + "_label");
        }
        ed.apply();
    }
}
