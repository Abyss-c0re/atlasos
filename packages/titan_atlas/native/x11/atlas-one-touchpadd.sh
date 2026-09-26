#!/system/bin/sh
# Controls can exec the system touchpadd and create a second virtual mouse.
# The desk only grabs titan2-orient-mouse from the one root driver.
while true; do
    for p in $(pidof titan2-touchpadd 2>/dev/null); do
        u=$(stat -c %u /proc/"$p" 2>/dev/null || echo 0)
        if [ "$u" != 0 ]; then
            kill "$p" 2>/dev/null || true
        fi
    done
    sleep 4
done
