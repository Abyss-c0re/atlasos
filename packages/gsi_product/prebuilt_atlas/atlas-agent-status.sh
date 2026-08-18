#!/system/bin/sh
# atlas-agent-status — one-screen hybrid/android plane for agents + reports.
# SoT for "what mode am I in?" — always run before claiming hybrid or Android IPC.
set -f
export PATH="/system/bin:/system/xbin:/system_ext/bin:/product/bin:/usr/local/bin:/usr/bin:/bin:$PATH"

HOME="${HOME:-$ATLAS_HOME}"
[ -z "$HOME" ] && HOME=/data/local/atlas-home/atlas
OUT="${1:-}"
# Plane status is not user HOME.
ST_HOME=/data/local/tmp/ATLAS_STATUS
ST_TMP=/data/local/tmp/atlas_status.txt
mkdir -p "$HOME/reports" 2>/dev/null || true

is_hybrid_env() {
  [ "${ATLAS_HYBRID:-0}" = "1" ] && return 0
  [ "${ATLAS_COMBINED:-0}" = "1" ] && return 0
  [ "${ATLAS_SESSION:-}" = "hybrid" ] && return 0
  [ -f /etc/atlas-hybrid-peer ] && return 0
  [ -f /etc/os-release ] && grep -qi debian /etc/os-release 2>/dev/null && return 0
  return 1
}

mode=android
plane=android
if is_hybrid_env; then
  mode=debian
  plane=hybrid
fi
# disk ready?
disk=no
storage=none
overlay=no
if grep -q atlas_linux_a /proc/self/mountinfo 2>/dev/null; then
  disk=yes
  storage=lp
  overlay=no
elif grep -q ' /data/local/atlas-hybrid/merge overlay ' /proc/mounts 2>/dev/null; then
  disk=yes
  storage=overlay
  overlay=yes
elif [ -f /data/local/atlas-hybrid/merge/usr/bin/bash ] || [ -f /etc/debian_version ]; then
  disk=yes
  storage=lp
  overlay=no
fi
rootfs=/
[ -d /data/local/atlas-hybrid/merge ] && [ "$(readlink /proc/self/root 2>/dev/null)" != "/" ] && rootfs=$(readlink /proc/self/root 2>/dev/null)
# simpler: check if / is debian
os_pretty=android
[ -f /etc/os-release ] && os_pretty=$(. /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-unknown}")

binder=unknown
if [ -e /dev/binder ] || [ -e /dev/binderfs/binder ]; then
  if [ -c /dev/binderfs/binder ] || [ -c /dev/binder ]; then
    binder=present
  else
    binder=broken_symlink
  fi
else
  binder=missing
fi
# hybrid without init ns → Android IPC needs android / android-exec
android_ipc=direct
if [ "$plane" = "hybrid" ]; then
  if [ -x /usr/local/libexec/atlas-android ] || [ -x /system/bin/atlas-android ]; then
    android_ipc=atlas-android-wrap
  elif [ -x /usr/local/libexec/atlas-android-exec ] || [ -x /usr/local/bin/android ]; then
    android_ipc=android-exec_nsenter
  else
    android_ipc=nsenter-required
  fi
fi

report() {
  echo "=== ATLAS PLANE (agent) ==="
  echo "atlas_version=${ATLAS_APP_VERSION:-unknown}"
  echo "plane=$plane"
  echo "mode=$mode"
  echo "session=${ATLAS_SESSION:-unset}"
  echo "priv=${ATLAS_PRIV:-0}"
  echo "hybrid_env=$(is_hybrid_env && echo yes || echo no)"
  echo "hybrid_disk=$disk"
  echo "hybrid_overlay=$overlay"
  echo "storage=$storage"
  echo "os_pretty=$os_pretty"
  echo "uid=$(id -u 2>/dev/null) user=${USER:-?} role=${ATLAS_ROLE:-?}"
  echo "home=$HOME"
  echo "atlas_bin=${ATLAS_BIN:-}"
  echo "atlas_sysbin=${ATLAS_SYSBIN:-/system/bin}"
  echo "binder=$binder"
  echo "android_ipc=$android_ipc"
  echo "reports_dir=$HOME/reports"
  echo "status_file=$ST_HOME"
  echo "plane_files=/data/local/tmp/titan2_atlas_mode /data/misc/titan2/"
  if [ -f /data/local/tmp/titan2_atlas_mode ]; then
    echo "titan2_atlas_mode=$(cat /data/local/tmp/titan2_atlas_mode 2>/dev/null)"
  fi
  echo "=== BRIDGE (read this) ==="
  echo "Debian cannot see Android Binder or user files."
  echo "ONE command:  android <tool> [args]"
  echo "  android screencap -p \$HOME/exports/x.png"
  echo "  android am start -n …"
  echo "  android cat|write|ls <android-path>"
  echo "Do not run: screencap screenshot atlas-screencap am pm (not Deb tools)"
  echo "Policy: /var/lib/atlas-auth/policy  (allow|ask|deny)"
  echo "Reports: \$HOME/reports/"
  if [ -f /var/lib/atlas-auth/policy ]; then
    echo "=== POLICY ==="
    grep -E '^(storage|cmd\.|default|bridge)=' /var/lib/atlas-auth/policy 2>/dev/null
  fi
}

TEXT=$(report)
echo "$TEXT"
printf '%s\n' "$TEXT" >"$ST_HOME" 2>/dev/null || true
printf '%s\n' "$TEXT" >"$ST_TMP" 2>/dev/null || true
# machine-readable one-liner for agents
printf 'plane=%s mode=%s disk=%s overlay=%s os=%s android_ipc=%s\n' \
  "$plane" "$mode" "$disk" "$overlay" "$os_pretty" "$android_ipc" \
  >"$HOME/ATLAS_PLANE.env" 2>/dev/null || true
printf 'plane=%s mode=%s disk=%s overlay=%s android_ipc=%s\n' \
  "$plane" "$mode" "$disk" "$overlay" "$android_ipc" \
  >/data/local/tmp/atlas_plane.env 2>/dev/null || true

if [ -n "$OUT" ]; then
  printf '%s\n' "$TEXT" >"$OUT"
fi
