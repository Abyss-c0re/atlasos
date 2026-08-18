#!/system/bin/sh
# titan2-sensor-privacy — Linux device plane for camera privacy (root init)
#
# Law: camera "off" = silicon unusable. Not Android theater. Not overlays.
#
# Desire: stock OS SensorPrivacy — cameratoggle (sensor=2) + mictoggle (sensor=1).
# Apply (Linux planes, kept APART):
#   CAM ON  → chmod 000 /dev/video* + fuser openers + force-stop camera apps
#   CAM OFF → chmod 660 instant
#   MIC ON  → ADC/PGA mux Idle + sidetone off (vendor audio_device.xml).
#             NEVER chmod pcm*c (Hostless_Spk / FS19XX share capture with speaker).
#   MIC OFF → heal PCM 660; HAL restores AIN0 on next record/call
#   TORCH   → separate plane: flashlight_core sysfs, independent of video nodes.
#             Never kill cameraserver/camerahalserver for privacy.
#
# 1.21-planes-apart — cam / mic / torch / speaker are separate kill switches.
export PATH=/system/bin:/system/xbin:/vendor/bin:/sbin:$PATH
LOG=/data/local/tmp/titan2_sensor_privacy.log
MARKER=/data/local/tmp/titan2_sp
PIDF=${PIDF:-/data/local/tmp/titan2_sp.pid}
# Belt is re-assert + fail-closed only; QS/Controls ImpulseSnap stamps
# titan2_sp_wake for cam/torch NOW (below). Steady poll must not thrash:
# INTERVAL 0.05 → dumpsys sensor_privacy ~20/s reheats load≈16 and delayed
# key UP looks like multi-fire (InputReader KeyRepeatTimeout). 1s steady +
# wake-file impulse is the single-owner path.
INTERVAL_S=${INTERVAL_S:-1}

log() {
  echo "$(date +%Y%m%dT%H%M%S) $*" >>"$LOG" 2>/dev/null
  chmod 644 "$LOG" 2>/dev/null
}

belt_enabled() {
  v=$(getprop persist.titan2.privacy_belt 2>/dev/null | tr -d '\r')
  case "$v" in 0|false|off|OFF) return 1 ;; *) return 0 ;; esac
}

AO_CACHE=""; AO_OK=0; SP_CACHE=""; SP_OK=0; SP_TS=0

refresh_desire() {
  # dumpsys sensor_privacy only — never dumpsys appops (12k + parse reheats load).
  # Cache 2s: analog re-assert uses last mic bit; HAL re-plugs AIN on call start.
  now=$(date +%s 2>/dev/null || echo 0)
  case "$now" in ''|*[!0-9]*) now=0 ;; esac
  if [ "$SP_OK" = "1" ] && [ "$now" -gt 0 ] && [ "$SP_TS" -gt 0 ]; then
    age=$((now - SP_TS))
    if [ "$age" -ge 0 ] && [ "$age" -lt 2 ]; then
      return 0
    fi
  fi
  SP_CACHE=$(dumpsys sensor_privacy 2>/dev/null | head -c 4000)
  if echo "$SP_CACHE" | grep -q 'sensor='; then
    SP_OK=1; AO_OK=0; AO_CACHE=""; SP_TS=$now; return 0
  fi
  SP_OK=0
  AO_OK=0
}

# sensor=2 CAMERA; state_type 1=privacy ON, 2=OFF
privacy_cam_on() {
  if [ "$SP_OK" = "1" ]; then
    st=$(echo "$SP_CACHE" | tr '\n' ' ' | sed -n 's/.*sensor=2[^}]*state_type=\([0-9]\).*/\1/p' | head -1)
    [ "$st" = "1" ] && return 0
    [ "$st" = "2" ] && return 1
  fi
  if [ "$AO_OK" = "1" ]; then
    echo "$AO_CACHE" | grep -E 'Global restrictions' -A3 | tr '\n' ' ' \
      | grep -E 'Restricted ops:[[:space:]]*\[[^]]*\bCAMERA\b' >/dev/null 2>&1 && return 0
    return 1
  fi
  return 2
}

