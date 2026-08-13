/*
 * atlas — pure C terminal core for Titan ROM (com.titanus2.atlas)
 *
 * Interactive REPL with product builtins. Unknown lines → sh -c.
 * Privilege: ATLAS_PRIV=1 or `priv on` → prefix commands with su -c when available.
 * Sandbox / pkg / usbip / modules / nanobot: real where possible, clear stubs otherwise.
 *
 * Build: packages/titan_atlas/build_native.sh (NDK aarch64)
 */
#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <limits.h>
#include <ctype.h>
#include <dirent.h>

#ifndef ATLAS_VERSION
#define ATLAS_VERSION "0.4.2"
#endif

static int g_priv = 0;
static int g_sandbox = 0;
static char g_home[PATH_MAX];
static char g_bin[PATH_MAX];
static char g_debian[PATH_MAX];

static int cmd_hybrid(int argc, char **argv);

static void trim(char *s) {
    if (!s) return;
    size_t n = strlen(s);
    while (n && (s[n - 1] == '\n' || s[n - 1] == '\r' || isspace((unsigned char)s[n - 1])))
        s[--n] = 0;
    char *p = s;
    while (*p && isspace((unsigned char)*p)) p++;
    if (p != s) memmove(s, p, strlen(p) + 1);
}

static int path_exists(const char *p) {
    struct stat st;
    return p && stat(p, &st) == 0;
}

static int is_exec(const char *p) {
    return p && access(p, X_OK) == 0;
}

/* Find first executable among candidates. */
static const char *first_exec(const char *const *cands) {
    for (int i = 0; cands[i]; i++) {
        if (is_exec(cands[i])) return cands[i];
    }
    return NULL;
}

static int run_argv(char *const argv[], int use_priv) {
    if (!argv || !argv[0]) return -1;

    pid_t pid = fork();
    if (pid < 0) {
        perror("fork");
        return -1;
    }
    if (pid == 0) {
        if (use_priv && g_priv) {
            /* KernelSU: su 0 -c '…'  (also accepts su -c) */
            size_t len = 0;
            for (int i = 0; argv[i]; i++) len += strlen(argv[i]) + 1;
            char *cmd = malloc(len + 8);
            if (!cmd) _exit(127);
            cmd[0] = 0;
            for (int i = 0; argv[i]; i++) {
                if (i) strcat(cmd, " ");
                strcat(cmd, argv[i]);
            }
            const char *su = first_exec((const char *const[]){
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "su", NULL});
            if (su) {
                execl(su, "su", "0", "-c", cmd, (char *)NULL);
                execl(su, "su", "-c", cmd, (char *)NULL);
            }
            free(cmd);
            /* fall through to direct exec if no su */
        }
        execvp(argv[0], argv);
        fprintf(stderr, "atlas: exec %s: %s\n", argv[0], strerror(errno));
        _exit(127);
    }
    int st = 0;
    if (waitpid(pid, &st, 0) < 0) {
        perror("waitpid");
        return -1;
    }
    if (WIFEXITED(st)) return WEXITSTATUS(st);
    if (WIFSIGNALED(st)) return 128 + WTERMSIG(st);
    return -1;
}

static int run_shell_line(const char *line, int use_priv) {
    if (!line || !*line) return 0;
    char *argv[8];
    if (use_priv && g_priv) {
        const char *su = first_exec((const char *const[]){
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "su", NULL});
        if (su) {
            /* KernelSU prefers: su 0 -c 'cmd' */
            argv[0] = (char *)su;
            argv[1] = (char *)"0";
            argv[2] = (char *)"-c";
            argv[3] = (char *)line;
            argv[4] = NULL;
            return run_argv(argv, 0);
        }
    }
    argv[0] = (char *)"/system/bin/sh";
    argv[1] = (char *)"-c";
    argv[2] = (char *)line;
    argv[3] = NULL;
    return run_argv(argv, 0);
}

