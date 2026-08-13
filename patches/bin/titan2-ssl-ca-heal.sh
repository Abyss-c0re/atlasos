#!/system/bin/sh
# titan2-ssl-ca-heal — GSI often has no /system/etc/security/cacerts (A14+ apex).
# System curl + static Linux tools fail: SSL certificate problem (60).
# Bind Apex conscrypt CAs + install Mozilla PEM for CURL_CA_BUNDLE.
#
# Safe to re-run. KernelSU/root. No Magisk modules.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH

APEX=/apex/com.android.conscrypt/cacerts
SYS=/system/etc/security/cacerts
PEM=/data/local/ssl/cacert.pem
ST=/data/local/tmp

log() { echo "ssl-ca-heal: $*" >>"$ST/titan2_ssl_ca_heal.log" 2>/dev/null || true; }

[ -d "$APEX" ] || { log "no apex cacerts"; exit 1; }

mkdir -p /data/local/ssl 2>/dev/null || true

# Mozilla PEM if present on tip / already staged
for s in /data/local/tmp/cacert.pem /data/local/ssl/cacert.pem \
         /data/user/0/com.titanus2.atlas/files/cacert.pem; do
  if [ -f "$s" ] && [ "$(stat -c %s "$s" 2>/dev/null || echo 0)" -gt 50000 ]; then
    [ "$s" = "$PEM" ] || cp -f "$s" "$PEM" 2>/dev/null || true
    break
  fi
done
chmod 644 "$PEM" 2>/dev/null || true

# Ensure mountpoint exists (system may be ro — use tmpfs under /system/etc if needed)
if [ ! -d /system/etc/security ]; then
  # /system/etc is often real; security missing
  mount -o remount,rw /system 2>/dev/null || true
  mkdir -p /system/etc/security 2>/dev/null || true
fi
if [ ! -d "$SYS" ]; then
  mount -o remount,rw /system 2>/dev/null || true
  mkdir -p "$SYS" 2>/dev/null || {
    # last resort: tmpfs for whole security dir
    mount -t tmpfs -o size=8m tmpfs /system/etc/security 2>/dev/null || true
    mkdir -p "$SYS" 2>/dev/null || true
  }
fi

# Bind if not already mounted
if ! mount | grep -q " $SYS "; then
  if [ -d "$SYS" ]; then
    mount --bind "$APEX" "$SYS" 2>/dev/null && log "bound $APEX -> $SYS" || log "bind failed"
  fi
else
  log "already mounted $SYS"
fi

n=$(ls "$SYS" 2>/dev/null | wc -l | tr -d ' ')
log "cacerts_count=$n pem=$([ -f "$PEM" ] && echo yes || echo no)"

# smoke
if command -v curl >/dev/null 2>&1; then
  if curl -sI -m5 https://auth.x.ai/ >/dev/null 2>&1; then
    log "curl_ok auth.x.ai"
  else
    # try with explicit pem
    if [ -f "$PEM" ]; then
      curl --cacert "$PEM" -sI -m5 https://auth.x.ai/ >/dev/null 2>&1 && log "curl_ok with pem" || log "curl still fail"
    else
      log "curl still fail no pem"
    fi
  fi
fi
exit 0
