# Bluey für Android (eigenständig, mit Gemini)

Bluey ist ein reiner KI-Helfer auf dem Handy. Es wird **kein Mac und kein Desktop** gebraucht.

- **Freihändig:** Sag „Hey Bluey“ (oder „Hey Pinky“, „Hey Greeny“ …), dann wacht er auf. Du musst nichts tippen.
- **Er spricht Deutsch** (und antwortet in deiner Sprache, wenn du eine andere sprichst) mit einer süßen Stimme.
  Bluey und Greeny sind Jungs, Pinky ist ein Mädchen. Dazu erscheint seine Antwort als kleine Sprechblase.
- **Farben:** In den Einstellungen wählst du seine Farbe. Je nach Farbe heißt er anders, hört auf einen anderen Namen
  und hat eine andere Stimme:

  | Farbe | Name | Stimme |
  | --- | --- | --- |
  | Blau | Bluey | Junge (Puck) |
  | Pink | Pinky | Mädchen (Leda) |
  | Grün | Greeny | Junge (Fenrir) |
  | Lila | Purply | Mädchen (Aoede) |
  | Orange | Orangy | Mädchen (Kore) |

- **Er schläft mit Zzz:** Solange er nicht wach ist, hat er die Augen zu und kleine Z's steigen auf. Die Kamera ist dann aus.
- **Aufwecken:** einmal auf den Bildschirm tippen, oder „Hey <Name>“ sagen. Doppeltippen schickt ihn wieder schlafen.
  Tippst du ihn an, wenn er wach ist, hüpft er vergnügt.
- **Er sieht live:** Die Frontkamera folgt deinem Gesicht mit den Augen (Gesichtserkennung auf dem Handy). Gemini bekommt
  dazu alle halbe Sekunde ein kleines Bild (Live-Video), er sieht also dich und alles, was du ihm zeigst. Abschaltbar.
- **Er reagiert auf Bewegung:** Hältst du ihm etwas hin oder bewegt sich etwas vor der Kamera, schaut er hin, reißt die Augen
  auf und hüpft. Er blinzelt nur noch ab und zu.
- **Stimmen:** Im Menü „Stimmen“ (oder per Zuruf „Bluey, lerne meine Stimme“) lernt er die Stimmen der Leute zu Hause.
  Danach erkennt er, wer spricht, und nennt die Person beim Namen. Das ist eine einfache Erkennung auf dem Handy, kein
  Sicherheitsmerkmal. Gespeichert werden nur Zahlen, keine Aufnahmen.
- **Noch mehr:** Wetter am Standort des Handys („Wie wird das Wetter?“), Timer und Wecker stellen, Taschenlampe, Uhrzeit, sich Dinge merken („Merk dir …“, Menü „Notizen“),
  Witze, Rätsel und Schere-Stein-Papier mit der Kamera.
- **Recherche:** Für Aktuelles nutzt er die Google-Suche von Gemini.
- **App-Icon:** Bluey selbst.

## Neu: Weckwort, Duo, Gegenstände, Standort

- **Aufwecken per Zuruf (drei Modi, Einstellungen):**
  - **Gemini prüft** (Standard): Das Mikrofon bleibt im Schlaf lautlos offen (kein Piepton, keine Aufnahme nötig). Kurze Sprachfetzen
    (unter 2,5 Sekunden, höchstens einer alle paar Sekunden) schickt das Handy zur Prüfung an Gemini („Hat jemand ‚Hey Bluey‘ gesagt?“).
    Am genauesten. Im Menü „Weckwort“ kannst du es testen.
  - **Mein Weckwort:** Du nimmst „Hey Bluey“ im Menü „Weckwort“ drei- bis viermal auf. Alles bleibt auf dem Handy, ist aber ungenauer.
  - **Handy-Erkennung:** Die Spracherkennung des Handys hört mit. Piept auf vielen Handys.
- **Sprache:** Beim ersten Start wählst du die Sprache (16 zur Auswahl, später änderbar in den Einstellungen). Er spricht dann
  **nur** diese Sprache, auch wenn du eine andere sprichst.
- **Duo-Raum mit bis zu 4 Blueys:** Handys nebeneinander stellen, überall Bluetooth und Standort an. Auf einem Handy im Menü „Duo“
  „Raum öffnen“, auf den anderen (bis zu drei) „Raum beitreten“. Sie finden sich von selbst (Googles Nearby über Bluetooth, **kein
  Koppeln nötig**). Dann ein Thema wählen und „Gespräch starten“ (oder per Zuruf „Bluey, unterhalte dich mit den anderen über Pizza“).
  Die Blueys reden der Reihe nach, jeder mit seiner Stimme, und **das Gespräch läuft ohne Ende, bis du es beendest**. Hängt eins,
  wird sein Zug übersprungen. Dabei schauen sie zum Sprecher, nicken, Noten und Herzen fliegen hinüber, und die Worte erscheinen an
  der Seite des Sprechers (bei 2 Handys links oder rechts, bei mehr links, oben und rechts).
