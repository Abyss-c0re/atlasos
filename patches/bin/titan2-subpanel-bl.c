#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <errno.h>
#include <string.h>

#define PATH "/dev/agold-sub-panel"
/* OEM type 700: _IOW(0x42, 3, int) */
#define REQ 0x40044203u

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <0|1> [hold_ms]\n", argv[0]);
        return 2;
    }
    int en = atoi(argv[1]) ? 1 : 0;
    int hold = argc > 2 ? atoi(argv[2]) : 0;
    int fd = open(PATH, O_RDWR);
    if (fd < 0) fd = open(PATH, O_RDONLY);
    if (fd < 0) { perror("open"); return 1; }
    int arg = en;
    int r = ioctl(fd, REQ, &arg);
    printf("ioctl en=%d r=%d errno=%d\n", en, r, errno);
    close(fd);

    /* try both paths for brightness */
    const char *paths[] = {
        "/sys/class/leds/lcd-backlight1/brightness",
        "/sys/devices/platform/mtk-leds1/leds/lcd-backlight1/brightness",
        NULL
    };
    for (int i = 0; paths[i]; i++) {
        int bfd = open(paths[i], O_WRONLY);
        if (bfd < 0) { printf("open %s fail %d\n", paths[i], errno); continue; }
        char buf[16];
        int n = snprintf(buf, sizeof(buf), "%d\n", en ? 255 : 0);
        int w = write(bfd, buf, n);
        printf("write %s -> %d bytes r=%d errno=%d\n", paths[i], n, w, errno);
        close(bfd);
    }
    if (hold > 0) usleep(hold * 1000L);
    return r < 0 ? 1 : 0;
}
