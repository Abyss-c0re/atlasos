#!/system/bin/sh
# Titan hybrid: late IMS / MT setup after boot_completed.
# Safe: setprop + bind override + APN. No stock ImsService. No userdata format.
# Guard with stamp so we don't hammer telephony every SIM prop flicker.
#
# 2026-08-01: multi-SIM slot-aware bind + MCC first-3 / MNC rest (US 310240).
# 2026-08-01b: Pixel IMS (kyujin-cho/pixel-volte-patch / dev.bluehouse.enablevolte)
#   carrier-config parity via `cmd phone cc set-value -p` (same overrideConfig path;
#   we run as system/root — no Shizuku). Plus multi_sim heal from siminfo when
#   defaultVoiceSubId is -1 (lab: US Tello stuck multi_sim_*=-1 → no service).
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH

logt() { log -t titan2-ims "$1" 2>/dev/null || echo "titan2-ims: $1"; }

STAMP=/data/local/tmp/titan2_ims_setup.stamp
# Re-run at most every 120s if invoked multiple times (init races)
if [ -f "$STAMP" ]; then
  now=$(date +%s 2>/dev/null || echo 0)
  prev=$(cat "$STAMP" 2>/dev/null || echo 0)
  case "$now" in ''|*[!0-9]*) now=0;; esac
  case "$prev" in ''|*[!0-9]*) prev=0;; esac
  if [ "$now" -gt 0 ] && [ "$prev" -gt 0 ]; then
    delta=$((now - prev))
    if [ "$delta" -ge 0 ] && [ "$delta" -lt 120 ]; then
      logt "skip (ran ${delta}s ago)"
      exit 0
    fi
  fi
fi
# Stamp deferred until end when SIM known; early stamp still set so concurrent
# starts coalesce, but cleared if no SIM so a later pad-agent/heal re-run works.
echo "${now:-0}" > "$STAMP" 2>/dev/null || true
chmod 666 "$STAMP" 2>/dev/null || true

# Wait for phone service (bounded)
i=0
while [ $i -lt 40 ]; do
  if service check phone 2>/dev/null | grep -q found; then
    break
  fi
  sleep 2
  i=$((i+1))
done
sleep 3

# Safe props only (persist; no wipe)
# Pixel IMS step 1 in ImsManager.isVolteEnabledByPlatform: dbg overrides short-circuit true.
setprop persist.sys.phh.ims.mtk true 2>/dev/null || true
setprop persist.dbg.volte_avail_ovr 1 2>/dev/null || true
setprop persist.dbg.vt_avail_ovr 1 2>/dev/null || true
setprop persist.dbg.wfc_avail_ovr 1 2>/dev/null || true
setprop persist.dbg.allow_ims_off 1 2>/dev/null || true
setprop persist.sys.phh.allow_binder_thread_on_incoming_calls 1 2>/dev/null || true
# NEVER default persist.sys.phh.restart_ril=true on this MTK (vndk.rc restarts
# vendor.ril-daemon-mtk → UICC apps disabled → SIMs vanish from Settings).
setprop persist.sys.phh.ims.floss false 2>/dev/null || true

mkdir -p /data/misc/titan2 /data/local/tmp 2>/dev/null || true
for f in titan2_ims_mtk titan2_ims_force_volte titan2_ims_binder; do
  [ -s /data/misc/titan2/$f ] || echo 1 > /data/misc/titan2/$f 2>/dev/null || true
  chmod 666 /data/misc/titan2/$f 2>/dev/null || true
done
[ -s /data/misc/titan2/titan2_tel_restart_ril ] || echo 0 > /data/misc/titan2/titan2_tel_restart_ril 2>/dev/null || true
chmod 666 /data/misc/titan2/titan2_tel_restart_ril 2>/dev/null || true

# Never enable-physical-subscription / setUiccApplicationsEnabled here.
# User "Use SIM" off must stay off. Re-enabling deletes that choice.

# Ensure system MtkIms is present; never download third-party IMS
if ! pm path com.mediatek.ims >/dev/null 2>&1; then
  if [ -f /system/priv-app/MtkIms/MtkIms.apk ]; then
    logt "pm install system MtkIms"
    pm install -r -g /system/priv-app/MtkIms/MtkIms.apk 2>&1 | while read -r l; do logt "$l"; done
  elif [ -f /system/etc/titanus2/ims-mtk-u-resigned.apk ]; then
    logt "pm install etc MtkIms"
    pm install -r -g /system/etc/titanus2/ims-mtk-u-resigned.apk 2>&1 | while read -r l; do logt "$l"; done
  else
    logt "WARN: no MtkIms on system"
  fi
