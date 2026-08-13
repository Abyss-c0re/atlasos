/*
 * termgrid — pure C VT-ish cell buffer for Atlas (no WebView).
 * Enough CSI for TUI apps (cursor, erase, SGR colors, alt-screen ignore).
 */
#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#ifndef TERM_COLS
#define TERM_COLS 100
#endif
#ifndef TERM_ROWS
#define TERM_ROWS 40
#endif

typedef struct {
    uint32_t ch;   /* Unicode codepoint */
    uint32_t fg;   /* ARGB */
    uint32_t bg;
    uint8_t bold;
} Cell;

typedef struct {
    int cols, rows;
    int cx, cy;
    uint32_t fg, bg;
    uint8_t bold;
    int dirty;
    /* ESC parse */
    int esc; /* 0 normal 1 esc 2 csi 3 osc */
    char csi[64];
    int csin;
    Cell *cells; /* rows * cols */
    Cell *alt;   /* optional alt screen */
    int use_alt;
} Term;

static Term G;
static int inited;

static uint32_t ansi_fg(int n) {
    static const uint32_t c[16] = {
        0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00,
        0xFF0000EE, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
        0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00,
        0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF
    };
    if (n >= 0 && n < 16) return c[n];
    return 0xFFECEFF1;
}
static uint32_t ansi_bg(int n) {
    if (n == 0) return 0xFF0A0A0A;
    return ansi_fg(n);
}

static Cell *cell_at(int y, int x) {
    if (x < 0 || y < 0 || x >= G.cols || y >= G.rows) return NULL;
    return &G.cells[y * G.cols + x];
}

static void term_clear_region(int y0, int y1) {
    for (int y = y0; y < y1 && y < G.rows; y++) {
        for (int x = 0; x < G.cols; x++) {
            Cell *c = cell_at(y, x);
            if (!c) continue;
            c->ch = ' ';
            c->fg = G.fg;
            c->bg = G.bg;
            c->bold = 0;
        }
    }
    G.dirty = 1;
}

void term_init(int cols, int rows) {
    if (cols < 20) cols = 20;
    if (rows < 10) rows = 10;
    if (cols > 200) cols = 200;
    if (rows > 80) rows = 80;
    free(G.cells);
    free(G.alt);
    memset(&G, 0, sizeof G);
    G.cols = cols;
    G.rows = rows;
    G.fg = 0xFFECEFF1;
    G.bg = 0xFF0A0A0A;
    G.cells = calloc((size_t)cols * (size_t)rows, sizeof(Cell));
    term_clear_region(0, rows);
    G.cx = G.cy = 0;
    G.dirty = 1;
    inited = 1;
}

void term_resize(int cols, int rows) {
    term_init(cols, rows);
}

static void put_cp(uint32_t cp) {
    if (cp == '\r') {
        G.cx = 0;
        return;
    }
    if (cp == '\n') {
        G.cx = 0; /* CR implicit on LF — no mid-line spam */
        G.cy++;
        if (G.cy >= G.rows) {
            /* scroll up */
            memmove(G.cells, G.cells + G.cols,
                    (size_t)(G.rows - 1) * (size_t)G.cols * sizeof(Cell));
            for (int x = 0; x < G.cols; x++) {
                Cell *c = cell_at(G.rows - 1, x);
                if (!c) continue;
                c->ch = ' ';
                c->fg = G.fg;
                c->bg = G.bg;
                c->bold = 0;
            }
            G.cy = G.rows - 1;
        }
        G.dirty = 1;
        return;
    }
    if (cp == '\b') {
        if (G.cx > 0) G.cx--;
        return;
    }
    if (cp == '\t') {
        G.cx = (G.cx + 8) & ~7;
        if (G.cx >= G.cols) G.cx = G.cols - 1;
        return;
    }
    if (cp < 32 && cp != 0) return;
    /* wrap only when we have a real width */
    if (G.cols > 1 && G.cx >= G.cols) {
        G.cx = 0;
        put_cp('\n');
    }
    Cell *c = cell_at(G.cy, G.cx);
    if (c) {
        c->ch = cp ? cp : ' ';
        c->fg = G.fg;
        c->bg = G.bg;
        c->bold = G.bold;
    }
    G.cx++;
    G.dirty = 1;
}

