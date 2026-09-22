# Let a model call the tools on the device, in the app's own host tree and home
#
# usage: pwsh -File tools/lw-device-turn.ps1 -Task "Call lw_screen and report what it lists."
#        pwsh -File tools/lw-device-turn.ps1 -Task "..." -SessionDirectory /data/user/0/<pkg>/files/DSH
#
# The turn runs under `run-as`, so it is the app's uid with the app's own dsh home: the phone's
# credentials and settings are the ones the model answers with, and the channel it calls is the one
# the app is serving. Two limits come with that, both explained in tools/lw-device/headless.sh: the
# session works in the sandbox rather than in shared storage, and its file tools cannot read even
# there, so this is for calls that act on the screen. Reading a screenshot back is verified in the
# app's own host, where the files are ordinary workspace paths
param(
    [Parameter(Mandatory = $true)][string]$Task,
    [string]$SessionDirectory,
    [string]$Package = 'io.github.miuzarte.littlewhale'
)

$ErrorActionPreference = 'Stop'
$adb = 'B:\Software\AndroidSDK\platform-tools\adb.exe'
$device = Join-Path (Split-Path -Parent $PSCommandPath) 'lw-device'
$sandbox = "/data/user/0/$Package/files"
if (-not $SessionDirectory) { $SessionDirectory = "$sandbox/DSH" }
& $adb shell "su -c 'mkdir -p $sandbox/DSH && chown u0_a326:u0_a326 $sandbox/DSH'"

& $adb push (Join-Path $device 'host-env.sh') /sdcard/DSH/lw-host-env.sh | Out-Null
$raw = & $adb shell "su -c 'sh /sdcard/DSH/lw-host-env.sh'"
$endpoint = ($raw | Select-String -Pattern '^LW_CHANNEL_ENDPOINT=(.+)$').Matches.Groups[1].Value
$token = ($raw | Select-String -Pattern '^LW_CHANNEL_TOKEN=(.+)$').Matches.Groups[1].Value
if (-not $endpoint -or -not $token) { throw "the host is not running, or it has no channel: $raw" }
Write-Host "channel $endpoint"

# The turn has to run from inside the app's data directory, which only root can write into
& $adb push (Join-Path $device 'headless.sh') /data/local/tmp/lw-headless.sh | Out-Null
& $adb shell "su -c 'cp /data/local/tmp/lw-headless.sh $sandbox/lw-headless.sh && chown u0_a326:u0_a326 $sandbox/lw-headless.sh && chmod 700 $sandbox/lw-headless.sh && rm -f /data/local/tmp/lw-headless.sh'"

& $adb shell "run-as $Package /system/bin/sh $sandbox/lw-headless.sh `"$Task`" $endpoint $token $SessionDirectory"
