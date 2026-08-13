/* atlas_io.h — full read/write + unix connect (header-only, no libc++). */
#ifndef ATLAS_IO_H
#define ATLAS_IO_H

#include "atlas_seat.h"

#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

static inline int atlas_full_read(int fd, void *buf, size_t n) {
    uint8_t *p = (uint8_t *)buf;
    size_t got = 0;
    while (got < n) {
        ssize_t r = read(fd, p + got, n - got);
        if (r == 0) return -1; /* EOF */
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        got += (size_t)r;
    }
    return 0;
}

static inline int atlas_full_write(int fd, const void *buf, size_t n) {
    const uint8_t *p = (const uint8_t *)buf;
    size_t put = 0;
    while (put < n) {
        ssize_t w = write(fd, p + put, n - put);
        if (w < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        put += (size_t)w;
    }
    return 0;
}

static inline int atlas_send_hdr_pay(int fd, const struct atlas_seat_hdr *h,
                                     const void *pay, size_t pay_n) {
    if (h->nbytes != (uint32_t)pay_n) return -1;
    if (atlas_full_write(fd, h, ATLAS_SEAT_HDR_SIZE) != 0) return -1;
    if (pay_n && pay && atlas_full_write(fd, pay, pay_n) != 0) return -1;
    return 0;
}

static inline int atlas_recv_hdr(int fd, struct atlas_seat_hdr *h) {
    return atlas_full_read(fd, h, ATLAS_SEAT_HDR_SIZE);
}

static inline int atlas_ensure_dir(const char *path) {
    struct stat st;
    if (stat(path, &st) == 0) {
        if (S_ISDIR(st.st_mode)) return 0;
        return -1;
    }
    if (mkdir(path, 0755) != 0 && errno != EEXIST) return -1;
    return 0;
}

/* Parent directory of a socket path (mutates a copy). */
static inline int atlas_ensure_parent(const char *path) {
    char tmp[sizeof(((struct sockaddr_un *)0)->sun_path)];
    size_t n = strlen(path);
    if (n == 0 || n >= sizeof(tmp)) return -1;
    memcpy(tmp, path, n + 1);
    char *slash = strrchr(tmp, '/');
    if (!slash || slash == tmp) return 0;
    *slash = '\0';
    /* one level is enough for product paths; create mid if missing */
    if (atlas_ensure_dir(tmp) != 0) {
        /* try grandparent + parent (…/atlas-seat/hub.sock) */
        char *s2 = strrchr(tmp, '/');
        if (s2 && s2 != tmp) {
            *s2 = '\0';
            (void)atlas_ensure_dir(tmp);
            *s2 = '/';
        }
        if (atlas_ensure_dir(tmp) != 0) return -1;
    }
    return 0;
}

static inline int atlas_unix_listen(const char *path) {
    if (atlas_ensure_parent(path) != 0) return -1;
    unlink(path);
    int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) return -1;
    struct sockaddr_un a;
    memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    if (strlen(path) >= sizeof(a.sun_path)) {
        close(s);
        return -1;
    }
    memcpy(a.sun_path, path, strlen(path) + 1);
    if (bind(s, (struct sockaddr *)&a, sizeof(a)) != 0) {
        close(s);
        return -1;
    }
    chmod(path, 0666); /* app + root; free energy on same device */
    if (listen(s, 8) != 0) {
        close(s);
        unlink(path);
        return -1;
    }
    return s;
}

static inline int atlas_unix_connect(const char *path) {
    int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) return -1;
    struct sockaddr_un a;
    memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    if (strlen(path) >= sizeof(a.sun_path)) {
        close(s);
        return -1;
    }
    memcpy(a.sun_path, path, strlen(path) + 1);
    if (connect(s, (struct sockaddr *)&a, sizeof(a)) != 0) {
        close(s);
        return -1;
    }
    return s;
}

static inline int atlas_hello(int fd, uint32_t role, const char *name) {
    struct atlas_seat_hello hi;
    memset(&hi, 0, sizeof(hi));
    hi.role = role;
    if (name) {
        size_t n = strlen(name);
        if (n > sizeof(hi.name) - 1) n = sizeof(hi.name) - 1;
        memcpy(hi.name, name, n);
    }
    struct atlas_seat_hdr h;
    memset(&h, 0, sizeof(h));
    h.magic = ATLAS_SEAT_MAGIC_CTL;
    h.ver = ATLAS_SEAT_VER;
    h.type = ATLAS_T_HELLO;
    h.nbytes = (uint32_t)sizeof(hi);
    return atlas_send_hdr_pay(fd, &h, &hi, sizeof(hi));
}

#endif /* ATLAS_IO_H */
