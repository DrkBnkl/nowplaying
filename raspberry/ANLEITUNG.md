# Raspberry: Anzeige-Server, Spektrum und Spotify Connect

Auf dem Raspberry („PlexAmpPi“, Raspberry Pi 3B mit HiFiBerry Digi, Debian 13) laufen drei Dienste. Das Tablet verbindet sich nur mit dem Pi.

| Dienst | Port | Aufgabe |
|---|---|---|
| `nowplaying` | 8080 | Anzeige-Server: Cover, Titel, Display an/aus, Befehle vom Tablet. Das Tablet zeigt `http://<Pi>:8080`. |
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
- Caldera und Spotify teilen sich die HiFiBerry. Es kann immer nur einer die Karte öffnen; nach einer Pause gibt Caldera sie erst nach 5 Sekunden frei. Startet der andere vorher, bleibt Caldera stumm bzw. beendet sich librespot.
- **Immer nur eine Quelle:** Der Anzeige-Server sorgt selbst dafür. Der Umschalter am Tablet hält die laufende Quelle an, wartet, bis die Karte frei ist, und startet die andere. Wird die andere Quelle am Server vorbei gestartet (Handy), erkennt er nach spätestens 10 Sekunden, dass beide „spielen“, hält die ältere an und bringt die zuletzt gestartete zum Spielen.
- Der WiiM muss für beide auf dem **optischen Eingang** stehen. **Lautstärke nur am WiiM regeln**: Caldera (Lautstärke 100) und Spotify (feste Lautstärke) liefern vollen Pegel.

## Woher die Anzeige ihre Daten hat

| Was läuft | Quelle | Leiste |
|---|---|---|
| Plexamp → PlexAmpPi | Caldera auf dem Pi (Port 60339), Titel, Interpret, Album, Jahr und Cover vom Plex-Server | ja |
| Spotify → PlexAmpPi | librespot meldet jeden Titelwechsel über `np-librespot-event` an den Server (ohne Jahr); Playlist-Name und fehlende Angaben kommen über die Spotify-Web-Schnittstelle | ja |
| Direkt auf dem WiiM (Radio, AirPlay, Spotify → WiiM) | Schnittstelle des WiiM (ohne Jahr) | nein |

Bei Plexamp liest der Server zusätzlich Calderas Warteschlange auf dem Plex-Server. Daraus kommen der **nächste Titel** („Danach“, sein Cover wird vorab geladen), der **Name der Playlist** und die Erkennung eines **Albums** (ungemischte Warteschlange mit genau den Titeln eines Albums → „Titel n von m“).

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
- installiert `python3-numpy`, die Programme nach `/usr/local/bin`, die Anzeigeseite nach `/usr/local/share/nowplaying` und die Dienste `nowplaying` und `nowplaying-spectrum`,
- trägt das Gerät `nowplaying` in `/etc/asound.conf` ein und macht es zum Standardgerät (Sicherung: `/etc/asound.conf.vor-nowplaying`),
- installiert Raspotify aus dessen Paketquelle und richtet es als „PlexAmpPi“ ein (Sicherung: `/etc/raspotify/conf.vor-nowplaying`): feste Lautstärke, Lautheitsangleichung, Absenkung (`SPOTIFY_PREGAIN`, Standard −2 dB) und gemerkte Anmeldung. Ohne Spotify: `sudo ./install.sh --no-spotify`.
- legt `/etc/default/nowplaying` an, aus `nowplaying.env`, falls vorhanden, sonst aus `nowplaying.default.beispiel`. Eine vorhandene Datei bleibt unangetastet.
- bringt eine vorhandene `spotify.json` (siehe unten) ins Zustandsverzeichnis des Dienstes.

Danach Caldera neu starten: `systemctl --user restart caldera-music`.

Nach einer Änderung an `index.html` reicht es, die Datei zu installieren und den Dienst neu zu starten; das Tablet lädt die neue Seite von selbst:

```bash
sudo install -m 644 index.html /usr/local/share/nowplaying/index.html && sudo systemctl restart nowplaying
```

## Spotify steuern: einmalige Anmeldung

Spotify auf dem Pi (librespot) lässt sich nicht lokal steuern. Umschalter, Skip, Zurück, Stopp/Play, Album, Zufallswiedergabe und die Playlist-Auswahl für Spotify gehen deshalb über die Web-Schnittstelle von Spotify (Spotify Premium nötig). Ohne diese Einrichtung bleibt Spotify eine reine Anzeige.

