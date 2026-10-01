#!/bin/sh
# Graphic session inside the Debian root. Android stays the display.
# atlas-x publishes the panel on $DIR. Plasma is X11, not a DRM seat.
# MindEye lesson kept: do not arm SDDM, night color off, blur/contrast off.
# Compositing follows ATLAS_DESK_COMPOSE. KWin alone uses virpipe.
# Plasma and apps stay on llvmpipe so a second GL client does not abort.
# Each step is one line in session.log and in /tmp/atlas-virgl/desk-phase
# so the glass can show the trace instead of a silent black screen.
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
# Compositing off leaves rootless Xwayland's buffer black. The cursor
# is a separate sprite, so Restart looked like a mouse on a black glass.
COMP=${ATLAS_DESK_COMPOSE:-1}
SCALE=${ATLAS_DESK_SCALE:-1}
mkdir -p "$DIR" /tmp/runtime-atlas /tmp/atlas-virgl /home/atlas/.config /root/.config
# One line per step. The Android glass reads desk-phase while the picture is black.
PHASE=/tmp/atlas-virgl/desk-phase
T0=$(date +%s 2>/dev/null || echo 0)
LAST_PHASE=
: >"$PHASE"
chmod 644 "$PHASE" 2>/dev/null || true
phase() {
    now=$(date +%s 2>/dev/null || echo "$T0")
    el=$((now - T0))
    line="${el}s  $*"
    [ "$line" = "${LAST_PHASE:-}" ] && return 0
    LAST_PHASE=$line
    echo "phase $line"
    printf '%s\n' "$line" >>"$PHASE"
}
# Own the directory, not the tree. .grok and .cache are hundreds of megabytes
# and a recursive chown on every start is a silent pause.
own_dir() {
    d=$1
    mkdir -p "$d" || return 0
    uid=$(stat -c %u "$d" 2>/dev/null || echo "")
    if [ "$uid" != "10081" ]; then
        chown atlas:atlas "$d" 2>/dev/null || true
    fi
}
LOG=$DIR/session.log
if [ -f "$LOG" ]; then
    mv -f "$LOG" "$DIR/session.log.1" 2>/dev/null || true
fi
: >"$LOG"
exec >>"$LOG" 2>&1
phase "preparing the desk"
echo "=== desk $(date 2>/dev/null || echo now) ${W}x${H} compose=$COMP scale=$SCALE ==="
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
# A second identical row makes nss and sudo complain, so never append over one.
if ! grep -q '^atlas:' /etc/group 2>/dev/null; then
    echo 'atlas:x:10081:' >>/etc/group
fi
if ! grep -q '^atlas:' /etc/passwd 2>/dev/null; then
    echo 'atlas:x:10081:10081:Atlas:/home/atlas:/bin/bash' >>/etc/passwd
fi
# One atlas row, and a hosts file that names this machine. An empty /etc/hosts
# makes sudo warn on every command because Titan2 does not resolve.
dedupe_name() {
    file=$1
    name=$2
    [ -f "$file" ] || return 0
    seen=0
    tmp=${file}.dedupe
    : >"$tmp"
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            "$name:"*)
                if [ "$seen" = 1 ]; then
                    continue
                fi
                seen=1
                ;;
        esac
        printf '%s\n' "$line" >>"$tmp"
    done <"$file"
    if cmp -s "$tmp" "$file" 2>/dev/null; then
        rm -f "$tmp"
    else
        mv -f "$tmp" "$file"
        echo "deduped $name in $file"
    fi
}
dedupe_name /etc/passwd atlas
dedupe_name /etc/group atlas
hn=$(tr -d '[:space:]' </etc/hostname 2>/dev/null || true)
[ -n "$hn" ] || hn=Titan2
if [ ! -s /etc/hosts ] || ! grep -q 'localhost' /etc/hosts 2>/dev/null; then
    printf '127.0.0.1\tlocalhost %s\n::1\tlocalhost ip6-localhost ip6-loopback\n' "$hn" >/etc/hosts
    echo "hosts rewritten for $hn"
elif ! grep -q "$hn" /etc/hosts 2>/dev/null; then
    sed -i "s/^127\\.0\\.0\\.1[[:space:]].*/127.0.0.1\tlocalhost $hn/" /etc/hosts
    echo "hosts names $hn"
fi
mkdir -p /dev/shm
if ! mountpoint -q /dev/shm 2>/dev/null; then
    mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs /dev/shm || true
fi
chmod 700 /tmp/runtime-atlas || true
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
        phase "timezone $tz"
    fi
}
sync_tz

