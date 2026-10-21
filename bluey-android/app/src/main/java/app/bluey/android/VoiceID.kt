package app.bluey.android

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One learned voice: the average sound of someone's voice and how much it varies. Only numbers, never audio. */
class VoiceProfile(val name: String, val mean: DoubleArray, val sd: DoubleArray)

class VoiceProfiles(context: Context) {
    private val prefs = context.getSharedPreferences("bluey_voices", Context.MODE_PRIVATE)
    var list by mutableStateOf(load()); private set

    fun add(profile: VoiceProfile) {
        list = list.filter { !it.name.equals(profile.name, true) } + profile
        save()
    }

    fun remove(name: String) { list = list.filter { it.name != name }; save() }

    private fun save() {
        val arr = JSONArray()
        for (p in list) arr.put(JSONObject().put("name", p.name)
            .put("mean", JSONArray(p.mean.toList())).put("sd", JSONArray(p.sd.toList())))
        prefs.edit().putString("profiles", arr.toString()).apply()
    }

    private fun load(): List<VoiceProfile> = try {
        val arr = JSONArray(prefs.getString("profiles", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            fun doubles(k: String) = o.getJSONArray(k).let { a -> DoubleArray(a.length()) { i -> a.getDouble(i) } }
            VoiceProfile(o.getString("name"), doubles("mean"), doubles("sd"))
        }
    } catch (_: Exception) { emptyList() }
}

/**
 * A simple voice fingerprint, computed on the phone: the average spectral shape (12 cepstral values) plus the pitch
 * of about a second of voiced speech. Good enough to tell the people at home apart, not a security feature.
 * Enrolling calibrates itself: the spread of the person's own samples sets how close a match must be.
 */
class VoiceID(val profiles: VoiceProfiles) {
    companion object {
        const val RATE = 16_000
        private const val FRAME = 512
        private const val HOP = 256
        private const val WINDOW = 60          // voiced frames per fingerprint (about one second of vowels)
        private const val ENROLL_WINDOWS = 8   // about 20 seconds of talking
        private const val MATCH = 2.8          // how many "own spreads" away still counts as the same voice
        private const val MELS = 24
        private const val COEFS = 12
    }

    /** Called on the main thread when the speaker changes: a name, or null for an unknown voice. */
    var onHeard: ((String?) -> Unit)? = null
    /** Called on the main thread when a new voice has been learned. */
    var onEnrolled: ((VoiceProfile) -> Unit)? = null

    var enrolling by mutableStateOf<String?>(null); private set
    var progress by mutableStateOf(0f); private set
    var message by mutableStateOf(""); private set

    val hasProfiles get() = profiles.list.isNotEmpty()

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var tail = ShortArray(0)
    private var noiseFloor = 200.0
    private val window = ArrayList<DoubleArray>()
    private val enrollWindows = ArrayList<DoubleArray>()
    private var lastCandidate: String? = "?"
    private var candidateCount = 0
    private var lastAnnounced: String? = "?"

    private val hamming = DoubleArray(FRAME) { 0.54 - 0.46 * cos(2 * PI * it / (FRAME - 1)) }
    private val filters = buildFilters()

    // Learning a voice

    /** Starts learning a voice. With [ownRecording] the phone listens by itself; otherwise the live mic feeds it. */
    fun startEnroll(name: String, ownRecording: Boolean) {
        val clean = name.trim()
        if (clean.isEmpty()) { message = "Sag mir erst deinen Namen."; return }
        synchronized(lock) { enrollWindows.clear(); window.clear() }
        message = "Sprich jetzt etwa 20 Sekunden einfach drauflos."
        progress = 0f
        enrolling = clean
        if (ownRecording) record()
    }

    fun cancelEnroll() { enrolling = null; progress = 0f; message = "" }

