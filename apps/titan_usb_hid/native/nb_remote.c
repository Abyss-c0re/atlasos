#include "nb_remote.h"

#include <string.h>

static int clamp16(int v) {
    if (v > 32767) return 32767;
    if (v < -32768) return -32768;
    return v;
}

static void put_i16(uint8_t *p, int v) {
    v = clamp16(v);
    p[0] = (uint8_t)(v & 0xff);
    p[1] = (uint8_t)((v >> 8) & 0xff);
}

static int emit_xy(uint8_t out[NB_FRAME], int ev, int action, int dx, int dy, int scale) {
    memset(out, 0, NB_FRAME);
    out[0] = (uint8_t)ev;
    out[2] = (uint8_t)(action & 0xff);
    put_i16(out + 3, dx);
    put_i16(out + 5, dy);
    out[7] = (uint8_t)(scale & 0xff);
    return NB_FRAME;
}

static int push_frame(uint8_t *out, int cap, int frames, const uint8_t frame[NB_FRAME]) {
    int off = frames * NB_FRAME;
    if (off < 0 || off + NB_FRAME > cap) return frames;
    memcpy(out + off, frame, NB_FRAME);
    return frames + 1;
}

int nb_frame_auth(uint8_t out[NB_FRAME]) {
    static const char sign[] = "VITURE";
    memset(out, 0, NB_FRAME);
    out[0] = NB_EV_AUTH;
    memcpy(out + 1, sign, 6);
    return 7;
}

int nb_frame_mouse(uint8_t out[NB_FRAME], int action, int dx, int dy) {
    return emit_xy(out, NB_EV_MOUSE, action, dx, dy, 0);
}

int nb_frame_key(uint8_t out[NB_FRAME], int down, int android_keycode) {
    memset(out, 0, NB_FRAME);
    out[0] = NB_EV_KEY;
    out[2] = down ? 0 : 1;
    out[3] = (uint8_t)(android_keycode & 0xff);
    out[4] = (uint8_t)((android_keycode >> 8) & 0xff);
    return NB_FRAME;
}

int nb_frame_gesture(uint8_t out[NB_FRAME], int action, int dx, int dy) {
    if (dx > 3000) dx = 3000;
    if (dx < -3000) dx = -3000;
    if (dy > 3000) dy = 3000;
    if (dy < -3000) dy = -3000;
    /* Scale byte 10: stored axes are pixels * 10. The peer divides. */
    return emit_xy(out, NB_EV_GESTURE, action, dx * 10, dy * 10, 10);
}

void nb_pointer_init(NbPointer *p) {
    memset(p, 0, sizeof(*p));
    p->gain = 100;
}

void nb_pointer_set_gain(NbPointer *p, int pct) {
    if (pct < 25) pct = 25;
    if (pct > 400) pct = 400;
    p->gain = pct;
}

static void cap_axis(int *v) {
    if (*v > NB_CAP) *v = NB_CAP;
    if (*v < -NB_CAP) *v = -NB_CAP;
}

void nb_pointer_move(NbPointer *p, int dx, int dy) {
    int g = p->gain;
    if (g <= 0) g = 100;
    p->acc_x += (int64_t)dx * (int64_t)g;
    p->acc_y += (int64_t)dy * (int64_t)g;
    {
        int qx = (int)(p->acc_x / 100);
        int qy = (int)(p->acc_y / 100);
        p->acc_x -= (int64_t)qx * 100;
        p->acc_y -= (int64_t)qy * 100;
        p->dx += qx;
        p->dy += qy;
    }
    /* Latest-wins past one fast flick. Do not replay a trail. */
    cap_axis(&p->dx);
    cap_axis(&p->dy);
}

int nb_pointer_pending(const NbPointer *p) {
    return p->dx != 0 || p->dy != 0;
}

int nb_pointer_take(NbPointer *p, uint8_t out[NB_FRAME]) {
    int x;
    int y;
    if (p->dx == 0 && p->dy == 0) return 0;
    x = p->dx;
    y = p->dy;
    p->dx = 0;
    p->dy = 0;
    nb_frame_mouse(out, NB_MOUSE_HOVER, x, y);
    return 1;
}

