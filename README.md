# Now Playing – album cover and controls on a Lenovo Yoga tablet

An old Lenovo Yoga Tab 3 stands sideways next to the hi-fi and shows what is playing: the cover on the left; artist, title, album and year on the right; and along the bottom a retro-style LED bar (spectrum analyzer) in the colors of the cover. Buttons on the tablet control the music: stop and play, next and previous, insert similar tracks (Song Radio), play the whole album, pick a playlist, and switch between Plex and Spotify. When no music is playing, the display turns off, and it turns back on by itself as soon as music starts again.

![Display with control buttons](screenshots/controls.png)

| Playlist picker with favorites and shuffle | Bright cover (older capture, before the buttons existed) |
|---|---|
| ![Playlist picker](screenshots/playlist-picker.png) | ![Bright cover](screenshots/bright-cover.png) |

---

## Contents

| Folder | What | Where it goes |
|---|---|---|
| [`raspberry/`](raspberry/) | Display server, display page, spectrum service, setup of the Spotify Connect device "PlexAmpPi", Spotify login | Raspberry Pi "PlexAmpPi" |
| [`tablet-app/`](tablet-app/) | Android app for the Yoga (source code) | built into an APK, then installed on the tablet |
| [`screenshots/`](screenshots/) | Screenshots from the real tablet | for viewing only |

Each folder has its own `README.md` with all the detailed steps. This file gives the overview.

---

## How it all fits together

```
Plexamp (phone) ──► Caldera ──┐
                              ├─► Raspberry "PlexAmpPi" ─► HiFiBerry Digi ─► Toslink ─► WiiM Amp ─► speakers
Spotify (phone) ──► librespot ┘        │
                                       ├─ Spectrum service (port 8090) ── 40 bands, 30×/s ───┐
Plex server (NAS) ── titles, covers ──►├─ Display server  (port 8080) ───────────────────────┼─► Lenovo Yoga
Spotify Web API ◄─────────────────────►│   track info, covers, display on/off,               │   (app, full screen)
WiiM (radio, AirPlay) ── titles, cover►┘   commands from the tablet (stop, skip, playlist …) ◄┘
```

The Raspberry Pi does all the work. The NAS only supplies the music and, through Plex, titles, covers, playlists and similar tracks; no Docker container is needed. The tablet talks only to the Pi and needs no internet access.

### Where the display gets its data

| What is playing | Source | Year | "Up next" | Progress | LED bar |
|---|---|---|---|---|---|
| Plexamp → **PlexAmpPi** (Caldera) | Caldera on the Pi, metadata and cover from the Plex server | yes (album year) | yes | yes | yes |
| Spotify → **PlexAmpPi** (librespot) | librespot reports every track change; playlist name via the Spotify Web API | – | – | – | yes |
| Directly on the WiiM (radio, AirPlay, Spotify → WiiM) | WiiM API | – | – | yes, if the WiiM reports a duration | – |

### What the tablet shows

| State | Display |
|---|---|
| Music playing | Cover on the left; on the right the artist (large), title, and album · year in colors taken from the cover; below that the extra line (see below) and "Up next: …"; buttons; at the bottom the progress line and LED bar; blurred cover in the background |
| Track change | Cover, background, text and colors crossfade over 1.5 s. With Plexamp the next cover is already preloaded. |
| Paused (stop button, or paused in the app) | Cover and text dimmed, the stop button becomes a play button. After 3 minutes (`PAUSE_DELAY`) the display turns off. |
| Music ended, source gone | Cover dimmed; after 10 s (`OFF_DELAY`) the display turns off |
| No music | Display off. When music starts, it turns back on within 1–3 s. |
| Woken by hand, no music | The last played track is shown dimmed with a play button; play resumes where it left off. Without interaction the display turns off again after 1 minute. |

**Extra line below the album**

| What is playing | Line |
|---|---|
| A playlist | Logo of the source (Plex or Spotify) and the playlist name |
| Tracks inserted by "Song Radio" | Spark icon and "Song Radio: Artist – Title" of the track the suggestions are based on |
| A whole album in its original order | Record icon and "Track 3 of 10"; the "Up next" line is omitted |

Bright covers darken the background more; black covers get a thin border. Long text wraps at sensible points (parenthesized additions and individual artist names are kept together); a single overlong word is shrunk rather than hyphenated. If the text block gets too tall, it is truncated before it runs into the buttons.

### Display language

