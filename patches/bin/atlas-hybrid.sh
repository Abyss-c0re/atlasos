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
#     Android OS trees → merge/android/{system,vendor,product,apex}
#     NEVER bind Android /system over Debian /system
#       (ro Android image — Linux self-update writes into a brick)
#     NEVER bind_rbind full /data (nests hybrid → bin fail)
#
# Architecture (NOT chroot-first):
#   f2fs /data cannot host overlayfs upper → ext4 loop image
#   lower  = Debian 13 trixie rootfs (from ROM seed)
#   upper  = writable layer (apt installs)
#   work   = overlay workdir (same ext4)
#   merge  = Debian root + kernel vfs + /android/* (not a mixed /system)
#   enter  = enterd / pivot into merge
#   HOME   = /data/local/atlas-home/atlas → /home/atlas
#            never Atlas CE /data/data/com.titanus2.atlas/files
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

# App AuthWatch polls LP only. $HOME/auth is leftover from the old protocol.
# Deb chroot does not see /data/local/atlas-linux — only /var/lib/atlas-auth
# (same inode) plus selected binds. Point $HOME/auth at the Deb path.
join_home_auth_to_lp() {
  _lp="${ATLAS_AUTH_ON_LP:-/data/local/atlas-linux/var/lib/atlas-auth}"
  _deb="${ATLAS_AUTH_IN_DEB:-/var/lib/atlas-auth}"
  mkdir -p "$_lp" 2>/dev/null || true
  chmod 0777 "$_lp" 2>/dev/null || true
  for _h in "$ATLAS_LINUX_HOME" /home/atlas /data/local/atlas-home/atlas; do
    [ -n "$_h" ] && [ -d "$_h" ] || continue
    _a="$_h/auth"
    if [ -L "$_a" ]; then
      _t=`readlink "$_a" 2>/dev/null || true`
      case "$_t" in
        "$_deb") continue ;;
      esac
    elif [ -d "$_a" ]; then
      for _f in "$_a"/req.* "$_a"/ok.* "$_a"/fail.* "$_a"/busy.* "$_a"/ticket "$_a"/wake; do
        [ -e "$_f" ] || continue
        mv -f "$_f" "$_lp/" 2>/dev/null || true
      done
      rm -rf "$_a" 2>/dev/null || true
    fi
    ln -sfn "$_deb" "$_a" 2>/dev/null || true
  done
  unset _lp _deb _h _a _t _f
}

BASE_DISTRO="${ATLAS_DEBIAN_DISTRO:-debian}"
BASE_CODENAME="${ATLAS_DEBIAN_CODENAME:-trixie}"
BASE_ARCH="${ATLAS_DEBIAN_ARCH:-arm64}"
URL="${ATLAS_ROOTFS_URL:-https://cloud.debian.org/images/cloud/trixie/latest/debian-13-genericcloud-${BASE_ARCH}.tar.xz}"

LOWER="$ROOT/lower"
UPPER="$ROOT/upper"
WORK="$ROOT/work"
MERGE="$ROOT/merge"
MARKER="$ROOT/.atlas-hybrid"
VER=9
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
  # Already up
  if is_mounted "$LP_MNT" 2>/dev/null; then
    return 0
  fi
  if [ -x "$LPCTL" ]; then
    "$LPCTL" mount 2>/dev/null && return 0
  fi
  for c in /system/bin/atlas-lpctl /system_ext/bin/atlas-lpctl \
           /product/bin/atlas-lpctl /data/local/tmp/atlas-lpctl; do
    [ -x "$c" ] || continue
    "$c" mount 2>/dev/null && return 0
  done
  # Fallback: toybox mount (init-root). lpctl dies SIGSYS under shell/seccomp;
  # wipe-survive path must still work when init runs ensure/boot as root.
  mkdir -p "$LP_MNT" 2>/dev/null || true
  for d in /dev/block/mapper/atlas_linux_a /dev/block/mapper/atlas_linux \
           /dev/block/by-name/atlas_linux_a /dev/block/by-name/atlas_linux; do
    [ -b "$d" ] || continue
    if mount -t ext4 -o noatime "$d" "$LP_MNT" 2>/dev/null \
      || mount -t ext4 "$d" "$LP_MNT" 2>/dev/null; then
      log "lp_try_mount: toybox mount $d → $LP_MNT"
      return 0
    fi
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
    seed=`find_rootfs_tarball`
    if [ "${ATLAS_AUTO_BOOTSTRAP:-1}" = "1" ] && [ -n "$seed" ]; then
      log "LP mounted but empty — extract seed onto atlas_linux (wipe-survive rehydrate)"
      log "seed=$seed"
      if tar -C "$LP_MNT" -xzf "$seed" 2>/dev/null \
        || tar -C "$LP_MNT" -xf "$seed" 2>/dev/null; then
        mkdir -p "$LP_MNT/var/lib/atlas-auth" 2>/dev/null || true
        chmod 0777 "$LP_MNT/var/lib/atlas-auth" 2>/dev/null || true
      else
        log "LP seed extract failed"
        return 1
      fi
    fi
    lp_root_ready || {
      log "LP mounted but empty debian — leave for seed path"
      return 1
    }
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

  # This device is Titan. OpenWrt "BlackCube" must never own UTS or Debian.
  echo Titan2 >/proc/sys/kernel/hostname 2>/dev/null || true
  echo Titan2 >"$LP_MNT/etc/hostname" 2>/dev/null || true
  echo Titan2 >"$MERGE/etc/hostname" 2>/dev/null || true
  # Hive MCP is not product on this phone. Strip leftover seed.
  for _mcp in \
    "$ATLAS_LINUX_HOME/.nanobot/mcp_servers.json" \
    /data/local/atlas-home/atlas/.nanobot/mcp_servers.json \
    /data/data/com.titanus2.atlas/files/.nanobot/mcp_servers.json
  do
    [ -f "$_mcp" ] || continue
    if grep -q blackcube "$_mcp" 2>/dev/null || grep -q braincube "$_mcp" 2>/dev/null; then
      printf '%s\n' '{"servers":[]}' >"$_mcp" 2>/dev/null || rm -f "$_mcp"
    fi
  done

  bind_android 2>/dev/null || true
  heal_merge_essentials 2>/dev/null || true
  ensure_auth_plane_on_lp 2>/dev/null || true

  mkdir -p "$ATLAS_LINUX_HOME" "$ATLAS_LINUX_HOME/reports" 2>/dev/null || true
  if [ -x "$LPCTL" ]; then
    "$LPCTL" home-ensure 2>/dev/null || true
    "$LPCTL" auth-ensure 2>/dev/null || true
  fi
  # ROM oneshot plane — never tip-land chmod after flash (heresy).
  heal_wipe_first_boot_perms 2>/dev/null || true
  # Prefer Deb path after bind so installers mkdir -p ~/… and /home/atlas work.
  export HOME=/home/atlas
  export ATLAS_HOME=/home/atlas
  export ATLAS_LINUX_HOME_HOST="$ATLAS_LINUX_HOME"
  export ATLAS_AUTH_DIR="$ATLAS_AUTH_ON_LP"
  plane_write titan2_atlas_mode debian 2>/dev/null || true
  atlas_sandbox_reapply
  log "super LP debian up merge=$MERGE from $LP_MNT HOME=$ATLAS_LINUX_HOME AUTH=$ATLAS_AUTH_DIR"
  return 0
}

# Wipe-first-boot permission plane (product peels only).
# After factory wipe, dirs are recreated as root:root 0700 → Deb admin drop
# (app uid) cannot chdir HOME / write auth / apt tmp. Fix here on every ensure
# so the plane is correct without post-flash thrash.
# LAW: Deb installers use ~/ and /home/atlas. Real home lives on Android
# /data/local/atlas-home/atlas (wipe with userdata). LP stub /home/atlas is
# empty system:system — bind real home there or curl|bash / cargo / npm mkdir fail.
bind_linux_home() {
  need_root || return 1
  AU=`stat -c %u /data/data/com.titanus2.atlas 2>/dev/null \
    || stat -c %u /data/user/0/com.titanus2.atlas 2>/dev/null || true`
  mkdir -p "$ATLAS_LINUX_HOME" "$ATLAS_LINUX_HOME/reports" \
    "$ATLAS_LINUX_HOME/.local/bin" 2>/dev/null || true
  chmod 0755 /data/local /data/local/atlas-home "$ATLAS_LINUX_HOME" \
    "$ATLAS_LINUX_HOME/reports" "$ATLAS_LINUX_HOME/.local" \
    "$ATLAS_LINUX_HOME/.local/bin" 2>/dev/null || true
  if [ -n "$AU" ] && [ "$AU" != "0" ]; then
    chown "$AU:$AU" "$ATLAS_LINUX_HOME" "$ATLAS_LINUX_HOME/reports" \
      "$ATLAS_LINUX_HOME/.local" "$ATLAS_LINUX_HOME/.local/bin" 2>/dev/null || true
  fi
  # Bind into every Deb view of /home/atlas (LP direct + merge bind).
  for root in "$MERGE" "$LP_MNT" "$LOWER"; do
    [ -n "$root" ] && [ -d "$root" ] || continue
    mkdir -p "$root/home/atlas" 2>/dev/null || true
    if ! is_mounted "$root/home/atlas"; then
      mount --bind "$ATLAS_LINUX_HOME" "$root/home/atlas" 2>/dev/null \
        || mount -o bind "$ATLAS_LINUX_HOME" "$root/home/atlas" 2>/dev/null || true
    fi
    # Path used when HOME is still absolute Android path after chroot
    mkdir -p "$root/data/local/atlas-home" 2>/dev/null || true
    if [ -d /data/local/atlas-home ] && ! is_mounted "$root/data/local/atlas-home"; then
      mount --bind /data/local/atlas-home "$root/data/local/atlas-home" 2>/dev/null \
        || mount -o bind /data/local/atlas-home "$root/data/local/atlas-home" 2>/dev/null || true
    fi
  done
  # Install scripts (root or drop-uid) must create dirs under home
  chmod 0755 "$ATLAS_LINUX_HOME" 2>/dev/null || true
  # User tool dirs often land 0700 as app — keep o+x parents only; contents stay private
  return 0
}

