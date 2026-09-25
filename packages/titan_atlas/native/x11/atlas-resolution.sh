#!/bin/sh
# Change the Debian desk size and restart the session.
# Usage: atlas-resolution 1440 1440
set -u
W=${1:-}
H=${2:-}
case "$W" in ''|*[!0-9]*) echo "usage: atlas-resolution WIDTH HEIGHT"; exit 2 ;; esac
case "$H" in ''|*[!0-9]*) echo "usage: atlas-resolution WIDTH HEIGHT"; exit 2 ;; esac
if [ "$W" -lt 640 ] || [ "$W" -gt 2160 ] || [ "$H" -lt 480 ] || [ "$H" -gt 2160 ]; then
    echo "size must be 640..2160 by 480..2160"
    exit 2
fi
mkdir -p /home/atlas/atlas-x
printf '%s %s\n' "$W" "$H" >/home/atlas/atlas-x/size
echo "desk size $W x $H"
if [ -f /home/atlas/atlas-x/session.pid ]; then
    old=$(cat /home/atlas/atlas-x/session.pid 2>/dev/null || true)
    if [ -n "$old" ] && [ "$old" != "$$" ]; then
        kill "$old" 2>/dev/null || true
        sleep 0.4
    fi
fi
exec /usr/local/bin/atlas-desk-session
