# Rear digitizer as touchScreen for the sub display (display 2).
# InputReader on this GSI reads touch.displayId. It does not read
# device.displayPort. Without touch.displayId, an unbound DIRECT
# touchscreen falls back to the default internal viewport — the
# primary 1440 panel — and rear touches drive the main screen.
device.internal = 1
touch.deviceType = touchScreen
touch.orientationAware = 1
touch.displayId = local:4627039422300187651
