#!/system/bin/sh
# Once per boot, only if ImsService started before the volte UA socket.
# Killing a healthy post-UA ImsService drops the next incoming.
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

pid=$(pidof com.mediatek.ims 2>/dev/null | awk '{print $1}')
if [ -n "$pid" ] && [ -d "/proc/$pid" ]; then
  # proc starttime (ticks) vs socket mtime — only kill if process is older.
  sock=$(stat -c %Y /dev/socket/volte_clientapi 2>/dev/null || echo 0)
  pstart=$(stat -c %Y /proc/$pid 2>/dev/null || echo 0)
  if [ "$pstart" -gt 0 ] && [ "$sock" -gt 0 ] && [ "$pstart" -lt "$sock" ]; then
    kill -9 "$pid" 2>/dev/null || true
    log -t titan2-ims "rearm: killed pre-UA ImsService pid=$pid"
  else
    log -t titan2-ims "rearm: ImsService pid=$pid already after UA — no kill"
  fi
fi
echo 1 >"$DONE" 2>/dev/null || true
exit 0
