#!/system/bin/sh
# One copy of each product script after a system update.
#
# Live fixes land in /data/adb/titan2 and /data/local/tmp and then hide
# /system/bin. A KEEP_DATA install leaves those copies in place, so the
# old script keeps running. This runs from KernelSU service.d (84) and
# from the image init unit.
#
# Generation line, in the script, as its own line:
#   # ATLAS_OVERLAY_STAMP=<integer>
# Bump it when a phone-side copy must outrank the image you are about to
# cook. The image wins when its stamp is greater or equal, when the bytes
# match, or when a known marker is already in the image. A higher userdata
# stamp stays bind-mounted. An unstamped file newer than the image stays
# too: that is an emergency tip that has not been stamped yet.
#
# Display, icons, sensor privacy, and dens-sanitize always follow the
# image. theme-watch already drops those leftover binds.
#
# OVERLAY_MARK, OVERLAY_IMGDIR, OVERLAY_TMP, OVERLAY_DRY=1 are host-test hooks.

MARK=${OVERLAY_MARK:-/data/adb/titan2}
IMGDIR=${OVERLAY_IMGDIR:-/system/bin}
ST=${OVERLAY_TMP:-/data/local/tmp}
DRY=${OVERLAY_DRY:-0}
DIS=$MARK/disabled
LOG=$ST/titan2-overlay-retire.log

mkdir -p "$ST" "$DIS" 2>/dev/null || true

log() {
  printf '%s %s\n' "$(date '+%H:%M:%S')" "$*" >>"$LOG" 2>/dev/null || true
}

stamp_of() {
  _so=$(grep -m1 '^# ATLAS_OVERLAY_STAMP=' "$1" 2>/dev/null) || true
  _so=${_so##*ATLAS_OVERLAY_STAMP=}
  _so=$(printf '%s' "$_so" | tr -cd '0-9')
  [ -n "$_so" ] || _so=0
  printf '%s\n' "$_so"
}

mtime_of() {
  _mo=$(stat -c %Y "$1" 2>/dev/null) || _mo=0
  case "$_mo" in
    ''|*[!0-9]*) _mo=0 ;;
  esac
  printf '%s\n' "$_mo"
}

always_image() {
  case "$1" in
    titan2-display.sh|titan2-ui-plane.sh|titan2-cube-icons.sh|titan2-sensor-privacy.sh|titan2-dens-sanitize.sh)
      return 0
      ;;
  esac
  return 1
}

# Return 0 when the image copy must be the one that runs.
image_wins() {
  _iw_data=$1
  _iw_img=$2
  [ -f "$_iw_img" ] || return 1
  [ -f "$_iw_data" ] || return 0
  cmp -s "$_iw_img" "$_iw_data" && return 0
  _iw_is=$(stamp_of "$_iw_img")
  _iw_ds=$(stamp_of "$_iw_data")
  if [ "$_iw_ds" -gt "$_iw_is" ]; then
    return 1
  fi
  if [ "$_iw_is" -gt 0 ] && [ "$_iw_ds" -le "$_iw_is" ]; then
    if [ "$_iw_ds" -eq 0 ]; then
      _iw_dm=$(mtime_of "$_iw_data")
      _iw_im=$(mtime_of "$_iw_img")
      [ "$_iw_dm" -gt "$_iw_im" ] && return 1
    fi
    return 0
  fi
  _iw_base=${_iw_data##*/}
  case "$_iw_base" in
    atlas-home-img.sh)
      grep -q rebind_deb_views "$_iw_img" 2>/dev/null && return 0
      return 1
      ;;
    atlas-hybrid.sh)
      grep -q publish_account_files "$_iw_img" 2>/dev/null && return 0
      return 1
      ;;
    titan2-remote-adb.sh)
      grep -q 'BEST_IP=wlan-first' "$_iw_img" 2>/dev/null \
        && grep -q 'NO_TR_CMDLINE=1' "$_iw_img" 2>/dev/null && return 0
      return 1
      ;;
    titan2-dev-action.sh)
      grep -q 'REMOTE_ADB_ADDR=lan' "$_iw_img" 2>/dev/null && return 0
      return 1
      ;;
  esac
  _iw_dm=$(mtime_of "$_iw_data")
  _iw_im=$(mtime_of "$_iw_img")
  [ "$_iw_dm" -gt "$_iw_im" ] && return 1
  return 0
}

