/* KWin GLX binds window pixmaps with glXBindTexImageEXT. Virpipe advertises
 * that path and then calls a null drawable hook (dri_set_tex_buffer2).
 * Upload the X pixmap with glTexImage2D instead. The composite stays on
 * the Mali GPU; only the pixel copy crosses the virgl socket. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>

typedef struct _XDisplay Display;
typedef unsigned long XID;
typedef XID Window;
typedef XID Pixmap;
typedef XID Drawable;
typedef struct _XImage XImage;
struct _XImage {
    int width, height;
    int xoffset;
    int format;
    char *data;
    int byte_order;
    int bitmap_unit;
    int bitmap_bit_order;
    int bitmap_pad;
    int depth;
    int bytes_per_line;
    int bits_per_pixel;
    unsigned long red_mask;
    unsigned long green_mask;
    unsigned long blue_mask;
};
typedef void *GLXFBConfig;
typedef XID GLXPixmap;
typedef XID GLXDrawable;

XImage *XGetImage(Display *, Drawable, int, int, unsigned, unsigned, unsigned long, int);
int XDestroyImage(XImage *);
int XGetGeometry(Display *, Drawable, Window *, int *, int *, unsigned *, unsigned *, unsigned *, unsigned *);

/* Resolved at bind time. Linking libGL here loads Mesa before KWin has a
 * context and the GL dispatch table comes up empty. */
static void (*p_glGetIntegerv)(unsigned, int *);
static void (*p_glTexImage2D)(unsigned, int, int, int, int, int, unsigned, unsigned, const void *);
static void (*p_glPixelStorei)(unsigned, int);

static int gl_ready(void)
{
    if (p_glTexImage2D)
        return 1;
    p_glGetIntegerv = dlsym(RTLD_DEFAULT, "glGetIntegerv");
    p_glTexImage2D = dlsym(RTLD_DEFAULT, "glTexImage2D");
    p_glPixelStorei = dlsym(RTLD_DEFAULT, "glPixelStorei");
    return p_glGetIntegerv && p_glTexImage2D && p_glPixelStorei;
}

#define GL_TEXTURE_2D 0x0DE1
#define GL_RGBA 0x1908
#define GL_BGRA 0x80E1
#define GL_UNSIGNED_BYTE 0x1401
#define GL_UNPACK_ALIGNMENT 0x0CF5
#define GL_UNPACK_ROW_LENGTH 0x0CF2
#define GL_TEXTURE_BINDING_2D 0x8069
#define GL_TEXTURE_RECTANGLE 0x84F5
#define GL_TEXTURE_BINDING_RECTANGLE 0x84F6

struct pix {
    GLXPixmap glx;
    Pixmap x;
    struct pix *next;
};
static struct pix *pix_head;

static void remember(GLXPixmap glx, Pixmap x)
{
    struct pix *p;
    for (p = pix_head; p; p = p->next) {
        if (p->glx == glx) {
            p->x = x;
            return;
        }
    }
    p = malloc(sizeof(*p));
    if (!p)
        return;
    p->glx = glx;
    p->x = x;
    p->next = pix_head;
    pix_head = p;
}

static Pixmap lookup(GLXPixmap glx)
{
    struct pix *p;
    for (p = pix_head; p; p = p->next) {
        if (p->glx == glx)
            return p->x;
    }
    return 0;
}

static void forget(GLXPixmap glx)
{
    struct pix **pp = &pix_head;
    while (*pp) {
        if ((*pp)->glx == glx) {
            struct pix *dead = *pp;
            *pp = dead->next;
            free(dead);
            return;
        }
        pp = &(*pp)->next;
    }
}

/* libepoxy exports these names as 8-byte function pointers. KWin loads the
 * pointer and calls it. Defining them as functions makes that load jump
 * into the first instruction. */
static GLXPixmap tfp_create(Display *dpy, GLXFBConfig config, Pixmap pixmap, const int *attrib_list)
{
    static GLXPixmap (*real)(Display *, GLXFBConfig, Pixmap, const int *);
    if (!real)
        real = dlsym(RTLD_DEFAULT, "glXCreatePixmap");
    GLXPixmap glx = real ? real(dpy, config, pixmap, attrib_list) : 0;
    if (glx)
        remember(glx, pixmap);
    return glx;
}