# One Plasma session. Opening the desk again attaches to it.
# Killing this script used to take the desktop down with it.
write_session_env() {
    disp=$(tr -d '[:space:]' <"$DIR/xdisplay" 2>/dev/null || true)
    [ -n "$disp" ] || return 0
    bus=
    p=$(pidof plasmashell 2>/dev/null | awk '{print $1}')
    if [ -n "$p" ] && [ -r "/proc/$p/environ" ]; then
        bus=$(tr '\0' '\n' < "/proc/$p/environ" \
            | sed -n 's/^DBUS_SESSION_BUS_ADDRESS=//p' | head -n 1)
    fi
    umask 022
    {
        echo "DISPLAY=$disp"
        echo "XDG_RUNTIME_DIR=/tmp/runtime-atlas"
        echo "XDG_SESSION_TYPE=x11"
        echo "DESKTOP_SESSION=plasma"
        echo "XDG_CURRENT_DESKTOP=KDE"
        echo "KDE_FULL_SESSION=true"
        echo "KDE_SESSION_VERSION=6"
        echo "QT_QPA_PLATFORM=xcb"
        echo "QT_QPA_PLATFORMTHEME=kde"
        if [ -n "$bus" ]; then
            echo "DBUS_SESSION_BUS_ADDRESS=$bus"
        fi
        if [ -S /tmp/runtime-atlas/pulse/native ]; then
            echo "PULSE_SERVER=unix:/tmp/runtime-atlas/pulse/native"
        fi
    } > "$DIR/session.env"
    chmod 644 "$DIR/session.env" 2>/dev/null || true
}

# One implementation, also used by the root helper before this script runs.
# Killing only KWin leaves plasma_session and ksmserver, and those report a crash.
ATLAS_DESK_STOP_KEEP=$$
if [ -r /usr/local/libexec/atlas-desk-stop.sh ]; then
    # shellcheck disable=SC1091
    . /usr/local/libexec/atlas-desk-stop.sh
fi
if ! command -v stop_desk_stack >/dev/null 2>&1; then
    echo "atlas-desk-stop missing" >&2
    stop_desk_stack() {
        echo "restart: stop helper missing" >&2
        return 1
    }
fi

if [ "${ATLAS_DESK_RESTART:-}" = 1 ]; then
    echo "restarting session"
    phase "stopping the old session"
    stop_desk_stack
    phase "old session stopped"
elif [ -s "$DIR/xdisplay" ] && pidof atlas-x >/dev/null 2>&1 \
    && pidof plasmashell >/dev/null 2>&1 \
    && { pidof kwin_x11.bin >/dev/null 2>&1 || pidof kwin_x11 >/dev/null 2>&1; }; then
    write_session_env
    echo "attached DISPLAY=$(tr -d '[:space:]' <"$DIR/xdisplay")"
    phase "desktop already running"
    exit 0
fi

if [ -f "$DIR/session.pid" ]; then
    old=$(cat "$DIR/session.pid" 2>/dev/null || true)
    if [ -n "$old" ] && [ "$old" != "$$" ]; then
        kill "$old" 2>/dev/null || true
    fi
fi
echo $$ >"$DIR/session.pid"

if [ ! -x /usr/local/bin/atlas-x ]; then
    echo "atlas-x missing"
    phase "display server is missing"
    exit 1
fi
if [ ! -x /usr/bin/startplasma-x11 ] && [ ! -x /usr/bin/kwin_x11 ]; then
    echo "KDE not installed"
    phase "KDE is not installed"
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
    phase "waiting for the GPU helper"
    i=$((i + 1))
    sleep 0.1
done
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
unset VTEST_SOCKET_NAME || true
if [ -S /tmp/atlas-virgl/virgl.sock ]; then
    echo "gpu=llvmpipe apps, virpipe kwin"
    phase "GPU helper ready"
else
    echo "gpu=llvmpipe"
    phase "no GPU helper, software only"
fi
if [ "$COMP" = "1" ]; then
    export KWIN_COMPOSE=O2
else
    export KWIN_COMPOSE=N
fi

rm -f "$DIR/xdisplay" "$DIR/wayland-0" "$DIR/wayland-0.lock"
phase "starting the display ${W}x${H}"
/usr/local/bin/atlas-x -d "$DIR" -g "${W}x${H}" -X &
AX=$!
i=0
while [ ! -s "$DIR/xdisplay" ] && [ "$i" -lt 80 ]; do
    phase "waiting for the display"
    i=$((i + 1))
    sleep 0.1
