package app.greeny.android

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Listens for "Hey Greeny" (or Pinky, Greeny …) while he's asleep, using Android's own speech recognizer,
 * on the phone when the phone has an offline German pack. Everything runs on the main thread.
 */
class WakeWord(private val context: Context) {
    var onWake: (() -> Unit)? = null
    /** The language the recognizer should listen in. */
    var languageTag: () -> String = { "de-DE" }
    /** The ways the current name might be written by the recognizer. */
    var variants: () -> List<String> = { emptyList() }

    var active = false; private set
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var restart: Runnable? = null
    private var lastEvent = 0L
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val muted = ArrayList<Int>()

    /** The recognizer plays a little beep every time it starts or stops. While we wait for the wake word, mute it. */
    private fun muteBeep() {
        if (muted.isNotEmpty()) return
        for (stream in intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_MUSIC)) {
            try {
                if (stream == AudioManager.STREAM_MUSIC && audio.isMusicActive) continue
                if (Build.VERSION.SDK_INT >= 23 && audio.isStreamMute(stream)) continue
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                muted.add(stream)
            } catch (_: Exception) {
            }
        }
    }

    private fun unmuteBeep() {
        for (stream in muted) try { audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        muted.clear()
    }

    /** If the recognizer goes quiet (no sound level, no result, no error), start it again. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!active) return
            if (SystemClock.elapsedRealtime() - lastEvent > 12_000) listen()
            main.postDelayed(this, 6_000)
        }
    }

    fun start() {
        if (active || !SpeechRecognizer.isRecognitionAvailable(context)) return
        active = true
        muteBeep()
        lastEvent = SystemClock.elapsedRealtime()
        listen()
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 6_000)
    }

    fun stop() {
        active = false
        unmuteBeep()
        main.removeCallbacks(watchdog)
        restart?.let { main.removeCallbacks(it) }
        recognizer?.let { try { it.cancel() } catch (_: Exception) {}; it.destroy() }
        recognizer = null
    }

    private fun later(delayMs: Long) {
        restart?.let { main.removeCallbacks(it) }
        val r = Runnable { listen() }
        restart = r
        main.postDelayed(r, delayMs)
    }

    private fun listen() {
        if (!active) return
        recognizer?.let { try { it.cancel() } catch (_: Exception) {}; it.destroy() }
        val r = if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { lastEvent = SystemClock.elapsedRealtime() }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { lastEvent = SystemClock.elapsedRealtime() }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) { lastEvent = SystemClock.elapsedRealtime(); check(partialResults) }
            override fun onResults(results: Bundle?) { lastEvent = SystemClock.elapsedRealtime(); if (!check(results)) later(150) }
            override fun onError(error: Int) {
                lastEvent = SystemClock.elapsedRealtime()
                if (!active) return
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> later(400)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> active = false
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> later(1000)
                    else -> later(2000)
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Stay open as long as possible, so it restarts (and beeps) less often.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 30_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 30_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 30_000L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try { r.startListening(intent) } catch (_: Exception) { later(2000) }
    }

    private fun check(bundle: Bundle?): Boolean {
        val heard = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        val names = variants()
        if (heard.none { matches(it, names) }) return false
        stop()
        main.post { onWake?.invoke() }
        return true
    }

    companion object {
        private val heys = setOf("hey", "hay", "hei", "hej", "hallo", "hi", "ey", "he", "heyy", "hai", "hello", "hallöchen")

        fun matches(text: String, variants: List<String>): Boolean {
            val tokens = text.lowercase().replace(Regex("[^a-zäöüß0-9 ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
            for ((i, t) in tokens.withIndex()) {
                for (h in heys) if (t.length > h.length && t.startsWith(h) && isName(t.removePrefix(h), variants)) return true
                if (t !in heys) continue
                if (i + 1 < tokens.size && isName(tokens[i + 1], variants)) return true
                if (i + 2 < tokens.size && isName(tokens[i + 1] + tokens[i + 2], variants)) return true
            }
            return false
        }

        private fun isName(word: String, variants: List<String>) =
            variants.any { v -> word == v || (v.length >= 5 && word.length >= 4 && distance(word, v) <= 1) }

        private fun distance(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                prev = cur
            }
            return prev[b.length]
        }
    }
}
