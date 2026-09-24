#!/bin/sh
# Graphic session inside the Debian root. Android stays the display.
# atlas-x publishes the panel on $DIR. Plasma is X11, not a DRM seat.
# MindEye lesson kept: do not arm SDDM, night color off, blur/contrast off.
# Compositing follows ATLAS_DESK_COMPOSE. KWin alone uses virpipe.
# Plasma and apps stay on llvmpipe so a second GL client does not abort.
set -u
DIR=/home/atlas/atlas-x
W=${ATLAS_DESK_W:-1440}
H=${ATLAS_DESK_H:-1440}
# atlas-resolution writes this. A size chosen inside Debian wins over the
# Android default for this session and the next start.
if [ -f "$DIR/size" ]; then
    read -r SW SH <"$DIR/size" || true
    case "$SW" in ''|*[!0-9]*) SW= ;; esac
    case "$SH" in ''|*[!0-9]*) SH= ;; esac
    if [ -n "$SW" ] && [ -n "$SH" ]; then
        W=$SW
        H=$SH
    fi
fi
COMP=${ATLAS_DESK_COMPOSE:-0}
SCALE=${ATLAS_DESK_SCALE:-1}
mkdir -p "$DIR" /tmp/runtime-atlas /home/atlas/.config /root/.config
# No logind in this chroot, so Plasma hides Lock. The panel button is
# atlas-lock. Idle autolock stays off so a missed key cannot trap the desk.
cat > /home/atlas/.config/kscreenlockerrc << 'EOF'
[Daemon]
Autolock=false
LockOnResume=false
Timeout=0
EOF
cp -f /home/atlas/.config/kscreenlockerrc /root/.config/kscreenlockerrc 2>/dev/null || true
# Greeter calls PAM service "kde". Without this file every unlock is denied.
if [ ! -f /etc/pam.d/kde ]; then
    cat > /etc/pam.d/kde << 'EOF'
#%PAM-1.0
@include common-auth
@include common-account
@include common-password
@include common-session
EOF
    chmod 644 /etc/pam.d/kde || true
fi
# Panel lock button. Plasma's own Lock entry stays hidden without logind.
cat > /usr/local/bin/atlas-lock << 'EOF'
#!/bin/sh
DIR=/home/atlas/atlas-x
if [ -f "$DIR/xdisplay" ]; then
    DISPLAY=$(tr -d '[:space:]' <"$DIR/xdisplay")
    export DISPLAY
fi
export HOME=/home/atlas
export USER=atlas
export LOGNAME=atlas
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/runtime-atlas}"
export QT_QPA_PLATFORM=xcb
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
exec /usr/lib/aarch64-linux-gnu/libexec/kscreenlocker_greet --testing --immediateLock
EOF
chmod 755 /usr/local/bin/atlas-lock
mkdir -p /usr/local/share/applications
cat > /usr/local/share/applications/atlas-lock.desktop << 'EOF'
[Desktop Entry]
Type=Application
Name=Lock
GenericName=Lock Screen
Comment=Lock the desk. Unlock as atlas.
Exec=atlas-lock
Icon=system-lock-screen
Terminal=false
Categories=System;Security;
StartupNotify=false
EOF
cp -f /usr/local/share/applications/atlas-lock.desktop /usr/share/applications/atlas-lock.desktop
if [ -f /etc/xdg/menus/plasma-applications.menu ] && [ ! -e /etc/xdg/menus/applications.menu ]; then
    ln -s plasma-applications.menu /etc/xdg/menus/applications.menu
fi
# Show the user and the password immediately. The stock screen hides them
# until a keypress, which looks like there is no login.
sed -i 's/property bool uiVisible: false/property bool uiVisible: true/' \
    /usr/share/plasma/shells/org.kde.plasma.desktop/contents/lockscreen/LockScreenUi.qml 2>/dev/null || true
# TitanKey is root:input. Desk opens it directly while it is in front.
chmod 0666 /dev/input/event* 2>/dev/null || true
# Firefox refuses root when $HOME is not owned by root. The desk home is the
# Android app uid. Give that uid a name so the session is not root.
if ! id atlas >/dev/null 2>&1; then
    grep -q '^atlas:' /etc/group || echo 'atlas:x:10081:' >>/etc/group
    echo 'atlas:x:10081:10081:Atlas:/home/atlas:/bin/bash' >>/etc/passwd
fi
mkdir -p /dev/shm
if ! mountpoint -q /dev/shm 2>/dev/null; then
    mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs /dev/shm || true
