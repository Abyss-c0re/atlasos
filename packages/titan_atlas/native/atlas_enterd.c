/*
 * atlas-enterd — init-root Deb enter daemon (product rootless, no Magisk/setuid).
 *
 * Listen order:
 *   1) TCP 127.0.0.1:17999 (tip-friendly)
 *   2) abstract unix @atlasenter  (always app-visible, no filesystem sock)
 *   3) /data/local/tmp/atlas-enter.sock
 *   4) ANDROID_SOCKET_atlasenter (init "socket" directive)
 *
 * Protocol:
 *   ENTER uid=N home=PATH ensure=0|1\n + SCM_RIGHTS PTY
 *     → chroot+drop+exec admin bash on PTY
 *   ELEVATE chroot=0|1 home=PATH\n
 *   CMD <shell command>\n
 *     → after atlas-auth ticket OK: run as root (optional Deb chroot).
 *       Product sudo path without KernelSU / setuid on nosuid trees.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#ifndef TIOCSCTTY
#define TIOCSCTTY 0x540E
#endif

#ifndef ATLAS_VERSION
#define ATLAS_VERSION "1.2.7-resume"
#endif

#define ABS_NAME "atlasenter"
#define SOCK_FS "/data/local/tmp/atlas-enter.sock"
#define MERGE_DEFAULT "/data/local/atlas-hybrid/merge"
#define HYBRID_SH "/system/bin/atlas-hybrid.sh"
#define STATUS_PATH "/data/local/tmp/atlas_hybrid.status"
#define ATLAS_PKG_DATA "/data/data/com.titanus2.atlas"
#define LINUX_HOME_HOST "/data/local/atlas-home/atlas"

/* Append ":" + dir to path if dir exists and is a directory. */
static void path_add_dir(char *path, size_t sz, const char *dir) {
  struct stat st;
  size_t n, dlen;
  if (!path || !dir || !dir[0] || sz < 8) return;
  if (stat(dir, &st) != 0 || !S_ISDIR(st.st_mode)) return;
  n = strlen(path);
  dlen = strlen(dir);
  if (n + 1 + dlen + 1 >= sz) return;
  if (n > 0) path[n++] = ':';
  memcpy(path + n, dir, dlen + 1);
}

/*
 * Universal user-install PATH: $HOME/bin, $HOME/.local/bin, and every
 * $HOME/.<name>/bin (cargo, npm-global, vendor CLIs, …). No product-specific
 * tool names — any curl|install layout under $HOME is live.
 */
static void path_add_user_installs(char *path, size_t sz, const char *home) {
  char buf[512];
  DIR *d;
  struct dirent *de;
  if (!home || !home[0]) return;
  snprintf(buf, sizeof(buf), "%s/bin", home);
  path_add_dir(path, sz, buf);
  snprintf(buf, sizeof(buf), "%s/.local/bin", home);
  path_add_dir(path, sz, buf);
  d = opendir(home);
  if (!d) return;
  while ((de = readdir(d)) != NULL) {
    if (de->d_name[0] != '.') continue;
    if (!de->d_name[1] || de->d_name[1] == '.') continue;
    /* skip .local (already) */
    if (strcmp(de->d_name, ".local") == 0) continue;
    snprintf(buf, sizeof(buf), "%s/%s/bin", home, de->d_name);
    path_add_dir(path, sz, buf);
  }
  closedir(d);
}

/* Bind Android linux home into Deb merge before chroot (survives only while mounted). */
static void ensure_home_bind(const char *merge) {
  char tgt[512];
  char tgt_parent[512];
  struct stat st;
  if (!merge || !merge[0]) return;
  if (stat(LINUX_HOME_HOST, &st) != 0 || !S_ISDIR(st.st_mode)) return;
  (void)mkdir("/data/local/atlas-home", 0755);
  (void)mkdir(LINUX_HOME_HOST, 0755);
  snprintf(tgt, sizeof(tgt), "%s/home/atlas", merge);
  snprintf(tgt_parent, sizeof(tgt_parent), "%s/home", merge);
  (void)mkdir(tgt_parent, 0755);
  (void)mkdir(tgt, 0755);
  /* already a mount? cheap check: different dev from merge root */
  {
    struct stat sm, st2;
    if (stat(merge, &sm) == 0 && stat(tgt, &st2) == 0 && sm.st_dev != st2.st_dev) {
      /* likely already bound */
    } else {
      if (mount(LINUX_HOME_HOST, tgt, NULL, MS_BIND, NULL) != 0) {
        /* ignore — may already be bound or RO */
      }
    }
  }
  /* also expose host path inside chroot for absolute ATLAS_LINUX_HOME */
  snprintf(tgt, sizeof(tgt), "%s/data/local/atlas-home", merge);
  snprintf(tgt_parent, sizeof(tgt_parent), "%s/data/local", merge);
  (void)mkdir(tgt_parent, 0755);
  (void)mkdir(tgt, 0755);
  {
    struct stat sm, st2;
    if (!(stat(merge, &sm) == 0 && stat(tgt, &st2) == 0 && sm.st_dev != st2.st_dev)) {
      (void)mount("/data/local/atlas-home", tgt, NULL, MS_BIND, NULL);
    }
  }
}
#define ATLAS_PKG_USER "/data/user/0/com.titanus2.atlas"
#define LOG_PATH "/data/local/tmp/atlas-enterd.log"