static void print_banner(void) {
    printf("Atlas %s — Titan terminal core\n", ATLAS_VERSION);
    printf("home=%s  bin=%s  priv=%s  sandbox=%s\n",
           g_home, g_bin, g_priv ? "on" : "off", g_sandbox ? "on" : "off");
    printf("Type `help`. Unknown lines run in sh (priv if enabled).\n\n");
}

static void cmd_help(void) {
    puts(
        "Atlas builtins — KEY to dual-mode hybrid\n"
        "  help | clear | exit | quit | paths | lsbin\n"
        "  whoami | id\n"
        "  priv on|off|status|run …     su plane (needed for hybrid)\n"
        "  run <path> [args…]           exec from $ATLAS_BIN or absolute\n"
        "  install <src> [name]         copy → $ATLAS_BIN (no /system mount)\n"
        "  hybrid status|bootstrap|mount|heal|enter|run|destroy\n"
        "  mode android|debian|status   synced mode plane\n"
        "  storage shared|isolated|status\n"
        "  trust on|off|status\n"
        "  usbip · pkg · modules · sandbox · nanobot\n"
        "\n"
        "Combined OS (Debian userspace + Android kernel + apps):\n"
        "  hybrid bootstrap   # priv — ext4 loop + overlay + Debian trixie\n"
        "  hybrid mount|heal  # bring overlay up / fix apt tmp+DNS\n"
        "  hybrid enter       # priv — pivot into merge (not chroot-first)\n"
        "  hybrid run apt-get update\n"
        "  mode android|debian\n"
        "Do NOT remount /system. ABI aarch64. SoT: docs/project/ATLAS.md\n");
}

/* Resolve atlas-hybrid.sh (product KEY script). */
static int hybrid_script_path(char *out, size_t outsz) {
    snprintf(out, outsz, "%s/atlas-hybrid.sh", g_bin);
    if (is_exec(out) || path_exists(out)) return 0;
    snprintf(out, outsz, "%s", "/data/local/tmp/atlas-hybrid.sh");
    if (is_exec(out) || path_exists(out)) return 0;
    snprintf(out, outsz, "%s", "/system/bin/atlas-hybrid.sh");
    if (is_exec(out) || path_exists(out)) return 0;
    return -1;
}

static int cmd_hybrid(int argc, char **argv) {
    char script[PATH_MAX];
    if (hybrid_script_path(script, sizeof script) != 0) {
        puts("hybrid: atlas-hybrid.sh missing — rebuild Atlas APK / push assets");
        return 1;
    }
    /* default: status */
    const char *sub = (argc >= 2) ? argv[1] : "status";
    char cmd[PATH_MAX * 2 + 256];
    /* rebuild remaining args */
    char rest[1024];
    rest[0] = 0;
    for (int i = 1; i < argc; i++) {
        if (i > 1) strncat(rest, " ", sizeof rest - strlen(rest) - 1);
        strncat(rest, argv[i], sizeof rest - strlen(rest) - 1);
    }
    if (!rest[0]) snprintf(rest, sizeof rest, "status");
    /* Pass Atlas home so bootstrap finds staged debian-trixie rootfs image */
    snprintf(cmd, sizeof cmd,
             "export HOME='%s' ATLAS_HOME='%s' ATLAS_BIN='%s' PATH='%s:/system/bin:/system/xbin:/vendor/bin'; "
             "/system/bin/sh '%s' %s",
             g_home, g_home, g_bin, g_bin, script, rest);
    /* bootstrap/enter/run/destroy/mode debian need root */
    int force_priv = 1;
    if (!strcmp(sub, "status") || !strcmp(sub, "help") || !strcmp(sub, "version"))
        force_priv = 0;
    if (!strcmp(sub, "mode") && argc >= 3 && !strcmp(argv[2], "android"))
        force_priv = 0;
    if (!strcmp(sub, "storage") || !strcmp(sub, "trust") || !strcmp(sub, "pad"))
        force_priv = 0;
    if (force_priv && !g_priv)
        puts("hybrid: using priv (KernelSU su) for this command…");
    int old = g_priv;
    if (force_priv) g_priv = 1;
    int rc = run_shell_line(cmd, force_priv ? 1 : g_priv);
    g_priv = old;
    return rc;
}

