/* Host audit for the neckband codec. No device, no vendor blobs. */
#include "nb_remote.h"

#include <stdio.h>
#include <string.h>

static int fails;

static void expect(int cond, const char *msg) {
    if (!cond) {
        fprintf(stderr, "FAIL %s\n", msg);
        fails++;
    } else {
        printf("ok %s\n", msg);
    }
}

static int i16_at(const uint8_t *p) {
    int v = (int)p[0] | ((int)p[1] << 8);
    if (v & 0x8000) v -= 0x10000;
    return v;
}

static void test_auth(void) {
    uint8_t f[NB_FRAME];
    int n = nb_frame_auth(f);
    expect(n == 7, "auth length");
    expect(f[0] == NB_EV_AUTH, "auth id");
    expect(memcmp(f + 1, "VITURE", 6) == 0, "auth sign");
    expect(f[7] == 0, "auth tail clear");
}

static void test_mouse(void) {
    uint8_t f[NB_FRAME];
    NbPointer p;
    nb_frame_mouse(f, NB_MOUSE_HOVER, -1, 2);
    expect(f[0] == NB_EV_MOUSE && f[2] == NB_MOUSE_HOVER, "hover id");
    expect(i16_at(f + 3) == -1 && i16_at(f + 5) == 2, "hover axes");
    expect(f[7] == 0, "hover scale 0");

    nb_pointer_init(&p);
    nb_pointer_move(&p, 3, 0);
    nb_pointer_move(&p, 4, -2);
    expect(nb_pointer_take(&p, f) == 1, "coalesce take");
    expect(i16_at(f + 3) == 7 && i16_at(f + 5) == -2, "coalesce sum");
    expect(nb_pointer_take(&p, f) == 0, "coalesce empty");

    nb_pointer_move(&p, 100000, -100000);
    expect(nb_pointer_take(&p, f) == 1, "cap take");
    expect(i16_at(f + 3) == NB_CAP && i16_at(f + 5) == -NB_CAP, "cap latest");
    expect(nb_pointer_pending(&p) == 0, "cap does not replay");

    nb_pointer_set_gain(&p, 50);
    nb_pointer_move(&p, 1, 0);
    expect(nb_pointer_pending(&p) == 0, "half gain holds fraction");
    nb_pointer_move(&p, 1, 0);
    expect(nb_pointer_take(&p, f) == 1 && i16_at(f + 3) == 1, "half gain emits");
}

static void test_buttons_wheel(void) {
    NbPointer p;
    uint8_t buf[8 * NB_FRAME];
    uint8_t one[NB_FRAME];
    int n;
    nb_pointer_init(&p);
    n = nb_pointer_buttons(&p, 1, buf, (int)sizeof(buf));
    expect(n == 0, "left down is not a click");
    n = nb_pointer_buttons(&p, 0, buf, (int)sizeof(buf));
    expect(n == 1 && buf[0] == NB_EV_MOUSE && buf[2] == NB_MOUSE_CLICK, "left up clicks");

    n = nb_pointer_buttons(&p, 2, buf, (int)sizeof(buf));
    expect(n == 1 && buf[0] == NB_EV_KEY && buf[2] == 0, "right down");
    expect(buf[3] == NB_KEY_BACK, "right is back");
    n = nb_pointer_buttons(&p, 0, buf, (int)sizeof(buf));
    expect(n == 1 && buf[2] == 1 && buf[3] == NB_KEY_BACK, "right up");

    n = nb_pointer_buttons(&p, 4, buf, (int)sizeof(buf));
    expect(n == 1 && buf[3] == NB_KEY_HOME && buf[2] == 0, "middle is home");
    nb_pointer_buttons(&p, 0, buf, (int)sizeof(buf));

    n = nb_pointer_wheel(&p, 1, buf, (int)sizeof(buf));
    expect(n == 2, "wheel opens a gesture");
    expect(buf[0] == NB_EV_GESTURE && buf[2] == NB_GESTURE_DOWN, "gesture down");
    expect(buf[NB_FRAME] == NB_EV_GESTURE && buf[NB_FRAME + 2] == NB_GESTURE_MOVE, "gesture move");
    expect(buf[NB_FRAME + 7] == 10, "scroll scale");
    expect(i16_at(buf + NB_FRAME + 5) == -(NB_WHEEL_PX * 10), "scroll up is negative y");
    n = nb_pointer_wheel(&p, 1, buf, (int)sizeof(buf));
    expect(n == 1 && buf[2] == NB_GESTURE_MOVE, "wheel stays open");
    expect(nb_pointer_scroll_end(&p, one) == 1 && one[2] == NB_GESTURE_UP, "scroll end");
    expect(nb_pointer_scroll_end(&p, one) == 0, "scroll end once");
}

