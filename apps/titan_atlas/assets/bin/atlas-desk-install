#!/bin/sh
# Install a phone-sized Plasma set into the Debian root.
# Package list is the MindEye live desktop, cut to what an Xwayland seat uses.
# SDDM is not installed. Android keeps the panel.
set -u
export DEBIAN_FRONTEND=noninteractive
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
LOG=/tmp/desk-install.log
exec >>"$LOG" 2>&1
echo "=== install $(date 2>/dev/null || echo now) ==="
apt-get update -qq || exit 1
# The Debian root is a 1.5G volume. plasma-desktop does not fit.
# This is the Plasma seat that does: workspace, X11 KWin, one terminal.
apt-get install -y --no-install-recommends \
    -o APT::Keep-Downloaded-Packages=false \
    plasma-workspace \
    plasma-desktop \
    kwin-x11 \
    xwayland \
    x11-utils \
    x11-xserver-utils \
    konsole \
    breeze \
    fonts-noto-core \
    dbus \
    || exit 1
if [ -d /etc/systemd/system ]; then
    ln -sfn /dev/null /etc/systemd/system/display-manager.service || true
    rm -f /etc/systemd/system/graphical.target.wants/sddm.service \
          /etc/systemd/system/display-manager.service
    ln -sfn /dev/null /etc/systemd/system/display-manager.service || true
fi
apt-get clean
echo "INSTALLED plasma=$(command -v startplasma-x11 || true) kwin=$(command -v kwin_x11 || true)"
dpkg -s plasma-desktop | awk '/^Status:|^Version:/ {print}'
echo "DONE"
