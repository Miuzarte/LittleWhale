# One call into the app's privileged channel, in the exact shape a tool sends it
#
# usage: pwsh -File tools/lw-bridge.ps1 -Method screen
#        pwsh -File tools/lw-bridge.ps1 -Method tap -Params '{"displayId":12,"x":540,"y":1200}'
#
# This is the fastest way to exercise a channel method: it skips the model and the plugin entirely,
# so a failure here is the app's, and a failure in a device turn with this passing is the plugin's
param(
    [Parameter(Mandatory = $true)][string]$Method,
    [string]$Params = '{}',
    [int]$LocalPort = 29999
)

$ErrorActionPreference = 'Stop'
$adb = 'B:\Software\AndroidSDK\platform-tools\adb.exe'
$device = Join-Path (Split-Path -Parent $PSCommandPath) 'lw-device'

# The endpoint and the token exist only in the host's environment, and the app's data directory is
# only readable as root, so the helper script is what reads them
& $adb push (Join-Path $device 'host-env.sh') /sdcard/DSH/lw-host-env.sh | Out-Null
$raw = & $adb shell "su -c 'sh /sdcard/DSH/lw-host-env.sh'"
$endpoint = ($raw | Select-String -Pattern '^LW_CHANNEL_ENDPOINT=(.+)$').Matches.Groups[1].Value
$token = ($raw | Select-String -Pattern '^LW_CHANNEL_TOKEN=(.+)$').Matches.Groups[1].Value
if (-not $endpoint -or -not $token) { throw "the host is not running, or it has no channel: $raw" }
$remotePort = [int]($endpoint -split ':')[-1]

& $adb forward "tcp:$LocalPort" "tcp:$remotePort" | Out-Null

$body = @{ method = $Method; token = $token } + ($Params | ConvertFrom-Json -AsHashtable)
$line = ($body | ConvertTo-Json -Compress -Depth 8) + "`n"

$client = [System.Net.Sockets.TcpClient]::new('127.0.0.1', $LocalPort)
try {
    $stream = $client.GetStream()
    $writer = [System.IO.StreamWriter]::new($stream, [System.Text.UTF8Encoding]::new($false))
    $writer.NewLine = "`n"
    $writer.AutoFlush = $true
    $writer.Write($line)
    $reader = [System.IO.StreamReader]::new($stream, [System.Text.UTF8Encoding]::new($false))
    $answer = $reader.ReadLine()
} finally {
    $client.Close()
    & $adb forward --remove "tcp:$LocalPort" | Out-Null
}

"request:  $line".Trim()
"answer:   $answer"
