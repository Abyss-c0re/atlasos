#!/usr/bin/env python3
# Flatpak launcher for this kernel.
# CONFIG_USER_NS and CONFIG_PID_NS are off, so bubblewrap stops before
# exec. Mount, net, uts, and cgroup namespaces work. Build the sandbox
# root in a private mount namespace and run the command as the caller.
import ctypes
import os
import select
import stat
import sys

ATLAS_BWRAP_VER = "1"

MS_RDONLY = 1
MS_NOSUID = 2
MS_NODEV = 4
MS_NOEXEC = 8
MS_REMOUNT = 32
MS_BIND = 4096
MS_REC = 16384
MS_PRIVATE = 1 << 18
CLONE_NEWNS = 0x00020000
CLONE_NEWUTS = 0x04000000
CLONE_NEWCGROUP = 0x02000000
CLONE_NEWNET = 0x40000000
PR_SET_PDEATHSIG = 1
PR_SET_DUMPABLE = 4
PR_SET_NO_NEW_PRIVS = 38
PR_SET_SECCOMP = 22
SECCOMP_MODE_FILTER = 2
SIGKILL = 9

libc = ctypes.CDLL("libc.so.6", use_errno=True)
libc.mount.argtypes = [
    ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_ulong, ctypes.c_char_p
]
libc.mount.restype = ctypes.c_int
libc.unshare.argtypes = [ctypes.c_int]
libc.unshare.restype = ctypes.c_int
libc.chroot.argtypes = [ctypes.c_char_p]
libc.chroot.restype = ctypes.c_int
libc.sethostname.argtypes = [ctypes.c_char_p, ctypes.c_size_t]
libc.sethostname.restype = ctypes.c_int
libc.prctl.argtypes = [ctypes.c_int, ctypes.c_ulong, ctypes.c_void_p, ctypes.c_ulong, ctypes.c_ulong]
libc.prctl.restype = ctypes.c_int


class SockFilter(ctypes.Structure):
    _fields_ = (
        ("code", ctypes.c_uint16),
        ("jt", ctypes.c_uint8),
        ("jf", ctypes.c_uint8),
        ("k", ctypes.c_uint32),
    )
    _pack_ = 1


class SockFprog(ctypes.Structure):
    _fields_ = (
        ("len", ctypes.c_ushort),
        ("filter", ctypes.POINTER(SockFilter)),
    )


def die(msg):
    sys.stderr.write("bwrap: %s\n" % msg)
    sys.stderr.flush()
    sys.exit(1)


def mount(src, target, fstype, flags, data):
    raw_src = os.fsencode(src) if src is not None else None
    raw_type = os.fsencode(fstype) if fstype is not None else None
    raw_data = os.fsencode(data) if data is not None else None
    if libc.mount(raw_src, os.fsencode(target), raw_type, flags, raw_data) != 0:
        err = ctypes.get_errno()
        raise OSError(err, "%s: %s" % (target, os.strerror(err)))


def try_unshare(flag):
    if libc.unshare(flag) == 0:
        return True
    return False


def read_fd_args(fd):
    parts = []
    buf = bytearray()
    while True:
        ready, _, _ = select.select([fd], [], [], 5.0)
        if not ready:
            break
        try:
            chunk = os.read(fd, 1 << 20)
        except InterruptedError:
            continue
        if not chunk:
            break
        buf += chunk
    if buf.endswith(b"\0"):
        buf = buf[:-1]
    if buf:
        parts = [p.decode("utf-8", "surrogateescape") for p in bytes(buf).split(b"\0")]
    return parts


def expand_args(argv, depth=0):
    if depth > 4:
        die("--args nested too deep")
    out = []
    i = 0
    while i < len(argv):
        if argv[i] == "--args":
            if i + 1 >= len(argv):
                die("--args takes a file descriptor")
            out.extend(expand_args(read_fd_args(int(argv[i + 1], 10)), depth + 1))
            i += 2
        else:
            out.append(argv[i])
            i += 1
    return out