static int cmd_mode_plane(int argc, char **argv) {
    char *a[8];
    int n = 0;
    a[n++] = (char *)"hybrid";
    a[n++] = (char *)"mode";
    if (argc >= 2) a[n++] = argv[1];
    a[n] = NULL;
    return cmd_hybrid(n, a);
}

static int cmd_paths(void) {
    printf("HOME=%s\n", g_home);
    printf("BIN=%s\n", g_bin);
    printf("DEBIAN=%s exists=%s\n", g_debian, path_exists(g_debian) ? "yes" : "no");
    printf("priv=%s sandbox=%s\n", g_priv ? "on" : "off", g_sandbox ? "on" : "off");
    printf("note: install tools into BIN — never mount /system for CLI\n");
    return 0;
}

static int cmd_lsbin(void) {
    DIR *d = opendir(g_bin);
    if (!d) {
        perror("lsbin");
        return 1;
    }
    printf("%s\n", g_bin);
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (de->d_name[0] == '.') continue;
        char path[PATH_MAX];
        snprintf(path, sizeof path, "%s/%s", g_bin, de->d_name);
        struct stat st;
        if (stat(path, &st) != 0) continue;
        printf("%c %8lld %s\n",
               (st.st_mode & S_IXUSR) ? 'x' : '-',
               (long long)st.st_size, de->d_name);
    }
    closedir(d);
    return 0;
}

/* Copy src → $ATLAS_BIN/[name]. Product install path (no system remount). */
static int cmd_install(int argc, char **argv) {
    if (argc < 2) {
        fputs("usage: install <src-path> [dest-name]\n", stderr);
        fputs("  copies into $ATLAS_BIN and chmod +x\n", stderr);
        return 2;
    }
    const char *src = argv[1];
    if (!path_exists(src)) {
        fprintf(stderr, "install: not found: %s\n", src);
        return 1;
    }
    const char *base = argv[1];
    const char *slash = strrchr(argv[1], '/');
    if (slash && slash[1]) base = slash + 1;
    const char *name = (argc >= 3 && argv[2][0]) ? argv[2] : base;
    if (strchr(name, '/')) {
        fputs("install: dest name must be a bare filename\n", stderr);
        return 2;
    }
    char dest[PATH_MAX];
    snprintf(dest, sizeof dest, "%s/%s", g_bin, name);
    char cmd[PATH_MAX * 2 + 64];
    snprintf(cmd, sizeof cmd, "cp -f '%s' '%s' && chmod 755 '%s'", src, dest, dest);
    int rc = run_shell_line(cmd, 0);
    if (rc == 0)
        printf("installed %s → %s\n", src, dest);
    else
        fprintf(stderr, "install failed rc=%d\n", rc);
    return rc;
}

static int cmd_run(int argc, char **argv) {
    if (argc < 2) {
        fputs("usage: run <path> [args…]\n", stderr);
        return 2;
    }
    char path[PATH_MAX];
    if (argv[1][0] == '/') {
        snprintf(path, sizeof path, "%s", argv[1]);
    } else {
        snprintf(path, sizeof path, "%s/%s", g_bin, argv[1]);
        if (!is_exec(path))
            snprintf(path, sizeof path, "%s", argv[1]);
    }
    if (!is_exec(path) && !path_exists(path)) {
        fprintf(stderr, "atlas: not found: %s\n", path);
        return 127;
    }
    /* rebuild argv with resolved path */
    char *nargv[64];
    int n = 0;
    nargv[n++] = path;
    for (int i = 2; i < argc && n < 63; i++) nargv[n++] = argv[i];
    nargv[n] = NULL;
    return run_argv(nargv, 1);
}

