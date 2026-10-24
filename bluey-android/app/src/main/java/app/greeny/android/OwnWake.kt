package app.greeny.android

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Sound analysis for the wake word: 12 cepstral values per 10 ms, and a dynamic-time-warping comparison. */
object WakeDsp {
    const val RATE = 16_000
    const val FRAME = 400   // 25 ms
    const val HOP = 160     // 10 ms
    private const val NFFT = 512
    private const val MELS = 24
    const val COEFS = 12

    private val hamming = DoubleArray(FRAME) { 0.54 - 0.46 * cos(2 * PI * it / (FRAME - 1)) }
    private val filters: Array<DoubleArray> = run {
        fun mel(f: Double) = 2595 * log10(1 + f / 700)
        fun hz(m: Double) = 700 * (10.0.pow(m / 2595) - 1)
        val lo = mel(100.0); val hi = mel(5500.0)
        val bins = IntArray(MELS + 2) { ((NFFT + 1) * hz(lo + (hi - lo) * it / (MELS + 1)) / RATE).toInt().coerceIn(0, NFFT / 2) }
        Array(MELS) { m ->
            val f = DoubleArray(NFFT / 2 + 1)
            val a = bins[m]; val b = bins[m + 1]; val c = bins[m + 2]
            for (k in a until b) if (b > a) f[k] = (k - a).toDouble() / (b - a)
            for (k in b..c) if (c > b) f[k] = (c - k).toDouble() / (c - b)
            f
        }
    }

    /** The 12 cepstral values of the 25 ms window that ends at [end] (exclusive) in [s]. */
    fun mfcc(s: ShortArray, end: Int): FloatArray {
        val re = DoubleArray(NFFT)
        val im = DoubleArray(NFFT)
        val start = end - FRAME
        var mean = 0.0
        for (i in 0 until FRAME) mean += s[start + i]
        mean /= FRAME
        for (i in 0 until FRAME) re[i] = (s[start + i] - mean) * hamming[i]
        fft(re, im)
        val power = DoubleArray(NFFT / 2 + 1) { re[it] * re[it] + im[it] * im[it] }
        val logMel = DoubleArray(MELS) { m ->
            var e = 0.0
            for (k in power.indices) e += filters[m][k] * power[k]
            ln(e + 1e-6)
        }
        return FloatArray(COEFS) { c0 ->
            val c = c0 + 1
            var v = 0.0
            for (m in 0 until MELS) v += logMel[m] * cos(PI * c * (m + 0.5) / MELS)
            v.toFloat()
        }
    }

    /** Takes away the average sound of the room and the microphone, and scales to unit spread. */
    fun normalize(seq: List<FloatArray>): List<FloatArray> {
        val n = seq.size
        val mean = FloatArray(COEFS)
        for (f in seq) for (i in 0 until COEFS) mean[i] += f[i] / n
        var spread = 0.0
        for (f in seq) for (i in 0 until COEFS) spread += (f[i] - mean[i]).toDouble().let { it * it }
        val sd = max(sqrt(spread / (n * COEFS)), 1e-3).toFloat()
        return seq.map { f -> FloatArray(COEFS) { (f[it] - mean[it]) / sd } }
    }

