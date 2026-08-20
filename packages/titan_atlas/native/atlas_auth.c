/*
 * atlas-auth — one wrapper for every privileged binary.
 *
 * LAW: a grant unlocks THAT command, not the world.
 *   atlas-auth exec [--scope name] [--] <cmd> [args…]
 *     → biometric (unless scoped ticket / bio off) → exec the locked binary
 *   atlas-auth request [--scope name] [-t sec] <reason…>
 *     → same gate without exec (sudo askpass / settings)
 *
 * Tickets are ticket.<scope> only. Never a blanket ticket.
 * Strict plane (titan2_atlas_auth_strict=1) or TTL=0 → auth every call.
 * ticket.exec lifetime is the Settings slider (titan2_atlas_ticket_ttl).
 * Never a 15s leftover that disagrees with the UI.
 *
 * Protocol (filesystem):
 *   $AUTH/req.<id>   client writes reason + scope + cmd
 *   $AUTH/ok.<id>    host grants after biometrics
 *   $AUTH/fail.<id>  host denies
 *   $AUTH/ticket.<scope>  skip-bio for this argv0 only
 *   $AUTH/ticket.exec     enterd one-shot
 *   $AUTH/log.jsonl       every request
 *   $AUTH/wake            nudge FileObserver
 *
 * Host: AtlasSessionService FileObserver + AuthPromptActivity
 * Exit: 0 grant · 1 deny · 2 usage · 3 timeout · 4 setup error
 */
#define _GNU_SOURCE
#include <dirent.h>
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
#include "atlas_bridge_class.h"

#ifndef ATLAS_VERSION
#define ATLAS_VERSION "1.1.6-grant-exec"
#endif

#define AUTH_ON_LP "/data/local/atlas-linux/var/lib/atlas-auth"
#define AUTH_IN_DEB "/var/lib/atlas-auth"
#define DEFAULT_TICKET_TTL 60

static const char *home_dir(void) {
  const char *h = getenv("ATLAS_HOME");
  if (h && h[0] && access(h, F_OK) == 0) return h;
  h = getenv("HOME");
  if (h && h[0] && access(h, F_OK) == 0) return h;
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
  const char *env = getenv("ATLAS_AUTH_DIR");
  if (env && env[0]) {
    snprintf(out, n, "%s", env);
  } else if (access(AUTH_ON_LP, F_OK) == 0 || access("/data/local/atlas-linux", F_OK) == 0) {
    snprintf(out, n, "%s", AUTH_ON_LP);
  } else if (access(AUTH_IN_DEB, F_OK) == 0) {
    snprintf(out, n, "%s", AUTH_IN_DEB);
  } else {
    snprintf(out, n, "%s", AUTH_ON_LP);
  }
  if (mkdir_p_mode(out, 0777) != 0) {
    if (access(out, F_OK) != 0) return -1;
  }
  chmod(out, 0777);
  return 0;
}

static int resolve_auth_dir_readonly(char *out, size_t n) {
  const char *env = getenv("ATLAS_AUTH_DIR");
  if (env && env[0] && access(env, R_OK) == 0) {
    snprintf(out, n, "%s", env);
    return 0;
  }
  static const char *cands[] = {AUTH_ON_LP, AUTH_IN_DEB, NULL};
  for (int i = 0; cands[i]; i++) {
    if (access(cands[i], R_OK) == 0) {
      snprintf(out, n, "%s", cands[i]);
      return 0;
    }
  }
  return -1;
}

static void sanit_scope(const char *in, char *out, size_t n) {
  size_t o = 0;
  if (in) {
    const char *slash = strrchr(in, '/');
    if (slash && slash[1]) in = slash + 1;
  }
  for (; in && *in && o + 1 < n; in++) {
    char c = *in;
    if (c >= 'A' && c <= 'Z') c = (char)(c - 'A' + 'a');
    if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_')
      out[o++] = c;
  }
  if (!o)
    snprintf(out, n, "ask");
  else
    out[o] = 0;
}