static char *tool_path(const char *name, char *out, size_t outsz) {
    snprintf(out, outsz, "%s/%s", g_bin, name);
    if (is_exec(out)) return out;
    /* system paths */
    static const char *prefs[] = {
        "/system/bin/", "/system/xbin/", "/vendor/bin/", NULL};
    for (int i = 0; prefs[i]; i++) {
        snprintf(out, outsz, "%s%s", prefs[i], name);
        if (is_exec(out)) return out;
    }
    snprintf(out, outsz, "%s", name);
    return out;
}

static int cmd_usbip(int argc, char **argv) {
    char usbip[PATH_MAX], hostb[PATH_MAX];
    tool_path("usbip", usbip, sizeof usbip);
    tool_path("quest-usbip-host", hostb, sizeof hostb);
    if (argc < 2) {
        fputs("usage: usbip list|list-r <host>|attach|detach|host start|host stop\n", stderr);
        return 2;
    }
    if (!strcmp(argv[1], "list")) {
        if (!is_exec(usbip)) {
            puts("usbip: tool not installed yet (bundle assets/bin/usbip)");
            return 1;
        }
        char *a[] = {usbip, (char *)"list", (char *)"-l", NULL};
        return run_argv(a, 1);
    }
    if (!strcmp(argv[1], "list-r") && argc >= 3) {
        if (!is_exec(usbip)) {
            puts("usbip: tool not installed yet");
            return 1;
        }
        char *a[] = {usbip, (char *)"list", (char *)"-r", argv[2], NULL};
        return run_argv(a, 0); /* list remote often works unprivileged */
    }
    if (!strcmp(argv[1], "attach") && argc >= 4) {
        if (!is_exec(usbip)) {
            puts("usbip: tool not installed; need vhci-hcd + root for attach");
            return 1;
        }
        if (!g_priv) puts("usbip attach: enabling priv for this call…");
        int old = g_priv;
        g_priv = 1;
        char *a[] = {usbip, (char *)"attach", (char *)"-r", argv[2],
                     (char *)"-b", argv[3], NULL};
        int rc = run_argv(a, 1);
        g_priv = old;
        return rc;
    }
    if (!strcmp(argv[1], "detach") && argc >= 3) {
        char *a[] = {usbip, (char *)"detach", (char *)"-p", argv[2], NULL};
        return run_argv(a, 1);
    }
    if (!strcmp(argv[1], "host") && argc >= 3) {
        if (!strcmp(argv[2], "start")) {
            if (!is_exec(hostb)) {
                puts("usbip host: quest-usbip-host not in files/bin — push asset");
                return 1;
            }
            printf("starting USB/IP host (opt-in) %s\n", hostb);
            int old = g_priv;
            g_priv = 1;
            char *a[] = {hostb, NULL};
            int rc = run_argv(a, 1);
            g_priv = old;
            return rc;
        }
        if (!strcmp(argv[2], "stop")) {
            return run_shell_line("pkill -f quest-usbip-host || pkill -f usbipd || true", 1);
        }
    }
    fputs("usbip: unknown subcommand\n", stderr);
    return 2;
}