    /** How different two (normalized) sounds are; smaller is more alike. Allows for faster or slower speaking. */
    fun dtw(a: List<FloatArray>, b: List<FloatArray>): Double {
        val n = a.size
        val m = b.size
        val band = max(abs(n - m), max(n, m) / 3) + 2
        val inf = Double.MAX_VALUE / 4
        var prev = DoubleArray(m + 1) { inf }
        prev[0] = 0.0
        for (i in 1..n) {
            val cur = DoubleArray(m + 1) { inf }
            val centre = i * m / n
            val lo = max(1, centre - band)
            val hi = min(m, centre + band)
            for (j in lo..hi) {
                var d = 0.0
                for (k in 0 until COEFS) { val x = (a[i - 1][k] - b[j - 1][k]).toDouble(); d += x * x }
                cur[j] = sqrt(d) / sqrt(COEFS.toDouble()) + min(prev[j], min(prev[j - 1], cur[j - 1]))
            }
            prev = cur
        }
        return prev[m] / (n + m)
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = i + k + len / 2
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr; im[b] = im[a] - vi
                    re[a] += vr; im[a] += vi
                    val next = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = next
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** The recordings of the wake phrase ("Hey Greeny" …) for each color, and how close a match has to be. Numbers only. */
class WakeTemplates(context: Context) {
    private val prefs = context.getSharedPreferences("greeny_wake", Context.MODE_PRIVATE)
    private val templates = HashMap<String, MutableList<List<FloatArray>>>()
    private val thresholds = HashMap<String, Double>()
    /** Changes whenever something was recorded or reset, so screens update. */
    var version by mutableStateOf(0); private set

    init { load() }

    fun count(skinId: String) = templates[skinId]?.size ?: 0
    fun ready(skinId: String) = count(skinId) >= 3
    fun list(skinId: String): List<List<FloatArray>> = templates[skinId] ?: emptyList()
    fun threshold(skinId: String) = thresholds[skinId] ?: 0.0

    fun add(skinId: String, seq: List<FloatArray>) {
        templates.getOrPut(skinId) { ArrayList() }.add(WakeDsp.normalize(seq))
        calibrate(skinId)
        save()
        version += 1
    }

    fun clear(skinId: String) {
        templates.remove(skinId); thresholds.remove(skinId)
        save()
        version += 1
    }

    /** The recordings of one person differ from each other by some amount: a match may differ by a bit more than that. */
    private fun calibrate(skinId: String) {
        val t = templates[skinId] ?: return
        if (t.size < 3) { thresholds.remove(skinId); return }
        val d = ArrayList<Double>()
        for (i in t.indices) for (j in i + 1 until t.size) d.add(WakeDsp.dtw(t[i], t[j]))
        thresholds[skinId] = max(d.max() * 1.2, d.average() * 1.5)
    }

    private fun save() {
        val root = JSONObject()
        for ((id, list) in templates) {
            val arr = JSONArray()
            for (seq in list) arr.put(JSONArray(seq.map { f -> JSONArray(f.map { it.toDouble() }) }))
            root.put(id, JSONObject().put("thr", thresholds[id] ?: 0.0).put("t", arr))
        }
        prefs.edit().putString("templates", root.toString()).apply()
    }

    private fun load() {
        try {
            val root = JSONObject(prefs.getString("templates", "{}") ?: "{}")
            for (id in root.keys()) {
                val o = root.getJSONObject(id)
                val arr = o.getJSONArray("t")
                templates[id] = (0 until arr.length()).map { i ->
                    val seq = arr.getJSONArray(i)
                    (0 until seq.length()).map { j ->
                        val f = seq.getJSONArray(j)
                        FloatArray(f.length()) { k -> f.getDouble(k).toFloat() }
                    }
                }.toMutableList()
                thresholds[id] = o.optDouble("thr", 0.0)
            }
        } catch (_: Exception) {
        }
    }
}

/**
 * Listens all the time, silently, for the phrase you taught him ("Hey Greeny"): the microphone stays open (no beeps),
 * short bursts of sound are compared with your recordings on the phone, and nothing leaves the phone.
 */
class OwnWake(private val templates: WakeTemplates) {
    var onWake: (() -> Unit)? = null
    /** The color whose phrase counts. */
    var skinId: () -> String = { Palette.skin.id }
    /** Above 1 is more lenient, below 1 stricter. */
    var sensitivity: () -> Double = { 1.0 }

    /** Only show what he hears; don't wake him (for trying it out in the menu). */
    var testMode by mutableStateOf(false)
    var lastScore by mutableStateOf(0.0); private set
    var lastLimit by mutableStateOf(0.0); private set
    var heard by mutableStateOf(false); private set
    var running by mutableStateOf(false); private set
    /** What the last check said ("ja" / "nein"), for the test in the menu. */
    var lastVerdict by mutableStateOf("")
    /**
     * If set, short bursts of sound are handed to this check (16 kHz mono PCM; it calls back with true when the wake
     * phrase was said) instead of being compared with your own recordings.
     */
    var verifier: ((ShortArray, (Boolean) -> Unit) -> Unit)? = null
    private var lastVerify = 0L
    /** True while a recording of the phrase is being waited for. */
    val capturing: Boolean get() = capture != null

    private val main = Handler(Looper.getMainLooper())
    private var thread: Thread? = null
    @Volatile private var go = false
    @Volatile private var capture: ((List<FloatArray>?) -> Unit)? = null
    @Volatile private var captureAt = 0L

    fun start() {
        if (go) return
        go = true
        running = true
        thread = Thread { loop() }.apply { isDaemon = true; start() }
    }

    fun stop() {
        go = false
        capture?.let { cb -> capture = null; main.post { cb(null) } }
        try { thread?.join(700) } catch (_: Exception) {}
        thread = null
        running = false
    }

    /** Takes the next burst of sound as one recording of the wake phrase. */
    fun captureOne(done: (List<FloatArray>?) -> Unit) {
        capture = { seq -> main.post { done(seq) } }
        captureAt = System.currentTimeMillis()
        start()
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(WakeDsp.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeDsp.RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minBuf * 2, WakeDsp.RATE))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); go = false; running = false
            capture?.let { cb -> capture = null; cb(null) }
            return
        }
        rec.startRecording()
        val chunk = ShortArray(WakeDsp.HOP)
        val window = ShortArray(WakeDsp.FRAME)
        val preroll = ArrayDeque<FloatArray>()
        val audioPre = ArrayDeque<ShortArray>()
        var utterance = ArrayList<FloatArray>()
        var audioUtt = ArrayList<ShortArray>()
        var inSpeech = false
        var loudRun = 0
        var silence = 0
        var floor = 150.0
        while (go) {
            val n = rec.read(chunk, 0, chunk.size)
            if (n <= 0) { try { Thread.sleep(10) } catch (_: InterruptedException) {}; continue }
            System.arraycopy(window, n, window, 0, window.size - n)
            System.arraycopy(chunk, 0, window, window.size - n, n)
            var sum = 0.0
            for (v in window) sum += v.toDouble() * v
            val rms = sqrt(sum / window.size)
            if (rms < floor * 1.5) floor = 0.995 * floor + 0.005 * rms
            val loud = rms > max(220.0, floor * 2.5)
            val feature = WakeDsp.mfcc(window, window.size)
            val audioChunk = chunk.copyOf(n)
            if (!inSpeech) {
                preroll.addLast(feature); audioPre.addLast(audioChunk)
                if (preroll.size > 20) { preroll.removeFirst(); audioPre.removeFirst() }
                if (loud) { loudRun += 1 } else loudRun = 0
                if (loudRun >= 3) { inSpeech = true; utterance = ArrayList(preroll); audioUtt = ArrayList(audioPre); silence = 0 }
            } else {
                utterance.add(feature); audioUtt.add(audioChunk)
                if (loud) silence = 0 else silence += 1
                if (silence >= 40 || utterance.size > 260) {
                    val tooLong = utterance.size > 260
                    val keep = if (silence > 6) utterance.size - (silence - 6) else utterance.size
                    val seq = ArrayList(utterance.subList(0, keep))
                    val audioSeq = ArrayList(audioUtt.subList(0, min(keep, audioUtt.size)))
                    inSpeech = false; loudRun = 0; preroll.clear(); audioPre.clear(); utterance = ArrayList(); audioUtt = ArrayList()
                    if (!tooLong && seq.size in 30..250) {
                        val cb = capture
                        if (cb != null) { capture = null; cb(seq); go = false; continue }
                        val check = verifier
                        if (check != null) verify(check, audioSeq, seq.size)
                        else if (judge(seq)) { go = false; continue }
                    }
                }
            }
            // A recording attempt that heard nothing for a while gives up.
            if (capture != null && System.currentTimeMillis() - captureAt > 12_000) {
                val cb = capture; capture = null; cb?.invoke(null); go = false
            }
        }
        try { rec.stop() } catch (_: Exception) {}
        rec.release()
        running = false
    }

    /** Hands a short burst of sound to the check (at most one every 3.5 seconds, and only if it is about as long as "Hey Greeny"). */
    private fun verify(check: (ShortArray, (Boolean) -> Unit) -> Unit, chunks: List<ShortArray>, frames: Int) {
        val now = System.currentTimeMillis()
        if (frames < 40 || frames > 230 || now - lastVerify < 3500) return
        lastVerify = now
        var total = 0
        for (c in chunks) total += c.size
        val pcm = ShortArray(total)
        var offset = 0
        for (c in chunks) { System.arraycopy(c, 0, pcm, offset, c.size); offset += c.size }
        check(pcm) { ok ->
            lastVerdict = if (ok) "ja" else "nein"
            if (ok) {
                heard = true
                main.postDelayed({ heard = false }, 1500)
                if (!testMode && go) { go = false; main.post { onWake?.invoke() } }
            }
        }
    }

    /** Is this burst of sound the wake phrase? Returns true when it was (and he should wake). */
    private fun judge(seq: List<FloatArray>): Boolean {
        val id = skinId()
        val mine = templates.list(id)
        if (mine.size < 3) return false
        val norm = WakeDsp.normalize(seq)
        val best = mine.minOf { WakeDsp.dtw(norm, it) }
        val lengths = mine.map { it.size }.sorted()
        val ratio = seq.size.toDouble() / lengths[lengths.size / 2]
        val limit = templates.threshold(id) * sensitivity()
        lastScore = best
        lastLimit = limit
        if (best <= limit && ratio in 0.55..1.8) {
            heard = true
            main.postDelayed({ heard = false }, 1500)
            if (!testMode) { main.post { onWake?.invoke() }; return true }
        }
        return false
    }
}
