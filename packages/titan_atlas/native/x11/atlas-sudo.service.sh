#!/system/bin/sh
# The flashed hybrid script still writes the enterd shim onto sudo.
# The Debian terminal runs /atlas-bin/sudo. The desk runs /usr/local/bin/sudo.
# Put the wrapper on both after hybrid boot, and once more in case ensure
# lands late.
install() {
    sh /data/local/atlas-linux/usr/local/libexec/atlas-sudo-install.sh
}
sleep 12
install
sleep 40
install