done
if [ ! -s "$DIR/xdisplay" ]; then
    echo "Xwayland did not publish a display"
    phase "display did not start"
    kill "$AX" 2>/dev/null || true
    exit 3
fi
DISPLAY=$(tr -d '[:space:]' <"$DIR/xdisplay")
export DISPLAY
echo "DISPLAY=$DISPLAY"
phase "display $DISPLAY"
write_session_env
i=0
while ! xdpyinfo >/dev/null 2>&1 && [ "$i" -lt 50 ]; do
    phase "waiting until the display accepts clients"
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
# A dead GPU socket aborts KWin and the desk stays black. If virpipe
# exits during startup, paint with llvmpipe instead of leaving no compositor.
export KWIN_OPENGL_INTERFACE=glx
export QSG_RENDER_LOOP=basic
export mesa_glthread=false
export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"

soft() {
    export GALLIUM_DRIVER=llvmpipe
    unset LD_PRELOAD
    unset VTEST_SOCKET_NAME
    export LIBGL_ALWAYS_SOFTWARE=1
    echo "kwin: software" >> /tmp/kwin-wrapper.log
    exec /usr/bin/kwin_x11.bin "$@"
}

# pidof inside the chroot does not see the root virgl server, so the
# socket is the signal. If that server drops the connection, KWin aborts
# and the branch below paints with llvmpipe.
if [ -S /tmp/atlas-virgl/virgl.sock ]; then
    export GALLIUM_DRIVER=virpipe
    export VTEST_SOCKET_NAME=/tmp/atlas-virgl/virgl.sock
    if [ -f /tmp/atlas-virgl/libatlas-virpipe-tfp.so ]; then
        export LD_PRELOAD=/tmp/atlas-virgl/libatlas-virpipe-tfp.so
    fi
    unset LIBGL_ALWAYS_SOFTWARE
    /usr/bin/kwin_x11.bin "$@" >/tmp/kwin-gpu.log 2>&1 &
    child=$!
    alive=0
    i=0
    while [ "$i" -lt 20 ]; do
        if ! kill -0 "$child" 2>/dev/null; then
            alive=0
            break
        fi
        alive=1
        i=$((i + 1))
        sleep 0.2
    done
    if [ "$alive" = 1 ]; then
        echo "kwin: gpu" >> /tmp/kwin-wrapper.log
        wait "$child"
        exit $?
    fi
    echo "kwin: gpu exited, software fallback" >> /tmp/kwin-wrapper.log
fi
soft "$@"
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
phase "preparing the window manager"
install_kwin_wrapper
# No systemd --user. The default boot asks systemd, fails, then waits on
# the splash bus name. That wait is the long black screen.
cat > /home/atlas/.config/startkderc << 'EOF'
[General]
systemdBoot=false

[WaitForDrKonqi]
Enabled=false
EOF
cat > /home/atlas/.config/ksplashrc << 'EOF'
[KSplash]
Engine=none
EOF
chown atlas:atlas /home/atlas/.config/startkderc /home/atlas/.config/ksplashrc 2>/dev/null || true
mkdir -p /usr/share/dbus-1/services
cat > /usr/share/dbus-1/services/org.kde.KSplash.service << 'EOF'
[D-BUS Service]
Name=org.kde.KSplash
Exec=/bin/true
EOF
ks=/home/atlas/.config/ksmserverrc
if [ ! -f "$ks" ]; then
    printf '[General]\nloginMode=empty\n' >"$ks"
elif ! grep -q '^loginMode=' "$ks"; then
    printf '\n[General]\nloginMode=empty\n' >>"$ks"
fi
chown atlas:atlas "$ks" 2>/dev/null || true
if [ ! -f /home/atlas/.config/baloofilerc ]; then
    printf '[Basic Settings]\nIndexing-Enabled=false\n' > /home/atlas/.config/baloofilerc
    chown atlas:atlas /home/atlas/.config/baloofilerc 2>/dev/null || true
fi
hide_autostart() {
    name=$1
    dest=/home/atlas/.config/autostart/$name
    [ -e "$dest" ] && return 0
    [ -f "/etc/xdg/autostart/$name" ] || return 0
    mkdir -p /home/atlas/.config/autostart
    printf '[Desktop Entry]\nHidden=true\n' >"$dest"
    chown atlas:atlas "$dest" 2>/dev/null || true
    echo "autostart off $name"
}
hide_autostart baloo_file.desktop
hide_autostart org.kde.discover.notifier.desktop
hide_autostart org.kde.kdeconnect.daemon.desktop
phase "splash off, file search off"
GPU_LINES="export GALLIUM_DRIVER=llvmpipe
export LIBGL_ALWAYS_SOFTWARE=1
export QSG_RENDER_LOOP=basic
export mesa_glthread=false"
if [ -n "${TZ:-}" ]; then
    GPU_LINES="$GPU_LINES
