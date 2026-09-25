#!/bin/sh
# PAM gate for real Debian sudo.
# Exit 0: Atlas authenticated — sudo does not ask for a password.
# Exit 1: cancelled, timed out, or no agent — sudo asks for the Debian password.
DIR=/var/lib/atlas-auth
mkdir -p "$DIR"
chmod 1777 "$DIR" 2>/dev/null || chmod 755 "$DIR" 2>/dev/null || true

ticket="$DIR/ticket.sudo"
if [ -f "$ticket" ]; then
    exp=$(awk "NR==1 { print \$1 }" "$ticket" 2>/dev/null || true)
    now=$(date +%s)
    if [ -n "$exp" ] && [ "$exp" -gt "$now" ] 2>/dev/null; then
        exit 0
    fi
fi

id="$$-$(date +%s)"
req="$DIR/req.$id"
umask 000
printf '%s\n%s\n' "sudo ${PAM_USER:-atlas}" "scope=sudo" > "$req"
chmod 666 "$req" 2>/dev/null || true
: > "$DIR/wake"
chmod 666 "$DIR/wake" 2>/dev/null || true

# Give the file watcher a moment to see a finished req.
sleep 0.4
end=$(( $(date +%s) + 45 ))
while [ "$(date +%s)" -lt "$end" ]; do
    if [ -f "$DIR/ok.$id" ]; then
        rm -f "$DIR/ok.$id" "$DIR/fail.$id" "$req" "$DIR/busy.$id"
        exit 0
    fi
    if [ -f "$DIR/fail.$id" ]; then
        rm -f "$DIR/ok.$id" "$DIR/fail.$id" "$req" "$DIR/busy.$id"
        exit 1
    fi
    sleep 0.2
done
rm -f "$req" "$DIR/busy.$id" "$DIR/ok.$id" "$DIR/fail.$id"
exit 1
