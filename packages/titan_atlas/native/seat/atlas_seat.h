/* atlas_seat.h — free-energy seat protocol (local AF_UNIX only).
 *
 * Unix model: small bins speak one binary frame format.
 *   atlas-seat-push  →  atlas-seatd  →  atlas-seat-pull / Atlas app
 *   Atlas app        →  atlas-seatd  →  atlas-seat-input (uinput later)
 *
 * No TCP. No RTP. No cloud. Drop-old video for latency (latest wins).
 */
#ifndef ATLAS_SEAT_H
#define ATLAS_SEAT_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ATLAS_SEAT_MAGIC_VID  0x46445641u /* 'AVDF' LE */
#define ATLAS_SEAT_MAGIC_INP  0x4e495641u /* 'AVIN' LE */
#define ATLAS_SEAT_MAGIC_CTL  0x4c544341u /* 'ACTL' LE */

#define ATLAS_SEAT_VER        1u

/* Video formats — decode on Android (MediaCodec path / raw). */
#define ATLAS_FMT_RGBA8888    1u
#define ATLAS_FMT_H264        2u  /* annex-B NALs, Moonlight-class payload */
#define ATLAS_FMT_I420        3u
#define ATLAS_FMT_NV12        4u

/* Message types (hdr.type) */
#define ATLAS_T_FRAME         1u
#define ATLAS_T_KEY           2u
#define ATLAS_T_PTR           3u  /* relative mouse / pad */
#define ATLAS_T_BTN           4u
#define ATLAS_T_HELLO         5u
#define ATLAS_T_BYE           6u
#define ATLAS_T_PING          7u

/* hello.role */
#define ATLAS_ROLE_PRODUCER   1u  /* video source (Sunshine-class encode / ffmpeg) */
#define ATLAS_ROLE_VIEWER     2u  /* decoder (Moonlight MediaCodec / raw) */
#define ATLAS_ROLE_INPUT      3u  /* key/ptr sink (uinput inject) */
#define ATLAS_ROLE_APP        4u  /* Atlas UI: viewer + input source */

/* Default paths — plane under tmp (world-reachable for app + root). */
#define ATLAS_SEAT_DIR        "/data/local/tmp/atlas-seat"
#define ATLAS_SEAT_SOCK       ATLAS_SEAT_DIR "/hub.sock"

/* Fixed header, little-endian on wire, packed. */
struct atlas_seat_hdr {
    uint32_t magic;
    uint16_t ver;
    uint16_t type;
    uint32_t w;       /* video: width; key: linux keycode; ptr: unused */
    uint32_t h;       /* video: height; key: 0/1 down; ptr: unused */
    uint32_t fmt;     /* video fmt; key: mods; ptr: buttons bitmask */
    uint32_t nbytes;  /* payload bytes after header (0 if none) */
    uint64_t pts_us;  /* monotonic us; key/ptr: same */
} __attribute__((packed));

/* Optional payload after HELLO: role u32 + name[16] */
struct atlas_seat_hello {
    uint32_t role;
    char     name[16];
} __attribute__((packed));

/* Pointer payload when type=PTR and nbytes>=sizeof */
struct atlas_seat_ptr {
    int32_t dx;
    int32_t dy;
    int32_t wheel;
    uint32_t buttons; /* bit0 L bit1 R bit2 M */
} __attribute__((packed));

#define ATLAS_SEAT_HDR_SIZE  ((unsigned)sizeof(struct atlas_seat_hdr))
#define ATLAS_SEAT_MAX_FRAME (8u * 1024u * 1024u) /* hard cap */
#define ATLAS_SEAT_MAX_CLIENTS 8

#ifdef __cplusplus
}
#endif
#endif /* ATLAS_SEAT_H */