static int plane_int(const char **paths, int fallback) {
  char buf[32];
  for (int i = 0; paths && paths[i]; i++) {
    FILE *f = fopen(paths[i], "r");
    if (!f) continue;
    memset(buf, 0, sizeof buf);
    if (!fgets(buf, sizeof buf, f)) {
      fclose(f);
      continue;
    }
    fclose(f);
    char *p = buf;
    while (*p == ' ' || *p == '\t') p++;
    if (*p == 0 || *p == '\n') continue;
    return atoi(p);
  }
  return fallback;
}

static int policy_strict(void) {
  const char *e = getenv("ATLAS_FORCE_AUTH");
  if (e && e[0] == '1') return 1;
  e = getenv("ATLAS_AUTH_STRICT");
  if (e && e[0] == '1') return 1;
  static const char *p[] = {
      AUTH_ON_LP "/titan2_atlas_auth_strict",
      AUTH_IN_DEB "/titan2_atlas_auth_strict",
      "/data/misc/titan2/titan2_atlas_auth_strict",
      "/data/local/tmp/titan2_atlas_auth_strict",
      NULL};
  int v = plane_int(p, 0);
  return v == 1;
}

static int policy_ttl(void) {
  if (policy_strict()) return 0;
  const char *e = getenv("ATLAS_TICKET_TTL");
  if (e && e[0]) {
    int t = atoi(e);
    if (t < 0) t = 0;
    if (t > 1800) t = 1800;
    return t;
  }
  static const char *p[] = {
      AUTH_ON_LP "/titan2_atlas_ticket_ttl",
      AUTH_IN_DEB "/titan2_atlas_ticket_ttl",
      "/data/misc/titan2/titan2_atlas_ticket_ttl",
      "/data/local/tmp/titan2_atlas_ticket_ttl",
      NULL};
  int t = plane_int(p, DEFAULT_TICKET_TTL);
  if (t < 0) t = 0;
  if (t > 1800) t = 1800;
  return t;
}

static int ticket_file_valid(const char *path) {
  FILE *f = fopen(path, "r");
  if (!f) return 0;
  long exp = 0;
  int ttl = 0;
  int n = fscanf(f, "%ld %d", &exp, &ttl);
  fclose(f);
  if (n < 2 || ttl <= 0) return 0;
  long now = (long)time(NULL);
  return exp > now && exp <= now + ttl + 5;
}

static int capture_scope(const char *scope) {
  return atlas_bridge_capture_name(scope);
}

static int scoped_ticket_valid(const char *auth_dir, const char *scope) {
  if (policy_strict()) return 0;
  if (policy_ttl() <= 0) return 0;
  /* Leftover ticket.screencap must not mint ticket.exec (08-19 live heresy). */
  if (capture_scope(scope)) return 0;
  if (!scope || !scope[0] || !strcmp(scope, "exec")) return 0;
  char path[640];
  if (auth_dir && auth_dir[0]) {
    snprintf(path, sizeof path, "%s/ticket.%s", auth_dir, scope);
    if (ticket_file_valid(path)) return 1;
  }
  snprintf(path, sizeof path, AUTH_ON_LP "/ticket.%s", scope);
  if (ticket_file_valid(path)) return 1;
  snprintf(path, sizeof path, AUTH_IN_DEB "/ticket.%s", scope);
  if (ticket_file_valid(path)) return 1;
  return 0;
}

static void write_ticket_file(const char *path, int ttl) {
  if (ttl <= 0) return;
  long exp = (long)time(NULL) + ttl;
  FILE *f = fopen(path, "w");
  if (!f) return;
  fprintf(f, "%ld %d\n", exp, ttl);
  fclose(f);
  chmod(path, 0644);
}

