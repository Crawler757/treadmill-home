# Points the treadmill's DNS at a filtering DNS service (Android 9 "Private DNS"), or turns it off.
# Usage:
#   .\network\set-dns.ps1 -Ip <treadmill-ip> -Hostname abc123.dns.nextdns.io   # NextDNS: <config id>.dns.nextdns.io
#   .\network\set-dns.ps1 -Ip <treadmill-ip> -Hostname abc123.d.adguard-dns.com # AdGuard DNS: <device id>.d.adguard-dns.com
#   .\network\set-dns.ps1 -Ip <treadmill-ip> -Off                             # back to the router's DNS
#   .\network\set-dns.ps1 -Ip <treadmill-ip>                            # just check what's blocked now
param([string]$Hostname, [switch]$Off, [string]$Ip = $(throw '-Ip <treadmill IP> is required'))

$dev = "${Ip}:5555"
adb connect $dev | Out-Null

if ($Off) {
    adb -s $dev shell settings put global private_dns_mode off
    "Private DNS off: treadmill uses the router's DNS again."
} elseif ($Hostname) {
    adb -s $dev shell settings put global private_dns_specifier $Hostname
    adb -s $dev shell settings put global private_dns_mode hostname
    "Private DNS set to $Hostname"
    Start-Sleep -Seconds 5
}

"mode=$((adb -s $dev shell settings get global private_dns_mode).Trim())  host=$((adb -s $dev shell settings get global private_dns_specifier).Trim())"

# Resolve a few names on the treadmill itself. Blocked names fail or come back as 0.0.0.0 / 127.0.0.1
# (NextDNS answers 127.0.0.1 for denylisted names on this console).
foreach ($name in 'api.ifit.com', 'img.ifit.com', 'api2.amplitude.com', 'www.netflix.com', 'www.crunchyroll.com', 'www.google.com') {
    $out = (adb -s $dev shell "ping -c 1 -W 2 $name 2>&1 | head -1") -join ' '
    $state = if ($out -match 'unknown host|bad address|\((0\.0\.0\.0|127\.0\.0\.1)\)') { 'BLOCKED' } else { 'resolves' }
    '{0,-22} {1}' -f $name, $state
}
