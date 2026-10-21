package app.bluey.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

enum class EntryKind(val raw: String) {
    HEARD("heard"), ASKED("asked"), REPLY("reply"), REPORT("report"), TRANSCRIPT("transcript");
    companion object { fun from(raw: String) = entries.firstOrNull { it.raw == raw } ?: HEARD }
}

data class TranscriptEntry(
    val id: String = UUID.randomUUID().toString(),
    val kind: EntryKind,
    val text: String,
    val timeMs: Long,
    val itemID: String? = null,
    val speaker: String? = null,
)

data class PendingChunk(val index: Int, val startedMs: Long)

data class BlueySession(
    val id: String = UUID.randomUUID().toString().uppercase(),
    val startedMs: Long,
    val endedMs: Long? = null,
    val entries: List<TranscriptEntry> = emptyList(),
    val notesPath: String? = null,
    val pending: List<PendingChunk> = emptyList(),
) {
    val questionCount get() = entries.count { it.kind == EntryKind.ASKED }
    val transcribing get() = pending.size
    val durationMs get() = (endedMs ?: System.currentTimeMillis()) - startedMs

    /** The proper transcript where it exists, rough live lines after it, plus questions, replies, reports. */
    val displayEntries: List<TranscriptEntry>
        get() {
            val properUntil = entries.filter { it.kind == EntryKind.TRANSCRIPT }.maxOfOrNull { it.timeMs } ?: Long.MIN_VALUE
            return entries
                .filter { it.text.isNotEmpty() && !(it.kind == EntryKind.HEARD && it.timeMs <= properUntil) }
                .sortedBy { it.timeMs }
        }

    val summary: String
        get() = (entries.firstOrNull { it.kind == EntryKind.ASKED && it.text.isNotEmpty() }
            ?: displayEntries.firstOrNull { it.kind == EntryKind.TRANSCRIPT || it.kind == EntryKind.HEARD })?.text
            ?: "Noch nichts gesagt"

    val exportText: String
        get() {
            val fmt = DateFormat.getTimeInstance(DateFormat.MEDIUM)
            val lines = mutableListOf("Bluey Gespräch, ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(startedMs))}", "")
            for (e in displayEntries) {
                val who = when (e.kind) {
                    EntryKind.TRANSCRIPT -> "Sprecher ${e.speaker ?: "?"}"
                    EntryKind.HEARD -> "Du"
                    EntryKind.ASKED -> "Du"
                    EntryKind.REPLY -> Palette.skin.name
                    EntryKind.REPORT -> "Recherche"
                }
                lines.add("[${fmt.format(Date(e.timeMs))}] $who: ${e.text}")
            }
            return lines.joinToString("\n")
        }
}

/** Keeps every session on the phone, as one JSON file in the app's files folder. */
class SessionStore(context: Context) {
    private val file = File(context.filesDir, "sessions.json")
    private val main = Handler(Looper.getMainLooper())

    var sessions by mutableStateOf<List<BlueySession>>(emptyList()); private set  // newest first
    var currentID by mutableStateOf<String?>(null); private set

    var onSync: ((List<BlueySession>) -> Unit)? = null
    private val dirty = HashSet<String>()
    private var syncWork: Runnable? = null
    private var saveWork: Runnable? = null

    init {
        sessions = load().map { s -> if (s.endedMs == null) s.copy(endedMs = s.entries.lastOrNull()?.timeMs ?: s.startedMs) else s }
    }

    val current: BlueySession? get() = sessions.firstOrNull { it.id == currentID }

    fun start() {
        end()
        val s = BlueySession(startedMs = System.currentTimeMillis())
        sessions = listOf(s) + sessions
        currentID = s.id
        save()
    }

    fun end() {
        val id = currentID ?: return
        val s = sessions.firstOrNull { it.id == id } ?: return
        currentID = null
        if (s.entries.all { it.text.isEmpty() } && s.transcribing == 0 && System.currentTimeMillis() - s.startedMs < 20_000) {
            sessions = sessions.filter { it.id != id }
        } else {
            replace(id) { it.copy(endedMs = System.currentTimeMillis(), entries = it.entries.filter { e -> e.text.isNotEmpty() }) }
            dirty.add(id); scheduleSync()
        }
        save()
    }

    fun placeholder(itemID: String, asked: Boolean) = update { s ->
        if (s.entries.any { it.itemID == itemID }) s
        else s.copy(entries = s.entries + TranscriptEntry(kind = if (asked) EntryKind.ASKED else EntryKind.HEARD, text = "", timeMs = System.currentTimeMillis(), itemID = itemID))
    }

    fun heard(itemID: String, rawText: String, asked: Boolean) {
        val text = rawText.trim()
        update { s ->
            val i = s.entries.indexOfFirst { it.itemID == itemID }
            when {
                i >= 0 -> s.copy(entries = s.entries.toMutableList().also {
                    it[i] = it[i].copy(text = text, kind = if (asked) EntryKind.ASKED else it[i].kind)
                })
                text.isNotEmpty() -> s.copy(entries = s.entries + TranscriptEntry(kind = if (asked) EntryKind.ASKED else EntryKind.HEARD, text = text, timeMs = System.currentTimeMillis(), itemID = itemID))
                else -> s
            }
        }
    }

