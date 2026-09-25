/* atlas-x — display server for Atlas Debian.
 *
 * X11 clients talk to Xwayland (the maintained X server). This process is the
 * Wayland compositor Xwayland sits on, and the only present path:
 *
 *   client  --X11-->  Xwayland  --wl_shm / dma-buf-->  atlas-x
 *   atlas-x --SCM_RIGHTS-->  present.sock  -->  Android viewer
 *   Android keys  --seat frame-->  input.sock  -->  wl_keyboard / wl_pointer
 *
 * Shm frames are copied once into a private memfd so the client can reuse its
 * pool. Dma-buf frames are not copied; the fd is the buffer.
 *
 * Default directory is /home/atlas/atlas-x, which the Android app opens as
 * /data/local/atlas-home/atlas/atlas-x (home bind).
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include "atlas_present.h"
#include "atlas_io.h"

#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <wayland-server-core.h>
#include <wayland-server-protocol.h>
#include <xkbcommon/xkbcommon.h>

#include "xdg-shell-server.h"
#include "linux-dmabuf-server.h"

#define ATLAS_X_VERSION "1.0.0"

struct ax_held {
    int fd;
    struct atlas_present_msg msg;
};

struct ax_parse {
    uint8_t buf[512];
    size_t have;
};

struct ax_in {
    struct ax_server *srv;
    int fd;
    struct wl_event_source *src;
    struct ax_parse parse;
};

struct ax_server {
    struct wl_display *dpy;
    char dir[256];
    int panel_w, panel_h;
    int present_lfd;
    int input_lfd;
    int seatd_fd;
    struct wl_event_source *seatd_src;
    struct ax_parse seatd_parse;
    int xdisp;
    pid_t xw_pid;
    struct wl_event_source *xwait;
    struct xkb_context *xkb_ctx;
    struct xkb_keymap *keymap;
    struct xkb_state *xkb;
    int keymap_fd;
    uint32_t keymap_size;
    struct ax_output *outs[ATLAS_OUT_COUNT];
    struct ax_seat *seats;
    struct ax_surface *focus;
    struct ax_viewer *viewers;
    struct ax_held latest[ATLAS_OUT_COUNT];
    int px, py;
    uint32_t buttons;
    int run_xwayland;
    /* Xwayland's cursor. Composited on top of the scene. Ignoring it left
     * a foreign mark under Plasma's panel. */
    struct ax_surface *cursor;
    int32_t cursor_hx, cursor_hy;
    /* Clean desk pixels, without the cursor. Pointer motion repaints from this. */
    void *scene;
    size_t scene_bytes;
    int32_t scene_w, scene_h, scene_stride;
    uint32_t scene_fmt;
    uint32_t scene_out;
    uint64_t cursor_paint_us;
};

struct ax_output {
    struct ax_server *srv;
    uint32_t index;
    int32_t x, y, w, h;
    const char *name;
};

struct ax_seat {
    struct ax_server *srv;
    struct wl_resource *seat;
    struct wl_resource *pointer;
    struct wl_resource *keyboard;
    struct ax_seat *next;
};

struct ax_surface {
    struct ax_server *srv;
    struct wl_resource *res;
    struct wl_resource *xdg;
    struct wl_resource *toplevel;
    struct wl_resource *held; /* dma-buf held until a different buffer is attached */
    struct wl_resource *current;
    struct wl_resource *pending;
    struct wl_listener buf_gone;
    int buf_listening;
    struct wl_resource *frame_cb;
    int acked;
    int mapped;
    int pending_attach;
    uint32_t output;
    int32_t scale;
};

struct ax_dma {
    int fd;
    int32_t w, h, stride;
    uint32_t offset;
    uint32_t format;
    uint64_t modifier;
    int y_invert;
};

struct ax_params {
    struct ax_server *srv;
    int used;
    int fd;
    uint32_t offset, stride, mod_hi, mod_lo;
};

struct ax_viewer {
    struct ax_server *srv;
    int fd;
    int waiting;
    uint16_t output;
    uint32_t min_gen;
    struct wl_event_source *src;
    struct ax_viewer *next;
};

static uint64_t now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000ull + (uint64_t)ts.tv_nsec / 1000ull;
}

static uint32_t now_ms(void) { return (uint32_t)(now_us() / 1000ull); }

static uint32_t shm_to_fourcc(uint32_t fmt) {
    if (fmt == WL_SHM_FORMAT_ARGB8888 || fmt == 0) return ATLAS_FMT_ARGB8888;
    if (fmt == WL_SHM_FORMAT_XRGB8888 || fmt == 1) return ATLAS_FMT_XRGB8888;
    return fmt;
}

static int seq_listen(const char *path) {
    if (atlas_ensure_parent(path) != 0) return -1;
    unlink(path);
    int s = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
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
    chmod(path, 0666);
    if (listen(s, 8) != 0) {
        close(s);
        unlink(path);
        return -1;
    }
    return s;
}

static int send_present(int fd, const struct atlas_present_msg *m, int pass) {
    struct iovec iov;
    iov.iov_base = (void *)m;
    iov.iov_len = sizeof(*m);
    char cbuf[CMSG_SPACE(sizeof(int))];
    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    if (pass >= 0) {
        memset(cbuf, 0, sizeof(cbuf));
        msg.msg_control = cbuf;
        msg.msg_controllen = sizeof(cbuf);
        struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
        c->cmsg_level = SOL_SOCKET;
        c->cmsg_type = SCM_RIGHTS;
        c->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(c), &pass, sizeof(pass));
    }
    for (;;) {
        ssize_t n = sendmsg(fd, &msg, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        return n == (ssize_t)sizeof(*m) ? 0 : -1;
    }
}

static void viewer_drop(struct ax_viewer *v) {
    struct ax_server *srv = v->srv;
    if (v->src) wl_event_source_remove(v->src);
    close(v->fd);
    struct ax_viewer **pp = &srv->viewers;
    while (*pp) {
        if (*pp == v) {
            *pp = v->next;
            break;
        }
        pp = &(*pp)->next;
    }
    free(v);
}

static int viewer_satisfy(struct ax_viewer *v) {
    struct ax_server *srv = v->srv;
    uint16_t out = v->output;
    if (out >= ATLAS_OUT_COUNT) out = ATLAS_OUT_PANEL;
    struct ax_held *h = &srv->latest[out];
    int have = h->fd >= 0 && (h->msg.flags & ATLAS_PRESENT_NONE) == 0;
    /* A client left over from the previous compositor asks for a generation
     * this server will never reach, and then waits forever. Hand it this frame. */
    int restarted = have && v->min_gen > h->msg.gen + 1;
    if (!restarted && (!have || (v->min_gen && h->msg.gen < v->min_gen))) {
        if (v->min_gen == 0 && !have) {
            struct atlas_present_msg none;
            memset(&none, 0, sizeof(none));
            none.magic = ATLAS_PRESENT_MAGIC;
            none.ver = ATLAS_PRESENT_VER;
            none.flags = ATLAS_PRESENT_NONE;
            none.output = out;
            v->waiting = 0;
            if (send_present(v->fd, &none, -1) != 0) return -1;
            return 0;
        }
        v->waiting = 1;
        return 0;
    }
    int dupfd = dup(h->fd);
    if (dupfd < 0) return -1;
    v->waiting = 0;
    if (send_present(v->fd, &h->msg, dupfd) != 0) {
        close(dupfd);
        return -1;
    }
    close(dupfd);
    return 0;
}

static void present_note_waiters(struct ax_server *srv, uint32_t out) {
    struct ax_viewer *v = srv->viewers;
    while (v) {
        struct ax_viewer *next = v->next;
        if (v->waiting && v->output == out) {
            if (viewer_satisfy(v) != 0) viewer_drop(v);
        }
        v = next;
    }
}

static int on_viewer(int fd, uint32_t mask, void *data) {
    struct ax_viewer *v = data;
    if (mask & (WL_EVENT_HANGUP | WL_EVENT_ERROR)) {
        viewer_drop(v);
        return 0;
    }
    struct atlas_present_req req;
    ssize_t n = recv(fd, &req, sizeof(req), 0);
    if (n == 0) {
        viewer_drop(v);
        return 0;
    }
    if (n < 0) {
        if (errno == EINTR || errno == EAGAIN) return 0;
        viewer_drop(v);
        return 0;
    }
    if (n != (ssize_t)sizeof(req) || req.magic != ATLAS_PRESENT_MAGIC_REQ ||
        req.ver != ATLAS_PRESENT_VER) {
        viewer_drop(v);
        return 0;
    }
    v->output = req.output >= ATLAS_OUT_COUNT ? ATLAS_OUT_PANEL : req.output;
    v->min_gen = req.min_gen;
    if (viewer_satisfy(v) != 0) viewer_drop(v);
    return 0;
}

