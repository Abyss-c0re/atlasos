#!/bin/bash
# Put the current Plasma desk session into a Debian seed tarball.
# atlas_linux_a is unpacked from that tar. A source edit does not reach
# the next super until this member is replaced.
set -euo pipefail

HERE="$(readlink -f "$0")"
ROOT="$(cd "$(dirname "$HERE")/../../.." && pwd)"
NATIVE="$ROOT/packages/titan_atlas/native/x11"
SESSION="$NATIVE/atlas-desk-session.sh"
STOP="$NATIVE/atlas-desk-stop.sh"

[ -f "$SESSION" ] || { echo "bake_desk_session: missing $SESSION" >&2; exit 1; }
[ -f "$STOP" ] || { echo "bake_desk_session: missing $STOP" >&2; exit 1; }
grep -q 'chmod 644 /etc/passwd' "$SESSION" \
  || { echo "bake_desk_session: source session has no passwd mode guard" >&2; exit 1; }

if [ "$#" -eq 0 ]; then
  set -- \
    "$ROOT/out/atlas_rootfs/debian-trixie-arm64-rootfs.tar.gz" \
    "$ROOT/../titanus2/out/atlas_rootfs/debian-trixie-arm64-rootfs.tar.gz"
fi

tars=()
for t in "$@"; do
  [ -f "$t" ] || { echo "bake_desk_session: missing $t" >&2; exit 1; }
  tars+=("$t")
done

primary="${tars[0]}"
same=()
rest=()
for t in "${tars[@]:1}"; do
  if cmp -s "$primary" "$t"; then
    same+=("$t")
  else
    rest+=("$t")
  fi
done

export ATLAS_DESK_SESSION="$SESSION"
export ATLAS_DESK_STOP="$STOP"

