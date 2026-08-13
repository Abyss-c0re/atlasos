package com.titanus2.api;

/**
 * Shared Messenger contract for the Titan2 framework API.
 * <p>
 * Host: {@code com.titanus2.controls/.Titan2CoreService} (privileged ROM app).
 * Clients: Titan USB HID and third-party apps with
 * {@link #PERMISSION_USE} (signature|privileged on ROM).
 * <p>
 * Not an AOSP framework.jar — both APKs compile these sources (no AIDL tooling).
 * See {@code docs/project/TITAN2_FRAMEWORK_API.md}.
 */
public final class Titan2ApiContract {
    private Titan2ApiContract() {}

    public static final String CONTROLS_PKG = "com.titanus2.controls";
    public static final String SERVICE_CLASS = "com.titanus2.controls.Titan2CoreService";
    public static final String ACTION_BIND = "com.titanus2.api.BIND";

    /** Apps that call the framework must hold this (declared in Controls + clients). */
    public static final String PERMISSION_USE = "com.titanus2.permission.USE_TITAN2_API";

    // --- Message what (client → service) ---
    public static final int MSG_PING = 1;
    public static final int MSG_GET_PAD_MODE = 10;
    public static final int MSG_SET_PAD_MODE = 11;
    public static final int MSG_GET_FOLLOW_ORIENT = 12;
    public static final int MSG_SET_FOLLOW_ORIENT = 13;
    public static final int MSG_PUBLISH_ROTATION = 14;
    public static final int MSG_GET_PAD_EPOCH = 15;

    public static final int MSG_GET_KEY_ACTION = 20;
    public static final int MSG_SET_KEY_ACTION = 21;
    public static final int MSG_PUSH_TEMP_KEYMAP = 22;
    public static final int MSG_POP_TEMP_KEYMAP = 23;
    public static final int MSG_LIST_TEMP_LAYERS = 24;
    public static final int MSG_PUBLISH_KEYMAP = 25;
    public static final int MSG_GET_EFFECTIVE_KEY_ACTION = 26;
    /**
     * Server builds a temp layer that sets every Controls remap slot to
     * {@code none} so phone shortcuts do not fight HID host input.
     * Permanent KeyMapPrefs are unchanged; pop restores them.
     */
    public static final int MSG_SILENCE_KEY_REMAPS = 27;
    /**
     * Ensure a {@link #KEY_PKG} per-app keymap profile exists (label optional).
     * HID uses {@link #HID_HOST_PKG} so Controls Keys UI shows a real app profile.
     */
    public static final int MSG_ENSURE_KEYMAP_PROFILE = 28;
    /** Return sparse slot→action map for {@link #KEY_PKG} profile. */
    public static final int MSG_GET_PROFILE_MAP = 29;
    /**
     * Re-push {@link #LAYER_HID_HOST} from the HID host profile while a session
     * is live (after Controls or API edits). No-op if layer not active.
     */
    public static final int MSG_REFRESH_HID_HOST_LAYER = 35;

    public static final int MSG_GET_LED_LEVEL = 30;
    public static final int MSG_SET_LED_LEVEL = 31;
    public static final int MSG_GET_LED_TIMEOUT = 32;
    public static final int MSG_SET_LED_TIMEOUT = 33;
    public static final int MSG_BUMP_KEY_ACTIVITY = 34;

    // --- Reply what (service → client) ---
    public static final int MSG_REPLY_OK = 100;
    public static final int MSG_REPLY_ERR = 101;
    public static final int MSG_EVENT_PAD_MODE = 110;
    public static final int MSG_EVENT_PAD_EPOCH = 111;
    public static final int MSG_EVENT_KEYMAP = 112;

    // --- Bundle / extra keys ---
    public static final String KEY_MODE = "mode";
    public static final String KEY_FOLLOW = "follow";
    public static final String KEY_EPOCH = "epoch";
    public static final String KEY_SLOT = "slot";
    public static final String KEY_ACTION = "action";
    public static final String KEY_LAYER_ID = "layer_id";
    public static final String KEY_MAP = "map";           // Bundle slot→action
    /** Package for per-app profile ops ({@link #MSG_SET_KEY_ACTION}, ensure/get). */
    public static final String KEY_PKG = "pkg";
    public static final String KEY_LABEL = "label";
    public static final String KEY_LEVEL = "level";
    public static final String KEY_TIMEOUT = "timeout";
    public static final String KEY_ERROR = "error";
    public static final String KEY_OK = "ok";
    public static final String KEY_LAYERS = "layers";     // String[] layer ids bottom→top
    public static final String KEY_VALUE = "value";

