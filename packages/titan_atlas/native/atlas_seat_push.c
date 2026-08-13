/*
 * atlas-seat-push — producer bin.
 *
 * Read framed or raw payload from stdin, push to atlas-seatd.
 *
 * Framed mode (default for H264 pipe from ffmpeg):
 *   length-prefixed is NOT used; each stdin chunk is one annex-B blob when
 *   using -F h264 -W -H and reading fixed? Better:
 *
 *   Simple mode: each write is one complete frame after a 4-byte BE size? No —
 *   freer: use our own AVDF on stdin or raw continuous with -S size.
 *
 * Minimal contract:
 *   atlas-seat-push -f h264 -w 1280 -h 720 < frame.h264   # one frame then exit
 *   atlas-seat-push -f h264 -w 1280 -h 720 -m             # multi: [u32 le nbytes][payload]...
 *
 * ffmpeg example (multi length-prefix via rawvideo not ideal for h264):
 *   ffmpeg … -f h264 - | atlas-seat-push -f h264 -w W -h H -a
 *   (-a = annex-B split on start codes — best effort)
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "seat/atlas_io.h"
#include "seat/atlas_seat.h"

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

static uint32_t parse_fmt(const char *s) {
    if (!s) return ATLAS_FMT_H264;
    if (!strcmp(s, "h264") || !strcmp(s, "avc")) return ATLAS_FMT_H264;
    if (!strcmp(s, "rgba")) return ATLAS_FMT_RGBA8888;
    if (!strcmp(s, "i420")) return ATLAS_FMT_I420;
    if (!strcmp(s, "nv12")) return ATLAS_FMT_NV12;
    return ATLAS_FMT_H264;
}

static int push_frame(int fd, uint32_t w, uint32_t h, uint32_t fmt,
                      const uint8_t *p, uint32_t n) {
    struct atlas_seat_hdr hdr;
    memset(&hdr, 0, sizeof(hdr));
    hdr.magic = ATLAS_SEAT_MAGIC_VID;
    hdr.ver = ATLAS_SEAT_VER;
    hdr.type = ATLAS_T_FRAME;
    hdr.w = w;
    hdr.h = h;
    hdr.fmt = fmt;
    hdr.nbytes = n;
    hdr.pts_us = now_us();
    return atlas_send_hdr_pay(fd, &hdr, p, n);
}

/* Best-effort annex-B NAL aggregation: emit on AUD or large gap / max. */
static int push_annexb_stream(int fd, uint32_t w, uint32_t h, uint32_t fmt) {
    enum { CAP = 2 * 1024 * 1024 };
    uint8_t *buf = (uint8_t *)malloc(CAP);
    if (!buf) return 1;
    size_t len = 0;
    uint8_t tmp[65536];
    for (;;) {
        ssize_t r = read(0, tmp, sizeof(tmp));
        if (r < 0) {
            if (errno == EINTR) continue;
            free(buf);
            return 1;
        }
        if (r == 0) {
            if (len && push_frame(fd, w, h, fmt, buf, (uint32_t)len) != 0) {
                free(buf);
                return 1;
            }
            free(buf);
            return 0;
        }
        /* append */
        if (len + (size_t)r > CAP) {
            /* flush what we have as one AU */
            if (len && push_frame(fd, w, h, fmt, buf, (uint32_t)len) != 0) {
                free(buf);
                return 1;
            }
            len = 0;
        }
        size_t room = CAP - len;
        size_t chunk = (size_t)r;
        if (chunk > room) chunk = room;
        memcpy(buf + len, tmp, chunk);
        len += chunk;
        /* crude AU: flush when buffer past 64k or full */
        if (len > 65536 || len >= CAP) {
            if (push_frame(fd, w, h, fmt, buf, (uint32_t)len) != 0) {
                free(buf);
                return 1;
            }
            len = 0;
            if (chunk < (size_t)r) {
                /* remainder that did not fit — start next AU */
                size_t rest = (size_t)r - chunk;
                if (rest > CAP) rest = CAP;
                memcpy(buf, tmp + chunk, rest);
                len = rest;
            }
        }
    }
}

