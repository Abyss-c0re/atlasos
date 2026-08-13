/*
 * atlas-seatd — local seat hub (free energy).
 *
 * One producer pushes video; viewers get latest frame only (drop-old).
 * App sends key/ptr; input sink receives for uinput.
 *
 *   AF_UNIX  /data/local/tmp/atlas-seat/hub.sock
 *
 * Like Linux bins on a bus — no TCP, no RTP.
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "seat/atlas_io.h"
#include "seat/atlas_seat.h"

#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#ifndef ATLAS_VERSION
#define ATLAS_VERSION "1.0.0"
#endif

static volatile sig_atomic_t g_run = 1;

static void on_sig(int s) {
    (void)s;
    g_run = 0;
}

struct client {
    int fd;
    uint32_t role;
    char name[16];
};

struct hub {
    int lfd;
    struct client c[ATLAS_SEAT_MAX_CLIENTS];
    struct atlas_seat_hdr vhdr;
    uint8_t *vpay;
    size_t vcap;
    int have_v;
};

static void client_close(struct hub *h, int i) {
    if (h->c[i].fd >= 0) close(h->c[i].fd);
    h->c[i].fd = -1;
    h->c[i].role = 0;
    h->c[i].name[0] = 0;
}

static int client_add(struct hub *h, int fd) {
    for (int i = 0; i < ATLAS_SEAT_MAX_CLIENTS; i++) {
        if (h->c[i].fd < 0) {
            h->c[i].fd = fd;
            h->c[i].role = 0;
            h->c[i].name[0] = 0;
            return i;
        }
    }
    return -1;
}

static int store_frame(struct hub *h, const struct atlas_seat_hdr *hdr,
                       const uint8_t *pay) {
    if (hdr->nbytes > ATLAS_SEAT_MAX_FRAME) return -1;
    if (hdr->nbytes > h->vcap) {
        size_t ncap = (size_t)hdr->nbytes + 4096u;
        uint8_t *n = (uint8_t *)realloc(h->vpay, ncap);
        if (!n) return -1;
        h->vpay = n;
        h->vcap = ncap;
    }
    h->vhdr = *hdr;
    if (hdr->nbytes && pay) memcpy(h->vpay, pay, hdr->nbytes);
    h->have_v = 1;
    return 0;
}

static void fanout_video(struct hub *h) {
    if (!h->have_v) return;
    for (int i = 0; i < ATLAS_SEAT_MAX_CLIENTS; i++) {
        if (h->c[i].fd < 0) continue;
        if (h->c[i].role != ATLAS_ROLE_VIEWER && h->c[i].role != ATLAS_ROLE_APP)
            continue;
        if (atlas_send_hdr_pay(h->c[i].fd, &h->vhdr, h->vpay, h->vhdr.nbytes) != 0)
            client_close(h, i);
    }
}

static void fanout_input(struct hub *h, const struct atlas_seat_hdr *hdr,
                         const uint8_t *pay) {
    for (int i = 0; i < ATLAS_SEAT_MAX_CLIENTS; i++) {
        if (h->c[i].fd < 0) continue;
        if (h->c[i].role != ATLAS_ROLE_INPUT) continue;
        if (atlas_send_hdr_pay(h->c[i].fd, hdr, pay, hdr->nbytes) != 0)
            client_close(h, i);
    }
}

static int handle_msg(struct hub *h, int i) {
    struct atlas_seat_hdr hdr;
    if (atlas_recv_hdr(h->c[i].fd, &hdr) != 0) return -1;
    if (hdr.ver != ATLAS_SEAT_VER) return -1;
    if (hdr.nbytes > ATLAS_SEAT_MAX_FRAME) return -1;

    uint8_t *pay = NULL;
    if (hdr.nbytes) {
        pay = (uint8_t *)malloc(hdr.nbytes);
        if (!pay) return -1;
        if (atlas_full_read(h->c[i].fd, pay, hdr.nbytes) != 0) {
            free(pay);
            return -1;
        }
    }

    int rc = 0;
    switch (hdr.type) {
    case ATLAS_T_HELLO: {
        if (hdr.magic != ATLAS_SEAT_MAGIC_CTL ||
            hdr.nbytes < sizeof(struct atlas_seat_hello)) {
            rc = -1;
            break;
        }
        struct atlas_seat_hello *hi = (struct atlas_seat_hello *)pay;
        h->c[i].role = hi->role;
        memcpy(h->c[i].name, hi->name, sizeof(h->c[i].name));
        if ((hi->role == ATLAS_ROLE_VIEWER || hi->role == ATLAS_ROLE_APP) &&
            h->have_v) {
            if (atlas_send_hdr_pay(h->c[i].fd, &h->vhdr, h->vpay, h->vhdr.nbytes) !=
                0)
                rc = -1;
        }
        break;
    }
    case ATLAS_T_FRAME:
        if (hdr.magic != ATLAS_SEAT_MAGIC_VID) {
            rc = -1;
            break;
        }
        /* producer only (or first frames before HELLO) */
        if (h->c[i].role != 0 && h->c[i].role != ATLAS_ROLE_PRODUCER) {
            rc = -1;
            break;
        }
        h->c[i].role = ATLAS_ROLE_PRODUCER;
        if (store_frame(h, &hdr, pay) != 0) {
            rc = -1;
            break;
        }
        fanout_video(h);
        break;

    case ATLAS_T_KEY:
    case ATLAS_T_PTR:
    case ATLAS_T_BTN:
        if (hdr.magic != ATLAS_SEAT_MAGIC_INP) {
            rc = -1;
            break;
        }
        if (h->c[i].role == 0) h->c[i].role = ATLAS_ROLE_APP;
        fanout_input(h, &hdr, pay);
        break;

    case ATLAS_T_PING:
        break;

    case ATLAS_T_BYE:
        rc = -1;
        break;

    default:
        rc = -1;
        break;
    }

    free(pay);
    return rc;
}