def path_from_fd(fd):
    """Path behind a Flatpak O_PATH fd.

    Binding /proc/self/fd/N returns EINVAL on this kernel. readlink of that
    symlink is the path from outside the chroot, which does not exist here.
    Use the longest suffix that does.
    """
    raw = os.readlink("/proc/self/fd/%d" % fd)
    bits = [b for b in raw.split("/") if b]
    for i in range(len(bits)):
        cand = "/" + "/".join(bits[i:])
        if os.path.lexists(cand):
            return cand
    die("bind-fd %s is not visible in this root" % raw)


def caller_ids():
    uid = os.getuid()
    gid = os.getgid()
    if uid == 0:
        su = os.environ.get("SUDO_UID", "")
        sg = os.environ.get("SUDO_GID", "")
        if su.isdigit() and int(su) != 0:
            uid = int(su)
            gid = int(sg) if sg.isdigit() else gid
    return uid, gid


ZERO = {
    "--help", "--version", "--unshare-all", "--share-net", "--unshare-user",
    "--unshare-user-try", "--unshare-ipc", "--unshare-pid", "--unshare-net",
    "--unshare-uts", "--unshare-cgroup", "--unshare-cgroup-try",
    "--disable-userns", "--assert-userns-disabled", "--clearenv",
    "--new-session", "--die-with-parent", "--as-pid-1",
    "--not-a-security-boundary", "--level-prefix",
}
ONE = {
    "--argv0", "--userns", "--userns2", "--pidns", "--uid", "--gid",
    "--hostname", "--chdir", "--unsetenv", "--lock-file", "--sync-fd",
    "--proc", "--dev", "--tmpfs", "--mqueue", "--dir", "--remount-ro",
    "--exec-label", "--file-label", "--seccomp", "--add-seccomp-fd",
    "--block-fd", "--userns-block-fd", "--info-fd", "--json-status-fd",
    "--cap-add", "--cap-drop", "--size", "--perms", "--overlay-src",
    "--tmp-overlay", "--ro-overlay",
}
TWO = {
    "--setenv", "--bind", "--bind-try", "--dev-bind", "--dev-bind-try",
    "--ro-bind", "--ro-bind-try", "--bind-fd", "--ro-bind-fd", "--file",
    "--bind-data", "--ro-bind-data", "--symlink", "--chmod",
}



