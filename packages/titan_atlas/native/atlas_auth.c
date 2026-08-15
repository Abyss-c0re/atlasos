/*
 * atlas-auth — native biometric privilege client for Atlas / Grok.
 *
 * Protocol (filesystem, works for any aarch64 ELF including grok):
 *   $ATLAS_HOME/auth/req.<id>   client writes reason (text)
 *   $ATLAS_HOME/auth/ok.<id>    host grants after biometrics
 *   $ATLAS_HOME/auth/fail.<id>  host denies
 *   $ATLAS_HOME/auth/wake       touch to nudge FileObserver
 *
 * Host: AtlasSessionService FileObserver + AuthPromptActivity
 *
 * CLI:
 *   atlas-auth request [-t sec] <reason...>
 *   atlas-auth status
 *   atlas-auth version
 *
 * Exit: 0 grant · 1 deny · 2 usage · 3 timeout · 4 setup error
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#ifndef ATLAS_VERSION
#define ATLAS_VERSION "1.0.0"
#endif

/*
 * LAW (every change): privilege auth plane lives on super LP atlas_linux so it
 * survives userdata wipe. Never app CE files/auth. Never /data/local/tmp.
 *
 * Android path: /data/local/atlas-linux/var/lib/atlas-auth
 * Inside Deb (LP as root or bind): /var/lib/atlas-auth
 */
#define AUTH_ON_LP "/data/local/atlas-linux/var/lib/atlas-auth"
#define AUTH_IN_DEB "/var/lib/atlas-auth"

static const char *home_dir(void) {
  const char *h = getenv("ATLAS_HOME");
  if (h && h[0] && access(h, F_OK) == 0) return h;
  h = getenv("HOME");
  if (h && h[0] && access(h, F_OK) == 0) return h;
  /* F_OK only — X_OK fails under some SELinux/ksu contexts */
  if (access("/data/user/0/com.titanus2.atlas/files", F_OK) == 0)
    return "/data/user/0/com.titanus2.atlas/files";
  if (access("/data/data/com.titanus2.atlas/files", F_OK) == 0)
    return "/data/data/com.titanus2.atlas/files";
  return NULL;
}

static int mkdir_p_mode(const char *path, mode_t mode) {
  char tmp[512];
  size_t len = strlen(path);
  if (len == 0 || len >= sizeof tmp) return -1;
  memcpy(tmp, path, len + 1);
  for (char *p = tmp + 1; *p; p++) {
    if (*p != '/') continue;
    *p = 0;
    if (mkdir(tmp, mode) != 0 && errno != EEXIST) return -1;
    *p = '/';
  }
  if (mkdir(tmp, mode) != 0 && errno != EEXIST) return -1;
  return 0;
}

static int ensure_auth_dir(char *out, size_t n) {
  /* 1) explicit env (must be LP path in product) */
  const char *env = getenv("ATLAS_AUTH_DIR");
  if (env && env[0]) {
    snprintf(out, n, "%s", env);
  } else if (access(AUTH_ON_LP, F_OK) == 0 || access("/data/local/atlas-linux", F_OK) == 0) {
    /* 2) LAW: super LP plane (survives wipe) */
    snprintf(out, n, "%s", AUTH_ON_LP);
  } else if (access(AUTH_IN_DEB, F_OK) == 0) {
    /* 3) inside Deb when merge is LP / auth bind present */
    snprintf(out, n, "%s", AUTH_IN_DEB);
  } else {
    /* 4) last resort: still prefer LP path string so ensure can create after mount */
    snprintf(out, n, "%s", AUTH_ON_LP);
  }
  /* 0777: app UID + Deb admin both write req/ok/fail (ticket shared). */
  if (mkdir_p_mode(out, 0777) != 0) {
    if (access(out, F_OK) != 0) return -1;
  }
  chmod(out, 0777);
  return 0;
}

static void touch(const char *path) {
  int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
  if (fd >= 0) {
    (void)write(fd, "1\n", 2);
    close(fd);
  }
}

