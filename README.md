# Treadmill Home

A replacement home screen and live stats overlay for NordicTrack treadmills running iFit's GlassOS. It lets you stream Netflix and Crunchyroll on the treadmill's built-in screen without an iFit login, ads, or membership nags, while iFit's own motor service keeps driving the belt.

![Crunchyroll with the overlay](screenshots/crunchyroll-overlay.png)

## Safety first

This is a running machine. Read this before anything else.

- **Keep the safety key on.** The physical safety key and the console's Stop button are the real way to stop. The overlay is never trusted with that.
- The app never talks to the motor board directly. It goes through iFit's own motor service (GlassOS), the same way the open source [QZ (qdomyos-zwift)](https://github.com/cagnulein/qdomyos-zwift) app does.
- Test anything that moves the belt with nobody on it, and stay close enough to pull the safety key or the plug.
- Speed and incline buttons only work during a running workout, step 0.5 mph / 1%, and are capped at 11.5 mph and -3% to 12% before anything is sent.
- **Use at your own risk.** This can void your warranty, and a factory reset or an iFit update can undo all of it.

## Network security

- **iFit's motor service is open to your whole Wi-Fi.** GlassOS listens on port 54321 on every network interface, and the client certificate it accepts is public (QZ ships it). Anyone on the same network who has it can start the belt and change speed and incline. That's how iFit built it, not something this app adds, but keep it in mind.
- **Keep the treadmill on a network only you control.** No guest Wi-Fi, shared apartment or office networks, or networks where you don't trust every device.
- **With eru removed, ADB stays on after restarts** (port 5555). A new computer still has to be approved with an on-screen prompt at the console. Never approve an ADB prompt you didn't start yourself.
- The app's status page (port 8765) is read-only and accepts no commands, but anyone on your network can read it, including your Private DNS hostname if you set one.
- To turn ADB off when you're done: Android Settings > Developer options > USB debugging off.

## What it does

- **Home screen:** big tiles for your streaming apps (no iFit apps), plus Settings and a Maintenance tile.
- **Top bar over every app:** speed, incline, workout time, distance, and a ⌂ Home button (the console has no reliable Back or Home keys).
- **On-screen controls** (tap ±): Start, Pause/Resume, Stop, and speed and incline -/+. The console's physical buttons keep working too.
- **Quarter-mile track panel:** a dot going around the lap, laps done, pace per mile, and moving time.
- **Housekeeping:** pushes iFit's "No internet connection" screen away, keeps the screen right way up, dims the screen after 10 minutes idle, and shows an orange unlock bar if the treadmill locks itself.
- **Status page** at `http://<treadmill-ip>:8765/` (read-only plain text: memory, motor service, lock state, which iFit apps are on).

![Controls expanded](screenshots/console-controls-expanded.png)

## Tested on

NordicTrack Commercial 2250, GlassOS 2026.06.26 ("Gen 6", CLASSIC), Android 9 (system version EKA2_20221110), MediaTek tablet with 2 GB RAM, 1920x1080. Other GlassOS consoles will probably work, but nothing else has been tested. Older iFit/ERU consoles (pre-GlassOS) won't work.

## Known limits

- Widevine is L3 on this console, so Netflix plays in SD.
- The tablet is slow. Netflix takes about 20 seconds to start with iFit's background apps removed, close to a minute with them running.
- There's no Google Play, so streaming apps must be sideloaded builds that run without Google services (for example the Amazon Fire tablet build of Crunchyroll).
- GlassOS resumes a paused workout at 1.0 mph and 0% incline, not where you paused.

## Setup

You need a Windows PC on the same Wi-Fi as the treadmill.

### 1. Tools on the PC

