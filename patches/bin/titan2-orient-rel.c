/*
 * titan2-orient-rel — grab titan2-virtual-mouse, re-emit REL_* with display rotation.
 * Control plane:
 *   /data/misc/titan2/titan2_pad_follow_orient  (1 = rotate)
 *   /data/misc/titan2/titan2_pad_rotation       (0..3 Surface rotation)
 * When follow=0, still forwards 1:1 (agent should not run us, but safe).
 */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define CTRL_FOLLOW "/data/misc/titan2/titan2_pad_follow_orient"
#define CTRL_FOLLOW2 "/data/local/tmp/titan2_pad_follow_orient"
#define CTRL_ROT "/data/misc/titan2/titan2_pad_rotation"
#define CTRL_ROT2 "/data/local/tmp/titan2_pad_rotation"
#define SRC_NAME "titan2-virtual-mouse"
#define OUT_NAME "titan2-orient-mouse"

/* Newest mtime wins across a/b — agent and apps write both planes. */
static int read_int_file(const char *a, const char *b, int def) {
  char buf[32];
  int fd, n, v, best = def, have = 0;
  struct stat st;
  time_t best_mt = 0;
  const char *paths[2] = {a, b};
  for (int i = 0; i < 2; i++) {
    if (!paths[i]) continue;
    fd = open(paths[i], O_RDONLY | O_CLOEXEC);
    if (fd < 0) continue;
    n = (int)read(fd, buf, sizeof(buf) - 1);
    if (fstat(fd, &st) != 0) st.st_mtime = 0;
    close(fd);
    if (n <= 0) continue;
    buf[n] = 0;
    v = atoi(buf);
    if (!have || st.st_mtime >= best_mt) {
      best = v;
      best_mt = st.st_mtime;
      have = 1;
    }
  }
  return have ? best : def;
}

static void apply_orient(int follow, int rot, int *dx, int *dy) {
  int x, y;
  if (!follow || (!*dx && !*dy)) return;
  if (rot < 0) rot = 0;
  if (rot > 3) rot = 3;
  x = *dx;
  y = *dy;
  switch (rot) {
  case 1: /* 90° CCW */  *dx = y;  *dy = -x; break;
  case 2: /* 180° */     *dx = -x; *dy = -y; break;
  case 3: /* 270° CCW */ *dx = -y; *dy = x;  break;
  default: break;
  }
}

static int find_event_by_name(const char *want, char *out, size_t outsz) {
  DIR *d = opendir("/sys/class/input");
  struct dirent *ent;
  char path[256], name[128];
  int fd, n;
  if (!d) return -1;
  while ((ent = readdir(d)) != NULL) {
    if (strncmp(ent->d_name, "event", 5) != 0) continue;
    snprintf(path, sizeof(path), "/sys/class/input/%s/device/name", ent->d_name);
    fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
      snprintf(path, sizeof(path), "/sys/class/input/%s/name", ent->d_name);
      fd = open(path, O_RDONLY | O_CLOEXEC);
    }
    if (fd < 0) continue;
    n = (int)read(fd, name, sizeof(name) - 1);
    close(fd);
    if (n <= 0) continue;
    while (n > 0 && (name[n - 1] == '\n' || name[n - 1] == '\r')) n--;
    name[n] = 0;
    if (strcmp(name, want) != 0) continue;
    snprintf(out, outsz, "/dev/input/%s", ent->d_name);
    closedir(d);
    return 0;
  }
  closedir(d);
  return -1;
}

static int setup_uinput(void) {
  int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
  struct uinput_setup usetup;
  if (fd < 0) return -1;
  ioctl(fd, UI_SET_EVBIT, EV_KEY);
  ioctl(fd, UI_SET_KEYBIT, BTN_LEFT);
  ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT);
  ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE);
  ioctl(fd, UI_SET_EVBIT, EV_REL);
  ioctl(fd, UI_SET_RELBIT, REL_X);
  ioctl(fd, UI_SET_RELBIT, REL_Y);
  ioctl(fd, UI_SET_RELBIT, REL_WHEEL);
  ioctl(fd, UI_SET_RELBIT, REL_HWHEEL);
#ifdef REL_WHEEL_HI_RES
  ioctl(fd, UI_SET_RELBIT, REL_WHEEL_HI_RES);
#endif
#ifdef REL_HWHEEL_HI_RES
  ioctl(fd, UI_SET_RELBIT, REL_HWHEEL_HI_RES);
#endif
  memset(&usetup, 0, sizeof(usetup));
  usetup.id.bustype = BUS_VIRTUAL;
  usetup.id.vendor = 0x1234;
  usetup.id.product = 0x5679;
  usetup.id.version = 1;
  strncpy(usetup.name, OUT_NAME, UINPUT_MAX_NAME_SIZE - 1);
  if (ioctl(fd, UI_DEV_SETUP, &usetup) < 0) {
    close(fd);
    return -1;
  }
  if (ioctl(fd, UI_DEV_CREATE) < 0) {
    close(fd);
    return -1;
  }
  return fd;
}

