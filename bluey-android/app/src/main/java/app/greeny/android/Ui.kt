package app.greeny.android

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.DateFormat
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import java.util.Date
import kotlin.math.PI
import kotlin.math.sin

val Fredoka = FontFamily(Font(R.font.fredoka, FontWeight.Bold))
val PlexSans = FontFamily(Font(R.font.ibm_plex_sans))
val PlexMono = FontFamily(Font(R.font.ibm_plex_mono))

private val BerryBlue: Color get() = Palette.berry2

@Composable
fun rememberFrameTime(): Double {
    var now by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(Unit) { while (true) androidx.compose.runtime.withFrameNanos { now = it / 1e9 } }
    return now
}

/** Shows which mode he's in: a solid border around the screen (blue while he listens) plus a small label. */
@Composable
fun ModeIndicator(state: LiveVoice.State, asleepHint: String?) {
    data class Look(val color: Color, val label: String?, val icon: androidx.compose.ui.graphics.vector.ImageVector, val border: Float, val pulse: Double)
    val look = when (state) {
        LiveVoice.State.ASLEEP -> Look(if (asleepHint != null) Color(0x33FFFFFF) else Color(0xFF8A84A0), asleepHint, Icons.Filled.Hearing, 0f, 0.0)
        LiveVoice.State.WAKING -> Look(Color(0xFFFFD66B), "Wacht auf", Icons.Filled.WbSunny, 6f, 1.6)
        LiveVoice.State.LISTENING -> Look(BerryBlue, "Ich höre zu", Icons.Filled.Hearing, 10f, 0.0)
        LiveVoice.State.ASKING -> Look(BerryBlue, "Ganz Ohr", Icons.Filled.Mic, 16f, 1.2)
        LiveVoice.State.THINKING -> Look(Color(0xFFC79BFF), "Denkt nach", Icons.Filled.AutoAwesome, 8f, 1.1)
        LiveVoice.State.SPEAKING -> Look(Color(0xFFFF9AD0), "Antwortet", Icons.Filled.ChatBubble, 8f, 0.0)
    }
    val t = if (look.pulse > 0) rememberFrameTime() else 0.0
    val wave = if (look.pulse > 0) 0.5 + 0.5 * sin(t * look.pulse * 2 * PI) else 1.0
    Box(Modifier.fillMaxSize()) {
        if (look.border > 0) {
            Canvas(Modifier.fillMaxSize()) {
                val w = look.border * density
                val radius = 28.dp.toPx()
                drawRoundRect(
                    look.color.copy(alpha = if (look.pulse > 0) (0.65 + 0.35 * wave).toFloat() else 1f),
                    topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
                    style = Stroke(w),
                )
            }
        }
        val labelled = look.label != null
        Row(
            Modifier
                .padding(top = (14 + look.border).dp, start = (24 + look.border).dp)
                .heightIn(min = 34.dp).widthIn(min = 34.dp)
                .clip(RoundedCornerShape(50))
                .background(if (labelled) look.color else Color.White.copy(alpha = 0.06f))
                .padding(horizontal = if (labelled) 14.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(look.icon, null, Modifier.size(16.dp).padding(start = if (labelled) 0.dp else 0.dp),
                tint = if (labelled) Color.White else look.color.copy(alpha = 0.7f))
            if (labelled) {
                Spacer(Modifier.width(7.dp))
                Text(look.label!!, color = Color.White, fontFamily = Fredoka, fontSize = 16.sp)
            }
        }
    }
}

/** A tiny, quiet speaker button in the top-right corner: volume and a test hi. */
@Composable
fun SoundButton(live: LiveVoice) {
    var volume by remember { mutableStateOf(live.volume) }
    var open by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastTouch, open) {
        if (open) { delay(6000); open = false }
    }
    Column(Modifier.fillMaxSize().padding(top = 10.dp, end = 14.dp), horizontalAlignment = Alignment.End) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable { open = !open; lastTouch = System.currentTimeMillis() },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = if (open) 0.14f else 0.06f)), contentAlignment = Alignment.Center) {
                val icon = when { open -> Icons.Filled.Close; volume < 0.01f -> Icons.Filled.VolumeOff; volume < 0.5f -> Icons.Filled.VolumeDown; else -> Icons.Filled.VolumeUp }
                Icon(icon, "Ton", Modifier.size(16.dp), tint = Color.White.copy(alpha = if (open) 0.9f else 0.35f))
            }
        }
        AnimatedVisibility(open) {
            Column(
                Modifier.padding(top = 10.dp).width(260.dp).clip(RoundedCornerShape(18.dp))
                    .background(Palette.panel.copy(alpha = 0.95f)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("LAUTSTÄRKE", color = Palette.inkSoft, fontFamily = PlexMono, fontSize = 12.sp)
                    Text("${(volume * 100).toInt()}%", color = Color.White, fontFamily = PlexMono, fontSize = 13.sp)
                }
                Slider(
                    value = volume, onValueChange = { volume = it; live.volume = it; lastTouch = System.currentTimeMillis() },
                    colors = SliderDefaults.colors(thumbColor = Palette.berry2, activeTrackColor = Palette.berry2),
                )
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(Palette.gradient))
                        .clickable { live.sayHi(); lastTouch = System.currentTimeMillis() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (live.state == LiveVoice.State.ASLEEP) "${Palette.skin.name} wecken" else "Hallo sagen",
                        color = Color.White, fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        }
    }
}