    // --- Control-plane file names (shared with pad-agent / HID service) ---
    // Single source of truth: both Titan Controls and USB HID read/write these
    // via {@link ControlPlane} / {@link InputPlane}. pad-agent polls the same names.
    public static final String FILE_PAD_MODE = "titan2_pad_mode";
    public static final String FILE_PAD_CLICK = "titan2_pad_click";
    public static final String FILE_PAD_FOLLOW = "titan2_pad_follow_orient";
    public static final String FILE_PAD_ROTATION = "titan2_pad_rotation";
    /** Monotonic counter bumped on every pad mode change (fast HID re-grab). */
    public static final String FILE_PAD_EPOCH = "titan2_pad_epoch";
    /** One-shot: hid_bridge forces mouse rediscover when non-zero. */
    public static final String FILE_PAD_REGRAB = "titan2_pad_regrab";
    /** 1 = freeze physical pad (typing lock) — never set while exclusive host mouse. */
    public static final String FILE_PAD_CURSOR_PAUSE = "titan2_pad_cursor_pause";
    /**
     * Functional top row (independent of pad body mode): 1 = caret slide + key
     * presses on upper key row; see docs/project/rd/INPUT_CORE_FUNCTIONAL_ROW.md.
     */
    public static final String FILE_PAD_TOP_ROW_CURSOR = "titan2_pad_top_row_cursor";
    /** 1 = top-row only (no body pointer); rare. Default 0 under mouse|trackpad. */
    public static final String FILE_PAD_TOP_ROW_ONLY = "titan2_pad_top_row_only";
    /**
     * Unlock delay ms for typing lock (Controls UI). pad-agent auto-expires
     * {@link #FILE_PAD_CURSOR_PAUSE} when this age is exceeded so a dead Handler
     * or re-pulse storm cannot leave the pad stuck forever.
     */
    public static final String FILE_PAD_CURSOR_PAUSE_MS = "titan2_pad_cursor_pause_ms";
    /** Unix epoch seconds when typing lock should auto-clear (pad-agent TTL). */
    public static final String FILE_PAD_CURSOR_PAUSE_UNTIL = "titan2_pad_cursor_pause_until";

    // HID session plane (owner: USB HID FGS while live; idle seed: Controls)
    public static final String FILE_HID_SESSION = "titan2_usb_hid_session";
    public static final String FILE_HID_GRAB = "titan2_usb_hid_grab";
    public static final String FILE_HID_KEYS = "titan2_usb_hid_keys";
    public static final String FILE_HID_MOUSE = "titan2_usb_hid_mouse";
    public static final String FILE_HID_LOCAL_INPUT = "titan2_usb_hid_local_input";
    public static final String FILE_HID_ON = "titan2_usb_hid_on";

    // Host keyboard layout / specials ownership
    public static final String FILE_HOST_LAYOUT = "titan2_host_layout"; // off|specials|arrows|c_*
    /** 1 = pause phys TitanKey→host (a11y/specials own letters). */
    public static final String FILE_KEYS_PAUSE = "titan2_host_layout_keys_pause";
    public static final String FILE_KEYS_PAUSE_TWIN = "titan2_usb_hid_keys_pause";
    /** 1 = Sym inject method holding (pause without sticky layout). */
    public static final String FILE_INJECT_PAUSE = "titan2_specials_inject_pause";
    public static final String FILE_SPECIALS_METHOD = "titan2_specials_method"; // inject|kcm
    /** 1 = OS/HW key repeat on (OEM-like); 0 = off. */
    public static final String FILE_KEY_REPEAT_ENABLED = "titan2_key_repeat_enabled";
    /** ms before hold-repeat starts (maps to Secure key_repeat_timeout). */
    public static final String FILE_KEY_REPEAT_TIMEOUT_MS = "titan2_key_repeat_timeout_ms";
    /** ms between repeats (maps to Secure key_repeat_delay). */
    public static final String FILE_KEY_REPEAT_DELAY_MS = "titan2_key_repeat_delay_ms";

