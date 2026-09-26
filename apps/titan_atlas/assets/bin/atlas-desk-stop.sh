#!/bin/sh
# Stop the desk session without letting ksmserver call it a crash.
# Skip $ATLAS_DESK_STOP_KEEP (the script that is about to start the new one).
# Session dbus is "dbus-daemon --nofork". The system bus is "--system" and stays.

kill_match() {
    pat=$1
    for p in $(pgrep -f "$pat" 2>/dev/null || true); do
        [ "$p" = "$$" ] && continue
        [ -n "$keep" ] && [ "$p" = "$keep" ] && continue
        [ -r /proc/"$p"/cmdline ] || continue
        cmd=$(tr '\0' ' ' < /proc/"$p"/cmdline 2>/dev/null || true)
        case "$cmd" in
            ''|pgrep*|*/pgrep*|*' pgrep '*) continue ;;
        esac
        case "$cmd" in
            *"$pat"*) kill -9 "$p" 2>/dev/null || true ;;
        esac
    done
}

stop_desk_stack() {
    keep="${ATLAS_DESK_STOP_KEEP:-}"
    echo "restart: stopping desk"
    # Session managers first. If KWin dies while ksmserver is alive, KDE
    # reports a crash and tears the desktop down.
    for pat in \
        '/usr/bin/plasma_session' \
        '/usr/bin/ksmserver' \
        'startplasma-x11' \
        'dbus-run-session' \
        '/usr/bin/kded6' \
        kglobalacceld \
        kactivitymanagerd \
        xembedsniproxy \
        gmenudbusmenuproxy \
        plasmashell \
        kwin_x11 \
        'dbus-daemon --nofork' \
        '/usr/local/bin/atlas-desk-session' \
        '/usr/local/bin/atlas-x'
    do
        kill_match "$pat"
    done
    sleep 0.3
    for pat in \
        '/usr/bin/plasma_session' \
        '/usr/bin/ksmserver' \
        'startplasma-x11' \
        'dbus-run-session' \
        '/usr/bin/kded6' \
        kglobalacceld \
        kactivitymanagerd \
        xembedsniproxy \
        gmenudbusmenuproxy \
        plasmashell \
        kwin_x11 \
        'dbus-daemon --nofork' \
        '/usr/local/bin/atlas-desk-session' \
        '/usr/local/bin/atlas-x'
    do
        kill_match "$pat"
    done
    for name in plasmashell kwin_x11.bin kwin_x11 Xwayland atlas-x kded6 ksmserver; do
        pkill -9 -x "$name" 2>/dev/null || true
    done
    if ! pgrep -x Xwayland >/dev/null 2>&1; then
        rm -f /tmp/.X11-unix/X[0-9]* /tmp/.X[0-9]*-lock 2>/dev/null || true
    fi
    echo "restart: desk stopped"
}

case "${0##*/}" in
    atlas-desk-stop.sh) stop_desk_stack ;;
esac
