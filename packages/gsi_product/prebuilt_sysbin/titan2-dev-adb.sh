#!/system/bin/sh
# Dev-profile lab bootstrap (WITH_DEV_ADB=1 / ro.titanus2.adb_bootstrap=1):
#   · USB debugging + developer options always on
#   · Skip first-boot setup wizard (device_provisioned / user_setup_complete)
#   · Prefer no ADB RSA gate when ro.adb.secure=0 (product prop on lab supers)
# Release builds leave this script no-op.

export PATH=/system/bin:/system/xbin:/product/bin:/vendor/bin:$PATH

LOG=/data/local/tmp/titan2-dev-adb.log
boot=$(getprop ro.titanus2.adb_bootstrap 2>/dev/null)
profile=$(getprop ro.titanus2.profile 2>/dev/null)
case "$boot$profile" in
  1*|dev) ;;
  *) exit 0 ;;
esac

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >>"$LOG" 2>/dev/null || true
}

mkdir -p /data/local/tmp 2>/dev/null || true
log "start profile=$profile bootstrap=$boot boot_completed=$(getprop sys.boot_completed) usb=$(getprop sys.usb.config)"

apply_once() {
  # --- USB debugging / developer options ---
  settings put global development_settings_enabled 1 2>/dev/null || return 1
  settings put global adb_enabled 1 2>/dev/null || true
  settings put global adb_wifi_enabled 0 2>/dev/null || true
  settings put global package_verifier_enable 0 2>/dev/null || true
  settings put global verifier_verify_adb_installs 0 2>/dev/null || true
  settings put global upload_apk_enable 1 2>/dev/null || true
  # Stay awake while charging (lab)
  settings put global stay_on_while_plugged_in 3 2>/dev/null || true
  # Product: night-first OLED (dark UI)
  settings put secure ui_night_mode 2 2>/dev/null || true
  cmd uimode night yes 2>/dev/null || true

  cur=$(getprop sys.usb.config 2>/dev/null)
  case "$cur" in
    *adb*) ;;
    *)
      setprop persist.sys.usb.config mtp,adb 2>/dev/null || true
      setprop sys.usb.config mtp,adb 2>/dev/null || true
      ;;
  esac

  # --- Skip setup wizard / mark device provisioned (lab only) ---
  settings put global device_provisioned 1 2>/dev/null || true
  settings put secure user_setup_complete 1 2>/dev/null || true
  settings put global user_setup_complete 1 2>/dev/null || true
  settings put global setup_wizard_has_run 1 2>/dev/null || true
  settings put secure user_setup_personalization_state 1 2>/dev/null || true
  settings put system setup_wizard_has_run 1 2>/dev/null || true
  # No lock screen gate for lab automation (best-effort)
  settings put secure lockscreen.disabled 1 2>/dev/null || true

  # Disable common SUW / provision packages so they do not re-take the UI.
  for pkg in \
    org.lineageos.setupwizard \
    com.google.android.setupwizard \
    com.android.provision \
    com.google.android.apps.restore
  do
    pm disable-user --user 0 "$pkg" 2>/dev/null \
      || pm disable "$pkg" 2>/dev/null \
      || true
    am force-stop "$pkg" 2>/dev/null || true
  done

  # Land on home if still stuck on setup activity
  am start -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null || true

  # Lab: enable Titan Controls key/accessibility service for pad + layouts.
  # After wipe Secure settings are empty — Key service must come back without
  # human UI. Wait for package if hybrid inject is still settling.
  SVC="com.titanus2.controls/com.titanus2.controls.TrackpadAccessService"
  pkg_ok=0
  pi=0
  while [ "$pi" -lt 15 ]; do
    if pm path com.titanus2.controls >/dev/null 2>&1; then
      pkg_ok=1
      break
    fi
    pi=$((pi + 1))
    sleep 1
  done
  cur=$(settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
  case "$cur" in
    *TrackpadAccessService*) ;;
    null|"" )
      settings put secure enabled_accessibility_services "$SVC" 2>/dev/null || true
      ;;
    *)
      settings put secure enabled_accessibility_services "${cur}:${SVC}" 2>/dev/null || true
      ;;
  esac
  settings put secure accessibility_enabled 1 2>/dev/null || true
  log "a11y put pkg_ok=$pkg_ok cur=$(settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')"

  return 0
}

# Settings provider may race first start — retry a few times.
ok=0
a11y_ok=0
i=0
while [ "$i" -lt 12 ]; do
  if apply_once; then
    # Confirm settings actually stuck
    dp=$(settings get global device_provisioned 2>/dev/null | tr -d '\r')
    ae=$(settings get global adb_enabled 2>/dev/null | tr -d '\r')
    a11y=$(settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
    case "$a11y" in *TrackpadAccessService*) a11y_ok=1 ;; esac
    if [ "$dp" = "1" ] && [ "$ae" = "1" ]; then
      ok=1
      # Prefer both ADB + a11y; keep retrying briefly if a11y missed package race
      [ "$a11y_ok" = "1" ] && break
    fi
  fi
  i=$((i + 1))
  sleep 2
done

# Nudge adbd once settings are live (helps half-dead shell after wipe)
if [ "$ok" = "1" ]; then
  setprop ctl.restart adbd 2>/dev/null || true
fi

# Classic TCP ADB (:5555) is NEVER a lab default. Clear sticky persist from old
# images / KEEP_DATA unless human opted in (Controls → Developer → Wireless ADB).
if [ -x /system/bin/titan2-dev-action.sh ]; then
  /system/bin/titan2-dev-action.sh enforce_wireless_adb_policy 2>/dev/null || true
else
  _desire=
  [ -f /data/misc/titan2/remote_adb.desire ] && \
    _desire=$(tr -d '\r\n ' </data/misc/titan2/remote_adb.desire 2>/dev/null)
  if [ "$_desire" != "on" ] && [ ! -f /data/misc/titan2/wireless_adb_wanted ]; then
    setprop service.adb.tcp.port -1 2>/dev/null || true
    setprop persist.adb.tcp.port "" 2>/dev/null || true
  fi
fi

log "done ok=$ok a11y_ok=$a11y_ok tries=$i usb=$(getprop sys.usb.config) adb_enabled=$(settings get global adb_enabled 2>/dev/null) device_provisioned=$(settings get global device_provisioned 2>/dev/null) user_setup=$(settings get secure user_setup_complete 2>/dev/null) a11y=$(settings get secure enabled_accessibility_services 2>/dev/null) adb_secure=$(getprop ro.adb.secure) tcp=$(getprop service.adb.tcp.port)"