    public static final String FILE_LED_LEVEL = "titan2_keyled_brightness";
    public static final String FILE_LED_TIMEOUT = "titan2_keyled_timeout";
    public static final String FILE_KEY_ACTIVITY = "titan2_key_activity";
    public static final String OS_CTRL = "/data/misc/titan2";
    public static final String TMP_CTRL = "/data/local/tmp";

    // --- Pad modes ---
    public static final String MODE_OFF = "off";
    public static final String MODE_TRACKPAD = "trackpad";
    public static final String MODE_MOUSE = "mouse";

    // --- Well-known key slots (side keys for HID temp override) ---
    public static final String SLOT_SIDE_SHORT = "side_func_short";
    public static final String SLOT_SIDE_LONG = "side_func_long";
    public static final String SLOT_SIDE_DOUBLE = "side_func_double";
    public static final String SLOT_SIDE2_SHORT = "side_func2_short";
    public static final String SLOT_SIDE2_LONG = "side_func2_long";
    public static final String SLOT_SIDE2_DOUBLE = "side_func2_double";

    /**
     * Session layer: temporary silence of phone-only Controls shortcuts while a
     * remote/USB session owns input. Computer actions ({@code mouse:*} /
     * {@code host:*}) are <b>not</b> silenced.
     * <p>
     * Priority (lowest among temp): API client layers such as
     * {@link #LAYER_HID_SIDE_KEYS} always win on slots they define; silence
     * only applies where the client map has no entry. Unmapped slots fall
     * through to permanent Controls prefs.
     */
    public static final String LAYER_HID_SESSION = "hid_session";

    /**
     * Universal remote/computer input event (not HID-specific).
     * Any app with {@link #PERMISSION_USE} may register a receiver.
     * Consumers: Titan USB HID, future remote-desktop bridges, etc.
     * <p>
     * Extras: {@link #EXTRA_REMOTE_ACTION} (e.g. mouse:left, host:ctrl+f1),
     * {@link #EXTRA_KIND} ({@link #KIND_MOUSE}|{@link #KIND_KEY}),
     * mouse: {@link #EXTRA_MOUSE_BUTTONS}, {@link #EXTRA_MOUSE_TAP};
     * key: {@link #EXTRA_MODIFIERS}, {@link #EXTRA_KEYCODE}, {@link #EXTRA_HID_USAGE}.
     */
    public static final String ACTION_REMOTE_INPUT = "com.titanus2.api.REMOTE_INPUT";
    /** @deprecated use {@link #ACTION_REMOTE_INPUT} */
    public static final String ACTION_HOST_MOUSE = "com.titanus2.usbhid.action.HOST_MOUSE";

    public static final String EXTRA_REMOTE_ACTION = "action";
    public static final String EXTRA_KIND = "kind";
    public static final String KIND_MOUSE = "mouse";
    public static final String KIND_KEY = "key";
    public static final String EXTRA_MOUSE_BUTTONS = "buttons";
    public static final String EXTRA_MOUSE_TAP = "tap";
    /**
     * Mouse wheel notches for {@link #KIND_MOUSE}: positive = scroll up,
     * negative = scroll down. Used by side-key remaps ({@code mouse:scroll_*}).
     */
    public static final String EXTRA_MOUSE_WHEEL = "wheel";
    /** Meta bitmask: 1=Ctrl 2=Shift 4=Alt 8=Meta/Win */
    public static final String EXTRA_MODIFIERS = "modifiers";
    public static final String EXTRA_KEYCODE = "keycode";
    public static final String EXTRA_HID_USAGE = "hid_usage";
    /** Control-plane stamp of last remote event (apps may poll). */
    public static final String FILE_REMOTE_INPUT = "titan2_remote_input";

