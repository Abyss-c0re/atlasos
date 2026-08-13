#!/system/bin/sh
# apt / apt-get / apt-cache for Atlas Debian
#
# LAW: package ops stay on Deb plane — NO biometric auth.
# Bio is only for Deb → Android access (optional; see atlas-android-exec).
#
# Inside hybrid: elevate (enterd/atlas-sudo or sudo.real) → real /usr/bin/apt*.
# Outside: blocked — switch And → Deb first.
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

# Android plane: never silent-bridge into Debian apt — user must switch Deb.
if [ "${ATLAS_HYBRID:-0}" != "1" ] && [ "${ATLAS_COMBINED:-0}" != "1" ] \
  && [ ! -f /etc/debian_version ] && [ ! -f /etc/atlas-hybrid-peer ]; then
  echo "apt: blocked on Android plane" >&2
  echo "Switch Atlas top-bar And → Deb (hybrid), then retry: $BASE $*" >&2
  exit 90
fi

find_elevate() {
  for s in \
    /system/bin/atlas-sudo \
    /system/bin/sudo \
    "${ATLAS_SYSBIN:-}/atlas-sudo" \
    "${ATLAS_BIN:-}/atlas-sudo" \
    "${ATLAS_BIN:-}/sudo"
  do
    case "$s" in ""|"/atlas-sudo"|"/sudo") continue ;; esac
    [ -x "$s" ] && echo "$s" && return 0
  done
  command -v sudo 2>/dev/null
}

# Deb-internal elevate — no biometrics (ATLAS_SKIP_BIOMETRIC=1).
elevate_exec() {
  if [ "$(id -u)" = "0" ]; then
    exec "$@"
  fi
  if [ -x /usr/bin/sudo.real ]; then
    exec /usr/bin/sudo.real -n "$@"
  fi
  SUDO=`find_elevate`
  if [ -n "$SUDO" ] && [ -x "$SUDO" ]; then
    export ATLAS_SKIP_BIOMETRIC=1
    exec "$SUDO" "$@"
  fi
  echo "apt: need root elevate (atlas-sudo / sudo.real) — no bio required" >&2
  exit 1
}

# ── Already inside Debian hybrid ──────────────────────────────────────
if [ "${ATLAS_HYBRID:-0}" = "1" ] || [ "${ATLAS_COMBINED:-0}" = "1" ] || [ -f /etc/debian_version ]; then
  if [ ! -x "$REAL" ]; then
    echo "apt: $REAL missing — hybrid image incomplete" >&2
    exit 127
  fi
  elevate_exec "$REAL" "$@"
fi

# ── Should not reach: outside Deb ─────────────────────────────────────
echo "apt: not in Debian hybrid" >&2
exit 90
