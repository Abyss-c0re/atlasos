#!/system/bin/sh
# Unihertz Titan 2 + phh GSI (EEA titanus2)
# Early defaults + detach pad agent (also started on boot_completed via init).

export PATH=/system/bin:/system/xbin:$PATH

# --- Radio / network always-on heal (Titan hybrid residual) ---
# GSI can leave restricted_networking_mode=1 (blocks apps + confuses telephony).
# Force OFF every boot regardless of BPF maps.
settings put global restricted_networking_mode 0 2>/dev/null || true
# MTK dual-SIM: sim.mode 2 = SIM2-only → empty tray = no service when SIM is slot0.
# 3 = both slots armed (safe with one physical SIM).
setprop persist.vendor.radio.sim.mode 3 2>/dev/null || true
setprop persist.vendor.radio.force_on 1 2>/dev/null || true
setprop persist.radio.multisim.config dsds 2>/dev/null || true

# --- SELinux fix for injected Titan stack ---
# guestfish setxattr can leave files as unlabeled; restorecon so PackageManager can open APKs.
for p in \
  /system/priv-app/TitanControls \
  /product/priv-app/TitanControls \
  /system/priv-app/OpenEUICC \
  /system/etc/permissions/privapp_whitelist_im.angry.openeuicc.xml \
  /system/bin/titan2-touchpadd \
  /system/bin/titan2-pad-agent.sh \
  /system/bin/titan2-ims-setup.sh \
  /system/etc/init/titan2-pad-agent.rc \
  /system/etc/init/titan2-touchpadd.rc \
  /system/etc/init/titan2-ims.rc \
  /system/etc/titanus2 \
  /system/priv-app/MtkIms \
  /system/product/overlay/treble-overlay-mtk-ims.apk \
  /system/product/overlay/treble-overlay-telephony-mtk-ims.apk
do
  [ -e "$p" ] || continue
  chcon -R u:object_r:system_file:s0 "$p" 2>/dev/null || true
  restorecon -RF "$p" 2>/dev/null || true
done
# pad agent / IMS setup / touchpadd need exec contexts
[ -f /system/bin/titan2-pad-agent.sh ] && chcon u:object_r:phhsu_exec:s0 /system/bin/titan2-pad-agent.sh 2>/dev/null || true
[ -f /system/bin/titan2-ims-setup.sh ] && chcon u:object_r:phhsu_exec:s0 /system/bin/titan2-ims-setup.sh 2>/dev/null || true
[ -f /system/bin/titan2-touchpadd ] && chcon u:object_r:system_file:s0 /system/bin/titan2-touchpadd 2>/dev/null || true


# Default keyboard LED mid once (agent takes over with idle timeout)
for led in \
  /sys/devices/platform/keypad_led/keyled_brightness \
  /sys/class/misc/keypad_led/keyled_brightness
do
  [ -e "$led" ] && echo 3 > "$led" 2>/dev/null
done

# Default pad OFF until pad-agent / Titan Controls apply user mode.
# Fresh install used to force Agui trackpad on + uninhibit touchPad, so the
# hardware pad was live while the QS/UI still showed Off and could not turn it off.
for inh in /sys/class/input/input*/inhibited; do
  [ -e "$inh" ] || continue
  dir=`dirname "$inh"`
  name=`cat "$dir/name" 2>/dev/null` || continue
  case "$name" in
    sub_touch) echo 1 > "$inh" 2>/dev/null ;;
    touchPad) echo 1 > "$inh" 2>/dev/null ;;
  esac
done

# USB audio host policy (was KSU module titan2_usb_audio). Stock vendor EROFS
# includes accessory-only; bind full USB_DEVICE/HEADSET policy from system.
# WITH_USB_AUDIO_HOST inject places the XML under /system/etc/titan2_audio/.
_ua_src=/system/etc/titan2_audio/audio_policy_configuration.xml
_ua_dst=/vendor/etc/audio_policy_configuration.xml
if [ -f "$_ua_src" ] && [ -f "$_ua_dst" ]; then
  # Only bind if vendor still accessory-only (or always prefer product file).
  if grep -q 'usb_audio_accessory_only' "$_ua_dst" 2>/dev/null \
     || ! grep -q 'href="usb_audio_policy_configuration.xml"' "$_ua_dst" 2>/dev/null; then
    mount --bind "$_ua_src" "$_ua_dst" 2>/dev/null || true
  fi
