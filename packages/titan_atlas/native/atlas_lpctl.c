/*
 * atlas-lpctl — mount super logical partition atlas_linux (Debian root).
 *
 * Product:
 *   Debian root  → super LP atlas_linux_a  (survives userdata wipe)
 *   Linux home   → /data/local/atlas-home  (wiped with Android)
 *
 * No network. No containers. No second super home LP.
 *
 * Usage:
 *   atlas-lpctl status
 *   atlas-lpctl path
 *   atlas-lpctl mount
 *   atlas-lpctl umount
 *   atlas-lpctl home-ensure   # mkdir HOME for user atlas under /data
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#ifndef ATLAS_VERSION
#define ATLAS_VERSION "1.0.1-wipe-home"
#endif

/* Mapper names (lpmake) — prefer _a slot. */
static const char *k_dev_candidates[] = {
    "/dev/block/mapper/atlas_linux_a",
    "/dev/block/mapper/atlas_linux",
    "/dev/block/by-name/atlas_linux_a",
    "/dev/block/by-name/atlas_linux",
    NULL,
};

#define MNT_DEFAULT   "/data/local/atlas-linux"
#define HOME_ROOT     "/data/local/atlas-home"
#define HOME_USER     HOME_ROOT "/atlas"
/* LAW: privilege auth plane lives ON the super LP so it survives userdata wipe.
 * App + Deb clients share this path (bind into merge as /var/lib/atlas-auth). */
#define AUTH_ON_LP    MNT_DEFAULT "/var/lib/atlas-auth"
#define AUTH_IN_DEB   "/var/lib/atlas-auth"
#define FS_TYPE       "ext4"

static void die(int code, const char *msg) {
    fprintf(stderr, "atlas-lpctl: %s\n", msg);
    if (errno) fprintf(stderr, "atlas-lpctl: errno=%d %s\n", errno, strerror(errno));
    exit(code);
}

static int is_blk(const char *p) {
    struct stat st;
    if (stat(p, &st) != 0) return 0;
    return S_ISBLK(st.st_mode);
}

static int is_dir(const char *p) {
    struct stat st;
    if (stat(p, &st) != 0) return 0;
    return S_ISDIR(st.st_mode);
}

static int is_mounted_on(const char *mnt) {
    FILE *f = fopen("/proc/mounts", "r");
    if (!f) return 0;
    char line[512];
    size_t n = strlen(mnt);
    int hit = 0;
    while (fgets(line, sizeof(line), f)) {
        /* source mntpoint fstype … */
        char *sp = strchr(line, ' ');
        if (!sp) continue;
        char *mp = sp + 1;
        char *sp2 = strchr(mp, ' ');
        if (!sp2) continue;
        *sp2 = '\0';
        if (strcmp(mp, mnt) == 0) {
            hit = 1;
            break;
        }
    }
    fclose(f);
    return hit;
}

static const char *find_dev(void) {
    for (int i = 0; k_dev_candidates[i]; i++) {
        if (is_blk(k_dev_candidates[i])) return k_dev_candidates[i];
    }
    return NULL;
}

/* Boot race: super mapper nodes appear after fs_mgr finishes (post wipe cold boot). */
static const char *wait_dev(int max_sec) {
    const char *dev = find_dev();
    if (dev) return dev;
    if (max_sec < 0) max_sec = 0;
    for (int t = 0; t < max_sec; t++) {
        sleep(1);
        dev = find_dev();
        if (dev) {
            fprintf(stderr, "atlas-lpctl: mapper ready after %ds (%s)\n", t + 1, dev);
            return dev;
        }
    }
    return NULL;
}

/* Always force mode when the dir already exists.
 * Wipe/first-boot heresy: mkdir once as root → 0700, then admin drop cannot
 * traverse /data/local/atlas-home. Post-flash chmod tips are banned — boot peels
 * must leave a correct plane. */
static int ensure_dir(const char *path, mode_t mode) {
    if (!is_dir(path)) {
        if (mkdir(path, mode) != 0 && errno != EEXIST) return -1;
        if (!is_dir(path)) return -1;
    }
    (void)chmod(path, mode);
    return 0;
}

static uid_t pkg_uid(void) {
    struct stat st;
    if (stat("/data/data/com.titanus2.atlas", &st) == 0 && st.st_uid > 0)
        return st.st_uid;
    if (stat("/data/user/0/com.titanus2.atlas", &st) == 0 && st.st_uid > 0)
        return st.st_uid;
    return 0;
}

