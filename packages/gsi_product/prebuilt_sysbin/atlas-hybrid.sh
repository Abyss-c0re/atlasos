#!/system/bin/sh
# atlas-hybrid — combined OS: Debian userspace + Android kernel + Android apps
#
# Product SoT: docs/project/ATLAS_ROM_SHIP.md · docs/project/ATLAS_HYBRID_STORAGE.md
#
# === Titanus2 product storage model (lock this) ===
#   SoT: docs/project/ATLAS_SUPER_LP.md · ATLAS_HYBRID_STORAGE.md
#
#   Debian root (survives userdata wipe):
#     Super LP atlas_linux_a → atlas-lpctl mount → /data/local/atlas-linux
#     ROM seed rehydrates empty LP / first pack only.
#
#   Linux HOME (wiped WITH Android userdata — intentional):
#     /data/local/atlas-home/atlas   (user atlas)
#
#   Compat (no LP yet):
#     /data/local/atlas-hybrid.img   loop lower+upper+work
#
#   SHARED with Android (bind, not full /data rbind):
#     /sdcard · /storage/emulated/0 → merge
#     NEVER bind_rbind full /data (nests hybrid → bin fail)
#
# Architecture (NOT chroot-first):
#   f2fs /data cannot host overlayfs upper → ext4 loop image
#   lower  = Debian 13 trixie rootfs (from ROM seed)
#   upper  = writable layer (apt installs)
#   work   = overlay workdir (same ext4)
#   merge  = combined root + selective Android binds
#   enter  = enterd / pivot into merge
#
# Usage:
#   atlas-hybrid.sh status|bootstrap|mount|umount|enter|run|destroy|ensure
#   atlas-hybrid.sh mode android|debian|status
#   atlas-hybrid.sh storage shared|isolated|status
#   atlas-hybrid.sh heal
#
# Env:
#   ATLAS_HYBRID_ROOT   default /data/local/atlas-hybrid
#   ATLAS_HYBRID_IMG    default /data/local/atlas-hybrid.img
#   ATLAS_HYBRID_SIZE_G auto (half free /data) or integer GiB
#   ATLAS_ALLOW_NET_SEED=1  only then allow cloud.debian.org fallback (lab)
#
# Base pin: Debian 13 trixie arm64 (Armbian NIO 12L 16G peer)
#
export PATH=/system/bin:/system/xbin:/vendor/bin:/data/adb/ksu/bin:$PATH

ROOT="${ATLAS_HYBRID_ROOT:-/data/local/atlas-hybrid}"
IMG="${ATLAS_HYBRID_IMG:-/data/local/atlas-hybrid.img}"
# SIZE_G resolved at first image create (compute_hybrid_size_g)
SIZE_G="${ATLAS_HYBRID_SIZE_G:-auto}"
LEGACY="${ATLAS_HYBRID_LEGACY:-/data/local/atlas-hybrid-legacy}"
# Super LP debian (preferred when present)
LP_MNT="${ATLAS_LINUX_MNT:-/data/local/atlas-linux}"
LPCTL="${ATLAS_LPCTL:-/system/bin/atlas-lpctl}"
# Linux user home on Android data (wiped with factory reset)
ATLAS_LINUX_HOME="${ATLAS_LINUX_HOME:-/data/local/atlas-home/atlas}"
# LAW (every change): privilege auth on super LP — survives userdata wipe.
# Never app CE files/auth. Never /data/local/tmp.
ATLAS_AUTH_ON_LP="${ATLAS_AUTH_ON_LP:-$LP_MNT/var/lib/atlas-auth}"
ATLAS_AUTH_IN_DEB="${ATLAS_AUTH_IN_DEB:-/var/lib/atlas-auth}"

BASE_DISTRO="${ATLAS_DEBIAN_DISTRO:-debian}"
BASE_CODENAME="${ATLAS_DEBIAN_CODENAME:-trixie}"
BASE_ARCH="${ATLAS_DEBIAN_ARCH:-arm64}"
URL="${ATLAS_ROOTFS_URL:-https://cloud.debian.org/images/cloud/trixie/latest/debian-13-genericcloud-${BASE_ARCH}.tar.xz}"

LOWER="$ROOT/lower"
UPPER="$ROOT/upper"
WORK="$ROOT/work"
MERGE="$ROOT/merge"
MARKER="$ROOT/.atlas-hybrid"
VER=7
T2=/data/misc/titan2
ST=/data/local/tmp
BB="${ATLAS_BUSYBOX:-/data/adb/ksu/bin/busybox}"

log() { echo "atlas-hybrid: $*" >&2; }

# Prefer super LP debian root when atlas-lpctl can mount it.
# Home always on /data (survives only until Android wipe).
# Product: LP = Deb root (rw). No legacy loop when LP works.
# Overlay upper cannot live on f2fs — so we bind Android into LP merge path
# and skip loop entirely (apt writes to super LP).
lp_dev_present() {
  [ -b /dev/block/mapper/atlas_linux_a ] || [ -b /dev/block/mapper/atlas_linux ] \
    || [ -b /dev/block/by-name/atlas_linux_a ]
}

lp_try_mount() {
  if [ -x "$LPCTL" ]; then
    "$LPCTL" mount 2>/dev/null && return 0
  fi
  for c in /system/bin/atlas-lpctl /system_ext/bin/atlas-lpctl \
           /product/bin/atlas-lpctl /data/local/tmp/atlas-lpctl; do
    [ -x "$c" ] || continue
    "$c" mount 2>/dev/null && return 0
  done
  return 1
}

lp_root_ready() {
  [ -x "$LP_MNT/bin/bash" ] || [ -x "$LP_MNT/usr/bin/bash" ] || return 1
  [ -f "$LP_MNT/etc/os-release" ] || [ -f "$LP_MNT/etc/debian_version" ] || return 1
  return 0
}

# Tear down legacy loop hybrid so LP can own the plane (KEEP_DATA residual).
umount_legacy_loop_for_lp() {
  umount_overlay 2>/dev/null || true
  if is_mounted "$ROOT"; then
    umount -l "$ROOT" 2>/dev/null || umount "$ROOT" 2>/dev/null || true
  fi
  detach_img_loops 2>/dev/null || true
  # Drop bind of LP onto lower if half-done
  if is_mounted "$LOWER"; then
    umount -l "$LOWER" 2>/dev/null || true
  fi
  if is_mounted "$MERGE"; then
    umount -l "$MERGE" 2>/dev/null || true
  fi
}

# Bring Debian from super LP. Sets MERGE to LP mount (direct, no loop overlay).
# Returns 0 if plane is enterable.
bring_up_from_lp() {
  need_root || return 1
  lp_dev_present || return 1
  lp_try_mount || return 1
  lp_root_ready || {
    log "LP mounted but empty debian — leave for seed path"
    return 1
  }

  # Leave legacy loop offline so enterd uses LP merge
  umount_legacy_loop_for_lp

  # Product plane: merge == LP root (rw). enterd default MERGE path is
  # /data/local/atlas-hybrid/merge — bind LP there so enterd finds bash.
  mkdir -p "$ROOT" "$MERGE" 2>/dev/null || true
  if ! is_mounted "$MERGE"; then
    mount --bind "$LP_MNT" "$MERGE" 2>/dev/null \
      || mount -o bind "$LP_MNT" "$MERGE" 2>/dev/null || {
        log "bind LP → $MERGE failed"
        return 1
      }
  fi
  # Also keep LOWER as bind for tools that look at lower/
  mkdir -p "$LOWER" 2>/dev/null || true
  if ! is_mounted "$LOWER"; then
    mount --bind "$LP_MNT" "$LOWER" 2>/dev/null || true
  fi

  # Marker so is_bootstrapped / status look healthy
  printf 'atlas-hybrid %s base=lp-atlas_linux home_on_data=1\n' "$VER" >"$MARKER" 2>/dev/null || true

  bind_android 2>/dev/null || true
  heal_merge_essentials 2>/dev/null || true
  ensure_auth_plane_on_lp 2>/dev/null || true

  mkdir -p "$ATLAS_LINUX_HOME" "$ATLAS_LINUX_HOME/reports" 2>/dev/null || true
  if [ -x "$LPCTL" ]; then
    "$LPCTL" home-ensure 2>/dev/null || true
    "$LPCTL" auth-ensure 2>/dev/null || true
  fi
  export HOME="$ATLAS_LINUX_HOME"
  export ATLAS_HOME="$ATLAS_LINUX_HOME"
  export ATLAS_AUTH_DIR="$ATLAS_AUTH_ON_LP"
  plane_write titan2_atlas_mode debian 2>/dev/null || true
  log "super LP debian up merge=$MERGE from $LP_MNT HOME=$ATLAS_LINUX_HOME AUTH=$ATLAS_AUTH_DIR"
  return 0
}

# LAW: privilege auth on atlas_linux LP (survives wipe). Bind into merge for Deb.
ensure_auth_plane_on_lp() {
  if [ -x "$LPCTL" ]; then
    "$LPCTL" mount 2>/dev/null || true
    "$LPCTL" auth-ensure 2>/dev/null || true
  fi
  mkdir -p "$ATLAS_AUTH_ON_LP" 2>/dev/null || true
  chmod 0777 "$ATLAS_AUTH_ON_LP" 2>/dev/null || true
  # Deb chroot sees AUTH_IN_DEB when merge is LP bind (same inode as AUTH_ON_LP).
  # Loop overlay: bind LP auth into merge so Deb can write without CE.
  if [ -d "$MERGE" ] && [ -d "$ATLAS_AUTH_ON_LP" ]; then
    mkdir -p "$MERGE/var/lib/atlas-auth" 2>/dev/null || true
    if ! is_mounted "$MERGE/var/lib/atlas-auth" 2>/dev/null; then
      # If merge IS the LP, path is already AUTH_IN_DEB on same FS — skip bind.
      if [ "$(stat -c %d:%i "$ATLAS_AUTH_ON_LP" 2>/dev/null)" = \
           "$(stat -c %d:%i "$MERGE/var/lib/atlas-auth" 2>/dev/null)" ]; then
        :
      else
        mount --bind "$ATLAS_AUTH_ON_LP" "$MERGE/var/lib/atlas-auth" 2>/dev/null \
          || mount -o bind "$ATLAS_AUTH_ON_LP" "$MERGE/var/lib/atlas-auth" 2>/dev/null || true
      fi
    fi
    chmod 0777 "$MERGE/var/lib/atlas-auth" 2>/dev/null || true
  fi
  export ATLAS_AUTH_DIR="$ATLAS_AUTH_ON_LP"
  return 0
}

# Android VPN / network DNS for Debian+musl tools (grok, apt, curl).
# Apps use netd (respects Tailscale/WireGuard/Private DNS). glibc/musl only
# read resolv.conf + classic UDP/53 — so we must seed nameservers from the
# *active* Android network, VPN first. Never pin public 8.8.8.8 while a VPN
# is up (bypasses MagicDNS / leaks outside the tunnel).
atlas_android_dns_body() {
  dump=`dumpsys connectivity 2>/dev/null` || dump=
  emit_ips() {
    tr ',' '\n' | sed 's|^[[:space:]]*/*||;s|[[:space:]]||g' | \
      grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$|^[0-9a-fA-F:]+$'
  }
  pick() {
    mode="$1"
    echo "$dump" | sed 's/NetworkAgentInfo{/\nNetworkAgentInfo{/g' | while IFS= read -r block; do
      [ -n "$block" ] || continue
      case "$block" in *DnsAddresses:*) ;; *) continue ;; esac
      if [ "$mode" = vpn ]; then
        case "$block" in
          *IS_VPN*) ;;
          *'ni{VPN '*) ;;
          *'ni{VPN}'*) ;;
          *'ni{VPN:'*) ;;
          *) continue ;;
        esac
      else
        case "$block" in *IS_VPN*|*ni{VPN*) continue ;; esac
        case "$block" in *INTERNET*|*TRANSPORT_PRIMARY*) ;; *) continue ;; esac
      fi
      echo "$block" | sed -n 's/.*DnsAddresses: \[ *\([^]]*\)\].*/\1/p' | emit_ips
    done
  }
  seen=; n=0
  add() {
    ip="$1"; [ -n "$ip" ] || return 0
    case " $seen " in *" $ip "*) return 0 ;; esac
    seen="$seen $ip"; echo "nameserver $ip"; n=$((n + 1))
  }
  for ip in `pick vpn`; do add "$ip"; done
  [ "$n" -gt 0 ] && return 0
  for ip in `pick net`; do add "$ip"; done
  [ "$n" -gt 0 ] && return 0
  for p in net.dns1 net.dns2 net.dns3 net.dns4; do
    v=`getprop "$p" 2>/dev/null`
    [ -n "$v" ] && add "$v"
  done
  [ "$n" -gt 0 ] && return 0
  if ip -o link show 2>/dev/null | grep -q ' tun[0-9][0-9]*:'; then
    return 1
  fi
  echo "nameserver 8.8.8.8"
  echo "nameserver 1.1.1.1"
}

# Write Android-VPN-aware resolv.conf into hybrid tree + app HOME.
# Must run as root (ensure/enter). Never called from app-UID atlas-net for merge.
atlas_apply_android_dns() {
  body=`atlas_android_dns_body 2>/dev/null` || body=
  [ -n "$body" ] || return 0
  for dest in \
    "$MERGE/etc/resolv.conf" \
    "$LOWER/etc/resolv.conf" \
    /data/data/com.titanus2.atlas/files/etc/resolv.conf \
    /data/local/tmp/atlas-dns/resolv.conf
  do
    ddir=`dirname "$dest" 2>/dev/null`
    mkdir -p "$ddir" 2>/dev/null || true
    [ -d "$ddir" ] || continue
    # Subshell so shell "can't create" never hits the PTY
    ( printf '%s\n' "$body" >"$dest" ) >/dev/null 2>&1 || true
    chmod 644 "$dest" 2>/dev/null || true
  done
  AU=`stat -c %u /data/data/com.titanus2.atlas 2>/dev/null || true`
  if [ -n "$AU" ] && [ "$AU" != "0" ]; then
    chown "$AU:$AU" /data/data/com.titanus2.atlas/files/etc/resolv.conf 2>/dev/null || true
  fi
  return 0
}

# --- plane ---
plane_write() {
  k="$1"; v="$2"
  mkdir -p "$T2" "$ST" 2>/dev/null || true
  printf '%s' "$v" >"$ST/$k" 2>/dev/null || true
  printf '%s' "$v" >"$T2/$k" 2>/dev/null || true
  chmod 666 "$ST/$k" "$T2/$k" 2>/dev/null || true
}

plane_read() {
  k="$1"; d="$2"
  v=""
  [ -f "$ST/$k" ] && v=`cat "$ST/$k" 2>/dev/null | tr -d '\r\n'`
  [ -z "$v" ] && [ -f "$T2/$k" ] && v=`cat "$T2/$k" 2>/dev/null | tr -d '\r\n'`
  [ -n "$v" ] && echo "$v" || echo "$d"
}

plane_defaults() {
  [ -f "$ST/titan2_atlas_mode" ] || plane_write titan2_atlas_mode android
  [ -f "$ST/titan2_atlas_storage" ] || plane_write titan2_atlas_storage shared
  [ -f "$ST/titan2_atlas_trust" ] || plane_write titan2_atlas_trust normal
  [ -f "$ST/titan2_atlas_desktop" ] || plane_write titan2_atlas_desktop none
}