fi

# MTK FrameworkResOverlay CamToggle=false (China vendor) — bind Cube replacement
# over EROFS. Same pattern as USB audio. Also started early via titan2-privacy-overlay.rc.
[ -x /system/bin/titan2-bind-mtk-privacy-overlay.sh ] \
  && /system/bin/titan2-bind-mtk-privacy-overlay.sh 2>/dev/null || true
# Power ≠ camera launch (settings layer; RRO bool also set in Cube overlay)
settings put secure camera_double_tap_power_gesture_disabled 1 2>/dev/null || true
settings put secure camera_double_tap_power_gesture_enabled 0 2>/dev/null || true
settings put global camera_double_tap_power_gesture_disabled 1 2>/dev/null || true
# Power long-press = GlobalActions (power menu). 0 = NOTHING (lab regression 2026-08-11).
settings put global power_button_long_press 1 2>/dev/null || true
settings put secure long_press_power_assistant 0 2>/dev/null || true

# Pad agent + USB HID + display: init owns long-lived services (single start).
# Only ctl.start — never background-fork a second agent (dual → InputReader thrash).
setprop ctl.start titan2-pad-agent 2>/dev/null || true
setprop ctl.start titan2-usb-hid 2>/dev/null || true
setprop ctl.start titan2-display 2>/dev/null || true
(
  sleep 8
  # If init.svc still stopped, one more ctl.start only (no manual & fork).
  pa=$(getprop init.svc.titan2-pad-agent 2>/dev/null)
  if [ "$pa" != "running" ]; then
    setprop ctl.start titan2-pad-agent 2>/dev/null || true
  fi
  uh=$(getprop init.svc.titan2-usb-hid 2>/dev/null)
  if [ "$uh" != "running" ]; then
    setprop ctl.start titan2-usb-hid 2>/dev/null || true
  fi
  # Display is oneshot: if cube density/size never applied, run script once.
  ov=$(wm size 2>/dev/null | awk '/Override/{print $3; exit}')
  if [ -z "$ov" ] && [ -x /system/bin/titan2-display.sh ]; then
    /system/bin/titan2-display.sh >>/data/local/tmp/titan2-display.log 2>&1 &
  fi
  # TrebleApp (me.phh.treble.app) MTK hygiene — lab crash sources:
  # 1) DesktopInput.onResume throws Exception("Meeeeeeeeeeeeeeeeeeh") by design
  #    when desktop mode is unsupported (Desktop.kt) — disable the activity.
  # 2) Starter/QtiAudio probes Qualcomm IQcRilAudio — missing on MTK → crash;
  #    jar is injected but startup is still fragile; disable Starter.
  # Controls wraps SettingsActivity for IMS/misc; product does not need Desktop/Starter.
  pm disable me.phh.treble.app/.DesktopInput >/dev/null 2>&1 || true
  pm disable me.phh.treble.app/.Starter >/dev/null 2>&1 || true
  pm disable-user --user 0 me.phh.treble.app/.DesktopInput >/dev/null 2>&1 || true
  pm disable-user --user 0 me.phh.treble.app/.Starter >/dev/null 2>&1 || true
) &
#
# Density is applied in titan2-display (cube ~300 for tablet Settings two-pane).
# Do not force DPI here — single owner is titan2-display.sh.

# Agui stock trackpad OFF by default (pad-agent sets 1 only for trackpad|mouse).
setprop persist.sys.agui.touchpad_function 0 2>/dev/null

