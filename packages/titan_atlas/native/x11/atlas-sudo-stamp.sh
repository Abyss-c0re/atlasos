#!/bin/sh
# One sudo invocation, just granted by Atlas auth. Not a stored login.
uid=$(id -u "${PAM_USER:-}" 2>/dev/null) || exit 1
f="/tmp/atlas-sudo-bypass.${uid}"
[ -f "$f" ] || exit 1
owner=$(stat -c %u "$f" 2>/dev/null) || exit 1
[ "$owner" = "$uid" ] || exit 1
ts=$(awk '{ print $2; exit }' "$f" 2>/dev/null)
rm -f "$f"
[ -n "$ts" ] || exit 1
now=$(date +%s)
age=$((now - ts))
[ "$age" -ge 0 ] && [ "$age" -le 20 ] || exit 1
exit 0