heal_wipe_first_boot_perms() {
  AU=`stat -c %u /data/data/com.titanus2.atlas 2>/dev/null \
    || stat -c %u /data/user/0/com.titanus2.atlas 2>/dev/null || true`
  mkdir -p /data/local/atlas-home/atlas/reports \
    /data/local/atlas-home/atlas/.local/bin \
    /data/local/atlas-hybrid \
    "$ATLAS_LINUX_HOME" 2>/dev/null || true
  # Parents must be traversable (o+x) for non-root admin after drop.
  chmod 0755 /data/local /data/local/atlas-home /data/local/atlas-hybrid 2>/dev/null || true
  chmod 0755 /data/local/atlas-home/atlas 2>/dev/null || true
  chmod 0755 /data/local/atlas-home/atlas/reports \
    /data/local/atlas-home/atlas/.local \
    /data/local/atlas-home/atlas/.local/bin 2>/dev/null || true
  if [ -n "$AU" ] && [ "$AU" != "0" ]; then
    chown -R "$AU:$AU" /data/local/atlas-home/atlas 2>/dev/null || true
  fi
  ensure_auth_plane_on_lp 2>/dev/null || true
  if [ -x "$LPCTL" ]; then
    "$LPCTL" home-ensure 2>/dev/null || true
    "$LPCTL" auth-ensure 2>/dev/null || true
  fi
  bind_linux_home 2>/dev/null || true
  heal_sudo_root_ownership 2>/dev/null || true
  heal_apt_sources_single 2>/dev/null || true
  # sticky tmp — apt / dpkg Permission denied without 1777
  for t in \
    "$MERGE/tmp" "$MERGE/var/tmp" \
    "$LP_MNT/tmp" "$LP_MNT/var/tmp" \
    "$LOWER/tmp" "$LOWER/var/tmp"; do
    [ -d "$t" ] || continue
    chmod 1777 "$t" 2>/dev/null || true
  done
  chmod 0755 "$MERGE" "$LP_MNT" 2>/dev/null || true
  # app CE HOME heal (resolv, profiles) — same plane after wipe recreates package
  if [ -n "$AU" ] && [ "$AU" != "0" ]; then
    if [ -d /data/data/com.titanus2.atlas/files ]; then
      _heal_atlas_home /data/data/com.titanus2.atlas/files "$AU" 2>/dev/null || true
    fi
    if [ -d /data/user/0/com.titanus2.atlas/files ]; then
      _heal_atlas_home /data/user/0/com.titanus2.atlas/files "$AU" 2>/dev/null || true
    fi
  fi
  # DNS into Deb root (curl resolve) — must run as root ensure path
  atlas_apply_android_dns 2>/dev/null || true
  log "heal_wipe_first_boot_perms au=${AU:-none} home=$ATLAS_LINUX_HOME auth=$ATLAS_AUTH_ON_LP"
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
  join_home_auth_to_lp 2>/dev/null || true
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

# Android VPN / network DNS for Debian+musl tools (apt, curl, user ELFs).
# SoT restored from pre-super-LP product (git 1fd4404c + atlas-net.sh):
#   VPN DnsAddresses first (Tailscale MagicDNS 100.100.100.100, WG, …)
#   then Wi‑Fi/carrier INTERNET primary — NEVER pin 8.8.8.8 when tun is up.
# Super LP path also writes /data/local/atlas-linux/etc/resolv.conf (same FS as merge).
atlas_android_dns_body() {
  dump=`dumpsys connectivity 2>/dev/null` || dump=
  emit_ips() {
    tr ',' '\n' | sed 's|^[[:space:]]*/*||;s|[[:space:]]||g' | \
      grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$|^[0-9a-fA-F:]+$'
  }
  pick() {
    mode="$1"  # vpn|net
    echo "$dump" | sed 's/NetworkAgentInfo{/\nNetworkAgentInfo{/g' | while IFS= read -r block; do
      [ -n "$block" ] || continue
      case "$block" in *DnsAddresses:*) ;; *) continue ;; esac
      if [ "$mode" = vpn ]; then
        # Strict: IS_VPN / ni{VPN — never match NOT_VPN substring.
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
    case "$ip" in
      127.*|0.0.0.0|::1) return 0 ;;
    esac
    case " $seen " in *" $ip "*) return 0 ;; esac
    seen="$seen $ip"; echo "nameserver $ip"; n=$((n + 1))
  }
  # 1) Android VPN first (MagicDNS / WG / Proton)
  for ip in `pick vpn`; do add "$ip"; done
  [ "$n" -gt 0 ] || {
    # 2) Non-VPN validated internet (Wi‑Fi / carrier)
    for ip in `pick net`; do add "$ip"; done
  }
  [ "$n" -gt 0 ] || {
    # 3) Global DnsAddresses fallback (format thrash residual)
    if [ -n "$dump" ]; then
      for ip in `echo "$dump" | grep -oE 'DnsAddresses: \[[^]]*\]' | tr ',[]/' ' ' | tr -s ' ' '\n' | \
        grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'`; do
        add "$ip"
        [ "$n" -ge 3 ] && break
      done
    fi
  }
  [ "$n" -gt 0 ] || {
    # 4) Props / DHCP
    for p in net.dns1 net.dns2 net.dns3 net.dns4 \
             dhcp.wlan0.dns1 dhcp.wlan0.dns2 dhcp.eth0.dns1 dhcp.eth0.dns2; do
      v=`getprop "$p" 2>/dev/null | tr -d '\r'`
      [ -n "$v" ] && add "$v"
    done
  }
  if [ "$n" -eq 0 ]; then
    # 5) Refuse public DNS when any tun is up (would bypass VPN)
    if ip -o link show 2>/dev/null | grep -q ' tun[0-9][0-9]*:'; then
      return 1
    fi
    echo "nameserver 8.8.8.8"
    echo "nameserver 1.1.1.1"
  fi
  # Search domains from Android (ts.net / lan — MagicDNS + LAN short names)
  if [ -n "$dump" ]; then
    doms=`echo "$dump" | grep -oE 'Domains: [^[:cntrl:]]+' | head -1 | \
      sed 's/^Domains:[[:space:]]*//;s/[[:space:]]*MTU:.*//;s/[[:space:]]*ServerAddress:.*//;s/[[:space:]]*$//'`
    case "$doms" in
      ''|null|NULL) ;;
      *)
        # only domain tokens
        doms=`echo "$doms" | tr ' ' '\n' | grep -E '^[A-Za-z0-9._-]+$' | tr '\n' ' ' | sed 's/[[:space:]]*$//'`
        [ -n "$doms" ] && echo "search $doms"
        ;;
    esac
  fi
  return 0
}

# Write Android-VPN-aware resolv.conf into hybrid tree + app HOME.
# Must run as root (ensure/enter). Never called from app-UID atlas-net for merge.
atlas_apply_android_dns() {
  body=`atlas_android_dns_body 2>/dev/null` || body=
  [ -n "$body" ] || return 0
  for dest in \
    "$MERGE/etc/resolv.conf" \
    "$LOWER/etc/resolv.conf" \
    "$LP_MNT/etc/resolv.conf" \
    /data/local/atlas-linux/etc/resolv.conf \
    /data/data/com.titanus2.atlas/files/etc/resolv.conf \
    /data/local/atlas-home/atlas/etc/resolv.conf \
    /data/local/tmp/atlas-dns/resolv.conf
  do
    ddir=`dirname "$dest" 2>/dev/null`
    [ -n "$ddir" ] || continue
    case "$ddir" in *$'\n'*|*Toybox*) continue ;; esac
    safe_mkdir_p "$ddir" || continue
    [ -d "$ddir" ] || continue
    # Subshell so shell "can't create" never hits the PTY
    ( printf '%s\n' "$body" >"$dest" ) >/dev/null 2>&1 || true
    chmod 644 "$dest" 2>/dev/null || true
  done
  AU=`stat -c %u /data/data/com.titanus2.atlas 2>/dev/null || true`
  if [ -n "$AU" ] && [ "$AU" != "0" ]; then
    chown "$AU:$AU" /data/data/com.titanus2.atlas/files/etc/resolv.conf 2>/dev/null || true
    chown "$AU:$AU" /data/local/atlas-home/atlas/etc/resolv.conf 2>/dev/null || true
  fi
  log "apply_android_dns nss=$(echo "$body" | tr '\n' ' ')"
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

# Refuse mkdir of toybox/help text (empty dirname → junk name in $HOME).
safe_mkdir_p() {
  d="$1"
  [ -n "$d" ] || return 1
  case "$d" in
    *$'\n'*|*Toybox*|*usage:*|*multicall*) return 1 ;;
    .|..|./|../) return 1 ;;
  esac
  mkdir -p "$d" 2>/dev/null || return 1
}

# Drop plane-junk names left by Android dirname/help captured as mkdir.
drop_hybrid_junk() {
  for root in \
    "${ATLAS_LINUX_HOME:-/data/local/atlas-home/atlas}" \
    /data/local/atlas-home/atlas \
    "$MERGE/home/atlas" \
    "$LP_MNT/home/atlas"
  do
    [ -n "$root" ] && [ -d "$root" ] || continue
    find "$root" -maxdepth 1 \( -name 'Toybox*' -o -name 'usage:*' \) \
      -exec rm -rf {} + 2>/dev/null || true
    # Plane status is not user HOME
    rm -f "$root/ATLAS_STATUS" "$root/ATLAS_PLANE.env" 2>/dev/null || true
  done
}

# OpenSSH client exits 255 if any ssh_config include is not root-owned.
# LP seed keeps Android uid 1000 (system) on /etc/ssh — "SSH is broken".
# systemd ssh-proxy drop-in is useless here (no systemd user@).
heal_debian_ssh() {
  etc="$MERGE/etc/ssh"
  [ -d "$etc" ] || etc="$LP_MNT/etc/ssh"
  [ -d "$etc" ] || return 0
  rm -f "$etc/ssh_config.d/20-systemd-ssh-proxy.conf" 2>/dev/null || true
  chown -R 0:0 "$etc" 2>/dev/null || true
  chmod 755 "$etc" "$etc/ssh_config.d" 2>/dev/null || true
  chmod 644 "$etc/ssh_config" 2>/dev/null || true
  find "$etc/ssh_config.d" -type f -exec chmod 644 {} + 2>/dev/null || true
}

bind_one() {
  src="$1"
  dst="$2"
  [ -e "$src" ] || return 0
  safe_mkdir_p "$dst" || return 0
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
  # Debian client for enterd ELEVATE. Never plant the Bionic ELF here —
  # after Android lives at /android/system the interp /system/bin/linker64
  # is gone and `android` dies ENOENT (Grok 01a01703).
  cat >"$d/atlas-android" <<'EOF'
#!/bin/bash
# Deb → enterd ELEVATE (Android plane). Not a Bionic ELF.
set -f
if [ $# -eq 0 ]; then
  echo "usage: android <tool|path> [args…]" >&2
  exit 2
fi
case "$1" in
  /*) ;;
  *) set -- "/system/bin/$1" "${@:2}" ;;
esac
cmd=""
for a in "$@"; do
  q=${a//\'/\'\\\'\'}
  cmd="${cmd:+$cmd }'$q'"
done
exec 3<>/dev/tcp/127.0.0.1/17999 || {
  echo "atlas-android: enterd not listening (127.0.0.1:17999)" >&2
  exit 4
}
printf 'ELEVATE chroot=0\n%s\n' "$cmd" >&3
code=4
while IFS= read -r line <&3; do
  case "$line" in
    OK) continue ;;
    ERR*) echo "atlas-android: $line" >&2; exec 3<&-; exit 3 ;;
    *__ATLAS_EXIT__*)
      code=${line##*__ATLAS_EXIT__ }
      break
      ;;
    *) printf '%s\n' "$line" ;;
  esac
done
exec 3<&-
exit "${code:-0}"
EOF
  chmod 755 "$d/atlas-android" 2>/dev/null || true
  ln -sfn /usr/local/libexec/atlas-android "$MERGE/usr/local/bin/android" 2>/dev/null || true
  ln -sfn /usr/local/libexec/atlas-android "$MERGE/usr/local/bin/android-exec" 2>/dev/null || true
  ln -sfn /usr/local/libexec/atlas-android "$MERGE/usr/local/bin/android-run" 2>/dev/null || true
  ln -sfn /usr/local/libexec/atlas-android "$d/atlas-android-exec" 2>/dev/null || true
  # Seeing is current — do not symlink screencap to the auth wrap.
  _sc=
    for s in /system/bin/atlas-screencap \
      /data/data/com.titanus2.atlas/files/bin/atlas-screencap \
      /data/data/com.titanus2.atlas/files/bin/atlas-screencap.sh; do
      [ -f "$s" ] && _sc=$s && break
    done
    if [ -n "$_sc" ]; then
      cp -f "$_sc" "$MERGE/usr/local/bin/atlas-screencap" 2>/dev/null || true
      chmod 755 "$MERGE/usr/local/bin/atlas-screencap" 2>/dev/null || true
    fi
  cat >"$d/atlas-android-exec" <<'EOF'
#!/bin/sh
# Run an Android (Bionic) binary from hybrid Debian root.
# Privilege plane (what is allowed) vs bio (optional enforcement):
#   titan2_atlas_priv_android_access=0 → DENY (privilege off)
#   titan2_atlas_priv_android_access=1 or missing → ALLOW execute
#   Bio only if: ATLAS_ANDROID_AUTH=1 OR titan2_atlas_android_auth=1
#     (Atlas publishes android_auth=1 only when master bio + bio Android access)
# Deb-internal tools (apt, bash, …) never go through this path.
set -f
# Prefer native wrap (auth + enterd elevate). Fall through only if missing.
for w in /usr/local/libexec/atlas-android /system/bin/atlas-android; do
  if [ -x "$w" ]; then
    exec "$w" "$@"
  fi
done
bin="$1"
shift
[ -n "$bin" ] || { echo "atlas-android-exec: missing binary" >&2; exit 2; }

_plane_val() {
  # first existing non-empty file wins
  for f in "$@"; do
    [ -f "$f" ] || continue
    v=`cat "$f" 2>/dev/null | tr -d '\r\n \t' | head -c 8`
    [ -n "$v" ] && { echo "$v"; return 0; }
  done
  echo ""
}

# Privilege: default ALLOW when plane unset (product: Deb may use android/screencap).
_priv_android_ok() {
  case "${ATLAS_PRIV_ANDROID_ACCESS:-}" in
    0|false|off|no|OFF) return 1 ;;
    1|true|on|yes|ON) return 0 ;;
  esac
  v=`_plane_val \
    /data/local/tmp/titan2_atlas_priv_android_access \
    /data/misc/titan2/titan2_atlas_priv_android_access \
    /var/lib/atlas-auth/titan2_atlas_priv_android_access`
  case "$v" in
    0|false|off|no) return 1 ;;
    *) return 0 ;;
  esac
}

# Bio enforcement: only when explicitly on (plane or env). Default OFF.
_bio_android_want() {
  case "${ATLAS_ANDROID_AUTH:-0}" in
    1|true|on|yes|ON) return 0 ;;
  esac
  v=`_plane_val \
    /data/local/tmp/titan2_atlas_android_auth \
    /data/misc/titan2/titan2_atlas_android_auth \
    /var/lib/atlas-auth/android_auth_on \
    /data/local/tmp/titan2_atlas_bio_android_access \
    /data/misc/titan2/titan2_atlas_bio_android_access`
  case "$v" in 1|true|on|yes) return 0 ;; esac
  return 1
}

# Light-image sandbox: no Android host (am/pm/screencap/nsenter) regardless of USER.
_sb=`_plane_val \
  /data/local/tmp/titan2_atlas_seat_sandbox \
  /data/misc/titan2/titan2_atlas_seat_sandbox \
  /var/lib/atlas-auth/titan2_atlas_seat_sandbox`
case "$_sb" in
  1|true|on|yes|ON)
    echo "atlas-android-exec: privilege denied (sandbox)" >&2
    exit 1 ;;
esac

if ! _priv_android_ok; then
  echo "atlas-android-exec: privilege denied (Android access off in Atlas Settings)" >&2
  exit 1
fi

# Per-identity gate: shared Atlas user may be denied Android even if global on.
_who="${ATLAS_LOGIN:-${USER:-${LOGNAME:-}}}"
_uperm="/var/lib/atlas-auth/users/${_who}/android"
[ -f "$_uperm" ] || _uperm="/data/local/atlas-linux/var/lib/atlas-auth/users/${_who}/android"
if [ -n "$_who" ] && [ -f "$_uperm" ]; then
  _uv=`cat "$_uperm" 2>/dev/null | tr -d '\r\n \t' | head -c 8`
  case "$_uv" in
    0|false|off|no)
      echo "atlas-android-exec: privilege denied (user $_who Android off)" >&2
      exit 1 ;;
  esac
fi

if _bio_android_want; then
  AUTH=""
  for a in /system/bin/atlas-auth \
    "${ATLAS_SYSBIN:-}/atlas-auth" \
    /usr/local/bin/atlas-auth \
    /bin/atlas-auth; do
    [ -x "$a" ] && AUTH=$a && break
  done
  if [ -n "$AUTH" ]; then
    base=`basename "$bin"`
    "$AUTH" request --scope "$base" "android $base" || {
      echo "atlas-android-exec: biometric denied for android $base" >&2
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
  # Same-name Android IPC wrappers first on PATH (/usr/local/bin).
  # Deb binderfs is empty — these must not be raw /system/bin ELFs.
  wrapbin="$MERGE/usr/local/libexec/atlas-android"
  # Keep the Debian enterd client. Never replace with Bionic ELF.
  if [ -x "$wrapbin" ]; then
    ln -sfn /usr/local/libexec/atlas-android "$MERGE/usr/local/bin/android" 2>/dev/null || true
  fi
  cat >"$MERGE/usr/local/libexec/atlas-android-hint" <<'EOF'
#!/bin/sh
echo "use: android ${0##*/} $*" >&2
echo "Debian cannot see Android. Run: atlas-agent-status" >&2
exit 64
EOF
  chmod 755 "$MERGE/usr/local/libexec/atlas-android-hint" 2>/dev/null || true
  for t in getprop setprop am pm cmd dumpsys service screencap screenshot \
    input wm settings logcat content atlas-screencap; do
    rm -f "$MERGE/usr/local/bin/$t" "$MERGE/atlas-bin/$t" 2>/dev/null || true
    ln -sfn /usr/local/libexec/atlas-android-hint "$MERGE/usr/local/bin/$t" 2>/dev/null || true
  done
  # User-managed: replace Deb ELF with symlink → atlas-wrap → atlas-auth
  _apply=""
  for s in \
    /data/local/tmp/atlas-managed-apply.sh \
    /data/data/com.titanus2.atlas/files/bin/atlas-managed-apply.sh \
    /system/bin/atlas-managed-apply.sh
  do
    [ -f "$s" ] && _apply=$s && break
  done
  if [ -n "$_apply" ]; then
    sh "$_apply" 2>/dev/null || true
  fi
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
# Deb product HOME: bound linux home (not CE files, not empty LP stub)
export ATLAS_LINUX_HOME="${ATLAS_LINUX_HOME:-/data/local/atlas-home/atlas}"
case "${HOME:-}" in
  ""|"/"|"/root"|"/data"|/data/data/*|/data/user/*)
    if [ -d /home/atlas ] && [ -x /home/atlas ]; then
      export HOME=/home/atlas
    elif [ -d "$ATLAS_LINUX_HOME" ]; then
      export HOME="$ATLAS_LINUX_HOME"
    fi
    ;;
esac
export ATLAS_HOME="${ATLAS_HOME:-$HOME}"
export ATLAS_BIN="${ATLAS_BIN:-$HOME/bin}"
# Debian + user-install PATH only. Android is /android/* via `android`.
# Putting /system/bin on this PATH made Linux CLIs pick a ro Android prefix.
_atlas_up=
for _h in "$HOME" "$ATLAS_LINUX_HOME" /home/atlas; do
  [ -n "$_h" ] && [ -d "$_h" ] || continue
  [ -d "$_h/bin" ] && _atlas_up="${_atlas_up:+$_atlas_up:}$_h/bin"
  [ -d "$_h/.local/bin" ] && _atlas_up="${_atlas_up:+$_atlas_up:}$_h/.local/bin"
  for _d in "$_h"/.*; do
    [ -d "$_d/bin" ] || continue
    case "$_d" in */.|*/..|*/.local) continue ;; esac
    _atlas_up="${_atlas_up:+$_atlas_up:}$_d/bin"
  done
