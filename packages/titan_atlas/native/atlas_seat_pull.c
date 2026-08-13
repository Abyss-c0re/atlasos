/*
 * atlas-seat-pull — viewer bin (stdout frames for decode / debug).
 *
 *   atlas-seat-pull | …decoder…
 *   atlas-seat-pull -n 1 > frame.bin   # one frame
 *
 * Wire payload only on stdout; meta on stderr.
 * Atlas app will speak the same protocol natively (Moonlight MediaCodec path).
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "seat/atlas_io.h"
#include "seat/atlas_seat.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#ifndef ATLAS_VERSION
#define ATLAS_VERSION "1.0.0"
#endif

static void usage(void) {
    fprintf(stderr,
            "atlas-seat-pull %s — seatd → stdout (viewer)\n"
            "  atlas-seat-pull [-s sock] [-n count|0=inf] [-H]\n"
            "  -H  write full AVDF headers to stdout (default: payload only)\n",
            ATLAS_VERSION);
}

int main(int argc, char **argv) {
    const char *sock = ATLAS_SEAT_SOCK;
    int count = 0; /* 0 = forever */
    int with_hdr = 0;

    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-h") || !strcmp(argv[i], "--help")) {
            usage();
            return 0;
        }
        if (!strcmp(argv[i], "-s") && i + 1 < argc) {
            sock = argv[++i];
            continue;
        }
        if (!strcmp(argv[i], "-n") && i + 1 < argc) {
            count = atoi(argv[++i]);
            continue;
        }
        if (!strcmp(argv[i], "-H")) {
            with_hdr = 1;
            continue;
        }
        usage();
        return 2;
    }

    int fd = atlas_unix_connect(sock);
    if (fd < 0) {
        perror("atlas-seat-pull: connect");
        return 1;
    }
    if (atlas_hello(fd, ATLAS_ROLE_VIEWER, "pull") != 0) {
        fprintf(stderr, "atlas-seat-pull: hello failed\n");
        close(fd);
        return 1;
    }

    int got = 0;
    for (;;) {
        struct atlas_seat_hdr hdr;
        if (atlas_recv_hdr(fd, &hdr) != 0) break;
        if (hdr.magic != ATLAS_SEAT_MAGIC_VID || hdr.type != ATLAS_T_FRAME) {
            /* skip non-video */
            if (hdr.nbytes) {
                uint8_t *skip = (uint8_t *)malloc(hdr.nbytes);
                if (!skip) break;
                atlas_full_read(fd, skip, hdr.nbytes);
                free(skip);
            }
            continue;
        }
        if (hdr.nbytes > ATLAS_SEAT_MAX_FRAME) break;
        uint8_t *pay = NULL;
        if (hdr.nbytes) {
            pay = (uint8_t *)malloc(hdr.nbytes);
            if (!pay) break;
            if (atlas_full_read(fd, pay, hdr.nbytes) != 0) {
                free(pay);
                break;
            }
        }
        fprintf(stderr, "frame %ux%u fmt=%u n=%u pts=%llu\n", hdr.w, hdr.h,
                hdr.fmt, hdr.nbytes, (unsigned long long)hdr.pts_us);
        if (with_hdr) {
            if (atlas_full_write(1, &hdr, ATLAS_SEAT_HDR_SIZE) != 0) {
                free(pay);
                break;
            }
        }
        if (hdr.nbytes && pay) {
            if (atlas_full_write(1, pay, hdr.nbytes) != 0) {
                free(pay);
                break;
            }
        }
        free(pay);
        got++;
        if (count > 0 && got >= count) break;
    }

    close(fd);
    return 0;
}
