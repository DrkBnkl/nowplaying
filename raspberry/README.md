# Raspberry Pi

The Pi sits on the WiiM's optical input. It runs the display server, which polls the WiiM, and produces the LED spectrum for everything played through it (Plexamp and Spotify).

| Service | Port | Purpose |
|---|---|---|
| `nowplaying` | 8080 | Display server: page, track info, covers, commands. Status page: `/setup` |
| `nowplaying-spectrum` | 8090 | 40 frequency bands, ~30×/s (Server-Sent Events) |
| `raspotify` | – | Spotify Connect device (default name "PlexAmpPi") |

[Caldera Music](https://forums.plex.tv/t/plexamp-updates-and-caldera-music/939563) (the headless successor to Plexamp headless) runs as before and is controlled from the Plexamp app. The ALSA device `nowplaying` passes the audio to the card unchanged and feeds a copy to the spectrum service; if that falls behind, the copy is dropped and the music keeps playing.

## WiiM

With `WIIM_HOST` set, the server polls the WiiM's HTTP API every 5 s:

- The Pi's output goes to the WiiM's **optical input**; set the volume on the WiiM only.
- Music playing directly on the WiiM (radio, AirPlay, Spotify on the WiiM) is shown in its own view: larger cover, previous/play/next, no LED bar (the audio doesn't pass through the Pi).
- The source switch in that view sets the WiiM to optical and resumes Plex where it left off. The same happens automatically when Plex or Spotify is started on the Pi while the WiiM is on another input.
- When the WiiM starts its own music, the server pauses Plex/Spotify on the Pi.

## Install

Copy `nowplaying.default.example` to `nowplaying.env` and fill in at least `WIIM_HOST`, plus `PLEX_URL` and `PLEX_TOKEN` if you use Plexamp (the file is git-ignored). Then:

```bash
scp -r raspberry <user>@<Pi>:
```

```bash
ssh <user>@<Pi> 'cd raspberry && sudo ./install.sh'
```

The script finds the HiFiBerry (or use `CARD=<name>`), installs the programs, services and `python3-numpy`, makes `nowplaying` the default ALSA device, installs and configures Raspotify (skip with `--no-spotify`) and creates `/etc/default/nowplaying` unless it already exists. Backups of changed files end in `.vor-nowplaying`. Afterwards restart Caldera: `systemctl --user restart caldera-music`.

Uninstall with `sudo ./uninstall.sh` (add `--spotify` to remove Raspotify too).

## Spotify control (optional)

librespot can't be controlled locally, so the switch, buttons and playlists for Spotify use the Spotify Web API (Premium required). Without it, Spotify is display-only.

1. Create an app at <https://developer.spotify.com/dashboard> with redirect URI `http://127.0.0.1:8888/callback` and "Web API" enabled. Copy the Client ID; no secret is needed.
2. On a computer with a browser: `./np-spotify-login <client-id>`. This writes `spotify.json`.
3. Set `SPOTIFY_CLIENT_ID` in `/etc/default/nowplaying`, copy `spotify.json` into the `raspberry` folder on the Pi and run `sudo ./install.sh` again.
4. Select the Pi as playback device once in the Spotify app.

`spotify.json` holds a token for your account: keep it private. Revoke access under "Manage apps" in your Spotify account.

## Settings

`/etc/default/nowplaying`, then `sudo systemctl restart nowplaying`:

| Setting | Meaning |
|---|---|
| `PLEX_URL`, `PLEX_TOKEN` | Plex server (titles, covers, playlists, similar tracks) |
| `WIIM_HOST` | IP of a WiiM streamer; empty = off |
| `SPOTIFY_CONNECT`, `SPOTIFY_CLIENT_ID`, `SPOTIFY_DEVICE` | Spotify on the Pi, Web API control, device name |
| `UI_LANG` | Display and `/setup` language: `de` (default) or `en` |
| `OFF_DELAY`, `PAUSE_DELAY` | Seconds until the display turns off after the music ends / after pausing (10 / 180) |
| `PLAYLIST_COUNT`, `SIMILAR_COUNT` | Playlists in the picker / tracks added by Song Radio (10 / 10) |

`/etc/default/nowplaying-spectrum`: `DELAY_MS` (raise if the bars run ahead of the sound), `RANGE_DB` (smaller = livelier), `TILT_DB` (treble boost).

Spotify is lowered by 2 dB (`SPOTIFY_PREGAIN` in `install.sh`) to match Caldera at volume 100; set the volume on the amplifier only. `http://<Pi>:8090/status` reports `level_db` to compare sources.

## API

| Endpoint | Purpose |
|---|---|
| `GET /api/now` | Current track, available buttons, display on/off, language |
| `GET /api/playlists`, `GET /art/<key>` | Playlists of the current source, cached cover art |
| `POST /api/skip`, `prev`, `stop`, `play`, `similar`, `albumplay`, `shuffle`, `switch` | Playback commands |
| `POST /api/playlist?id=…`, `/api/favorite?id=…&on=1` | Start a playlist, set a favorite |

POST requests need the header `X-NowPlaying: 1` (blocks cross-site requests from browsers). There is no authentication.

## Troubleshooting

| Symptom | Check |
|---|---|
| Display stays off or shows an error | `http://<Pi>:8080/setup`, `systemctl status nowplaying` |
| No LED bar | `curl http://localhost:8090/status` (`"active": true`?) |
| Music stops or skips by itself | `journalctl -u nowplaying`: every command is logged with its sender ("Command stop (caldera) from …"), and so is every automatic pause ("Automatic …"). |
| One source silent | The other still holds the sound card; the server sorts it out within ~10 s. Is the WiiM on the optical input? |
| Spotify switch missing | `/setup` shows whether Spotify control is set up; check `SPOTIFY_CLIENT_ID` and `spotify.json`. |
| Song Radio grayed out | The track has no sonic analysis in Plex yet. |
