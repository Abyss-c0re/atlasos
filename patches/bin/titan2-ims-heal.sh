#!/system/bin/sh
# titan2-ims-heal — OPTIMIZE Phase 3 peel from pad-agent tower
# SoT: docs/project/OPTIMIZE_SOURCE_PRODUCT.md · INCOMING_CALLS_IMS.md
# Invoked by pad-agent:
#   action | sub_defaults | bind_all | auto_sub | props | version
# Never downloads third-party IMS APKs.
# props (2.173): IMS/telephony setprop plane + apply_ims_action + status.
# BT plane files apply only if present; missing = leave persist (TrebleApp SoT).
# Agent still runs apply_dev_action + apply_subdisplay after props.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
T2=/data/misc/titan2
ST=/data/local/tmp
IMS_STATUS=$ST/titan2_ims_status
IMS_PROPS_STATE=$ST/titan2_ims_props_last
IMS_VER=2.174-calls-sot

log() {
  echo "ims-heal: $*" >>"$ST/titan2_pad_agent.log" 2>/dev/null || true
}

# Minimal control-plane read (T2 then ST) — same SoT as pad-agent
read_first() {
  _n=$1
  for _d in "$T2" "$ST"; do
    [ -f "$_d/$_n" ] || continue
    _v=`cat "$_d/$_n" 2>/dev/null | tr -d '\r\n \t'`
    [ -n "$_v" ] && { echo "$_v"; return 0; }
  done
  echo ""
}

clear_ctrl_name() {
  _n=$1
  for _d in "$T2" "$ST"; do
    rm -f "$_d/$_n" 2>/dev/null || true
  done
}

# Root-powered persistence (T2 only) — avoid mtime thrash when unchanged.
persist_ctrl() {
  name="$1"; val="$2"
  [ -n "$val" ] || return 0
  mkdir -p "$T2" 2>/dev/null || true
  cur=`cat "$T2/$name" 2>/dev/null | tr -d '\r\n '`
  if [ "$cur" = "$val" ]; then
    last=`cat "$T2/${name}_last" 2>/dev/null | tr -d '\r\n '`
    if [ "$last" != "$val" ]; then
      { echo "$val" >"$T2/${name}_last"; } 2>/dev/null || true
      chmod 666 "$T2/${name}_last" 2>/dev/null || true
    fi
    return 0
  fi
  { echo "$val" >"$T2/$name"; } 2>/dev/null || true
  chmod 666 "$T2/$name" 2>/dev/null || true
  { echo "$val" >"$T2/${name}_last"; } 2>/dev/null || true
  chmod 666 "$T2/${name}_last" 2>/dev/null || true
}

_load_ims_props_state() {
  LAST_IMS_MTK=""; LAST_IMS_FORCE=""; LAST_IMS_BINDER=""
  LAST_TEL_5G=""; LAST_TEL_VCI=""; LAST_TEL_RIL=""; LAST_TEL_SMSC=""
  LAST_BT_SYSBTA=""; LAST_BT_APCF=""; LAST_BT_WA=""; LAST_BT_ESCO=""
  [ -f "$IMS_PROPS_STATE" ] || return 0
  # shellcheck disable=SC1090
  . "$IMS_PROPS_STATE" 2>/dev/null || true
}

_save_ims_props_state() {
  mkdir -p "$ST" 2>/dev/null || true
  {
    echo "LAST_IMS_MTK='$LAST_IMS_MTK'"
    echo "LAST_IMS_FORCE='$LAST_IMS_FORCE'"
    echo "LAST_IMS_BINDER='$LAST_IMS_BINDER'"
    echo "LAST_TEL_5G='$LAST_TEL_5G'"
    echo "LAST_TEL_VCI='$LAST_TEL_VCI'"
    echo "LAST_TEL_RIL='$LAST_TEL_RIL'"
    echo "LAST_TEL_SMSC='$LAST_TEL_SMSC'"
    echo "LAST_BT_SYSBTA='$LAST_BT_SYSBTA'"
    echo "LAST_BT_APCF='$LAST_BT_APCF'"
    echo "LAST_BT_WA='$LAST_BT_WA'"
    echo "LAST_BT_ESCO='$LAST_BT_ESCO'"
  } >"$IMS_PROPS_STATE" 2>/dev/null || true
  chmod 666 "$IMS_PROPS_STATE" 2>/dev/null || true
}

