/* Read TitanKey and push evdev codes into the atlas-x seat.
 * Grabs the device so Android does not eat the same keys. Events are
 * forwarded only after EVIOCGRAB succeeds; a failed grab is retried.
 * Back, volume, power, and the Home/Recents scan are handed back to Android.
 *
 * HID host map: Sym (222, 253) is the printed US glyph layer, not XKB
 * AltGr. Free Alt (100) is Left Alt. Fn (183, 251) is Left Ctrl.
 * Neither Sym scan is Android KEYCODE_SYM (that keycode is a lock).
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
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define MAGIC_INP 0x4e495641u
#define MAGIC_CTL 0x4c544341u
#define SEAT_VER 1u
#define T_KEY 2u
#define T_HELLO 5u
#define ROLE_APP 4u
#define KEY_TRACK 768
/* DeskActivity writes this while its window is focused. Missing, 0, or
 * stale means the phone owns TitanKey. A root grabber cannot be killed
 * by the app uid, so the grabber itself drops EVIOCGRAB. */
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

static int devfd = -1;
static int lockfd = -1;
static int grabbed;
static int can_inject;
static const char *sockpath;
static volatile sig_atomic_t stop;
static long long home_down_ms;
static unsigned char phys_down[KEY_TRACK];

static const unsigned clear_scans[] = {
    29, 42, 54, 56, 97, 100, 125, 126, 183, 222, 251, 253
};

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

static int send_key(uint32_t code, int down) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_un a;
    struct seat_hdr h;
    struct seat_hello hi;
    if (fd < 0) return -1;
    memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    if (strlen(sockpath) >= sizeof(a.sun_path)) {
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
    memcpy(hi.name, "keys", 4);
    memset(&h, 0, sizeof(h));
    h.magic = MAGIC_CTL;
    h.ver = SEAT_VER;
    h.type = T_HELLO;
    h.nbytes = sizeof(hi);
    if (send_all(fd, &h, sizeof(h)) != 0 || send_all(fd, &hi, sizeof(hi)) != 0) {
        close(fd);
        return -1;
    }
    memset(&h, 0, sizeof(h));
    h.magic = MAGIC_INP;
    h.ver = SEAT_VER;
    h.type = T_KEY;
    h.w = code;
    h.h = down ? 1u : 0u;
    if (send_all(fd, &h, sizeof(h)) != 0) {
        close(fd);
        return -1;
    }
    close(fd);
    return 0;
}

/* Same host map as hid_bridge: Sym is the printed layer, not XKB AltGr.
 * Free Alt (scan 100) is Left Alt. Fn is Left Ctrl. */
static unsigned sym_mask;
static unsigned char synth_shift[KEY_TRACK];
static unsigned short mapped_key[KEY_TRACK];
static int keys_held;
static long long mono_ms(void);

static void write_held(void) {
    int fd = open("/data/local/tmp/atlas-virgl/desk-keys-held",
                  O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0666);
    char b[48];
    int n;
    if (fd < 0) return;
    /* count and monotonic ms. A missed key-up must not freeze the pad. */
    n = snprintf(b, sizeof(b), "%d %lld\n", keys_held, (long long)mono_ms());
    if (n > 0) {
        if (write(fd, b, (size_t)n) != n) {
            /* palm lock is best-effort */
        }
    }
    close(fd);
}

static int hid_to_linux(unsigned usage) {
    switch (usage) {
    case 0x1e: return 2;  case 0x1f: return 3;  case 0x20: return 4;
    case 0x21: return 5;  case 0x22: return 6;  case 0x23: return 7;
    case 0x24: return 8;  case 0x25: return 9;  case 0x26: return 10;
    case 0x27: return 11; case 0x2d: return 12; case 0x2e: return 13;
    case 0x33: return 39; case 0x34: return 40; case 0x36: return 51;
    case 0x37: return 52; case 0x38: return 53;
    default: return 0;
    }
}