done
export PATH="${_atlas_up:+$_atlas_up:}/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
unset ANDROID_ROOT ANDROID_DATA ANDROID_STORAGE 2>/dev/null || true
unset _atlas_up _h _d
export ATLAS_REPORTS="${HOME}/reports"
if [ -n "${PS1:-}" ] && [ -z "${ATLAS_MOTD_SHOWN:-}" ]; then
  export ATLAS_MOTD_SHOWN=1
  echo "Atlas: Debian cannot see Android."
  echo "Bridge: android <cmd>   files: android cat|write|ls   status: atlas-agent-status"
fi
case "${PS1:-}" in
  *debian*|*android*) ;;
  *) PS1='\[\e[1;36m\]debian\[\e[0m\]:atlas\$ ' ;;
esac
EOF
  chmod 644 "$MERGE/etc/profile.d/zz-atlas-plane.sh" 2>/dev/null || true
}


# LAW: never overlay Android partitions onto Debian /system (or /vendor…).
# KEEP_DATA leftovers from VER<=7 must be torn down on every ensure.
unbind_android_os_over_debian() {
  for d in \
    "$MERGE/system" "$MERGE/vendor" "$MERGE/product" "$MERGE/system_ext" \
    "$MERGE/apex" "$MERGE/linkerconfig" "$MERGE/bootstrap-apex" \
    "$LP_MNT/system" "$LP_MNT/vendor" "$LP_MNT/product" "$LP_MNT/system_ext" \
    "$LP_MNT/apex" "$LP_MNT/linkerconfig" "$LP_MNT/bootstrap-apex"
  do
    [ -n "$d" ] || continue
    if is_mounted "$d" || grep -q " $d " /proc/mounts 2>/dev/null; then
      umount -l "$d" 2>/dev/null || true
    fi
  done
}

# Android OS visible under /android/* only. Bridge: `android <cmd>`.
bind_android_os() {
  mkdir -p "$MERGE/android/system" "$MERGE/android/vendor" \
    "$MERGE/android/product" "$MERGE/android/system_ext" \
    "$MERGE/android/apex" 2>/dev/null || true
  bind_one /system "$MERGE/android/system"
  bind_one /vendor "$MERGE/android/vendor"
  bind_one /product "$MERGE/android/product"
  bind_one /system_ext "$MERGE/android/system_ext"
  bind_rbind /apex "$MERGE/android/apex"
  if [ -d /linkerconfig ]; then
    mkdir -p "$MERGE/android/linkerconfig" 2>/dev/null || true
    bind_rbind /linkerconfig "$MERGE/android/linkerconfig"
  fi
  if [ -d /bootstrap-apex ]; then
    mkdir -p "$MERGE/android/bootstrap-apex" 2>/dev/null || true
    bind_rbind /bootstrap-apex "$MERGE/android/bootstrap-apex"
  fi
}

# User tools live on $HOME PATH. Root symlinks in /usr make self-update hit a
# system path. Drop leftovers that point at linux home (any tool, not one CLI).
drop_user_home_promoted_to_usr() {
  for root in "$MERGE" "$LP_MNT"; do
    [ -n "$root" ] && [ -d "$root/usr/local/bin" ] || continue
    for f in "$root/usr/local/bin"/*; do
      [ -L "$f" ] || continue
      t=`readlink "$f" 2>/dev/null || true`
      case "$t" in
        /data/local/atlas-home/*|/home/atlas/*)
          rm -f "$f" 2>/dev/null || true
          ;;
      esac
    done
  done
}

# Live kernel vfs + /android/* + selected /data. Debian /system stays Debian.
bind_android() {
  storage=`plane_read titan2_atlas_storage shared`
  [ -d "$MERGE" ] || return 1
  is_mounted "$MERGE" || mount_overlay || return 1
  unbind_android_os_over_debian
  drop_user_home_promoted_to_usr
  drop_hybrid_junk
  heal_debian_ssh

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
  bind_android_os

  if [ "$storage" = "shared" ]; then
    # Heal multi-user layout BEFORE bind so chroot sees a usable app HOME.
    _heal_atlas_user_layout 2>/dev/null || true
    # NEVER bind_rbind full /data → nests $ROOT inside merge (merge/data/local/atlas-hybrid/…)
    # and shadows Debian usrmerge (bin→usr/bin gone → "bin fail" / hybrid-down).
    # Bind only app + tmp planes Deb needs.
    # Debian does not see Atlas CE. App wipe is not the Deb plane.
    mkdir -p "$MERGE/data/local/tmp" "$MERGE/data/misc" 2>/dev/null || true
    if [ -d /data/local/tmp ]; then
      bind_one /data/local/tmp "$MERGE/data/local/tmp"
    fi
    if [ -d /data/misc/titan2 ]; then
      mkdir -p "$MERGE/data/misc/titan2" 2>/dev/null || true
      bind_one /data/misc/titan2 "$MERGE/data/misc/titan2"
    fi
    # Prefer selective /mnt — full rbind also re-enters hybrid under mnt paths.
    mkdir -p "$MERGE/mnt" 2>/dev/null || true
    _st=`plane_read titan2_atlas_storage ask`
    if [ -f "$LP_MNT/var/lib/atlas-auth/policy" ]; then
      _ps=`grep '^storage=' "$LP_MNT/var/lib/atlas-auth/policy" 2>/dev/null | head -1 | cut -d= -f2`
      [ -n "$_ps" ] && _st=$_ps
    fi
    case "$_st" in
      allow|1|shared)
        bind_rbind /storage "$MERGE/storage"
        if [ -d /sdcard ] || [ -L /sdcard ]; then
          bind_one /sdcard "$MERGE/sdcard"
        fi
        ;;
      *)
        mkdir -p "$MERGE/etc/atlas" 2>/dev/null || true
        printf '%s\n' \
          "Android user files are not mounted here." \
          "use: android cat|write|ls <path>" \
          >"$MERGE/etc/atlas/BRIDGE" 2>/dev/null || true
        ;;
    esac
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
    bind_android_os
    mkdir -p "$MERGE/data/local/tmp" 2>/dev/null || true
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

# Android IPC must not appear as Deb PATH names (Grok tries screencap first).
is_android_ipc_name() {
  case "$1" in
    screencap|screenshot|atlas-screencap|am|pm|cmd|dumpsys|getprop|setprop|\
    logcat|input|wm|settings|service|content|app_process|app_process64) return 0 ;;
    *) return 1 ;;
  esac
}

# LAW: one apt source SoT — classic /etc/apt/sources.list only.
# trixie-slim deb822 debian.sources + our sources.list → yellow apt spam
# "Target Packages configured multiple times". Product peels, not tip.
heal_apt_sources_single() {
  for root in "$MERGE" "$LOWER" "$LP_MNT"; do
    [ -n "$root" ] && [ -d "$root/etc/apt" ] || continue
    # Drop deb822 duplicates (any *.sources under list.d)
    if [ -d "$root/etc/apt/sources.list.d" ]; then
      rm -f "$root/etc/apt/sources.list.d/debian.sources" \
            "$root/etc/apt/sources.list.d/"*.sources 2>/dev/null || true
    fi
    # Ensure classic list exists (trixie product pin)
    if [ ! -s "$root/etc/apt/sources.list" ]; then
      cat >"$root/etc/apt/sources.list" <<'EOF'
deb http://deb.debian.org/debian trixie main contrib non-free non-free-firmware
deb http://deb.debian.org/debian trixie-updates main contrib non-free non-free-firmware
deb http://security.debian.org/debian-security trixie-security main contrib non-free non-free-firmware
EOF
    fi
    chown 0:0 "$root/etc/apt/sources.list" 2>/dev/null || true
    chmod 0644 "$root/etc/apt/sources.list" 2>/dev/null || true
  done
  return 0
}

# LAW: sudo refuses elevate if /etc/sudoers|/etc/sudo.conf not owned by uid 0.
# Host pack / mkfs / Android "system"(1000) remaps left files as uid 1000 after
# wipe ship → apt→sudo.real fails: "owned by uid 1000, should be 0" (lab UI).
heal_sudo_root_ownership() {
  for root in "$MERGE" "$LOWER" "$LP_MNT"; do
    [ -n "$root" ] && [ -d "$root/etc" ] || continue
    for f in "$root/etc/sudoers" "$root/etc/sudo.conf"; do
      [ -e "$f" ] || continue
      chown 0:0 "$f" 2>/dev/null || true
    done
    [ -f "$root/etc/sudoers" ] && chmod 0440 "$root/etc/sudoers" 2>/dev/null || true
    [ -f "$root/etc/sudo.conf" ] && chmod 0644 "$root/etc/sudo.conf" 2>/dev/null || true
    if [ -d "$root/etc/sudoers.d" ]; then
      chown 0:0 "$root/etc/sudoers.d" 2>/dev/null || true
      chmod 0750 "$root/etc/sudoers.d" 2>/dev/null || true
      # chown files only (not follow weird links)
      for f in "$root/etc/sudoers.d"/*; do
        [ -f "$f" ] || continue
        chown 0:0 "$f" 2>/dev/null || true
        chmod 0440 "$f" 2>/dev/null || true
      done
    fi
    if [ -d "$root/usr/libexec/sudo" ]; then
      chown -R 0:0 "$root/usr/libexec/sudo" 2>/dev/null || true
    fi
    for sbin in \
      "$root/usr/bin/sudo.real" "$root/bin/sudo.real" \
      "$root/usr/bin/sudo" "$root/bin/sudo" \
      "$root/usr/bin/sudoreplay"
    do
      [ -f "$sbin" ] || continue
      head -1 "$sbin" 2>/dev/null | grep -q '^#!' && continue
      chown 0:0 "$sbin" 2>/dev/null || true
      case "$sbin" in
        */sudo.real|*/sudo) chmod 4755 "$sbin" 2>/dev/null || true ;;
        *) chmod 755 "$sbin" 2>/dev/null || true ;;
      esac
    done
  done
  return 0
}