static void logf(const char *fmt, ...) {
  int fd = open(LOG_PATH, O_WRONLY | O_CREAT | O_APPEND, 0644);
  if (fd < 0) return;
  char buf[512];
  int n = snprintf(buf, sizeof(buf), "%ld ", (long)time(NULL));
  if (n > 0) (void)write(fd, buf, (size_t)n);
  /* simple message only after time */
  (void)write(fd, fmt, strlen(fmt));
  (void)write(fd, "\n", 1);
  close(fd);
  chmod(LOG_PATH, 0644);
}

static void logf2(const char *a, const char *b) {
  char m[256];
  snprintf(m, sizeof(m), "%s %s", a, b ? b : "");
  logf(m);
}

static uid_t pkg_uid(void) {
  struct stat st;
  if (stat(ATLAS_PKG_DATA, &st) == 0 && st.st_uid > 0) return st.st_uid;
  if (stat(ATLAS_PKG_USER, &st) == 0 && st.st_uid > 0) return st.st_uid;
  return 0;
}

static int caller_ok(uid_t peer) {
  if (peer == 0 || peer == 2000) return 1;
  uid_t a = pkg_uid();
  return (a > 0 && peer == a) ? 1 : 0;
}

/* True if MERGE is a mount (overlay *or* super LP bind of atlas_linux). */
static int merge_mounted(void) {
  FILE *f = fopen("/proc/mounts", "r");
  if (!f) return 0;
  char line[512];
  int ok = 0;
  while (fgets(line, sizeof(line), f)) {
    if (strstr(line, " /data/local/atlas-hybrid/merge ")) {
      ok = 1;
      break;
    }
  }
  fclose(f);
  return ok;
}

static int hybrid_ready(const char *merge) {
  char p[512];
  snprintf(p, sizeof(p), "%s/bin/bash", merge);
  if (access(p, X_OK) == 0) return 1;
  snprintf(p, sizeof(p), "%s/usr/bin/bash", merge);
  if (access(p, X_OK) == 0) return 1;
  snprintf(p, sizeof(p), "%s/bin/sh", merge);
  if (access(p, X_OK) == 0) return 1;
  /* Super LP mount path (direct) */
  if (access("/data/local/atlas-linux/bin/bash", X_OK) == 0) return 1;
  if (access("/data/local/atlas-linux/usr/bin/bash", X_OK) == 0) return 1;
  /* lower present + merge mounted — whiteout may need hybrid ensure heal */
  if (merge_mounted()) {
    if (access("/data/local/atlas-hybrid/lower/bin/bash", X_OK) == 0) return 1;
    if (access("/data/local/atlas-hybrid/lower/usr/bin/bash", X_OK) == 0) return 1;
  }
  return 0;
}

static void write_status(int ready) {
  int fd = open(STATUS_PATH, O_WRONLY | O_CREAT | O_TRUNC, 0644);
  if (fd >= 0) {
    char buf[128];
    int n = snprintf(buf, sizeof(buf),
                     "ready=%d\noverlay=%d\nenterd=1\nts=%ld\n",
                     ready, ready, (long)time(NULL));
    if (n > 0) (void)write(fd, buf, (size_t)n);
    close(fd);
    chmod(STATUS_PATH, 0644);
  }
  fd = open("/data/local/tmp/atlas_hybrid.ready", O_WRONLY | O_CREAT | O_TRUNC, 0644);
  if (fd >= 0) {
    (void)write(fd, ready ? "1\n" : "0\n", 2);
    close(fd);
    chmod("/data/local/tmp/atlas_hybrid.ready", 0644);
  }
}

static int run_ensure(void) {
  if (access(HYBRID_SH, R_OK) != 0) return -1;
  pid_t p = fork();
  if (p < 0) return -1;
  if (p == 0) {
    int dn = open("/dev/null", O_RDWR);
    if (dn >= 0) {
      dup2(dn, 0);
      dup2(dn, 1);
      dup2(dn, 2);
      if (dn > 2) close(dn);
    }
    execl("/system/bin/sh", "sh", HYBRID_SH, "ensure", (char *)NULL);
    _exit(127);
  }
  int st = 0;
  waitpid(p, &st, 0);
  return (WIFEXITED(st) && WEXITSTATUS(st) == 0) ? 0 : -1;
}