    @SuppressLint("MissingPermission")
    private fun record() {
        Thread {
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf * 2, RATE))
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release(); message = "Das Mikrofon ist gerade nicht frei."; enrolling = null; return@Thread
            }
            rec.startRecording()
            val buf = ShortArray(1600)
            val start = SystemClock.elapsedRealtime()
            while (enrolling != null && SystemClock.elapsedRealtime() - start < 90_000) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) feed(buf.copyOf(n))
            }
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            if (enrolling != null) { message = "Ich habe zu wenig gehört. Versuch es nochmal."; enrolling = null }
        }.apply { isDaemon = true; start() }
    }

    // Audio in (16 kHz, 16-bit mono)

    fun feed(pcm: ShortArray) {
        if (enrolling == null && profiles.list.isEmpty()) return
        synchronized(lock) {
            val all = tail + pcm
            var pos = 0
            while (pos + FRAME <= all.size) { frame(all, pos); pos += HOP }
            tail = all.copyOfRange(pos, all.size)
        }
    }

    private fun frame(s: ShortArray, pos: Int) {
        var sum = 0.0
        for (i in 0 until FRAME) { val v = s[pos + i].toDouble(); sum += v * v }
        val rms = sqrt(sum / FRAME)
        if (rms < 1.5 * noiseFloor) noiseFloor = 0.98 * noiseFloor + 0.02 * rms
        if (rms < max(250.0, 2.5 * noiseFloor)) return

        // Pitch: strongest repeat of the waveform between 80 and 400 Hz. Frames without a clear pitch are skipped.
        var mean = 0.0
        for (i in 0 until FRAME) mean += s[pos + i]
        mean /= FRAME
        val x = DoubleArray(FRAME) { s[pos + it] - mean }
        var r0 = 0.0
        for (v in x) r0 += v * v
        if (r0 <= 0.0) return
        var bestLag = 0; var best = 0.0
        for (lag in 40..200) {
            var r = 0.0
            for (i in 0 until FRAME - lag) r += x[i] * x[i + lag]
            if (r > best) { best = r; bestLag = lag }
        }
        if (bestLag == 0 || best / r0 < 0.4) return
        val f0 = RATE.toDouble() / bestLag

        // Spectrum → mel energies → cepstrum.
        val re = DoubleArray(FRAME) { x[it] * hamming[it] }
        val im = DoubleArray(FRAME)
        fft(re, im)
        val power = DoubleArray(FRAME / 2 + 1) { re[it] * re[it] + im[it] * im[it] }
        val logMel = DoubleArray(MELS) { m ->
            var e = 0.0
            for (k in power.indices) e += filters[m][k] * power[k]
            ln(e + 1e-6)
        }
        val feature = DoubleArray(COEFS + 1)
        for (c in 1..COEFS) {
            var v = 0.0
            for (m in 0 until MELS) v += logMel[m] * cos(PI * c * (m + 0.5) / MELS)
            feature[c - 1] = v
        }
        feature[COEFS] = ln(f0)
        window.add(feature)
        if (window.size >= WINDOW) {
            val emb = DoubleArray(COEFS + 1)
            for (f in window) for (i in emb.indices) emb[i] += f[i] / window.size
            window.clear()
            fingerprint(emb)
        }
    }

    private fun fingerprint(emb: DoubleArray) {
        val name = enrolling
        if (name != null) {
            enrollWindows.add(emb)
            progress = enrollWindows.size / ENROLL_WINDOWS.toFloat()
            if (enrollWindows.size >= ENROLL_WINDOWS) finishEnroll(name)
            return
        }
        val list = profiles.list
        if (list.isEmpty()) return
        val nearest = list.minByOrNull { distance(emb, it) } ?: return
        val candidate = if (distance(emb, nearest) <= MATCH) nearest.name else null
        // The same answer twice in a row, and different from what was announced last.
        if (candidate == lastCandidate) candidateCount += 1 else { lastCandidate = candidate; candidateCount = 1 }
        if (candidateCount >= 2 && candidate != lastAnnounced) {
            lastAnnounced = candidate
            main.post { onHeard?.invoke(candidate) }
        }
    }

    private fun finishEnroll(name: String) {
        val n = enrollWindows.size
        val dims = COEFS + 1
        val mean = DoubleArray(dims)
        for (w in enrollWindows) for (i in 0 until dims) mean[i] += w[i] / n
        val sd = DoubleArray(dims)
        for (w in enrollWindows) for (i in 0 until dims) sd[i] += (w[i] - mean[i]).let { it * it } / n
        for (i in 0 until dims) sd[i] = sqrt(sd[i])
        val average = sd.average()
        for (i in 0 until dims) sd[i] = max(sd[i], 0.35 * average).coerceAtLeast(1e-3)
        val profile = VoiceProfile(name, mean, sd)
        enrollWindows.clear()
        enrolling = null
        progress = 1f
        message = "Fertig, ich kenne jetzt deine Stimme, $name."
        lastCandidate = "?"; lastAnnounced = "?"; candidateCount = 0
        main.post { profiles.add(profile); onEnrolled?.invoke(profile) }
    }

    private fun distance(x: DoubleArray, p: VoiceProfile): Double {
        var sum = 0.0
        for (i in x.indices) { val d = (x[i] - p.mean[i]) / p.sd[i]; sum += d * d }
        return sqrt(sum / x.size)
    }

    // Signal processing

    private fun buildFilters(): Array<DoubleArray> {
        fun mel(f: Double) = 2595 * log10(1 + f / 700)
        fun hz(m: Double) = 700 * (10.0.pow(m / 2595) - 1)
        val lo = mel(100.0); val hi = mel(5500.0)
        val bins = IntArray(MELS + 2) { ((FRAME + 1) * hz(lo + (hi - lo) * it / (MELS + 1)) / RATE).toInt().coerceIn(0, FRAME / 2) }
        return Array(MELS) { m ->
            val f = DoubleArray(FRAME / 2 + 1)
            val a = bins[m]; val b = bins[m + 1]; val c = bins[m + 2]
            for (k in a until b) if (b > a) f[k] = (k - a).toDouble() / (b - a)
            for (k in b..c) if (c > b) f[k] = (c - k).toDouble() / (c - b)
            f
        }
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
