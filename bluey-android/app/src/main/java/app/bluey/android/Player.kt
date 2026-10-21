package app.bluey.android

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Plays his voice: 24 kHz 16-bit mono PCM chunks from Gemini, one after another, with a loudness reading for his mouth.
 * If the speaker stops moving (route change, audio server restart …) the track is rebuilt, so he never stays stuck.
 */
class Player {
    companion object { const val RATE = 24_000 }

    private val lock = Any()
    private var track: AudioTrack? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var written = 0L
    @Volatile private var lastHead = 0
    @Volatile private var lastHeadAt = 0L
    @Volatile private var lastWriteAt = 0L
    @Volatile var level = 0.0; private set

    var gain = 1f
        set(value) { field = value; track?.setVolume(value) }

    private fun head(): Int {
        val t = track ?: return 0
        val h = try { t.playbackHeadPosition } catch (_: Exception) { return lastHead }
        if (h != lastHead) { lastHead = h; lastHeadAt = SystemClock.elapsedRealtime() }
        return h
    }

    /** True while his voice is queued or still coming out of the speaker (and the speaker really is moving). */
    val busy: Boolean
        get() {
            if (!running || track == null) return false
            val pending = queue.isNotEmpty() || written - head() > RATE / 10
            // Neither the speaker nor we moved for a while: the audio is stuck, so don't keep the mic muted.
            val alive = SystemClock.elapsedRealtime() - max(lastHeadAt, lastWriteAt) < 1500
            return pending && alive
        }

    private fun build(): AudioTrack? = try {
        val minBuffer = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(max(minBuffer * 2, RATE))  // about half a second
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.setVolume(gain)
        t.play()
        t
    } catch (_: Exception) {
        null
    }

    /** Replaces the AudioTrack with a fresh one and forgets what was queued. */
    private fun rebuild() {
        synchronized(lock) {
            queue.clear()
            val old = track
            track = build()
            written = 0
            lastHead = 0
            lastHeadAt = SystemClock.elapsedRealtime()
            old?.let { try { it.pause(); it.flush() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
            level = 0.0
        }
    }

    fun start() {
        if (running) return
        synchronized(lock) {
            track = build()
            written = 0
            lastHead = 0
            lastHeadAt = SystemClock.elapsedRealtime()
        }
        running = true
        thread = Thread {
            while (running) {
                val chunk = queue.poll(100, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    level = 0.0
                    // Audio is still "pending" but nothing moves: the speaker is stuck. Start over with a new track.
                    val now = SystemClock.elapsedRealtime()
                    if (written - head() > 0 && now - max(lastHeadAt, lastWriteAt) > 2500) rebuild()
                    continue
                }
                var offset = 0
                while (offset < chunk.size && running) {
                    val n = min(960, chunk.size - offset)  // 20 ms pieces, so the mouth can follow along
                    var sum = 0.0
                    var i = offset
                    while (i + 1 < offset + n) {
                        val v = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xFF)).toShort().toInt() / 32768.0
                        sum += v * v
                        i += 2
                    }
                    level = min(1.0, sqrt(sum / (n / 2)) * 5.0)
                    val t = track
                    if (t == null) { rebuild(); break }
                    val w = try { t.write(chunk, offset, n) } catch (_: Exception) { -1 }
                    if (w <= 0) { rebuild(); break }
                    written += w / 2
                    lastWriteAt = SystemClock.elapsedRealtime()
                    offset += w
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun write(pcm: ByteArray) { if (running) queue.offer(pcm) }

    /** He was interrupted: drop everything that's still queued. */
    fun clear() { if (running) rebuild() }

    fun stop() {
        running = false
        queue.clear()
        try { thread?.join(300) } catch (_: Exception) {}
        thread = null
        synchronized(lock) {
            track?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
            track = null
        }
        level = 0.0
    }
}