need_root() {
  id=$(id -u 2>/dev/null) || id=99999
  [ "$id" = "0" ] && return 0
  log "need root (KernelSU su / priv on). Combined OS is privileged."
  return 1
}

has_bb() {
  [ -x "$BB" ] && return 0
  for c in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox busybox; do
    [ -x "$c" ] || continue
    BB=$c
    return 0
  done
  return 1
}

is_mounted() {
  # $1 = mountpoint
  grep -q " $1 " /proc/mounts 2>/dev/null
}

is_loop_mounted() {
  is_mounted "$ROOT" && [ -d "$LOWER" -o -d "$ROOT/bin" -o -f "$MARKER" ]
}

is_overlay_up() {
  # Overlay *or* LP bind at MERGE (product super path has no overlay fstype).
  is_mounted "$MERGE" && [ -x "$MERGE/bin/bash" -o -x "$MERGE/usr/bin/bash" -o -x "$MERGE/bin/sh" ]
}

is_bootstrapped() {
  # Super LP debian (product)
  if lp_root_ready; then
    return 0
  fi
  # loop image + lower debian present (or legacy flat tree still usable)
  if [ -f "$IMG" ] && [ -d "$LOWER" ] && {
       [ -x "$LOWER/bin/bash" ] || [ -x "$LOWER/usr/bin/bash" ] || [ -x "$LOWER/bin/sh" ]
     }; then
    return 0
  fi
  # pre-v4 flat extract still counts until migrate
  if [ -f "$MARKER" ] && {
       [ -x "$ROOT/bin/bash" ] || [ -x "$ROOT/usr/bin/bash" ] || [ -x "$ROOT/bin/sh" ]
     }; then
    return 0
  fi
  # merge already has debian (LP bind)
  if [ -x "$MERGE/bin/bash" ] || [ -x "$MERGE/usr/bin/bash" ]; then
    return 0
  fi
  return 1
}

# Product: size Deb private volume from free userdata (first create only).
# ~half free /data, leave room for Android; clamp so small phones still work.
compute_hybrid_size_g() {
  req="${ATLAS_HYBRID_SIZE_G:-auto}"
  case "$req" in
    ''|auto|AUTO)
      free_k=`df -k /data 2>/dev/null | tail -1 | awk '{print $4}'`
      case "$free_k" in ''|*[!0-9]*) free_k=0 ;; esac
      free_g=$((free_k / 1024 / 1024))
      if [ "$free_g" -lt 1 ] 2>/dev/null; then
        echo 4
        return 0
      fi
      # half free
      half=$((free_g / 2))
      # leave Android at least ~3G when possible
      max_take=$((free_g - 3))
      [ "$max_take" -lt 2 ] 2>/dev/null && max_take=2
      [ "$half" -gt "$max_take" ] 2>/dev/null && half=$max_take
      [ "$half" -lt 4 ] 2>/dev/null && half=4
      [ "$half" -gt 24 ] 2>/dev/null && half=24
      # tiny free space: never take more than free-1
      if [ "$free_g" -lt 6 ] 2>/dev/null; then
        half=$((free_g > 2 ? free_g - 1 : free_g))
        [ "$half" -lt 2 ] 2>/dev/null && half=2
      fi
      echo "$half"
      ;;
    *[!0-9]*)
      echo 8
      ;;
    *)
      echo "$req"
      ;;
  esac
}

# --- loop ext4 (overlay needs non-f2fs upper) ---
# Detach every loop bound to $IMG (Android can leave several after failed mounts).
detach_img_loops() {
  command -v losetup >/dev/null 2>&1 || return 0
  # losetup -j prints one line per device: /dev/block/loopN: [...] (/path)
  losetup -j "$IMG" 2>/dev/null | while IFS= read -r line; do
    lo=`echo "$line" | cut -d: -f1`
    [ -n "$lo" ] && losetup -d "$lo" 2>/dev/null || true
  done
  # also scan -a (some toybox builds omit -j path)
  losetup -a 2>/dev/null | grep -F "$IMG" | while IFS= read -r line; do
    lo=`echo "$line" | cut -d: -f1`
    [ -n "$lo" ] && losetup -d "$lo" 2>/dev/null || true
  done
}

# Wait until loop block device has non-zero capacity (avoids I/O error race).
loop_ready() {
  lo="$1"
  n=0
  while [ "$n" -lt 40 ]; do
    # sysfs size is 512-byte sectors; non-zero means attach finished
    base=`basename "$lo"`
    sz=`cat "/sys/class/block/$base/size" 2>/dev/null` || sz=0
    [ -z "$sz" ] && sz=0
    if [ "$sz" -gt 0 ] 2>/dev/null; then
      # prefer blkid when available
      if command -v blkid >/dev/null 2>&1; then
        blkid "$lo" >/dev/null 2>&1 && return 0
        # capacity present but blkid not yet — still try after a beat
        [ "$n" -ge 5 ] && return 0
      else
        return 0
      fi
    fi
    n=$((n + 1))
    # ~50ms without requiring usleep
    sleep 0.05 2>/dev/null || sleep 1
  done
  return 1
}

attach_img_loop() {
  # returns loop path on stdout, 0 on success
  if ! command -v losetup >/dev/null 2>&1; then
    return 1
  fi
  # reuse a single existing association if already ready
  lo=`losetup -j "$IMG" 2>/dev/null | head -1 | cut -d: -f1`
  if [ -n "$lo" ]; then
    if loop_ready "$lo"; then
      # drop extras from previous failed mounts
      extras=`losetup -j "$IMG" 2>/dev/null | tail -n +2 | cut -d: -f1`
      for e in $extras; do
        [ -n "$e" ] && [ "$e" != "$lo" ] && losetup -d "$e" 2>/dev/null || true
      done
      echo "$lo"
      return 0
    fi
    # stale / capacity-0 — drop all and reattach clean
    detach_img_loops
  fi
  # Prefer single losetup -f --show (util-linux). Fallback: free node + attach.
  lo=`losetup -f --show "$IMG" 2>/dev/null` || lo=""
  if [ -z "$lo" ]; then
    free=`losetup -f 2>/dev/null`
    [ -n "$free" ] || return 1
    losetup "$free" "$IMG" 2>/dev/null || return 1
    lo=$free
  fi
  [ -n "$lo" ] || return 1
  loop_ready "$lo" || {
    log "loop $lo never reported capacity for $IMG"
    return 1
  }
  # keep exactly one binding
  extras=`losetup -j "$IMG" 2>/dev/null | tail -n +2 | cut -d: -f1`
  for e in $extras; do
    [ -n "$e" ] && [ "$e" != "$lo" ] && losetup -d "$e" 2>/dev/null || true
  done
  echo "$lo"
  return 0
}

ensure_img() {
  # Never re-format a live or existing image. wipe only via: hybrid destroy
  # Hybrid ROM: existing $IMG is sacred — even sparse/stat-weird/empty-looking.
  # Dual concurrent ensure used to mke2fs twice and wipe Deb (KEEP_DATA thrash).
  if is_mounted "$ROOT"; then
    return 0
  fi
  # Loop still attached to IMG → treat as existing (mount_loop will recover)
  if command -v losetup >/dev/null 2>&1; then
    if losetup -a 2>/dev/null | grep -qF "$IMG"; then
      return 0
    fi
  fi
  # ANY existing path → never mke2fs unless explicit force after destroy.
  if [ -e "$IMG" ] || [ -L "$IMG" ]; then
    if [ "${ATLAS_FORCE_IMG_CREATE:-0}" = "1" ]; then
      log "ATLAS_FORCE_IMG_CREATE=1 — reformatting existing $IMG"
      rm -f "$IMG" 2>/dev/null || true
    else
      sz=`stat -c %s "$IMG" 2>/dev/null` || sz=0
      log "ensure_img: keep existing $IMG (sz=$sz) — never mke2fs"
      return 0
    fi
  fi
  SIZE_G=`compute_hybrid_size_g`
  free_k=`df -k /data 2>/dev/null | tail -1 | awk '{print $4}'`
  log "creating sparse ext4 image ${SIZE_G}G → $IMG (first time; free_data_k=${free_k:-?} half-user policy)"
  mkdir -p "`dirname "$IMG"`"
  # flock so concurrent boot+ensure cannot double mke2fs
  lock=/data/local/tmp/atlas-hybrid-img.lock
  mkdir -p /data/local/tmp 2>/dev/null || true
  exec 9>"$lock" 2>/dev/null || true
  if command -v flock >/dev/null 2>&1; then
    flock 9 2>/dev/null || true
  fi
  # Re-check under lock (peer may have created)
  if [ -e "$IMG" ] && [ "${ATLAS_FORCE_IMG_CREATE:-0}" != "1" ]; then
    log "ensure_img: peer created $IMG — skip mke2fs"
    return 0
  fi
  dd if=/dev/zero of="$IMG" bs=1M count=0 seek=$((SIZE_G * 1024)) 2>/dev/null || \
    truncate -s ${SIZE_G}G "$IMG" || {
      log "failed to allocate $IMG"
      return 1
    }
  mke2fs -t ext4 -F -L atlas-hybrid "$IMG" || {
    log "mke2fs failed"
    return 1
  }
  # f2fs + sparse: flush metadata before losetup or first mount can I/O-error
  sync
  return 0
}

# Try mount a loop device onto $ROOT (several flag combos).
try_mount_lo() {
  lo="$1"
  [ -n "$lo" ] || return 1
  mount -t ext4 -o rw,noatime "$lo" "$ROOT" 2>/dev/null \
    || mount -t ext4 "$lo" "$ROOT" 2>/dev/null \
    || mount "$lo" "$ROOT" 2>/dev/null
}

mount_loop() {
  need_root || return 1
  ensure_img || return 1
  mkdir -p "$ROOT"
  if is_mounted "$ROOT"; then
    # Still prune orphan multi-loops while mounted (I/O thrash residual)
    if command -v losetup >/dev/null 2>&1; then
      cur=`findmnt -n -o SOURCE "$ROOT" 2>/dev/null` \
        || cur=`mount | awk -v r="$ROOT" '$3==r {print $1; exit}'`
      if [ -n "$cur" ]; then
        losetup -j "$IMG" 2>/dev/null | while IFS= read -r line; do
          e=`echo "$line" | cut -d: -f1`
          [ -n "$e" ] && [ "$e" != "$cur" ] && losetup -d "$e" 2>/dev/null || true
        done
      fi
    fi
    return 0
  fi

  # Path A: explicit losetup + wait for capacity (fixes I/O error on fresh sparse img)
  # Stability (2026-08-09): dual-loop attach left mount I/O-error and hybrid "refuse to start".
  # Always: one attach → mount with retries → on fail detach ALL → one clean retry.
  if command -v losetup >/dev/null 2>&1; then
    round=0
    while [ "$round" -lt 2 ]; do
      lo=`attach_img_loop 2>/dev/null` || lo=""
      if [ -n "$lo" ]; then
        # Speed: skip full e2fsck on warm path. Only fsck when forced or prior mount failed.
        # Boot service + normal ensure were paying multi-second journal scan every attach.
        if [ "${ATLAS_FORCE_FSCK:-0}" = "1" ] || [ -f /data/local/tmp/atlas-hybrid-need-fsck ]; then
          if command -v e2fsck >/dev/null 2>&1; then
            log "e2fsck (forced or dirty flag)"
            e2fsck -fy "$lo" >/dev/null 2>&1 || true
          fi
          rm -f /data/local/tmp/atlas-hybrid-need-fsck 2>/dev/null || true
        fi
        tries=0
        while [ "$tries" -lt 6 ]; do
          if try_mount_lo "$lo"; then
            # prune any second binding created during race
            extras=`losetup -j "$IMG" 2>/dev/null | tail -n +2 | cut -d: -f1`
            for e in $extras; do
              [ -n "$e" ] && [ "$e" != "$lo" ] && losetup -d "$e" 2>/dev/null || true
            done
            return 0
          fi
          tries=$((tries + 1))
          sleep 0.1 2>/dev/null || sleep 1
          loop_ready "$lo" || true
        done
        # Mark dirty so next attach pays e2fsck once
        touch /data/local/tmp/atlas-hybrid-need-fsck 2>/dev/null || true
        log "mount loop $lo → $ROOT failed (round $round) — detach + clean retry"
      else
        log "losetup attach failed for $IMG (round $round)"
      fi
      detach_img_loops
      sync
      sleep 0.2 2>/dev/null || sleep 1
      round=$((round + 1))
    done
    log "losetup path exhausted — trying mount -o loop"
  fi

  # Path B: kernel auto-loop (can also leave dual binds — prune after)
  if mount -o loop,rw,noatime -t ext4 "$IMG" "$ROOT" 2>/dev/null \
    || mount -o loop -t ext4 "$IMG" "$ROOT" 2>/dev/null \
    || mount -o loop "$IMG" "$ROOT" 2>/dev/null; then
    # keep single association
    if command -v losetup >/dev/null 2>&1; then
      cur=`findmnt -n -o SOURCE "$ROOT" 2>/dev/null` \
        || cur=`mount | awk -v r="$ROOT" '$3==r {print $1; exit}'`
      losetup -j "$IMG" 2>/dev/null | while IFS= read -r line; do
        e=`echo "$line" | cut -d: -f1`
        [ -n "$e" ] && [ -n "$cur" ] && [ "$e" != "$cur" ] && losetup -d "$e" 2>/dev/null || true
      done
    fi
    return 0
  fi
  log "mount -o loop failed for $IMG → $ROOT"
  detach_img_loops
  return 1
}

umount_loop() {
  need_root || return 1
  umount_overlay
  if is_mounted "$ROOT"; then
    umount "$ROOT" 2>/dev/null || umount -l "$ROOT" 2>/dev/null || true
  fi
  detach_img_loops
}

# --- overlay ---
prepare_dirs() {
  mkdir -p "$LOWER" "$UPPER" "$WORK" "$MERGE" \
    "$LOWER/tmp" "$LOWER/var/tmp" "$LOWER/dev" "$LOWER/proc" "$LOWER/sys" \
    "$LOWER/system" "$LOWER/vendor" "$LOWER/product" "$LOWER/system_ext" \
    "$LOWER/apex" "$LOWER/data" "$LOWER/sdcard" "$LOWER/storage" "$LOWER/mnt" \
    "$LOWER/data/local/tmp" 2>/dev/null || true
  # sticky tmp for apt (Permission denied without 1777)
  chmod 1777 "$LOWER/tmp" "$LOWER/var/tmp" 2>/dev/null || true
  mkdir -p "$UPPER/tmp" "$UPPER/var/tmp" 2>/dev/null || true
  chmod 1777 "$UPPER/tmp" 2>/dev/null || true
}

mount_overlay() {
  need_root || return 1
  # Super LP mode: merge is already bind of atlas_linux — no loop overlay.
  if [ "${ATLAS_LP_MODE:-0}" = "1" ] && is_mounted "$MERGE" && {
       [ -x "$MERGE/bin/bash" ] || [ -x "$MERGE/usr/bin/bash" ]
     }; then
    return 0
  fi
  mount_loop || return 1
  prepare_dirs
  if is_mounted "$MERGE"; then
    return 0
  fi
  # lower must have a userspace
  if [ ! -x "$LOWER/bin/sh" ] && [ ! -x "$LOWER/usr/bin/bash" ] && [ ! -x "$LOWER/bin/bash" ]; then
    if [ -x "$ROOT/bin/sh" ] || [ -x "$ROOT/bin/bash" ]; then
      log "flat tree at $ROOT — migrate into lower first (heal/bootstrap)"
      return 1
    fi
    log "empty lower — run bootstrap"
    return 1
  fi
  # clean work (must be empty-ish; kernel wants work on same fs as upper)
  # do not wipe upper
  mount -t overlay overlay \
    -o "lowerdir=$LOWER,upperdir=$UPPER,workdir=$WORK" \
    "$MERGE" || {
      log "overlay mount failed (need ext4 loop; f2fs upper unsupported)"
      return 1
    }
  # ensure merge tmp sticky after upper shadows
  mkdir -p "$MERGE/tmp" "$MERGE/var/tmp" 2>/dev/null || true
  chmod 1777 "$MERGE/tmp" "$MERGE/var/tmp" 2>/dev/null || true
  return 0
}