static int push_key(uint8_t *out, int cap, int frames, int down, int code) {
    uint8_t frame[NB_FRAME];
    if (code == 0) return frames;
    nb_frame_key(frame, down, code);
    return push_frame(out, cap, frames, frame);
}

static int push_mouse(uint8_t *out, int cap, int frames, int action, int dx, int dy) {
    uint8_t frame[NB_FRAME];
    nb_frame_mouse(frame, action, dx, dy);
    return push_frame(out, cap, frames, frame);
}

static int push_gesture(uint8_t *out, int cap, int frames, int action, int dx, int dy) {
    uint8_t frame[NB_FRAME];
    nb_frame_gesture(frame, action, dx, dy);
    return push_frame(out, cap, frames, frame);
}

int nb_pointer_buttons(NbPointer *p, int buttons, uint8_t *out, int cap) {
    int prev;
    int frames = 0;
    if (buttons < 0) return 0;
    buttons &= 7;
    prev = p->buttons & 7;
    if (!(buttons & 1) && (prev & 1)) {
        frames = push_mouse(out, cap, frames, NB_MOUSE_CLICK, 0, 0);
    }
    if ((buttons & 2) && !(prev & 2)) {
        frames = push_key(out, cap, frames, 1, NB_KEY_BACK);
    }
    if (!(buttons & 2) && (prev & 2)) {
        frames = push_key(out, cap, frames, 0, NB_KEY_BACK);
    }
    if ((buttons & 4) && !(prev & 4)) {
        frames = push_key(out, cap, frames, 1, NB_KEY_HOME);
    }
    if (!(buttons & 4) && (prev & 4)) {
        frames = push_key(out, cap, frames, 0, NB_KEY_HOME);
    }
    p->buttons = buttons;
    return frames;
}

int nb_pointer_wheel(NbPointer *p, int notches, uint8_t *out, int cap) {
    int g;
    int px;
    int dy;
    int frames = 0;
    if (notches == 0) return 0;
    if (notches > 8) notches = 8;
    if (notches < -8) notches = -8;
    g = p->gain > 0 ? p->gain : 100;
    px = (notches * NB_WHEEL_PX * g) / 100;
    if (px == 0) px = notches > 0 ? 1 : -1;
    /* Positive notch is scroll-up. The peer's finger distance is negated. */
    dy = -px;
    if (!p->scrolling) {
        frames = push_gesture(out, cap, frames, NB_GESTURE_DOWN, 0, 0);
        p->scrolling = 1;
    }
    frames = push_gesture(out, cap, frames, NB_GESTURE_MOVE, 0, dy);
    return frames;
}

int nb_pointer_scroll_end(NbPointer *p, uint8_t out[NB_FRAME]) {
    if (!p->scrolling) return 0;
    p->scrolling = 0;
    nb_frame_gesture(out, NB_GESTURE_UP, 0, 0);
    return 1;
}

int nb_pointer_cancel(NbPointer *p, uint8_t out[NB_FRAME]) {
    p->dx = 0;
    p->dy = 0;
    p->acc_x = 0;
    p->acc_y = 0;
    p->buttons = 0;
    p->scrolling = 0;
    nb_frame_mouse(out, NB_MOUSE_CANCEL, 0, 0);
    return 1;
}