privacy_mic_on() {
  if [ "$SP_OK" = "1" ]; then
    st=$(echo "$SP_CACHE" | tr '\n' ' ' | sed -n 's/.*sensor=1[^}]*state_type=\([0-9]\).*/\1/p' | head -1)
    [ "$st" = "1" ] && return 0
    [ "$st" = "2" ] && return 1
  fi
  if [ "$AO_OK" = "1" ]; then
    echo "$AO_CACHE" | grep -E 'Global restrictions' -A3 | tr '\n' ' ' \
      | grep -E 'Restricted ops:[[:space:]]*\[[^]]*\bRECORD_AUDIO\b' >/dev/null 2>&1 && return 0
    return 1
  fi
  return 2
}

_is_protected_capture_pcm() { return 0; }
_is_fm_capture_pcm() { return 0; }

# --- TORCH plane (APART from camera) ---
# Camera privacy zeros /dev/video* → CameraService device list can go empty →
# setTorchMode dies. LED is still driven by MTK flashlight_core sysfs.
TORCH_SYSFS=/sys/devices/virtual/flashlight_core/flashlight/flashlight_torch
[ -e "$TORCH_SYSFS" ] || TORCH_SYSFS=/sys/class/flashlight_core/flashlight/flashlight_torch
LAST_TORCH=-1

torch_sysfs_set() {
  on="$1" # 0|1
  # Prefer class path (always present on MTK); fall back to virtual.
  path=$TORCH_SYSFS
  [ -e "$path" ] || path=/sys/class/flashlight_core/flashlight/flashlight_torch
  [ -e "$path" ] || path=/sys/devices/virtual/flashlight_core/flashlight/flashlight_torch
  [ -e "$path" ] || return 1
  # type0 ct0 + ct1 (dual color temp LEDs) — printf, not echo (toybox spaces)
  printf '0 0 0 %s\n' "$on" >"$path" 2>/dev/null || return 1
  printf '0 1 0 %s\n' "$on" >"$path" 2>/dev/null || true
  return 0
}

# Honor QS / Settings flashlight desire via Secure/System; apply on sysfs.
# Independent of cam privacy and video nodes.
torch_bridge_tick() {
  # Product Flashlight tile writes titan2_torch_on (AgentBridge). Prefer that ONLY —
  # stock SystemUI fights Secure.flashlight_enabled (zeros it under camera privacy).
  want=
  best_m=0
  for tf in /data/local/tmp/titan2_torch_on /data/misc/titan2/titan2_torch_on; do
    [ -f "$tf" ] || continue
    tv=$(tr -d '\r\n \t' <"$tf" 2>/dev/null)
    case "$tv" in
      1|true|on|ON|0|false|off|OFF) ;;
      *) continue ;;
    esac
    m=$(stat -c %Y "$tf" 2>/dev/null || echo 0)
    case "$m" in ''|*[!0-9]*) m=0 ;; esac
    if [ "$m" -ge "$best_m" ]; then
      best_m=$m
      case "$tv" in
        1|true|on|ON) want=1 ;;
        *) want=0 ;;
      esac
    fi
  done
  # Fallback: settings only if no product plane file (legacy).
  if [ -z "$want" ]; then
    fe=$(settings get secure flashlight_enabled 2>/dev/null | tr -d '\r\n \t')
    [ -z "$fe" ] || [ "$fe" = "null" ] && fe=$(settings get system flashlight_enabled 2>/dev/null | tr -d '\r\n \t')
    case "$fe" in
      1|true|on|ON) want=1 ;;
      *) want=0 ;;
    esac
  fi
  if [ "$want" != "$LAST_TORCH" ]; then
    if torch_sysfs_set "$want"; then
      log "TORCH plane sysfs=$want (product plane; cam privacy OK)"
      LAST_TORCH=$want
    else
      log "TORCH plane FAIL want=$want"
    fi
  elif [ "$want" = "1" ] && [ $((TICK % 3)) -eq 0 ]; then
    # Re-assert ON — CameraManager / heat may clear LED under privacy.
    torch_sysfs_set 1 || true
  fi
}

