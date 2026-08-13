#!/system/bin/sh
# titan2-subdisplay — OPTIMIZE Phase 3 peel: rear panel BL + plane
# SoT: docs/project/OPTIMIZE_SOURCE_PRODUCT.md · SUB_DISPLAY.md
# Invoked by pad-agent:
#   apply   — apply_subdisplay (BL + dual-plane mirror + sub_mode heal)
#   toggle  — side-key subdisplay_toggle flip + apply
#   version
# Digitizer IDC/assoc: titan2-pad-idc.sh (2.182); agent calls digitizer_post after apply.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
T2=/data/misc/titan2
ST=/data/local/tmp
SUB_VER=2.175-subdisplay-peel
SUBDISP_BL=/sys/devices/platform/mtk-leds1/leds/lcd-backlight1/brightness
SUBDISP_BL_MAX=/sys/devices/platform/mtk-leds1/leds/lcd-backlight1/max_brightness
LAST_FILE=$ST/titan2_subdisplay_last_key
STATUS=$ST/titan2_subdisplay_status

log() {
  mkdir -p "$ST" 2>/dev/null || true
  { echo "subdisplay: $*" >>"$ST/titan2_pad_agent.log"; } 2>/dev/null || true
}

_hb() {
  mkdir -p "$ST" 2>/dev/null || true
  { echo "pad-agent subdisplay $*" >"$ST/titan2_agent_status"; } 2>/dev/null || true
  chmod 666 "$ST/titan2_agent_status" 2>/dev/null || true
}

_read_line_file() {
  f="$1"
  [ -f "$f" ] || { echo ""; return 1; }
  v=""
  IFS= read -r v < "$f" || true
  case "$v" in *$'\r') v=${v%$'\r'} ;; esac
  echo "$v"
  return 0
}

read_first() {
  _n=$1
  for _d in "$T2" "$ST"; do
    [ -f "$_d/$_n" ] || continue
    _v=`cat "$_d/$_n" 2>/dev/null | tr -d '\r\n \t'`
    [ -n "$_v" ] && { echo "$_v"; return 0; }
  done
  echo ""
}

pick_subpanel_bin() {
  for b in /system/bin/titan2-subpanel-bl \
           /system_ext/bin/titan2-subpanel-bl \
           /data/local/tmp/subpanel_bl; do
    [ -x "$b" ] && { echo "$b"; return; }
  done
  echo ""
}

_mirror_subdisplay_plane() {
  _on="$1"
  _bri="$2"
  _id="$3"
  case "$_on" in 1|true|on|ON) _on=1;; *) _on=0;; esac
  case "$_bri" in ''|*[!0-9.]* ) _bri=1.00;; esac
  case "$_id" in ''|*[!0-9]*) _id=2;; esac
  for _d in "$T2" "$ST" /data/adb/titan2; do
    [ -d "$_d" ] || mkdir -p "$_d" 2>/dev/null || true
    { echo "$_on" > "$_d/titan2_subdisplay_on"; } 2>/dev/null || true
    { echo "$_bri" > "$_d/titan2_subdisplay_bri"; } 2>/dev/null || true
    { echo "$_id" > "$_d/titan2_subdisplay_id"; } 2>/dev/null || true
    chmod 666 "$_d/titan2_subdisplay_on" "$_d/titan2_subdisplay_bri" \
      "$_d/titan2_subdisplay_id" 2>/dev/null || true
  done
  chcon u:object_r:shell_data_file:s0 \
    "$T2/titan2_subdisplay_on" "$T2/titan2_subdisplay_bri" "$T2/titan2_subdisplay_id" \
    2>/dev/null || true
  settings put global titan2_subdisplay_on "$_on" 2>/dev/null || true
  settings put global titan2_subdisplay_bri "$_bri" 2>/dev/null || true
  settings put global titan2_subdisplay_id "$_id" 2>/dev/null || true
}

