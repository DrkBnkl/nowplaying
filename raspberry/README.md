# Raspberry Pi: display server, spectrum and Spotify Connect

The Raspberry Pi ("PlexAmpPi", a Raspberry Pi 3B with HiFiBerry Digi, Debian 13) runs three services. The tablet connects only to the Pi.

| Service | Port | Purpose |
|---|---|---|
| `nowplaying` | 8080 | Display server: cover, track info, display on/off, commands from the tablet. The tablet shows `http://<Pi>:8080`. |
| `nowplaying-spectrum` | 8090 | Spectrum: 40 frequency bands, about 30 times per second, straight to the tablet |
| `raspotify` | – | Spotify Connect, appears in the Spotify app as **"PlexAmpPi"** |

Caldera (the Plexamp player) keeps running as a user service of `plexamppi`, as before.

## Audio path

```
Caldera ──┐                                                           (audio unchanged)
          ├─► ALSA "default" ─► "nowplaying" ─► HiFiBerry ─► Toslink ─► WiiM Amp
Spotify ──┘   (librespot)              │
                                       └─► copy ─► np-spectrum-feed ─► np-spectrum-server ─► tablet
```

- The ALSA device `nowplaying` passes the signal unchanged to the HiFiBerry and hands a copy to the spectrum service.
- **Safety principle:** if the service is not running or cannot keep up, the copy is discarded. The music always keeps playing.
- Caldera and Spotify share the HiFiBerry. Only one of them can open the card at a time; after pausing, Caldera only releases it after 5 seconds. If the other one starts earlier, Caldera stays silent or librespot exits.
- **Only one source at a time:** the display server takes care of this itself. The switch on the tablet stops the current source, waits until the card is free, and starts the other one. If the other source is started bypassing the server (from a phone), the server notices within 10 seconds at most that both are "playing", stops the older one and gets the most recently started one playing.
- The WiiM must be set to its **optical input** for both. **Only adjust the volume on the WiiM**: Caldera (volume 100) and Spotify (fixed volume) output full level.

## Where the display gets its data

| What is playing | Source | LED bar |
|---|---|---|
| Plexamp → PlexAmpPi | Caldera on the Pi (port 60339); title, artist, album, year and cover from the Plex server | yes |
| Spotify → PlexAmpPi | librespot reports every track change to the server via `np-librespot-event` (no year); the playlist name and missing details come from the Spotify Web API | yes |
| Directly on the WiiM (radio, AirPlay, Spotify → WiiM) | WiiM API (no year) | no |

For Plexamp, the server also reads Caldera's play queue on the Plex server. That provides the **next track** ("Up next"; its cover is preloaded), the **playlist name**, and the detection of an **album** (an unshuffled queue containing exactly the tracks of one album → "Track n of m").

Status of all sources: `http://<Pi>:8080/setup`

## Installation

First create the settings: copy `nowplaying.default.example` to `nowplaying.env` and enter your Plex token (this file is not checked in). Then copy the `raspberry` folder to the Pi and install it there:

```bash
scp -r raspberry <user>@<Pi>:
```

```bash
ssh <user>@<Pi> 'cd raspberry && sudo ./install.sh'
```

The script:
- looks for the HiFiBerry card (otherwise use `sudo CARD=<name> ./install.sh`),
- installs `python3-numpy`, the programs to `/usr/local/bin`, the display page to `/usr/local/share/nowplaying`, and the services `nowplaying` and `nowplaying-spectrum`,
- adds the `nowplaying` device to `/etc/asound.conf` and makes it the default device (backup: `/etc/asound.conf.vor-nowplaying`),
- installs Raspotify from its package repository and sets it up as "PlexAmpPi" (backup: `/etc/raspotify/conf.vor-nowplaying`): fixed volume, loudness normalization, pregain (`SPOTIFY_PREGAIN`, default −2 dB) and a remembered login. Without Spotify: `sudo ./install.sh --no-spotify`.
- creates `/etc/default/nowplaying` from `nowplaying.env` if present, otherwise from `nowplaying.default.example`. An existing file is left untouched.
- moves an existing `spotify.json` (see below) into the service's state directory.