# --- Linux apply: camera (video only — never torch/audio) ---
kill_video_holders() {
  for n in /dev/video*; do
    [ -e "$n" ] || continue
    fuser -k "$n" 2>/dev/null || true
  done
  for pkg in org.lineageos.aperture com.mediatek.camera com.android.camera2 \
             com.google.android.GoogleCamera com.android.camera; do
    am force-stop "$pkg" 2>/dev/null || true
  done
}

revoke_camera_nodes() {
  for n in /dev/video*; do
    [ -e "$n" ] || continue
    chmod 000 "$n" 2>/dev/null
  done
}

restore_camera_nodes() {
  for n in /dev/video*; do
    [ -e "$n" ] || continue
    chmod 660 "$n" 2>/dev/null
  done
  chown media:system /dev/video0 /dev/video1 2>/dev/null
  chown camera:system /dev/video[2-9] /dev/video[1-9][0-9]* 2>/dev/null
}

cam_nodes_open() {
  mode=$(ls -l /dev/video0 2>/dev/null | cut -c1-10)
  case "$mode" in
    c---------*|c---*---*|'') return 1 ;;
    *) return 0 ;;
  esac
}

# Fail-closed camera streams. NEVER kill cameraserver/camerahalserver (torch).
# kill_video_holders only on edge block — not on steady re-assert (avoids
# DIED client thrash when privacy ON and user still has camera UI open).
cam_block() {
  revoke_camera_nodes
  kill_video_holders
  revoke_camera_nodes
  if ! pidof camerahalserver >/dev/null 2>&1; then
    start camerahalserver 2>/dev/null || true
  fi
}

# HI847S (id 2) is SYSTEM_CAMERA. CameraProviderManager publishes it only
# when persist.sys.phh.include_all_cameras=true at first enum. Init stamps
# that + aux packagelist at early-init (before HAL). Never dumpsys-gate.
# Privacy ON (nodes 000): stamp only — do not bounce HAL.
# Privacy OFF miss: init recycle so 0+1+2+3 all publish.
_AUX_PKGS="org.lineageos.aperture,org.lineageos.aperture.lenslauncher"
_AUX_PUB_TS=0
aux_cam_stamp() {
  setprop persist.sys.phh.include_all_cameras true 2>/dev/null || true
  setprop camera.aux.packagelist "$_AUX_PKGS" 2>/dev/null || true
  setprop vendor.camera.aux.packagelist "$_AUX_PKGS" 2>/dev/null || true
  setprop persist.camera.aux.packagelist "$_AUX_PKGS" 2>/dev/null || true
  setprop persist.vendor.camera.aux.packagelist "$_AUX_PKGS" 2>/dev/null || true
  setprop persist.vendor.camera.privapp.list org.lineageos.aperture 2>/dev/null || true
}
aux_cam_nodes_blocked() {
  mode=$(ls -l /dev/video0 2>/dev/null | cut -c1-10)
  case "$mode" in c---------*|c---*---*|'') return 0 ;; esac
  return 1
}
_aux_svc_up() {
  [ "$(getprop init.svc.camerahalserver | tr -d '\r')" = running ] \
    && [ "$(getprop init.svc.cameraserver | tr -d '\r')" = running ]
}
_aux_hal_up() {
  [ "$(getprop init.svc.camerahalserver | tr -d '\r')" = running ]
}
# MTK HAL must finish probing HI847S before CameraService enums.
# Recycle before this line → ADD 0+1 only (lab T2128Z v32).
_aux_hal_has_hi847s() {
  logcat -d -b main 2>/dev/null | grep -q 'HI847S_MIPI_RAW:2 is installed'
}
# Last CameraService enum only (stale ADD 2 from a prior instance is a lie).
# Never dumpsys media.camera (hangs).
_aux_device2_added() {
  logcat -d -b main -b system -s CameraService:I 2>/dev/null \
    | grep 'ADD device' | tail -8 | grep -q 'ADD device 2'
}
# $1=force — skip 20s debounce (privacy OFF edge / boot miss).
aux_cam_publish() {
  aux_cam_stamp
  if aux_cam_nodes_blocked; then
    log "aux stamp only (privacy fail-closed — all cameras off)"
    return 0
  fi
  if [ "${1:-}" != "force" ] && _aux_device2_added; then
    log "aux already ADD 2 — no bounce"
    return 0
  fi
  now=$(date +%s 2>/dev/null || echo 0)
  case "$now" in ''|*[!0-9]*) now=0 ;; esac
  if [ "${1:-}" != "force" ] && [ "$now" -gt 0 ] && [ "$_AUX_PUB_TS" -gt 0 ]; then
    age=$((now - _AUX_PUB_TS))
    [ "$age" -ge 0 ] && [ "$age" -lt 20 ] && return 0
  fi
  # boot_completed: first HAL probe is done. Recycle, then give the
  # restarted HAL 15s to re-probe HI847S. 3s was too short (T2128Z).
  _w=0
  while [ "$_w" -lt 20 ]; do
    _aux_hal_up && break
    sleep 1
    _w=$((_w + 1))
  done
  aux_cam_stamp
  _AUX_PUB_TS=$(date +%s 2>/dev/null || echo 0)
  setprop sys.titan2.aux_pub 1
  sleep 5
  hs=$(getprop init.svc.camerahalserver | tr -d '\r')
  cs=$(getprop init.svc.cameraserver | tr -d '\r')
  log "aux after stop hal=$hs cam=$cs"
  aux_cam_stamp
  setprop sys.titan2.aux_pub 2
  _w=0
  while [ "$_w" -lt 20 ]; do
    _aux_hal_up && break
    sleep 1
    _w=$((_w + 1))
  done
  sleep 15
  setprop sys.titan2.aux_pub 3
  _w=0
  while [ "$_w" -lt 20 ]; do
    _aux_svc_up && break
    sleep 1
    _w=$((_w + 1))
  done
  sleep 8
  restore_camera_nodes
  aux_cam_stamp
  setprop sys.titan2.aux_pub 0
  if _aux_device2_added; then
    log "aux published init recycle (HI847S ADD 2)"
  else
    log "aux recycle missed ADD 2"
    return 1
  fi
}

