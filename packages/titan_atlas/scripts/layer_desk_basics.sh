#!/bin/bash
# Put the desk audio packages into the Debian seed the ROM packs.
# The host cannot chroot arm64, so the arm64 debs are downloaded and unpacked.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
NATIVE="$ROOT/packages/titan_atlas/native/x11"
SEED="${ATLAS_ROOTFS_TAR:-}"
if [ -z "$SEED" ]; then
  for c in \
    "$ROOT/../titanus2/out/atlas_rootfs/debian-trixie-arm64-rootfs.tar.gz" \
    "$ROOT/out/atlas_rootfs/debian-trixie-arm64-rootfs.tar.gz"
  do
    if [ -s "$c" ]; then SEED="$c"; break; fi
  done
fi
[ -s "$SEED" ] || { echo "layer_desk_basics: no debian seed tar" >&2; exit 1; }
command -v docker >/dev/null || { echo "layer_desk_basics: docker required" >&2; exit 1; }

WORK="$(mktemp -d /tmp/atlas-desk-layer.XXXXXX)"
DEBS="$WORK/debs"
mkdir -p "$DEBS"
trap 'docker run --rm -v /tmp:/tmp debian:trixie-slim rm -rf "'"$WORK"'" >/dev/null 2>&1 || true' EXIT

echo "layer_desk_basics: unpack $SEED" >&2
docker run --rm -v "$SEED":/in.tar.gz:ro -v "$WORK/rootfs":/out debian:trixie-slim \
  bash -c 'mkdir -p /out && tar -C /out -xzf /in.tar.gz'

echo "layer_desk_basics: download arm64 audio debs" >&2
docker run --rm -v "$DEBS":/out debian:trixie-slim \
  bash -c 'set -e
    export DEBIAN_FRONTEND=noninteractive
    rm -f /etc/apt/sources.list.d/*.sources
    printf "deb [arch=arm64] http://deb.debian.org/debian trixie main\n" > /etc/apt/sources.list
    dpkg --add-architecture arm64
    apt-get update -qq
    cd /out
    apt-get download plasma-pa:arm64 pipewire-pulse:arm64 pulseaudio-utils:arm64 \
      libpulsedsp:arm64 gpgv:arm64
  '

echo "layer_desk_basics: unpack debs into the seed" >&2
docker run --rm -v "$WORK":/work debian:trixie-slim \
  bash -c 'set -e
    apt-get update -qq
    apt-get install -y -qq dpkg-dev
    for deb in /work/debs/*.deb; do
      echo "  $deb"
      dpkg-deb -x "$deb" /work/rootfs
      ctrl=$(dpkg-deb -f "$deb" Package Version Architecture)
      # dpkg-deb -f prints values one per line in the order asked.
      pkg=$(dpkg-deb -f "$deb" Package)
      ver=$(dpkg-deb -f "$deb" Version)
      arch=$(dpkg-deb -f "$deb" Architecture)
      # Drop any previous stanza, then record it installed.
      awk -v p="$pkg" "BEGIN{RS=\"\"; ORS=\"\n\n\"} \$0 !~ \"Package: \" p \"\\n\"" \
        /work/rootfs/var/lib/dpkg/status > /tmp/status.new || true
      {
        echo "Package: $pkg"
        echo "Status: install ok installed"
        echo "Priority: optional"
        echo "Section: sound"
        echo "Installed-Size: $(dpkg-deb -f "$deb" Installed-Size)"
        echo "Maintainer: AtlasOS seed"
        echo "Architecture: $arch"
        echo "Version: $ver"
        echo "Description: baked by layer_desk_basics"
        echo
      } >> /tmp/status.new
      mv /tmp/status.new /work/rootfs/var/lib/dpkg/status
    done
    test -e /work/rootfs/usr/bin/pactl
    test -e /work/rootfs/usr/bin/pipewire-pulse
    test -d /work/rootfs/usr/lib/aarch64-linux-gnu/qt6/plugins/plasma/kcms/systemsettings_qwidgets/kcm_pulseaudio.so \
      || find /work/rootfs -name "kcm_pulseaudio*" | head
  '

# Scripts the desk runs live in the Debian root, not only in the APK.
docker run --rm -v "$WORK/rootfs":/rootfs -v "$NATIVE":/native:ro debian:trixie-slim \
  bash -c 'set -e
    mkdir -p /rootfs/usr/local/bin /rootfs/usr/local/libexec /rootfs/etc/profile.d
    cp -f /native/atlas-desk-session.sh /rootfs/usr/local/bin/atlas-desk-session
    cp -f /native/atlas-desk-stop.sh /rootfs/usr/local/libexec/atlas-desk-stop.sh
    cp -f /native/atlas-audio-pw.sh /rootfs/usr/local/bin/atlas-audio-pw
    cp -f /native/atlas-bwrap.py /rootfs/usr/local/libexec/atlas-bwrap.py
    cp -f /native/atlas-bwrap-install.sh /rootfs/usr/local/libexec/atlas-bwrap-install.sh
    cp -f /native/atlas-bwrap-bin /rootfs/usr/local/libexec/atlas-bwrap-bin
    chmod 755 /rootfs/usr/local/bin/atlas-desk-session \
      /rootfs/usr/local/libexec/atlas-desk-stop.sh \
      /rootfs/usr/local/bin/atlas-audio-pw \
      /rootfs/usr/local/libexec/atlas-bwrap.py \
      /rootfs/usr/local/libexec/atlas-bwrap-install.sh
    chown 0:0 /rootfs/usr/local/libexec/atlas-bwrap-bin
    chmod 4755 /rootfs/usr/local/libexec/atlas-bwrap-bin
    cp -f /rootfs/usr/local/libexec/atlas-bwrap-bin /rootfs/usr/bin/bwrap
    chmod 4755 /rootfs/usr/bin/bwrap
    printf "%s\n" "export TMPDIR=/tmp" > /rootfs/etc/profile.d/atlas-tmpdir.sh
    for svc in \
      /rootfs/usr/share/dbus-1/system-services/org.freedesktop.PackageKit.service \
      /rootfs/usr/share/dbus-1/system-services/org.freedesktop.Accounts.service
    do
      [ -f "$svc" ] && sed -i "/^SystemdService=/d" "$svc" || true
    done
    grep -q "^Package: plasma-pa$" /rootfs/var/lib/dpkg/status
  '

OUT="$(dirname "$SEED")/debian-trixie-arm64-rootfs.tar.gz.new"
echo "layer_desk_basics: pack" >&2
docker run --rm -v "$WORK/rootfs":/rootfs -v "$(dirname "$SEED")":/out debian:trixie-slim \
  bash -c "tar -C /rootfs -czf /out/$(basename "$OUT") . && chown $(id -u):$(id -g) /out/$(basename "$OUT")"
mv -f "$OUT" "$SEED"
sha256sum "$SEED" | tee "$SEED.sha256"
echo "layer_desk_basics: updated $SEED" >&2
