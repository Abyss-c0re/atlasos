/* Grab titan2-virtual-mouse and push it into the atlas-x seat.
 * touchPad is absolute. titan2-touchpadd already turns it into this relative
 * mouse (REL_X/Y, BTN_LEFT/RIGHT, REL_WHEEL). Grabbing the mouse node keeps
 * Android from drawing a second cursor and gives Debian the same deltas.
 *
 * Position is also written to desk-ptr (two little-endian int32) so the
 * picture can draw the one pointer KDE is using.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define MAGIC_INP 0x4e495641u
#define MAGIC_CTL 0x4c544341u
#define SEAT_VER 1u
#define T_PTR 3u
#define T_HELLO 5u
#define ROLE_APP 4u
#define MAX_DEV 4
#define PTR_PATH "/data/local/tmp/atlas-virgl/desk-ptr"
/* Same file atlas-desk-keys watches. 0, missing, or stale: Android owns
 * the trackpad. The app uid cannot kill a root-owned grabber. */
#define FOCUS_PATH "/data/local/tmp/atlas-virgl/desk-focus"
#define FOCUS_STALE_MS 8000

struct seat_hdr {
    uint32_t magic;
    uint16_t ver;
    uint16_t type;
    uint32_t w, h, fmt, nbytes;
    uint64_t pts_us;
} __attribute__((packed));

struct seat_hello {
    uint32_t role;
    char name[16];
} __attribute__((packed));

struct seat_ptr {
    int32_t dx, dy, wheel;
    uint32_t buttons;
} __attribute__((packed));

static const char *sockpath;
static volatile sig_atomic_t stop;
static struct {
    int fd;
    int forward; /* 1 = titan2-orient-mouse, the HID host mouse */
} devs[MAX_DEV];
static int ndev;
static int cur_x = 540, cur_y = 540;
static int desk_w = 1440, desk_h = 1440;
static int cur_known;
static uint32_t buttons;
static int32_t acc_x, acc_y, acc_w;

static void on_sig(int sig) {
    (void)sig;
    stop = 1;
}

static int send_all(int fd, const void *buf, size_t n) {
    const char *p = buf;
    while (n) {
        ssize_t w = write(fd, p, n);
        if (w < 0 && errno == EINTR) continue;
        if (w <= 0) return -1;
        p += w;
        n -= (size_t)w;
    }
    return 0;
}

static void read_ptr_file(void) {
    int fd = open(PTR_PATH, O_RDONLY | O_CLOEXEC);
    int32_t xy[2];
    if (fd < 0) return;
    if (read(fd, xy, sizeof(xy)) == (ssize_t)sizeof(xy)) {
        cur_x = xy[0];
        cur_y = xy[1];
        cur_known = 1;
    }
    close(fd);
}

static void write_ptr_file(void) {
    int fd = open(PTR_PATH, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0666);
    int32_t xy[2];
    if (fd < 0) return;
    xy[0] = cur_x;
    xy[1] = cur_y;
    if (write(fd, xy, sizeof(xy)) != (ssize_t)sizeof(xy)) {
        /* best effort */
    }
    close(fd);
}

static int send_ptr(int32_t x, int32_t y, int32_t wheel, uint32_t btns) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_un a;
    struct seat_hdr h;
    struct seat_hello hi;
    struct seat_ptr p;
    if (fd < 0) return -1;
    memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    if (!sockpath || strlen(sockpath) >= sizeof(a.sun_path)) {
        close(fd);
        return -1;
    }
    memcpy(a.sun_path, sockpath, strlen(sockpath) + 1);
    if (connect(fd, (struct sockaddr *)&a, sizeof(a)) != 0) {
        close(fd);
        return -1;
    }
    memset(&hi, 0, sizeof(hi));
    hi.role = ROLE_APP;
    memcpy(hi.name, "pad", 3);
    memset(&h, 0, sizeof(h));
    h.magic = MAGIC_CTL;
    h.ver = SEAT_VER;
    h.type = T_HELLO;
    h.nbytes = sizeof(hi);
    if (send_all(fd, &h, sizeof(h)) != 0 || send_all(fd, &hi, sizeof(hi)) != 0) {
        close(fd);
        return -1;
    }
    memset(&p, 0, sizeof(p));
    p.dx = x;
    p.dy = y;
    p.wheel = wheel;
    p.buttons = btns;
    memset(&h, 0, sizeof(h));
    h.magic = MAGIC_INP;
    h.ver = SEAT_VER;
    h.type = T_PTR;
    h.fmt = 1; /* absolute desktop pixel, same one drawn on the picture */
    h.nbytes = sizeof(p);
    /* Header alone is not a frame. atlas-x drops the event on EOF if the
     * payload never arrives, and the X pointer stays where Java last put it. */
    if (send_all(fd, &h, sizeof(h)) != 0 || send_all(fd, &p, sizeof(p)) != 0) {
        close(fd);
        return -1;
    }
    close(fd);
    return 0;
}

