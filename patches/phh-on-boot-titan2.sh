#!/system/bin/sh
# Titan 2 (EEA/TEE) customizations for phh-style GSIs
# Appended/overlayed onto Lineage GSI system/bin/phh-on-boot.sh Titan section.
#
# IMPORTANT: Do NOT hardcode inputN sysfs nodes — numbers change between boots.
# Only inhibit the rear "sub_touch" display. Do NOT inhibit "touchPad" or the
# main hynitron digitizer (main screen will stop working).

# Staged rear pin must be visible before InputReader opens sub_touch.
_sub_stage=/data/adb/titan2/idc/sub_touch.idc
_sub_live=/system/usr/idc/sub_touch.idc
if [ -f "$_sub_stage" ] && grep -q 'touch.displayId = local:' "$_sub_stage" 2>/dev/null; then
  if [ ! -f "$_sub_live" ] || ! cmp -s "$_sub_stage" "$_sub_live" 2>/dev/null; then
    mount --bind "$_sub_stage" "$_sub_live" 2>/dev/null || true
  fi
fi

# Inhibit rear sub-display touch by input device name (best-effort, non-fatal)
for inh in /sys/class/input/input*/inhibited; do
  [ -e "$inh" ] || continue
  dir=$(dirname "$inh")
  name=$(cat "$dir/name" 2>/dev/null) || continue
  case "$name" in
    sub_touch)
      echo 1 > "$inh" 2>/dev/null || true
      ;;
  esac
done

# Optional: keyboard capacitive "scroll assistant" only (NOT main LCD).
# Uncomment if accidental keyboard-pad taps interfere — leave OFF until main
# touch is confirmed working on a given build.
# for inh in /sys/class/input/input*/inhibited; do
#   dir=$(dirname "$inh"); name=$(cat "$dir/name" 2>/dev/null) || continue
#   case "$name" in touchPad) echo 1 > "$inh" 2>/dev/null || true ;; esac
# done

# --- stock phh-on-boot body continues below when merged ---