static int cmd_mount(void); /* forward — auth-ensure may mount first */

static int cmd_path(void) {
    const char *d = find_dev();
    if (!d) {
        printf("dev=\n");
        printf("present=0\n");
        return 1;
    }
    printf("dev=%s\n", d);
    printf("present=1\n");
    return 0;
}

static int cmd_status(void) {
    const char *d = find_dev();
    printf("key=atlas-lpctl\n");
    printf("version=%s\n", ATLAS_VERSION);
    printf("model=debian_super_lp+home_on_data\n");
    printf("dev=%s\n", d ? d : "");
    printf("present=%d\n", d ? 1 : 0);
    printf("mnt=%s\n", MNT_DEFAULT);
    printf("mounted=%d\n", is_mounted_on(MNT_DEFAULT) ? 1 : 0);
    printf("home=%s\n", HOME_USER);
    printf("home_exists=%d\n", is_dir(HOME_USER) ? 1 : 0);
    printf("auth_on_lp=%s\n", AUTH_ON_LP);
    printf("auth_in_deb=%s\n", AUTH_IN_DEB);
    printf("auth_exists=%d\n", is_dir(AUTH_ON_LP) ? 1 : 0);
    printf("auth_wipe=survives_userdata_wipe\n");
    if (is_mounted_on(MNT_DEFAULT)) {
        /* cheap identity probe */
        if (access(MNT_DEFAULT "/etc/os-release", R_OK) == 0)
            printf("os_release=1\n");
        else if (access(MNT_DEFAULT "/etc/debian_version", R_OK) == 0)
            printf("debian_version=1\n");
        else
            printf("os_release=0\n");
        if (access(MNT_DEFAULT "/bin/bash", X_OK) == 0 ||
            access(MNT_DEFAULT "/usr/bin/bash", X_OK) == 0)
            printf("bash=1\n");
        else
            printf("bash=0\n");
    }
    printf("wipe_policy=home_with_android_data; debian+auth_on_super_survives\n");
    return d ? 0 : 2;
}

static int cmd_home_ensure(void) {
    if (ensure_dir("/data/local", 0755) != 0) die(1, "mkdir /data/local");
    /* Hybrid mount parent must be traversable (often lands 0700 root after wipe). */
    (void)ensure_dir("/data/local/atlas-hybrid", 0755);
    if (ensure_dir(HOME_ROOT, 0755) != 0) die(1, "mkdir atlas-home");
    if (ensure_dir(HOME_USER, 0755) != 0) die(1, "mkdir atlas home");
    /* skeleton */
    ensure_dir(HOME_USER "/.local", 0755);
    ensure_dir(HOME_USER "/.local/bin", 0755);
    ensure_dir(HOME_USER "/reports", 0755);
    /* Force modes every boot — ensure_dir alone used to skip existing 0700. */
    (void)chmod(HOME_ROOT, 0755);
    (void)chmod(HOME_USER, 0755);
    uid_t u = pkg_uid();
    if (u > 0) {
        /* Parent stays root-owned 0755 (traverse); user tree owned by Atlas uid. */
        (void)chown(HOME_USER, u, u);
        (void)chown(HOME_USER "/.local", u, u);
        (void)chown(HOME_USER "/.local/bin", u, u);
        (void)chown(HOME_USER "/reports", u, u);
    }
    printf("home=%s\n", HOME_USER);
    printf("home_uid=%u\n", (unsigned)u);
    printf("home_mode=0755\n");
    return 0;
}

/* LAW: auth plane on super LP only — never app CE, never /data/local/tmp. */
static int cmd_auth_ensure(void) {
    if (!is_mounted_on(MNT_DEFAULT)) {
        /* mount first so auth lives on LP blocks */
        if (cmd_mount() != 0) {
            fprintf(stderr, "atlas-lpctl: auth-ensure needs atlas_linux mounted\n");
            return 1;
        }
    }
    if (ensure_dir(MNT_DEFAULT "/var", 0755) != 0) die(1, "mkdir var");
    if (ensure_dir(MNT_DEFAULT "/var/lib", 0755) != 0) die(1, "mkdir var/lib");
    /* 0777: app UID + Deb admin both R/W req/ok/fail/ticket (same as product ticket). */
    if (ensure_dir(AUTH_ON_LP, 0777) != 0) die(1, "mkdir atlas-auth on LP");
    chmod(AUTH_ON_LP, 0777);
    /* also expose in-tree path used when merge is LP bind */
    ensure_dir(MNT_DEFAULT "/var/lib/atlas", 0755);
    printf("auth_dir=%s\n", AUTH_ON_LP);
    printf("auth_in_deb=%s\n", AUTH_IN_DEB);
    printf("wipe=survives\n");
    return 0;
}

