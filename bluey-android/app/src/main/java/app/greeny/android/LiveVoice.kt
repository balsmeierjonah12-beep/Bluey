package app.greeny.android

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A live, hands-free conversation with Google's Gemini Live API. Once he's awake the mic streams to Gemini,
 * which notices when you talk, and he answers out loud with the voice of his color. His words also appear
 * as a speech bubble. Every few seconds he gets a small picture of you from the front camera.
 */
class LiveVoice(
    context: Context,
    private val settings: Settings,
    private val camera: CameraWatcher,
    private val voice: VoiceID,
) {
    enum class State { ASLEEP, WAKING, LISTENING, ASKING, THINKING, SPEAKING }

    companion object {
        const val MIC_RATE = 16_000
        private const val TAG = "GreenyLive"
        private const val IDLE_SLEEP_MS = 5 * 60_000L   // nobody talked to him for 5 minutes: back to sleep
        private const val VIDEO_EVERY_MS = 500L          // live picture: a new frame every half second
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("greeny", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val player = Player()
    val device = DeviceTools(app, settings, voice)

    var state by mutableStateOf(State.ASLEEP); private set

    /** Asks for the microphone permission; calls back with whether it was granted. */
    var requestMic: (((Boolean) -> Unit) -> Unit)? = null
    /** Called when he needs a Gemini key before he can wake up. */
    var onNeedKey: (() -> Unit)? = null
    var onCaption: ((String, Boolean) -> Unit)? = null
    var onStateChange: ((State) -> Unit)? = null
    var onSessionStart: (() -> Unit)? = null
    var onSessionEnd: (() -> Unit)? = null
    var onUserTurn: ((String, Boolean) -> Unit)? = null
    var onUserWords: ((String, String, Boolean) -> Unit)? = null
    var onReply: ((String) -> Unit)? = null
    var onReport: ((String) -> Unit)? = null

    /** Loudness of his voice right now, 0…1 (moves his mouth). */
    val level: Double get() = player.level

    var volume: Float
        get() = prefs.getFloat("volume", 0.8f)
        set(v) { prefs.edit().putFloat("volume", v).apply(); if (micRunning) applyVolume() }

    private var wantAwake = false
    private var reconnecting = false
    private var socket: WebSocket? = null
    private var ready = false
    private var everReady = false
    private var reconnectAttempts = 0
    private var resumeHandle: String? = null
    private var transcript = StringBuilder()
    private var userText = StringBuilder()
    private var userItem: String? = null
    private var userCounter = 0
    private var responseActive = false
    private var sleepAfterReply = false
    private var toolsRunning = 0
    @Volatile private var lastActivity = 0L
    private var stateSince = 0L
    private var wakeStartedAt = 0L
    private var reconnectStartedAt = 0L
    private var toolsStartedAt = 0L
    private var lastHintAt = 0L
    private var lastObjectHint = 0L
    // Refusals: the canned English "I'm just a language model …" is never spoken.
    private val refusal = Regex("language model|sprachmodell|can'?t help|cannot help|can'?t assist|nicht dabei helfen|large language", RegexOption.IGNORE_CASE)
    private val englishOpener = Regex("^\\s*(i['’]?m|i am|i can|i cannot|i don|as an?|sorry|unfortunately|my apologies)\\b", RegexOption.IGNORE_CASE)
    private val held = ArrayList<ByteArray>()
    private var holdingAudio = false
    private var turnAudioStarted = false
    private var dropTurn = false
    private val holdTimeout = Runnable { flushHeld() }
    private val resetDrop = Runnable { dropTurn = false }
    private var savedCallVolume = -1

    // Duo room: up to four phones with a Greeny each, talking to each other without end.
    var duo: DuoLink? = null
    var duoActive by mutableStateOf(false); private set
    var duoPeerLine by mutableStateOf(""); private set
    /** Who spoke last (not me), for the animation. */
    var duoSpeaker by mutableStateOf(""); private set
    var onDuoPartnerLine: ((String) -> Unit)? = null
    private var duoTopic = ""
    private var duoIntroSent = false
    private val duoBuffer = ArrayList<String>()
    private var duoTurnPending = false
    private var duoStartTurn = false
    private var duoPendingOut: String? = null

    init {
        // Who is talking, as far as the voice fingerprint can tell.
        voice.onHeard = { name ->
            // Only known people, and not too often: every hint makes him say something.
            val now = System.currentTimeMillis()
            if (name != null && ready && state != State.ASLEEP && now - lastHintAt > 20_000) {
                lastHintAt = now
                sendText("(Hinweis vom Handy: Gerade spricht $name.)")
            }
        }
        voice.onEnrolled = { profile ->
            if (ready && state != State.ASLEEP) sendText("(Hinweis vom Handy: Die Stimme von ${profile.name} ist jetzt gelernt. Sag ihr das kurz.)")
        }
    }

    /** Date, remembered notes and known voices: what he knows when a conversation starts. */
    private fun knowledge(): String {
        val lines = mutableListOf<String>()
        lines.add("Jetzt ist " + java.text.SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm", java.util.Locale.forLanguageTag(settings.lang.tag)).format(java.util.Date()) + ".")
        device.place?.let { lines.add("Das Handy ist gerade in: $it.") }
        if (settings.notes.isNotEmpty()) {
            lines.add("Das hast du dir über den Nutzer gemerkt:")
            settings.notes.forEach { lines.add("- $it") }
        }
        val names = voice.profiles.list.map { it.name }
        if (names.isNotEmpty()) lines.add("Diese Stimmen kennst du: ${names.joinToString(", ")}. Hinweise vom Handy sagen dir, wer spricht.")
        return lines.joinToString("\n")
    }

    // The first moments of an answer wait briefly, so a canned English refusal can be cut before anyone hears it.

    private val recent = ArrayList<Pair<Long, String>>()   // when, what he said (lower case)
    private var suppressNext = false

    private fun norm(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun remember(said: String) {
        val n = norm(said)
        if (n.isEmpty()) return
        recent.add(System.currentTimeMillis() to n)
        if (recent.size > 5) recent.removeAt(0)
    }

    /** He starts to say again what he said a moment ago: a loop (usually his own voice coming back in). */
    private fun looksRepeated(partial: String): Boolean {
        val n = norm(partial)
        if (n.length < 12) return false
        val now = System.currentTimeMillis()
        return recent.any { now - it.first < 25_000 && (it.second.startsWith(n.take(24)) || n.startsWith(it.second.take(24))) }
    }

    /** What the mic heard is mostly what he said himself a moment ago. */
    private fun looksLikeEcho(heard: String): Boolean {
        val words = norm(heard).split(" ").filter { it.length > 2 }
        if (words.size < 3) return false
        val now = System.currentTimeMillis()
        val pool = HashSet<String>()
        for ((time, text) in recent) if (now - time < 30_000) pool.addAll(text.split(" "))
        pool.addAll(norm(transcript.toString()).split(" "))
        val hits = words.count { it in pool }
        return hits >= 3 && hits >= words.size * 0.6
    }

    private fun queueAudio(bytes: ByteArray) {
        if (dropTurn) return
        if (!turnAudioStarted && suppressNext) {
            // He would only answer his own voice coming back in: drop it.
            suppressNext = false; turnAudioStarted = true; dropTurn = true
            player.clear()
            main.removeCallbacks(resetDrop); main.postDelayed(resetDrop, 6_000)
            return
        }
        if (holdingAudio) { held.add(bytes); return }
        if (!turnAudioStarted) {
            turnAudioStarted = true
            holdingAudio = true
            held.add(bytes)
            main.removeCallbacks(holdTimeout)
            main.postDelayed(holdTimeout, 700)
            return
        }
        player.write(bytes)
    }

    private fun flushHeld() {
        main.removeCallbacks(holdTimeout)
        holdingAudio = false
        if (!dropTurn) for (b in held) player.write(b)
        held.clear()
    }

    /** Looks at what he says: cut it off if it is the canned refusal, let the held audio through once it looks fine. */
    private fun judgeTranscript() {
        val said = transcript.toString()
        if (refusal.containsMatchIn(said)) { refuseTurn(); return }
        if (looksRepeated(said)) { refuseTurn(silent = true); return }
        if (holdingAudio && ((said.trim().length >= 4 && !englishOpener.containsMatchIn(said)) || said.length >= 50)) flushHeld()
    }

    private fun refuseTurn(silent: Boolean = false) {
        remember(transcript.toString())
        dropTurn = true
        main.removeCallbacks(holdTimeout)
        held.clear()
        holdingAudio = false
        player.clear()
        transcript = StringBuilder()
        if (!silent) onCaption?.invoke("Das kann ich leider nicht. Frag mich was anderes!", true)
        // Never stay muted: after a few seconds the next sentences get through again.
        main.removeCallbacks(resetDrop)
        main.postDelayed(resetDrop, 6_000)
    }

    private fun resetTurnState() {
        dropTurn = false; turnAudioStarted = false; holdingAudio = false
        held.clear()
        main.removeCallbacks(holdTimeout)
    }

    // The call volume decides how loud he is: set it from the slider, put it back afterwards.

    private fun applyVolume() {
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            if (savedCallVolume < 0) savedCallVolume = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (volume * max).roundToInt().coerceIn(1, max), 0)
        } catch (_: Exception) {
        }
    }

    private fun restoreVolume() {
        if (savedCallVolume >= 0) {
            try { audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedCallVolume, 0) } catch (_: Exception) {}
            savedCallVolume = -1
        }
    }

    // Duo room

    private fun me() = duo?.myName ?: settings.skin.name
    private fun others() = duo?.peers?.joinToString(", ") { it.name } ?: "die anderen Greenys"

    private fun duoIntro() = "Duo-Modus: Du heißt ${me()}. Neben dir stehen andere Greenys auf Handys: ${others()}. " +
        "Ihr hört euch nicht, ihr bekommt die Sätze als Text. Unterhaltet euch ausschließlich auf ${settings.lang.english}, " +
        "locker und witzig, und sprich die anderen mit Namen an."

    /** Starts a talk with the others in the room. Returns what he can tell the user. */
    fun startDuo(topic: String): String {
        val link = duo
        if (link == null || link.state != DuoState.CONNECTED || link.peers.isEmpty()) return "Es ist kein zweites Greeny verbunden."
        link.startConversation(topic.ifBlank { "irgendwas Lustiges" })
        if (state == State.ASLEEP) wake()
        return "Ich unterhalte mich jetzt mit ${others()}."
    }

    /** The talk starts for everybody; [first] begins. */
    fun duoStarted(topic: String, first: String) {
        duoActive = true
        duoTopic = topic
        duoIntroSent = false
        duoBuffer.clear(); duoPendingOut = null; duoPeerLine = ""; duoSpeaker = ""
        duoTurnPending = false
        if (state == State.ASLEEP) wake()
        if (first == me()) { duoStartTurn = true; takeTurn() }
    }

    /** Somebody said a line (or, with empty text, a turn was passed on). */
    fun duoLine(from: String, text: String, next: String) {
        if (!duoActive) { duoActive = true; duoIntroSent = false }
        if (text.isNotBlank() && from != me()) {
            duoBuffer.add("$from sagt: „$text“")
            if (duoBuffer.size > 8) duoBuffer.removeAt(0)
            duoPeerLine = text
            duoSpeaker = from
            onDuoPartnerLine?.invoke(text)
        }
        lastActivity = System.currentTimeMillis()
        if (next == me()) takeTurn()
    }

    /** It is my turn: he is given everything the others said since his last turn, and answers. */
    private fun takeTurn() {
        if (state == State.ASLEEP) { duoTurnPending = true; wake(); return }
        if (!ready) { duoTurnPending = true; return }
        duoTurnPending = false
        val intro = if (!duoIntroSent) { duoIntroSent = true; duoIntro() + " " } else ""
        val context = if (duoBuffer.isEmpty()) "" else duoBuffer.joinToString(" ") + " "
        duoBuffer.clear()
        val task = if (duoStartTurn) { duoStartTurn = false; "Thema: $duoTopic. Fang an und sag den anderen etwas dazu" } else "Du bist dran"
        sendText("($intro$context$task, in ein bis zwei kurzen Sätzen.)")
    }

    /** His answer is spoken completely: now the others get it as text, so nobody talks over anybody. */
    private fun duoFlushOut() {
        val line = duoPendingOut ?: return
        duoPendingOut = null
        duo?.sendLine(line)
    }

    fun endDuo(notify: Boolean) {
        if (!duoActive) return
        duoActive = false; duoPeerLine = ""; duoSpeaker = ""
        duoBuffer.clear(); duoPendingOut = null; duoTurnPending = false; duoStartTurn = false
        if (notify) duo?.stopConversation()
    }

    /** Something new was held in front of the camera: after a moment, if it is still there, he is told to have a look. */
    fun objectHint() {
        val now = System.currentTimeMillis()
        if (!ready || state != State.LISTENING || !settings.seeMe || now - lastObjectHint < 40_000) return
        main.postDelayed({
            if (camera.objectVisible && ready && state == State.LISTENING) {
                lastObjectHint = System.currentTimeMillis()
                sendText("(Hinweis vom Handy: Der Nutzer zeigt dir gerade etwas. Schau hin und sag in einem kurzen Satz, was du siehst.)")
            }
        }, 1200)
    }

    private fun changeState(new: State) {
        if (new == state) return
        state = new
        stateSince = System.currentTimeMillis()
        onStateChange?.invoke(new)
    }

    // Wake and sleep

    fun toggle() { if (state == State.ASLEEP) wake() else sleep() }

    fun wake() {
        if (state != State.ASLEEP) return
        wantAwake = true
        wakeStartedAt = System.currentTimeMillis()
        lastActivity = wakeStartedAt
        changeState(State.WAKING)
        main.removeCallbacks(watchdog); main.postDelayed(watchdog, 2_000)
        val ask = requestMic
        if (ask == null) { fail(); return }
        ask { granted ->
            main.post {
                if (!granted) {
                    onCaption?.invoke("Ich brauche das Mikrofon. Erlaube es in den Handy-Einstellungen.", true)
                    fail(); return@post
                }
                if (!settings.hasKey) {
                    onCaption?.invoke("Ich brauche zuerst einen Gemini-Schlüssel. Trag ihn in den Einstellungen ein.", true)
                    onNeedKey?.invoke()
                    fail(); return@post
                }
                connect(settings.apiKey)
            }
        }
    }

    private fun fail() { wantAwake = false; changeState(State.ASLEEP) }

    fun sleep() {
        val wasRunning = wantAwake || socket != null
        wantAwake = false
        reconnecting = false
        camera.stopLive()
        main.removeCallbacks(idleTick)
        main.removeCallbacks(watchdog)
        endDuo(true)
        if (wasRunning) onSessionEnd?.invoke()
        val old = socket
        socket = null
        old?.close(1000, null)
        stopAudio()
        transcript = StringBuilder()
        userText = StringBuilder()
        userItem = null
        responseActive = false
        sleepAfterReply = false
        toolsRunning = 0
        ready = false
        everReady = false
        resumeHandle = null
        changeState(State.ASLEEP)
    }

    /** Makes him say a quick hi (for checking the volume). */
    fun sayHi() {
        if (!ready) { wake(); return }
        sendText("Sag ganz kurz hallo, höchstens vier Wörter.")
    }

    // Connection

    private fun connect(key: String) {
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key"
        ready = false
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(Agent.setup(settings.skin, settings.lang, resumeHandle, knowledge()).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = incoming(webSocket, text)
            // Gemini sends its JSON as binary frames.
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = incoming(webSocket, bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                dropped(webSocket, code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket, code, reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                dropped(webSocket, response?.code ?: 0, t.message ?: "")
            }
        })
        socket = ws
    }

    private fun incoming(webSocket: WebSocket, raw: String) {
        val event = try { JSONObject(raw) } catch (_: Exception) { return }
        main.post { if (webSocket === socket) handle(event) }
    }

    private fun dropped(webSocket: WebSocket, code: Int, reason: String) {
        Log.d(TAG, "closed: $code $reason")
        main.post {
            if (webSocket !== socket) return@post
            val keyProblem = code == 401 || code == 403 || reason.contains("API key", true) || reason.contains("API_KEY")
            when {
                keyProblem -> {
                    onCaption?.invoke("Gemini hat meinen Schlüssel abgelehnt. Prüf ihn in den Einstellungen.", true)
                    onNeedKey?.invoke()
                    sleep()
                }
                !everReady -> {
                    onCaption?.invoke("Gemini hat die Verbindung beendet: ${reason.take(140)}", true)
                    sleep()
                }
                wantAwake -> reconnect()
                else -> sleep()
            }
        }
    }

    /** The connection dropped mid-session: try again shortly and continue where we were. Gives up after a while. */
    private fun reconnect(quiet: Boolean = false) {
        socket = null
        ready = false
        responseActive = false
        toolsRunning = 0
        if (!wantAwake) return
        if (!reconnecting) { reconnecting = true; reconnectStartedAt = System.currentTimeMillis() }
        reconnectAttempts += 1
        if (reconnectAttempts == 1 && !quiet) onCaption?.invoke("Die Verbindung ist weg, ich versuche es nochmal …", true)
        // An old conversation that can't be continued would fail again and again: start fresh instead.
        if (reconnectAttempts >= 3) resumeHandle = null
        if (reconnectAttempts > 6) {
            onCaption?.invoke("Ich habe die Verbindung verloren. Weck mich bitte nochmal.", true)
            sleep()
            return
        }
        val delay = min(15_000L, 1000L + reconnectAttempts * 2000L)
        main.postDelayed({
            if (wantAwake && socket == null) connect(settings.apiKey)
        }, delay)
    }

    /** If something got stuck (no answer, a tool that never returns, a connection that never comes up), set him straight. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!wantAwake) return
            val now = System.currentTimeMillis()
            when {
                state == State.WAKING && now - wakeStartedAt > 15_000 -> {
                    onCaption?.invoke("Die Verbindung kam nicht zustande. Versuch es nochmal.", true)
                    sleep(); return
                }
                reconnecting && now - reconnectStartedAt > 60_000 -> {
                    onCaption?.invoke("Ich habe die Verbindung verloren. Weck mich bitte nochmal.", true)
                    sleep(); return
                }
                toolsRunning > 0 && now - toolsStartedAt > 120_000 -> {
                    toolsRunning = 0
                    if (state != State.SPEAKING) changeState(State.LISTENING)
                }
                state == State.ASKING && now - lastActivity > 12_000 -> changeState(State.LISTENING)
                state == State.THINKING && toolsRunning == 0 && now - lastActivity > 20_000 -> {
                    responseActive = false
                    resetTurnState()
                    changeState(State.LISTENING)
                }
                state == State.SPEAKING && !player.busy && now - lastActivity > 6_000 -> {
                    responseActive = false
                    transcript = StringBuilder()
                    resetTurnState()
                    changeState(State.LISTENING)
                }
            }
            main.postDelayed(this, 2_000)
        }
    }

    /** The mic stopped delivering sound: close it and open it again. */
    private fun restartAudio() {
        if (!wantAwake) return
        stopAudio()
        try {
            startAudio()
        } catch (e: Exception) {
            onCaption?.invoke("Das Mikrofon ist weg. Weck mich bitte nochmal.", true)
            sleep()
        }
    }

    private fun send(message: JSONObject) { socket?.send(message.toString()) }

    private fun sendText(text: String) =
        send(JSONObject().put("realtimeInput", JSONObject().put("text", text)))

    private fun sendVideo(base64Jpeg: String) =
        send(JSONObject().put("realtimeInput", JSONObject().put("video",
            JSONObject().put("data", base64Jpeg).put("mimeType", "image/jpeg"))))

    private fun sendAudio(pcm: ShortArray) {
        if (!ready) return
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            bytes[2 * i] = (pcm[i].toInt() and 0xFF).toByte()
            bytes[2 * i + 1] = ((pcm[i].toInt() shr 8) and 0xFF).toByte()
        }
        socket?.send(JSONObject().put("realtimeInput", JSONObject().put("audio",
            JSONObject().put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("mimeType", "audio/pcm;rate=16000"))).toString())
    }

    // Messages from Gemini

    private fun handle(event: JSONObject) {
        when {
            event.has("setupComplete") -> onReady()
            event.has("serverContent") -> content(event.getJSONObject("serverContent"))
            event.has("toolCall") -> {
                val calls = event.getJSONObject("toolCall").optJSONArray("functionCalls") ?: JSONArray()
                runTools((0 until calls.length()).map { calls.getJSONObject(it) })
            }
            event.has("sessionResumptionUpdate") -> {
                val update = event.getJSONObject("sessionResumptionUpdate")
                val handle = update.optString("newHandle", "")
                if (update.optBoolean("resumable", false) && handle.isNotEmpty()) resumeHandle = handle
            }
            event.has("goAway") -> {
                // The server will hang up soon: reconnect right away, resuming the same conversation.
                val old = socket
                socket = null
                old?.close(1000, "goAway")
                reconnect(quiet = true)
            }
        }
    }

    private fun onReady() {
        ready = true
        everReady = true
        reconnectAttempts = 0
        lastActivity = System.currentTimeMillis()
        if (reconnecting) {
            reconnecting = false
            changeState(State.LISTENING)
            return
        }
        try {
            startAudio()
        } catch (e: Exception) {
            onCaption?.invoke("Ich konnte das Mikrofon nicht starten: ${e.message}", true)
            sleep(); return
        }
        onSessionStart?.invoke()
        changeState(State.LISTENING)
        if (duoActive) { if (duoTurnPending) takeTurn() }
        else sendText("Der Nutzer hat dich gerade geweckt. Begrüße ihn ganz kurz, höchstens vier Wörter.")
        // Live video: about one small picture per second, so he sees you and what you show him.
        camera.startLive(VIDEO_EVERY_MS) { jpeg -> if (ready && settings.seeMe) sendVideo(jpeg) }
        main.removeCallbacks(idleTick); main.postDelayed(idleTick, 5_000)
    }

    private fun content(c: JSONObject) {
        // What you said (Gemini's transcript of your voice).
        c.optJSONObject("inputTranscription")?.optString("text", "")?.takeIf { it.isNotEmpty() }?.let { piece ->
            lastActivity = System.currentTimeMillis()
            if (dropTurn && !holdingAudio) { dropTurn = false; turnAudioStarted = false }
            var item = userItem
            if (item == null) {
                userCounter += 1
                item = "u$userCounter"
                userItem = item
                userText = StringBuilder()
                onUserTurn?.invoke(item, true)
            }
            userText.append(piece)
            if (looksLikeEcho(userText.toString())) { suppressNext = true; return@let }
            onUserWords?.invoke(item, userText.toString(), true)
            if (state == State.LISTENING) changeState(State.ASKING)
        }
        // His voice.
        c.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val data = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data", "") ?: ""
                if (data.isEmpty()) continue
                responseActive = true
                lastActivity = System.currentTimeMillis()
                if (state != State.SPEAKING) changeState(State.SPEAKING)
                queueAudio(Base64.decode(data, Base64.DEFAULT))
            }
        }
        // What he said (the transcript of his voice, shown as the speech bubble).
        c.optJSONObject("outputTranscription")?.optString("text", "")?.takeIf { it.isNotEmpty() }?.let { piece ->
            responseActive = true
            lastActivity = System.currentTimeMillis()
            if (!dropTurn) {
                transcript.append(piece)
                if (state != State.SPEAKING) changeState(State.SPEAKING)
                if (duoActive) duoPeerLine = ""
                judgeTranscript()
                if (!dropTurn) onCaption?.invoke(transcript.toString().trim(), false)
            }
        }
        if (c.optBoolean("interrupted", false)) {
            player.clear()
            held.clear(); holdingAudio = false; turnAudioStarted = false; dropTurn = false
            main.removeCallbacks(holdTimeout)
            transcript = StringBuilder()
            responseActive = false
        }
        if (c.optBoolean("turnComplete", false)) {
            responseActive = false
            userItem = null
            flushHeld()
            val said = transcript.toString().trim()
            if (said.isNotEmpty()) {
                onCaption?.invoke(said, true)
                onReply?.invoke(said)
                remember(said)
                if (duoActive) duoPendingOut = said
            } else if (duoActive && turnAudioStarted) {
                duoPendingOut = ""
            }
            transcript = StringBuilder()
            turnAudioStarted = false
            dropTurn = false
            suppressNext = false
            main.postDelayed({ finishIfQuiet() }, 200)
        }
    }

    private fun runTools(calls: List<JSONObject>) {
        if (calls.isEmpty()) return
        var remaining = calls.size
        if (toolsRunning == 0) toolsStartedAt = System.currentTimeMillis()
        toolsRunning += 1
        if (state != State.SPEAKING) changeState(State.THINKING)
        val responses = JSONArray()
        for (call in calls) {
            val id = call.optString("id")
            val name = call.optString("name")
            val arguments = call.optJSONObject("args")?.toString() ?: "{}"
            if (name == "go_to_sleep") sleepAfterReply = true
            val deliver = { output: String ->
                main.post {
                    if (socket == null) return@post
                    if (name == "web_research" && !output.startsWith("The research")) onReport?.invoke(output)
                    responses.put(JSONObject().put("id", id).put("name", name)
                        .put("response", JSONObject().put("result", output)))
                    remaining -= 1
                    if (remaining == 0) {
                        toolsRunning = max(0, toolsRunning - 1)
                        send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)))
                    }
                }
            }
            val duoReply: String? = when (name) {
                "duo_talk" -> startDuo(call.optJSONObject("args")?.optString("topic", "") ?: "")
                "duo_stop" -> { endDuo(true); "Okay, wir hören auf." }
                else -> null
            }
            if (duoReply != null) { deliver(duoReply); continue }
            // Things on the phone first (timer, flashlight, notes …), then the web.
            val handled = try {
                device.run(name, call.optJSONObject("args") ?: JSONObject()) { output -> deliver(output) }
            } catch (e: Exception) {
                deliver("Das hat nicht geklappt: ${e.message}"); true
            }
            if (!handled) Agent.runTool(settings.apiKey, name, arguments, settings.lang) { output -> deliver(output) }
        }
    }

    private fun finishIfQuiet() {
        if (state == State.ASLEEP) return
        if (responseActive || toolsRunning != 0) return
        if (player.busy) { main.postDelayed({ finishIfQuiet() }, 200); return }
        if (duoActive) duoFlushOut()
        if (sleepAfterReply) { sleep(); return }
        if (state == State.SPEAKING || state == State.THINKING || state == State.ASKING) changeState(State.LISTENING)
    }

    /** Nobody has said anything for a while: back to sleep (and listening for the wake word). */
    private val idleTick = object : Runnable {
        override fun run() {
            if (!wantAwake) return
            if (System.currentTimeMillis() - lastActivity > IDLE_SLEEP_MS && !player.busy && toolsRunning == 0) { sleep(); return }
            main.postDelayed(this, 5_000)
        }
    }

    // Audio in: the mic, as 16 kHz 16-bit mono PCM

    private var record: AudioRecord? = null
    private var micThread: Thread? = null
    @Volatile private var micRunning = false
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var savedMode = AudioManager.MODE_NORMAL

    @Suppress("DEPRECATION")
    private fun routeToSpeaker() {
        savedMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        applyVolume()
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = audio.availableCommunicationDevices
            val headset = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
            if (devices.none { it.type in headset }) {
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { audio.setCommunicationDevice(it) }
            }
        } else {
            if (!audio.isWiredHeadsetOn && !audio.isBluetoothScoOn) audio.isSpeakerphoneOn = true
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreRoute() {
        if (Build.VERSION.SDK_INT >= 31) {
            audio.clearCommunicationDevice()
        } else {
            audio.isSpeakerphoneOn = false
        }
        restoreVolume()
        audio.mode = savedMode
    }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        if (micRunning) return
        var rec: AudioRecord? = null
        var rate = MIC_RATE
        for (candidate in intArrayOf(16_000, 48_000, 44_100, 24_000)) {
            val minBuf = AudioRecord.getMinBufferSize(candidate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) continue
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, candidate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf * 2, candidate / 5 * 2))
            if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; rate = candidate; break } else r.release()
        }
        val r = rec ?: throw IllegalStateException("kein Mikrofon verfügbar")
        routeToSpeaker()
        player.gain = 1f
        player.start()
        record = r
        if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(r.audioSessionId)?.also { it.enabled = true }
        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(r.audioSessionId)?.also { it.enabled = true }
        r.startRecording()
        micRunning = true
        val ratio = rate.toDouble() / MIC_RATE
        micThread = Thread {
            val frame = ShortArray(rate / 10)  // 100 ms
            var carry = 0.0
            var lastBusy = 0L
            var errors = 0
            var gain = 1.0
            while (micRunning) {
                val n = r.read(frame, 0, frame.size)
                if (n < 0) {
                    // The mic broke (another app took it, audio server restart …): after a moment, start it again.
                    errors += 1
                    if (errors > 20) { main.post { restartAudio() }; return@Thread }
                    try { Thread.sleep(50) } catch (_: InterruptedException) {}
                    continue
                }
                errors = 0
                if (n == 0) { try { Thread.sleep(10) } catch (_: InterruptedException) {}; continue }
                val out: ShortArray
                if (rate == MIC_RATE) {
                    out = frame.copyOf(n)
                } else {
                    // Linear resampling to 16 kHz.
                    val list = ShortArray(((n + 1) / ratio).toInt() + 2)
                    var count = 0
                    var pos = carry
                    while (pos < n) {
                        val i = pos.toInt()
                        val a = frame[i]
                        val b = if (i + 1 < n) frame[i + 1] else a
                        val frac = pos - i
                        if (count < list.size) list[count++] = (a + (b - a) * frac).toInt().toShort()
                        pos += ratio
                    }
                    carry = pos - n
                    out = list.copyOf(count)
                }
                if (out.isEmpty()) continue
                // While his voice comes out of the speaker the mic stays quiet, so he never answers himself.
                val now = SystemClock.elapsedRealtime()
                if (player.busy) lastBusy = now
                else if (!duoActive && now - lastBusy > 900) {
                    // Quiet voices get lifted a bit (the phone's echo filter makes the mic rather soft).
                    var energy = 0.0
                    for (v in out) energy += v.toDouble() * v
                    val rms = sqrt(energy / out.size)
                    if (rms > 30) gain += ((2500.0 / rms).coerceIn(1.0, 8.0) - gain) * 0.15
                    val lifted = ShortArray(out.size) { (out[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
                    sendAudio(lifted)
                    voice.feed(out)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopAudio() {
        val wasRunning = micRunning
        micRunning = false
        try { micThread?.join(300) } catch (_: Exception) {}
        micThread = null
        try { record?.stop() } catch (_: Exception) {}
        record?.release(); record = null
        aec?.release(); aec = null
        ns?.release(); ns = null
        player.stop()
        if (wasRunning) restoreRoute()
    }
}
