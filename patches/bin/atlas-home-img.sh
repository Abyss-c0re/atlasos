#!/bin/sh
# Debian home is an ext4 image in the Atlas app's files directory.
# The system LP stays the OS. This image is the home partition:
# create, grow, recreate, export, and load.
set -u
PATH=/system/bin:/system/xbin:/vendor/bin:/sbin:/bin

MNT=/data/local/atlas-home
IMG_NAME=debian-home.img
DEFAULT_MIB=32768

app_files() {
    for d in /data/user/0/com.titanus2.atlas/files \
             /data/data/com.titanus2.atlas/files; do
        if [ -d "$d" ]; then
            echo "$d"
            return 0
        fi
    done
    return 1
}

img_path() {
    files=$(app_files) || return 1
    echo "$files/$IMG_NAME"
}

read_num() {
    f=$1
    [ -f "$f" ] || return 1
    n=$(tr -dc '0-9' <"$f" | head -c 8)
    [ -n "$n" ] || return 1
    echo "$n"
}

want_mib() {
    if [ -n "${ATLAS_HOME_IMG_MIB:-}" ]; then
        echo "$ATLAS_HOME_IMG_MIB"
        return
    fi
    files=$(app_files) || files=""
    for f in /data/local/tmp/titan2_home_img_mib \
             ${files:+$files/home-img-mib}; do
        if n=$(read_num "$f"); then
            echo "$n"
            return
        fi
    done
    echo "$DEFAULT_MIB"
}

clamp_mib() {
    n=$1
    [ "$n" -lt 1024 ] && n=1024
    [ "$n" -gt 262144 ] && n=262144
    echo "$n"
}

atlas_uid() {
    stat -c %u /data/user/0/com.titanus2.atlas 2>/dev/null \
        || stat -c %u /data/data/com.titanus2.atlas 2>/dev/null \
        || echo 0
}

is_mounted() {
    awk -v m="$1" '$2==m {found=1} END{exit !found}' /proc/mounts
}

# A bind is good only when it is the live home image and its source still exists.
# atlas-home.migrate was removed after the first image copy. Those binds stayed
# as "//deleted" and Deb bash then fails getcwd with ENOENT.
deb_view_ok() {
    mp=$1
    src=$2
    is_mounted "$mp" || return 1
    if awk -v m="$mp" '$5==m && index($4,"deleted"){bad=1} END{exit !bad}' \
        /proc/self/mountinfo 2>/dev/null; then
        return 1
    fi
    sd=$(stat -c %d "$src" 2>/dev/null) || return 1
    md=$(stat -c %d "$mp" 2>/dev/null) || return 1
    [ -n "$sd" ] && [ "$sd" = "$md" ]
}

rebind_one() {
    src=$1
    dst=$2
    [ -d "$src" ] || return 1
    if deb_view_ok "$dst" "$src"; then
        return 0
    fi
    if is_mounted "$dst"; then
        umount "$dst" 2>/dev/null || umount -l "$dst" 2>/dev/null || true
    fi
    mkdir -p "$dst" 2>/dev/null || true
    mount --bind "$src" "$dst" 2>/dev/null \
        || mount -o bind "$src" "$dst" 2>/dev/null
}

# Drop Deb binds before the directory they point at is renamed or deleted.
detach_deb_home_views() {
    for mp in \
        /data/local/atlas-linux/home/atlas \
        /data/local/atlas-hybrid/merge/home/atlas \
        /data/local/atlas-hybrid/lower/home/atlas \
        /data/local/atlas-linux/data/local/atlas-home \
        /data/local/atlas-hybrid/merge/data/local/atlas-home \
        /data/local/atlas-hybrid/lower/data/local/atlas-home
    do
        is_mounted "$mp" || continue
        umount "$mp" 2>/dev/null || umount -l "$mp" 2>/dev/null || true
    done
}

# Debian /home/atlas and /data/local/atlas-home follow the mounted image.
rebind_deb_views() {
    src_home="$MNT/atlas"
    [ -d "$MNT" ] && [ -d "$src_home" ] || return 1
    is_mounted "$MNT" || return 1
    for root in /data/local/atlas-hybrid/merge \
        /data/local/atlas-linux \
        /data/local/atlas-hybrid/lower; do
        [ -d "$root/etc" ] || continue
        mkdir -p "$root/home" "$root/data/local" 2>/dev/null || true
        rebind_one "$src_home" "$root/home/atlas" || true
        rebind_one "$MNT" "$root/data/local/atlas-home" || true
    done
    return 0
}

detach_loops() {
    img=$1
    losetup 2>/dev/null | while read -r line; do
        case "$line" in
            *"$img"*)
                dev=${line%%:*}
                losetup -d "$dev" 2>/dev/null || true
                ;;
        esac
    done
}

do_mount() {
    img=$1
    mkdir -p "$MNT"
    if ! is_mounted "$MNT"; then
        if ! mount -o loop "$img" "$MNT" 2>/dev/null; then
            loop=$(losetup -f 2>/dev/null) || return 1
            losetup "$loop" "$img" || return 1
            mount "$loop" "$MNT" || return 1
        fi
    fi
    rebind_deb_views || true
    return 0
}