/* hid_bridge titan_specials_layer_hid — printed Sym glyphs as US keys. */
static int specials_layer(unsigned code, int *shift, int *linux_key) {
    unsigned usage = 0;
    int sh = 0;
    switch (code) {
    case 16: usage = 0x27; break;             /* Q → 0 */
    case 17: usage = 0x1e; break;             /* W → 1 */
    case 18: usage = 0x1f; break;             /* E → 2 */
    case 19: usage = 0x20; break;             /* R → 3 */
    case 20: sh = 1; usage = 0x26; break;     /* T → ( */
    case 21: sh = 1; usage = 0x27; break;     /* Y → ) */
    case 22: sh = 1; usage = 0x2d; break;     /* U → _ */
    case 23: usage = 0x2d; break;             /* I → - */
    case 24: usage = 0x38; break;             /* O → / */
    case 25: sh = 1; usage = 0x33; break;     /* P → : */
    case 30: sh = 1; usage = 0x1f; break;     /* A → @ */
    case 31: usage = 0x21; break;             /* S → 4 */
    case 32: usage = 0x22; break;             /* D → 5 */
    case 33: usage = 0x23; break;             /* F → 6 */
    case 34: sh = 1; usage = 0x25; break;     /* G → * */
    case 35: sh = 1; usage = 0x20; break;     /* H → # */
    case 36: sh = 1; usage = 0x2e; break;     /* J → + */
    case 37: sh = 1; usage = 0x34; break;     /* K → " */
    case 38: usage = 0x34; break;             /* L → ' */
    case 44: sh = 1; usage = 0x1e; break;     /* Z → ! */
    case 45: usage = 0x24; break;             /* X → 7 */
    case 46: usage = 0x25; break;             /* C → 8 */
    case 47: usage = 0x26; break;             /* V → 9 */
    case 48: usage = 0x37; break;             /* B → . */
    case 49: usage = 0x36; break;             /* N → , */
    case 50: sh = 1; usage = 0x38; break;     /* M → ? */
    default: return 0;
    }
    if (shift) *shift = sh;
    if (linux_key) *linux_key = hid_to_linux(usage);
    return 1;
}

static uint32_t seat_code(unsigned code) {
    switch (code) {
    case 100: return 56;          /* free Alt, not AltGr */
    case 183: case 251: return 29; /* Fn → Left Ctrl, same as HID */
    default: return code;
    }
}

static int is_mod_scan(unsigned code) {
    switch (code) {
    case 29: case 42: case 54: case 56: case 97:
    case 100: case 125: case 126: case 183: case 222: case 251: case 253:
        return 1;
    default:
        return 0;
    }
}

/* Volume and power stay Android keys. Home and Back are not injected:
 * the grab would hide them from key-watch, and a synthetic keyevent
 * cannot express a hold. */
static int android_key(int code) {
    switch (code) {
    case 114: return 25;  /* KEY_VOLUMEDOWN */
    case 115: return 24;  /* KEY_VOLUMEUP */
    case 116: return 26;  /* KEY_POWER */
    default: return 0;
    }
}

static long long mono_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

/* Controls plane: same files key-watch reads. Empty means the product default. */
static void plane_action(const char *name, const char *fallback, char *out, size_t n) {
    const char *dirs[] = { "/data/misc/titan2/", "/data/local/tmp/" };
    size_t i;
    out[0] = 0;
    for (i = 0; i < 2; i++) {
        char path[160];
        char buf[64];
        int fd;
        ssize_t r;
        size_t k;
        snprintf(path, sizeof(path), "%s%s", dirs[i], name);
        fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        r = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (r <= 0) continue;
        buf[r] = 0;
        for (k = 0; buf[k] && buf[k] != ' ' && buf[k] != '\n' && buf[k] != '\r' && buf[k] != '\t'; k++)
            ;
        buf[k] = 0;
        if (!buf[0] || !strcmp(buf, "null") || !strcmp(buf, "none")
            || !strcmp(buf, "default"))
            continue;
        for (k = 0; buf[k] && k + 1 < n; k++) {
            char c = buf[k];
            int ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == ':' || c == '-';
            if (!ok) break;
            out[k] = c;
        }
        out[k] = 0;
        if (out[0]) return;
    }
    snprintf(out, n, "%s", fallback);
}

static void exec_sh(const char *cmd) {
    pid_t p = fork();
    if (p < 0) return;
    if (p == 0) {
        execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
        _exit(127);
    }
}

/* Short/long Home is the Controls keymap (titan2_km_recents_*), fired as KEY_FIRE.
 * Never keyevent 187 and never RecentsActivity. */
static void fire_home(int held_long) {
    char act[40];
    char cmd[256];
    plane_action(held_long ? "titan2_km_recents_long" : "titan2_km_recents_short",
                 held_long ? "recents" : "home", act, sizeof(act));
    snprintf(cmd, sizeof(cmd),
             "exec /system/bin/am broadcast --user 0 -a com.titanus2.controls.KEY_FIRE "
             "-p com.titanus2.controls --es action %s --ei scan 580",
             act);
    exec_sh(cmd);
}

/* Back leaves the desk and drops the Android keyguard if it is up.
 * wm is backgrounded: a stuck dismiss must not block the leave broadcast.
 * GLOBAL_ACTION_BACK would hit this same window. */
static void fire_leave(void) {
    exec_sh("/system/bin/wm dismiss-keyguard >/dev/null 2>&1 & "
            "/system/bin/am broadcast --user 0 -a com.titanus2.atlas.DESK_LEAVE "
            "-p com.titanus2.atlas >/dev/null 2>&1");
}

