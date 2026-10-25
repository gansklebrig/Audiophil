# Equalizer (Android)

Systemweiter Equalizer im Schwarz-Gold-Design – wahlweise als Band-EQ oder parametrischer EQ,
mit eigenen Profilen, Hörtest, Lautstärke-Boost, Limiter, Balance, Lautheitsausgleich, Nachtmodus,
Kopfhörer-Automatik, AutoEq-Import, Live-Spektrum und Homescreen-Widget.

## APK bauen
1. Android Studio installieren: https://developer.android.com/studio
2. Ordner `EqualizerApp` öffnen (File → Open – den Ordner, nicht eine einzelne Datei). Gradle-Sync abwarten.
3. Handy per USB verbinden (USB-Debugging an) und ▶ Run drücken –
   oder Build → Build APK(s) und die APK unter `app/build/outputs/apk/` aufs Handy kopieren.

## Funktionen

### Equalizer-Art
- **Band-EQ**: die festen Bänder des Handys (meist 5), je mit −/+ in 0,1-dB-Schritten, plus Bass-Boost.
- **Parametrisch** (ab Android 9): bis zu 20 frei setzbare Filter – Glocke, Low-/High-Shelf, Tief-/Hochpass –
  jeweils mit Frequenz, Verstärkung und Güte (Q). Bedienung über die goldene Klangkurve
  (Punkt ziehen, zwei Finger für Q) oder exakt über Regler, −/+ und Zahleneingabe.
  Technik: Die exakte Filterkurve wird berechnet und auf 64 feine Bänder von Androids
  DynamicsProcessing verteilt.

- **Getrennt für links und rechts**: Jeder Filter wirkt wahlweise auf beide Seiten, nur links oder nur rechts.
  Die Klangkurve zeigt dann zwei Linien (links gold, rechts hell gestrichelt).

### Bedienung
- **Rückgängig / Wiederholen** (↶ ↷) für alle Klangänderungen; ein Regler-Zug zählt als ein Schritt.
- **Vorher / Nachher**: Knopf gedrückt halten → Klangfärbung kurz aus. Lautstärke-Boost, Balance und Limiter
  bleiben dabei an, damit der Vergleich nicht durch einen Lautstärkesprung verfälscht wird.
- **Live-Spektrum**: goldene Balken hinter der Klangkurve (bzw. als Streifen im Band-EQ). Braucht die
  Mikrofon-Berechtigung, weil Android die Klanganalyse daran koppelt – aufgenommen wird nichts.

### Hörprofil (Hörtest, ab Android 9)
- Misst in ca. 4 Minuten je Ohr bei 9 Frequenzen (125 Hz – 8 kHz), ab welchem Pegel ein Ton hörbar ist,
  plus eine Kontrollmessung. Der Equalizer wird währenddessen automatisch umgangen.
- Auswertung: Abzug der normalen Hörschwelle (ISO 226), Bezug auf den eigenen Median, Ausgleich der
  halben Differenz („Halbe-Verstärkung-Regel“), begrenzt auf −6 … +12 dB, als Glockenfilter je Ohr.
- Wird im Profil gespeichert (aktuelles oder alle ★-Profile); Stärke 0–150 %, abschaltbar.
- Kein Ersatz für einen ärztlichen Hörtest.

### Klangautomatik (ab Android 9)
- **Lautheitsausgleich**: hebt bei leiser Handy-Lautstärke Bass (bis +12 dB) und Höhen (bis +4 dB) an –
  je leiser, desto mehr; bei voller Lautstärke wirkungslos. Folgt der Lautstärke automatisch.
- **Nachtmodus**: Breitband-Kompressor – leise Stellen lauter, Spitzen leiser.

### Kopfhörer-Korrektur (AutoEq)
- „AutoEq durchsuchen“: über 8.800 gemessene Kopfhörer; die Korrektur wird als ★-Profil angelegt.
- „Aus Datei laden“: ParametricEQ.txt im Equalizer-APO-Format (AutoEq, Wavelet-Export u. a.).
- AutoEq senkt meist die Vorverstärkung (z. B. −5 dB), damit nichts übersteuert – mit dem
  Lautstärke-Boost wieder ausgleichen.

### Lautstärke
- **Lautstärke-Boost** bis +15 dB, auf 0,01 dB genau (Regler, −/+ mit wählbarer Schrittweite, Zahleneingabe).
- **Limiter** (ab Android 9): fängt Spitzen ab, damit es bei hohem Boost nicht knackt. Grenze einstellbar.
- **Balance** links/rechts (ab Android 9).

### Profile
- „Speichern als …“, Umbenennen, Löschen. Änderungen an einem ★-Profil werden automatisch gespeichert.
- Ein Profil enthält alles: EQ-Art, Bänder, Filter, Boost, Limiter, Balance.
- Export/Import als JSON-Datei (z. B. für ein neues Handy).

### Kopfhörer-Automatik
- Jedem Bluetooth-/USB-Kopfhörer (oder „Kabelkopfhörer“) ein ★-Profil zuordnen – beim Verbinden
  wird es automatisch aktiviert. Zusätzlich ein Profil „für alle anderen Geräte“.

### Schnellzugriff & Benachrichtigung
- **Homescreen-Widget** (4×2): Ein/Aus, Profil vor/zurück, Lautstärke-Boost ±0,5 dB.
- Schnelleinstellungs-Kacheln „Equalizer“ (an/aus) und „EQ-Profil“ (nächstes Profil).
- Benachrichtigung mit „Nächstes Profil“ und „Ausschalten“.
- Statusleisten-Symbol abschaltbar. Android verlangt für Hintergrund-Apps eine Benachrichtigung;
  ganz ausblenden geht über die Android-Benachrichtigungseinstellungen (der EQ läuft weiter).

## Wie andere Apps beeinflusst werden
- **Globaler Modus**: Effekt auf die gesamte Audioausgabe (Session 0). Funktioniert nicht auf jedem Gerät.
- **App-Modus**: Musik-Apps (Spotify, YouTube Music, Poweramp, VLC …) melden ihre Audio-Session,
  wenn in der App der Equalizer eingeschaltet und diese App gewählt wird.
- Akku-Optimierung für die App ausschalten, damit Android den Dienst nicht beendet.

## Aufbau des Codes (`app/src/main/java/de/eq/app`)
| Datei | Inhalt |
|---|---|
| `Model.kt` | Datenmodell (EqState, Filter, Profile), Filtermathematik (Biquad, RBJ-Cookbook), Hörtest-Auswertung |
| `Prefs.kt` | Speichern von Zustand, Profilen, Geräte-Zuordnungen, Export/Import |
| `EqService.kt` | Hintergrund-Dienst: Effekte je Session, Kopfhörer-Erkennung, Benachrichtigung |
| `MainActivity.kt` | Oberfläche |
| `CurveView.kt` | Interaktive Klangkurve |
| `Ui.kt` | Gestaltung (Schwarz-Gold) und Bedien-Bausteine |
| `AutoEq.kt` | AutoEq-Datenbank und Einlesen parametrischer EQ-Dateien |
| `AudioDevices.kt` | Erkennung des Ausgabegeräts |
| `Tiles.kt` | Schnelleinstellungs-Kacheln |
| `Widget.kt` | Homescreen-Widget und Einschalt-Hilfe |
| `HearingTestActivity.kt` | Hörtest: Tonerzeugung, Ablauf, Ergebnisgrafik |
| `Spectrum.kt` | Live-Spektrum (Visualizer-Analyse und Darstellung) |
| `Receivers.kt` | Session-Meldungen von Musik-Apps, Autostart nach Neustart |
