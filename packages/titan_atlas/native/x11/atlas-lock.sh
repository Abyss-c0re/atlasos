#!/bin/sh
# Lock the running desk and ask for the atlas user's password.
# Plasma hides its own Lock button when logind is absent. This is that button.
DIR=/home/atlas/atlas-x
if [ -f "$DIR/xdisplay" ]; then
    DISPLAY=$(tr -d '[:space:]' <"$DIR/xdisplay")
    export DISPLAY
fi
export HOME=/home/atlas
export USER=atlas
export LOGNAME=atlas
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/runtime-atlas}"
export QT_QPA_PLATFORM=xcb
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
exec /usr/lib/aarch64-linux-gnu/libexec/kscreenlocker_greet --testing --immediateLock
