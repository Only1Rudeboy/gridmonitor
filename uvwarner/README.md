# ☀️ UV-Warner — Android-App

Warnt, sobald der UV-Index am eigenen Standort einen einstellbaren Wert
erreicht (Standard: **4**). Kein Konto, kein API-Schlüssel, kein eigener Server.

**[📲 UV-Warner.apk herunterladen](https://github.com/Only1Rudeboy/gridmonitor/releases/download/uv-v1.2/UV-Warner.apk)**
— Installation aus unbekannter Quelle bestätigen. Ab Android 8.

## Oberfläche

Zwei Bildschirme: vorne nur der Zustand, alles Einstellbare dahinter.

- **Start** — Ort, großer Ring mit dem aktuellen Wert und der WHO-Kategorie,
  eine Zeile Empfehlung, Tages- und Morgen-Zusammenfassung, Balkenverlauf der
  nächsten 12 Stunden. Zum Aktualisieren nach unten ziehen.
- **Einstellungen** — Warnungen an/aus, Schwelle per Schieberegler, Prüf­intervall,
  Akku- und Hintergrund-Standort-Hinweise.

Material 3, hell und dunkel; ab Android 12 übernimmt die App die Systemfarben.

## Funktionen

- **Aktueller UV-Index** für den per GPS/Netz ermittelten Standort, mit
  WHO-Kategorie (niedrig · mäßig · hoch · sehr hoch · extrem) und Schutzhinweis
- **Warnung ab Schwellwert** — Standard 4, einstellbar von 1 bis 11
- **Vorwarnung** bis zu drei Stunden bevor die Schwelle erreicht wird
- **Tagesübersicht:** von wann bis wann die Schwelle überschritten wird,
  Höchstwert und Uhrzeit
- **Morgen-Vorschau** mit Höchstwert und ab wann die Schwelle fällt
- **Balkenverlauf** der nächsten 12 Stunden
- **Hintergrundprüfung** über WorkManager, Intervall wählbar
  (15 / 30 / 60 / 180 Minuten), übersteht Neustarts
- **Offline brauchbar:** der letzte Stand wird gespeichert und beim Öffnen
  sofort angezeigt — inklusive Stundenverlauf
- Keine Google-Play-Dienste nötig (reiner `LocationManager`)

Sparsam mit Akku und Datenvolumen: nachts wird gar nicht erst abgerufen, wenn
die gespeicherte Vorhersage für die nächsten Stunden ohnehin 0 sagt. Beim
Öffnen der App wird nur nachgeladen, wenn der letzte Abruf über 10 Minuten
zurückliegt. Ein fehlgeschlagener Abruf wird einmal sofort wiederholt.

Gewarnt wird jeweils **einmal pro Überschreitung** — erst wenn der Wert wieder
unter die Schwelle fällt, ist die nächste Warnung möglich. Die Vorwarnung kommt
höchstens einmal pro Tag.

## Datenquelle

[Open-Meteo](https://open-meteo.com) (`uv_index`, Basis CAMS/ECMWF) — frei
nutzbar ohne Schlüssel. Es werden ausschließlich Breiten- und Längengrad an
Open-Meteo übertragen; die Position bleibt sonst auf dem Gerät.

## Berechtigungen

| Berechtigung | Wofür |
|---|---|
| Standort (grob/genau) | UV-Index für die aktuelle Position |
| Standort im Hintergrund | *optional* — sonst nutzt die Hintergrundprüfung die zuletzt in der App ermittelte Position |
| Benachrichtigungen | für die Warnung selbst (Android 13+) |
| Internet | Abruf der UV-Daten |
| Neustart empfangen | Prüfung nach Reboot wieder aktivieren |

## Bauen und testen

```bash
cd uvwarner
./gradlew testDebugUnitTest      # Warnlogik und Auswertung der API-Antwort
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

Die Entscheidung „wird gewarnt?" steckt in [`UvWarningLogic`](app/src/main/java/at/osmovoltaik/uvwarner/UvWarningLogic.kt)
— bewusst ohne Android-Abhängigkeiten, damit sie als reiner Unit-Test prüfbar
ist (Schwellenüberschreitung, Vorwarnfenster, Doppelmeldungen, Nachtabschaltung).

Voraussetzungen: JDK 17, Android SDK (compileSdk 35). minSdk 26 (Android 8).

Ohne hinterlegten Signaturschlüssel wird mit dem Debug-Schlüssel signiert —
installierbar, aber bei jedem Rechner/Build eine andere Signatur.

## APK aus GitHub Actions

Der Workflow [`uvwarner.yml`](../.github/workflows/uvwarner.yml) baut die APK bei
jedem Push auf `uvwarner/**` und legt sie als Artefakt **UV-Warner-APK** ab.

Ein GitHub-Release mit `UV-Warner.apk` entsteht auf zwei Wegen:

- **Tag** `uv-v1.1` pushen — Release unter genau diesem Tag, oder
- **`[release]`** in der Commit-Nachricht — der Tag wird aus `versionName`
  gebildet (`uv-v1.1`) und von der Action selbst angelegt.

### Eigener Signaturschlüssel (empfohlen für Updates)

Damit die App über bestehende Installationen aktualisiert werden kann, muss die
Signatur gleich bleiben. Dafür einmalig einen Schlüssel erzeugen …

```bash
keytool -genkeypair -v -keystore uvwarner.jks -alias uvwarner \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 uvwarner.jks   # Ausgabe kopieren
```

… und als Repository-Secrets hinterlegen:

| Secret | Inhalt |
|---|---|
| `UV_KEYSTORE_BASE64` | Base64 der `.jks`-Datei |
| `UV_KEYSTORE_PASSWORD` | Keystore-Passwort |
| `UV_KEY_ALIAS` | `uvwarner` |
| `UV_KEY_PASSWORD` | Schlüssel-Passwort |

Sind die Secrets gesetzt, signiert der Build automatisch damit.

## Zuverlässigkeit der Warnungen

Android verzögert Hintergrundarbeit im Energiesparmodus. Für pünktliche
Warnungen sollte die App in den Systemeinstellungen von der Akku-Optimierung
ausgenommen werden — der Knopf dafür ist unten in der App.

---

*Angaben ohne Gewähr — die App ersetzt keine amtliche UV-Warnung.*