1. Bei <https://developer.spotify.com/dashboard> anmelden und eine App anlegen (Name frei, z. B. „Now Playing“).
2. Als Redirect-URI genau `http://127.0.0.1:8888/callback` eintragen (`localhost` lehnt Spotify ab), „Web API“ ankreuzen, speichern.
3. Die **Client ID** der App kopieren. Ein Client Secret wird nicht gebraucht.
4. Auf dem Rechner, an dem du im Browser bei Spotify angemeldet bist (nicht auf dem Pi):

   ```bash
   ./np-spotify-login <Client-ID>
   ```

   Das Programm öffnet die Zustimmungsseite von Spotify und schreibt danach `spotify.json` neben sich. Erfragt werden vier Rechte: Wiedergabe lesen, Wiedergabe steuern, Hörverlauf lesen (für „zuletzt gespielte Playlists“), eigene Playlists lesen.
5. `SPOTIFY_CLIENT_ID=<Client-ID>` in `/etc/default/nowplaying` eintragen, `spotify.json` in den Ordner `raspberry` auf dem Pi kopieren und `sudo ./install.sh` ausführen (oder die Datei mit dem Besitzer des Verzeichnisses nach `/var/lib/private/nowplaying/spotify.json` legen und `nowplaying` neu starten).
6. In der Spotify-App **einmal „PlexAmpPi“ als Gerät auswählen**. Raspotify merkt sich die Anmeldung (`/var/lib/raspotify`) und meldet sich danach bei jedem Start selbst am Konto an; nur dann findet der Server das Gerät.

Prüfen: `http://<Pi>:8080/setup` zeigt „Spotify-Steuerung: eingerichtet“. `spotify.json` enthält ein Zugangstoken für dein Konto: nicht einchecken, nicht weitergeben. Zurückziehen lässt sich der Zugriff in den Kontoeinstellungen von Spotify unter „Apps verwalten“.

Einen „Song Radio“-Knopf gibt es für Spotify nicht: Spotify gibt eigenen Apps keine Titelvorschläge mehr heraus.

## Einstellungen

**Anzeige-Server:** `/etc/default/nowplaying`, danach `sudo systemctl restart nowplaying`.

| Wert | Bedeutung |
|---|---|
| `PLEX_URL` / `PLEX_TOKEN` | Plex-Server, liefert Titel, Cover, Playlists und ähnliche Titel zu Caldera |
| `WIIM_HOST` | IP-Adresse des WiiM Amp |
| `SPOTIFY_CONNECT` | `yes`, wenn Raspotify installiert ist |
| `SPOTIFY_CLIENT_ID` | Client ID der eigenen Spotify-App; leer = Spotify nur anzeigen |
| `SPOTIFY_DEVICE` | Name des Spotify-Connect-Geräts auf dem Pi (Standard `PlexAmpPi`) |
| `OFF_DELAY` | Sekunden, die nach dem Ende der Musik das Cover noch stehen bleibt; dann geht das Display aus (Standard 10) |
| `PAUSE_DELAY` | Sekunden, die nach Pause/Stopp das Cover mit Play-Knopf stehen bleibt (Standard 180) |
| `PLAYLIST_COUNT` | Anzahl Playlists in der Auswahl (Standard 10) |
| `SIMILAR_COUNT` | Anzahl Titel, die „Song Radio“ einfügt (Standard 10) |
| `SPECTRUM_URL` / `SPECTRUM_PORT` | Adresse des Spektrum-Dienstes; `auto` = dieser Pi. `SPECTRUM_PORT` nur ändern, wenn `PORT` in `/etc/default/nowplaying-spectrum` geändert wurde. |

**Spektrum:** `/etc/default/nowplaying-spectrum`, danach `sudo systemctl restart nowplaying-spectrum`.

| Wert | Bedeutung |
|---|---|
| `DELAY_MS` | Hält die Balken zurück. Eingestellt: 0 (so früh wie möglich). Laufen die Balken dem Ton voraus, erhöhen. |
| `RANGE_DB` | Kleiner = die Balken springen stärker, größer = ruhiger. |
| `TILT_DB` | Hebt die Höhen an, damit rechts mehr passiert. |

**Spotify Connect:** `/etc/raspotify/conf`, danach `sudo systemctl restart raspotify`.

