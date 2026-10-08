/* titan2-keys — the keyboard map.
 * Linked into hid_bridge. Also a query binary:
 *   titan2-keys --selftest
 *   titan2-keys serve          (abstract socket @titan2_keys)
 *   titan2-keys glyph 8
 *   titan2-keys android 45
 *   titan2-keys linux 16
 *
 * Query datagrams are 4 bytes in, 4 bytes out:
 *   'G' latin1 0 0        -> mod, usage, char, 1
 *   'A' keycode_lo hi 0   -> mod, usage, char, 1
 *   'L' linux_lo hi 0     -> mod, usage, char, 1
 *   'R' held glyph_mod 0  -> report_mods, 0, 0, 1
 * A miss is 0,0,0,0.
 */
#include "titan_keys.h"

#include <string.h>

#define HID_LSHIFT 0x02u
#define HID_RSHIFT 0x20u

struct titan_spec {
    unsigned linux_code;
    unsigned android_code;
    uint8_t mod;
    uint8_t usage;
    char glyph;
};

/* Linux KEY_* and Android KEYCODE_* for the printed Sym layer. */
static const struct titan_spec SPECIALS[] = {
    {16, 45, 0x00, 0x27, '0'}, /* Q */
    {17, 51, 0x00, 0x1e, '1'}, /* W */
    {18, 33, 0x00, 0x1f, '2'}, /* E */
    {19, 46, 0x00, 0x20, '3'}, /* R */
    {20, 48, 0x02, 0x26, '('}, /* T */
    {21, 53, 0x02, 0x27, ')'}, /* Y */
    {22, 49, 0x02, 0x2d, '_'}, /* U */
    {23, 37, 0x00, 0x2d, '-'}, /* I */
    {24, 43, 0x00, 0x38, '/'}, /* O */
    {25, 44, 0x02, 0x33, ':'}, /* P */
    {30, 29, 0x02, 0x1f, '@'}, /* A */
    {31, 47, 0x00, 0x21, '4'}, /* S */
    {32, 32, 0x00, 0x22, '5'}, /* D */
    {33, 34, 0x00, 0x23, '6'}, /* F */
    {34, 35, 0x02, 0x25, '*'}, /* G */
    {35, 36, 0x02, 0x20, '#'}, /* H */
    {36, 38, 0x02, 0x2e, '+'}, /* J */
    {37, 39, 0x02, 0x34, '"'}, /* K */
    {38, 40, 0x00, 0x34, '\''}, /* L */
    {44, 54, 0x02, 0x1e, '!'}, /* Z */
    {45, 52, 0x00, 0x24, '7'}, /* X */
    {46, 31, 0x00, 0x25, '8'}, /* C */
    {47, 50, 0x00, 0x26, '9'}, /* V */
    {48, 30, 0x00, 0x37, '.'}, /* B */
    {49, 42, 0x00, 0x36, ','}, /* N */
    {50, 41, 0x02, 0x38, '?'}, /* M */
};

uint8_t titan_specials_report_mods(uint8_t held, uint8_t glyph_mod) {
    return (uint8_t)((held & (uint8_t)~(HID_LSHIFT | HID_RSHIFT)) | glyph_mod);
}

static int fill_spec(const struct titan_spec *s, uint8_t *mod, uint8_t *usage, char *glyph) {
    if (mod) *mod = s->mod;
    if (usage) *usage = s->usage;
    if (glyph) *glyph = s->glyph;
    return 1;
}

int titan_specials_linux(unsigned code, uint8_t *mod, uint8_t *usage) {
    unsigned i;
    for (i = 0; i < sizeof(SPECIALS) / sizeof(SPECIALS[0]); i++) {
        if (SPECIALS[i].linux_code == code)
            return fill_spec(&SPECIALS[i], mod, usage, 0);
    }
    return 0;
}

int titan_specials_android(unsigned keycode, uint8_t *mod, uint8_t *usage, char *glyph) {
    unsigned i;
    for (i = 0; i < sizeof(SPECIALS) / sizeof(SPECIALS[0]); i++) {
        if (SPECIALS[i].android_code == keycode)
            return fill_spec(&SPECIALS[i], mod, usage, glyph);
    }
    return 0;
}

