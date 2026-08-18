#!/system/bin/sh
# Atlas hybrid ROM — late boot install path (NOT optional tip theater).
#
# Product: this is a hybrid ROM. On every boot after data is ready:
#   1) Super LP atlas_linux first (wipe-survive Deb root)
#   2) If Debian lower already present → mount + bind Android
#   3) Else if rootfs seed is staged → bootstrap once (extract) then mount
#   4) Never mke2fs/destroy an existing image here
#
# Seeds (first match wins — find_rootfs_tarball in atlas-hybrid.sh):
#   /system/etc/atlas/debian-trixie-arm64-rootfs.tar.gz
#   /data/local/tmp/atlas-hybrid-dl/rootfs.tar.gz
#   $ATLAS_HOME/rootfs/…
#
# Logs: /data/local/tmp/atlas-hybrid-service.log
export PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin:$PATH
LOG=/data/local/tmp/atlas-hybrid-service.log
SCRIPT=/system/bin/atlas-hybrid.sh
# Debian/linux home on userdata — never Atlas CE files.
export HOME="${ATLAS_HOME:-/data/local/atlas-home/atlas}"
export ATLAS_HOME="$HOME"
export ATLAS_LINUX_HOME="${ATLAS_LINUX_HOME:-/data/local/atlas-home/atlas}"
export ATLAS_HYBRID_SIZE_G="${ATLAS_HYBRID_SIZE_G:-8}"
# Hybrid ROM: auto bootstrap when seed present
export ATLAS_AUTO_BOOTSTRAP=1
# LP mapper often lags fs_mgr on cold post-wipe boot
export ATLAS_LPCTL_WAIT_S="${ATLAS_LPCTL_WAIT_S:-60}"

mark_ready() {
  # World-readable + property for prove scripts / app (best-effort)
  if [ -f /data/local/tmp/atlas_hybrid.ready ]; then
    r=$(cat /data/local/tmp/atlas_hybrid.ready 2>/dev/null | tr -d '\r\n')
  else
    r=0
  fi
  [ -z "$r" ] && r=0
  setprop sys.atlas.hybrid.ready "$r" 2>/dev/null || true
  echo "sys.atlas.hybrid.ready=$r" >>"$LOG" 2>/dev/null || true
}

i=0
while [ $i -lt 90 ]; do
  [ -d /data/local ] && [ -d /data/user/0 ] && break
  sleep 1
  i=$((i + 1))
done

# Allow create log early even if ensure fails
mkdir -p /data/local/tmp 2>/dev/null || true
touch "$LOG" 2>/dev/null || true
chmod 644 "$LOG" 2>/dev/null || true

[ -f "$SCRIPT" ] || {
  echo "=== $(date) no $SCRIPT ===" >>"$LOG"
  setprop sys.atlas.hybrid.ready 0 2>/dev/null || true
  exit 0
}

