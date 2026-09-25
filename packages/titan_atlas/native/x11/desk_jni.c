/* Viewer side of atlas-x. Bionic, no libwayland.
 * Pulls the latest memfd/dma-buf and copies pixels into an int[].
 * ARGB8888 memory on the wire is B,G,R,A, which is Android's ARGB_8888 int.
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include "atlas_present.h"
#include "atlas_io.h"

#include <errno.h>
#include <stdint.h>
#include <jni.h>
#include <poll.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

static uint64_t now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000ull + (uint64_t)ts.tv_nsec / 1000ull;
}

static int connect_seq(const char *path) {
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un a;
    memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    if (!path || strlen(path) >= sizeof(a.sun_path)) {
        close(fd);
        return -1;
    }
    memcpy(a.sun_path, path, strlen(path) + 1);
    if (connect(fd, (struct sockaddr *)&a, sizeof(a)) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

/* Last frame copied into Java. The next pull asks for a newer one, so a
 * still desktop is not memcpy'd sixty times a second. */
static uint32_t seen_gen;
/* After a compositor restart the new server's generation is lower. The
 * old server waits forever for a generation it will never reach, and the
 * glass stays on the last frame. A run of missed pulls starts over at 0. */
static int missed_pulls;

static const char *jpath(JNIEnv *env, jstring js, char *buf, size_t n) {
    const char *p = (*env)->GetStringUTFChars(env, js, NULL);
    if (!p) return NULL;
    if (strlen(p) >= n) {
        (*env)->ReleaseStringUTFChars(env, js, p);
        return NULL;
    }
    memcpy(buf, p, strlen(p) + 1);
    (*env)->ReleaseStringUTFChars(env, js, p);
    return buf;
}

JNIEXPORT jint JNICALL Java_com_titanus2_atlas_DeskClient_pull(JNIEnv *env, jclass cls,
                                                               jstring path, jintArray pixels,
                                                               jintArray meta) {
    (void)cls;
    char sock[256];
    if (!jpath(env, path, sock, sizeof(sock))) return -1;
    if (!pixels || !meta) return -1;
    int fd = connect_seq(sock);
    if (fd < 0) {
        if (++missed_pulls >= 20) seen_gen = 0;
        return -1;
    }
    struct atlas_present_req req;
    memset(&req, 0, sizeof(req));
    req.magic = ATLAS_PRESENT_MAGIC_REQ;
    req.ver = ATLAS_PRESENT_VER;
    req.output = ATLAS_OUT_PANEL;
    req.min_gen = seen_gen ? seen_gen + 1 : 0;
    if (send(fd, &req, sizeof(req), MSG_NOSIGNAL) != (ssize_t)sizeof(req)) {
        close(fd);
        return -1;
    }
    struct pollfd pfd = {.fd = fd, .events = POLLIN};
    if (poll(&pfd, 1, 200) <= 0) {
        close(fd);
        if (++missed_pulls >= 20) seen_gen = 0;
        return 2;
    }
    struct atlas_present_msg m;
    char cbuf[CMSG_SPACE(sizeof(int))];
    struct iovec iov = {.iov_base = &m, .iov_len = sizeof(m)};
    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cbuf;
    msg.msg_controllen = sizeof(cbuf);
    ssize_t n = recvmsg(fd, &msg, 0);
    close(fd);
    if (n != (ssize_t)sizeof(m) || m.magic != ATLAS_PRESENT_MAGIC) return -1;
    jint info[4];
    info[0] = (jint)m.width;
    info[1] = (jint)m.height;
    info[2] = (jint)m.gen;
    info[3] = (jint)m.flags;
    (*env)->SetIntArrayRegion(env, meta, 0, 4, info);
    if (m.flags & ATLAS_PRESENT_NONE) return 2;
    int pfd_pass = -1;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS)
            memcpy(&pfd_pass, CMSG_DATA(c), sizeof(pfd_pass));
    }
    if (seen_gen && m.gen == seen_gen) {
        if (pfd_pass >= 0) close(pfd_pass);
        return 2;
    }
    if (pfd_pass < 0 || m.width == 0 || m.height == 0 || m.width > 2160 || m.height > 2160
            || m.stride < m.width * 4u || m.stride > 2160u * 4u
            || m.height > SIZE_MAX / m.stride) {
        if (pfd_pass >= 0) close(pfd_pass);
        return -1;
    }
    jsize cap = (*env)->GetArrayLength(env, pixels);
    if (cap < 0 || (uint32_t)cap < m.width * m.height) {
        close(pfd_pass);
        return 1;
    }
    size_t nbytes = (size_t)m.stride * (size_t)m.height;
    void *map = mmap(NULL, nbytes, PROT_READ, MAP_SHARED, pfd_pass, (off_t)m.offset);
    if (map == MAP_FAILED) {
        close(pfd_pass);
        return -1;
    }
    jint *dst = (*env)->GetIntArrayElements(env, pixels, NULL);
    if (!dst) {
        munmap(map, nbytes);
        close(pfd_pass);
        return -1;
    }
    const uint8_t *src = (const uint8_t *)map;
    if (m.stride == m.width * 4u) {
        memcpy(dst, src, (size_t)m.width * (size_t)m.height * 4u);
    } else {
        for (uint32_t y = 0; y < m.height; y++) {
            memcpy(dst + (size_t)y * m.width, src + (size_t)y * m.stride, m.width * 4u);
        }
    }
    (*env)->ReleaseIntArrayElements(env, pixels, dst, 0);
    munmap(map, nbytes);
    close(pfd_pass);
    seen_gen = m.gen;
    missed_pulls = 0;
    return 0;
}