The tablet display and the `/setup` page are in German by default. Set `UI_LANG=en` in `/etc/default/nowplaying` for English (see [Settings](#settings)). The tablet app's own texts (settings dialog, messages) follow the Android system language: English on an English device, German otherwise.

### Controls on the tablet

Bottom row, aligned with the text:

| Button | Action | Plex (Caldera) | Spotify on the Pi | WiiM |
|---|---|---|---|---|
| Record | Play the album of the current track from track 1. Grayed out while an album is already playing. | yes | yes | – |
| Spark ("Song Radio") | Inserts ten sonically similar tracks after the current one; the current track keeps playing. Grayed out for tracks Plex has not yet sonically analyzed. | yes | – (Spotify no longer gives recommendations to third-party apps) | – |
| Previous | previous track | yes | yes | yes |
| Stop / Play | pauses, or resumes at the same position | yes | yes | yes |
| Next | next track | yes | yes | yes |

Top row, right (the source switch and playlists sit to the left of the view switch):

| Button | Action |
|---|---|
| View (three-part switch, always at the top right) | Three segments, each showing its view as a small picture; the segment of the current view is highlighted. Tapping selects directly: all info with LED bar · info without the bar (larger cover, buttons at the bottom right, like the WiiM view) · cover only (centered, as large as possible; all other buttons, text and the progress line are hidden). Applies to Plex and Spotify alike; the Pi remembers the choice. |
| Plex / Spotify switch | Stops the current source and resumes the other one (Plex where it left off, Spotify wherever the account last was). The logo of the source currently playing is shown in color. Switching to Spotify takes about 6 s, because Caldera only releases the sound card 5 s after pausing. |
| Playlists | Opens the picker: up to ten playlists of the current source in two columns, favorites first, then the most recently played. Tapping starts the playlist. The star marks a favorite without starting it. At the top is the "Shuffle" toggle: it shuffles the current queue and applies to the next playlist you choose. The panel closes after 10 s without interaction. |

When music is playing directly on the WiiM (radio, AirPlay, Spotify → WiiM), there is no LED bar. The cover is larger, the buttons sit at the bottom right, and at the top right there is only the source switch (both logos gray, no playlist button). Tapping it sets the WiiM to its optical input and resumes Plex where it left off. The same happens automatically when Plex or Spotify on the Pi is started some other way (e.g. from the Plex app) while the WiiM is not on the optical input. Conversely, the server stops Plex or Spotify on the Pi as soon as the WiiM starts playing its own music (e.g. Spotify Connect on the WiiM); the tablet then shows the WiiM.

A long press on the screen opens the app settings.

For Spotify (switch, buttons, playlists) the Spotify Web API must be set up once, see [`raspberry/README.md`](raspberry/README.md). Without it, Spotify is display-only.

### Good to know in everyday use

- For music via the Pi, the WiiM must be set to its **optical input**.
- **Only adjust the volume on the WiiM.** Caldera (volume 100) and Spotify output full level; Spotify is lowered by 2 dB so both sound about equally loud (tuned by ear, `SPOTIFY_PREGAIN`). If Caldera's volume is changed in the Plex app, this no longer holds.
- Caldera and Spotify share the sound card: **only one source plays at a time.** The switch on the tablet handles the change. If you start the other source from your phone, the server stops the previous one itself and gets the new one playing; this takes about 10–15 s (the sound card is only released after 5 s).
- Spotify on the Pi: albums and playlists play through; after a single track playback stops, because autoplay does not load there.
- The display server has no authentication: anyone on the home network can trigger the buttons too.

---

## Setup

1. **Raspberry Pi:** [`raspberry/README.md`](raspberry/README.md) – copy the folder to the Pi, run `sudo ./install.sh`; for Spotify control, additionally do the one-time login.
2. **Tablet:** [`tablet-app/README.md`](tablet-app/README.md) – build and install the APK, enter the Pi's address, enable display control, set the screen lock to "None".

### Settings

| What | File on the Pi | Template in the repository |
|---|---|---|
| Display server (Plex, WiiM, Spotify, timings, number of playlists, display language `UI_LANG`) | `/etc/default/nowplaying` | `raspberry/nowplaying.default.example` |
| Spectrum (`DELAY_MS`, sensitivity) | `/etc/default/nowplaying-spectrum` | `raspberry/nowplaying-spectrum.default` |
| Spotify Connect (name, normalization, pregain) | `/etc/raspotify/conf` | set by `install.sh` |
| Spotify login, favorites | `/var/lib/private/nowplaying/` | created at runtime |

`UI_LANG` sets the language of the tablet display and the `/setup` page: `de` (default) or `en`.

The following do not belong in the repository and are excluded via `.gitignore`: `raspberry/nowplaying.env` (local copy of the template containing the Plex token) and `raspberry/spotify.json` (Spotify login).

---

## Current setup (as of 2026-11-14)

- **Raspberry Pi** "PlexAmpPi" (Pi 3B, HiFiBerry Digi, Debian 13): services `nowplaying`, `nowplaying-spectrum` and `raspotify` are running.
  Caldera: source sample rate, volume 100, loudness normalization and transitions on. Spectrum delay `DELAY_MS=0`. Spotify: pregain −2 dB, Web API set up, Raspotify remembers the login.
- **Tablet** Lenovo Yoga Tab 3 8" (YT3-850L, Android 6.0.1): app 1.10, address `http://<Pi>:8080`, display control enabled, screen lock "None", unneeded apps removed or disabled, USB debugging on.
- **Tested** with real music: Plexamp and Spotify via the Pi with cover, track info and LED bar; crossfade; display off and on; stop/play, skip, Song Radio, album, playlist start, favorites and shuffle with Plex; source switch in both directions; skip, stop, play and playlist start with Spotify; "only one source at a time" in both directions (other source started bypassing the server).
- **Not yet tested:** all buttons on the WiiM, the WiiM view on the tablet, and switching from the WiiM to Plex; album button, favorites and shuffle with Spotify; the page continuing to run after waking by hand without music (since app 1.9); display off after 3 minutes of pause; the English UI texts of app 1.10.

---

## Troubleshooting: where to start?

| What you see | Where to look |
|---|---|
| Tablet shows nothing, or a loading error | Check the address in the app (long press); is `nowplaying` running on the Pi? |
| Display stays off although music is playing | `http://<Pi>:8080/setup`: does a source show an error? |
| Cover shows, but no LED bar | Is the music playing via PlexAmpPi? `curl http://<Pi>:8090/status` |
| LED bar runs ahead of the sound | Increase `DELAY_MS` in `/etc/default/nowplaying-spectrum` |
| LED bar lags behind the sound | Is `DELAY_MS` in `/etc/default/nowplaying-spectrum` set to 0? It cannot go lower; `install.sh` does not change an existing file |
| LED bar stutters | Is the app using a full CPU core? Then the page is constantly redrawing (e.g. because of an animation in `index.html`). |
| No sound via the Pi | WiiM on the optical input? Is the other player (Caldera/Spotify) currently playing? |
| Source switch and Spotify buttons missing | `/setup`: does it say Spotify control is set up? If not, repeat the login (`np-spotify-login`). |
| "To Spotify" does nothing | Does the Spotify account know the device "PlexAmpPi"? Select it once in the Spotify app. |
| Spark button grayed out | The track has not been sonically analyzed in Plex; start the analysis on the Plex server. |
| Spotify and Plex differ in loudness | Is Caldera's volume at 100? Level of both sources: `level_db` at `http://<Pi>:8090/status` |
| Display does not turn off without music | Long press in the app → "Enable display control" ("Display-Steuerung aktivieren" on a German device) |

---

## Technical overview

- **Display server** (`raspberry/np-server`): Python 3, standard library only, systemd service. Polls Caldera locally (Plex Companion API), reads Caldera's play queue (next track, playlist, album), receives Spotify events from librespot and polls the WiiM. It forwards commands from the tablet (`/api/skip`, `prev`, `stop`, `play`, `similar`, `albumplay`, `shuffle`, `switch`, `playlist`, `favorite`) to Caldera, the Plex server, the Spotify Web API or the WiiM. All covers go through the server and are cached in advance; this avoids certificate problems with the old Android version.
- **Display page** (`raspberry/index.html`): ES5 JavaScript for the Android WebView. Two layers for crossfading, cover colors from a 32×32 thumbnail of the cover, polling every second. It reloads itself as soon as a new version is on the Pi. In a browser it draws the LED bar itself; in the tablet app, the app does that.
- **Spectrum** (`np-spectrum-feed`, `np-spectrum-server`): the ALSA device `nowplaying` passes the signal unchanged to the HiFiBerry and hands off a copy. From that, an FFT (numpy) computes 40 bands from 40 Hz to 16 kHz, which are sent to the tablet via Server-Sent Events. If the service cannot keep up, the copy is discarded – the music always keeps playing. `/status` also reports the average level (`level_db`).
- **Spotify:** Raspotify (librespot 0.8) plays, with fixed volume, loudness normalization on, pregain −2 dB, events via `np-librespot-event`. Control goes through the Spotify Web API (login with `np-spotify-login`, PKCE without a client secret); every 10 s the server checks whether Spotify is playing on the Pi.
- **Tablet app** (`tablet-app/`): Java, no third-party libraries, Android 5 and later. Shows the page full screen and draws the LED bar natively on its own surface on top (in the WebView it cost a full CPU core; now the app uses about a quarter of a core). Turns the display off via device administrator and back on via a wake lock, brings itself back to the foreground after 60 s, reloads a page that has stopped responding, and starts by itself after a reboot. UI texts are German by default and English when the device language is English.

---

## Security

- **Run it on your home network only.** The display server (port 8080) and the spectrum service (port 8090) have no authentication: anyone who can reach them can control the music. So do not open these ports on your router or forward them to the internet in any other way.
- **Credentials** (Plex token, Spotify login) are stored only on the Pi in `/etc/default/nowplaying` and `/var/lib/private/nowplaying/`, and never in the repository.
- **Tablet app signing key:** `tablet-app/keystore/nowplaying.jks` is deliberately checked in with a fixed password so that every build installs as an update of the existing app. It therefore protects nothing: anyone can use it to build an APK that Android will accept as an update. Only install APKs from your own build or your own GitHub Actions runs. If you want to distribute the app, generate your own key (`keytool -genkeypair -v -keystore own.jks -alias nowplaying -keyalg RSA -keysize 2048 -validity 10000`) and enter it in `tablet-app/app/build.gradle`.

## License

[MIT](LICENSE). The screenshots in `screenshots/` show album covers and names belonging to their respective rights holders; they are for illustration only and are not covered by the license.
