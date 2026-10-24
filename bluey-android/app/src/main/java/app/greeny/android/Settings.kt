package app.greeny.android

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray

/** The Gemini key (stored encrypted on this phone), the chosen color and a few switches. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = try {
        val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "greeny_secure", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        context.getSharedPreferences("greeny_secure_fallback", Context.MODE_PRIVATE)
    }

    private var keyState by mutableStateOf(prefs.getString("geminiKey", "") ?: "")
    private var seeMeState by mutableStateOf(prefs.getBoolean("seeMe", true))
    private var wakeWordState by mutableStateOf(prefs.getBoolean("wakeWord", true))
    private var languageState by mutableStateOf(prefs.getString("language", "") ?: "")
    private var wakeModeState by mutableStateOf(prefs.getString("wakeMode", "gemini") ?: "gemini")
    private var sensitivityState by mutableStateOf(prefs.getFloat("wakeSensitivity", 1.0f))

    private var notesState by mutableStateOf(loadNotes())

    /** What he has been asked to remember, newest last. */
    val notes: List<String> get() = notesState

    fun addNote(note: String) {
        notesState = (notesState + note.take(200)).takeLast(60)
        saveNotes()
    }

    /** Removes every note that contains [text]. Returns how many went. */
    fun removeNotes(text: String): Int {
        if (text.isBlank()) return 0
        val keep = notesState.filter { !it.contains(text, ignoreCase = true) }
        val removed = notesState.size - keep.size
        notesState = keep
        saveNotes()
        return removed
    }

    fun removeNoteAt(index: Int) {
        notesState = notesState.filterIndexed { i, _ -> i != index }
        saveNotes()
    }

    private fun saveNotes() { prefs.edit().putString("notes", JSONArray(notesState).toString()).apply() }

    private fun loadNotes(): List<String> = try {
        val a = JSONArray(prefs.getString("notes", "[]"))
        (0 until a.length()).map { a.getString(it) }
    } catch (_: Exception) { emptyList() }

    init { Palette.skin = Skins.byId(prefs.getString("color", "green")) }

    var apiKey: String
        get() = keyState
        set(value) { keyState = value.trim(); prefs.edit().putString("geminiKey", keyState).apply() }

    /** Which berry he is. Changes his look, his name, the wake words and his voice. */
    var skin: Skin
        get() = Palette.skin
        set(value) { Palette.skin = value; prefs.edit().putString("color", value.id).apply() }

    /** Let him see you through the front camera while he's awake (a small picture every few seconds). */
    var seeMe: Boolean
        get() = seeMeState
        set(value) { seeMeState = value; prefs.edit().putBoolean("seeMe", value).apply() }

    /** Wake him by saying "Hey <name>". */
    var wakeWord: Boolean
        get() = wakeWordState
        set(value) { wakeWordState = value; prefs.edit().putBoolean("wakeWord", value).apply() }

    /**
     * How he hears "Hey <name>" while asleep: "gemini" (silent mic, short sounds are checked by Gemini, no training),
     * "own" (silent mic, compared with your own recordings, only on the phone) or "system" (the phone's speech recognizer, beeps).
     */
    var wakeMode: String
        get() = wakeModeState
        set(value) { wakeModeState = value; prefs.edit().putString("wakeMode", value).apply() }

    /** How lenient the taught wake phrase is: 0.7 (strict) … 1.5 (lenient). */
    var wakeSensitivity: Float
        get() = sensitivityState
        set(value) { sensitivityState = value; prefs.edit().putFloat("wakeSensitivity", value).apply() }

    /** The language he speaks, and only that one. Empty until the user has chosen. */
    var languageCode: String
        get() = languageState
        set(value) { languageState = value; prefs.edit().putString("language", value).apply() }

    val languageChosen get() = languageState.isNotEmpty()
    val lang: Lang get() = Languages.byCode(languageState)

    val hasKey get() = keyState.isNotBlank()
}
