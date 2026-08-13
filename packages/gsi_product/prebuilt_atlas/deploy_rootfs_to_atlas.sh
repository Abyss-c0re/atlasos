#!/usr/bin/env bash
# deploy_rootfs_to_atlas.sh — stage the built Debian trixie image into Atlas app
# and run hybrid bootstrap under KernelSU (no Magisk modules).
#
#   ./packages/titan_atlas/scripts/build_debian_rootfs.sh
#   ./packages/titan_atlas/scripts/deploy_rootfs_to_atlas.sh
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
TAR="${ATLAS_ROOTFS_TAR:-$ROOT/out/atlas_rootfs/debian-trixie-arm64-rootfs.tar.gz}"
HYB="$ROOT/packages/titan_atlas/scripts/atlas-hybrid.sh"
SERIAL="${ANDROID_SERIAL:-${SERIAL:-}}"
[ -n "$SERIAL" ] || SERIAL=$(adb devices 2>/dev/null | awk '/device$/{print $1; exit}')
[ -n "$SERIAL" ] || { echo "no adb device" >&2; exit 1; }
[ -f "$TAR" ] || { echo "missing $TAR — run build_debian_rootfs.sh first" >&2; exit 1; }

HOME_ATLAS=/data/user/0/com.titanus2.atlas/files
echo "deploy: $TAR → $SERIAL Atlas $HOME_ATLAS/rootfs/"
adb -s "$SERIAL" shell "mkdir -p $HOME_ATLAS/rootfs 2>/dev/null || true"
# App may not allow shell write; use root
adb -s "$SERIAL" shell "su 0 mkdir -p $HOME_ATLAS/rootfs /data/local/tmp/atlas-hybrid-dl"
adb -s "$SERIAL" push "$TAR" /data/local/tmp/atlas-hybrid-dl/debian-trixie-arm64-rootfs.tar.gz
adb -s "$SERIAL" shell "su 0 sh -c '
  cp -f /data/local/tmp/atlas-hybrid-dl/debian-trixie-arm64-rootfs.tar.gz \
    $HOME_ATLAS/rootfs/debian-trixie-arm64-rootfs.tar.gz
  cp -f /data/local/tmp/atlas-hybrid-dl/debian-trixie-arm64-rootfs.tar.gz \
    /data/local/tmp/atlas-hybrid-dl/rootfs.tar.gz
  U=\$(stat -c %u $HOME_ATLAS)
  G=\$(stat -c %g $HOME_ATLAS)
  chown -R \$U:\$G $HOME_ATLAS/rootfs
  chmod 644 $HOME_ATLAS/rootfs/debian-trixie-arm64-rootfs.tar.gz
'"
adb -s "$SERIAL" push "$HYB" /data/local/tmp/atlas-hybrid.sh
adb -s "$SERIAL" shell "su 0 sh -c '
  chmod 755 /data/local/tmp/atlas-hybrid.sh
  cp -f /data/local/tmp/atlas-hybrid.sh $HOME_ATLAS/bin/atlas-hybrid.sh
  U=\$(stat -c %u $HOME_ATLAS); G=\$(stat -c %g $HOME_ATLAS)
  chown \$U:\$G $HOME_ATLAS/bin/atlas-hybrid.sh
  chmod 755 $HOME_ATLAS/bin/atlas-hybrid.sh
  export HOME=$HOME_ATLAS ATLAS_HOME=$HOME_ATLAS ATLAS_BIN=$HOME_ATLAS/bin
  # re-deploy same image even if old ubuntu jammy present
  /data/local/tmp/atlas-hybrid.sh destroy || true
  /data/local/tmp/atlas-hybrid.sh bootstrap
  /data/local/tmp/atlas-hybrid.sh status
'"
echo "deploy: OK — in Atlas: hybrid status | hybrid enter | debian button"