static const char *pick_shell(const char *merge) {
  char p[512];
  snprintf(p, sizeof(p), "%s/bin/bash", merge);
  if (access(p, X_OK) == 0) return "/bin/bash";
  snprintf(p, sizeof(p), "%s/usr/bin/bash", merge);
  if (access(p, X_OK) == 0) return "/usr/bin/bash";
  snprintf(p, sizeof(p), "%s/bin/dash", merge);
  if (access(p, X_OK) == 0) return "/bin/dash";
  snprintf(p, sizeof(p), "%s/bin/sh", merge);
  if (access(p, X_OK) == 0) return "/bin/sh";
  return NULL;
}

static int recv_fd(int sock) {
  struct msghdr msg;
  struct iovec iov;
  char buf[1];
  char cmsgbuf[CMSG_SPACE(sizeof(int))];
  memset(&msg, 0, sizeof(msg));
  iov.iov_base = buf;
  iov.iov_len = 1;
  msg.msg_iov = &iov;
  msg.msg_iovlen = 1;
  msg.msg_control = cmsgbuf;
  msg.msg_controllen = sizeof(cmsgbuf);
  if (recvmsg(sock, &msg, 0) < 0) return -1;
  struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
  if (!c || c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS) return -1;
  int fd = -1;
  memcpy(&fd, CMSG_DATA(c), sizeof(fd));
  return fd;
}

/* Valid atlas-auth ticket written after biometric/agent grant. */
static int auth_ticket_ok(void) {
  static const char *cands[] = {
      /* LAW: super LP auth plane (survives wipe) */
      "/data/local/atlas-linux/var/lib/atlas-auth/ticket",
      "/var/lib/atlas-auth/ticket",
      "/data/user/0/com.titanus2.atlas/files/auth/ticket",
      "/data/data/com.titanus2.atlas/files/auth/ticket",
      /* world-readable mirror for shell/chroot when CE path is SELinux-denied */
      "/data/local/tmp/atlas_auth.ticket",
      NULL};
  long now = (long)time(NULL);
  for (int i = 0; cands[i]; i++) {
    FILE *f = fopen(cands[i], "r");
    if (!f) continue;
    long exp = 0;
    int n = fscanf(f, "%ld", &exp);
    fclose(f);
    if (n == 1 && exp > now) return 1;
  }
  return 0;
}

static int read_line(int fd, char *buf, size_t cap) {
  size_t off = 0;
  while (off + 1 < cap) {
    ssize_t n = read(fd, buf + off, 1);
    if (n <= 0) {
      buf[off] = 0;
      return off > 0 ? 0 : -1;
    }
    if (buf[off] == '\n') {
      buf[off] = 0;
      return 0;
    }
    off++;
  }
  buf[cap - 1] = 0;
  return -1;
}

