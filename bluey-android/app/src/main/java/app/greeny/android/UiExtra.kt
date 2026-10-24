package app.greeny.android

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Teach him the wake phrase ("Hey Greeny"): three or four recordings, then he listens for it all the time, silently. */
@Composable
fun WakeTab(settings: Settings, templates: WakeTemplates, ownWake: OwnWake, awake: Boolean) {
    val skin = Palette.skin
    val version = templates.version
    val count = remember(version, skin.id) { templates.count(skin.id) }
    var recording by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("WECKWORT FÜR ${skin.name.uppercase()}", color = Palette.inkSoft, fontFamily = PlexMono, fontSize = 12.sp)
                Text("Dieses Menü brauchst du nur im Modus „Mein Weckwort“ (Einstellungen). " +
                    "Nimm den Satz „Hey ${skin.name}“ drei- bis viermal mit deiner Stimme auf. Danach hört das Handy im Schlaf " +
                    "durchgehend und lautlos darauf (ohne Piepton), und es verlässt nichts das Handy. Jede Farbe hat ihr eigenes Weckwort.",
                    color = Palette.inkSoft, fontFamily = PlexSans, fontSize = 13.sp)
            }
        }
        item {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (count >= 3) "$count Aufnahmen, das Weckwort ist bereit." else "$count von mindestens 3 Aufnahmen",
                    color = Palette.text, fontFamily = PlexSans, fontSize = 15.sp)
                LinearProgressIndicator(progress = { min(1f, count / 3f) }, color = Palette.berry2, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(if (recording) "Ich höre zu …" else "Aufnehmen") {
                        when {
                            awake -> info = "Schick ihn erst schlafen (doppelt tippen)."
                            recording -> {}
                            else -> {
                                recording = true
                                info = "Sag jetzt „Hey ${skin.name}“."
                                ownWake.captureOne { seq ->
                                    recording = false
                                    if (seq != null) { templates.add(skin.id, seq); info = "Aufgenommen." } else info = "Ich habe nichts gehört. Nochmal?"
                                }
                            }
                        }
                    }
                    Chip("Zurücksetzen") { templates.clear(skin.id); info = "" }
                }
                if (info.isNotEmpty()) Text(info, color = Palette.inkSoft, fontFamily = PlexSans, fontSize = 13.sp)
            }
        }
        item {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("EMPFINDLICHKEIT", color = Palette.inkSoft, fontFamily = PlexMono, fontSize = 12.sp)
                Slider(
                    value = settings.wakeSensitivity, onValueChange = { settings.wakeSensitivity = it }, valueRange = 0.7f..1.5f,
                    colors = SliderDefaults.colors(thumbColor = Palette.berry2, activeTrackColor = Palette.berry2),
                )
                Text("Wacht er zu schnell auf: nach links. Wacht er nie auf: nach rechts.", color = Palette.inkSoft, fontFamily = PlexSans, fontSize = 13.sp)
            }
        }
        item {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(if (ownWake.testMode) "Test beenden" else "Weckwort testen") {
                    if (awake) info = "Schick ihn erst schlafen (doppelt tippen)." else ownWake.testMode = !ownWake.testMode
                }
                if (ownWake.testMode) {
                    Text(if (settings.wakeMode == "gemini") "Sag „Hey ${skin.name}“. Letzte Prüfung: ${ownWake.lastVerdict.ifEmpty { "–" }}"
                        else "Sag „Hey ${skin.name}“. Letzter Wert: %.2f, Grenze: %.2f".format(ownWake.lastScore, ownWake.lastLimit),
                        color = Palette.inkSoft, fontFamily = PlexMono, fontSize = 12.sp)
                    if (ownWake.heard) Text("Erkannt!", color = Palette.berry1, fontFamily = Fredoka, fontSize = 22.sp)
                }
            }
        }
    }
}

/** First start: which language does he speak? Only that one. */
@Composable
fun LanguageView(settings: Settings) {
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }.padding(horizontal = 48.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Welche Sprache soll er sprechen?", color = Palette.text, fontFamily = Fredoka, fontSize = 28.sp)
        Text("Er spricht dann nur diese Sprache, auch wenn du eine andere sprichst. Du kannst sie später in den Einstellungen ändern.",
            color = Palette.inkSoft, fontFamily = PlexSans, fontSize = 14.sp)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            gridItems(Languages.all, key = { it.code }) { l ->
                Box(
                    Modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(Palette.panel)
                        .clickable { settings.languageCode = l.code },
                    contentAlignment = Alignment.Center,
                ) { Text(l.native, color = Palette.text, fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }
            }
        }
    }
}