_read_subdisplay_on() {
  best_mt=0
  best_v=""
  for f in \
    "$T2/titan2_subdisplay_on" \
    "$ST/titan2_subdisplay_on" \
    /data/user/0/com.titanus2.controls/files/titan2_subdisplay_on \
    /data/data/com.titanus2.controls/files/titan2_subdisplay_on
  do
    [ -f "$f" ] || continue
    v=`_read_line_file "$f"`
    case "$v" in
      1|true|on|ON) v=1 ;;
      0|false|off|OFF) v=0 ;;
      *) continue ;;
    esac
    mt=`stat -c %Y "$f" 2>/dev/null` || mt=0
    case "$mt" in ''|*[!0-9]*) mt=0;; esac
    if [ "$mt" -ge "$best_mt" ] 2>/dev/null; then
      best_mt=$mt
      best_v=$v
    fi
  done
  if [ -z "$best_v" ]; then
    f=/data/adb/titan2/titan2_subdisplay_on
    if [ -f "$f" ]; then
      v=`_read_line_file "$f"`
      case "$v" in
        1|true|on|ON) best_v=1 ;;
        0|false|off|OFF) best_v=0 ;;
      esac
    fi
  fi
  if [ -z "$best_v" ]; then
    g=`settings get global titan2_subdisplay_on 2>/dev/null`
    case "$g" in
      1|true|on|ON) best_v=1 ;;
      0|false|off|OFF) best_v=0 ;;
    esac
  fi
  echo "${best_v:-0}"
}

_read_subdisplay_bri() {
  best_mt=0
  best_v=""
  for f in \
    "$T2/titan2_subdisplay_bri" \
    "$ST/titan2_subdisplay_bri" \
    /data/user/0/com.titanus2.controls/files/titan2_subdisplay_bri \
    /data/data/com.titanus2.controls/files/titan2_subdisplay_bri
  do
    [ -f "$f" ] || continue
    v=`_read_line_file "$f"`
    case "$v" in ''|*[!0-9.]*) continue ;; esac
    mt=`stat -c %Y "$f" 2>/dev/null` || mt=0
    case "$mt" in ''|*[!0-9]*) mt=0;; esac
    if [ "$mt" -ge "$best_mt" ] 2>/dev/null; then
      best_mt=$mt
      best_v=$v
    fi
  done
  if [ -z "$best_v" ]; then
    f=/data/adb/titan2/titan2_subdisplay_bri
    if [ -f "$f" ]; then
      v=`_read_line_file "$f"`
      case "$v" in ''|*[!0-9.]*|null|NULL) ;; *) best_v=$v ;; esac
    fi
  fi
  if [ -z "$best_v" ]; then
    g=`settings get global titan2_subdisplay_bri 2>/dev/null`
    case "$g" in ''|*[!0-9.]*|null|NULL) ;; *) best_v=$g ;; esac
  fi
  echo "${best_v:-1.00}"
}