# Titan 2: stamp Aperture onto aux packagelist. HAL bounce is owned by
# titan2-sensor-privacy v25 (privacy OFF → all lenses; ON → no bounce).
# Never dumpsys media.camera here — it hangs and skips the stamp.
# PROP_VALUE_MAX=91. ApertureLensLauncher is fishfood, not *.lenslauncher.
_titan2_aux_pkgs="org.lineageos.aperture,com.google.android.apps.googlecamera.fishfood"
_titan2_stamp_aux() {
  setprop persist.sys.phh.include_all_cameras true 2>/dev/null || true
  setprop camera.aux.packagelist "$_titan2_aux_pkgs" 2>/dev/null || true
  setprop vendor.camera.aux.packagelist "$_titan2_aux_pkgs" 2>/dev/null || true
  setprop persist.camera.aux.packagelist "$_titan2_aux_pkgs" 2>/dev/null || true
  setprop persist.vendor.camera.aux.packagelist "$_titan2_aux_pkgs" 2>/dev/null || true
  setprop persist.vendor.camera.privapp.list org.lineageos.aperture 2>/dev/null || true
}
_titan2_stamp_aux
(
  _i=0
  while [ "$_i" -lt 36 ]; do
    [ "$(getprop sys.boot_completed)" = 1 ] && break
    sleep 5
    _i=$((_i + 1))
  done
  _titan2_stamp_aux
) &

# --- stock camera prefer (WITH_STOCK_CAMERA product face) ---
# Keep GSI Aperture on the image as fallback. Only disable it when OEM
# ACamera2 is actually registered (never leave the drawer with zero cameras).
# Matches scripts/install_stock_camera_adb.sh.
(
  # Label stock path so PackageManager can open the APK after super flash.
  if [ -d /system_ext/app/ACamera2 ]; then
    chcon -R u:object_r:system_file:s0 /system_ext/app/ACamera2 2>/dev/null || true
    restorecon -RF /system_ext/app/ACamera2 2>/dev/null || true
  fi
  # Wait for package scan (boot early — short poll).
  i=0
  while [ "$i" -lt 30 ]; do
    if pm path com.mediatek.camera >/dev/null 2>&1; then
      pm enable com.mediatek.camera >/dev/null 2>&1 || true
      for pkg in org.lineageos.aperture com.google.android.apps.googlecamera.fishfood; do
        pm disable-user --user 0 "$pkg" >/dev/null 2>&1 || true
      done
      echo "titan2: prefer stock camera com.mediatek.camera" \
        >>/data/local/tmp/titan2_camera_prefer.log 2>/dev/null || true
      break
    fi
    i=$((i + 1))
    sleep 2
  done
) &

# --- stock phh ---
vndk="$(getprop persist.sys.vndk)"
[ -z "$vndk" ] && vndk="$(getprop ro.vndk.version |grep -oE '^[0-9]+')"

[ "$(getprop vold.decrypt)" = "trigger_restart_min_framework" ] && exit 0

setprop ctl.start media.swcodec

for i in wpa p2p;do
if [ ! -f /data/misc/wifi/${i}_supplicant.conf ];then
cp /vendor/etc/wifi/wpa_supplicant.conf /data/misc/wifi/${i}_supplicant.conf
fi
chmod 0660 /data/misc/wifi/${i}_supplicant.conf
chown wifi:system /data/misc/wifi/${i}_supplicant.conf
done

if [ -f /vendor/bin/mtkmal ]; then
    # IMPORTANT (2026-07-09 bootloop): Do NOT inject stock ImsService into GSI system.
    # See docs/project/INCOMING_CALLS_IMS.md and DANGEROUS_IMS_INJECT.md.
    #
    # Safe Treble-style flags only (same as Non-EEA / Titan Controls agent). No stock
    # ImsService APK, no early `settings put`. Pad-agent re-applies user toggles later.
    setprop persist.sys.phh.ims.mtk true 2>/dev/null || true
    setprop persist.dbg.volte_avail_ovr 1 2>/dev/null || true
    setprop persist.dbg.vt_avail_ovr 1 2>/dev/null || true
    setprop persist.dbg.wfc_avail_ovr 1 2>/dev/null || true
    setprop persist.dbg.allow_ims_off 1 2>/dev/null || true
    # Incoming MT: binder thread ON. Do NOT set restart_ril=true on this SoC
    # (vndk.rc restarts vendor.ril-daemon-mtk and UICC apps drop).
    setprop persist.sys.phh.allow_binder_thread_on_incoming_calls 1 2>/dev/null || true
    # BT persist: TrebleApp Misc is SoT. Seed lab-proven defaults once;
    # never restamp if the user later changes Misc (or persist already set).
    _bt_seed=/data/misc/titan2/titan2_bt_defaults_seeded
    if [ ! -f "$_bt_seed" ]; then
      mkdir -p /data/misc/titan2 2>/dev/null || true
      setprop persist.sys.bt.unsupported.commands 182 2>/dev/null || true
      setprop persist.sys.bt.unsupported.ogfeatures "" 2>/dev/null || true
      setprop persist.sys.bt.unsupported.lefeatures "" 2>/dev/null || true
      setprop persist.sys.bt.unsupported.states "" 2>/dev/null || true
      setprop persist.bluetooth.system_audio_hal.enabled true 2>/dev/null || true
      setprop persist.sys.bt.le.disable_apcf_extended_features 1 2>/dev/null || true
      echo 1 > "$_bt_seed" 2>/dev/null || true
      chmod 666 "$_bt_seed" 2>/dev/null || true
    fi
