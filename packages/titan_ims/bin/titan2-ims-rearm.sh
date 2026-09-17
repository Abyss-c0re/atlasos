#!/system/bin/sh
# Once per boot: ImsService often binds MmTel before volte UA exists.
# After the UA socket and a loaded tray exist, restart ImsService so
# incoming Voice is advertised without a human/adb poke.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
DONE=/dev/titan2_ims_rearm.done
[ -f "$DONE" ] && exit 0

i=0
while [ $i -lt 30 ]; do
  [ -S /dev/socket/volte_clientapi ] && break
  sleep 1
  i=$((i + 1))
done
[ -S /dev/socket/volte_clientapi ] || exit 0

i=0
while [ $i -lt 40 ]; do
  st=$(getprop gsm.sim.state 2>/dev/null | tr -d '\r\n ')
  case "$st" in
    *LOADED*|*READY*) break ;;
  esac
  sleep 2
  i=$((i + 1))
done

killall -9 com.mediatek.ims 2>/dev/null || true
echo 1 >"$DONE" 2>/dev/null || true
log -t titan2-ims "rearm: ImsService restarted after UA+SIM"
exit 0