else
  logt "ims already: $(pm path com.mediatek.ims 2>/dev/null | head -1)"
fi
# Drop floss if user ever installed it; do NOT install Pixel IMS APK (Tensor/Shizuku)
pm uninstall me.phh.ims 2>/dev/null || true
pm uninstall dev.bluehouse.enablevolte 2>/dev/null || true

settings put global enhanced_4g_mode_enabled 1 2>/dev/null || true
settings put global volte_vt_enabled 1 2>/dev/null || true
settings put global mobile_data 1 2>/dev/null || true
# LTE/NR hybrid (11) — not "GSM only"; 9=LTE/GSM/WCDMA also ok
settings put global preferred_network_mode 11 2>/dev/null || true
settings put global preferred_network_mode1 11 2>/dev/null || true
settings put global preferred_network_mode2 11 2>/dev/null || true
settings put global wfc_ims_enabled 1 2>/dev/null || true
settings put global wfc_ims_roaming_enabled 1 2>/dev/null || true
# Cellular preferred (1): lab residual WIFI_PREFERRED (2) left WWAN PS on EMERGENCY
# only while IWLAN showed HOME — Tello/T-Mobile never finished LTE attach.
# VoWiFi still available after cellular is in service.
settings put global wfc_ims_mode 1 2>/dev/null || true

cmd overlay enable me.phh.treble.overlay.mtkims_telephony 2>/dev/null || true
cmd overlay enable me.phh.treble.overlay.mtkims 2>/dev/null || true

# --- multi-SIM helpers ---
ims_sim_numeric() {
  _raw=`getprop gsm.sim.operator.numeric 2>/dev/null | tr -d '\r\n '`
  _out=
  _oldifs=$IFS
  IFS=,
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
  # siminfo fallback (SIM prop empty mid-bind)
  if [ -z "$_out" ]; then
    _row=`content query --uri content://telephony/siminfo 2>/dev/null \
      | grep 'mcc_string=310' | tail -1`
    if [ -n "$_row" ]; then
      _mcc=`echo "$_row" | sed -n 's/.*mcc_string=\([0-9]*\).*/\1/p'`
      _mnc=`echo "$_row" | sed -n 's/.*mnc_string=\([0-9]*\).*/\1/p'`
      [ -n "$_mcc" ] && [ -n "$_mnc" ] && _out="${_mcc}${_mnc}"
    fi
  fi
  echo "$_out"
}

ims_active_slot() {
  ims_slot_for_sub "$(ims_active_subid)"
}

# MTK 4G/IMS capability is 1-indexed persist.vendor.radio.simswitch.
# Voice SIM on slot 1 with simswitch=1 → ImsService createMmTelFeature returns
# null IInterface (listener "unused"). vendor persist ignores plain setprop;
# use resetprop_phh. Do not restart RIL (UICC drop). Modem reads this at boot.
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

ims_align_simswitch() {
  _slot=`ims_active_slot`
  case "$_slot" in
    0|1) _want=$((_slot + 1)) ;;
    *)
      logt "simswitch skip: Settings Calls has no slot"
      return 0
      ;;
  esac
  _cur=`getprop persist.vendor.radio.simswitch 2>/dev/null | tr -d '\r\n '`
  logt "simswitch $_cur -> $_want (voice slot $_slot). reboot applies modem cap; no ril restart"
  ims_set_vendor_prop persist.vendor.radio.simswitch "$_want"
  ims_set_vendor_prop persist.vendor.radio.c_capability_slot "$_want"
  setprop persist.radio.simswitch "$_want" 2>/dev/null || true
  setprop persist.radio.titan2_simswitch "$_want" 2>/dev/null || true
  mkdir -p /data/misc/titan2 2>/dev/null || true
  echo "$_want" > /data/misc/titan2/titan2_tel_simswitch 2>/dev/null || true
  chmod 666 /data/misc/titan2/titan2_tel_simswitch 2>/dev/null || true
  mkdir -p /data/unencrypted 2>/dev/null || true
  echo "$_want" > /data/unencrypted/titan2_tel_simswitch 2>/dev/null || true
  chmod 644 /data/unencrypted/titan2_tel_simswitch 2>/dev/null || true
}