/* The desk size moves (windowed hole vs full glass). Never stop short of it. */
static void load_desk_size(void) {
    static const char *paths[] = {
        "/data/local/atlas-linux/home/atlas/atlas-x/size",
        "/home/atlas/atlas-x/size",
        NULL
    };
    int i;
    for (i = 0; paths[i]; i++) {
        FILE *f = fopen(paths[i], "r");
        int w = 0, h = 0;
        if (!f) continue;
        if (fscanf(f, "%d %d", &w, &h) == 2
            && w >= 640 && w <= 2160 && h >= 480 && h <= 2160) {
            desk_w = w;
            desk_h = h;
        }
        fclose(f);
        return;
    }
}

static void clamp_ptr(void) {
    load_desk_size();
    if (cur_x < 0) cur_x = 0;
    if (cur_y < 0) cur_y = 0;
    if (cur_x > desk_w - 1) cur_x = desk_w - 1;
    if (cur_y > desk_h - 1) cur_y = desk_h - 1;
}

static int read_plane_int(const char *a, const char *b, int def) {
    FILE *f = fopen(a, "r");
    int v = def;
    if (!f) f = fopen(b, "r");
    if (!f) return def;
    if (fscanf(f, "%d", &v) != 1) v = def;
    fclose(f);
    return v;
}

static long long mono_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

static int typing_locked(void) {
    int n = 0;
    long long ts = 0;
    FILE *f = fopen("/data/local/tmp/atlas-virgl/desk-keys-held", "r");
    if (!f) return 0;
    if (fscanf(f, "%d %lld", &n, &ts) != 2) n = 0;
    fclose(f);
    /* Only while a key is actually down. A stale count must not freeze the pad. */
    if (n <= 0 || ts <= 0) return 0;
    return (mono_ms() - ts) < 400;
}

/* hid_bridge scale_rel: titan2_usb_hid_speed / accel. Defaults 100% / off. */
static void scale_rel(int32_t *dx, int32_t *dy) {
    int speed, accel, x, y, mag, boost;
    if (!dx || !dy || (!*dx && !*dy)) return;
    speed = read_plane_int("/data/misc/titan2/titan2_usb_hid_speed",
                           "/data/local/tmp/titan2_usb_hid_speed", 100);
    accel = read_plane_int("/data/misc/titan2/titan2_usb_hid_accel",
                           "/data/local/tmp/titan2_usb_hid_accel", 0);
    if (speed < 25) speed = 25;
    if (speed > 400) speed = 400;
    if (accel < 0) accel = 0;
    if (accel > 3) accel = 3;
    x = (*dx * speed) / 100;
    y = (*dy * speed) / 100;
    if (accel > 0) {
        mag = abs(x) + abs(y);
        boost = 100 + (accel * mag * 5);
        if (boost > 350) boost = 350;
        x = (x * boost) / 100;
        y = (y * boost) / 100;
    }
    if (*dx && !x) x = (*dx > 0) ? 1 : -1;
    if (*dy && !y) y = (*dy > 0) ? 1 : -1;
    *dx = x;
    *dy = y;
}

static void flush_motion(void) {
    if (!acc_x && !acc_y && !acc_w) return;
    /* Palm on the keybed is the trackpad. HID freezes host mouse while typing. */
    if (typing_locked()) {
        acc_x = acc_y = acc_w = 0;
        return;
    }
    scale_rel(&acc_x, &acc_y);
    read_ptr_file();
    if (!cur_known) {
        cur_x = 540;
        cur_y = 540;
        cur_known = 1;
    }
    cur_x += acc_x;
    cur_y += acc_y;
    clamp_ptr();
    send_ptr(cur_x, cur_y, acc_w, buttons);
    write_ptr_file();
    acc_x = acc_y = acc_w = 0;
}

static void on_event(const struct input_event *ev) {
    if (ev->type == EV_REL) {
        if (ev->code == REL_X) acc_x += ev->value;
        else if (ev->code == REL_Y) acc_y += ev->value;
        else if (ev->code == REL_WHEEL) acc_w += ev->value;
    } else if (ev->type == EV_KEY && ev->value != 2) {
        uint32_t bit = 0;
        if (ev->code == BTN_LEFT) bit = 1;
        else if (ev->code == BTN_RIGHT) bit = 2;
        else if (ev->code == BTN_MIDDLE) bit = 4;
        if (bit) {
            if (ev->value) buttons |= bit;
            else buttons &= ~bit;
            flush_motion();
            send_ptr(cur_known ? cur_x : 540, cur_known ? cur_y : 540, 0, buttons);
        }
    } else if (ev->type == EV_SYN && ev->code == SYN_REPORT) {
        flush_motion();
    }
}