/* Post-auth root exec — product path without KernelSU / setuid-on-client. */
static void handle_elevate(int csock, uid_t peer, char *line) {
  /* Ticket is the real gate (post atlas-auth biometrics).
   * TCP SO_PEERCRED on Android loopback is flaky (often wrong uid) — do not
   * hard-deny on peer alone when a valid ticket is present. */
  if (!auth_ticket_ok()) {
    dprintf(csock, "ERR need-auth-ticket peer=%u\n", (unsigned)peer);
    close(csock);
    return;
  }
  if (!caller_ok(peer)) {
    /* Still allow: ticket proves Atlas agent granted recently. */
    logf2("elevate peer-odd", "ticket-ok");
  }

  int do_chroot = 1;
  char home[512];
  home[0] = 0;
  char *p = line;
  while (*p && *p != ' ') p++;
  while (*p == ' ') p++;
  while (*p) {
    char *eq = strchr(p, '=');
    char *sp = strchr(p, ' ');
    if (!eq || (sp && sp < eq)) {
      if (!sp) break;
      p = sp + 1;
      continue;
    }
    *eq = 0;
    char *val = eq + 1;
    if (sp) *sp = 0;
    if (strcmp(p, "chroot") == 0) do_chroot = (val[0] != '0');
    else if (strcmp(p, "home") == 0) snprintf(home, sizeof(home), "%s", val);
    if (!sp) break;
    p = sp + 1;
  }
  if (!home[0]) snprintf(home, sizeof(home), "/data/data/com.titanus2.atlas/files");

  char cmd[4096];
  if (read_line(csock, cmd, sizeof(cmd)) != 0 || !cmd[0]) {
    dprintf(csock, "ERR no-cmd\n");
    close(csock);
    return;
  }
  /* Strip optional "CMD " prefix */
  const char *shellcmd = cmd;
  if (strncmp(cmd, "CMD ", 4) == 0) shellcmd = cmd + 4;
  if (!shellcmd[0]) {
    dprintf(csock, "ERR empty-cmd\n");
    close(csock);
    return;
  }

  const char *merge = MERGE_DEFAULT;
  if (do_chroot) {
    if (!hybrid_ready(merge)) (void)run_ensure();
    if (!hybrid_ready(merge)) {
      dprintf(csock, "ERR hybrid-down\n");
      close(csock);
      return;
    }
  }

  logf2("elevate", shellcmd);

  int outp[2];
  if (pipe(outp) != 0) {
    dprintf(csock, "ERR pipe\n");
    close(csock);
    return;
  }

  pid_t child = fork();
  if (child < 0) {
    dprintf(csock, "ERR fork\n");
    close(outp[0]);
    close(outp[1]);
    close(csock);
    return;
  }
  if (child == 0) {
    close(csock);
    close(outp[0]);
    dup2(outp[1], 1);
    dup2(outp[1], 2);
    if (outp[1] > 2) close(outp[1]);
    int dn = open("/dev/null", O_RDONLY);
    if (dn >= 0) {
      dup2(dn, 0);
      if (dn > 2) close(dn);
    }
    if (do_chroot) {
      if (chdir(merge) != 0) _exit(78);
      if (chroot(merge) != 0) _exit(78);
      chdir("/");
    }
    setenv("HOME", home, 1);
    setenv("ATLAS_HOME", home, 1);
    setenv("ATLAS_HYBRID", do_chroot ? "1" : "0", 1);
    setenv("ATLAS_COMBINED", do_chroot ? "1" : "0", 1);
    setenv("ATLAS_ELEVATED", "1", 1);
    setenv("USER", "root", 1);
    setenv("LOGNAME", "root", 1);
    unsetenv("LD_LIBRARY_PATH");
    unsetenv("LD_PRELOAD");
    {
      char path[1024];
      if (do_chroot) {
        snprintf(path, sizeof(path),
                 "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:"
                 "/system/bin:/system_ext/bin:/product/bin:/system/xbin:/vendor/bin");
      } else {
        snprintf(path, sizeof(path),
                 "/system/bin:/system_ext/bin:/product/bin:/system/xbin:/vendor/bin:"
                 "/sbin:/bin");
      }
      setenv("PATH", path, 1);
    }
    const char *sh = "/system/bin/sh";
    if (do_chroot) {
      if (access("/bin/bash", X_OK) == 0) sh = "/bin/bash";
      else if (access("/bin/sh", X_OK) == 0) sh = "/bin/sh";
      else if (access("/system/bin/sh", X_OK) == 0) sh = "/system/bin/sh";
    }
    execl(sh, sh, "-c", shellcmd, (char *)NULL);
    _exit(127);
  }

  close(outp[1]);
  /* Stream command output to client, then footer with exit code. */
  dprintf(csock, "OK\n");
  char buf[4096];
  for (;;) {
    ssize_t n = read(outp[0], buf, sizeof(buf));
    if (n <= 0) break;
    ssize_t off = 0;
    while (off < n) {
      ssize_t w = write(csock, buf + off, (size_t)(n - off));
      if (w <= 0) break;
      off += w;
    }
  }
  close(outp[0]);
  int st = 0;
  waitpid(child, &st, 0);
  int code = WIFEXITED(st) ? WEXITSTATUS(st) : 128;
  dprintf(csock, "\n__ATLAS_EXIT__ %d\n", code);
  close(csock);
}

/*
 * BRIDGE / HEAL — rootless ownership + plane heal (NO KernelSU).
 * No auth ticket: only atlas package / shell / init peer; narrow chown paths.
 * Fixes "chdir …/files: Permission denied" when root thrash owned $HOME.
 */