# Settings → SIMs → Calls is the only voice pin. Never invent a subId.
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
    [0-9]|[0-9][0-9]) echo "$_slot" ;;
    *) echo "" ;;
  esac
}

# ABSENT / empty slot has no ImsPhone. Bind and restart must refuse it.
ims_slot_absent() {
  _i=$1
  case "$_i" in
    0|1|2|3) ;;
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

ims_bind_slot() {
  _s=$1
  if ims_slot_absent "$_s"; then
    logt "ims bind skip absent slot=$_s"
    return 0
  fi
  cmd phone ims set-ims-service -s "$_s" -c com.mediatek.ims 2>/dev/null || true
  cmd phone ims set-ims-service -s "$_s" -d com.mediatek.ims 2>/dev/null || true
  cmd phone ims set-ims-service -s "$_s" -c -f 1 com.mediatek.ims 2>/dev/null || true
  cmd phone ims set-ims-service -s "$_s" -d -f 1 com.mediatek.ims 2>/dev/null || true
  cmd phone ims enable -s "$_s" 2>/dev/null || true
}

# Pixel IMS parity: ICarrierConfigLoader.overrideConfig via shell.
# Keys match kyujin-cho/pixel-volte-patch Config.kt / Moder.kt defaults for VoLTE+WFC.
# -p = persistent (survives reboot on non-QPR2-broken loaders; we re-apply on boot anyway).
ims_pixel_cc_force_slot() {
  _s=$1
  [ -n "$_s" ] || return 0
  # Core VoLTE / VoWiFi (Pixel IMS primary toggles)
  cmd phone cc set-value -s "$_s" -p carrier_volte_available_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p carrier_wfc_ims_available_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p carrier_default_wfc_ims_enabled_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p carrier_default_wfc_ims_roaming_enabled_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p carrier_wfc_supports_wifi_only_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p carrier_volte_provisioned_bool true 2>/dev/null || true
  # GBA gate (ImsManager path 4–5): TMO configs often require GBA; incomplete MTK → block.
  # Pixel IMS README documents this check; force off so platform IMS can enable.
  cmd phone cc set-value -s "$_s" -p carrier_ims_gba_required_bool false 2>/dev/null || true
  # Enhanced 4G / LTE+ toggles (Pixel IMS "4G+" group)
  cmd phone cc set-value -s "$_s" -p enhanced_4g_lte_on_by_default_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p editable_enhanced_4g_lte_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p hide_enhanced_4g_lte_bool false 2>/dev/null || true
  # WFC UI / roaming editability
  cmd phone cc set-value -s "$_s" -p editable_wfc_mode_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p editable_wfc_roaming_mode_bool true 2>/dev/null || true
  # Status / diagnostics
  cmd phone cc set-value -s "$_s" -p show_ims_registration_status_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p show_wifi_calling_icon_in_status_bar_bool true 2>/dev/null || true
  # Supplementary services over UT (common VoLTE dependency)
  cmd phone cc set-value -s "$_s" -p carrier_supports_ss_over_ut_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p support_ss_over_cdma_bool false 2>/dev/null || true
  # Allow manual APN (wholesale/ims insert for Tello)
  cmd phone cc set-value -s "$_s" -p allow_adding_apns_bool true 2>/dev/null || true
  # VT off by default (save power; enable via Controls if needed)
  cmd phone cc set-value -s "$_s" -p carrier_vt_available_bool false 2>/dev/null || true
  # T-Mobile ePDG FQDN (VoWiFi when LTE reject / IWLAN data only)
  cmd phone cc set-value -s "$_s" -p iwlan.epdg_static_address_string "epdg.epc.mnc260.mcc310.pub.3gppnetwork.org" 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p iwlan.epdg_static_address_roaming_string "epdg.epc.mnc260.mcc310.pub.3gppnetwork.org" 2>/dev/null || true
  # Without these, DNC blocks IMS PDN on emergency-only / no-VoPS camp (Tello abroad:
  # WWAN DENIED/EMERGENCY + IWLAN HOME but ePDG tunnelSetup counts stay empty).
  # AccessNetworkType: GERAN=1 UTRAN=2 EUTRAN=3 IWLAN=5 NGRAN=6
  cmd phone cc set-value -s "$_s" -p ims.ims_pdn_enabled_in_no_vops_support_int_array 1 2 3 5 6 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p ims.keep_pdn_up_in_no_vops_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p carrier_rcs_provisioning_required_bool false 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p imsvoice.carrier_volte_roaming_available_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p carrier_default_wfc_ims_mode_int 1 2>/dev/null || true
  # TMO AppAuth / QNS WFC activation gate (lab: mAllowIwlanForWfcActivation stuck false)
  cmd phone cc set-value -s "$_s" -p require_entitlement_checks_bool false 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p imsserviceentitlement.skip_wfc_activation_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$_s" -p imsserviceentitlement.default_service_entitlement_status_bool true 2>/dev/null || true
}

