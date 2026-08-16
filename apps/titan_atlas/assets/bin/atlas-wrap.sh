#!/bin/sh
# argv0 is the managed name. Real ELF lives in /usr/local/libexec/atlas-managed/.
me=${0##*/}
AUTH=""
for a in /usr/local/bin/atlas-auth /system/bin/atlas-auth \
  /data/data/com.titanus2.atlas/files/bin/atlas-auth; do
  [ -x "$a" ] && AUTH=$a && break
done
[ -n "$AUTH" ] || { echo "atlas-wrap: atlas-auth missing" >&2; exit 127; }
REAL="/usr/local/libexec/atlas-managed/$me"
if [ ! -e "$REAL" ]; then
  for f in /var/lib/atlas-auth/managed.bins \
    /data/local/atlas-linux/var/lib/atlas-auth/managed.bins; do
    [ -f "$f" ] || continue
    REAL=`awk -v n="$me" '$1==n {print $2; exit}' "$f"`
    [ -n "$REAL" ] && break
  done
fi
[ -n "$REAL" ] && [ -e "$REAL" ] || {
  echo "atlas-wrap: $me: no real binary" >&2
  exit 127
}
exec "$AUTH" exec --scope "$me" -- "$REAL" "$@"