int titan_glyph_hid(unsigned cp, uint8_t *mod, uint8_t *usage) {
    uint8_t m = 0, u = 0;
    const uint8_t sh = HID_LSHIFT;
    if (cp >= 'a' && cp <= 'z') {
        m = 0;
        u = (uint8_t)(0x04 + (cp - 'a'));
    } else if (cp >= 'A' && cp <= 'Z') {
        m = sh;
        u = (uint8_t)(0x04 + (cp - 'A'));
    } else if (cp >= '1' && cp <= '9') {
        m = 0;
        u = (uint8_t)(0x1e + (cp - '1'));
    } else if (cp == '0') {
        u = 0x27;
    } else {
        switch (cp) {
        case ' ': u = 0x2c; break;
        case '\n':
        case '\r': u = 0x28; break;
        case '\t': u = 0x2b; break;
        case '-': u = 0x2d; break;
        case '=': u = 0x2e; break;
        case '[': u = 0x2f; break;
        case ']': u = 0x30; break;
        case '\\': u = 0x31; break;
        case ';': u = 0x33; break;
        case '\'': u = 0x34; break;
        case '`': u = 0x35; break;
        case ',': u = 0x36; break;
        case '.': u = 0x37; break;
        case '/': u = 0x38; break;
        case '!': m = sh; u = 0x1e; break;
        case '@': m = sh; u = 0x1f; break;
        case '#': m = sh; u = 0x20; break;
        case '$': m = sh; u = 0x21; break;
        case '%': m = sh; u = 0x22; break;
        case '^': m = sh; u = 0x23; break;
        case '&': m = sh; u = 0x24; break;
        case '*': m = sh; u = 0x25; break;
        case '(': m = sh; u = 0x26; break;
        case ')': m = sh; u = 0x27; break;
        case '_': m = sh; u = 0x2d; break;
        case '+': m = sh; u = 0x2e; break;
        case '{': m = sh; u = 0x2f; break;
        case '}': m = sh; u = 0x30; break;
        case '|': m = sh; u = 0x31; break;
        case ':': m = sh; u = 0x33; break;
        case '"': m = sh; u = 0x34; break;
        case '~': m = sh; u = 0x35; break;
        case '<': m = sh; u = 0x36; break;
        case '>': m = sh; u = 0x37; break;
        case '?': m = sh; u = 0x38; break;
        default: return 0;
        }
    }
    if (!u) return 0;
    if (mod) *mod = m;
    if (usage) *usage = u;
    return 1;
}

#ifdef TITAN_KEYS_MAIN

#include <errno.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <poll.h>

static int fail(const char *msg) {
    fprintf(stderr, "titan2-keys: %s\n", msg);
    return 1;
}

static void answer(const uint8_t in[4], uint8_t out[4]);

static int selftest(void) {
    unsigned i;
    uint8_t m = 0, u = 0, gm = 0, gu = 0;
    char g = 0;
    if (titan_specials_report_mods(0x02, 0) != 0)
        return fail("shift must not change an unshifted special");
    if (titan_specials_report_mods(0x22, 0) != 0)
        return fail("both shifts must drop off an unshifted special");
    if (titan_specials_report_mods(0x02 | 0x01, 0) != 0x01)
        return fail("ctrl must survive a special");
    if (titan_specials_report_mods(0x02, 0x02) != 0x02)
        return fail("glyph shift stays");
    if (!titan_specials_linux(46, &m, &u) || m != 0 || u != 0x25)
        return fail("Sym+C is 8");
    if (!titan_glyph_hid('8', &gm, &gu) || gm != m || gu != u)
        return fail("glyph 8 != Sym+C");
    if (!titan_specials_android(29, &m, &u, &g) || g != '@' || m != 0x02 || u != 0x1f)
        return fail("Sym+A is @");
    if (!titan_glyph_hid('@', &gm, &gu) || gm != m || gu != u)
        return fail("glyph @ != Sym+A");
    if (!titan_glyph_hid('&', &m, &u) || m != 0x02 || u != 0x24)
        return fail("ampersand is shift+7");
    for (i = 0; i < sizeof(SPECIALS) / sizeof(SPECIALS[0]); i++) {
        if (!titan_glyph_hid((unsigned char)SPECIALS[i].glyph, &gm, &gu))
            return fail("special glyph missing from US map");
        if (gm != SPECIALS[i].mod || gu != SPECIALS[i].usage)
            return fail("special glyph hid != US chord");
        if (!titan_specials_linux(SPECIALS[i].linux_code, &m, &u))
            return fail("linux special missing");
        if (m != SPECIALS[i].mod || u != SPECIALS[i].usage)
            return fail("linux special hid drift");
    }
    {
        uint8_t in[4], out[4];
        memset(in, 0, sizeof in);
        in[0] = 'A';
        in[1] = 31; /* Android KEYCODE_C */
        answer(in, out);
        if (out[3] != 1 || out[0] != 0 || out[1] != 0x25 || out[2] != '8')
            return fail("android query C is 8");
        memset(in, 0, sizeof in);
        in[0] = 'R';
        in[1] = 0x22; /* both Shifts held */
        in[2] = 0;
        answer(in, out);
        if (out[3] != 1 || out[0] != 0)
            return fail("report-mods query drops Shift");
        memset(in, 0, sizeof in);
        in[0] = 'G';
        in[1] = '!';
        answer(in, out);
        if (out[3] != 1 || out[0] != 0x02 || out[1] != 0x1e)
            return fail("glyph ! is shift+1");
        memset(in, 0, sizeof in);
        in[0] = 'L';
        in[1] = 46; /* Linux KEY_C */
        answer(in, out);
        if (out[3] != 1 || out[0] != 0 || out[1] != 0x25 || out[2] != '8')
            return fail("linux query C is 8");
    }
    printf("titan2-keys selftest ok (%u specials)\n",
           (unsigned)(sizeof(SPECIALS) / sizeof(SPECIALS[0])));
    return 0;
}