def _parse(argv):
    spec = {
        "ops": [],
        "locks": [],
        "env_ops": [],
        "chdir": None,
        "argv0": None,
        "hostname": None,
        "unshare_net": False,
        "unshare_uts": False,
        "unshare_cgroup": False,
        "share_net": False,
        "die_with_parent": False,
        "new_session": False,
        "sync_fd": None,
        "block_fd": None,
        "userns_block_fd": None,
        "info_fd": None,
        "json_fd": None,
        "seccomp": [],
        "command": [],
        "perms": None,
        "size": None,
        "overlay_src": [],
    }
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == "--":
            spec["command"] = argv[i + 1:]
            return _finish(spec)
        if not arg.startswith("--"):
            spec["command"] = argv[i:]
            return _finish(spec)
        if arg in ONE or arg in TWO:
            if i + 1 >= len(argv):
                die("%s takes an argument" % arg)
        if arg in TWO and i + 2 >= len(argv):
            die("%s takes two arguments" % arg)
        nxt = argv[i + 1] if arg in ONE or arg in TWO else None
        nxt2 = argv[i + 2] if arg in TWO else None
        if arg == "--perms":
            spec["perms"] = int(nxt, 8)
            i += 2
            continue
        if arg == "--size":
            spec["size"] = int(nxt, 10)
            i += 2
            continue
        perms = spec["perms"]
        size = spec["size"]
        if arg in ("--tmpfs", "--dir", "--file", "--bind-data", "--ro-bind-data"):
            spec["perms"] = None
            spec["size"] = None
        if arg == "--clearenv":
            spec["env_ops"].append(("clear",))
        elif arg == "--share-net":
            spec["share_net"] = True
        elif arg == "--unshare-all":
            spec["unshare_net"] = True
            spec["unshare_uts"] = True
            spec["unshare_cgroup"] = True
        elif arg == "--unshare-net":
            spec["unshare_net"] = True
        elif arg == "--unshare-uts":
            spec["unshare_uts"] = True
        elif arg == "--unshare-cgroup" or arg == "--unshare-cgroup-try":
            spec["unshare_cgroup"] = True
        elif arg == "--die-with-parent":
            spec["die_with_parent"] = True
        elif arg == "--new-session":
            spec["new_session"] = True
        elif arg == "--chdir":
            spec["chdir"] = nxt
        elif arg == "--argv0":
            spec["argv0"] = nxt
        elif arg == "--hostname":
            spec["hostname"] = nxt
            spec["unshare_uts"] = True
        elif arg == "--setenv":
            spec["env_ops"].append(("set", nxt, nxt2))
        elif arg == "--unsetenv":
            spec["env_ops"].append(("unset", nxt))
        elif arg == "--lock-file":
            spec["locks"].append(nxt)
        elif arg in ("--sync-fd", "--block-fd", "--userns-block-fd", "--info-fd", "--json-status-fd"):
            fd = int(nxt, 10)
            spec[{
                "--sync-fd": "sync_fd",
                "--block-fd": "block_fd",
                "--userns-block-fd": "userns_block_fd",
                "--info-fd": "info_fd",
                "--json-status-fd": "json_fd",
            }[arg]] = fd
        elif arg in ("--seccomp", "--add-seccomp-fd"):
            spec["seccomp"].append(int(nxt, 10))
        elif arg == "--overlay-src":
            spec["overlay_src"].append(nxt)
        elif arg in ("--bind", "--bind-try", "--ro-bind", "--ro-bind-try", "--dev-bind", "--dev-bind-try"):
            spec["ops"].append((
                "bind", nxt, nxt2,
                arg.startswith("--ro-"), arg.endswith("-try"), arg.startswith("--dev-"),
            ))
        elif arg in ("--bind-fd", "--ro-bind-fd"):
            spec["ops"].append(("bind-fd", int(nxt, 10), nxt2, arg.startswith("--ro-")))
        elif arg == "--tmpfs":
            spec["ops"].append(("tmpfs", nxt, perms if perms is not None else 0o755, size or 0))
        elif arg == "--dir":
            spec["ops"].append(("dir", nxt, perms if perms is not None else 0o755))
        elif arg == "--proc":
            spec["ops"].append(("proc", nxt))
        elif arg == "--dev":
            spec["ops"].append(("dev", nxt))
        elif arg == "--mqueue":
            spec["ops"].append(("skip", "mqueue"))
        elif arg == "--file":
            spec["ops"].append(("file", int(nxt, 10), nxt2, perms if perms is not None else 0o666))
        elif arg in ("--bind-data", "--ro-bind-data"):
            spec["ops"].append((
                "bind-data", int(nxt, 10), nxt2,
                arg.startswith("--ro-"), perms if perms is not None else 0o600,
            ))
        elif arg == "--symlink":
            spec["ops"].append(("symlink", nxt, nxt2))
        elif arg == "--remount-ro":
            spec["ops"].append(("remount-ro", nxt))
        elif arg == "--chmod":
            spec["ops"].append(("chmod", int(nxt, 8), nxt2))
        elif arg == "--overlay":
            if i + 3 >= len(argv):
                die("--overlay takes three arguments")
            spec["ops"].append(("overlay", nxt, argv[i + 2], argv[i + 3], list(spec["overlay_src"])))
            spec["overlay_src"] = []
            i += 4
            continue
        elif arg == "--tmp-overlay":
            spec["ops"].append(("tmp-overlay", nxt, list(spec["overlay_src"])))
            spec["overlay_src"] = []
        elif arg == "--ro-overlay":
            spec["ops"].append(("ro-overlay", nxt, list(spec["overlay_src"])))
            spec["overlay_src"] = []
        elif arg in ZERO or arg.startswith("--"):
            if arg not in ZERO and arg not in ONE and arg not in TWO and arg not in (
                "--overlay",
            ):
                # Known no-ops (user, pid, ipc, labels, caps) fall through.
                if arg not in (
                    "--unshare-user", "--unshare-user-try", "--unshare-ipc",
                    "--unshare-pid", "--disable-userns", "--assert-userns-disabled",
                    "--as-pid-1", "--not-a-security-boundary", "--level-prefix",
                    "--cap-add", "--cap-drop", "--uid", "--gid", "--userns",
                    "--userns2", "--pidns", "--exec-label", "--file-label",
                ):
                    die("unknown option %s" % arg)
        if arg in TWO:
            i += 3
        elif arg in ONE:
            i += 2
        else:
            i += 1
    return _finish(spec)