static void handle_heal(int csock, uid_t peer) {
  uid_t a = pkg_uid();
  if (peer != 0 && peer != 2000 && peer != a && peer != (uid_t)-1
      && peer != 0xffffffffu) {
    dprintf(csock, "ERR deny-peer peer=%u\n", (unsigned)peer);
    close(csock);
    return;
  }
  if (a == 0) a = 10100; /* fallback */

  char cmd[1536];
  snprintf(cmd, sizeof(cmd),
           "set -e; "
           "U=%u; G=%u; "
           "mkdir -p /data/data/com.titanus2.atlas/files/bin "
           "/data/user/0/com.titanus2.atlas/files/bin "
           "/data/local/atlas-home/atlas/auth "
           "/data/local/atlas-home/atlas/reports "
           "/data/local/tmp; "
           "chown -R $U:$G /data/data/com.titanus2.atlas/files 2>/dev/null || true; "
           "chown -R $U:$G /data/user/0/com.titanus2.atlas/files 2>/dev/null || true; "
           "chmod -R u+rwX /data/data/com.titanus2.atlas/files 2>/dev/null || true; "
           "chmod -R u+rwX /data/user/0/com.titanus2.atlas/files 2>/dev/null || true; "
           "chown -R $U:$G /data/local/atlas-home 2>/dev/null || true; "
           "chmod 755 /data/local/atlas-home /data/local/atlas-home/atlas 2>/dev/null || true; "
           "chmod 777 /data/local/atlas-home/atlas/auth 2>/dev/null || true; "
           "mkdir -p /data/local/atlas-linux/var/lib/atlas-auth 2>/dev/null || true; "
           "chmod 777 /data/local/atlas-linux/var/lib/atlas-auth 2>/dev/null || true; "
           "rm -f /data/data/com.titanus2.atlas/files/.atlas-need-heal "
           "/data/user/0/com.titanus2.atlas/files/.atlas-need-heal 2>/dev/null || true; "
           "echo OK_BRIDGE_HEAL uid=$U",
           (unsigned)a, (unsigned)a);

  logf2("bridge-heal", cmd);
  int outp[2];
  if (pipe(outp) != 0) {
    dprintf(csock, "ERR pipe\n");
    close(csock);
    return;
  }
  pid_t child = fork();
  if (child < 0) {
    dprintf(csock, "ERR fork\n");
    close(outp[0]);
    close(outp[1]);
    close(csock);
    return;
  }
  if (child == 0) {
    close(csock);
    close(outp[0]);
    dup2(outp[1], 1);
    dup2(outp[1], 2);
    if (outp[1] > 2) close(outp[1]);
    int dn = open("/dev/null", O_RDONLY);
    if (dn >= 0) {
      dup2(dn, 0);
      if (dn > 2) close(dn);
    }
    execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
    _exit(127);
  }
  close(outp[1]);
  dprintf(csock, "OK\n");
  char buf[1024];
  for (;;) {
    ssize_t n = read(outp[0], buf, sizeof(buf));
    if (n <= 0) break;
    (void)write(csock, buf, (size_t)n);
  }
  close(outp[0]);
  int st = 0;
  waitpid(child, &st, 0);
  int code = WIFEXITED(st) ? WEXITSTATUS(st) : 128;
  dprintf(csock, "\n__ATLAS_EXIT__ %d\n", code);
  close(csock);
}