is_mounted() {
  [ "$DRY" = 1 ] && return 1
  awk -v p="$1" '$5==p { found=1 } END { exit !found }' /proc/self/mountinfo 2>/dev/null
}

umount_one() {
  [ "$DRY" = 1 ] && return 0
  is_mounted "$1" || return 0
  umount "$1" 2>/dev/null || umount -l "$1" 2>/dev/null || true
  log "umount $1"
}

release_mounts() {
  _rn=$1
  umount_one "$IMGDIR/$_rn"
  umount_one "/data/local/atlas-hybrid/lower/android/system/bin/$_rn"
  umount_one "/data/local/atlas-linux/android/system/bin/$_rn"
  umount_one "/data/local/atlas-hybrid/merge/android/system/bin/$_rn"
}

retire_copy() {
  _src=$1
  _destname=$2
  [ -f "$_src" ] || return 0
  if [ "$DRY" = 1 ]; then
    log "retire $_src"
    return 0
  fi
  mkdir -p "$DIS" 2>/dev/null || true
  if mv -f "$_src" "$DIS/$_destname" 2>/dev/null; then
    chmod 000 "$DIS/$_destname" 2>/dev/null || true
    log "retired $_src"
  else
    log "retire failed $_src"
  fi
}

bind_over() {
  _src=$1
  _dst=$2
  [ -f "$_src" ] || return 0
  [ -e "$_dst" ] || return 0
  if [ "$DRY" = 1 ]; then
    log "bind $_src $_dst"
    return 0
  fi
  mount --bind "$_src" "$_dst" 2>/dev/null || mount -o bind "$_src" "$_dst" 2>/dev/null || true
  log "bind $_src $_dst"
}

skip_name() {
  case "$1" in
    83-atlas-wifi-muzzle.sh|84-atlas-home-adb.sh|titan2-overlay-retire.sh|titan2-wifi-muzzle.sh|titan2-wifi-heal.sh)
      return 0
      ;;
  esac
  return 1
}

consider_mark() {
  _data=$1
  _name=${_data##*/}
  skip_name "$_name" && return 0
  release_mounts "$_name"
  _img="$IMGDIR/$_name"
  if [ ! -f "$_img" ]; then
    log "keep $_name (no image copy)"
    return 0
  fi
  if is_mounted "$_img"; then
    log "keep $_name (image path still mounted)"
    return 0
  fi
  _tip="$ST/$_name"
  if always_image "$_name" || image_wins "$_data" "$_img"; then
    retire_copy "$_data" "$_name"
    if [ -f "$_tip" ] && ! always_image "$_name" && ! image_wins "$_tip" "$_img"; then
      log "keep tmp $_name"
    else
      retire_copy "$_tip" "tmp-$_name"
    fi
    log "image $_name"
    return 0
  fi
  if [ -f "$_tip" ] && image_wins "$_tip" "$_data"; then
    retire_copy "$_tip" "tmp-$_name"
  fi
  bind_over "$_data" "$_img"
}

consider_tmp() {
  _tip=$1
  _name=${_tip##*/}
  skip_name "$_name" && return 0
  [ -f "$MARK/$_name" ] && return 0
  release_mounts "$_name"
  _img="$IMGDIR/$_name"
  [ -f "$_img" ] || return 0
  if is_mounted "$_img"; then
    log "keep tmp $_name (image path still mounted)"
    return 0
  fi
  if always_image "$_name" || image_wins "$_tip" "$_img"; then
    retire_copy "$_tip" "tmp-$_name"
    log "image tmp $_name"
  else
    log "keep tmp $_name"
  fi
}

log "retire start mark=$MARK img=$IMGDIR"
for _data in "$MARK"/*.sh; do
  [ -f "$_data" ] || continue
  consider_mark "$_data"
done
for _tip in "$ST"/titan2-*.sh "$ST"/atlas-*.sh; do
  [ -f "$_tip" ] || continue
  consider_tmp "$_tip"
done
log "retire done"
exit 0