static int open_virtual_mice(void) {
    int i;
    ndev = 0;
    for (i = 0; i < 32 && ndev < MAX_DEV; i++) {
        char npath[80], dpath[40], name[64];
        int fd, n, forward = 0;
        snprintf(npath, sizeof(npath), "/sys/class/input/event%d/device/name", i);
        fd = open(npath, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        n = (int)read(fd, name, sizeof(name) - 1);
        close(fd);
        if (n <= 0) continue;
        name[n] = 0;
        if (name[n - 1] == '\n') name[n - 1] = 0;
        /* orient-rel owns the first virtual mouse and re-emits the finger
         * as titan2-orient-mouse. That is the device Android was moving. */
        if (strcmp(name, "titan2-virtual-mouse") != 0 &&
            strcmp(name, "titan2-orient-mouse") != 0)
            continue;
        forward = !strcmp(name, "titan2-orient-mouse");
        snprintf(dpath, sizeof(dpath), "/dev/input/event%d", i);
        fd = open(dpath, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
        if (fd < 0) {
            perror(dpath);
            continue;
        }
        {
            /* orient-rel already owns the first virtual mouse. Take the one
             * Android was reading. A failed grab is not our stream.
             * Non-NULL grabs. NULL releases. A pointer to 0 does not. */
            if (ioctl(fd, EVIOCGRAB, (void *)1) != 0) {
                fprintf(stderr, "pad skip %s (%s)\n", dpath, strerror(errno));
                close(fd);
                continue;
            }
        }
        devs[ndev].fd = fd;
        devs[ndev].forward = forward;
        ndev++;
        fprintf(stderr, "pad %s %s\n", dpath, forward ? "forward" : "drop");
    }
    return ndev;
}

static void ungrab(void) {
    int i;
    for (i = 0; i < ndev; i++) {
        if (devs[i].fd >= 0)
            ioctl(devs[i].fd, EVIOCGRAB, (void *)0);
        close(devs[i].fd);
        devs[i].fd = -1;
    }
    ndev = 0;
}

/* 1 only while DeskActivity is focused and refreshing the file. */
static int desk_wants_grab(void) {
    struct stat st;
    struct timespec now;
    char b[4];
    int fd, n;
    long long age;
    if (stat(FOCUS_PATH, &st) != 0) return 0;
    clock_gettime(CLOCK_REALTIME, &now);
    age = (now.tv_sec - st.st_mtim.tv_sec) * 1000LL
        + (now.tv_nsec - st.st_mtim.tv_nsec) / 1000000LL;
    if (age < 0) age = 0;
    if (age > FOCUS_STALE_MS) return 0;
    fd = open(FOCUS_PATH, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    n = (int)read(fd, b, sizeof(b));
    close(fd);
    return n > 0 && b[0] == '1';
}

static void arm_forward(void) {
    int i, any = 0;
    for (i = 0; i < ndev; i++) if (devs[i].forward) any = 1;
    /* No orient-mouse yet: the raw virtual mouse is the host device. */
    if (!any) {
        for (i = 0; i < ndev; i++) {
            devs[i].forward = 1;
            fprintf(stderr, "pad forward raw %d\n", i);
        }
    }
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: atlas-desk-pad seat.sock\n");
        return 2;
    }
    sockpath = argv[1];
    signal(SIGTERM, on_sig);
    signal(SIGINT, on_sig);
    signal(SIGCHLD, SIG_IGN);
    while (!stop) {
        struct pollfd pf[MAX_DEV];
        int i, pr;
        /* Shade, lock, or another app. The phone owns the trackpad. */
        if (!desk_wants_grab()) {
            if (ndev > 0) {
                fprintf(stderr, "released touchpad\n");
                ungrab();
            }
            poll(NULL, 0, 100);
            continue;
        }
        if (ndev < 1) {
            if (open_virtual_mice() < 1) {
                poll(NULL, 0, 200);
                continue;
            }
            arm_forward();
            fprintf(stderr, "focus held, touchpad grabbed mice=%d\n", ndev);
        }
        for (i = 0; i < ndev; i++) {
            pf[i].fd = devs[i].fd;
            pf[i].events = POLLIN;
            pf[i].revents = 0;
        }
        pr = poll(pf, (nfds_t)ndev, 100);
        if (pr < 0 && errno == EINTR) continue;
        if (pr < 0) break;
        if (pr == 0) continue;
        for (i = 0; i < ndev; i++) {
            struct input_event ev;
            if ((pf[i].revents & POLLIN) == 0) continue;
            for (;;) {
                ssize_t n = read(devs[i].fd, &ev, sizeof(ev));
                if (n == (ssize_t)sizeof(ev)) {
                    if (devs[i].forward) on_event(&ev);
                    continue;
                }
                break;
            }
        }
    }
    ungrab();
    return 0;
}