/* Intent flags for AuthPromptActivity:
 *   NEW_TASK | MULTIPLE_TASK | EXCLUDE_FROM_RECENTS | NO_USER_ACTION
 *   = 0x10000000 | 0x08000000 | 0x00800000 | 0x00040000 = 0x18840000
 * NEVER CLEAR_TOP/CLEAR_TASK — those relaunched MainActivity and reloaded
 * the Deb shell after apt/sudo biometrics ("finger OK → shell restarts"). */
#define ATLAS_AUTH_AM_FLAGS "0x18840000"

/* Touch wake only — FileObserver already launched UI. Do not am-start again. */
static void nudge_wake_only(const char *auth_dir) {
  char wake[512];
  snprintf(wake, sizeof wake, "%s/wake", auth_dir);
  touch(wake);
}

/* Wake Atlas host once: FileObserver + explicit AuthPrompt activity. */
static void wake_host(const char *auth_dir, const char *id, const char *reason,
                      int am_start) {
  char wake[512];
  snprintf(wake, sizeof wake, "%s/wake", auth_dir);
  touch(wake);
  if (!am_start) return;

  /* Prefer /system/bin/am — works when Atlas is installed. */
  char id_arg[128], reason_arg[512];
  snprintf(id_arg, sizeof id_arg, "%s", id);
  snprintf(reason_arg, sizeof reason_arg, "%s", reason ? reason : "Atlas privilege");

  pid_t p = fork();
  if (p == 0) {
    /* child: am start, discard output */
    int devnull = open("/dev/null", O_RDWR);
    if (devnull >= 0) {
      dup2(devnull, 1);
      dup2(devnull, 2);
      if (devnull > 2) close(devnull);
    }
    /* NEW_TASK|MULTIPLE_TASK|EXCLUDE_FROM_RECENTS|NO_USER_ACTION — never CLEAR_* */
    execl("/system/bin/am", "am", "start",
         "-n", "com.titanus2.atlas/.AuthPromptActivity",
         "-a", "com.titanus2.atlas.action.AUTH_PROMPT",
         "--es", "auth_id", id_arg,
         "--es", "auth_reason", reason_arg,
         "-f", ATLAS_AUTH_AM_FLAGS,
         (char *)NULL);
    execl("/system/bin/am", "am", "start",
         "-n", "com.titanus2.atlas/.AuthPromptActivity",
         "--es", "auth_id", id_arg,
         "--es", "auth_reason", reason_arg,
         "-f", ATLAS_AUTH_AM_FLAGS,
         (char *)NULL);
    _exit(127);
  }
  if (p > 0) {
    int st = 0;
    waitpid(p, &st, 0);
  }
}

static int file_exists(const char *path) {
  return access(path, F_OK) == 0;
}

/* Short-lived agent ticket: auth/ticket first field = expiry unix seconds. */
static int ticket_valid(const char *auth_dir) {
  char path[640];
  if (auth_dir && auth_dir[0]) {
    snprintf(path, sizeof path, "%s/ticket", auth_dir);
    FILE *f = fopen(path, "r");
    if (f) {
      long exp = 0;
      int n = fscanf(f, "%ld", &exp);
      fclose(f);
      if (n == 1 && exp > (long)time(NULL)) return 1;
    }
  }
  /* SELinux-friendly mirror */
  FILE *f = fopen("/data/local/tmp/atlas_auth.ticket", "r");
  if (!f) return 0;
  long exp = 0;
  int n = fscanf(f, "%ld", &exp);
  fclose(f);
  return n == 1 && exp > (long)time(NULL);
}

static void write_ticket(const char *auth_dir, int ttl) {
  if (ttl <= 0) ttl = 1800;
  long exp = (long)time(NULL) + ttl;
  if (auth_dir && auth_dir[0]) {
    char path[640];
    snprintf(path, sizeof path, "%s/ticket", auth_dir);
    FILE *f = fopen(path, "w");
    if (f) {
      fprintf(f, "%ld %d\n", exp, ttl);
      fclose(f);
      chmod(path, 0644);
    }
  }
  /* Always mirror for enterd / shell / chroot clients */
  FILE *m = fopen("/data/local/tmp/atlas_auth.ticket", "w");
  if (m) {
    fprintf(m, "%ld %d\n", exp, ttl);
    fclose(m);
    chmod("/data/local/tmp/atlas_auth.ticket", 0644);
  }
}

