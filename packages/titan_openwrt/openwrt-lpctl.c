/*
 * openwrt-lpctl — mount super LP atlas_openwrt (official OpenWrt root).
 * Sibling of atlas-lpctl / atlas_linux. Wipe-survives. No containers.
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <unistd.h>

#ifndef ATLAS_VERSION
#define ATLAS_VERSION "1.0.0-openwrt-lp"
#endif

static const char *k_dev[] = {
    "/dev/block/mapper/atlas_openwrt_a",
    "/dev/block/mapper/atlas_openwrt",
    "/dev/block/by-name/atlas_openwrt_a",
    "/dev/block/by-name/atlas_openwrt",
    NULL,
};
#define MNT "/data/local/atlas-openwrt"
#define FS "ext4"

static int is_blk(const char *p) {
    struct stat st;
    return stat(p, &st) == 0 && S_ISBLK(st.st_mode);
}
static int is_dir(const char *p) {
    struct stat st;
    return stat(p, &st) == 0 && S_ISDIR(st.st_mode);
}
static int mounted(void) {
    FILE *f = fopen("/proc/mounts", "r");
    if (!f) return 0;
    char line[512];
    int hit = 0;
    while (fgets(line, sizeof line, f)) {
        char *sp = strchr(line, ' ');
        if (!sp) continue;
        char *mp = sp + 1;
        char *sp2 = strchr(mp, ' ');
        if (!sp2) continue;
        *sp2 = '\0';
        if (strcmp(mp, MNT) == 0) { hit = 1; break; }
    }
    fclose(f);
    return hit;
}
static const char *find_dev(void) {
    for (int i = 0; k_dev[i]; i++)
        if (is_blk(k_dev[i])) return k_dev[i];
    return NULL;
}
static int ensure_mnt(void) {
    if (!is_dir(MNT) && mkdir(MNT, 0755) != 0 && errno != EEXIST) return -1;
    chmod(MNT, 0755);
    return 0;
}

int main(int argc, char **argv) {
    const char *cmd = argc > 1 ? argv[1] : "status";
    const char *d = find_dev();
    if (!strcmp(cmd, "status") || !strcmp(cmd, "path")) {
        printf("key=openwrt-lpctl\nversion=%s\n", ATLAS_VERSION);
        printf("dev=%s\npresent=%d\nmounted=%d\nmnt=%s\n",
               d ? d : "", d ? 1 : 0, mounted(), MNT);
        return d ? 0 : 1;
    }
    if (!strcmp(cmd, "mount")) {
        if (!d) { fprintf(stderr, "openwrt-lpctl: no atlas_openwrt LP\n"); return 2; }
        if (ensure_mnt() != 0) { perror("mkdir"); return 3; }
        if (mounted()) { printf("already %s\n", MNT); return 0; }
        if (mount(d, MNT, FS, 0, "") != 0) { perror("mount"); return 4; }
        printf("mounted %s -> %s\n", d, MNT);
        return 0;
    }
    if (!strcmp(cmd, "umount")) {
        if (!mounted()) { printf("not mounted\n"); return 0; }
        if (umount(MNT) != 0) { perror("umount"); return 5; }
        printf("umounted %s\n", MNT);
        return 0;
    }
    fprintf(stderr, "usage: openwrt-lpctl status|path|mount|umount\n");
    return 2;
}