/** A tiny camera mark in the bottom-left corner: on whenever he can see, lit when he sees your face. */
@Composable
fun CameraDot(camera: CameraWatcher) {
    Box(Modifier.fillMaxSize().padding(start = 18.dp, bottom = 14.dp), contentAlignment = Alignment.BottomStart) {
        Icon(
            Icons.Filled.Videocam, "Kamera", Modifier.size(16.dp),
            tint = when {
                !camera.running -> Color.White.copy(alpha = 0.12f)
                camera.faceVisible -> Color(0xFF7CE0A0).copy(alpha = 0.8f)
                else -> Color.White.copy(alpha = 0.3f)
            },
        )
    }
}

/** His speech bubble: what he says, as one short line at the top of the screen. */
@Composable
fun CaptionBubble(text: String) {
    Box(Modifier.fillMaxSize().padding(top = 18.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(text.isNotBlank()) {
            Text(
                text, color = Palette.ink, fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFFF4F1FA))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
internal fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedBorderColor = Palette.berry2, unfocusedBorderColor = Palette.inkSoft.copy(alpha = 0.5f),
    cursorColor = Palette.berry1,
)

/** First start: he needs a brain. The key stays on this phone, encrypted. */
@Composable
fun KeyView(settings: Settings) {
    var key by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }.padding(horizontal = 72.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(56.dp, Alignment.CenterHorizontally),
    ) {
        val t = rememberFrameTime()
        Canvas(Modifier.size(width = 200.dp, height = 170.dp)) {
            val s = 1f + 0.02f * sin(t * 1.1).toFloat()
            val rect = Rect(0f, 0f, size.width, size.height)
            withTransform({ scale(s, s, Offset(size.width / 2, size.height)) }) {
                drawPath(blobPath(rect), skinBrush(rect), alpha = 0.9f)
                val eyeY = size.height * 0.38f
                drawLine(Palette.ink, Offset(size.width * 0.5f - 62.dp.toPx(), eyeY), Offset(size.width * 0.5f - 16.dp.toPx(), eyeY), 7.dp.toPx(), StrokeCap.Round)
                drawLine(Palette.ink, Offset(size.width * 0.5f + 16.dp.toPx(), eyeY), Offset(size.width * 0.5f + 62.dp.toPx(), eyeY), 7.dp.toPx(), StrokeCap.Round)
                drawOval(Palette.nose, Offset(size.width * 0.5f - 5.dp.toPx(), size.height * 0.62f), Size(18.dp.toPx(), 12.dp.toPx()))
            }
        }
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Gib mir ein Gehirn", color = Palette.text, fontFamily = Fredoka, fontSize = 32.sp)
            Text(
                "Füge deinen Gemini-API-Schlüssel ein (aistudio.google.com/apikey). Er bleibt verschlüsselt auf diesem Handy und geht nur an Google.",
                color = Palette.inkSoft, fontFamily = PlexSans, fontSize = 16.sp,
            )
            OutlinedTextField(
                value = key, onValueChange = { key = it }, singleLine = true, label = { Text("AIza…") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = darkFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            Box(
                Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (key.isBlank()) Palette.panel else Palette.berry3)
                    .clickable(enabled = key.isNotBlank()) { settings.apiKey = key; key = "" }.padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Speichern", color = Palette.text, fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
        }
    }
}

@Composable
internal fun Chip(label: String, selected: Boolean = false, onClick: () -> Unit) = Box(
    Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) Palette.berry3 else Palette.panel)
        .clickable(onClick = onClick).padding(horizontal = 14.dp),
    contentAlignment = Alignment.Center,
) { Text(label, color = Palette.text, fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }

/** His app: the list of conversations, one conversation's transcript, and the settings. */
@Composable
fun GreenyAppView(
    store: SessionStore, settings: Settings, voice: VoiceID, awake: Boolean, startOnSettings: Boolean,
    templates: WakeTemplates, ownWake: OwnWake, duo: DuoLink, live: LiveVoice, onClose: () -> Unit,
) {
    var openID by remember { mutableStateOf<String?>(null) }
    var tab by remember(startOnSettings) { mutableStateOf(if (startOnSettings) 1 else 0) }
    val session = store.sessions.firstOrNull { it.id == openID }
    Column(Modifier.fillMaxSize().background(Color(0xFF0E0C14)).pointerInput(Unit) { detectTapGestures { } }.padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(Palette.skin.name, color = Palette.text, fontFamily = Fredoka, fontSize = 26.sp)
                if (session == null) {
                    Chip("Verlauf", tab == 0) { tab = 0 }
                    Chip("Einstellungen", tab == 1) { tab = 1 }
                    Chip("Weckwort", tab == 4) { tab = 4 }
                    Chip("Duo", tab == 5) { tab = 5 }
                    Chip("Stimmen", tab == 2) { tab = 2 }
                    Chip("Notizen", tab == 3) { tab = 3 }
                    Chip("Info", tab == 6) { tab = 6 }
                }
            }
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { if (session != null) openID = null else onClose() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, "Schließen", tint = Color.White.copy(alpha = 0.7f))
            }
        }
        when {
            session != null -> SessionDetail(store, session) { openID = null }
            tab == 1 -> SettingsTab(settings)
            tab == 2 -> VoicesTab(voice, awake)
            tab == 3 -> NotesTab(settings)
            tab == 4 -> WakeTab(settings, templates, ownWake, awake)
            tab == 5 -> DuoTab(duo, live)
            tab == 6 -> InfoTab()
            store.sessions.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Noch keine Gespräche. Sag „Hey ${Palette.skin.name}“, um loszulegen.", color = Palette.inkSoft, fontFamily = PlexSans, fontSize = 16.sp)
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(store.sessions, key = { it.id }) { s ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.panel)
                            .clickable { openID = s.id }.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val whenText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.startedMs))
                        val minutes = (s.durationMs / 60_000).toInt()
                        Text("$whenText · ${if (minutes < 1) "<1" else minutes.toString()} Min. · ${s.questionCount} Fragen",
                            color = Palette.inkSoft, fontFamily = PlexMono, fontSize = 12.sp)
                        Text(s.summary, color = Palette.text, fontFamily = PlexSans, fontSize = 16.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun VoicesTab(voice: VoiceID, awake: Boolean) {
    var name by remember { mutableStateOf("") }
    val enrolling = voice.enrolling
    LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("GELERNTE STIMMEN", color = Palette.inkSoft, fontFamily = PlexMono, fontSize = 12.sp)
                Text("Er erkennt, wer gerade spricht, und spricht die Person mit Namen an. Gespeichert werden nur Zahlen, keine Aufnahmen. " +
                    "Das ist eine einfache Erkennung und kein Sicherheitsmerkmal.",
                    color = Palette.inkSoft, fontFamily = PlexSans, fontSize = 13.sp)
            }
        }
        if (voice.profiles.list.isEmpty()) {
            item { Text("Noch keine Stimme gelernt.", color = Palette.text, fontFamily = PlexSans, fontSize = 15.sp) }
        }
        items(voice.profiles.list, key = { it.name }) { p ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(p.name, color = Palette.text, fontFamily = Fredoka, fontSize = 20.sp, modifier = Modifier.weight(1f))
                Chip("Löschen") { voice.profiles.remove(p.name) }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (enrolling != null) {
                    Text("Ich höre dir zu, $enrolling. Erzähl einfach etwa 20 Sekunden, was du heute gemacht hast.",
                        color = Palette.text, fontFamily = PlexSans, fontSize = 15.sp)
                    LinearProgressIndicator(progress = { voice.progress }, color = Palette.berry2, modifier = Modifier.fillMaxWidth())
                    Chip("Abbrechen") { voice.cancelEnroll() }
                } else {
                    Text("NEUE STIMME LERNEN", color = Palette.inkSoft, fontFamily = PlexMono, fontSize = 12.sp)
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name der Person") },
                        colors = darkFieldColors(), modifier = Modifier.fillMaxWidth(),
                    )
                    Chip("Stimme lernen") { voice.startEnroll(name, ownRecording = !awake); if (name.isNotBlank()) name = "" }
                    if (voice.message.isNotEmpty()) {
                        Text(voice.message, color = Palette.inkSoft, fontFamily = PlexSans, fontSize = 13.sp)
                    }
                    Text("Du kannst auch sagen: „${Palette.skin.name}, lerne meine Stimme“.", color = Palette.inkSoft, fontFamily = PlexSans, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun NotesTab(settings: Settings) {
    LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Das merkt er sich. Sag zum Beispiel: „Merk dir, dass ich Mittwoch Zahnarzt habe.“",
                color = Palette.inkSoft, fontFamily = PlexSans, fontSize = 13.sp)
        }
        if (settings.notes.isEmpty()) {
            item { Text("Noch nichts gemerkt.", color = Palette.text, fontFamily = PlexSans, fontSize = 15.sp) }
        }
        items(settings.notes.size) { i ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(settings.notes[i], color = Palette.text, fontFamily = PlexSans, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Chip("Löschen") { settings.removeNoteAt(i) }
            }
        }
    }
}