static void apply_sgr(int *p, int n) {
    if (n == 0) {
        G.fg = 0xFFECEFF1;
        G.bg = 0xFF0A0A0A;
        G.bold = 0;
        return;
    }
    for (int i = 0; i < n; i++) {
        int v = p[i];
        if (v == 0) {
            G.fg = 0xFFECEFF1;
            G.bg = 0xFF0A0A0A;
            G.bold = 0;
        } else if (v == 1) G.bold = 1;
        else if (v == 22) G.bold = 0;
        else if (v >= 30 && v <= 37) G.fg = ansi_fg(v - 30 + (G.bold ? 8 : 0));
        else if (v >= 90 && v <= 97) G.fg = ansi_fg(v - 90 + 8);
        else if (v >= 40 && v <= 47) G.bg = ansi_bg(v - 40);
        else if (v >= 100 && v <= 107) G.bg = ansi_fg(v - 100 + 8);
        else if (v == 39) G.fg = 0xFFECEFF1;
        else if (v == 49) G.bg = 0xFF0A0A0A;
        else if (v == 38 && i + 2 < n && p[i + 1] == 5) {
            /* 256-color approx → gray scale */
            int c = p[i + 2];
            if (c < 16) G.fg = ansi_fg(c);
            else G.fg = 0xFFB0BEC5;
            i += 2;
        } else if (v == 48 && i + 2 < n && p[i + 1] == 5) {
            int c = p[i + 2];
            if (c < 16) G.bg = ansi_bg(c);
            else G.bg = 0xFF121212;
            i += 2;
        }
    }
}

static void exec_csi(char final) {
    int params[16];
    int np = 0;
    int val = 0, got = 0;
    for (int i = 0; i < G.csin; i++) {
        char ch = G.csi[i];
        if (ch >= '0' && ch <= '9') {
            val = val * 10 + (ch - '0');
            got = 1;
        } else if (ch == ';') {
            if (np < 16) params[np++] = got ? val : 0;
            val = 0;
            got = 0;
        }
    }
    if (np < 16) params[np++] = got ? val : 0;

    int a = np > 0 ? params[0] : 0;
    int b = np > 1 ? params[1] : 0;
    if (a < 0) a = 0;
    if (b < 0) b = 0;

    switch (final) {
    case 'A': /* CUU */
        G.cy -= (a == 0 ? 1 : a);
        if (G.cy < 0) G.cy = 0;
        break;
    case 'B':
        G.cy += (a == 0 ? 1 : a);
        if (G.cy >= G.rows) G.cy = G.rows - 1;
        break;
    case 'C':
        G.cx += (a == 0 ? 1 : a);
        if (G.cx >= G.cols) G.cx = G.cols - 1;
        break;
    case 'D':
        G.cx -= (a == 0 ? 1 : a);
        if (G.cx < 0) G.cx = 0;
        break;
    case 'H':
    case 'f': {
        int row = (a == 0 ? 1 : a) - 1;
        int col = (b == 0 ? 1 : b) - 1;
        if (row < 0) row = 0;
        if (col < 0) col = 0;
        if (row >= G.rows) row = G.rows - 1;
        if (col >= G.cols) col = G.cols - 1;
        G.cy = row;
        G.cx = col;
        break;
    }
    case 'J': /* ED */
        if (a == 0) {
            /* cursor to end */
            for (int x = G.cx; x < G.cols; x++) {
                Cell *c = cell_at(G.cy, x);
                if (c) { c->ch = ' '; c->fg = G.fg; c->bg = G.bg; }
            }
            term_clear_region(G.cy + 1, G.rows);
        } else if (a == 1) {
            term_clear_region(0, G.cy);
            for (int x = 0; x <= G.cx && x < G.cols; x++) {
                Cell *c = cell_at(G.cy, x);
                if (c) { c->ch = ' '; c->fg = G.fg; c->bg = G.bg; }
            }
        } else {
            term_clear_region(0, G.rows);
            G.cx = G.cy = 0;
        }
        G.dirty = 1;
        break;
    case 'K': /* EL */
        if (a == 0) {
            for (int x = G.cx; x < G.cols; x++) {
                Cell *c = cell_at(G.cy, x);
                if (c) { c->ch = ' '; c->fg = G.fg; c->bg = G.bg; }
            }
        } else if (a == 1) {
            for (int x = 0; x <= G.cx && x < G.cols; x++) {
                Cell *c = cell_at(G.cy, x);
                if (c) { c->ch = ' '; c->fg = G.fg; c->bg = G.bg; }
            }
        } else {
            for (int x = 0; x < G.cols; x++) {
                Cell *c = cell_at(G.cy, x);
                if (c) { c->ch = ' '; c->fg = G.fg; c->bg = G.bg; }
            }
        }
        G.dirty = 1;
        break;
    case 'm':
        apply_sgr(params, np);
        break;
    case 'n': /* DSR ignore */
    case 'h':
    case 'l':
    case 'r':
    default:
        break;
    }
}