static void write_exec_token(const char *auth_dir) {
  int ttl = policy_ttl();
  char path[640];
  if (ttl <= 0) return;
  if (auth_dir && auth_dir[0]) {
    snprintf(path, sizeof path, "%s/ticket.exec", auth_dir);
    write_ticket_file(path, ttl);
  }
  write_ticket_file(AUTH_ON_LP "/ticket.exec", ttl);
  write_ticket_file(AUTH_IN_DEB "/ticket.exec", ttl);
}

static int looks_like_capture(const char *scope, const char *reason, const char *cmd) {
  if (capture_scope(scope)) return 1;
  const char *blob[3] = { scope, reason, cmd };
  for (int i = 0; i < 3; i++) {
    if (!blob[i] || !blob[i][0]) continue;
    if (strcasestr(blob[i], "screencap") || strcasestr(blob[i], "screenshot")
        || strcasestr(blob[i], "nsenter") || strcasestr(blob[i], "capture"))
      return 1;
  }
  return 0;
}

static void write_scoped_ticket(const char *auth_dir, const char *scope,
                                const char *reason, const char *cmd) {
  int ttl = policy_ttl();
  if (ttl <= 0) return;
  int cap = looks_like_capture(scope, reason, cmd);
  /* Human Approve (ok file) for capture always mints ticket.exec at UI TTL.
   * scope=ask + "display capture" was 390c: grant ok, tickets=none. */
  if (cap) write_exec_token(auth_dir);
  const char *sc = scope;
  if (!sc || !sc[0] || !strcmp(sc, "ask") || !strcmp(sc, "exec"))
    sc = cap ? "screencap" : NULL;
  if (!sc) return;
  char path[640];
  if (auth_dir && auth_dir[0]) {
    snprintf(path, sizeof path, "%s/ticket.%s", auth_dir, sc);
    write_ticket_file(path, ttl);
  }
}

static void json_esc(const char *in, char *out, size_t n) {
  size_t o = 0;
  if (!in) in = "";
  for (; *in && o + 2 < n; in++) {
    unsigned char c = (unsigned char)*in;
    if (c == '"' || c == '\\') {
      if (o + 3 >= n) break;
      out[o++] = '\\';
      out[o++] = (char)c;
    } else if (c == '\n' || c == '\r' || c == '\t') {
      if (o + 3 >= n) break;
      out[o++] = '\\';
      out[o++] = (c == '\n') ? 'n' : (c == '\r') ? 'r' : 't';
    } else if (c < 32) {
      continue;
    } else {
      out[o++] = (char)c;
    }
  }
  out[o] = 0;
}

static void append_log(const char *auth_dir, const char *event, const char *scope,
                       const char *reason, const char *cmd, const char *result) {
  char path[640];
  if (!auth_dir || !auth_dir[0]) return;
  snprintf(path, sizeof path, "%s/log.jsonl", auth_dir);
  FILE *f = fopen(path, "a");
  if (!f) return;
  char er[256], es[64], ee[32], ec[512], ev[32];
  json_esc(reason, er, sizeof er);
  json_esc(scope, es, sizeof es);
  json_esc(event, ee, sizeof ee);
  json_esc(cmd ? cmd : "", ec, sizeof ec);
  json_esc(result ? result : "", ev, sizeof ev);
  fprintf(f,
          "{\"ts\":%ld,\"event\":\"%s\",\"scope\":\"%s\",\"reason\":\"%s\","
          "\"cmd\":\"%s\",\"result\":\"%s\",\"pid\":%d,\"uid\":%d}\n",
          (long)time(NULL), ee, es, er, ec, ev, (int)getpid(), (int)getuid());
  fclose(f);
  chmod(path, 0666);
  /* rotate ~512K */
  struct stat st;
  if (stat(path, &st) == 0 && st.st_size > 512 * 1024) {
    char bak[656];
    snprintf(bak, sizeof bak, "%s.1", path);
    unlink(bak);
    rename(path, bak);
  }
}

static void touch(const char *path) {
  int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
  if (fd >= 0) {
    (void)write(fd, "1\n", 2);
    close(fd);
  }
}

#define ATLAS_AUTH_AM_FLAGS "0x18840000"

