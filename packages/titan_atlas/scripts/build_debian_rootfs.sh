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

# Cross-arch seed needs binfmt qemu (host amd64 building arm64 rootfs).
# Without this, docker run --platform linux/arm64 dies: exec format error.
if [ "$(uname -m)" != "aarch64" ] && [ "$ARCH" = "arm64" ]; then
  if ! docker run --rm --platform "linux/$ARCH" "$IMAGE" true >/dev/null 2>&1; then
    log "register qemu-user-static binfmt for cross-arch seed…"
    docker run --rm --privileged multiarch/qemu-user-static --reset -p yes >/dev/null
  fi
  if ! docker run --rm --platform "linux/$ARCH" "$IMAGE" true >/dev/null 2>&1; then
    log "FATAL: cannot exec linux/$ARCH containers (install qemu-user-static / binfmt)"
    exit 1
  fi
fi

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

# Seed apt sources (trixie) — single SoT: classic sources.list only.
# trixie-slim ships deb822 /etc/apt/sources.list.d/debian.sources; dual files
# → apt Warning "Target Packages configured multiple times" (lab UI).
# Remove deb822 before and after seed apt (packages may restore it).
_atlas_apt_sources_list() {
  rm -f "$WORKDIR/etc/apt/sources.list.d/debian.sources" \
        "$WORKDIR/etc/apt/sources.list.d/"*.sources 2>/dev/null || true
  mkdir -p "$WORKDIR/etc/apt/sources.list.d"
  cat >"$WORKDIR/etc/apt/sources.list" <<EOF
deb $MIRROR $CODENAME main contrib non-free non-free-firmware
deb $MIRROR $CODENAME-updates main contrib non-free non-free-firmware
deb http://security.debian.org/debian-security $CODENAME-security main contrib non-free non-free-firmware
EOF
}
_atlas_apt_sources_list

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
# LAW: single apt SoT — drop deb822 if seed apt re-added it
rm -f /rootfs/etc/apt/sources.list.d/debian.sources \
      /rootfs/etc/apt/sources.list.d/*.sources 2>/dev/null || true
# hybrid marker: real-system seed
printf 'atlas-essentials=2\n' >> /rootfs/etc/atlas-hybrid-peer
chroot /rootfs apt-get clean
rm -rf /rootfs/var/lib/apt/lists/*
umount /rootfs/proc /rootfs/sys /rootfs/dev 2>/dev/null || true
SEED
chmod +x "$SEED_SH"
# FAIL CLOSED: essentials seed must land curl/ca-certs (product Deb usable).
# Silent best-effort left slim-only LP → curl/ca-certs missing (lab 2026-08-13 heresy).
if ! docker run --rm --privileged --platform "linux/$ARCH" \
  -v "$WORKDIR:/rootfs" \
  -v "$SEED_SH:/seed.sh:ro" \
  "$IMAGE" bash /seed.sh 2>&1 | tee "$OUT_DIR/seed_chroot.log" | tail -80
then
  log "FATAL: essentials seed failed — see $OUT_DIR/seed_chroot.log"
  exit 1
fi
# Prove seed (not theater)
_seed_need=(
  usr/bin/curl
  usr/bin/wget
  usr/bin/openssl
  etc/ssl/certs/ca-certificates.crt
  usr/bin/ping
  usr/bin/dig
  usr/bin/nano
  usr/bin/sudo
  usr/bin/git
  usr/bin/python3
)
for _rel in "${_seed_need[@]}"; do
  if [ ! -e "$WORKDIR/$_rel" ] && [ ! -L "$WORKDIR/$_rel" ]; then
    log "FATAL: seed missing $_rel after apt install"
    exit 1
  fi
done
# ping may be in bin/ via alternatives
if [ ! -x "$WORKDIR/usr/bin/ping" ] && [ ! -x "$WORKDIR/bin/ping" ]; then
  log "FATAL: seed missing ping binary"
  exit 1
fi
if ! grep -q 'atlas-essentials=2' "$WORKDIR/etc/atlas-hybrid-peer" 2>/dev/null; then
  log "FATAL: atlas-essentials marker missing"
  exit 1
fi
log "seed PROVEN: curl + ca-certificates + ping + dig + essentials"

# Strip machine-id for first boot uniqueness
rm -f "$WORKDIR/etc/machine-id" "$WORKDIR/var/lib/dbus/machine-id" 2>/dev/null || true
: >"$WORKDIR/etc/machine-id"

log "pack $TAR"
# Pack as root via docker so root-owned files (sudoers, ssl/private) are included.
# Host tar as non-root silently drops them (Permission denied) → broken sudo seed.
# Use host-native platform for pack (just tar, no arch-specific exec needed).
if ! docker run --rm \
  -v "$WORKDIR:/rootfs:ro" \
  -v "$OUT_DIR:/out" \
  debian:trixie-slim \
  bash -c "tar -czf /out/$(basename "$TAR") -C /rootfs . && chown $(id -u):$(id -g) /out/$(basename "$TAR")"; then
  log "FATAL: docker pack failed"
  exit 1
fi
[ -f "$TAR" ] || { log "FATAL: missing $TAR after pack"; exit 1; }
# Re-prove critical paths inside the tar (not just workdir)
for _rel in usr/bin/curl etc/ssl/certs/ca-certificates.crt etc/sudoers usr/bin/ping; do
  if ! tar -tzf "$TAR" | grep -E -q "(^|./)${_rel}$"; then
    log "FATAL: packed tar missing $_rel"
    exit 1
  fi
done
# No dual apt sources in seed (sources.list + deb822 → yellow apt spam)
if tar -tzf "$TAR" | grep -E -q 'etc/apt/sources\.list\.d/.*\.sources$'; then
  log "FATAL: seed still has deb822 *.sources — dual apt config"
  exit 1
fi
# LAW: Grok is a user CLI — never bake it into the Debian image.
# _ngrok bash-completion (unrelated) may exist; grok ELF / ~/.grok must not.
if tar -tzf "$TAR" | grep -E -q '(^|./)(usr/bin/grok|usr/local/bin/grok|home/.*/\.grok/|root/\.grok/)'; then
  log "FATAL: seed ships grok — user CLI must not be in debian image"
  exit 1
fi
# LAW: sudoers must be archive uid 0 — host extract as non-root remaps to 1000
# and bakes broken LP. Fail closed at seed pack, not after flash.
if ! python3 - "$TAR" <<'PY'
import sys, tarfile
tar = sys.argv[1]
need = ("etc/sudoers", "etc/sudo.conf", "usr/bin/sudo")
with tarfile.open(tar, "r:gz") as t:
    for rel in need:
        for cand in ("./" + rel, rel):
            try:
                m = t.getmember(cand)
                break
            except KeyError:
                m = None
        if m is None:
            print("FATAL: tar missing", rel, file=sys.stderr)
            sys.exit(1)
        if m.uid != 0 or m.gid != 0:
            print(f"FATAL: {cand} uid/gid={m.uid}/{m.gid} want 0/0", file=sys.stderr)
            sys.exit(1)
        print(f"seed_uid_ok {cand} uid=0 mode={oct(m.mode)}")
sys.exit(0)
PY
then
  log "FATAL: seed tar ownership gate failed (sudoers must be uid 0)"
  exit 1
fi
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
