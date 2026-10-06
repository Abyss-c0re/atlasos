/*
 * Open neckband cursor codec.
 *
 * 20-byte frames on the neckband's BLE write characteristic. The layout is
 * an interoperability contract (event id, little-endian axes, Android
 * keycodes). This file is original. It is not vendor source.
 *
 * Callers that share one NbPointer or NbKbd across threads must hold one lock.
 * The JNI wrapper does that. Host tests are single-threaded.
 */
#ifndef NB_REMOTE_H
#define NB_REMOTE_H

#include <stdint.h>

#define NB_FRAME 20
#define NB_CAP 400
#define NB_WHEEL_PX 24

enum {
    NB_EV_AUTH = 1,
    NB_EV_KEY = 4,
    NB_EV_MOUSE = 5,
    NB_EV_TEXT = 7,
    NB_EV_GESTURE = 9
};

enum {
    NB_MOUSE_HOVER = 1,
    NB_MOUSE_CLICK = 2,
    NB_MOUSE_CANCEL = 4
};

enum {
    NB_GESTURE_DOWN = 1,
    NB_GESTURE_MOVE = 2,
    NB_GESTURE_UP = 3
};

/* Android KeyEvent codes used on the neckband path. */
enum {
    NB_KEY_HOME = 3,
    NB_KEY_BACK = 4,
    NB_KEY_DPAD_UP = 19,
    NB_KEY_DPAD_DOWN = 20,
    NB_KEY_DPAD_LEFT = 21,
    NB_KEY_DPAD_RIGHT = 22
};

typedef struct NbPointer {
    int64_t acc_x;
    int64_t acc_y;
    int dx;
    int dy;
    int gain;       /* percent, 100 = 1.0 */
    int buttons;    /* HID bits: 1 left, 2 right, 4 middle */
    int scrolling;
} NbPointer;

typedef struct NbKbd {
    uint8_t mods;
    uint8_t keys[6];
} NbKbd;

void nb_pointer_init(NbPointer *p);
void nb_pointer_set_gain(NbPointer *p, int pct);
void nb_pointer_move(NbPointer *p, int dx, int dy);
int nb_pointer_pending(const NbPointer *p);
/* One hover frame. Excess motion above NB_CAP was already dropped. */
int nb_pointer_take(NbPointer *p, uint8_t out[NB_FRAME]);
/* buttons < 0 keeps the previous buttons. Returns frame count. */
int nb_pointer_buttons(NbPointer *p, int buttons, uint8_t *out, int cap);
int nb_pointer_wheel(NbPointer *p, int notches, uint8_t *out, int cap);
int nb_pointer_scroll_end(NbPointer *p, uint8_t out[NB_FRAME]);
int nb_pointer_cancel(NbPointer *p, uint8_t out[NB_FRAME]);

/* Returns 20, or 7 for auth. Buffers are NB_FRAME and zero-filled. */
int nb_frame_auth(uint8_t out[NB_FRAME]);
int nb_frame_mouse(uint8_t out[NB_FRAME], int action, int dx, int dy);
int nb_frame_key(uint8_t out[NB_FRAME], int down, int android_keycode);
int nb_frame_gesture(uint8_t out[NB_FRAME], int action, int dx, int dy);

/* HID keyboard-page usage -> Android keycode, or 0 if this link skips it.
 * Esc is Back and keyboard-Home is Android Home. That matches the pad labels
 * on a neckband. PC HID reports are unchanged. */
int nb_hid_to_android(int usage);

/* Boot report (8 bytes) -> key frames. Updates *st. Returns frame count. */
int nb_kbd_diff(NbKbd *st, const uint8_t report[8], uint8_t *out, int cap);
int nb_kbd_edge(NbKbd *st, int mod, int usage, int press, uint8_t *out, int cap);
int nb_kbd_release(NbKbd *st, uint8_t *out, int cap);

/* UTF-8 text. Always ends on a COMPLETE chunk. Returns frame count, or -1. */
int nb_text_encode(uint8_t *out, int cap, const uint8_t *utf8, int len, int pkg_id);

#endif