cam_allow() {
  restore_camera_nodes
  # HAL crash-loops on 000 nodes and stays empty after chmod unless bounced.
  hs=$(getprop init.svc.camerahalserver 2>/dev/null | tr -d '\r')
  case "$hs" in
    restarting|stopped|'')
      start camerahalserver 2>/dev/null || true
      ;;
  esac
  if ! pidof cameraserver >/dev/null 2>&1; then
    start cameraserver 2>/dev/null || true
  fi
  usleep 50000 2>/dev/null || sleep 0.05 2>/dev/null || true
  restore_camera_nodes
}

# --- MIC plane (enhance stock mictoggle): kill recorders, NEVER touch playback ---
# Binding lab 2026-08-07: chmod /dev/snd/pcm*c silences speaker (Hostless_Spk/FS19XX).
restore_mic_nodes() {
  for n in /dev/snd/pcm*c; do
    [ -e "$n" ] || continue
    chmod 660 "$n" 2>/dev/null
  done
  chown system:audio /dev/snd/pcm*c 2>/dev/null
  if [ -e /dev/fm ]; then
    chmod 660 /dev/fm 2>/dev/null
    chown media:media /dev/fm 2>/dev/null || chown system:audio /dev/fm 2>/dev/null
  fi
}

# Analog disconnect from vendor audio_device.xml (builtin_Mic_Mic1 turnoff).
# ADC mux Idle = preamp unplugged. Sidetone Filter off = no local echo.
# Re-assert every tick: speech HAL re-enables AIN0 on call start.
# DAC / HPL / Ext_Speaker_Amp never touched.
tinymix_bin() {
  if [ -x /vendor/bin/tinymix ]; then echo /vendor/bin/tinymix
  elif [ -x /system/bin/tinymix ]; then echo /system/bin/tinymix
  else echo tinymix
  fi
}

analog_mic_disconnect() {
  MIX=$(tinymix_bin)
  command -v "$MIX" >/dev/null 2>&1 || { log "MIC analog: no tinymix"; return 1; }
  "$MIX" 'ADC_L_Mux' Idle >/dev/null 2>&1 || true
  "$MIX" 'ADC_R_Mux' Idle >/dev/null 2>&1 || true
  "$MIX" 'PGA_L_Mux' None >/dev/null 2>&1 || true
  "$MIX" 'PGA_R_Mux' None >/dev/null 2>&1 || true
  "$MIX" 'Sidetone Filter Switch' 0 >/dev/null 2>&1 || true
}

