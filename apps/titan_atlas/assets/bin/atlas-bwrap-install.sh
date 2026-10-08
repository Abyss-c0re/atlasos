#!/bin/sh
# Install the Flatpak launcher. The stock kernel has no user or pid
# namespaces, so /usr/bin/bwrap cannot build a sandbox. This puts a mount
# namespace helper in its place. Atlas reaches it through sudo.real, which
# keeps the caller's file descriptors and records SUDO_UID.
set -u
PATH=/usr/sbin:/usr/bin:/sbin:/bin
PY=/usr/local/libexec/atlas-bwrap.py
MARK="# atlas-bwrap flatpak"
if [ ! -f "$PY" ]; then
    echo "atlas-bwrap: helper missing"
    exit 0
fi
chown root:root "$PY"
chmod 755 "$PY"
mkdir -p /usr/local/libexec
# sudo closes Flatpak's --args fd. A setuid helper keeps every descriptor.
BIN=""
for c in /usr/local/libexec/atlas-bwrap-bin \
         "$(dirname "$0")/atlas-bwrap-bin"; do
    if [ -f "$c" ]; then
        BIN=$c
        break
    fi
done
if [ -n "$BIN" ]; then
    if [ "$BIN" != /usr/local/libexec/atlas-bwrap-bin ]; then
        cp -f "$BIN" /usr/local/libexec/atlas-bwrap-bin
    fi
    chown root:root /usr/local/libexec/atlas-bwrap-bin
    chmod 4755 /usr/local/libexec/atlas-bwrap-bin
    if [ -x /usr/bin/bwrap ] && [ ! -e /usr/bin/bwrap.upstream ]; then
        if ! grep -q atlas-bwrap.py /usr/bin/bwrap 2>/dev/null; then
            mv /usr/bin/bwrap /usr/bin/bwrap.upstream 2>/dev/null || true
        fi
    fi
    cp -f /usr/local/libexec/atlas-bwrap-bin /usr/bin/bwrap
    chown root:root /usr/bin/bwrap
    chmod 4755 /usr/bin/bwrap
else
    echo "atlas-bwrap: setuid helper missing"
    exit 1
fi
if ! grep -q "$MARK" /etc/sudoers 2>/dev/null; then
    tmp=$(mktemp)
    cp /etc/sudoers "$tmp"
    cat >> "$tmp" << EOF
$MARK
Defaults:atlas closefrom_override
atlas ALL=(root) NOPASSWD:SETENV: /usr/bin/python3 -I /usr/local/libexec/atlas-bwrap.py
atlas ALL=(root) NOPASSWD:SETENV: /usr/bin/python3 -I /usr/local/libexec/atlas-bwrap.py *
EOF
    if visudo -c -f "$tmp" >/tmp/atlas-bwrap-sudoers.log 2>&1; then
        cp "$tmp" /etc/sudoers
        chmod 440 /etc/sudoers
        echo "atlas-bwrap: sudoers updated"
    else
        echo "atlas-bwrap: sudoers rejected"
        cat /tmp/atlas-bwrap-sudoers.log
    fi
    rm -f "$tmp"
fi
echo "atlas-bwrap: installed"