static void handle_client(int csock, uid_t peer) {
  char line[1024];
  if (read_line(csock, line, sizeof(line)) != 0) {
    close(csock);
    return;
  }
  if (strncmp(line, "HEAL", 4) == 0 || strncmp(line, "BRIDGE", 6) == 0) {
    handle_heal(csock, peer);
    return;
  }
  if (strncmp(line, "ELEVATE", 7) == 0) {
    handle_elevate(csock, peer, line);
    return;
  }
  if (strncmp(line, "ENTER", 5) != 0) {
    dprintf(csock, "ERR bad-cmd\n");
    close(csock);
    return;
  }

  uid_t drop = 0;
  char home[512];
  home[0] = 0;
  int do_ensure = 1;
  char *p = line;
  while (*p && *p != ' ') p++;
  while (*p == ' ') p++;
  while (*p) {
    char *eq = strchr(p, '=');
    char *sp = strchr(p, ' ');
    if (!eq || (sp && sp < eq)) {
      if (!sp) break;
      p = sp + 1;
      continue;
    }
    *eq = 0;
    char *val = eq + 1;
    if (sp) *sp = 0;
    if (strcmp(p, "uid") == 0) drop = (uid_t)strtoul(val, NULL, 10);
    else if (strcmp(p, "home") == 0) snprintf(home, sizeof(home), "%s", val);
    else if (strcmp(p, "ensure") == 0) do_ensure = (val[0] != '0');
    if (!sp) break;
    p = sp + 1;
  }

  /* TCP loopback PEERCRED is often uid=-1 on Android — do not hard-deny ENTER
   * for Atlas/admin drop when peer looks odd. Still require drop==pkg or shell. */
  if (!caller_ok(peer)) {
    uid_t a = pkg_uid();
    if (!(drop == a || drop == 2000 || peer == (uid_t)-1 || peer == 0xffffffffu)) {
      dprintf(csock, "ERR deny-peer peer=%u drop=%u\n", (unsigned)peer, (unsigned)drop);
      close(csock);
      return;
    }
    logf2("enter peer-odd", "allow-drop");
  }
  if (drop == 0) drop = peer;
  if (drop == 0 || drop == (uid_t)-1 || drop == 0xffffffffu) drop = pkg_uid();
  if (drop == 0) {
    dprintf(csock, "ERR no-drop-uid\n");
    close(csock);
    return;
  }
  if (peer != 0 && peer != 2000 && peer != drop && peer != (uid_t)-1
      && peer != 0xffffffffu) {
    uid_t a = pkg_uid();
    if (drop != a) {
      dprintf(csock, "ERR uid-mismatch peer=%u drop=%u\n", (unsigned)peer,
              (unsigned)drop);
      close(csock);
      return;
    }
  }
  if (!home[0]) snprintf(home, sizeof(home), "/data/data/com.titanus2.atlas/files");

  int pty = recv_fd(csock);
  if (pty < 0) {
    dprintf(csock, "ERR no-fd\n");
    close(csock);
    return;
  }

  const char *merge = MERGE_DEFAULT;
  /* Never block ENTER on ensure when plane is already up — always-ensure was
   * hanging Deb PTY (black term / exit -9 thrash) while hybrid.sh ensure ran. */
  if (!hybrid_ready(merge)) {
    if (do_ensure) {
      logf("enter hybrid-down → ensure");
      (void)run_ensure();
    }
  }
  if (!hybrid_ready(merge)) {
    write_status(0);
    dprintf(csock, "ERR hybrid-down\n");
    close(pty);
    close(csock);
    return;
  }
  write_status(1);
  logf2("enter ok", home);

  const char *shrel = pick_shell(merge);
  if (!shrel) {
    dprintf(csock, "ERR no-shell\n");
    close(pty);
    close(csock);
    return;
  }

  pid_t child = fork();
  if (child < 0) {
    dprintf(csock, "ERR fork\n");
    close(pty);
    close(csock);
    return;
  }
  if (child == 0) {
    close(csock);
    /* Controlling TTY required — without TIOCSCTTY bash prints
     * "cannot set terminal process group" and the term looks dead/black.
     * Force-steal (arg 1) when the Termux client already opened the slave. */
    if (setsid() < 0) { /* continue */ }
    if (ioctl(pty, TIOCSCTTY, 1) != 0 && ioctl(pty, TIOCSCTTY, 0) != 0) {
      char *pn = ptsname(pty);
      if (pn) {
        int s2 = open(pn, O_RDWR);
        if (s2 >= 0) {
          (void)ioctl(s2, TIOCSCTTY, 1);
          if (pty > 2) close(pty);
          pty = s2;
        }
      }
    }
    dup2(pty, 0);
    dup2(pty, 1);
    dup2(pty, 2);
    if (pty > 2) close(pty);

    /* Wipe-first-boot permission plane BEFORE chroot/drop (ROM peel path).
     * Post-wipe: /data/local/atlas-home often root:root 0700 → chdir fails after
     * setuid(app). Never depend on tip thrash or post-flash adb chmod. */
    {
      (void)mkdir("/data/local", 0755);
      (void)mkdir("/data/local/atlas-home", 0755);
      (void)mkdir("/data/local/atlas-home/atlas", 0755);
      (void)mkdir("/data/local/atlas-home/atlas/reports", 0755);
      (void)mkdir("/data/local/atlas-hybrid", 0755);
      (void)chmod("/data/local", 0755);
      (void)chmod("/data/local/atlas-home", 0755);
      (void)chmod("/data/local/atlas-hybrid", 0755);
      (void)chmod("/data/local/atlas-home/atlas", 0755);
      if (drop > 0) {
        (void)chown("/data/local/atlas-home/atlas", drop, drop);
        (void)chown("/data/local/atlas-home/atlas/reports", drop, drop);
      }
      /* Auth on LP (survives wipe) — world-writable ticket plane. */
      (void)mkdir("/data/local/atlas-linux/var", 0755);
      (void)mkdir("/data/local/atlas-linux/var/lib", 0755);
      (void)mkdir("/data/local/atlas-linux/var/lib/atlas-auth", 0777);
      (void)chmod("/data/local/atlas-linux/var/lib/atlas-auth", 0777);
      (void)mkdir("/data/local/atlas-linux/tmp", 01777);
      (void)mkdir("/data/local/atlas-linux/var/tmp", 01777);
      (void)chmod("/data/local/atlas-linux/tmp", 01777);
      (void)chmod("/data/local/atlas-linux/var/tmp", 01777);
    }

    /* Bind real linux home into merge BEFORE chroot (reboot drops binds). */
    ensure_home_bind(merge);

    if (chdir(merge) != 0) _exit(78);
    if (chroot(merge) != 0) _exit(78);
    chdir("/");
    /* Prefer /home/atlas (bound to Android atlas-home). Installers mkdir ~/… */
    {
      const char *h = home;
      if (access("/home/atlas", X_OK) == 0)
        h = "/home/atlas";
      else if (access("/data/local/atlas-home/atlas", X_OK) == 0)
        h = "/data/local/atlas-home/atlas";
      else if (!h || !h[0] || access(h, X_OK) != 0)
        h = "/tmp";
      setenv("HOME", h, 1);
      setenv("ATLAS_HOME", h, 1);
      setenv("ATLAS_LINUX_HOME", "/data/local/atlas-home/atlas", 1);
      (void)chdir(h);
    }
    setenv("ATLAS_HYBRID", "1", 1);
    setenv("ATLAS_COMBINED", "1", 1);
    setenv("ATLAS_PLANE", "hybrid", 1);
    setenv("ATLAS_MODE", "debian", 1);
    setenv("ATLAS_SESSION", "hybrid", 1);
    setenv("ATLAS_PRIV", "1", 1);
    setenv("ATLAS_ROLE", "admin", 1);
    /* Avoid "I have no name!" when passwd lacks app uid (LP often RO). */
    setenv("USER", "atlas", 1);
    setenv("LOGNAME", "atlas", 1);
    setenv("PS1", "debian:atlas:\\w\\$ ", 1);
    setenv("PROMPT_COMMAND", "", 1);
    setenv("TERM", "xterm-256color", 1);
    setenv("COLORTERM", "truecolor", 1);
    setenv("ANDROID_ROOT", "/system", 1);
    setenv("ANDROID_DATA", "/data", 1);
    /* Stop CE bashrc clobber — PATH is product-owned (user installs on HOME). */
    setenv("BASH_ENV", "", 1);
    unsetenv("LD_LIBRARY_PATH");
    unsetenv("LD_PRELOAD");
    {
      /* Deb bins first; then every $HOME install layout; then Android ROM bins.
       * enterd uses --noprofile so this PATH is the only session PATH — must
       * include curl-install destinations or tools are "not found". */
      char path[2048];
      const char *h = getenv("HOME");
      path[0] = 0;
      path_add_dir(path, sizeof(path), "/usr/local/sbin");
      path_add_dir(path, sizeof(path), "/usr/local/bin");
      path_add_dir(path, sizeof(path), "/usr/sbin");
      path_add_dir(path, sizeof(path), "/usr/bin");
      path_add_dir(path, sizeof(path), "/sbin");
      path_add_dir(path, sizeof(path), "/bin");
      path_add_dir(path, sizeof(path), "/atlas-bin");
      if (h && h[0]) path_add_user_installs(path, sizeof(path), h);
      /* also CE files home if distinct (android plane tools) */
      path_add_user_installs(path, sizeof(path), "/data/data/com.titanus2.atlas/files");
      path_add_dir(path, sizeof(path), "/system/bin");
      path_add_dir(path, sizeof(path), "/system_ext/bin");
      path_add_dir(path, sizeof(path), "/product/bin");
      path_add_dir(path, sizeof(path), "/system/xbin");
      path_add_dir(path, sizeof(path), "/vendor/bin");
      if (!path[0]) {
        snprintf(path, sizeof(path),
                 "/usr/local/bin:/usr/bin:/bin:/system/bin");
      }
      setenv("PATH", path, 1);
    }
    if (setgid(drop) != 0) _exit(77);
    if (setuid(drop) != 0) _exit(77);
    if (geteuid() == 0) _exit(77);
    /* Load = exec grok --resume. bash --norc never sources .bashrc hooks. */
    {
      char rid[80];
      FILE *rf = fopen("/home/atlas/.atlas-resume", "r");
      if (!rf) rf = fopen("/data/local/atlas-home/atlas/.atlas-resume", "r");
      rid[0] = 0;
      if (rf) {
        char line[128];
        while (fgets(line, sizeof(line), rf)) {
          if (strncmp(line, "ATLAS_RESUME_GROK=", 18) != 0) continue;
          size_t n = strcspn(line + 18, "\r\n");
          if (n >= sizeof(rid)) n = sizeof(rid) - 1;
          memcpy(rid, line + 18, n);
          rid[n] = 0;
          break;
        }
        fclose(rf);
      }
      if (rid[0] && strcmp(rid, "none") != 0) {
        unlink("/home/atlas/.atlas-resume");
        unlink("/data/local/atlas-home/atlas/.atlas-resume");
        execlp("grok", "grok", "--resume", rid, (char *)NULL);
        const char *gb[] = {
          "/home/atlas/.grok/bin/grok",
          "/data/local/atlas-home/atlas/.grok/bin/grok",
          NULL
        };
        int gi;
        for (gi = 0; gb[gi]; gi++) {
          if (access(gb[gi], X_OK) == 0)
            execl(gb[gi], "grok", "--resume", rid, (char *)NULL);
        }
      }
    }
    /* Interactive non-login, no rc: prompt from PS1; PATH set above */
    execl(shrel, "bash", "--norc", "--noprofile", "-i", (char *)NULL);
    execl(shrel, shrel, "-i", (char *)NULL);
    execl(shrel, shrel, (char *)NULL);
    _exit(78);
  }

  close(pty);
  dprintf(csock, "OK\n");
  int st = 0;
  waitpid(child, &st, 0);
  int code = WIFEXITED(st) ? WEXITSTATUS(st) : 128;
  dprintf(csock, "EXIT %d\n", code);
  close(csock);
}


