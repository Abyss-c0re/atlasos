/* Titan 2 USB HID bridge: TitanKey + touchPad (+ app socket) → hidg keyboard/mouse.
 *
 * Session protocol (control files polled by parent service, or CLI flags):
 *   --grab / --nograb     exclusive TitanKey vs share (focus-routed keys)
 *   --mouse / --nomouse   virtual mouse always EVIOCGRAB when on (share + exclusive);
 *                         pad is always the HID guest — never Android cursor
 *   --keys / --nokeys     open TitanKey (default on); --nokeys = soft inject only
 *   local_input=1         share only: TitanKey → Android (editor). Pad stays guest.
 *                         Bridge stays up; hot EVIOCGRAB flip. Typing lock still
 *                         samples TitanKey. Ignored under --grab.
 *   --sock PATH           unix DGRAM for app inject (default /data/local/tmp/titan2_hid.sock)
 *
 * Socket packets (little-endian):
 *   [0]=0x01 key:  [1]=mod [2]=hid_usage [3]=1 press / 0 release
 *   [0]=0x02 mouse:[1]=dx(int8) [2]=dy(int8) [3]=buttons
 *   [0]=0x03 btn:  [1]=buttons absolute
 *   [0]=0x04 wheel:[1]=wheel(int8)
 *
 * Also reads /data/local/tmp/titan2_hid.inj (app su fallback, 4-byte packets).
 */
#define INJ_PATH "/data/local/tmp/titan2_hid.inj"
#define KEY_ACT_PATH "/data/local/tmp/titan2_key_activity"
#define KEYLED_WANT "/data/misc/titan2/titan2_keyled_brightness"
#define KEYLED_WANT2 "/data/local/tmp/titan2_keyled_brightness"
#define TYPING_MS_PATH "/data/misc/titan2/titan2_usb_hid_typing_ms"
#define TYPING_MS_PATH2 "/data/local/tmp/titan2_usb_hid_typing_ms"
#define SPEED_PATH "/data/misc/titan2/titan2_usb_hid_speed"
#define SPEED_PATH2 "/data/local/tmp/titan2_usb_hid_speed"
#define ACCEL_PATH "/data/misc/titan2/titan2_usb_hid_accel"
#define ACCEL_PATH2 "/data/local/tmp/titan2_usb_hid_accel"
/* Shared with Titan Controls pad-agent (screen-orientation follow). */
#define FOLLOW_ORIENT_PATH "/data/misc/titan2/titan2_pad_follow_orient"
#define FOLLOW_ORIENT_PATH2 "/data/local/tmp/titan2_pad_follow_orient"
#define ROTATION_PATH "/data/misc/titan2/titan2_pad_rotation"
#define ROTATION_PATH2 "/data/local/tmp/titan2_pad_rotation"
/* 1 = phone has active text field / IME — release TitanKey + pad to Android,
 * stop host redirect until 0 (share: type on phone; exclusive: same escape hatch). */
#define LOCAL_INPUT_PATH "/data/misc/titan2/titan2_usb_hid_local_input"
#define LOCAL_INPUT_PATH2 "/data/local/tmp/titan2_usb_hid_local_input"
/* Keys-only host pause (Sym inject specials) — must NOT close mouse. */
#define KEYS_PAUSE_PATH "/data/misc/titan2/titan2_host_layout_keys_pause"
#define KEYS_PAUSE_PATH2 "/data/local/tmp/titan2_host_layout_keys_pause"
#define INJECT_PAUSE_PATH "/data/misc/titan2/titan2_specials_inject_pause"
#define INJECT_PAUSE_PATH2 "/data/local/tmp/titan2_specials_inject_pause"
#define HID_KEYS_PATH "/data/misc/titan2/titan2_usb_hid_keys"
#define HID_KEYS_PATH2 "/data/local/tmp/titan2_usb_hid_keys"
/* Framework pad epoch / one-shot regrab — fast re-intercept when OS pad mode changes */
#define PAD_EPOCH_PATH "/data/misc/titan2/titan2_pad_epoch"
#define PAD_EPOCH_PATH2 "/data/local/tmp/titan2_pad_epoch"
#define PAD_REGRAB_PATH "/data/misc/titan2/titan2_pad_regrab"
#define PAD_REGRAB_PATH2 "/data/local/tmp/titan2_pad_regrab"
#define HID_GRAB_PATH "/data/misc/titan2/titan2_usb_hid_grab"
#define HID_GRAB_PATH2 "/data/local/tmp/titan2_usb_hid_grab"
/* After key activity, suppress pad→host mouse (motion + click). While any
 * physical key is still held (incl. hold-Backspace autorepeat), stay blocked
 * so palm on the keyboard surface cannot keep a right-click hold alive. */
#define DEFAULT_TYPING_MS 600
#define DEFAULT_SPEED_PCT 100
#define DEFAULT_ACCEL 0
/* Controls Unlock delay plane — prefer when user set typing-lock cooldown. */
#define PAD_PAUSE_MS_PATH "/data/misc/titan2/titan2_pad_cursor_pause_ms"
#define PAD_PAUSE_MS_PATH2 "/data/local/tmp/titan2_pad_cursor_pause_ms"
#define PAD_PAUSE_PATH "/data/misc/titan2/titan2_pad_cursor_pause"
#define PAD_PAUSE_PATH2 "/data/local/tmp/titan2_pad_cursor_pause"

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <linux/un.h>
#include <poll.h>
#include <dirent.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/wait.h>
#include <unistd.h>
#include <time.h>

/* Product upper-row nav (TitanKey) — must stay on phone in exclusive grab. */
#ifndef KEY_APPSELECT
#define KEY_APPSELECT 0x244 /* 580 — Recents / App switch on Titan */
#endif
#ifndef KEY_HOMEPAGE
#define KEY_HOMEPAGE 172
#endif
#define PHONE_NAV_LONG_MS 400

static volatile int g_run = 1;
static void on_sig(int s) { (void)s; g_run = 0; }

/* Same control plane as Titan Controls / pad-agent (grab steals getevent).
 * Prefer activity stamps only — pad-agent owns keypad_led sysfs. Dual writers
 * caused visible KB blink. Optional light is heavily throttled as fallback. */
static long long last_keyled_ms = 0;
static int last_keyled_lvl = -1;
static long long last_act_ms = 0;

static long long now_ms(void); /* defined below */

static void light_keyled(int force) {
    int lvl = 3;
    FILE *f = fopen(KEYLED_WANT, "r");
    if (!f) f = fopen(KEYLED_WANT2, "r");
    if (f) {
        if (fscanf(f, "%d", &lvl) != 1) lvl = 3;
        fclose(f);
    }
    if (lvl < 0) lvl = 0;
    if (lvl > 7) lvl = 7;
    long long now = now_ms();
    /* At most ~1 Hz; skip if same level within window unless forced */
    if (!force && last_keyled_lvl == lvl && (now - last_keyled_ms) < 1000)
        return;
    last_keyled_ms = now;
    last_keyled_lvl = lvl;
    char b[8];
    int n = snprintf(b, sizeof b, "%d", lvl);
    const char *paths[] = {
        "/sys/devices/platform/keypad_led/keyled_brightness",
        "/sys/class/misc/keypad_led/keyled_brightness",
        "/sys/class/leds/keypad_led/brightness",
        "/sys/class/leds/keyboard_backlight/brightness",
        NULL
    };
    for (int i = 0; paths[i]; i++) {
        int fd = open(paths[i], O_WRONLY | O_CLOEXEC);
        if (fd < 0) continue;
        if (write(fd, b, (size_t)n) > 0) { close(fd); return; }
        close(fd);
    }
}

static void bump_key_activity(void) {
    long long now = now_ms();
    if ((now - last_act_ms) < 150) return; /* stamp throttle */
    last_act_ms = now;
    char buf[32];
    int n = snprintf(buf, sizeof buf, "%ld\n", (long)time(NULL));
    int fd = open(KEY_ACT_PATH, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0666);
    if (fd >= 0) {
        (void)write(fd, buf, (size_t)n);
        close(fd);
        chmod(KEY_ACT_PATH, 0666);
    }
    fd = open("/data/misc/titan2/titan2_key_activity",
              O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0666);
    if (fd >= 0) {
        (void)write(fd, buf, (size_t)n);
        close(fd);
        chmod("/data/misc/titan2/titan2_key_activity", 0666);
    }
    /* Rare fallback light — pad-agent owns LED during HID session */
    light_keyled(0);
}


static int typing_guard_ms = DEFAULT_TYPING_MS;
static int mouse_speed_pct = DEFAULT_SPEED_PCT; /* 25..400 */
static int mouse_accel = DEFAULT_ACCEL;         /* 0=off .. 3=high */
static int follow_orient = 0;                   /* 0|1 from Controls */
static int display_rotation = 0;                /* Surface 0..3 */
/* 1 = reading titan2-orient-mouse (already rotated) — do not apply_orient again */
static int mouse_pre_oriented = 0;
static long long last_key_ms = 0;
/** Count of physical keys currently down (press without matching release). */
static int phys_keys_held = 0;
/** Last mouse_blocked state — edge-trigger host button release. */
static int mouse_was_typing_blocked = 0;
/** uinput keyboard for phone Back/Home/Recents while TitanKey is EVIOCGRAB'd. */
static int phone_nav_fd = -1;
/** Recents key (580) short=Home / long=App switch (matches pad-agent). */
static long long recents_down_ms = 0;

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

static int read_int_file(const char *a, const char *b, int def) {
    int v = def;
    FILE *f = fopen(a, "r");
    if (!f && b) f = fopen(b, "r");
    if (f) {
        if (fscanf(f, "%d", &v) != 1) v = def;
        fclose(f);
    }
    return v;
}

static void load_typing_ms(void) {
    /* Prefer shared Unlock delay (Controls typing lock) when set; else HID plane. */
    int v = read_int_file(PAD_PAUSE_MS_PATH, PAD_PAUSE_MS_PATH2, 0);
    if (v < 50)
        v = read_int_file(TYPING_MS_PATH, TYPING_MS_PATH2, DEFAULT_TYPING_MS);
    if (v < 0) v = 0;
    if (v > 5000) v = 5000;
    typing_guard_ms = v;
}

static void load_mouse_feel(void) {
    int s = read_int_file(SPEED_PATH, SPEED_PATH2, DEFAULT_SPEED_PCT);
    if (s < 25) s = 25;
    if (s > 400) s = 400;
    mouse_speed_pct = s;
    int a = read_int_file(ACCEL_PATH, ACCEL_PATH2, DEFAULT_ACCEL);
    if (a < 0) a = 0;
    if (a > 3) a = 3;
    mouse_accel = a;
}

static void load_orient(void) {
    /* Default follow=1 matches Controls / pad-agent seed (rotate with phone). */
    int f = read_int_file(FOLLOW_ORIENT_PATH, FOLLOW_ORIENT_PATH2, 1);
    follow_orient = (f == 1) ? 1 : 0;
    int r = read_int_file(ROTATION_PATH, ROTATION_PATH2, 0);
    if (r < 0) r = 0;
    if (r > 3) r = 3;
    display_rotation = r;
}

/* Phone editor/IME focused — yield TitanKey to Android (share only). */
static int local_input_pause = 0;
/* Specials inject / keys_pause — TitanKey closed; mouse stays open. */
static int keys_host_pause = 0;
/* TitanKey EVIOCGRAB currently held. */
static int key_grabbed = 0;
/* Emit TitanKey to HID guest (0 = Android owns keys; we only watch typing). */
static int keys_emit = 1;
/* 1 = inject-mode map (share: plane inject; exclusive: always in-bridge). */
static int specials_inject_mode = 0;

/* True if any path holds "1". App (no root) and Magisk/OS plane can both
 * write; OR so a false-clear on one plane cannot resume dual-typing. */
static int file_is_one(const char *path) {
    FILE *f;
    int v = 0;
    if (!path) return 0;
    f = fopen(path, "r");
    if (!f) return 0;
    if (fscanf(f, "%d", &v) != 1) v = 0;
    fclose(f);
    return (v == 1) ? 1 : 0;
}

static int load_local_input_pause(void) {
    if (file_is_one("/data/user/0/com.titanus2.usbhid/files/titan2_usb_hid_local_input"))
        return 1;
    if (file_is_one("/data/data/com.titanus2.usbhid/files/titan2_usb_hid_local_input"))
        return 1;
    if (file_is_one(LOCAL_INPUT_PATH)) return 1;
    if (file_is_one(LOCAL_INPUT_PATH2)) return 1;
    return 0;
}

/* Share only. Exclusive (--grab) never pauses for phone editors. */
static int effective_local_input_pause(int grab_mode) {
    if (grab_mode) return 0;
    return load_local_input_pause();
}

/* Sym inject / layout specials: pause phys TitanKey→host only (keep mouse).
 * Reads InputPlane: inject_pause, keys_pause, or newest keys plane.
 * 1.85: ANY path with keys=0 used to pause (stale idle seed in tmp while CE
 * had keys=1) → USB host keyboard dead at Start. Newest mtime wins. */
