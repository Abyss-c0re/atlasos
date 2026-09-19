#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <dirent.h>
#include <linux/input.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

/* OEM Agui: sendMessageByIoctl(100,…) → ioctl(/dev/touch, 0x40044103, on).
 * Firmware then injects KEY_POWER on sub_touch. Grab that node while the
 * rear is dark so KEY_POWER never reaches PowerManager (would wake main).
 * On KEY_POWER: Agold type-700 backlight only. Never cmd power wakeup.
 */
#define GESTURE_DEV "/dev/touch"
#define GESTURE_REQ 0x40044103u
#define PANEL_DEV   "/dev/agold-sub-panel"
#define PANEL_REQ   0x40044203u
#define BL_A "/sys/class/leds/lcd-backlight1/brightness"
#define BL_B "/sys/devices/platform/mtk-leds1/leds/lcd-backlight1/brightness"

static volatile int g_run = 1;
static void on_sig(int s) { (void)s; g_run = 0; }

static int write_int_path(const char *path, int v) {
    int fd = open(path, O_WRONLY);
    if (fd < 0) return -1;
    char buf[16];
    int n = snprintf(buf, sizeof(buf), "%d\n", v);
    int w = write(fd, buf, n);
    close(fd);
    return w == n ? 0 : -1;
}

static int ioctl_int(const char *path, unsigned long req, int v) {
    int fd = open(path, O_RDWR);
    if (fd < 0) fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    int arg = v;
    int r = ioctl(fd, req, &arg);
    close(fd);
    return r;
}

static int light_rear(void) {
    ioctl_int(PANEL_DEV, PANEL_REQ, 1);
    write_int_path(BL_A, 255);
    write_int_path(BL_B, 255);
    return 0;
}

static int find_sub_touch(char *out, size_t n) {
    DIR *d = opendir("/sys/class/input");
    if (!d) return -1;
    struct dirent *e;
    int ok = -1;
    while ((e = readdir(d)) != NULL) {
        if (strncmp(e->d_name, "input", 5) != 0) continue;
        char namep[128], name[64];
        snprintf(namep, sizeof(namep), "/sys/class/input/%s/name", e->d_name);
        FILE *f = fopen(namep, "r");
        if (!f) continue;
        if (!fgets(name, sizeof(name), f)) { fclose(f); continue; }
        fclose(f);
        if (strncmp(name, "sub_touch", 9) != 0) continue;
        char sysdir[160];
        snprintf(sysdir, sizeof(sysdir), "/sys/class/input/%s", e->d_name);
        DIR *ed = opendir(sysdir);
        if (!ed) continue;
        struct dirent *ee;
        while ((ee = readdir(ed)) != NULL) {
            if (strncmp(ee->d_name, "event", 5) != 0) continue;
            snprintf(out, n, "/dev/input/%s", ee->d_name);
            ok = 0;
            break;
        }
        closedir(ed);
        if (ok == 0) break;
    }
    closedir(d);
    return ok;
}

int main(int argc, char **argv) {
    if (argc > 1 && strcmp(argv[1], "enable") == 0) {
        int r = ioctl_int(GESTURE_DEV, GESTURE_REQ, 1);
        fprintf(stderr, "gesture ioctl on r=%d errno=%d\n", r, errno);
        return r < 0 ? 1 : 0;
    }
    if (argc > 1 && strcmp(argv[1], "disable") == 0) {
        ioctl_int(GESTURE_DEV, GESTURE_REQ, 0);
        return 0;
    }

    signal(SIGTERM, on_sig);
    signal(SIGINT, on_sig);
    ioctl_int(GESTURE_DEV, GESTURE_REQ, 1);

    char evpath[64];
    if (find_sub_touch(evpath, sizeof(evpath)) != 0) {
        fprintf(stderr, "no sub_touch event node\n");
        return 1;
    }
    int fd = open(evpath, O_RDONLY);
    if (fd < 0) { perror(evpath); return 1; }
    int grab = 1;
    if (ioctl(fd, EVIOCGRAB, &grab) != 0) {
        fprintf(stderr, "grab %s failed errno=%d (events still read)\n", evpath, errno);
    }
    fprintf(stderr, "watching %s for KEY_POWER\n", evpath);

    struct input_event ev;
    while (g_run) {
        ssize_t n = read(fd, &ev, sizeof(ev));
        if (n != (ssize_t)sizeof(ev)) {
            if (!g_run) break;
            if (errno == EINTR) continue;
            break;
        }
        if (ev.type == EV_KEY && ev.value == 1 && ev.code == KEY_POWER) {
            light_rear();
        }
    }
    grab = 0;
    ioctl(fd, EVIOCGRAB, &grab);
    close(fd);
    return 0;
}
