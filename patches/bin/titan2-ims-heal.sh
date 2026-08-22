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
IMS_VER=2.173-bt-noforce

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

# Logical slot with LOADED/READY SIM, prefer default voice sub mapping.
ims_active_slot() {
  _slot=
  _dv=`dumpsys isub 2>/dev/null | sed -n 's/.*defaultVoiceSubId=\([0-9][0-9]*\).*/\1/p' | head -1`
  [ -n "$_dv" ] || _dv=`dumpsys isub 2>/dev/null | sed -n 's/.*defaultSubId=\([0-9][0-9]*\).*/\1/p' | head -1`
  if [ -n "$_dv" ] && [ "$_dv" != "-1" ]; then
    _slot=`dumpsys isub 2>/dev/null | sed -n "s/.*Logical SIM slot \([0-9][0-9]*\): subId=${_dv}.*/\1/p" | head -1`
    if [ -z "$_slot" ]; then
      _slot=`dumpsys isub 2>/dev/null | tr '\n' ' ' \
        | sed -n "s/.*id=${_dv}[^[]*simSlotIndex=\([0-9][0-9]*\).*/\1/p" | head -1`
    fi
  fi
  if [ -z "$_slot" ]; then
    _st=`getprop gsm.sim.state 2>/dev/null | tr -d '\r\n '`
    _i=0
    _oldifs=$IFS
    IFS=,
    for _s in $_st; do
      case "$_s" in
        LOADED|READY|IMSI|LOADED*|READY*) _slot=$_i; break ;;
      esac
      _i=`expr $_i + 1 2>/dev/null` || _i=0
    done
    IFS=$_oldifs
  fi
  [ -n "$_slot" ] || _slot=0
  echo "$_slot"
}

ims_active_subid() {
  _dv=`dumpsys isub 2>/dev/null | sed -n 's/.*defaultVoiceSubId=\([0-9][0-9]*\).*/\1/p' | head -1`
  if [ -z "$_dv" ] || [ "$_dv" = "-1" ]; then
    _dv=`dumpsys isub 2>/dev/null | sed -n 's/.*defaultSubId=\([0-9][0-9]*\).*/\1/p' | head -1`
  fi
  if [ -z "$_dv" ] || [ "$_dv" = "-1" ]; then
    _dv=`dumpsys isub 2>/dev/null | sed -n 's/.*defaultDataSubId=\([0-9][0-9]*\).*/\1/p' | head -1`
  fi
  if [ -z "$_dv" ] || [ "$_dv" = "-1" ]; then
    # settings fallback (survives brief phone binder death)
    _dv=`settings get global multi_sim_voice_call 2>/dev/null | tr -d '\r\n '`
  fi
  if [ -z "$_dv" ] || [ "$_dv" = "-1" ] || [ "$_dv" = "null" ]; then
    _dv=`settings get global multi_sim_data_call 2>/dev/null | tr -d '\r\n '`
  fi
  # siminfo: prefer latest US T-Mobile/Tello (310/*), else any real MCC (not 001 test)
  if [ -z "$_dv" ] || [ "$_dv" = "-1" ] || [ "$_dv" = "null" ]; then
    _dv=`content query --uri content://telephony/siminfo 2>/dev/null \
      | grep 'mcc_string=310' \
      | sed -n 's/.*_id=\([0-9][0-9]*\).*/\1/p' | tail -1`
  fi
  if [ -z "$_dv" ] || [ "$_dv" = "-1" ]; then
    _dv=`content query --uri content://telephony/siminfo 2>/dev/null \
      | grep -E 'mcc_string=[1-9]' \
      | grep -v 'mcc_string=001' \
      | sed -n 's/.*_id=\([0-9][0-9]*\).*/\1/p' | tail -1`
  fi
  case "$_dv" in
    [0-9]|[0-9][0-9]|[0-9][0-9][0-9]|[0-9][0-9][0-9][0-9]) echo "$_dv" ;;
    *) echo "" ;;
  esac
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

# Bind + enable MtkIms for one slot (MMTEL feature).
ims_bind_slot() {
  _s=$1
  [ -n "$_s" ] || return 0
  cmd phone ims set-ims-service -s "$_s" -c com.mediatek.ims 2>/dev/null || true
  cmd phone ims set-ims-service -s "$_s" -d com.mediatek.ims 2>/dev/null || true
  cmd phone ims set-ims-service -s "$_s" -c -f 1 com.mediatek.ims 2>/dev/null || true
  cmd phone ims set-ims-service -s "$_s" -d -f 1 com.mediatek.ims 2>/dev/null || true
  cmd phone ims enable -s "$_s" 2>/dev/null || true
}

