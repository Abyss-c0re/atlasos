/*
 * sudo / su — Authentication Agent client + in-ROM / in-Debian elevate.
 *
 * Product model (no KernelSU required for normal privilege):
 *   1) admin shell (app UID / Debian admin) — never free root
 *   2) biometrics via Atlas auth agent (Android FGS)
 *   3) elevate using:
 *        a) real /usr/bin/sudo -n  (Debian hybrid — setuid inside image)
 *        b) setuid(0) if this binary is setuid-root (ROM-supervised)
 *        c) KernelSU absolute su — optional last resort only
 *
 * Hybrid is seamless: Android ↔ Debian bins share the combined root;
 * privilege is agent-gated, not “ask KSU for everything”.
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <netinet/in.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static int run_auth(const char *reason) {
  char path[512];
  const char *bin = getenv("ATLAS_BIN");
  if (bin && bin[0])
    snprintf(path, sizeof path, "%s/atlas-auth", bin);
  else if (access("/data/user/0/com.titanus2.atlas/files/bin/atlas-auth", X_OK) == 0)
    snprintf(path, sizeof path, "/data/user/0/com.titanus2.atlas/files/bin/atlas-auth");
  else if (access("/data/data/com.titanus2.atlas/files/bin/atlas-auth", X_OK) == 0)
    snprintf(path, sizeof path, "/data/data/com.titanus2.atlas/files/bin/atlas-auth");
  else if (access("/system/bin/atlas-auth", X_OK) == 0)
    snprintf(path, sizeof path, "/system/bin/atlas-auth");
  else
    snprintf(path, sizeof path, "atlas-auth");

  pid_t p = fork();
  if (p < 0) return 4;
  if (p == 0) {
    execl(path, "atlas-auth", "request", "--scope", "sudo", reason, (char *)NULL);
    execlp("atlas-auth", "atlas-auth", "request", "--scope", "sudo", reason,
           (char *)NULL);
    _exit(4);
  }
  int st = 0;
  waitpid(p, &st, 0);
  if (WIFEXITED(st)) return WEXITSTATUS(st);
  return 4;
}

static int in_debian_hybrid(void) {
  if (getenv("ATLAS_HYBRID") && getenv("ATLAS_HYBRID")[0] == '1') return 1;
  if (getenv("ATLAS_COMBINED") && getenv("ATLAS_COMBINED")[0] == '1') return 1;
  if (access("/etc/debian_version", F_OK) == 0) return 1;
  return 0;
}

static int is_self_path(const char *path) {
  char self[PATH_MAX];
  ssize_t n = readlink("/proc/self/exe", self, sizeof self - 1);
  if (n <= 0) return 0;
  self[n] = 0;
  return strcmp(self, path) == 0;
}

/* True if path is ELF — shell shims that re-exec atlas-sudo must be skipped. */
static int is_elf_binary(const char *path) {
  int fd = open(path, O_RDONLY);
  if (fd < 0) return 0;
  unsigned char mag[4];
  ssize_t n = read(fd, mag, 4);
  close(fd);
  return n == 4 && mag[0] == 0x7f && mag[1] == 'E' && mag[2] == 'L' && mag[3] == 'F';
}

/* Real Debian setuid sudo — only AFTER agent grant.
 * NEVER return a shell shim (loop). NEVER return non-setuid ELF (sudo self-check fails). */
static const char *find_debian_sudo_real(void) {
  static const char *cands[] = {
      "/usr/bin/sudo.real", "/usr/libexec/atlas/sudo-real", "/usr/bin/sudo", NULL};
  for (int i = 0; cands[i]; i++) {
    struct stat st;
    if (is_self_path(cands[i])) continue;
    if (access(cands[i], X_OK) != 0) continue;
    if (!is_elf_binary(cands[i])) continue;
    if (stat(cands[i], &st) != 0) continue;
    /* Debian sudo refuses: must be uid 0 + setuid bit (not just mode on disk if nosuid). */
    if (st.st_uid != 0) continue;
    if ((st.st_mode & S_ISUID) == 0) continue;
    return cands[i];
  }
  return NULL;
}

