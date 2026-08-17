#!/system/bin/sh
# Incoming-path heresy detector. No test call. No RIL/IMS kill.
# Settings → SIMs → Calls is the only tray switch.
# Detects the 2026-08-17 class: vendor NVRAM simswitch drift, MMTEL READY/empty
# caps, PHH+Titan IMS APN flap, USP OP08 on a non-TMO tray, silent ring theater.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH

fail=0
heresy=0

her() { echo "HERESY  $1"; heresy=$((heresy + 1)); }
bad() { echo "FAIL    $1"; fail=$((fail + 1)); }
ok() { echo "OK      $1"; }
info() { echo "INFO    $1"; }

echo "=== Titan incoming path (no test call) ==="

air=$(settings get global airplane_mode_on 2>/dev/null | tr -d '\r')
case "$air" in
  1) bad "airplane on" ;;
  *) ok "airplane off" ;;
esac

sim=$(getprop gsm.sim.state | tr -d '\r')
case "$sim" in
  *LOADED*|*READY*) ok "sim $sim" ;;
  *) bad "sim $sim" ;;
esac

t2=$(getprop persist.radio.titan2_simswitch | tr -d '\r\n ')
vendor=$(getprop persist.vendor.radio.simswitch | tr -d '\r\n ')
radio=$(getprop persist.radio.simswitch | tr -d '\r\n ')
cap=$(getprop persist.vendor.radio.c_capability_slot | tr -d '\r\n ')
dv=$(dumpsys isub 2>/dev/null | sed -n 's/.*defaultVoiceSubId=\([-0-9][0-9]*\).*/\1/p' | head -1)
info "calls_sot titan2=$t2 vendor=$vendor radio=$radio cap=$cap voiceSub=$dv"

split=0
[ -n "$t2" ] && [ -n "$vendor" ] && [ "$t2" != "$vendor" ] && split=1
[ -n "$t2" ] && [ -n "$radio" ] && [ "$t2" != "$radio" ] && split=1
[ -n "$t2" ] && [ -n "$cap" ] && [ "$t2" != "$cap" ] && split=1
if [ "$split" = "1" ]; then
  her "simswitch split Calls=$t2 vendor=$vendor radio=$radio cap=$cap (NVRAM tray 1 overwrite)"
else
  ok "simswitch aligned titan2=$t2 vendor=$vendor"
fi

hold=0
if [ -s /data/local/tmp/titan2_ims_simswitch_hold.pid ]; then
  hp=$(tr -d '\r\n ' </data/local/tmp/titan2_ims_simswitch_hold.pid)
  [ -n "$hp" ] && [ -d "/proc/$hp" ] && hold=1
fi
if [ "$hold" != "1" ]; then
  for d in /proc/[0-9]*; do
    [ -r "$d/cmdline" ] || continue
    c=$(tr '\0' ' ' <"$d/cmdline" 2>/dev/null)
    case "$c" in
      *titan2-ims-simswitch-hold*) hold=1; break ;;
    esac
  done
fi
if [ "$hold" = "1" ]; then
  ok "simswitch hold running"
else
  her "simswitch hold not running (vendor can drift to tray 1)"
fi

voice=$(dumpsys telephony.registry 2>/dev/null | grep -o 'mVoiceRegState=[^(]*([^)]*)' | tail -1)
vops=$(dumpsys telephony.registry 2>/dev/null | grep -o 'mVopsSupport = [0-9]*' | tail -1)
info "voice=$voice $vops"
case "$voice" in
  *IN_SERVICE*) ok "voice IN_SERVICE" ;;
  *) bad "voice $voice" ;;
esac

phone=$(dumpsys phone 2>/dev/null | grep -E 'addConnection.*MMTEL|notifyFeatureCapabilitiesChanged, type=MMTEL|NO_IMS_SERVICE_CONFIGURED|isBound=' | tail -40)
echo "$phone" | grep -q 'isBound=true' && ok "ImsService bound" || bad "ImsService not bound"
echo "$phone" | grep -q 'NO_IMS_SERVICE_CONFIGURED' && her "NO_IMS_SERVICE_CONFIGURED (listener theater)"
lastcaps=$(echo "$phone" | grep 'notifyFeatureCapabilitiesChanged, type=MMTEL' | tail -1)
lastadd=$(echo "$phone" | grep 'addConnection' | grep MMTEL | tail -1)
mmtel_voice=0
echo "$lastadd" | grep -q VOICE && echo "$lastadd" | grep -qv 'capabilities={ }' && mmtel_voice=1
echo "$lastcaps" | grep -q VOICE && echo "$lastcaps" | grep -qv 'capabilities={ }' && mmtel_voice=1
if echo "$lastadd" | grep -q 'capabilities={ }' || echo "$lastcaps" | grep -q 'capabilities={ }'; then
  her "MMTEL READY/empty caps (looks bound, no Voice) last=$lastcaps"
elif [ "$mmtel_voice" = "1" ]; then
  ok "MMTEL Voice advertised"
else
  info "MMTEL last=$lastcaps"
fi

reg=$(dumpsys telephony.registry 2>/dev/null | grep -E 'ApnSetting|LOST_CONNECTION' | tail -40)
phh=0; titanapn=0
echo "$reg" | grep -q 'PHH IMS' && phh=1
echo "$reg" | grep -q 'Titan IMS' && titanapn=1
if [ "$phh" = "1" ] && [ "$titanapn" = "1" ]; then
  her "dual IMS APN PHH IMS + Titan IMS (bearer flap)"
elif [ "$phh" = "1" ]; then
  ok "IMS APN PHH IMS"
elif [ "$titanapn" = "1" ]; then
  ok "IMS APN Titan IMS"
else
  info "no IMS APN name in last registry lines"
fi
echo "$reg" | grep LOST_CONNECTION | grep -qi ims && her "IMS APN LOST_CONNECTION in registry log"

usp=$(getprop persist.vendor.mtk_usp_operator | tr -d '\r')
op=$(getprop gsm.sim.operator.numeric | tr -d '\r')
alpha=$(getprop gsm.sim.operator.alpha | tr -d '\r')
info "usp=$usp sim=$op $alpha"
if [ "$usp" = "OP08" ]; then
  case "$op" in
    *310240*|*310260*) ok "USP OP08 matches TMO live SIM" ;;
    *) her "USP OP08 (T-Mobile) while live SIM is $op $alpha" ;;
  esac
fi

skip=$(dumpsys telecom 2>/dev/null | grep 'SKIP_RINGING' | tail -1)
case "$skip" in
  *Inaudible*) her "last incoming SKIP_RINGING volume=0 (looks like incoming died)" ;;
esac
load=$(cut -d' ' -f1 /proc/loadavg 2>/dev/null)
info "load=$load"
case "$load" in
  [8-9].*|[1-9][0-9]*) her "load $load (privacy belt / dumpsys thrash can drop IMS)" ;;
esac

echo
if [ "$heresy" -gt 0 ] || [ "$fail" -gt 0 ]; then
  echo "VERDICT  FAIL incoming path — heresy=$heresy fail=$fail (no test call)"
  exit 2
fi
echo "VERDICT  facts only — voice path not proven by a ring"
exit 0
