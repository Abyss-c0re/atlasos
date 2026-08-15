/*
 * atlas-android — one wrap for every Android host binary.
 *
 * Invoked as:
 *   atlas-android <name|path> [args]
 *   android <name|path> [args]     (symlink)
 *   screencap [args]               (same-name symlink; argv0)
 *
 * Discovers bins by scanning Android bin dirs — no hardcoded tool list.
 * Never execs the Bionic ELF in the Debian mount ns (empty binderfs).
 * Auth: plane files + atlas-auth when bio is on; a valid LP ticket skips.
 * Exec: nsenter -t 1 if permitted, else enterd ELEVATE chroot=0 (init ns).
 */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#ifndef ATLAS_VERSION
#define ATLAS_VERSION "1.1.0-wrap"
#endif

static const char *BIN_DIRS[] = {
    "/system/bin", "/system/xbin", "/system_ext/bin",
    "/product/bin", "/vendor/bin", NULL};

static const char *SOCKS[] = {
    "/data/local/tmp/atlas-enter.sock",
    "/dev/socket/atlas-enter",
    NULL};

static const char *AUTH_BINS[] = {
    "/system/bin/atlas-auth",
    "/usr/local/bin/atlas-auth",
    "/bin/atlas-auth",
    NULL};

static const char *TICKETS[] = {
    "/var/lib/atlas-auth/ticket",
    "/data/local/atlas-linux/var/lib/atlas-auth/ticket",
    "/data/local/tmp/atlas_auth.ticket",
    NULL};

static int blocked_name(const char *n) {
  static const char *b[] = {
      "su", "sudo", "doas", "pkexec",
      "apt", "apt-get", "apt-cache", "dpkg",
      "atlas-android", "android", "android-exec", "android-run",
      NULL};
  for (int i = 0; b[i]; i++)
    if (strcmp(n, b[i]) == 0) return 1;
  return 0;
}

static int plane_on(const char **paths) {
  char buf[16];
  for (int i = 0; paths[i]; i++) {
    FILE *f = fopen(paths[i], "r");
    if (!f) continue;
    memset(buf, 0, sizeof buf);
    if (!fgets(buf, sizeof buf, f)) {
      fclose(f);
      continue;
    }
    fclose(f);
    if (buf[0] == '0') return 0;
    if (buf[0] == '1' || buf[0] == 't' || buf[0] == 'T' || buf[0] == 'y' ||
        buf[0] == 'Y')
      return 1;
  }
  return -1; /* unset */
}

static int priv_ok(void) {
  const char *e = getenv("ATLAS_PRIV_ANDROID_ACCESS");
  if (e && (e[0] == '0')) return 0;
  if (e && (e[0] == '1')) return 1;
  static const char *p[] = {
      "/var/lib/atlas-auth/titan2_atlas_priv_android_access",
      "/data/misc/titan2/titan2_atlas_priv_android_access",
      "/data/local/tmp/titan2_atlas_priv_android_access", NULL};
  int v = plane_on(p);
  return v != 0;
}

static int bio_want(void) {
  const char *e = getenv("ATLAS_ANDROID_AUTH");
  if (e && (e[0] == '1' || e[0] == 't' || e[0] == 'T')) return 1;
  static const char *p[] = {
      "/var/lib/atlas-auth/titan2_atlas_android_auth",
      "/data/misc/titan2/titan2_atlas_android_auth",
      "/data/local/tmp/titan2_atlas_android_auth",
      "/var/lib/atlas-auth/titan2_atlas_bio_android_access",
      "/data/misc/titan2/titan2_atlas_bio_android_access", NULL};
  return plane_on(p) == 1;
}

static int sandbox_on(void) {
  static const char *p[] = {
      "/var/lib/atlas-auth/titan2_atlas_seat_sandbox",
      "/data/misc/titan2/titan2_atlas_seat_sandbox",
      "/data/local/tmp/titan2_atlas_seat_sandbox", NULL};
  return plane_on(p) == 1;
}

/* Ticket must be "expiry ttl" (two fields). A lone epoch (forged date +%s) is invalid. */
static int ticket_ok(void) {
  long now = (long)time(NULL);
  for (int i = 0; TICKETS[i]; i++) {
    FILE *f = fopen(TICKETS[i], "r");
    if (!f) continue;
    long exp = 0;
    int ttl = 0;
    int n = fscanf(f, "%ld %d", &exp, &ttl);
    fclose(f);
    if (n < 2 || ttl <= 0) continue;
    if (exp > now && exp <= now + ttl + 5) return 1;
  }
  return 0;
}