apply_subdisplay() {
  on=`_read_subdisplay_on`
  bri=`_read_subdisplay_bri`
  idhint=`read_first titan2_subdisplay_id`
  case "$on" in 1|true|on|ON) on=1;; *) on=0;; esac
  case "$bri" in ''|*[!0-9.]* ) bri=1.00;; esac
  id="$idhint"
  case "$id" in ''|*[!0-9]*) id=2;; esac
  maxb=255
  if [ -r "$SUBDISP_BL_MAX" ]; then
    maxb=`cat "$SUBDISP_BL_MAX" 2>/dev/null`
  fi
  case "$maxb" in ''|*[!0-9]*) maxb=255;; esac
  hw=`busybox awk -v b="$bri" -v m="$maxb" 'BEGIN{v=int(b*m+0.5); if(v<0)v=0; if(v>m)v=m; print v}' 2>/dev/null`
  if [ -z "$hw" ]; then
    case "$bri" in
      0|0.0|0.00) hw=0 ;;
      1|1.0|1.00) hw=$maxb ;;
      *) hw=$(( maxb * 8 / 10 )) ;;
    esac
  fi
  [ "$on" = "1" ] || hw=0
  key="${on}:${hw}:${id}"
  LAST_SUBDISP=`cat "$LAST_FILE" 2>/dev/null | tr -d '\r\n '`
  SUBDISP_IOCTL_BIN=`pick_subpanel_bin`
  _bl_cur=`cat "$SUBDISP_BL" 2>/dev/null | tr -d '\r\n '`
  case "$_bl_cur" in ''|*[!0-9]*) _bl_cur=-1 ;; esac
  _need=0
  [ "$key" != "$LAST_SUBDISP" ] && _need=1
  if [ "$on" = "1" ] && [ "$hw" -gt 0 ] 2>/dev/null; then
    [ "$_bl_cur" != "$hw" ] && _need=1
    case "$_bl_cur" in 0|-1) _need=1 ;; esac
  fi
  if [ "$on" = "0" ] && [ "$_bl_cur" != "0" ] && [ "$_bl_cur" != "-1" ]; then
    _need=1
  fi
  if [ "$on" = "1" ]; then
    if [ "$_need" = "1" ]; then
      SUBDISP_IOCTL_BIN=`pick_subpanel_bin`
      if [ -n "$SUBDISP_IOCTL_BIN" ]; then
        "$SUBDISP_IOCTL_BIN" 1 >/dev/null 2>&1 || true
      fi
      if command -v timeout >/dev/null 2>&1; then
        timeout 0.4 cmd display set-brightness "$bri" --id "$id" >/dev/null 2>&1 || true
      fi
      echo "$hw" > "$SUBDISP_BL" 2>/dev/null || true
      echo "$hw" > /sys/class/leds/lcd-backlight1/brightness 2>/dev/null || true
      _hb "ON id=$id bri=$bri hw=$hw"
    fi
  elif [ "$_need" = "1" ]; then
    [ -n "$SUBDISP_IOCTL_BIN" ] && "$SUBDISP_IOCTL_BIN" 0 >/dev/null 2>&1 || true
    if command -v timeout >/dev/null 2>&1; then
      timeout 0.4 cmd display set-brightness 0 --id "$id" >/dev/null 2>&1 || true
    fi
    echo 0 > "$SUBDISP_BL" 2>/dev/null || true
    echo 0 > /sys/class/leds/lcd-backlight1/brightness 2>/dev/null || true
    _hb "OFF"
  fi
  { echo "$key" >"$LAST_FILE"; } 2>/dev/null || true
  chmod 666 "$LAST_FILE" 2>/dev/null || true
  _mirror_subdisplay_plane "$on" "$bri" "$id"
  if [ "$on" = "1" ]; then
    _sm=`read_first titan2_sub_mode`
    case "$_sm" in
      apps|app|face|clock|off|cube|lattice) ;;
      *)
        for _d in "$T2" "$ST"; do
          [ -d "$_d" ] || continue
          printf face >"$_d/titan2_sub_mode" 2>/dev/null || true
          chmod 666 "$_d/titan2_sub_mode" 2>/dev/null || true
        done
        ;;
    esac
  else
    for _d in "$T2" "$ST"; do
      [ -d "$_d" ] || continue
      printf off >"$_d/titan2_sub_mode" 2>/dev/null || true
      chmod 666 "$_d/titan2_sub_mode" 2>/dev/null || true
    done
  fi
  { echo "on=$on bri=$bri id=$id hw=$hw need=$_need key=$key" >"$STATUS"; } 2>/dev/null || true
  chmod 666 "$STATUS" 2>/dev/null || true
}

subdisplay_toggle() {
  cur=`_read_subdisplay_on`
  if [ "$cur" = "1" ]; then
    _mirror_subdisplay_plane 0 0 2
  else
    bri_now=`_read_subdisplay_bri`
    case "$bri_now" in ''|0|0.0|0.00) bri_now=1.00 ;; esac
    _mirror_subdisplay_plane 1 "$bri_now" 2
  fi
  rm -f "$LAST_FILE" 2>/dev/null || true
  apply_subdisplay
  SUBDISP_IOCTL_BIN=`pick_subpanel_bin`
  if [ -n "$SUBDISP_IOCTL_BIN" ]; then
    on_now=`_read_subdisplay_on`
    case "$on_now" in 1|true|on|ON) "$SUBDISP_IOCTL_BIN" 1 >/dev/null 2>&1 || true ;;
      *) "$SUBDISP_IOCTL_BIN" 0 >/dev/null 2>&1 || true ;;
    esac
  fi
  log "toggle on=$(_read_subdisplay_on) bin=${SUBDISP_IOCTL_BIN:-none}"
}

cmd="${1-apply}"
case "$cmd" in
  apply)
    apply_subdisplay
    ;;
  toggle)
    subdisplay_toggle
    ;;
  on)
    _read_subdisplay_on
    ;;
  bri)
    _read_subdisplay_bri
    ;;
  version|-v|--version)
    echo "$SUB_VER"
    ;;
  *)
    log "unknown cmd=$cmd"
    exit 1
    ;;
esac
exit 0