    fun reply(rawText: String) {
        val text = rawText.trim(); if (text.isEmpty()) return
        update { it.copy(entries = it.entries + TranscriptEntry(kind = EntryKind.REPLY, text = text, timeMs = System.currentTimeMillis())) }
    }

    fun report(rawText: String) {
        val text = rawText.trim(); if (text.isEmpty()) return
        update { it.copy(entries = it.entries + TranscriptEntry(kind = EntryKind.REPORT, text = text, timeMs = System.currentTimeMillis())) }
    }

    // Proper transcript (recorded audio, transcribed on the Mac)

    fun addPending(session: String, index: Int, startedMs: Long) = update(session) { s ->
        if (s.pending.any { it.index == index }) s else s.copy(pending = s.pending + PendingChunk(index, startedMs))
    }

    fun transcribed(session: String, index: Int, startedMs: Long, segments: List<Triple<String, Double, String>>) = update(session) { s ->
        var entries = s.entries
        for ((speaker, start, raw) in segments) {
            val text = raw.trim(); if (text.isEmpty()) continue
            entries = entries + TranscriptEntry(kind = EntryKind.TRANSCRIPT, text = text, timeMs = startedMs + (start * 1000).toLong(), speaker = speaker)
        }
        s.copy(pending = s.pending.filter { it.index != index }, entries = entries.sortedBy { it.timeMs })
    }

    fun setNotesPath(session: String, path: String) {
        val s = sessions.firstOrNull { it.id == session } ?: return
        if (s.notesPath == path) return
        replace(session) { it.copy(notesPath = path) }
        scheduleSave()
    }

    fun recentText(limit: Int = 6000): String? {
        val cur = current ?: return null
        val text = cur.displayEntries.filter { it.kind != EntryKind.REPORT }
            .joinToString("\n") { (if (it.kind == EntryKind.REPLY) "Bluey: " else "") + it.text }
        return if (text.isEmpty()) null else text.takeLast(limit)
    }

    fun resyncRecent() {
        val cutoff = System.currentTimeMillis() - 3 * 86_400_000L
        dirty.addAll(sessions.filter { it.startedMs > cutoff }.map { it.id })
        scheduleSync()
    }

    fun delete(id: String) {
        if (id == currentID) currentID = null
        sessions = sessions.filter { it.id != id }
        save()
    }

    private fun scheduleSync() {
        syncWork?.let { main.removeCallbacks(it) }
        val w = Runnable {
            val changed = sessions.filter { it.id in dirty }
            dirty.clear()
            if (changed.isNotEmpty()) onSync?.invoke(changed)
        }
        syncWork = w
        main.postDelayed(w, 6000)
    }

    private fun update(change: (BlueySession) -> BlueySession) { currentID?.let { update(it, change) } }

    private fun update(id: String, change: (BlueySession) -> BlueySession) {
        if (sessions.none { it.id == id }) return
        replace(id, change)
        dirty.add(id); scheduleSync(); scheduleSave()
    }

    private fun replace(id: String, change: (BlueySession) -> BlueySession) {
        sessions = sessions.map { if (it.id == id) change(it) else it }
    }

    private fun scheduleSave() {
        saveWork?.let { main.removeCallbacks(it) }
        val w = Runnable { save() }
        saveWork = w
        main.postDelayed(w, 1000)
    }

    private fun save() {
        saveWork?.let { main.removeCallbacks(it) }
        try {
            val tmp = File(file.parentFile, "sessions.json.tmp")
            tmp.writeText(JSONArray(sessions.map { it.toJson() }).toString())
            tmp.renameTo(file)
        } catch (_: Exception) {}
    }

    private fun load(): List<BlueySession> = try {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { sessionFromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    private fun BlueySession.toJson() = JSONObject()
        .put("id", id).put("started", startedMs).put("ended", endedMs ?: JSONObject.NULL)
        .put("notesPath", notesPath ?: JSONObject.NULL)
        .put("entries", JSONArray(entries.map {
            JSONObject().put("id", it.id).put("kind", it.kind.raw).put("text", it.text).put("time", it.timeMs)
                .put("itemID", it.itemID ?: JSONObject.NULL).put("speaker", it.speaker ?: JSONObject.NULL)
        }))
        .put("pending", JSONArray(pending.map { JSONObject().put("index", it.index).put("started", it.startedMs) }))

    private fun sessionFromJson(o: JSONObject): BlueySession {
        fun JSONObject.str(k: String) = if (has(k) && !isNull(k)) getString(k) else null
        val entries = o.getJSONArray("entries")
        val pending = o.optJSONArray("pending") ?: JSONArray()
        return BlueySession(
            id = o.getString("id"),
            startedMs = o.getLong("started"),
            endedMs = if (o.isNull("ended")) null else o.getLong("ended"),
            notesPath = o.str("notesPath"),
            entries = (0 until entries.length()).map {
                val e = entries.getJSONObject(it)
                TranscriptEntry(e.getString("id"), EntryKind.from(e.getString("kind")), e.getString("text"), e.getLong("time"), e.str("itemID"), e.str("speaker"))
            },
            pending = (0 until pending.length()).map { PendingChunk(pending.getJSONObject(it).getInt("index"), pending.getJSONObject(it).getLong("started")) },
        )
    }
}
