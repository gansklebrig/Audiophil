# Equalizer (Android)

Systemweiter Equalizer im Schwarz-Gold-Design – wahlweise als Band-EQ oder parametrischer EQ,
mit eigenen Profilen, Hörtest, Boxen-Einmessung per Mikrofon, Lautstärke-Boost, Limiter, Balance,
Lautheitsausgleich, Nachtmodus, Kopfhörer-Automatik, AutoEq-Import, Live-Spektrum und Homescreen-Widget.

## Aufs Handy installieren (ohne Android Studio)
Bei jedem Push baut GitHub die App automatisch (`.github/workflows/apk.yml`).
1. Auf dem Handy **github.com/gansklebrig/Audiophil/releases/latest** öffnen (bei GitHub angemeldet, das Repo ist privat).
2. **Equalizer.apk** antippen, herunterladen, öffnen, „Installieren“.
   Beim ersten Mal fragt Android, ob der Browser Apps installieren darf → erlauben.
3. Neue Versionen werden genauso einfach drüberinstalliert – Profile und Einstellungen bleiben.

Signiert wird mit einem festen Schlüssel. Er liegt nur verschlüsselt im Repo (`keystore/equalizer.jks.enc`);
GitHub entschlüsselt ihn beim Bauen mit dem Secret `KEYSTORE_PASSPHRASE`.
Eine früher mit dem Android-Studio-Standardschlüssel installierte Version muss einmal deinstalliert werden
(vorher Profile → Exportieren, danach wieder importieren).

### Update direkt aus der App
Kachel **Update** auf der Startseite (oder Einstellungen → App-Update): sucht das neueste Release
in diesem öffentlichen Repo, zeigt die Neuerungen und installiert es nach Bestätigung.
Beim Öffnen prüft die App höchstens alle 6 Stunden von selbst und zeigt dann oben „Update ist da“.
Beim ersten Update fragt Android, ob der Equalizer Apps installieren darf → erlauben.

## Selbst bauen (Android Studio)
1. Android Studio installieren: https://developer.android.com/studio
2. Ordner öffnen (File → Open). Gradle-Sync abwarten.
3. Handy per USB verbinden (USB-Debugging an) und ▶ Run drücken.
   Ohne den entschlüsselten Schlüssel wird mit dem Debug-Schlüssel signiert – so eine Version lässt sich
   nicht über die GitHub-Version installieren (und umgekehrt).

## Oberfläche
- **Startseite**: Ein/Aus, Profil, Klangkurve bzw. Frequenzbänder mit ↶ ↷ und Vorher/Nachher,
  Lautstärke-Boost und sieben Kacheln, die jeweils den aktuellen Stand zeigen.
- **Untermenüs** (Kachel antippen, Zurück-Taste oder ‹ führt zurück):
  Equalizer (Art, Spektrum, Filter, Vorverstärkung) · Lautstärke (Schrittweite, Limiter, Balance,
  Lautheitsausgleich, Nachtmodus) · Hörprofil (Hörtest, Stärke) · Boxen einmessen (eigene Ansicht) ·
  Kopfhörer (AutoEq, Automatik) ·
  Profile (Speichern, Umbenennen, Export/Import, Zurücksetzen) · Einstellungen (Global-Modus,
  Akku, Benachrichtigung, Schnellzugriff).

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

### Boxen einmessen (ab Android 9)
- Über die verbundenen Boxen (Bluetooth oder Kabel) läuft rosa Rauschen; das Handy-Mikrofon nimmt es am
  Hörplatz auf. Vorher wird 2 s das Raumgeräusch gemessen. Pegelprüfung mit Live-Anzeige vor dem Start.
- 1 oder 3 Messpunkte (räumliche Mittelung). Der Equalizer wird während der Messung umgangen.
- Auswertung: Welch-Spektrum (32768-Punkte-FFT) von Aufnahme und Testsignal, 1/12-Oktav-Bänder,
  Glättung (Bass ungeglättet, Grundton 1/6, darüber 1/3 Oktave). Bluetooth-Verzögerung spielt keine Rolle.
- Zielkurven: neutral (−0,8 dB/Oktave, empfohlen), linear, mehr Bass.
- Korrektur: bis zu 10 Glockenfilter, Stärken per gewichteter kleinster Quadrate. Absenken bis −12 dB,
  Anheben höchstens +6 dB und nur breit, erst ab der doppelten Box-Untergrenze (schont Tieftöner),
  zu den Höhen hin auslaufend. Vorverstärkung so, dass nichts übersteuert. Stärke 0–100 % wählbar.
- Ergebnis wird ein eigenes ★-Profil und kann mit den Boxen verknüpft werden (Kopfhörer-Automatik).
- Grenzen: Handy-Mikrofone sind im tiefen Bass und in den obersten Höhen ungenau – deshalb wird dort
  zurückhaltend korrigiert.

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

### Profil je App
- Musik-Apps melden sich beim Abspielen und erscheinen unter „Kopfhörer & Apps“. Einer App ein ★-Profil
  zuordnen → es wird beim Abspielen geladen, danach geht es zurück zum Kopfhörer- bzw. vorigen Profil.

### Automatischer Spielraum
- Ragt die Gesamtkurve (EQ + Hörkorrektur + Lautheitsausgleich) über 0 dB, wird der Pegel davor genau
  um diesen Betrag gesenkt – keine Verzerrung. Lautstärke → Spielraum, abschaltbar.

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
| `RoomEq.kt` | Boxen einmessen: Testrauschen, FFT/Spektrum, Zielkurven, automatische Filterwahl |
| `RoomMeasureActivity.kt` | Boxen einmessen: Pegelprüfung, Aufnahme/Wiedergabe, Ergebnisgrafik, Profil |
| `Spectrum.kt` | Live-Spektrum (Visualizer-Analyse und Darstellung) |
| `Receivers.kt` | Session-Meldungen von Musik-Apps, Autostart nach Neustart |