do_umount() {
    detach_deb_home_views
    if is_mounted "$MNT"; then
        umount "$MNT" 2>/dev/null || umount -l "$MNT" 2>/dev/null || return 1
    fi
    img=$(img_path) || return 0
    detach_loops "$img"
    return 0
}

mkfs_img() {
    img=$1
    if command -v mkfs.ext4 >/dev/null 2>&1; then
        mkfs.ext4 -F -L atlas-home -m 0 "$img" >/dev/null
        return
    fi
    root=/data/local/atlas-linux
    [ -x "$root/usr/sbin/mkfs.ext4" ] || return 1
    parent=$(dirname "$img")
    mkdir -p "$root/mnt/atlas-app-files"
    mount --bind "$parent" "$root/mnt/atlas-app-files" || return 1
    chroot "$root" /usr/sbin/mkfs.ext4 -F -L atlas-home -m 0 \
        "/mnt/atlas-app-files/$(basename "$img")" >/dev/null
    rc=$?
    umount "$root/mnt/atlas-app-files" 2>/dev/null || true
    return "$rc"
}

resize_fs() {
    img=$1
    if command -v resize2fs >/dev/null 2>&1; then
        resize2fs "$img" >/dev/null
        return
    fi
    root=/data/local/atlas-linux
    [ -x "$root/usr/sbin/resize2fs" ] || return 1
    parent=$(dirname "$img")
    mkdir -p "$root/mnt/atlas-app-files"
    mount --bind "$parent" "$root/mnt/atlas-app-files" || return 1
    chroot "$root" /usr/sbin/resize2fs \
        "/mnt/atlas-app-files/$(basename "$img")" >/dev/null
    rc=$?
    umount "$root/mnt/atlas-app-files" 2>/dev/null || true
    return "$rc"
}

make_sparse() {
    img=$1
    mib=$2
    if command -v truncate >/dev/null 2>&1; then
        truncate -s "${mib}M" "$img"
        return
    fi
    dd if=/dev/zero of="$img" bs=1048576 count=0 seek="$mib" 2>/dev/null
}

seed_user() {
    uid=$(atlas_uid)
    mkdir -p "$MNT/atlas/reports" "$MNT/atlas/.local/bin"
    chmod 0755 "$MNT" "$MNT/atlas" "$MNT/atlas/reports" \
        "$MNT/atlas/.local" "$MNT/atlas/.local/bin" 2>/dev/null || true
    if [ -n "$uid" ] && [ "$uid" != 0 ]; then
        chown "$uid:$uid" "$MNT/atlas" "$MNT/atlas/reports" \
            "$MNT/atlas/.local" "$MNT/atlas/.local/bin" 2>/dev/null || true
    fi
}

is_ext4() {
    sig=$(od -An -tx1 -j 1080 -N 2 "$1" 2>/dev/null | tr -d ' \n')
    [ "$sig" = "53ef" ]
}

migrate_dir() {
    src=$1
    [ -d "$src" ] || return 0
    [ -n "$(ls -A "$src" 2>/dev/null)" ] || {
        rmdir "$src" 2>/dev/null || true
        return 0
    }
    cp -a "$src/." "$MNT/" || return 1
    # Binds of this tree must be gone before unlink, or Deb cwd stays "(deleted)".
    detach_deb_home_views
    rm -rf "$src"
    rebind_deb_views || true
}

cmd_ensure() {
    img=$(img_path) || {
        echo "atlas app storage is not available"
        return 1
    }
    mib=$(clamp_mib "$(want_mib)")
    if [ ! -f "$img" ]; then
        old=""
        if ! is_mounted "$MNT" && [ -d "$MNT" ] \
            && [ -n "$(ls -A "$MNT" 2>/dev/null)" ]; then
            old="${MNT}.migrate"
            detach_deb_home_views
            mv "$MNT" "$old" || return 1
        fi
        if ! make_sparse "$img" "$mib"; then
            if [ -n "$old" ] && [ ! -d "$MNT" ]; then
                mv "$old" "$MNT" 2>/dev/null || true
            fi
            return 1
        fi
        if ! mkfs_img "$img" || ! do_mount "$img"; then
            if [ -n "$old" ] && [ ! -d "$MNT" ]; then
                mv "$old" "$MNT" 2>/dev/null || true
            fi
            echo "home image failed"
            return 1
        fi
        if [ -n "$old" ]; then
            migrate_dir "$old" || {
                echo "home copy failed; left at $old"
                return 1
            }
        fi
        seed_user
        echo "home image ${mib}M $img"
        return 0
    fi
    do_mount "$img" || return 1
    if [ -d "${MNT}.migrate" ]; then
        migrate_dir "${MNT}.migrate" || true
    fi
    seed_user
    echo "home mounted $img"
}