static int send_key_fd(int fd, uint32_t code, int down) {
    if (atlas_hello(fd, ATLAS_ROLE_APP, "desk") != 0) return -1;
    struct atlas_seat_hdr h;
    memset(&h, 0, sizeof(h));
    h.magic = ATLAS_SEAT_MAGIC_INP;
    h.ver = ATLAS_SEAT_VER;
    h.type = ATLAS_T_KEY;
    h.w = code;
    h.h = down ? 1u : 0u;
    h.pts_us = now_us();
    return atlas_send_hdr_pay(fd, &h, NULL, 0);
}

JNIEXPORT jint JNICALL Java_com_titanus2_atlas_DeskClient_key(JNIEnv *env, jclass cls, jstring path,
                                                              jint code, jint down) {
    (void)cls;
    char sock[256];
    if (!jpath(env, path, sock, sizeof(sock))) return -1;
    int fd = atlas_unix_connect(sock);
    if (fd < 0) return -1;
    int rc = send_key_fd(fd, (uint32_t)code, down ? 1 : 0);
    close(fd);
    return rc;
}

JNIEXPORT jint JNICALL Java_com_titanus2_atlas_DeskClient_pointer(JNIEnv *env, jclass cls,
                                                                  jstring path, jint dx, jint dy,
                                                                  jint buttons, jint wheel,
                                                                  jint absolute) {
    (void)cls;
    char sock[256];
    if (!jpath(env, path, sock, sizeof(sock))) return -1;
    int fd = atlas_unix_connect(sock);
    if (fd < 0) return -1;
    if (atlas_hello(fd, ATLAS_ROLE_APP, "desk") != 0) {
        close(fd);
        return -1;
    }
    struct atlas_seat_ptr p;
    p.dx = dx;
    p.dy = dy;
    p.wheel = wheel;
    p.buttons = (uint32_t)buttons;
    struct atlas_seat_hdr h;
    memset(&h, 0, sizeof(h));
    h.magic = ATLAS_SEAT_MAGIC_INP;
    h.ver = ATLAS_SEAT_VER;
    h.type = ATLAS_T_PTR;
    h.fmt = absolute ? ATLAS_PTR_ABSOLUTE : 0;
    h.nbytes = (uint32_t)sizeof(p);
    h.pts_us = now_us();
    int rc = atlas_send_hdr_pay(fd, &h, &p, sizeof(p));
    close(fd);
    return rc;
}
