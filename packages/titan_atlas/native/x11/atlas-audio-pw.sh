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