int nb_hid_to_android(int usage) {
    if (usage >= 0x04 && usage <= 0x1d) {
        return 29 + (usage - 0x04); /* A-Z */
    }
    if (usage >= 0x1e && usage <= 0x26) {
        return 8 + (usage - 0x1e); /* 1-9 */
    }
    if (usage == 0x27) return 7; /* 0 */
    switch (usage) {
    case 0x28: return 66;  /* enter */
    case 0x29: return NB_KEY_BACK;
    case 0x2a: return 67;  /* backspace */
    case 0x2b: return 61;  /* tab */
    case 0x2c: return 62;  /* space */
    case 0x2d: return 69;  /* minus */
    case 0x2e: return 70;  /* equals */
    case 0x2f: return 71;  /* [ */
    case 0x30: return 72;  /* ] */
    case 0x31: return 73;  /* backslash */
    case 0x33: return 74;  /* semicolon */
    case 0x34: return 75;  /* apostrophe */
    case 0x35: return 68;  /* grave */
    case 0x36: return 55;  /* comma */
    case 0x37: return 56;  /* period */
    case 0x38: return 76;  /* slash */
    case 0x39: return 115; /* caps */
    case 0x46: return 120; /* sysrq */
    case 0x47: return 116; /* scroll lock */
    case 0x48: return 121; /* pause */
    case 0x49: return 124; /* insert */
    case 0x4a: return NB_KEY_HOME;
    case 0x4b: return 92;  /* page up */
    case 0x4c: return 112; /* forward del */
    case 0x4d: return 123; /* move end */
    case 0x4e: return 93;  /* page down */
    case 0x4f: return NB_KEY_DPAD_RIGHT;
    case 0x50: return NB_KEY_DPAD_LEFT;
    case 0x51: return NB_KEY_DPAD_DOWN;
    case 0x52: return NB_KEY_DPAD_UP;
    case 0xe0: return 113; /* left ctrl */
    case 0xe1: return 59;  /* left shift */
    case 0xe2: return 57;  /* left alt */
    case 0xe3: return 117; /* left meta */
    case 0xe4: return 114; /* right ctrl */
    case 0xe5: return 60;  /* right shift */
    case 0xe6: return 58;  /* right alt */
    case 0xe7: return 118; /* right meta */
    default:
        break;
    }
    if (usage >= 0x3a && usage <= 0x45) {
        return 131 + (usage - 0x3a); /* F1-F12 */
    }
    return 0;
}

static int has_key(const uint8_t keys[6], int usage) {
    int i;
    if (usage == 0) return 0;
    for (i = 0; i < 6; i++) {
        if (keys[i] == (uint8_t)usage) return 1;
    }
    return 0;
}

int nb_kbd_diff(NbKbd *st, const uint8_t report[8], uint8_t *out, int cap) {
    uint8_t nmods = report[0];
    uint8_t nkeys[6];
    int frames = 0;
    int i;
    int b;
    memcpy(nkeys, report + 2, 6);
    for (i = 0; i < 6; i++) {
        int u = st->keys[i];
        if (u && !has_key(nkeys, u)) {
            frames = push_key(out, cap, frames, 0, nb_hid_to_android(u));
        }
    }
    for (b = 0; b < 8; b++) {
        int bit = 1 << b;
        if ((st->mods & bit) && !(nmods & bit)) {
            frames = push_key(out, cap, frames, 0, nb_hid_to_android(0xe0 + b));
        }
    }
    for (b = 0; b < 8; b++) {
        int bit = 1 << b;
        if (!(st->mods & bit) && (nmods & bit)) {
            frames = push_key(out, cap, frames, 1, nb_hid_to_android(0xe0 + b));
        }
    }
    for (i = 0; i < 6; i++) {
        int u = nkeys[i];
        if (u && !has_key(st->keys, u)) {
            frames = push_key(out, cap, frames, 1, nb_hid_to_android(u));
        }
    }
    st->mods = nmods;
    memcpy(st->keys, nkeys, 6);
    st->base_mods = nmods;
    st->abs_hold = 0;
    return frames;
}

static void compact_remove(uint8_t keys[6], int usage) {
    int i;
    int j;
    for (i = 0; i < 6; i++) {
        if (keys[i] != (uint8_t)usage) continue;
        for (j = i; j < 5; j++) keys[j] = keys[j + 1];
        keys[5] = 0;
        return;
    }
}

