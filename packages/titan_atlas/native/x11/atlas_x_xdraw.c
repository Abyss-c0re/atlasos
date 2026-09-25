/* Paint the X root red through Xwayland. Proves the X11 wire reaches present. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <X11/Xlib.h>

int main(int argc, char **argv) {
    const char *name = argc > 1 ? argv[1] : NULL;
    Display *d = XOpenDisplay(name);
    if (!d) {
        fprintf(stderr, "atlas-x-xdraw: cannot open %s\n", name ? name : "DISPLAY");
        return 1;
    }
    int s = DefaultScreen(d);
    Window root = RootWindow(d, s);
    Colormap cm = DefaultColormap(d, s);
    XColor c;
    memset(&c, 0, sizeof(c));
    c.red = 65535;
    c.green = 0;
    c.blue = 0;
    c.flags = DoRed | DoGreen | DoBlue;
    if (!XAllocColor(d, cm, &c)) {
        fprintf(stderr, "atlas-x-xdraw: alloc color failed\n");
        return 1;
    }
    XSetWindowBackground(d, root, c.pixel);
    XClearWindow(d, root);
    XFlush(d);
    XSync(d, False);
    XCloseDisplay(d);
    return 0;
}