@Composable
private fun SettingsTab(settings: Settings) {
    var key by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(saved) { if (saved) { delay(1500); saved = false } }
    LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("FARBE", color = Palette.inkSoft, fontFamily = PlexMono, fontSize = 12.sp)
                Text("Je nach Farbe heißt er anders, hört auf einen anderen Namen und hat eine andere Stimme.",
                    color = Palette.inkSoft, fontFamily = PlexSans, fontSize = 13.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(Skins.all, key = { it.id }) { skin ->
                        val selected = skin.id == Palette.skin.id
                        Column(
                            Modifier.clickable { settings.skin = skin }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                Modifier.size(56.dp).clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(skin.c1, skin.c2, skin.c3)))
                                    .then(if (selected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier),
                            )
                            Text(skin.name, color = if (selected) Color.White else Palette.inkSoft, fontFamily = Fredoka, fontSize = 16.sp)
                            Text(skin.colorName, color = Palette.inkSoft, fontFamily = PlexMono, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SPRACHE", color = Palette.inkSoft, fontFamily = PlexMono, fontSize = 12.sp)
                Text("Er spricht nur diese Sprache, auch wenn du eine andere sprichst. Gilt ab dem nächsten Aufwachen.",
                    color = Palette.inkSoft, fontFamily = PlexSans, fontSize = 13.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Languages.all, key = { it.code }) { l ->
                        Chip(l.native, settings.languageChosen && settings.lang.code == l.code) { settings.languageCode = l.code }
                    }
                }
            }
        }
        item {
            ToggleRow(
                "Aufwecken per Zuruf", "Sag „Hey ${Palette.skin.name}“, dann wacht er auf, ohne dass du das Handy anfassen musst.",
                settings.wakeWord,
            ) { settings.wakeWord = it }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SO HÖRT ER „HEY ${Palette.skin.name.uppercase()}“", color = Palette.inkSoft, fontFamily = PlexMono, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Gemini prüft", settings.wakeMode == "gemini") { settings.wakeMode = "gemini" }
                    Chip("Mein Weckwort", settings.wakeMode == "own") { settings.wakeMode = "own" }
                    Chip("Handy-Erkennung", settings.wakeMode == "system") { settings.wakeMode = "system" }
                }
                Text(
                    when (settings.wakeMode) {
                        "own" -> "Du nimmst dein Weckwort im Menü „Weckwort“ auf. Das Mikrofon bleibt lautlos offen, alles bleibt auf dem Handy."
                        "system" -> "Die Spracherkennung des Handys hört mit. Das klappt ohne Aufnahme, piept aber auf vielen Handys."
                        else -> "Das Mikrofon bleibt lautlos offen. Kurze Sprachfetzen (unter 2,5 Sekunden) schickt das Handy zur Prüfung an Gemini, höchstens einen alle paar Sekunden. Am genauesten, ohne Aufnahme."
                    },
                    color = Palette.inkSoft, fontFamily = PlexSans, fontSize = 13.sp,
                )
            }
        }
        item {
            ToggleRow(
                "Er sieht mich", "Seine Augen folgen deinem Gesicht nur auf dem Handy. Ist das an, bekommt Google während des Gesprächs alle paar Sekunden ein kleines Bild von der Frontkamera.",
                settings.seeMe,
            ) { settings.seeMe = it }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GEMINI-SCHLÜSSEL", color = Palette.inkSoft, fontFamily = PlexMono, fontSize = 12.sp)
                val shown = if (settings.hasKey) "Gespeichert: …${settings.apiKey.takeLast(4)}" else "Noch kein Schlüssel"
                Text(shown, color = Palette.text, fontFamily = PlexSans, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = key, onValueChange = { key = it }, singleLine = true, label = { Text("Neuer Schlüssel") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = darkFieldColors(), modifier = Modifier.weight(1f),
                    )
                    Chip(if (saved) "Gespeichert" else "Speichern") { if (key.isNotBlank()) { settings.apiKey = key; key = ""; saved = true } }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Palette.text, fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(text, color = Palette.inkSoft, fontFamily = PlexSans, fontSize = 13.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.berry3, checkedThumbColor = Palette.berry1),
        )
    }
}