# Active slot first, then both DSDS slots (cheap; empty slot ok).
ims_bind_all_slots() {
  _as=`ims_active_slot`
  ims_bind_slot "$_as"
  ims_bind_slot 0
  ims_bind_slot 1
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

# Point default voice/data/SMS + per-sub data/WFC at the live subId.
ims_apply_sub_defaults() {
  _sub=`ims_active_subid`
  _slot=`ims_active_slot`
  if [ -n "$_sub" ]; then
    settings put global multi_sim_data_call "$_sub" 2>/dev/null || true
    settings put global multi_sim_voice_call "$_sub" 2>/dev/null || true
    settings put global multi_sim_sms "$_sub" 2>/dev/null || true
    settings put global "mobile_data${_sub}" 1 2>/dev/null || true
    settings put global "data_roaming${_sub}" 1 2>/dev/null || true
    settings put global "preferred_network_mode${_sub}" 9 2>/dev/null || true
    settings put global "volte_vt_enabled${_sub}" 1 2>/dev/null || true
    settings put global "wfc_ims_enabled${_sub}" 1 2>/dev/null || true
    settings put global "wfc_ims_mode${_sub}" 2 2>/dev/null || true
    settings put global "wfc_ims_roaming_enabled${_sub}" 1 2>/dev/null || true
    settings put global "wfc_ims_roaming_mode${_sub}" 1 2>/dev/null || true
    content update --uri content://telephony/siminfo \
      --bind volte_vt_enabled:i:1 \
      --bind wfc_ims_enabled:i:1 \
      --bind wfc_ims_mode:i:2 \
      --bind wfc_ims_roaming_enabled:i:1 \
      --bind wfc_ims_roaming_mode:i:1 \
      --where "_id=$_sub" 2>/dev/null || true
    log "ims sub defaults sub=$_sub slot=$_slot"
  else
    log "ims sub defaults: no active subId (slot=$_slot)"
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
      # Airplane cycle
      settings put global airplane_mode_on 1 2>/dev/null || true
      am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true 2>/dev/null || true
      sleep 4
      settings put global airplane_mode_on 0 2>/dev/null || true
      am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false 2>/dev/null || true
      sleep 3
      cmd phone restart-modem 2>/dev/null || true
      ims_wait_phone 25 || true
      ims_bind_all_slots
      # Re-enable WFC after search starts (Tello needs both)
      settings put global wfc_ims_enabled 1 2>/dev/null || true
      settings put global wfc_ims_roaming_enabled 1 2>/dev/null || true
      settings put global wfc_ims_mode 2 2>/dev/null || true
      if [ -n "$_sub" ]; then
        settings put global "wfc_ims_enabled${_sub}" 1 2>/dev/null || true
        settings put global "wfc_ims_mode${_sub}" 2 2>/dev/null || true
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
      # Cube SMS/IMS heal: UICC apps can be silently disabled (Settings) → SIMs greyed + no SMS.
      # Re-enable every known sub, force data/roaming/VoLTE, IMS APN + rebind MtkIms.
      log "ims_heal start"
      for sid in 1 2 3 4 5 6 7 8 9 10; do
        cmd phone enable-physical-subscription "$sid" 2>/dev/null && log "ims_heal enable sub=$sid" || true
      done
      setprop persist.sys.phh.ims.mtk true 2>/dev/null || true
      setprop persist.dbg.volte_avail_ovr 1 2>/dev/null || true
      setprop persist.dbg.vt_avail_ovr 1 2>/dev/null || true
      setprop persist.dbg.wfc_avail_ovr 1 2>/dev/null || true
      setprop persist.dbg.allow_ims_off 1 2>/dev/null || true
      setprop persist.dbg.ims_volte_enable 1 2>/dev/null || true
      setprop persist.sys.phh.allow_binder_thread_on_incoming_calls 1 2>/dev/null || true
      setprop persist.sys.phh.ims.floss false 2>/dev/null || true
      setprop persist.data.iwlan.enable true 2>/dev/null || true
      setprop persist.vendor.mtk.volte.enable 1 2>/dev/null || true
      setprop persist.vendor.mtk.wfc.enable 1 2>/dev/null || true
      settings put global enhanced_4g_mode_enabled 1 2>/dev/null || true
      settings put global volte_vt_enabled 1 2>/dev/null || true
      settings put global mobile_data 1 2>/dev/null || true
      settings put global data_roaming 1 2>/dev/null || true
      settings put global wfc_ims_enabled 1 2>/dev/null || true
      settings put global wfc_ims_roaming_enabled 1 2>/dev/null || true
      settings put global wfc_ims_roaming_mode 1 2>/dev/null || true
      # Cellular preferred first, then Wi‑Fi preferred after rebind (US MVNO)
      settings put global wfc_ims_mode 1 2>/dev/null || true
      # Product privacy: never force GPS on heal. Clear sticky lab flag if present.
      settings delete global titan2_force_location_for_wfc 2>/dev/null || true
      ims_apply_sub_defaults
      cmd overlay enable me.phh.treble.overlay.mtkims 2>/dev/null || true
      cmd overlay enable me.phh.treble.overlay.mtkims_telephony 2>/dev/null || true
      ims_insert_ims_apn || true
      NUM=`ims_sim_numeric`
      _slot=`ims_active_slot`
      if [ "$NUM" = "310240" ] || [ "$NUM" = "310260" ]; then
        ims_split_mccmnc "$NUM"
        setprop persist.vendor.operator.optr OP08 2>/dev/null || true
        setprop persist.vendor.operator.spec SPEC0200 2>/dev/null || true
        setprop persist.vendor.operator.seg SEGDEFAULT 2>/dev/null || true
        setprop persist.vendor.mtk_usp_operator OP08 2>/dev/null || true
        setprop persist.vendor.radio.mtk_dsbp_id 8 2>/dev/null || true
        setprop vendor.mtk.md.sbp 8 2>/dev/null || true
        content insert --uri content://telephony/carriers \
          --bind name:s:"Tello wholesale" --bind apn:s:"wholesale" \
          --bind type:s:"default,supl,mms,ia,hipri" \
          --bind protocol:s:"IPV4V6" --bind roaming_protocol:s:"IPV4V6" \
          --bind mmsc:s:"http://wholesale.mmsmvno.com/mms/wapenc" \
          --bind carrier_enabled:i:1 --bind numeric:s:"$NUM" \
          --bind mcc:s:"$IMS_MCC" --bind mnc:s:"$IMS_MNC" 2>/dev/null || true
        tid=$(content query --uri content://telephony/carriers --projection _id \
          --where "apn=\"wholesale\" AND numeric=\"$NUM\"" 2>/dev/null \
          | sed -n "s/.*_id=\([0-9][0-9]*\).*/\1/p" | head -1)
        [ -n "$tid" ] && content insert --uri content://telephony/carriers/preferapn \
          --bind apn_id:i:"$tid" 2>/dev/null || true
        cmd phone set-allowed-network-types-for-users -s "$_slot" 916479 2>/dev/null || true
        log "ims_heal tello/tmobile apn numeric=$NUM slot=$_slot op08"
      fi
      # Restart modem first so UICC re-enable is visible, THEN wait for phone
      # service recovery. Restart-modem was leaving cmd phone in Failed transaction
      # so IMS rebind never stuck (lab 2026-07-31 Tello).
      cmd phone restart-modem 2>/dev/null || true
      ims_wait_phone 30 && log "ims_heal phone_ok" || log "ims_heal phone_wait timeout"
      # Re-resolve slot/sub after modem — isub often empty mid-restart
      _slot=`ims_active_slot`
      ims_bind_all_slots
      ims_apply_sub_defaults
      # Pixel IMS re-register after carrier config
      cmd phone ims disable -s "$_slot" 2>/dev/null || true
      sleep 1
      cmd phone ims enable -s "$_slot" 2>/dev/null || true
      ims_bind_all_slots
      # Prefer Wi‑Fi calling path when WWAN rejects (US MVNO roaming)
      settings put global wfc_ims_enabled 1 2>/dev/null || true
      settings put global wfc_ims_roaming_enabled 1 2>/dev/null || true
      settings put global wfc_ims_mode 2 2>/dev/null || true
      _sub=`ims_active_subid`
      if [ -n "$_sub" ]; then
        settings put global "wfc_ims_enabled${_sub}" 1 2>/dev/null || true
        settings put global "wfc_ims_mode${_sub}" 2 2>/dev/null || true
        settings put global "volte_vt_enabled${_sub}" 1 2>/dev/null || true
      fi
      # Kick vendor VoLTE UA (missing stock init.volte.rc)
      setprop ctl.start titan2_rcs_volte_stack 2>/dev/null || true
      setprop ctl.start titan2_volte_rcs_ua 2>/dev/null || true
      setprop ctl.start titan2_volte_clientapi_ua 2>/dev/null || true
      NUM=`ims_sim_numeric`
      voice=$(dumpsys telephony.registry 2>/dev/null | grep -o 'mVoiceRegState=[^(]*([^)]*)' | head -1)
      emerg=$(dumpsys telephony.registry 2>/dev/null | grep -o 'mIsEmergencyOnly=[a-z]*' | head -1)
      dsvc=$(cmd phone ims get-ims-service -s "$_slot" -d 2>/dev/null | tr '\n' ' ')
      multi=$(settings get global multi_sim_voice_call 2>/dev/null | tr -d '\r\n ')
      ims_write_status "heal slot=$_slot sub=$_sub multi=$multi numeric=$NUM $voice $emerg d=$dsvc $(date +%s)"
      log "ims_heal done slot=$_slot sub=$_sub multi=$multi numeric=$NUM $voice $emerg d=$dsvc"
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
  version)
    echo "$IMS_VER"
    ;;
  *)
    log "unknown cmd=$cmd"
    exit 1
    ;;
esac
exit 0