static int request_auth(const char *name) {
  if (ticket_ok()) return 0;
  if (!bio_want()) return 0;
  const char *auth = NULL;
  for (int i = 0; AUTH_BINS[i]; i++) {
    if (access(AUTH_BINS[i], X_OK) == 0) {
      auth = AUTH_BINS[i];
      break;
    }
  }
  if (!auth) return 0;
  char reason[128];
  snprintf(reason, sizeof reason, "android %s", name);
  pid_t p = fork();
  if (p < 0) return -1;
  if (p == 0) {
    execl(auth, auth, "request", reason, (char *)NULL);
    _exit(127);
  }
  int st = 0;
  waitpid(p, &st, 0);
  return WIFEXITED(st) ? WEXITSTATUS(st) : 1;
}

static int resolve_bin(const char *name, char *out, size_t n) {
  if (!name || !name[0]) return -1;
  if (name[0] == '/') {
    if (access(name, X_OK) != 0) return -1;
    if (strncmp(name, "/system/", 8) && strncmp(name, "/vendor/", 8) &&
        strncmp(name, "/product/", 9) && strncmp(name, "/apex/", 6) &&
        strncmp(name, "/system_ext/", 12))
      return -1;
    snprintf(out, n, "%s", name);
    return 0;
  }
  for (int i = 0; BIN_DIRS[i]; i++) {
    snprintf(out, n, "%s/%s", BIN_DIRS[i], name);
    if (access(out, X_OK) == 0) return 0;
  }
  return -1;
}

static void rewrite_home(char *arg, size_t n) {
  const char *home = getenv("HOME");
  const char *real = "/data/local/atlas-home/atlas";
  if (!arg) return;
  if (strncmp(arg, "/home/atlas/", 12) == 0) {
    char tmp[512];
    snprintf(tmp, sizeof tmp, "%s/%s", real, arg + 12);
    snprintf(arg, n, "%s", tmp);
    return;
  }
  if (home && home[0] == '/' && strncmp(arg, home, strlen(home)) == 0 &&
      (arg[strlen(home)] == '/' || arg[strlen(home)] == 0) &&
      strncmp(home, "/data/", 6) != 0) {
    char tmp[512];
    snprintf(tmp, sizeof tmp, "%s%s", real, arg + strlen(home));
    snprintf(arg, n, "%s", tmp);
  }
}

static int shell_quote(char *dst, size_t n, const char *s) {
  size_t o = 0;
  if (o + 1 >= n) return -1;
  dst[o++] = '\'';
  for (; *s; s++) {
    if (*s == '\'') {
      if (o + 4 >= n) return -1;
      dst[o++] = '\'';
      dst[o++] = '\\';
      dst[o++] = '\'';
      dst[o++] = '\'';
    } else {
      if (o + 1 >= n) return -1;
      dst[o++] = *s;
    }
  }
  if (o + 1 >= n) return -1;
  dst[o++] = '\'';
  dst[o] = 0;
  return 0;
}

static int connect_enterd(void) {
  for (int i = 0; SOCKS[i]; i++) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) continue;
    struct sockaddr_un a;
    memset(&a, 0, sizeof a);
    a.sun_family = AF_UNIX;
    snprintf(a.sun_path, sizeof a.sun_path, "%s", SOCKS[i]);
    if (connect(fd, (struct sockaddr *)&a, sizeof a) == 0) return fd;
    close(fd);
  }
  /* abstract @atlasenter */
  int fd = socket(AF_UNIX, SOCK_STREAM, 0);
  if (fd < 0) return -1;
  struct sockaddr_un a;
  memset(&a, 0, sizeof a);
  a.sun_family = AF_UNIX;
  a.sun_path[0] = 0;
  memcpy(a.sun_path + 1, "atlasenter", 10);
  if (connect(fd, (struct sockaddr *)&a, sizeof(sa_family_t) + 1 + 10) == 0)
    return fd;
  close(fd);
  return -1;
}