- Android SDK with `platforms;android-28` and `build-tools;35.0.0` (installing Android Studio is the easy way), and a JDK 11 or newer.
- `adb` (Android platform-tools).
- Optional: [grpcurl](https://github.com/fullstorydev/grpcurl) for the scripts in `glassos/`.

### 2. Turn on ADB on the console

1. In iFit, open settings > About and enter **privileged mode** (tap the About screen 10 times, wait 7 seconds, tap 10 more times). Recent builds ask for a customer-care response code to a challenge; existing NordicTrack console guides cover this.
2. In Android settings: About tablet > tap Build number 7 times, then Developer options > USB debugging on.
3. On the PC: `adb connect <treadmill-ip>:5555` and accept the prompt on the console.

Give the treadmill a fixed IP on your router so it keeps the same address.

### 3. GlassOS client certificates

The app talks to GlassOS's gRPC server on port 54321 using the client certificate QZ uses. This repo doesn't ship it. Copy `client_cert.pem` and `client_key.pem` from QZ's `nordictrack-build-grpc` branch into `glassos/certs/`. For the `glassos/` scripts, also put QZ's GlassOS `.proto` files in `glassos/proto/`.

### 4. Build and install

```powershell
.\app\build.ps1
adb install -r -t app\build\treadmill-home.apk
adb shell cmd package set-home-activity com.treadmill.home/.HomeActivity
adb shell appops set com.treadmill.home SYSTEM_ALERT_WINDOW allow
adb shell appops set com.treadmill.home WRITE_SETTINGS allow
adb shell appops set com.treadmill.home GET_USAGE_STATS allow
adb shell am start -n com.treadmill.home/.HomeActivity
```

`build.ps1` uses aapt2, javac, d8, and apksigner directly (no Gradle) and makes a local debug key on the first run. The app is marked `testOnly`, so installs need `-t`.

Install your streaming apps with `adb install`. Don't install while someone is watching something: the install kicks them back to the home screen.

### 5. Optional: remove iFit's lockdown app (recommended)

`com.ifit.eru` is iFit's updater and lockdown app. After every restart it turns ADB off, re-enables iFit apps you disabled, locks the treadmill, and uses a lot of CPU. Removing it for user 0 is reversible, and the motor service keeps working without it.

```powershell
.\app\eru-experiment.ps1 -Ip <treadmill-ip> -Stage check
.\app\eru-experiment.ps1 -Ip <treadmill-ip> -Stage remove    # watches the motor service for 2 min, puts eru back if it goes down
.\app\eru-experiment.ps1 -Ip <treadmill-ip> -Stage restore   # undo
```

`remove` refuses unless eru is a system app (so `pm install-existing` can always bring it back). It also makes the app device owner as a safety net: if the motor service hasn't answered 4 minutes after a boot, the app re-enables eru by itself. Test Start and Stop with the physical buttons before and after a restart.

With eru gone, you can also free about 500 MB of RAM:

```powershell
adb shell pm disable-user --user 0 com.ifit.gandalf
adb shell pm disable-user --user 0 com.ifit.arda
```

Undo with `adb shell pm enable <package>`.

### 6. Optional: block iFit's servers

`network/set-dns.ps1` points the console's Private DNS at a filtering DNS service (NextDNS or AdGuard DNS) where you've blocked the domains in `network/denylist.txt`. Without eru, nothing installs iFit updates anyway, so this matters less.

## Lessons learned (don't repeat these)

- **Never disable `com.ifit.rivendell`.** It killed the motor service.
- **Don't hide iFit apps with device-owner `setApplicationHidden`.** eru treated them as uninstalled and, on the next boot, disabled the motor service with system authority (shell can't re-enable it). Recovery meant un-hiding them as device owner, dropping device owner, and rebooting.
- **Don't deny rivendell's overlay permission** to hide its bottom bar. It crashes ("Workout Player has stopped").
- **Restore with care:** `pm unhide` needs device owner, so un-hide before removing the device-owner role.
- The console's correct orientation is 180°. The app locks rotation because auto-rotate sometimes flips the screen upside down.
- `SetSpeed` and `SetIncline` only work while a workout is RUNNING.
- In PowerShell, don't name a helper `Rd`, `Del`, `Rm`, or `Erase`. Built-in aliases win over functions, and `Rd` is Remove-Item.

## Undo everything

```powershell
adb shell pm install-existing --user 0 com.ifit.eru
adb shell dpm remove-active-admin com.treadmill.home/.AdminReceiver
adb shell pm enable com.ifit.gandalf
adb shell pm enable com.ifit.arda
adb uninstall com.treadmill.home
```

Then restart the treadmill. iFit makes itself the home screen again.

## Credits

Built with Claude Code. GlassOS access follows the work of the [QZ (qdomyos-zwift)](https://github.com/cagnulein/qdomyos-zwift) project; none of this happens without it.

Not affiliated with or endorsed by NordicTrack, iFit, or ICON Health & Fitness.

## License

MIT, see [LICENSE](LICENSE).
