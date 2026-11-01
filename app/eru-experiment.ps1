# Experiment: run the treadmill without iFit's core app (com.ifit.eru), keeping glassos_service
# (the motor driver) and rivendell. Run while ADB is connected and someone is at the treadmill.
#   .\app\eru-experiment.ps1 -Stage check    # read-only: how eru is installed, what depends on it
#   .\app\eru-experiment.ps1 -Stage remove   # device owner (safety net) + remove eru for user 0, 2-min motor watch
#   .\app\eru-experiment.ps1 -Stage restore  # put eru back, drop device owner
# Removal only proceeds if eru is a system app, so `pm install-existing` can always bring it back.
param([ValidateSet('check', 'remove', 'restore')][string]$Stage = 'check', [string]$Ip = $(throw '-Ip <treadmill IP> is required'))
$dev = "${Ip}:5555"
$eru = 'com.ifit.eru'

function Status { ((Invoke-WebRequest -UseBasicParsing -TimeoutSec 10 "http://${Ip}:8765/").Content) }
function IsSystemApp {
    # toybox grep on the console doesn't understand \s, so match the bracket instead.
    $flags = (adb -s $dev shell "dumpsys package $eru | grep -m1 'flags=\['") -join ''
    return $flags -match '\bSYSTEM\b'
}

switch ($Stage) {
    'check' {
        "--- eru install facts:"
        adb -s $dev shell "dumpsys package $eru | grep -E -m8 'codePath|flags=|sharedUser|userId=|installerPackageName|versionName'"
        "--- system image copy (if any):"
        adb -s $dev shell "ls -l /system/priv-app /system/app 2>/dev/null | grep -i -E 'eru|ifit'"
        "--- who binds to the motor service (eru should not be the only one):"
        adb -s $dev shell "dumpsys activity services com.ifit.glassos_service | grep -E 'binding=AppBindRecord' | sort -u"
        "--- does glassos_service declare a dependency on eru?"
        adb -s $dev shell "dumpsys package com.ifit.glassos_service | grep -i -E 'eru|sharedUser|uses-library'"
        "system app (removable + restorable): $(IsSystemApp)"
    }
    'remove' {
        if (-not (IsSystemApp)) { throw "eru is not a system app; removing it may be permanent. Stopping." }
        $front = adb -s $dev shell "dumpsys activity activities | grep -m1 mResumedActivity" 2>$null
        if ("$front" -match 'netflix|crunchyroll') { throw 'Someone is watching; try again from the home screen.' }
        adb -s $dev install -r -t "$PSScriptRoot\build\treadmill-home.apk"
        adb -s $dev shell dpm set-device-owner com.treadmill.home/.AdminReceiver
        adb -s $dev shell "am force-stop com.treadmill.home; am start -n com.treadmill.home/.HomeActivity >/dev/null"
        Start-Sleep -Seconds 5

        "removing $eru for user 0 at $(Get-Date -Format HH:mm:ss)"
        adb -s $dev shell pm uninstall -k --user 0 $eru
        $bad = 0
        for ($i = 0; $i -lt 12; $i++) {
            Start-Sleep -Seconds 10
            $h = & "$PSScriptRoot\..\glassos\health-check.ps1" -Ip $Ip 2>$null | Select-Object -Last 1
            "$(Get-Date -Format HH:mm:ss) motor service: $h"
            if ("$h" -match 'UNHEALTHY') { $bad++ } else { $bad = 0 }
            if ($bad -ge 3) {
                "motor service down 30 s: restoring eru"
                adb -s $dev shell pm install-existing --user 0 $eru
                break
            }
        }
        Status
    }
    'restore' {
        adb -s $dev shell pm install-existing --user 0 $eru
        adb -s $dev shell dpm remove-active-admin com.treadmill.home/.AdminReceiver
        Start-Sleep -Seconds 5
        Status
    }
}