{
  echo "=== $(date) atlas-hybrid-boot (hybrid ROM install path) ==="
  echo "ATLAS_AUTO_BOOTSTRAP=$ATLAS_AUTO_BOOTSTRAP HOME=$HOME ATLAS_LPCTL_WAIT_S=$ATLAS_LPCTL_WAIT_S"
  echo "uid=$(id -u 2>/dev/null) se=$(id 2>/dev/null | head -1)"

  # Wait briefly for super mapper (lpctl also waits; this logs progress)
  m=0
  while [ $m -lt 30 ]; do
    if [ -b /dev/block/mapper/atlas_linux_a ] || [ -b /dev/block/mapper/atlas_linux ] \
      || [ -b /dev/block/by-name/atlas_linux_a ]; then
      echo "mapper present after ${m}s"
      break
    fi
    m=$((m + 1))
    sleep 1
  done
  ls -la /dev/block/mapper/atlas_linux* /dev/block/by-name/atlas_linux* 2>&1 | head -10 || true

  # Super LP first (product Deb root — SURVIVES userdata wipe).
  # LAW: wipe-survive means mount LP after wipe without re-bootstrap when seed already on LP.
  # Prefer atlas-lpctl; fallback toybox mount if lpctl SIGSYS/seccomp under bad domain.
  mount_atlas_linux_lp() {
    mnt=/data/local/atlas-linux
    mkdir -p "$mnt" /data/local/atlas-home/atlas 2>/dev/null || true
    if grep -q " $mnt " /proc/mounts 2>/dev/null; then
      echo "LP already mounted at $mnt"
      return 0
    fi
    if [ -x /system/bin/atlas-lpctl ]; then
      echo "atlas-lpctl mount…"
      if /system/bin/atlas-lpctl mount 2>&1; then
        /system/bin/atlas-lpctl status 2>&1 || true
        /system/bin/atlas-lpctl home-ensure 2>&1 || true
        /system/bin/atlas-lpctl auth-ensure 2>&1 || true
        return 0
      fi
      echo "atlas-lpctl mount failed — try toybox mount"
    fi
    dev=""
    for d in /dev/block/mapper/atlas_linux_a /dev/block/mapper/atlas_linux \
             /dev/block/by-name/atlas_linux_a /dev/block/by-name/atlas_linux; do
      [ -b "$d" ] && dev=$d && break
    done
    if [ -z "$dev" ]; then
      echo "LP device missing (pack WITH_ATLAS_LP=1)"
      return 1
    fi
    # Init root: mount(2) via toybox — shell domain gets SIGSYS without priv
    if mount -t ext4 -o noatime "$dev" "$mnt" 2>&1 \
      || mount -t ext4 "$dev" "$mnt" 2>&1; then
      echo "toybox mount OK dev=$dev mnt=$mnt"
      mkdir -p /data/local/atlas-home/atlas/reports \
        "$mnt/var/lib/atlas-auth" 2>/dev/null || true
      chmod 0777 "$mnt/var/lib/atlas-auth" 2>/dev/null || true
      return 0
    fi
    echo "LP mount FAILED dev=$dev"
    return 1
  }
  mount_atlas_linux_lp || true

  # App may leave ensure-request after unlock, Atlas Clear data, or crash.
  # CE wipe must remount surviving Deb — never bootstrap / mke2fs.
  if [ -f /data/local/tmp/atlas-hybrid-need-fsck ] \
    || [ -f /data/local/tmp/atlas-hybrid-ensure-request ]; then
    export ATLAS_FORCE_FSCK=1
    echo "ATLAS_FORCE_FSCK=1 (need-fsck or app request)"
  fi

  # Prefer mount; hybrid.sh will auto-bootstrap when empty + seed + AUTO=1
  ok=0
  n=0
  while [ $n -lt 5 ]; do
    n=$((n + 1))
    if /system/bin/sh "$SCRIPT" ensure; then
      echo "ensure: OK (try $n)"
      ok=1
      break
    fi
    echo "ensure: failed try $n — retry"
    # re-try LP mount between ensures (mapper may have just appeared)
    if [ -x /system/bin/atlas-lpctl ]; then
      /system/bin/atlas-lpctl mount 2>&1 || true
    fi
    sleep $((n * 3))
  done
  [ "$ok" = "1" ] || echo "ensure: still failed after $n tries"
  rm -f /data/local/tmp/atlas-hybrid-ensure-request 2>/dev/null || true

  # Wipe-first-boot permission plane (product ROM — NOT tip thrash).
  # After userdata wipe, atlas-home / hybrid parents land root:root 0700 and
  # Deb admin (app uid) cannot chdir HOME. Force every boot from peels.
  {
    echo "wipe-first-boot perm plane…"
    AU=$(stat -c %u /data/data/com.titanus2.atlas 2>/dev/null \
      || stat -c %u /data/user/0/com.titanus2.atlas 2>/dev/null || true)
    mkdir -p /data/local/atlas-home/atlas/reports \
      /data/local/atlas-home/atlas/.local/bin \
      /data/local/atlas-hybrid \
      /data/local/atlas-linux/var/lib/atlas-auth \
      /data/local/atlas-linux/tmp \
      /data/local/atlas-linux/var/tmp \
      /data/local/atlas-hybrid/merge/tmp \
      /data/local/atlas-hybrid/merge/var/tmp 2>/dev/null || true
    chmod 0755 /data/local /data/local/atlas-home /data/local/atlas-hybrid \
      /data/local/atlas-home/atlas 2>/dev/null || true
    if [ -n "$AU" ] && [ "$AU" != "0" ]; then
      chown -R "$AU:$AU" /data/local/atlas-home/atlas 2>/dev/null || true
    fi
    if [ -x /system/bin/atlas-lpctl ]; then
      /system/bin/atlas-lpctl home-ensure 2>&1 || true
      /system/bin/atlas-lpctl auth-ensure 2>&1 || true
    fi
    chmod 0777 /data/local/atlas-linux/var/lib/atlas-auth 2>/dev/null || true
    chmod 1777 /data/local/atlas-linux/tmp /data/local/atlas-linux/var/tmp \
      /data/local/atlas-hybrid/merge/tmp \
      /data/local/atlas-hybrid/merge/var/tmp 2>/dev/null || true
    # Product: sudoers must be uid 0 on LP (never Android system=1000).
    # Boot peel — not tip. Pack should already bake uid 0; this is fail-closed.
    for root in /data/local/atlas-linux /data/local/atlas-hybrid/merge; do
      [ -d "$root/etc" ] || continue
      for f in "$root/etc/sudoers" "$root/etc/sudo.conf"; do
        [ -e "$f" ] || continue
        chown 0:0 "$f" 2>/dev/null || true
      done
      [ -f "$root/etc/sudoers" ] && chmod 0440 "$root/etc/sudoers" 2>/dev/null || true
      [ -f "$root/etc/sudo.conf" ] && chmod 0644 "$root/etc/sudo.conf" 2>/dev/null || true
      if [ -d "$root/etc/sudoers.d" ]; then
        chown 0:0 "$root/etc/sudoers.d" 2>/dev/null || true
        chmod 0750 "$root/etc/sudoers.d" 2>/dev/null || true
        for f in "$root/etc/sudoers.d"/*; do
          [ -f "$f" ] || continue
          chown 0:0 "$f" 2>/dev/null || true
          chmod 0440 "$f" 2>/dev/null || true
        done
      fi
      for s in "$root/usr/bin/sudo.real" "$root/usr/bin/sudo" \
               "$root/bin/sudo.real" "$root/bin/sudo"; do
        [ -f "$s" ] || continue
        chown 0:0 "$s" 2>/dev/null || true
        chmod 4755 "$s" 2>/dev/null || true
      done
      # Single apt SoT: drop deb822 duplicates (sources.list stays)
      if [ -d "$root/etc/apt/sources.list.d" ]; then
        rm -f "$root/etc/apt/sources.list.d/debian.sources" \
              "$root/etc/apt/sources.list.d/"*.sources 2>/dev/null || true
      fi
    done
    # App CE files often root-owned after root ensure thrash
    if [ -n "$AU" ] && [ "$AU" != "0" ]; then
      for h in /data/data/com.titanus2.atlas/files /data/user/0/com.titanus2.atlas/files; do
        [ -d "$h" ] || continue
        chown -R "$AU:$AU" "$h" 2>/dev/null || true
        chmod -R u+rwX "$h" 2>/dev/null || true
      done
    fi
    echo "wipe-first-boot perm plane done au=${AU:-none}"
    ls -la /data/local/atlas-linux/etc/sudoers /data/local/atlas-linux/etc/sudo.conf 2>&1 | head -5 || true
    ls -laZd /data/local/atlas-home /data/local/atlas-home/atlas \
      /data/local/atlas-hybrid 2>&1 | head -10 || true
  }

  if [ -f /system/bin/atlas-hybrid-ctl.sh ]; then
    /system/bin/sh /system/bin/atlas-hybrid-ctl.sh status | head -40
  else
    /system/bin/sh "$SCRIPT" status | head -40
  fi
  /system/bin/atlas-lpctl status 2>&1 || true
  mark_ready
  # Late DNS re-apply (VPN-first SoT): wipe/boot ensure often runs before Wi‑Fi
  # has DnsAddresses → public 8.8.8.8 residual breaks Deb curl. Keep applying
  # until resolv is non-public or 60s elapsed (also covers VPN connect late).
  (
    w=0
    while [ $w -lt 60 ]; do
      if dumpsys connectivity 2>/dev/null | grep -q 'DnsAddresses: \['; then
        /system/bin/sh "$SCRIPT" apply-dns >>"$LOG" 2>&1 || true
        cur=`cat /data/local/atlas-linux/etc/resolv.conf 2>/dev/null | tr '\n' ' '`
        echo "=== $(date) late apply-dns after ${w}s resolv=[$cur] ===" >>"$LOG" 2>/dev/null || true
        # Prefer non-public nameserver; keep looping if still only 8.8.8.8/1.1.1.1
        case "$cur" in
          *'nameserver 8.8.8.8'*|*'nameserver 1.1.1.1'*)
            case "$cur" in
              *'192.168.'*|*'10.'*|*'172.'*|*'100.100.'*|*'100.64.'*) break ;;
            esac
            ;;
          *nameserver*) break ;;
        esac
      fi
      w=$((w + 1))
      sleep 1
    done
  ) >/dev/null 2>&1 &
  echo "=== $(date) atlas-hybrid-boot done ok=$ok ==="
} >>"$LOG" 2>&1 || true

# Deb enter: overlay up is not enough — atlas-enterd must listen @atlasenter.
# T2138Z shipped the ELF without atlas-enterd.rc; Deb tap then died exit 79.
start_enterd() {
  if pidof atlas-enterd >/dev/null 2>&1 \
    || [ -S /dev/socket/atlasenter ] \
    || grep -q '@atlasenter' /proc/net/unix 2>/dev/null; then
    echo "=== $(date) enterd already live ===" >>"$LOG" 2>/dev/null || true
    return 0
  fi
  # KEEP_DATA leaves a dead sock; KSU post-fs treated that as "up" (1217Z).
  rm -f /data/local/tmp/atlas-enter.sock 2>/dev/null || true
  echo "=== $(date) ctl.start atlas-enterd ===" >>"$LOG" 2>/dev/null || true
  setprop sys.atlas.enterd 1 2>/dev/null || true
  setprop ctl.start atlas-enterd 2>/dev/null || true
  w=0
  while [ "$w" -lt 20 ]; do
    if pidof atlas-enterd >/dev/null 2>&1 \
      || [ -S /dev/socket/atlasenter ]; then
      echo "=== $(date) enterd live via init ===" >>"$LOG" 2>/dev/null || true
      return 0
    fi
    w=$((w + 1))
    sleep 0.1
  done
  if [ -x /system/bin/atlas-hybrid-watch.sh ] \
    && ! pidof atlas-hybrid-watch.sh >/dev/null 2>&1; then
    echo "=== $(date) start atlas-hybrid-watch ===" >>"$LOG" 2>/dev/null || true
    /system/bin/sh /system/bin/atlas-hybrid-watch.sh \
      >>/data/local/tmp/atlas-hybrid-watch.log 2>&1 &
    sleep 0.4
  fi
  if pidof atlas-enterd >/dev/null 2>&1 \
    || [ -S /dev/socket/atlasenter ]; then
    echo "=== $(date) enterd live via watch ===" >>"$LOG" 2>/dev/null || true
    return 0
  fi
  echo "=== $(date) init miss — exec enterd ELF ===" >>"$LOG" 2>/dev/null || true
  if [ -x /system/bin/atlas-enterd.sh ]; then
    /system/bin/atlas-enterd.sh >>/data/local/tmp/atlas-enterd.log 2>&1 &
  elif [ -x /system/bin/atlas-enterd ]; then
    /system/bin/atlas-enterd >>/data/local/tmp/atlas-enterd.log 2>&1 &
  else
    echo "=== $(date) no atlas-enterd ELF ===" >>"$LOG" 2>/dev/null || true
    return 1
  fi
  sleep 0.3
  echo "=== $(date) enterd pids=$(pidof atlas-enterd 2>/dev/null) ===" \
    >>"$LOG" 2>/dev/null || true
}
start_enterd

# Second-chance: if not ready, schedule one re-trigger via property (rc listens).
# Avoid infinite loop — only once per boot via stamp.
if [ ! -f /data/local/tmp/atlas_hybrid.ready ] \
  || [ "$(cat /data/local/tmp/atlas_hybrid.ready 2>/dev/null | tr -d '\r\n')" != "1" ]; then
  if [ ! -f /data/local/tmp/atlas-hybrid-boot-retried ]; then
    echo "=== $(date) schedule delayed hybrid re-ensure (ready!=1) ===" >>"$LOG" 2>/dev/null || true
    touch /data/local/tmp/atlas-hybrid-boot-retried 2>/dev/null || true
    (
      sleep 25
      setprop sys.atlas.hybrid 1 2>/dev/null || true
    ) >/dev/null 2>&1 &
  fi
fi

# OpenWrt sibling plane. Init oneshot may never leave a svc prop; belt after /data.
if [ -f /system/bin/titan2-openwrt-boot.sh ]; then
  /system/bin/sh /system/bin/titan2-openwrt-boot.sh >>"$LOG" 2>&1 || true
elif [ -f /system/bin/titan2-openwrt.sh ]; then
  /system/bin/sh /system/bin/titan2-openwrt.sh start >>"$LOG" 2>&1 || true
fi

exit 0
