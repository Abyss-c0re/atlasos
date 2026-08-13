#!/system/bin/sh
# Faster screen wake on Unihertz Titan 2 (PHH GSI + MediaTek)
# F-WAKE-SNAPP: doze suspend off + CPU min floor + no ambient thrash.
# Install: /data/adb/service.d/99-wake-snappy.sh (KernelSU) + hybrid phh-on-boot.
# 15.60: run IMMEDIATELY (no sleep 12) — delayed apply = delayed first wake.
apply_wake_snappy() {
  setprop persist.sys.phh.disable_display_doze_suspend true 2>/dev/null || true
  if [ -x /system/bin/resetprop_phh ]; then
    /system/bin/resetprop_phh persist.sys.phh.disable_display_doze_suspend true 2>/dev/null || true
  fi

  setprop vendor.debug.sf.cpupolicy.hw_comp_suspend 0 2>/dev/null || true
  setprop debug.sf.no_vsyncs_on_screen_off false 2>/dev/null || true
  # Keep SF composition responsive on dual-panel resume
  setprop debug.sf.enable_gl_backpressure 0 2>/dev/null || true
  setprop debug.sf.latch_unsignaled 1 2>/dev/null || true

  settings put global animator_duration_scale 0.5 2>/dev/null || true
  settings put global transition_animation_scale 0.5 2>/dev/null || true
  settings put global window_animation_scale 0.5 2>/dev/null || true

  settings put secure doze_enabled 0 2>/dev/null || true
  settings put secure doze_always_on 0 2>/dev/null || true
  settings put secure doze_pulse_on_pick_up 0 2>/dev/null || true
  settings put secure ambient_enabled 0 2>/dev/null || true
  settings put global low_power 0 2>/dev/null || true

  settings put secure double_tap_to_wake 1 2>/dev/null || true
  settings put secure wake_gesture_enabled 1 2>/dev/null || true
  setprop persist.sys.doubletapwake 1 2>/dev/null || true

  # Lab/product: avoid 10s auto-sleep thrash (felt as lag + delayed wake)
  _to=$(settings get system screen_off_timeout 2>/dev/null | tr -d '\r')
  case "$_to" in
    ''|null|0|5000|10000|15000|30000)
      settings put system screen_off_timeout 120000 2>/dev/null || true
      ;;
  esac

  for c in 0 1 2 3 4 5 6 7; do
    g=/sys/devices/system/cpu/cpu$c/cpufreq
    [ -d "$g" ] || continue
    if grep -qw sugov_ext "$g/scaling_available_governors" 2>/dev/null; then
      echo sugov_ext > "$g/scaling_governor" 2>/dev/null || true
    elif grep -qw schedutil "$g/scaling_available_governors" 2>/dev/null; then
      echo schedutil > "$g/scaling_governor" 2>/dev/null || true
    fi
  done
  # Little/mid min ≥ 700 MHz — cold resume was parking at 450 MHz
  for c in 0 1 2 3 4 5; do
    f=/sys/devices/system/cpu/cpu$c/cpufreq/scaling_min_freq
    [ -w "$f" ] || continue
    echo 700000 > "$f" 2>/dev/null || true
  done
}

# Immediate (first path) + re-apply after late settings provider (once)
apply_wake_snappy
(
  sleep 8
  apply_wake_snappy
) &
