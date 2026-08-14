/*
 * atlas-enter — rootless Deb client. Talks to atlas-enterd (init root).
 * No Magisk. No setuid.
 *
 * Connect order: abstract @atlasenter, then /data/local/tmp/atlas-enter.sock
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#ifndef ATLAS_VERSION
#define ATLAS_VERSION "1.1.0"
#endif

#define ABS_NAME "atlasenter"
#define SOCK_FS "/data/local/tmp/atlas-enter.sock"
#define MERGE_DEFAULT "/data/local/atlas-hybrid/merge"
#define HYBRID_SH "/system/bin/atlas-hybrid.sh"
#define ATLAS_PKG_DATA "/data/data/com.titanus2.atlas"
#define ATLAS_PKG_USER "/data/user/0/com.titanus2.atlas"

static void die(int code, const char *msg) {
  fprintf(stderr, "atlas-enter: %s\n", msg);
  exit(code);
}

static uid_t pkg_uid(void) {
  struct stat st;
  if (stat(ATLAS_PKG_DATA, &st) == 0 && st.st_uid > 0) return st.st_uid;
  if (stat(ATLAS_PKG_USER, &st) == 0 && st.st_uid > 0) return st.st_uid;
  return 0;
}


static int connect_tcp(void) {
  int s = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (s < 0) return -1;
  struct sockaddr_in in;
  memset(&in, 0, sizeof(in));
  in.sin_family = AF_INET;
  in.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  in.sin_port = htons(17999);
  if (connect(s, (struct sockaddr *)&in, sizeof(in)) != 0) {
    close(s);
    return -1;
  }
  return s;
}

static int connect_abstract(void) {
  int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (s < 0) return -1;
  struct sockaddr_un addr;
  memset(&addr, 0, sizeof(addr));
  addr.sun_family = AF_UNIX;
  addr.sun_path[0] = '\0';
  memcpy(addr.sun_path + 1, ABS_NAME, strlen(ABS_NAME));
  socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(ABS_NAME));
  if (connect(s, (struct sockaddr *)&addr, len) != 0) {
    close(s);
    return -1;
  }
  return s;
}

static int connect_fs(const char *path) {
  int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (s < 0) return -1;
  struct sockaddr_un addr;
  memset(&addr, 0, sizeof(addr));
  addr.sun_family = AF_UNIX;
  snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", path);
  /* Must use path length — sizeof(addr) breaks connect on Android. */
  socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + strlen(addr.sun_path) + 1);
  if (connect(s, (struct sockaddr *)&addr, len) != 0) {
    close(s);
    return -1;
  }
  return s;
}

static int send_fd(int sock, int fd) {
  struct msghdr msg;
  struct iovec iov;
  char buf[1] = {0};
  char cmsgbuf[CMSG_SPACE(sizeof(int))];
  memset(&msg, 0, sizeof(msg));
  iov.iov_base = buf;
  iov.iov_len = 1;
  msg.msg_iov = &iov;
  msg.msg_iovlen = 1;
  msg.msg_control = cmsgbuf;
  msg.msg_controllen = sizeof(cmsgbuf);
  struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
  c->cmsg_level = SOL_SOCKET;
  c->cmsg_type = SCM_RIGHTS;
  c->cmsg_len = CMSG_LEN(sizeof(int));
  memcpy(CMSG_DATA(c), &fd, sizeof(int));
  return sendmsg(sock, &msg, 0) < 0 ? -1 : 0;
}

static int hybrid_ready(void) {
  if (access(MERGE_DEFAULT "/bin/bash", X_OK) == 0) return 1;
  if (access(MERGE_DEFAULT "/usr/bin/bash", X_OK) == 0) return 1;
  return 0;
}

static int local_root_enter(uid_t drop, const char *home, int do_ensure) {
  if (do_ensure || !hybrid_ready()) {
    pid_t p = fork();
    if (p == 0) {
      execl("/system/bin/sh", "sh", HYBRID_SH, "ensure", (char *)NULL);
      _exit(127);
    }
    int st = 0;
    waitpid(p, &st, 0);
  }
  if (!hybrid_ready()) die(80, "FATAL hybrid ensure failed");
  if (chdir(MERGE_DEFAULT) != 0 || chroot(MERGE_DEFAULT) != 0) die(78, "chroot failed");
  chdir("/");
  {
    const char *h = home;
    if (!h || !h[0] || access(h, X_OK) != 0) {
      if (access("/data/local/atlas-home/atlas", X_OK) == 0)
        h = "/data/local/atlas-home/atlas";
      else
        h = "/tmp";
    }
    setenv("HOME", h, 1);
    setenv("ATLAS_HOME", h, 1);
    (void)chdir(h);
  }
  setenv("ATLAS_HYBRID", "1", 1);
  setenv("ATLAS_PLANE", "hybrid", 1);
  setenv("ATLAS_MODE", "debian", 1);
  setenv("USER", "atlas", 1);
  setenv("LOGNAME", "atlas", 1);
  setenv("PS1", "debian:atlas:\\w\\$ ", 1);
  setenv("TERM", "xterm-256color", 1);
  unsetenv("LD_LIBRARY_PATH");
  if (setgid(drop) != 0 || setuid(drop) != 0) die(77, "drop failed");
  if (geteuid() == 0) die(77, "still root");
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
      if (access("/home/atlas/.grok/bin/grok", X_OK) == 0)
        execl("/home/atlas/.grok/bin/grok", "grok", "--resume", rid, (char *)NULL);
    }
  }
  const char *sh = access("/bin/bash", X_OK) == 0 ? "/bin/bash" : "/bin/sh";
  /* Interactive, not login — avoids broken -l profiles / nameless passwd noise */
  execl(sh, sh, "-i", (char *)NULL);
  die(78, "exec failed");
  return 78;
}

