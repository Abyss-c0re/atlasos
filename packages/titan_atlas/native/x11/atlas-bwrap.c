/* Setuid launcher for atlas-bwrap.py.
 * sudo closes the file descriptors Flatpak passes with --args and
 * --ro-bind-fd. This process keeps them and stays root just long enough
 * for the helper to build the mount namespace. The real uid stays the caller.
 */
#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>

int main(int argc, char **argv) {
    int i;
    char **args;
    unsetenv("PYTHONPATH");
    unsetenv("PYTHONHOME");
    unsetenv("PYTHONSTARTUP");
    unsetenv("PYTHONINSPECT");
    unsetenv("LD_PRELOAD");
    unsetenv("LD_LIBRARY_PATH");
    unsetenv("LD_AUDIT");
    args = calloc((size_t)argc + 3, sizeof(char *));
    if (!args) return 127;
    args[0] = "python3";
    args[1] = "-I";
    args[2] = "/usr/local/libexec/atlas-bwrap.py";
    for (i = 1; i < argc; i++) args[i + 2] = argv[i];
    execv("/usr/bin/python3", args);
    perror("atlas-bwrap");
    return 127;
}
