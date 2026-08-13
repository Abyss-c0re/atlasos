/*
 * atlas-seat-input — input edge bins.
 *
 * Modes:
 *   atlas-seat-input send-key CODE DOWN [mods]   # app → hub
 *   atlas-seat-input send-ptr DX DY [buttons wheel]
 *   atlas-seat-input sink [-u]                   # hub → stdout or uinput
 *
 * sink -u: create /dev/uinput virtual keyboard+mouse (root, Linux).
 * Without -u: print events (test).
 *
 * Plane kinship: same role as touchpadd — local inject, not network HID.
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "seat/atlas_io.h"
#include "seat/atlas_seat.h"

#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#ifndef ATLAS_VERSION
#define ATLAS_VERSION "1.0.0"
#endif

static uint64_t now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000ull + (uint64_t)ts.tv_nsec / 1000ull;
}

static int send_key(int fd, uint32_t code, uint32_t down, uint32_t mods) {
    struct atlas_seat_hdr h;
    memset(&h, 0, sizeof(h));
    h.magic = ATLAS_SEAT_MAGIC_INP;
    h.ver = ATLAS_SEAT_VER;
    h.type = ATLAS_T_KEY;
    h.w = code;
    h.h = down ? 1u : 0u;
    h.fmt = mods;
    h.nbytes = 0;
    h.pts_us = now_us();
    return atlas_send_hdr_pay(fd, &h, NULL, 0);
}

static int send_ptr(int fd, int32_t dx, int32_t dy, uint32_t buttons,
                    int32_t wheel) {
    struct atlas_seat_ptr p;
    p.dx = dx;
    p.dy = dy;
    p.wheel = wheel;
    p.buttons = buttons;
    struct atlas_seat_hdr h;
    memset(&h, 0, sizeof(h));
    h.magic = ATLAS_SEAT_MAGIC_INP;
    h.ver = ATLAS_SEAT_VER;
    h.type = ATLAS_T_PTR;
    h.nbytes = (uint32_t)sizeof(p);
    h.pts_us = now_us();
    return atlas_send_hdr_pay(fd, &h, &p, sizeof(p));
}

static int uinput_open(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) fd = open("/dev/input/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) return -1;

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_REL);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    for (int k = 0; k < 256; k++) ioctl(fd, UI_SET_KEYBIT, k);
    ioctl(fd, UI_SET_KEYBIT, BTN_LEFT);
    ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT);
    ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE);
    ioctl(fd, UI_SET_RELBIT, REL_X);
    ioctl(fd, UI_SET_RELBIT, REL_Y);
    ioctl(fd, UI_SET_RELBIT, REL_WHEEL);

    struct uinput_setup us;
    memset(&us, 0, sizeof(us));
    us.id.bustype = BUS_VIRTUAL;
    us.id.vendor = 0x5442;  /* TB */
    us.id.product = 0x5345; /* SE */
    snprintf(us.name, sizeof(us.name), "atlas-seat");
    if (ioctl(fd, UI_DEV_SETUP, &us) != 0) {
        close(fd);
        return -1;
    }
    if (ioctl(fd, UI_DEV_CREATE) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static void uinput_emit(int ufd, uint16_t type, uint16_t code, int32_t val) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = val;
    (void)write(ufd, &ev, sizeof(ev));
}

static void uinput_syn(int ufd) {
    uinput_emit(ufd, EV_SYN, SYN_REPORT, 0);
}