umount_overlay() {
  unbind_android
  if is_mounted "$MERGE"; then
    umount "$MERGE" 2>/dev/null || umount -l "$MERGE" 2>/dev/null || true
  fi
}

bind_one() {
  src="$1"
  dst="$2"
  [ -e "$src" ] || return 0
  mkdir -p "$dst" 2>/dev/null || true
  if is_mounted "$dst"; then
    return 0
  fi
  mount --bind "$src" "$dst" 2>/dev/null || mount -o bind "$src" "$dst" 2>/dev/null || true
}

# Recursive bind — required for /apex (nested mounts). Plain bind leaves empty tmpfs
# and Android linker64 → /apex/com.android.runtime/bin/linker64 fails inside hybrid.
bind_rbind() {
  src="$1"
  dst="$2"
  [ -e "$src" ] || return 0
  mkdir -p "$dst" 2>/dev/null || true
  if is_mounted "$dst"; then
    # If already mounted but looks empty/wrong, remount rbind
    if [ "$src" = "/apex" ] && [ ! -e "$dst/com.android.runtime/bin/linker64" ]; then
      umount -l "$dst" 2>/dev/null || true
    else
      return 0
    fi
  fi
  # Drop leftover empty tmpfs/dir mounts
  if grep -q " $dst " /proc/mounts 2>/dev/null; then
    umount -l "$dst" 2>/dev/null || true
  fi
  mount --rbind "$src" "$dst" 2>/dev/null || mount -o rbind "$src" "$dst" 2>/dev/null || {
    log "rbind failed $src → $dst (android bins may need android-exec)"
    bind_one "$src" "$dst"
    return 0
  }
  # Do not propagate unmounts into the host tree
  mount --make-rslave "$dst" 2>/dev/null || true
}


# Helper used by /atlas-bin/am etc. — leave Debian root, run on Android mount ns.
write_android_exec_helpers() {
  d="$MERGE/usr/local/libexec"
  mkdir -p "$d" "$MERGE/usr/local/bin" 2>/dev/null || true
  cat >"$d/atlas-android-exec" <<'EOF'
#!/bin/sh
# Run an Android (Bionic) binary from hybrid Debian root.
# Deb → Android plane. Bio auth is OPTIONAL (off by default).
#   ATLAS_ANDROID_AUTH=1  → require atlas-auth before Android bin
#   ATLAS_ANDROID_AUTH=0  → pass (default)
# Deb-internal tools (apt, bash, …) never go through this path.
set -f
bin="$1"
shift
[ -n "$bin" ] || { echo "atlas-android-exec: missing binary" >&2; exit 2; }

# Optional gate: only when Deb accesses Android (screencap, am, pm, …).
_android_auth_want() {
  case "${ATLAS_ANDROID_AUTH:-0}" in
    1|true|on|yes|ON) return 0 ;;
  esac
  # plane files (app Settings can write later)
  for f in /data/local/tmp/titan2_atlas_android_auth \
           /data/misc/titan2/titan2_atlas_android_auth \
           /var/lib/atlas-auth/android_auth_on; do
    [ -f "$f" ] || continue
    v=`cat "$f" 2>/dev/null | tr -d '\r\n \t' | head -c 8`
    case "$v" in 1|true|on|yes) return 0 ;; esac
  done
  return 1
}
if _android_auth_want; then
  AUTH=""
  for a in /system/bin/atlas-auth \
    "${ATLAS_SYSBIN:-}/atlas-auth" \
    /usr/local/bin/atlas-auth \
    /bin/atlas-auth; do
    [ -x "$a" ] && AUTH=$a && break
  done
  if [ -n "$AUTH" ]; then
    base=`basename "$bin"`
    "$AUTH" request "android $base" || {
      echo "atlas-android-exec: Android access denied (bio/auth)" >&2
      exit 1
    }
  fi
fi

# Static/host nsenter candidates (work even when Bionic is broken in-chroot)
NSENTER=""
for c in \
  /data/adb/ksu/bin/busybox \
  /data/adb/magisk/busybox \
  /system/bin/nsenter \
  /usr/bin/nsenter \
  nsenter
do
  if [ -x "$c" ]; then
    case "$c" in
      *busybox)
        if "$c" nsenter -t 1 -m -- /system/bin/true >/dev/null 2>&1; then
          NSENTER="$c nsenter"
          break
        fi
        ;;
      *)
        if "$c" -t 1 -m -- /system/bin/true >/dev/null 2>&1; then
          NSENTER="$c"
          break
        fi
        ;;
    esac
  fi
done

if [ -n "$NSENTER" ]; then
  # Host Android env — no Debian LD_LIBRARY_PATH
  exec $NSENTER -t 1 -m -- env -i \
    PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin:/apex/com.android.runtime/bin \
    ANDROID_DATA=/data \
    ANDROID_ROOT=/system \
    ANDROID_STORAGE=/storage \
    EXTERNAL_STORAGE=/sdcard \
    TMPDIR=/data/local/tmp \
    "$bin" "$@"
fi

# Fallback: hope apex rbind made linker64 resolvable
if [ -x /apex/com.android.runtime/bin/linker64 ] || [ -x /system/bin/linker64 ]; then
  exec env \
    PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin \
    ANDROID_DATA=/data ANDROID_ROOT=/system \
    LD_LIBRARY_PATH= \
    "$bin" "$@"
fi

echo "atlas-android-exec: cannot run $bin (no nsenter; apex linker missing)" >&2
echo "hint: hybrid mount should rbind /apex — try: hybrid mount && ATLAS_RELINK=1 hybrid mount" >&2
exit 127
EOF
  chmod 755 "$d/atlas-android-exec" 2>/dev/null || true

  # Friendly aliases
  cat >"$MERGE/usr/local/bin/android-exec" <<'EOF'
#!/bin/sh
# Deb → Android binary (optional bio: ATLAS_ANDROID_AUTH=1)
exec /usr/local/libexec/atlas-android-exec "$@"
EOF
  cat >"$MERGE/usr/local/bin/android" <<'EOF'
#!/bin/sh
# android am|pm|screencap|… — Deb → Android plane (optional bio)
if [ -z "$1" ]; then
  echo "usage: android <tool|path> [args…]" >&2
  echo "  android am start -n …" >&2
  echo "  android screencap -p /sdcard/x.png" >&2
  echo "  optional bio: ATLAS_ANDROID_AUTH=1" >&2
  exit 2
