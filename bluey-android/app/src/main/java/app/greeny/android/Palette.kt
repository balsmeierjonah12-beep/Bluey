package app.greeny.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** One color of berry: its look, its name, the words that wake it, and its voice. */
data class Skin(
    val id: String,
    val name: String,          // Greeny, Pinky, Greeny …
    val colorName: String,     // shown in German in the settings
    val c1: Color, val c2: Color, val c3: Color, val c4: Color,
    val nose: Color,
    val male: Boolean,
    val voice: String,         // Gemini prebuilt voice
    val wakeVariants: List<String>,  // how a speech recognizer might write the name
)

object Skins {
    val blaubi = Skin("blue", "Blaubi", "Blau",
        Color(0xFFA9BCFF), Color(0xFF6C86F5), Color(0xFF4254D6), Color(0xFF2B2F8F), Color(0xFF1C1F66),
        male = true, voice = "Puck",
        wakeVariants = listOf("blaubi", "blaubie", "blaubii", "blauby", "blaupi", "plaubi", "blau bi", "blau bie", "blauwi", "blaube"))
    val pinky = Skin("pink", "Pinky", "Pink",
        Color(0xFFFFB6D9), Color(0xFFFF7EB8), Color(0xFFE8458E), Color(0xFFA6246A), Color(0xFF6B1546),
        male = false, voice = "Leda",
        wakeVariants = listOf("pinky", "pinki", "pinkie", "pinkey", "pink", "pinke", "pinkii"))
    val greeny = Skin("green", "Greeny", "Grün",
        Color(0xFFB8F0B0), Color(0xFF6FD47C), Color(0xFF38A84F), Color(0xFF1F6E3A), Color(0xFF134A26),
        male = true, voice = "Fenrir",
        wakeVariants = listOf("greeny", "greeni", "grini", "grüni", "gryni", "grieni", "greenie", "green", "grien", "grienie", "grinie", "grünie"))
    val purply = Skin("purple", "Purply", "Lila",
        Color(0xFFDCC1FF), Color(0xFFB088F5), Color(0xFF8650D6), Color(0xFF553094), Color(0xFF36195F),
        male = false, voice = "Aoede",
        wakeVariants = listOf("purply", "purpli", "purpley", "purple", "pörpli", "perpli", "purplie"))
    val orangy = Skin("orange", "Orangy", "Orange",
        Color(0xFFFFD6A0), Color(0xFFFFAA5C), Color(0xFFF07A22), Color(0xFFB04A0E), Color(0xFF6B2A06),
        male = false, voice = "Kore",
        wakeVariants = listOf("orangy", "orangi", "orangie", "orange", "oranji", "orangey", "orandschi"))

    val all = listOf(greeny, blaubi, pinky, purply, orangy)
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: greeny
}

/** Colors of the screen. The berry colors follow the chosen skin; the rest is fixed. */
object Palette {
    var skin by mutableStateOf(Skins.greeny)

    val berry1 get() = skin.c1
    val berry2 get() = skin.c2
    val berry3 get() = skin.c3
    val berry4 get() = skin.c4
    val nose get() = skin.nose
    val ink = Color(0xFF17151F)
    val inkSoft = Color(0xFFB9B2CC)
    val panel = Color(0xFF1E1B29)
    val text = Color(0xFFF4F1FA)

    val gradient: List<Color> get() = listOf(skin.c1, skin.c2, skin.c3, skin.c4)
    val gradientStops = listOf(0f, 0.34f, 0.68f, 1f)
}
