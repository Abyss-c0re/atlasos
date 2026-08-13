# Rear digitizer as touchScreen for independent rear content:
#   sub_mode=apps  → SecondaryLauncher / apps on display 2
#   sub_mode=cube  → Neural Cube OpenGL on display 2
# InputManager association (pad-agent 2.67) binds this device to rear uniqueId
# so touches do NOT duplicate onto the main OS display.
device.internal = 1
touch.deviceType = touchScreen
touch.orientationAware = 1
device.displayPort = 3