static int load_keys_host_pause(void) {
    if (file_is_one(INJECT_PAUSE_PATH) || file_is_one(INJECT_PAUSE_PATH2))
        return 1;
    if (file_is_one(KEYS_PAUSE_PATH) || file_is_one(KEYS_PAUSE_PATH2))
        return 1;
    {
        FILE *f;
        int v = 1;
        int best_v = 1;
        long best_mt = -1;
        struct stat st;
        const char *paths[] = {
            HID_KEYS_PATH, HID_KEYS_PATH2,
            "/data/user/0/com.titanus2.usbhid/files/titan2_usb_hid_keys",
            "/data/data/com.titanus2.usbhid/files/titan2_usb_hid_keys",
            NULL
        };
        int i;
        for (i = 0; paths[i]; i++) {
            f = fopen(paths[i], "r");
            if (!f) continue;
            if (fscanf(f, "%d", &v) != 1) v = 1;
            fclose(f);
            long mt = 0;
            if (stat(paths[i], &st) == 0) mt = (long)st.st_mtime;
            if (mt >= best_mt) {
                best_mt = mt;
                best_v = v;
            }
        }
        if (best_mt >= 0 && best_v == 0) return 1;
    }
    return 0;
}

/*
 * specials_method plane is phone-path SoT (FB-IN-1 product default = kcm).
 * Exclusive grab always uses in-bridge specials map (host boot US HID) — that
 * is independent of the plane. HID app must NOT clobber plane to inject on
 * exclusive Start (0.16.14 residual after 2.50/13.88 kcm seed).
 * exclusive_grab: force inject-mode map in-memory only; never rewrite plane.
 */
static void load_specials_method(int exclusive_grab) {
    FILE *f;
    char buf[32];
    const char *paths[] = {
        "/data/misc/titan2/titan2_specials_method",
        "/data/local/tmp/titan2_specials_method",
        "/data/user/0/com.titanus2.controls/files/titan2_specials_method",
        NULL
    };
    int i;
    specials_inject_mode = 0; /* product default kcm when plane missing */
    for (i = 0; paths[i]; i++) {
        f = fopen(paths[i], "r");
        if (!f) continue;
        if (fgets(buf, sizeof buf, f)) {
            /* trim */
            char *p = buf;
            while (*p == ' ' || *p == '\t') p++;
            if (!strncmp(p, "inject", 6) || !strncmp(p, "INJECT", 6)) {
                specials_inject_mode = 1;
            } else {
                /* kcm / legacy / empty / unknown → product kcm */
                specials_inject_mode = 0;
            }
        }
        fclose(f);
        break;
    }
    /* Exclusive: always in-bridge specials map (plane stays phone kcm). */
    if (exclusive_grab)
        specials_inject_mode = 1;
}

/* char_mod=alt → specials owner is free Alt; else Sym (product default). */
static int specials_owner_alt = 0;

static void load_char_mod_owner(void) {
    FILE *f;
    char buf[32];
    const char *paths[] = {
        "/data/misc/titan2/titan2_char_mod",
        "/data/local/tmp/titan2_char_mod",
        NULL
    };
    int i;
    specials_owner_alt = 0;
    for (i = 0; paths[i]; i++) {
        f = fopen(paths[i], "r");
        if (!f) continue;
        if (fgets(buf, sizeof buf, f)) {
            char *p = buf;
            while (*p == ' ' || *p == '\t') p++;
            if (!strncmp(p, "alt", 3) || !strncmp(p, "ALT", 3))
                specials_owner_alt = 1;
        }
        fclose(f);
        return;
    }
}

/*
 * Titan specials-mod scans for exclusive inject layer.
 * Sym dual-report: 222 (KEY_ALTERASE, mainline input-event-codes) + 253
 * (Unihertz OEM second scan — mainline names stop at KEY_MICMUTE=248; 255 is
 * AT reserved, so 249–254 are free for OEM). Both must refcount: if only one
 * scan is tracked, Sym UP of the other drops specials while still held.
 * Free Alt (KEY_LEFTALT/RIGHTALT) is specials only when char_mod=alt; under
 * char_mod=sym free Alt must reach host as real Alt, not arm the layer.
 */
static int is_specials_mod_scan(int code) {
    if (code == 222 || code == 253)
        return !specials_owner_alt; /* Sym owner */
    if (code == KEY_RIGHTALT || code == KEY_LEFTALT)
        return specials_owner_alt;  /* Alt owner */
    return 0;
}

/* Bitmask of currently-down specials-mod scans (dual Sym never drops early). */
static unsigned specials_mod_mask = 0;

static unsigned specials_mod_bit(int code) {
    if (code == 222) return 1u;
    if (code == 253) return 2u;
    if (code == KEY_RIGHTALT) return 4u;
    if (code == KEY_LEFTALT) return 8u;
    return 0;
}

static int sym_layer_held(void) {
    return specials_mod_mask != 0;
}

/*
 * Host HID specials (share + exclusive): map Titan printed specials layer
 * → US HID usages (host always sees boot-protocol US keyboard, NOT TitanKey.kcm).
 * Product map matches HostLayoutController.specialsChar (C→8, A→@, …).
 * Returns 1 if code is a specials-layer letter; *out_mod = LShift (0x02) or 0;
 * *out_usage = HID keyboard usage.
 */
static int titan_specials_layer_hid(unsigned code, uint8_t *out_mod, uint8_t *out_usage) {
    uint8_t m = 0, u = 0;
    /* Linux KEY_* for QWERTY body (input-event-codes) */
    switch (code) {
    case KEY_Q: u = 0x27; break;              /* 0 */
    case KEY_W: u = 0x1e; break;              /* 1 */
    case KEY_E: u = 0x1f; break;              /* 2 */
    case KEY_R: u = 0x20; break;              /* 3 */
    case KEY_T: m = 0x02; u = 0x26; break;    /* ( */
    case KEY_Y: m = 0x02; u = 0x27; break;    /* ) */
    case KEY_U: m = 0x02; u = 0x2d; break;    /* _  product inject map */
    case KEY_I: u = 0x2d; break;              /* - */
    case KEY_O: u = 0x38; break;              /* / */
    case KEY_P: m = 0x02; u = 0x33; break;    /* : */
    case KEY_A: m = 0x02; u = 0x1f; break;    /* @ */
    case KEY_S: u = 0x21; break;              /* 4 */
    case KEY_D: u = 0x22; break;              /* 5 */
    case KEY_F: u = 0x23; break;              /* 6 */
    case KEY_G: m = 0x02; u = 0x25; break;    /* * */
    case KEY_H: m = 0x02; u = 0x20; break;    /* # */
    case KEY_J: m = 0x02; u = 0x2e; break;    /* + */
    case KEY_K: m = 0x02; u = 0x34; break;    /* " */
    case KEY_L: u = 0x34; break;              /* ' */
    case KEY_Z: m = 0x02; u = 0x1e; break;    /* ! */
    case KEY_X: u = 0x24; break;              /* 7 */
    case KEY_C: u = 0x25; break;              /* 8 */
    case KEY_V: u = 0x26; break;              /* 9 */
    case KEY_B: u = 0x37; break;              /* . */
    case KEY_N: u = 0x36; break;              /* , */
    case KEY_M: m = 0x02; u = 0x38; break;    /* ? */
    default: return 0;
    }
    if (out_mod) *out_mod = m;
    if (out_usage) *out_usage = u;
    return 1;
}

/* Map body-fixed pad deltas → screen axes when follow_orient is on.
 * Surface.ROTATION_*: 0=0°, 1=90°CCW, 2=180°, 3=270°CCW.
 * Skip when source is titan2-orient-mouse (orient-rel already rotated). */
static void apply_orient(int *dx, int *dy) {
    int x, y;
    if (mouse_pre_oriented) return;
    if (!follow_orient || !dx || !dy) return;
    if (!*dx && !*dy) return;
    x = *dx;
    y = *dy;
    switch (display_rotation) {
    case 1: /* 90° CCW */  *dx =  y; *dy = -x; break;
    case 2: /* 180° */     *dx = -x; *dy = -y; break;
    case 3: /* 270° CCW */ *dx = -y; *dy =  x; break;
    default: break; /* 0: identity */
    }
}

static void note_typing(void) {
    last_key_ms = now_ms();
}

/**
 * Host mouse (incl. right-click hold from pad long-press) must freeze while the
 * user is typing on TitanKey — the keyboard surface is the trackpad. Block while
 * any key is held (hold-Backspace) OR within post-key cooldown (palm settle).
 */
static int mouse_blocked_by_typing(void) {
    if (phys_keys_held > 0) return 1;
    if (read_int_file(PAD_PAUSE_PATH, PAD_PAUSE_PATH2, 0) == 1) return 1;
    if (typing_guard_ms <= 0) return 0;
    if (last_key_ms <= 0) return 0;
    long long dt = now_ms() - last_key_ms;
    return dt >= 0 && dt < typing_guard_ms;
}

static int emit_mouse(int fd, uint8_t buttons, int dx, int dy, int wheel, int apply_feel);

/** Cancel in-flight pad click (right-hold etc.) on host when typing starts. */
static void release_host_mouse_buttons(int hid_m, uint8_t *buttons) {
    if (!buttons) return;
    if (*buttons == 0) return;
    *buttons = 0;
    emit_mouse(hid_m >= 0 ? hid_m : -1, 0, 0, 0, 0, 0);
}

/** Re-sample BTN_* from grabbed virt mouse so a node reopen does not drop
 * left-latch drag on the host (touchpadd still holds BTN_LEFT). */
static uint8_t mouse_buttons_from_fd(int rfd) {
    unsigned long bits[(KEY_MAX + 1 + (sizeof(long) * 8 - 1)) / (sizeof(long) * 8)];
    uint8_t b = 0;
    if (rfd < 0) return 0;
    memset(bits, 0, sizeof bits);
    if (ioctl(rfd, EVIOCGKEY(sizeof bits), bits) != 0) return 0;
#define BIT_SET(k) (bits[(k) / (sizeof(long) * 8)] & (1UL << ((k) % (sizeof(long) * 8))))
    if (BIT_SET(BTN_LEFT) || BIT_SET(BTN_MOUSE)) b |= 0x01;
    if (BIT_SET(BTN_RIGHT)) b |= 0x02;
    if (BIT_SET(BTN_MIDDLE)) b |= 0x04;
#undef BIT_SET
    return b;
}

/** Seed raw-pad contact from kernel state. HID start mid-touch has no
 *  BTN_TOUCH / TRACKING_ID edge — without this the host waits for lift. */
static void seed_raw_pad_contact(int fd, int *contact, int *last_x, int *last_y)
{
    unsigned long bits[(KEY_MAX + 1 + (sizeof(long) * 8 - 1)) / (sizeof(long) * 8)];
    struct input_absinfo ax, ay;
    int x = -1, y = -1, down = 0;

    if (fd < 0 || !contact || !last_x || !last_y) return;
    memset(bits, 0, sizeof bits);
    if (ioctl(fd, EVIOCGKEY(sizeof bits), bits) == 0) {
#define BIT_SET(k) (bits[(k) / (sizeof(long) * 8)] & (1UL << ((k) % (sizeof(long) * 8))))
        if (BIT_SET(BTN_TOUCH) || BIT_SET(BTN_TOOL_FINGER)) down = 1;
#undef BIT_SET
    }
    if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &ax) == 0) x = ax.value;
    else if (ioctl(fd, EVIOCGABS(ABS_X), &ax) == 0) x = ax.value;
    if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &ay) == 0) y = ay.value;
    else if (ioctl(fd, EVIOCGABS(ABS_Y), &ay) == 0) y = ay.value;
    if (down) {
        *contact = 1;
        if (x >= 0) *last_x = x;
        if (y >= 0) *last_y = y;
        fprintf(stderr, "raw pad seed contact=1 x=%d y=%d (HID attach mid-touch)\n",
                x, y);
    }
}

/**
 * Upper-row phone chrome while exclusive grab owns TitanKey.
 * BACK (158), HOME (102), APPSELECT/Recents (580), MENU/HOMEPAGE.
 * Without this the user cannot leave an app when letters are redirected to host.
 */
static int is_phone_nav_key(unsigned code) {
    switch (code) {
    case KEY_BACK:      /* 158 — Titan upper Back */
    case KEY_HOME:      /* 102 */
    case KEY_HOMEPAGE:  /* 172 */
    case KEY_MENU:      /* 139 */
    case KEY_APPSELECT: /* 580 — product short=Home long=Recents */
        return 1;
    default:
        return 0;
    }
}

