#!/bin/bash
# Installiert Now Playing auf dem Raspberry:
#   - Spektrum-Abzweig (ALSA-Geraet "nowplaying") + Dienst nowplaying-spectrum (Port 8090)
#   - Anzeige-Server nowplaying (Port 8080), das Tablet verbindet sich hierhin
#   - Spotify Connect "PlexAmpPi" (Raspotify), spielt ueber denselben Abzweig
#
# Aufruf:  sudo ./install.sh                 alles
#          sudo ./install.sh --no-spotify    ohne Spotify Connect
#          sudo CARD=sndrpihifiberry ./install.sh   (Soundkarte selbst angeben)
set -e
cd "$(dirname "$0")"
[ "$(id -u)" = 0 ] || { echo "Bitte mit sudo ausfuehren."; exit 1; }

SPOTIFY=1
SPOTIFY_NAME=${SPOTIFY_NAME:-PlexAmpPi}
for a in "$@"; do
  case "$a" in
    --no-spotify) SPOTIFY=0 ;;
    *) echo "Unbekannte Option: $a"; exit 1 ;;
  esac
done

# --- Soundkarte finden ---
if [ -z "$CARD" ]; then
  CARD=$(awk -F'[][]' 'tolower($0) ~ /hifiberry/ {gsub(/ /,"",$2); print $2; exit}' /proc/asound/cards)
fi
if [ -z "$CARD" ]; then
  echo "Keine HiFiBerry-Karte gefunden. Vorhandene Karten:"
  cat /proc/asound/cards
  echo "Bitte so aufrufen:  sudo CARD=<Name in eckigen Klammern> ./install.sh"
  exit 1
fi
echo "Soundkarte: $CARD"

# --- Pakete und Programme ---
apt-get install -y python3-numpy
install -m 755 np-spectrum-feed     /usr/local/bin/np-spectrum-feed
install -m 755 np-spectrum-server   /usr/local/bin/np-spectrum-server
install -m 755 np-server            /usr/local/bin/np-server
install -m 755 np-librespot-event   /usr/local/bin/np-librespot-event
install -d -m 755 /usr/local/share/nowplaying
install -m 644 index.html           /usr/local/share/nowplaying/index.html
install -m 644 nowplaying-spectrum.service /etc/systemd/system/nowplaying-spectrum.service
install -m 644 nowplaying.service          /etc/systemd/system/nowplaying.service
[ -f /etc/default/nowplaying-spectrum ] || install -m 644 nowplaying-spectrum.default /etc/default/nowplaying-spectrum
if [ ! -f /etc/default/nowplaying ]; then
  if [ -f nowplaying.env ]; then install -m 600 nowplaying.env /etc/default/nowplaying
  else install -m 600 nowplaying.default.beispiel /etc/default/nowplaying; fi
fi

# --- ALSA: Abzweig-Geraet "nowplaying" als Standardgeraet ---
CONF=/etc/asound.conf
[ -f "$CONF" ] && [ ! -f "$CONF.vor-nowplaying" ] && cp "$CONF" "$CONF.vor-nowplaying"
touch "$CONF"
sed -i '/# >>> nowplaying-spectrum/,/# <<< nowplaying-spectrum/d' "$CONF"
cat >> "$CONF" <<EOF
# >>> nowplaying-spectrum (wird von install.sh/uninstall.sh verwaltet)
pcm.nowplaying {
  type file
  slave.pcm "hw:CARD=$CARD,DEV=0"
  file "|/usr/local/bin/np-spectrum-feed"
  format "wav"
  hint { show on description "Now Playing (HiFiBerry + Spektrum)" }
}
pcm.!default { type plug slave.pcm "nowplaying" }
ctl.!default { type hw card "$CARD" }
# <<< nowplaying-spectrum
EOF