# Install Authentication Agent clients as the only PATH-visible su/sudo.
# Real Debian setuid lives at /usr/bin/sudo.real (agent calls it after biometrics).
ensure_agent_elevate_gates() {
  [ -d "$MERGE" ] || return 0
  heal_sudo_root_ownership 2>/dev/null || true
  heal_apt_sources_single 2>/dev/null || true
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

  # Agent client is the ROM ELF. Never Atlas CE files (Settings wipe is not Debian).
  gate=""
  for c in \
    /system/bin/atlas-sudo \
    /system/xbin/atlas-sudo \
    "${ATLAS_BIN:-}/atlas-sudo"
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
    case "$dir" in ''|*$'\n'*|*Toybox*) continue ;; esac
    safe_mkdir_p "$dir" || continue
    cat >"$dest" <<'EOF'
#!/bin/sh
# Elevate via enterd (Deb cannot exec Bionic /system/bin/atlas-sudo).
AND=/usr/local/libexec/atlas-android
[ -x "$AND" ] || AND=/usr/local/bin/android
if [ -x "$AND" ]; then
  exec "$AND" /system/bin/atlas-sudo "$@"
fi
echo "atlas-sudo: android/enterd bridge missing" >&2
exit 127
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
  chmod 0755 "$dest" 2>/dev/null || true
  stamp="$dest/.stamp"
  if [ -z "${ATLAS_RELINK:-}" ] && [ -f "$stamp" ]; then
    # Still reinstall elevate gates (cheap, law must hold)
    ensure_agent_elevate_gates
    chmod 0755 "$dest" 2>/dev/null || true
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
  wrap=/usr/local/libexec/atlas-android
  [ -x /system/bin/atlas-android ] && wrap=/system/bin/atlas-android
  [ -x "$MERGE/usr/local/libexec/atlas-android" ] && wrap=/usr/local/libexec/atlas-android
  cat >"$shim" <<EOF
#!/bin/sh
# argv0 name → atlas-android wrap (discover + auth + elevate).
exec ${wrap} "\$@"
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
  # Android bins are NOT Debian PATH names. Bridge: `android <cmd>`.
  # Optional user CLIs stay on $HOME PATH (do not promote into /usr).
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
  for h in \
    "${ATLAS_LINUX_HOME:-/data/local/atlas-home/atlas}" \
    /home/atlas \
    /data/local/atlas-home/atlas
  do
    [ -n "$h" ] && [ -d "$h" ] && echo "$h" && return 0
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
  # any user tool that uses ~/.tool/bin
  for d in "$h"/.* /dev/null; do
    [ -d "$d/bin" ] || continue
    case "$d" in
      */.local|*/.cargo|*/.npm-global) continue ;; # already listed
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
  # PATH already scans $HOME/bin, .local/bin, ~/.*/bin.
  # Never promote user ELFs into /usr or /atlas-bin (self-update hits a system path).
  drop_user_home_promoted_to_usr
  return 0
}

unbind_android() {
  unbind_android_os_over_debian
  for d in \
    "$MERGE/android/bootstrap-apex" "$MERGE/android/linkerconfig" \
    "$MERGE/android/apex" "$MERGE/android/system_ext" \
    "$MERGE/android/product" "$MERGE/android/vendor" "$MERGE/android/system" \
    "$MERGE/android" \
    "$MERGE/data/local/tmp" "$MERGE/mnt" "$MERGE/storage" "$MERGE/sdcard" \
    "$MERGE/data" \
    "$MERGE/dev/pts" "$MERGE/dev" "$MERGE/proc" "$MERGE/sys"
  do
    if is_mounted "$d" || grep -q " $d " /proc/mounts 2>/dev/null; then
      umount -R "$d" 2>/dev/null || umount -l "$d" 2>/dev/null || true
    fi
  done
}

# Map Android app UID → Debian user so ssh/sudo/apt resolve the live PTY uid.
# Live proof 2026-08-14: session uid 10101 with only admin@10198 →
#   "No user exists for uid 10101" / sudo hang / apt wrapper fail.
# Always ensure a passwd row for the *current* app uid (name: atlas),
# not only "add admin if that name is missing".
ensure_admin_user() {
  uid="${ATLAS_DROP_UID:-}"
  [ -z "$uid" ] || [ "$uid" = "0" ] && \
    uid=`stat -c %u /data/data/com.titanus2.atlas 2>/dev/null \
      || stat -c %u /data/user/0/com.titanus2.atlas 2>/dev/null || echo 10198`
  home="${ATLAS_LINUX_HOME:-/data/local/atlas-home/atlas}"
  [ -d "$home" ] || home=/home/atlas
  roots="$MERGE $LOWER"
  [ -d /data/local/atlas-linux/etc ] && roots="$roots /data/local/atlas-linux"
  for root in $roots; do
    [ -d "$root/etc" ] || continue
    pw="$root/etc/passwd"
    gr="$root/etc/group"
    sh="$root/etc/shadow"
    # Live uid must exist (ssh/sudo/nss). One name: atlas at app uid.
    if [ -f "$pw" ]; then
      if grep -q "^atlas:" "$pw" 2>/dev/null; then
        sed -i "s#^atlas:[^:]*:[^:]*:[^:]*:#atlas:x:${uid}:${uid}:#" "$pw" 2>/dev/null || true
      else
        echo "atlas:x:${uid}:${uid}:Atlas:${home}:/bin/bash" >>"$pw"
      fi
      # One identity: atlas. Drop leftover admin alias (same uid).
      sed -i "/^admin:/d" "$pw" 2>/dev/null || true
      sed -i "/^atlas${uid}:/d" "$pw" 2>/dev/null || true
    fi
    if [ -f "$gr" ]; then
      sed -i "/^admin:/d" "$gr" 2>/dev/null || true
      if grep -q "^atlas:" "$gr" 2>/dev/null; then
        sed -i "s#^atlas:x:[^:]*:#atlas:x:${uid}:#" "$gr" 2>/dev/null || true
      else
        echo "atlas:x:${uid}:" >>"$gr"
      fi
    fi
    if [ -f "$sh" ]; then
      sed -i "/^admin:/d" "$sh" 2>/dev/null || true
      if ! grep -q "^atlas:" "$sh" 2>/dev/null; then
        echo "atlas:!:19600:0:99999:7:::" >>"$sh"
      fi
    fi
    # sudo group membership for atlas (optional; sudoers names uid too)
    if [ -f "$gr" ] && grep -q "^sudo:" "$gr" 2>/dev/null; then
      if ! grep -q "^sudo:.*atlas" "$gr" 2>/dev/null; then
        sed -i "s/^sudo:\\([^:]*\\):\\([^:]*\\):\\(.*\\)/sudo:\\1:\\2:\\3,atlas/" "$gr" 2>/dev/null \
          || true
      fi
    fi
    mkdir -p "$root/etc/sudoers.d" 2>/dev/null || true
    printf 'atlas ALL=(ALL) NOPASSWD:ALL\n# uid %s same rights when listed by number\nDefaults:%s !authenticate\n%s ALL=(ALL) NOPASSWD:ALL\n' \
      "$uid" "$uid" "$uid" >"$root/etc/sudoers.d/atlas" 2>/dev/null || true
    rm -f "$root/etc/sudoers.d/atlas-admin" 2>/dev/null || true
    chown 0:0 "$root/etc/sudoers.d" "$root/etc/sudoers.d/atlas" 2>/dev/null || true
    chmod 0750 "$root/etc/sudoers.d" 2>/dev/null || true
    chmod 0440 "$root/etc/sudoers.d/atlas" 2>/dev/null || true
    for sbin in "$root/usr/bin/sudo.real" "$root/usr/bin/sudo"; do
      [ -f "$sbin" ] || continue
      if head -1 "$sbin" 2>/dev/null | grep -q '^#!'; then
        continue
      fi
      sz=`stat -c %s "$sbin" 2>/dev/null` || sz=0
      [ "$sz" -lt 20000 ] && continue
      chown 0:0 "$sbin" 2>/dev/null || true
      chmod 4755 "$sbin" 2>/dev/null || true
    done
  done
  heal_sudo_root_ownership 2>/dev/null || true
}

cmd_ensure_user() {
  need_root || return 1
  ensure_admin_user
  uid="${ATLAS_DROP_UID:-}"
  [ -z "$uid" ] || [ "$uid" = "0" ] && \
    uid=`stat -c %u /data/data/com.titanus2.atlas 2>/dev/null || echo 0`
  echo "ensure-user uid=$uid"
  for root in /data/local/atlas-linux "$MERGE" "$LOWER"; do
    [ -f "$root/etc/passwd" ] || continue
    grep ":x:${uid}:${uid}:" "$root/etc/passwd" 2>/dev/null && break
  done
}

# Create/update a Debian login: any name, optional password, atlas-auth sudo.
# Env: ATLAS_NEWUSER_PASS_B64 (optional) · ATLAS_USER_SUDO=1 (default)
cmd_add_user() {
  need_root || return 1
  name="${1:-}"
  echo "$name" | grep -Eq '^[a-z_][a-z0-9_-]{0,31}$' || {
    echo "error=bad-name"
    return 2
  }
  case "$name" in
    root|daemon|bin|sys|sync|games|man|lp|mail|news|uucp|proxy|www-data|\
    backup|list|irc|gnats|nobody|systemd-network|messagebus|sshd|_apt)
      echo "error=reserved"
      return 2 ;;
  esac
  app_uid=`stat -c %u /data/data/com.titanus2.atlas 2>/dev/null || echo 10101`
  pwfile=/data/local/atlas-linux/etc/passwd
  [ -f "$pwfile" ] || pwfile=/data/local/atlas-hybrid/merge/etc/passwd
  if [ "$name" = "atlas" ]; then
    uid="$app_uid"
  elif grep -q "^${name}:" "$pwfile" 2>/dev/null; then
    uid=`awk -F: -v n="$name" '$1==n {print $3; exit}' "$pwfile"`
  else
    uid=10102
    while grep -q ":x:${uid}:" "$pwfile" 2>/dev/null; do
      uid=$((uid + 1))
    done
  fi
  home="/data/local/atlas-home/$name"
  mkdir -p "$home"
  chmod 0755 "$home" 2>/dev/null || true
  sudo_on="${ATLAS_USER_SUDO:-1}"
  roots="/data/local/atlas-linux"
  [ -d /data/local/atlas-hybrid/merge/etc ] && roots="$roots /data/local/atlas-hybrid/merge"
  for root in $roots; do
    [ -d "$root/etc" ] || continue
    if [ -f "$root/etc/passwd" ] && ! grep -q "^${name}:" "$root/etc/passwd"; then
      echo "${name}:x:${uid}:${uid}:Atlas ${name}:${home}:/bin/bash" >>"$root/etc/passwd"
    fi
    if [ -f "$root/etc/group" ] && ! grep -q "^${name}:" "$root/etc/group"; then
      echo "${name}:x:${uid}:" >>"$root/etc/group"
    fi
    if [ -f "$root/etc/shadow" ] && ! grep -q "^${name}:" "$root/etc/shadow"; then
      echo "${name}:!:19600:0:99999:7:::" >>"$root/etc/shadow"
    fi
    if [ "$sudo_on" = "1" ]; then
      if [ -f "$root/etc/group" ] && grep -q "^sudo:" "$root/etc/group"; then
        if ! grep -q "^sudo:.*${name}" "$root/etc/group"; then
          sed -i "s/^sudo:\\([^:]*\\):\\([^:]*\\):\\(.*\\)/sudo:\\1:\\2:\\3,${name}/" \
            "$root/etc/group" 2>/dev/null || true
        fi
      fi
      mkdir -p "$root/etc/sudoers.d"
      printf '%s ALL=(ALL) NOPASSWD:ALL\nDefaults:%s !authenticate\n%s ALL=(ALL) NOPASSWD:ALL\n' \
        "$name" "$uid" "$uid" >"$root/etc/sudoers.d/atlas-${name}"
      chown 0:0 "$root/etc/sudoers.d/atlas-${name}" 2>/dev/null || true
      chmod 0440 "$root/etc/sudoers.d/atlas-${name}"
    fi
    mkdir -p "$root/home/$name" 2>/dev/null || true
    if [ -d "$root/home/$name" ] && ! grep -q " $root/home/$name " /proc/mounts; then
      mount --bind "$home" "$root/home/$name" 2>/dev/null \
        || mount -o bind "$home" "$root/home/$name" 2>/dev/null || true
    fi
  done
  # Login password optional. Empty/lock → atlas-auth only (no unix secret).
  pass=""
  pass_state=lock
  if [ -n "${ATLAS_NEWUSER_PASS_B64:-}" ]; then
    pass=`printf '%s' "$ATLAS_NEWUSER_PASS_B64" | base64 -d 2>/dev/null` || pass=""
  fi
  if [ -n "$pass" ]; then
    if [ -x /data/local/atlas-linux/usr/sbin/chpasswd ]; then
      printf '%s:%s\n' "$name" "$pass" | chroot /data/local/atlas-linux /usr/sbin/chpasswd
    elif [ -x /data/local/atlas-hybrid/merge/usr/sbin/chpasswd ]; then
      printf '%s:%s\n' "$name" "$pass" | chroot /data/local/atlas-hybrid/merge /usr/sbin/chpasswd
    fi
    pass_state=set
    pass=""
    unset pass
  fi
  unset ATLAS_NEWUSER_PASS_B64
  # atlas-auth in this user's login (sudo askpass + LP ticket dir)
  for prof in "$home/.profile" "$home/.bashrc"; do
    if [ ! -f "$prof" ] || ! grep -q ATLAS_AUTH_DIR "$prof" 2>/dev/null; then
      {
        echo "# Atlas auth — regenerated"
        echo "export ATLAS_AUTH_DIR=/var/lib/atlas-auth"
        echo "export ATLAS_AUTH_ON_LP=/data/local/atlas-linux/var/lib/atlas-auth"
        echo "export SUDO_ASKPASS=\"\${ATLAS_BIN:-/data/data/com.titanus2.atlas/files/bin}/atlas-auth-askpass\""
        echo "export PATH=\"\$HOME/bin:\$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin:\$PATH\""
      } >>"$prof"
    fi
  done
  chown -R "${uid}:${uid}" "$home" 2>/dev/null || true
  chmod 0755 "$home" 2>/dev/null || true
  android_on="${ATLAS_USER_ANDROID:-1}"
  debian_on="${ATLAS_USER_DEBIAN:-1}"
  atlas_user_write_perm "$name" android "$android_on"
  atlas_user_write_perm "$name" debian "$debian_on"
  atlas_user_write_perm "$name" sudo "$sudo_on"
  echo "user=$name uid=$uid home=$home sudo=$sudo_on android=$android_on debian=$debian_on pass=$pass_state"
  echo "auth=atlas-auth shared=1"
}