static int ensure_phone_nav_uinput(void) {
    struct uinput_setup usetup;
    int fd;
    if (phone_nav_fd >= 0) return 0;
    fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        fprintf(stderr, "phone-nav uinput open fail errno=%d\n", errno);
        return -1;
    }
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    ioctl(fd, UI_SET_KEYBIT, KEY_BACK);
    ioctl(fd, UI_SET_KEYBIT, KEY_HOME);
    ioctl(fd, UI_SET_KEYBIT, KEY_HOMEPAGE);
    ioctl(fd, UI_SET_KEYBIT, KEY_MENU);
    ioctl(fd, UI_SET_KEYBIT, KEY_APPSELECT);
    memset(&usetup, 0, sizeof usetup);
    usetup.id.bustype = BUS_VIRTUAL;
    usetup.id.vendor = 0x2533;
    usetup.id.product = 0x0e01;
    snprintf(usetup.name, UINPUT_MAX_NAME_SIZE, "titan2-phone-nav");
    if (ioctl(fd, UI_DEV_SETUP, &usetup) != 0
            || ioctl(fd, UI_DEV_CREATE) != 0) {
        fprintf(stderr, "phone-nav uinput create fail errno=%d\n", errno);
        close(fd);
        return -1;
    }
    phone_nav_fd = fd;
    fprintf(stderr, "phone-nav uinput ready (Back/Home/Recents stay on phone)\n");
    fflush(stderr);
    return 0;
}

static void close_phone_nav_uinput(void) {
    if (phone_nav_fd < 0) return;
    ioctl(phone_nav_fd, UI_DEV_DESTROY);
    close(phone_nav_fd);
    phone_nav_fd = -1;
}

static void phone_nav_emit(unsigned code, int value) {
    struct input_event ev;
    struct timeval tv;
    if (ensure_phone_nav_uinput() != 0) return;
    if (value != 0 && value != 1) return; /* no autorepeat spam into phone */
    gettimeofday(&tv, NULL);
    memset(&ev, 0, sizeof ev);
    ev.time = tv;
    ev.type = EV_KEY;
    ev.code = (uint16_t)code;
    ev.value = value;
    if (write(phone_nav_fd, &ev, sizeof ev) != (ssize_t)sizeof ev) return;
    memset(&ev, 0, sizeof ev);
    ev.time = tv;
    ev.type = EV_SYN;
    ev.code = SYN_REPORT;
    ev.value = 0;
    (void)write(phone_nav_fd, &ev, sizeof ev);
}

/** Tap phone key (down+up) for short/long Recents product actions. */
static void phone_nav_tap(unsigned code) {
    phone_nav_emit(code, 1);
    usleep(20 * 1000);
    phone_nav_emit(code, 0);
}

/**
 * Fire Controls KEY_FIRE — same path as titan2-key-watch / a11y.
 * Home = GLOBAL_ACTION_HOME, Recents = GLOBAL_ACTION_RECENTS.
 * Never keyevent 3, never 187, never RecentsActivity, never uinput APPSELECT.
 */
static void fire_os_nav(const char *act) {
    pid_t pid;
    char scan[8];
    /* AccessibilityService.GLOBAL_ACTION_*: 1=back 2=home 3=recents.
     * Same factory action as Controls a11y. Used when Key a11y is listed
     * but not bound (KEY_FIRE then no-ops). Never 187 / RecentsActivity. */
    const char *sys_act = NULL;
    if (!act || !act[0]) return;
    if (!strcmp(act, "back")) sys_act = "1";
    else if (!strcmp(act, "home")) sys_act = "2";
    else if (!strcmp(act, "recents")) sys_act = "3";
    snprintf(scan, sizeof scan, "%s", (strcmp(act, "back") == 0) ? "158" : "580");
    pid = fork();
    if (pid < 0) {
        fprintf(stderr, "phone-nav: fork KEY_FIRE %s errno=%d\n", act, errno);
        return;
    }
    if (pid == 0) {
        pid_t g = fork();
        if (g != 0) _exit(0);
        if (sys_act)
            execl("/system/bin/cmd", "cmd", "accessibility",
                  "call-system-action", sys_act, (char *)NULL);
        execl("/system/bin/titan2-key-fire.sh", "titan2-key-fire.sh",
              "fire", act, scan, (char *)NULL);
        execl("/system/bin/am", "am", "broadcast",
              "-a", "com.titanus2.controls.KEY_FIRE",
              "-n", "com.titanus2.controls/.KeyFireReceiver",
              "--es", "action", act,
              "--ei", "scan", scan,
              (char *)NULL);
        _exit(127);
    }
    (void)waitpid(pid, NULL, 0);
}

/**
 * Exclusive grab path: top-panel Home/Recents stay on the phone via the
 * host-OS binding (Controls KEY_FIRE). Returns 1 if consumed.
 * Recents (580): short → home, long (≥400ms) → recents.
 */
static int handle_phone_nav_exclusive(unsigned code, int value) {
    if (!is_phone_nav_key(code)) return 0;
    if (value == 2) return 1; /* swallow autorepeat */

    if (code == KEY_APPSELECT) {
        if (value == 1) {
            recents_down_ms = now_ms();
            return 1;
        }
        if (value == 0) {
            long long held = (recents_down_ms > 0)
                ? (now_ms() - recents_down_ms) : 0;
            recents_down_ms = 0;
            if (held >= PHONE_NAV_LONG_MS) {
                fprintf(stderr, "phone-nav: APPSELECT long → KEY_FIRE recents\n");
                fflush(stderr);
                fire_os_nav("recents");
            } else {
                fprintf(stderr, "phone-nav: APPSELECT short → KEY_FIRE home\n");
                fflush(stderr);
                fire_os_nav("home");
            }
            return 1;
        }
        return 1;
    }

    if (code == KEY_HOME || code == KEY_HOMEPAGE) {
        if (value == 1) {
            fprintf(stderr, "phone-nav: HOME → KEY_FIRE home\n");
            fflush(stderr);
            fire_os_nav("home");
        }
        return 1;
    }

    /* BACK / MENU: KEY_FIRE back + uinput (a11y may be unbound). */
    if (value == 1) {
        fprintf(stderr, "phone-nav: BACK → KEY_FIRE back\n");
        fflush(stderr);
        fire_os_nav("back");
    }
    if (value == 1 || value == 0)
        phone_nav_emit(code, value);
    return 1;
}

/* Pointer speed + simple magnitude-based acceleration (driver-like feel).
 * Orientation is applied by callers on physical pad only — never soft inject. */
static void scale_rel(int *dx, int *dy) {
    int x = *dx, y = *dy;
    if (!x && !y) return;
    /* percent scale */
    x = (x * mouse_speed_pct) / 100;
    y = (y * mouse_speed_pct) / 100;
    if (mouse_accel > 0) {
        int mag = abs(x) + abs(y);
        /* boost grows with speed; accel 1..3 → up to ~+50/100/150% at mag≈20 */
        int boost = 100 + (mouse_accel * mag * 5);
        if (boost > 350) boost = 350;
        x = (x * boost) / 100;
        y = (y * boost) / 100;
    }
    /* never collapse non-zero to zero when speed is low */
    if (*dx && !x) x = (*dx > 0) ? 1 : -1;
    if (*dy && !y) y = (*dy > 0) ? 1 : -1;
    *dx = x;
    *dy = y;
}

static uint8_t linux_to_hid(unsigned code) {
    switch (code) {
    case KEY_A: return 0x04; case KEY_B: return 0x05; case KEY_C: return 0x06;
    case KEY_D: return 0x07; case KEY_E: return 0x08; case KEY_F: return 0x09;
    case KEY_G: return 0x0a; case KEY_H: return 0x0b; case KEY_I: return 0x0c;
    case KEY_J: return 0x0d; case KEY_K: return 0x0e; case KEY_L: return 0x0f;
    case KEY_M: return 0x10; case KEY_N: return 0x11; case KEY_O: return 0x12;
    case KEY_P: return 0x13; case KEY_Q: return 0x14; case KEY_R: return 0x15;
    case KEY_S: return 0x16; case KEY_T: return 0x17; case KEY_U: return 0x18;
    case KEY_V: return 0x19; case KEY_W: return 0x1a; case KEY_X: return 0x1b;
    case KEY_Y: return 0x1c; case KEY_Z: return 0x1d;
    case KEY_1: return 0x1e; case KEY_2: return 0x1f; case KEY_3: return 0x20;
    case KEY_4: return 0x21; case KEY_5: return 0x22; case KEY_6: return 0x23;
    case KEY_7: return 0x24; case KEY_8: return 0x25; case KEY_9: return 0x26;
    case KEY_0: return 0x27;
    case KEY_ENTER: return 0x28; case KEY_ESC: return 0x29;
    case KEY_BACKSPACE: return 0x2a; case KEY_TAB: return 0x2b;
    case KEY_SPACE: return 0x2c; case KEY_MINUS: return 0x2d;
    case KEY_EQUAL: return 0x2e; case KEY_LEFTBRACE: return 0x2f;
    case KEY_RIGHTBRACE: return 0x30; case KEY_BACKSLASH: return 0x31;
    case KEY_SEMICOLON: return 0x33; case KEY_APOSTROPHE: return 0x34;
    case KEY_GRAVE: return 0x35; case KEY_COMMA: return 0x36;
    case KEY_DOT: return 0x37; case KEY_SLASH: return 0x38;
    case KEY_CAPSLOCK: return 0x39;
    case KEY_F1: return 0x3a; case KEY_F2: return 0x3b; case KEY_F3: return 0x3c;
    case KEY_F4: return 0x3d; case KEY_F5: return 0x3e; case KEY_F6: return 0x3f;
    case KEY_F7: return 0x40; case KEY_F8: return 0x41; case KEY_F9: return 0x42;
    case KEY_F10: return 0x43; case KEY_F11: return 0x44; case KEY_F12: return 0x45;
    case KEY_RIGHT: return 0x4f; case KEY_LEFT: return 0x50;
    case KEY_DOWN: return 0x51; case KEY_UP: return 0x52;
    /* BTN_DPAD_* (0x220+) used by some pads; KEY_UP/DOWN/LEFT/RIGHT already above */
    case 0x220: return 0x52; /* BTN_DPAD_UP */
    case 0x221: return 0x51; /* BTN_DPAD_DOWN */
    case 0x222: return 0x50; /* BTN_DPAD_LEFT */
    case 0x223: return 0x4f; /* BTN_DPAD_RIGHT */
    case 0x224: return 0x28; /* BTN_DPAD_CENTER → Enter */
    case KEY_LEFTCTRL: return 0xe0; case KEY_LEFTSHIFT: return 0xe1;
    case KEY_LEFTALT: return 0xe2; case KEY_LEFTMETA: return 0xe3;
    case KEY_RIGHTCTRL: return 0xe4; case KEY_RIGHTSHIFT: return 0xe5;
    case KEY_RIGHTALT: return 0xe6; case KEY_RIGHTMETA: return 0xe7;
    /* Titan OEM dual reports (decimal scan codes, not 0xNNN hex BTN_DPAD) */
    case 183: case 251: return 0xe0; /* Fn → Left Ctrl on host */
    /* Sym: never host Right Alt. In-bridge Titan layer owns specials (share + exclusive). */
    case 222: case 253:
        return 0;
    /* Titan Back/Home/Recents stay on the phone OS — never HID guest. */
    case KEY_BACK:
    case KEY_HOME:
    case KEY_HOMEPAGE:
    case KEY_MENU:
    case KEY_APPSELECT:
        return 0;
    case KEY_DELETE: return 0x4c;
    case KEY_END: return 0x4d;
    case KEY_PAGEUP: return 0x4b; case KEY_PAGEDOWN: return 0x4e;
    case KEY_INSERT: return 0x49;
    default: return 0;
    }
}

static int is_mod(uint8_t h) { return h >= 0xe0 && h <= 0xe7; }

static int find_by_name(const char *want, char *out, size_t outsz) {
    DIR *d = opendir("/sys/class/input");
    if (!d) return -1;
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (strncmp(de->d_name, "event", 5) != 0) continue;
        char path[256], name[256];
        snprintf(path, sizeof path, "/sys/class/input/%s/device/name", de->d_name);
        FILE *f = fopen(path, "r");
        if (!f) continue;
        if (!fgets(name, sizeof name, f)) { fclose(f); continue; }
        fclose(f);
        size_t n = strlen(name);
        while (n && (name[n-1] == '\n' || name[n-1] == '\r')) name[--n] = 0;
        if (strcmp(name, want) == 0) {
            snprintf(out, outsz, "/dev/input/%s", de->d_name);
            closedir(d);
            return 0;
        }
    }
    closedir(d);
    return -1;
}

/* Prefer orient-mouse (follow=1 path) so we do not EVIOCGRAB virtual-mouse
 * out from under titan2-orient-rel (which would make local pad axes stuck).
 * When require_orient=1, only accept titan2-orient-mouse (caller waits). */