    // --- Well-known action strings (remap catalog; freeform host:/mouse:/keycode: also valid) ---
    public static final String ACT_MOUSE_LEFT = "mouse:left";
    public static final String ACT_MOUSE_RIGHT = "mouse:right";
    public static final String ACT_MOUSE_MIDDLE = "mouse:middle";
    public static final String ACT_MOUSE_SCROLL_UP = "mouse:scroll_up";
    public static final String ACT_MOUSE_SCROLL_DOWN = "mouse:scroll_down";
    /**
     * Temp layer id for HID host remaps. Filled from the Controls per-app
     * profile {@link #HID_HOST_PKG} so Titan Controls Keys → app profiles shows
     * the same map the HID session uses (side, volume, home, magic chords, …).
     * @deprecated use {@link #LAYER_HID_HOST} — kept as alias for old clients.
     */
    public static final String LAYER_HID_SIDE_KEYS = "hid_host";
    /** Canonical HID host temp layer (API + Controls profile SoT). */
    public static final String LAYER_HID_HOST = "hid_host";
    /**
     * Package owning the permanent HID host profile in Controls
     * ({@code KeyMapProfiles}). Edits in Controls or via
     * {@link #MSG_SET_KEY_ACTION}+{@link #KEY_PKG} stay visible there.
     */
    public static final String HID_HOST_PKG = "com.titanus2.usbhid";
    public static final String HID_HOST_LABEL = "USB HID host";

    // --- Pad gesture plane (touchpadd hot-read; HID + Controls) ---
    /** 1 = left tap-click on (default). */
    public static final String FILE_PAD_TAP_CLICK = "titan2_pad_tap_click";
    /** 1 = long-press right click (default). */
    public static final String FILE_PAD_LONG_CLICK = "titan2_pad_long_click";
    /** 1 = two-finger / edge scroll (default). */
    public static final String FILE_PAD_SCROLL = "titan2_pad_scroll";
    /**
     * Double-tap policy: {@code classic} (hold while finger down — product default),
     * {@code latch} (toggle hold until 2nd double-tap or HW key), {@code off}.
     */
    public static final String FILE_PAD_DBLTAP = "titan2_pad_dbltap";
    public static final String PAD_DBLTAP_CLASSIC = "classic";
    public static final String PAD_DBLTAP_LATCH = "latch";
    public static final String PAD_DBLTAP_OFF = "off";

    // --- Minimal display API (main + rear independent) ---
    // SoT: control-plane files + Settings.Global; pad-agent / DisplayApi apply.
    /** 0|1 main panel power intent (framework DisplayManager still owns true off). */
    public static final String FILE_DISPLAY_MAIN_ON = "titan2_display_main_on";
    /** 0|1 rear subdisplay power (maps to titan2_subdisplay_on). */
    public static final String FILE_DISPLAY_SUB_ON = "titan2_subdisplay_on";
    /** 0.0–1.0 rear brightness. */
    public static final String FILE_DISPLAY_SUB_BRI = "titan2_subdisplay_bri";
    /** Display id hint for rear (default 2). */
    public static final String FILE_DISPLAY_SUB_ID = "titan2_subdisplay_id";
    /**
     * Rear touch: 1 = inhibit sub_touch (default), 0 = native IDC trackpad.
     * Independent of main keyboard-surface pad.
     */
    public static final String FILE_SUBTOUCH_INHIBIT = "titan2_subtouch_inhibit";
    /**
     * High-level rear use: off | face | trackpad | raw.
     * trackpad ⇒ sub on + subtouch inhibit 0; face ⇒ sub on + inhibit 1.
     */
    public static final String FILE_DISPLAY_SUB_USE = "titan2_display_sub_use";

    // --- On-device nanobot (system binary + priv wrapper) ---
    /** 0|1 peer service wanted. */
    public static final String FILE_NANOBOT_ON = "titan2_nanobot_on";
    /** Listen port (default 8787). */
    public static final String FILE_NANOBOT_PORT = "titan2_nanobot_port";
    /** offline | grok | llama — backend mode. */
    public static final String FILE_NANOBOT_BACKEND = "titan2_nanobot_backend";
    /** Base URL for llama.cpp / OpenAI-style (e.g. http://127.0.0.1:8080/v1). */
    public static final String FILE_NANOBOT_BASE_URL = "titan2_nanobot_base_url";
    /**
     * Personal-files ACL: deny (default) | read | full.
     * Shell/device control may still be allowed when user enables agent control;
     * personal paths stay gated by this ACL (Privacy constitution §2–3).
     */
    public static final String FILE_NANOBOT_FILES_ACL = "titan2_nanobot_files_acl";
    /** 0|1 allow nanobot to run device shell / privileged tools (user switch). */
    public static final String FILE_NANOBOT_DEVICE_CONTROL = "titan2_nanobot_device_control";
    /** NANOBOT_HOME on device (default /data/misc/titan2/nanobot). */
    public static final String FILE_NANOBOT_HOME = "titan2_nanobot_home";
}
