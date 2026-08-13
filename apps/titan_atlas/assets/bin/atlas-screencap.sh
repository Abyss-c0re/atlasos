#!/system/bin/sh
# atlas-screencap — hybrid-safe screenshot (agent report I-01/I-02/I-03 + 2026-08-13).
# Bare `screencap` from Debian hybrid hangs on empty binderfs. Always use this.
# Fail-fast when Binder is missing (report: exit 124 timeout → clear error).
set -f
export PATH="/system/bin:/system/xbin:/product/bin:/usr/local/bin:/usr/bin:/bin:$PATH"

HOME="${HOME:-$ATLAS_HOME}"
[ -z "$HOME" ] && HOME=/data/user/0/com.titanus2.atlas/files
OUT="${1:-}"
if [ -z "$OUT" ]; then
  mkdir -p "$HOME/screenshots" 2>/dev/null || true
  # Prefer Android Documents when writable (agent deliverables)
  if [ -d /sdcard/Documents ] && [ -w /sdcard/Documents ]; then
    OUT="/sdcard/Documents/atlas-$(date +%Y%m%dT%H%M%S).png"
  else
    OUT="$HOME/screenshots/atlas-$(date +%Y%m%dT%H%M%S).png"
  fi
fi
mkdir -p "$(dirname "$OUT")" 2>/dev/null || true

# Prefer hybrid android-exec / atlas-android-exec (nsenter init mount ns)
run_android() {
  if [ -x /usr/local/libexec/atlas-android-exec ]; then
    /usr/local/libexec/atlas-android-exec "$@"
    return $?
  fi
  if [ -x /usr/local/bin/android-exec ]; then
    /usr/local/bin/android-exec "$@"
    return $?
  fi
  if [ -x /system/bin/nsenter ]; then
    /system/bin/nsenter -t 1 -m -- env PATH=/system/bin:/system/xbin \
      ANDROID_DATA=/data ANDROID_ROOT=/system "$@"
    return $?
  fi
  "$@"
}

# Fail-fast: empty binderfs / missing binder → no SurfaceFlinger IPC (report §4).
binder_live() {
  if [ -e /dev/binder ] || [ -e /dev/binderfs/binder ]; then
    return 0
  fi
  # Check via android-exec (init mount ns may have nodes Deb ns lacks)
  if run_android /system/bin/ls /dev/binderfs/binder >/dev/null 2>&1 \
    || run_android /system/bin/ls /dev/binder >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

if ! binder_live; then
  echo "atlas-screencap: FAIL binder=missing (empty /dev/binderfs — hybrid ns)" >&2
  echo "atlas-screencap: heal: approve Atlas privilege elevate, then: atlas-hybrid ensure" >&2
  echo "atlas-screencap: or capture from Android shell: adb shell screencap -p …" >&2
  exit 75
fi

pick_display() {
  ids=$(timeout 4 run_android /system/bin/dumpsys SurfaceFlinger --display-id 2>/dev/null)
  echo "$ids" | awk '
    /Display [0-9]+/ { id=$2 }
    /size/ && /1440/ { print id; found=1; exit }
    END { if (!found && id != "") print id }
  ' | head -1
}

DID=$(pick_display)
SC=/system/bin/screencap
[ -x "$SC" ] || SC=screencap

if [ -n "$DID" ]; then
  timeout 8 run_android "$SC" -d "$DID" -p "$OUT" 2>/dev/null \
    || timeout 8 run_android "$SC" -p "$OUT"
else
  timeout 8 run_android "$SC" -p "$OUT"
fi
ec=$?
if [ -f "$OUT" ] && [ -s "$OUT" ]; then
  sz=$(stat -c %s "$OUT" 2>/dev/null || echo 0)
  if [ "$sz" -lt 5000 ]; then
    echo "atlas-screencap: WARN tiny PNG (${sz}B) — wrong display? path=$OUT" >&2
  fi
  echo "$OUT"
  exit 0
fi
echo "atlas-screencap: FAIL ec=$ec out=$OUT" >&2
exit ${ec:-1}