static void answer(const uint8_t in[4], uint8_t out[4]) {
    uint8_t m = 0, u = 0;
    char g = 0;
    unsigned n;
    memset(out, 0, 4);
    switch (in[0]) {
    case 'G':
        if (!titan_glyph_hid(in[1], &m, &u)) return;
        out[0] = m;
        out[1] = u;
        out[2] = in[1];
        out[3] = 1;
        return;
    case 'A':
        n = (unsigned)in[1] | ((unsigned)in[2] << 8);
        if (!titan_specials_android(n, &m, &u, &g)) return;
        out[0] = m;
        out[1] = u;
        out[2] = (uint8_t)g;
        out[3] = 1;
        return;
    case 'L':
        n = (unsigned)in[1] | ((unsigned)in[2] << 8);
        if (!titan_specials_linux(n, &m, &u)) return;
        out[0] = m;
        out[1] = u;
        out[3] = 1;
        for (unsigned i = 0; i < sizeof(SPECIALS) / sizeof(SPECIALS[0]); i++) {
            if (SPECIALS[i].linux_code == n) {
                out[2] = (uint8_t)SPECIALS[i].glyph;
                break;
            }
        }
        return;
    case 'R':
        out[0] = titan_specials_report_mods(in[1], in[2]);
        out[3] = 1;
        return;
    default:
        return;
    }
}

static int serve(void) {
    int s;
    struct sockaddr_un addr;
    socklen_t alen;
    struct pollfd pf;
    s = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (s < 0) return fail("socket");
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, TITAN_KEYS_NAME, sizeof(TITAN_KEYS_NAME) - 1);
    alen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1
        + (sizeof(TITAN_KEYS_NAME) - 1));
    if (bind(s, (struct sockaddr *)&addr, alen) != 0) {
        fprintf(stderr, "titan2-keys: bind @%s: %s\n", TITAN_KEYS_NAME, strerror(errno));
        close(s);
        return 1;
    }
    fprintf(stderr, "titan2-keys serve @%s\n", TITAN_KEYS_NAME);
    fflush(stderr);
    pf.fd = s;
    pf.events = POLLIN;
    for (;;) {
        uint8_t in[4], out[4];
        struct sockaddr_un peer;
        socklen_t plen = sizeof peer;
        ssize_t n;
        if (poll(&pf, 1, -1) < 0) {
            if (errno == EINTR) continue;
            break;
        }
        n = recvfrom(s, in, sizeof in, 0, (struct sockaddr *)&peer, &plen);
        if (n != 4) continue;
        answer(in, out);
        (void)sendto(s, out, sizeof out, MSG_DONTWAIT, (struct sockaddr *)&peer, plen);
    }
    close(s);
    return 0;
}

static int cmd_one(int kind, unsigned n) {
    uint8_t m = 0, u = 0;
    char g = 0;
    int ok = 0;
    if (kind == 'G') ok = titan_glyph_hid(n, &m, &u);
    else if (kind == 'A') ok = titan_specials_android(n, &m, &u, &g);
    else ok = titan_specials_linux(n, &m, &u);
    if (!ok) {
        printf("miss\n");
        return 1;
    }
    if (kind == 'A')
        printf("mod=0x%02x usage=0x%02x glyph=%c\n", m, u, g);
    else
        printf("mod=0x%02x usage=0x%02x\n", m, u);
    return 0;
}

int main(int argc, char **argv) {
    if (argc >= 2 && strcmp(argv[1], "--selftest") == 0) return selftest();
    if (argc >= 2 && strcmp(argv[1], "serve") == 0) return serve();
    if (argc >= 3 && strcmp(argv[1], "glyph") == 0)
        return cmd_one('G', (unsigned char)argv[2][0]);
    if (argc >= 3 && strcmp(argv[1], "android") == 0)
        return cmd_one('A', (unsigned)strtoul(argv[2], 0, 0));
    if (argc >= 3 && strcmp(argv[1], "linux") == 0)
        return cmd_one('L', (unsigned)strtoul(argv[2], 0, 0));
    fprintf(stderr, "usage: titan2-keys --selftest | serve | glyph C | android KEY | linux KEY\n");
    return 2;
}

#endif
