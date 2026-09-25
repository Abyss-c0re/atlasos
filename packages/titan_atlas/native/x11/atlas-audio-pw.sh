#!/bin/sh
# PipeWire ends of the desk audio FIFOs. No socket, no port.
# Playback: atlas-android. Capture: atlas-mic.
export PATH=/usr/bin:/bin
export HOME=/home/atlas
export XDG_RUNTIME_DIR=/tmp/runtime-atlas
PLAY=/tmp/atlas-virgl/audio-play
CAP=/tmp/atlas-virgl/audio-cap
{
  echo "load-module libpipewire-module-pipe-tunnel { tunnel.mode = sink pipe.filename = ${PLAY} audio.format = S16 audio.rate = 48000 audio.channels = 2 node.name = atlas-android node.description = AtlasAndroid tunnel.may-pause = true }"
  echo "load-module libpipewire-module-pipe-tunnel { tunnel.mode = source pipe.filename = ${CAP} audio.format = S16 audio.rate = 48000 audio.channels = 2 node.name = atlas-mic node.description = AtlasMic tunnel.may-pause = true }"
  sleep 86400
} | pw-cli > /tmp/atlas-audio-pw.log 2>&1 &
holder=$!
sleep 0.4
pw-metadata 0 default.audio.sink '{"name":"atlas-android"}' >/dev/null 2>&1 || true
pw-metadata 0 default.audio.source '{"name":"atlas-mic"}' >/dev/null 2>&1 || true
wait "$holder"
