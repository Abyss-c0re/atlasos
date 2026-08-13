#!/system/bin/sh
# titan2-sensor-privacy — Linux device plane for camera privacy (root init)
#
# Law: camera "off" = silicon unusable. Not Android theater. Not overlays.
#
# Desire: stock OS SensorPrivacy — cameratoggle (sensor=2) + mictoggle (sensor=1).
# Apply (Linux planes, kept APART):
#   CAM ON  → chmod 000 /dev/video* + fuser openers + force-stop camera apps
#   CAM OFF → chmod 660 instant
#   MIC ON  → force-stop recorder apps (enhance mictoggle). NEVER chmod pcm*c
#             (playback shares Hostless_Spk / FS19XX with capture on MTK).
#   MIC OFF → heal capture nodes 660 only
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

AO_CACHE=""; AO_OK=0; SP_CACHE=""; SP_OK=0

refresh_desire() {
  # Prefer dumpsys sensor_privacy (cheap enough at 1Hz).
  SP_CACHE=$(dumpsys sensor_privacy 2>/dev/null | head -c 4000)
  if echo "$SP_CACHE" | grep -q 'sensor='; then
    SP_OK=1; AO_OK=0; AO_CACHE=""; return 0
  fi
  SP_OK=0
  AO_CACHE=$(dumpsys appops 2>/dev/null | head -c 12000 \
    | sed 's/PHONE_CALL_CAMERA//g; s/PHONE_CALL_MICROPHONE//g; s/RECEIVE_SOUNDTRIGGER_AUDIO//g; s/RECEIVE_SANDBOX_TRIGGER_AUDIO//g')
  if echo "$AO_CACHE" | grep -q 'Global restrictions'; then AO_OK=1; else AO_OK=0; fi
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

cam_allow() {
  restore_camera_nodes
  if ! pidof camerahalserver >/dev/null 2>&1; then
    start camerahalserver 2>/dev/null || true
  fi
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

# Packages that may hold RECORD_AUDIO; skip telephony/system (OS handles call path).
# NEVER force-stop camera apps here — mic privacy must not kill camera preview
# when cam privacy is OFF (heretic residual: aperture DIED every ~few seconds).
mic_block() {
  # Always heal PCM open so speaker path stays alive
  restore_mic_nodes
  # Dedicated recorders / voice only — not camera, not browser, not messengers
  # (SPM + AppOps own those; force-stop is last resort for sticky recorders).
  for pkg in \
    org.lineageos.recorder com.android.soundrecorder \
    com.google.android.apps.recorder com.sec.android.app.voicenote \
    com.google.android.apps.speechservices; do
    am force-stop "$pkg" 2>/dev/null || true
  done
  restore_mic_nodes
  log "MIC block — recorders only (no camera kill; pcm 660 speaker safe)"
}

mic_allow() {
  restore_mic_nodes
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

# Boot: fail-closed first — never open nodes before desire known
refresh_desire
privacy_cam_on
_rc=$?
last_cam=0
if [ "$_rc" = "0" ] || [ "$_rc" = "2" ]; then
  cam_block
  last_cam=1
  log "boot CAM blocked (linux fail-closed rc=$_rc)"
else
  cam_allow
  last_cam=0
  log "boot CAM allowed (SPM OFF)"
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

log "titan2-sensor-privacy ONLINE v23 (INTERVAL_S=1 steady + sp_wake impulse; no 20Hz dumpsys heat)"
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
      cam_block) cam_block; last_cam=1 ;;
      cam_allow) cam_allow; last_cam=0 ;;
      mic_block) mic_block 2>/dev/null || true; last_mic=1 ;;
      mic_allow) mic_allow 2>/dev/null || true; last_mic=0 ;;
    esac
    torch_bridge_tick
    # Tight re-sample after human impulse
    usleep 20000 2>/dev/null || sleep 0.02
  fi
  if ! belt_enabled; then
    if [ "$last_cam" = "1" ] || [ "$last_mic" = "1" ] || [ ! -f "${MARKER}.idle" ]; then
      log "belt DISABLED — allow cam+mic"
      cam_allow
      mic_allow
      last_cam=0
      last_mic=0
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
      log "CAM allow — chmod 660 instant"
      cam_allow
    else
      # Privacy OFF: keep nodes open. Never fuser/kill clients.
      if [ $((TICK % 5)) -eq 0 ]; then
        case "$(ls -l /dev/video0 2>/dev/null | cut -c1-10)" in
          c---------*|c---*---*) restore_camera_nodes ;;
        esac
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
    if [ "$last_mic" != "1" ]; then
      log "MIC block — edge only (never periodic force-stop)"
      mic_block
    else
      # Steady: pcm heal only. Periodic force-stop was heresy — killed
      # org.lineageos.aperture every few seconds while cam privacy OFF.
      restore_mic_nodes
    fi
  else
    if [ "$last_mic" = "1" ]; then
      log "MIC allow — pcm heal only"
      mic_allow
    else
      restore_mic_nodes
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
