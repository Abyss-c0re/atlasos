/*
 * ptyexec — run command on a real PTY (for grok/TUI under Atlas pipes).
 * Usage: ptyexec <cmd> [args…]
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <termios.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>

static volatile sig_atomic_t child_dead = 0;
static pid_t child_pid = -1;

static void on_chld(int sig) {
    (void)sig;
    child_dead = 1;
}

/* openpty without libutil — works on Android NDK. */
static int open_pty(int *master, int *slave, struct winsize *ws) {
    int m = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (m < 0) m = open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (m < 0) return -1;
    if (grantpt(m) != 0 || unlockpt(m) != 0) {
        close(m);
        return -1;
    }
    char *name = ptsname(m);
    if (!name) {
        close(m);
        return -1;
    }
    int s = open(name, O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (s < 0) {
        close(m);
        return -1;
    }
    if (ws) ioctl(m, TIOCSWINSZ, ws);
    *master = m;
    *slave = s;
    return 0;
}

static void pump(int master) {
    struct pollfd pf[2];
    char buf[8192];
    for (;;) {
        if (child_dead) {
            for (;;) {
                ssize_t n = read(master, buf, sizeof buf);
                if (n > 0) {
                    if (write(STDOUT_FILENO, buf, (size_t)n) < 0) return;
                } else break;
            }
            break;
        }
        pf[0].fd = STDIN_FILENO;
        pf[0].events = POLLIN;
        pf[1].fd = master;
        pf[1].events = POLLIN;
        int pr = poll(pf, 2, 200);
        if (pr < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (pf[0].revents & (POLLIN | POLLHUP)) {
            ssize_t n = read(STDIN_FILENO, buf, sizeof buf);
            if (n > 0) {
                if (write(master, buf, (size_t)n) < 0 && errno != EAGAIN) break;
            } else if (n < 0 && errno != EINTR && errno != EAGAIN) {
                break;
            }
        }
        if (pf[1].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t n = read(master, buf, sizeof buf);
            if (n > 0) {
                if (write(STDOUT_FILENO, buf, (size_t)n) < 0) break;
            } else if (n == 0) {
                break;
            } else if (errno != EINTR && errno != EAGAIN && errno != EIO) {
                break;
            }
        }
    }
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: ptyexec <cmd> [args…]\n");
        return 2;
    }

    int master = -1, slave = -1;
    struct winsize ws;
    memset(&ws, 0, sizeof ws);
    ws.ws_row = 40;
    ws.ws_col = 100;

    if (open_pty(&master, &slave, &ws) != 0) {
        fprintf(stderr, "ptyexec: openpty failed (%s) — direct exec\n",
                strerror(errno));
        execvp(argv[1], argv + 1);
        perror("exec");
        return 127;
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_chld;
    sigaction(SIGCHLD, &sa, NULL);

    child_pid = fork();
    if (child_pid < 0) {
        perror("fork");
        return 1;
    }
    if (child_pid == 0) {
        close(master);
        setsid();
        ioctl(slave, TIOCSCTTY, 0);
        /* Line mode shell: ICANON + ECHO. UI uses EditText for input so HW
         * keys never fight the PTY; output stream still uses this PTY. */
        {
            struct termios t;
            if (tcgetattr(slave, &t) == 0) {
                t.c_iflag |= ICRNL;
                t.c_iflag &= ~(INLCR | IGNCR);
                t.c_oflag &= ~(ONLCR | OCRNL);
                t.c_lflag |= (ICANON | ECHO | ECHOE | ECHOK);
                t.c_lflag &= ~(ECHONL);
                tcsetattr(slave, TCSANOW, &t);
            }
        }
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > 2) close(slave);
        setenv("TERM", "xterm-256color", 0);
        execvp(argv[1], argv + 1);
        dprintf(2, "ptyexec: exec %s: %s\n", argv[1], strerror(errno));
        _exit(127);
    }

    close(slave);
    pump(master);
    close(master);

    int st = 0;
    while (waitpid(child_pid, &st, 0) < 0 && errno == EINTR) {
    }
    if (WIFEXITED(st)) return WEXITSTATUS(st);
    if (WIFSIGNALED(st)) return 128 + WTERMSIG(st);
    return 1;
}
