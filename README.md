# TV Home — a Fire TV launcher

A minimal home screen for the Fire TV Stick 4K (2nd gen, Fire OS 8 / Android 11), written in Kotlin with Jetpack Compose. It replaces Amazon's home screen with a photo wallpaper, a frosted-glass list of your streaming apps, and a large clock.

![TV Home on the Big Sur wallpaper](docs/screenshots/home-big-sur.jpg)

- Apps shown as white logos made from each app's own icon: Apple TV, Disney+, Hulu, Netflix, Peacock, Plex, Prime Video, YouTube
- D-pad to move, Select to open, Menu (☰) to move an app up or down (order is saved)
- Back does nothing on the home screen
- Top-right buttons: change wallpaper, open Fire TV Settings
- Five built-in CC0 photo wallpapers, plus your own photos from `Pictures/TVHome`

## Screenshots

Captured on the stick itself.

| Mirror lake wallpaper | Menu (☰) on an app |
|---|---|
| ![Home screen on the mirror lake wallpaper](docs/screenshots/home-lake.jpg) | ![Move up / Move down menu](docs/screenshots/move-menu.jpg) |

| Alpine | Sunset | Misty forest |
|---|---|---|
| ![Alpine wallpaper](docs/screenshots/wallpaper-alpine.jpg) | ![Sunset wallpaper](docs/screenshots/wallpaper-sunset.jpg) | ![Misty forest wallpaper](docs/screenshots/wallpaper-forest.jpg) |

## How it takes over Home on Fire OS 8

Fire OS ignores third-party HOME launchers, so:

- **`HomeButtonService`** (accessibility service) watches for Amazon's home screen (`HomeActivity_vNext`) and brings this launcher back on top within about half a second. Opening Fire TV Settings briefly passes through Amazon's home screen, so that detour is recognized and left alone.
- **`BootReceiver`** opens the launcher on startup and re-enables the accessibility service if Fire OS turned it off (it does this whenever the app is force-stopped). The launcher also re-enables it every time it opens.

## Build

Needs JDK 17 and the Android SDK (platform 35).

```bash
./gradlew assembleRelease
```

The release APK is signed with the debug key and lands in `app/build/outputs/apk/release/app-release.apk`.

## Install on the stick

Turn on **Settings → My Fire TV → Developer options → ADB debugging**, then from your computer (use your stick's IP from Settings → My Fire TV → About → Network):

```bash
adb connect <stick-ip>:5555
```

Allow the debugging prompt on the TV, then:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package compile -m speed -f com.tt.tvlauncher
adb shell pm grant com.tt.tvlauncher android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.tt.tvlauncher android.permission.READ_EXTERNAL_STORAGE
adb shell appops set com.tt.tvlauncher SYSTEM_ALERT_WINDOW allow
adb shell settings put secure enabled_accessibility_services com.tt.tvlauncher/com.tt.tvlauncher.HomeButtonService
adb shell settings put secure accessibility_enabled 1
adb shell am start -n com.tt.tvlauncher/.MainActivity
```

`WRITE_SECURE_SETTINGS` lets the launcher re-enable its own accessibility service; `compile -m speed` compiles it ahead of time for smooth scrolling.

## Your own wallpapers

```bash
adb shell mkdir -p /sdcard/Pictures/TVHome
adb push my-photo.jpg /sdcard/Pictures/TVHome/
```

Then press the wallpaper button. Your photos come first in the rotation.

## Getting to Amazon's home screen

Turn off the redirect until the launcher next opens (or the stick restarts):

```bash
adb shell settings put secure enabled_accessibility_services null
```

## Known limits

- Amazon's home screen may flash briefly after pressing Home; Fire OS always starts it first.
- The remote's mic button can't be blocked by an app: Fire OS's System UI and Alexa handle it before any app sees it.
- Fire TV's own search requires an Amazon-only permission, so there is no search bar.

## Wallpaper credits

All from Wikimedia Commons (originally Unsplash), released under CC0:
"Rocky coastline at Big Sur, CA", "Clouds mirrored in a mountain lake", "Alpine landscape", "Colorful sky during sunset", "Cloud over a forest".