static int cmd_pkg(int argc, char **argv) {
    if (argc < 2) {
        fputs("usage: pkg update|search|install|remove|rootfs …\n", stderr);
        return 2;
    }
    if (!strcmp(argv[1], "rootfs") && argc >= 3) {
        if (!strcmp(argv[2], "status")) {
            printf("debian_root=%s exists=%s\n", g_debian,
                   path_exists(g_debian) ? "yes" : "no");
            printf("target_arch=aarch64 (Titan 2 — not armv7)\n");
            return 0;
        }
        if (!strcmp(argv[2], "bootstrap")) {
            puts("pkg rootfs bootstrap → hybrid bootstrap (privileged Ubuntu arm64)");
            char *a[] = {(char *)"hybrid", (char *)"bootstrap", NULL};
            return cmd_hybrid(2, a);
        }
    }
    if (!path_exists(g_debian)) {
        puts("pkg: no rootfs — run `pkg rootfs bootstrap` first");
        return 1;
    }
    /* When rootfs exists, drive apt via proot */
    char proot[PATH_MAX];
    tool_path("proot", proot, sizeof proot);
    if (!is_exec(proot)) {
        puts("pkg: proot missing in files/bin");
        return 1;
    }
    char cmdline[2048];
    if (!strcmp(argv[1], "update")) {
        snprintf(cmdline, sizeof cmdline,
                 "%s -0 -r %s -b /dev -b /proc -b /sys /usr/bin/apt-get update",
                 proot, g_debian);
        return run_shell_line(cmdline, 0);
    }
    if ((!strcmp(argv[1], "install") || !strcmp(argv[1], "remove") ||
         !strcmp(argv[1], "search")) &&
        argc >= 3) {
        const char *apt =
            !strcmp(argv[1], "search") ? "apt-cache search" : "apt-get";
        const char *op = !strcmp(argv[1], "install")   ? "install -y"
                         : !strcmp(argv[1], "remove")  ? "remove -y"
                                                       : "";
        if (!strcmp(argv[1], "search"))
            snprintf(cmdline, sizeof cmdline,
                     "%s -0 -r %s -b /dev -b /proc -b /sys /usr/bin/apt-cache search %s",
                     proot, g_debian, argv[2]);
        else
            snprintf(cmdline, sizeof cmdline,
                     "%s -0 -r %s -b /dev -b /proc -b /sys /usr/bin/apt-get %s %s",
                     proot, g_debian, op, argv[2]);
        return run_shell_line(cmdline, 0);
    }
    fputs("pkg: unknown subcommand\n", stderr);
    return 2;
}

static int modules_dir(char *out, size_t n) {
    static const char *cands[] = {
        "/data/adb/modules",
        "/data/adb/ksu/modules",
        NULL};
    for (int i = 0; cands[i]; i++) {
        if (path_exists(cands[i])) {
            snprintf(out, n, "%s", cands[i]);
            return 0;
        }
    }
    snprintf(out, n, "%s", "/data/adb/modules");
    return -1;
}