static int listen_tcp(void) {
  int s = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (s < 0) return -1;
  int one = 1;
  setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
  struct sockaddr_in in;
  memset(&in, 0, sizeof(in));
  in.sin_family = AF_INET;
  in.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  in.sin_port = htons(17999);
  if (bind(s, (struct sockaddr *)&in, sizeof(in)) != 0) {
    logf2("bind tcp fail", strerror(errno));
    close(s);
    return -1;
  }
  if (listen(s, 16) != 0) { close(s); return -1; }
  logf("listen tcp 127.0.0.1:17999");
  return s;
}

static int listen_android_socket(void) {
  const char *name = "atlasenter";
  char envname[64];
  snprintf(envname, sizeof(envname), "ANDROID_SOCKET_%s", name);
  const char *val = getenv(envname);
  if (!val || !val[0]) return -1;
  int fd = atoi(val);
  if (fd < 0) return -1;
  fcntl(fd, F_SETFD, FD_CLOEXEC);
  logf2("listen ANDROID_SOCKET", name);
  return fd;
}

static int listen_abstract(void) {
  int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (s < 0) return -1;
  struct sockaddr_un addr;
  memset(&addr, 0, sizeof(addr));
  addr.sun_family = AF_UNIX;
  addr.sun_path[0] = '\0';
  memcpy(addr.sun_path + 1, ABS_NAME, strlen(ABS_NAME));
  socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(ABS_NAME));
  if (bind(s, (struct sockaddr *)&addr, len) != 0) {
    logf2("bind abstract fail", strerror(errno));
    close(s);
    return -1;
  }
  if (listen(s, 16) != 0) {
    close(s);
    return -1;
  }
  logf("listen abstract @atlasenter");
  return s;
}