# Root applies IMS/telephony props from Titan Controls (priv_app cannot setprop).
# BT persist is TrebleApp Misc SoT. This script does not write
# persist.sys.bt.unsupported.* / sysbta / disable_apcf.
apply_ims_props() {
  _load_ims_props_state
  mtk=`read_first titan2_ims_mtk`
  force=`read_first titan2_ims_force_volte`
  binder=`read_first titan2_ims_binder`
  [ -n "$mtk" ] || mtk=1
  [ -n "$force" ] || force=1
  [ -n "$binder" ] || binder=1

  case "$mtk" in 1|true|on|ON|yes|YES)
    if [ "$LAST_IMS_MTK" != "1" ]; then
      setprop persist.sys.phh.ims.mtk true 2>/dev/null || true
      LAST_IMS_MTK=1
    fi
    ;;
  *)
    if [ "$LAST_IMS_MTK" != "0" ]; then
      setprop persist.sys.phh.ims.mtk false 2>/dev/null || true
      LAST_IMS_MTK=0
    fi
    ;;
  esac

  case "$force" in 1|true|on|ON|yes|YES)
    if [ "$LAST_IMS_FORCE" != "1" ]; then
      setprop persist.dbg.volte_avail_ovr 1 2>/dev/null || true
      setprop persist.dbg.vt_avail_ovr 1 2>/dev/null || true
      setprop persist.dbg.wfc_avail_ovr 1 2>/dev/null || true
      setprop persist.dbg.allow_ims_off 1 2>/dev/null || true
      LAST_IMS_FORCE=1
    fi
    ;;
  *)
    if [ "$LAST_IMS_FORCE" != "0" ]; then
      setprop persist.dbg.volte_avail_ovr 0 2>/dev/null || true
      setprop persist.dbg.vt_avail_ovr 0 2>/dev/null || true
      setprop persist.dbg.wfc_avail_ovr 0 2>/dev/null || true
      setprop persist.dbg.allow_ims_off 0 2>/dev/null || true
      LAST_IMS_FORCE=0
    fi
    ;;
  esac

  case "$binder" in 1|true|on|ON|yes|YES)
    if [ "$LAST_IMS_BINDER" != "1" ]; then
      setprop persist.sys.phh.allow_binder_thread_on_incoming_calls 1 2>/dev/null || true
      LAST_IMS_BINDER=1
    fi
    ;;
  *)
    if [ "$LAST_IMS_BINDER" != "0" ]; then
      setprop persist.sys.phh.allow_binder_thread_on_incoming_calls 0 2>/dev/null || true
      LAST_IMS_BINDER=0
    fi
    ;;
  esac

  tel5=`read_first titan2_tel_force_5g`; [ -n "$tel5" ] || tel5=0
  case "$tel5" in 1|true|on|ON)
    [ "$LAST_TEL_5G" = "1" ] || { setprop persist.sys.phh.force_display_5g true 2>/dev/null || true; LAST_TEL_5G=1; }
    ;;
  *)
    [ "$LAST_TEL_5G" = "0" ] || { setprop persist.sys.phh.force_display_5g false 2>/dev/null || true; LAST_TEL_5G=0; }
    ;;
  esac

  vci=`read_first titan2_tel_disable_vci`; [ -n "$vci" ] || vci=0
  case "$vci" in 1|true|on|ON)
    [ "$LAST_TEL_VCI" = "1" ] || { setprop persist.sys.phh.disable_voice_call_in true 2>/dev/null || true; LAST_TEL_VCI=1; }
    ;;
  *)
    [ "$LAST_TEL_VCI" = "0" ] || { setprop persist.sys.phh.disable_voice_call_in false 2>/dev/null || true; LAST_TEL_VCI=0; }
    ;;
  esac

  # Default OFF. persist.sys.phh.restart_ril=true restarts vendor.ril-daemon-mtk
  # (vndk.rc) and on this SoC kills UICC / hides SIMs. Only honor explicit plane=1.
  ril=`read_first titan2_tel_restart_ril`; [ -n "$ril" ] || ril=0
  case "$ril" in 1|true|on|ON)
    [ "$LAST_TEL_RIL" = "1" ] || { setprop persist.sys.phh.restart_ril true 2>/dev/null || true; LAST_TEL_RIL=1; }
    ;;
  *)
    [ "$LAST_TEL_RIL" = "0" ] || { setprop persist.sys.phh.restart_ril false 2>/dev/null || true; LAST_TEL_RIL=0; }
    ;;
  esac

  smsc=`read_first titan2_tel_patch_smsc`; [ -n "$smsc" ] || smsc=1
  case "$smsc" in 1|true|on|ON)
    [ "$LAST_TEL_SMSC" = "1" ] || { setprop persist.sys.phh.patch_smsc true 2>/dev/null || true; LAST_TEL_SMSC=1; }
    ;;
  *)
    [ "$LAST_TEL_SMSC" = "0" ] || { setprop persist.sys.phh.patch_smsc false 2>/dev/null || true; LAST_TEL_SMSC=0; }
    ;;
  esac

  esco=`read_first titan2_bt_esco`
  if [ -n "$esco" ]; then
    case "$esco" in 0|8|16|24|32) ;; *) esco=0;; esac
    if [ "$esco" != "$LAST_BT_ESCO" ]; then
      setprop persist.sys.bt.esco_transport_unit_size "$esco" 2>/dev/null || true
      LAST_BT_ESCO=$esco
    fi
  fi

  persist_ctrl titan2_ims_mtk "$LAST_IMS_MTK"
  persist_ctrl titan2_ims_force_volte "$LAST_IMS_FORCE"
  persist_ctrl titan2_ims_binder "$LAST_IMS_BINDER"
  persist_ctrl titan2_tel_force_5g "$LAST_TEL_5G"
  persist_ctrl titan2_tel_disable_vci "$LAST_TEL_VCI"
  persist_ctrl titan2_tel_restart_ril "$LAST_TEL_RIL"
  persist_ctrl titan2_tel_patch_smsc "$LAST_TEL_SMSC"

  apply_ims_action

  _ims_path=""
  if command -v timeout >/dev/null 2>&1; then
    _ims_path=$(timeout 0.5 pm path com.mediatek.ims 2>/dev/null | head -1)
  fi
  echo "mtk=$LAST_IMS_MTK force=$LAST_IMS_FORCE binder=$LAST_IMS_BINDER prop_mtk=$(getprop persist.sys.phh.ims.mtk) volte=$(getprop persist.dbg.volte_avail_ovr) binder=$(getprop persist.sys.phh.allow_binder_thread_on_incoming_calls) bt=$LAST_BT_WA esco=$LAST_BT_ESCO ims=$_ims_path" >"$IMS_STATUS" 2>/dev/null
  chmod 666 "$IMS_STATUS" 2>/dev/null || true
  _save_ims_props_state
}