static int cmd_modules(int argc, char **argv) {
    char mdir[PATH_MAX];
    int have = modules_dir(mdir, sizeof mdir) == 0;
    if (argc < 2 || !strcmp(argv[1], "list")) {
        if (!have) {
            puts("modules: no /data/adb/modules (need Magisk/KSU root layout)");
            return 1;
        }
        DIR *d = opendir(mdir);
        if (!d) {
            perror("modules");
            return 1;
        }
        printf("%-24s %-8s %s\n", "ID", "STATE", "NAME");
        struct dirent *de;
        while ((de = readdir(d)) != NULL) {
            if (de->d_name[0] == '.') continue;
            char path[PATH_MAX], dis[PATH_MAX], prop[PATH_MAX];
            snprintf(path, sizeof path, "%s/%s", mdir, de->d_name);
            struct stat st;
            if (stat(path, &st) || !S_ISDIR(st.st_mode)) continue;
            snprintf(dis, sizeof dis, "%s/disable", path);
            snprintf(prop, sizeof prop, "%s/module.prop", path);
            const char *state = path_exists(dis) ? "off" : "on";
            char name[128] = "-";
            FILE *f = fopen(prop, "r");
            if (f) {
                char line[256];
                while (fgets(line, sizeof line, f)) {
                    if (!strncmp(line, "name=", 5)) {
                        trim(line + 5);
                        snprintf(name, sizeof name, "%s", line + 5);
                        break;
                    }
                }
                fclose(f);
            }
            printf("%-24s %-8s %s\n", de->d_name, state, name);
        }
        closedir(d);
        return 0;
    }
    if ((!strcmp(argv[1], "enable") || !strcmp(argv[1], "disable") ||
         !strcmp(argv[1], "info")) &&
        argc >= 3) {
        char path[PATH_MAX], dis[PATH_MAX], prop[PATH_MAX];
        snprintf(path, sizeof path, "%s/%s", mdir, argv[2]);
        snprintf(dis, sizeof dis, "%s/disable", path);
        snprintf(prop, sizeof prop, "%s/module.prop", path);
        if (!path_exists(path)) {
            fprintf(stderr, "modules: no such id %s\n", argv[2]);
            return 1;
        }
        if (!strcmp(argv[1], "info")) {
            if (path_exists(prop)) {
                char cmd[PATH_MAX + 16];
                snprintf(cmd, sizeof cmd, "cat '%s'", prop);
                return run_shell_line(cmd, 0);
            }
            puts("(no module.prop)");
            return 0;
        }
        if (!g_priv) {
            puts("modules enable/disable need `priv on` (writes /data/adb)");
            return 1;
        }
        if (!strcmp(argv[1], "disable")) {
            char cmd[PATH_MAX + 32];
            snprintf(cmd, sizeof cmd, "touch '%s'", dis);
            return run_shell_line(cmd, 1);
        }
        /* enable */
        {
            char cmd[PATH_MAX + 32];
            snprintf(cmd, sizeof cmd, "rm -f '%s'", dis);
            return run_shell_line(cmd, 1);
        }
    }
    fputs("modules: usage list|enable|disable|info\n", stderr);
    return 2;
}

static int cmd_sandbox(int argc, char **argv) {
    if (argc < 2 || !strcmp(argv[1], "status")) {
        printf("sandbox=%s debian=%s\n", g_sandbox ? "on" : "off",
               path_exists(g_debian) ? g_debian : "(missing)");
        return 0;
    }
    if (!strcmp(argv[1], "on")) {
        g_sandbox = 1;
        setenv("ATLAS_SANDBOX", "1", 1);
        puts("sandbox on (session flag; enter with `sandbox enter`)");
        return 0;
    }
    if (!strcmp(argv[1], "off")) {
        g_sandbox = 0;
        setenv("ATLAS_SANDBOX", "0", 1);
        puts("sandbox off");
        return 0;
    }
    if (!strcmp(argv[1], "bootstrap")) {
        puts("sandbox bootstrap → hybrid bootstrap");
        char *a[] = {(char *)"hybrid", (char *)"bootstrap", NULL};
        return cmd_hybrid(2, a);
    }
    if (!strcmp(argv[1], "enter")) {
        puts("sandbox enter → hybrid enter (priv Debian+Android)");
        char *a[] = {(char *)"hybrid", (char *)"enter", NULL};
        return cmd_hybrid(2, a);
    }
    fputs("sandbox: on|off|enter|status|bootstrap\n", stderr);
    return 2;
}

