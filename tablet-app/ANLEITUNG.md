# Tablet-App „Now Playing“ für das Lenovo Yoga

Die App zeigt die Seite des Now-Playing-Servers auf dem Raspberry im Vollbild an, fest im Querformat. Sie schaltet das Display aus, sobald keine Musik mehr läuft, und wieder ein, wenn Musik startet. Nach einem Neustart des Tablets startet sie von selbst.

Sie läuft ab Android 5, auf dem Yoga also in jedem Fall. Die Android-Version findest du unter *Einstellungen → Über das Tablet*.

## 1. APK bauen

Hier gibt es drei Wege. Alle liefern dieselbe Datei `app-release.apk`.

### Weg A: GitHub baut die APK (ohne eigene Software)

Das Repository enthält den Workflow `.github/workflows/tablet-apk.yml`. Bei jeder Änderung unter `tablet-app/` baut GitHub die APK automatisch (etwa 3–5 Minuten); von Hand: Reiter **Actions** → „Tablet-APK bauen“ → *Run workflow*.
Den fertigen Lauf öffnen, unten unter *Artifacts* liegt **NowPlaying-APK**. Herunterladen und entpacken, darin steckt `app-release.apk`.

### Weg B: Android Studio am PC

Den Ordner `tablet-app` in Android Studio öffnen und *Build → Build App Bundle(s) / APK(s) → Build APK(s)* wählen.

### Weg C: Am Mac per Kommandozeile (so ist die installierte Version gebaut)

Voraussetzung: `brew install openjdk@17` und `brew install --cask android-commandlinetools android-platform-tools`, dann `sdkmanager "platforms;android-34" "build-tools;34.0.0"`. In `local.properties` steht `sdk.dir=/opt/homebrew/share/android-commandlinetools`.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleRelease
```

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Auf dem Yoga ist dafür USB-Debugging eingeschaltet (Entwickleroptionen).

## 2. Auf dem Tablet installieren

1. Die APK aufs Tablet bringen, per USB, Mail oder Cloud.
2. Antippen und „Installation aus unbekannten Quellen“ erlauben.
3. Die App starten und die Adresse des Raspberry eingeben: `http://192.168.178.68:8080`.
4. Lange auf den Bildschirm drücken, dann **„Display-Steuerung aktivieren“** und bestätigen. Erst dann geht das Display ohne Musik wirklich aus. Ohne diese Berechtigung wird es nur auf die geringste Helligkeit gedimmt.
5. In den Android-Einstellungen unter *Sicherheit → Bildschirmsperre* **„Keine“** wählen. Sonst steht beim Einschalten des Displays der Sperrbildschirm vor der Anzeige.
6. In den Android-Einstellungen unter *Akku* bzw. *Energie sparen* die App von der Akku-Optimierung ausnehmen, falls es diese Option gibt.

## Tipps für das Yoga

- Mit ausgeklapptem Standfuß quer hinstellen. Die App bleibt fest im Querformat, auch wenn das Tablet gedreht wird.
- Das Ladekabel dauerhaft angesteckt lassen. Weil die App CPU und WLAN wach hält, damit sie bei ausgeschaltetem Display den Musikstart bemerkt, würde der Akku sonst leerlaufen.
- Den Akku gelegentlich auf Aufblähen prüfen, etwa wenn sich das Gehäuse wölbt. Bei Dauerbetrieb am Netz kann das bei älteren Geräten vorkommen.
- Im WLAN-Menü unter *Erweitert* die Einstellung „WLAN im Ruhezustand aktiviert lassen“ auf **Immer** stellen, falls es diese Option gibt.
- Automatische Updates und Benachrichtigungen anderer Apps abschalten, damit nichts über die Anzeige springt.

## Bedienung

- **Lange drücken:** Einstellungen öffnen (Adresse, Display-Steuerung)
- **Zurück-Taste:** wird absichtlich ignoriert
- **Display von Hand geweckt, ohne Musik:** geht nach 1 Minute von selbst wieder aus (nach 5 Minuten, wenn gerade eine andere App oder das Einstellungsfenster offen ist)

## Updates

Neue Version bauen (Weg A, B oder C), in `app/build.gradle` `versionCode` erhöhen und einfach über die alte installieren. Weil der Signaturschlüssel im Projekt liegt (`keystore/`), ist keine Deinstallation nötig.

Hinweis: Der Schlüssel liegt mit einfachem Passwort im Projekt. Für eine private App ist das in Ordnung, das Repository sollte aber privat bleiben.