fi
chmod 700 /tmp/runtime-atlas || true
LOG=$DIR/session.log
exec >>"$LOG" 2>&1
echo "=== desk $(date 2>/dev/null || echo now) ${W}x${H} compose=$COMP scale=$SCALE ==="
# First session copies the phone zone. Once LocalZone is written, that
# choice wins and /etc/localtime is brought back in line with it.
sync_tz() {
    tz=""
    cfg=/home/atlas/.config/ktimezonedrc
    mark=/home/atlas/.config/atlas-tz-seeded
    if [ -f "$cfg" ]; then
        tz=$(sed -n 's/^LocalZone=//p' "$cfg" | head -n 1)
        tz=$(printf '%s' "$tz" | tr -d '[:space:]')
    fi
    # ktimezoned records Etc/UTC on its own before anyone picks a zone.
    # That is not a choice. The phone zone wins until the marker exists.
    case "$tz" in
        ""|UTC|Etc/UTC) unset_tz=1 ;;
        *) unset_tz=0 ;;
    esac
    if [ ! -f "$mark" ] && [ "$unset_tz" = 1 ]; then
        tz=${ATLAS_TZ:-}
        if [ -z "$tz" ] && [ -f /tmp/atlas-virgl/android-tz ]; then
            tz=$(tr -d '[:space:]' < /tmp/atlas-virgl/android-tz)
        fi
        if [ -n "$tz" ] && [ -e "/usr/share/zoneinfo/$tz" ]; then
            mkdir -p /home/atlas/.config
            if [ -f "$cfg" ]; then
                sed -i "s#^LocalZone=.*#LocalZone=$tz#" "$cfg"
            else
                printf '[TimeZones]\nLocalZone=%s\nZoneinfoDir=/usr/share/zoneinfo\nZonetab=/usr/share/zoneinfo/zone.tab\n' "$tz" > "$cfg"
            fi
            chown atlas:atlas "$cfg" 2>/dev/null || true
            touch "$mark"
            chown atlas:atlas "$mark" 2>/dev/null || true
        else
            tz=""
        fi
    fi
    if [ -n "$tz" ] && [ -e "/usr/share/zoneinfo/$tz" ]; then
        ln -sfn "/usr/share/zoneinfo/$tz" /etc/localtime
        printf '%s\n' "$tz" > /etc/timezone
        export TZ="$tz"
        echo "tz=$tz"
    fi
}
sync_tz

if [ -f "$DIR/session.pid" ]; then
    old=$(cat "$DIR/session.pid" 2>/dev/null || true)
    if [ -n "$old" ] && [ "$old" != "$$" ]; then
        kill "$old" 2>/dev/null || true
    fi
fi
echo $$ >"$DIR/session.pid"

if [ ! -x /usr/local/bin/atlas-x ]; then
    echo "atlas-x missing"
    exit 1
fi
if [ ! -x /usr/bin/startplasma-x11 ] && [ ! -x /usr/bin/kwin_x11 ]; then
    echo "KDE not installed"
    exit 2
fi

enabled=false
[ "$COMP" = "1" ] && enabled=true
cat > /home/atlas/.config/kwinrc <<EOF
[Compositing]
Enabled=$enabled
Backend=OpenGL
GLCore=false

[NightColor]
Active=false
Mode=3
DayTemperature=6500
NightTemperature=6500
TransitionTime=0

[Plugins]
blurEnabled=false
contrastEnabled=false
nightlightEnabled=false
colorblindnesscorrectionEnabled=false
invertEnabled=false
EOF

if [ -d /etc/systemd/system ]; then
    ln -sfn /dev/null /etc/systemd/system/display-manager.service 2>/dev/null || true
fi

export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export XDG_RUNTIME_DIR=/tmp/runtime-atlas
export XDG_SESSION_TYPE=x11
export DESKTOP_SESSION=plasma
export QT_QPA_PLATFORM=xcb
export QT_SCALE_FACTOR="$SCALE"
# Wait for the GPU helper, but do not point Plasma at it. virpipe aborts
# in driCreateNewScreen for a second client, which is System Settings,
# Dolphin, and Kate dying as soon as they open a window.
i=0
while [ ! -S /tmp/atlas-virgl/virgl.sock ] && [ "$i" -lt 40 ]; do
    i=$((i + 1))
    sleep 0.1
done
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
unset VTEST_SOCKET_NAME || true
if [ -S /tmp/atlas-virgl/virgl.sock ]; then
    echo "gpu=llvmpipe apps, virpipe kwin"
else
    echo "gpu=llvmpipe"
fi
if [ "$COMP" = "1" ]; then
    export KWIN_COMPOSE=O2
else
    export KWIN_COMPOSE=N
fi

rm -f "$DIR/xdisplay" "$DIR/wayland-0" "$DIR/wayland-0.lock"
/usr/local/bin/atlas-x -d "$DIR" -g "${W}x${H}" -X &
AX=$!
i=0
while [ ! -s "$DIR/xdisplay" ] && [ "$i" -lt 80 ]; do
    i=$((i + 1))
    sleep 0.1