static int cmd_nanobot(int argc, char **argv) {
    char nb[PATH_MAX];
    tool_path("nanobot", nb, sizeof nb);
    if (argc < 2 || !strcmp(argv[1], "status")) {
        printf("nanobot_bin=%s exec=%s\n", nb, is_exec(nb) ? "yes" : "no");
        /* peer HTTP common on product */
        return run_shell_line(
            "curl -sS --max-time 1 http://127.0.0.1:8787/api/health 2>/dev/null || "
            "curl -sS --max-time 1 http://127.0.0.1:8787/ 2>/dev/null | head -c 200 || "
            "echo peer_down",
            0);
    }
    if (!strcmp(argv[1], "start")) {
        if (!is_exec(nb)) {
            puts("nanobot: binary missing — install product nanobot or push to files/bin");
            return 1;
        }
        char cmd[PATH_MAX + 64];
        snprintf(cmd, sizeof cmd, "%s >/data/local/tmp/atlas-nanobot.log 2>&1 &", nb);
        return run_shell_line(cmd, g_priv);
    }
    if (!strcmp(argv[1], "stop")) {
        return run_shell_line("pkill -f nanobot || true", g_priv);
    }
    if (!strcmp(argv[1], "peer")) {
        return run_shell_line(
            "curl -sS --max-time 2 http://127.0.0.1:8787/api/braincube 2>/dev/null | head -c 800 || "
            "echo peer_not_listening",
            0);
    }
    if (!strcmp(argv[1], "chat") && argc >= 3) {
        /* join remaining args */
        char msg[1024];
        msg[0] = 0;
        for (int i = 2; i < argc; i++) {
            if (i > 2) strncat(msg, " ", sizeof msg - strlen(msg) - 1);
            strncat(msg, argv[i], sizeof msg - strlen(msg) - 1);
        }
        char cmd[1400];
        snprintf(cmd, sizeof cmd,
                 "curl -sS --max-time 30 -H 'Content-Type: application/json' "
                 "-d '{\"action\":\"chat\",\"text\":\"%s\"}' "
                 "http://127.0.0.1:8787/api/braincube 2>/dev/null | head -c 2000 || "
                 "echo chat_failed",
                 msg);
        return run_shell_line(cmd, 0);
    }
    fputs("nanobot: status|start|stop|chat|peer\n", stderr);
    return 2;
}

static int dispatch(char *line) {
    trim(line);
    if (!*line) return 0;
    if (line[0] == '#') return 0;

    /* tokenize into argv (simple, no quotes yet) */
    char *argv[64];
    int argc = 0;
    char *save = NULL;
    for (char *tok = strtok_r(line, " \t", &save); tok && argc < 63;
         tok = strtok_r(NULL, " \t", &save)) {
        argv[argc++] = tok;
    }
    argv[argc] = NULL;
    if (argc == 0) return 0;

    if (!strcmp(argv[0], "help") || !strcmp(argv[0], "?")) {
        cmd_help();
        return 0;
    }
    if (!strcmp(argv[0], "clear")) {
        fputs("\033[2J\033[H", stdout);
        fflush(stdout);
        return 0;
    }
    if (!strcmp(argv[0], "exit") || !strcmp(argv[0], "quit")) {
        exit(0);
    }
    if (!strcmp(argv[0], "paths")) return cmd_paths();
    if (!strcmp(argv[0], "lsbin")) return cmd_lsbin();
    if (!strcmp(argv[0], "install")) return cmd_install(argc, argv);
    if (!strcmp(argv[0], "hybrid")) return cmd_hybrid(argc, argv);
    if (!strcmp(argv[0], "mode")) return cmd_mode_plane(argc, argv);
    if (!strcmp(argv[0], "storage") || !strcmp(argv[0], "trust") ||
        !strcmp(argv[0], "pad")) {
        /* forward: hybrid storage|trust|pad … */
        char *a[8];
        int n = 0;
        a[n++] = (char *)"hybrid";
        for (int i = 0; i < argc && n < 7; i++) a[n++] = argv[i];
        a[n] = NULL;
        return cmd_hybrid(n, a);
    }
    if (!strcmp(argv[0], "whoami")) {
        return run_shell_line("whoami; id", g_priv);
    }
    if (!strcmp(argv[0], "id")) {
        return run_shell_line("id", g_priv);
    }
    if (!strcmp(argv[0], "priv")) {
        if (argc < 2 || !strcmp(argv[1], "status")) {
            printf("priv=%s\n", g_priv ? "on" : "off");
            return 0;
        }
        if (!strcmp(argv[1], "on")) {
            g_priv = 1;
            setenv("ATLAS_PRIV", "1", 1);
            puts("priv on — subsequent builtins may use su");
            return 0;
        }
        if (!strcmp(argv[1], "off")) {
            g_priv = 0;
            setenv("ATLAS_PRIV", "0", 1);
            puts("priv off");
            return 0;
        }
        if (!strcmp(argv[1], "run") && argc >= 3) {
            /* join rest */
            char buf[2048];
            buf[0] = 0;
            for (int i = 2; i < argc; i++) {
                if (i > 2) strncat(buf, " ", sizeof buf - strlen(buf) - 1);
                strncat(buf, argv[i], sizeof buf - strlen(buf) - 1);
            }
            int old = g_priv;
            g_priv = 1;
            int rc = run_shell_line(buf, 1);
            g_priv = old;
            return rc;
        }
        fputs("priv: on|off|status|run <cmd>\n", stderr);
        return 2;
    }
    if (!strcmp(argv[0], "run")) return cmd_run(argc, argv);
    if (!strcmp(argv[0], "usbip")) return cmd_usbip(argc, argv);
    if (!strcmp(argv[0], "pkg")) return cmd_pkg(argc, argv);
    if (!strcmp(argv[0], "modules")) return cmd_modules(argc, argv);
    if (!strcmp(argv[0], "sandbox")) return cmd_sandbox(argc, argv);
    if (!strcmp(argv[0], "nanobot")) return cmd_nanobot(argc, argv);

    /* re-join original line for sh — rebuild from argv */
    char buf[2048];
    buf[0] = 0;
    for (int i = 0; i < argc; i++) {
        if (i) strncat(buf, " ", sizeof buf - strlen(buf) - 1);
        strncat(buf, argv[i], sizeof buf - strlen(buf) - 1);
    }
    return run_shell_line(buf, 1);
}

