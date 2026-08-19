#ifndef ATLAS_BRIDGE_CLASS_H
#define ATLAS_BRIDGE_CLASS_H
/*
 * Android ↔ Debian bridge class — one SoT for enterd, atlas-android, atlas-auth.
 *
 * Observe  — energy MUST flow. No ticket. First token only. No ; && | smuggle.
 * Capture  — glass, mutate, elevate. Live Atlas grant (ticket.exec). User in control.
 * Broker   — atlas-auth / atlas-sudo may start their own prompt without a prior ticket.
 */
#include <stddef.h>
#include <string.h>

static inline int atlas_bridge_name_in(const char *name, const char *const *list) {
  if (!name || !name[0]) return 0;
  for (int i = 0; list[i]; i++)
    if (strcmp(name, list[i]) == 0) return 1;
  return 0;
}

static inline int atlas_bridge_observe_name(const char *name) {
  static const char *obs[] = { "getprop", "dumpsys", "logcat", NULL };
  return atlas_bridge_name_in(name, obs);
}

static inline int atlas_bridge_capture_name(const char *name) {
  static const char *cap[] = {
      "screencap", "screenshot", "input", "am", "pm", "cmd",
      "settings", "setprop", "wm", "service", "content", "appops",
      "nsenter", "unshare", "reboot", "sm", "bm",
      "sudo", "su", "exec", "adb", "remoteadb", "remote_adb",
      NULL};
  return atlas_bridge_name_in(name, cap);
}

static inline int atlas_bridge_broker_name(const char *name) {
  static const char *bro[] = {
      "atlas-auth", "atlas-sudo", "atlas-auth-askpass", NULL };
  return atlas_bridge_name_in(name, bro);
}

/* First-token basename of a CMD line. Returns length, writes name. */
static inline int atlas_bridge_first_token(const char *cmd, char *name, size_t nmax) {
  if (!cmd || !name || nmax < 2) return 0;
  const char *s = cmd;
  while (*s == ' ' || *s == '\t') s++;
  if (strncmp(s, "CMD ", 4) == 0) s += 4;
  while (*s == ' ' || *s == '\t' || *s == '\'' || *s == '"') s++;
  const char *slash = s;
  const char *p = s;
  while (*p && *p != ' ' && *p != '\t' && *p != '\'' && *p != '"') {
    if (*p == '/') slash = p + 1;
    p++;
  }
  size_t n = (size_t)(p - slash);
  if (n == 0 || n >= nmax) return 0;
  memcpy(name, slash, n);
  name[n] = 0;
  return (*p == 0 && n > 0) ? 1 : 1;
}

static inline const char *atlas_bridge_after_token(const char *cmd) {
  const char *s = cmd ? cmd : "";
  while (*s == ' ' || *s == '\t') s++;
  if (strncmp(s, "CMD ", 4) == 0) s += 4;
  while (*s == ' ' || *s == '\t' || *s == '\'' || *s == '"') s++;
  while (*s && *s != ' ' && *s != '\t' && *s != '\'' && *s != '"') s++;
  return s;
}

static inline int atlas_bridge_smuggle(const char *cmd, const char *after) {
  if (after && strpbrk(after, ";&|`") != NULL) return 1;
  if (!cmd) return 0;
  if (strstr(cmd, "screencap") || strstr(cmd, "screenshot")
      || strstr(cmd, "nsenter") || strstr(cmd, "unshare"))
    return 1;
  return 0;
}

static inline int atlas_bridge_observe_cmd(const char *cmd) {
  char name[64];
  if (!atlas_bridge_first_token(cmd, name, sizeof name)) return 0;
  if (!atlas_bridge_observe_name(name)) return 0;
  if (atlas_bridge_smuggle(cmd, atlas_bridge_after_token(cmd))) return 0;
  return 1;
}

static inline int atlas_bridge_broker_cmd(const char *cmd) {
  char name[64];
  if (!atlas_bridge_first_token(cmd, name, sizeof name)) return 0;
  if (!atlas_bridge_broker_name(name)) return 0;
  if (atlas_bridge_smuggle(cmd, atlas_bridge_after_token(cmd))) return 0;
  return 1;
}

#endif
