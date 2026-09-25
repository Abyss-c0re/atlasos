#!/bin/sh
# Plasma is root. Firefox exits when root's $HOME is not owned by root.
# The desk home belongs to uid 10081. Drop to that uid.
export MOZ_ENABLE_WAYLAND=0
export XDG_SESSION_TYPE="${XDG_SESSION_TYPE:-x11}"
if [ "$(id -u)" = 0 ]; then
    mkdir -p /dev/shm /tmp/runtime-10081
    if ! mountpoint -q /dev/shm 2>/dev/null; then
        mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs /dev/shm || true
    fi
    chown 10081:10081 /tmp/runtime-10081 2>/dev/null || true
    chmod 700 /tmp/runtime-10081 2>/dev/null || true
    export HOME=/home/atlas
    export USER=atlas
    export LOGNAME=atlas
    export XDG_RUNTIME_DIR=/tmp/runtime-10081
    exec setpriv --reuid=10081 --regid=10081 --clear-groups --inh-caps=-all \
        /usr/lib/firefox-esr/firefox-esr "$@"
fi
exec /usr/lib/firefox-esr/firefox-esr "$@"