static void init_paths(void) {
    const char *h = getenv("ATLAS_HOME");
    if (!h || !*h) h = getenv("HOME");
    if (!h || !*h) h = "/data/local/tmp/atlas";
    snprintf(g_home, sizeof g_home, "%s", h);
    const char *b = getenv("ATLAS_BIN");
    if (b && *b)
        snprintf(g_bin, sizeof g_bin, "%s", b);
    else
        snprintf(g_bin, sizeof g_bin, "%s/bin", g_home);
    snprintf(g_debian, sizeof g_debian, "%s/debian", g_home);
    mkdir(g_home, 0755);
    mkdir(g_bin, 0755);

    const char *p = getenv("ATLAS_PRIV");
    if (p && (!strcmp(p, "1") || !strcasecmp(p, "on"))) g_priv = 1;
    const char *s = getenv("ATLAS_SANDBOX");
    if (s && (!strcmp(s, "1") || !strcasecmp(s, "on"))) g_sandbox = 1;
}

int main(int argc, char **argv) {
    init_paths();

    /* non-interactive: atlas -c "cmd" or atlas <builtin...> */
    if (argc >= 3 && !strcmp(argv[1], "-c")) {
        char buf[2048];
        snprintf(buf, sizeof buf, "%s", argv[2]);
        return dispatch(buf) & 0xff;
    }
    if (argc >= 2 && strcmp(argv[1], "-i") != 0) {
        /* treat remaining as one command */
        char buf[2048];
        buf[0] = 0;
        for (int i = 1; i < argc; i++) {
            if (i > 1) strncat(buf, " ", sizeof buf - strlen(buf) - 1);
            strncat(buf, argv[i], sizeof buf - strlen(buf) - 1);
        }
        return dispatch(buf) & 0xff;
    }

    print_banner();
    char *line = NULL;
    size_t cap = 0;
    for (;;) {
        fputs(g_priv ? "atlas# " : "atlas$ ", stdout);
        fflush(stdout);
        ssize_t n = getline(&line, &cap, stdin);
        if (n < 0) break;
        dispatch(line);
    }
    free(line);
    return 0;
}