# --- Spotify Connect (Raspotify) ---
set_conf() {  # Schluessel [Wert] in /etc/raspotify/conf setzen (ohne Wert = Schalter an)
  local f=/etc/raspotify/conf key=$1 line
  if [ $# -ge 2 ]; then line="$key=\"$2\""; else line="$key="; fi
  sed -i "/^#\?$key=/d" "$f"
  echo "$line" >> "$f"
}
if [ "$SPOTIFY" = 1 ]; then
  if ! dpkg -s raspotify >/dev/null 2>&1; then
    curl -sSfL https://dtcooper.github.io/raspotify/key.asc -o /usr/share/keyrings/raspotify_key.asc
    chmod 644 /usr/share/keyrings/raspotify_key.asc
    echo "deb [signed-by=/usr/share/keyrings/raspotify_key.asc] https://dtcooper.github.io/raspotify raspotify main" \
      > /etc/apt/sources.list.d/raspotify.list
    apt-get update
    apt-get install -y raspotify
  fi
  [ -f /etc/raspotify/conf.vor-nowplaying ] || cp /etc/raspotify/conf /etc/raspotify/conf.vor-nowplaying
  set_conf LIBRESPOT_NAME "$SPOTIFY_NAME"
  set_conf LIBRESPOT_DEVICE_TYPE "speaker"
  set_conf LIBRESPOT_BACKEND "alsa"
  set_conf LIBRESPOT_DEVICE "default"
  set_conf LIBRESPOT_BITRATE "320"
  # Lautstaerke wird am WiiM geregelt (wie bei Caldera): volles Signal ausgeben
  set_conf LIBRESPOT_VOLUME_CTRL "fixed"
  set_conf LIBRESPOT_INITIAL_VOLUME "100"
  # Lautheitsangleichung wie bei Caldera
  set_conf LIBRESPOT_ENABLE_VOLUME_NORMALISATION
  # Spotify gegenueber Caldera angleichen. Rechnerisch (Spotify -14 LUFS, Caldera mit
  # Plex-Analyse etwa -19 LUFS) waeren es -5 dB; nach Gehoer war das zu leise, deshalb -2 dB.
  # Voraussetzung: Calderas Lautstaerke steht auf 100. Anpassen: SPOTIFY_PREGAIN=-4 ./install.sh
  # oder LIBRESPOT_NORMALISATION_PREGAIN in /etc/raspotify/conf (-10 bis 10), dann raspotify neu starten.
  set_conf LIBRESPOT_NORMALISATION_PREGAIN "${SPOTIFY_PREGAIN:--2.0}"
  set_conf LIBRESPOT_ONEVENT "/usr/local/bin/np-librespot-event"
  # Anmeldung merken: nach einmaliger Auswahl von "$SPOTIFY_NAME" in der Spotify-App meldet sich
  # librespot bei jedem Start selbst am Konto an. Nur dann findet der Umschalter am Tablet das Geraet.
  sed -i '/^LIBRESPOT_DISABLE_CREDENTIAL_CACHE=/d' /etc/raspotify/conf
  set_conf LIBRESPOT_SYSTEM_CACHE "/var/lib/raspotify"
  systemctl enable raspotify
  systemctl restart raspotify
fi

systemctl daemon-reload
for s in nowplaying-spectrum nowplaying; do
  systemctl enable "$s"
  systemctl restart "$s"
done

# --- Spotify-Anmeldung (von np-spotify-login erzeugt) ins Zustandsverzeichnis des Dienstes ---
STATE=/var/lib/private/nowplaying
if [ -f spotify.json ] && [ -d "$STATE" ]; then
  install -m 600 -o "$(stat -c %u "$STATE")" -g "$(stat -c %g "$STATE")" spotify.json "$STATE/spotify.json"
  systemctl restart nowplaying
fi

IP=$(hostname -I | awk '{print $1}')
echo
echo "Fertig."
echo "  Anzeige:        http://$IP:8080      (diese Adresse in der Tablet-App eintragen)"
echo "  Status:         http://$IP:8080/setup"
echo "  Spektrum:       curl http://$IP:8090/status"
[ "$SPOTIFY" = 1 ] && echo "  Spotify Connect: \"$SPOTIFY_NAME\" erscheint in der Spotify-App als Lautsprecher."
echo "Caldera neu starten, damit es das neue Standardgeraet nutzt:"
echo "  systemctl --user restart caldera-music   (als Caldera-Benutzer)"
echo "Rueckgaengig machen:  sudo ./uninstall.sh"