static int push_sized_stream(int fd, uint32_t w, uint32_t h, uint32_t fmt) {
    for (;;) {
        uint32_t n = 0;
        if (atlas_full_read(0, &n, 4) != 0) return 0; /* clean EOF */
        if (n == 0 || n > ATLAS_SEAT_MAX_FRAME) return 1;
        uint8_t *pay = (uint8_t *)malloc(n);
        if (!pay) return 1;
        if (atlas_full_read(0, pay, n) != 0) {
            free(pay);
            return 1;
        }
        int rc = push_frame(fd, w, h, fmt, pay, n);
        free(pay);
        if (rc != 0) return 1;
    }
}

static int push_one_file(int fd, uint32_t w, uint32_t h, uint32_t fmt) {
    enum { CAP = ATLAS_SEAT_MAX_FRAME };
    uint8_t *buf = (uint8_t *)malloc(CAP);
    if (!buf) return 1;
    size_t len = 0;
    for (;;) {
        ssize_t r = read(0, buf + len, CAP - len);
        if (r < 0) {
            if (errno == EINTR) continue;
            free(buf);
            return 1;
        }
        if (r == 0) break;
        len += (size_t)r;
        if (len >= CAP) break;
    }
    int rc = len ? push_frame(fd, w, h, fmt, buf, (uint32_t)len) : 0;
    free(buf);
    return rc != 0;
}

static void usage(void) {
    fprintf(stderr,
            "atlas-seat-push %s — stdin → seatd (producer)\n"
            "  atlas-seat-push -w W -h H [-f h264|rgba|i420|nv12] [-s sock]\n"
            "    -m   multi: [u32le nbytes][payload]…\n"
            "    -a   annex-B stream (default for h264 multi-ish)\n"
            "    (default) single frame from stdin then exit\n",
            ATLAS_VERSION);
}

int main(int argc, char **argv) {
    const char *sock = ATLAS_SEAT_SOCK;
    uint32_t w = 1280, h = 720, fmt = ATLAS_FMT_H264;
    int multi = 0, annex = 0;

    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-h") || !strcmp(argv[i], "--help")) {
            usage();
            return 0;
        }
        if (!strcmp(argv[i], "-s") && i + 1 < argc) {
            sock = argv[++i];
            continue;
        }
        if (!strcmp(argv[i], "-w") && i + 1 < argc) {
            w = (uint32_t)atoi(argv[++i]);
            continue;
        }
        if (!strcmp(argv[i], "-H") && i + 1 < argc) {
            h = (uint32_t)atoi(argv[++i]);
            continue;
        }
        /* allow -h height after --help check with -H; also --height */
        if (!strcmp(argv[i], "--height") && i + 1 < argc) {
            h = (uint32_t)atoi(argv[++i]);
            continue;
        }
        if (!strcmp(argv[i], "-f") && i + 1 < argc) {
            fmt = parse_fmt(argv[++i]);
            continue;
        }
        if (!strcmp(argv[i], "-m")) {
            multi = 1;
            continue;
        }
        if (!strcmp(argv[i], "-a")) {
            annex = 1;
            continue;
        }
        usage();
        return 2;
    }

    int fd = atlas_unix_connect(sock);
    if (fd < 0) {
        perror("atlas-seat-push: connect");
        return 1;
    }
    if (atlas_hello(fd, ATLAS_ROLE_PRODUCER, "push") != 0) {
        fprintf(stderr, "atlas-seat-push: hello failed\n");
        close(fd);
        return 1;
    }

    int rc;
    if (multi)
        rc = push_sized_stream(fd, w, h, fmt);
    else if (annex || fmt == ATLAS_FMT_H264)
        rc = push_annexb_stream(fd, w, h, fmt);
    else
        rc = push_one_file(fd, w, h, fmt);

    close(fd);
    return rc;
}