static int listen_fs(void) {
  unlink(SOCK_FS);
  int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (s < 0) return -1;
  struct sockaddr_un addr;
  memset(&addr, 0, sizeof(addr));
  addr.sun_family = AF_UNIX;
  snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", SOCK_FS);
  if (bind(s, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
    logf2("bind fs fail", strerror(errno));
    close(s);
    return -1;
  }
  chmod(SOCK_FS, 0666);
  if (listen(s, 16) != 0) {
    close(s);
    unlink(SOCK_FS);
    return -1;
  }
  logf("listen fs /data/local/tmp/atlas-enter.sock");
  return s;
}

int main(void) {
  if (geteuid() != 0) {
    fprintf(stderr, "atlas-enterd: need root\n");
    return 79;
  }
  signal(SIGPIPE, SIG_IGN);

  logf("start atlas-enterd " ATLAS_VERSION);
  /* LISTEN FIRST — never block on hybrid ensure (hang left TCP dead → no bridge,
   * "no enterd" elevate fail, forced KernelSU thrash). Ensure runs async. */
  write_status(hybrid_ready(MERGE_DEFAULT) ? 1 : 0);

  /* Multi-listen:
   *   abstract/fs unix — ENTER needs SCM_RIGHTS (PTY); TCP cannot carry that.
   *   TCP 17999 — ELEVATE / HEAL (text protocol only).
   * Single-socket TCP-first left Deb enter broken (ERR no-fd). */
  int socks[4];
  int ns = 0;
  /* Abstract first — ENTER SCM_RIGHTS. Retry if busy (peer enterd dying). */
  int a = -1;
  for (int try = 0; try < 10 && a < 0; try++) {
    a = listen_abstract();
    if (a < 0) usleep(100 * 1000);
  }
  if (a >= 0) socks[ns++] = a;
  else logf("WARN no abstract @atlasenter — Deb ENTER will fail");
  int f = listen_fs();
  if (f >= 0) socks[ns++] = f;
  else logf("WARN no fs sock");
  int t = listen_tcp();
  if (t >= 0) socks[ns++] = t;
  else logf("WARN no tcp 17999");
  int as = listen_android_socket();
  if (as >= 0) socks[ns++] = as;
  if (ns <= 0) {
    logf("FATAL no listen");
    return 1;
  }
  {
    char m[64];
    snprintf(m, sizeof(m), "listening n=%d abs=%d fs=%d tcp=%d", ns,
             a >= 0, f >= 0, t >= 0);
    logf(m);
  }

  /* Background hybrid ensure — does not block HEAL/ELEVATE accept loop. */
  {
    pid_t bg = fork();
    if (bg == 0) {
      (void)run_ensure();
      write_status(hybrid_ready(MERGE_DEFAULT) ? 1 : 0);
      _exit(0);
    }
  }

  for (;;) {
    struct pollfd pfd[4];
    for (int i = 0; i < ns; i++) {
      pfd[i].fd = socks[i];
      pfd[i].events = POLLIN;
      pfd[i].revents = 0;
    }
    int pr = poll(pfd, (nfds_t)ns, 1000);
    if (pr < 0) {
      if (errno == EINTR) continue;
      sleep(1);
      continue;
    }
    for (int i = 0; i < ns; i++) {
      if (!(pfd[i].revents & POLLIN)) continue;
      int cs = accept(socks[i], NULL, NULL);
      if (cs < 0) continue;
      struct ucred cred;
      memset(&cred, 0, sizeof(cred));
      socklen_t clen = sizeof(cred);
      uid_t peer = 0;
      if (getsockopt(cs, SOL_SOCKET, SO_PEERCRED, &cred, &clen) == 0)
        peer = cred.uid;
      pid_t w = fork();
      if (w == 0) {
        for (int j = 0; j < ns; j++) close(socks[j]);
        handle_client(cs, peer);
        _exit(0);
      }
      close(cs);
    }
    while (waitpid(-1, NULL, WNOHANG) > 0) {
    }
  }
}