fi
cmd="$1"
shift
case "$cmd" in
  /*) exec /usr/local/libexec/atlas-android-exec "$cmd" "$@" ;;
  *)
    for d in /system/bin /system/xbin /product/bin /vendor/bin; do
      if [ -x "$d/$cmd" ]; then
        exec /usr/local/libexec/atlas-android-exec "$d/$cmd" "$@"
      fi
    done
    echo "android: $cmd not found under /system/bin" >&2
    exit 127
    ;;
esac
EOF
  chmod 755 "$MERGE/usr/local/bin/android-exec" "$MERGE/usr/local/bin/android" 2>/dev/null || true
  # Alias names agents expect from issue reports
  ln -sf android "$MERGE/usr/local/bin/android-run" 2>/dev/null || true
  # Screencap + status helpers (copy from system/app if present)
  for h in atlas-screencap atlas-agent-status; do
    for src in \
      /data/user/0/com.titanus2.atlas/files/bin/$h \
      /data/user/0/com.titanus2.atlas/files/bin/${h}.sh \
      /system/bin/$h
    do
      if [ -f "$src" ]; then
        cp -f "$src" "$MERGE/usr/local/bin/$h" 2>/dev/null || true
        chmod 755 "$MERGE/usr/local/bin/$h" 2>/dev/null || true
        break
      fi
    done
  done
  # Short MOTD for hybrid enter
  mkdir -p "$MERGE/etc/profile.d" 2>/dev/null || true
  cat >"$MERGE/etc/profile.d/zz-atlas-plane.sh" <<'EOF'
# Atlas hybrid plane banner (agents + humans)
export ATLAS_PLANE=hybrid ATLAS_MODE=debian ATLAS_HYBRID=1 ATLAS_COMBINED=1
# Stable admin home (never /) so grok sessions stay under one workspace key
case "${HOME:-}" in
  ""|"/"|"/root"|"/data") export HOME=/data/data/com.titanus2.atlas/files ;;
esac
export ATLAS_HOME="${ATLAS_HOME:-$HOME}"
export ATLAS_SYSBIN="${ATLAS_SYSBIN:-/system/bin}"
export ATLAS_BIN="${ATLAS_BIN:-$ATLAS_SYSBIN}"
# Prefer ROM auth/sudo ELFs (executeable from hybrid when /system is bound).
export PATH="/usr/local/bin:/usr/bin:/bin:${ATLAS_SYSBIN}:/system/xbin:/vendor/bin:${PATH:-}"
export GROK_HOME="${GROK_HOME:-$HOME/.grok}"
export ATLAS_REPORTS="${HOME}/reports"
if [ -n "${PS1:-}" ] && [ -z "${ATLAS_MOTD_SHOWN:-}" ]; then
  export ATLAS_MOTD_SHOWN=1
  echo "Atlas PLANE=hybrid MODE=debian — Android IPC via: android <cmd> | atlas-screencap"
  echo "Status: atlas-agent-status · Reports: $ATLAS_REPORTS · Grok: $GROK_HOME"
fi
# Prefer debian:admin prompt if not already set by ~/.bashrc
case "${PS1:-}" in
  *debian*|*android*) ;;
  *) PS1='\[\e[1;36m\]debian\[\e[0m\]:admin\$ ' ;;
esac
EOF
  chmod 644 "$MERGE/etc/profile.d/zz-atlas-plane.sh" 2>/dev/null || true
}


# Live Android into merge so debian mode sees apps + binaries.
bind_android() {
  storage=`plane_read titan2_atlas_storage shared`
  [ -d "$MERGE" ] || return 1
  is_mounted "$MERGE" || mount_overlay || return 1

  bind_one /dev "$MERGE/dev"
  # pts: NEVER inherit host ptmxmode=000 (dead Deb PTY / "broken debian").
  # Fresh devpts with ptmxmode=666 — product law for hybrid enter.
  mkdir -p "$MERGE/dev/pts" 2>/dev/null || true
  if is_mounted "$MERGE/dev/pts"; then
    # heal sticky 000 from earlier host bind
    mount -o remount,ptmxmode=666,mode=620,gid=5 "$MERGE/dev/pts" 2>/dev/null \
      || umount -l "$MERGE/dev/pts" 2>/dev/null || true
  fi
  if ! is_mounted "$MERGE/dev/pts"; then
    mount -t devpts -o rw,nosuid,noexec,relatime,gid=5,mode=620,ptmxmode=666 \
      devpts "$MERGE/dev/pts" 2>/dev/null \
      || mount -t devpts -o ptmxmode=666 devpts "$MERGE/dev/pts" 2>/dev/null \
      || bind_one /dev/pts "$MERGE/dev/pts"
  fi
  chmod 666 "$MERGE/dev/ptmx" "$MERGE/dev/pts/ptmx" 2>/dev/null || true
  bind_one /proc "$MERGE/proc"
  bind_one /sys "$MERGE/sys"
  # Android OS + apps (same kernel)
  bind_one /system "$MERGE/system"
  bind_one /vendor "$MERGE/vendor"
  bind_one /product "$MERGE/product"
  bind_one /system_ext "$MERGE/system_ext"
  # CRITICAL: apex must be rbind — linker64 lives under /apex/com.android.runtime
  bind_rbind /apex "$MERGE/apex"
  if [ -d /linkerconfig ]; then
    bind_rbind /linkerconfig "$MERGE/linkerconfig"
  fi
  # Some GSIs keep bootstrap apex separately
  if [ -d /bootstrap-apex ]; then
    bind_rbind /bootstrap-apex "$MERGE/bootstrap-apex"
  fi

  if [ "$storage" = "shared" ]; then
    # Heal multi-user layout BEFORE bind so chroot sees a usable app HOME.
    _heal_atlas_user_layout 2>/dev/null || true
    # NEVER bind_rbind full /data → nests $ROOT inside merge (merge/data/local/atlas-hybrid/…)
    # and shadows Debian usrmerge (bin→usr/bin gone → "bin fail" / hybrid-down).
    # Bind only app + tmp planes Deb needs.
    mkdir -p "$MERGE/data/data" "$MERGE/data/user/0" "$MERGE/data/local/tmp" \
      "$MERGE/data/misc" 2>/dev/null || true
    if [ -d /data/data/com.titanus2.atlas ]; then
      bind_rbind /data/data/com.titanus2.atlas "$MERGE/data/data/com.titanus2.atlas"
    fi
    if [ -d /data/user/0/com.titanus2.atlas ]; then
      bind_rbind /data/user/0/com.titanus2.atlas "$MERGE/data/user/0/com.titanus2.atlas"
    fi
    if [ -d /data/local/tmp ]; then
      bind_one /data/local/tmp "$MERGE/data/local/tmp"
    fi
    if [ -d /data/misc/titan2 ]; then
      mkdir -p "$MERGE/data/misc/titan2" 2>/dev/null || true
      bind_one /data/misc/titan2 "$MERGE/data/misc/titan2"
    fi
    bind_rbind /storage "$MERGE/storage"
    # Prefer selective /mnt — full rbind also re-enters hybrid under mnt paths.
    mkdir -p "$MERGE/mnt" 2>/dev/null || true
    if [ -d /sdcard ] || [ -L /sdcard ]; then
      if is_mounted "$MERGE/sdcard" && [ ! -e "$MERGE/sdcard/Download" ] && [ ! -d "$MERGE/sdcard" ]; then
        umount -l "$MERGE/sdcard" 2>/dev/null || true
      fi
      bind_one /sdcard "$MERGE/sdcard"
    fi
  else
    log "storage=isolated — skipping /data /sdcard binds"
    mkdir -p "$MERGE/data/local/tmp" "$MERGE/sdcard" 2>/dev/null || true
  fi
  # Fail closed: if bind stack hid Debian bash, remount overlay clean then re-bind light.
  if [ ! -x "$MERGE/bin/bash" ] && [ ! -x "$MERGE/usr/bin/bash" ]; then
    log "bind_android: debian bash missing after binds — heal remount"
    # Drop android binds on merge, remount pure overlay, re-bind without full /data.
    awk '$2 ~ /atlas-hybrid\/merge\// {print $2}' /proc/mounts 2>/dev/null \
      | sort -r | while read _m; do umount -l "$_m" 2>/dev/null || true; done
    umount -l "$MERGE" 2>/dev/null || true
    mount_overlay || return 1
    # re-enter without shared full-data (avoid nest loop)
    storage=isolated
    bind_one /dev "$MERGE/dev"
    mkdir -p "$MERGE/dev/pts" 2>/dev/null || true
    if ! is_mounted "$MERGE/dev/pts"; then
      mount -t devpts -o rw,nosuid,noexec,relatime,gid=5,mode=620,ptmxmode=666 \
        devpts "$MERGE/dev/pts" 2>/dev/null || true
    fi
    chmod 666 "$MERGE/dev/ptmx" "$MERGE/dev/pts/ptmx" 2>/dev/null || true
    bind_one /proc "$MERGE/proc"
    bind_one /sys "$MERGE/sys"
    bind_one /system "$MERGE/system"
    bind_one /vendor "$MERGE/vendor"
    bind_one /product "$MERGE/product"
    bind_one /system_ext "$MERGE/system_ext"
    bind_rbind /apex "$MERGE/apex" 2>/dev/null || true
    mkdir -p "$MERGE/data/data/com.titanus2.atlas" "$MERGE/data/local/tmp" 2>/dev/null || true
    [ -d /data/data/com.titanus2.atlas ] && \
      bind_rbind /data/data/com.titanus2.atlas "$MERGE/data/data/com.titanus2.atlas"
    [ -d /data/local/tmp ] && bind_one /data/local/tmp "$MERGE/data/local/tmp"
  fi
  if [ ! -x "$MERGE/bin/bash" ] && [ ! -x "$MERGE/usr/bin/bash" ]; then
    log "bind_android: FATAL still no debian bash on merge"
    return 1
  fi

  # DNS for apt/curl/musl inside combined root — Android VPN first
  mkdir -p "$MERGE/etc" "$LOWER/etc" 2>/dev/null || true
  atlas_apply_android_dns

  write_profile
  ensure_admin_user
  write_android_exec_helpers
  # Privilege law: PATH su/sudo = agent only (never raw setuid / KernelSU)
  ensure_agent_elevate_gates
  # Debian + Android peers — bidirectional, re-synced when ATLAS_RELINK=1
  link_combined_bins
  ensure_agent_elevate_gates
  write_android_exec_helpers
  ensure_admin_user
}

# Names that must NEVER appear as raw elevate on PATH — agent clients only.
is_elevate_name() {
  case "$1" in
    su|sudo|sudo.real|doas|pkexec|su.real) return 0 ;;
    *) return 1 ;;
  esac
}

# Install Authentication Agent clients as the only PATH-visible su/sudo.
# Real Debian setuid lives at /usr/bin/sudo.real (agent calls it after biometrics).
ensure_agent_elevate_gates() {
  [ -d "$MERGE" ] || return 0
  # Preserve real setuid sudo once
  for root in "$MERGE" "$LOWER"; do
    [ -d "$root/usr/bin" ] || continue
    if [ -f "$root/usr/bin/sudo" ] && [ ! -e "$root/usr/bin/sudo.real" ]; then
      # Only rename if it looks like real sudo (setuid or large ELF), not a tiny wrapper
      sz=`stat -c %s "$root/usr/bin/sudo" 2>/dev/null` || sz=0
      mode=`stat -c %a "$root/usr/bin/sudo" 2>/dev/null` || mode=0
      case "$mode" in
        *4???|*2???|4755|4751|4111|4000) 
          mv -f "$root/usr/bin/sudo" "$root/usr/bin/sudo.real" 2>/dev/null || true
          ;;
        *)
          if [ "$sz" -gt 50000 ]; then
            mv -f "$root/usr/bin/sudo" "$root/usr/bin/sudo.real" 2>/dev/null || true
          fi
          ;;
      esac
    fi
    if [ -f "$root/usr/bin/sudo.real" ]; then
      chmod 4755 "$root/usr/bin/sudo.real" 2>/dev/null || true
      chown 0:0 "$root/usr/bin/sudo.real" 2>/dev/null || true
    fi
  done

  # Agent client binary (Bionic). Prefer app extract; fall back to ROM system ELFs
  # (hybrid inject puts atlas-sudo on /system/bin — works before CE unlock / first extract).
  gate=""
  for c in \
    "${ATLAS_BIN:-}/atlas-sudo" \
    "${ATLAS_BIN:-}/sudo" \
    /data/user/0/com.titanus2.atlas/files/bin/atlas-sudo \
    /data/user/0/com.titanus2.atlas/files/bin/sudo \
    /data/data/com.titanus2.atlas/files/bin/atlas-sudo \
    /system/bin/atlas-sudo \
    /system/xbin/atlas-sudo
  do
    [ -n "$c" ] && [ -x "$c" ] && [ -f "$c" ] && gate=$c && break
  done
  if [ -z "$gate" ]; then
    log "agent elevate gate missing — install Atlas bin first"
    return 0
  fi

  # Agent clients on PATH-early dirs — NEVER replace /usr/bin/sudo with a
  # shim that re-execs atlas-sudo (that loop spammed auth forever).
  for dest in \
    "$MERGE/usr/local/bin/sudo" "$MERGE/usr/local/bin/su" \
    "$MERGE/atlas-bin/sudo" "$MERGE/atlas-bin/su"
  do
    dir=`dirname "$dest"`
    mkdir -p "$dir" 2>/dev/null || true
    cat >"$dest" <<EOF
#!/bin/sh
# Atlas elevate gate → agent client (biometrics) → real setuid elevate
exec '$gate' "\$@"
EOF
    chmod 755 "$dest" 2>/dev/null || true
  done
  # apt wrappers: product /system/bin/apt-hybrid.sh (finds /system/bin/atlas-auth)
  APHYB=""
  for c in \
    /system/bin/apt-hybrid.sh \
    /system/bin/apt \
    "${ATLAS_SYSBIN:-}/apt-hybrid.sh" \
    "${ATLAS_BIN:-}/apt-hybrid.sh" \
    /data/user/0/com.titanus2.atlas/files/bin/apt-hybrid.sh \
    /data/data/com.titanus2.atlas/files/bin/apt-hybrid.sh
  do
    [ -f "$c" ] && APHYB=$c && break
  done
  if [ -n "$APHYB" ]; then
    for name in apt apt-get apt-cache; do
      # Prefer system inject named apt if present (same script, basename=$name)
      if [ -x "/system/bin/$name" ] && head -1 "/system/bin/$name" 2>/dev/null | grep -q '^#!'; then
        cp -f "/system/bin/$name" "$MERGE/usr/local/bin/$name" 2>/dev/null \
          || cp -f "$APHYB" "$MERGE/usr/local/bin/$name" 2>/dev/null \
          || cat "$APHYB" >"$MERGE/usr/local/bin/$name"
      else
        cp -f "$APHYB" "$MERGE/usr/local/bin/$name" 2>/dev/null \
          || cat "$APHYB" >"$MERGE/usr/local/bin/$name"
      fi
      chmod 755 "$MERGE/usr/local/bin/$name" 2>/dev/null || true
    done
  fi
  # If /usr/bin/sudo is our tiny shim (not ELF), remove it so it cannot loop
  if [ -f "$MERGE/usr/bin/sudo" ]; then
    sz=`stat -c %s "$MERGE/usr/bin/sudo" 2>/dev/null` || sz=0
    if [ "$sz" -lt 2000 ] || head -1 "$MERGE/usr/bin/sudo" 2>/dev/null | grep -q '^#!'; then
      if [ -f "$MERGE/usr/bin/sudo.real" ]; then
        # keep .real; do not put shim back at /usr/bin/sudo
        rm -f "$MERGE/usr/bin/sudo" 2>/dev/null || true
      else
        rm -f "$MERGE/usr/bin/sudo" 2>/dev/null || true
      fi
    fi
  fi
  # Always re-apply setuid on real sudo (apt install / overlay often drops it to 755)
  for sbin in "$MERGE/usr/bin/sudo.real" "$LOWER/usr/bin/sudo" "$LOWER/usr/bin/sudo.real"; do
    [ -f "$sbin" ] || continue
    head -1 "$sbin" 2>/dev/null | grep -q '^#!' && continue
    chown 0:0 "$sbin" 2>/dev/null || true
    chmod 4755 "$sbin" 2>/dev/null || true
  done
  log "agent elevate gates installed (PATH sudo → $gate; real = /usr/bin/sudo.real)"
}

# Build $MERGE/atlas-bin: Debian tools + Android bins (via android-exec) + user CLIs.
# NEVER raw-link /system/bin ELFs (wrong interpreter). NEVER link su/sudo (agent only).
link_combined_bins() {
  dest="$MERGE/atlas-bin"
  mkdir -p "$dest" 2>/dev/null || true
  stamp="$dest/.stamp"
  if [ -z "${ATLAS_RELINK:-}" ] && [ -f "$stamp" ]; then
    # Still reinstall elevate gates (cheap, law must hold)
    ensure_agent_elevate_gates
    return 0
  fi
  log "linking combined bin → $dest (debian + android peers)"
  if has_bb; then
    "$BB" find "$dest" -maxdepth 1 -type l -delete 2>/dev/null || true
    # drop old elevate links/scripts; gates reinstalled after
    rm -f "$dest/su" "$dest/sudo" 2>/dev/null || true
  else
    for old in "$dest"/*; do
      [ -L "$old" ] && rm -f "$old"
    done
    rm -f "$dest/su" "$dest/sudo" 2>/dev/null || true
  fi
  # Shim for Android tools (one script; names symlink here)
  shim="$MERGE/usr/local/libexec/atlas-android-shim"
  mkdir -p "$MERGE/usr/local/libexec" 2>/dev/null || true
  cat >"$shim" <<'EOF'
#!/bin/sh
# argv0 name → host Android binary via android-exec (Bionic + apex).
# Deb → Android only. Optional bio: ATLAS_ANDROID_AUTH=1.
# Never used for Deb apt/bash — those are real Deb packages.
name=`basename "$0"`
case "$name" in
  su|sudo|doas|pkexec)
    echo "atlas: $name is agent-gated — use PATH sudo/su" >&2
    exit 126
    ;;
  apt|apt-get|apt-cache)
    # Never shim apt to Android — real Deb path must win
    echo "atlas: $name must be Debian package, not Android shim" >&2
    exit 126
    ;;
esac
for d in /system/bin /system/xbin /product/bin /vendor/bin; do
  if [ -x "$d/$name" ]; then
    exec /usr/local/libexec/atlas-android-exec "$d/$name" "$@"
  fi
done
echo "$name: not found on Android or Debian PATH" >&2
exit 127
EOF
  chmod 755 "$shim" 2>/dev/null || true

  # --- Debian (real packages). Use find — shell globs choke on large /usr/bin. ---
  for dir in /usr/local/sbin /usr/local/bin /usr/sbin /usr/bin /sbin /bin; do
    real="$MERGE$dir"
    [ -d "$real" ] || continue
    if has_bb; then
      "$BB" find "$real" -maxdepth 1 \( -type f -o -type l \) 2>/dev/null | while read -r f; do
        [ -x "$f" ] || continue
        b=${f##*/}
        [ -n "$b" ] || continue
        [ "$b" = "*" ] && continue
        is_elevate_name "$b" && continue
        [ "$b" = "sudo.real" ] && continue
        ln -sfn "$dir/$b" "$dest/$b" 2>/dev/null || true
      done
    else
      for f in "$real"/*; do
        [ -e "$f" ] || continue
        [ -d "$f" ] && continue
        [ -x "$f" ] || continue
        b=${f##*/}
        [ -n "$b" ] || continue
        is_elevate_name "$b" && continue
        [ "$b" = "sudo.real" ] && continue
        ln -sfn "$dir/$b" "$dest/$b" 2>/dev/null || true
      done
    fi
  done
  # --- Android: every bin name not owned by Debian → shim (android-exec) ---
  # Visible as peers under /atlas-bin so `which screencap` works; one terminal.
  mkdir -p "$dest" 2>/dev/null || true
  for dir in /system/bin /system/xbin /product/bin /vendor/bin; do
    [ -d "$dir" ] || continue
    if has_bb; then
      "$BB" find "$dir" -maxdepth 1 \( -type f -o -type l \) 2>/dev/null | while read -r f; do
        [ -x "$f" ] || continue
        b=${f##*/}
        [ -n "$b" ] || continue
        is_elevate_name "$b" && continue
        # Debian package wins on name clash (nano, curl, …)
        if [ -e "$dest/$b" ] || [ -L "$dest/$b" ]; then
          continue
        fi
        ln -sfn /usr/local/libexec/atlas-android-shim "$dest/$b" 2>/dev/null || true
      done
    else
      for f in "$dir"/*; do
        [ -e "$f" ] || continue
        [ -d "$f" ] && continue
        [ -x "$f" ] || continue
        b=${f##*/}
        is_elevate_name "$b" && continue
        if [ -e "$dest/$b" ] || [ -L "$dest/$b" ]; then
          continue
        fi
        ln -sfn /usr/local/libexec/atlas-android-shim "$dest/$b" 2>/dev/null || true
      done
    fi
  done
  # Optional user CLIs on /data (curl-installed grok, ~/.local/bin, …)
  link_user_cli_into_merge
  ensure_agent_elevate_gates
  n=0
  for _ in "$dest"/*; do
    [ -e "$_" ] || [ -L "$_" ] || continue
    n=$((n + 1))
  done
  printf 'atlas-combined-bin count=%s\n' "$n" >"$stamp"
  log "combined bin entries≈$n"
}

# Universal user-install surface (curl, installers, static ELFs).
# Not product-specific: any tool dropped under these dirs is on PATH in hybrid.
# /data is bound into merge, so $HOME paths resolve live — links are for /atlas-bin peers.
user_home_candidates() {
  # Prefer live ATLAS_HOME; fall back to package files
  h="${ATLAS_HOME:-}"
  [ -n "$h" ] && [ -d "$h" ] && echo "$h" && return 0
  for h in /data/data/com.titanus2.atlas/files /data/user/0/com.titanus2.atlas/files; do
    [ -d "$h" ] && echo "$h" && return 0
  done
  echo ""
}

# Standard curl/install destinations (XDG + common vendor layouts).
user_bin_dirs() {
  h=`user_home_candidates`
  [ -n "$h" ] || return 0
  echo "$h/bin"
  echo "$h/.local/bin"
  echo "$h/.cargo/bin"
  echo "$h/.npm-global/bin"
  echo "$h/.grok/bin"
  # any future tool that uses ~/.tool/bin
  for d in "$h"/.* /dev/null; do
    [ -d "$d/bin" ] || continue
    case "$d" in
      */.local|*/.cargo|*/.grok|*/.npm-global) continue ;; # already listed
      */.) continue ;;
    esac
    echo "$d/bin"
  done
}

is_atlas_reserved_bin() {
  case "$1" in
    atlas|atlas-sudo|atlas-auth|atlas-auth-askpass|atlas-auth-pam|atlas-heal-home|\
    atlas-hybrid.sh|atlas-net.sh|atlas-sudo|sudo|su|bash|ptyexec|libatlaspty.so|\
    libatlasterm.so|apt|apt-get|apt-cache|apt-hybrid.sh) return 0 ;;
    *) return 1 ;;
  esac
}