static void tfp_destroy(Display *dpy, GLXPixmap pixmap)
{
    static void (*real)(Display *, GLXPixmap);
    if (!real)
        real = dlsym(RTLD_DEFAULT, "glXDestroyPixmap");
    forget(pixmap);
    if (real)
        real(dpy, pixmap);
}

static void tfp_bind(Display *dpy, GLXDrawable drawable, int buffer, const int *attrib_list)
{
    Window root;
    int x, y;
    unsigned w, h, bw, depth;
    XImage *im;
    int align = 4, row = 0, bound2d = 0, boundrect = 0;
    unsigned target, format, pixel_bytes;
    Pixmap pixmap;
    (void)buffer;
    (void)attrib_list;
    pixmap = lookup((GLXPixmap)drawable);
    if (!pixmap)
        return;
    if (!XGetGeometry(dpy, pixmap, &root, &x, &y, &w, &h, &bw, &depth) || !w || !h)
        return;
    im = XGetImage(dpy, pixmap, 0, 0, w, h, ~0UL, 2 /* ZPixmap */);
    if (!im || !im->data) {
        if (im)
            XDestroyImage(im);
        return;
    }
    pixel_bytes = im->bits_per_pixel >= 8 ? (unsigned)im->bits_per_pixel / 8 : 4;
    /* Virpipe's desktop GL does not unpack GL_BGRA. Swap into RGBA. */
    format = GL_RGBA;
    {
        unsigned char *src = (unsigned char *)im->data;
        unsigned char *dst = malloc((size_t)w * h * 4);
        unsigned rowi, coli;
        if (!dst) {
            XDestroyImage(im);
            return;
        }
        for (rowi = 0; rowi < h; rowi++) {
            unsigned char *s = src + (size_t)rowi * im->bytes_per_line;
            unsigned char *d = dst + (size_t)rowi * w * 4;
            if (im->red_mask == 0xff) {
                memcpy(d, s, (size_t)w * 4);
            } else {
                for (coli = 0; coli < w; coli++) {
                    d[0] = s[2];
                    d[1] = s[1];
                    d[2] = s[0];
                    d[3] = pixel_bytes >= 4 ? s[3] : 255;
                    s += pixel_bytes;
                    d += 4;
                }
            }
        }
        if (!gl_ready()) {
            free(dst);
            XDestroyImage(im);
            return;
        }
        p_glGetIntegerv(GL_UNPACK_ALIGNMENT, &align);
        p_glGetIntegerv(GL_UNPACK_ROW_LENGTH, &row);
        p_glGetIntegerv(GL_TEXTURE_BINDING_2D, &bound2d);
        p_glGetIntegerv(GL_TEXTURE_BINDING_RECTANGLE, &boundrect);
        target = bound2d ? GL_TEXTURE_2D : GL_TEXTURE_RECTANGLE;
        p_glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        p_glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        p_glTexImage2D(target, 0, GL_RGBA, (int)w, (int)h, 0, format, GL_UNSIGNED_BYTE, dst);
        p_glPixelStorei(GL_UNPACK_ALIGNMENT, align);
        p_glPixelStorei(GL_UNPACK_ROW_LENGTH, row);
        free(dst);
    }
    XDestroyImage(im);
}

static void tfp_release(Display *dpy, GLXDrawable drawable, int buffer)
{
    (void)dpy;
    (void)drawable;
    (void)buffer;
}

GLXPixmap (*epoxy_glXCreatePixmap)(Display *, GLXFBConfig, Pixmap, const int *) = tfp_create;
void (*epoxy_glXDestroyPixmap)(Display *, GLXPixmap) = tfp_destroy;
void (*epoxy_glXBindTexImageEXT)(Display *, GLXDrawable, int, const int *) = tfp_bind;
void (*epoxy_glXReleaseTexImageEXT)(Display *, GLXDrawable, int) = tfp_release;