# vendor audio_device.xml builtin_Mic_DualMic turnon — analog must come back.
# Recorder is Mic1 (ADC_L / AIN0). Call HAL DualMic-off leaves L Idle;
# re-assert on every allow tick, not only the privacy edge.
analog_mic_reconnect() {
  MIX=$(tinymix_bin)
  command -v "$MIX" >/dev/null 2>&1 || return 1
  "$MIX" 'MISO0_MUX' UL1_CH1 >/dev/null 2>&1 || true
  "$MIX" 'MISO1_MUX' UL1_CH2 >/dev/null 2>&1 || true
  "$MIX" 'ADC_L_Mux' 'Left Preamplifier' >/dev/null 2>&1 || true
  "$MIX" 'PGA_L_Mux' AIN0 >/dev/null 2>&1 || true
  "$MIX" 'ADC_R_Mux' 'Right Preamplifier' >/dev/null 2>&1 || true
  "$MIX" 'PGA_R_Mux' AIN2 >/dev/null 2>&1 || true
  "$MIX" 'UL_SRC_MUX' AMIC >/dev/null 2>&1 || true
  "$MIX" 'UL2_SRC_MUX' AMIC >/dev/null 2>&1 || true
}

# NEVER force-stop camera apps here — mic privacy must not kill camera preview
# when cam privacy is OFF (heretic residual: aperture DIED every ~few seconds).
mic_block() {
  restore_mic_nodes
  analog_mic_disconnect
  for pkg in \
    org.lineageos.recorder com.android.soundrecorder \
    com.google.android.apps.recorder com.sec.android.app.voicenote \
    com.google.android.apps.speechservices; do
    am force-stop "$pkg" 2>/dev/null || true
  done
  restore_mic_nodes
  log "MIC block — analog ADC Idle + sidetone off (pcm 660 speaker safe)"
}

mic_allow() {
  restore_mic_nodes
  analog_mic_reconnect
  log "MIC allow — ADC mux AIN0/AIN2 restored"
}