def _finish(spec):
    if spec["share_net"]:
        spec["unshare_net"] = False
    env = dict(os.environ)
    for op in spec["env_ops"]:
        if op[0] == "clear":
            env = {}
        elif op[0] == "set":
            env[op[1]] = op[2]
        else:
            env.pop(op[1], None)
    spec["env"] = env
    if not spec["command"]:
        die("no command")
    return spec


class Root:
    def __init__(self):
        self.base = "/tmp/atlas-bwrap.%d" % os.getpid()
        self.newroot = self.base + "/root"
        self.stash = self.base + "/stash"
        os.makedirs(self.base, 0o755)
        mount("tmpfs", self.base, "tmpfs", MS_NOSUID | MS_NODEV, "mode=755")
        os.mkdir(self.newroot, 0o755)
        os.mkdir(self.stash, 0o755)
        mount(self.newroot, self.newroot, None, MS_BIND, None)

    def dest(self, path):
        if not path.startswith("/"):
            path = "/" + path
        norm = os.path.normpath(path)
        if not norm.startswith("/"):
            die("bad path %s" % path)
        return self.newroot + norm

    def ensure_dir(self, path):
        os.makedirs(path, 0o755, exist_ok=True)

    def ensure_file(self, path):
        self.ensure_dir(os.path.dirname(path))
        if not os.path.lexists(path):
            fd = os.open(path, os.O_CREAT | os.O_WRONLY, 0o644)
            os.close(fd)

    def bind(self, src, dest, ro):
        st = os.stat(src)
        target = self.dest(dest)
        if stat.S_ISDIR(st.st_mode):
            self.ensure_dir(target)
            mount(src, target, None, MS_BIND | MS_REC, None)
        else:
            self.ensure_file(target)
            mount(src, target, None, MS_BIND, None)
        if ro:
            try:
                mount(None, target, None, MS_BIND | MS_REMOUNT | MS_RDONLY, None)
            except OSError:
                pass

    def copy_fd(self, fd, mode):
        out_path = self.stash + "/data-%d" % fd
        out = os.open(out_path, os.O_CREAT | os.O_TRUNC | os.O_WRONLY, mode)
        while True:
            chunk = os.read(fd, 1 << 20)
            if not chunk:
                break
            os.write(out, chunk)
        os.close(out)
        os.chmod(out_path, mode)
        # Flatpak marks these 0600. In a user namespace that owner is the
        # app; here the creator is root, so hand the inode to the caller.
        uid, gid = caller_ids()
        try:
            os.chown(out_path, uid, gid)
        except OSError:
            pass
        return out_path