static void usage(void) {
    fprintf(stderr,
            "atlas-seatd %s — local seat hub (AF_UNIX only)\n"
            "  atlas-seatd [-s path]\n"
            "  default: %s\n",
            ATLAS_VERSION, ATLAS_SEAT_SOCK);
}

int main(int argc, char **argv) {
    const char *sock = ATLAS_SEAT_SOCK;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-h") || !strcmp(argv[i], "--help")) {
            usage();
            return 0;
        }
        if (!strcmp(argv[i], "-s") && i + 1 < argc) {
            sock = argv[++i];
            continue;
        }
        usage();
        return 2;
    }

    signal(SIGINT, on_sig);
    signal(SIGTERM, on_sig);
    signal(SIGPIPE, SIG_IGN);

    struct hub h;
    memset(&h, 0, sizeof(h));
    for (int i = 0; i < ATLAS_SEAT_MAX_CLIENTS; i++) h.c[i].fd = -1;

    h.lfd = atlas_unix_listen(sock);
    if (h.lfd < 0) {
        perror("atlas-seatd: listen");
        return 1;
    }
    fprintf(stderr, "atlas-seatd: listen %s\n", sock);

    while (g_run) {
        struct pollfd pf[1 + ATLAS_SEAT_MAX_CLIENTS];
        int cmap[1 + ATLAS_SEAT_MAX_CLIENTS];
        int np = 0;

        pf[np].fd = h.lfd;
        pf[np].events = POLLIN;
        pf[np].revents = 0;
        cmap[np] = -1;
        np++;

        for (int i = 0; i < ATLAS_SEAT_MAX_CLIENTS; i++) {
            if (h.c[i].fd < 0) continue;
            pf[np].fd = h.c[i].fd;
            pf[np].events = POLLIN;
            pf[np].revents = 0;
            cmap[np] = i;
            np++;
        }

        int pr = poll(pf, (nfds_t)np, 1000);
        if (pr < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (pr == 0) continue;

        if (pf[0].revents & POLLIN) {
            int cfd = accept4(h.lfd, NULL, NULL, SOCK_CLOEXEC);
            if (cfd >= 0) {
                if (client_add(&h, cfd) < 0) close(cfd);
            }
        }

        for (int p = 1; p < np; p++) {
            if (!(pf[p].revents & (POLLIN | POLLHUP | POLLERR))) continue;
            int ci = cmap[p];
            if (ci < 0) continue;
            if (pf[p].revents & (POLLHUP | POLLERR)) {
                client_close(&h, ci);
                continue;
            }
            if (handle_msg(&h, ci) != 0) client_close(&h, ci);
        }
    }

    for (int i = 0; i < ATLAS_SEAT_MAX_CLIENTS; i++) client_close(&h, i);
    close(h.lfd);
    unlink(sock);
    free(h.vpay);
    fprintf(stderr, "atlas-seatd: exit\n");
    return 0;
}