seed_qs_tiles() {
  raw=$(settings get secure sysui_qs_tiles 2>/dev/null | tr -d '\r')
  case "$raw" in ''|null|NULL) return 0 ;; esac
  next=''; has_cam=0; has_mic=0; has_torch=0
  TORCH_TILE='custom(com.titanus2.controls/.TorchTileService)'
  old_ifs=$IFS; IFS=,; set -- $raw; IFS=$old_ifs
  for p in "$@"; do
    p=$(echo "$p" | tr -d '\r\n ')
    [ -n "$p" ] || continue
    case "$p" in
      *PrivateModeTileService*|*\.privacy/*|*CameraPrivacyTileService*|*MicPrivacyTileService*) continue ;;
      # Stock flashlight grays under camera privacy — replace with product Torch tile.
      flashlight|*.flashlight|*FlashlightTile*)
        [ "$has_torch" = "0" ] && { [ -n "$next" ] && next="$next,$TORCH_TILE" || next="$TORCH_TILE"; has_torch=1; }
        continue ;;
      *TorchTileService*)
        [ "$has_torch" = "0" ] && { [ -n "$next" ] && next="$next,$TORCH_TILE" || next="$TORCH_TILE"; has_torch=1; }
        continue ;;
      cameratoggle|*.cameratoggle|*CameraToggleTile*)
        [ "$has_cam" = "0" ] && { [ -n "$next" ] && next="$next,cameratoggle" || next="cameratoggle"; has_cam=1; }
        continue ;;
      mictoggle|*.mictoggle|*MicrophoneToggleTile*)
        [ "$has_mic" = "0" ] && { [ -n "$next" ] && next="$next,mictoggle" || next="mictoggle"; has_mic=1; }
        continue ;;
    esac
    [ -n "$next" ] && next="$next,$p" || next="$p"
  done
  [ "$has_cam" = "0" ] && { [ -n "$next" ] && next="$next,cameratoggle" || next="cameratoggle"; }
  [ "$has_mic" = "0" ] && { [ -n "$next" ] && next="$next,mictoggle" || next="mictoggle"; }
  [ "$has_torch" = "0" ] && { [ -n "$next" ] && next="$next,$TORCH_TILE" || next="$TORCH_TILE"; }
  [ "$next" = "$raw" ] && return 0
  settings put secure sysui_qs_tiles "$next" 2>/dev/null || true
  cmd statusbar set-tiles "$next" >/dev/null 2>&1 && log "QS cam+mic+torch (product Torch, not stock flashlight)"
  pm disable-user --user 0 com.titanus2.controls/.CameraPrivacyTileService >/dev/null 2>&1 || true
  pm disable-user --user 0 com.titanus2.controls/.MicPrivacyTileService >/dev/null 2>&1 || true
  pm enable com.titanus2.controls/.TorchTileService >/dev/null 2>&1 || true
}

# --- single instance ---
_self=$$
_ppid=$PPID
if [ -f "$PIDF" ]; then
  _old=$(cat "$PIDF" 2>/dev/null | tr -d '\r\n ')
  case "$_old" in
    ''|0|1|*[!0-9]*) ;;
    *) [ "$_old" != "$_self" ] && [ "$_old" != "$_ppid" ] && kill "$_old" 2>/dev/null || true ;;
  esac
fi
# prune peers
for _p in $(pidof titan2-sensor-privacy.sh 2>/dev/null); do
  [ "$_p" = "$_self" ] && continue
  kill "$_p" 2>/dev/null || true
done

# boot wait (short)
i=0
while [ $i -lt 60 ]; do
  b=$(getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  [ "$b" = "1" ] && break
  i=$((i + 1)); sleep 1
done

echo $$ >"$PIDF" 2>/dev/null || true
chmod 666 "$PIDF" 2>/dev/null || true

# Boot: wait for SensorPrivacy dumpsys before fail-closed.
# Unreadable SPM (rc=2) used to chmod 000 /dev/video* and then die —
# toggle later said allowed, HAL crash-looped, CameraService devices=0.
i=0
while [ $i -lt 15 ]; do
  refresh_desire
  [ "$SP_OK" = "1" ] && break
  i=$((i + 1)); sleep 1
done
refresh_desire
privacy_cam_on
_rc=$?
last_cam=0
if [ "$_rc" = "0" ]; then
  cam_block
  last_cam=1
  aux_cam_stamp
  log "boot CAM blocked (linux fail-closed rc=$_rc)"
elif [ "$_rc" = "2" ]; then
  cam_allow
  last_cam=0
  aux_cam_stamp
  log "boot CAM allow (SPM unread — do not strand video nodes)"
else
  cam_allow
  last_cam=0
  aux_cam_stamp
  log "boot CAM allowed (SPM OFF)"
fi
# First enum is usually 0+1 until HAL has probed HI847S and we recycle.
# Wait for that probe, then one recycle. Instant double-retry kills enum.
if [ "$last_cam" = "0" ]; then
  if _aux_device2_added; then
    log "boot HI847S already public (early stamp)"
  else
    aux_cam_publish force
    if ! _aux_device2_added; then
      sleep 8
      aux_cam_publish force
    fi
  fi
fi
privacy_mic_on
_rcm=$?
if [ "$_rcm" = "0" ] || [ "$_rcm" = "2" ]; then
  mic_block
  last_mic=1
  log "boot MIC blocked (mictoggle enhance; pcm open for speaker)"
else
  mic_allow
  last_mic=0
  log "boot MIC allowed"
fi

log "titan2-sensor-privacy ONLINE v33 (wait HI847S probe then recycle; privacy-off only)"
seed_qs_tiles
TICK=0
[ -f "$LOG" ] && [ "$(wc -c <"$LOG" 2>/dev/null || echo 0)" -gt 200000 ] && : >"$LOG"

# ImpulseSnap (app uid) cannot *create* under /data/local/tmp (EACCES).
# Keep a world-writable wake inode; consume by truncate, never rm.
_sp_wake=/data/local/tmp/titan2_sp_wake
: >"$_sp_wake" 2>/dev/null || true
chmod 666 "$_sp_wake" 2>/dev/null || true

# Main loop: always re-read desire every INTERVAL_S. Planes applied separately.
while true; do
  # CubalC free-flow: Controls ImpulseSnap stamps sp_wake — apply cam/torch NOW.
  if [ -s "$_sp_wake" ]; then
    _w=$(tr -d '\r\n \t' <"$_sp_wake" 2>/dev/null)
    : >"$_sp_wake" 2>/dev/null || true
    chmod 666 "$_sp_wake" 2>/dev/null || true
    case "$_w" in
      cam_block) cam_block; last_cam=1; aux_cam_stamp ;;
      cam_allow) cam_allow; last_cam=0; aux_cam_publish force ;;
      aux_pub) aux_cam_publish force ;;
      mic_block) mic_block 2>/dev/null || true; last_mic=1 ;;
      mic_allow) mic_allow 2>/dev/null || true; last_mic=0 ;;
    esac
    torch_bridge_tick
    # Tight re-sample after human impulse
    usleep 20000 2>/dev/null || sleep 0.02
  fi
  if ! belt_enabled; then
    if [ "$last_cam" = "1" ] || [ "$last_mic" = "1" ] \
        || [ ! -f "${MARKER}.idle" ] || ! cam_nodes_open; then
      log "belt DISABLED — allow cam+mic (restore leftover 000)"
      cam_allow
      mic_allow
      last_cam=0
      last_mic=0
      aux_cam_publish force
      touch "${MARKER}.idle" 2>/dev/null
    fi
    torch_bridge_tick
    sleep 0.2
    continue
  fi
  rm -f "${MARKER}.idle" 2>/dev/null
  TICK=$((TICK + 1))

  refresh_desire

  # --- CAM plane (cameratoggle) ---
  privacy_cam_on
  rc_cam=$?
  cam=0
  [ "$rc_cam" = "0" ] && cam=1
  [ "$rc_cam" = "2" ] && cam=1
  priv=$(settings get secure titan2_private_mode 2>/dev/null | tr -d '\r')
  [ "$priv" = "1" ] && cam=1

  if [ "$cam" = "1" ]; then
    if [ "$last_cam" != "1" ]; then
      log "CAM block — video nodes only (torch plane separate)"
      cam_block
    else
      # Steady privacy ON: re-revoke only if nodes leaked open (no fuser thrash).
      if cam_nodes_open; then
        log "CAM re-block — nodes leaked"
        revoke_camera_nodes
      fi
    fi
  else
    if [ "$last_cam" = "1" ]; then
      log "CAM allow — chmod 660 instant + publish all lenses"
      cam_allow
      aux_cam_publish force
    else
      # Privacy OFF: keep nodes open. Re-heal leftover 000 every tick.
      if ! cam_nodes_open; then
        log "CAM re-allow — nodes still 000 while toggle allowed"
        cam_allow
        aux_cam_publish
      fi
    fi
  fi

  # --- MIC plane (mictoggle enhance) ---
  privacy_mic_on
  rc_mic=$?
  mic=0
  [ "$rc_mic" = "0" ] && mic=1
  [ "$rc_mic" = "2" ] && mic=1
  [ "$priv" = "1" ] && mic=1

  if [ "$mic" = "1" ]; then
    # HAL re-plugs AIN0 on every speech/record start. Hold Idle while ON.
    analog_mic_disconnect
    if [ "$last_mic" != "1" ]; then
      log "MIC block — edge (recorders once)"
      mic_block
    else
      restore_mic_nodes
    fi
  else
    if [ "$last_mic" = "1" ]; then
      log "MIC allow — analog reconnect"
      mic_allow
    else
      restore_mic_nodes
      analog_mic_reconnect
    fi
  fi

  # --- TORCH plane (always; independent of cam/mic) ---
  torch_bridge_tick

  if [ $((TICK % 20)) -eq 0 ]; then
    q=$(settings get secure sysui_qs_tiles 2>/dev/null | tr -d '\r')
    case "$q" in
      *CameraPrivacyTileService*|*MicPrivacyTileService*) seed_qs_tiles ;;
      *cameratoggle*) : ;;
      *) seed_qs_tiles ;;
    esac
  fi

  last_cam=$cam
  last_mic=$mic
  sleep "$INTERVAL_S" 2>/dev/null || sleep 1
done