done
if [ ! -s "$DIR/xdisplay" ]; then
    echo "Xwayland did not publish a display"
    kill "$AX" 2>/dev/null || true
    exit 3
fi
DISPLAY=$(tr -d '[:space:]' <"$DIR/xdisplay")
export DISPLAY
echo "DISPLAY=$DISPLAY"
i=0
while ! xdpyinfo >/dev/null 2>&1 && [ "$i" -lt 50 ]; do
    i=$((i + 1))
    sleep 0.1
done

# startplasma execs /usr/bin/kwin_x11 by absolute path, so the wrapper has
# to live there. The real ELF is kept beside it.
install_kwin_wrapper() {
    if [ -f /usr/bin/kwin_x11 ]; then
        sig=$(dd if=/usr/bin/kwin_x11 bs=4 count=1 2>/dev/null | od -An -tx1)
        case "$sig" in
            *7f*45*4c*46*)
                mv -f /usr/bin/kwin_x11 /usr/bin/kwin_x11.bin
                ;;
        esac
    fi
    if [ ! -x /usr/bin/kwin_x11.bin ]; then
        echo "kwin wrapper: real binary missing"
        return 0
    fi
    cat > /usr/bin/kwin_x11 << 'EOF'
#!/bin/sh
if [ -S /tmp/atlas-virgl/virgl.sock ]; then
    export GALLIUM_DRIVER=virpipe
    export VTEST_SOCKET_NAME=/tmp/atlas-virgl/virgl.sock
    if [ -f /tmp/atlas-virgl/libatlas-virpipe-tfp.so ]; then
        export LD_PRELOAD=/tmp/atlas-virgl/libatlas-virpipe-tfp.so
    fi
else
    export GALLIUM_DRIVER=llvmpipe
    unset LD_PRELOAD
    unset VTEST_SOCKET_NAME
fi
export LIBGL_ALWAYS_SOFTWARE=1
export KWIN_OPENGL_INTERFACE=glx
export QSG_RENDER_LOOP=basic
export mesa_glthread=false
exec /usr/bin/kwin_x11.bin "$@"
EOF
    chmod 755 /usr/bin/kwin_x11
    cp -f /usr/bin/kwin_x11 /usr/local/bin/kwin_x11
    chmod 755 /usr/local/bin/kwin_x11
}
# No systemd --user in this chroot, so the shortcut daemon is not activated
# from its user unit. Super and the application menu need this service file.
mkdir -p /usr/share/dbus-1/services
cat > /usr/share/dbus-1/services/org.kde.kglobalaccel.service << 'EOF'
[D-BUS Service]
Name=org.kde.kglobalaccel
Exec=/usr/lib/aarch64-linux-gnu/libexec/kglobalacceld
EOF
install_kwin_wrapper
GPU_LINES="export GALLIUM_DRIVER=llvmpipe
export LIBGL_ALWAYS_SOFTWARE=1
export QSG_RENDER_LOOP=basic
export mesa_glthread=false"
if [ -n "${TZ:-}" ]; then
    GPU_LINES="$GPU_LINES
export TZ='$TZ'"
fi
# Discover reads the Debian catalog through PackageKit. There is no
# systemd here, so the system bus and packagekitd are started directly.
# An empty apt list dir means the image was packed without the catalog.
mkdir -p /run/dbus /usr/share/metainfo /var/lib/apt/lists/partial
if [ ! -f /usr/share/metainfo/org.debian.debian.metainfo.xml ]; then
    cat > /usr/share/metainfo/org.debian.debian.metainfo.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<component type="operating-system">
  <id>org.debian.debian</id>
  <name>Debian</name>
  <summary>Debian GNU/Linux</summary>
  <metadata_license>FSFAP</metadata_license>
  <url type="homepage">https://www.debian.org/</url>
</component>
EOF
fi
if ! pgrep -f "dbus-daemon --system" >/dev/null 2>&1; then
    dbus-daemon --system --fork || true
fi
if ! pgrep -x packagekitd >/dev/null 2>&1 && [ -x /usr/libexec/packagekitd ]; then
    /usr/libexec/packagekitd >/tmp/packagekitd.log 2>&1 &
fi
if [ ! -d /var/lib/apt/lists ] || [ -z "$(ls /var/lib/apt/lists 2>/dev/null | head -1)" ]; then
    (
        apt-get update
        appstreamcli refresh --force
    ) >/tmp/appstream-refresh.log 2>&1 &
fi
# sudo is real Debian sudo. Atlas auth runs beside the password prompt.
if [ -x /usr/bin/sudo.real ]; then
    ln -sfn sudo.real /usr/bin/sudo
    ln -sfn sudo.real /bin/sudo
fi
if [ -f /usr/local/share/atlas/sudo ]; then
    cp -f /usr/local/share/atlas/sudo /usr/local/bin/sudo
    chmod 755 /usr/local/bin/sudo