static int elevate_run(char **argv) {
  char cmd[4096];
  size_t o = 0;
  cmd[0] = 0;
  for (int i = 0; argv[i]; i++) {
    char q[768];
    char arg[512];
    snprintf(arg, sizeof arg, "%s", argv[i]);
    rewrite_home(arg, sizeof arg);
    if (shell_quote(q, sizeof q, arg) != 0) return 2;
    size_t ql = strlen(q);
    if (o + ql + 2 >= sizeof cmd) return 2;
    if (o) cmd[o++] = ' ';
    memcpy(cmd + o, q, ql);
    o += ql;
    cmd[o] = 0;
  }
  int fd = connect_enterd();
  if (fd < 0) {
    fprintf(stderr, "atlas-android: enterd not listening\n");
    return 4;
  }
  char hdr[] = "ELEVATE chroot=0\n";
  if (write(fd, hdr, sizeof hdr - 1) != (ssize_t)(sizeof hdr - 1)) {
    close(fd);
    return 4;
  }
  if (write(fd, cmd, strlen(cmd)) < 0 || write(fd, "\n", 1) != 1) {
    close(fd);
    return 4;
  }
  char buf[4096];
  size_t have = 0;
  int code = 0;
  int saw_ok = 0;
  for (;;) {
    ssize_t n = read(fd, buf + have, sizeof(buf) - have - 1);
    if (n <= 0) break;
    have += (size_t)n;
    buf[have] = 0;
    if (!saw_ok) {
      char *nl = strchr(buf, '\n');
      if (!nl) continue;
      *nl = 0;
      if (strncmp(buf, "ERR", 3) == 0) {
        fprintf(stderr, "atlas-android: %s\n", buf);
        close(fd);
        return buf[4] == 'n' ? 3 : 1; /* need-auth-ticket → 3 */
      }
      if (strcmp(buf, "OK") != 0) {
        fprintf(stderr, "atlas-android: %s\n", buf);
        close(fd);
        return 4;
      }
      saw_ok = 1;
      size_t rest = have - (size_t)(nl + 1 - buf);
      memmove(buf, nl + 1, rest);
      have = rest;
      buf[have] = 0;
    }
    char *mark = strstr(buf, "\n__ATLAS_EXIT__ ");
    if (!mark) mark = strstr(buf, "__ATLAS_EXIT__ ");
    if (mark) {
      char *p = strstr(mark, "__ATLAS_EXIT__ ");
      if (p) {
        code = atoi(p + 15);
        size_t keep = (size_t)(mark - buf);
        if (keep) (void)write(1, buf, keep);
      }
      have = 0;
      break;
    }
    if (have > 64) {
      size_t flush = have - 32;
      (void)write(1, buf, flush);
      memmove(buf, buf + flush, have - flush);
      have -= flush;
    }
  }
  if (have) (void)write(1, buf, have);
  close(fd);
  return code;
}

static int nsenter_ok(void) {
  if (access("/system/bin/nsenter", X_OK) != 0) return 0;
  pid_t p = fork();
  if (p < 0) return 0;
  if (p == 0) {
    int dn = open("/dev/null", O_RDWR);
    if (dn >= 0) {
      dup2(dn, 1);
      dup2(dn, 2);
      if (dn > 2) close(dn);
    }
    execl("/system/bin/nsenter", "nsenter", "-t", "1", "-m", "--",
          "/system/bin/true", (char *)NULL);
    _exit(127);
  }
  int st = 0;
  waitpid(p, &st, 0);
  return WIFEXITED(st) && WEXITSTATUS(st) == 0;
}

static int file_has(const char *path) {
  return access(path, F_OK) == 0;
}

static void print_status(void) {
  int deb = file_has("/etc/debian_version");
  int lp = file_has("/dev/block/mapper/atlas_linux_a")
           || file_has("/data/local/atlas-linux/etc/debian_version");
  int wrap = file_has("/usr/local/libexec/atlas-android")
             || file_has("/system/bin/atlas-android")
             || file_has("/data/local/atlas-linux/usr/local/libexec/atlas-android")
             || file_has("/data/data/com.titanus2.atlas/files/bin/atlas-android");
  int binder = 0;
  struct stat st;
  if (stat("/dev/binderfs/binder", &st) == 0 && S_ISCHR(st.st_mode)) binder = 1;
  else if (stat("/dev/binder", &st) == 0 && S_ISCHR(st.st_mode)) binder = 1;
  printf("plane=%s\n", deb ? "hybrid" : "android");
  printf("storage=%s\n", lp ? "lp" : (deb ? "unknown" : "android"));
  printf("overlay=0\n");
  printf("binder=%s\n", binder ? "present" : "missing");
  printf("android_ipc=%s\n", wrap ? "atlas-android-wrap" : "none");
  printf("wrap=%s\n", wrap ? "atlas-android" : "missing");
  printf("uid=%d\n", (int)getuid());
}