static int try_debian_sudo(int argc, char **argv, int arg0) {
  const char *dsudo = find_debian_sudo_real();
  if (!dsudo) return -1;
  /* After agent grant: passwordless admin (sudoers written by hybrid). */
  int n = argc - arg0;
  char **nargv = calloc((size_t)n + 3, sizeof(char *));
  if (!nargv) return -1;
  int j = 0;
  nargv[j++] = (char *)dsudo;
  nargv[j++] = "-n"; /* non-interactive; agent already authorized */
  if (n <= 0) {
    nargv[j++] = "/bin/bash";
    nargv[j++] = "-il";
    nargv[j] = NULL;
  } else {
    for (int i = 0; i < n; i++) nargv[j++] = argv[arg0 + i];
    nargv[j] = NULL;
  }
  execv(dsudo, nargv);
  return -1;
}

/* Connect to atlas-enterd (TCP / abstract / fs sock). */
static int connect_enterd(void) {
  int s = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (s >= 0) {
    struct sockaddr_in in;
    memset(&in, 0, sizeof(in));
    in.sin_family = AF_INET;
    in.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    in.sin_port = htons(17999);
    if (connect(s, (struct sockaddr *)&in, sizeof(in)) == 0) return s;
    close(s);
  }
  s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (s >= 0) {
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, "atlasenter", 10);
    socklen_t len =
        (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + 10);
    if (connect(s, (struct sockaddr *)&addr, len) == 0) return s;
    close(s);
  }
  s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (s >= 0) {
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    snprintf(addr.sun_path, sizeof(addr.sun_path),
             "/data/local/tmp/atlas-enter.sock");
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) +
                                strlen(addr.sun_path) + 1);
    if (connect(s, (struct sockaddr *)&addr, len) == 0) return s;
    close(s);
  }
  return -1;
}