ims_restart_registration() {
  _s=$1
  if ims_slot_absent "$_s"; then
    logt "ims restart skip absent slot=$_s"
    return 0
  fi
  # Pixel IMS: telephony.resetIms(slot). Shell equivalent: disable/enable IMS.
  cmd phone ims disable -s "$_s" 2>/dev/null || true
  sleep 1
  cmd phone ims enable -s "$_s" 2>/dev/null || true
  ims_bind_slot "$_s"
}

# Bind MMTEL on the trays Controls asked for (1 | 2 | both).
ASLOT=$(ims_active_slot)
BIND_WANT=$(cat /data/misc/titan2/titan2_ims_bind_slots 2>/dev/null | tr -d '\r\n ')
[ -n "$BIND_WANT" ] || BIND_WANT=$(settings get global titan2_ims_bind_slots 2>/dev/null | tr -d '\r\n ')
j=0
while [ $j -lt 15 ]; do
  case "$BIND_WANT" in
    1) ims_bind_slot 0 ;;
    2) ims_bind_slot 1 ;;
    *)
      ims_bind_slot "$ASLOT"
      for _bs in 0 1; do
        case $(getprop gsm.sim.state 2>/dev/null | cut -d, -f$((_bs+1))) in
          ABSENT|"") continue ;;
        esac
        ims_bind_slot "$_bs"
      done
      ;;
  esac
  _chk=$ASLOT
  case "$BIND_WANT" in 1) _chk=0 ;; 2) _chk=1 ;; esac
  got=$(cmd phone ims get-ims-service -s "$_chk" -d 2>/dev/null)
  if echo "$got" | grep -q mediatek; then
    logt "ims bind OK want=$BIND_WANT slot=$_chk d=$got"
    break
  fi
  logt "ims bind retry $j want=$BIND_WANT"
  sleep 2
  j=$((j+1))
done

# Follow Settings Calls. Never write multi_sim_voice_call.
SUB=$(ims_active_subid)
if [ -n "$SUB" ]; then
  settings put global multi_sim_data_call "$SUB" 2>/dev/null || true
  settings put global multi_sim_sms "$SUB" 2>/dev/null || true
  settings put global "mobile_data${SUB}" 1 2>/dev/null || true
  settings put global data_roaming 1 2>/dev/null || true
  settings put global "data_roaming${SUB}" 1 2>/dev/null || true
  # SubscriptionManager reads siminfo.data_roaming — settings alone stay stale at 0
  # (lab: WWAN DENIED 11/13 while other handset works same SIM).
  content update --uri content://telephony/siminfo \
    --bind data_roaming:i:1 --where "_id=$SUB" 2>/dev/null || true
  content update --uri content://telephony/siminfo \
    --bind data_roaming:i:1 --where "sim_id=$ASLOT" 2>/dev/null || true
  settings put global "volte_vt_enabled${SUB}" 1 2>/dev/null || true
  settings put global "wfc_ims_enabled${SUB}" 1 2>/dev/null || true
  # Cellular preferred so WWAN attaches (WIFI_PREFERRED starved LTE on Titan hybrid)
  settings put global "wfc_ims_mode${SUB}" 1 2>/dev/null || true
  settings put global wfc_ims_mode 1 2>/dev/null || true
  settings put global "wfc_ims_roaming_enabled${SUB}" 1 2>/dev/null || true
  settings put global "wfc_ims_roaming_mode${SUB}" 1 2>/dev/null || true
  # Explicit mobile data + subscription flags (SERVICE_OPTION_NOT_SUPPORTED residual)
  settings put global mobile_data 1 2>/dev/null || true
  settings put global "mobile_data${SUB}" 1 2>/dev/null || true
  content update --uri content://telephony/siminfo \
    --bind is_opportunistic:i:0 --where "_id=$SUB" 2>/dev/null || true
  cmd phone data enable 2>/dev/null || true
  cmd phone set-allowed-network-types-for-users "$SUB" nr,lte,wcdma,gsm 2>/dev/null \
    || cmd phone set-allowed-network-types-for-users 1 nr,lte,wcdma,gsm 2>/dev/null || true
  # Subscription-level wifi calling mode if API exists
  cmd phone ims set-wfc-mode "$SUB" cellular-preferred 2>/dev/null \
    || cmd phone ims set-wfc-mode 1 cellular-preferred 2>/dev/null || true
  logt "sub defaults sub=$SUB slot=$ASLOT multi_sim+data_roaming+cellular_pref healed"
