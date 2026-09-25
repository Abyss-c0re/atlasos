#!/bin/sh
# One sudo for the desk and the Debian terminal.
# The terminal's PATH starts at /atlas-bin. The desk uses /usr/local/bin.
# Both must be the wrapper: password prompt, Atlas auth at the same time.
# su stays the enterd shim.
if [ -n "${ATLAS_LINUX_ROOT:-}" ]; then
    base=$ATLAS_LINUX_ROOT
elif [ -d /atlas-bin ] && [ -d /usr/local/bin ]; then
    base=
else
    base=/data/local/atlas-linux
fi
wrap="$base/usr/local/share/atlas/sudo"
[ -f "$wrap" ] || exit 0

# Debian's setuid sudo stays at sudo.real. Do not rename a small script.
if [ ! -e "$base/usr/bin/sudo.real" ] && [ -f "$base/usr/bin/sudo" ] && [ ! -L "$base/usr/bin/sudo" ]; then
    sz=$(wc -c < "$base/usr/bin/sudo" 2>/dev/null || echo 0)
    if [ "$sz" -gt 20000 ]; then
        mv -f "$base/usr/bin/sudo" "$base/usr/bin/sudo.real"
    fi
fi

install_sudo() {
    dest=$1
    mkdir -p "$(dirname "$dest")" 2>/dev/null || return 0
    cp -f "$wrap" "$dest" || return 0
    chmod 755 "$dest" 2>/dev/null || true
}

install_sudo "$base/usr/local/bin/sudo"
install_sudo "$base/atlas-bin/sudo"
# Hybrid merge is what the flashed boot script rewrites. Keep that copy too
# when it is a separate tree from the chroot.
if [ -n "$base" ]; then
    install_sudo /data/local/atlas-hybrid/merge/usr/local/bin/sudo
    install_sudo /data/local/atlas-hybrid/merge/atlas-bin/sudo
fi

if [ -x "$base/usr/bin/sudo.real" ]; then
    ln -sfn sudo.real "$base/usr/bin/sudo"
    ln -sfn sudo.real "$base/bin/sudo" 2>/dev/null || true
    chown 0:0 "$base/usr/bin/sudo.real" 2>/dev/null || true
    chmod 4755 "$base/usr/bin/sudo.real" 2>/dev/null || true
fi

stamp_src="$base/usr/local/share/atlas/atlas-sudo-stamp"
if [ -f "$stamp_src" ]; then
    mkdir -p "$base/usr/local/bin"
    cp -f "$stamp_src" "$base/usr/local/bin/atlas-sudo-stamp"
    chmod 755 "$base/usr/local/bin/atlas-sudo-stamp" 2>/dev/null || true
fi

pam="$base/etc/pam.d/sudo"
if [ -f "$pam" ] && [ -x "$base/usr/local/bin/atlas-sudo-stamp" ]; then
    if grep -q atlas-sudo-pam "$pam" 2>/dev/null \
        || ! grep -q atlas-sudo-stamp "$pam" 2>/dev/null; then
        tmp=$(mktemp 2>/dev/null || echo /tmp/atlas-sudo-pam.$$)
        {
            echo '#%PAM-1.0'
            echo 'auth [success=done default=ignore] pam_exec.so quiet /usr/local/bin/atlas-sudo-stamp'
            grep -v -E '^#%PAM-1.0|atlas-sudo-pam|atlas-sudo-stamp' "$pam" || true
        } > "$tmp"
        mv "$tmp" "$pam"
    fi
fi
