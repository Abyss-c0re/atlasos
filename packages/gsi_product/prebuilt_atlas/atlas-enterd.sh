#!/system/bin/sh
# Init-facing launcher for atlas-enterd ELF (this GSI registers shell services reliably).
# Product path: always /system/bin/atlas-enterd — no tip override after wipe ship.
export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
BIN=/system/bin/atlas-enterd
[ -x "$BIN" ] || BIN=/system/bin/atlas-enterd.bin
exec "$BIN" "$@"
