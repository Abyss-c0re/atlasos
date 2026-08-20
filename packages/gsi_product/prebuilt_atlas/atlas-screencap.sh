#!/system/bin/sh
# Same wrap as `screencap` / `android screencap`. Exit 64 was a dam.
exec /system/bin/atlas-android screencap "$@"
