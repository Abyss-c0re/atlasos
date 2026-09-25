#!/usr/bin/env python3
"""Read a 48 kHz stereo s16le fifo and hand it to atlas-audio-bridge.

Debian must not open ALSA. The bridge is the AAudio client.
"""
import os
import socket
import struct
import sys

SOCK = os.environ.get("ATLAS_AUDIO_SOCK", "/tmp/atlas-virgl/audio.sock")
FIFO = os.environ.get("ATLAS_AUDIO_FIFO", "/tmp/atlas-audio.fifo")
MAGIC = 0x41554442


def main():
    hello = struct.pack("<IIIIII", MAGIC, 1, 0, 48000, 2, 1)
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.connect(SOCK)
    sock.sendall(hello)
    rep = sock.recv(16)
    if len(rep) < 16:
        sys.stderr.write("short reply\n")
        return 1
    status, rate, ch, burst = struct.unpack("<iIII", rep)
    sys.stderr.write("bridge status=%s rate=%s ch=%s burst=%s\n" % (status, rate, ch, burst))
    sys.stderr.flush()
    if status != 0:
        return 1
    fd = os.open(FIFO, os.O_RDONLY)
    try:
        while True:
            buf = os.read(fd, 4096)
            if not buf:
                break
            sock.sendall(buf)
    finally:
        os.close(fd)
        sock.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