static int sink_loop(int fd, int ufd) {
    for (;;) {
        struct atlas_seat_hdr hdr;
        if (atlas_recv_hdr(fd, &hdr) != 0) break;
        uint8_t *pay = NULL;
        if (hdr.nbytes) {
            if (hdr.nbytes > 4096) break;
            pay = (uint8_t *)malloc(hdr.nbytes);
            if (!pay) break;
            if (atlas_full_read(fd, pay, hdr.nbytes) != 0) {
                free(pay);
                break;
            }
        }

        if (hdr.type == ATLAS_T_KEY) {
            uint32_t code = hdr.w;
            uint32_t down = hdr.h;
            fprintf(stderr, "key code=%u down=%u mods=%u\n", code, down, hdr.fmt);
            if (ufd >= 0) {
                uinput_emit(ufd, EV_KEY, (uint16_t)code, down ? 1 : 0);
                uinput_syn(ufd);
            }
        } else if (hdr.type == ATLAS_T_PTR && pay &&
                   hdr.nbytes >= sizeof(struct atlas_seat_ptr)) {
            struct atlas_seat_ptr *p = (struct atlas_seat_ptr *)pay;
            fprintf(stderr, "ptr dx=%d dy=%d btn=%u wheel=%d\n", p->dx, p->dy,
                    p->buttons, p->wheel);
            if (ufd >= 0) {
                if (p->dx) uinput_emit(ufd, EV_REL, REL_X, p->dx);
                if (p->dy) uinput_emit(ufd, EV_REL, REL_Y, p->dy);
                if (p->wheel) uinput_emit(ufd, EV_REL, REL_WHEEL, p->wheel);
                /* buttons: edge-less absolute for simplicity */
                uinput_emit(ufd, EV_KEY, BTN_LEFT, (p->buttons & 1) ? 1 : 0);
                uinput_emit(ufd, EV_KEY, BTN_RIGHT, (p->buttons & 2) ? 1 : 0);
                uinput_emit(ufd, EV_KEY, BTN_MIDDLE, (p->buttons & 4) ? 1 : 0);
                uinput_syn(ufd);
            }
        }
        free(pay);
    }
    return 0;
}

static void usage(void) {
    fprintf(stderr,
            "atlas-seat-input %s\n"
            "  send-key CODE DOWN [mods]\n"
            "  send-ptr DX DY [buttons [wheel]]\n"
            "  sink [-u] [-s sock]     # -u = uinput\n",
            ATLAS_VERSION);
}

int main(int argc, char **argv) {
    const char *sock = ATLAS_SEAT_SOCK;
    if (argc < 2) {
        usage();
        return 2;
    }

    /* global -s before mode */
    int argi = 1;
    if (argi < argc && !strcmp(argv[argi], "-s") && argi + 1 < argc) {
        sock = argv[argi + 1];
        argi += 2;
    }
    if (argi >= argc) {
        usage();
        return 2;
    }

    const char *mode = argv[argi++];

    if (!strcmp(mode, "send-key")) {
        if (argi + 1 >= argc) {
            usage();
            return 2;
        }
        uint32_t code = (uint32_t)atoi(argv[argi++]);
        uint32_t down = (uint32_t)atoi(argv[argi++]);
        uint32_t mods = (argi < argc) ? (uint32_t)atoi(argv[argi++]) : 0;
        int fd = atlas_unix_connect(sock);
        if (fd < 0) {
            perror("connect");
            return 1;
        }
        if (atlas_hello(fd, ATLAS_ROLE_APP, "app") != 0) return 1;
        int rc = send_key(fd, code, down, mods);
        close(fd);
        return rc != 0;
    }

    if (!strcmp(mode, "send-ptr")) {
        if (argi + 1 >= argc) {
            usage();
            return 2;
        }
        int32_t dx = (int32_t)atoi(argv[argi++]);
        int32_t dy = (int32_t)atoi(argv[argi++]);
        uint32_t btn = (argi < argc) ? (uint32_t)atoi(argv[argi++]) : 0;
        int32_t wheel = (argi < argc) ? (int32_t)atoi(argv[argi++]) : 0;
        int fd = atlas_unix_connect(sock);
        if (fd < 0) {
            perror("connect");
            return 1;
        }
        if (atlas_hello(fd, ATLAS_ROLE_APP, "app") != 0) return 1;
        int rc = send_ptr(fd, dx, dy, btn, wheel);
        close(fd);
        return rc != 0;
    }

    if (!strcmp(mode, "sink")) {
        int use_u = 0;
        while (argi < argc) {
            if (!strcmp(argv[argi], "-u")) {
                use_u = 1;
                argi++;
                continue;
            }
            if (!strcmp(argv[argi], "-s") && argi + 1 < argc) {
                sock = argv[argi + 1];
                argi += 2;
                continue;
            }
            break;
        }
        int fd = atlas_unix_connect(sock);
        if (fd < 0) {
            perror("connect");
            return 1;
        }
        if (atlas_hello(fd, ATLAS_ROLE_INPUT, "uinput") != 0) return 1;
        int ufd = -1;
        if (use_u) {
            ufd = uinput_open();
            if (ufd < 0) {
                perror("uinput");
                close(fd);
                return 1;
            }
            fprintf(stderr, "atlas-seat-input: uinput up\n");
        }
        int rc = sink_loop(fd, ufd);
        if (ufd >= 0) {
            ioctl(ufd, UI_DEV_DESTROY);
            close(ufd);
        }
        close(fd);
        return rc;
    }

    usage();
    return 2;
}