Then restart Caldera: `systemctl --user restart caldera-music`.

After changing `index.html`, it is enough to install that file and restart the service; the tablet loads the new page by itself:

```bash
sudo install -m 644 index.html /usr/local/share/nowplaying/index.html && sudo systemctl restart nowplaying
```

## Controlling Spotify: one-time login

Spotify on the Pi (librespot) cannot be controlled locally. The source switch, skip, previous, stop/play, album, shuffle and the playlist picker for Spotify therefore go through the Spotify Web API (Spotify Premium required). Without this setup, Spotify is display-only.

1. Log in at <https://developer.spotify.com/dashboard> and create an app (any name, e.g. "Now Playing").
2. Enter exactly `http://127.0.0.1:8888/callback` as the redirect URI (Spotify rejects `localhost`), tick "Web API", save.
3. Copy the app's **Client ID**. No client secret is needed.
4. On the computer where you are logged in to Spotify in the browser (not on the Pi):

   ```bash
   ./np-spotify-login <Client-ID>
   ```

   The program opens Spotify's consent page and then writes `spotify.json` next to itself. It requests four permissions: read playback, control playback, read listening history (for "recently played playlists"), and read your own playlists.
5. Add `SPOTIFY_CLIENT_ID=<Client-ID>` to `/etc/default/nowplaying`, copy `spotify.json` into the `raspberry` folder on the Pi and run `sudo ./install.sh` (or place the file at `/var/lib/private/nowplaying/spotify.json`, owned by the directory's owner, and restart `nowplaying`).
6. In the Spotify app, **select "PlexAmpPi" as the device once**. Raspotify remembers the login (`/var/lib/raspotify`) and from then on signs in to the account by itself on every start; only then can the server find the device.

Check: `http://<Pi>:8080/setup` shows that Spotify control is set up. `spotify.json` contains an access token for your account: do not check it in, do not share it. You can revoke access in your Spotify account settings under "Manage apps".

There is no "Song Radio" button for Spotify: Spotify no longer gives track recommendations to third-party apps.

## Settings

**Display server:** `/etc/default/nowplaying`, then `sudo systemctl restart nowplaying`.

| Value | Meaning |
|---|---|
| `PLEX_URL` / `PLEX_TOKEN` | Plex server; provides titles, covers, playlists and similar tracks for Caldera |
| `WIIM_HOST` | IP address of the WiiM Amp |
| `SPOTIFY_CONNECT` | `yes` if Raspotify is installed |
| `SPOTIFY_CLIENT_ID` | Client ID of your own Spotify app; empty = Spotify display only |
| `SPOTIFY_DEVICE` | Name of the Spotify Connect device on the Pi (default `PlexAmpPi`) |
| `OFF_DELAY` | Seconds the cover stays visible after the music ends before the display turns off (default 10) |
| `PAUSE_DELAY` | Seconds the cover with the play button stays visible after pause/stop (default 180) |
| `PLAYLIST_COUNT` | Number of playlists in the picker (default 10) |
| `SIMILAR_COUNT` | Number of tracks "Song Radio" inserts (default 10) |
| `SPECTRUM_URL` / `SPECTRUM_PORT` | Address of the spectrum service; `auto` = this Pi. Only change `SPECTRUM_PORT` if `PORT` was changed in `/etc/default/nowplaying-spectrum`. |
| `UI_LANG` | Language of the tablet display and the `/setup` page: `de` (default) or `en` |

**Spectrum:** `/etc/default/nowplaying-spectrum`, then `sudo systemctl restart nowplaying-spectrum`.

| Value | Meaning |
|---|---|
| `DELAY_MS` | Holds the bars back. Current setting: 0 (as early as possible). If the bars run ahead of the sound, increase it. |
| `RANGE_DB` | Smaller = the bars jump more, larger = calmer. |
| `TILT_DB` | Boosts the treble so there is more movement on the right. |

**Spotify Connect:** `/etc/raspotify/conf`, then `sudo systemctl restart raspotify`.

**Caldera** (state after setup): source sample rate (`audio.sampleRate=0`), volume 100, loudness normalization and transitions on. Change with `~/caldera-music/bin/caldera-music --set …` (with `LD_LIBRARY_PATH=~/caldera-music/lib` set first); backup of the old values: `~/.config/caldera-music/preferences.json.vor-nowplaying`.

### What the server remembers

In the state directory `/var/lib/private/nowplaying/`:

| File | Contents |
|---|---|
| `spotify.json` | Spotify login (refresh token); Spotify replaces it occasionally, and the server then rewrites the file |
| `favorites.json` | Playlist favorites set on the tablet, separately for Plex and Spotify |

Kept only in memory and forgotten when the service restarts: the last played track (for "play after waking"), which tracks "Song Radio" inserted, and the last shuffle setting (it then defaults to "on" again).

## Matching Spotify and Plex loudness

Spotify normalizes to about −14 LUFS, Caldera to about −19 LUFS for tracks with Plex loudness analysis. Spotify's pregain (`LIBRESPOT_NORMALISATION_PREGAIN`, range −10 to 10 dB) is set by ear to **−2 dB**. This assumes Caldera's volume is 100; the volume slider in the Plex app changes it.

To measure: the spectrum service reports `level_db` at `http://<Pi>:8090/status`, the average level of the last 30 seconds of audio, measured at the same point for both sources. Tracks that Caldera plays without analysis are noticeably louder; that cannot be compensated here.

## Display server API

| Endpoint | Purpose |
|---|---|
| `GET /` | Display page |
| `GET /api/now` | What is playing, display on/off, which buttons are available, page version |
| `GET /art/<key>` | Cover (proxied and cached by the server) |
| `GET /api/playlists` | Playlists of the current source (favorites first) |
| `POST /api/skip`, `prev`, `stop`, `play` | Control playback (`stop` pauses, so that `play` resumes at the same position) |
| `POST /api/similar` | "Song Radio": insert similar tracks into Caldera's queue |
| `POST /api/albumplay` | Album of the current track from track 1 |
| `POST /api/shuffle` | Toggle shuffle |
| `POST /api/switch` | Plex ↔ Spotify switch |
| `POST /api/playlist?id=…` | Start a playlist |
| `POST /api/favorite?id=…&on=1` | Set or remove a favorite |
| `GET /setup` | Status of the sources |

All `POST` commands require the header `X-NowPlaying: 1`; this prevents a third-party web page from triggering them in the browser without your consent. There is no authentication: anyone on the home network can send the commands.

## Uninstalling

```bash
sudo ./uninstall.sh            # keeps Raspotify, restores its old configuration
sudo ./uninstall.sh --spotify  # also removes Raspotify
```

## Troubleshooting

| What you see | Check |
|---|---|
| Tablet shows a loading error | `systemctl status nowplaying`, the address in the app (long press) |
| Display stays off although music is playing | `http://<Pi>:8080/setup` – does a source show an error? |
| Cover shows, no LED bar | `curl http://localhost:8090/status`: `"active": true`? Is the music playing via PlexAmpPi? |
| Spotify track info missing | `sudo journalctl -u raspotify -n 50`; is `LIBRESPOT_ONEVENT` set in `/etc/raspotify/conf`? |
| Spotify or Caldera silent | Is the other one still playing? The server resolves this by itself after about 10 s; otherwise pause and wait 5 s. WiiM on the optical input? |
| Music stops or skips without you pressing anything | `sudo journalctl -u nowplaying -n 30`: the server logs every command from the page with the sender's address ("Command stop (caldera) from <Tablet-IP>") and every intervention of the "only one source at a time" rule ("Automatic ..."). If nothing is logged there, the command did not come from the server but, for example, from a Plex or Spotify app. |
| Source switch missing on the tablet | `/setup`: does it say Spotify control is set up? Is `SPOTIFY_CLIENT_ID` set, and is `spotify.json` present and readable by the service? |
| "To Spotify" has no effect | The account does not know "PlexAmpPi": select it once in the Spotify app. Does `/var/lib/raspotify/credentials.json` exist? |
| Raspotify keeps restarting | `journalctl -u raspotify`: "Device … Busy" means the sound card was in use (Caldera was playing or still holding it). |
| Spark button grayed out | The track has no sonic analysis in Plex; start one on the Plex server. |