static int find_rel_mouse_ex(char *out, size_t outsz, int require_orient) {
    mouse_pre_oriented = 0;
    if (find_by_name("titan2-orient-mouse", out, outsz) == 0) {
        mouse_pre_oriented = 1;
        return 0;
    }
    if (require_orient)
        return -1;
    {
        const char *names[] = {
            "titan2-virtual-mouse", "titan2-touchpadd", "titan2_touchpadd",
            "Titan2 Touchpad", "Virtual Mouse", "titan2-mouse", NULL
        };
        for (int i = 0; names[i]; i++) {
            if (find_by_name(names[i], out, outsz) == 0)
                return 0;
        }
    }
    /* scan for EV_REL capable non-touchscreen */
    DIR *d = opendir("/sys/class/input");
    if (!d) return -1;
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (strncmp(de->d_name, "event", 5) != 0) continue;
        char path[256];
        snprintf(path, sizeof path, "/sys/class/input/%s/device/capabilities/rel", de->d_name);
        FILE *f = fopen(path, "r");
        if (!f) continue;
        char buf[64];
        if (!fgets(buf, sizeof buf, f)) { fclose(f); continue; }
        fclose(f);
        /* non-zero rel cap */
        if (strspn(buf, "0 \t\n") == strlen(buf)) continue;
        snprintf(path, sizeof path, "/sys/class/input/%s/device/name", de->d_name);
        f = fopen(path, "r");
        char name[128] = "";
        if (f) { fgets(name, sizeof name, f); fclose(f); }
        if (strstr(name, "Touchscreen") || strstr(name, "synaptics") || strstr(name, "gpio"))
            continue;
        /* Never fall through to virtual-mouse via scan when we already skipped
         * it above for require_orient — still skip grabbing virtual here if
         * orient-rel is expected; scan is last-resort only. */
        if (strstr(name, "orient-mouse")) {
            snprintf(out, outsz, "/dev/input/%s", de->d_name);
            closedir(d);
            mouse_pre_oriented = 1;
            return 0;
        }
        if (strstr(name, "titan") || strstr(name, "Titan") || strstr(name, "mouse") ||
            strstr(name, "Mouse") || strstr(name, "touchpad") || strstr(name, "Touchpad")) {
            snprintf(out, outsz, "/dev/input/%s", de->d_name);
            closedir(d);
            return 0;
        }
    }
    closedir(d);
    return -1;
}

static int find_rel_mouse(char *out, size_t outsz) {
    return find_rel_mouse_ex(out, outsz, 0);
}

/* Wait for the right relative mouse without stealing virtual-mouse from
 * titan2-orient-rel when follow_orient is on. */
static int wait_rel_mouse(char *out, size_t outsz) {
    out[0] = '\0';
    mouse_pre_oriented = 0;
    if (follow_orient) {
        /* Orient-rel is either already up or not on this boot. Do not wait 1s
         * per reopen — that is the HID pad lag (log: orient-mouse timeout). */
        if (find_rel_mouse_ex(out, outsz, 1) == 0) {
            fprintf(stderr, "mouse=orient-mouse (pre-rotated)\n");
            return 0;
        }
    }
    /* virt mouse / scan — 5×2ms max so a just-spawned uinput can appear */
    for (int t = 0; t < 5; t++) {
        if (find_rel_mouse_ex(out, outsz, 0) == 0) {
            fprintf(stderr, "mouse=%s pre_orient=%d\n", out, mouse_pre_oriented);
            return 0;
        }
        usleep(2000);
    }
    return -1;
}

static int open_hidg_index(int idx) {
    char path[32];
    snprintf(path, sizeof path, "/dev/hidg%d", idx);
    int fd = open(path, O_RDWR | O_NONBLOCK);
    if (fd >= 0) return fd;
    /* scan */
    DIR *d = opendir("/dev");
    if (!d) return -1;
    struct dirent *de;
    int n = 0;
    while ((de = readdir(d)) != NULL) {
        if (strncmp(de->d_name, "hidg", 4) != 0) continue;
        if (n == idx) {
            snprintf(path, sizeof path, "/dev/%s", de->d_name);
            fd = open(path, O_RDWR | O_NONBLOCK);
            closedir(d);
            return fd;
        }
        n++;
    }
    closedir(d);
    return -1;
}

/* Optional mirror of events for app Bluetooth HID (physical → wireless).
 * Dual-write: tmp is always app-readable (0666). App-private path is best-effort.
 *
 * Mouse path is coalesced (~125 Hz): some host SoCs choke
 * if every EV_REL becomes an interrupt report. Keys still go out immediately. */
static int hw_out_fd = -1;
static int hw_out_fd_app = -1;
static int no_hidg = 0;
/* 1 = BT/soft mirror. USB hidg already sent the key — do not also append
 * hw.out or FGS drainHwOut replays the file as a burst (lag → many keys). */
static int want_hw_out = 0;

/* 0 = flush every input event (lowest lag on Snapdragon hosts). */
#define BT_MOUSE_COALESCE_MS 0
static int bt_mdx, bt_mdy, bt_mwheel;
static uint8_t bt_mbtn;
static int bt_mouse_dirty;
static long long bt_last_flush_ms;
static uint8_t bt_last_btn_sent = 0xff;

static int open_hw_path(const char *path) {
    int fd = open(path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0666);
    if (fd < 0) return -1;
    chmod(path, 0666);
    /* Best-effort: keep app CE dir owned by app so priv_app can drain. */
    if (strstr(path, "com.titanus2.usbhid")) {
        /* u0_a* often 10064 lab; fchown to -1 keeps owner if we only chmod */
        fchmod(fd, 0666);
    }
    return fd;
}

static void open_hw_out(void) {
    /* Prefer world path first so BT drain never depends on CE ownership. */
    if (hw_out_fd < 0) {
        hw_out_fd = open_hw_path("/data/local/tmp/titan2_hid_hw.out");
        if (hw_out_fd >= 0)
            fprintf(stderr, "hw_out=/data/local/tmp/titan2_hid_hw.out\n");
    }
    if (hw_out_fd_app < 0) {
        hw_out_fd_app = open_hw_path(
            "/data/user/0/com.titanus2.usbhid/files/titan2_hid_hw.out");
        if (hw_out_fd_app < 0)
            hw_out_fd_app = open_hw_path(
                "/data/data/com.titanus2.usbhid/files/titan2_hid_hw.out");
        if (hw_out_fd_app >= 0)
            fprintf(stderr, "hw_out_app=ce\n");
    }
    if (hw_out_fd < 0 && hw_out_fd_app < 0)
        fprintf(stderr, "hw_out open fail\n");
}

static void hw_out4(uint8_t a, uint8_t b, uint8_t c, uint8_t d) {
    if (!want_hw_out && !no_hidg)
        return;
    uint8_t r[4] = { a, b, c, d };
    int any = 0;
    if (hw_out_fd < 0 && hw_out_fd_app < 0) open_hw_out();
    if (hw_out_fd >= 0) {
        if (write(hw_out_fd, r, 4) == 4) any = 1;
        else {
            close(hw_out_fd);
            hw_out_fd = -1;
        }
    }
    if (hw_out_fd_app >= 0) {
        if (write(hw_out_fd_app, r, 4) == 4) any = 1;
        else {
            close(hw_out_fd_app);
            hw_out_fd_app = -1;
        }
    }
    if (!any) {
        open_hw_out();
        if (hw_out_fd >= 0) (void)write(hw_out_fd, r, 4);
        if (hw_out_fd_app >= 0) (void)write(hw_out_fd_app, r, 4);
    }
}

static int8_t clamp_i8_bt(int v) {
    if (v > 127) return 127;
    if (v < -127) return -127;
    return (int8_t)v;
}

/*
 * BT mouse = latest-wins, never a FIFO.
 * Symptom we fix: "mouse recorded then played a second later" on Snapdragon
 * hosts when hw.out was an append queue drained in order.
 *
 * Format of /data/local/tmp/titan2_hid_mouse.mbx (16 bytes, little-endian):
 *   magic 'M''B''X''1' | int32 dx | int32 dy | int16 wheel | u8 buttons | u8 seq
 * Writer ADDS into the slot; reader zeros after take (see Java drainMailbox).
 */
#define MOUSE_MBX "/data/misc/titan2/titan2_hid_mouse.mbx"
#define MOUSE_MBX2 "/data/user/0/com.titanus2.usbhid/files/titan2_hid_mouse.mbx"

/* Keep mailbox FDs open — open/close every event was multi-ms lag. */
static int mouse_mbx_fd = -1;
static int mouse_mbx2_fd = -1; /* app-private mirror the Java drain reads */
/* Abstract unix DGRAM to app for zero-copy-ish mouse (rootless). */
static int mouse_sock = -1;
static struct sockaddr_un mouse_sock_addr;
static int mouse_sock_ready = 0;

static void mouse_sock_init(void) {
    if (mouse_sock >= 0) return;
    mouse_sock = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (mouse_sock < 0) return;
    memset(&mouse_sock_addr, 0, sizeof mouse_sock_addr);
    mouse_sock_addr.sun_family = AF_UNIX;
    /* abstract name: leading \0 */
    mouse_sock_addr.sun_path[0] = '\0';
    memcpy(mouse_sock_addr.sun_path + 1, "titan2_bt_mouse", 15);
    mouse_sock_ready = 1;
}

/*
 * 1.96 B6: accumulate from the same slot Java zeros after take (app-private).
 * Pre-1.96 read /data/misc only while Java zeroed only app-private → already-sent
 * motion was re-added every flush (rubber-band / Snapdragon lag residual).
 */
static int mbx_read_slot(int fd, int32_t *odx, int32_t *ody, int16_t *owh,
                         uint8_t *seq) {
    uint8_t buf[16];
    if (fd < 0) return 0;
    if (lseek(fd, 0, SEEK_SET) < 0) return 0;
    if (read(fd, buf, 16) < 16) return 0;
    if (buf[0] != 'M' || buf[1] != 'B' || buf[2] != 'X' || buf[3] != '1')
        return 0;
    memcpy(odx, buf + 4, 4);
    memcpy(ody, buf + 8, 4);
    memcpy(owh, buf + 12, 2);
    *seq = buf[15];
    return 1;
}

static void bt_mouse_mailbox_add(int dx, int dy, int wheel, uint8_t buttons) {
    if (mouse_mbx_fd < 0) {
        mkdir("/data/misc/titan2", 0777);
        chmod("/data/misc/titan2", 0777);
        mouse_mbx_fd = open(MOUSE_MBX, O_RDWR | O_CREAT | O_CLOEXEC, 0666);
        if (mouse_mbx_fd >= 0) {
            fchmod(mouse_mbx_fd, 0666);
            /* ensure size */
            uint8_t z[16] = { 'M','B','X','1',0,0,0,0,0,0,0,0,0,0,0,0 };
            if (lseek(mouse_mbx_fd, 0, SEEK_END) < 16) {
                (void)lseek(mouse_mbx_fd, 0, SEEK_SET);
                (void)write(mouse_mbx_fd, z, 16);
            }
        }
    }
    /* App-private SoT — open before read so accumulate matches Java drain. */
    if (mouse_mbx2_fd < 0) {
        mouse_mbx2_fd = open(MOUSE_MBX2, O_RDWR | O_CREAT | O_CLOEXEC, 0666);
        if (mouse_mbx2_fd >= 0) fchmod(mouse_mbx2_fd, 0666);
    }
    int32_t odx = 0, ody = 0;
    int16_t owh = 0;
    uint8_t seq = 0;
    uint8_t buf[16];
    memset(buf, 0, sizeof buf);
    /* Prefer app-private (Java zeros after take). Misc is mirror only. */
    if (!mbx_read_slot(mouse_mbx2_fd, &odx, &ody, &owh, &seq)) {
        (void)mbx_read_slot(mouse_mbx_fd, &odx, &ody, &owh, &seq);
    }
    odx += dx;
    ody += dy;
    owh = (int16_t)(owh + wheel);
    /* Cap ≈ 4×int8 so Java residual reflush (≤4 packets) can carry distance
     * without a multi-second FIFO. Higher saturates (latest-wins). */
    if (odx > 508) odx = 508;
    if (odx < -508) odx = -508;
    if (ody > 508) ody = 508;
    if (ody < -508) ody = -508;
    seq++;
    buf[0] = 'M'; buf[1] = 'B'; buf[2] = 'X'; buf[3] = '1';
    memcpy(buf + 4, &odx, 4);
    memcpy(buf + 8, &ody, 4);
    memcpy(buf + 12, &owh, 2);
    buf[14] = buttons;
    buf[15] = seq;
    /* Write app-private SoT first (Java drain), then misc mirror. */
    if (mouse_mbx2_fd >= 0) {
        (void)lseek(mouse_mbx2_fd, 0, SEEK_SET);
        if (write(mouse_mbx2_fd, buf, 16) != 16) {
            /* CE path may have been recreated after wipe — reopen once */
            close(mouse_mbx2_fd);
            mouse_mbx2_fd = open(MOUSE_MBX2, O_RDWR | O_CREAT | O_CLOEXEC, 0666);
            if (mouse_mbx2_fd >= 0) {
                fchmod(mouse_mbx2_fd, 0666);
                (void)lseek(mouse_mbx2_fd, 0, SEEK_SET);
                (void)write(mouse_mbx2_fd, buf, 16);
            }
        }
    }
    if (mouse_mbx_fd >= 0) {
        (void)lseek(mouse_mbx_fd, 0, SEEK_SET);
        (void)write(mouse_mbx_fd, buf, 16);
    }
    /* Also fire datagram so app can wake immediately (if listening) */
    mouse_sock_init();
    if (mouse_sock_ready && mouse_sock >= 0) {
        uint8_t pkt[8];
        int8_t sx = (odx > 127) ? 127 : (odx < -127) ? -127 : (int8_t)odx;
        int8_t sy = (ody > 127) ? 127 : (ody < -127) ? -127 : (int8_t)ody;
        int8_t sw = (owh > 127) ? 127 : (owh < -127) ? -127 : (int8_t)owh;
        pkt[0] = 0x02; pkt[1] = (uint8_t)sx; pkt[2] = (uint8_t)sy; pkt[3] = buttons;
        pkt[4] = 0x04; pkt[5] = (uint8_t)sw; pkt[6] = seq; pkt[7] = 0;
        socklen_t alen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + 15);
        (void)sendto(mouse_sock, pkt, 8, 0,
                     (struct sockaddr *)&mouse_sock_addr, alen);
    }
}