static void list_bins(void) {
  for (int i = 0; BIN_DIRS[i]; i++) {
    DIR *d = opendir(BIN_DIRS[i]);
    if (!d) continue;
    struct dirent *e;
    while ((e = readdir(d))) {
      if (e->d_name[0] == '.') continue;
      if (blocked_name(e->d_name)) continue;
      char p[512];
      snprintf(p, sizeof p, "%s/%s", BIN_DIRS[i], e->d_name);
      if (access(p, X_OK) != 0) continue;
      printf("%s\t%s\n", e->d_name, p);
    }
    closedir(d);
  }
}

int main(int argc, char **argv) {
  const char *me = strrchr(argv[0], '/');
  me = me ? me + 1 : argv[0];

  if (argc >= 2 && (!strcmp(argv[1], "--version") || !strcmp(argv[1], "version"))) {
    printf("atlas-android %s wrap+elevate\n", ATLAS_VERSION);
    return 0;
  }
  if (argc >= 2 && (!strcmp(argv[1], "--list") || !strcmp(argv[1], "list"))) {
    list_bins();
    return 0;
  }
  if (!strcmp(me, "atlas-agent-status")
      || (argc >= 2 && (!strcmp(argv[1], "status") || !strcmp(argv[1], "--status")))) {
    print_status();
    return 0;
  }
  const char *name;
  char **rest;
  if (!strcmp(me, "atlas-screencap")) {
    name = "screencap";
    rest = argv + 1;
  } else if (!strcmp(me, "atlas-android") || !strcmp(me, "android") ||
      !strcmp(me, "android-exec") || !strcmp(me, "android-run")) {
    if (argc < 2) {
      fprintf(stderr,
              "usage: %s <tool|path> [args…]\n"
              "       %s --list\n"
              "same-name symlinks (screencap, am, …) wrap Android bins\n",
              me, me);
      return 2;
    }
    name = argv[1];
    rest = argv + 2;
  } else {
    name = me;
    rest = argv + 1;
  }

  if (blocked_name(name) && name[0] != '/') {
    fprintf(stderr, "atlas-android: %s is not an Android wrap\n", name);
    return 126;
  }

  if (sandbox_on()) {
    fprintf(stderr, "atlas-android: privilege denied (sandbox)\n");
    return 1;
  }
  if (!priv_ok()) {
    fprintf(stderr, "atlas-android: privilege denied (Android access off)\n");
    return 1;
  }

  char real[512];
  if (resolve_bin(name, real, sizeof real) != 0) {
    fprintf(stderr, "atlas-android: %s: not an Android host binary\n", name);
    return 127;
  }
  const char *base = strrchr(real, '/');
  base = base ? base + 1 : real;
  if (blocked_name(base)) {
    fprintf(stderr, "atlas-android: %s is not an Android wrap\n", base);
    return 126;
  }

  int ar = request_auth(base);
  if (ar != 0) {
    fprintf(stderr, "atlas-android: biometric denied for android %s\n", base);
    return ar == 3 ? 3 : 1;
  }

  int narg = 0;
  while (rest[narg]) narg++;
  char *nargv[narg + 2];
  nargv[0] = real;
  for (int i = 0; i < narg; i++) {
    static char store[16][512];
    if (i < 16) {
      snprintf(store[i], sizeof store[i], "%s", rest[i]);
      rewrite_home(store[i], sizeof store[i]);
      nargv[i + 1] = store[i];
    } else {
      nargv[i + 1] = rest[i];
    }
  }
  nargv[narg + 1] = NULL;

  if (nsenter_ok()) {
    char *ns[narg + 16];
    int k = 0;
    ns[k++] = "/system/bin/nsenter";
    ns[k++] = "-t";
    ns[k++] = "1";
    ns[k++] = "-m";
    ns[k++] = "--";
    ns[k++] = "env";
    ns[k++] = "-i";
    ns[k++] = "PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin";
    ns[k++] = "ANDROID_DATA=/data";
    ns[k++] = "ANDROID_ROOT=/system";
    ns[k++] = "TMPDIR=/data/local/tmp";
    for (int i = 0; nargv[i]; i++) ns[k++] = nargv[i];
    ns[k] = NULL;
    execv(ns[0], ns);
  }

  return elevate_run(nargv);
}
