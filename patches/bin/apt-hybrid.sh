#!/system/bin/sh
# apt / apt-get / apt-cache for Atlas
#
# ALWAYS requests Authentication Agent for package ops (ATLAS_FORCE_AUTH=1).
# Inside hybrid: force auth → real /usr/bin/apt* as root via sudo.real or agent sudo.
# Outside: force auth → mount hybrid → chroot as root → real apt.
#
# Product: system inject first. App files/bin is optional overlay only.
export PATH=/system/bin:/system/xbin:/vendor/bin:${ATLAS_SYSBIN:-/system/bin}:${ATLAS_BIN:-}:$PATH

BASE=`basename "$0"`
case "$BASE" in
  apt|apt-get|apt-cache) ;;
  *) BASE=apt-get ;;
esac

MERGE=/data/local/atlas-hybrid/merge
REAL="/usr/bin/$BASE"

find_auth() {
  for a in \
    /system/bin/atlas-auth \
    "${ATLAS_SYSBIN:-}/atlas-auth" \
    "${ATLAS_BIN:-}/atlas-auth" \
    "${HOME:-}/bin/atlas-auth" \
    /data/user/0/com.titanus2.atlas/files/bin/atlas-auth \
    /data/data/com.titanus2.atlas/files/bin/atlas-auth
  do
    case "$a" in ""|"/atlas-auth") continue ;; esac
    [ -x "$a" ] && echo "$a" && return 0
  done
  return 1
}

# Force human biometrics for every package op (no silent ticket skip).
force_auth() {
  AUTH=`find_auth` || {
    echo "apt: atlas-auth missing — cannot gate package ops" >&2
    exit 127
  }
  export ATLAS_FORCE_AUTH=1
  "$AUTH" request "apt $BASE $*" || {
    echo "apt: authentication agent denied" >&2
    exit 1
  }
  unset ATLAS_FORCE_AUTH
}

# Android plane: never silent-bridge into Debian apt — user must switch Deb.
if [ "${ATLAS_HYBRID:-0}" != "1" ] && [ "${ATLAS_COMBINED:-0}" != "1" ] \
  && [ ! -f /etc/debian_version ] && [ ! -f /etc/atlas-hybrid-peer ]; then
  echo "apt: blocked on Android plane" >&2
  echo "Switch Atlas top-bar And → Deb (hybrid), then retry: $BASE $*" >&2
  exit 90
fi

# ── Already inside Debian hybrid ──────────────────────────────────────
if [ "${ATLAS_HYBRID:-0}" = "1" ] || [ "${ATLAS_COMBINED:-0}" = "1" ] || [ -f /etc/debian_version ]; then
  if [ ! -x "$REAL" ]; then
    echo "apt: $REAL missing — hybrid image incomplete" >&2
    exit 127
  fi
  # Always prompt user for package ops (one biometric). Then elevate without a
  # second auth hop that re-launches AuthPrompt and reloads the shell.
  force_auth

  if [ "$(id -u)" = "0" ]; then
    exec "$REAL" "$@"
  fi

  # Prefer real setuid sudo.real (agent already said yes → -n)
  if [ -x /usr/bin/sudo.real ]; then
    exec /usr/bin/sudo.real -n "$REAL" "$@"
  fi
  # Agent client — ticket from force_auth; skip second biometric UI.
  # Product: /system/bin/atlas-sudo first (enterd elevate, no KernelSU).
  SUDO=""
  for s in /system/bin/atlas-sudo /system/bin/sudo \
    "${ATLAS_SYSBIN:-}/atlas-sudo" "${ATLAS_BIN:-}/sudo" \
    "${ATLAS_BIN:-}/atlas-sudo"; do
    case "$s" in ""|"/atlas-sudo"|"/sudo") continue ;; esac
    [ -x "$s" ] && SUDO=$s && break
  done
  [ -n "$SUDO" ] || SUDO=`command -v sudo 2>/dev/null`
  if [ -n "$SUDO" ] && [ -x "$SUDO" ]; then
    export ATLAS_SKIP_BIOMETRIC=1
    exec "$SUDO" "$REAL" "$@"
  fi
  echo "apt: no elevate path after auth (need system atlas-sudo / sudo.real)" >&2
  exit 1
fi

# ── Android admin shell: bridge into hybrid as root ───────────────────
HYB=""
for c in \
  "${ATLAS_BIN:-}/atlas-hybrid.sh" \
  "${HOME:-}/bin/atlas-hybrid.sh" \
  /data/local/tmp/atlas-hybrid.sh \
  /system/bin/atlas-hybrid.sh
do
  [ -n "$c" ] && [ -f "$c" ] && HYB=$c && break
done
if [ -z "$HYB" ]; then
  echo "apt: atlas-hybrid.sh missing" >&2
  exit 127
fi

force_auth

# mount hybrid if needed
if ! grep -q " $MERGE " /proc/mounts 2>/dev/null; then
  if [ "$(id -u)" = "0" ]; then
    sh "$HYB" mount || exit 1
  else
    for s in /system/bin/su /system/xbin/su /data/adb/ksu/bin/su; do
      [ -x "$s" ] || continue
      head -c 4 "$s" 2>/dev/null | grep -q ELF || continue
      "$s" 0 sh "$HYB" mount || exit 1
      break
    done
  fi
fi

if [ ! -x "$MERGE$REAL" ]; then
  echo "apt: $REAL missing in hybrid — bootstrap first" >&2
  exit 127
fi

# Root inside chroot (apt writes /var/lib/apt). Agent already OK.
if [ "$(id -u)" = "0" ]; then
  exec /system/bin/chroot "$MERGE" "$REAL" "$@"
fi

for s in /system/bin/su /system/xbin/su /data/adb/ksu/bin/su; do
  [ -x "$s" ] || continue
  head -c 4 "$s" 2>/dev/null | grep -q ELF || continue
  exec "$s" 0 /system/bin/chroot "$MERGE" "$REAL" "$@"
done

echo "apt: no root to enter hybrid chroot" >&2
exit 1