/* Flush pending BT mouse: mailbox only (no hw.out FIFO for motion). */
static void bt_mouse_flush(int force) {
    long long n = now_ms();
    if (!bt_mouse_dirty) return;
    if (!force && (n - bt_last_flush_ms) < BT_MOUSE_COALESCE_MS
            && bt_mbtn == bt_last_btn_sent)
        return;
    int dx = bt_mdx, dy = bt_mdy, wh = bt_mwheel;
    uint8_t btn = bt_mbtn;
    bt_mdx = bt_mdy = bt_mwheel = 0;
    bt_mouse_dirty = 0;
    bt_last_btn_sent = btn;
    bt_last_flush_ms = n;
    if (dx || dy || wh || btn != 0xff)
        bt_mouse_mailbox_add(dx, dy, wh, btn);
    /* Keep hw.out free of mouse FIFO — only keys use append path.
     * Truncate if something old left a backlog (legacy). */
    if (hw_out_fd >= 0) {
        struct stat st;
        if (fstat(hw_out_fd, &st) == 0 && st.st_size > 64) {
            (void)ftruncate(hw_out_fd, 0);
        }
    }
}

static void bt_mouse_queue(uint8_t buttons, int dx, int dy, int wheel) {
    int btn_edge = (buttons != bt_mbtn);
    bt_mbtn = buttons;
    bt_mdx += dx;
    bt_mdy += dy;
    bt_mwheel += wheel;
    bt_mouse_dirty = 1;
    /* Saturate early flush so we don't lose motion past int16 */
    if (bt_mdx > 1000 || bt_mdx < -1000 || bt_mdy > 1000 || bt_mdy < -1000
            || btn_edge || wheel)
        bt_mouse_flush(1);
    else
        bt_mouse_flush(0);
}

/* Consecutive hidg write failures (host unplugged) — release exclusive grab. */
static int hidg_fail_streak = 0;
static int grab_released_for_host = 0;

static int send_kbd(int fd, uint8_t mods, const uint8_t keys[6]) {
    int ok = 0;
    if (fd >= 0) {
        uint8_t r[8] = { mods, 0, keys[0], keys[1], keys[2], keys[3], keys[4], keys[5] };
        ssize_t w = write(fd, r, 8);
        if (w != 8) {
            usleep(1000);
            w = write(fd, r, 8);
        }
        if (w == 8) {
            ok = 1;
            hidg_fail_streak = 0;
        } else {
            hidg_fail_streak++;
        }
    }
    /* Soft inject / socket path must not fake success without writing USB. */
    if (fd < 0 && hw_out_fd < 0 && hw_out_fd_app < 0) return -1;
    if (fd < 0 && (hw_out_fd >= 0 || hw_out_fd_app >= 0)) {
        /* nohidg BT-only: caller should also hw_out4 per-key; mark ok if open */
        ok = 1;
    }
    return ok ? 0 : -1;
}