int main(int argc, char **argv) {
  uid_t ruid = getuid();
  uid_t euid = geteuid();
  uid_t drop = 0;
  const char *home = NULL;
  int do_ensure = 1;
  int argi = 1;

  while (argi < argc) {
    if (strcmp(argv[argi], "--") == 0) {
      argi++;
      break;
    }
    if (strcmp(argv[argi], "--uid") == 0 && argi + 1 < argc) {
      drop = (uid_t)strtoul(argv[++argi], NULL, 10);
      argi++;
      continue;
    }
    if (strcmp(argv[argi], "--home") == 0 && argi + 1 < argc) {
      home = argv[++argi];
      argi++;
      continue;
    }
    if (strcmp(argv[argi], "--ensure") == 0) {
      do_ensure = 1;
      argi++;
      continue;
    }
    if (strcmp(argv[argi], "--no-ensure") == 0) {
      do_ensure = 0;
      argi++;
      continue;
    }
    if (strcmp(argv[argi], "-h") == 0 || strcmp(argv[argi], "--help") == 0) {
      fprintf(stderr, "atlas-enter %s — Deb via atlas-enterd (abstract @atlasenter)\n",
              ATLAS_VERSION);
      return 2;
    }
    break;
  }

  if (drop == 0) {
    const char *e = getenv("ATLAS_DROP_UID");
    if (e && e[0]) drop = (uid_t)strtoul(e, NULL, 10);
  }
  if (drop == 0 && ruid > 0) drop = ruid;
  if (drop == 0) drop = pkg_uid();
  if (drop == 0) die(77, "FATAL no admin uid");

  if (!home || !home[0]) home = getenv("ATLAS_HOME");
  if (!home || !home[0]) home = getenv("HOME");
  if (!home || !home[0]) home = "/data/data/com.titanus2.atlas/files";

  if (euid == 0) return local_root_enter(drop, home, do_ensure);

  /* ENTER needs SCM_RIGHTS → unix only. Never prefer TCP for enter. */
  int s = connect_abstract();
  if (s < 0) s = connect_fs(SOCK_FS);
  if (s < 0) s = connect_fs("/dev/socket/atlasenter");
  /* TCP last-resort only if elevated text path ever uses enter without fd — skip. */
  if (s < 0) {
    int fd = open("/data/local/tmp/atlas-hybrid-ensure-request",
                  O_WRONLY | O_CREAT | O_TRUNC, 0666);
    if (fd >= 0) {
      (void)write(fd, "enter\n", 6);
      close(fd);
    }
    die(79, "FATAL cannot connect atlas-enterd (@atlasenter) — Deb plane down");
  }

  char hdr[768];
  int hl = snprintf(hdr, sizeof(hdr), "ENTER uid=%u home=%s ensure=%d\n",
                    (unsigned)drop, home, do_ensure ? 1 : 0);
  if (write(s, hdr, (size_t)hl) != hl) {
    close(s);
    die(79, "FATAL enterd write failed");
  }
  if (send_fd(s, 0) != 0) {
    close(s);
    die(79, "FATAL enterd SCM_RIGHTS failed");
  }

  char resp[256];
  size_t off = 0;
  while (off + 1 < sizeof(resp)) {
    ssize_t n = read(s, resp + off, 1);
    if (n <= 0) {
      close(s);
      die(79, "FATAL enterd closed before OK");
    }
    if (resp[off] == '\n') {
      resp[off] = 0;
      break;
    }
    off++;
  }
  if (strncmp(resp, "OK", 2) != 0) {
    fprintf(stderr, "atlas-enter: %s\n", resp);
    close(s);
    if (strstr(resp, "hybrid")) exit(80);
    exit(79);
  }

  off = 0;
  while (off + 1 < sizeof(resp)) {
    ssize_t n = read(s, resp + off, 1);
    if (n <= 0) break;
    if (resp[off] == '\n') {
      resp[off] = 0;
      break;
    }
    off++;
  }
  close(s);
  if (strncmp(resp, "EXIT ", 5) == 0) return atoi(resp + 5);
  return 0;
}
