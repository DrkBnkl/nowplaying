#!/bin/bash
# Installs Now Playing on the Raspberry Pi:
#   - spectrum tap (ALSA device "nowplaying") + service nowplaying-spectrum (port 8090)
#   - display server nowplaying (port 8080), the tablet connects here
#   - Spotify Connect "PlexAmpPi" (Raspotify), plays through the same tap
#
# Usage:  sudo ./install.sh                 everything
#         sudo ./install.sh --no-spotify    without Spotify Connect
#         sudo CARD=sndrpihifiberry ./install.sh   (specify the sound card yourself)
set -e
cd "$(dirname "$0")"
[ "$(id -u)" = 0 ] || { echo "Please run with sudo."; exit 1; }

SPOTIFY=1
SPOTIFY_NAME=${SPOTIFY_NAME:-PlexAmpPi}
for a in "$@"; do
  case "$a" in
    --no-spotify) SPOTIFY=0 ;;
    *) echo "Unknown option: $a"; exit 1 ;;
  esac
done

# --- Find the sound card ---
if [ -z "$CARD" ]; then
  CARD=$(awk -F'[][]' 'tolower($0) ~ /hifiberry/ {gsub(/ /,"",$2); print $2; exit}' /proc/asound/cards)
fi
if [ -z "$CARD" ]; then
  echo "No HiFiBerry card found. Available cards:"
  cat /proc/asound/cards
  echo "Run it like this:  sudo CARD=<name in square brackets> ./install.sh"
  exit 1
fi
echo "Sound card: $CARD"

# --- Packages and programs ---
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
  else install -m 600 nowplaying.default.example /etc/default/nowplaying; fi
fi

# --- ALSA: tap device "nowplaying" as the default device ---
CONF=/etc/asound.conf
[ -f "$CONF" ] && [ ! -f "$CONF.vor-nowplaying" ] && cp "$CONF" "$CONF.vor-nowplaying"
touch "$CONF"
sed -i '/# >>> nowplaying-spectrum/,/# <<< nowplaying-spectrum/d' "$CONF"
cat >> "$CONF" <<EOF
# >>> nowplaying-spectrum (managed by install.sh/uninstall.sh)
pcm.nowplaying {
  type file
  slave.pcm "hw:CARD=$CARD,DEV=0"
  file "|/usr/local/bin/np-spectrum-feed"
  format "wav"
  hint { show on description "Now Playing (HiFiBerry + spectrum)" }
}
pcm.!default { type plug slave.pcm "nowplaying" }
ctl.!default { type hw card "$CARD" }
# <<< nowplaying-spectrum
EOF

# --- Spotify Connect (Raspotify) ---
set_conf() {  # set key [value] in /etc/raspotify/conf (no value = flag on)
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
  # Volume is controlled on the WiiM (as with Caldera): output the full signal
  set_conf LIBRESPOT_VOLUME_CTRL "fixed"
  set_conf LIBRESPOT_INITIAL_VOLUME "100"
  # Loudness normalisation as with Caldera
  set_conf LIBRESPOT_ENABLE_VOLUME_NORMALISATION
  # Match Spotify's loudness to Caldera. On paper (Spotify -14 LUFS, Caldera with Plex
  # loudness analysis about -19 LUFS) that would be -5 dB; by ear that was too quiet, hence -2 dB.
  # Assumes Caldera's volume is at 100. To adjust: SPOTIFY_PREGAIN=-4 ./install.sh
  # or LIBRESPOT_NORMALISATION_PREGAIN in /etc/raspotify/conf (-10 to 10), then restart raspotify.
  set_conf LIBRESPOT_NORMALISATION_PREGAIN "${SPOTIFY_PREGAIN:--2.0}"
  set_conf LIBRESPOT_ONEVENT "/usr/local/bin/np-librespot-event"
  # Remember the login: after "$SPOTIFY_NAME" has been selected once in the Spotify app, librespot
  # logs in to the account by itself on every start. Only then can the tablet's switcher find the device.
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

# --- Spotify login (created by np-spotify-login) into the service's state directory ---
STATE=/var/lib/private/nowplaying
if [ -f spotify.json ] && [ -d "$STATE" ]; then
  install -m 600 -o "$(stat -c %u "$STATE")" -g "$(stat -c %g "$STATE")" spotify.json "$STATE/spotify.json"
  systemctl restart nowplaying
fi

IP=$(hostname -I | awk '{print $1}')
echo
echo "Done."
echo "  Display:         http://$IP:8080      (enter this address in the tablet app)"
echo "  Status:          http://$IP:8080/setup"
echo "  Spectrum:        curl http://$IP:8090/status"
[ "$SPOTIFY" = 1 ] && echo "  Spotify Connect: \"$SPOTIFY_NAME\" shows up as a speaker in the Spotify app."
echo "Restart Caldera so it uses the new default device:"
echo "  systemctl --user restart caldera-music   (as the Caldera user)"
echo "To undo:  sudo ./uninstall.sh"
# Existing settings are never overwritten. If DELAY_MS differs from the template
# (e.g. still the earlier value 40), point it out: otherwise the old value goes unnoticed.
delay() { sed -n 's/^DELAY_MS=//p' "$1" | tail -n 1; }
OLD=$(delay /etc/default/nowplaying-spectrum)
NEW=$(delay nowplaying-spectrum.default)
if [ -n "$OLD" ] && [ "$OLD" != "$NEW" ]; then
  echo
  echo "Note: /etc/default/nowplaying-spectrum has DELAY_MS=$OLD, the template has DELAY_MS=$NEW."
  echo "  Change it there if needed, then: sudo systemctl restart nowplaying-spectrum"
fi
