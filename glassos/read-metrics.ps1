# Read-only: polls the treadmill's GlassOS gRPC service for live speed and incline.
# Usage: .\glassos\read-metrics.ps1 -Ip <treadmill-ip> [-Seconds 60]
# Never calls SetSpeed/SetIncline.
param([string]$Ip = $(throw '-Ip <treadmill IP> is required'), [int]$Seconds = 60)

$here = $PSScriptRoot
$common = @('-insecure',
    '-cert', "$here\certs\client_cert.pem", '-key', "$here\certs\client_key.pem",
    '-H', 'client_id: com.ifit.eriador', '-import-path', "$here\proto")

$end = (Get-Date).AddSeconds($Seconds)
while ((Get-Date) -lt $end) {
    $speed = grpcurl @common -proto workout/SpeedService.proto -d '{}' "${Ip}:54321" com.ifit.glassos.SpeedService/GetSpeed | ConvertFrom-Json
    $incl = grpcurl @common -proto workout/InclineService.proto -d '{}' "${Ip}:54321" com.ifit.glassos.InclineService/GetIncline | ConvertFrom-Json
    $kph = [double]$speed.lastKph
    '{0:HH:mm:ss}  {1,5:N1} km/h  {2,5:N1} mph  incline {3,5:N1}%' -f (Get-Date), $kph, ($kph / 1.609344), [double]$incl.lastInclinePercent
    Start-Sleep -Seconds 1
}