/** Where on the screen the [index]-th other Greeny is, as a gaze (x, y). One other: left or right; more: left, top, right. */
fun duoPosition(index: Int, count: Int, side: String): Pair<Double, Double> =
    if (count <= 1) (if (side == "left") -1.0 else 1.0) to -0.05
    else listOf(-1.0 to -0.05, 0.0 to -0.9, 1.0 to -0.05)[index.coerceIn(0, 2)]

/** Duo room: one phone opens it, up to three join, and the Greenys talk without end. */
@Composable
fun DuoTab(duo: DuoLink, live: LiveVoice) {
    var topic by remember { mutableStateOf("") }
    LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("DUO: BIS ZU 4 GREENYS", color = Palette.inkSoft, fontFamily = PlexMono, fontSize = 12.sp)
                Text("Stell die Handys nebeneinander und schalte überall Bluetooth und Standort an. Auf einem Handy tippst du " +
                    "„Raum öffnen“, auf den anderen (bis zu drei) „Raum beitreten“. Sie finden sich von selbst, du musst nichts koppeln. " +
                    "Dann unterhalten sich die Greenys der Reihe nach, jeder mit seiner Stimme, so lange du willst.",
                    color = Palette.inkSoft, fontFamily = PlexSans, fontSize = 13.sp)
            }
        }
        when (duo.state) {
            DuoState.OFF -> item {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Chip("Raum öffnen") { duo.openRoom() }
                        Chip("Raum beitreten") { duo.joinRoom() }
                    }
                    if (duo.message.isNotEmpty()) Text(duo.message, color = Palette.inkSoft, fontFamily = PlexSans, fontSize = 13.sp)
                }
            }
            DuoState.WAITING, DuoState.CONNECTING -> item {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(duo.message, color = Palette.text, fontFamily = PlexSans, fontSize = 15.sp)
                    Chip(if (duo.isHost) "Raum schließen" else "Abbrechen") { duo.close() }
                }
            }
            DuoState.CONNECTED -> {
                item {
                    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Im Raum (${duo.peers.size + 1} von ${DuoLink.MAX_PEOPLE})", color = Palette.text, fontFamily = Fredoka, fontSize = 22.sp)
                        Text((listOf("${duo.myName} (du)") + duo.peers.map { it.name }).joinToString(", "),
                            color = Palette.text, fontFamily = PlexSans, fontSize = 15.sp)
                        if (duo.isHost && duo.peers.size < DuoLink.MAX_PEOPLE - 1) {
                            Text("Es können noch ${DuoLink.MAX_PEOPLE - 1 - duo.peers.size} Handy(s) beitreten.", color = Palette.inkSoft, fontFamily = PlexSans, fontSize = 13.sp)
                        }
                        if (duo.peers.size == 1) {
                            Text("Das andere Handy steht:", color = Palette.inkSoft, fontFamily = PlexSans, fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Chip("links", duo.side == "left") { duo.side = "left" }
                                Chip("rechts", duo.side == "right") { duo.side = "right" }
                            }
                        }
                    }
                }
                item {
                    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = topic, onValueChange = { topic = it }, singleLine = true, label = { Text("Thema (z. B. Pizza)") },
                            colors = darkFieldColors(), modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Chip("Gespräch starten") { live.startDuo(topic) }
                            Chip("Gespräch beenden") { live.endDuo(true) }
                            Chip("Raum verlassen") { live.endDuo(true); duo.close() }
                        }
                        if (live.duoActive) Text("Das Gespräch läuft, bis du es beendest.", color = Palette.berry1, fontFamily = PlexSans, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/** While the Greenys talk: dots, notes and hearts flow from the speaker to the listeners, and the words pop up at the speaker's side. */
@Composable
fun DuoOverlay(live: LiveVoice, duo: DuoLink) {
    if (duo.state != DuoState.CONNECTED || duo.peers.isEmpty()) return
    val t = rememberFrameTime()
    val me = Palette.skin
    val peers = duo.peers
    val talking = live.duoActive
    val iSpeak = talking && live.state == LiveVoice.State.SPEAKING
    val speakerIndex = peers.indexOfFirst { it.name == live.duoSpeaker }
    val peerSpeaks = talking && live.duoPeerLine.isNotEmpty() && !iSpeak && speakerIndex >= 0

    Box(Modifier.fillMaxSize()) {
        if (iSpeak || peerSpeaks) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                fun edge(index: Int): Offset {
                    val p = duoPosition(index, peers.size, duo.side)
                    return Offset(if (p.first < -0.5) 0f else if (p.first > 0.5) w else w / 2f, if (p.second < -0.5) 0f else h * 0.3f)
                }
                val centre = Offset(w / 2f, h * 0.45f)
                val color = if (iSpeak) me.c2 else peers[speakerIndex].skin.c2
                val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 17.dp.toPx(); isFakeBoldText = true }
                val n = 8
                for (i in 0 until n) {
                    val phase = ((t * 0.55 + i / n.toDouble()) % 1.0).toFloat()
                    val lift = sin(phase * PI.toFloat())
                    val target = if (iSpeak) edge(i % peers.size) else edge(speakerIndex)
                    val from = if (iSpeak) centre else target
                    val to = if (iSpeak) target else centre
                    val x = from.x + (to.x - from.x) * phase
                    val y = from.y + (to.y - from.y) * phase + sin(phase * 7f + i) * h * 0.05f - lift * h * 0.08f
                    if (i % 3 == 0) {
                        paint.color = android.graphics.Color.argb((255 * (0.35f + 0.6f * lift)).toInt().coerceIn(0, 255),
                            (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
                        drawContext.canvas.nativeCanvas.drawText(if (i % 2 == 0) "♪" else "♥", x, y, paint)
                    } else {
                        drawCircle(color.copy(alpha = 0.3f + 0.55f * lift), (4f + 7f * lift).dp.toPx(), Offset(x, y))
                    }
                }
            }
        }
        if (peerSpeaks) {
            val p = duoPosition(speakerIndex, peers.size, duo.side)
            val where = if (p.first < -0.5) Alignment.CenterStart else if (p.first > 0.5) Alignment.CenterEnd else Alignment.TopCenter
            Text(
                "${peers[speakerIndex].name}: ${live.duoPeerLine}", color = Color.White, fontFamily = PlexSans,
                fontWeight = FontWeight.Medium, fontSize = 15.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(where).padding(start = 14.dp, end = 14.dp, top = if (where == Alignment.TopCenter) 64.dp else 14.dp, bottom = 14.dp)
                    .widthIn(max = 230.dp).clip(RoundedCornerShape(18.dp))
                    .background(peers[speakerIndex].skin.c3.copy(alpha = 0.92f)).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Text(
            "Duo: " + peers.joinToString(", ") { it.name } + if (talking) " · tippen zum Beenden" else "",
            color = Color.White, fontFamily = PlexSans, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).clip(RoundedCornerShape(50))
                .background(peers[0].skin.c3.copy(alpha = 0.85f)).clickable(enabled = talking) { live.endDuo(true) }
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}


@Composable
private fun InfoBlock(title: String, text: String) {
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Palette.inkSoft, fontFamily = PlexMono, fontSize = 12.sp)
        Text(text, color = Palette.text, fontFamily = PlexSans, fontSize = 14.sp)
    }
}

/** What happens with the data, and whose work is in the app. */
@Composable
fun InfoTab() {
    LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            InfoBlock("ÜBER GREENY", "Greeny ist ein kleiner KI-Helfer mit Gesicht. Er hört zu, sieht dich über die Frontkamera, antwortet mit Stimme " +
                "und kann zum Beispiel Timer stellen, Orte in der Nähe suchen und mit anderen Greenys sprechen.")
        }
        item {
            InfoBlock("DEINE DATEN", "Solange er wach ist, geht dein Ton an Google (Gemini). Ist „Er sieht mich“ an, bekommt Gemini dazu kleine Kamerabilder. " +
                "Im Schlaf prüft Gemini im Modus „Gemini prüft“ kurze Sprachfetzen auf das Weckwort. " +
                "Gesichts-, Gegenstands- und Stimmenerkennung laufen nur auf deinem Handy. Dein Schlüssel, deine Notizen, der Verlauf und die " +
                "Stimmenprofile (nur Zahlen, keine Aufnahmen) bleiben auf dem Handy. Für Orte in der Nähe fragt er OpenStreetMap mit deinem " +
                "ungefähren Standort, und das Wetter holt Gemini mit der Google-Suche.")
        }
        item {
            InfoBlock("GEMINI", "Du nutzt deinen eigenen Gemini-API-Schlüssel (aistudio.google.com/apikey). Es gelten die Nutzungsbedingungen von Google. " +
                "Je nach Land und Tarif kann Google verlangen, dass der Schlüssel zu einem Konto mit Abrechnung gehört. Bitte prüfe das dort.")
        }
        item {
            InfoBlock("LIZENZEN", "Schriften Fredoka und IBM Plex: SIL Open Font License 1.1. Android-Bibliotheken (Jetpack Compose, CameraX, OkHttp): " +
                "Apache License 2.0. Google ML Kit, Google Play-Dienste und Gemini: Bedingungen von Google. Orte in der Nähe: " +
                "© OpenStreetMap-Mitwirkende (Open Database License). Idee und Teile des Aufbaus beruhen auf einem gemeinfreien Open-Source-Projekt (Unlicense).")
        }
    }
}