# --- IMS helpers (2.144): multi-SIM slot + correct MCC/MNC (US 3-digit MNC) ---
# First non-empty PLMN from dual-SIM props (",310240" or "310260,310240").
ims_sim_numeric() {
  _raw=`getprop gsm.sim.operator.numeric 2>/dev/null | tr -d '\r\n '`
  _out=
  _oldifs=$IFS
  IFS=,
  # shellcheck disable=SC2086
  for _p in $_raw; do
    case "$_p" in
      [0-9][0-9][0-9]*) _out=$_p; break ;;
    esac
  done
  IFS=$_oldifs
  if [ -z "$_out" ]; then
    _raw=`getprop gsm.operator.numeric 2>/dev/null | tr -d '\r\n '`
    IFS=,
    for _p in $_raw; do
      case "$_p" in
        [0-9][0-9][0-9]*) _out=$_p; break ;;
      esac
    done
    IFS=$_oldifs
  fi
  echo "$_out"
}

# MCC = first 3 digits; MNC = remainder (2 or 3). Never NUM%?? (breaks 310240→3102/40).
ims_split_mccmnc() {
  _n=$1
  IMS_MCC=
  IMS_MNC=
  case "$_n" in
    [0-9][0-9][0-9][0-9][0-9]|[0-9][0-9][0-9][0-9][0-9][0-9])
      IMS_MCC=`echo "$_n" | cut -c1-3`
      IMS_MNC=`echo "$_n" | cut -c4-`
      ;;
  esac
}