static void emit(int ufd, int type, int code, int val) {
  struct input_event ev;
  memset(&ev, 0, sizeof(ev));
  gettimeofday(&ev.time, NULL);
  ev.type = type;
  ev.code = code;
  ev.value = val;
  if (write(ufd, &ev, sizeof(ev)) != (ssize_t)sizeof(ev)) {
    /* ignore short write */
  }
}

int main(void) {
  char path[128];
  int sfd = -1, ufd = -1, grab = 1;
  int follow = 0, rot = 0;
  int pending_x = 0, pending_y = 0;
  struct input_event ev;
  struct pollfd pfd;
  time_t last_ctrl = 0;

  /* wait for source device */
  for (int i = 0; i < 100; i++) {
    if (find_event_by_name(SRC_NAME, path, sizeof(path)) == 0) break;
    usleep(50000);
    path[0] = 0;
  }
  if (!path[0]) {
    fprintf(stderr, "orient-rel: no %s\n", SRC_NAME);
    return 1;
  }
  sfd = open(path, O_RDONLY | O_CLOEXEC);
  if (sfd < 0) {
    perror("open source");
    return 1;
  }
  /* Retry grab: hid_bridge may briefly hold virtual-mouse at session start. */
  grab = 1;
  {
    int ok = 0;
    for (int g = 0; g < 40; g++) {
      if (ioctl(sfd, EVIOCGRAB, &grab) == 0) {
        ok = 1;
        break;
      }
      usleep(50000);
    }
    if (!ok) {
      perror("EVIOCGRAB");
      /* continue ungrabbed would double-move — fail hard */
      close(sfd);
      return 1;
    }
  }
  ufd = setup_uinput();
  if (ufd < 0) {
    perror("uinput");
    grab = 0;
    ioctl(sfd, EVIOCGRAB, &grab);
    close(sfd);
    return 1;
  }
  fprintf(stderr, "orient-rel: src=%s out=%s\n", path, OUT_NAME);

  pfd.fd = sfd;
  pfd.events = POLLIN;
  for (;;) {
    time_t now = time(NULL);
    if (now != last_ctrl) {
      last_ctrl = now;
      follow = read_int_file(CTRL_FOLLOW, CTRL_FOLLOW2, 0) == 1;
      rot = read_int_file(CTRL_ROT, CTRL_ROT2, 0);
      if (rot < 0) rot = 0;
      if (rot > 3) rot = 3;
    }
    int pr = poll(&pfd, 1, 200);
    if (pr < 0) {
      if (errno == EINTR) continue;
      break;
    }
    if (pr == 0) continue;
    ssize_t n = read(sfd, &ev, sizeof(ev));
    if (n != (ssize_t)sizeof(ev)) {
      if (n < 0 && (errno == EINTR || errno == EAGAIN)) continue;
      break;
    }
    if (ev.type == EV_REL) {
      if (ev.code == REL_X) {
        pending_x += ev.value;
        continue;
      }
      if (ev.code == REL_Y) {
        pending_y += ev.value;
        continue;
      }
      /* wheel etc: pass through (optionally rotate wheel axes too) */
      if (follow && (ev.code == REL_WHEEL || ev.code == REL_HWHEEL
#ifdef REL_WHEEL_HI_RES
                     || ev.code == REL_WHEEL_HI_RES
#endif
                     )) {
        int dx = 0, dy = 0;
        if (ev.code == REL_WHEEL ||
#ifdef REL_WHEEL_HI_RES
            ev.code == REL_WHEEL_HI_RES ||
#endif
            0) {
          dy = ev.value;
        } else {
          dx = ev.value;
        }
        apply_orient(follow, rot, &dx, &dy);
        /* map back to primary wheel if only one component */
        if (dx && !dy)
          emit(ufd, EV_REL, REL_HWHEEL, dx);
        else if (dy)
          emit(ufd, EV_REL, REL_WHEEL, dy);
        continue;
      }
      emit(ufd, ev.type, ev.code, ev.value);
      continue;
    }
    if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
      if (pending_x || pending_y) {
        int dx = pending_x, dy = pending_y;
        pending_x = pending_y = 0;
        apply_orient(follow, rot, &dx, &dy);
        if (dx) emit(ufd, EV_REL, REL_X, dx);
        if (dy) emit(ufd, EV_REL, REL_Y, dy);
      }
      emit(ufd, EV_SYN, SYN_REPORT, 0);
      continue;
    }
    /* keys / other */
    if (pending_x || pending_y) {
      int dx = pending_x, dy = pending_y;
      pending_x = pending_y = 0;
      apply_orient(follow, rot, &dx, &dy);
      if (dx) emit(ufd, EV_REL, REL_X, dx);
      if (dy) emit(ufd, EV_REL, REL_Y, dy);
      emit(ufd, EV_SYN, SYN_REPORT, 0);
    }
    emit(ufd, ev.type, ev.code, ev.value);
  }

  grab = 0;
  ioctl(sfd, EVIOCGRAB, &grab);
  ioctl(ufd, UI_DEV_DESTROY);
  close(ufd);
  close(sfd);
  return 0;
}