/*
 * Blocking request to Atlas Authentication Agent (host FGS + biometrics).
 * returns 0 grant, 1 deny, 3 timeout, 4 error
 */
/* Resolve auth dir for ticket check even when mkdir is denied (shell tip prove). */
static int resolve_auth_dir_readonly(char *out, size_t n) {
  const char *env = getenv("ATLAS_AUTH_DIR");
  if (env && env[0] && access(env, R_OK) == 0) {
    snprintf(out, n, "%s", env);
    return 0;
  }
  /* LAW: LP first (survives wipe). CE files/auth is not product. */
  static const char *cands[] = {
      AUTH_ON_LP,
      AUTH_IN_DEB,
      NULL};
  for (int i = 0; cands[i]; i++) {
    if (access(cands[i], R_OK) == 0) {
      snprintf(out, n, "%s", cands[i]);
      return 0;
    }
  }
  return -1;
}

int atlas_auth_request(const char *reason, int timeout_sec) {
  char auth_dir[512];
  if (!reason || !reason[0]) reason = "Atlas privilege";
  /* Human finger + lock-screen: default 180s (was 90; hybrid am re-start
   * used to kill the sheet before users finished). */
  if (timeout_sec <= 0) timeout_sec = 180;

  /* Agent ticket first — even when we cannot create auth/ (SELinux shell).
   * ATLAS_FORCE_AUTH=1 (apt package ops) always prompts the human again. */
  {
    const char *force = getenv("ATLAS_FORCE_AUTH");
    char tdir[512];
    if (!(force && force[0] == '1')) {
      if (resolve_auth_dir_readonly(tdir, sizeof tdir) == 0 && ticket_valid(tdir)) {
        return 0;
      }
    }
  }

  if (ensure_auth_dir(auth_dir, sizeof auth_dir) != 0) {
    fprintf(stderr,
            "atlas-auth: cannot create auth dir on super LP\n"
            "  need: atlas-lpctl mount && atlas-lpctl auth-ensure\n"
            "  path: %s (survives wipe)\n"
            "  set ATLAS_AUTH_DIR if Deb bind uses /var/lib/atlas-auth\n",
            AUTH_ON_LP);
    return 4;
  }

  char id[64];
  snprintf(id, sizeof id, "%d-%ld", (int)getpid(), (long)time(NULL));

  char req[640], ok[640], fail[640], busy[640];
  snprintf(req, sizeof req, "%s/req.%s", auth_dir, id);
  snprintf(ok, sizeof ok, "%s/ok.%s", auth_dir, id);
  snprintf(fail, sizeof fail, "%s/fail.%s", auth_dir, id);
  snprintf(busy, sizeof busy, "%s/busy.%s", auth_dir, id);

  unlink(ok);
  unlink(fail);
  unlink(busy);

  int fd = open(req, O_WRONLY | O_CREAT | O_EXCL | O_TRUNC, 0600);
  if (fd < 0) {
    fprintf(stderr, "atlas-auth: cannot write %s: %s\n", req, strerror(errno));
    return 4;
  }
  (void)write(fd, reason, strlen(reason));
  (void)write(fd, "\n", 1);
  fsync(fd);
  close(fd);

  /* One am start only. Re-am-start with CLEAR_TASK used to destroy the
   * biometric dialog every 5s (Debian hybrid "can't wait" for finger). */
  wake_host(auth_dir, id, reason, 1);

  time_t deadline = time(NULL) + timeout_sec;
  time_t last_nudge = time(NULL);
  time_t last_am = time(NULL);
  int am_retries = 0;
  while (time(NULL) < deadline) {
    if (file_exists(ok)) {
      unlink(ok);
      unlink(fail);
      unlink(req);
      unlink(busy);
      write_ticket(auth_dir, 1800);
      return 0;
    }
    if (file_exists(fail)) {
      unlink(ok);
      unlink(fail);
      unlink(req);
      unlink(busy);
      return 1;
    }
    time_t now = time(NULL);
    /* Soft nudge FileObserver only — never restart AuthPrompt while waiting. */
    if (now - last_nudge >= 3) {
      nudge_wake_only(auth_dir);
      last_nudge = now;
    }
    /* If host never claimed (no busy.*) for 12s, one more am start (max 2).
     * Covers Atlas killed / first am failed under chroot — not mid-prompt. */
    if (am_retries < 2 && now - last_am >= 12
        && !file_exists(busy) && !file_exists(ok) && !file_exists(fail)) {
      wake_host(auth_dir, id, reason, 1);
      last_am = now;
      am_retries++;
    }
    struct timespec ts = {0, 150 * 1000 * 1000}; /* 150ms */
    nanosleep(&ts, NULL);
  }
  unlink(ok);
  unlink(fail);
  unlink(req);
  unlink(busy);
  fprintf(stderr, "atlas-auth: timeout (%ds) — unlock phone / open Atlas\n", timeout_sec);
  return 3;
}