atlas_user_roots() {
  roots="/data/local/atlas-linux"
  [ -d /data/local/atlas-hybrid/merge/etc ] && roots="$roots /data/local/atlas-hybrid/merge"
  echo "$roots"
}

atlas_user_pwfile() {
  if [ -f /data/local/atlas-linux/etc/passwd ]; then
    echo /data/local/atlas-linux/etc/passwd
  else
    echo /data/local/atlas-hybrid/merge/etc/passwd
  fi
}

atlas_user_valid() {
  name="${1:-}"
  echo "$name" | grep -Eq '^[a-z_][a-z0-9_-]{0,31}$' || return 2
  case "$name" in
    root|daemon|bin|sys|sync|games|man|lp|mail|news|uucp|proxy|www-data|\
    backup|list|irc|gnats|nobody|systemd-network|messagebus|sshd|_apt)
      return 3 ;;
  esac
  return 0
}

atlas_user_app_uid() {
  stat -c %u /data/data/com.titanus2.atlas 2>/dev/null || echo 10101
}

cmd_list_users() {
  pw=`atlas_user_pwfile`
  [ -f "$pw" ] || { echo "users="; return 1; }
  shad=`dirname "$pw"`/shadow
  app_uid=`atlas_user_app_uid`
  # Always surface Debian root so Atlas Settings can set its password.
  if grep -q "^root:" "$pw" 2>/dev/null; then
    rpass=lock
    if [ -f "$shad" ]; then
      rh=`awk -F: '$1=="root"{print $2; exit}' "$shad"`
      case "$rh" in \$*) rpass=set ;; esac
    fi
    echo "name=root uid=0 home=/root pass=$rpass sudo=1 session=0 android=0 debian=1"
  fi
  awk -F: -v shad="$shad" -v app="$app_uid" '
    $3>=1000 && $3<65000 {
      name=$1; uid=$3; home=$6
      pass="lock"
      if (shad != "") {
        cmd="awk -F: -v n=\"" name "\" '\''$1==n {print $2}'\'' " shad
        cmd | getline hash
        close(cmd)
        if (hash ~ /^\$/) pass="set"
        else if (hash == "" || hash == "!" || hash == "*" || hash ~ /^!/) pass="lock"
      }
      sudo=0
      session=(uid==app)?1:0
      printf "name=%s uid=%s home=%s pass=%s sudo=%s session=%s\n", name, uid, home, pass, sudo, session
    }' "$pw" | while read -r line; do
      name=`echo "$line" | sed -n 's/.*name=\([^ ]*\).*/\1/p'`
      sudo=0
      for root in `atlas_user_roots`; do
        [ -f "$root/etc/sudoers.d/atlas-${name}" ] && sudo=1
        grep -q "^sudo:.*${name}" "$root/etc/group" 2>/dev/null && sudo=1
      done
      android=`atlas_user_read_perm "$name" android 1`
      debian=`atlas_user_read_perm "$name" debian 1`
      echo "$line" | sed "s/sudo=[01]/sudo=$sudo/" | tr -d '\n'
      echo " android=$android debian=$debian"
    done
}

cmd_set_pass() {
  need_root || return 1
  name="${1:-}"
  # root is reserved for add/delete; password set is product (Atlas Settings).
  if [ "$name" != "root" ]; then
    atlas_user_valid "$name" || { echo "error=bad-name"; return 2; }
  fi
  pw=`atlas_user_pwfile`
  grep -q "^${name}:" "$pw" 2>/dev/null || { echo "error=no-user"; return 2; }
  pass=""
  if [ -n "${ATLAS_NEWUSER_PASS_B64:-}" ]; then
    pass=`printf '%s' "$ATLAS_NEWUSER_PASS_B64" | base64 -d 2>/dev/null` || pass=""
  fi
  [ -n "$pass" ] || { echo "error=empty-pass"; return 2; }
  if [ -x /data/local/atlas-linux/usr/sbin/chpasswd ]; then
    printf '%s:%s\n' "$name" "$pass" | chroot /data/local/atlas-linux /usr/sbin/chpasswd
  elif [ -x /data/local/atlas-hybrid/merge/usr/sbin/chpasswd ]; then
    printf '%s:%s\n' "$name" "$pass" | chroot /data/local/atlas-hybrid/merge /usr/sbin/chpasswd
  else
    echo "error=no-chpasswd"
    unset pass ATLAS_NEWUSER_PASS_B64
    return 1
  fi
  unset pass ATLAS_NEWUSER_PASS_B64
  echo "user=$name pass=set auth=atlas-auth"
}

cmd_lock_pass() {
  need_root || return 1
  name="${1:-}"
  atlas_user_valid "$name" || { echo "error=bad-name"; return 2; }
  for root in `atlas_user_roots`; do
    [ -f "$root/etc/shadow" ] || continue
    if grep -q "^${name}:" "$root/etc/shadow"; then
      sed -i "s/^${name}:[^:]*:/${name}:!:/" "$root/etc/shadow"
    fi
  done
  echo "user=$name pass=lock"
}

cmd_set_sudo() {
  need_root || return 1
  name="${1:-}"
  on="${2:-1}"
  atlas_user_valid "$name" || { echo "error=bad-name"; return 2; }
  pw=`atlas_user_pwfile`
  grep -q "^${name}:" "$pw" 2>/dev/null || { echo "error=no-user"; return 2; }
  uid=`awk -F: -v n="$name" '$1==n {print $3; exit}' "$pw"`
  for root in `atlas_user_roots`; do
    [ -d "$root/etc" ] || continue
    if [ "$on" = "1" ]; then
      if [ -f "$root/etc/group" ] && grep -q "^sudo:" "$root/etc/group"; then
        if ! grep -q "^sudo:.*${name}" "$root/etc/group"; then
          sed -i "s/^sudo:\\([^:]*\\):\\([^:]*\\):\\(.*\\)/sudo:\\1:\\2:\\3,${name}/" \
            "$root/etc/group" 2>/dev/null || true
        fi
      fi
      mkdir -p "$root/etc/sudoers.d"
      printf '%s ALL=(ALL) NOPASSWD:ALL\nDefaults:%s !authenticate\n%s ALL=(ALL) NOPASSWD:ALL\n' \
        "$name" "$uid" "$uid" >"$root/etc/sudoers.d/atlas-${name}"
      chown 0:0 "$root/etc/sudoers.d/atlas-${name}" 2>/dev/null || true
      chmod 0440 "$root/etc/sudoers.d/atlas-${name}"
    else
      rm -f "$root/etc/sudoers.d/atlas-${name}"
      if [ -f "$root/etc/group" ]; then
        sed -i "s/,${name}//g; s/:${name},/:/; s/:${name}$/:/" "$root/etc/group" 2>/dev/null || true
      fi
    fi
  done
  echo "user=$name sudo=$on auth=atlas-auth"
}

cmd_del_user() {
  need_root || return 1
  name="${1:-}"
  atlas_user_valid "$name" || { echo "error=bad-name"; return 2; }
  pw=`atlas_user_pwfile`
  grep -q "^${name}:" "$pw" 2>/dev/null || { echo "error=no-user"; return 2; }
  uid=`awk -F: -v n="$name" '$1==n {print $3; exit}' "$pw"`
  app_uid=`atlas_user_app_uid`
  if [ "$uid" = "$app_uid" ]; then
    echo "error=session-user"
    return 2
  fi
  for root in `atlas_user_roots`; do
    umount "$root/home/$name" 2>/dev/null || umount -l "$root/home/$name" 2>/dev/null || true
    [ -f "$root/etc/passwd" ] && sed -i "/^${name}:/d" "$root/etc/passwd"
    [ -f "$root/etc/shadow" ] && sed -i "/^${name}:/d" "$root/etc/shadow"
    [ -f "$root/etc/group" ] && sed -i "/^${name}:/d" "$root/etc/group"
    [ -f "$root/etc/group" ] && sed -i "s/,${name}//g; s/:${name},/:/; s/:${name}$/:/" "$root/etc/group"
    rm -f "$root/etc/sudoers.d/atlas-${name}"
  done
  if [ "${ATLAS_DEL_HOME:-0}" = "1" ]; then
    rm -rf "/data/local/atlas-home/$name"
  fi
  rm -rf "/data/local/atlas-linux/var/lib/atlas-auth/users/$name"
  echo "deleted=$name uid=$uid"
}

atlas_user_auth_dir() {
  echo "/data/local/atlas-linux/var/lib/atlas-auth/users/${1}"
}

atlas_user_write_perm() {
  name="$1"
  key="$2"
  val="$3"
  d=`atlas_user_auth_dir "$name"`
  mkdir -p "$d"
  printf '%s\n' "$val" >"$d/$key"
  chmod 0644 "$d/$key" 2>/dev/null || true
}

atlas_user_read_perm() {
  name="$1"
  key="$2"
  def="${3:-1}"
  f=`atlas_user_auth_dir "$name"`/"$key"
  if [ -f "$f" ]; then
    tr -d '\r\n \t' <"$f" | head -c 8
  else
    echo "$def"
  fi
}

cmd_set_perm() {
  need_root || return 1
  name="${1:-}"
  key="${2:-}"
  val="${3:-1}"
  atlas_user_valid "$name" || { echo "error=bad-name"; return 2; }
  case "$key" in
    android|debian|sudo) ;;
    *) echo "error=bad-perm"; return 2 ;;
  esac
  case "$val" in 0|1) ;; *) echo "error=bad-val"; return 2 ;; esac
  atlas_user_write_perm "$name" "$key" "$val"
  if [ "$key" = "sudo" ]; then
    cmd_set_sudo "$name" "$val" >/dev/null
  fi
  echo "user=$name $key=$val"
}

SEAT_ROOT=/data/local/atlas-seats
# Reboot-persistent backups (userdata, not tmp). LP mirror survives extra /data/local cleanups.
BACKUP_ROOT=/data/local/atlas-backups
BACKUP_LP=/data/local/atlas-linux/var/lib/atlas-backups

atlas_seat_dir() {
  echo "$SEAT_ROOT/${1}"
}

atlas_seat_read() {
  name="$1"
  key="$2"
  def="${3:-0}"
  f=`atlas_seat_dir "$name"`/seat.prop
  [ -f "$f" ] || { echo "$def"; return; }
  awk -F= -v k="$key" '$1==k {print $2; found=1; exit} END{if(!found) print d}' d="$def" "$f"
}

atlas_seat_write() {
  name="$1"
  key="$2"
  val="$3"
  d=`atlas_seat_dir "$name"`
  mkdir -p "$d/snaps"
  f="$d/seat.prop"
  touch "$f"
  if grep -q "^${key}=" "$f" 2>/dev/null; then
    sed -i "s/^${key}=.*/${key}=${val}/" "$f"
  else
    echo "${key}=${val}" >>"$f"
  fi
}

