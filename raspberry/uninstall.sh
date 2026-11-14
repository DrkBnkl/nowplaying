#!/bin/bash
# Entfernt Now Playing vom Raspberry wieder (Server, Spektrum-Abzweig, optional Raspotify).
# Aufruf:  sudo ./uninstall.sh              Raspotify bleibt, bekommt aber seine alte Konfiguration zurueck
#          sudo ./uninstall.sh --spotify    Raspotify ebenfalls deinstallieren
set -e
[ "$(id -u)" = 0 ] || { echo "Bitte mit sudo ausfuehren."; exit 1; }

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

echo "Entfernt. Die Einstellungen in /etc/default/nowplaying und /etc/default/nowplaying-spectrum"
echo "bleiben liegen (koennen geloescht werden). Caldera danach neu starten."