@Composable
private fun SessionDetail(store: SessionStore, s: GreenySession, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    val timeFmt = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip(if (copied) "Kopiert" else "Text kopieren") { clipboard.setText(AnnotatedString(s.exportText)); copied = true }
            Chip("Teilen") {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, s.exportText) }
                context.startActivity(Intent.createChooser(send, "Gespräch teilen"))
            }
            Chip("Löschen") { store.delete(s.id); onDeleted() }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.displayEntries, key = { it.id }) { e ->
                val who = when (e.kind) {
                    EntryKind.TRANSCRIPT -> "Sprecher ${e.speaker ?: "?"}"
                    EntryKind.HEARD, EntryKind.ASKED -> "Du"
                    EntryKind.REPLY -> Palette.skin.name
                    EntryKind.REPORT -> "Recherche"
                }
                val accent = when (e.kind) { EntryKind.ASKED -> BerryBlue; EntryKind.REPLY -> Color(0xFFFF9AD0); EntryKind.REPORT -> Color(0xFFC79BFF); else -> Palette.inkSoft }
                Column(Modifier.fillMaxWidth()) {
                    Text("${timeFmt.format(Date(e.timeMs))} · $who", color = accent, fontFamily = PlexMono, fontSize = 11.sp)
                    Text(e.text, color = Palette.text, fontFamily = PlexSans, fontSize = 15.sp)
                }
            }
        }
    }
}