# Expose user-installed tools into hybrid peers. PATH already covers $HOME dirs;
# links make `which` under /atlas-bin work after ATLAS_RELINK.
link_user_cli_into_merge() {
  dest="$MERGE/usr/local/bin"
  adest="$MERGE/atlas-bin"
  mkdir -p "$dest" "$adest" 2>/dev/null || true
  link_one_user() {
    src="$1"
    name="$2"
    [ -n "$name" ] || name=`basename "$src"`
    is_elevate_name "$name" && return 0
    is_atlas_reserved_bin "$name" && return 0
    [ -f "$src" ] || [ -L "$src" ] || return 0
    # Follow symlink to real file for size check
    real="$src"
    if [ -L "$src" ]; then
      t=`readlink -f "$src" 2>/dev/null || readlink "$src" 2>/dev/null || true`
      [ -n "$t" ] && [ -e "$t" ] && real=$t
    fi
    [ -x "$src" ] || chmod 755 "$src" 2>/dev/null || true
    [ -x "$src" ] || [ -x "$real" ] || return 0
    ln -sfn "$src" "$dest/$name" 2>/dev/null || true
    ln -sfn "$src" "$adest/$name" 2>/dev/null || true
  }

  # 1) Everything in standard user bin dirs
  user_bin_dirs | while read -r dir; do
    [ -d "$dir" ] || continue
    for f in "$dir"/*; do
      [ -e "$f" ] || continue
      [ -d "$f" ] && continue
      b=${f##*/}
      link_one_user "$f" "$b"
    done
  done

  # 2) Downloaded single ELFs (installers often leave tool-linux-aarch64 here)
  h=`user_home_candidates`
  if [ -n "$h" ]; then
    for dldir in "$h"/.*/downloads "$h/downloads" /sdcard/Download /data/local/tmp; do
      [ -d "$dldir" ] || continue
      for f in "$dldir"/*; do
        [ -f "$f" ] || continue
        [ -d "$f" ] && continue
        b=${f##*/}
        is_elevate_name "$b" && continue
        is_atlas_reserved_bin "$b" && continue
        # skip archives / text
        case "$b" in
          *.tar|*.gz|*.tgz|*.zip|*.deb|*.apk|*.txt|*.md|*.json|*.toml|*.sh) continue ;;
        esac
        sz=`stat -c %s "$f" 2>/dev/null` || sz=0
        [ "$sz" -ge 100000 ] || continue
        [ -x "$f" ] || chmod 755 "$f" 2>/dev/null || true
        [ -x "$f" ] || continue
        # Prefer short name: foo-linux-aarch64 → foo when free
        short=$b
        case "$b" in
          *-linux-aarch64) short=${b%-linux-aarch64} ;;
          *-linux-arm64) short=${b%-linux-arm64} ;;
          *_linux_aarch64) short=${b%_linux_aarch64} ;;
          *_linux_arm64) short=${b%_linux_arm64} ;;
        esac
        [ -n "$short" ] || short=$b
        if [ "$short" != "$b" ] && [ ! -e "$dest/$short" ] && [ ! -e "$adest/$short" ]; then
          link_one_user "$f" "$short"
        fi
        link_one_user "$f" "$b"
      done
    done
  fi
}

unbind_android() {
  # recursive first (apex/linkerconfig rbind trees)
  for d in \
    "$MERGE/bootstrap-apex" "$MERGE/linkerconfig" "$MERGE/apex" \
    "$MERGE/data/local/tmp" "$MERGE/mnt" "$MERGE/storage" "$MERGE/sdcard" \
    "$MERGE/data" "$MERGE/system_ext" \
    "$MERGE/product" "$MERGE/vendor" "$MERGE/system" \
    "$MERGE/dev/pts" "$MERGE/dev" "$MERGE/proc" "$MERGE/sys"
  do
    if is_mounted "$d" || grep -q " $d " /proc/mounts 2>/dev/null; then
      umount -R "$d" 2>/dev/null || umount -l "$d" 2>/dev/null || true
    fi
  done
}

# Map Android app UID → Debian "admin" with passwordless sudo *after* agent grant.
# (Agent client runs biometrics, then /usr/bin/sudo -n — no KernelSU.)
ensure_admin_user() {
  uid="${ATLAS_DROP_UID:-}"
  [ -z "$uid" ] || [ "$uid" = "0" ] && \
    uid=`stat -c %u /data/data/com.titanus2.atlas 2>/dev/null \
      || stat -c %u /data/user/0/com.titanus2.atlas 2>/dev/null || echo 10198`
  home="${ATLAS_HOME:-/data/data/com.titanus2.atlas/files}"
  for root in "$MERGE" "$LOWER"; do
    [ -d "$root/etc" ] || continue
    # passwd / group / shadow
    if [ -f "$root/etc/passwd" ] && ! grep -q "^admin:" "$root/etc/passwd" 2>/dev/null; then
      echo "admin:x:${uid}:${uid}:Atlas Admin:${home}:/bin/bash" >>"$root/etc/passwd"
    fi
    if [ -f "$root/etc/group" ] && ! grep -q "^admin:" "$root/etc/group" 2>/dev/null; then
      echo "admin:x:${uid}:" >>"$root/etc/group"
    fi
    if [ -f "$root/etc/shadow" ] && ! grep -q "^admin:" "$root/etc/shadow" 2>/dev/null; then
      echo "admin:*:19600:0:99999:7:::" >>"$root/etc/shadow"
    fi
    mkdir -p "$root/etc/sudoers.d" 2>/dev/null || true
    # NOPASSWD: agent already authorized on the Android host
    printf 'admin ALL=(ALL) NOPASSWD:ALL\n# uid %s same rights when listed by number\nDefaults:%s !authenticate\n' \
      "$uid" "$uid" >"$root/etc/sudoers.d/atlas-admin" 2>/dev/null || true
    # Also allow numeric user (app uid without name match)
    printf '%s ALL=(ALL) NOPASSWD:ALL\n' "$uid" >>"$root/etc/sudoers.d/atlas-admin" 2>/dev/null || true
    chmod 440 "$root/etc/sudoers.d/atlas-admin" 2>/dev/null || true
    # Real setuid target (agent calls this after biometrics). Must re-apply every
    # bind — package install / overlay often leaves mode 755 and sudo refuses.
    for sbin in "$root/usr/bin/sudo.real" "$root/usr/bin/sudo"; do
      [ -f "$sbin" ] || continue
      # Never setuid a shell shim
      if head -1 "$sbin" 2>/dev/null | grep -q '^#!'; then
        continue
      fi
      sz=`stat -c %s "$sbin" 2>/dev/null` || sz=0
      [ "$sz" -lt 20000 ] && continue
      chown 0:0 "$sbin" 2>/dev/null || true
      chmod 4755 "$sbin" 2>/dev/null || true
    done
  done
}

write_profile() {
  # profile lives in upper via merge writes, or lower if merge not up
  target="$LOWER/etc/profile.d"
  mkdir -p "$target" 2>/dev/null || true
  cat >"$target/atlas-hybrid.sh" <<'EOF'
# Atlas combined OS — one terminal, seamless Android ↔ Debian
export ATLAS_HYBRID=1
export ATLAS_COMBINED=1
export USER="${USER:-admin}"
export LOGNAME="${LOGNAME:-admin}"
export ATLAS_ROLE=admin
_AB="${ATLAS_BIN:-}"
_AH="${ATLAS_HOME:-${HOME:-}}"
# curl-install destinations (universal — any tool you drop here is on PATH)
mkdir -p \
  "${_AH}/bin" "${_AH}/.local/bin" "${_AH}/.cargo/bin" \
  "${_AH}/.npm-global/bin" "${_AH}/.grok/bin" 2>/dev/null || true
# Debian first, then user installs, then ATLAS_BIN (sudo agent). Never let $HOME/bin/apt shadow /usr/bin/apt.
_USER_PATH="${_AH}/.local/bin:${_AH}/.cargo/bin:${_AH}/.npm-global/bin:${_AH}/.grok/bin:${_AH}/bin"
export PATH="/usr/local/sbin:/usr/local/bin:/atlas-bin:/usr/sbin:/usr/bin:/sbin:/bin:${_USER_PATH}:${_AB}:/system/bin:/system/xbin:/vendor/bin:/product/bin"
export ANDROID_ROOT="${ANDROID_ROOT:-/system}"
export ANDROID_DATA="${ANDROID_DATA:-/data}"
export ANDROID_STORAGE="${ANDROID_STORAGE:-/storage}"
export TMPDIR="${TMPDIR:-/tmp}"
export DEBIAN_FRONTEND="${DEBIAN_FRONTEND:-noninteractive}"
export SUDO_ASKPASS="${SUDO_ASKPASS:-${ATLAS_BIN:-}/atlas-auth-askpass}"
export ATLAS_AUTH_DIR="${ATLAS_AUTH_DIR:-${ATLAS_HOME:-}/auth}"
if [ -x /bin/bash ]; then
  export SHELL=/bin/bash
elif [ -x /usr/bin/bash ]; then
  export SHELL=/usr/bin/bash
fi
if [ -n "${BASH_VERSION:-}" ]; then
  PS1='admin:\w\$ '
else
  PS1='admin\$ '
fi
stty sane 2>/dev/null || true
stty erase ^? intr ^C 2>/dev/null || true
atlas-bins() { ls -1 /atlas-bin 2>/dev/null | wc -l; echo "entries in /atlas-bin (linux+android+user synced)"; }
# Privilege law: typing sudo/su always hits Authentication Agent (biometrics)
if [ -n "${BASH_VERSION:-}" ] && [ -n "${ATLAS_BIN:-}" ]; then
  sudo() { "$ATLAS_BIN/sudo" "$@"; }
  su() { "$ATLAS_BIN/su" "$@"; }
fi
# Missed Android name → android-exec (no mode switch)
if [ -n "${BASH_VERSION:-}" ]; then
  command_not_found_handle() {
    cmd="$1"; shift
    case "$cmd" in su|sudo|doas|pkexec)
      echo "$cmd: use PATH sudo/su (Authentication Agent)" >&2; return 126 ;;
    esac
    if [ -x /usr/local/libexec/atlas-android-exec ]; then
      for d in /system/bin /system/xbin /product/bin /vendor/bin; do
        if [ -x "$d/$cmd" ]; then
          exec /usr/local/libexec/atlas-android-exec "$d/$cmd" "$@"
        fi
      done
    fi
    echo "$cmd: not found" >&2
    return 127
  }
fi
EOF
  chmod 644 "$target/atlas-hybrid.sh" 2>/dev/null || true
  ensure_admin_user
  # copy into upper-visible merge if mounted
  if is_mounted "$MERGE"; then
    mkdir -p "$MERGE/etc/profile.d" 2>/dev/null || true
    cp -f "$target/atlas-hybrid.sh" "$MERGE/etc/profile.d/atlas-hybrid.sh" 2>/dev/null || true
  fi
}

heal_debian_tree() {
  # $1 = root of debian tree (lower or flat)
  tree="$1"
  [ -d "$tree" ] || return 1
  mkdir -p "$tree/tmp" "$tree/var/tmp" "$tree/var/cache/apt/archives/partial" \
    "$tree/var/lib/apt/lists/partial" 2>/dev/null || true
  chmod 1777 "$tree/tmp" "$tree/var/tmp" 2>/dev/null || true
  # apt needs writable cache owned by root with sane modes
  chmod -R a+rX "$tree/var/cache/apt" 2>/dev/null || true
  mkdir -p "$tree/var/cache/apt/archives/partial" 2>/dev/null || true
  chmod 755 "$tree/var/cache/apt" "$tree/var/cache/apt/archives" 2>/dev/null || true
  chmod 1777 "$tree/var/cache/apt/archives/partial" 2>/dev/null || true

  # Prefer live Android/VPN DNS; fall back only when body empty
  body=`atlas_android_dns_body 2>/dev/null` || body=
  if [ -n "$body" ]; then
    printf '%s\n' "$body" >"$tree/etc/resolv.conf" 2>/dev/null || true
  fi
  [ -f "$tree/etc/hosts" ] || printf '127.0.0.1 localhost\n::1 localhost\n' >"$tree/etc/hosts"

  if [ -d "$tree/etc/apt" ]; then
    # single sources.list — drop duplicate deb822 that doubles suites
    cat >"$tree/etc/apt/sources.list" <<EOF
deb http://deb.debian.org/debian ${BASE_CODENAME} main contrib non-free non-free-firmware
deb http://deb.debian.org/debian ${BASE_CODENAME}-updates main contrib non-free non-free-firmware
deb http://security.debian.org/debian-security ${BASE_CODENAME}-security main contrib non-free non-free-firmware
EOF
    # disable packaged debian.sources if present (duplicate)
    if [ -f "$tree/etc/apt/sources.list.d/debian.sources" ]; then
      mv -f "$tree/etc/apt/sources.list.d/debian.sources" \
        "$tree/etc/apt/sources.list.d/debian.sources.disabled" 2>/dev/null || true
    fi
  fi
  write_profile
}

# Migrate pre-v4 flat extract at ROOT into lower/ inside loop image.
migrate_flat_to_loop() {
  need_root || return 1
  # If ROOT already is loop mount with lower/, nothing to do
  if is_mounted "$ROOT" && [ -d "$LOWER/etc" ]; then
    return 0
  fi
  # Flat tree sitting on f2fs at ROOT path
  if [ ! -d "$ROOT/etc" ] && [ ! -d "$ROOT/bin" ]; then
    return 0
  fi
  if [ -d "$LOWER/etc" ]; then
    return 0
  fi

  log "migrating flat rootfs → ext4 loop + lower/ (one-time)"
  # move aside
  if is_mounted "$ROOT"; then
    log "ROOT already mounted unexpectedly"
    return 1
  fi
  rm -rf "$LEGACY" 2>/dev/null || true
  mv "$ROOT" "$LEGACY" || {
    log "could not move $ROOT → $LEGACY"
    return 1
  }
  ensure_img || return 1
  mkdir -p "$ROOT"
  mount_loop || return 1
  prepare_dirs
  log "copying debian into lower (this can take a minute)…"
  # prefer busybox cp -a
  if has_bb; then
    "$BB" cp -a "$LEGACY/." "$LOWER/" || {
      log "copy failed"
      return 1
    }
  else
    cp -a "$LEGACY/." "$LOWER/" || {
      log "copy failed"
      return 1
    }
  fi
  # drop nested garbage if any
  heal_debian_tree "$LOWER"
  printf 'atlas-hybrid %s base=%s-%s peer=nio-12l-16g overlay=1 combined=1\n' \
    "$VER" "$BASE_DISTRO" "$BASE_CODENAME" >"$MARKER"
  log "migrate OK — legacy kept at $LEGACY (safe to rm after verify)"
  return 0
}

find_rootfs_tarball() {
  cand=""
  # Product order: ROM seed first (survives wipe), then app stage, then lab tmp.
  # Network URL is NOT used here — only ATLAS_ALLOW_NET_SEED bootstrap path.
  for f in \
      /product/etc/atlas/debian-trixie-arm64-rootfs.tar.gz \
      /system/product/etc/atlas/debian-trixie-arm64-rootfs.tar.gz \
      /system/etc/atlas/debian-trixie-arm64-rootfs.tar.gz \
      /system_ext/etc/atlas/debian-trixie-arm64-rootfs.tar.gz \
      "${ATLAS_HOME:-}/rootfs/debian-trixie-arm64-rootfs.tar.gz" \
      "${HOME:-}/rootfs/debian-trixie-arm64-rootfs.tar.gz" \
      "${ATLAS_HOME:-}/rootfs/rootfs.tar.gz" \
      "${HOME:-}/rootfs/rootfs.tar.gz" \
      /data/user/0/com.titanus2.atlas/files/rootfs/debian-trixie-arm64-rootfs.tar.gz \
      /data/data/com.titanus2.atlas/files/rootfs/debian-trixie-arm64-rootfs.tar.gz \
      /data/local/tmp/atlas-hybrid-dl/debian-trixie-arm64-rootfs.tar.gz \
      /data/local/tmp/atlas-hybrid-dl/rootfs.tar.gz \
      /data/local/tmp/atlas-hybrid-dl/rootfs.tar.xz \
      /sdcard/Atlas/debian-trixie-arm64-rootfs.tar.gz \
      /storage/emulated/0/Atlas/debian-trixie-arm64-rootfs.tar.gz
  do
    [ -n "$f" ] || continue
    [ -f "$f" ] || continue
    sz=`stat -c %s "$f" 2>/dev/null` || sz=0
    case "$sz" in ''|*[!0-9]*) continue ;; esac
    [ "$sz" -ge 1000000 ] || continue
    cand=$f
    break
  done
  echo "$cand"
}

