#!/usr/bin/env bash
# build_debian_rootfs.sh — host-side arm64 Debian rootfs for Atlas hybrid
#
# Pin matches Armbian recommended base for Radxa NIO 12L (16GB lab peer):
#   Debian 13 "trixie"  — https://armbian.com/boards/radxa-nio-12l
#   Armbian: RELEASE=trixie BUILD_MINIMAL=yes
#
# Output (gitignored artifacts / local):
#   out/debian-trixie-arm64-rootfs.tar.gz
#   out/debian-trixie-arm64-rootfs.sha256
#   out/debian-trixie-arm64-rootfs.META
#
# Usage:
#   ./packages/titan_atlas/scripts/build_debian_rootfs.sh
#   ./packages/titan_atlas/scripts/build_debian_rootfs.sh --push   # adb push + hybrid rebootstrap
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
OUT_DIR="${ATLAS_ROOTFS_OUT:-$ROOT/out/atlas_rootfs}"
DISTRO="${ATLAS_DEBIAN_DISTRO:-debian}"
CODENAME="${ATLAS_DEBIAN_CODENAME:-trixie}"
ARCH="${ATLAS_DEBIAN_ARCH:-arm64}"
VARIANT="${ATLAS_DEBIAN_VARIANT:-minbase}"
MIRROR="${ATLAS_DEBIAN_MIRROR:-http://deb.debian.org/debian}"
IMAGE="debian:${CODENAME}-slim"
NAME="debian-${CODENAME}-${ARCH}-rootfs"
TAR="$OUT_DIR/${NAME}.tar.gz"
META="$OUT_DIR/${NAME}.META"
SHA="$OUT_DIR/${NAME}.sha256"
WORKDIR="$OUT_DIR/work-${CODENAME}-${ARCH}"
PUSH=0
for a in "$@"; do
  case "$a" in
    --push) PUSH=1 ;;
    -h|--help)
      sed -n '2,20p' "$0"
      exit 0
      ;;
  esac
done

mkdir -p "$OUT_DIR"
log() { echo "build_debian_rootfs: $*" >&2; }

need() { command -v "$1" >/dev/null 2>&1 || { log "need $1"; exit 1; }; }
need docker
need tar
need gzip
need sha256sum

log "pin: $DISTRO $CODENAME $ARCH (Armbian NIO 12L peer base)"
log "image: $IMAGE (platform linux/$ARCH)"

docker pull --platform "linux/$ARCH" "$IMAGE"

rm -rf "$WORKDIR"
mkdir -p "$WORKDIR"

# Export a clean rootfs from official Debian slim + seed packages used on hybrid.
# Product: essentials only (no proprietary CLIs — user may install those later).
# No Magisk; no Android bits; no HW-keyboard packages (out of Atlas ROM scope).
log "export rootfs via container…"
cid=$(docker create --platform "linux/$ARCH" "$IMAGE" /bin/true)
trap 'docker rm -f "$cid" >/dev/null 2>&1 || true' EXIT
docker export "$cid" | tar -x -C "$WORKDIR"
docker rm -f "$cid" >/dev/null 2>&1 || true
trap - EXIT

# Seed apt sources (trixie) — match Armbian Debian line, not Ubuntu jammy
cat >"$WORKDIR/etc/apt/sources.list" <<EOF
deb $MIRROR $CODENAME main contrib non-free non-free-firmware
deb $MIRROR $CODENAME-updates main contrib non-free non-free-firmware
deb http://security.debian.org/debian-security $CODENAME-security main contrib non-free non-free-firmware
EOF

# Hybrid mount points (Android binds later on device)
mkdir -p "$WORKDIR"/{tmp,system,vendor,product,system_ext,apex,data,sdcard,storage,mnt,dev,proc,sys,data/local/tmp}
chmod 1777 "$WORKDIR/tmp" 2>/dev/null || true

# Marker consumed by atlas-hybrid
printf 'atlas-hybrid-rootfs %s %s %s\n' "$DISTRO" "$CODENAME" "$ARCH" \
  >"$WORKDIR/.atlas-hybrid-rootfs"
printf 'peer=armbian-radxa-nio-12l\nbase=debian-%s\nram_peer=16G\nessentials=1\n' "$CODENAME" \
  >"$WORKDIR/etc/atlas-hybrid-peer"

# Essentials seed — network, editor, process tools. Supports user curl|bash installers
# (redirects + TLS). Does NOT ship third-party proprietary CLIs.
log "seed packages (essentials: curl nano ca-certificates …)…"
SEED_SH="$OUT_DIR/seed_chroot.sh"
cat >"$SEED_SH" <<'SEED'
#!/bin/bash
set -e
export DEBIAN_FRONTEND=noninteractive
mount -t proc proc /rootfs/proc 2>/dev/null || true
mount -t sysfs sys /rootfs/sys 2>/dev/null || true
mount -o bind /dev /rootfs/dev 2>/dev/null || true
cp -a /etc/resolv.conf /rootfs/etc/resolv.conf 2>/dev/null || \
  printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > /rootfs/etc/resolv.conf