def apply_ops(spec, root):
    for op in spec["ops"]:
        kind = op[0]
        try:
            if kind == "bind":
                _, src, dest, ro, optional, _dev = op
                if not os.path.lexists(src):
                    if optional:
                        continue
                    die("source missing %s" % src)
                root.bind(os.path.realpath(src), dest, ro)
            elif kind == "bind-fd":
                _, fd, dest, ro = op
                root.bind(path_from_fd(fd), dest, ro)
            elif kind == "tmpfs":
                _, dest, mode, size = op
                target = root.dest(dest)
                root.ensure_dir(target)
                data = "mode=%o" % mode
                if size:
                    data += ",size=%d" % size
                mount("tmpfs", target, "tmpfs", MS_NOSUID | MS_NODEV, data)
            elif kind == "dir":
                _, dest, mode = op
                target = root.dest(dest)
                root.ensure_dir(target)
                os.chmod(target, mode)
            elif kind == "proc":
                target = root.dest(op[1])
                root.ensure_dir(target)
                root.bind("/proc", op[1], False)
            elif kind == "dev":
                setup_dev(root, op[1])
            elif kind == "file":
                _, fd, dest, mode = op
                target = root.dest(dest)
                root.ensure_dir(os.path.dirname(target))
                out = os.open(target, os.O_CREAT | os.O_TRUNC | os.O_WRONLY, mode)
                while True:
                    chunk = os.read(fd, 1 << 20)
                    if not chunk:
                        break
                    os.write(out, chunk)
                os.close(out)
                os.chmod(target, mode)
                uid, gid = caller_ids()
                try:
                    os.chown(target, uid, gid)
                except OSError:
                    pass
            elif kind == "bind-data":
                _, fd, dest, ro, mode = op
                host = root.copy_fd(fd, mode)
                root.bind(host, dest, ro)
                try:
                    os.unlink(host)
                except OSError:
                    pass
            elif kind == "symlink":
                _, src, dest = op
                target = root.dest(dest)
                root.ensure_dir(os.path.dirname(target))
                try:
                    os.symlink(src, target)
                except FileExistsError:
                    current = os.readlink(target)
                    if current != src:
                        die("symlink %s exists and points at %s" % (dest, current))
            elif kind == "remount-ro":
                target = root.dest(op[1])
                mount(None, target, None, MS_BIND | MS_REMOUNT | MS_RDONLY, None)
            elif kind == "chmod":
                _, mode, dest = op
                os.chmod(root.dest(dest), mode)
            elif kind == "overlay":
                _, upper, work, dest, lowers = op
                mount_overlay(root, dest, upper, work, lowers, False)
            elif kind == "ro-overlay":
                _, dest, lowers = op
                mount_overlay(root, dest, None, None, lowers, True)
            elif kind == "tmp-overlay":
                _, dest, lowers = op
                upper = root.stash + "/ov-upper"
                work = root.stash + "/ov-work"
                os.mkdir(upper, 0o755)
                os.mkdir(work, 0o755)
                mount_overlay(root, dest, upper, work, lowers, False)
            elif kind == "skip":
                continue
            else:
                die("unhandled op %s" % kind)
        except OSError as exc:
            die("%s %s" % (kind, exc))


def mount_overlay(root, dest, upper, work, lowers, read_only):
    target = root.dest(dest)
    root.ensure_dir(target)
    if not lowers:
        die("overlay %s has no lowerdir" % dest)
    opt = "lowerdir=" + ":".join(lowers)
    if upper and work:
        opt += ",upperdir=%s,workdir=%s" % (upper, work)
    opt += ",userxattr"
    flags = MS_RDONLY if read_only and not upper else 0
    mount("overlay", target, "overlay", flags, opt)
    if read_only and upper:
        mount(None, target, None, MS_BIND | MS_REMOUNT | MS_RDONLY, None)