/* Open / close TitanKey. Grab is a live ioctl — do not close to switch owners. */
static int open_key_fd(const char *keypath, int grab) {
    if (!keypath || !keypath[0]) return -1;
    int fd = open(keypath, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -1;
    key_grabbed = 0;
    if (grab) {
        /* EVIOCGRAB is exclusive: EBUSY (16) = dual owner (second hid_bridge /
         * unpatched touchpadd / side-watch). Retry once after short wait so a
         * peer that is exiting can release; never silently read without grab
         * (phone+host dual-type residual). */
        int tries = 0;
        for (;;) {
            if (ioctl(fd, EVIOCGRAB, 1) == 0) {
                key_grabbed = 1;
                fprintf(stderr, "EXCLUSIVE grab key=%s\n", keypath);
                break;
            }
            int e = errno;
            if (e == EBUSY && tries < 1) {
                fprintf(stderr,
                    "key grab EBUSY errno=%d — dual-owner, retry once\n", e);
                usleep(50 * 1000);
                tries++;
                continue;
            }
            fprintf(stderr,
                "key grab fail errno=%d%s — exclusive may dual-type phone\n",
                e, e == EBUSY ? " (EBUSY dual-owner)" : "");
            break;
        }
    } else {
        fprintf(stderr, "nograb key=%s (android)\n", keypath);
    }
    return fd;
}

static void flush_guest_kbd(int hid_k, uint8_t *mods, uint8_t keys[6]) {
    if (mods) *mods = 0;
    if (keys) memset(keys, 0, 6);
    {
        uint8_t z6[6] = {0};
        if (hid_k >= 0) send_kbd(hid_k, 0, z6);
        hw_out4(0x01, 0, 0, 0);
    }
}

static int set_key_grab(int kfd, int want) {
    if (kfd < 0) return -1;
    if (want == key_grabbed) return 0;
    if (ioctl(kfd, EVIOCGRAB, want ? 1 : 0) == 0) {
        key_grabbed = want ? 1 : 0;
        return 0;
    }
    fprintf(stderr, "key grab %d fail errno=%d\n", want, errno);
    return -1;
}

/* Share hub: guest gets keys unless an Android editor is focused.
 * Exclusive: always guest. Never close the fd — pad stays grabbed. */
static void apply_key_route(int kfd, int grab_mode, int hid_k,
        uint8_t *mods, uint8_t keys[6]) {
    int want_guest = 1;
    if (!grab_mode && (local_input_pause || keys_host_pause))
        want_guest = 0;
    keys_emit = want_guest;
    if (kfd < 0) return;
    if (want_guest) {
        if (set_key_grab(kfd, 1) == 0)
            fprintf(stderr, "keys → guest (hub)\n");
    } else {
        flush_guest_kbd(hid_k, mods, keys);
        specials_mod_mask = 0;
        if (set_key_grab(kfd, 0) == 0)
            fprintf(stderr, "keys → android (hub, pad stays guest)\n");
    }
    fflush(stderr);
}

static void close_key_fd(int *kfd, int grab, int hid_k, uint8_t *mods, uint8_t keys[6]) {
    if (!kfd || *kfd < 0) return;
    flush_guest_kbd(hid_k, mods, keys);
    phys_keys_held = 0;
    if (key_grabbed || grab) ioctl(*kfd, EVIOCGRAB, 0);
    key_grabbed = 0;
    keys_emit = 0;
    close(*kfd);
    *kfd = -1;
    fprintf(stderr, "keys fd closed\n");
}

static void write_plane_digit(const char *name, int one) {
    char path[160];
    const char *dirs[] = { "/data/local/tmp", "/data/misc/titan2", NULL };
    int i;
    for (i = 0; dirs[i]; i++) {
        int n = snprintf(path, sizeof path, "%s/%s", dirs[i], name);
        FILE *f;
        if (n <= 0 || n >= (int)sizeof path) continue;
        f = fopen(path, "w");
        if (!f) continue;
        fputc(one ? '1' : '0', f);
        fclose(f);
        chmod(path, 0666);
    }
}

static int send_mouse(int fd, uint8_t buttons, int8_t dx, int8_t dy, int8_t wheel) {
    int ok = 0;
    /* USB gadget: immediate report (host is wired, can take high rate). */
    if (fd >= 0) {
        uint8_t r[4] = { buttons, (uint8_t)dx, (uint8_t)dy, (uint8_t)wheel };
        ssize_t w = write(fd, r, 4);
        if (w != 4) {
            usleep(500);
            w = write(fd, r, 4);
        }
        if (w == 4) ok = 1;
    }
    /* BT path: coalesce — never append every micro-move to hw.out. */
    if (hw_out_fd >= 0 || hw_out_fd_app >= 0 || fd < 0) {
        open_hw_out();
        bt_mouse_queue(buttons, (int)dx, (int)dy, (int)wheel);
        ok = 1;
    }
    if (fd < 0 && hw_out_fd < 0 && hw_out_fd_app < 0) return -1;
    return ok ? 0 : -1;
}

static int8_t clamp_i8(int v) {
    if (v > 127) return 127;
    if (v < -127) return -127;
    return (int8_t)v;
}

/* Apply speed/accel and emit HID reports.
 * USB: multi-packet for large deltas. BT: summed into coalesce queue once. */
static int emit_mouse(int fd, uint8_t buttons, int dx, int dy, int wheel, int apply_feel) {
    if (fd < 0 && hw_out_fd < 0 && hw_out_fd_app < 0) {
        open_hw_out();
        if (hw_out_fd < 0 && hw_out_fd_app < 0) return -1;
    }
    if (apply_feel) scale_rel(&dx, &dy);
    /* BT-only (no hidg): one queue call with full deltas — coalesce handles int8. */
    if (fd < 0) {
        open_hw_out();
        bt_mouse_queue(buttons, dx, dy, wheel);
        return 0;
    }
    int guard = 0;
    int ok = 0;
    /* Sum for BT while still splitting USB */
    int sum_dx = dx, sum_dy = dy, sum_wh = wheel;
    do {
        int8_t sx = clamp_i8(dx); dx -= sx;
        int8_t sy = clamp_i8(dy); dy -= sy;
        int8_t sw = clamp_i8(wheel); wheel -= sw;
        /* USB write only here — do not double-queue BT per slice */
        if (fd >= 0) {
            uint8_t r[4] = { buttons, (uint8_t)sx, (uint8_t)sy, (uint8_t)sw };
            ssize_t w = write(fd, r, 4);
            if (w != 4) {
                usleep(500);
                w = write(fd, r, 4);
            }
            if (w == 4) ok = 1;
            else return -1;
        }
        if (++guard > 16) break;
    } while (dx || dy || wheel);
    /* One BT coalesce sample for the whole motion */
    open_hw_out();
    bt_mouse_queue(buttons, sum_dx, sum_dy, sum_wh);
    ok = 1;
    return ok ? 0 : -1;
}

/* Always EVIOCGRAB virtual mouse while session mouse is on (share + exclusive).
 * Share only softens *keys* so the phone keyboard still works for navigation;
 * the pad must not move Android UI. local_input_pause closes this fd instead.
 *
 * Never return a non-grabbed fd: shared open looks "working" but the phone
 * cursor still moves and HID appears to "not always grab" the pad. */
static int open_rel_mouse_grab(const char *relpath) {
    if (!relpath || !relpath[0]) return -1;
    int fd = open(relpath, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -1;
    int gerr = 0;
    for (int attempt = 0; attempt < 25; attempt++) {
        if (ioctl(fd, EVIOCGRAB, 1) == 0) {
            fprintf(stderr, "EXCLUSIVE grab driver mouse=%s (host only)\n", relpath);
            return fd;
        }
        gerr = errno;
        usleep(2000);
    }
    fprintf(stderr, "driver mouse grab FAIL path=%s errno=%d (closed, not shared)\n",
            relpath, gerr);
    close(fd);
    return -1;
}

/* Open the preferred mouse node with exclusive grab. On EBUSY of virtual-mouse
 * (orient-rel owns it), wait for titan2-orient-mouse and grab that instead. */
static int open_best_rel_mouse_grab(char *relpath, size_t relsz) {
    load_orient();
    if (!relpath[0]) {
        if (wait_rel_mouse(relpath, relsz) != 0)
            return -1;
    }
    int fd = open_rel_mouse_grab(relpath);
    if (fd >= 0)
        return fd;
    /* Stale path or race: rediscover (prefer orient-mouse when follow on). */
    relpath[0] = '\0';
    if (wait_rel_mouse(relpath, relsz) != 0)
        return -1;
    return open_rel_mouse_grab(relpath);
}

static void close_mouse_fd(int *fd, int was_grabbed) {
    if (!fd || *fd < 0) return;
    if (was_grabbed) ioctl(*fd, EVIOCGRAB, 0);
    close(*fd);
    *fd = -1;
}

/* Re-open virtual mouse if node vanished (touchpadd restart). Always grab.
 * Grab the NEW node first, then release the old — never ungrab into a gap
 * where Android InputReader steals the pad (host mouse "unplugs" → phone cursor). */
static int reopen_rel_mouse(int old_fd, int grab, char *relpath, size_t relsz) {
    (void)grab; /* mouse is always exclusive while session mouse is on */
    char newpath[256];
    newpath[0] = '\0';
    int nfd = open_best_rel_mouse_grab(newpath, sizeof newpath);
    if (nfd >= 0) {
        if (old_fd >= 0) {
            ioctl(old_fd, EVIOCGRAB, 0);
            close(old_fd);
        }
        if (relpath && relsz) {
            strncpy(relpath, newpath, relsz - 1);
            relpath[relsz - 1] = '\0';
        }
        return nfd;
    }
    /* Failed to open new — keep old grab if still usable */
    if (old_fd >= 0)
        return old_fd;
    if (relpath) relpath[0] = '\0';
    return -1;
}

static int reopen_hidg(int old_fd, int idx) {
    if (old_fd >= 0) close(old_fd);
    return open_hidg_index(idx);
}

static int setup_sock_fs(const char *path) {
    unlink(path);
    int s = socket(AF_UNIX, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (s < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
    if (bind(s, (struct sockaddr *)&addr, sizeof addr) != 0) {
        close(s);
        return -1;
    }
    chmod(path, 0666);
    return s;
}

/* Abstract namespace: apps can often send without SELinux path denials. */
static int setup_sock_abs(const char *name) {
    int s = socket(AF_UNIX, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (s < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    /* sun_path[0]=0 marks abstract; name follows */
    size_t n = strlen(name);
    if (n + 1 >= sizeof(addr.sun_path)) { close(s); return -1; }
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, name, n);
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + n);
    if (bind(s, (struct sockaddr *)&addr, len) != 0) {
        close(s);
        return -1;
    }
    return s;
}


static void apply_key(uint8_t *mods, uint8_t keys[6], uint8_t m, uint8_t h, uint8_t press) {
    if (is_mod(h)) {
        uint8_t bit = 1u << (h - 0xe0);
        if (press) *mods |= bit; else *mods &= (uint8_t)~bit;
        return;
    }
    if (press) {
        int have = 0;
        for (int i = 0; i < 6; i++) if (keys[i] == h) have = 1;
        if (!have) for (int i = 0; i < 6; i++) if (!keys[i]) { keys[i] = h; break; }
    } else {
        for (int i = 0; i < 6; i++) if (keys[i] == h) {
            for (int j = i; j < 5; j++) keys[j] = keys[j+1];
            keys[5] = 0; break;
        }
    }
    /* Soft inject m bits OR in on press. Never assign m on a letter/tab —
     * that wiped held physical/virtual Alt so Alt+Tab became bare Tab.
     * Soft Shift+letter still clears via a later e1 release packet. */
    if (press && m)
        *mods |= m;
}

static const char *inj_paths[] = {
    /* OS control plane first, then app-owned, then legacy */
    "/data/misc/titan2/titan2_hid.inj",
    "/data/user/0/com.titanus2.usbhid/files/titan2_hid.inj",
    "/data/data/com.titanus2.usbhid/files/titan2_hid.inj",
    "/sdcard/Android/data/com.titanus2.usbhid/files/titan2_hid.inj",
    "/data/media/0/Android/data/com.titanus2.usbhid/files/titan2_hid.inj",
    INJ_PATH,
    NULL
};

static int drain_inj_one(const char *path, int hid_k, int hid_m,
                         uint8_t *mods, uint8_t keys[6], uint8_t *buttons) {
    int fd = open(path, O_RDONLY | O_NONBLOCK);
    if (fd < 0) return 0;
    uint8_t buf[512];
    ssize_t n = read(fd, buf, sizeof buf);
    close(fd);
    /* truncate after read */
    int w = open(path, O_WRONLY | O_TRUNC | O_CREAT, 0666);
    if (w >= 0) {
        close(w);
        chmod(path, 0666);
    }
    if (n < 4) return 0;
    int handled = 0;
    for (ssize_t off = 0; off + 4 <= n; off += 4) {
        uint8_t *p = buf + off;
        switch (p[0]) {
        case 0x01:
            apply_key(mods, keys, p[1], p[2], p[3]);
            send_kbd(hid_k, *mods, keys);
            /* Mirror to BT only when there is no USB hidg — app already
             * handlePacket()s BT when Link has BT, and double-feed races case. */
            if (no_hidg || hid_k < 0)
                hw_out4(0x01, p[1], p[2], p[3]);
            if (p[3]) {
                note_typing();
                /* Activity stamp only — light_keyled throttled inside bump */
                bump_key_activity();
            }
            handled++;
            break;
        case 0x02:
            if (!mouse_blocked_by_typing())
                emit_mouse(hid_m >= 0 ? hid_m : -1, p[3], (int8_t)p[1], (int8_t)p[2], 0, 1);
            handled++;
            break;
        case 0x03:
            if (!mouse_blocked_by_typing()) {
                *buttons = p[1];
                emit_mouse(hid_m >= 0 ? hid_m : -1, *buttons, 0, 0, 0, 0);
            }
            handled++;
            break;
        case 0x04:
            if (!mouse_blocked_by_typing())
                emit_mouse(hid_m >= 0 ? hid_m : -1, *buttons, 0, 0, (int8_t)p[1], 0);
            handled++;
            break;
        default:
            break;
        }
    }
    return handled;
}

static int drain_inj(int hid_k, int hid_m, uint8_t *mods, uint8_t keys[6], uint8_t *buttons) {
    int total = 0;
    for (int i = 0; inj_paths[i]; i++)
        total += drain_inj_one(inj_paths[i], hid_k, hid_m, mods, keys, buttons);
    return total;
}

int main(int argc, char **argv) {
    int grab = 1;
    int mouse_on = 1;
    int keys_on = 1;
    int hw_out = 0;
    const char *sock_path = "/data/local/tmp/titan2_hid.sock";
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--nograb")) grab = 0;
        else if (!strcmp(argv[i], "--grab")) grab = 1;
        else if (!strcmp(argv[i], "--nomouse")) mouse_on = 0;
        else if (!strcmp(argv[i], "--mouse")) mouse_on = 1;
        else if (!strcmp(argv[i], "--nokeys")) keys_on = 0;
        else if (!strcmp(argv[i], "--keys")) keys_on = 1;
        else if (!strcmp(argv[i], "--nohidg")) no_hidg = 1;
        else if (!strcmp(argv[i], "--hw-out")) hw_out = 1;
        else if (!strcmp(argv[i], "--sock") && i + 1 < argc) sock_path = argv[++i];
        else if (!strcmp(argv[i], "--typing-ms") && i + 1 < argc) typing_guard_ms = atoi(argv[++i]);
    }
    /* Control file can also enable hw-out (BT mirror) without CLI flag */
    {
        int v = read_int_file("/data/misc/titan2/titan2_usb_hid_hw_out",
                              "/data/local/tmp/titan2_usb_hid_hw_out", 0);
        if (v) hw_out = 1;
    }
    want_hw_out = hw_out;
    if (want_hw_out) open_hw_out();
    load_typing_ms();
    load_mouse_feel();
    load_orient();
    if (typing_guard_ms < 0) typing_guard_ms = 0;
    if (typing_guard_ms > 5000) typing_guard_ms = 5000;
    fprintf(stderr, "typing_guard_ms=%d speed=%d%% accel=%d follow=%d rot=%d\n",
            typing_guard_ms, mouse_speed_pct, mouse_accel, follow_orient, display_rotation);

    signal(SIGINT, on_sig);
    signal(SIGTERM, on_sig);
    signal(SIGHUP, on_sig);

    char keypath[128] = "", padpath[128] = "", relpath[128] = "";
    find_by_name("TitanKey", keypath, sizeof keypath);
    find_by_name("touchPad", padpath, sizeof padpath);
    /* Share driver with pad-agent: wait for orient-mouse when follow=1 so we
     * do not EVIOCGRAB titan2-virtual-mouse out from under orient-rel. */
    (void)wait_rel_mouse(relpath, sizeof relpath);

    int kfd = -1, pfd = -1, rfd = -1;
    local_input_pause = effective_local_input_pause(grab);
    load_specials_method(grab);
    load_char_mod_owner();
    specials_mod_mask = 0;
    /*
     * Exclusive: specials mapped in-bridge (Titan layer → US HID) always
     * (0.16.14; plane specials_method is phone-only kcm default). Sticky
     * inject_pause / keys_pause must not keep TitanKey closed.
     */
    if (grab && specials_inject_mode) {
        write_plane_digit("titan2_specials_inject_pause", 0);
        {
            FILE *f = fopen("/data/local/tmp/titan2_host_layout", "r");
            char buf[32] = {0};
            int layout_on = 0;
            if (f) {
                if (fgets(buf, sizeof buf, f)) {
                    char *p = buf;
                    while (*p == ' ' || *p == '\t') p++;
                    if (p[0] && strncmp(p, "off", 3) && strncmp(p, "0", 1)
                            && strncmp(p, "none", 4) && strncmp(p, "inherit", 7))
                        layout_on = 1;
                }
                fclose(f);
            }
            if (!layout_on) {
                write_plane_digit("titan2_host_layout_keys_pause", 0);
                write_plane_digit("titan2_usb_hid_keys_pause", 0);
                write_plane_digit("titan2_usb_hid_keys", 1);
            }
        }
        keys_on = 1; /* force open for exclusive inject map */
        fprintf(stderr,
            "exclusive inject — TitanKey open, specials layer in-bridge (owner=%s)\n",
            specials_owner_alt ? "alt" : "sym");
        fflush(stderr);
    }
    keys_host_pause = load_keys_host_pause();
    /* exclusive inject: never honor keys_host_pause for opening (map needs grab) */
    if (grab && specials_inject_mode)
        keys_host_pause = 0;
    if (keypath[0] && keys_on && !keys_host_pause) {
        /* Share: grab keys for guest unless an editor is already focused. */
        int want_g = grab || !local_input_pause;
        kfd = open_key_fd(keypath, want_g);
        keys_emit = want_g;
        if (local_input_pause)
            fprintf(stderr, "keys → android at start (editor, pad stays guest)\n");
    } else if (keypath[0] && keys_on && keys_host_pause) {
        fprintf(stderr, "keys paused — specials inject / keys_pause (mouse stays)\n");
    } else if (keypath[0] && !keys_on) {
        fprintf(stderr, "nokeys — TitanKey stays on phone (soft inject only)\n");
    }
    /*
     * Pad is ALWAYS exclusive-grabbed while session mouse is on.
     * Share yields only TitanKey to Android editors — never the pad.
     * Do NOT grab raw touchPad when virtual mouse exists (starves touchpadd).
     */
    if (mouse_on) {
        rfd = open_best_rel_mouse_grab(relpath, sizeof relpath);
        if (rfd < 0 && padpath[0]) {
            pfd = open(padpath, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
            if (pfd >= 0) {
                int gok = 0;
                for (int t = 0; t < 25; t++) {
                    if (ioctl(pfd, EVIOCGRAB, 1) == 0) { gok = 1; break; }
                    usleep(2000);
                }
                if (gok)
                    fprintf(stderr, "EXCLUSIVE raw pad fallback=%s\n", padpath);
                else {
                    fprintf(stderr, "raw pad grab fail errno=%d\n", errno);
                    close(pfd);
                    pfd = -1;
                }
            }
        }
        fprintf(stderr, "mode=%s keys=%s raw=%d rel=%d path=%s pre_orient=%d\n",
                grab ? "exclusive" : "share",
                keys_emit ? "guest" : "android",
                pfd >= 0, rfd >= 0, relpath[0] ? relpath : "-", mouse_pre_oriented);
    }

    int hid_k = -1, hid_m = -1;
    for (int t = 0; t < 50 && (hid_k < 0 || hid_m < 0); t++) {
        if (hid_k < 0) hid_k = open_hidg_index(0);
        if (hid_m < 0) hid_m = open_hidg_index(1);
        /* single hidg: mouse may share if only one — still open same for inject fail soft */
        if (hid_k >= 0 && hid_m < 0 && t > 20) {
            /* only one function — mouse optional */
            break;
        }
        usleep(100000);
    }
    fprintf(stderr, "hidg k=%d m=%d grab=%d mouse=%d keys=%d nohidg=%d hw_out=%d\n",
            hid_k, hid_m, grab, mouse_on, keys_on, no_hidg,
            (hw_out_fd >= 0 || hw_out_fd_app >= 0));
    if (hid_k < 0 && !no_hidg && hw_out_fd < 0 && hw_out_fd_app < 0) {
        fprintf(stderr, "no keyboard hidg\n");
        return 2;
    }
    if (hid_k < 0 && no_hidg)
        fprintf(stderr, "nohidg mode — physical → hw_out/BT only\n");

    int sfd = setup_sock_fs(sock_path);
    if (sfd >= 0) fprintf(stderr, "sock=%s\n", sock_path);
    else fprintf(stderr, "sock fail %s errno=%d\n", sock_path, errno);
    int sfd_abs = setup_sock_abs("titan2_hid");
    if (sfd_abs >= 0) fprintf(stderr, "sock=@titan2_hid\n");
    else fprintf(stderr, "abs sock fail errno=%d\n", errno);

    uint8_t mods = 0, keys[6] = {0};
    uint8_t buttons = 0;
    int last_x = -1, last_y = -1, contact = 0;
    if (pfd >= 0)
        seed_raw_pad_contact(pfd, &contact, &last_x, &last_y);
    int scale = 2; /* raw touchpad → base mouse gain (speed slider multiplies later) */
    int pad_down_x = -1, pad_down_y = -1, pad_moved = 0;
    struct timespec pad_down_ts = {0, 0};
    (void)pad_down_x;
    (void)pad_down_y;

    {
        for (int i = 0; inj_paths[i]; i++) {
            int ij = open(inj_paths[i], O_WRONLY | O_CREAT | O_TRUNC, 0666);
            if (ij >= 0) close(ij);
            chmod(inj_paths[i], 0666);
        }
    }
    fprintf(stderr, "bridge start keys_on=%d local_input=%d\n", keys_on, local_input_pause);
    while (g_run) {
        struct pollfd pf[6];
        int np = 0;
        int i_k = -1, i_p = -1, i_r = -1, i_s = -1, i_sa = -1;
        if (kfd >= 0) { i_k = np; pf[np].fd = kfd; pf[np].events = POLLIN; np++; }
        if (pfd >= 0) { i_p = np; pf[np].fd = pfd; pf[np].events = POLLIN; np++; }
        if (rfd >= 0) { i_r = np; pf[np].fd = rfd; pf[np].events = POLLIN; np++; }
        if (sfd >= 0) { i_s = np; pf[np].fd = sfd; pf[np].events = POLLIN; np++; }
        if (sfd_abs >= 0) { i_sa = np; pf[np].fd = sfd_abs; pf[np].events = POLLIN; np++; }
        int pr = poll(pf, np, 8);
        if (pr < 0) {
            if (errno == EINTR) continue;
            break;
        }
        /* Always flush pending BT mouse on poll tick (~8ms) so Snapdragon
         * hosts see a steady ~125 Hz stream, never a multi-second backlog. */
        bt_mouse_flush(0);

        /* Typing edge: freeze host mouse + cancel right-click hold immediately
         * even if pad is quiet (no EV_SYN). Hold-key stays blocked via
         * phys_keys_held; cooldown after last key via typing_guard_ms. */
        {
            int blk = mouse_blocked_by_typing();
            if (blk && !mouse_was_typing_blocked) {
                if (buttons)
                    release_host_mouse_buttons(hid_m, &buttons);
            }
            if (blk && buttons) {
                /* Sticky button while still blocked (pad keeps BTN_RIGHT down). */
                release_host_mouse_buttons(hid_m, &buttons);
            }
            mouse_was_typing_blocked = blk;
        }

        /* keyboard */
        if (i_k >= 0 && (pf[i_k].revents & POLLIN)) {
            struct input_event ev;
            while (read(kfd, &ev, sizeof ev) == (ssize_t)sizeof ev) {
                if (ev.type != EV_KEY) continue;
                /*
                 * Upper-row Back / Home (short 580) / Recents (long 580):
                 * always Titan OS in exclusive and share. Never HID guest
                 * (Quest ESC / host Home). If TitanKey is grabbed, re-inject
                 * via KEY_FIRE / phone-nav. If not, OS already sees them —
                 * do not dual-fire.
                 */
                if (is_phone_nav_key(ev.code)) {
                    if (key_grabbed)
                        (void)handle_phone_nav_exclusive((unsigned)ev.code, ev.value);
                    continue;
                }
                /* Autorepeat (value=2): keep typing-lock alive for hold-Backspace;
                 * do not re-map the key. Also cancel sticky pad right-click. */
                if (ev.value == 2) {
                    note_typing();
                    if (buttons)
                        release_host_mouse_buttons(hid_m, &buttons);
                    continue;
                }
                if (ev.value == 1) {
                    phys_keys_held++;
                    if (phys_keys_held < 1) phys_keys_held = 1;
                    bump_key_activity();
                    note_typing();
                    /* Finger on keyboard surface while key down must not keep
                     * host right-click / drag from pad long-press. */
                    if (buttons)
                        release_host_mouse_buttons(hid_m, &buttons);
                } else if (ev.value == 0) {
                    if (phys_keys_held > 0) phys_keys_held--;
                    note_typing(); /* cooldown after release still blocks palm */
                }
                if (!keys_emit) {
                    /* Android owns TitanKey; keep sampling for pad typing lock. */
                    continue;
                }
                /*
                 * Host HID specials: one map, share and exclusive.
                 * Titan printed layer → US HID (host boot protocol is US, not
                 * TitanKey.kcm). Share keeps TitanKey ungrabbed so the phone
                 * still gets KCM specials; host never sees raw RAlt+letter.
                 * Never close TitanKey on share Sym (remote_q / inject_pause
                 * is phone-path only).
                 */
                if (is_specials_mod_scan(ev.code)) {
                    unsigned bit = specials_mod_bit(ev.code);
                    if (ev.value == 1) {
                        unsigned was = specials_mod_mask;
                        if (bit) specials_mod_mask |= bit;
                        else specials_mod_mask |= 1u; /* unknown but matched */
                        if (!was && specials_mod_mask) {
                            fprintf(stderr,
                                "sym layer on (host HID map) code=%d mask=0x%x grab=%d\n",
                                ev.code, specials_mod_mask, grab);
                            fflush(stderr);
                        }
                    } else if (ev.value == 0) {
                        if (bit) specials_mod_mask &= ~bit;
                        else specials_mod_mask = 0;
                        if (!specials_mod_mask) {
                            /* clear any stuck shift from specials chords */
                            if (hid_k >= 0 || hw_out_fd >= 0 || hw_out_fd_app >= 0) {
                                uint8_t empty[6] = {0};
                                hw_out4(0x01, 0, 0, 0);
                                if (hid_k >= 0) send_kbd(hid_k, 0, empty);
                            }
                            fprintf(stderr, "sym layer off\n");
                            fflush(stderr);
                        }
                    }
                    continue; /* never stream Sym as host Right Alt */
                }
                /* Sym held: letter → Titan specials glyph as US HID */
                if (sym_layer_held() && ev.value != 2) {
                    uint8_t sm = 0, su = 0;
                    if (titan_specials_layer_hid(ev.code, &sm, &su)) {
                        uint8_t report_mods = (uint8_t)(mods | sm);
                        uint8_t k6[6] = {0};
                        if (ev.value == 1) k6[0] = su;
                        hw_out4(0x01, report_mods, su, ev.value ? 1 : 0);
                        if (hid_k >= 0) {
                            if (send_kbd(hid_k, report_mods, k6) != 0) {
                                hid_k = reopen_hidg(hid_k, 0);
                                if (hid_k >= 0) send_kbd(hid_k, report_mods, k6);
                            }
                        }
                        if (ev.value == 1) {
                            fprintf(stderr,
                                "specials map code=%u → hid=0x%02x mod=0x%02x\n",
                                ev.code, su, report_mods);
                            fflush(stderr);
                        }
                        /* do not leave host with sticky Shift from specials */
                        if (ev.value == 0 && sm) {
                            uint8_t empty[6] = {0};
                            hw_out4(0x01, mods, 0, 0);
                            if (hid_k >= 0) send_kbd(hid_k, mods, empty);
                        }
                        continue;
                    }
                }
                uint8_t hid = linux_to_hid(ev.code);
                if (!hid) continue;
                if (is_mod(hid)) {
                    uint8_t bit = 1u << (hid - 0xe0);
                    if (ev.value) mods |= bit; else mods &= (uint8_t)~bit;
                } else if (ev.value) {
                    int have = 0;
                    for (int i = 0; i < 6; i++) if (keys[i] == hid) have = 1;
                    if (!have) for (int i = 0; i < 6; i++) if (!keys[i]) { keys[i] = hid; break; }
                } else {
                    for (int i = 0; i < 6; i++) if (keys[i] == hid) {
                        for (int j = i; j < 5; j++) keys[j] = keys[j+1];
                        keys[5] = 0; break;
                    }
                }
                /* physical key → BT mirror (per-event). Carry live mod mask so
                 * hosts get Ctrl/Alt/Shift+letter even if e0–e7 ordering races. */
                hw_out4(0x01, mods, hid, ev.value ? 1 : 0);
                if (send_kbd(hid_k, mods, keys) != 0) {
                    if (!no_hidg) {
                        hid_k = reopen_hidg(hid_k, 0);
                        if (hid_k >= 0) send_kbd(hid_k, mods, keys);
                    }
                    /* Host gone: exclusive grab made phone dead AND host silent.
                     * Drop EVIOCGRAB so TitanKey returns to Android immediately. */
                    if (grab && !grab_released_for_host && hidg_fail_streak >= 3
                            && kfd >= 0) {
                        ioctl(kfd, EVIOCGRAB, 0);
                        if (rfd >= 0) ioctl(rfd, EVIOCGRAB, 0);
                        if (pfd >= 0) ioctl(pfd, EVIOCGRAB, 0);
                        grab_released_for_host = 1;
                        grab = 0;
                        fprintf(stderr,
                            "hidg dead (fail=%d) — released exclusive grab (phone keys live)\n",
                            hidg_fail_streak);
                    }
                }
            }
        }
        /* key device error (hot-unplug rare) */
        if (i_k >= 0 && (pf[i_k].revents & (POLLERR | POLLHUP | POLLNVAL))) {
            fprintf(stderr, "key fd error — drop\n");
            if (grab) ioctl(kfd, EVIOCGRAB, 0);
            close(kfd); kfd = -1; i_k = -1;
        }

        /* absolute touchPad → relative mouse */
        if (i_p >= 0 && (pf[i_p].revents & POLLIN)) {
            struct input_event ev;
            int x = last_x, y = last_y, sync = 0;
            while (read(pfd, &ev, sizeof ev) == (ssize_t)sizeof ev) {
                if (ev.type == EV_ABS) {
                    if (ev.code == ABS_MT_POSITION_X || ev.code == ABS_X) x = ev.value;
                    if (ev.code == ABS_MT_POSITION_Y || ev.code == ABS_Y) y = ev.value;
                    if (ev.code == ABS_MT_TRACKING_ID) {
                        if (ev.value < 0) { contact = 0; last_x = last_y = -1; }
                        else contact = 1;
                    }
                } else if (ev.type == EV_KEY) {
                    if (ev.code == BTN_TOUCH || ev.code == BTN_TOOL_FINGER) {
                        if (ev.value) {
                            contact = 1;
                            pad_down_x = x; pad_down_y = y; pad_moved = 0;
                            clock_gettime(CLOCK_MONOTONIC, &pad_down_ts);
                        } else {
                            /* finger up: tap-to-click if little movement */
                            if (contact && pad_moved < 40) {
                                struct timespec now;
                                clock_gettime(CLOCK_MONOTONIC, &now);
                                long ms = (now.tv_sec - pad_down_ts.tv_sec) * 1000L
                                    + (now.tv_nsec - pad_down_ts.tv_nsec) / 1000000L;
                                if (ms > 0 && ms < 280) {
                                    /* Raw-pad tap → host click only; no key_activity
                                     * stamp (would cancel touchpadd left latch). */
                                    emit_mouse(hid_m >= 0 ? hid_m : -1, 0x01, 0, 0, 0, 0);
                                    usleep(30000);
                                    emit_mouse(hid_m >= 0 ? hid_m : -1, 0, 0, 0, 0, 0);
                                }
                            }
                            contact = 0; last_x = last_y = -1;
                        }
                    }
                    if (ev.code == BTN_LEFT || ev.code == BTN_MOUSE) {
                        if (ev.value) buttons |= 0x01; else buttons &= ~0x01;
                        emit_mouse(hid_m >= 0 ? hid_m : hid_k, buttons, 0, 0, 0, 0);
                        /* Do NOT bump_key_activity on mouse buttons — touchpadd
                         * left-latch watches key_activity and would release the
                         * host drag-hold ~10ms after double-tap (log: latch ON
                         * then OFF hw key activity). Keyboard keys only. */
                    }
                } else if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
                    sync = 1;
                }
            }
            if (sync && contact && x >= 0 && y >= 0) {
                if (last_x >= 0 && last_y >= 0) {
                    int dx = (x - last_x) / scale;
                    int dy = (y - last_y) / scale;
                    if (dx || dy) {
                        apply_orient(&dx, &dy);
                        pad_moved += abs(dx) + abs(dy);
                        if (!mouse_blocked_by_typing())
                            emit_mouse(hid_m >= 0 ? hid_m : -1, buttons, dx, dy, 0, 1);
                    }
                }
                last_x = x; last_y = y;
            }
        }

        /* relative mouse (titan2-touchpadd / titan2-virtual-mouse) */
        if (i_r >= 0 && (pf[i_r].revents & (POLLERR | POLLHUP | POLLNVAL))) {
            uint8_t prev_btn = buttons;
            fprintf(stderr, "virt mouse fd error — reopen\n");
            rfd = reopen_rel_mouse(rfd, 1, relpath, sizeof relpath);
            i_r = -1; /* poll list rebuilt next loop */
            /* Preserve latch: re-read BTN state instead of forcing host button-up. */
            buttons = mouse_buttons_from_fd(rfd);
            if (buttons != prev_btn && hid_m >= 0)
                emit_mouse(hid_m, buttons, 0, 0, 0, 0);
            else if (buttons && hid_m >= 0)
                emit_mouse(hid_m, buttons, 0, 0, 0, 0); /* reassert hold */
        } else if (i_r >= 0 && (pf[i_r].revents & POLLIN)) {
            struct input_event ev;
            int dx = 0, dy = 0, wheel = 0, dirty = 0;
            static int hi_wheel_acc = 0;
            while (read(rfd, &ev, sizeof ev) == (ssize_t)sizeof ev) {
                if (ev.type == EV_REL) {
                    if (ev.code == REL_X) { dx += ev.value; dirty = 1; }
                    if (ev.code == REL_Y) { dy += ev.value; dirty = 1; }
                    if (ev.code == REL_WHEEL) { wheel += ev.value; dirty = 1; }
                    if (ev.code == REL_HWHEEL) { /* ignore lateral for boot mouse */ }
                    /* touchpadd emits REL_WHEEL_HI_RES (11); 120 units ≈ 1 notch */
                    if (ev.code == 11 /* REL_WHEEL_HI_RES */) {
                        hi_wheel_acc += ev.value;
                        while (hi_wheel_acc >= 120) { wheel += 1; hi_wheel_acc -= 120; dirty = 1; }
                        while (hi_wheel_acc <= -120) { wheel -= 1; hi_wheel_acc += 120; dirty = 1; }
                    }
                } else if (ev.type == EV_KEY) {
                    uint8_t bit = 0;
                    if (ev.code == BTN_LEFT || ev.code == BTN_MOUSE) bit = 0x01;
                    if (ev.code == BTN_RIGHT) bit = 0x02;
                    if (ev.code == BTN_MIDDLE) bit = 0x04;
                    if (bit) {
                        if (ev.value) buttons |= bit; else buttons &= ~bit;
                        dirty = 1;
                        /* No bump_key_activity: pad BTN is not typing — see
                         * left-latch race with titan2-touchpadd. */
                    }
                } else if (ev.type == EV_SYN && dirty) {
                    if (!mouse_blocked_by_typing()) {
                        apply_orient(&dx, &dy);
                        if (emit_mouse(hid_m >= 0 ? hid_m : -1, buttons, dx, dy, wheel, 1) != 0) {
                            hid_m = reopen_hidg(hid_m, 1);
                            if (hid_m >= 0)
                                emit_mouse(hid_m, buttons, dx, dy, wheel, 0);
                        }
                    } else {
                        /* drop motion/clicks while typing; release buttons host-side once */
                        if (buttons) {
                            buttons = 0;
                            emit_mouse(hid_m >= 0 ? hid_m : -1, 0, 0, 0, 0, 0);
                        }
                    }
                    dx = dy = wheel = 0; dirty = 0;
                }
            }
        }

        /* reload palm-reject + feel; local-input key pause; rediscover virt mouse */
        {
            static long long last_reload;
            static int last_pad_epoch = -1;
            long long n = now_ms();
            /* Pad regrab: only when service sets regrab=1 (real mode switch /
             * touchpadd dead) or our mouse fd is already dead.
             * Epoch-only bumps used to close→ungrab→reopen every few seconds —
             * Android stole the virt mouse mid-session (host "unplug"). */
            if (mouse_on && n - last_reload > 200) {
                int epoch = read_int_file(PAD_EPOCH_PATH, PAD_EPOCH_PATH2, 0);
                int regrab = read_int_file(PAD_REGRAB_PATH, PAD_REGRAB_PATH2, 0);
                int need = 0;
                if (regrab)
                    need = 1;
                else if (rfd < 0 && pfd < 0)
                    need = 1; /* lost mouse — recover */
                /* epoch-only: track, do not unplug */
                if (epoch > 0 && epoch != last_pad_epoch && last_pad_epoch >= 0
                        && !need) {
                    /* silence — service already logs debounce */
                }
                if (need) {
                    uint8_t held = buttons;
                    fprintf(stderr, "pad regrab epoch=%d→%d regrab=%d rfd=%d\n",
                            last_pad_epoch, epoch, regrab, rfd);
                    /* Grab new first (via reopen_rel_mouse), keep host BTN state */
                    if (rfd >= 0) {
                        rfd = reopen_rel_mouse(rfd, 1, relpath, sizeof relpath);
                    } else {
                        relpath[0] = '\0';
                        rfd = open_best_rel_mouse_grab(relpath, sizeof relpath);
                    }
                    if (pfd >= 0 && rfd >= 0)
                        close_mouse_fd(&pfd, 1);
                    if (rfd < 0 && padpath[0] && pfd < 0) {
                        pfd = open(padpath, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
                        if (pfd >= 0) {
                            if (ioctl(pfd, EVIOCGRAB, 1) != 0) {
                                close(pfd);
                                pfd = -1;
                            }
                        }
                    }
                    /* Re-sample latch BTN after reopen; do not force host up */
                    if (rfd >= 0) {
                        uint8_t nb = mouse_buttons_from_fd(rfd);
                        if (nb) buttons = nb;
                        else if (held) buttons = held; /* keep host drag if EVIOCGKEY empty */
                        if (buttons && hid_m >= 0)
                            emit_mouse(hid_m, buttons, 0, 0, 0, 0);
                    }
                    if (regrab) {
                        FILE *cf = fopen(PAD_REGRAB_PATH, "w");
                        if (!cf) cf = fopen(PAD_REGRAB_PATH2, "w");
                        if (cf) { fputs("0", cf); fclose(cf); }
                    }
                    fprintf(stderr, "pad regrab done rel=%d raw=%d path=%s btn=0x%x\n",
                            rfd >= 0, pfd >= 0, relpath[0] ? relpath : "-",
                            (unsigned)buttons);
                }
                if (epoch > 0) last_pad_epoch = epoch;
                else if (last_pad_epoch < 0) last_pad_epoch = 0;
            }
            /* Share vs exclusive: plane grab is SoT. Do not wait for service
             * kill/restart — that left share stuck on --grab (phone keys dead)
             * and dumped a key burst on later ungrab. */
            {
                int want = read_int_file(HID_GRAB_PATH, HID_GRAB_PATH2, grab);
                if (want != grab && kfd >= 0) {
                    grab = want;
                    apply_key_route(kfd, grab, hid_k, &mods, keys);
                    fprintf(stderr, "plane grab=%d — %s\n",
                        grab, grab ? "exclusive" : "share hub");
                    fflush(stderr);
                }
            }
            /* local-input + keys-only pause checked often (~200ms) */
            if (n - last_reload > 200) {
                int lip = effective_local_input_pause(grab);
                int khp = load_keys_host_pause();
                if (lip != local_input_pause) {
                    local_input_pause = lip;
                    if (keys_on && kfd < 0 && keypath[0] && !keys_host_pause)
                        kfd = open_key_fd(keypath, 0);
                    if (keys_on)
                        apply_key_route(kfd, grab, hid_k, &mods, keys);
                    /* pad stays grabbed — never close on editor focus */
                }
                /* Keys-only: layout specials / share inject — close TitanKey, keep mouse.
                 * Exclusive inject maps specials in-bridge: ignore inject_pause (never close). */
                if (grab && specials_inject_mode) {
                    if (keys_host_pause) {
                        write_plane_digit("titan2_specials_inject_pause", 0);
                        keys_host_pause = 0;
                    }
                    if (kfd < 0 && keys_on && keypath[0]) {
                        kfd = open_key_fd(keypath, 1);
                        apply_key_route(kfd, grab, hid_k, &mods, keys);
                    }
                } else if (khp != keys_host_pause) {
                    keys_host_pause = khp;
                    if (keys_on) {
                        if (kfd < 0 && keypath[0])
                            kfd = open_key_fd(keypath, 0);
                        apply_key_route(kfd, grab, hid_k, &mods, keys);
                    }
                }
                last_reload = n;
            }
            static long long last_slow;
            if (n - last_slow > 1500) {
                load_typing_ms();
                load_mouse_feel();
                load_orient();
                load_specials_method(grab);
                load_char_mod_owner();
                last_slow = n;
                if (mouse_on && rfd < 0) {
                    rfd = reopen_rel_mouse(-1, 1, relpath, sizeof relpath);
                    if (rfd >= 0)
                        fprintf(stderr, "virt mouse recovered\n");
                }
                /* hidg may reappear after cable reattach */
                if (hid_k < 0) hid_k = open_hidg_index(0);
                if (hid_m < 0) hid_m = open_hidg_index(1);
                /* recover TitanKey — stay open while yielding to Android too */
                if (keys_on && !keys_host_pause && kfd < 0) {
                    if (!keypath[0])
                        find_by_name("TitanKey", keypath, sizeof keypath);
                    if (keypath[0])
                        kfd = open_key_fd(keypath, grab);
                }
            }
        }

        /* app inject file (fallback; prefer socket) */
        drain_inj(hid_k, hid_m, &mods, keys, &buttons);

        /* app sockets: filesystem + abstract @titan2_hid */
        for (int pass = 0; pass < 2; pass++) {
            int fd = -1;
            int idx = -1;
            if (pass == 0) { fd = sfd; idx = i_s; }
            else { fd = sfd_abs; idx = i_sa; }
            if (fd < 0 || idx < 0 || !(pf[idx].revents & POLLIN)) continue;
            uint8_t buf[16];
            for (;;) {
                ssize_t n = recv(fd, buf, sizeof buf, 0);
                if (n < 1) break;
                switch (buf[0]) {
                case 0x01: /* key */
                    if (n < 4) break;
                    {
                        uint8_t m = buf[1], h = buf[2], press = buf[3];
                        apply_key(&mods, keys, m, h, press);
                        send_kbd(hid_k, mods, keys);
                        /* Soft inject: app dual-sends BT; only mirror if BT-only */
                        if (no_hidg || hid_k < 0)
                            hw_out4(0x01, m, h, press);
                        if (press) {
                            note_typing();
                            bump_key_activity();
                        }
                    }
                    break;
                case 0x02: /* mouse move */
                    if (n < 4) break;
                    if (!mouse_blocked_by_typing())
                        emit_mouse(hid_m >= 0 ? hid_m : -1, buf[3], (int8_t)buf[1], (int8_t)buf[2], 0, 1);
                    break;
                case 0x03:
                    if (n < 2) break;
                    if (!mouse_blocked_by_typing()) {
                        buttons = buf[1];
                        emit_mouse(hid_m >= 0 ? hid_m : -1, buttons, 0, 0, 0, 0);
                    }
                    break;
                case 0x04:
                    if (n < 2) break;
                    if (!mouse_blocked_by_typing())
                        emit_mouse(hid_m >= 0 ? hid_m : -1, buttons, 0, 0, (int8_t)buf[1], 0);
                    break;
                default: break;
                }
            }
        }
    }

    /* Empty keyboard + mouse reports before ungrab/close (sticky mods on host). */
    {
        uint8_t z6[6] = {0};
        if (hid_k >= 0) send_kbd(hid_k, 0, z6);
        hw_out4(0x01, 0, 0, 0);
        if (hid_m >= 0 || hw_out_fd >= 0 || hw_out_fd_app >= 0)
            emit_mouse(hid_m >= 0 ? hid_m : -1, 0, 0, 0, 0, 0);
    }
    if (kfd >= 0) { if (grab) ioctl(kfd, EVIOCGRAB, 0); close(kfd); }
    if (pfd >= 0) { if (grab) ioctl(pfd, EVIOCGRAB, 0); close(pfd); }
    if (rfd >= 0) { if (grab) ioctl(rfd, EVIOCGRAB, 0); close(rfd); }
    if (hid_k >= 0) close(hid_k);
    if (hid_m >= 0) close(hid_m);
    if (sfd >= 0) { close(sfd); unlink(sock_path); }
    if (sfd_abs >= 0) close(sfd_abs);
    close_phone_nav_uinput();
    fprintf(stderr, "bridge stop\n");
    return 0;
}