static void test_keys(void) {
    NbKbd k;
    uint8_t buf[8 * NB_FRAME];
    uint8_t report[8];
    int n;
    memset(&k, 0, sizeof(k));
    expect(nb_hid_to_android(0x04) == 29, "A");
    expect(nb_hid_to_android(0x1e) == 8, "1");
    expect(nb_hid_to_android(0x27) == 7, "0");
    expect(nb_hid_to_android(0x29) == NB_KEY_BACK, "esc is back");
    expect(nb_hid_to_android(0x4a) == NB_KEY_HOME, "home key");
    expect(nb_hid_to_android(0x52) == NB_KEY_DPAD_UP, "up");
    expect(nb_hid_to_android(0xe1) == 59, "shift");
    expect(nb_hid_to_android(0x3a) == 131, "f1");
    expect(nb_hid_to_android(0xff) == 0, "unknown skipped");

    n = nb_kbd_edge(&k, 0x02, 0x04, 1, buf, (int)sizeof(buf));
    expect(n == 2, "shift+a is two edges");
    expect(buf[0] == NB_EV_KEY && buf[2] == 0 && buf[3] == 59, "shift down first");
    expect(buf[NB_FRAME + 2] == 0 && buf[NB_FRAME + 3] == 29, "A down second");

    memset(report, 0, sizeof(report));
    n = nb_kbd_diff(&k, report, buf, (int)sizeof(buf));
    expect(n == 2, "release two");
    expect(buf[2] == 1 && buf[3] == 29, "A up before shift");
    expect(buf[NB_FRAME + 2] == 1 && buf[NB_FRAME + 3] == 59, "shift up last");
    expect(k.mods == 0 && k.keys[0] == 0, "kbd clear");

    n = nb_kbd_edge(&k, 0, 0xe1, 1, buf, (int)sizeof(buf));
    expect(n == 1 && buf[3] == 59, "modifier usage");
    n = nb_kbd_release(&k, buf, (int)sizeof(buf));
    expect(n == 1 && buf[2] == 1, "release held shift");
}

static void test_text(void) {
    uint8_t out[8 * NB_FRAME];
    const uint8_t hi[] = {'H', 'i'};
    uint8_t block[15];
    int n;
    int i;
    n = nb_text_encode(out, (int)sizeof(out), hi, 2, 7);
    expect(n == 1, "short text one frame");
    expect(out[0] == NB_EV_TEXT && out[1] == 7 && out[2] == 0, "text header");
    expect(out[3] == 2 && out[4] == 1, "text complete");
    expect(out[5] == 'H' && out[6] == 'i', "text bytes");

    for (i = 0; i < 15; i++) block[i] = (uint8_t)('a' + (i % 26));
    n = nb_text_encode(out, (int)sizeof(out), block, 15, 3);
    expect(n == 2, "exact chunk plus complete");
    expect(out[3] == 15 && out[4] == 0, "full chunk ongoing");
    expect(out[NB_FRAME + 2] == 1 && out[NB_FRAME + 3] == 0 && out[NB_FRAME + 4] == 1,
        "empty complete trailer");

    n = nb_text_encode(out, 10, hi, 2, 1);
    expect(n < 0, "text rejects a short buffer");
}

static void test_cancel(void) {
    NbPointer p;
    uint8_t f[NB_FRAME];
    nb_pointer_init(&p);
    nb_pointer_move(&p, 5, 5);
    nb_pointer_buttons(&p, 1, f, NB_FRAME);
    expect(nb_pointer_cancel(&p, f) == 1, "cancel");
    expect(f[0] == NB_EV_MOUSE && f[2] == NB_MOUSE_CANCEL, "cancel id");
    expect(nb_pointer_pending(&p) == 0 && p.buttons == 0, "cancel clears");
}

int main(void) {
    test_auth();
    test_mouse();
    test_buttons_wheel();
    test_keys();
    test_text();
    test_cancel();
    if (fails) {
        fprintf(stderr, "%d failed\n", fails);
        return 1;
    }
    printf("nb_remote audit passed\n");
    return 0;
}
