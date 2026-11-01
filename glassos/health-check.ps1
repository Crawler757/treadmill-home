# Read-only: checks that the treadmill's motor service (GlassOS) is alive and answering.
# Usage: .\glassos\health-check.ps1 -Ip <treadmill-ip>
param([string]$Ip = $(throw '-Ip <treadmill IP> is required'))

$here = $PSScriptRoot
$dev = "${Ip}:5555"
$common = @('-insecure', '-max-time', '5',
    '-cert', "$here\certs\client_cert.pem", '-key', "$here\certs\client_key.pem",
    '-H', 'client_id: com.ifit.eriador', '-import-path', "$here\proto")
$ok = $true

foreach ($p in 'com.ifit.glassos_service') {
    $procId = "$(adb -s $dev shell pidof $p)".Trim()
    if ($procId) { "  process $p running (pid $procId)" } else { "  FAIL process $p not running"; $ok = $false }
}
# eru is reported but not required (the eru-removal experiment runs without it).
$eruPid = "$(adb -s $dev shell pidof com.ifit.eru)".Trim()
"  eru: " + $(if ($eruPid) { "running (pid $eruPid)" } else { "not running" })
foreach ($call in @(
        @('workout/SpeedService.proto', 'com.ifit.glassos.SpeedService/CanRead'),
        @('workout/SpeedService.proto', 'com.ifit.glassos.SpeedService/GetSpeed'),
        @('workout/InclineService.proto', 'com.ifit.glassos.InclineService/CanRead'),
        @('workout/InclineService.proto', 'com.ifit.glassos.InclineService/GetIncline'))) {
    $out = grpcurl @common -proto $call[0] -d '{}' "${Ip}:54321" $call[1] 2>&1 | Out-String
    if ($LASTEXITCODE -eq 0) { "  ok   $($call[1]) $($out -replace '\s+', ' ')" } else { "  FAIL $($call[1]) $($out.Trim())"; $ok = $false }
}
$crashes = adb -s $dev logcat -d -b crash 2>$null | Select-String 'ifit' | Select-Object -Last 3
if ($crashes) { '  recent crash log lines:'; $crashes | ForEach-Object { "    $_" } }
if ($ok) { 'HEALTHY' } else { 'UNHEALTHY' }
