# MonLauncher

[![Release](https://img.shields.io/github/v/release/monfbull/MonLauncher?include_prereleases&label=release)](https://github.com/monfbull/MonLauncher/releases)
[![Android 11+](https://img.shields.io/badge/Android%20TV-11%2B-3DDC84?logo=android&logoColor=white)](#compatibility)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

**A clean home screen for Android TV and Google TV: your apps, your wallpaper, and nothing else.**

The stock Google TV home screen is built around recommendations and sponsored rows. MonLauncher replaces it with a dock for your favorite apps, a grid for everything else, and a wallpaper you choose. No ads, no analytics.

Open source, written in Kotlin with Jetpack Compose.

Early beta.

<!-- Screenshots: home, app list, settings -->

## Features

- Dock and app grid
- Hide apps you don't use
- Custom wallpapers: pick one from Download, or send one from your phone or computer by scanning a QR code
- Icon pack support
- Navigation sounds, with on/off and volume settings
- Diagnostics: view crash reports on your phone and download them to send with a bug report
- Android settings at the top of MonLauncher's settings, since most remotes have no settings button

## Icon packs

Designed to work with the [Projectivy Icon Pack](https://github.com/SicMundus86/ProjectivyIconPack), a free pack by SicMundus86 made for TV. If it is installed and no pack is selected, MonLauncher uses it by default. Get it via Downloader code `9257057` or from GitHub.

The Projectivy Icon Pack is currently the only supported icon pack. Support for more icon packs is planned.

## Compatibility

Requires Android 11 or newer.

| Device | Status | Notes |
|---|---|---|
| Onn 4K Plus | Confirmed | Main test device |
| Google TV Streamer | Should work | Android 14 |
| Chromecast with Google TV (4K and HD) | Should work | Android 12 or 14, depending on updates |
| Onn 4K Pro | Should work | |
| NVIDIA Shield TV | Should work | Android 11. Tested on the Android 11 Android TV emulator, not yet on a real Shield |
| Other Android TV and Google TV devices, including TVs | Should work | Android 11 or newer |
| Fire TV (Fire OS) | Not supported | Installs on Fire OS 8, but Amazon blocks third-party home screens |
| Fire TV (Vega OS), such as Fire TV Stick 4K Select | Not supported | Not Android, can't run Android apps |
| Any device on Android 10 or earlier | Not supported | Won't install |
| Phones and tablets | Not supported | Designed for TV and remote only |

Support for Android 9 and 10 is planned.

Tried it on a device not listed? [Open an issue](https://github.com/monfbull/MonLauncher/issues) and say how it went.

## Install

The APK is on the [Releases](https://github.com/monfbull/MonLauncher/releases) page. This link always downloads the newest one:

```
https://github.com/monfbull/MonLauncher/releases/latest/download/MonLauncher.apk
```

Install it with one of these:

- [Downloader](https://play.google.com/store/apps/details?id=com.esaba.downloader) (on the TV): enter code `9324705`, or the link above. It downloads and installs from there.
- USB: download the APK on a computer, copy it to a drive, and open it on the TV with a file manager
- ADB: download the APK on a computer, then run `adb install MonLauncher.apk`

Open MonLauncher once before setting it as default.

### Set as default launcher

Google TV does not list third-party launchers in Settings > Default apps. Use one of these:

**1. Launcher Manager (no PC)**

Sideload Launcher Manager (1.0.4 for Android TV) via Downloader code `160445` or from the [XDA thread](https://forum.xda-developers.com/t/app-firetv-noroot-launcher-manager-change-launcher-without-root.4176349/post-83705245), open it, and select MonLauncher as the default launcher.

**2. ADB**

The stock home screen always takes the Home button while it's enabled, even if you set MonLauncher as the default. So with ADB, you turn the stock home screen off instead. Turning it off doesn't uninstall it, and you can turn it back on at any time (see [Switch back](#switch-back)).

1. Install MonLauncher and open it once.
2. Find your stock home screen:

   ```bash
   adb shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN
   ```

   The last line starts with its package name, for example `com.google.android.apps.tv.launcherx/.home.HomeActivity`.
3. Turn it off. On Google TV:

   ```bash
   adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
   adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
   ```

   The second package turns the stock home screen back on by itself if it's left enabled.

   On Android TV without Google TV, such as Shield:

   ```bash
   adb shell pm disable-user --user 0 com.google.android.tvlauncher
   ```

   If step 2 showed a different package, disable that one instead.
4. Press Home. MonLauncher opens. If the TV asks which home app to use, choose MonLauncher.

A system update can turn the stock home screen back on. If that happens, run step 3 again.

### Updating

Install the new APK over the old one: enter the Downloader code or link again, or run `adb install -r MonLauncher.apk`. Your dock, hidden apps, settings and wallpapers stay. There's no need to uninstall first or to set MonLauncher as default again.

If the TV asks which home app to use after updating, choose MonLauncher.

Releases are all signed with the same key, so they install over each other. A build you made yourself is signed with a different key and won't update in place.

### Switch back

Switch back before uninstalling MonLauncher. Uninstalling first leaves the TV without a home screen.

- Launcher Manager: open it and turn off the custom launcher. The stock home screen comes back.
- ADB: turn the stock home screen back on:

  ```bash
  # Google TV
  adb shell pm enable com.google.android.tungsten.setupwraith
  adb shell pm enable com.google.android.apps.tv.launcherx
  # Android TV without Google TV
  adb shell pm enable com.google.android.tvlauncher
  ```

Uninstalling deletes wallpapers you uploaded to MonLauncher. To keep one, select it in the wallpaper settings and choose **Save a copy to Download** first.

## Build from source

Requires JDK 17 or newer and the Android SDK (compileSdk 35).

```bash
git clone https://github.com/monfbull/MonLauncher.git
cd MonLauncher
```

Debug build:

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Run tests:

```bash
./gradlew :core:test
```

Release builds need a signing key configured in `keystore.properties`, which is not in the repo (see `app/build.gradle.kts`). Without it, `assembleRelease` stops with an error. Contributors only need debug builds.

## Report a bug

Open an [Issue](https://github.com/monfbull/MonLauncher/issues) with:

- Device and Android version
- MonLauncher version
- Steps to reproduce, expected vs. actual
- If it crashed: the report from Settings > About & diagnostics

## Project structure

- `app/` — Android app
- `core/` — pure Kotlin core, home state machine in `Home.kt`, no Android imports

## License

GPL-3.0 — see [LICENSE](LICENSE)