static void reinject(int keycode) {
    pid_t p = fork();
    if (p < 0) return;
    if (p == 0) {
        char num[16];
        snprintf(num, sizeof(num), "%d", keycode);
        execl("/system/bin/input", "input", "keyevent", num, (char *)NULL);
        _exit(127);
    }
}

static void inject_scan_up(unsigned code) {
    struct input_event ev;
    if (devfd < 0 || !can_inject) return;
    memset(&ev, 0, sizeof(ev));
    ev.type = EV_KEY;
    ev.code = code;
    ev.value = 0;
    if (write(devfd, &ev, sizeof(ev)) != (ssize_t)sizeof(ev)) return;
    memset(&ev, 0, sizeof(ev));
    ev.type = EV_SYN;
    ev.code = SYN_REPORT;
    write(devfd, &ev, sizeof(ev));
}

static void release_android_mods(void) {
    size_t i;
    for (i = 0; i < sizeof(clear_scans) / sizeof(clear_scans[0]); i++)
        inject_scan_up(clear_scans[i]);
}

static void drain_dev(void) {
    struct pollfd p;
    struct input_event ev;
    int flags;
    if (devfd < 0) return;
    flags = fcntl(devfd, F_GETFL, 0);
    if (flags < 0) return;
    fcntl(devfd, F_SETFL, flags | O_NONBLOCK);
    p.fd = devfd;
    p.events = POLLIN;
    while (poll(&p, 1, 0) > 0 && (p.revents & POLLIN)) {
        if (read(devfd, &ev, sizeof(ev)) != (ssize_t)sizeof(ev)) break;
    }
    fcntl(devfd, F_SETFL, flags);
}

/* Drop a shift/alt layer the seat still holds after a missed key-up.
 * Sym is not AltGr, so this must not emit key 100 (XKB Right Alt). */
static void clear_stale_seat(unsigned arriving) {
    if (is_mod_scan(arriving)) return;
    if (!phys_down[100]) send_key(56, 0);
    if (!phys_down[42]) send_key(42, 0);
    if (!phys_down[54]) send_key(54, 0);
}

static void release_seat(void) {
    unsigned i;
    for (i = 0; i < KEY_TRACK; i++) {
        if (!phys_down[i]) continue;
        send_key(seat_code(i), 0);
        phys_down[i] = 0;
    }
    /* Previous grabber may have latched raw AltGr (scan 100) with no up. */
    send_key(100, 0);
    send_key(56, 0);
    send_key(42, 0);
    send_key(54, 0);
}

/* One reader. A second onResume must exit instead of sitting on the same node. */
static int take_lock(void) {
    lockfd = open("/data/local/tmp/atlas-virgl/keys.lock",
                  O_RDWR | O_CREAT | O_CLOEXEC, 0666);
    if (lockfd < 0) return 0;
    if (flock(lockfd, LOCK_EX | LOCK_NB) != 0) {
        fprintf(stderr, "keys already running\n");
        close(lockfd);
        lockfd = -1;
        return 0;
    }
    return 1;
}

static int try_grab(void) {
    if (grabbed || devfd < 0) return grabbed;
    /* The kernel treats a non-NULL argument as grab. A pointer to 0 does not release. */
    if (ioctl(devfd, EVIOCGRAB, (void *)1) != 0) return 0;
    grabbed = 1;
    fprintf(stderr, "grab ok\n");
    return 1;
}

