/* atlas_present.h — explicit present between the display server and viewers.
 *
 * One AF_UNIX SEQPACKET socket. No TCP. The hot path passes a memfd or a
 * dma-buf with SCM_RIGHTS. Viewers pull the latest frame per output; frames
 * that were never pulled are dropped.
 *
 *   atlas-x (Debian)  listen  $ATLAS_X_DIR/present.sock
 *   Android viewer    connect /data/local/atlas-home/atlas/atlas-x/present.sock
 *
 * Those two paths are the same inode when /home/atlas is the bind of
 * /data/local/atlas-home/atlas.
 *
 * Outputs: 0 phone panel, 1 HDMI, 2 DisplayPort.
 * Pixel fourcc is drm_fourcc (ARGB8888 memory is B,G,R,A).
 */
#ifndef ATLAS_PRESENT_H
#define ATLAS_PRESENT_H

#include <stdint.h>

#define ATLAS_PRESENT_VER 1u

#define ATLAS_PRESENT_MAGIC     0x52505641u /* 'AVPR' frame */
#define ATLAS_PRESENT_MAGIC_REQ 0x51505641u /* 'AVPQ' pull */

#define ATLAS_OUT_PANEL 0u
#define ATLAS_OUT_HDMI  1u
#define ATLAS_OUT_DP    2u
#define ATLAS_OUT_COUNT 3u

#define ATLAS_PRESENT_MEMFD    1u
#define ATLAS_PRESENT_DMABUF   2u
#define ATLAS_PRESENT_Y_INVERT 4u
#define ATLAS_PRESENT_NONE     8u

#define ATLAS_FOURCC(a, b, c, d) \
    ((uint32_t)(unsigned char)(a) | ((uint32_t)(unsigned char)(b) << 8) | \
     ((uint32_t)(unsigned char)(c) << 16) | ((uint32_t)(unsigned char)(d) << 24))
#define ATLAS_FMT_ARGB8888 ATLAS_FOURCC('A', 'R', '2', '4')
#define ATLAS_FMT_XRGB8888 ATLAS_FOURCC('X', 'R', '2', '4')

/* Android path of the same directory Debian sees as /home/atlas/atlas-x. */
#define ATLAS_X_DIR_DEBIAN  "/home/atlas/atlas-x"
#define ATLAS_X_DIR_ANDROID "/data/local/atlas-home/atlas/atlas-x"

struct atlas_present_req {
    uint32_t magic;
    uint16_t ver;
    uint16_t output;
    uint32_t min_gen; /* 0 = latest now, or none if this output has no frame */
} __attribute__((packed));

struct atlas_present_msg {
    uint32_t magic;
    uint16_t ver;
    uint16_t flags;
    uint32_t output;
    uint32_t width;
    uint32_t height;
    uint32_t fourcc;
    uint32_t stride;
    uint32_t offset;
    uint32_t modifier_lo;
    uint32_t modifier_hi;
    uint32_t gen;
    uint64_t pts_us;
} __attribute__((packed));

#endif /* ATLAS_PRESENT_H */