export TZ='$TZ'"
fi
# PackageKit and apt run after the shell is up. They used to sit on the
# path between the display and Plasma.
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
# A dead dbus-daemon leaves /run/dbus/system_bus_socket behind. The next
# start cannot bind it, so Discover, PackageKit, and polkit all get
# "Connection refused". Clear the socket, then start polkit before PackageKit.
start_system_bus() {
    if pgrep -f "dbus-daemon --system" >/dev/null 2>&1 \
        && [ -S /run/dbus/system_bus_socket ] \
        && dbus-send --system --dest=org.freedesktop.DBus \
            /org/freedesktop/DBus org.freedesktop.DBus.Peer.Ping >/dev/null 2>&1; then
        return 0
    fi
    # Kill by pid file and by command name. pkill -f would also match a
    # shell whose own command line mentions the system bus.
    if [ -f /run/dbus/pid ]; then
        old=$(cat /run/dbus/pid 2>/dev/null || true)
        if [ -n "$old" ]; then
            kill "$old" 2>/dev/null || true
        fi
    fi
    for p in /proc/[0-9]*; do
        cmd=$(tr '\0' ' ' <"$p/cmdline" 2>/dev/null || true)
        case "$cmd" in
            *'dbus-daemon --system'*)
                kill "${p##*/}" 2>/dev/null || true
                ;;
        esac
    done
    sleep 0.1
    rm -f /run/dbus/pid /run/dbus/system_bus_socket
    mkdir -p /run/dbus
    dbus-daemon --system --fork || return 1
    i=0
    while [ "$i" -lt 20 ]; do
        if [ -S /run/dbus/system_bus_socket ] \
            && dbus-send --system --dest=org.freedesktop.DBus \
                /org/freedesktop/DBus org.freedesktop.DBus.Peer.Ping >/dev/null 2>&1; then
            echo "system bus up"
            return 0
        fi
        i=$((i + 1))
        sleep 0.1
    done
    echo "system bus did not answer"
    return 1
}
desk_later() {
    echo "later: catalog, packagekit, sudo"
    if start_system_bus; then
        pk_svc=/usr/share/dbus-1/system-services/org.freedesktop.PackageKit.service
        if [ -f "$pk_svc" ] && grep -q '^SystemdService=' "$pk_svc"; then
            sed -i '/^SystemdService=/d' "$pk_svc"
        fi
        if ! pgrep -x polkitd >/dev/null 2>&1 && [ -x /usr/lib/polkit-1/polkitd ]; then
            /usr/lib/polkit-1/polkitd --no-debug >/tmp/polkitd.log 2>&1 &
            echo "polkit started"
        fi
        # fwupd cannot run here. Discover otherwise opens with that error.
        if dpkg -s plasma-discover-backend-fwupd >/dev/null 2>&1; then
            DEBIAN_FRONTEND=noninteractive dpkg -r plasma-discover-backend-fwupd \
                >>/tmp/discover-quiet.log 2>&1 || true
        fi
        if ! pgrep -x packagekitd >/dev/null 2>&1 && [ -x /usr/libexec/packagekitd ]; then
            /usr/libexec/packagekitd >/tmp/packagekitd.log 2>&1 &
            echo "packagekit started"
        fi
        # Flatpak asks Accounts after the download. DBus must exec the
        # daemon itself; the systemd unit line makes that fail.
        acc=/usr/share/dbus-1/system-services/org.freedesktop.Accounts.service
        if [ -f "$acc" ] && grep -q '^SystemdService=' "$acc"; then
            sed -i '/^SystemdService=/d' "$acc"
        fi
        if ! pgrep -x accounts-daemon >/dev/null 2>&1 && [ -x /usr/libexec/accounts-daemon ]; then
            /usr/libexec/accounts-daemon >/tmp/accounts-daemon.log 2>&1 &
            echo "accounts started"
        fi
    fi
    if [ ! -d /var/lib/apt/lists ] || [ -z "$(ls /var/lib/apt/lists 2>/dev/null | head -1)" ]; then
        apt-get update >>/tmp/appstream-refresh.log 2>&1 || true
        if [ -x /usr/bin/appstreamcli ]; then
            appstreamcli refresh --force >>/tmp/appstream-refresh.log 2>&1 || true
        fi
    fi
    if [ -x /usr/local/libexec/atlas-sudo-install.sh ]; then
        /usr/local/libexec/atlas-sudo-install.sh || true
    fi
    if ! dpkg -s plasma-pa >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
            plasma-pa >>/tmp/plasma-pa-install.log 2>&1 || true
        echo "plasma-pa install attempted"
    fi
    if ! command -v flatpak >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get update >>/tmp/flatpak-install.log 2>&1 || true
        DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
            flatpak plasma-discover-backend-flatpak \
            >>/tmp/flatpak-install.log 2>&1 || true
        echo "flatpak install attempted"
    fi
    if [ -x /usr/local/libexec/atlas-bwrap-install.sh ]; then
        /usr/local/libexec/atlas-bwrap-install.sh || true
    fi
    if command -v flatpak >/dev/null 2>&1 && [ -x /usr/bin/setpriv ]; then
        setpriv --reuid=10081 --regid=10081 --clear-groups --inh-caps=-all \
            env HOME=/home/atlas USER=atlas \
            flatpak remote-add --user --if-not-exists flathub \
            https://dl.flathub.org/repo/flathub.flatpakrepo \
            >>/tmp/flatpak-install.log 2>&1 || true
        # The running menu cannot see the export directory until the next
        # session. Copy the desktop files where Plasma already looks.
        apps=/home/atlas/.local/share/flatpak/exports/share/applications
        dest=/home/atlas/.local/share/applications
        if [ -d "$apps" ]; then
            mkdir -p "$dest"
            cp -f "$apps"/*.desktop "$dest"/ 2>/dev/null || true
            chown -R atlas:atlas "$dest" 2>/dev/null || true
        fi
    fi
    if [ -x /usr/local/libexec/atlas-blackcube-install.sh ]; then
        /usr/local/libexec/atlas-blackcube-install.sh >/dev/null 2>&1 || true
    fi
    if ! grep -q '^atlas ' /etc/sudoers 2>/dev/null; then
        echo 'atlas ALL=(ALL:ALL) ALL' >> /etc/sudoers
    fi
    # Discover's Flatpak rule allows the sudo group. PackageKit has no
    # agent on this seat, so atlas must be allowed without a prompt.
    if [ -f /etc/group ] && grep -q '^sudo:' /etc/group \
        && ! grep -q '^sudo:.*\<atlas\>' /etc/group; then
        sed -i 's/^sudo:\([^:]*\):\([^:]*\):\(.*\)/sudo:\1:\2:\3,atlas/; s/:,atlas/:atlas/' /etc/group
        echo "atlas in sudo group"
    fi
    if [ -d /etc/polkit-1/rules.d ]; then
        cat > /etc/polkit-1/rules.d/40-atlas-install.rules << 'EOF'
polkit.addRule(function(action, subject) {
    if (subject.user != "atlas")
        return polkit.Result.NOT_HANDLED;
    if (action.id.indexOf("org.freedesktop.packagekit.") == 0 ||
        action.id.indexOf("org.freedesktop.Flatpak.") == 0)
        return polkit.Result.YES;
    return polkit.Result.NOT_HANDLED;
});
EOF
        chown root:root /etc/polkit-1/rules.d/40-atlas-install.rules 2>/dev/null || true
        chmod 644 /etc/polkit-1/rules.d/40-atlas-install.rules 2>/dev/null || true
    fi
    if [ -x /usr/bin/flatpak ] && [ -x /usr/bin/setpriv ]; then
        /usr/bin/setpriv --reuid=10081 --regid=10081 --clear-groups --inh-caps=-all \
            /usr/bin/env HOME=/home/atlas USER=atlas \
            XDG_DATA_HOME=/home/atlas/.local/share \
            /usr/bin/flatpak update --appstream --user \
            >/tmp/flatpak-appstream.log 2>&1 &
        echo "flatpak appstream refresh"
    fi
    echo "later: done"
}
mkdir -p /etc/profile.d
own_dir /home/atlas/.grok
own_dir /home/atlas/.grok/data
own_dir /home/atlas/.local
own_dir /home/atlas/.local/bin
cat > /etc/profile.d/atlas-desk.sh << 'EOF'
# Desk session. HOME stays the Debian home. Grok and Pulse live there.
if [ -d /home/atlas ]; then
    export HOME=/home/atlas
fi
case ":$PATH:" in
    *:/home/atlas/.local/bin:*) ;;
    *) export PATH="/home/atlas/.local/bin:/home/atlas/.grok/bin:${PATH:-/usr/bin:/bin}" ;;
esac
if [ -z "${XDG_RUNTIME_DIR:-}" ] && [ -d /tmp/runtime-atlas ]; then
    export XDG_RUNTIME_DIR=/tmp/runtime-atlas
fi
if [ -n "${XDG_RUNTIME_DIR:-}" ] && [ -S "$XDG_RUNTIME_DIR/pulse/native" ]; then
    export PULSE_SERVER="unix:$XDG_RUNTIME_DIR/pulse/native"
fi
EOF
chmod 644 /etc/profile.d/atlas-desk.sh
cat > /usr/local/bin/atlas-grok << 'EOF'
#!/bin/sh
export HOME=/home/atlas
export USER=atlas
export LOGNAME=atlas
export PATH="/home/atlas/.local/bin:/home/atlas/.grok/bin:${PATH:-/usr/bin:/bin}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/runtime-atlas}"
mkdir -p "$HOME/.grok/data" || exit 1
if [ -x /usr/local/libexec/atlas-blackcube-install.sh ]; then
  /usr/local/libexec/atlas-blackcube-install.sh >/dev/null 2>&1 || true
fi
cd "$HOME" || exit 1
exec /home/atlas/.local/bin/grok "$@"
EOF
chmod 755 /usr/local/bin/atlas-grok
mkdir -p /usr/local/share/applications /usr/share/applications
cat > /usr/local/share/applications/atlas-grok.desktop << 'EOF'
[Desktop Entry]
Type=Application
Name=Grok
GenericName=Grok
Comment=Grok Build in a terminal
Exec=konsole -e atlas-grok
Icon=utilities-terminal
Terminal=false
Categories=Development;
StartupNotify=false
EOF
cp -f /usr/local/share/applications/atlas-grok.desktop /usr/share/applications/atlas-grok.desktop
# Speaker and mic are FIFOs the Atlas app opens. This seat has no user
# systemd, so PipeWire does not come up with Plasma unless we start it.
install_desk_audio() {
    mkdir -p /usr/local/bin /tmp/atlas-virgl /tmp/runtime-atlas
    for f in audio-play audio-cap; do
        if [ ! -p "/tmp/atlas-virgl/$f" ]; then
            rm -f "/tmp/atlas-virgl/$f"
            mkfifo -m 666 "/tmp/atlas-virgl/$f" 2>/dev/null || true
        fi
    done
    cat > /usr/local/bin/atlas-audio-pw << 'EOF'
#!/bin/sh
# PipeWire ends of the desk audio FIFOs. No socket, no port.
# Playback: atlas-android. Capture: atlas-mic.
#
# The tunnels live in this pw-cli, not in the daemon. pw-cli spins on stdin
# EOF (one full core, mostly in the kernel) once the writer exits. A finite
# sleep used to end after a day and leave that spin up until the next boot.
# Hold the write end open for the life of pw-cli, and kill pw-cli if the
# writer dies so a closed stdin cannot spin.
export PATH=/usr/bin:/bin
export HOME=/home/atlas
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/runtime-atlas}"
PLAY=/tmp/atlas-virgl/audio-play
CAP=/tmp/atlas-virgl/audio-cap
IN=/tmp/atlas-audio-pw.in
CLI_PID=/tmp/atlas-audio-pw.cli.pid
WRITER_PID=/tmp/atlas-audio-pw.writer.pid

_killpid() {
  _p=
  [ -f "$1" ] || return 0
  IFS= read -r _p < "$1" || return 0
  case "$_p" in ''|*[!0-9]*) return 0 ;; esac
  kill "$_p" 2>/dev/null || true
}

_killpid "$CLI_PID"
_killpid "$WRITER_PID"
rm -f "$IN"
mkfifo "$IN" || exit 1

(
  echo "load-module libpipewire-module-pipe-tunnel { tunnel.mode = sink pipe.filename = ${PLAY} audio.format = S16 audio.rate = 48000 audio.channels = 2 node.name = atlas-android node.description = AtlasAndroid media.name = AtlasAndroid node.virtual = false tunnel.may-pause = true }"
  echo "load-module libpipewire-module-pipe-tunnel { tunnel.mode = source pipe.filename = ${CAP} audio.format = S16 audio.rate = 48000 audio.channels = 2 node.name = atlas-mic node.description = AtlasMic media.name = AtlasMic node.virtual = false tunnel.may-pause = true }"
  if ! sleep infinity >/dev/null 2>&1; then
    while true; do sleep 3600; done
  fi
) > "$IN" &
writer=$!
echo "$writer" > "$WRITER_PID"

pw-cli < "$IN" > /tmp/atlas-audio-pw.log 2>&1 &
cli=$!
echo "$cli" > "$CLI_PID"

_stop() {
  kill "$cli" "$writer" 2>/dev/null || true
  wait "$cli" 2>/dev/null || true
  wait "$writer" 2>/dev/null || true
  rm -f "$IN" "$CLI_PID" "$WRITER_PID"
  exit 0
}
trap _stop TERM INT HUP

sleep 0.4
pw-metadata 0 default.audio.sink '{"name":"atlas-android"}' >/dev/null 2>&1 || true
pw-metadata 0 default.audio.source '{"name":"atlas-mic"}' >/dev/null 2>&1 || true
if command -v pactl >/dev/null 2>&1; then
  pactl set-default-sink atlas-android >/dev/null 2>&1 || true
  pactl set-default-source atlas-mic >/dev/null 2>&1 || true
fi

while kill -0 "$cli" 2>/dev/null && kill -0 "$writer" 2>/dev/null; do
  sleep 5
done
kill "$cli" "$writer" 2>/dev/null || true
wait "$cli" 2>/dev/null || true
wait "$writer" 2>/dev/null || true
rm -f "$IN" "$CLI_PID" "$WRITER_PID"
EOF
    chmod 755 /usr/local/bin/atlas-audio-pw
}
install_desk_audio
# Battery, LTE, and the active network, read from Android.
# su is the enterd shim and will not start this. setpriv drops to atlas.
pkg=/usr/local/share/atlas/plasma/org.kde.plasma.atlasstatus
if [ -f "$pkg/metadata.json" ] && [ -f "$pkg/contents/ui/main.qml" ]; then
    for dest in /usr/share/plasma/plasmoids/org.kde.plasma.atlasstatus \
                /home/atlas/.local/share/plasma/plasmoids/org.kde.plasma.atlasstatus; do
        mkdir -p "$dest/contents/ui"
        cp -f "$pkg/metadata.json" "$dest/metadata.json"
        cp -f "$pkg/contents/ui/main.qml" "$dest/contents/ui/main.qml"
    done
    chown -R atlas:atlas /home/atlas/.local/share/plasma/plasmoids/org.kde.plasma.atlasstatus 2>/dev/null || true
fi
if [ -x /usr/local/bin/atlas-phone-status ]; then
    oldpid=
    [ -f /tmp/atlas-phone-status.pid ] && oldpid=$(cat /tmp/atlas-phone-status.pid)
    if [ -z "$oldpid" ] || ! kill -0 "$oldpid" 2>/dev/null; then
        mkdir -p /home/atlas/.cache
        chown atlas:atlas /home/atlas/.cache 2>/dev/null || true
        if [ -x /usr/bin/setpriv ]; then
            setpriv --reuid=10081 --regid=10081 --clear-groups --inh-caps=-all \
                /usr/local/bin/atlas-phone-status >/tmp/atlas-phone-status.log 2>&1 &
        else
            /usr/local/bin/atlas-phone-status >/tmp/atlas-phone-status.log 2>&1 &
        fi
        echo $! >/tmp/atlas-phone-status.pid
    fi
fi
# The panel tray only lists applets named in appletsrc. Volume is plasma-pa.
tray=/home/atlas/.config/plasma-org.kde.plasma.desktop-appletsrc
if [ -f "$tray" ] && [ -d /usr/share/plasma/plasmoids/org.kde.plasma.volume ] \
    && ! grep -q 'org.kde.plasma.volume' "$tray"; then
    sed -i \
        -e '/^extraItems=/s/$/,org.kde.plasma.volume/' \
        -e '/^knownItems=/s/$/,org.kde.plasma.volume/' \
        "$tray"
fi
cat > /tmp/atlas-desk-launch.sh <<EOF
#!/bin/sh
export HOME=/home/atlas
export USER=atlas
export LOGNAME=atlas
export XDG_CACHE_HOME=/home/atlas/.cache
export PATH="/home/atlas/.local/bin:/home/atlas/.grok/bin:$PATH"
export PULSE_SERVER="unix:$XDG_RUNTIME_DIR/pulse/native"
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export DISPLAY='$DISPLAY'
export XDG_RUNTIME_DIR='$XDG_RUNTIME_DIR'
export XDG_SESSION_TYPE=x11
export DESKTOP_SESSION=plasma
export QT_QPA_PLATFORM=xcb
export QT_QPA_PLATFORMTHEME=kde
export XDG_CURRENT_DESKTOP=KDE
# Flatpak user installs live here. Without it Discover cannot see the
# desktop file it just installed and retries the lookup.
export XDG_DATA_DIRS="/home/atlas/.local/share/flatpak/exports/share:/usr/local/share:/usr/share"
# Android su sets TMPDIR to /data/local/tmp, which atlas cannot use.
# Ostree then fails Flathub signatures with "GPG: Permission denied".
export TMPDIR=/tmp
export KDE_FULL_SESSION=true
export KDE_SESSION_VERSION=6
export QT_SCALE_FACTOR='$SCALE'
export KWIN_COMPOSE='$KWIN_COMPOSE'
$GPU_LINES
# PipeWire shares this dbus session. Plasma does not start it on its own.
if [ -x /usr/bin/dbus-run-session ] && [ -x /usr/bin/startplasma-x11 ]; then
    exec dbus-run-session -- /bin/sh -c '
        if [ -x /usr/bin/pipewire ] && [ ! -S "\$XDG_RUNTIME_DIR/pipewire-0" ]; then
            pipewire >/tmp/pipewire.log 2>&1 &
            i=0
            while [ "\$i" -lt 25 ]; do
                [ -S "\$XDG_RUNTIME_DIR/pipewire-0" ] && break
                sleep 0.2
                i=\$((i + 1))
            done
        fi
        if [ -x /usr/bin/wireplumber ]; then
            wireplumber >/tmp/wireplumber.log 2>&1 &
        fi
        if [ -x /usr/bin/pipewire-pulse ]; then
            pipewire-pulse >/tmp/pipewire-pulse.log 2>&1 &
        fi
        if [ -x /usr/local/bin/atlas-audio-pw ]; then
            /usr/local/bin/atlas-audio-pw >/tmp/atlas-audio-pw-holder.log 2>&1 &
        fi
        exec startplasma-x11
    '
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
phase "starting Plasma"
: >"$DIR/plasma.log"
chmod 644 "$DIR/plasma.log" 2>/dev/null || true
echo "plasma log $DIR/plasma.log"
if id atlas >/dev/null 2>&1 && [ -x /usr/bin/setpriv ]; then
    own_dir /home/atlas/.cache
    own_dir /home/atlas/.config
    own_dir /tmp/runtime-atlas
    chown atlas:atlas /tmp/atlas-desk-launch.sh 2>/dev/null || true
    export HOME=/home/atlas USER=atlas LOGNAME=atlas XDG_CACHE_HOME=/home/atlas/.cache
    setpriv --reuid=10081 --regid=10081 --clear-groups --inh-caps=-all \
        /bin/sh /tmp/atlas-desk-launch.sh >"$DIR/plasma.log" 2>&1 &
    PL=$!
else
    /tmp/atlas-desk-launch.sh >"$DIR/plasma.log" 2>&1 &
    PL=$!
fi
echo "$PL" >"$DIR/plasma.pid"
# Xwayland commits one black buffer, then window damage never reaches it.
# The cursor still moves, so Restart looks like a mouse on a black glass.
# One root expose makes it publish the desktop that is already on the X screen.
(
    i=0
    while [ "$i" -lt 160 ]; do
        pidof plasmashell >/dev/null 2>&1 && break
        phase "Plasma is loading"
        i=$((i + 1))
        sleep 0.25
    done
    if pidof plasmashell >/dev/null 2>&1; then
        phase "Plasma shell is up"
        j=0
        while [ "$j" -lt 4 ]; do
            phase "drawing the desktop"
            j=$((j + 1))
            sleep 0.25
        done
        python3 - << 'PY'
import ctypes
x = ctypes.CDLL("libX11.so.6")
x.XOpenDisplay.restype = ctypes.c_void_p
x.XRootWindow.restype = ctypes.c_ulong
dpy = x.XOpenDisplay(None)
if dpy:
    root = x.XRootWindow(dpy, 0)
    x.XClearArea(dpy, root, 0, 0, 0, 0, 1)
    x.XFlush(dpy)
PY
        phase "desktop is up"
    else
        phase "Plasma shell did not start"
    fi
    desk_later
) &
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
            write_session_env
            DBUS_SESSION_BUS_ADDRESS="$bus" dbus-send --dest=org.freedesktop.ScreenSaver \
                /ScreenSaver org.freedesktop.ScreenSaver.SimulateUserActivity \
                >/dev/null 2>&1 || true
        fi
        sleep 20
    done
) &
wait "$AX"
