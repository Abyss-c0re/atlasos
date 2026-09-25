#!/system/bin/sh
# Root helper. The Atlas app cannot exec su, so Restart writes desk.cmd.
# Stop the whole session before starting another, or ksmserver calls it a crash.
F=/data/local/tmp/atlas-virgl/desk.cmd
CH=/data/local/atlas-linux
LOG=/data/local/tmp/desk-restart.out
mkdir -p /data/local/tmp/atlas-virgl
while true; do
    if [ -s "$F" ]; then
        cmd=$(head -n 1 "$F" 2>/dev/null)
        rm -f "$F"
        if [ "$cmd" = restart ]; then
            if [ -x "$CH/usr/local/libexec/atlas-desk-stop.sh" ]; then
                chroot "$CH" /usr/bin/env -i \
                    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                    /usr/local/libexec/atlas-desk-stop.sh \
                    >>"$LOG" 2>&1
            fi
            chroot "$CH" /usr/bin/env -i \
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                HOME=/home/atlas USER=atlas LOGNAME=atlas \
                ATLAS_DESK_RESTART=1 ATLAS_DESK_W=1440 ATLAS_DESK_H=1440 \
                /usr/bin/setsid /usr/local/bin/atlas-desk-session \
                </dev/null >>"$LOG" 2>&1 &
        fi
    fi
    sleep 0.4
done
