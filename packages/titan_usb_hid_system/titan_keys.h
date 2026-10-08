#ifndef TITAN_KEYS_H
#define TITAN_KEYS_H

#include <stdint.h>

/* One map for the Titan keyboard.
 * USB hidg, Bluetooth, the neckband, and Moonlight all use these bytes.
 * A specials glyph already includes the Shift it needs. Physical Shift
 * is not OR'd on top: Shift+Sym+C stays "8", not "*".
 */

int titan_glyph_hid(unsigned cp, uint8_t *mod, uint8_t *usage);
int titan_specials_linux(unsigned code, uint8_t *mod, uint8_t *usage);
int titan_specials_android(unsigned keycode, uint8_t *mod, uint8_t *usage, char *glyph);
uint8_t titan_specials_report_mods(uint8_t held, uint8_t glyph_mod);

#define TITAN_KEYS_NAME "titan2_keys"

#endif