static int read_line_fd(int fd, char *buf, size_t cap) {
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

/*
 * Product rootless elevate: atlas-enterd (init root) runs CMD after auth ticket.
 * No KernelSU. No setuid client. Works for Debian chroot and Android (chroot=0).
 */
static int try_enterd_elevate(int argc, char **argv, int arg0, int as_su) {
  int s = connect_enterd();
  if (s < 0) return -1;

  const char *home = getenv("ATLAS_HOME");
  if (!home || !home[0]) home = getenv("HOME");
  if (!home || !home[0]) home = "/data/data/com.titanus2.atlas/files";

  int do_chroot = in_debian_hybrid() ? 1 : 0;
  /* Force Deb chroot when hybrid merge is mounted even if env missing. */
  if (!do_chroot && access("/data/local/atlas-hybrid/merge/bin/bash", X_OK) == 0 &&
      access("/etc/debian_version", F_OK) == 0)
    do_chroot = 1;

  char hdr[768];
  int hl = snprintf(hdr, sizeof(hdr), "ELEVATE chroot=%d home=%s\n", do_chroot,
                    home);
  if (write(s, hdr, (size_t)hl) != hl) {
    close(s);
    return -1;
  }

  /* Build shell command from remaining argv */
  char cmd[3072];
  size_t off = 0;
  if (as_su && arg0 >= argc) {
    /* interactive root shell */
    snprintf(cmd, sizeof(cmd), "%s",
             do_chroot ? "exec /bin/bash -il" : "exec /system/bin/sh -i");
  } else if (arg0 >= argc) {
    snprintf(cmd, sizeof(cmd), "%s",
             do_chroot ? "exec /bin/bash -il" : "exec /system/bin/sh -i");
  } else {
    for (int i = arg0; i < argc; i++) {
      const char *a = argv[i] ? argv[i] : "";
      /* naive single-quote escape */
      if (off + 4 >= sizeof(cmd)) break;
      cmd[off++] = '\'';
      for (const char *p = a; *p && off + 4 < sizeof(cmd); p++) {
        if (*p == '\'') {
          cmd[off++] = '\'';
          cmd[off++] = '\\';
          cmd[off++] = '\'';
          cmd[off++] = '\'';
        } else {
          cmd[off++] = *p;
        }
      }
      if (off + 2 >= sizeof(cmd)) break;
      cmd[off++] = '\'';
      if (i + 1 < argc) cmd[off++] = ' ';
    }
    cmd[off] = 0;
  }

  char line[3200];
  int n = snprintf(line, sizeof(line), "CMD %s\n", cmd);
  if (n <= 0 || write(s, line, (size_t)n) != n) {
    close(s);
    return -1;
  }

  char resp[256];
  if (read_line_fd(s, resp, sizeof(resp)) != 0) {
    close(s);
    return -1;
  }
  if (strncmp(resp, "OK", 2) != 0) {
    fprintf(stderr, "sudo: enterd elevate: %s\n", resp);
    close(s);
    /* need-auth-ticket is soft — caller may fall through */
    return -1;
  }

  /* Stream body until __ATLAS_EXIT__ N */
  char buf[4096];
  size_t hold = 0;
  char carry[64];
  carry[0] = 0;
  int exit_code = 0;
  int got_exit = 0;
  for (;;) {
    ssize_t r = read(s, buf, sizeof(buf));
    if (r <= 0) break;
    /* append to scan buffer for footer — stream most bytes live */
    size_t total = hold + (size_t)r;
    char *scan = malloc(total + 1);
    if (!scan) {
      (void)write(STDOUT_FILENO, buf, (size_t)r);
      continue;
    }
    if (hold) memcpy(scan, carry, hold);
    memcpy(scan + hold, buf, (size_t)r);
    scan[total] = 0;

    char *foot = strstr(scan, "\n__ATLAS_EXIT__ ");
    if (!foot) foot = strstr(scan, "__ATLAS_EXIT__ ");
    if (foot) {
      size_t out_len = (size_t)(foot - scan);
      if (out_len && scan[0] == '\n' && out_len == 0) {
        /* nothing */
      }
      if (foot > scan && foot[-1] != '\n' && foot[0] == '\n') {
        /* foot points at \n__ATLAS… */
      }
      /* print everything before footer marker */
      const char *print_end = foot;
      if (print_end > scan && print_end[-1] == '\n' &&
          strncmp(print_end, "\n__ATLAS_EXIT__", 15) != 0) {
        /* ok */
      }
      if (strncmp(foot, "\n__ATLAS_EXIT__ ", 16) == 0) {
        if ((size_t)(foot - scan) > 0)
          (void)write(STDOUT_FILENO, scan, (size_t)(foot - scan));
        exit_code = atoi(foot + 16);
        got_exit = 1;
        free(scan);
        break;
      }
      if (strncmp(foot, "__ATLAS_EXIT__ ", 15) == 0) {
        if (foot > scan)
          (void)write(STDOUT_FILENO, scan, (size_t)(foot - scan));
        exit_code = atoi(foot + 15);
        got_exit = 1;
        free(scan);
        break;
      }
    }

    /* keep last 32 bytes for footer straddling reads */
    if (total > 32) {
      (void)write(STDOUT_FILENO, scan, total - 32);
      memcpy(carry, scan + total - 32, 32);
      hold = 32;
      carry[32] = 0;
    } else {
      memcpy(carry, scan, total);
      hold = total;
      carry[hold] = 0;
    }
    free(scan);
  }
  if (!got_exit && hold) {
    /* flush carry if no footer */
    char *foot = strstr(carry, "__ATLAS_EXIT__ ");
    if (foot) {
      if (foot > carry)
        (void)write(STDOUT_FILENO, carry, (size_t)(foot - carry));
      exit_code = atoi(foot + 15);
      got_exit = 1;
    } else {
      (void)write(STDOUT_FILENO, carry, hold);
    }
  }
  close(s);
  if (!got_exit) return -1;
  _exit(exit_code);
  return exit_code;
}

/* ROM-supervised: if we are setuid-root, drop to real root after agent. */
static int try_setuid_root_exec(int argc, char **argv, int arg0, int as_su) {
  if (geteuid() != 0 && setuid(0) != 0) return -1;
  if (setgid(0) != 0) { /* best effort */ }
  if (as_su) {
    if (arg0 >= argc) {
      execl("/system/bin/sh", "sh", "-i", (char *)NULL);
      execl("/bin/bash", "bash", "-il", (char *)NULL);
      execl("/bin/sh", "sh", "-i", (char *)NULL);
      return -1;
    }
    execvp(argv[arg0], &argv[arg0]);
    return -1;
  }
  /* sudo style: remaining args are command */
  if (arg0 >= argc) {
    execl("/bin/bash", "bash", "-il", (char *)NULL);
    execl("/system/bin/sh", "sh", "-i", (char *)NULL);
    return -1;
  }
  execvp(argv[arg0], &argv[arg0]);
  return -1;
}

/* Optional last resort — not the product path. */
static const char *find_kernelsu(void) {
  static const char *cands[] = {
      "/system/bin/su", "/system/xbin/su", "/data/adb/ksu/bin/su",
      "/debug_ramdisk/su", NULL};
  const char *env = getenv("ATLAS_REAL_SU");
  if (env && env[0] && access(env, F_OK) == 0 && !is_self_path(env)) return env;
  for (int i = 0; cands[i]; i++) {
    if (is_self_path(cands[i])) continue;
    if (access(cands[i], F_OK) != 0) continue;
    return cands[i];
  }
  return NULL;
}

static const char *basename_of(const char *p) {
  const char *s = strrchr(p, '/');
  return s ? s + 1 : p;
}

static int skip_sudo_opts(int argc, char **argv) {
  int i = 1;
  while (i < argc) {
    if (!strcmp(argv[i], "-i") || !strcmp(argv[i], "-s")) return i; /* special */
    if (!strcmp(argv[i], "-u")) {
      i += 2;
      continue;
    }
    if (!strcmp(argv[i], "-n") || !strcmp(argv[i], "-E") || !strcmp(argv[i], "-k") ||
        !strcmp(argv[i], "-K") || !strcmp(argv[i], "-v") || !strcmp(argv[i], "-V")) {
      i++;
      continue;
    }
    if (argv[i][0] == '-' && strcmp(argv[i], "--") != 0) {
      i++;
      continue;
    }
    if (!strcmp(argv[i], "--")) {
      i++;
      break;
    }
    break;
  }
  return i;
}

int main(int argc, char **argv) {
  const char *base = basename_of(argv[0] ? argv[0] : "sudo");
  int as_su = !strcmp(base, "su");

  char reason[1024];
  snprintf(reason, sizeof reason, "%s", as_su ? "su" : "sudo");
  for (int i = 1; i < argc; i++) {
    strncat(reason, " ", sizeof reason - strlen(reason) - 1);
    strncat(reason, argv[i], sizeof reason - strlen(reason) - 1);
  }

  /* 1) Authentication Agent — optional by plane (Settings → Biometric).
   *    Privilege plane is separate (what is allowed). Bio only enforces when on.
   *    Deb sudo  → titan2_atlas_bio_debian_sudo (default off)
   *    Android su → titan2_atlas_bio_android_su (default off — match AtlasPrefs)
   *    apt never comes here for package ops (apt-hybrid skips bio).
   */
  {
    const char *internal = getenv("ATLAS_INTERNAL_SU");
    const char *skip = getenv("ATLAS_SKIP_BIOMETRIC");
    if (!(internal && !strcmp(internal, "1")) && !(skip && !strcmp(skip, "1"))) {
      int in_deb = in_debian_hybrid();
      int need_bio = 1;
      {
        /* plane files: 1 = require bio, 0 = skip */
        const char *keys[4];
        int nk = 0;
        if (in_deb) {
          keys[nk++] = "titan2_atlas_bio_debian_sudo";
        } else {
          keys[nk++] = "titan2_atlas_bio_android_su";
        }
        int found = 0;
        int want = 0; /* default off: privilege primary; bio optional */
        for (int k = 0; k < nk; k++) {
          char path[512];
          const char *roots[] = {
              "/data/local/tmp", "/data/misc/titan2",
              "/data/local/atlas-linux/var/lib/atlas-auth", "/var/lib/atlas-auth",
              NULL};
          for (int r = 0; roots[r]; r++) {
            snprintf(path, sizeof path, "%s/%s", roots[r], keys[k]);
            FILE *f = fopen(path, "r");
            if (!f) continue;
            char buf[16];
            if (fgets(buf, sizeof buf, f)) {
              found = 1;
              if (buf[0] == '0' || buf[0] == 'f' || buf[0] == 'F' ||
                  buf[0] == 'n' || buf[0] == 'N' || buf[0] == 'o')
                want = 0;
              else
                want = 1;
            }
            fclose(f);
            if (found) break;
          }
          if (found) break;
        }
        need_bio = want;
      }
      if (!need_bio) {
        /* Settings toggled bio off for this plane — elevate without agent */
        goto after_auth;
      }
      /* One wrap: atlas-auth owns tickets (ticket.sudo only, never blanket). */
      fprintf(stderr, "%s: auth agent (biometrics)…\n", base);
      int rc = run_auth(reason);
      if (rc != 0) {
        fprintf(stderr, "%s: denied by auth agent (exit %d)\n", base, rc);
        return rc == 3 ? 3 : 1;
      }
    }
  }
after_auth:
  /* enterd wants ticket.exec (15s one-shot). atlas-auth writes it on grant.
   * If bio was skipped, mint exec token only — never a 1800s blanket. */
  {
    long exp = (long)time(NULL) + 15;
    static const char *mirrors[] = {
        "/data/local/atlas-linux/var/lib/atlas-auth/ticket.exec",
        "/var/lib/atlas-auth/ticket.exec",
        NULL};
    for (int i = 0; mirrors[i]; i++) {
      FILE *tf = fopen(mirrors[i], "w");
      if (!tf) continue;
      fprintf(tf, "%ld 15\n", exp);
      fclose(tf);
      chmod(mirrors[i], 0644);
    }
  }

  int arg0 = as_su ? 1 : skip_sudo_opts(argc, argv);

  /* 2) Debian / hybrid: real setuid sudo.real|sudo — no KernelSU (agent already ran) */
  if (in_debian_hybrid() || find_debian_sudo_real() != NULL) {
    if (try_debian_sudo(argc, argv, arg0) == 0) return 0;
    /* fall through if -n sudo not configured yet */
  }

  /* 3) Product rootless: atlas-enterd elevate (init root + auth ticket) */
  {
    int rc = try_enterd_elevate(argc, argv, arg0, as_su);
    if (rc >= 0) return rc; /* _exit on success path; -1 = try next */
  }

  /* 4) ROM-supervised setuid elevate (this binary installed setuid-root in product) */
  if (try_setuid_root_exec(argc, argv, arg0, as_su) == 0) return 0;

  /* 5) Optional KernelSU — only if present; not required */
  const char *ksu = find_kernelsu();
  if (ksu) {
    if (as_su) {
      char **nargv = calloc((size_t)argc + 1, sizeof(char *));
      if (!nargv) return 4;
      nargv[0] = (char *)ksu;
      for (int i = 1; i < argc; i++) nargv[i] = argv[i];
      nargv[argc] = NULL;
      execv(ksu, nargv);
    } else if (arg0 >= argc) {
      execl(ksu, ksu, "0", "-s", "/system/bin/sh", "-i", (char *)NULL);
      execl(ksu, ksu, "0", "/system/bin/sh", "-i", (char *)NULL);
    } else {
      int n = argc - arg0;
      char **nargv = calloc((size_t)n + 3, sizeof(char *));
      if (!nargv) return 4;
      nargv[0] = (char *)ksu;
      nargv[1] = "0";
      for (int j = 0; j < n; j++) nargv[2 + j] = argv[arg0 + j];
      nargv[2 + n] = NULL;
      execv(ksu, nargv);
    }
    fprintf(stderr, "%s: exec %s failed: %s\n", base, ksu, strerror(errno));
    return 127;
  }

  fprintf(stderr,
          "%s: auth granted but cannot elevate (no enterd / sudo.real / setuid).\n"
          "  Product path: atlas-enterd must be running (init root).\n"
          "  Debian: hybrid may also stage setuid /usr/bin/sudo.real.\n"
          "  KernelSU is optional and not required.\n",
          base);
  return 127;
}