/* UTF-8 decode one codepoint; returns bytes consumed */
static int utf8_cp(const uint8_t *s, int left, uint32_t *out) {
    if (left <= 0) return 0;
    uint8_t b0 = s[0];
    if (b0 < 0x80) {
        *out = b0;
        return 1;
    }
    if ((b0 & 0xE0) == 0xC0 && left >= 2) {
        *out = ((b0 & 0x1F) << 6) | (s[1] & 0x3F);
        return 2;
    }
    if ((b0 & 0xF0) == 0xE0 && left >= 3) {
        *out = ((b0 & 0x0F) << 12) | ((s[1] & 0x3F) << 6) | (s[2] & 0x3F);
        return 3;
    }
    if ((b0 & 0xF8) == 0xF0 && left >= 4) {
        *out = ((b0 & 0x07) << 18) | ((s[1] & 0x3F) << 12) | ((s[2] & 0x3F) << 6) | (s[3] & 0x3F);
        return 4;
    }
    *out = 0xFFFD;
    return 1;
}

void term_feed(const uint8_t *data, int len) {
    if (!inited) term_init(TERM_COLS, TERM_ROWS);
    int i = 0;
    while (i < len) {
        uint8_t b = data[i];
        if (G.esc == 0) {
            if (b == 0x1B) {
                G.esc = 1;
                i++;
                continue;
            }
            uint32_t cp;
            int n = utf8_cp(data + i, len - i, &cp);
            if (n <= 0) break;
            put_cp(cp);
            i += n;
            continue;
        }
        if (G.esc == 1) {
            if (b == '[') {
                G.esc = 2;
                G.csin = 0;
                i++;
                continue;
            }
            if (b == ']') {
                G.esc = 3;
                i++;
                continue;
            }
            /* short ESC sequences: ESC ( B etc — skip one */
            G.esc = 0;
            i++;
            continue;
        }
        if (G.esc == 2) {
            /* CSI */
            if ((b >= '0' && b <= '9') || b == ';' || b == '?' || b == '!' || b == '=' || b == '>') {
                if (G.csin < (int)sizeof(G.csi) - 1) G.csi[G.csin++] = (char)b;
                i++;
                continue;
            }
            G.csi[G.csin] = 0;
            exec_csi((char)b);
            G.esc = 0;
            G.csin = 0;
            i++;
            continue;
        }
        if (G.esc == 3) {
            /* OSC … BEL or ST */
            if (b == 0x07) {
                G.esc = 0;
            } else if (b == 0x1B) {
                /* wait \ */
            } else if (b == '\\') {
                G.esc = 0;
            }
            i++;
            continue;
        }
        i++;
    }
}

int term_cols(void) { return G.cols; }
int term_rows(void) { return G.rows; }
int term_dirty(void) { return G.dirty; }
void term_clear_dirty(void) { G.dirty = 0; }
int term_cx(void) { return G.cx; }
int term_cy(void) { return G.cy; }

/* Pack row into out: for each cell 12 bytes: u32 ch, u32 fg, u32 bg — or simplified */
int term_copy_row(int row, uint32_t *ch, uint32_t *fg, uint32_t *bg, int maxc) {
    if (!inited || row < 0 || row >= G.rows) return 0;
    int n = G.cols < maxc ? G.cols : maxc;
    for (int x = 0; x < n; x++) {
        Cell *c = cell_at(row, x);
        ch[x] = c ? c->ch : ' ';
        fg[x] = c ? c->fg : 0xFFECEFF1;
        bg[x] = c ? c->bg : 0xFF0A0A0A;
    }
    return n;
}

/* Simple snapshot for Java: UTF-16-ish dump not used — Java will call per-row */
void term_reset(void) {
    if (!inited) return;
    term_clear_region(0, G.rows);
    G.cx = G.cy = 0;
    G.esc = 0;
    G.fg = 0xFFECEFF1;
    G.bg = 0xFF0A0A0A;
    G.dirty = 1;
}
