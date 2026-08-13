/*
 * atlas-auth-askpass — SUDO_ASKPASS helper for any real sudo that expects a password.
 * On Atlas we prefer PATH sudo (native). This exists so grok/tools setting
 * SUDO_ASKPASS still trigger biometrics; we never print a usable password.
 * Exit 0 only after grant; sudo -A may still fail if it needs a real password —
 * that is OK because our PATH `sudo` binary does not need one.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/wait.h>

int main(int argc, char **argv) {
  (void)argc;
  const char *prompt = argv[1] ? argv[1] : "sudo";
  char reason[512];
  snprintf(reason, sizeof reason, "SUDO_ASKPASS %s", prompt);

  char *bin = getenv("ATLAS_BIN");
  char path[512];
  if (bin && bin[0])
    snprintf(path, sizeof path, "%s/atlas-auth", bin);
  else
    snprintf(path, sizeof path, "/data/user/0/com.titanus2.atlas/files/bin/atlas-auth");

  pid_t p = fork();
  if (p == 0) {
    execl(path, "atlas-auth", "request", reason, (char *)NULL);
    execlp("atlas-auth", "atlas-auth", "request", reason, (char *)NULL);
    _exit(4);
  }
  if (p < 0) return 1;
  int st = 0;
  waitpid(p, &st, 0);
  if (!WIFEXITED(st) || WEXITSTATUS(st) != 0) return 1;
  /* sudo -A reads a line; empty fails closed for real sudo (good).
   * Our native sudo does not use this path. */
  fputc('\n', stdout);
  return 0;
}