cmd_seat_status() {
  name="${1:-}"
  atlas_user_valid "$name" || { echo "error=bad-name"; return 2; }
  pw=`atlas_user_pwfile`
  grep -q "^${name}:" "$pw" 2>/dev/null || { echo "error=no-user"; return 2; }
  uid=`awk -F: -v n="$name" '$1==n {print $3; exit}' "$pw"`
  shell=`awk -F: -v n="$name" '$1==n {print $7; exit}' "$pw"`
  sb=`atlas_seat_read "$name" sandbox 0`
  fr=`atlas_seat_read "$name" frozen 0`
  ls=`atlas_seat_read "$name" last_save none`
  android=`atlas_user_read_perm "$name" android 1`
  debian=`atlas_user_read_perm "$name" debian 1`
  sudo=0
  [ -f /data/local/atlas-linux/etc/sudoers.d/atlas-${name} ] && sudo=1
  nsnap=0
  _sd=`atlas_seat_dir "$name"`/snaps
  if [ -d "$_sd" ]; then
    nsnap=`ls -1 "$_sd" 2>/dev/null | wc -l`
  fi
  layer=down
  if grep -q " $MERGE overlay " /proc/mounts 2>/dev/null \
      || grep -q "overlay $MERGE " /proc/mounts 2>/dev/null; then
    layer=up
  fi
  echo "name=$name uid=$uid sandbox=$sb frozen=$fr snaps=$nsnap last_save=$ls android=$android debian=$debian sudo=$sudo shell=$shell layer=$layer home=/data/local/atlas-home/$name"
}

# Light image (LXC-class): shared kernel + shared LP lower + private ext4 upper.
# f2fs /data cannot host overlay upper — per-seat loop file is the writable image.
SANDBOX_LAYER_G="${ATLAS_SANDBOX_LAYER_G:-2}"

atlas_sandbox_layer_dir() {
  echo "$SEAT_ROOT/${1}/layer"
}

atlas_sandbox_home() {
  echo "$SEAT_ROOT/${1}/home"
}

atlas_sandbox_active_name() {
  for d in "$SEAT_ROOT"/*/seat.prop; do
    [ -f "$d" ] || continue
    n=$(basename "$(dirname "$d")")
    [ "$(atlas_seat_read "$n" sandbox 0)" = "1" ] && { echo "$n"; return 0; }
  done
  return 1
}

atlas_sandbox_merge_is_overlay() {
  grep -q " $MERGE overlay " /proc/mounts 2>/dev/null \
    || grep -q "overlay $MERGE " /proc/mounts 2>/dev/null
}

atlas_sandbox_layer_ensure() {
  name="$1"
  img="$SEAT_ROOT/$name/layer.img"
  mnt=$(atlas_sandbox_layer_dir "$name")
  mkdir -p "$SEAT_ROOT/$name" "$mnt"
  if [ ! -f "$img" ]; then
    if command -v truncate >/dev/null 2>&1; then
      truncate -s "${SANDBOX_LAYER_G}G" "$img" || return 1
    else
      dd if=/dev/zero of="$img" bs=1M count=0 seek=$((SANDBOX_LAYER_G * 1024)) 2>/dev/null || return 1
    fi
    mk=0
    for t in /system/bin/mkfs.ext4 /system/bin/mke2fs /sbin/mkfs.ext4; do
      if [ -x "$t" ]; then
        if [ "$(basename "$t")" = "mke2fs" ]; then
          "$t" -t ext4 -F -q "$img" && mk=1 && break
        else
          "$t" -F -q "$img" && mk=1 && break
        fi
      fi
    done
    [ "$mk" = "1" ] || { rm -f "$img"; echo "error=mkfs"; return 1; }
  fi
  if ! grep -q " $mnt " /proc/mounts 2>/dev/null; then
    mount -o loop "$img" "$mnt" 2>/dev/null \
      || mount -t ext4 -o loop "$img" "$mnt" 2>/dev/null || {
      echo "error=loop"
      return 1
    }
  fi
  mkdir -p "$mnt/upper" "$mnt/work"
  return 0
}

atlas_sandbox_bind_home() {
  name="$1"
  shome=$(atlas_sandbox_home "$name")
  alias="$SEAT_ROOT/$name/alias"
  mkdir -p "$shome" "$alias/atlas"
  if [ -z "$(ls -A "$shome" 2>/dev/null)" ] && [ -d "/data/local/atlas-home/$name" ]; then
    cp -a "/data/local/atlas-home/$name/." "$shome/" 2>/dev/null || true
  fi
  uid=$(awk -F: -v n="$name" '$1==n {print $3; exit}' "$(atlas_user_pwfile)")
  [ -n "$uid" ] && chown -R "${uid}:${uid}" "$shome" 2>/dev/null || true
  if ! is_mounted "$alias/atlas"; then
    mount --bind "$shome" "$alias/atlas" 2>/dev/null || true
  fi
  for tgt in "$MERGE/home/$name" "$MERGE/home/atlas"; do
    mkdir -p "$tgt" 2>/dev/null || true
    if is_mounted "$tgt"; then
      umount "$tgt" 2>/dev/null || umount -l "$tgt" 2>/dev/null || true
    fi
    mount --bind "$shome" "$tgt" 2>/dev/null || true
  done
  mkdir -p "$MERGE/data/local/atlas-home" 2>/dev/null || true
  if is_mounted "$MERGE/data/local/atlas-home"; then
    umount "$MERGE/data/local/atlas-home" 2>/dev/null \
      || umount -l "$MERGE/data/local/atlas-home" 2>/dev/null || true
  fi
  mount --bind "$alias" "$MERGE/data/local/atlas-home" 2>/dev/null || true
}

atlas_sandbox_up() {
  name="$1"
  [ -d "$LP_MNT" ] || { echo "error=no-lp"; return 1; }
  atlas_sandbox_layer_ensure "$name" || return 1
  mnt=$(atlas_sandbox_layer_dir "$name")
  if atlas_sandbox_merge_is_overlay; then
    atlas_sandbox_bind_home "$name"
    plane_write titan2_atlas_seat_sandbox 1
    return 0
  fi
  unbind_android 2>/dev/null || true
  if is_mounted "$MERGE"; then
    umount "$MERGE" 2>/dev/null || umount -l "$MERGE" 2>/dev/null || true
  fi
  mkdir -p "$MERGE" "$mnt/upper" "$mnt/work"
  mount -t overlay overlay \
    -o "lowerdir=$LP_MNT,upperdir=$mnt/upper,workdir=$mnt/work" \
    "$MERGE" || {
    echo "error=overlay"
    mount --bind "$LP_MNT" "$MERGE" 2>/dev/null || true
    bind_android 2>/dev/null || true
    return 1
  }
  bind_android 2>/dev/null || true
  write_android_exec_helpers 2>/dev/null || true
  atlas_sandbox_bind_home "$name"
  plane_write titan2_atlas_seat_sandbox 1
  return 0
}

atlas_sandbox_down() {
  name="$1"
  unbind_android 2>/dev/null || true
  for tgt in "$MERGE/home/$name" "$MERGE/home/atlas" "$MERGE/data/local/atlas-home"; do
    if is_mounted "$tgt"; then
      umount "$tgt" 2>/dev/null || umount -l "$tgt" 2>/dev/null || true
    fi
  done
  alias="$SEAT_ROOT/$name/alias/atlas"
  if is_mounted "$alias"; then
    umount "$alias" 2>/dev/null || umount -l "$alias" 2>/dev/null || true
  fi
  if atlas_sandbox_merge_is_overlay; then
    umount "$MERGE" 2>/dev/null || umount -l "$MERGE" 2>/dev/null || true
  fi
  mkdir -p "$MERGE"
  if ! is_mounted "$MERGE"; then
    mount --bind "$LP_MNT" "$MERGE" 2>/dev/null \
      || mount -o bind "$LP_MNT" "$MERGE" 2>/dev/null || true
  fi
  bind_android 2>/dev/null || true
  bind_linux_home 2>/dev/null || true
  plane_write titan2_atlas_seat_sandbox 0
}

atlas_sandbox_reapply() {
  n=$(atlas_sandbox_active_name) || return 0
  [ -n "$n" ] || return 0
  atlas_sandbox_up "$n" >/dev/null 2>&1 || true
}

cmd_seat_sandbox() {
  # Loop-image seats are not product. One LP Debian.
  echo "error=not-product"
  return 2
}

cmd_seat_freeze() {
  need_root || return 1
  name="${1:-}"
  atlas_user_valid "$name" || { echo "error=bad-name"; return 2; }
  app_uid=`atlas_user_app_uid`
  uid=`awk -F: -v n="$name" '$1==n {print $3; exit}' "$(atlas_user_pwfile)"`
  for root in `atlas_user_roots`; do
    umount "$root/home/$name" 2>/dev/null || umount -l "$root/home/$name" 2>/dev/null || true
    if [ "$uid" != "$app_uid" ] && [ -f "$root/etc/passwd" ]; then
      sed -i "s#^${name}:\\([^:]*\\):\\([^:]*\\):\\([^:]*\\):\\([^:]*\\):\\([^:]*\\):.*#${name}:\\1:\\2:\\3:\\4:\\5:/usr/sbin/nologin#" \
        "$root/etc/passwd"
    fi
  done
  cmd_lock_pass "$name" >/dev/null
  atlas_seat_write "$name" frozen 1
  echo "name=$name frozen=1"
}

cmd_seat_thaw() {
  need_root || return 1
  name="${1:-}"
  atlas_user_valid "$name" || { echo "error=bad-name"; return 2; }
  home="/data/local/atlas-home/$name"
  mkdir -p "$home"
  for root in `atlas_user_roots`; do
    if [ -f "$root/etc/passwd" ]; then
      sed -i "s#^${name}:\\([^:]*\\):\\([^:]*\\):\\([^:]*\\):\\([^:]*\\):\\([^:]*\\):.*#${name}:\\1:\\2:\\3:\\4:\\5:/bin/bash#" \
        "$root/etc/passwd"
    fi
    mkdir -p "$root/home/$name"
    if [ -d "$root/home/$name" ] && ! grep -q " $root/home/$name " /proc/mounts; then
      mount --bind "$home" "$root/home/$name" 2>/dev/null || true
    fi
  done
  atlas_seat_write "$name" frozen 0
  echo "name=$name frozen=0"
}

cmd_seat_save() {
  # Seats save into the Backups category (reboot-persistent, full moment).
  cmd_backup_save "$@"
}

cmd_seat_load() {
  cmd_backup_load "$@"
}

cmd_seat_snaps() {
  cmd_backup_list "${1:-}"
}

cmd_seat_rm_snap() {
  # $1 user (ignored if $2 is a backup id), $2 backup id
  if [ -n "${2:-}" ]; then
    cmd_backup_rm "$2"
  else
    cmd_backup_rm "${1:-}"
  fi
}

cmd_seat_clone() {
  echo "error=not-product"
  return 2
}

cmd_seat_export() {
  cmd_backup_export "$@"
}

atlas_backup_valid_id() {
  echo "${1:-}" | grep -q '^[A-Za-z0-9._-]\{1,80\}$'
}

atlas_backup_dir() {
  id="$1"
  if [ -d "$BACKUP_ROOT/$id" ]; then
    echo "$BACKUP_ROOT/$id"
  elif [ -d "$BACKUP_LP/$id" ]; then
    echo "$BACKUP_LP/$id"
  else
    echo ""
  fi
}

atlas_backup_ensure_roots() {
  mkdir -p "$BACKUP_ROOT"
  if [ -d /data/local/atlas-linux ]; then
    mkdir -p "$BACKUP_LP"
  fi
}

# Import old seat tarballs into the Backups category (once).
cmd_backup_import_legacy() {
  atlas_backup_ensure_roots
  for f in /data/local/atlas-seats/*/snaps/*.tgz; do
    [ -f "$f" ] || continue
    base=$(basename "$f" .tgz)
    atlas_backup_valid_id "$base" || continue
    dest="$BACKUP_ROOT/$base"
    [ -d "$dest" ] && continue
    mkdir -p "$dest"
    cp -f "$f" "$dest/home.tgz"
    user=$(echo "$base" | sed 's/-20[0-9][0-9].*//')
    [ -n "$user" ] || user=atlas
    {
      echo "user=$user"
      echo "ts=$(echo "$base" | sed 's/^.*-20/20/')"
      echo "imported=1"
      echo "src=$f"
    } >"$dest/meta"
  done
}

# Capture $HOME + writable overlay. User tool state under $HOME is included.
cmd_backup_save() {
  need_root || return 1
  name="${1:-atlas}"
  atlas_user_valid "$name" || { echo "error=bad-name"; return 2; }
  home="/data/local/atlas-home/$name"
  [ -d "$home" ] || { echo "error=no-home"; return 2; }
  atlas_backup_ensure_roots
  ts=$(date +%Y%m%d-%H%M%S)
  id="${name}-${ts}"
  dest="$BACKUP_ROOT/$id"
  mkdir -p "$dest"
  tar -C "$home" -czf "$dest/home.tgz" . 2>/dev/null || {
    echo "error=tar-home"
    rm -rf "$dest"
    return 1
  }
  overlay=0
  if [ -d /data/local/atlas-hybrid/upper ] \
      && [ -n "$(ls -A /data/local/atlas-hybrid/upper 2>/dev/null)" ]; then
    tar -C /data/local/atlas-hybrid/upper -czf "$dest/overlay.tgz" . 2>/dev/null && overlay=1
  fi
  {
    echo "user=$name"
    echo "ts=$ts"
    echo "overlay=$overlay"
    echo "persist=userdata"
  } >"$dest/meta"
  sz=$(stat -c %s "$dest/home.tgz" 2>/dev/null || echo 0)
  if [ -d /data/local/atlas-linux ]; then
    mkdir -p "$BACKUP_LP"
    rm -rf "$BACKUP_LP/$id"
    cp -a "$dest" "$BACKUP_LP/$id" 2>/dev/null || true
  fi
  atlas_seat_write "$name" last_save "$id"
  echo "backup=$id saved=$dest overlay=$overlay bytes=$sz"
}

