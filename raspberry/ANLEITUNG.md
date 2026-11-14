# Raspberry: Anzeige-Server, Spektrum und Spotify Connect

Auf dem Raspberry („PlexAmpPi“, Raspberry Pi 3B mit HiFiBerry Digi, Debian 13) laufen drei Dienste. Das Tablet verbindet sich nur mit dem Pi.

| Dienst | Port | Aufgabe |
|---|---|---|
| `nowplaying` | 8080 | Anzeige-Server: Cover, Titel, Display an/aus. Das Tablet zeigt `http://<Pi>:8080`. |
| `nowplaying-spectrum` | 8090 | Spektrum: 40 Frequenzbänder, ca. 30-mal pro Sekunde, direkt ans Tablet |
| `raspotify` | – | Spotify Connect, erscheint in der Spotify-App als **„PlexAmpPi“** |

Caldera (Plexamp-Player) läuft wie bisher als Benutzerdienst von `plexamppi`.

## Wie der Ton läuft

```
Caldera ──┐                                                           (Ton unverändert)
          ├─► ALSA "default" ─► "nowplaying" ─► HiFiBerry ─► Toslink ─► WiiM Amp
Spotify ──┘   (librespot)              │
                                       └─► Kopie ─► np-spectrum-feed ─► np-spectrum-server ─► Tablet
```

- Das ALSA-Gerät `nowplaying` reicht das Signal unverändert an die HiFiBerry weiter und gibt eine Kopie an den Spektrum-Dienst.
- **Sicherheitsprinzip:** Läuft der Dienst nicht oder kommt er nicht hinterher, wird die Kopie verworfen. Die Musik spielt in jedem Fall weiter.
- Caldera und Spotify teilen sich die HiFiBerry. Es spielt immer nur einer; nach einer Pause gibt Caldera das Gerät nach 5 Sekunden frei.
- Der WiiM muss für beide auf dem **optischen Eingang** stehen. **Lautstärke nur am WiiM regeln**: Caldera (Lautstärke 100) und Spotify (feste Lautstärke) liefern vollen Pegel.

## Woher die Anzeige ihre Daten hat

| Was läuft | Quelle | Leiste |
|---|---|---|
| Plexamp → PlexAmpPi | Caldera auf dem Pi (Port 60339), Titel, Interpret, Album, Jahr und Cover vom Plex-Server | ja |
| Spotify → PlexAmpPi | librespot meldet jeden Titelwechsel über `np-librespot-event` an den Server (ohne Jahr) | ja |
| Direkt auf dem WiiM (Radio, AirPlay, Spotify → WiiM) | Schnittstelle des WiiM (ohne Jahr) | nein |

Bei Plexamp liest der Server zusätzlich Calderas Warteschlange auf dem Plex-Server und liefert den **nächsten Titel** mit (`next` in `/api/now`). Dessen Cover holt er vorab in seinen Zwischenspeicher, das Tablet lädt es ebenfalls vor. Spotify und WiiM nennen keinen nächsten Titel mit Angaben.

Status aller Quellen: `http://<Pi>:8080/setup`

## Installation

Zuerst die Einstellungen anlegen: `nowplaying.default.beispiel` nach `nowplaying.env` kopieren und den Plex-Token eintragen (die Datei wird nicht eingecheckt). Dann den Ordner `raspberry` auf den Pi kopieren und dort installieren:

```bash
scp -r raspberry plexamppi@192.168.178.68:
```

```bash
ssh plexamppi@192.168.178.68 'cd raspberry && sudo ./install.sh'
```

Das Skript:
- sucht die HiFiBerry-Karte (sonst `sudo CARD=<Name> ./install.sh`),
- installiert `python3-numpy`, die Programme nach `/usr/local/bin` und die Dienste `nowplaying` und `nowplaying-spectrum`,
- trägt das Gerät `nowplaying` in `/etc/asound.conf` ein und macht es zum Standardgerät (Sicherung: `/etc/asound.conf.vor-nowplaying`),
- installiert Raspotify aus dessen Paketquelle und richtet es als „PlexAmpPi“ ein (Sicherung: `/etc/raspotify/conf.vor-nowplaying`). Ohne Spotify: `sudo ./install.sh --no-spotify`.
- legt `/etc/default/nowplaying` an, aus `nowplaying.env`, falls vorhanden, sonst aus `nowplaying.default.beispiel`. Eine vorhandene Datei bleibt unangetastet.

Danach Caldera neu starten: `systemctl --user restart caldera-music`.

## Einstellungen

**Anzeige-Server:** `/etc/default/nowplaying`, danach `sudo systemctl restart nowplaying`.

| Wert | Bedeutung |
|---|---|
| `PLEX_URL` / `PLEX_TOKEN` | Plex-Server, liefert Titel und Cover zu Caldera |
| `WIIM_HOST` | IP-Adresse des WiiM Amp |
| `SPOTIFY_CONNECT` | `yes`, wenn Raspotify installiert ist |
| `OFF_DELAY` | Sekunden, die nach Pause oder Ende der Musik das Cover noch stehen bleibt; dann geht das Display aus (Standard 10) |

**Spektrum:** `/etc/default/nowplaying-spectrum`, danach `sudo systemctl restart nowplaying-spectrum`.

| Wert | Bedeutung |
|---|---|
| `DELAY_MS` | Gleicht die Balken an den Ton an. Laufen sie vor, erhöhen; hinken sie hinterher, verringern. Eingestellt: 40 (rechnerisch Ton und Leiste etwa gleichauf). |
| `RANGE_DB` | Kleiner = die Balken springen stärker, größer = ruhiger. |
| `TILT_DB` | Hebt die Höhen an, damit rechts mehr passiert. |

**Spotify Connect:** `/etc/raspotify/conf`, danach `sudo systemctl restart raspotify`.

**Caldera** (Stand nach der Einrichtung): Abtastrate der Quelle (`audio.sampleRate=0`), Lautstärke 100, Lautheitsangleichung und Übergänge an. Ändern mit `~/caldera-music/bin/caldera-music --set …` (vorher `LD_LIBRARY_PATH=~/caldera-music/lib`), Sicherung der alten Werte: `~/.config/caldera-music/preferences.json.vor-nowplaying`.

## Rückbau

```bash
sudo ./uninstall.sh            # Raspotify bleibt, bekommt seine alte Konfiguration zurück
sudo ./uninstall.sh --spotify  # Raspotify ebenfalls entfernen
```

## Fehlersuche

| Was du siehst | Prüfen |
|---|---|
| Tablet zeigt Ladefehler | `systemctl status nowplaying`, Adresse in der App (lange drücken) |
| Display bleibt aus, obwohl Musik läuft | `http://<Pi>:8080/setup` – zeigt eine Quelle einen Fehler? |
| Cover da, keine Leiste | `curl http://localhost:8090/status`: `"active": true`? Läuft die Musik über PlexAmpPi? |
| Spotify-Titel fehlt | `sudo journalctl -u raspotify -n 50`; `LIBRESPOT_ONEVENT` in `/etc/raspotify/conf` gesetzt? |
| Spotify oder Caldera stumm | Spielt der andere noch? Erst pausieren, 5 s warten. WiiM auf optischem Eingang? |