static int cmd_status(void) {
  char auth_dir[512];
  if (ensure_auth_dir(auth_dir, sizeof auth_dir) != 0) {
    printf("auth_dir=missing\n");
    return 4;
  }
  printf("role=authentication-agent-client\n");
  printf("home=%s\n", home_dir() ? home_dir() : "");
  printf("auth_dir=%s\n", auth_dir);
  printf("protocol=file:req/ok/fail/ticket + AtlasSessionService\n");
  printf("ticket=%s\n", ticket_valid(auth_dir) ? "valid" : "none");
  printf("version=%s\n", ATLAS_VERSION);
  return 0;
}

int main(int argc, char **argv) {
  if (argc < 2) {
    fprintf(stderr,
            "Atlas Authentication Agent client\n"
            "usage: atlas-auth request [-t sec] <reason...>\n"
            "       atlas-auth status|version|clear-ticket\n"
            "Agent host: AtlasSessionService (biometrics).\n"
            "Debian: SUDO_ASKPASS=atlas-auth-askpass with real /usr/bin/sudo -A\n"
            "Android: sudo client asks agent then /system/bin/su\n");
    return 2;
  }
  const char *cmd = argv[1];
  if (!strcmp(cmd, "version") || !strcmp(cmd, "-v") || !strcmp(cmd, "--version")) {
    printf("atlas-auth %s\n", ATLAS_VERSION);
    return 0;
  }
  if (!strcmp(cmd, "status")) return cmd_status();
  if (!strcmp(cmd, "clear-ticket")) {
    char auth_dir[512];
    if (ensure_auth_dir(auth_dir, sizeof auth_dir) == 0) {
      char path[640];
      snprintf(path, sizeof path, "%s/ticket", auth_dir);
      unlink(path);
    }
    return 0;
  }
  if (!strcmp(cmd, "request") || !strcmp(cmd, "ask") || !strcmp(cmd, "sudo")) {
    int t = 180;
    int i = 2;
    if (i < argc && (!strcmp(argv[i], "-t") || !strcmp(argv[i], "--timeout"))) {
      if (i + 1 >= argc) return 2;
      t = atoi(argv[i + 1]);
      i += 2;
    }
    char reason[1024];
    reason[0] = 0;
    for (; i < argc; i++) {
      if (reason[0]) strncat(reason, " ", sizeof reason - strlen(reason) - 1);
      strncat(reason, argv[i], sizeof reason - strlen(reason) - 1);
    }
    if (!reason[0]) snprintf(reason, sizeof reason, "Atlas privilege");
    int rc = atlas_auth_request(reason, t);
    /* Stay quiet on success — terminal spam after grant was a product bug.
     * Exit code 0 is the contract; tools should not parse "granted" text. */
    if (rc == 1) {
      fprintf(stderr, "atlas-auth: denied\n");
    }
    return rc;
  }
  fprintf(stderr, "atlas-auth: unknown command '%s'\n", cmd);
  return 2;
}
