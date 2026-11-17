# Tablet app

Shows the Pi's page full screen in landscape and draws the LED bar natively (much lighter on old hardware). It turns the display off without music and on when music starts, keeps itself in front, reloads a stuck page and starts after a reboot. Android 5+, no dependencies.

The layout and buttons come from `raspberry/index.html` on the Pi, so changing them needs no app update.

## Build

- **GitHub Actions** (easiest): every change under `tablet-app/` builds the APK. Download **NowPlaying-APK** from the run's artifacts, or start the workflow by hand under *Actions*.
- **Locally:** `./gradlew assembleRelease` (JDK 17, Android SDK 34), then `adb install -r app/build/outputs/apk/release/app-release.apk`.

## Install

1. Install the APK (allow unknown sources).
2. Enter the server address, e.g. `http://192.168.1.10:8080`.
3. Long-press the screen → **Enable display control**. Without it the display is only dimmed.
4. Set the screen lock to **None** and exclude the app from battery optimization.

Keep the tablet on the charger. Optionally block its internet access in the router; it only needs the Pi.

## Usage

- **Long press:** settings (address, display control)
- **Back button:** ignored
- Logs: `adb logcat -s NowPlaying`

App texts are German, or English on an English-language device.

## Updates

Raise `versionCode` in `app/build.gradle` and install over the old version. The signing key is in the repository, so no uninstall is needed. That also means the key protects nothing, see [Security](../README.md#security).