cmd_grow() {
    img=$(img_path) || return 1
    mib=$(clamp_mib "${1:-$(want_mib)}")
    if [ ! -f "$img" ]; then
        ATLAS_HOME_IMG_MIB=$mib cmd_ensure
        return
    fi
    cur=$(stat -c %s "$img" 2>/dev/null || echo 0)
    want=$((mib * 1048576))
    if [ "$cur" -ge "$want" ]; then
        echo "home image already ${cur} bytes"
        do_mount "$img"
        return 0
    fi
    do_umount || {
        echo "home is in use"
        return 1
    }
    make_sparse "$img" "$mib" || return 1
    resize_fs "$img" || return 1
    do_mount "$img" || return 1
    seed_user
    echo "home image ${mib}M"
}

cmd_recreate() {
    img=$(img_path) || return 1
    mib=$(clamp_mib "${1:-$(want_mib)}")
    do_umount || {
        echo "home is in use"
        return 1
    }
    rm -f "$img"
    make_sparse "$img" "$mib" || return 1
    mkfs_img "$img" || return 1
    do_mount "$img" || return 1
    seed_user
    echo "home recreated ${mib}M"
}

cmd_export() {
    img=$(img_path) || return 1
    dest=${1:-/data/media/0/Atlas/debian-home.img}
    [ -f "$img" ] || {
        echo "no home image"
        return 1
    }
    do_umount || {
        echo "home is in use"
        return 1
    }
    mkdir -p "$(dirname "$dest")"
    cp -f "$img" "$dest" || {
        do_mount "$img" || true
        echo "export failed"
        return 1
    }
    do_mount "$img" || true
    echo "exported $dest"
}

cmd_load() {
    src=$1
    img=$(img_path) || return 1
    [ -f "$src" ] || {
        echo "no file $src"
        return 1
    }
    if ! is_ext4 "$src"; then
        echo "not an ext4 image"
        return 1
    fi
    do_umount || {
        echo "home is in use"
        return 1
    }
    cp -f "$src" "$img" || return 1
    do_mount "$img" || return 1
    seed_user
    echo "loaded $src"
}

sdcard_flag() {
    files=$(app_files) || files=""
    for f in ${files:+$files/sdcard-rw} /data/local/tmp/titan2_sdcard_rw; do
        if [ -f "$f" ]; then
            read_num "$f" && return
        fi
    done
    echo 0
}

grant_media_group() {
    gid=$1
    [ -n "$gid" ] && [ "$gid" != 0 ] || return 0
    root=/data/local/atlas-linux
    gf="$root/etc/group"
    [ -f "$gf" ] || return 0
    if grep -q ":$gid:" "$gf"; then
        grep -q ":$gid:.*\<atlas\>" "$gf" && return 0
        sed -i "s/\\(:$gid:[^:]*\\)\$/\\1,atlas/; s/:$gid:,atlas/:$gid:atlas/" "$gf"
    else
        echo "media_rw:x:$gid:atlas" >>"$gf"
    fi
}

cmd_sdcard() {
    rw=$(sdcard_flag)
    src=/storage/emulated/0
    if [ "$rw" = 1 ] && [ -d /data/media/0 ]; then
        src=/data/media/0
    fi
    [ -d "$src" ] || return 0
    for root in /data/local/atlas-linux /data/local/atlas-hybrid/merge; do
        [ -d "$root" ] || continue
        mkdir -p "$root/sdcard"
        if is_mounted "$root/sdcard"; then
            umount "$root/sdcard" 2>/dev/null || umount -l "$root/sdcard" 2>/dev/null || true
        fi
        mount --bind "$src" "$root/sdcard" 2>/dev/null \
            || mount -o bind "$src" "$root/sdcard" 2>/dev/null || true
    done
    if [ "$rw" = 1 ]; then
        gid=$(stat -c %g /data/media/0 2>/dev/null || true)
        grant_media_group "$gid"
    fi
    echo "sdcard rw=$rw src=$src"
}

cmd_status() {
    img=$(img_path) || img="?"
    mib=$(want_mib)
    mounted=no
    is_mounted "$MNT" && mounted=yes
    bytes=0
    [ -f "$img" ] && bytes=$(stat -c %s "$img" 2>/dev/null || echo 0)
    echo "home_img=$img bytes=$bytes want_mib=$mib mounted=$mounted sdcard_rw=$(sdcard_flag)"
}

usage() {
    echo "usage: atlas-home-img.sh ensure|rebind|grow [MiB]|recreate [MiB]|export [dest]|load <img>|sdcard|status"
    exit 2
}

cmd=${1:-status}
case "$cmd" in
    ensure) cmd_ensure ;;
    rebind) rebind_deb_views ;;
    grow) cmd_grow "${2:-}" ;;
    recreate) cmd_recreate "${2:-}" ;;
    export) cmd_export "${2:-}" ;;
    load)
        [ -n "${2:-}" ] || usage
        cmd_load "$2"
        ;;
    sdcard) cmd_sdcard ;;
    status) cmd_status ;;
    *) usage ;;
esac