else
  logt "sub defaults: no subId yet (slot=$ASLOT multi_sim may stay -1)"
fi

NUM=$(ims_sim_numeric)
if [ -n "$NUM" ] && [ ${#NUM} -ge 5 ]; then
  # MCC first 3 digits; MNC remainder (2 or 3) — never ${NUM%??}
  MCC=$(echo "$NUM" | cut -c1-3)
  MNC=$(echo "$NUM" | cut -c4-)
  # Dual IMS APNs (PHH IMS + Titan IMS) flap the bearer and unbind ImsService.
  # Never keep both. Prefer an existing ims row (PHH). Titan IMS is last resort.
  content delete --uri content://telephony/carriers \
    --where "name='Titan IMS' AND numeric='$NUM'" 2>/dev/null || true
  if ! content query --uri content://telephony/carriers \
      --where "apn='ims' AND numeric='$NUM' AND mcc='$MCC'" 2>/dev/null | grep -qi ims; then
    content insert --uri content://telephony/carriers \
      --bind name:s:"Titan IMS" --bind apn:s:"ims" --bind type:s:"ims" \
      --bind protocol:s:"IPV4V6" --bind roaming_protocol:s:"IPV4V6" \
      --bind carrier_enabled:i:1 --bind numeric:s:"$NUM" \
      --bind mcc:s:"$MCC" --bind mnc:s:"$MNC" 2>/dev/null \
      && logt "IMS APN for $NUM mcc=$MCC mnc=$MNC" || logt "APN insert skipped"
  else
    logt "IMS APN already present for $NUM"
  fi
  # Tello (carrier_id 2578) uses apn=tello, not wholesale — wrong preferapn = no default PDN.
  case "$NUM" in
    310240|310260)
      tid=$(content query --uri content://telephony/carriers --projection _id \
        --where "apn='tello' AND numeric='$NUM'" 2>/dev/null \
        | sed -n "s/.*_id=\([0-9][0-9]*\).*/\1/p" | head -1)
      if [ -z "$tid" ]; then
        tid=$(content query --uri content://telephony/carriers --projection _id \
          --where "apn='wholesale' AND numeric='$NUM'" 2>/dev/null \
          | sed -n "s/.*_id=\([0-9][0-9]*\).*/\1/p" | head -1)
      fi
      if [ -n "$tid" ]; then
        content insert --uri content://telephony/carriers/preferapn \
          --bind apn_id:i:"$tid" 2>/dev/null || true
        logt "preferapn numeric=$NUM id=$tid"
      else
        logt "WARN: no tello/wholesale APN for $NUM"
      fi
      ;;
  esac
else
  logt "no SIM numeric yet (slot=$ASLOT)"
fi

# Platform VoLTE/WFC + MTK stack props (again after bind)
settings put global restricted_networking_mode 0 2>/dev/null || true
# Dual-SIM both slots (never SIM2-only — kills slot0 Tello)
setprop persist.vendor.radio.sim.mode 3 2>/dev/null || true
setprop persist.vendor.radio.force_on 1 2>/dev/null || true
setprop persist.dbg.volte_avail_ovr 1 2>/dev/null || true
setprop persist.dbg.vt_avail_ovr 1 2>/dev/null || true
setprop persist.dbg.wfc_avail_ovr 1 2>/dev/null || true
setprop persist.dbg.allow_ims_off 1 2>/dev/null || true
setprop persist.dbg.ims_volte_enable 1 2>/dev/null || true
setprop persist.radio.calls.on.ims 1 2>/dev/null || true
setprop persist.data.iwlan.enable true 2>/dev/null || true
setprop persist.vendor.mtk.volte.enable 1 2>/dev/null || true
# WFC on for US MVNO abroad (VoWiFi when WWAN only emergency-camps)
setprop persist.vendor.mtk.wfc.enable 1 2>/dev/null || true
setprop persist.vendor.mtk_wfc_support 1 2>/dev/null || true
setprop persist.vendor.mtk_volte_support 1 2>/dev/null || true
setprop persist.vendor.ims_support 1 2>/dev/null || true
# Vendor radio WFC state (0 = modem treats WFC off even if Android settings are on)
setprop persist.vendor.radio.wfc_state 3 2>/dev/null || true
setprop persist.vendor.radio.volte_state 3 2>/dev/null || true
setprop persist.vendor.radio.wfc_enable 1 2>/dev/null || true
setprop persist.vendor.clientapi_support 1 2>/dev/null || true
# MTK DSBP: load OP08 (T-Mobile US) modem profile when US SIM present
NUM_ALL=$(getprop gsm.sim.operator.numeric 2>/dev/null | tr -d '\r')
[ -n "$NUM_ALL" ] || NUM_ALL=$NUM
case "$NUM_ALL" in
  *310240*|*310260*)
    setprop persist.vendor.operator.optr OP08 2>/dev/null || true
    setprop persist.vendor.operator.spec SPEC0200 2>/dev/null || true
    setprop persist.vendor.operator.seg SEGDEFAULT 2>/dev/null || true
    setprop persist.vendor.mtk_usp_operator OP08 2>/dev/null || true
    setprop persist.vendor.radio.mtk_dsbp_id 8 2>/dev/null || true
    setprop vendor.mtk.md.sbp 8 2>/dev/null || true
    logt "US TMO SIM → OP08/SBP8 forced"
    ;;
