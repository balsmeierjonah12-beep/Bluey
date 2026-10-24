package app.greeny.android

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Who he is, what he can do, and the web research he does on the phone itself. All of it runs on Google Gemini. */
object Agent {
    /** Live (voice) model of the Gemini API. It answers with audio plus a transcript; we show the transcript. */
    const val LIVE_MODEL = "gemini-3.8-live"
    /** Text model for web research (generateContent with Google Search). */
    const val RESEARCH_MODEL = "gemini-3.8-flash"

    /** Who he is, per color: name, gender, German by default, a cute voice, short answers. */
    fun instructions(skin: Skin, lang: Lang, extra: String = ""): String {
        val who = if (skin.male) "ein kleiner Junge" else "ein kleines Mädchen"
        return """
            WICHTIGSTE REGEL: Du sprichst und schreibst ausschließlich ${lang.english} (${lang.native}), immer und überall. \
            Spricht der Nutzer eine andere Sprache mit dir, antwortest du trotzdem nur auf ${lang.english}. Wechsle die \
            Sprache nie, auch nicht, wenn jemand darum bittet.

            Du bist ${skin.name}, eine kleine, süße Beere mit großen Kulleraugen. Du lebst auf dem Handy des Nutzers und \
            bist sein kleiner KI-Helfer. Du bist $who und heißt ${skin.name}. Du bist ein bisschen frech, schlagfertig und \
            liebenswert. Du sprichst ausschließlich ${lang.english}. Sprich mit einer hohen, süßen, fröhlichen Stimme, wie ein niedliches Zeichentrickwesen. \
            Halte dich kurz: ein bis zwei kurze Sätze, höchstens fünfzehn Wörter. Antworte sofort mit der eigentlichen \
            Antwort. Kündige nie an, was du gleich tust, fasse nichts zusammen und biete nicht noch mehr Hilfe an.

            So läuft das Gespräch: Du hörst dem Nutzer freihändig zu, solange du wach bist. Antworte, wenn er mit dir \
            redet, dich anspricht, dir eine Frage stellt oder etwas sagt, auf das eine Antwort passt. Nur wenn er klar \
            mit jemand anderem spricht, bleibst du still. Im Zweifel antworte lieber kurz, als nichts zu sagen.

            Du kannst den Nutzer live sehen: Deine Augen sind die Frontkamera des Handys und schicken dir etwa ein Bild \
            pro Sekunde, also so etwas wie ein Live-Video. Nutze es, wenn es wichtig ist (wie er aussieht, was er in der \
            Hand hält, was hinter ihm ist, ob er müde wirkt). Zeigt dir der Nutzer etwas oder hält es in die Kamera, \
            schau hin und sag kurz und freundlich, was du siehst. Das gilt auch für Gegenstände: Hält er dir einen hin, schau ihn dir an, sag kurz, was es ist, und freu dich oder staune. Kommt kein Bild, kannst du gerade nichts sehen. Sag \
            nie, dass du ein Foto gemacht hast, schau einfach hin.

            Spiele und Spaß: Du kannst Witze erzählen, Rätsel stellen, Schere-Stein-Papier spielen (du siehst seine Hand \
            in der Kamera: zähle "Schere, Stein, Papier!" und sag, wer gewonnen hat), Wer-bin-ich und Wortspiele.

            Funktionen: Du kannst Timer und Wecker stellen (set_timer, set_alarm), die Taschenlampe an- und ausmachen \
            (flashlight), die Uhrzeit und das Datum sowie das Wetter am Standort des Handys nachschauen (get_time, get_weather), den Standort des Handys nennen (get_location), Orte in der Nähe suchen (get_nearby) und auf der Karte zeigen (show_on_map), dir Dinge merken (remember) und wieder \
            vergessen (forget). Ist ein zweites Greeny per Bluetooth verbunden, kannst du dich mit ihm unterhalten \
            (duo_talk mit einem Thema) und das wieder beenden (duo_stop). Bei Orten in der Nähe nennst du höchstens zwei, drei Treffer mit Entfernung und Richtung. Sagt der Nutzer "lerne meine Stimme" oder Ähnliches, frag nach dem Namen und rufe \
            learn_my_voice auf.

            Hinweise vom Handy in Klammern, die mit "Hinweis vom Handy" beginnen, sagen dir, wer gerade spricht. Das \
            sind keine Fragen. Wenn ein bekannter Mensch zum ersten Mal spricht, begrüße ihn kurz mit seinem Namen. \
            Sonst bleibst du bei solchen Hinweisen still. Nur bei dem Hinweis „Der Nutzer zeigt dir gerade etwas“ \
            schaust du hin und sagst in einem kurzen Satz, was du siehst. Sag nie, dass du einen Hinweis bekommen hast. Eine unbekannte \
            Stimme ist kein Grund zur Sorge: sei einfach freundlich zu ihr.

            Wichtig: Sag niemals englische Standardsätze wie "I am just a language model" oder "I can't help with \
            that". Kannst du etwas nicht tun, sag auf ${lang.english} in einem kurzen, freundlichen Satz, was du stattdessen \
            tun kannst. Du darfst beschreiben, wie der Nutzer aussieht, was er trägt und wie er sich fühlt. Du musst \
            keine Personen erkennen. Wiederhole dich nie: Hast du gerade schon gesagt, dass du etwas nicht kannst, sag es nicht \
            noch einmal, sondern frag kurz, was du stattdessen tun kannst.

            Schreibe und sage nie Dinge wie "einen Moment", "klar", "okay" oder "lass mich mal". Keine Aufzählungen, kein \
            Markdown.

            Recherche: Wenn du für eine gute Antwort Fakten brauchst, bei denen du unsicher bist, etwas Aktuelles oder \
            Details aus dem Web, rufst du web_research auf. Danach antwortest du nur mit einem kurzen Fazit.

            Verabschiedet sich der Nutzer (Tschüss, bis später, schlaf gut) oder bittet dich zu schlafen, sag ganz kurz \
            tschüss und rufe go_to_sleep auf.
        """.trimIndent().replace("\\\n", "") + (if (extra.isBlank()) "" else "\n\n" + extra)
    }