def setup_dev(root, dest):
    target = root.dest(dest)
    root.ensure_dir(target)
    mount("tmpfs", target, "tmpfs", MS_NOSUID | MS_NOEXEC, "mode=755")
    for name in ("null", "zero", "full", "random", "urandom", "tty"):
        src = "/dev/" + name
        node = target + "/" + name
        if not os.path.exists(src):
            continue
        fd = os.open(node, os.O_CREAT | os.O_WRONLY, 0o666)
        os.close(fd)
        mount(src, node, None, MS_BIND, None)
    for name, link in (("stdin", "/proc/self/fd/0"), ("stdout", "/proc/self/fd/1"),
                       ("stderr", "/proc/self/fd/2"), ("fd", "/proc/self/fd")):
        try:
            os.symlink(link, target + "/" + name)
        except FileExistsError:
            pass
    try:
        os.symlink("/proc/kcore", target + "/core")
    except FileExistsError:
        pass
    os.mkdir(target + "/shm", 0o755)
    os.mkdir(target + "/pts", 0o755)
    try:
        mount("tmpfs", target + "/shm", "tmpfs", MS_NOSUID | MS_NODEV, "mode=1777")
    except OSError as exc:
        sys.stderr.write("bwrap: /dev/shm: %s\n" % exc)
    try:
        mount("devpts", target + "/pts", "devpts", MS_NOSUID | MS_NOEXEC,
              "newinstance,ptmxmode=0666,mode=0620")
        os.symlink("pts/ptmx", target + "/ptmx")
    except OSError as exc:
        sys.stderr.write("bwrap: devpts: %s\n" % exc)
    try:
        tty = os.ttyname(1)
    except OSError:
        tty = ""
    if tty and os.path.exists(tty):
        console = target + "/console"
        fd = os.open(console, os.O_CREAT | os.O_WRONLY, 0o666)
        os.close(fd)
        try:
            mount(tty, console, None, MS_BIND, None)
        except OSError:
            pass


def take_locks(spec, root):
    held = []
    for path in spec["locks"]:
        target = root.dest(path)
        try:
            fd = os.open(target, os.O_RDONLY | os.O_CLOEXEC)
        except OSError as exc:
            sys.stderr.write("bwrap: lock %s: %s\n" % (path, exc))
            continue
        try:
            import fcntl
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            sys.stderr.write("bwrap: lock %s: %s\n" % (path, exc))
        held.append(fd)
    return held


def read_seccomp(fds):
    programs = []
    for fd in fds:
        data = b""
        while True:
            chunk = os.read(fd, 1 << 20)
            if not chunk:
                break
            data += chunk
        if len(data) % 8 != 0 or not data:
            die("invalid seccomp data")
        n = len(data) // 8
        filters = (SockFilter * n).from_buffer_copy(data)
        programs.append(filters)
    return programs


def apply_seccomp(programs):
    if libc.prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0:
        return
    for filters in programs:
        prog = SockFprog(len(filters), filters)
        if libc.prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, ctypes.addressof(prog), 0, 0) != 0:
            err = ctypes.get_errno()
            die("seccomp: %s" % os.strerror(err))


def drop_ids(uid, gid):
    if os.geteuid() != 0:
        return
    os.setgroups([])
    os.setgid(gid)
    os.setuid(uid)
    libc.prctl(PR_SET_DUMPABLE, 1, 0, 0, 0)


def child_run(spec, root, uid, gid, release_r, programs):
    os.close(release_r[1])
    while True:
        got = os.read(release_r[0], 1)
        if got:
            break
    os.close(release_r[0])
    if spec["info_fd"] is not None:
        os.close(spec["info_fd"])
    if spec["json_fd"] is not None:
        os.close(spec["json_fd"])
    if libc.chroot(os.fsencode(root.newroot)) != 0:
        die("chroot: %s" % os.strerror(ctypes.get_errno()))
    os.chdir("/")
    drop_ids(uid, gid)
    if spec["block_fd"] is not None:
        try:
            os.read(spec["block_fd"], 1)
        except OSError:
            pass
        os.close(spec["block_fd"])
    if spec["die_with_parent"]:
        libc.prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0)
        if os.getppid() == 1:
            sys.exit(1)
    if programs:
        apply_seccomp(programs)
    if spec["chdir"]:
        try:
            os.chdir(spec["chdir"])
        except OSError as exc:
            die("chdir %s: %s" % (spec["chdir"], exc))
    else:
        home = spec["env"].get("HOME", "")
        if home:
            try:
                os.chdir(home)
            except OSError:
                pass
    spec["env"]["PWD"] = os.getcwd()
    if spec["new_session"]:
        os.setsid()
    # Leave Flatpak's fds open. xdg-dbus-proxy is started as
    # `bwrap --args N -- xdg-dbus-proxy --args=M` and dies if M is closed.
    cmd = spec["command"]
    argv0 = spec["argv0"] or cmd[0]
    try:
        os.execvpe(cmd[0], [argv0] + cmd[1:], spec["env"])
    except OSError as exc:
        die("exec %s: %s" % (cmd[0], exc))