static void release_dev(void) {
    if (devfd < 0) return;
    if (grabbed) ioctl(devfd, EVIOCGRAB, (void *)0);
    grabbed = 0;
    release_android_mods();
    close(devfd);
    devfd = -1;
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

/* Ungrab but keep the fd and the flock. Android receives keys immediately.
 * Focus coming back re-grabs this same process. */
static void drop_grab(void) {
    if (devfd < 0 || !grabbed) return;
    release_seat();
    keys_held = 0;
    sym_mask = 0;
    memset(synth_shift, 0, sizeof(synth_shift));
    memset(mapped_key, 0, sizeof(mapped_key));
    memset(phys_down, 0, sizeof(phys_down));
    write_held();
    if (ioctl(devfd, EVIOCGRAB, (void *)0) != 0)
        fprintf(stderr, "ungrab: %s\n", strerror(errno));
    grabbed = 0;
    release_android_mods();
    fprintf(stderr, "released keyboard\n");
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: atlas-desk-keys /dev/input/eventN seat.sock\n");
        return 2;
    }
    sockpath = argv[2];
    devfd = open(argv[1], O_RDWR | O_CLOEXEC);
    can_inject = 1;
    if (devfd < 0) {
        can_inject = 0;
        devfd = open(argv[1], O_RDONLY | O_CLOEXEC);
    }
    if (devfd < 0) {
        perror("open");
        return 1;
    }
    if (!take_lock()) {
        close(devfd);
        devfd = -1;
        return 0;
    }
    /* Android still owns the node until the grab. Clear a stuck Alt/Sym/Shift. */
    release_android_mods();
    drain_dev();
    if (desk_wants_grab()) {
        if (!try_grab())
            fprintf(stderr, "grab waiting\n");
    } else {
        fprintf(stderr, "focus absent, keyboard free\n");
    }
    signal(SIGTERM, on_sig);
    signal(SIGINT, on_sig);
    signal(SIGCHLD, SIG_IGN);
    release_seat();
    keys_held = 0;
    write_held();
    fprintf(stderr, "keys %s sizeof_ev=%zu inject=%d\n",
            argv[1], sizeof(struct input_event), can_inject);
    while (!stop) {
        struct input_event ev;
        struct pollfd pw;
        ssize_t n;
        uint32_t mapped;
        int android;
        int want = desk_wants_grab();
        /* Shade, lock, or another app. The phone owns the keyboard. */
        if (!want) {
            if (grabbed) drop_grab();
            pw.fd = devfd;
            pw.events = POLLIN;
            pw.revents = 0;
            if (poll(&pw, 1, 100) > 0) drain_dev();
            continue;
        }
        /* Without the grab Android and this process both see the key.
         * Java then pushes a second code (AltGr or a stray Enter). */
        if (!grabbed) {
            pw.fd = devfd;
            pw.events = POLLIN;
            pw.revents = 0;
            if (poll(&pw, 1, 100) > 0) drain_dev();
            if (desk_wants_grab() && try_grab()) {
                drain_dev();
                release_seat();
                fprintf(stderr, "focus held, keyboard grabbed\n");
            }
            continue;
        }
        pw.fd = devfd;
        pw.events = POLLIN;
        pw.revents = 0;
        if (poll(&pw, 1, 100) <= 0) continue;
        n = read(devfd, &ev, sizeof(ev));
        if (n < 0 && errno == EINTR) continue;
        if (n != (ssize_t)sizeof(ev)) break;
        if (ev.type != EV_KEY || ev.value == 2) continue;
        /* Scan 580 is the Home key. Short and hold come from Controls. */
        if (ev.code == 580) {
            if (ev.value == 1) home_down_ms = mono_ms();
            else if (ev.value == 0) {
                long long held = home_down_ms ? mono_ms() - home_down_ms : 0;
                home_down_ms = 0;
                if (held < 0 || held > 5000) held = 0;
                fire_home(held >= 700);
            }
            continue;
        }
        /* Back returns from this screen. Do not feed it to KDE. */
        if (ev.code == 158) {
            if (ev.value == 0) fire_leave();
            continue;
        }
        android = android_key(ev.code);
        if (android) {
            if (ev.value == 1) reinject(android);
            continue;
        }
        if (ev.code >= KEY_TRACK) continue;
        /* Sym arms the printed layer. It is not a key on the host. */
        if (ev.code == 222 || ev.code == 253) {
            if (ev.value == 1) sym_mask |= (ev.code == 222) ? 1u : 2u;
            else if (ev.value == 0) sym_mask &= (ev.code == 222) ? ~1u : ~2u;
            continue;
        }
        mapped = seat_code(ev.code);
        if (sym_mask) {
            int sh = 0, lk = 0;
            if (specials_layer(ev.code, &sh, &lk) && lk > 0) {
                mapped = (uint32_t)lk;
                if (ev.value == 1 && sh && !phys_down[42] && !phys_down[54])
                    synth_shift[ev.code] = 1;
            }
        }
        if (ev.value == 1) {
            clear_stale_seat(ev.code);
            if (!phys_down[ev.code] && !is_mod_scan(ev.code)) {
                keys_held++;
                write_held();
            }
            phys_down[ev.code] = 1;
            mapped_key[ev.code] = (unsigned short)mapped;
            if (synth_shift[ev.code]) send_key(42, 1);
            send_key(mapped, 1);
        } else if (ev.value == 0) {
            unsigned short up = mapped_key[ev.code] ? mapped_key[ev.code]
                                                    : (unsigned short)mapped;
            if (phys_down[ev.code] && !is_mod_scan(ev.code)) {
                if (keys_held > 0) keys_held--;
                write_held();
            }
            phys_down[ev.code] = 0;
            mapped_key[ev.code] = 0;
            send_key(up, 0);
            if (synth_shift[ev.code]) {
                synth_shift[ev.code] = 0;
                send_key(42, 0);
            }
        }
    }
    release_seat();
    release_dev();
    return 0;
}
