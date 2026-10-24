package app.greeny.android

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Asks Gemini whether a short burst of sound contains the wake phrase ("Hey Greeny" …). This is what makes waking up
 * by voice reliable without any recording or training. Only short bursts of speech (under 2.5 seconds) are sent,
 * at most one every few seconds, and only while he sleeps and the app is open.
 */
object GeminiWake {
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    @Volatile private var pausedUntil = 0L

    /** Checks on a background thread; [done] gets true if the phrase was said. */
    fun check(key: String, skin: Skin, pcm: ShortArray, done: (Boolean) -> Unit) {
        if (System.currentTimeMillis() < pausedUntil || key.isBlank()) { done(false); return }
        Thread {
            val ok = try { ask(key, skin, pcm) } catch (_: Exception) { false }
            done(ok)
        }.start()
    }

    private fun wav(pcm: ShortArray): ByteArray {
        val data = pcm.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        for (v in pcm) b.putShort(v)
        return b.array()
    }

    private fun ask(key: String, skin: Skin, pcm: ShortArray): Boolean {
        val sounds = skin.wakeVariants.take(6).joinToString(", ")
        val prompt = "Das ist eine sehr kurze Tonaufnahme aus einem Zimmer. Sagt jemand deutlich „Hey ${skin.name}“ oder ruft den Namen " +
            "${skin.name} (er kann auch so klingen oder geschrieben werden: $sounds), um ein kleines Assistenten-Wesen zu wecken? " +
            "Antworte nur mit JA oder NEIN. Wenn nur Hintergrundgeräusche, Musik, Fernseher oder ein anderes Gespräch zu hören sind, " +
            "oder du unsicher bist: NEIN."
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                .put(JSONObject().put("text", prompt))
                .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav")
                    .put("data", Base64.encodeToString(wav(pcm), Base64.NO_WRAP)))))))
            .put("generationConfig", JSONObject().put("temperature", 0)
                .put("thinkingConfig", JSONObject().put("thinkingLevel", "low")))
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${Agent.RESEARCH_MODEL}:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: ""
            if (response.code == 429) { pausedUntil = System.currentTimeMillis() + 60_000; return false }
            if (!response.isSuccessful) return false
            val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: return false
            var answer = ""
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (!p.optBoolean("thought", false)) answer += p.optString("text", "")
            }
            return Regex("\\bJA\\b", RegexOption.IGNORE_CASE).containsMatchIn(answer) &&
                !Regex("\\bNEIN\\b", RegexOption.IGNORE_CASE).containsMatchIn(answer)
        }
    }
}