# Settings → SIMs → Calls is the only voice pin. Never dumpsys isub (hangs).
# Never first-LOADED (picks tray 1 when a dead US SIM is in slot 0).
# Never invent a subId from T-Mobile numeric.
ims_active_subid() {
  _dv=`settings get global multi_sim_voice_call 2>/dev/null | tr -d '\r\n '`
  case "$_dv" in
    [1-9]|[1-9][0-9]|[1-9][0-9][0-9]|[1-9][0-9][0-9][0-9]) echo "$_dv" ;;
    *) echo "" ;;
  esac
}

ims_slot_for_sub() {
  _sub=$1
  [ -n "$_sub" ] || { echo ""; return 0; }
  _slot=`content query --uri content://telephony/siminfo --projection sim_id \
    --where "_id=$_sub" 2>/dev/null \
    | sed -n 's/.*sim_id=\([0-9][0-9]*\).*/\1/p' | head -1`
  case "$_slot" in
    0|1) echo "$_slot" ;;
    *) echo "" ;;
  esac
}

ims_active_slot() {
  _slot=`ims_slot_for_sub "$(ims_active_subid)"`
  [ -n "$_slot" ] && echo "$_slot"
}

ims_set_vendor_prop() {
  _k=$1; _v=$2
  if [ -x /system/bin/resetprop_phh ]; then
    /system/bin/resetprop_phh "$_k" "$_v" 2>/dev/null || true
  elif [ -x /data/adb/ksu/bin/resetprop ]; then
    /data/adb/ksu/bin/resetprop "$_k" "$_v" 2>/dev/null || true
  else
    setprop "$_k" "$_v" 2>/dev/null || true
  fi
}

# Align vendor simswitch to Settings Calls. Never write multi_sim_voice_call.
ims_align_calls_tray() {
  _sub=`ims_active_subid`
  _slot=`ims_slot_for_sub "$_sub"`
  case "$_slot" in
    0|1) _want=$((_slot + 1)) ;;
    *)
      log "align skip: Settings Calls sub=$_sub has no slot"
      return 1
      ;;
  esac
  ims_set_vendor_prop persist.vendor.radio.simswitch "$_want"
  ims_set_vendor_prop persist.vendor.radio.c_capability_slot "$_want"
  setprop persist.radio.simswitch "$_want" 2>/dev/null || true
  setprop persist.radio.titan2_simswitch "$_want" 2>/dev/null || true
  mkdir -p /data/misc/titan2 /data/unencrypted 2>/dev/null || true
  echo "$_want" > /data/misc/titan2/titan2_tel_simswitch 2>/dev/null || true
  echo "$_want" > /data/unencrypted/titan2_tel_simswitch 2>/dev/null || true
  chmod 666 /data/misc/titan2/titan2_tel_simswitch 2>/dev/null || true
  chmod 644 /data/unencrypted/titan2_tel_simswitch 2>/dev/null || true
  log "align Calls sub=$_sub slot=$_slot simswitch=$_want"
  return 0
}

# Pixel IMS (kyujin-cho/pixel-volte-patch) carrier-config key set via shell override.
ims_pixel_cc_force_slot() {
  _s=$1
  [ -n "$_s" ] || return 0
  cmd phone cc set-value -s "$_s" -p carrier_volte_available_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p carrier_wfc_ims_available_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p carrier_default_wfc_ims_enabled_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p carrier_default_wfc_ims_roaming_enabled_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p carrier_wfc_supports_wifi_only_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p carrier_volte_provisioned_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p carrier_ims_gba_required_bool false 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p enhanced_4g_lte_on_by_default_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p editable_enhanced_4g_lte_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p hide_enhanced_4g_lte_bool false 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p editable_wfc_mode_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p editable_wfc_roaming_mode_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p show_ims_registration_status_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p show_wifi_calling_icon_in_status_bar_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p carrier_supports_ss_over_ut_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p support_ss_over_cdma_bool false 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p allow_adding_apns_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p carrier_vt_available_bool false 2>/dev/null || true
}

# ABSENT / empty slot has no ImsPhone. Heal must not poke it every tick.
ims_slot_absent() {
  _i=$1
  case "$_i" in
    0|1) ;;
    *) return 0 ;;
  esac
  _st=`getprop gsm.sim.state 2>/dev/null | tr -d '\r\n '`
  _n=0
  _oldifs=$IFS
  IFS=,
  for _p in $_st; do
    if [ "$_n" = "$_i" ]; then
      IFS=$_oldifs
      case "$_p" in ABSENT|"") return 0 ;; *) return 1 ;; esac
    fi
    _n=$((_n + 1))
  done
  IFS=$_oldifs
  return 0
}