bake_one() {
  local tar="$1"
  local tmp="${tar}.bake-new"
  rm -f "$tmp"
  if ! python3 - "$tar" "$tmp" <<'PY'
import io, os, sys, tarfile

src, dst = sys.argv[1], sys.argv[2]
session_path = os.environ["ATLAS_DESK_SESSION"]
stop_path = os.environ["ATLAS_DESK_STOP"]
MARKER = b"chmod 644 /etc/passwd"
REPL = {
    "usr/local/bin/atlas-desk-session": session_path,
    "usr/local/libexec/atlas-desk-stop.sh": stop_path,
}
MODES = {
    "etc/passwd": 0o644,
    "etc/group": 0o644,
    "etc/shadow": 0o640,
    "etc/gshadow": 0o640,
}

def norm(name):
    n = name
    while n.startswith("./"):
        n = n[2:]
    return n.lstrip("/")

def load(path):
    with open(path, "rb") as f:
        data = f.read()
    if path == session_path and MARKER not in data:
        raise SystemExit("source desk session has no passwd guard")
    return data

blobs = {k: load(p) for k, p in REPL.items()}
mtimes = {k: int(os.stat(p).st_mtime) for k, p in REPL.items()}

def needs_rewrite(path):
    got = {}
    modes = {}
    with tarfile.open(path, "r:gz") as t:
        for m in t:
            k = norm(m.name)
            if k in REPL and m.isreg():
                f = t.extractfile(m)
                got[k] = f.read() if f is not None else b""
                if got[k] != blobs[k]:
                    return True
            if k in MODES and m.isreg():
                modes[k] = m.mode & 0o777
    for k in REPL:
        if got.get(k) != blobs[k]:
            return True
    for k, mode in MODES.items():
        if k not in modes:
            if k in ("etc/shadow", "etc/gshadow"):
                continue
            return True
        if modes[k] != mode:
            return True
    return False

def rewrite(path, out):
    require_plasma = os.path.getsize(path) > 80_000_000
    replaced = set()
    saw = {"plasma": False, "pactl": False, "pipewire": False}
    sudo_in = None
    with tarfile.open(path, "r:gz") as tin:
        fmt = tin.format if tin.format is not None else tarfile.PAX_FORMAT
        with tarfile.open(out, "w:gz", format=fmt, compresslevel=6) as tout:
            n = 0
            for m in tin:
                n += 1
                if n % 20000 == 0:
                    print(f"bake_desk_session: {n} members", file=sys.stderr, flush=True)
                k = norm(m.name)
                if k in REPL:
                    data = blobs[k]
                    info = tarfile.TarInfo(name=m.name if m.name else "./" + k)
                    info.size = len(data)
                    info.mode = 0o755
                    info.uid = 0
                    info.gid = 0
                    info.uname = "root"
                    info.gname = "root"
                    info.mtime = mtimes[k]
                    info.type = tarfile.REGTYPE
                    tout.addfile(info, io.BytesIO(data))
                    replaced.add(k)
                    continue
                if k in MODES and m.isreg():
                    m.mode = (m.mode & ~0o777) | MODES[k]
                if k == "usr/bin/sudo.real":
                    sudo_in = (m.mode & 0o7777, m.uid, m.gid)
                if k == "usr/bin/pactl":
                    saw["pactl"] = True
                elif k == "usr/bin/pipewire-pulse":
                    saw["pipewire"] = True
                if m.isreg():
                    f = tin.extractfile(m)
                    if f is None and m.size:
                        raise SystemExit(f"unreadable member {m.name}")
                    if k == "var/lib/dpkg/status" and f is not None:
                        data = f.read()
                        if b"Package: plasma-pa\n" in data:
                            saw["plasma"] = True
                        tout.addfile(m, io.BytesIO(data))
                        continue
                    tout.addfile(m, f)
                else:
                    tout.addfile(m)
            for k, data in blobs.items():
                if k in replaced:
                    continue
                info = tarfile.TarInfo(name="./" + k)
                info.size = len(data)
                info.mode = 0o755
                info.uid = 0
                info.gid = 0
                info.uname = "root"
                info.gname = "root"
                info.mtime = mtimes[k]
                info.type = tarfile.REGTYPE
                tout.addfile(info, io.BytesIO(data))
    if require_plasma and not (saw["plasma"] and saw["pactl"] and saw["pipewire"]):
        raise SystemExit("rewrite lost plasma-pa, pipewire-pulse, or pactl")
    return sudo_in

def prove(path, sudo_in, require_plasma):
    marker = 0
    modes = {}
    saw = {"plasma": False, "pactl": False, "pipewire": False}
    sudo = None
    with tarfile.open(path, "r:gz") as t:
        for m in t:
            k = norm(m.name)
            if k == "usr/local/bin/atlas-desk-session" and m.isreg():
                f = t.extractfile(m)
                data = f.read() if f is not None else b""
                marker = data.count(MARKER)
                if (m.mode & 0o777) != 0o755 or m.uid != 0:
                    raise SystemExit(
                        f"desk session mode/uid {oct(m.mode)}/{m.uid}, want 0755/0")
            elif k in MODES and m.isreg():
                modes[k] = m.mode & 0o777
            elif k == "usr/bin/sudo.real":
                sudo = (m.mode & 0o7777, m.uid, m.gid)
            elif k == "usr/bin/pactl":
                saw["pactl"] = True
            elif k == "usr/bin/pipewire-pulse":
                saw["pipewire"] = True
            elif k == "var/lib/dpkg/status" and m.isreg():
                f = t.extractfile(m)
                data = f.read() if f is not None else b""
                saw["plasma"] = b"Package: plasma-pa\n" in data
    if marker < 1:
        raise SystemExit("baked desk session has no passwd mode guard")
    for k, mode in (("etc/passwd", 0o644), ("etc/group", 0o644)):
        if modes.get(k) != mode:
            raise SystemExit(f"{k} mode {modes.get(k)} want {oct(mode)}")
    for k, mode in (("etc/shadow", 0o640), ("etc/gshadow", 0o640)):
        if k in modes and modes[k] != mode:
            raise SystemExit(f"{k} mode {oct(modes[k])} want {oct(mode)}")
    if sudo is not None:
        if (sudo[0] & 0o4000) == 0 or sudo[1] != 0:
            raise SystemExit(f"sudo.real lost setuid root: {sudo}")
        if sudo_in is not None and sudo != sudo_in:
            raise SystemExit(f"sudo.real changed {sudo_in} -> {sudo}")
    if require_plasma and not (saw["plasma"] and saw["pactl"] and saw["pipewire"]):
        raise SystemExit("baked seed lost plasma-pa, pipewire-pulse, or pactl")
    print(
        f"marker={marker} passwd={oct(modes.get('etc/passwd', 0))} "
        f"group={oct(modes.get('etc/group', 0))} plasma={int(saw['plasma'])}",
        flush=True,
    )

require_plasma = os.path.getsize(src) > 80_000_000
if not needs_rewrite(src):
    prove(src, None, require_plasma)
    print(f"bake_desk_session: current {src}", flush=True)
    sys.exit(0)
print(f"bake_desk_session: rewriting {src}", file=sys.stderr, flush=True)
sudo_in = rewrite(src, dst)
prove(dst, sudo_in, require_plasma)
print(f"bake_desk_session: updated {src}", flush=True)
PY
  then
    rm -f "$tmp"
    echo "bake_desk_session: failed $tar" >&2
    exit 1
  fi
  # prove() already read the temp. Replace only after it returned 0.
  if [ -s "$tmp" ]; then
    mv -f "$tmp" "$tar"
  else
    rm -f "$tmp"
  fi
  if [ -f "${tar}.sha256" ] || [ -f "$(dirname "$tar")/debian-trixie-arm64-rootfs.tar.gz.sha256" ]; then
    sha256sum "$tar" > "${tar}.sha256"
  fi
}

bake_one "$primary"
for t in "${same[@]}"; do
  cp -f "$primary" "$t"
  if [ -f "${t}.sha256" ]; then
    sha256sum "$t" > "${t}.sha256"
  fi
  echo "bake_desk_session: copied onto $t"
done
for t in "${rest[@]}"; do
  bake_one "$t"
done
