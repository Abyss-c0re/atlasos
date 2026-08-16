#!/system/bin/sh
# Settings managed.bins → Deb PATH name becomes symlink → atlas-wrap → atlas-auth.
# Real ELF is moved to /usr/local/libexec/atlas-managed/<name>.
set -f
LP=/data/local/atlas-linux
[ -d "$LP/usr" ] || LP=/data/local/atlas-hybrid/merge
WRAP_IN=/usr/local/libexec/atlas-wrap
STASH_IN=/usr/local/libexec/atlas-managed
WRAP_HOST="$LP$WRAP_IN"
STASH_HOST="$LP$STASH_IN"
BIN_HOST="$LP/usr/local/bin"

mkdir -p "$STASH_HOST" "$BIN_HOST" "$LP/usr/local/libexec" || exit 1

cat >"$WRAP_HOST" <<'EOF'
#!/bin/sh
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
EOF
chmod 755 "$WRAP_HOST"

is_ours() {
  [ -L "$1" ] || return 1
  t=`readlink "$1" 2>/dev/null`
  [ "$t" = "$WRAP_IN" ] || [ "$t" = "$WRAP_HOST" ]
}

host_of() {
  p=$1
  case "$p" in
    /data/local/atlas-linux/*|/data/local/atlas-hybrid/merge/*) echo "$p" ;;
    /usr/*|/bin/*|/sbin/*) echo "$LP$p" ;;
    *) echo "" ;;
  esac
}

reserved() {
  case "$1" in
    atlas-auth|atlas-android|atlas-sudo|atlas|atlas-wrap|atlas-managed|\
    am|cmd|sh|bash|login|su|sudo|apt|screencap) return 0 ;;
    *) return 1 ;;
  esac
}

MF=""
for f in "$LP/var/lib/atlas-auth/managed.bins" \
  /data/misc/titan2/managed.bins /data/local/tmp/managed.bins; do
  [ -f "$f" ] && MF=$f && break
done

KEEP=" "
if [ -n "$MF" ]; then
  while read -r name path _; do
    case "$name" in ''|\#*) continue ;; esac
    reserved "$name" && continue
    case "$path" in /*) ;; *) continue ;; esac
    KEEP="$KEEP$name "
    host=`host_of "$path"`
    if [ -n "$host" ] && [ -e "$host" ] && ! is_ours "$host"; then
      if [ -f "$host" ] || [ -L "$host" ]; then
        if [ ! -e "$STASH_HOST/$name" ]; then
          cp -p "$host" "$STASH_HOST/$name" 2>/dev/null || continue
          echo "$host" >"$STASH_HOST/$name.origin"
        fi
        rm -f "$host"
      fi
    fi
    if [ -n "$host" ]; then
      ln -sfn "$WRAP_IN" "$host"
      [ -f "$STASH_HOST/$name.origin" ] || echo "$host" >"$STASH_HOST/$name.origin"
    fi
    ln -sfn "$WRAP_IN" "$BIN_HOST/$name"
  done < "$MF"
fi

# noglob is on for read loops; restore names must glob bin dirs.
set +f
for d in "$LP/usr/local/bin" "$LP/usr/local/sbin" "$LP/usr/bin" \
  "$LP/usr/sbin" "$LP/bin" "$LP/sbin"; do
  [ -d "$d" ] || continue
  for f in "$d"/*; do
    [ -e "$f" ] || [ -L "$f" ] || continue
    is_ours "$f" || continue
    n=`basename "$f"`
    case "$KEEP" in *" $n "*) continue ;; esac
    orig=
    [ -f "$STASH_HOST/$n.origin" ] && orig=`head -1 "$STASH_HOST/$n.origin"`
    rm -f "$f"
    if [ -n "$orig" ] && [ -e "$STASH_HOST/$n" ]; then
      rm -f "$orig"
      mv "$STASH_HOST/$n" "$orig"
      rm -f "$STASH_HOST/$n.origin"
    elif [ -e "$STASH_HOST/$n" ]; then
      mv "$STASH_HOST/$n" "$f"
      rm -f "$STASH_HOST/$n.origin"
    fi
  done
done
set -f

echo "atlas-managed-apply ok wrap=$WRAP_HOST"