static int cmd_mount(void) {
    /* Default wait: boot oneshot often races fs_mgr (lab post-wipe 2026-08-13). */
    int wait_s = 45;
    const char *we = getenv("ATLAS_LPCTL_WAIT_S");
    if (we && *we) wait_s = atoi(we);

    const char *dev = wait_dev(wait_s);
    if (!dev) die(2, "atlas_linux LP not found (pack WITH_ATLAS_LP=1)");

    if (ensure_dir("/data/local", 0755) != 0) die(1, "mkdir /data/local");
    if (ensure_dir(MNT_DEFAULT, 0755) != 0) die(1, "mkdir mnt");

    if (is_mounted_on(MNT_DEFAULT)) {
        printf("already_mounted=1 mnt=%s dev=%s\n", MNT_DEFAULT, dev);
        cmd_home_ensure();
        /* do not recurse through cmd_auth_ensure (would re-enter mount) */
        ensure_dir(MNT_DEFAULT "/var", 0755);
        ensure_dir(MNT_DEFAULT "/var/lib", 0755);
        ensure_dir(AUTH_ON_LP, 0777);
        chmod(AUTH_ON_LP, 0777);
        printf("auth_dir=%s\n", AUTH_ON_LP);
        return 0;
    }

    /*
     * Prefer noatime. Do not force ro — bare Deb needs apt/state on root
     * unless overlay upper is used; product may later split upper to /data.
     */
    if (mount(dev, MNT_DEFAULT, FS_TYPE, MS_NOATIME, "") != 0) {
        /* retry without flags; one short sleep for dm settle */
        usleep(200000);
        if (mount(dev, MNT_DEFAULT, FS_TYPE, 0, "") != 0)
            die(1, "mount atlas_linux failed");
    }

    printf("mounted=1 mnt=%s dev=%s\n", MNT_DEFAULT, dev);
    cmd_home_ensure();
    ensure_dir(MNT_DEFAULT "/var", 0755);
    ensure_dir(MNT_DEFAULT "/var/lib", 0755);
    ensure_dir(AUTH_ON_LP, 0777);
    chmod(AUTH_ON_LP, 0777);
    printf("auth_dir=%s\n", AUTH_ON_LP);
    return 0;
}

static int cmd_umount(void) {
    if (!is_mounted_on(MNT_DEFAULT)) {
        printf("mounted=0\n");
        return 0;
    }
    if (umount2(MNT_DEFAULT, MNT_DETACH) != 0 && umount(MNT_DEFAULT) != 0)
        die(1, "umount failed");
    printf("mounted=0\n");
    return 0;
}

static void usage(void) {
    fprintf(stderr,
            "atlas-lpctl %s — Debian super LP + home on /data\n"
            "  status | path | mount | umount | home-ensure | auth-ensure\n"
            "  mnt=%s  home=%s  auth=%s (survives wipe)\n",
            ATLAS_VERSION, MNT_DEFAULT, HOME_USER, AUTH_ON_LP);
}

int main(int argc, char **argv) {
    if (argc < 2) {
        usage();
        return 2;
    }
    const char *cmd = argv[1];
    if (!strcmp(cmd, "-h") || !strcmp(cmd, "--help") || !strcmp(cmd, "help")) {
        usage();
        return 0;
    }
    if (!strcmp(cmd, "status")) return cmd_status();
    if (!strcmp(cmd, "path")) return cmd_path();
    if (!strcmp(cmd, "mount")) return cmd_mount();
    if (!strcmp(cmd, "umount") || !strcmp(cmd, "unmount")) return cmd_umount();
    if (!strcmp(cmd, "home-ensure") || !strcmp(cmd, "home")) return cmd_home_ensure();
    if (!strcmp(cmd, "auth-ensure") || !strcmp(cmd, "auth")) return cmd_auth_ensure();
    usage();
    return 2;
}
