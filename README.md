# MonLauncher

[![Release](https://img.shields.io/github/v/release/monfbull/MonLauncher?include_prereleases&label=release)](https://github.com/monfbull/MonLauncher/releases)
[![Android 12+](https://img.shields.io/badge/Android%20TV-12%2B-3DDC84?logo=android&logoColor=white)](#compatibility)
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

## Icon packs

Designed to work with the [Projectivy Icon Pack](https://github.com/SicMundus86/ProjectivyIconPack), a free pack by SicMundus86 made for TV. If it is installed and no pack is selected, MonLauncher uses it by default. Get it via Downloader code `9257057` or from GitHub.

The Projectivy Icon Pack is currently the only supported icon pack. Support for more icon packs is planned.

## Compatibility

Requires Android 12 or newer.

| Device | Status | Notes |
|---|---|---|
| Onn 4K Plus | Confirmed | Main test device |
| Google TV Streamer | Should work | Android 14 |
| Chromecast with Google TV (4K and HD) | Should work | Android 12 or 14, depending on updates |
| Onn 4K Pro | Should work | |
| Other Android TV and Google TV devices, including TVs | Should work | Android 12 or newer |
| NVIDIA Shield TV | Not supported | Android 11 |
| Fire TV (Fire OS) | Not supported | Android 11 or earlier, and Amazon blocks third-party home screens |
| Fire TV (Vega OS), such as Fire TV Stick 4K Select | Not supported | Not Android, can't run Android apps |
| Any device on Android 11 or earlier | Not supported | Won't install |
| Phones and tablets | Not supported | Designed for TV and remote only |

Support for Android 11 and earlier is planned, which would include Shield TV and Fire TV on Fire OS. On Fire TV, MonLauncher would likely open as a regular app rather than replace the home screen.

Tried it on a device not listed? [Open an issue](https://github.com/monfbull/MonLauncher/issues) and say how it went.

## Install

The APK is on the [Releases](https://github.com/monfbull/MonLauncher/releases) page. Install it with one of these:

- [Downloader](https://play.google.com/store/apps/details?id=com.esaba.downloader) (on the TV): open the Releases page in Downloader and select the APK. It downloads and installs from there.
- USB: download the APK on a computer, copy it to a drive, and open it on the TV with a file manager
- ADB: download the APK on a computer, then run `adb install MonLauncher.apk`

Open MonLauncher once before setting it as default.

### Set as default launcher

Google TV does not list third-party launchers in Settings > Default apps. Use one of these:

**1. Button Mapper (no PC)**

1. Install [Button Mapper](https://play.google.com/store/apps/details?id=flar2.homebutton) from the Play Store on the TV
2. Settings > System > Accessibility > turn on Button Mapper
3. Open Button Mapper > Home Button > Customize > Single Tap > MonLauncher
4. Press Home to test

Free version is enough for this.

**2. Launcher Manager**

Sideload Launcher Manager (1.0.4 for Android TV) via Downloader code `160445` or from the [XDA thread](https://forum.xda-developers.com/t/app-firetv-noroot-launcher-manager-change-launcher-without-root.4176349/post-83705245), open it, and select MonLauncher as the default launcher.

**3. ADB**

```bash
adb shell cmd package set-home-activity mon.launcher/.MainActivity
```

Check the current default:

```bash
adb shell cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN
```

### Switch back

- Button Mapper: remove the Home button customization, or turn off Button Mapper in Accessibility
- Launcher Manager: select your previous launcher
- ADB: uninstall MonLauncher

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