static void nudge_wake_only(const char *auth_dir) {
  char wake[512];
  snprintf(wake, sizeof wake, "%s/wake", auth_dir);
  touch(wake);
}

static void wake_host(const char *auth_dir, const char *id, const char *reason,
                      int am_start) {
  char wake[512];
  snprintf(wake, sizeof wake, "%s/wake", auth_dir);
  touch(wake);
  if (!am_start) return;

  char id_arg[128], reason_arg[512];
  snprintf(id_arg, sizeof id_arg, "%s", id);
  snprintf(reason_arg, sizeof reason_arg, "%s", reason ? reason : "Atlas privilege");

  pid_t p = fork();
  if (p == 0) {
    int devnull = open("/dev/null", O_RDWR);
    if (devnull >= 0) {
      dup2(devnull, 1);
      dup2(devnull, 2);
      if (devnull > 2) close(devnull);
    }
    setenv("PATH", "/system/bin:/system/xbin", 1);
    setenv("ATLAS_AUTH_RECURSE", "1", 1);
    execl("/system/bin/am", "am", "start",
         "-n", "com.titanus2.atlas/.AuthPromptActivity",
         "-a", "com.titanus2.atlas.action.AUTH_PROMPT",
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

static int atlas_auth_request(const char *reason, int timeout_sec,
                              const char *scope, const char *cmd) {
  char auth_dir[512];
  char sc[64];
  sanit_scope(scope && scope[0] ? scope : "ask", sc, sizeof sc);
  if (!reason || !reason[0]) reason = "Atlas privilege";
  if (timeout_sec <= 0) timeout_sec = 25;

  {
    char tdir[512];
    if (resolve_auth_dir_readonly(tdir, sizeof tdir) == 0 &&
        scoped_ticket_valid(tdir, sc)) {
      write_exec_token(tdir);
      append_log(tdir, "skip", sc, reason, cmd, "ticket");
      return 0;
    }
  }

  if (ensure_auth_dir(auth_dir, sizeof auth_dir) != 0) {
    fprintf(stderr,
            "atlas-auth: cannot create auth dir on super LP\n"
            "  need: atlas-lpctl mount && atlas-lpctl auth-ensure\n"
            "  path: %s\n",
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

  /* 0666: Atlas host (non-root) must read scope= or bio-off silent-grants
   * as scope=ask (live 08-20: screencap reqs logged host-claim ask). */
  int fd = open(req, O_WRONLY | O_CREAT | O_EXCL | O_TRUNC, 0666);
  if (fd < 0) {
    fprintf(stderr, "atlas-auth: cannot write %s: %s\n", req, strerror(errno));
    return 4;
  }
  dprintf(fd, "%s\nscope=%s\n", reason, sc);
  if (cmd && cmd[0]) dprintf(fd, "cmd=%s\n", cmd);
  fsync(fd);
  close(fd);
  chmod(req, 0666);
  append_log(auth_dir, "request", sc, reason, cmd, "pending");

  {
    const char *want_am = getenv("ATLAS_AUTH_AM");
    int am = (want_am && want_am[0] == '1') ? 1 : 0;
    wake_host(auth_dir, id, reason, am);
  }

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
      write_scoped_ticket(auth_dir, sc, reason, cmd);
      append_log(auth_dir, "grant", sc, reason, cmd, "ok");
      return 0;
    }
    if (file_exists(fail)) {
      unlink(ok);
      unlink(fail);
      unlink(req);
      unlink(busy);
      append_log(auth_dir, "deny", sc, reason, cmd, "fail");
      return 1;
    }
    time_t now = time(NULL);
    if (now - last_nudge >= 3) {
      nudge_wake_only(auth_dir);
      last_nudge = now;
    }
    if (am_retries < 2 && now - last_am >= 12
        && !file_exists(busy) && !file_exists(ok) && !file_exists(fail)) {
      const char *want_am = getenv("ATLAS_AUTH_AM");
      int am = (want_am && want_am[0] == '1') ? 1 : 0;
      wake_host(auth_dir, id, reason, am);
      last_am = now;
      am_retries++;
    }
    struct timespec ts = {0, 150 * 1000 * 1000};
    nanosleep(&ts, NULL);
  }
  unlink(ok);
  unlink(fail);
  unlink(req);
  unlink(busy);
  append_log(auth_dir, "timeout", sc, reason, cmd, "timeout");
  fprintf(stderr, "atlas-auth: timeout (%ds) — unlock phone / open Atlas\n", timeout_sec);
  return 3;
}

static void join_args(char **argv, char *out, size_t n) {
  size_t o = 0;
  out[0] = 0;
  for (int i = 0; argv && argv[i]; i++) {
    size_t l = strlen(argv[i]);
    if (o && o + 1 < n) out[o++] = ' ';
    if (o + l >= n) {
      memcpy(out + o, argv[i], n - o - 1);
      out[n - 1] = 0;
      return;
    }
    memcpy(out + o, argv[i], l);
    o += l;
    out[o] = 0;
  }
}

static int cmd_exec(int argc, char **argv) {
  int timeout = 25;
  const char *scope_in = NULL;
  int i = 0;
  while (i < argc) {
    if (!strcmp(argv[i], "--")) {
      i++;
      break;
    }
    if ((!strcmp(argv[i], "-t") || !strcmp(argv[i], "--timeout")) && i + 1 < argc) {
      timeout = atoi(argv[i + 1]);
      i += 2;
      continue;
    }
    if ((!strcmp(argv[i], "--scope") || !strcmp(argv[i], "-s")) && i + 1 < argc) {
      scope_in = argv[i + 1];
      i += 2;
      continue;
    }
    break;
  }
  if (i >= argc) {
    fprintf(stderr, "usage: atlas-auth exec [--scope name] [--] <cmd> [args…]\n");
    return 2;
  }
  char **cmdv = argv + i;
  const char *bin = cmdv[0];
  if (!bin || !bin[0]) return 2;
  const char *base = strrchr(bin, '/');
  base = base ? base + 1 : bin;
  if (!strcmp(base, "atlas-auth")) {
    fprintf(stderr, "atlas-auth: refusing to wrap itself\n");
    return 2;
  }
  char sc[64];
  sanit_scope(scope_in ? scope_in : base, sc, sizeof sc);
  char cmdline[768];
  join_args(cmdv, cmdline, sizeof cmdline);
  char reason[160];
  snprintf(reason, sizeof reason, "exec %s", sc);

  int rc = atlas_auth_request(reason, timeout, sc, cmdline);
  if (rc != 0) {
    if (rc == 1) fprintf(stderr, "atlas-auth: denied\n");
    return rc;
  }
  setenv("ATLAS_AUTH_DONE", "1", 1);
  {
    char ad[512];
    if (ensure_auth_dir(ad, sizeof ad) == 0)
      append_log(ad, "exec", sc, reason, cmdline, "exec");
  }
  execvp(bin, cmdv);
  fprintf(stderr, "atlas-auth: exec %s: %s\n", bin, strerror(errno));
  return 127;
}

static int cmd_status(void) {
  char auth_dir[512];
  if (ensure_auth_dir(auth_dir, sizeof auth_dir) != 0) {
    printf("auth_dir=missing\n");
    return 4;
  }
  int strict = policy_strict();
  int ttl = policy_ttl();
  printf("role=privilege-wrapper\n");
  printf("home=%s\n", home_dir() ? home_dir() : "");
  printf("auth_dir=%s\n", auth_dir);
  printf("protocol=file:req/ok/fail/ticket.<scope>+ticket.exec+log.jsonl\n");
  printf("strict=%s\n", strict ? "on" : "off");
  printf("ttl=%d\n", ttl);
  printf("blanket=forbidden\n");
  DIR *d = opendir(auth_dir);
  int n = 0;
  if (d) {
    struct dirent *e;
    while ((e = readdir(d))) {
      if (strncmp(e->d_name, "ticket.", 7) != 0) continue;
      char p[640];
      snprintf(p, sizeof p, "%s/%s", auth_dir, e->d_name);
      printf("ticket.%s=%s\n", e->d_name + 7,
             ticket_file_valid(p) ? "valid" : "stale");
      n++;
    }
    closedir(d);
  }
  if (!n) printf("tickets=none\n");
  printf("version=%s\n", ATLAS_VERSION);
  return 0;
}

static int cmd_clear(const char *scope) {
  char auth_dir[512];
  if (ensure_auth_dir(auth_dir, sizeof auth_dir) != 0) return 4;
  if (scope && scope[0]) {
    char sc[64], path[640];
    sanit_scope(scope, sc, sizeof sc);
    snprintf(path, sizeof path, "%s/ticket.%s", auth_dir, sc);
    unlink(path);
    return 0;
  }
  unlink("/data/local/tmp/atlas_auth.ticket");
  DIR *d = opendir(auth_dir);
  if (!d) return 0;
  struct dirent *e;
  while ((e = readdir(d))) {
    if (!strcmp(e->d_name, "ticket") || strncmp(e->d_name, "ticket.", 7) == 0) {
      char path[640];
      snprintf(path, sizeof path, "%s/%s", auth_dir, e->d_name);
      unlink(path);
    }
  }
  closedir(d);
  return 0;
}

int main(int argc, char **argv) {
  if (argc < 2) {
    fprintf(stderr,
            "Atlas privilege wrapper\n"
            "usage: atlas-auth exec [--scope name] [--] <cmd> [args…]\n"
            "       atlas-auth request [--scope name] [-t sec] <reason…>\n"
            "       atlas-auth status|version|clear-ticket [scope]\n"
            "One wrap. After auth, the locked binary runs.\n");
    return 2;
  }
  const char *cmd = argv[1];
  if (!strcmp(cmd, "version") || !strcmp(cmd, "-v") || !strcmp(cmd, "--version")) {
    printf("atlas-auth %s\n", ATLAS_VERSION);
    return 0;
  }
  if (!strcmp(cmd, "status")) return cmd_status();
  if (!strcmp(cmd, "clear-ticket"))
    return cmd_clear(argc >= 3 ? argv[2] : NULL);
  if (!strcmp(cmd, "exec") || !strcmp(cmd, "run") || !strcmp(cmd, "wrap"))
    return cmd_exec(argc - 2, argv + 2);
  if (!strcmp(cmd, "request") || !strcmp(cmd, "ask") || !strcmp(cmd, "sudo")) {
    int t = 25;
    const char *scope = NULL;
    int i = 2;
    while (i < argc) {
      if ((!strcmp(argv[i], "-t") || !strcmp(argv[i], "--timeout")) && i + 1 < argc) {
        t = atoi(argv[i + 1]);
        i += 2;
        continue;
      }
      if ((!strcmp(argv[i], "--scope") || !strcmp(argv[i], "-s")) && i + 1 < argc) {
        scope = argv[i + 1];
        i += 2;
        continue;
      }
      if (!strcmp(argv[i], "--")) {
        i++;
        break;
      }
      break;
    }
    char reason[1024];
    reason[0] = 0;
    for (; i < argc; i++) {
      if (reason[0]) strncat(reason, " ", sizeof reason - strlen(reason) - 1);
      strncat(reason, argv[i], sizeof reason - strlen(reason) - 1);
    }
    if (!reason[0]) snprintf(reason, sizeof reason, "Atlas privilege");
    if (!scope) {
      scope = looks_like_capture(NULL, reason, NULL) ? "screencap" : "ask";
    }
    int rc = atlas_auth_request(reason, t, scope, NULL);
    if (rc == 1) fprintf(stderr, "atlas-auth: denied\n");
    return rc;
  }
  fprintf(stderr, "atlas-auth: unknown command '%s'\n", cmd);
  return 2;
}
