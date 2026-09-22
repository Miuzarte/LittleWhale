#!/system/bin/sh
# The running host's environment, which is where the privileged channel's endpoint and token live
#
# Pushed to the device by the PowerShell scripts next to it; the app's data directory is only
# readable as root, so this is run through `su`
pid=$(ps -A -o PID,ARGS | grep 'bin.js web' | grep -v grep | awk '{print $1}' | head -1)
if [ -z "$pid" ]; then
  echo "no host process" >&2
  exit 1
fi
tr '\0' '\n' < "/proc/$pid/environ"