static int on_present_listen(int fd, uint32_t mask, void *data) {
    (void)mask;
    struct ax_server *srv = data;
    int cfd = accept4(fd, NULL, NULL, SOCK_CLOEXEC);
    if (cfd < 0) return 0;
    struct ax_viewer *v = calloc(1, sizeof(*v));
    if (!v) {
        close(cfd);
        return 0;
    }
    v->srv = srv;
    v->fd = cfd;
    v->output = ATLAS_OUT_PANEL;
    v->src = wl_event_loop_add_fd(wl_display_get_event_loop(srv->dpy), cfd,
                                  WL_EVENT_READABLE, on_viewer, v);
    if (!v->src) {
        close(cfd);
        free(v);
        return 0;
    }
    v->next = srv->viewers;
    srv->viewers = v;
    return 0;
}

static void hold_replace(struct ax_server *srv, uint32_t out, int fd,
                         const struct atlas_present_msg *msg) {
    struct ax_held *h = &srv->latest[out];
    if (h->fd >= 0) close(h->fd);
    h->fd = fd;
    h->msg = *msg;
    if (msg->gen == 1) {
        fprintf(stderr, "atlas-x: present out=%u %ux%u %s\n", out, msg->width,
                msg->height, (msg->flags & ATLAS_PRESENT_DMABUF) ? "dmabuf" : "memfd");
    }
    present_note_waiters(srv, out);
}

static void blit_cursor(struct ax_server *srv, void *dst, int32_t dw, int32_t dh,
                        int32_t dstride);

