# Datenschutzerklärung (Vorlage) für Greeny

> Das ist eine Vorlage, keine Rechtsberatung. Ergänze Name und Anschrift des Verantwortlichen, prüfe den Text und lass ihn
> bei Bedarf rechtlich prüfen, bevor du die App anbietest.

**Verantwortlicher:** [Name, Anschrift, E-Mail]

## Was die App tut
Greeny ist ein KI-Helfer. Er hört über das Mikrofon zu, sieht über die Frontkamera, nutzt den Standort und antwortet mit Sprache.

## Welche Daten verarbeitet werden
| Daten | Wozu | Wohin |
| --- | --- | --- |
| Ton vom Mikrofon | Gespräch mit der KI | an Google (Gemini API), solange Greeny wach ist |
| Kurze Sprachfetzen im Schlaf | Erkennen des Weckworts (Modus „Gemini prüft“) | an Google (Gemini API), höchstens alle paar Sekunden |
| Kamerabilder (etwa 2 pro Sekunde, klein) | Greeny sieht den Nutzer (wenn „Er sieht mich“ an ist) | an Google (Gemini API) |
| Gesichts-, Gegenstands- und Stimmenerkennung | Blick folgen, Reaktionen, Sprecher erkennen | nur auf dem Handy |
| Ungefährer Standort | Wetter, Orte in der Nähe, „Wo bin ich?“ | Suchanfragen an Google (Wetter) und OpenStreetMap/Overpass (Orte) |
| Gemini-Schlüssel, Notizen, Gesprächsverlauf, Stimmenprofile (nur Zahlen) | Funktion der App | nur auf dem Handy |
| Duo-Modus | Gespräche zwischen Handys | direkt zwischen den Handys (Bluetooth/Nearby), Texte der Gespräche |

## Rechtsgrundlage und Rechte
[Einwilligung (Art. 6 Abs. 1 lit. a DSGVO) über die Erlaubnisse des Handys; Auskunft, Löschung, Widerruf usw. ergänzen.]

## Hinweise
- Die App nutzt den **eigenen** Gemini-Schlüssel des Nutzers. Dafür gelten die Bedingungen von Google.
- Wer andere Personen aufnimmt oder filmt, braucht deren Einverständnis.
- Die App ist nicht für Kinder unter [Alter] bestimmt.
