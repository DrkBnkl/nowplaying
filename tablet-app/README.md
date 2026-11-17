# Tablet app "Now Playing" for the Lenovo Yoga

The app shows the page of the Now Playing server on the Raspberry Pi full screen, locked to landscape, and draws the LED bar itself (which takes a lot of load off the old tablet). It turns the display off as soon as no music is playing and back on when music starts. It keeps itself in the foreground, reloads the page when it stops responding, and starts by itself after the tablet reboots.

The look and the control buttons belong to the page on the Pi (`raspberry/index.html`). Changing them does not require rebuilding the app: the page reloads itself on the tablet as soon as a new version is on the Pi.

The app runs on Android 5 and later, so the Yoga is covered in any case. You can find the Android version under *Settings → About tablet*.

The app's own texts (settings dialog, messages) are in German by default and in English when the Android device language is English. The language of the display page itself is set on the Pi with `UI_LANG` (see [`raspberry/README.md`](../raspberry/README.md#settings)).

## 1. Build the APK

There are three ways. All of them produce the same file, `app-release.apk`.

### Option A: GitHub builds the APK (no software of your own needed)

The repository contains the workflow `.github/workflows/tablet-apk.yml`. On every change under `tablet-app/`, GitHub builds the APK automatically (about 3–5 minutes); to run it by hand: **Actions** tab → select the tablet APK workflow → *Run workflow*.
Open the finished run; at the bottom under *Artifacts* you will find **NowPlaying-APK**. Download and unzip it; it contains `app-release.apk`.

### Option B: Android Studio on a PC

Open the `tablet-app` folder in Android Studio and choose *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.

### Option C: Command line on a Mac (this is how the installed version was built)

Prerequisites: `brew install openjdk@17` and `brew install --cask android-commandlinetools android-platform-tools`, then `sdkmanager "platforms;android-34" "build-tools;34.0.0"`. `local.properties` contains `sdk.dir=/opt/homebrew/share/android-commandlinetools`.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleRelease
```

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

For this, USB debugging is enabled on the Yoga (developer options).

## 2. Install on the tablet

1. Copy the APK to the tablet, via USB, email or cloud storage.
2. Tap it and allow "installation from unknown sources".
3. Start the app and enter the Raspberry Pi's address: `http://<Pi>:8080` (`<Pi>` = IP address or hostname of the Raspberry Pi on your home network).
4. Long-press the screen, then tap **"Enable display control"** ("Display-Steuerung aktivieren" on a German device) and confirm. Only then does the display really turn off when no music is playing. Without this permission it is merely dimmed to the lowest brightness.
5. In the Android settings under *Security → Screen lock*, choose **"None"**. Otherwise the lock screen appears in front of the display whenever the screen turns on.
6. In the Android settings under *Battery* or *Power saving*, exclude the app from battery optimization, if that option exists.

## Tips for the Yoga

- Stand it in landscape with the kickstand folded out. The app stays locked to landscape even when the tablet is rotated.
- Leave the charging cable plugged in permanently. Because the app keeps the CPU and Wi-Fi awake so it notices when music starts while the display is off, the battery would otherwise run flat.
- Check the battery for swelling now and then, e.g. if the case starts to bulge. With continuous operation on mains power this can happen with older devices.
- In the Wi-Fi menu under *Advanced*, set "Keep Wi-Fi on during sleep" to **Always**, if that option exists.
- Turn off automatic updates and notifications from other apps so nothing pops up over the display.
- The most thorough option: block the tablet's internet access in your router (Fritz!Box: *Internet → Filters → Parental Controls*, access profile "Blocked"). It can still reach the Pi on the home network, and that is all the display needs. The tablet's clock may drift a little over time as a result.

## Usage

- **Buttons on the display** (stop/play, next, previous, Song Radio, album, playlists, Plex/Spotify switch): see the overview in the [README](../README.md#controls-on-the-tablet).
- **Long press:** opens the app settings (address, display control)
- **Back button:** deliberately ignored
- **Display woken by hand, no music:** it shows the last played track with a play button and turns off again by itself after 1 minute (after 5 minutes if the settings dialog is open).

## What the app monitors itself

| What | Behavior |
|---|---|
| Something else in front (another app, the home screen), or the display was closed | After 60 seconds the background service brings the display back to the foreground or restarts it. So you have one minute at a time for the Android settings. Only applies while the display is on. |
| Page not responding | The page checks in with the app every second. If that stops for 30 seconds although the Pi is reachable, the app reloads it. |
| Pi not reachable | After 2 minutes the display turns off; as soon as the Pi responds again and music is playing, it turns back on. |
| Android kills the app | A background service restarts the display. |

The related messages appear in the Android log under the tag `NowPlaying` (`adb logcat -s NowPlaying`).

## Updates

Build a new version (option A, B or C), increase `versionCode` in `app/build.gradle`, and simply install it over the old one. Because the signing key is part of the project (`keystore/`), no uninstall is needed.

Note: the key is stored in the project with a simple password. That means it protects nothing: anyone can build an APK with it that Android accepts as an update. Only install APKs from your own build, and generate your own key if you want to distribute the app (see "Security" in the [README](../README.md#security)).
