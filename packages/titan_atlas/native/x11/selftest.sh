#!/bin/bash
# Prove: a key on the seat changes the buffer, and a pull returns only the
# latest handle. Then, if Xwayland is installed, an X11 root fill shows up
# on the panel output.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
PKG="$(cd "$ROOT/../.." && pwd)"
BIN="$PKG/out/x11"
DIR=""
SRV=0

cleanup() {
  if [ "$SRV" != 0 ]; then kill "$SRV" 2>/dev/null || true; wait "$SRV" 2>/dev/null || true; fi
  [ -n "$DIR" ] && rm -rf "$DIR"
  true
}
trap cleanup EXIT

wait_file() {
  local f="$1" i
  for i in $(seq 1 100); do
    [ -e "$f" ] && return 0
    sleep 0.05
  done
  echo "timeout waiting for $f" >&2
  return 1
}

pull_pixels() {
  local extra=()
  [ -n "${1:-}" ] && extra=(--min-gen "$1")
  "$BIN/atlas-x" --pull -d "$DIR" --timeout 2000 "${extra[@]}" >"$DIR/frame" 2>"$DIR/pull.err"
  cat "$DIR/pull.err" >&2
}

expect_pixel() {
  local want="$1" flags="$2"
  python3 - "$DIR/frame" "$DIR/pull.err" "$want" "$flags" <<'PY'
import re, struct, sys
frame, err, want, flags = sys.argv[1:]
meta = open(err).read().strip().splitlines()[-1]
m = re.search(r"flags=(\d+)\s+gen=(\d+)", meta)
if not m:
    sys.exit(f"no present line: {meta}")
if m.group(1) != flags:
    sys.exit(f"flags {m.group(1)} != {flags} ({meta})")
if int(m.group(2)) < 3:
    sys.exit(f"gen {m.group(2)} < 3; an earlier frame was kept ({meta})")
raw = open(frame, "rb").read()
pix = struct.unpack_from("<I", raw)[0]
want_i = int(want, 16)
if pix != want_i:
    sys.exit(f"pixel {pix:#x} != {want_i:#x} ({meta})")
print(meta)
PY
}

start_server() {
  local geom="$1"
  local xflag="${2:-}"
  DIR="$(mktemp -d /tmp/atlas-x.XXXXXX)"
  # shellcheck disable=SC2086
  "$BIN/atlas-x" -d "$DIR" -g "$geom" $xflag >"$DIR/server.log" 2>&1 &
  SRV=$!
  wait_file "$DIR/present.sock"
  wait_file "$DIR/wayland-0"
}

stop_server() {
  kill "$SRV" 2>/dev/null || true
  wait "$SRV" 2>/dev/null || true
  SRV=0
  rm -rf "$DIR"
  DIR=""
}

echo "== shm drop-old =="
start_server 32x32
"$BIN/atlas-x-paint" -d "$DIR" &
PAINT=$!
wait_file "$DIR/mapped"
"$BIN/atlas-x" --key -d "$DIR" 19
wait "$PAINT"
wait_file "$DIR/ready"
pull_pixels
expect_pixel ff0000ff 1
stop_server

echo "== dmabuf handle =="
start_server 32x32
"$BIN/atlas-x-paint" -d "$DIR" --dma &
PAINT=$!
wait_file "$DIR/mapped"
"$BIN/atlas-x" --key -d "$DIR" 19
wait "$PAINT"
wait_file "$DIR/ready"
pull_pixels
expect_pixel ff0000ff 2
stop_server

if ! command -v Xwayland >/dev/null; then
  echo "Xwayland not installed; skipped the X11 wire check"
  exit 0
fi

echo "== Xwayland root fill =="
start_server 800x600 -X
wait_file "$DIR/xdisplay"
DISP="$(tr -d '[:space:]' <"$DIR/xdisplay")"
"$BIN/atlas-x-xdraw" "$DISP"
ok=0
gen=0
for _ in $(seq 1 25); do
  if "$BIN/atlas-x" --pull -d "$DIR" --min-gen "$gen" --timeout 400 >"$DIR/frame" 2>"$DIR/pull.err"; then
    cat "$DIR/pull.err" >&2
    gen="$(sed -n 's/.*gen=\([0-9]*\).*/\1/p' "$DIR/pull.err" | tail -1)"
    gen=$((gen + 1))
    if python3 - "$DIR/frame" <<'PY'
import struct, sys
raw = open(sys.argv[1], "rb").read()
n = len(raw) // 4
for i in range(n):
    p = struct.unpack_from("<I", raw, i * 4)[0]
    r = (p >> 16) & 0xFF
    g = (p >> 8) & 0xFF
    b = p & 0xFF
    if r > 200 and g < 80 and b < 80:
        sys.exit(0)
sys.exit(1)
PY
    then
      if ! grep -q '800x600' "$DIR/pull.err"; then
        echo "Xwayland frame is not the panel size" >&2
        cat "$DIR/pull.err" >&2
        exit 1
      fi
      ok=1
      break
    fi
  fi
done
if [ "$ok" != 1 ]; then
  echo "Xwayland frame had no red pixel" >&2
  echo "---- server ----" >&2
  cat "$DIR/server.log" >&2 || true
  echo "---- xwayland ----" >&2
  cat "$DIR/xwayland.log" >&2 || true
  exit 1
fi
echo "Xwayland present carries the X root"
stop_server
echo "atlas-x selftest passed"
