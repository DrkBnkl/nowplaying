#!/bin/bash
# Removes Now Playing from the Raspberry Pi (server, spectrum tap, optionally Raspotify).
# Usage:  sudo ./uninstall.sh              Raspotify stays, but gets its previous configuration back
#         sudo ./uninstall.sh --spotify    also uninstall Raspotify
set -e
[ "$(id -u)" = 0 ] || { echo "Please run with sudo."; exit 1; }

for s in nowplaying nowplaying-spectrum; do
  systemctl disable --now "$s" 2>/dev/null || true
done
rm -f /etc/systemd/system/nowplaying.service /etc/systemd/system/nowplaying-spectrum.service \
      /usr/local/bin/np-server /usr/local/bin/np-spectrum-feed /usr/local/bin/np-spectrum-server \
      /usr/local/bin/np-librespot-event
rm -rf /usr/local/share/nowplaying
systemctl daemon-reload
[ -f /etc/asound.conf ] && sed -i '/# >>> nowplaying-spectrum/,/# <<< nowplaying-spectrum/d' /etc/asound.conf

if [ "$1" = "--spotify" ]; then
  apt-get remove -y raspotify || true
  rm -f /etc/apt/sources.list.d/raspotify.list /usr/share/keyrings/raspotify_key.asc
elif [ -f /etc/raspotify/conf.vor-nowplaying ]; then
  cp /etc/raspotify/conf.vor-nowplaying /etc/raspotify/conf
  systemctl restart raspotify 2>/dev/null || true
fi

echo "Removed. The settings in /etc/default/nowplaying and /etc/default/nowplaying-spectrum"
echo "are kept (you can delete them). Restart Caldera afterwards."