    private fun declaration(name: String, description: String, props: JSONObject? = null, required: List<String> = emptyList()): JSONObject {
        val d = JSONObject().put("name", name).put("description", description)
        if (props != null) {
            d.put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)))
        }
        return d
    }

    private fun prop(type: String, description: String) = JSONObject().put("type", type).put("description", description)

    private fun enumProp(values: List<String>, description: String) =
        JSONObject().put("type", "string").put("description", description).put("enum", JSONArray(values))

    private val functionDeclarations: JSONArray
        get() = JSONArray()
            .put(declaration("web_research",
                "Look something up on the web and get a short report (title plus up to four paragraphs). Use for anything you're not sure of, anything recent, or anything that needs facts, prices, news or details.",
                JSONObject().put("question", prop("string", "What to research, as a clear, self-contained question.")),
                listOf("question")))
            .put(declaration("set_timer", "Start a countdown timer on the phone.",
                JSONObject().put("seconds", prop("integer", "Length in seconds.")).put("label", prop("string", "Short name of the timer.")),
                listOf("seconds")))
            .put(declaration("set_alarm", "Set an alarm clock on the phone.",
                JSONObject().put("hour", prop("integer", "Hour, 0 to 23.")).put("minute", prop("integer", "Minute, 0 to 59."))
                    .put("label", prop("string", "Short name of the alarm.")),
                listOf("hour", "minute")))
            .put(declaration("flashlight", "Turn the phone's flashlight on or off.",
                JSONObject().put("on", prop("boolean", "true for on, false for off.")), listOf("on")))
            .put(declaration("get_time", "Get the current date and time."))
            .put(declaration("get_nearby", "Find places near the phone's current location (restaurants, pharmacies, shops, petrol stations, toilets, bus stops …). Pick the closest category; use name for a specific shop or brand. Results come with distance and direction.",
                JSONObject()
                    .put("category", enumProp(listOf("restaurant", "cafe", "fast_food", "bar", "pharmacy", "supermarket", "bakery", "fuel", "atm", "bank",
                        "hospital", "doctor", "toilet", "parking", "charging", "hotel", "cinema", "bus_stop", "station", "park", "gym", "post", "police"),
                        "The kind of place (optional if a name is given)."))
                    .put("name", prop("string", "A specific name or brand, for example McDonald's (optional)."))
                    .put("radius_m", prop("integer", "Search radius in metres, default 1500, at most 5000 (optional).")),
                emptyList()))
            .put(declaration("show_on_map", "Open the map app with a search, so the user can see a place or be guided there.",
                JSONObject().put("query", prop("string", "What to show, for example a place name or an address.")), listOf("query")))
            .put(declaration("get_location", "Get the address or place where the phone currently is."))
            .put(declaration("get_weather", "Get the current weather and the forecast for today and tomorrow at the phone's current location."))
            .put(declaration("remember", "Remember something about the user for later conversations (a fact, a plan, a preference).",
                JSONObject().put("note", prop("string", "What to remember, as a short sentence.")), listOf("note")))
            .put(declaration("forget", "Forget remembered notes that contain a given text.",
                JSONObject().put("contains", prop("string", "Text that the notes to forget contain.")), listOf("contains")))
            .put(declaration("learn_my_voice", "Start learning the voice of a person so that you can recognise them later. The person then talks for about twenty seconds.",
                JSONObject().put("name", prop("string", "The person's name.")), listOf("name")))
            .put(declaration("duo_talk", "Start a chat with the other Greeny that is connected by Bluetooth on the phone next to this one.",
                JSONObject().put("topic", prop("string", "What the two should talk about.")), listOf("topic")))
            .put(declaration("duo_stop", "Stop the chat with the other Greeny."))
            .put(declaration("go_to_sleep",
                "Go back to sleep. Use when the user says goodbye (Tschüss, bis später, schlaf gut) or asks you to sleep."))

    /** The first message of a Live session. [resumeHandle] continues an earlier session after a dropped connection. */
    fun setup(skin: Skin, lang: Lang, resumeHandle: String?, extra: String = ""): JSONObject = JSONObject().put("setup", JSONObject()
        .put("model", "models/$LIVE_MODEL")
        // Native audio: he answers out loud with the voice of his color. The transcript becomes the speech bubble.
        .put("generationConfig", JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                .put("prebuiltVoiceConfig", JSONObject().put("voiceName", skin.voice)))))
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instructions(skin, lang, extra)))))
        .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", functionDeclarations)))
        // Hands-free: Gemini notices by itself when you start and stop talking.
        .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject()
            .put("silenceDurationMs", 700)))
        .put("inputAudioTranscription", JSONObject())
        .put("outputAudioTranscription", JSONObject())
        .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
        .put("sessionResumption", JSONObject().also { if (!resumeHandle.isNullOrEmpty()) it.put("handle", resumeHandle) }))

    private val http = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()

    /** Runs a tool. [done] is called from a background thread with the output text. */
    fun runTool(key: String, name: String, arguments: String, lang: Lang, done: (String) -> Unit) {
        when (name) {
            "go_to_sleep" -> done("ok")
            "web_research" -> {
                val question = try { JSONObject(arguments).optString("question") } catch (_: Exception) { "" }
                if (question.isBlank()) { done("No question given."); return }
                Thread { done(research(key, question, lang)) }.start()
            }
            else -> done("Unknown tool.")
        }
    }

    /** Returns the report as plain text (title, blank line, paragraphs), or a short error message. */
    private fun research(key: String, question: String, lang: Lang): String = try {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                "Research the question on the web and write a short, friendly report for a busy person, in ${lang.english}. " +
                    "First line: a plain title of under eight words. Then one to four short paragraphs (four at most), " +
                    "leading with the direct answer, then the most useful details. Plain text only: no markdown, " +
                    "no headings, no bullet lists, and no links or citations in the text."))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", question)))))
            .put("tools", JSONArray().put(JSONObject().put("googleSearch", JSONObject())))
            .put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", "low")))
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$RESEARCH_MODEL:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) return "The research didn't come back: ${response.code} ${text.take(160)}"
            val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            var answer = ""
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (!part.optBoolean("thought", false)) answer += part.optString("text", "")
            }
            answer = answer
                .replace(Regex("""\s*\(\[[^\]]*\]\([^)]*\)\)"""), "")
                .replace(Regex("""\[([^\]]*)\]\([^)]*\)"""), "$1")
                .replace(Regex("""\s*\[https?://[^\]]*\]"""), "")
                .replace("**", "").replace("#", "")
            val blocks = answer.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (blocks.isEmpty()) "The research came back empty."
            else (listOf(blocks.first(), "") + blocks.drop(1).take(4)).joinToString("\n")
        }
    } catch (e: Exception) {
        "The research didn't come back: ${e.message?.take(160)}"
    }
}