cmd_backup_list() {
  cmd_backup_import_legacy >/dev/null 2>&1 || true
  filter="${1:-}"
  n=0
  seen="|"
  for root in "$BACKUP_ROOT" "$BACKUP_LP"; do
    [ -d "$root" ] || continue
    for d in "$root"/*; do
      [ -d "$d" ] || continue
      id=$(basename "$d")
      case "$seen" in *"|$id|"*) continue ;; esac
      seen="${seen}${id}|"
      user=""; overlay=0; ts=""; label=""; note=""
      if [ -f "$d/meta" ]; then
        user=$(awk -F= '$1=="user"{print $2; exit}' "$d/meta")
        overlay=$(awk -F= '$1=="overlay"{print $2; exit}' "$d/meta")
        ts=$(awk -F= '$1=="ts"{print $2; exit}' "$d/meta")
        label=$(awk -F= '$1=="label"{print $2; exit}' "$d/meta")
      fi
      [ -f "$d/label" ] && label=$(cat "$d/label" 2>/dev/null | tr -d '\r' | head -1)
      [ -f "$d/note" ] && note=$(cat "$d/note" 2>/dev/null)
      [ -n "$user" ] || user=$(echo "$id" | sed 's/-20[0-9][0-9].*//')
      [ -n "$ts" ] || ts=$(echo "$id" | sed 's/^.*-20/20/')
      if [ -n "$filter" ] && [ "$user" != "$filter" ] && [ "$id" != "$filter" ]; then
        continue
      fi
      sz=0
      [ -f "$d/home.tgz" ] && sz=$(stat -c %s "$d/home.tgz" 2>/dev/null || echo 0)
      lb64=$(printf '%s' "$label" | base64 | tr -d '\n ')
      nb64=$(printf '%s' "$note" | base64 | tr -d '\n ')
      echo "id=$id user=$user ts=$ts overlay=${overlay:-0} bytes=$sz persist=reboot label_b64=$lb64 note_b64=$nb64"
      n=$((n + 1))
    done
  done
  echo "backups=$n"
}

cmd_backup_load() {
  need_root || return 1
  a="${1:-}"
  b="${2:-}"
  id=""
  name=""
  if atlas_backup_valid_id "$a" && [ -n "$(atlas_backup_dir "$a")" ]; then
    id="$a"
  elif atlas_backup_valid_id "$b" && [ -n "$(atlas_backup_dir "$b")" ]; then
    name="$a"
    id="$b"
  elif [ -n "$a" ]; then
    name="$a"
    id=$(cmd_backup_list "$a" | awk '/^id=/{print $1; exit}' | sed 's/^id=//')
  fi
  [ -n "$id" ] || { echo "error=no-backup"; return 1; }
  dest=$(atlas_backup_dir "$id")
  [ -n "$dest" ] && [ -d "$dest" ] || { echo "error=no-backup"; return 1; }
  if [ -z "$name" ] && [ -f "$dest/meta" ]; then
    name=$(awk -F= '$1=="user"{print $2; exit}' "$dest/meta")
  fi
  [ -n "$name" ] || name=atlas
  home="/data/local/atlas-home/$name"
  cmd_seat_thaw "$name" >/dev/null 2>&1 || true
  mkdir -p "$home"
  if [ -f "$dest/home.tgz" ]; then
    tar -C "$home" -xzf "$dest/home.tgz" 2>/dev/null || {
      echo "error=untar-home"
      return 1
    }
  fi
  uid=$(awk -F: -v n="$name" '$1==n {print $3; exit}' "$(atlas_user_pwfile)")
  [ -n "$uid" ] && chown -R "${uid}:${uid}" "$home" 2>/dev/null || true
  _bk_user="$name"
  _bk_id="$id"
  atlas_seat_write "$_bk_user" last_load "$_bk_id"
  echo "backup=$_bk_id loaded=$dest home=$home"
}

cmd_backup_rm() {
  need_root || return 1
  id="${1:-}"
  atlas_backup_valid_id "$id" || { echo "error=bad-id"; return 2; }
  dest=$(atlas_backup_dir "$id")
  [ -n "$dest" ] || { echo "error=no-backup"; return 2; }
  rm -rf "$BACKUP_ROOT/$id" "$BACKUP_LP/$id"
  echo "deleted=$id"
}

atlas_backup_sync_lp() {
  id="$1"
  [ -n "$id" ] && [ -d "$BACKUP_ROOT/$id" ] || return 0
  if [ -d /data/local/atlas-linux ]; then
    mkdir -p "$BACKUP_LP"
    rm -rf "$BACKUP_LP/$id"
    cp -a "$BACKUP_ROOT/$id" "$BACKUP_LP/$id" 2>/dev/null || true
  fi
}

atlas_backup_b64d() {
  printf '%s' "${1:-}" | base64 -d 2>/dev/null || printf '%s' "${1:-}" | toybox base64 -d 2>/dev/null || true
}

cmd_backup_rename() {
  need_root || return 1
  id="${1:-}"
  atlas_backup_valid_id "$id" || { echo "error=bad-id"; return 2; }
  dest=$(atlas_backup_dir "$id")
  [ -n "$dest" ] || { echo "error=no-backup"; return 2; }
  label=""
  if [ -n "${ATLAS_BACKUP_LABEL_B64:-}" ]; then
    label=$(atlas_backup_b64d "$ATLAS_BACKUP_LABEL_B64")
  else
    shift
    label="$*"
  fi
  label=$(printf '%s' "$label" | tr -d '\r' | head -1)
  printf '%s' "$label" >"$dest/label"
  if [ -f "$dest/meta" ]; then
    if grep -q '^label=' "$dest/meta" 2>/dev/null; then
      sed -i '/^label=/d' "$dest/meta"
    fi
    printf 'label=%s\n' "$(printf '%s' "$label" | tr ' \t' '__')" >>"$dest/meta"
  fi
  atlas_backup_sync_lp "$id"
  echo "renamed=$id"
}

cmd_backup_note() {
  need_root || return 1
  id="${1:-}"
  atlas_backup_valid_id "$id" || { echo "error=bad-id"; return 2; }
  dest=$(atlas_backup_dir "$id")
  [ -n "$dest" ] || { echo "error=no-backup"; return 2; }
  note=""
  if [ -n "${ATLAS_BACKUP_NOTE_B64:-}" ]; then
    note=$(atlas_backup_b64d "$ATLAS_BACKUP_NOTE_B64")
  else
    shift
    note="$*"
  fi
  printf '%s' "$note" >"$dest/note"
  atlas_backup_sync_lp "$id"
  echo "noted=$id"
}

cmd_backup_export() {
  need_root || return 1
  a="${1:-}"
  b="${2:-}"
  id=""
  if atlas_backup_valid_id "$a" && [ -n "$(atlas_backup_dir "$a")" ]; then
    id="$a"
  elif atlas_backup_valid_id "$b" && [ -n "$(atlas_backup_dir "$b")" ]; then
    id="$b"
  elif [ -n "$a" ]; then
    id=$(cmd_backup_list "$a" | awk '/^id=/{print $1; exit}' | sed 's/^id=//')
  fi
  [ -n "$id" ] || { echo "error=no-backup"; return 1; }
  dest=$(atlas_backup_dir "$id")
  [ -n "$dest" ] && [ -d "$dest" ] || { echo "error=no-backup"; return 1; }
  [ -f "$dest/home.tgz" ] || { echo "error=no-home"; return 1; }
  mkdir -p /sdcard/AtlasBackups /data/local/atlas-home/atlas/exports
  tag="$id"
  [ -f "$dest/label" ] && [ -s "$dest/label" ] && tag=$(cat "$dest/label" | tr -cd 'A-Za-z0-9._-' | head -c 40)
  [ -n "$tag" ] || tag="$id"
  out=""
  if [ -d /sdcard/AtlasBackups ] && touch /sdcard/AtlasBackups/.w 2>/dev/null; then
    rm -f /sdcard/AtlasBackups/.w
    out="/sdcard/AtlasBackups/${tag}.atlas.tgz"
  else
    out="/data/local/atlas-home/atlas/exports/${tag}.atlas.tgz"
  fi
  tar -C "$dest" -czf "$out" . 2>/dev/null || {
    echo "error=tar-export"
    return 1
  }
  chmod 0644 "$out" 2>/dev/null || true
  echo "export=$out backup=$id"
}

cmd_backup_exports() {
  n=0
  seen="|"
  for d in /sdcard/AtlasBackups /data/local/atlas-home/atlas/exports; do
    [ -d "$d" ] || continue
    for f in "$d"/*.atlas.tgz "$d"/*.tgz; do
      [ -f "$f" ] || continue
      real=$(readlink -f "$f" 2>/dev/null || echo "$f")
      case "$seen" in *"|$real|"*) continue ;; esac
      seen="${seen}${real}|"
      echo "path=$f bytes=$(stat -c %s "$f" 2>/dev/null || echo 0)"
      n=$((n + 1))
    done
  done
  echo "exports=$n"
}

# Import a full session pack (export) or a bare home tarball.
cmd_backup_import() {
  need_root || return 1
  src="${1:-}"
  [ -n "$src" ] && [ -f "$src" ] || { echo "error=no-file"; return 2; }
  atlas_backup_ensure_roots
  ts=$(date +%Y%m%d-%H%M%S)
  id="import-${ts}"
  dest="$BACKUP_ROOT/$id"
  work="/data/local/tmp/atlas-import-$$"
  rm -rf "$work"
  mkdir -p "$work" "$dest"
  tar -C "$work" -xzf "$src" 2>/dev/null || {
    echo "error=untar"
    rm -rf "$work" "$dest"
    return 1
  }
  pack="$work"
  if [ ! -f "$work/home.tgz" ]; then
    inner=$(find "$work" -name home.tgz -type f 2>/dev/null | head -1)
    if [ -n "$inner" ]; then
      pack=$(dirname "$inner")
    fi
  fi
  if [ -f "$pack/home.tgz" ]; then
    cp -a "$pack/." "$dest/"
  elif [ -d "$pack/.grok" ] || [ -f "$pack/.bashrc" ] || [ -d "$pack/." ]; then
    tar -C "$pack" -czf "$dest/home.tgz" . 2>/dev/null || {
      echo "error=repack"
      rm -rf "$work" "$dest"
      return 1
    }
  else
    echo "error=not-session"
    rm -rf "$work" "$dest"
    return 1
  fi
  [ -f "$dest/home.tgz" ] || {
    echo "error=no-home"
    rm -rf "$work" "$dest"
    return 1
  }
  if [ ! -f "$dest/meta" ]; then
    {
      echo "user=atlas"
      echo "ts=$ts"
      echo "imported=1"
      echo "src=$src"
    } >"$dest/meta"
  else
    grep -q '^imported=' "$dest/meta" || echo "imported=1" >>"$dest/meta"
    echo "src=$src" >>"$dest/meta"
  fi
  if [ ! -f "$dest/label" ] || [ ! -s "$dest/label" ]; then
    base=$(basename "$src")
    base=$(echo "$base" | sed 's/\.atlas\.tgz$//;s/\.tgz$//')
    printf '%s' "$base" >"$dest/label"
  fi
  rm -rf "$work"
  atlas_backup_sync_lp "$id"
  echo "imported=$id src=$src"
}

write_profile() {
  # profile lives in upper via merge writes, or lower if merge not up
  target="$LOWER/etc/profile.d"
  mkdir -p "$target" 2>/dev/null || true
  cat >"$target/atlas-hybrid.sh" <<'EOF'
# Atlas combined OS — one terminal, seamless Android ↔ Debian
export ATLAS_HYBRID=1
export ATLAS_COMBINED=1
export USER="${USER:-atlas}"
export LOGNAME="${LOGNAME:-atlas}"
export ATLAS_ROLE=atlas
_AB="${ATLAS_BIN:-}"
export ATLAS_LINUX_HOME="${ATLAS_LINUX_HOME:-/data/local/atlas-home/atlas}"
# Prefer bound Deb home for installs
case "${HOME:-}" in
  ""|"/"|"/root")
    if [ -d /home/atlas ]; then HOME=/home/atlas
    elif [ -d "$ATLAS_LINUX_HOME" ]; then HOME=$ATLAS_LINUX_HOME
    fi
    ;;
esac
export HOME ATLAS_HOME="${ATLAS_HOME:-$HOME}"
# Universal: mkdir common layouts; PATH scans every $HOME/.<name>/bin (no hardcodes)
mkdir -p "${HOME}/bin" "${HOME}/.local/bin" 2>/dev/null || true
_USER_PATH=
for _h in "$HOME" "$ATLAS_LINUX_HOME"; do
  [ -n "$_h" ] && [ -d "$_h" ] || continue
  [ -d "$_h/bin" ] && _USER_PATH="${_USER_PATH:+$_USER_PATH:}$_h/bin"
  [ -d "$_h/.local/bin" ] && _USER_PATH="${_USER_PATH:+$_USER_PATH:}$_h/.local/bin"
  for _d in "$_h"/.*; do
    [ -d "$_d/bin" ] || continue
    case "$_d" in */.|*/..|*/.local) continue ;; esac
    _USER_PATH="${_USER_PATH:+$_USER_PATH:}$_d/bin"
  done