def parent_wait(spec, pid, release_w, programs_unused):
    os.close(release_w[0])
    if spec["info_fd"] is not None:
        os.write(spec["info_fd"], ('{\n    "child-pid": %d\n}\n' % pid).encode())
        os.close(spec["info_fd"])
    if spec["json_fd"] is not None:
        os.write(spec["json_fd"], ('{ "child-pid": %d }\n' % pid).encode())
    if spec["userns_block_fd"] is not None:
        ready, _, _ = select.select([spec["userns_block_fd"]], [], [], 15.0)
        if ready:
            try:
                os.read(spec["userns_block_fd"], 1)
            except OSError:
                pass
        os.close(spec["userns_block_fd"])
    os.write(release_w[1], b"\n")
    os.close(release_w[1])
    while True:
        try:
            waited, status = os.waitpid(pid, 0)
        except InterruptedError:
            continue
        if waited == pid:
            break
    if os.WIFEXITED(status):
        code = os.WEXITSTATUS(status)
    elif os.WIFSIGNALED(status):
        code = 128 + os.WTERMSIG(status)
    else:
        code = 1
    if spec["json_fd"] is not None:
        try:
            os.write(spec["json_fd"], ('{ "exit-code": %d }\n' % code).encode())
            os.close(spec["json_fd"])
        except OSError:
            pass
    sys.exit(code)


def main():
    if os.geteuid() != 0:
        die("Creating new namespace failed, likely because the kernel does not support user namespaces")
    raw = sys.argv[1:]
    if raw == ["--version"]:
        sys.stdout.write("bubblewrap 0.12.0\n")
        return
    if raw == ["--help"]:
        sys.stdout.write("usage: bwrap [OPTIONS...] [--] COMMAND [ARGS...]\n")
        return
    argv = expand_args(raw)
    spec = _parse(argv)
    if not try_unshare(CLONE_NEWNS):
        die("unshare mount namespace: %s" % os.strerror(ctypes.get_errno()))
    try:
        mount(None, "/", None, MS_REC | MS_PRIVATE, None)
    except OSError as exc:
        die("private /: %s" % exc)
    if spec["unshare_net"]:
        try_unshare(CLONE_NEWNET)
    if spec["unshare_uts"]:
        try_unshare(CLONE_NEWUTS)
    if spec["unshare_cgroup"]:
        try_unshare(CLONE_NEWCGROUP)
    if spec["hostname"] and spec["unshare_uts"]:
        raw = os.fsencode(spec["hostname"])
        libc.sethostname(raw, len(raw))
    old_umask = os.umask(0)
    root = Root()
    apply_ops(spec, root)
    held_locks = take_locks(spec, root)
    programs = read_seccomp(spec["seccomp"]) if spec["seccomp"] else []
    os.umask(old_umask)
    uid, gid = caller_ids()
    release = os.pipe()
    pid = os.fork()
    if pid == 0:
        try:
            child_run(spec, root, uid, gid, release, programs)
        except SystemExit:
            raise
        except Exception as exc:
            die("child: %s" % exc)
    parent_wait(spec, pid, release, held_locks)


if __name__ == "__main__":
    main()