chroot /rootfs apt-get update -qq
# Real-userspace essentials (not a full desktop; apt remains for the rest).
# No proprietary CLIs. Designed so user installers (curl -L redirects + TLS) work.
chroot /rootfs apt-get install -y -qq --no-install-recommends \
  ca-certificates \
  curl \
  wget \
  openssl \
  nano \
  vim-tiny \
  less \
  procps \
  psmisc \
  iproute2 \
  iputils-ping \
  net-tools \
  dnsutils \
  locales \
  util-linux \
  cron \
  openssh-client \
  python3-minimal \
  git \
  file \
  sudo \
  hostname \
  xz-utils \
  tar \
  gzip \
  bzip2 \
  unzip \
  findutils \
  grep \
  sed \
  gawk \
  coreutils \
  bash-completion
# TLS trust for install scripts that follow redirects
chroot /rootfs update-ca-certificates 2>/dev/null || true
# minimal locale so tools stop complaining
echo 'en_US.UTF-8 UTF-8' > /rootfs/etc/locale.gen
chroot /rootfs locale-gen en_US.UTF-8 2>/dev/null || true
# sticky tmp for apt
chmod 1777 /rootfs/tmp /rootfs/var/tmp 2>/dev/null || true
# hybrid marker: real-system seed
printf 'atlas-essentials=2\n' >> /rootfs/etc/atlas-hybrid-peer
chroot /rootfs apt-get clean
rm -rf /rootfs/var/lib/apt/lists/*
umount /rootfs/proc /rootfs/sys /rootfs/dev 2>/dev/null || true
SEED
chmod +x "$SEED_SH"
docker run --rm --privileged --platform "linux/$ARCH" \
  -v "$WORKDIR:/rootfs" \
  -v "$SEED_SH:/seed.sh:ro" \
  "$IMAGE" bash /seed.sh 2>&1 | tail -50 \
  || log "seed best-effort — slim image already has bash/apt"

# Strip machine-id for first boot uniqueness
rm -f "$WORKDIR/etc/machine-id" "$WORKDIR/var/lib/dbus/machine-id" 2>/dev/null || true
: >"$WORKDIR/etc/machine-id"

log "pack $TAR"
tar -czf "$TAR" -C "$WORKDIR" .
sha256sum "$TAR" | tee "$SHA"
{
  echo "name=$NAME"
  echo "distro=$DISTRO"
  echo "codename=$CODENAME"
  echo "arch=$ARCH"
  echo "variant=docker-slim+essentials-v2"
  echo "seed=ca-certificates,curl,wget,openssl,nano,vim-tiny,less,procps,psmisc,iproute2,iputils-ping,net-tools,dnsutils,locales,util-linux,cron,openssh-client,python3-minimal,git,file,sudo,hostname,xz-utils,tar,gzip,bzip2,unzip,findutils,grep,sed,gawk,coreutils,bash-completion"
  echo "no_proprietary_cli=1"
  echo "android_bins=via_android_exec_nsenter"
  echo "peer_board=radxa-nio-12l"
  echo "peer_armbian_release=trixie"
  echo "peer_ram=16G"
  echo "built=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "sha256=$(cut -d' ' -f1 "$SHA")"
  echo "bytes=$(stat -c%s "$TAR")"
  echo "tar=$TAR"
} | tee "$META"

log "OK $TAR ($(du -h "$TAR" | awk '{print $1}'))"
log "META: peer Armbian NIO 12L Debian $CODENAME · Titan hybrid shares this base"

if [ "$PUSH" = "1" ]; then
  SERIAL="${ANDROID_SERIAL:-${SERIAL:-}}"
  if [ -z "$SERIAL" ]; then
    SERIAL=$(adb devices 2>/dev/null | awk '/device$/{print $1; exit}')
  fi
  [ -n "$SERIAL" ] || { log "no adb device for --push"; exit 1; }
  log "push → $SERIAL /data/local/tmp/atlas-hybrid-dl/rootfs.tar.gz"
  adb -s "$SERIAL" shell 'su 0 mkdir -p /data/local/tmp/atlas-hybrid-dl'
  adb -s "$SERIAL" push "$TAR" /data/local/tmp/atlas-hybrid-dl/rootfs.tar.gz
  adb -s "$SERIAL" push \
    "$ROOT/packages/titan_atlas/scripts/atlas-hybrid.sh" \
    /data/local/tmp/atlas-hybrid.sh
  adb -s "$SERIAL" shell 'su 0 sh -c "
    chmod 755 /data/local/tmp/atlas-hybrid.sh
    /data/local/tmp/atlas-hybrid.sh destroy || true
    /data/local/tmp/atlas-hybrid.sh bootstrap
    /data/local/tmp/atlas-hybrid.sh status
  "'
fi