cmd_status() {
  plane_defaults
  echo "key=Atlas"
  echo "engine=overlay+loop+pivot (combined OS, not chroot-first)"
  echo "img=$IMG"
  echo "root=$ROOT"
  echo "lower=$LOWER"
  echo "merge=$MERGE"
  echo "storage_model=debian_super_lp+home_on_android_data"
  echo "lp_mnt=$LP_MNT lp_ready=$(lp_root_ready && echo yes || echo no)"
  echo "auth_on_lp=$ATLAS_AUTH_ON_LP"
  echo "auth_exists=$([ -d "$ATLAS_AUTH_ON_LP" ] && echo yes || echo no)"
  echo "auth_wipe=survives_userdata_wipe"
  echo "linux_home=$ATLAS_LINUX_HOME"
  echo "size_policy=${ATLAS_HYBRID_SIZE_G:-auto} (compat loop GiB=$(compute_hybrid_size_g))"
  seed_now=`find_rootfs_tarball`
  echo "rom_seed=${seed_now:-none}"
  echo "img_present=$([ -f "$IMG" ] && echo yes || echo no)"
  echo "loop_mounted=$(is_mounted "$ROOT" && echo yes || echo no)"
  echo "overlay_up=$(is_overlay_up && echo yes || echo no)"
  echo "bootstrapped=$(is_bootstrapped && echo yes || echo no)"
  echo "marker=$([ -f "$MARKER" ] && cat "$MARKER" || echo missing)"
  bash_l=no
  [ -x "$LOWER/bin/bash" ] || [ -x "$LOWER/usr/bin/bash" ] || \
    [ -x "$MERGE/bin/bash" ] || [ -x "$ROOT/bin/bash" ] && bash_l=yes
  echo "bash=$bash_l"
  merge_bash=no
  [ -x "$MERGE/bin/bash" ] || [ -x "$MERGE/usr/bin/bash" ] && merge_bash=yes
  echo "merge_bash=$merge_bash"
  echo "uid=$(id -u 2>/dev/null)"
  echo "mode=$(plane_read titan2_atlas_mode android)"
  echo "storage=$(plane_read titan2_atlas_storage shared)"
  echo "trust=$(plane_read titan2_atlas_trust normal)"
  echo "desktop=$(plane_read titan2_atlas_desktop none)"
  echo "pad=$(plane_read titan2_pad_mode off)"
  echo "base=${BASE_DISTRO}-${BASE_CODENAME}-${BASE_ARCH}"
  echo "peer=armbian-radxa-nio-12l (16G) · shared Debian ${BASE_CODENAME}"
  echo "android_bins=bound into merge (/system /vendor …)"
  echo "android_apps=same kernel; am/pm via PATH when bound"
  # Agent report contract (I-01 binder): IPC path must be explicit
  if [ -x "$MERGE/usr/local/libexec/atlas-android-exec" ] || [ -x "$MERGE/usr/local/bin/android" ]; then
    echo "android_ipc=android-exec_nsenter (use: android screencap|am|pm …)"
  else
    echo "android_ipc=nsenter-required (install helpers: hybrid mount)"
  fi
  echo "agent_status=atlas-agent-status · reports=\$HOME/reports/"
  if [ -f "$LOWER/etc/os-release" ]; then
    # shellcheck disable=SC1090
    . "$LOWER/etc/os-release" 2>/dev/null || true
    echo "os_pretty=${PRETTY_NAME:-unknown}"
    echo "os_version=${VERSION_CODENAME:-${VERSION_ID:-unknown}}"
  elif [ -f "$ROOT/etc/os-release" ]; then
    . "$ROOT/etc/os-release" 2>/dev/null || true
    echo "os_pretty=${PRETTY_NAME:-unknown}"
  fi
  if [ -f "$IMG" ]; then
    du -h "$IMG" 2>/dev/null | awk '{print "img_size="$1}'
  fi
  if is_mounted "$ROOT"; then
    df -h "$ROOT" 2>/dev/null | tail -1 | awk '{print "loop_free="$4" used="$3}'
  fi
}

cmd_heal() {
  need_root || return 1
  migrate_flat_to_loop 2>/dev/null || true
  if is_bootstrapped; then
    mount_loop 2>/dev/null || true
    if [ -d "$LOWER/etc" ]; then
      heal_debian_tree "$LOWER"
    elif [ -d "$ROOT/etc" ]; then
      heal_debian_tree "$ROOT"
    fi
  fi
  mount_overlay 2>/dev/null || true
  bind_android 2>/dev/null || true
  log "heal done"
  cmd_status
}

cmd_mount() {
  need_root || return 1
  # Product first: super atlas_linux LP (Debian root). Prefer over legacy loop.
  if lp_dev_present; then
    if bring_up_from_lp; then
      export ATLAS_LP_MODE=1
      write_product_status 2>/dev/null || true
      return 0
    fi
    log "LP present but bring_up_from_lp failed — compat loop"
  fi
  # Fast path: already mounted → no loop/overlay/bind work
  if is_overlay_up; then
    log "already up at $MERGE (skip remount)"
    return 0
  fi
  migrate_flat_to_loop || true

  # If image already exists (file OR loop-backed), ONLY remount / seed-extract.
  # NEVER fall through to "first install" mke2fs path — that wiped Deb after
  # concurrent ensure / mount_loop races (product mess 2026-08-10).
  # Skipped when super LP already provided Deb (above).
  if [ -e "$IMG" ] || [ -L "$IMG" ] || losetup -a 2>/dev/null | grep -qF "$IMG"; then
    sz=`stat -c %s "$IMG" 2>/dev/null` || sz=0
    log "existing hybrid image sz=$sz — remount/seed only (no recreate)"
    if ! mount_loop; then
      log "mount_loop failed for existing $IMG — preserving image (no bootstrap/mke2fs)"
      touch /data/local/tmp/atlas-hybrid-need-fsck 2>/dev/null || true
      return 1
    fi
    if is_bootstrapped; then
      mount_overlay || return 1
      bind_android || return 1
      plane_write titan2_atlas_mode debian
      log "combined root up at $MERGE (remount existing image)"
      return 0
    fi
    # Mounted but empty lower — only extract seed into LOWER, never recreate IMG
    seed=`find_rootfs_tarball`
    if [ "${ATLAS_AUTO_BOOTSTRAP:-1}" = "1" ] && [ -n "$seed" ]; then
      log "mounted image but empty lower — seed extract only (no mke2fs)"
      log "seed=$seed"
      cmd_bootstrap || return 1
      return 0
    fi
    log "mounted image empty lower — no seed"
    return 1
  fi

  # No image file at all — first-install auto-bootstrap when seed staged
  if ! is_bootstrapped; then
    seed=`find_rootfs_tarball`
    if [ "${ATLAS_AUTO_BOOTSTRAP:-1}" = "1" ] && [ -n "$seed" ]; then
      log "no image file + seed present → auto-bootstrap (hybrid ROM first install)"
      log "seed=$seed"
      cmd_bootstrap || return 1
      return 0
    fi
    mount_loop 2>/dev/null || true
    prepare_dirs 2>/dev/null || true
    log "empty lower — no seed (stage /system/etc/atlas/*.tar.gz or app rootfs)"
    return 1
  fi
  mount_overlay || return 1
  bind_android || return 1
  plane_write titan2_atlas_mode debian
  log "combined root up at $MERGE"
  return 0
}

# Remove overlay whiteouts that hide essential Debian bins (merge 0700 + whiteout = hybrid-down).
heal_merge_essentials() {
  need_root || return 1
  [ -d "$UPPER" ] || return 0
  for f in bin/bash usr/bin/bash bin/sh bin/dash usr/bin/dash \
           etc/debian_version etc/os-release; do
    # whiteout = char device, or empty blocker file over lower
    if [ -c "$UPPER/$f" ] || [ -e "$UPPER/$f" ]; then
      if [ ! -x "$MERGE/$f" ] 2>/dev/null; then
        rm -rf "$UPPER/$f" 2>/dev/null || true
        log "heal: removed upper blocker $f"
      fi
    fi
  done
  # allow app/status probes (rootless ready file still SoT)
  chmod 755 "$MERGE" 2>/dev/null || true
  chmod 755 "$MERGE/bin" "$MERGE/usr" "$MERGE/usr/bin" "$MERGE/etc" 2>/dev/null || true
  return 0
}

# World-readable product status — app cannot probe merge (often 0700).
write_product_status() {
  st=/data/local/tmp/atlas_hybrid.status
  ready=0
  overlay=0
  boot=0
  img=0
  deb=
  enter=0
  [ -x /system/bin/atlas-enter ] && enter=1
  [ -f "$IMG" ] && img=1
  # mount line alone (is_overlay_up also needs bash — can disagree when whiteout)
  grep -q " $MERGE " /proc/mounts 2>/dev/null && overlay=1
  is_bootstrapped && boot=1
  if [ "$overlay" = "1" ]; then
    heal_merge_essentials 2>/dev/null || true
    # ready only if chroot merge can exec bash (lower-only is NOT enterable)
    if [ -x "$MERGE/usr/bin/bash" ] || [ -x "$MERGE/bin/bash" ] || [ -x "$MERGE/bin/sh" ]; then
      ready=1
    else
      ready=0
    fi
    deb=`cat "$MERGE/etc/debian_version" 2>/dev/null | head -1 | tr -d '\r\n'`
    [ -z "$deb" ] && deb=`cat "$LOWER/etc/debian_version" 2>/dev/null | head -1 | tr -d '\r\n'`
  fi
  {
    echo "ready=$ready"
    echo "overlay=$overlay"
    echo "bootstrapped=$boot"
    echo "img=$img"
    echo "debian=$deb"
    echo "enter_bin=$enter"
    echo "ts=$(date +%s 2>/dev/null || echo 0)"
  } >"$st" 2>/dev/null || true
  chmod 644 "$st" 2>/dev/null || true
  if [ "$ready" = "1" ]; then
    echo 1 >/data/local/tmp/atlas_hybrid.ready 2>/dev/null || true
  else
    echo 0 >/data/local/tmp/atlas_hybrid.ready 2>/dev/null || true
  fi
  chmod 644 /data/local/tmp/atlas_hybrid.ready 2>/dev/null || true
}

# ensure = product install/boot entry: mount or auto-bootstrap then mount
cmd_ensure() {
  need_root || return 1
  export ATLAS_AUTO_BOOTSTRAP="${ATLAS_AUTO_BOOTSTRAP:-1}"

  # Serialize — concurrent boot+watch+enterd was detaching live loop50 (hybrid thrash).
  elock=/data/local/tmp/atlas-hybrid-ensure.lock
  mkdir -p /data/local/tmp 2>/dev/null || true
  exec 8>"$elock" 2>/dev/null || true
  if command -v flock >/dev/null 2>&1; then
    flock 8 2>/dev/null || true
  fi

  # Product: super LP wins even if legacy loop overlay is already up (KEEP_DATA).
  if lp_dev_present; then
    # If merge is already bind of LP, heal only
    if is_mounted "$MERGE" && lp_root_ready && grep -q " $MERGE " /proc/mounts 2>/dev/null; then
      # If merge is overlay (legacy), switch to LP
      if grep -q " $MERGE overlay " /proc/mounts 2>/dev/null \
        || grep -q "overlay $MERGE " /proc/mounts 2>/dev/null; then
        log "ensure: tearing legacy overlay for super LP"
        if bring_up_from_lp; then
          export ATLAS_LP_MODE=1
          write_product_status
          return 0
        fi
      elif [ -x "$MERGE/bin/bash" ] || [ -x "$MERGE/usr/bin/bash" ]; then
        # already LP-style (bind or direct)
        bind_android 2>/dev/null || true
        heal_merge_essentials 2>/dev/null || true
        plane_write titan2_atlas_mode debian
        export ATLAS_LP_MODE=1
        write_product_status
        log "ensure: live LP merge — heal only"
        return 0
      fi
    fi
    if bring_up_from_lp; then
      export ATLAS_LP_MODE=1
      write_product_status
      return 0
    fi
    log "ensure: LP present but failed — fall through to loop"
  fi

  # LIVE overlay in /proc/mounts: never remount, never e2fsck, never detach.
  if grep -q " $MERGE " /proc/mounts 2>/dev/null; then
    unset ATLAS_FORCE_FSCK
    rm -f /data/local/tmp/atlas-hybrid-need-fsck 2>/dev/null || true
    heal_merge_essentials 2>/dev/null || true
    if [ ! -x "$MERGE/system/bin/sh" ] && [ ! -d "$MERGE/system/bin" ]; then
      bind_android 2>/dev/null || true
    fi
    plane_write titan2_atlas_mode debian
    write_product_status
    log "ensure: live overlay — heal only (no remount)"
    return 0
  fi

  # Loop already mounted, overlay down — only bring overlay up
  if is_mounted "$ROOT"; then
    unset ATLAS_FORCE_FSCK
    rm -f /data/local/tmp/atlas-hybrid-need-fsck 2>/dev/null || true
    if ! is_bootstrapped; then
      log "ensure: loop up but not bootstrapped"
      write_product_status
      return 1
    fi
    mount_overlay || {
      write_product_status
      return 1
    }
    bind_android 2>/dev/null || true
    heal_merge_essentials 2>/dev/null || true
    plane_write titan2_atlas_mode debian
    write_product_status
    log "ensure: overlay from live loop"
    return 0
  fi

  # Cold path: no live mounts
  cmd_mount
  rc=$?
  heal_merge_essentials 2>/dev/null || true
  write_product_status
  return $rc
}

cmd_umount() {
  need_root || return 1
  umount_overlay
  # leave loop mounted so upper persists without remount cost; use umount-all for full
  if [ "${1:-}" = "all" ]; then
    umount_loop
  fi
  log "overlay down"
}