done
# Debian first, then user installs, then agent bin. Never let $HOME/bin/apt shadow /usr/bin/apt.
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin${_USER_PATH:+:$_USER_PATH}${_AB:+:$_AB}"
unset _USER_PATH _h _d
unset ANDROID_ROOT ANDROID_DATA ANDROID_STORAGE 2>/dev/null || true
export TMPDIR="${TMPDIR:-/tmp}"
export DEBIAN_FRONTEND="${DEBIAN_FRONTEND:-noninteractive}"
export SUDO_ASKPASS="${SUDO_ASKPASS:-${ATLAS_BIN:-}/atlas-auth-askpass}"
if [ -d /var/lib/atlas-auth ]; then
  export ATLAS_AUTH_DIR="${ATLAS_AUTH_DIR:-/var/lib/atlas-auth}"
elif [ -d /data/local/atlas-linux/var/lib/atlas-auth ]; then
  export ATLAS_AUTH_DIR="${ATLAS_AUTH_DIR:-/data/local/atlas-linux/var/lib/atlas-auth}"
else
  export ATLAS_AUTH_DIR="${ATLAS_AUTH_DIR:-/var/lib/atlas-auth}"
fi
if [ -x /bin/bash ]; then
  export SHELL=/bin/bash
elif [ -x /usr/bin/bash ]; then
  export SHELL=/usr/bin/bash
fi
if [ -n "${BASH_VERSION:-}" ]; then
  PS1='atlas:\w\$ '
else
  PS1='atlas\$ '
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
  storage=none
  overlay=0
  if grep -q " $MERGE overlay " /proc/mounts 2>/dev/null \
      || grep -q "^overlay $MERGE " /proc/mounts 2>/dev/null; then
    overlay=1
    storage=overlay
  elif grep -q atlas_linux_a /proc/self/mountinfo 2>/dev/null \
      || grep -q " $MERGE " /proc/mounts 2>/dev/null; then
    # LP bind or this process already lives on atlas_linux_a — not overlay.
    storage=lp
    overlay=0
  fi
  is_bootstrapped && boot=1
  if [ "$storage" != "none" ]; then
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
    echo "storage=$storage"
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
  # Init / app / prove can poll property without reading CE paths
  setprop sys.atlas.hybrid.ready "$ready" 2>/dev/null || true
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
        if [ "$(plane_read titan2_atlas_seat_sandbox 0)" = "1" ] \
            || atlas_sandbox_active_name >/dev/null 2>&1; then
          log "ensure: sandbox overlay — heal only"
          bind_android 2>/dev/null || true
          atlas_sandbox_reapply
          heal_merge_essentials 2>/dev/null || true
          plane_write titan2_atlas_mode debian
          export ATLAS_LP_MODE=1
          write_product_status
          return 0
        fi
        log "ensure: tearing legacy overlay for super LP"
        if bring_up_from_lp; then
          export ATLAS_LP_MODE=1
          write_product_status
          return 0
        fi
      elif [ -x "$MERGE/bin/bash" ] || [ -x "$MERGE/usr/bin/bash" ]; then
        # already LP-style (bind or direct) — still run wipe-first-boot perms
        # (prior heresy: early return skipped home/auth → tip thrash "fix")
        bind_android 2>/dev/null || true
        heal_merge_essentials 2>/dev/null || true
        ensure_auth_plane_on_lp 2>/dev/null || true
        heal_wipe_first_boot_perms 2>/dev/null || true
        plane_write titan2_atlas_mode debian
        export ATLAS_LP_MODE=1
        write_product_status
        log "ensure: live LP merge — heal only + wipe-first-boot perms"
        return 0
      fi
    fi
    if bring_up_from_lp; then
      export ATLAS_LP_MODE=1
      write_product_status
      return 0
    fi
    log "ensure: LP present but failed — refusing loop/mke2fs (would wipe Debian)"
    write_product_status
    return 1
  fi

  # LIVE overlay in /proc/mounts: never remount, never e2fsck, never detach.
  if grep -q " $MERGE " /proc/mounts 2>/dev/null; then
    unset ATLAS_FORCE_FSCK
    rm -f /data/local/tmp/atlas-hybrid-need-fsck 2>/dev/null || true
    heal_merge_essentials 2>/dev/null || true
    # Never treat missing Android /system/bin as a reason to re-bind.
    # Debian bash on merge is the enterable plane.
    if [ ! -x "$MERGE/bin/bash" ] && [ ! -x "$MERGE/usr/bin/bash" ]; then
      bind_android 2>/dev/null || true
    fi
    heal_wipe_first_boot_perms 2>/dev/null || true
    plane_write titan2_atlas_mode debian
    write_product_status
    log "ensure: live overlay — heal only (no remount) + wipe-first-boot perms"
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
  # Atlas APK CE files — Android app plane only. Not Debian HOME.
  echo "$(_atlas_pkg_real)/files"
}

_linux_home_real() {
  echo "${ATLAS_LINUX_HOME:-/data/local/atlas-home/atlas}"
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
  mkdir -p "$home/etc" "$home/bin" "$home/reports" 2>/dev/null || true
  join_home_auth_to_lp 2>/dev/null || true
  chown "$uid:$uid" "$home/etc" "$home/bin" "$home/reports" 2>/dev/null || true
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
    log "FATAL no atlas uid (ATLAS_DROP_UID) — refuse root shell"
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

  # Debian plane HOME is linux home (/home/atlas bind), never Atlas CE files.
  bind_linux_home 2>/dev/null || true
  _AH=/home/atlas
  _HOST_HOME=`_linux_home_real`
  [ -d "$_HOST_HOME" ] || mkdir -p "$_HOST_HOME" 2>/dev/null || true
  mkdir -p "$_HOST_HOME/bin" "$_HOST_HOME/.local/bin" \
    "$_HOST_HOME/reports" "$_HOST_HOME/etc" 2>/dev/null || true
  _AB=/home/atlas/bin
  _USER_PATH="/home/atlas/.local/bin:/home/atlas/bin"
  for _d in "$_HOST_HOME"/.*; do
    [ -d "$_d/bin" ] || continue
    case "$_d" in */.local|*/.|*/..) continue ;; esac
    _bn=`basename "$_d"`
    _USER_PATH="$_USER_PATH:/home/atlas/$_bn/bin"
  done
  # Debian + user only. No /system/bin — that is Android (ro).
  _PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$_USER_PATH:$_AB"

  export ATLAS_HYBRID=1 ATLAS_COMBINED=1
  export HOME="$_AH" ATLAS_HOME="$_AH" ATLAS_BIN="$_AB"
  export ATLAS_LINUX_HOME="$_HOST_HOME"
  export ATLAS_AUTH_DIR="${ATLAS_AUTH_DIR:-${ATLAS_AUTH_ON_LP:-/data/local/atlas-linux/var/lib/atlas-auth}}"
  export SUDO_ASKPASS="${SUDO_ASKPASS:-$_AB/atlas-auth-askpass}"
  export USER=atlas LOGNAME=atlas ATLAS_ROLE=atlas
  export TERM="${TERM:-xterm-256color}"
  export LANG="${LANG:-C.UTF-8}"
  export COLORTERM="${COLORTERM:-truecolor}"
  export PATH="$_PATH"
  unset ANDROID_ROOT ANDROID_DATA ANDROID_STORAGE 2>/dev/null || true
  export TMPDIR=/tmp
  atlas_apply_android_dns 2>/dev/null || true
  unset BASH_ENV 2>/dev/null || true
  unset LD_LIBRARY_PATH 2>/dev/null || true
  unset LD_PRELOAD 2>/dev/null || true
  [ -n "${SSL_CERT_FILE:-}" ] && export SSL_CERT_FILE
  [ -n "${SSL_CERT_DIR:-}" ] && export SSL_CERT_DIR

  log "enter chroot+debian-su admin=$DROP shell=$shrel home=$_AH"

  _DEB_ENV="
      export HOME='/home/atlas' ATLAS_HOME='/home/atlas' ATLAS_BIN='/home/atlas/bin'
      export ATLAS_HYBRID=1 ATLAS_COMBINED=1 ATLAS_ROLE=atlas USER=atlas LOGNAME=atlas
      export PATH='$_PATH'
      export ATLAS_AUTH_DIR='/var/lib/atlas-auth'
      export SUDO_ASKPASS='/home/atlas/bin/atlas-auth-askpass'
      export TERM='${TERM:-xterm-256color}' LANG='${LANG:-C.UTF-8}' COLORTERM='${COLORTERM:-truecolor}'
      unset ANDROID_ROOT ANDROID_DATA ANDROID_STORAGE LD_LIBRARY_PATH LD_PRELOAD 2>/dev/null || true
      export TMPDIR=/tmp
      [ -f /home/atlas/cacert.pem ] && export SSL_CERT_FILE='/home/atlas/cacert.pem'
      unset BASH_ENV
  "

  # Host chroot + Debian su. Never /system/bin/su inside merge (needs Android /system).
  DEB_SU=
  [ -x "$MERGE/usr/bin/su" ] && DEB_SU=/usr/bin/su
  [ -z "$DEB_SU" ] && [ -x "$MERGE/bin/su" ] && DEB_SU=/bin/su
  if [ -n "$DEB_SU" ]; then
    _IN="$DEB_SU -s $shrel atlas -c"
  else
    _IN="$shrel -c"
  fi

  if [ "$#" -eq 0 ] || [ "$1" = "-l" ] || [ "$1" = "--login" ]; then
    exec env -u LD_LIBRARY_PATH -u LD_PRELOAD \
      "$CHROOT" "$MERGE" $_IN "
      $_DEB_ENV
      cd /home/atlas 2>/dev/null || true
      exec '$shrel' -l
    "
  fi

  if [ "$1" = "-lc" ]; then
    shift
    cmd="$*"
    exec env -u LD_LIBRARY_PATH -u LD_PRELOAD \
      "$CHROOT" "$MERGE" $_IN \
      "$_DEB_ENV cd /home/atlas 2>/dev/null; $cmd"
  fi

  exec env -u LD_LIBRARY_PATH -u LD_PRELOAD \
    "$CHROOT" "$MERGE" $_IN \
    "$_DEB_ENV cd /home/atlas 2>/dev/null; $*"
}

cmd_enter() {
  # Product: prefer setuid atlas-enter (works from app without Magisk).
  if [ -x /system/bin/atlas-enter ]; then
    DROP="${ATLAS_DROP_UID:-}"
    if [ -z "$DROP" ] || [ "$DROP" = "0" ]; then
      DROP=`stat -c %u /data/data/com.titanus2.atlas 2>/dev/null \
        || stat -c %u /data/user/0/com.titanus2.atlas 2>/dev/null || true`
    fi
    AH=/home/atlas
    log "enter via /system/bin/atlas-enter drop=$DROP home=$AH"
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
  if lp_dev_present; then
    log "destroy refused — live Debian is super LP atlas_linux_a (will not trash OS)"
    echo "error=lp-live"
    return 2
  fi
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
  ensure-user|user) cmd_ensure_user ;;
  add-user|useradd) cmd_add_user "$@" ;;
  list-users|users) cmd_list_users ;;
  set-pass) cmd_set_pass "$@" ;;
  lock-pass) cmd_lock_pass "$@" ;;
  set-sudo) cmd_set_sudo "$@" ;;
  set-perm) cmd_set_perm "$@" ;;
  del-user|userdel) cmd_del_user "$@" ;;
  seat-status) cmd_seat_status "$@" ;;
  seat-sandbox) cmd_seat_sandbox "$@" ;;
  seat-freeze) cmd_seat_freeze "$@" ;;
  seat-thaw) cmd_seat_thaw "$@" ;;
  seat-save) cmd_seat_save "$@" ;;
  seat-clone) cmd_seat_clone "$@" ;;
  seat-export) cmd_seat_export "$@" ;;
  seat-snaps) cmd_seat_snaps "$@" ;;
  seat-load|seat-restore) cmd_seat_load "$@" ;;
  seat-rm-snap|seat-del-snap) cmd_seat_rm_snap "$@" ;;
  backup-save) cmd_backup_save "$@" ;;
  backup-list|backups) cmd_backup_list "$@" ;;
  backup-load) cmd_backup_load "$@" ;;
  backup-rm|backup-del) cmd_backup_rm "$@" ;;
  backup-export) cmd_backup_export "$@" ;;
  backup-import) cmd_backup_import "$@" ;;
  backup-import-legacy) cmd_backup_import_legacy ;;
  backup-rename) cmd_backup_rename "$@" ;;
  backup-note) cmd_backup_note "$@" ;;
  backup-exports) cmd_backup_exports ;;
  apply-dns|dns)
    need_root || exit 1
    atlas_apply_android_dns
    ;;
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
