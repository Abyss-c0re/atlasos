/* Wayland client used to prove atlas-x: one surface, then a second colour
 * after a seat key. The present pull must observe only the later colour.
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include <wayland-client.h>
#include <wayland-client-protocol.h>

#include "xdg-shell-client.h"
#include "linux-dmabuf-client.h"

static int configured;
static int entered;
static int keyed;
static int32_t cfg_w, cfg_h;
static struct wl_buffer *buffer;
static struct wl_buffer *buffer_b;
static uint32_t *pixels;
static uint32_t *pixels_b;
static int stride;
static int shm_fd = -1;
static int shm_fd_b = -1;

static void fill(uint32_t px) {
    int n = cfg_w * cfg_h;
    for (int i = 0; i < n; i++) pixels[i] = px;
}

static void xdg_ping(void *data, struct xdg_wm_base *wm, uint32_t serial) {
    (void)data;
    xdg_wm_base_pong(wm, serial);
}

static const struct xdg_wm_base_listener wm_listener = {.ping = xdg_ping};

static void xdg_surface_configure(void *data, struct xdg_surface *surf, uint32_t serial) {
    (void)data;
    xdg_surface_ack_configure(surf, serial);
    configured = 1;
}

static const struct xdg_surface_listener xdg_listener = {.configure = xdg_surface_configure};

static void toplevel_configure(void *data, struct xdg_toplevel *top, int32_t w, int32_t h,
                               struct wl_array *states) {
    (void)data;
    (void)top;
    (void)states;
    if (w > 0) cfg_w = w;
    if (h > 0) cfg_h = h;
}

static void toplevel_close(void *data, struct xdg_toplevel *top) {
    (void)data;
    (void)top;
}

static const struct xdg_toplevel_listener top_listener = {
    .configure = toplevel_configure,
    .close = toplevel_close,
};

static void kb_keymap(void *d, struct wl_keyboard *k, uint32_t fmt, int32_t fd, uint32_t size) {
    (void)d;
    (void)k;
    (void)fmt;
    (void)size;
    close(fd);
}

static void kb_enter(void *d, struct wl_keyboard *k, uint32_t serial, struct wl_surface *s,
                     struct wl_array *keys) {
    (void)d;
    (void)k;
    (void)serial;
    (void)s;
    (void)keys;
    entered = 1;
}

static void kb_leave(void *d, struct wl_keyboard *k, uint32_t serial, struct wl_surface *s) {
    (void)d;
    (void)k;
    (void)serial;
    (void)s;
}

static void kb_key(void *d, struct wl_keyboard *k, uint32_t serial, uint32_t time, uint32_t key,
                   uint32_t state) {
    (void)d;
    (void)k;
    (void)serial;
    (void)time;
    (void)key;
    if (state == WL_KEYBOARD_KEY_STATE_PRESSED) keyed = 1;
}

static void kb_mod(void *d, struct wl_keyboard *k, uint32_t serial, uint32_t dep, uint32_t lat,
                   uint32_t lck, uint32_t grp) {
    (void)d;
    (void)k;
    (void)serial;
    (void)dep;
    (void)lat;
    (void)lck;
    (void)grp;
}

static void kb_repeat(void *d, struct wl_keyboard *k, int32_t rate, int32_t delay) {
    (void)d;
    (void)k;
    (void)rate;
    (void)delay;
}

static const struct wl_keyboard_listener kb_listener = {
    .keymap = kb_keymap,
    .enter = kb_enter,
    .leave = kb_leave,
    .key = kb_key,
    .modifiers = kb_mod,
    .repeat_info = kb_repeat,
};

static void seat_caps(void *data, struct wl_seat *seat, uint32_t caps) {
    (void)data;
    if (caps & WL_SEAT_CAPABILITY_KEYBOARD) {
        struct wl_keyboard *kb = wl_seat_get_keyboard(seat);
        wl_keyboard_add_listener(kb, &kb_listener, NULL);
    }
}

static void seat_name(void *data, struct wl_seat *seat, const char *name) {
    (void)data;
    (void)seat;
    (void)name;
}

static const struct wl_seat_listener seat_listener = {.capabilities = seat_caps, .name = seat_name};

struct globals {
    struct wl_compositor *comp;
    struct wl_shm *shm;
    struct xdg_wm_base *wm;
    struct wl_seat *seat;
    struct zwp_linux_dmabuf_v1 *dma;
};

static void registry_global(void *data, struct wl_registry *reg, uint32_t id, const char *iface,
                            uint32_t ver) {
    struct globals *g = data;
    if (!strcmp(iface, "wl_compositor"))
        g->comp = wl_registry_bind(reg, id, &wl_compositor_interface, ver < 4 ? ver : 4);
    else if (!strcmp(iface, "wl_shm"))
        g->shm = wl_registry_bind(reg, id, &wl_shm_interface, 1);
    else if (!strcmp(iface, "xdg_wm_base"))
        g->wm = wl_registry_bind(reg, id, &xdg_wm_base_interface, ver < 3 ? ver : 3);
    else if (!strcmp(iface, "wl_seat"))
        g->seat = wl_registry_bind(reg, id, &wl_seat_interface, ver < 5 ? ver : 5);
    else if (!strcmp(iface, "zwp_linux_dmabuf_v1"))
        g->dma = wl_registry_bind(reg, id, &zwp_linux_dmabuf_v1_interface, ver < 3 ? ver : 3);
}

static void registry_remove(void *data, struct wl_registry *reg, uint32_t id) {
    (void)data;
    (void)reg;
    (void)id;
}

static const struct wl_registry_listener reg_listener = {
    .global = registry_global,
    .global_remove = registry_remove,
};

static int make_shm(struct wl_shm *shm) {
    stride = cfg_w * 4;
    int nbytes = stride * cfg_h;
    shm_fd = memfd_create("atlas-x-paint", MFD_CLOEXEC);
    if (shm_fd < 0 || ftruncate(shm_fd, nbytes) != 0) return -1;
    pixels = mmap(NULL, (size_t)nbytes, PROT_READ | PROT_WRITE, MAP_SHARED, shm_fd, 0);
    if (pixels == MAP_FAILED) return -1;
    struct wl_shm_pool *pool = wl_shm_create_pool(shm, shm_fd, nbytes);
    buffer = wl_shm_pool_create_buffer(pool, 0, cfg_w, cfg_h, stride, WL_SHM_FORMAT_ARGB8888);
    wl_shm_pool_destroy(pool);
    return buffer ? 0 : -1;
}

static struct wl_buffer *dma_buf(struct zwp_linux_dmabuf_v1 *dma, int *fd, uint32_t **pix) {
    int nbytes = stride * cfg_h;
    *fd = memfd_create("atlas-x-paint-dma", MFD_CLOEXEC);
    if (*fd < 0 || ftruncate(*fd, nbytes) != 0) return NULL;
    *pix = mmap(NULL, (size_t)nbytes, PROT_READ | PROT_WRITE, MAP_SHARED, *fd, 0);
    if (*pix == MAP_FAILED) return NULL;
    uint32_t fmt = ('A') | ('R' << 8) | ('2' << 16) | ('4' << 24);
    uint64_t mod = 0x00ffffffffffffffull;
    struct zwp_linux_buffer_params_v1 *p = zwp_linux_dmabuf_v1_create_params(dma);
    zwp_linux_buffer_params_v1_add(p, *fd, 0, 0, (uint32_t)stride, (uint32_t)(mod >> 32),
                                   (uint32_t)mod);
    struct wl_buffer *b = zwp_linux_buffer_params_v1_create_immed(p, cfg_w, cfg_h, fmt, 0);
    zwp_linux_buffer_params_v1_destroy(p);
    return b;
}

static int make_dma(struct zwp_linux_dmabuf_v1 *dma) {
    stride = cfg_w * 4;
    buffer = dma_buf(dma, &shm_fd, &pixels);
    buffer_b = dma_buf(dma, &shm_fd_b, &pixels_b);
    return (buffer && buffer_b) ? 0 : -1;
}

static void touch(const char *dir, const char *name) {
    char path[512];
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    int fd = open(path, O_CREAT | O_WRONLY | O_CLOEXEC, 0644);
    if (fd >= 0) close(fd);
}

int main(int argc, char **argv) {
    const char *dir = NULL;
    int use_dma = 0;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-d") && i + 1 < argc) dir = argv[++i];
        else if (!strcmp(argv[i], "--dma")) use_dma = 1;
        else {
            fprintf(stderr, "usage: atlas-x-paint -d dir [--dma]\n");
            return 2;
        }
    }
    if (!dir) return 2;
    setenv("XDG_RUNTIME_DIR", dir, 1);
    setenv("WAYLAND_DISPLAY", "wayland-0", 1);
    struct wl_display *dpy = wl_display_connect(NULL);
    if (!dpy) {
        perror("atlas-x-paint: connect");
        return 1;
    }
    struct globals g;
    memset(&g, 0, sizeof(g));
    struct wl_registry *reg = wl_display_get_registry(dpy);
    wl_registry_add_listener(reg, &reg_listener, &g);
    wl_display_roundtrip(dpy);
    if (!g.comp || !g.wm || !g.seat || (use_dma ? !g.dma : !g.shm)) {
        fprintf(stderr, "atlas-x-paint: missing global\n");
        return 1;
    }
    xdg_wm_base_add_listener(g.wm, &wm_listener, NULL);
    wl_seat_add_listener(g.seat, &seat_listener, NULL);
    wl_display_roundtrip(dpy);

    struct wl_surface *surf = wl_compositor_create_surface(g.comp);
    struct xdg_surface *xs = xdg_wm_base_get_xdg_surface(g.wm, surf);
    xdg_surface_add_listener(xs, &xdg_listener, NULL);
    struct xdg_toplevel *top = xdg_surface_get_toplevel(xs);
    xdg_toplevel_add_listener(top, &top_listener, NULL);
    wl_surface_commit(surf);
    while (!configured || cfg_w <= 0 || cfg_h <= 0) {
        if (wl_display_dispatch(dpy) < 0) return 1;
    }
    if (use_dma ? make_dma(g.dma) : make_shm(g.shm)) {
        fprintf(stderr, "atlas-x-paint: buffer failed\n");
        return 1;
    }
    fill(0xFF202020u);
    wl_surface_attach(surf, buffer, 0, 0);
    wl_surface_damage_buffer(surf, 0, 0, cfg_w, cfg_h);
    wl_surface_commit(surf);
    wl_display_roundtrip(dpy);
    while (!entered) {
        if (wl_display_dispatch(dpy) < 0) return 1;
    }
    touch(dir, "mapped");
    while (!keyed) {
        if (wl_display_dispatch(dpy) < 0) return 1;
    }
    /* Green then blue without a pull in between. Latest must win. */
    /* Sample green before overwriting it. Dma-buf has no private copy, so the
     * second colour goes into the other buffer, which was not held. */
    if (use_dma) {
        for (int i = 0; i < cfg_w * cfg_h; i++) pixels_b[i] = 0xFF00FF00u;
        wl_surface_attach(surf, buffer_b, 0, 0);
    } else {
        fill(0xFF00FF00u);
        wl_surface_attach(surf, buffer, 0, 0);
    }
    wl_surface_damage_buffer(surf, 0, 0, cfg_w, cfg_h);
    wl_surface_commit(surf);
    wl_display_roundtrip(dpy);
    if (use_dma) {
        for (int i = 0; i < cfg_w * cfg_h; i++) pixels[i] = 0xFF0000FFu;
        wl_surface_attach(surf, buffer, 0, 0);
    } else {
        fill(0xFF0000FFu);
        wl_surface_attach(surf, buffer, 0, 0);
    }
    wl_surface_damage_buffer(surf, 0, 0, cfg_w, cfg_h);
    wl_surface_commit(surf);
    wl_display_roundtrip(dpy);
    touch(dir, "ready");
    wl_display_disconnect(dpy);
    return 0;
}
