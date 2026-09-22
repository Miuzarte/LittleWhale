#!/system/bin/sh
# Fake a real finger on the phone's own touchscreen, for testing the brake without a person
#
# Writing to an evdev node goes into the kernel's input core, so the events reach every reader of
# that node - LittleWhale's touch watch included - while an injected touch never appears there at
# all. That difference is exactly what the brake is built on, so this is the only way to exercise
# it from a machine
#
# The node and the raw ranges come from `lw_probe`: on 192.168.1.103 it is /dev/input/event7 ("fts")
# with x 0..10799 and y 0..23999, i.e. ten units per screen pixel. The coordinates below are raw
# device units, not screen pixels
#
# usage: su -c 'sh lw-fake-touch.sh down [rawX rawY]'   # a finger arrives and stays
#        su -c 'sh lw-fake-touch.sh up'                 # the finger leaves
#
# A finger left down is treated by the system as a real press: if the screen scrolls underneath it,
# letting go lands as a tap on whatever is there by then. Point it at something harmless
NODE=${TOUCH_NODE:-/dev/input/event7}
X=${2:-5400}
Y=${3:-9470}

case "$1" in
  down)
    sendevent $NODE 1 330 1        # EV_KEY BTN_TOUCH DOWN
    sendevent $NODE 3 47 0         # EV_ABS ABS_MT_SLOT 0
    sendevent $NODE 3 57 1         # EV_ABS ABS_MT_TRACKING_ID 1
    sendevent $NODE 3 53 $X        # EV_ABS ABS_MT_POSITION_X
    sendevent $NODE 3 54 $Y        # EV_ABS ABS_MT_POSITION_Y
    sendevent $NODE 0 0 0          # EV_SYN SYN_REPORT
    ;;
  move)
    sendevent $NODE 3 47 0
    sendevent $NODE 3 53 $X
    sendevent $NODE 3 54 $Y
    sendevent $NODE 0 0 0
    ;;
  up)
    sendevent $NODE 3 47 0
    sendevent $NODE 3 57 -1        # the slot is empty again
    sendevent $NODE 0 0 0
    sendevent $NODE 1 330 0
    sendevent $NODE 0 0 0
    ;;
  *)
    echo "usage: $0 down|move|up [rawX rawY]"
    ;;
esac
