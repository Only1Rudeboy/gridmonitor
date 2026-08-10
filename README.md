# ⚡ Grid Monitor — Dynamische Strompreise für Österreich

Strompreis-Dashboard für dynamische Tarife (z. B. VKW Strom dynamisch) in
**15-Minuten-Auflösung** — als Web-App und Android-App. Keine Anmeldung,
keine Datensammlung, kein eigener Server nötig.

## 📱 Nutzen

- **Web-App (empfohlen, auch iPhone):** https://only1rudeboy.github.io/gridmonitor/
  — im Browser öffnen und über „App installieren" bzw. „Zum Home-Bildschirm
  hinzufügen" wie eine App verwenden. Funktioniert dank Offline-Speicher auch
  ohne Netz mit dem letzten Stand.
- **Android-App (APK):** [GridMonitor.apk herunterladen](https://github.com/Only1Rudeboy/gridmonitor/releases/latest/download/GridMonitor.apk)
  — Installation aus unbekannter Quelle bestätigen. Ab Android 8.

## ✨ Funktionen

- **Preis jetzt** — Bruttopreis in ct/kWh mit Bewertungsring (günstig/normal/teuer)
- **Bestes Ladefenster** — getrennt für heute und morgen, mit Ersparnis in Euro
- **Preisverlauf** heute & morgen — antippen zeigt jeden 15-Minuten-Slot
- **Stundenraster, nächste Slots, Preisbestandteile** (Börse · Aufschlag · MwSt · Netz)
- **Kosten-Rechner** — was kosten Waschgang, Geschirrspüler, Trockner, Laden
  jetzt vs. im besten Fenster
- **30-Tage-Historie** mit monatlichem Sparpotenzial
- **Eigener Tarif einstellbar** (⚙ Mein Tarif): Aufschlag, Netzentgelt, MwSt,
  Schwellen, Lademenge — der Bruttopreis wird immer aus deinem Tarif gerechnet
- **Benachrichtigung** (Android-App), wenn das günstigste Ladefenster beginnt
- **Warnung bei veralteten Daten**

## 🔧 Technik

Ein GitHub-Actions-Job holt die Day-Ahead-Preise mehrmals täglich
(ENTSO-E Transparency, Fallback Bundesnetzagentur SMARD.de via Energy-Charts,
CC BY 4.0) und veröffentlicht sie als statische JSON-Dateien über GitHub Pages
(`prices.json`, `history.json`) — dadurch skaliert die App ohne Serverkosten.

---
*Unterstützt von OSMOVOLTAIK e.U.*