static int publish_memfd(struct ax_server *srv, struct ax_surface *s, void *src,
                         int32_t w, int32_t h, int32_t stride, uint32_t fourcc) {
    /* The cursor is drawn on top of the desk. Its own buffer is not a frame. */
    if (s == srv->cursor) return 0;
    if (w <= 0 || h <= 0 || stride < w * 4) return -1;
    int fd = memfd_create("atlas-present", MFD_CLOEXEC);
    if (fd < 0) return -1;
    size_t nbytes = (size_t)stride * (size_t)h;
    if (ftruncate(fd, (off_t)nbytes) != 0) {
        close(fd);
        return -1;
    }
    void *dst = mmap(NULL, nbytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (dst == MAP_FAILED) {
        close(fd);
        return -1;
    }
    memcpy(dst, src, nbytes);
    if (srv->scene_bytes != nbytes) {
        free(srv->scene);
        srv->scene = malloc(nbytes);
        srv->scene_bytes = srv->scene ? nbytes : 0;
    }
    if (srv->scene) {
        memcpy(srv->scene, src, nbytes);
        srv->scene_w = w;
        srv->scene_h = h;
        srv->scene_stride = stride;
        srv->scene_fmt = fourcc;
        srv->scene_out = s->output;
    }
    blit_cursor(srv, dst, w, h, stride);
    munmap(dst, nbytes);

    struct atlas_present_msg m;
    memset(&m, 0, sizeof(m));
    m.magic = ATLAS_PRESENT_MAGIC;
    m.ver = ATLAS_PRESENT_VER;
    m.flags = ATLAS_PRESENT_MEMFD;
    m.output = s->output;
    m.width = (uint32_t)w;
    m.height = (uint32_t)h;
    m.fourcc = fourcc;
    m.stride = (uint32_t)stride;
    m.gen = srv->latest[s->output].msg.gen + 1;
    m.pts_us = now_us();
    hold_replace(srv, s->output, fd, &m);
    return 0;
}

/* Plasma's cursor, above the panel. Hotspot is the click point. */
static void blit_cursor(struct ax_server *srv, void *dst, int32_t dw, int32_t dh,
                        int32_t dstride) {
    struct ax_surface *c;
    struct wl_shm_buffer *shm;
    int32_t sw, sh, sstride, ox, oy, y, x;
    if (!srv || !dst || !srv->cursor || !srv->cursor->current) return;
    c = srv->cursor;
    shm = wl_shm_buffer_get(c->current);
    if (!shm) return;
    sw = wl_shm_buffer_get_width(shm);
    sh = wl_shm_buffer_get_height(shm);
    sstride = wl_shm_buffer_get_stride(shm);
    if (sw <= 0 || sh <= 0 || sstride < sw * 4) return;
    {
        static int announced;
        if (!announced) {
            fprintf(stderr, "cursor sprite %dx%d at %d,%d\n", sw, sh, srv->px, srv->py);
            fflush(stderr);
            announced = 1;
        }
    }
    wl_shm_buffer_begin_access(shm);
    ox = srv->px - srv->cursor_hx;
    oy = srv->py - srv->cursor_hy;
    for (y = 0; y < sh; y++) {
        int32_t dy = oy + y;
        uint32_t *srow;
        uint8_t *drow;
        if (dy < 0 || dy >= dh) continue;
        srow = (uint32_t *)((uint8_t *)wl_shm_buffer_get_data(shm) + (size_t)y * (size_t)sstride);
        drow = (uint8_t *)dst + (size_t)dy * (size_t)dstride;
        for (x = 0; x < sw; x++) {
            int32_t dx = ox + x;
            uint32_t sp, dp, sa, inv, sr, sg, sb, dr, dg, db;
            if (dx < 0 || dx >= dw) continue;
            sp = srow[x];
            sa = sp >> 24;
            if (sa == 0) continue;
            if (sa == 255) {
                ((uint32_t *)drow)[dx] = sp;
                continue;
            }
            dp = ((uint32_t *)drow)[dx];
            inv = 255 - sa;
            sr = (sp >> 16) & 255;
            sg = (sp >> 8) & 255;
            sb = sp & 255;
            dr = (dp >> 16) & 255;
            dg = (dp >> 8) & 255;
            db = dp & 255;
            dr = sr + dr * inv / 255;
            dg = sg + dg * inv / 255;
            db = sb + db * inv / 255;
            ((uint32_t *)drow)[dx] = (255u << 24) | (dr << 16) | (dg << 8) | db;
        }
    }
    wl_shm_buffer_end_access(shm);
}

/* Paint the cursor onto the clean scene and publish that frame.
 * Motion is throttled. A new cursor image is not. */
static void repaint_cursor(struct ax_server *srv, int force) {
    uint64_t t;
    if (!srv || !srv->scene || !srv->focus) return;
    if (srv->focus == srv->cursor) return;
    if (srv->focus->output != srv->scene_out) return;
    t = now_us();
    if (!force && srv->cursor_paint_us && t - srv->cursor_paint_us < 30000)
        return;
    srv->cursor_paint_us = t;
    publish_memfd(srv, srv->focus, srv->scene, srv->scene_w, srv->scene_h,
                  srv->scene_stride, srv->scene_fmt);
}

static int publish_dma(struct ax_server *srv, struct ax_surface *s, struct ax_dma *d) {
    int fd = dup(d->fd);
    if (fd < 0) return -1;
    struct atlas_present_msg m;
    memset(&m, 0, sizeof(m));
    m.magic = ATLAS_PRESENT_MAGIC;
    m.ver = ATLAS_PRESENT_VER;
    m.flags = ATLAS_PRESENT_DMABUF;
    if (d->y_invert) m.flags |= ATLAS_PRESENT_Y_INVERT;
    m.output = s->output;
    m.width = (uint32_t)d->w;
    m.height = (uint32_t)d->h;
    m.fourcc = d->format;
    m.stride = (uint32_t)d->stride;
    m.offset = d->offset;
    m.modifier_lo = (uint32_t)d->modifier;
    m.modifier_hi = (uint32_t)(d->modifier >> 32);
    m.gen = srv->latest[s->output].msg.gen + 1;
    m.pts_us = now_us();
    hold_replace(srv, s->output, fd, &m);
    return 0;
}

static void focus_surface(struct ax_server *srv, struct ax_surface *s) {
    srv->focus = s;
    if (srv->px <= 0 && srv->py <= 0) {
        srv->px = srv->outs[s->output]->w / 2;
        srv->py = srv->outs[s->output]->h / 2;
    }
    struct wl_client *cl = wl_resource_get_client(s->res);
    struct wl_array keys;
    wl_array_init(&keys);
    for (struct ax_seat *st = srv->seats; st; st = st->next) {
        if (wl_resource_get_client(st->seat) != cl) continue;
        uint32_t serial = wl_display_next_serial(srv->dpy);
        if (st->keyboard)
            wl_keyboard_send_enter(st->keyboard, serial, s->res, &keys);
        if (st->pointer)
            wl_pointer_send_enter(st->pointer, serial, s->res, wl_fixed_from_int(srv->px),
                                  wl_fixed_from_int(srv->py));
    }
    wl_array_release(&keys);
}

static void deliver_key(struct ax_server *srv, uint32_t code, int down) {
    if (!srv->focus || !srv->xkb) return;
    struct wl_client *cl = wl_resource_get_client(srv->focus->res);
    xkb_state_update_key(srv->xkb, code + 8, down ? XKB_KEY_DOWN : XKB_KEY_UP);
    uint32_t dep = xkb_state_serialize_mods(srv->xkb, XKB_STATE_MODS_DEPRESSED);
    uint32_t lat = xkb_state_serialize_mods(srv->xkb, XKB_STATE_MODS_LATCHED);
    uint32_t lck = xkb_state_serialize_mods(srv->xkb, XKB_STATE_MODS_LOCKED);
    uint32_t grp = xkb_state_serialize_layout(srv->xkb, XKB_STATE_LAYOUT_EFFECTIVE);
    uint32_t ms = now_ms();
    for (struct ax_seat *st = srv->seats; st; st = st->next) {
        if (!st->keyboard) continue;
        if (wl_resource_get_client(st->seat) != cl) continue;
        uint32_t serial = wl_display_next_serial(srv->dpy);
        wl_keyboard_send_key(st->keyboard, serial, ms, code,
                             down ? WL_KEYBOARD_KEY_STATE_PRESSED
                                  : WL_KEYBOARD_KEY_STATE_RELEASED);
        wl_keyboard_send_modifiers(st->keyboard, serial, dep, lat, lck, grp);
    }
}

static void deliver_ptr(struct ax_server *srv, int32_t dx, int32_t dy, uint32_t buttons,
                        int32_t wheel, int absolute) {
    if (!srv->focus) return;
    struct ax_output *o = srv->outs[srv->focus->output];
    if (absolute) {
        srv->px = dx;
        srv->py = dy;
    } else {
        srv->px += dx;
        srv->py += dy;
    }
    if (srv->px < 0) srv->px = 0;
    if (srv->py < 0) srv->py = 0;
    if (srv->px >= o->w) srv->px = o->w - 1;
    if (srv->py >= o->h) srv->py = o->h - 1;
    struct wl_client *cl = wl_resource_get_client(srv->focus->res);
    uint32_t ms = now_ms();
    for (struct ax_seat *st = srv->seats; st; st = st->next) {
        if (!st->pointer) continue;
        if (wl_resource_get_client(st->seat) != cl) continue;
        wl_pointer_send_motion(st->pointer, ms, wl_fixed_from_int(srv->px),
                               wl_fixed_from_int(srv->py));
        uint32_t changed = srv->buttons ^ buttons;
        for (uint32_t b = 0; b < 3; b++) {
            uint32_t bit = 1u << b;
            if ((changed & bit) == 0) continue;
            uint32_t serial = wl_display_next_serial(srv->dpy);
            wl_pointer_send_button(st->pointer, serial, ms, BTN_LEFT + b,
                                   (buttons & bit) ? WL_POINTER_BUTTON_STATE_PRESSED
                                                   : WL_POINTER_BUTTON_STATE_RELEASED);
        }
        if (wheel) {
            wl_pointer_send_axis(st->pointer, ms, WL_POINTER_AXIS_VERTICAL_SCROLL,
                                 wl_fixed_from_int(-wheel * 15));
        }
        if (wl_resource_get_version(st->pointer) >= WL_POINTER_FRAME_SINCE_VERSION)
            wl_pointer_send_frame(st->pointer);
    }
    repaint_cursor(srv, wheel != 0 || srv->buttons != buttons);
    srv->buttons = buttons;
}

/* Non-blocking. Returns 0 if the peer is still open, -1 on EOF or a bad frame.
 * A hangup can arrive in the same epoll event as the last bytes; those bytes
 * are applied before the fd is dropped. */
static int parse_feed(struct ax_server *srv, struct ax_parse *p, int fd) {
    for (;;) {
        if (p->have < sizeof(struct atlas_seat_hdr)) {
            ssize_t r = read(fd, p->buf + p->have, sizeof(p->buf) - p->have);
            if (r < 0 && errno == EINTR) continue;
            if (r < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return 0;
            if (r <= 0) return -1;
            p->have += (size_t)r;
            continue;
        }
        struct atlas_seat_hdr h;
        memcpy(&h, p->buf, sizeof(h));
        if (h.ver != ATLAS_SEAT_VER || h.nbytes > 256) return -1;
        size_t need = sizeof(h) + h.nbytes;
        if (need > sizeof(p->buf)) return -1;
        if (p->have < need) {
            ssize_t r = read(fd, p->buf + p->have, sizeof(p->buf) - p->have);
            if (r < 0 && errno == EINTR) continue;
            if (r < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return 0;
            if (r <= 0) return -1;
            p->have += (size_t)r;
            continue;
        }
        const uint8_t *pay = p->buf + sizeof(h);
        if (h.type == ATLAS_T_KEY && h.magic == ATLAS_SEAT_MAGIC_INP)
            deliver_key(srv, h.w, h.h ? 1 : 0);
        else if (h.type == ATLAS_T_PTR && h.magic == ATLAS_SEAT_MAGIC_INP &&
                 h.nbytes >= sizeof(struct atlas_seat_ptr)) {
            struct atlas_seat_ptr ptr;
            memcpy(&ptr, pay, sizeof(ptr));
            deliver_ptr(srv, ptr.dx, ptr.dy, ptr.buttons, ptr.wheel,
                        (h.fmt & ATLAS_PTR_ABSOLUTE) != 0);
        }
        memmove(p->buf, p->buf + need, p->have - need);
        p->have -= need;
    }
}

static void in_drop(struct ax_in *in) {
    if (in->src) wl_event_source_remove(in->src);
    close(in->fd);
    free(in);
}

static int on_input_client(int fd, uint32_t mask, void *data) {
    (void)fd;
    (void)mask;
    struct ax_in *in = data;
    if (parse_feed(in->srv, &in->parse, in->fd) != 0) in_drop(in);
    return 0;
}

static int on_input_listen(int fd, uint32_t mask, void *data) {
    (void)mask;
    struct ax_server *srv = data;
    int cfd = accept4(fd, NULL, NULL, SOCK_CLOEXEC);
    if (cfd < 0) return 0;
    struct ax_in *in = calloc(1, sizeof(*in));
    if (!in) {
        close(cfd);
        return 0;
    }
    in->srv = srv;
    in->fd = cfd;
    fcntl(cfd, F_SETFL, O_NONBLOCK);
    in->src = wl_event_loop_add_fd(wl_display_get_event_loop(srv->dpy), cfd, WL_EVENT_READABLE,
                                   on_input_client, in);
    if (!in->src) {
        close(cfd);
        free(in);
    }
    return 0;
}

static int on_seatd(int fd, uint32_t mask, void *data) {
    (void)fd;
    (void)mask;
    struct ax_server *srv = data;
    if (parse_feed(srv, &srv->seatd_parse, srv->seatd_fd) != 0) {
        if (srv->seatd_src) wl_event_source_remove(srv->seatd_src);
        srv->seatd_src = NULL;
        close(srv->seatd_fd);
        srv->seatd_fd = -1;
    }
    return 0;
}

static void send_toplevel_configure(struct ax_surface *s) {
    if (!s->toplevel) return;
    struct ax_output *o = s->srv->outs[s->output];
    struct wl_array states;
    wl_array_init(&states);
    uint32_t *st = wl_array_add(&states, sizeof(uint32_t));
    if (st) *st = XDG_TOPLEVEL_STATE_FULLSCREEN;
    uint32_t ver = wl_resource_get_version(s->toplevel);
    if (ver >= XDG_TOPLEVEL_CONFIGURE_BOUNDS_SINCE_VERSION)
        xdg_toplevel_send_configure_bounds(s->toplevel, o->w, o->h);
    xdg_toplevel_send_configure(s->toplevel, o->w, o->h, &states);
    wl_array_release(&states);
    if (s->xdg)
        xdg_surface_send_configure(s->xdg, wl_display_next_serial(s->srv->dpy));
}

/* release_new: this commit attached the buffer. Shm is copied, then released
 * so the client may write the next frame into it. A later commit with no new
 * attach samples that write and must not release a second time. */
static void present_buffer(struct ax_surface *s, struct wl_resource *buf, int release_new) {
    if (!buf) return;
    struct wl_shm_buffer *shm = wl_shm_buffer_get(buf);
    if (shm) {
        int32_t w = wl_shm_buffer_get_width(shm);
        int32_t h = wl_shm_buffer_get_height(shm);
        int32_t stride = wl_shm_buffer_get_stride(shm);
        uint32_t fmt = shm_to_fourcc(wl_shm_buffer_get_format(shm));
        wl_shm_buffer_begin_access(shm);
        void *data = wl_shm_buffer_get_data(shm);
        int rc = data ? publish_memfd(s->srv, s, data, w, h, stride, fmt) : -1;
        wl_shm_buffer_end_access(shm);
        if (release_new) wl_buffer_send_release(buf);
        if (rc != 0) fprintf(stderr, "atlas-x: shm present failed\n");
        return;
    }
    struct ax_dma *d = wl_resource_get_user_data(buf);
    if (!d || d->fd < 0) {
        if (release_new) wl_buffer_send_release(buf);
        return;
    }
    if (release_new && s->held && s->held != buf) wl_buffer_send_release(s->held);
    if (release_new) s->held = buf;
    if (publish_dma(s->srv, s, d) != 0) fprintf(stderr, "atlas-x: dmabuf present failed\n");
}

static void on_buf_gone(struct wl_listener *l, void *data) {
    (void)data;
    struct ax_surface *s = wl_container_of(l, s, buf_gone);
    s->current = NULL;
    if (s->held) s->held = NULL;
    s->buf_listening = 0;
}

static void surface_commit(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    struct ax_surface *s = wl_resource_get_user_data(resource);
    if (s->frame_cb) {
        wl_callback_send_done(s->frame_cb, now_ms());
        wl_resource_destroy(s->frame_cb);
        s->frame_cb = NULL;
    }
    int attached = s->pending_attach;
    if (attached) {
        if (s->pending != s->current) {
            if (s->buf_listening) {
                wl_list_remove(&s->buf_gone.link);
                s->buf_listening = 0;
            }
            if (s->pending) {
                s->buf_gone.notify = on_buf_gone;
                wl_resource_add_destroy_listener(s->pending, &s->buf_gone);
                s->buf_listening = 1;
            }
        }
        s->current = s->pending;
        s->pending = NULL;
        s->pending_attach = 0;
    }
    if (s == s->srv->cursor) {
        repaint_cursor(s->srv, 1);
        return;
    }
    if (s->current && s->acked) {
        present_buffer(s, s->current, attached);
        if (!s->mapped) {
            s->mapped = 1;
            /* Cursor and popup surfaces must not take the keyboard.
             * Xwayland drops keys unless enter is on its toplevel. */
            if (s->toplevel)
                focus_surface(s->srv, s);
        }
    }
}

static void surface_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void surface_attach(struct wl_client *client, struct wl_resource *resource,
                           struct wl_resource *buffer, int32_t x, int32_t y) {
    (void)client;
    (void)x;
    (void)y;
    struct ax_surface *s = wl_resource_get_user_data(resource);
    s->pending = buffer;
    s->pending_attach = 1;
}

static void surface_damage(struct wl_client *client, struct wl_resource *resource, int32_t x,
                           int32_t y, int32_t w, int32_t h) {
    (void)client;
    (void)resource;
    (void)x;
    (void)y;
    (void)w;
    (void)h;
}

static void surface_frame(struct wl_client *client, struct wl_resource *resource,
                          uint32_t callback) {
    struct ax_surface *s = wl_resource_get_user_data(resource);
    if (s->frame_cb) wl_resource_destroy(s->frame_cb);
    s->frame_cb = wl_resource_create(client, &wl_callback_interface, 1, callback);
}

static void surface_set_region(struct wl_client *client, struct wl_resource *resource,
                               struct wl_resource *region) {
    (void)client;
    (void)resource;
    (void)region;
}

static void surface_set_buffer_transform(struct wl_client *client, struct wl_resource *resource,
                                         int32_t transform) {
    (void)client;
    (void)resource;
    (void)transform;
}

static void surface_set_buffer_scale(struct wl_client *client, struct wl_resource *resource,
                                     int32_t scale) {
    (void)client;
    struct ax_surface *s = wl_resource_get_user_data(resource);
    if (scale < 1) {
        wl_resource_post_error(resource, WL_SURFACE_ERROR_INVALID_SCALE, "scale");
        return;
    }
    s->scale = scale;
}

static void surface_damage_buffer(struct wl_client *client, struct wl_resource *resource,
                                  int32_t x, int32_t y, int32_t w, int32_t h) {
    surface_damage(client, resource, x, y, w, h);
}

static void surface_offset(struct wl_client *client, struct wl_resource *resource, int32_t x,
                           int32_t y) {
    (void)client;
    (void)resource;
    (void)x;
    (void)y;
}

static void surface_get_release(struct wl_client *client, struct wl_resource *resource,
                                uint32_t callback) {
    (void)resource;
    /* Buffer storage is released inside commit (shm copy, or one-frame dma hold). */
    struct wl_resource *cb = wl_resource_create(client, &wl_callback_interface, 1, callback);
    if (!cb) return;
    wl_callback_send_done(cb, 0);
    wl_resource_destroy(cb);
}

static const struct wl_surface_interface surface_impl = {
    .destroy = surface_destroy,
    .attach = surface_attach,
    .damage = surface_damage,
    .frame = surface_frame,
    .set_opaque_region = surface_set_region,
    .set_input_region = surface_set_region,
    .commit = surface_commit,
    .set_buffer_transform = surface_set_buffer_transform,
    .set_buffer_scale = surface_set_buffer_scale,
    .damage_buffer = surface_damage_buffer,
    .offset = surface_offset,
#ifdef WL_SURFACE_GET_RELEASE_SINCE_VERSION
    .get_release = surface_get_release,
#endif
};

static void surface_gone(struct wl_resource *resource) {
    struct ax_surface *s = wl_resource_get_user_data(resource);
    if (!s) return;
    if (s->srv->focus == s) s->srv->focus = NULL;
    if (s->srv->cursor == s) s->srv->cursor = NULL;
    if (s->buf_listening) wl_list_remove(&s->buf_gone.link);
    if (s->held) wl_buffer_send_release(s->held);
    if (s->frame_cb) wl_resource_destroy(s->frame_cb);
    free(s);
}

static void region_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void region_add(struct wl_client *client, struct wl_resource *resource, int32_t x, int32_t y,
                       int32_t w, int32_t h) {
    (void)client;
    (void)resource;
    (void)x;
    (void)y;
    (void)w;
    (void)h;
}

static void region_subtract(struct wl_client *client, struct wl_resource *resource, int32_t x,
                            int32_t y, int32_t w, int32_t h) {
    region_add(client, resource, x, y, w, h);
}

static const struct wl_region_interface region_impl = {
    .destroy = region_destroy,
    .add = region_add,
    .subtract = region_subtract,
};

static void compositor_create_surface(struct wl_client *client, struct wl_resource *resource,
                                      uint32_t id) {
    struct ax_server *srv = wl_resource_get_user_data(resource);
    struct ax_surface *s = calloc(1, sizeof(*s));
    if (!s) {
        wl_client_post_no_memory(client);
        return;
    }
    s->srv = srv;
    s->scale = 1;
    s->output = ATLAS_OUT_PANEL;
    s->res = wl_resource_create(client, &wl_surface_interface,
                                wl_resource_get_version(resource), id);
    if (!s->res) {
        free(s);
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(s->res, &surface_impl, s, surface_gone);
}

static void compositor_create_region(struct wl_client *client, struct wl_resource *resource,
                                     uint32_t id) {
    (void)resource;
    struct wl_resource *r = wl_resource_create(client, &wl_region_interface, 1, id);
    if (!r) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(r, &region_impl, NULL, NULL);
}

static void compositor_release(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static const struct wl_compositor_interface compositor_impl = {
    .create_surface = compositor_create_surface,
    .create_region = compositor_create_region,
#ifdef WL_COMPOSITOR_RELEASE_SINCE_VERSION
    .release = compositor_release,
#endif
};

static void bind_compositor(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    /* Wayland 1.23 (Debian trixie) compositor stops at version 5. */
    uint32_t ver = version < 5 ? version : 5;
    struct wl_resource *r = wl_resource_create(client, &wl_compositor_interface, ver, id);
    if (!r) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(r, &compositor_impl, data, NULL);
}

static void seat_gone(struct wl_resource *resource) {
    struct ax_seat *st = wl_resource_get_user_data(resource);
    if (!st) return;
    struct ax_seat **pp = &st->srv->seats;
    while (*pp) {
        if (*pp == st) {
            *pp = st->next;
            break;
        }
        pp = &(*pp)->next;
    }
    free(st);
}

static void pointer_set_cursor(struct wl_client *client, struct wl_resource *resource,
                               uint32_t serial, struct wl_resource *surface, int32_t hx,
                               int32_t hy) {
    struct ax_seat *st = wl_resource_get_user_data(resource);
    (void)client;
    (void)serial;
    if (!st || !st->srv) return;
    st->srv->cursor = surface ? wl_resource_get_user_data(surface) : NULL;
    st->srv->cursor_hx = hx;
    st->srv->cursor_hy = hy;
}

static void pointer_release(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static const struct wl_pointer_interface pointer_impl = {
    .set_cursor = pointer_set_cursor,
    .release = pointer_release,
};

static void keyboard_release(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static const struct wl_keyboard_interface keyboard_impl = {
    .release = keyboard_release,
};

static void pointer_gone(struct wl_resource *resource) {
    struct ax_seat *st = wl_resource_get_user_data(resource);
    if (st) st->pointer = NULL;
}

static void keyboard_gone(struct wl_resource *resource) {
    struct ax_seat *st = wl_resource_get_user_data(resource);
    if (st) st->keyboard = NULL;
}

static void seat_get_pointer(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct ax_seat *st = wl_resource_get_user_data(resource);
    struct wl_resource *p =
        wl_resource_create(client, &wl_pointer_interface, wl_resource_get_version(resource), id);
    if (!p) {
        wl_client_post_no_memory(client);
        return;
    }
    st->pointer = p;
    wl_resource_set_implementation(p, &pointer_impl, st, pointer_gone);
}

static void seat_get_keyboard(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct ax_seat *st = wl_resource_get_user_data(resource);
    uint32_t ver = wl_resource_get_version(resource);
    struct wl_resource *k = wl_resource_create(client, &wl_keyboard_interface, ver, id);
    if (!k) {
        wl_client_post_no_memory(client);
        return;
    }
    st->keyboard = k;
    wl_resource_set_implementation(k, &keyboard_impl, st, keyboard_gone);
    int fd = dup(st->srv->keymap_fd);
    if (fd >= 0) {
        wl_keyboard_send_keymap(k, WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1, fd, st->srv->keymap_size);
        close(fd);
    }
    if (ver >= WL_KEYBOARD_REPEAT_INFO_SINCE_VERSION) wl_keyboard_send_repeat_info(k, 40, 400);
    if (st->srv->focus && wl_resource_get_client(st->srv->focus->res) == client) {
        struct wl_array keys;
        wl_array_init(&keys);
        wl_keyboard_send_enter(k, wl_display_next_serial(st->srv->dpy), st->srv->focus->res, &keys);
        wl_array_release(&keys);
    }
}

static void seat_get_touch(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    wl_resource_post_error(resource, WL_SEAT_ERROR_MISSING_CAPABILITY, "no touch");
    (void)client;
    (void)id;
}

static void seat_release(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static const struct wl_seat_interface seat_impl = {
    .get_pointer = seat_get_pointer,
    .get_keyboard = seat_get_keyboard,
    .get_touch = seat_get_touch,
    .release = seat_release,
};

static void bind_seat(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    struct ax_server *srv = data;
    uint32_t ver = version < 5 ? version : 5;
    struct ax_seat *st = calloc(1, sizeof(*st));
    if (!st) {
        wl_client_post_no_memory(client);
        return;
    }
    st->srv = srv;
    st->seat = wl_resource_create(client, &wl_seat_interface, ver, id);
    if (!st->seat) {
        free(st);
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(st->seat, &seat_impl, st, seat_gone);
    st->next = srv->seats;
    srv->seats = st;
    wl_seat_send_capabilities(st->seat, WL_SEAT_CAPABILITY_POINTER | WL_SEAT_CAPABILITY_KEYBOARD);
    if (ver >= WL_SEAT_NAME_SINCE_VERSION) wl_seat_send_name(st->seat, "atlas");
}

static void bind_output(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    struct ax_output *o = data;
    uint32_t ver = version < 3 ? version : 3;
    struct wl_resource *r = wl_resource_create(client, &wl_output_interface, ver, id);
    if (!r) {
        wl_client_post_no_memory(client);
        return;
    }
    static const struct wl_output_interface output_impl = {.release = compositor_release};
    wl_resource_set_implementation(r, &output_impl, o, NULL);
    int32_t pmw = o->w * 254 / 1600;
    int32_t pmh = o->h * 254 / 1600;
    if (pmw < 1) pmw = 1;
    if (pmh < 1) pmh = 1;
    wl_output_send_geometry(r, o->x, o->y, pmw, pmh, WL_OUTPUT_SUBPIXEL_UNKNOWN, "atlas", o->name,
                            WL_OUTPUT_TRANSFORM_NORMAL);
    wl_output_send_mode(r, WL_OUTPUT_MODE_CURRENT | WL_OUTPUT_MODE_PREFERRED, o->w, o->h, 60000);
    if (ver >= WL_OUTPUT_SCALE_SINCE_VERSION) wl_output_send_scale(r, 1);
    if (ver >= WL_OUTPUT_DONE_SINCE_VERSION) wl_output_send_done(r);
}

static void xdg_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void xdg_pong(struct wl_client *client, struct wl_resource *resource, uint32_t serial) {
    (void)client;
    (void)resource;
    (void)serial;
}

static void positioner_set_size(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h) {
    (void)c;
    (void)r;
    (void)w;
    (void)h;
}
static void positioner_set_anchor_rect(struct wl_client *c, struct wl_resource *r, int32_t x,
                                       int32_t y, int32_t w, int32_t h) {
    (void)c;
    (void)r;
    (void)x;
    (void)y;
    (void)w;
    (void)h;
}
static void positioner_set_anchor(struct wl_client *c, struct wl_resource *r, uint32_t a) {
    (void)c;
    (void)r;
    (void)a;
}
static void positioner_set_gravity(struct wl_client *c, struct wl_resource *r, uint32_t g) {
    (void)c;
    (void)r;
    (void)g;
}
static void positioner_set_constraint(struct wl_client *c, struct wl_resource *r, uint32_t v) {
    (void)c;
    (void)r;
    (void)v;
}
static void positioner_set_offset(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y) {
    (void)c;
    (void)r;
    (void)x;
    (void)y;
}
static void positioner_set_reactive(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    (void)r;
}
static void positioner_set_parent_size(struct wl_client *c, struct wl_resource *r, int32_t w,
                                       int32_t h) {
    (void)c;
    (void)r;
    (void)w;
    (void)h;
}
static void positioner_set_parent_configure(struct wl_client *c, struct wl_resource *r,
                                            uint32_t serial) {
    (void)c;
    (void)r;
    (void)serial;
}

static const struct xdg_positioner_interface positioner_impl = {
    .destroy = xdg_destroy,
    .set_size = positioner_set_size,
    .set_anchor_rect = positioner_set_anchor_rect,
    .set_anchor = positioner_set_anchor,
    .set_gravity = positioner_set_gravity,
    .set_constraint_adjustment = positioner_set_constraint,
    .set_offset = positioner_set_offset,
    .set_reactive = positioner_set_reactive,
    .set_parent_size = positioner_set_parent_size,
    .set_parent_configure = positioner_set_parent_configure,
};

static void toplevel_set_parent(struct wl_client *c, struct wl_resource *r, struct wl_resource *p) {
    (void)c;
    (void)r;
    (void)p;
}
static void toplevel_set_title(struct wl_client *c, struct wl_resource *r, const char *title) {
    (void)c;
    (void)r;
    (void)title;
}
static void toplevel_set_app_id(struct wl_client *c, struct wl_resource *r, const char *id) {
    (void)c;
    (void)r;
    (void)id;
}
static void toplevel_show_window_menu(struct wl_client *c, struct wl_resource *r,
                                      struct wl_resource *seat, uint32_t serial, int32_t x,
                                      int32_t y) {
    (void)c;
    (void)r;
    (void)seat;
    (void)serial;
    (void)x;
    (void)y;
}
static void toplevel_move(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat,
                          uint32_t serial) {
    (void)c;
    (void)r;
    (void)seat;
    (void)serial;
}
static void toplevel_resize(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat,
                            uint32_t serial, uint32_t edges) {
    (void)c;
    (void)r;
    (void)seat;
    (void)serial;
    (void)edges;
}
static void toplevel_set_max_size(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h) {
    (void)c;
    (void)r;
    (void)w;
    (void)h;
}
static void toplevel_set_min_size(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h) {
    toplevel_set_max_size(c, r, w, h);
}
static void toplevel_set_maximized(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    (void)r;
}
static void toplevel_unset_maximized(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    (void)r;
}
static void toplevel_set_fullscreen(struct wl_client *c, struct wl_resource *r,
                                    struct wl_resource *output) {
    (void)c;
    struct ax_surface *s = wl_resource_get_user_data(r);
    if (output) {
        struct ax_output *o = wl_resource_get_user_data(output);
        if (o) s->output = o->index;
    }
    send_toplevel_configure(s);
}
static void toplevel_unset_fullscreen(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    send_toplevel_configure(wl_resource_get_user_data(r));
}
static void toplevel_set_minimized(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    (void)r;
}

static const struct xdg_toplevel_interface toplevel_impl = {
    .destroy = xdg_destroy,
    .set_parent = toplevel_set_parent,
    .set_title = toplevel_set_title,
    .set_app_id = toplevel_set_app_id,
    .show_window_menu = toplevel_show_window_menu,
    .move = toplevel_move,
    .resize = toplevel_resize,
    .set_max_size = toplevel_set_max_size,
    .set_min_size = toplevel_set_min_size,
    .set_maximized = toplevel_set_maximized,
    .unset_maximized = toplevel_unset_maximized,
    .set_fullscreen = toplevel_set_fullscreen,
    .unset_fullscreen = toplevel_unset_fullscreen,
    .set_minimized = toplevel_set_minimized,
};

static void toplevel_gone(struct wl_resource *resource) {
    struct ax_surface *s = wl_resource_get_user_data(resource);
    if (s) s->toplevel = NULL;
}

static void popup_grab(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat,
                       uint32_t serial) {
    (void)c;
    (void)r;
    (void)seat;
    (void)serial;
}

static void popup_reposition(struct wl_client *c, struct wl_resource *r, struct wl_resource *pos,
                             uint32_t token) {
    (void)c;
    (void)pos;
    if (wl_resource_get_version(r) >= XDG_POPUP_REPOSITIONED_SINCE_VERSION)
        xdg_popup_send_repositioned(r, token);
}

static const struct xdg_popup_interface popup_impl = {
    .destroy = xdg_destroy,
    .grab = popup_grab,
    .reposition = popup_reposition,
};

static void xdg_get_toplevel(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct ax_surface *s = wl_resource_get_user_data(resource);
    uint32_t ver = wl_resource_get_version(resource);
    struct wl_resource *t = wl_resource_create(client, &xdg_toplevel_interface, ver, id);
    if (!t) {
        wl_client_post_no_memory(client);
        return;
    }
    s->toplevel = t;
    wl_resource_set_implementation(t, &toplevel_impl, s, toplevel_gone);
    send_toplevel_configure(s);
}

static void xdg_get_popup(struct wl_client *client, struct wl_resource *resource, uint32_t id,
                          struct wl_resource *parent, struct wl_resource *positioner) {
    (void)parent;
    (void)positioner;
    struct ax_surface *s = wl_resource_get_user_data(resource);
    uint32_t ver = wl_resource_get_version(resource);
    struct wl_resource *p = wl_resource_create(client, &xdg_popup_interface, ver, id);
    if (!p) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(p, &popup_impl, s, NULL);
    struct ax_output *o = s->srv->outs[s->output];
    xdg_popup_send_configure(p, 0, 0, o->w, o->h);
    xdg_surface_send_configure(resource, wl_display_next_serial(s->srv->dpy));
}

static void xdg_set_window_geometry(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y,
                                    int32_t w, int32_t h) {
    (void)c;
    (void)r;
    (void)x;
    (void)y;
    (void)w;
    (void)h;
}

static void xdg_ack_configure(struct wl_client *c, struct wl_resource *r, uint32_t serial) {
    (void)c;
    (void)serial;
    struct ax_surface *s = wl_resource_get_user_data(r);
    s->acked = 1;
}

static const struct xdg_surface_interface xdg_surface_impl = {
    .destroy = xdg_destroy,
    .get_toplevel = xdg_get_toplevel,
    .get_popup = xdg_get_popup,
    .set_window_geometry = xdg_set_window_geometry,
    .ack_configure = xdg_ack_configure,
};

static void xdg_surface_gone(struct wl_resource *resource) {
    struct ax_surface *s = wl_resource_get_user_data(resource);
    if (s) s->xdg = NULL;
}

static void wm_create_positioner(struct wl_client *client, struct wl_resource *resource,
                                 uint32_t id) {
    (void)resource;
    struct wl_resource *r =
        wl_resource_create(client, &xdg_positioner_interface, wl_resource_get_version(resource), id);
    if (!r) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(r, &positioner_impl, NULL, NULL);
}

static void wm_get_xdg_surface(struct wl_client *client, struct wl_resource *resource, uint32_t id,
                               struct wl_resource *surface) {
    struct ax_surface *s = wl_resource_get_user_data(surface);
    uint32_t ver = wl_resource_get_version(resource);
    struct wl_resource *r = wl_resource_create(client, &xdg_surface_interface, ver, id);
    if (!r) {
        wl_client_post_no_memory(client);
        return;
    }
    s->xdg = r;
    wl_resource_set_implementation(r, &xdg_surface_impl, s, xdg_surface_gone);
}

static const struct xdg_wm_base_interface wm_impl = {
    .destroy = xdg_destroy,
    .create_positioner = wm_create_positioner,
    .get_xdg_surface = wm_get_xdg_surface,
    .pong = xdg_pong,
};

static void bind_wm(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    uint32_t ver = version < 6 ? version : 6;
    struct wl_resource *r = wl_resource_create(client, &xdg_wm_base_interface, ver, id);
    if (!r) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(r, &wm_impl, data, NULL);
}

static void dma_buffer_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static const struct wl_buffer_interface dma_buffer_impl = {
    .destroy = dma_buffer_destroy,
};

static void dma_buffer_gone(struct wl_resource *resource) {
    struct ax_dma *d = wl_resource_get_user_data(resource);
    if (!d) return;
    if (d->fd >= 0) close(d->fd);
    free(d);
}

static int params_import(struct ax_params *p, struct wl_client *client, struct wl_resource *params,
                         uint32_t buffer_id, int32_t width, int32_t height, uint32_t format,
                         uint32_t flags, int immediate) {
    if (p->used) {
        wl_resource_post_error(params, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_ALREADY_USED, "used");
        return -1;
    }
    p->used = 1;
    if (p->fd < 0 || width <= 0 || height <= 0) {
        wl_resource_post_error(params, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INCOMPLETE, "plane");
        return -1;
    }
    if (format != ATLAS_FMT_ARGB8888 && format != ATLAS_FMT_XRGB8888) {
        wl_resource_post_error(params, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_FORMAT, "format");
        return -1;
    }
    if (p->stride < (uint32_t)width * 4u) {
        wl_resource_post_error(params, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_OUT_OF_BOUNDS, "stride");
        return -1;
    }
    struct ax_dma *d = calloc(1, sizeof(*d));
    if (!d) {
        wl_client_post_no_memory(client);
        return -1;
    }
    d->fd = p->fd;
    p->fd = -1;
    d->w = width;
    d->h = height;
    d->stride = (int32_t)p->stride;
    d->offset = p->offset;
    d->format = format;
    d->modifier = ((uint64_t)p->mod_hi << 32) | p->mod_lo;
    d->y_invert = (flags & ZWP_LINUX_BUFFER_PARAMS_V1_FLAGS_Y_INVERT) != 0;
    struct wl_resource *buf = wl_resource_create(client, &wl_buffer_interface, 1, buffer_id);
    if (!buf) {
        close(d->fd);
        free(d);
        wl_client_post_no_memory(client);
        return -1;
    }
    wl_resource_set_implementation(buf, &dma_buffer_impl, d, dma_buffer_gone);
    if (!immediate) zwp_linux_buffer_params_v1_send_created(params, buf);
    return 0;
}

static void params_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void params_add(struct wl_client *client, struct wl_resource *resource, int32_t fd,
                       uint32_t plane_idx, uint32_t offset, uint32_t stride, uint32_t modifier_hi,
                       uint32_t modifier_lo) {
    (void)client;
    struct ax_params *p = wl_resource_get_user_data(resource);
    if (plane_idx != 0) {
        close(fd);
        wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_PLANE_IDX, "plane");
        return;
    }
    if (p->fd >= 0) {
        close(fd);
        wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_PLANE_SET, "plane set");
        return;
    }
    p->fd = fd;
    p->offset = offset;
    p->stride = stride;
    p->mod_hi = modifier_hi;
    p->mod_lo = modifier_lo;
}

static void params_create(struct wl_client *client, struct wl_resource *resource, int32_t width,
                          int32_t height, uint32_t format, uint32_t flags) {
    struct ax_params *p = wl_resource_get_user_data(resource);
    /* Async create still needs an id. The protocol returns the buffer via the created event
     * and allocates no client-chosen id here — libwayland requires one. Use a server-side
     * id of 0 only through send_created, which needs an existing resource. */
    if (params_import(p, client, resource, 0, width, height, format, flags, 0) != 0 && p->fd >= 0) {
        /* params_import posts the error */
    }
}

static void params_create_immed(struct wl_client *client, struct wl_resource *resource,
                                uint32_t buffer_id, int32_t width, int32_t height, uint32_t format,
                                uint32_t flags) {
    struct ax_params *p = wl_resource_get_user_data(resource);
    params_import(p, client, resource, buffer_id, width, height, format, flags, 1);
}

static const struct zwp_linux_buffer_params_v1_interface params_impl = {
    .destroy = params_destroy,
    .add = params_add,
    .create = params_create,
    .create_immed = params_create_immed,
};

static void params_gone(struct wl_resource *resource) {
    struct ax_params *p = wl_resource_get_user_data(resource);
    if (!p) return;
    if (p->fd >= 0) close(p->fd);
    free(p);
}

static void dmabuf_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void dmabuf_create_params(struct wl_client *client, struct wl_resource *resource,
                                 uint32_t id) {
    struct ax_server *srv = wl_resource_get_user_data(resource);
    struct ax_params *p = calloc(1, sizeof(*p));
    if (!p) {
        wl_client_post_no_memory(client);
        return;
    }
    p->srv = srv;
    p->fd = -1;
    struct wl_resource *r = wl_resource_create(client, &zwp_linux_buffer_params_v1_interface,
                                               wl_resource_get_version(resource), id);
    if (!r) {
        free(p);
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(r, &params_impl, p, params_gone);
}

static const struct zwp_linux_dmabuf_v1_interface dmabuf_impl = {
    .destroy = dmabuf_destroy,
    .create_params = dmabuf_create_params,
};

static void bind_dmabuf(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    uint32_t ver = version < 3 ? version : 3;
    struct wl_resource *r = wl_resource_create(client, &zwp_linux_dmabuf_v1_interface, ver, id);
    if (!r) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(r, &dmabuf_impl, data, NULL);
    const uint32_t fmts[2] = {ATLAS_FMT_ARGB8888, ATLAS_FMT_XRGB8888};
    const uint64_t mod = 0x00ffffffffffffffull; /* DRM_FORMAT_MOD_INVALID */
    for (int i = 0; i < 2; i++) {
        zwp_linux_dmabuf_v1_send_format(r, fmts[i]);
        if (ver >= ZWP_LINUX_DMABUF_V1_MODIFIER_SINCE_VERSION)
            zwp_linux_dmabuf_v1_send_modifier(r, fmts[i], (uint32_t)(mod >> 32), (uint32_t)mod);
    }
}

static int setup_keymap(struct ax_server *srv) {
    srv->xkb_ctx = xkb_context_new(XKB_CONTEXT_NO_FLAGS);
    if (!srv->xkb_ctx) return -1;
    struct xkb_rule_names names = {0};
    srv->keymap = xkb_keymap_new_from_names(srv->xkb_ctx, &names, XKB_KEYMAP_COMPILE_NO_FLAGS);
    if (!srv->keymap) return -1;
    srv->xkb = xkb_state_new(srv->keymap);
    if (!srv->xkb) return -1;
    char *text = xkb_keymap_get_as_string(srv->keymap, XKB_KEYMAP_FORMAT_TEXT_V1);
    if (!text) return -1;
    size_t n = strlen(text);
    int fd = memfd_create("atlas-x-keymap", MFD_CLOEXEC);
    if (fd < 0 || ftruncate(fd, (off_t)n) != 0) {
        free(text);
        if (fd >= 0) close(fd);
        return -1;
    }
    void *map = mmap(NULL, n, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (map == MAP_FAILED) {
        free(text);
        close(fd);
        return -1;
    }
    memcpy(map, text, n);
    munmap(map, n);
    free(text);
    srv->keymap_fd = fd;
    srv->keymap_size = (uint32_t)n;
    return 0;
}

/* Publish :N once Xwayland's socket exists. The number is the one we passed
 * on its command line; displayfd is not used (a reused fd 3 swallowed it). */
static int on_xwait(void *data) {
    struct ax_server *srv = data;
    char sock[64], path[512];
    snprintf(sock, sizeof(sock), "/tmp/.X11-unix/X%d", srv->xdisp);
    if (access(sock, F_OK) != 0) {
        if (srv->xwait) wl_event_source_timer_update(srv->xwait, 50);
        return 0;
    }
    snprintf(path, sizeof(path), "%s/xdisplay", srv->dir);
    FILE *f = fopen(path, "w");
    if (f) {
        fprintf(f, ":%d\n", srv->xdisp);
        fclose(f);
    }
    fprintf(stderr, "atlas-x: Xwayland display :%d\n", srv->xdisp);
    return 0;
}

static int spawn_xwayland(struct ax_server *srv) {
    int disp = -1;
    for (int n = 7; n < 40; n++) {
        char path[64];
        snprintf(path, sizeof(path), "/tmp/.X11-unix/X%d", n);
        if (access(path, F_OK) != 0) {
            disp = n;
            break;
        }
    }
    if (disp < 0) return -1;
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        char logpath[512];
        snprintf(logpath, sizeof(logpath), "%s/xwayland.log", srv->dir);
        int logfd = open(logpath, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0644);
        if (logfd >= 0) {
            dup2(logfd, 2);
            close(logfd);
        }
        char geom[32], name[16];
        snprintf(geom, sizeof(geom), "%dx%d", srv->panel_w, srv->panel_h);
        snprintf(name, sizeof(name), ":%d", disp);
        setenv("XDG_RUNTIME_DIR", srv->dir, 1);
        setenv("WAYLAND_DISPLAY", "wayland-0", 1);
        /* Glamor keeps the frame on the GPU. Forcing it off made every
         * frame a CPU memfd copy and the desk lagged. */
        unsetenv("XWAYLAND_NO_GLAMOR");
        execlp("Xwayland", "Xwayland", name, "-geometry", geom, "-noreset", "-nolisten", "tcp",
               "-ac", (char *)NULL);
        _exit(127);
    }
    srv->xw_pid = pid;
    srv->xdisp = disp;
    fprintf(stderr, "atlas-x: Xwayland pid %d display :%d\n", (int)pid, disp);
    return 0;
}

static int on_term(int sig, void *data) {
    (void)sig;
    wl_display_terminate(data);
    return 0;
}

static int serve(struct ax_server *srv) {
    for (uint32_t i = 0; i < ATLAS_OUT_COUNT; i++) srv->latest[i].fd = -1;
    if (mkdir(srv->dir, 0755) != 0 && errno != EEXIST) {
        perror("atlas-x: mkdir");
        return 1;
    }
    if (setup_keymap(srv) != 0) {
        fprintf(stderr, "atlas-x: keymap failed\n");
        return 1;
    }
    srv->dpy = wl_display_create();
    if (!srv->dpy) return 1;
    if (wl_display_init_shm(srv->dpy) != 0) {
        fprintf(stderr, "atlas-x: shm init failed\n");
        return 1;
    }
    setenv("XDG_RUNTIME_DIR", srv->dir, 1);
    if (wl_display_add_socket(srv->dpy, "wayland-0") != 0) {
        fprintf(stderr, "atlas-x: wayland socket failed\n");
        return 1;
    }
    char wpath[512];
    snprintf(wpath, sizeof(wpath), "%s/wayland-0", srv->dir);
    chmod(wpath, 0666);

    wl_global_create(srv->dpy, &wl_compositor_interface, 5, srv, bind_compositor);
    wl_global_create(srv->dpy, &wl_seat_interface, 5, srv, bind_seat);
    wl_global_create(srv->dpy, &xdg_wm_base_interface, 6, srv, bind_wm);
    wl_global_create(srv->dpy, &zwp_linux_dmabuf_v1_interface, 3, srv, bind_dmabuf);

    int x = 0;
    for (uint32_t i = 0; i < ATLAS_OUT_COUNT; i++) {
        struct ax_output *o = calloc(1, sizeof(*o));
        o->srv = srv;
        o->index = i;
        o->x = x;
        o->y = 0;
        if (i == ATLAS_OUT_PANEL) {
            o->w = srv->panel_w;
            o->h = srv->panel_h;
            o->name = "panel";
        } else if (i == ATLAS_OUT_HDMI) {
            o->w = 1920;
            o->h = 1080;
            o->name = "hdmi";
        } else {
            o->w = 1920;
            o->h = 1080;
            o->name = "dp";
        }
        x += o->w;
        srv->outs[i] = o;
        wl_global_create(srv->dpy, &wl_output_interface, 3, o, bind_output);
    }

    char path[512];
    snprintf(path, sizeof(path), "%s/present.sock", srv->dir);
    srv->present_lfd = seq_listen(path);
    snprintf(path, sizeof(path), "%s/input.sock", srv->dir);
    srv->input_lfd = atlas_unix_listen(path);
    if (srv->present_lfd < 0 || srv->input_lfd < 0) {
        perror("atlas-x: socket");
        return 1;
    }
    struct wl_event_loop *loop = wl_display_get_event_loop(srv->dpy);
    wl_event_loop_add_fd(loop, srv->present_lfd, WL_EVENT_READABLE, on_present_listen, srv);
    wl_event_loop_add_fd(loop, srv->input_lfd, WL_EVENT_READABLE, on_input_listen, srv);
    if (srv->seatd_fd >= 0)
        srv->seatd_src =
            wl_event_loop_add_fd(loop, srv->seatd_fd, WL_EVENT_READABLE, on_seatd, srv);
    wl_event_loop_add_signal(loop, SIGINT, on_term, srv->dpy);
    wl_event_loop_add_signal(loop, SIGTERM, on_term, srv->dpy);

    if (srv->run_xwayland && spawn_xwayland(srv) != 0)
        fprintf(stderr, "atlas-x: Xwayland did not start\n");
    if (srv->xw_pid > 0) {
        srv->xwait = wl_event_loop_add_timer(loop, on_xwait, srv);
        if (srv->xwait) wl_event_source_timer_update(srv->xwait, 50);
    }

    fprintf(stderr, "atlas-x: %s panel %dx%d present+input ready\n", srv->dir, srv->panel_w,
            srv->panel_h);
    wl_display_run(srv->dpy);

    if (srv->xw_pid > 0) {
        kill(srv->xw_pid, SIGTERM);
        for (int i = 0; i < 50; i++) {
            if (waitpid(srv->xw_pid, NULL, WNOHANG) == srv->xw_pid) {
                srv->xw_pid = 0;
                break;
            }
            usleep(10000);
        }
        if (srv->xw_pid > 0) {
            kill(srv->xw_pid, SIGKILL);
            waitpid(srv->xw_pid, NULL, 0);
        }
    }
    wl_display_destroy(srv->dpy);
    return 0;
}

static int cmd_key(const char *dir, uint32_t code) {
    char path[512];
    snprintf(path, sizeof(path), "%s/input.sock", dir);
    int fd = atlas_unix_connect(path);
    if (fd < 0) {
        perror("atlas-x: input");
        return 1;
    }
    if (atlas_hello(fd, ATLAS_ROLE_APP, "atlas-x") != 0) return 1;
    struct atlas_seat_hdr h;
    memset(&h, 0, sizeof(h));
    h.magic = ATLAS_SEAT_MAGIC_INP;
    h.ver = ATLAS_SEAT_VER;
    h.type = ATLAS_T_KEY;
    h.w = code;
    h.h = 1;
    h.pts_us = now_us();
    if (atlas_send_hdr_pay(fd, &h, NULL, 0) != 0) return 1;
    h.h = 0;
    if (atlas_send_hdr_pay(fd, &h, NULL, 0) != 0) return 1;
    close(fd);
    return 0;
}

static int cmd_pull(const char *dir, uint16_t output, uint32_t min_gen, int timeout_ms) {
    char path[512];
    snprintf(path, sizeof(path), "%s/present.sock", dir);
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (fd < 0) return 1;
    struct sockaddr_un a;
    memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    if (strlen(path) >= sizeof(a.sun_path)) return 1;
    memcpy(a.sun_path, path, strlen(path) + 1);
    if (connect(fd, (struct sockaddr *)&a, sizeof(a)) != 0) {
        perror("atlas-x: present");
        return 1;
    }
    struct atlas_present_req req;
    memset(&req, 0, sizeof(req));
    req.magic = ATLAS_PRESENT_MAGIC_REQ;
    req.ver = ATLAS_PRESENT_VER;
    req.output = output;
    req.min_gen = min_gen;
    if (send(fd, &req, sizeof(req), MSG_NOSIGNAL) != (ssize_t)sizeof(req)) return 1;
    struct pollfd pfd = {.fd = fd, .events = POLLIN};
    int pr = poll(&pfd, 1, timeout_ms);
    if (pr <= 0) {
        fprintf(stderr, "atlas-x: pull timeout\n");
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
    if (n != (ssize_t)sizeof(m) || m.magic != ATLAS_PRESENT_MAGIC) return 1;
    int pfd_pass = -1;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS)
            memcpy(&pfd_pass, CMSG_DATA(c), sizeof(pfd_pass));
    }
    fprintf(stderr, "present flags=%u gen=%u %ux%u fourcc=%08x stride=%u\n", m.flags, m.gen,
            m.width, m.height, m.fourcc, m.stride);
    if (m.flags & ATLAS_PRESENT_NONE) {
        if (pfd_pass >= 0) close(pfd_pass);
        return 0;
    }
    if (pfd_pass < 0) return 1;
    size_t nbytes = (size_t)m.stride * (size_t)m.height;
    void *map = mmap(NULL, nbytes, PROT_READ, MAP_SHARED, pfd_pass, m.offset);
    if (map == MAP_FAILED) {
        close(pfd_pass);
        return 1;
    }
    fwrite(map, 1, nbytes, stdout);
    munmap(map, nbytes);
    close(pfd_pass);
    return 0;
}

static void usage(void) {
    fprintf(stderr,
            "atlas-x %s — Xwayland compositor + explicit present\n"
            "  atlas-x [-d dir] [-g WxH] [-s seat.sock] [-X]\n"
            "  atlas-x --key -d dir CODE\n"
            "  atlas-x --pull -d dir [-o 0|1|2] [--min-gen N] [--timeout ms]\n"
            "\n"
            "dir defaults to %s\n"
            "Android opens the same directory at %s\n"
            "  present.sock  SEQPACKET pull of the latest memfd or dma-buf\n"
            "  input.sock    seat frames (KEY/PTR), same wire as atlas-seatd\n"
            "  wayland-0     Xwayland and other Wayland clients\n"
            "-X starts rootful Xwayland on the panel (XWAYLAND_NO_GLAMOR).\n"
            "GL clients inside that X server use virpipe when a virgl server is up.\n",
            ATLAS_X_VERSION, ATLAS_X_DIR_DEBIAN, ATLAS_X_DIR_ANDROID);
}

int main(int argc, char **argv) {
    const char *dir = ATLAS_X_DIR_DEBIAN;
    const char *seat = NULL;
    int panel_w = 1080, panel_h = 1200;
    int xwayland = 0;
    int mode = 0; /* 1 key, 2 pull */
    uint32_t keycode = 0;
    uint16_t output = ATLAS_OUT_PANEL;
    uint32_t min_gen = 0;
    int timeout_ms = 2000;

    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-h") || !strcmp(argv[i], "--help")) {
            usage();
            return 0;
        }
        if (!strcmp(argv[i], "--key")) {
            mode = 1;
            continue;
        }
        if (!strcmp(argv[i], "--pull")) {
            mode = 2;
            continue;
        }
        if (!strcmp(argv[i], "-X")) {
            xwayland = 1;
            continue;
        }
        if (!strcmp(argv[i], "-d") && i + 1 < argc) {
            dir = argv[++i];
            continue;
        }
        if (!strcmp(argv[i], "-s") && i + 1 < argc) {
            seat = argv[++i];
            continue;
        }
        if (!strcmp(argv[i], "-g") && i + 1 < argc) {
            if (sscanf(argv[++i], "%dx%d", &panel_w, &panel_h) != 2 || panel_w < 1 || panel_h < 1) {
                usage();
                return 2;
            }
            continue;
        }
        if (!strcmp(argv[i], "-o") && i + 1 < argc) {
            output = (uint16_t)atoi(argv[++i]);
            continue;
        }
        if (!strcmp(argv[i], "--min-gen") && i + 1 < argc) {
            min_gen = (uint32_t)atoi(argv[++i]);
            continue;
        }
        if (!strcmp(argv[i], "--timeout") && i + 1 < argc) {
            timeout_ms = atoi(argv[++i]);
            continue;
        }
        if (mode == 1 && keycode == 0 && argv[i][0] != '-') {
            keycode = (uint32_t)atoi(argv[i]);
            continue;
        }
        usage();
        return 2;
    }

    if (mode == 1) return cmd_key(dir, keycode);
    if (mode == 2) return cmd_pull(dir, output, min_gen, timeout_ms);

    struct ax_server srv;
    memset(&srv, 0, sizeof(srv));
    snprintf(srv.dir, sizeof(srv.dir), "%s", dir);
    srv.panel_w = panel_w;
    srv.panel_h = panel_h;
    srv.run_xwayland = xwayland;
    srv.seatd_fd = -1;
    srv.keymap_fd = -1;
    if (seat) {
        srv.seatd_fd = atlas_unix_connect(seat);
        if (srv.seatd_fd < 0) {
            perror("atlas-x: seatd");
            return 1;
        }
        if (atlas_hello(srv.seatd_fd, ATLAS_ROLE_INPUT, "atlas-x") != 0) return 1;
        fcntl(srv.seatd_fd, F_SETFL, O_NONBLOCK);
    }
    signal(SIGPIPE, SIG_IGN);
    return serve(&srv);
}