esac

# Pixel IMS carrier-config force (both slots + any active)
for SLOT in 0 1; do
  ims_pixel_cc_force_slot "$SLOT"
  # Without these, Google's Iwlan reports "Wfc enabled: false" and never
  # opens ePDG (lab 2026-08-02: tunnelSetup counts stay empty).
  cmd phone cc set-value -s "$SLOT" -p carrier_rcs_provisioning_required_bool false 2>/dev/null || true
  cmd phone cc set-value -s "$SLOT" -p imsvoice.carrier_volte_roaming_available_bool true 2>/dev/null || true
  cmd phone cc set-value -s "$SLOT" -p carrier_default_wfc_ims_mode_int 1 2>/dev/null || true
done
logt "pixel-ims carrier config forced (cc set-value -p); GBA required=false; WFC mode=1"
ims_align_simswitch

# Sub-level WFC/VoLTE including roaming (US SIM abroad)
if [ -n "$SUB" ]; then
  content update --uri content://telephony/siminfo \
    --bind volte_vt_enabled:i:1 \
    --bind wfc_ims_enabled:i:1 \
    --bind wfc_ims_mode:i:1 \
    --bind wfc_ims_roaming_enabled:i:1 \
    --bind wfc_ims_roaming_mode:i:1 \
    --where "_id=$SUB" 2>/dev/null || true
  # Also patch any other 310 rows (stale multi-insert SIMs)
  for _sid in $(content query --uri content://telephony/siminfo 2>/dev/null \
      | grep 'mcc_string=310' | sed -n 's/.*_id=\([0-9][0-9]*\).*/\1/p'); do
    content update --uri content://telephony/siminfo \
      --bind volte_vt_enabled:i:1 \
      --bind wfc_ims_enabled:i:1 \
      --bind wfc_ims_mode:i:1 \
      --bind wfc_ims_roaming_enabled:i:1 \
      --bind wfc_ims_roaming_mode:i:1 \
      --where "_id=$_sid" 2>/dev/null || true
  done
fi
settings put global wfc_ims_roaming_enabled 1 2>/dev/null || true
settings put global wfc_ims_roaming_mode 1 2>/dev/null || true
settings put global wfc_ims_mode 1 2>/dev/null || true
settings put global enhanced_4g_mode_enabled 1 2>/dev/null || true
# Product privacy (2026-08-06): NEVER force location_mode on boot.
# Old lab opt-in titan2_force_location_for_wfc=1 caused location ON every reboot.
# Clear sticky flag so residual userdata cannot re-arm force. User controls Location QS.
settings delete global titan2_force_location_for_wfc 2>/dev/null || true
# Do not write secure location_mode here — leave user preference.