# Bind + enable MtkIms for one slot (MMTEL feature).
ims_bind_slot() {
  _s=$1
  if ims_slot_absent "$_s"; then
    log "ims bind skip absent slot=$_s"
    return 0
  fi
  cmd phone ims set-ims-service -s "$_s" -c com.mediatek.ims 2>/dev/null || true
  cmd phone ims set-ims-service -s "$_s" -d com.mediatek.ims 2>/dev/null || true
  cmd phone ims set-ims-service -s "$_s" -c -f 1 com.mediatek.ims 2>/dev/null || true
  cmd phone ims set-ims-service -s "$_s" -d -f 1 com.mediatek.ims 2>/dev/null || true
  cmd phone ims enable -s "$_s" 2>/dev/null || true
}

# Controls plane: 1 | 2 | both (default both). 1=slot0, 2=slot1.
ims_wanted_slots() {
  _w=`read_first titan2_ims_bind_slots`
  [ -n "$_w" ] || _w=`settings get global titan2_ims_bind_slots 2>/dev/null | tr -d '\r\n '`
  case "$_w" in
    1) echo 0 ;;
    2) echo 1 ;;
    *) echo "0 1" ;;
  esac
}

ims_bind_all_slots() {
  for _s in `ims_wanted_slots`; do
    ims_bind_slot "$_s"
  done
}

ims_wait_phone() {
  _max=${1:-30}
  _as=`ims_active_slot`
  _w=0
  while [ "$_w" -lt "$_max" ]; do
    _w=`expr $_w + 1 2>/dev/null` || _w=$_max
    if cmd phone ims get-ims-service -s "$_as" -d 2>/dev/null | grep -q mediatek; then
      return 0
    fi
    if cmd phone ims get-ims-service -s 0 -d 2>/dev/null | grep -q mediatek; then
      return 0
    fi
    if cmd phone ims get-ims-service -s 1 -d 2>/dev/null | grep -q mediatek; then
      return 0
    fi
    sleep 1
  done
  return 1
}

ims_write_status() {
  _msg=$1
  echo "$_msg" >"$IMS_STATUS" 2>/dev/null || true
  chmod 666 "$IMS_STATUS" 2>/dev/null || true
}