cmd_bootstrap() {
  need_root || return 1
  if is_bootstrapped && [ -d "$LOWER/etc" ]; then
    log "already bootstrapped — use destroy first to redo, or heal"
    cmd_mount
    return 0
  fi

  # migrate existing flat if present
  if [ -d "$ROOT/etc" ] && [ ! -f "$IMG" ]; then
    migrate_flat_to_loop || return 1
    mount_overlay || return 1
    bind_android
    log "bootstrap OK (migrated) — hybrid enter"
    cmd_status
    return 0
  fi

  log "bootstrap Debian ${BASE_CODENAME} arm64 → loop+overlay"
  ensure_img || return 1
  mount_loop || return 1
  prepare_dirs

  tmp=`find_rootfs_tarball`
  if [ -z "$tmp" ]; then
    log "no staged rootfs image"
    log "stage: build_debian_rootfs.sh --push  or Atlas assets/rootfs"
    return 1
  fi
  log "using image: $tmp"
  # Absolute symlinks in Debian rootfs (e.g. /etc/alternatives → absolute targets)
  # make Android/toybox tar exit non-zero even when the tree is usable. Only fail
  # if essential userspace is missing after extract.
  tar_ok=0
  case "$tmp" in
    *.xz) tar -xJf "$tmp" -C "$LOWER" && tar_ok=1 || tar_ok=0 ;;
    *)    tar -xzf "$tmp" -C "$LOWER" && tar_ok=1 || tar_ok=0 ;;
  esac
  if [ "$tar_ok" != "1" ]; then
    if [ -x "$LOWER/bin/bash" ] || [ -x "$LOWER/usr/bin/bash" ]; then
      log "tar reported errors (abs symlinks?) — rootfs usable, continuing"
    else
      log "tar extract failed — no bash in lower"
      return 1
    fi
  fi
  # some cloud images nest under a single dir
  if [ ! -d "$LOWER/etc" ]; then
    sub=`ls -1 "$LOWER" 2>/dev/null | head -1`
    if [ -n "$sub" ] && [ -d "$LOWER/$sub/etc" ]; then
      log "flattening nested $sub"
      if has_bb; then
        "$BB" mv "$LOWER/$sub"/* "$LOWER/" 2>/dev/null || true
        "$BB" mv "$LOWER/$sub"/.* "$LOWER/" 2>/dev/null || true
      else
        mv "$LOWER/$sub"/* "$LOWER/" 2>/dev/null || true
      fi
      rmdir "$LOWER/$sub" 2>/dev/null || true
    fi
  fi

  heal_debian_tree "$LOWER"
  printf 'atlas-hybrid %s base=%s-%s peer=nio-12l-16g overlay=1 combined=1\n' \
    "$VER" "$BASE_DISTRO" "$BASE_CODENAME" >"$MARKER"
  printf 'peer=armbian-radxa-nio-12l\nbase=debian-%s\nengine=overlay+loop\n' \
    "$BASE_CODENAME" >"$LOWER/etc/atlas-hybrid-peer" 2>/dev/null || true

  mount_overlay || return 1
  bind_android
  write_product_status
  log "bootstrap OK — Atlas is the KEY: hybrid enter (combined OS)"
  cmd_status
  return 0
}

shell_bin_in() {
  base="$1"
  if [ -x "$base/bin/bash" ]; then echo /bin/bash
  elif [ -x "$base/usr/bin/bash" ]; then echo /usr/bin/bash
  else echo /bin/sh
  fi
}

# Canonical package home. Prefer /data/data — on this GSI /data/user/0 is often
# mode 700 (system-only) and/or a root-owned SHADOW of the real package tree.
# That produces: resolv.conf / .bash_env / .bash_profile Permission denied on enter.
_atlas_pkg_real() {
  if [ -d /data/data/com.titanus2.atlas ]; then
    echo /data/data/com.titanus2.atlas
  elif [ -d /data/user/0/com.titanus2.atlas ] && [ ! -L /data/user/0/com.titanus2.atlas ]; then
    # only accept user/0 if owned by app (not root shadow)
    u=`stat -c %u /data/user/0/com.titanus2.atlas 2>/dev/null`
    [ -n "$u" ] && [ "$u" != "0" ] && echo /data/user/0/com.titanus2.atlas && return 0
    echo /data/data/com.titanus2.atlas
  else
    echo /data/data/com.titanus2.atlas
  fi
}

_atlas_home_real() {
  echo "$(_atlas_pkg_real)/files"
}

# Fix multi-user path layout so Android ContextImpl getFilesDir works:
#  - /data/user + /data/user/0 must be 0711 (path walk)
#  - /data/user/0/$pkg must be a real dir or BIND of /data/data/$pkg
#    (symlink breaks ensurePrivateDirExists → mkdir ENOENT → black terminal)
_heal_atlas_user_layout() {
  real=`_atlas_pkg_real`
  [ -d "$real" ] || return 0
  chmod 711 /data/user 2>/dev/null || true
  chmod 711 /data/user/0 2>/dev/null || true
  mkdir -p /data/user/0 2>/dev/null || true
  fake=/data/user/0/com.titanus2.atlas
  # Symlink is poison for getFilesDir — always remove
  if [ -L "$fake" ]; then
    log "heal: remove package symlink $fake (breaks Android files dir)"
    rm -f "$fake" 2>/dev/null || true
  fi
  if mount 2>/dev/null | grep -q " $fake "; then
    return 0
  fi
  if [ -d "$fake" ]; then
    ri=`stat -c %i "$real" 2>/dev/null`
    fi_=`stat -c %i "$fake" 2>/dev/null`
    if [ -n "$ri" ] && [ "$ri" = "$fi_" ]; then
      return 0
    fi
    # root shadow tree — park then bind
    log "heal: replace shadow $fake with bind → $real"
    rm -rf /data/local/tmp/atlas-user0-shadow.bak 2>/dev/null || true
    mv "$fake" /data/local/tmp/atlas-user0-shadow.bak 2>/dev/null \
      || rm -rf "$fake" 2>/dev/null || true
  fi
  mkdir -p "$fake" 2>/dev/null || true
  mount --bind "$real" "$fake" 2>/dev/null || true
}

# After panic/ensure-as-root, Atlas HOME often ends root:root mode 700/600.
# Admin drop (app UID) then cannot read .bash_profile or write etc/resolv.conf.
_heal_atlas_home() {
  home="$1"
  uid="$2"
  _heal_atlas_user_layout
  # always prefer real package files if caller passed the shadow path
  realh=`_atlas_home_real`
  if [ -d "$realh" ]; then
    home="$realh"
  fi
  [ -n "$home" ] && [ -d "$home" ] || return 0
  [ -n "$uid" ] && [ "$uid" != "0" ] || return 0
  # package dir + files tree
  chown -R "$uid:$uid" "$home" 2>/dev/null || true
  # seclabel for app data (best-effort; fail closed if restorecon missing)
  if command -v restorecon >/dev/null 2>&1; then
    restorecon -RF "$home" 2>/dev/null || true
  fi
  # readable profiles for login shell
  for f in .profile .bash_profile .bashrc .bash_env .inputrc; do
    [ -f "$home/$f" ] || continue
    chown "$uid:$uid" "$home/$f" 2>/dev/null || true
    chmod 644 "$home/$f" 2>/dev/null || true
  done
  mkdir -p "$home/etc" "$home/bin" "$home/reports" "$home/auth" 2>/dev/null || true
  chown "$uid:$uid" "$home/etc" "$home/bin" "$home/reports" "$home/auth" 2>/dev/null || true
  chmod 755 "$home" "$home/etc" "$home/bin" 2>/dev/null || true
  # resolv for musl/static tools — Android VPN-aware (refresh on every home heal)
  if [ -w "$home/etc" ] || [ "$(stat -c %u "$home" 2>/dev/null)" = "$uid" ]; then
    body=`atlas_android_dns_body 2>/dev/null` || body=
    if [ -n "$body" ]; then
      printf '%s\n' "$body" >"$home/etc/resolv.conf" 2>/dev/null || true
      chown "$uid:$uid" "$home/etc/resolv.conf" 2>/dev/null || true
      chmod 644 "$home/etc/resolv.conf" 2>/dev/null || true
    fi
  fi
}

# Enter combined root as admin (app UID). Lab GSI: pivot_root returns EINVAL;
# chroot + absolute /system/bin/su drop is the proven path (Debian lower + binds).
_enter_exec() {
  # args: shell path relative (e.g. /bin/bash), then optional -l / -lc "cmd" / cmd…
  shrel="$1"
  shift
  if ! is_overlay_up; then
    mount_overlay || return 1
    bind_android || return 1
  else
    # always re-bind (repairs plain /data bind missing /data/user/0)
    bind_android 2>/dev/null || true
  fi

  DROP="${ATLAS_DROP_UID:-}"
  if [ -z "$DROP" ] || [ "$DROP" = "0" ]; then
    DROP=`stat -c %u /data/data/com.titanus2.atlas 2>/dev/null \
      || stat -c %u /data/user/0/com.titanus2.atlas 2>/dev/null || true`
  fi
  if [ -z "$DROP" ] || [ "$DROP" = "0" ]; then
    log "FATAL no admin uid (ATLAS_DROP_UID) — refuse root shell"
    return 1
  fi

  # Overlay can report "up" while merge is mid-rebind (empty /bin). Remount once.
  if [ ! -x "$MERGE$shrel" ] && [ ! -x "$MERGE/bin/bash" ] && [ ! -x "$MERGE/usr/bin/bash" ] \
      && [ ! -x "$MERGE/bin/sh" ] && [ ! -x "$MERGE/bin/dash" ]; then
    log "merge has no shell yet — remount overlay+binds once"
    umount_overlay 2>/dev/null || true
    mount_overlay || return 1
    bind_android || return 1
  fi
  if [ ! -x "$MERGE$shrel" ] && [ ! -x "$MERGE/bin/bash" ] && [ ! -x "$MERGE/usr/bin/bash" ] \
      && [ ! -x "$MERGE/bin/sh" ] && [ ! -x "$MERGE/bin/dash" ]; then
    log "FATAL no shell in merge ($shrel) — bootstrap incomplete?"
    return 1
  fi
  # Prefer real bash; fall back dash/sh (Debian /bin/sh → dash).
  if [ -x "$MERGE/bin/bash" ]; then shrel=/bin/bash
  elif [ -x "$MERGE/usr/bin/bash" ]; then shrel=/usr/bin/bash
  elif [ -x "$MERGE/bin/dash" ]; then shrel=/bin/dash
  elif [ -x "$MERGE/bin/sh" ]; then shrel=/bin/sh
  fi

  CHROOT=`command -v chroot 2>/dev/null || echo /system/bin/chroot`
  [ -x "$CHROOT" ] || CHROOT=/system/bin/chroot
  if [ ! -x "$CHROOT" ]; then
    log "FATAL no chroot binary"
    return 1
  fi

  # Env for the admin shell inside Debian merge (PATH: agent · user · debian · android)
  # Never trust HOME=/ or empty — agents report home=//bin when this breaks.
  # Prefer /data/data (canonical). /data/user/0 is often unreadable (mode 700) for app UID.
  _heal_atlas_user_layout
  _AH=`_atlas_home_real`
  case "${ATLAS_HOME:-${HOME:-}}" in
    ""|"/"|"/root"|"/data") ;;
    *)
      # honor explicit HOME only if app-owned and exists
      _cand="${ATLAS_HOME:-$HOME}"
      if [ -d "$_cand" ]; then
        _ou=`stat -c %u "$_cand" 2>/dev/null`
        [ -n "$_ou" ] && [ "$_ou" != "0" ] && _AH="$_cand"
      fi
      ;;
  esac
  [ -d "$_AH" ] || _AH=/data/data/com.titanus2.atlas/files
  _AB="${ATLAS_BIN:-$_AH/bin}"
  case "$_AB" in
    ""|"/bin"|"//bin") _AB="$_AH/bin" ;;
  esac
  mkdir -p "$_AH/bin" "$_AH/.local/bin" "$_AH/.cargo/bin" \
    "$_AH/.npm-global/bin" "$_AH/.grok/bin" "$_AH/reports" "$_AH/etc" 2>/dev/null || true
  # Heal root-owned HOME after crash/su thrash (admin drop must read .bash_* / write resolv).
  # Symptom: Permission denied on $HOME/.bash_env + HOME/etc/resolv.conf → "android mode" feel.
  _heal_atlas_home "$_AH" "$DROP"
  # Re-resolve after heal (shadow may have been replaced)
  _AH=`_atlas_home_real`
  [ -d "$_AH" ] || _AH=/data/data/com.titanus2.atlas/files
  _AB="$_AH/bin"
  # PATH order (critical):
  #  1) Debian real tools first (/usr/bin apt, nano) — never shadow with Android wrappers
  #  2) user installs (.local, .grok, $HOME/bin) — curl-installed grok etc.
  #  3) ATLAS_BIN — agent sudo/su only
  #  4) Android system bins last
  # NOTE: $HOME/bin has apt/apt-get wrappers for Android shell — must be AFTER /usr/bin
  _USER_PATH="$_AH/.local/bin:$_AH/.cargo/bin:$_AH/.npm-global/bin:$_AH/.grok/bin:$_AH/bin"
  _PATH="/usr/local/sbin:/usr/local/bin:/atlas-bin:/usr/sbin:/usr/bin:/sbin:/bin:$_USER_PATH:$_AB:/system/bin:/system/xbin:/vendor/bin:/product/bin"

  export ATLAS_HYBRID=1 ATLAS_COMBINED=1
  export HOME="$_AH" ATLAS_HOME="$_AH" ATLAS_BIN="$_AB"
  # Pin grok store to app files — never let sessions scatter under cwd=/ or wrong HOME.
  export GROK_HOME="$_AH/.grok"
  export ATLAS_AUTH_DIR="${ATLAS_AUTH_DIR:-$_AH/auth}"
  export SUDO_ASKPASS="${SUDO_ASKPASS:-$_AB/atlas-auth-askpass}"
  export USER=admin LOGNAME=admin ATLAS_ROLE=admin
  export TERM="${TERM:-xterm-256color}"
  export LANG="${LANG:-C.UTF-8}"
  export COLORTERM="${COLORTERM:-truecolor}"
  export PATH="$_PATH"
  export ANDROID_ROOT=/system ANDROID_DATA=/data TMPDIR=/tmp
  # Refresh DNS on every enter so VPN connect/disconnect is respected live
  atlas_apply_android_dns 2>/dev/null || true
  export BASH_ENV="${BASH_ENV:-}"
  # Only set BASH_ENV if admin can read it (else bash: Permission denied spam)
  if [ -r "$_AH/.bash_env" ]; then
    export BASH_ENV="$_AH/.bash_env"
  else
    unset BASH_ENV 2>/dev/null || true
  fi
  # CRITICAL: never set Debian LD_LIBRARY_PATH in hybrid shell (outer OR post-su).
  # Hybrid PATH mixes Bionic Android bins (nanobot NDK, /system/bin/sh, am, …)
  # with Debian glibc tools. If LD_LIBRARY_PATH points at /usr/lib/aarch64-linux-gnu,
  # Bionic linker64 loads Debian libm.so / libc.so which are GNU *ld scripts*
  # (text: "/* GNU ld script") → CANNOT LINK EXECUTABLE bad ELF magic (2f2a2047).
  # Debian ld.so already searches /lib /usr/lib via conf — no LD_LIBRARY_PATH needed.
  # Lab 2026-08-10 (su enter) + 2026-08-10 (grok/nanobot in Deb shell).
  unset LD_LIBRARY_PATH 2>/dev/null || true
  unset LD_PRELOAD 2>/dev/null || true
  [ -n "${SSL_CERT_FILE:-}" ] && export SSL_CERT_FILE
  [ -n "${SSL_CERT_DIR:-}" ] && export SSL_CERT_DIR

  log "enter chroot+su admin=$DROP shell=$shrel"

  # Prefer /data/data path (always present); /data/user/0 needs rbind (fixed above).
  if [ ! -d "$_AH" ] && [ -d /data/data/com.titanus2.atlas/files ]; then
    _AH=/data/data/com.titanus2.atlas/files
    _AB=$_AH/bin
  fi

  # Env fragment for Debian shell *after* Bionic su has already linked.
  # Keep LD_LIBRARY_PATH empty forever in hybrid admin shell.
  _DEB_ENV="
      export HOME='$_AH' ATLAS_HOME='$_AH' ATLAS_BIN='$_AB' GROK_HOME='$_AH/.grok'
      export ATLAS_HYBRID=1 ATLAS_COMBINED=1 ATLAS_ROLE=admin USER=admin LOGNAME=admin
      export PATH='$_PATH'
      export ATLAS_AUTH_DIR='$_AH/auth'
      export SUDO_ASKPASS='$_AB/atlas-auth-askpass'
      export TERM='${TERM:-xterm-256color}' LANG='${LANG:-C.UTF-8}' COLORTERM='${COLORTERM:-truecolor}'
      export ANDROID_ROOT=/system ANDROID_DATA=/data TMPDIR=/tmp
      unset LD_LIBRARY_PATH LD_PRELOAD 2>/dev/null || true
      [ -f '$_AH/cacert.pem' ] && export SSL_CERT_FILE='$_AH/cacert.pem'
      unset BASH_ENV
  "

  # KernelSU su resets PATH — always re-export hybrid env inside -c.
  # Outer env must stay free of Debian LD_LIBRARY_PATH (see unset above).
  # Login shell
  if [ "$#" -eq 0 ] || [ "$1" = "-l" ] || [ "$1" = "--login" ]; then
    exec env -u LD_LIBRARY_PATH -u LD_PRELOAD \
      "$CHROOT" "$MERGE" /system/bin/su "$DROP" -s "$shrel" -c "
      $_DEB_ENV
      # Never start at / — grok keys sessions by cwd; cwd=/ orphaned history under sessions/%2F
      cd '$_AH' 2>/dev/null || cd \"\$HOME\" 2>/dev/null || true
      exec '$shrel' -l
    "
  fi

  if [ "$1" = "-lc" ]; then
    shift
    cmd="$*"
    exec env -u LD_LIBRARY_PATH -u LD_PRELOAD \
      "$CHROOT" "$MERGE" /system/bin/su "$DROP" -s "$shrel" -c \
      "$_DEB_ENV cd '$_AH' 2>/dev/null; $cmd"
  fi

  exec env -u LD_LIBRARY_PATH -u LD_PRELOAD \
    "$CHROOT" "$MERGE" /system/bin/su "$DROP" -s "$shrel" -c \
    "$_DEB_ENV cd '$_AH' 2>/dev/null; $*"
}

cmd_enter() {
  # Product: prefer setuid atlas-enter (works from app without Magisk).
  if [ -x /system/bin/atlas-enter ]; then
    DROP="${ATLAS_DROP_UID:-}"
    if [ -z "$DROP" ] || [ "$DROP" = "0" ]; then
      DROP=`stat -c %u /data/data/com.titanus2.atlas 2>/dev/null \
        || stat -c %u /data/user/0/com.titanus2.atlas 2>/dev/null || true`
    fi
    AH="${ATLAS_HOME:-${HOME:-/data/data/com.titanus2.atlas/files}}"
    log "enter via /system/bin/atlas-enter drop=$DROP"
    if [ -n "$DROP" ] && [ "$DROP" != "0" ]; then
      exec /system/bin/atlas-enter --uid "$DROP" --home "$AH" --ensure --
    fi
    exec /system/bin/atlas-enter --home "$AH" --ensure --
  fi
  need_root || return 1
  if ! is_bootstrapped; then
    log "not bootstrapped — run once (slow): atlas-hybrid.sh bootstrap"
    return 1
  fi
  plane_defaults
  plane_write titan2_atlas_mode debian
  # Fast path: overlay already live → skip migrate/mount/bind
  if ! is_overlay_up; then
    migrate_flat_to_loop 2>/dev/null || true
    mount_overlay || return 1
    bind_android || return 1
  fi
  sh=`shell_bin_in "$MERGE"`
  log "enter combined OS ($sh) mode=debian storage=$(plane_read titan2_atlas_storage shared)"
  write_product_status
  _enter_exec "$sh" -l
}

cmd_run() {
  need_root || return 1
  if ! is_bootstrapped; then
    log "not bootstrapped — run once: atlas-hybrid.sh bootstrap"
    return 1
  fi
  if [ "$#" -eq 0 ]; then
    log "usage: hybrid run <cmd...>"
    return 2
  fi
  plane_defaults
  if ! is_overlay_up; then
    migrate_flat_to_loop 2>/dev/null || true
    mount_overlay || return 1
    bind_android || return 1
  fi
  sh=`shell_bin_in "$MERGE"`
  cmd="$*"
  _enter_exec "$sh" -lc "$cmd"
}

cmd_mode() {
  plane_defaults
  case "${1:-status}" in
    status|"")
      echo "mode=$(plane_read titan2_atlas_mode android)"
      ;;
    android)
      plane_write titan2_atlas_mode android
      plane_write titan2_atlas_desktop none
      # keep overlay mounted so debian tools remain on PATH for Atlas android session
      if need_root 2>/dev/null; then
        mount_overlay 2>/dev/null || true
        bind_android 2>/dev/null || true
      fi
      echo "mode=android (UI plane; combined tools still at $MERGE if mounted)"
      echo "path_hint=export PATH=$MERGE/usr/bin:$MERGE/bin:\$PATH"
      ;;
    debian|linux)
      need_root || return 1
      if ! is_bootstrapped; then
        log "bootstrap first: hybrid bootstrap"
        return 1
      fi
      plane_write titan2_atlas_mode debian
      mount_overlay || return 1
      bind_android || return 1
      echo "mode=debian (combined OS up). enter: hybrid enter"
      ;;
    *) log "mode: android|debian|status"; return 2 ;;
  esac
}

cmd_storage() {
  plane_defaults
  case "${1:-status}" in
    status|"") echo "storage=$(plane_read titan2_atlas_storage shared)" ;;
    shared)
      plane_write titan2_atlas_storage shared
      echo "storage=shared (default — one disk, both modes)"
      ;;
    isolated)
      plane_write titan2_atlas_storage isolated
      echo "storage=isolated (opt-in — fewer binds on next enter)"
      ;;
    *) log "storage: shared|isolated|status"; return 2 ;;
  esac
}

cmd_trust() {
  plane_defaults
  case "${1:-status}" in
    status|"") echo "trust=$(plane_read titan2_atlas_trust normal)" ;;
    on|trusted)
      plane_write titan2_atlas_trust trusted
      echo "trust=trusted — Android app containers may share via groups more freely"
      echo "constitution: mic/camera still fail-closed"
      ;;
    off|normal)
      plane_write titan2_atlas_trust normal
      echo "trust=normal — sandbox default per app"
      ;;
    *) log "trust: on|off|status"; return 2 ;;
  esac
}

cmd_pad() {
  plane_defaults
  case "${1:-status}" in
    status|"") echo "pad=$(plane_read titan2_pad_mode off)" ;;
    trackpad|mouse|off)
      plane_write titan2_pad_mode "$1"
      echo "pad=$1 (synced Android+Linux)"
      ;;
    *) log "pad: trackpad|mouse|off|status"; return 2 ;;
  esac
}

cmd_destroy() {
  need_root || return 1
  log "destroy hybrid (overlay + loop + image)"
  umount_overlay
  umount_loop
  rm -rf "$ROOT" 2>/dev/null || true
  rm -f "$IMG" 2>/dev/null || true
  # leave LEGACY unless force
  if [ "${1:-}" = "all" ]; then
    rm -rf "$LEGACY" 2>/dev/null || true
  fi
  plane_write titan2_atlas_mode android
  log "destroyed — mode=android"
}

# Actual *logical* image size in GiB (ceil). 0 if missing.
# Sparse files: du -m is allocated size (wrong). Use stat %s via awk (not 32-bit $(( )).
img_size_g() {
  [ -f "$IMG" ] || { echo 0; return; }
  g=$(stat -c %s "$IMG" 2>/dev/null | awk '{
    if ($1+0 <= 0) { print 0; exit }
    print int(($1 + 1073741823) / 1073741824)
  }')
  [ -n "$g" ] && echo "$g" || echo 0
}

# Grow sparse image + resize2fs. Shrink is NOT supported (use rebuild --wipe).
# Usage: resize | resize <GiB>
cmd_resize() {
  need_root || return 1
  target="${1:-$SIZE_G}"
  case "$target" in
    ''|*[!0-9]*) log "resize: need integer GiB (got '$target')"; return 2 ;;
  esac
  [ "$target" -ge 2 ] && [ "$target" -le 64 ] || {
    log "resize: GiB must be 2..64"; return 2
  }
  if [ ! -f "$IMG" ]; then
    log "resize: no image — bootstrap with ATLAS_HYBRID_SIZE_G=$target"
    export ATLAS_HYBRID_SIZE_G="$target"
    SIZE_G="$target"
    export ATLAS_AUTO_BOOTSTRAP=1
    cmd_ensure || return 1
    log "resize: created new image ${target}G"
    cmd_status
    return 0
  fi
  cur=$(img_size_g)
  if [ "$target" -lt "$cur" ]; then
    log "resize: refuse shrink ${cur}G → ${target}G (use rebuild --wipe for smaller)"
    echo "RESIZE_REFUSE_SHRINK cur=${cur}G want=${target}G"
    return 3
  fi
  if [ "$target" -eq "$cur" ]; then
    log "resize: already ${cur}G — remount only"
    cmd_ensure >/dev/null 2>&1 || true
    echo "RESIZE_SAME ${cur}G"
    return 0
  fi
  log "resize: grow ${cur}G → ${target}G (preserve data)"
  umount_overlay 2>/dev/null || true
  # drop loop so truncate can extend file
  if [ -n "${LOOPDEV:-}" ] && [ -b "$LOOPDEV" ]; then
    losetup -d "$LOOPDEV" 2>/dev/null || true
  fi
  # clear any loop attached to IMG
  for n in $(losetup -j "$IMG" 2>/dev/null | cut -d: -f1); do
    losetup -d "$n" 2>/dev/null || true
  done
  truncate -s ${target}G "$IMG" || {
    dd if=/dev/zero of="$IMG" bs=1M count=0 seek=$((target * 1024)) 2>/dev/null || {
      log "resize: truncate failed"
      return 1
    }
  }
  # attach, resize fs, detach, re-ensure
  LOOP=$(losetup -f --show "$IMG" 2>/dev/null) || {
    log "resize: losetup failed"
    return 1
  }
  e2fsck -fy "$LOOP" 2>/dev/null || true
  if ! resize2fs "$LOOP" 2>/dev/null; then
    log "resize: resize2fs failed — image may be larger than fs; keep file, remount"
  fi
  losetup -d "$LOOP" 2>/dev/null || true
  export ATLAS_FORCE_FSCK=1
  if ! cmd_ensure; then
    log "resize: ensure after grow failed"
    return 1
  fi
  log "resize OK now=$(img_size_g)G"
  echo "RESIZE_OK $(img_size_g)G"
  cmd_status
  return 0
}

# rebuild — product recovery for "hybrid not loading"
#   rebuild | rebuild --preserve   keep image + upper (apt/home) — e2fsck + remount + heal
#                                  (does NOT change image size — size is resize or --wipe)
#   rebuild --wipe                 destroy image then auto-bootstrap from seed (uses SIZE_G)
cmd_rebuild() {
  need_root || return 1
  mode=preserve
  case "${1:-}" in
    --wipe|wipe|full|destroy) mode=wipe ;;
    --preserve|preserve|"") mode=preserve ;;
    *) log "rebuild: --preserve (default) | --wipe"; return 2 ;;
  esac
  export ATLAS_AUTO_BOOTSTRAP="${ATLAS_AUTO_BOOTSTRAP:-1}"
  if [ "$mode" = "wipe" ]; then
    log "rebuild --wipe: destroy image then ensure SIZE_G=${SIZE_G}G (seed required if empty)"
    cmd_destroy
    rm -f /data/local/tmp/atlas-hybrid-need-fsck 2>/dev/null || true
    ATLAS_FORCE_FSCK=0 cmd_ensure || return 1
    log "rebuild wipe done size=$(img_size_g)G"
    cmd_status
    return 0
  fi
  log "rebuild --preserve: keep $IMG + upper — remount/heal only (no size change; use resize)"
  # Soft cycle: drop overlay so ensure re-attaches; never mke2fs / rm image
  umount_overlay 2>/dev/null || true
  # force journal check if dirty flag or always once on explicit rebuild
  touch /data/local/tmp/atlas-hybrid-need-fsck 2>/dev/null || true
  export ATLAS_FORCE_FSCK=1
  if ! cmd_ensure; then
    log "rebuild preserve: ensure failed — image kept (no wipe)"
    return 1
  fi
  cmd_heal 2>/dev/null || true
  rm -f /data/local/tmp/atlas-hybrid-need-fsck 2>/dev/null || true
  if is_overlay_up && is_bootstrapped; then
    log "rebuild preserve OK — overlay up size=$(img_size_g)G"
    cmd_status
    return 0
  fi
  log "rebuild preserve: still down after ensure"
  return 1
}

# PATH helper for android mode without pivot
cmd_path() {
  mount_overlay 2>/dev/null || true
  if is_overlay_up; then
    echo "export PATH=\"$MERGE/usr/local/bin:$MERGE/usr/bin:$MERGE/bin:/system/bin:/system/xbin:/vendor/bin:\$PATH\""
    echo "export ATLAS_HYBRID_MERGE=$MERGE"
  else
    echo "# overlay not up — hybrid mount first" >&2
    return 1
  fi
}

cmd="${1:-status}"
shift 2>/dev/null || true
case "$cmd" in
  status) cmd_status ;;
  bootstrap|init) cmd_bootstrap ;;
  mount|up) cmd_mount ;;
  ensure|install|boot) cmd_ensure ;;
  umount|down) cmd_umount "$@" ;;
  heal|fix) cmd_heal ;;
  enter|shell|login) cmd_enter ;;
  run) cmd_run "$@" ;;
  destroy|wipe) cmd_destroy "$@" ;;
  rebuild) cmd_rebuild "$@" ;;
  resize) cmd_resize "$@" ;;
  size) echo "pref=${SIZE_G}G actual=$(img_size_g)G img=$IMG" ;;
  mode) cmd_mode "${1:-status}" ;;
  storage) cmd_storage "${1:-status}" ;;
  trust) cmd_trust "${1:-status}" ;;
  pad) cmd_pad "${1:-status}" ;;
  path|env) cmd_path ;;
  unbind) need_root && unbind_android ;;
  bind) need_root && bind_android ;;
  version|-v|--version) echo "atlas-hybrid $VER overlay+loop combined" ;;
  help|-h|--help)
    cat <<EOF
Atlas hybrid ROM — Debian + Android on one plane
  status | ensure | bootstrap | mount | umount | heal | enter | run <cmd>
  rebuild [--preserve|--wipe] | resize [GiB] | size | destroy
  ensure   # product install/boot: mount, or auto-bootstrap when seed present
  rebuild --preserve  # remount/heal only — does NOT change image size
  rebuild --wipe      # destroy + bootstrap at ATLAS_HYBRID_SIZE_G (data loss)
  resize [GiB]        # grow only (truncate+resize2fs); shrink requires --wipe
  size                # print pref vs actual GiB
  mode android|debian|status
  storage shared|isolated|status
  trust on|off|status
  pad trackpad|mouse|off|status
  path   # print PATH export for android-mode debian tools

Boot: atlas-hybrid-boot.sh (sys.boot_completed) → ensure
Seed: /system/etc/atlas/*.tar.gz · app files/rootfs · /data/local/tmp/atlas-hybrid-dl
Engine: ext4 loop ($IMG) + overlayfs → $MERGE
SoT:    docs/project/ATLAS.md
EOF
    ;;
  *) log "unknown: $cmd (help)"; exit 2 ;;
esac