int nb_kbd_edge(NbKbd *st, int mod, int usage, int press, uint8_t *out, int cap) {
    NbKbd next = *st;
    uint8_t report[8];
    usage &= 0xff;
    mod &= 0xff;
    if (usage == 0 && !press) {
        next.mods = (uint8_t)mod;
        memset(next.keys, 0, 6);
    } else if (usage >= 0xe0 && usage <= 0xe7) {
        int bit = 1 << (usage - 0xe0);
        if (press) next.mods = (uint8_t)(next.mods | bit);
        else next.mods = (uint8_t)(next.mods & ~bit);
    } else {
        if (press && mod) next.mods = (uint8_t)(next.mods | mod);
        if (press) {
            if (!has_key(next.keys, usage)) {
                int i;
                for (i = 0; i < 6; i++) {
                    if (next.keys[i] == 0) {
                        next.keys[i] = (uint8_t)usage;
                        break;
                    }
                }
            }
        } else {
            compact_remove(next.keys, usage);
        }
    }
    memset(report, 0, sizeof(report));
    report[0] = next.mods;
    memcpy(report + 2, next.keys, 6);
    return nb_kbd_diff(st, report, out, cap);
}

int nb_kbd_absolute(NbKbd *st, int mod, int usage, int press, uint8_t *out, int cap) {
    NbKbd next;
    uint8_t report[8];
    uint8_t base;
    int n;
    if (st == NULL) return 0;
    usage &= 0xff;
    mod &= 0xff;
    if (usage == 0) return 0;
    if (st->abs_hold == 0) st->base_mods = st->mods;
    base = st->base_mods;
    next = *st;
    if (press) {
        next.mods = (uint8_t)((base & (uint8_t)~0x22) | (uint8_t)mod);
        next.abs_hold = (uint8_t)(st->abs_hold + 1);
        if (!has_key(next.keys, usage)) {
            int i;
            for (i = 0; i < 6; i++) {
                if (next.keys[i] == 0) {
                    next.keys[i] = (uint8_t)usage;
                    break;
                }
            }
        }
    } else {
        compact_remove(next.keys, usage);
        next.abs_hold = st->abs_hold > 0 ? (uint8_t)(st->abs_hold - 1) : 0;
        next.mods = next.abs_hold ? (uint8_t)((base & (uint8_t)~0x22) | (uint8_t)mod) : base;
    }
    next.base_mods = base;
    memset(report, 0, sizeof(report));
    report[0] = next.mods;
    memcpy(report + 2, next.keys, 6);
    n = nb_kbd_diff(st, report, out, cap);
    st->base_mods = base;
    st->abs_hold = next.abs_hold;
    return n;
}

int nb_kbd_release(NbKbd *st, uint8_t *out, int cap) {
    uint8_t z[8];
    memset(z, 0, sizeof(z));
    return nb_kbd_diff(st, z, out, cap);
}

int nb_text_encode(uint8_t *out, int cap, const uint8_t *utf8, int len, int pkg_id) {
    int chunks;
    int rem;
    int n;
    int i;
    if (len < 0 || cap < 0) return -1;
    if (len > 0 && utf8 == NULL) return -1;
    if (len > 15 * 40) len = 15 * 40;
    chunks = len / 15;
    rem = len % 15;
    n = chunks + 1;
    if (n * NB_FRAME > cap) return -1;
    for (i = 0; i < chunks; i++) {
        uint8_t *f = out + i * NB_FRAME;
        memset(f, 0, NB_FRAME);
        f[0] = NB_EV_TEXT;
        f[1] = (uint8_t)pkg_id;
        f[2] = (uint8_t)i;
        f[3] = 15;
        f[4] = 0;
        memcpy(f + 5, utf8 + i * 15, 15);
    }
    {
        uint8_t *f = out + chunks * NB_FRAME;
        memset(f, 0, NB_FRAME);
        f[0] = NB_EV_TEXT;
        f[1] = (uint8_t)pkg_id;
        f[2] = (uint8_t)chunks;
        f[3] = (uint8_t)rem;
        f[4] = 1;
        if (rem > 0) memcpy(f + 5, utf8 + chunks * 15, (size_t)rem);
    }
    return n;
}