ims_insert_ims_apn() {
  NUM=`ims_sim_numeric`
  if [ -z "$NUM" ] || [ ${#NUM} -lt 5 ]; then
    log "ims apn skip: no SIM numeric"
    return 1
  fi
  ims_split_mccmnc "$NUM"
  if [ -z "$IMS_MCC" ] || [ -z "$IMS_MNC" ]; then
    log "ims apn skip: bad mccmnc from $NUM"
    return 1
  fi
  # Drop prior broken Titan IMS rows (e.g. mcc=3102/mnc=40 from old %?? split)
  content delete --uri content://telephony/carriers \
    --where "name='Titan IMS' AND numeric='$NUM'" 2>/dev/null || true
  content insert --uri content://telephony/carriers \
    --bind name:s:"Titan IMS" --bind apn:s:"ims" --bind type:s:"ims" \
    --bind protocol:s:"IPV4V6" --bind roaming_protocol:s:"IPV4V6" \
    --bind carrier_enabled:i:1 --bind numeric:s:"$NUM" \
    --bind mcc:s:"$IMS_MCC" --bind mnc:s:"$IMS_MNC" 2>/dev/null \
    && log "ims apn ok numeric=$NUM mcc=$IMS_MCC mnc=$IMS_MNC" \
    || log "ims apn insert failed numeric=$NUM"
  return 0
}

# VoLTE/WFC on the Settings Calls sub only. Never write multi_sim_voice_call.
ims_apply_sub_defaults() {
  _sub=`ims_active_subid`
  _slot=`ims_slot_for_sub "$_sub"`
  if [ -n "$_sub" ]; then
    settings put global "volte_vt_enabled${_sub}" 1 2>/dev/null || true
    settings put global "wfc_ims_enabled${_sub}" 1 2>/dev/null || true
    settings put global "wfc_ims_roaming_enabled${_sub}" 1 2>/dev/null || true
    content update --uri content://telephony/siminfo \
      --bind volte_vt_enabled:i:1 \
      --bind wfc_ims_enabled:i:1 \
      --where "_id=$_sub" 2>/dev/null || true
    log "ims sub defaults Calls sub=$_sub slot=$_slot (voice pin untouched)"
  else
    log "ims sub defaults: Settings Calls unset — not inventing a pin"
  fi
  # Slot-scoped preferred network for active modem
  settings put global preferred_network_mode 9 2>/dev/null || true
  settings put global preferred_network_mode1 9 2>/dev/null || true
  settings put global preferred_network_mode2 9 2>/dev/null || true
  # Pixel IMS-class carrier config on active + both slots
  ims_pixel_cc_force_slot "$_slot"
  ims_pixel_cc_force_slot 0
  ims_pixel_cc_force_slot 1
}

# One-shot IMS actions from Titan Controls (never downloads random APKs).
apply_ims_action() {
  act=`read_first titan2_ims_action`
  [ -n "$act" ] || return 0
  clear_ctrl_name titan2_ims_action
  log "ims_action=$act"
  case "$act" in
    force_lte)
      # Drop IWLAN preference so modem searches WWAN; US Tello roaming needs LTE attach
      log "force_lte start"
      _slot=`ims_active_slot`
      _sub=`ims_active_subid`
      settings put global wfc_ims_enabled 0 2>/dev/null || true
      settings put global wfc_ims_mode 1 2>/dev/null || true
      settings put global preferred_network_mode 11 2>/dev/null || true
      settings put global preferred_network_mode1 11 2>/dev/null || true
      settings put global preferred_network_mode2 11 2>/dev/null || true
      if [ -n "$_sub" ]; then
        settings put global "preferred_network_mode${_sub}" 11 2>/dev/null || true
      fi
      settings put global data_roaming 1 2>/dev/null || true
      settings put global mobile_data 1 2>/dev/null || true
      ims_apply_sub_defaults
      setprop persist.dbg.volte_avail_ovr 1 2>/dev/null || true
      setprop persist.dbg.wfc_avail_ovr 1 2>/dev/null || true
      setprop persist.vendor.mtk.volte.enable 1 2>/dev/null || true
      # No airplane pulse (commander quiet law). Modem search without radio-off.
      cmd phone restart-modem 2>/dev/null || true
      ims_wait_phone 25 || true
      ims_bind_all_slots
      # Re-enable WFC after search starts (Tello needs both)
      settings put global wfc_ims_enabled 1 2>/dev/null || true
      settings put global wfc_ims_roaming_enabled 1 2>/dev/null || true
      settings put global wfc_ims_mode 1 2>/dev/null || true
      if [ -n "$_sub" ]; then
        settings put global "wfc_ims_enabled${_sub}" 1 2>/dev/null || true
        settings put global "wfc_ims_mode${_sub}" 1 2>/dev/null || true
      fi
      voice=$(dumpsys telephony.registry 2>/dev/null | grep -o 'mVoiceRegState=[^(]*([^)]*)' | head -1)
      emerg=$(dumpsys telephony.registry 2>/dev/null | grep -o 'mIsEmergencyOnly=[a-z]*' | head -1)
      op=$(getprop gsm.operator.numeric)
      dsvc=$(cmd phone ims get-ims-service -s "$_slot" -d 2>/dev/null | tr '\n' ' ')
      ims_write_status "force_lte slot=$_slot sub=$_sub $voice $emerg op=$op d=$dsvc $(date +%s)"
      log "force_lte done slot=$_slot sub=$_sub $voice $emerg op=$op"
      ;;
    rebind)
      _slot=`ims_active_slot`
      ims_bind_all_slots
      d0=$(cmd phone ims get-ims-service -s 0 -d 2>/dev/null | tr '\n' ' ')
      d1=$(cmd phone ims get-ims-service -s 1 -d 2>/dev/null | tr '\n' ' ')
      ims_write_status "rebind slot=$_slot d0=$d0 d1=$d1 $(date +%s)"
      log "ims rebind slot=$_slot d0=$d0 d1=$d1"
      ;;
    create_apn)
      if ims_insert_ims_apn; then
        ims_write_status "create_apn ok numeric=$(ims_sim_numeric) mcc=$IMS_MCC mnc=$IMS_MNC $(date +%s)"
      else
        ims_write_status "create_apn fail numeric=$(ims_sim_numeric) $(date +%s)"
      fi
      ;;
    install)
      # Safe only: reinstall in-ROM MtkIms — never download third-party IMS
      if [ -f /system/priv-app/MtkIms/MtkIms.apk ]; then
        pm install -r -g /system/priv-app/MtkIms/MtkIms.apk 2>&1 | while read -r l; do log "ims_install $l"; done
      elif [ -f /system/etc/titanus2/ims-mtk-u-resigned.apk ]; then
        pm install -r -g /system/etc/titanus2/ims-mtk-u-resigned.apk 2>&1 | while read -r l; do log "ims_install $l"; done
      else
        log "ims_install: no system MtkIms APK"
      fi
      pm uninstall me.phh.ims 2>/dev/null || true
      ims_bind_all_slots
      ims_write_status "install+rebind slot=$(ims_active_slot) $(date +%s)"
      ;;
    heal)
      # Follow Settings Calls. Bind both trays. Restore plane. No modem restart.
      # Never write multi_sim_voice_call. Never enable-physical-subscription.
      log "ims_heal start"
      _sub=`ims_active_subid`
      _slot=`ims_slot_for_sub "$_sub"`
      setprop persist.sys.phh.ims.mtk true 2>/dev/null || true
      setprop persist.dbg.volte_avail_ovr 1 2>/dev/null || true
      setprop persist.dbg.ims_volte_enable 1 2>/dev/null || true
      setprop persist.radio.calls.on.ims 1 2>/dev/null || true
      setprop persist.sys.phh.allow_binder_thread_on_incoming_calls 1 2>/dev/null || true
      setprop persist.vendor.mtk.volte.enable 1 2>/dev/null || true
      persist_ctrl titan2_ims_mtk 1
      persist_ctrl titan2_ims_force_volte 1
      persist_ctrl titan2_ims_binder 1
      ims_align_calls_tray || true
      ims_apply_sub_defaults
      ims_bind_all_slots
      if [ -n "$_slot" ]; then
        cmd phone ims enable -s "$_slot" 2>/dev/null || true
        ims_bind_slot "$_slot"
      fi
      d0=$(cmd phone ims get-ims-service -s 0 -d 2>/dev/null | tr '\n' ' ')
      d1=$(cmd phone ims get-ims-service -s 1 -d 2>/dev/null | tr '\n' ' ')
      sw=$(getprop persist.vendor.radio.simswitch)
      multi=$(settings get global multi_sim_voice_call 2>/dev/null | tr -d '\r\n ')
      ims_write_status "heal Calls=$multi slot=$_slot sw=$sw d0=$d0 d1=$d1 $(date +%s)"
      log "ims_heal done Calls=$multi slot=$_slot sw=$sw d0=$d0 d1=$d1"
      ;;
    *)
      log "ims_action unknown: $act"
      ;;
  esac
}

