#!/system/bin/sh
# pam_exec helper for Debian hybrid /usr/bin/sudo (real sudo + agent).
# Exit 0 = agent granted biometrics (or valid ticket).
export PATH="${ATLAS_BIN:-/data/user/0/com.titanus2.atlas/files/bin}:/system/bin:$PATH"
AUTH="${ATLAS_BIN:-}/atlas-auth"
[ -x "$AUTH" ] || AUTH=/data/user/0/com.titanus2.atlas/files/bin/atlas-auth
[ -x "$AUTH" ] || AUTH=/data/data/com.titanus2.atlas/files/bin/atlas-auth
if [ ! -x "$AUTH" ]; then
  echo "atlas-auth-pam: atlas-auth missing" >&2
  exit 1
fi
exec "$AUTH" request "pam sudo ${PAM_USER:-user} ${PAM_RHOST:-local}"