fi

if grep -qF android.hardware.boot /vendor/manifest.xml || grep -qF android.hardware.boot /vendor/etc/vintf/manifest.xml ;then
bootctl mark-boot-successful
fi

setprop ctl.restart sec-light-hal-2-0

if find /sys/firmware -name support_fod 2>/dev/null | grep -qE .; then
setprop ctl.restart vendor.fps_hal
fi
for svc in vendor.fps_hal vendor.fingerprint-default android.hardware.biometrics.fingerprint-service vendor.goodix_fp; do
setprop ctl.restart "$svc" 2>/dev/null
done

# Titan2 stability (2026-07-10):
# Do NOT stop storageproxyd — on modern Android it backs FUSE/media storage;
# killing it correlates with "data is corrupt" / forced wipes after reboot.
# Do NOT mass-stop every "restarting" init service — can kill vold/zygote helpers mid-boot.
# Do NOT bind-mount ancient VNDK 27/28 minijail on this train (vendor is newer).
#
# Optional legacy phh behavior only if explicitly requested:
if [ "$(getprop persist.sys.titanus2.legacy_phh_storage)" = "1" ]; then
  setprop ctl.stop storageproxyd 2>/dev/null || true
fi

# Safe BT HID coex props (no service kills)
setprop persist.bluetooth.disable_a2dp_hw_offload 1 2>/dev/null || true
setprop persist.bluetooth.a2dp_offload.disabled 1 2>/dev/null || true
setprop persist.bluetooth.hid.use_le 0 2>/dev/null || true
setprop persist.sys.phh.bt.mtk 1 2>/dev/null || true

# F-WAKE-SNAPP: snappier main panel resume (Titan dual-display / MTK)
# Apply immediately at boot — never wait for late service.d (delayed wake = product fail).
setprop persist.sys.phh.disable_display_doze_suspend true 2>/dev/null || true
if [ -x /system/bin/resetprop_phh ]; then
  /system/bin/resetprop_phh persist.sys.phh.disable_display_doze_suspend true 2>/dev/null || true
fi
setprop vendor.debug.sf.cpupolicy.hw_comp_suspend 0 2>/dev/null || true
setprop debug.sf.no_vsyncs_on_screen_off false 2>/dev/null || true
setprop persist.sys.doubletapwake 1 2>/dev/null || true
# Async re-apply after settings provider (screen timeout / doze) without blocking boot
(
  sleep 3
  settings put secure doze_enabled 0 2>/dev/null || true
  settings put secure doze_always_on 0 2>/dev/null || true
  settings put secure ambient_enabled 0 2>/dev/null || true
  settings put global low_power 0 2>/dev/null || true
  settings put global animator_duration_scale 0.5 2>/dev/null || true
  settings put global transition_animation_scale 0.5 2>/dev/null || true
  settings put global window_animation_scale 0.5 2>/dev/null || true
  _to=$(settings get system screen_off_timeout 2>/dev/null | tr -d '\r')
  case "$_to" in
    ''|null|0|5000|10000|15000|30000)
      settings put system screen_off_timeout 120000 2>/dev/null || true
      ;;
  esac
  for c in 0 1 2 3 4 5; do
    f=/sys/devices/system/cpu/cpu$c/cpufreq/scaling_min_freq
    [ -w "$f" ] && echo 700000 > "$f" 2>/dev/null || true
  done
) &