- **Er sieht Gegenstände:** Alles Neue im Bild, das nicht dein Gesicht ist (zum Beispiel ein Gegenstand, den du ihm hinhältst), verfolgt er
  mit den Augen, staunt und hüpft. Nach einem Moment bekommt Gemini den Hinweis, hinzusehen, und sagt kurz, was es ist.
- **Standort:** „Wo bin ich?“ und „Wie wird das Wetter?“ nutzen den Standort des Handys. Beim Aufwachen kennt er schon deinen Ort.
- **Keine Endlosschleife:** Wenn er etwas nicht kann, sagt er es einmal. Hört das Mikrofon seine eigene Stimme wieder, wird das erkannt
  und nicht beantwortet, und wiederholt er sich, wird die Wiederholung abgeschnitten.
- **Er sagt nie „Ich bin nur ein Sprachmodell“:** Solche Sätze werden erkannt und abgeschnitten, bevor man sie hört. Stattdessen
  sagt er kurz auf Deutsch, dass er das nicht kann.
- **Stimme laut genug:** Die Lautstärke-Regelung wirkt jetzt auf die Anruf-Lautstärke, und leise Sprache wird angehoben.

## Bauen

Mit Android Studio oder, ohne PC, über GitHub Actions (Anleitung im Chat): ZIP in ein GitHub-Repository hochladen,
`.github/workflows/build.yml` anlegen, die fertige APK unter Actions → Artifacts herunterladen.

Beim ersten Start Kamera und Mikrofon erlauben und den **Gemini-API-Schlüssel** einfügen
(kostenlos bei aistudio.google.com/apikey). Er wird verschlüsselt auf dem Handy gespeichert.

## Bedienung

- **„Hey <Name>“:** aufwecken. Er schläft von selbst wieder ein, wenn 5 Minuten niemand mit ihm gesprochen hat, oder wenn du „Tschüss“ sagst.
- **Tippen:** aufwecken. **Doppeltippen:** schlafen legen
- Lautsprecher-Knopf oben rechts: Lautstärke, „Hallo sagen“
- Kachel-Knopf daneben: Verlauf, Einstellungen (Farbe, Zuruf, Kamera, Schlüssel), Stimmen, Notizen
- Kamera-Symbol unten links: grau = Kamera an, grün = er sieht dein Gesicht

## Technik

`Agent.kt` (Persönlichkeit, Stimme, Tools, Recherche) · `LiveVoice.kt` (Gemini Live per WebSocket, Mikrofon) ·
`Player.kt` (seine Stimme) · `WakeWord.kt` („Hey Bluey“) · `CameraWatcher.kt` (Kamera, Gesicht) ·
`Palette.kt` (Farben und Namen) · `FaceView.kt` (Gesicht) · `Ui.kt`, `MainActivity.kt` · `SessionStore.kt` (Verlauf) · `Settings.kt`

Modelle (in `Agent.kt` änderbar): Live `gemini-3.8-live`, Recherche `gemini-3.8-flash`.

## Datenschutz

- Gesichtserkennung bleibt auf dem Handy.
- Solange er wach ist, geht dein Ton an Google (Gemini Live). Mit „Er sieht mich“ an auch ein kleines Kamerabild pro Sekunde.
- Stimmen- und Bewegungserkennung laufen nur auf dem Handy.
- Im Schlaf hört das Handy mit Androids Spracherkennung auf „Hey <Name>“. Je nach Handy läuft das auf dem Gerät oder über Google.
- Die Kamera läuft nur, solange er wach ist und die App offen ist.

## Wenn er mal hängt

Er überwacht sich jetzt selbst: Kommt keine Verbindung zustande, hängt eine Antwort, ein Werkzeug (z. B. Recherche) oder das
Mikrofon, setzt er sich zurück. Geht die Verbindung verloren, versucht er es mehrmals neu und sagt Bescheid, wenn es nicht klappt.
Hilft das nicht: **Doppeltippen** setzt ihn immer zurück (er schläft ein), dann wecken.

## Bekannte Grenzen

- Das Aufwecken per Zuruf hängt an der Spracherkennung deines Handys. Auf manchen Handys piept sie bei jedem Neustart.
  Dann den Zuruf in den Einstellungen ausschalten und doppeltippen.
- Der Piepton der Spracherkennung wird im Schlaf stummgeschaltet (Benachrichtigungs-, System- und Medienton). Bei manchen Handys
  klappt das nicht. Dann den Zuruf in den Einstellungen ausschalten und tippen.
- Das Wetter braucht die Standort-Erlaubnis und kommt von open-meteo.com.
- Das eigene Weckwort ist eine einfache Mustererkennung. Es kennt nur die Stimmen, die es aufgenommen hat, und kann sich irren.
- Der Modus „Gemini prüft“ schickt kurze Sprachfetzen im Schlaf an Google. Wer das nicht will, wählt „Mein Weckwort“.
- Duo braucht die Google Play-Dienste. Die Blueys hören sich nicht gegenseitig, sie schicken sich die Sätze als Text.
- Er hört dir nicht zu, solange er selbst spricht (sonst würde er sich selbst antworten).

## Lizenz

Unlicense, wie das Hauptprojekt. Die Schriften (Fredoka, IBM Plex) stehen unter der SIL OFL, siehe `licenses/`.