fi
if [ -f /usr/local/share/atlas/atlas-sudo-stamp ]; then
    cp -f /usr/local/share/atlas/atlas-sudo-stamp /usr/local/bin/atlas-sudo-stamp
    chmod 755 /usr/local/bin/atlas-sudo-stamp
fi
pam=/etc/pam.d/sudo
if [ -f "$pam" ] && [ -x /usr/local/bin/atlas-sudo-stamp ]; then
    if grep -q atlas-sudo-pam "$pam" || ! grep -q atlas-sudo-stamp "$pam"; then
        tmp=$(mktemp)
        {
            echo '#%PAM-1.0'
            echo 'auth [success=done default=ignore] pam_exec.so quiet /usr/local/bin/atlas-sudo-stamp'
            grep -v -E '^#%PAM-1.0|atlas-sudo-pam|atlas-sudo-stamp' "$pam" || true
        } > "$tmp"
        mv "$tmp" "$pam"
    fi
fi
if ! grep -q '^atlas ' /etc/sudoers 2>/dev/null; then
    echo 'atlas ALL=(ALL:ALL) ALL' >> /etc/sudoers
fi
# PipeWire writes the speaker fifo. The feeder is what plays it.
# Debian does not open ALSA.
if [ -p /tmp/atlas-audio.fifo ] && [ -S /tmp/atlas-virgl/audio.sock ] \
    && ! pgrep -f atlas-audio-feeder >/dev/null 2>&1; then
    if [ -f /usr/local/bin/atlas-audio-feeder.py ]; then
        python3 /usr/local/bin/atlas-audio-feeder.py >/tmp/audio-feeder.log 2>&1 &
    fi
fi
cat > /tmp/atlas-desk-launch.sh <<EOF
#!/bin/sh
export HOME=/home/atlas
export USER=atlas
export LOGNAME=atlas
export XDG_CACHE_HOME=/home/atlas/.cache
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export DISPLAY='$DISPLAY'
export XDG_RUNTIME_DIR='$XDG_RUNTIME_DIR'
export XDG_SESSION_TYPE=x11
export DESKTOP_SESSION=plasma
export QT_QPA_PLATFORM=xcb
export QT_QPA_PLATFORMTHEME=kde
export XDG_CURRENT_DESKTOP=KDE
export KDE_FULL_SESSION=true
export KDE_SESSION_VERSION=6
export QT_SCALE_FACTOR='$SCALE'
export KWIN_COMPOSE='$KWIN_COMPOSE'
$GPU_LINES
if [ -x /usr/bin/dbus-run-session ] && [ -x /usr/bin/startplasma-x11 ]; then
    exec dbus-run-session -- startplasma-x11
fi
if [ -x /usr/bin/kwin_x11 ]; then
    kwin_x11 --replace &
fi
if [ -x /usr/bin/plasmashell ]; then
    exec plasmashell
fi
echo "no plasma binaries"
exit 2
EOF
chmod 755 /tmp/atlas-desk-launch.sh
chmod 0666 "$DIR"/present.sock "$DIR"/input.sock "$DIR"/wayland-0 2>/dev/null || true
if id atlas >/dev/null 2>&1 && [ -x /usr/bin/setpriv ]; then
    mkdir -p /home/atlas/.cache /home/atlas/.config /tmp/runtime-atlas
    chown -R atlas:atlas /tmp/atlas-desk-launch.sh /home/atlas/.cache /home/atlas/.config /tmp/runtime-atlas 2>/dev/null || true
    export HOME=/home/atlas USER=atlas LOGNAME=atlas XDG_CACHE_HOME=/home/atlas/.cache
    setpriv --reuid=10081 --regid=10081 --clear-groups --inh-caps=-all \
        /bin/sh /tmp/atlas-desk-launch.sh &
    PL=$!
else
    /tmp/atlas-desk-launch.sh &
    PL=$!
fi
echo "$PL" >"$DIR/plasma.pid"
# kscreenlocker reads its timeout once. Poke the idle timer so a session that
# already armed the locker cannot grab the keyboard out from under the desk.
(
    sleep 8
    while true; do
        p=$(pidof plasmashell 2>/dev/null || true)
        bus=
        if [ -n "$p" ]; then
            bus=$(tr '\0' '\n' < /proc/"$p"/environ 2>/dev/null \
                | sed -n 's/^DBUS_SESSION_BUS_ADDRESS=//p' | head -n 1)
        fi
        if [ -n "$bus" ]; then
            DBUS_SESSION_BUS_ADDRESS="$bus" dbus-send --dest=org.freedesktop.ScreenSaver \
                /ScreenSaver org.freedesktop.ScreenSaver.SimulateUserActivity \
                >/dev/null 2>&1 || true
        fi
        sleep 20
    done
) &
wait "$AX"