# --- CLI ---
cmd=${1:-action}
case "$cmd" in
  action)
    apply_ims_action
    ;;
  props)
    apply_ims_props
    ;;
  sub_defaults)
    ims_apply_sub_defaults
    ;;
  bind_all)
    ims_bind_all_slots
    ;;
  auto_sub)
    ims_apply_sub_defaults
    ims_bind_all_slots
    ;;
  detect)
    _sub=`ims_active_subid`
    _slot=`ims_slot_for_sub "$_sub"`
    echo "Calls=$_sub slot=$_slot vendor=$(getprop persist.vendor.radio.simswitch) titan2=$(getprop persist.radio.titan2_simswitch) calls_on_ims=$(getprop persist.radio.calls.on.ims) binder=$(getprop persist.sys.phh.allow_binder_thread_on_incoming_calls)"
    ;;
  align)
    ims_align_calls_tray || true
    _sub=`ims_active_subid`
    _slot=`ims_slot_for_sub "$_sub"`
    echo "Calls=$_sub slot=$_slot vendor=$(getprop persist.vendor.radio.simswitch) titan2=$(getprop persist.radio.titan2_simswitch) calls_on_ims=$(getprop persist.radio.calls.on.ims) binder=$(getprop persist.sys.phh.allow_binder_thread_on_incoming_calls)"
    ;;
  version)
    echo "$IMS_VER"
    ;;
  *)
    log "unknown cmd=$cmd"
    exit 1
    ;;
esac
exit 0