**Caldera** (Stand nach der Einrichtung): Abtastrate der Quelle (`audio.sampleRate=0`), Lautstärke 100, Lautheitsangleichung und Übergänge an. Ändern mit `~/caldera-music/bin/caldera-music --set …` (vorher `LD_LIBRARY_PATH=~/caldera-music/lib`), Sicherung der alten Werte: `~/.config/caldera-music/preferences.json.vor-nowplaying`.

### Was der Server sich merkt

Im Zustandsverzeichnis `/var/lib/private/nowplaying/`:

| Datei | Inhalt |
|---|---|
| `spotify.json` | Spotify-Anmeldung (Refresh-Token); Spotify tauscht es gelegentlich aus, der Server schreibt es dann neu |
| `favorites.json` | am Tablet gesetzte Playlist-Favoriten, getrennt für Plex und Spotify |

Nur im Arbeitsspeicher und nach einem Neustart des Dienstes vergessen: der zuletzt gespielte Titel (für „Play nach dem Aufwecken“), welche Titel „Song Radio“ eingefügt hat, und die letzte Stellung der Zufallswiedergabe (dann wieder „an“).

## Spotify und Plex gleich laut

Spotify regelt auf etwa −14 LUFS, Caldera bei Titeln mit Plex-Lautheitsanalyse auf etwa −19 LUFS. Die Absenkung von Spotify (`LIBRESPOT_NORMALISATION_PREGAIN`, Bereich −10 bis 10 dB) ist nach Gehör auf **−2 dB** eingestellt. Voraussetzung ist Calderas Lautstärke 100; der Regler in der Plex-App verstellt sie.

Nachmessen: Der Spektrum-Dienst nennt unter `http://<Pi>:8090/status` mit `level_db` den mittleren Pegel der letzten 30 Sekunden Ton, für beide Quellen an derselben Stelle gemessen. Titel, die Caldera ohne Analyse spielt, sind deutlich lauter; das lässt sich hier nicht ausgleichen.

## Schnittstelle des Anzeige-Servers

| Adresse | Zweck |
|---|---|
| `GET /` | Anzeigeseite |
| `GET /api/now` | Was läuft, Display an/aus, welche Knöpfe gehen, Fassung der Seite |
| `GET /art/<Schlüssel>` | Cover (vom Server durchgereicht und zwischengespeichert) |
| `GET /api/playlists` | Playlists der laufenden Quelle (Favoriten zuerst) |
| `POST /api/skip`, `prev`, `stop`, `play` | Wiedergabe steuern (`stop` hält an, damit `play` an derselben Stelle weiterspielt) |
| `POST /api/similar` | „Song Radio“: ähnliche Titel in Calderas Warteschlange einfügen |
| `POST /api/albumplay` | Album des laufenden Titels ab Titel 1 |
| `POST /api/shuffle` | Zufallswiedergabe umschalten |
| `POST /api/switch` | Umschalter Plex ↔ Spotify |
| `POST /api/playlist?id=…` | Playlist starten |
| `POST /api/favorite?id=…&on=1` | Favorit setzen oder entfernen |
| `GET /setup` | Status der Quellen |

Alle `POST`-Befehle verlangen den Kopf `X-NowPlaying: 1`; so kann eine fremde Webseite sie im Browser nicht ungefragt auslösen. Eine Anmeldung gibt es nicht: Wer im Heimnetz ist, kann die Befehle senden.

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
| Spotify oder Caldera stumm | Spielt der andere noch? Der Server löst das nach etwa 10 s selbst; sonst pausieren und 5 s warten. WiiM auf optischem Eingang? |
| Umschalter fehlt am Tablet | `/setup`: „Spotify-Steuerung: eingerichtet“? `SPOTIFY_CLIENT_ID` gesetzt, `spotify.json` vorhanden und für den Dienst lesbar? |
| „Zu Spotify“ ohne Wirkung | Das Konto kennt „PlexAmpPi“ nicht: in der Spotify-App einmal auswählen. Liegt `/var/lib/raspotify/credentials.json` vor? |
| Raspotify startet immer wieder neu | `journalctl -u raspotify`: „Device … Busy“ heißt, die Soundkarte war belegt (Caldera spielte oder hielt sie noch). |
| Funken-Knopf ausgegraut | Der Titel hat in Plex keine Klanganalyse; im Plex-Server anstoßen. |