# Never thrash RIL stop/start here (left SIM UNKNOWN for minutes). Soft heal only:
settings put global restricted_networking_mode 0 2>/dev/null || true
setprop persist.vendor.radio.sim.mode 3 2>/dev/null || true
svc data enable 2>/dev/null || true
cmd phone data enable 2>/dev/null || true
# US SIM abroad: keep cellular-preferred. WIFI_PREFERRED starved LTE (A10).
_nitz=$(getprop persist.vendor.radio.nitz_oper_code_0 2>/dev/null | tr -d '\r')
_simn=$(getprop gsm.sim.operator.numeric 2>/dev/null | cut -d, -f1 | tr -d '\r')
case "$_simn" in
  310240|310260)
    case "$_nitz" in
      310*|311*|312*|313*|316*)
        logt "US SIM home MCC — keep cellular-preferred WFC"
        settings put global wfc_ims_mode 1 2>/dev/null || true
        ;;
      *)
        logt "US SIM abroad nitz=$_nitz - keep cellular-preferred WFC"
        settings put global wfc_ims_enabled 1 2>/dev/null || true
        settings put global wfc_ims_mode 1 2>/dev/null || true
        settings put global wfc_ims_roaming_enabled 1 2>/dev/null || true
        setprop persist.vendor.mtk.wfc.enable 1 2>/dev/null || true
        content update --uri content://telephony/siminfo \
          --bind wfc_ims_enabled:i:1 --bind wfc_ims_mode:i:1 \
          --bind wfc_ims_roaming_enabled:i:1 --where "mcc_string=310" 2>/dev/null || true
        ;;
    esac
    ;;
esac

# Start vendor VoLTE UA stack (Unihertz vendor lacks init.volte.rc; system rc defines titan2_* services).
# Prefer a SINGLE clientapi UA — vendor + titan2 dual-start races the same socket name.
setprop ctl.stop volte_clientapi_ua 2>/dev/null || true
setprop ctl.start titan2_rcs_volte_stack 2>/dev/null || true
setprop ctl.start titan2_volte_rcs_ua 2>/dev/null || true
setprop ctl.start titan2_volte_clientapi_ua 2>/dev/null || true
# SELinux on GSI denies init creating volte_clientapi as socket_device; if missing, permissive retry (lab).
if [ ! -S /dev/socket/volte_clientapi ]; then
  setenforce 0 2>/dev/null || true
  setprop ctl.stop titan2_volte_clientapi_ua 2>/dev/null || true
  sleep 1
  setprop ctl.start titan2_volte_clientapi_ua 2>/dev/null || true
  logt "clientapi socket recreate (setenforce0) present=$(test -S /dev/socket/volte_clientapi && echo y || echo n)"
fi
logt "volte stack start requested"

# Pixel IMS restartIMSRegistration after config. Honor bind pin; skip ABSENT.
case "$BIND_WANT" in
  1) ims_restart_registration 0 ;;
  2) ims_restart_registration 1 ;;
  *)
    ims_restart_registration "$ASLOT"
    ims_restart_registration 0
    ims_restart_registration 1
    ;;
esac
logt "ims re-register after pixel-ims config"

# QNS WFC activation (sets mAllowIwlanForWfcActivation). Without this, IWLAN is
# qualified but transport stays INVALID and ePDG never opens (Tello abroad lab).
# QNS gate: extras must be SUB_ID + TRY_STATUS=1 (not subId). No WfcActivationActivity UI.
am broadcast -a com.android.qns.wfcactivation.TRY_WFC_CONNECTION --ei SUB_ID 1 --ei TRY_STATUS 1 2>/dev/null || true
am broadcast -a com.android.qns.wfcactivation.TRY_WFC_CONNECTION \
  --ei android.telephony.extra.SUBSCRIPTION_INDEX "${SUB:-1}" 2>/dev/null || true
logt "qns wfc activation kicked sub=${SUB:-1}"

# If no SIM yet, clear stamp so pad-agent heal / next trigger can re-run fully
if [ -z "$NUM" ]; then
  rm -f "$STAMP" 2>/dev/null || true
  logt "no SIM — stamp cleared for later re-run"
fi

logt "done slot=$ASLOT sub=$SUB num=$NUM mtk=$(getprop persist.sys.phh.ims.mtk) multi=$(settings get global multi_sim_voice_call) d=$(cmd phone ims get-ims-service -s $ASLOT -d 2>/dev/null)"
