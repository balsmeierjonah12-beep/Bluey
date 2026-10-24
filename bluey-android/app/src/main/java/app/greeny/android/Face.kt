package app.greeny.android

enum class Mood(val raw: String) {
    LISTENING("listening"), RESTING("resting"), THINKING("thinking"), TALKING("talking"),
    POINTING("pointing"), HAPPY("happy"), SLEEPY("sleepy")
}

/** What the face should show. gazeX: -1 left … 1 right, gazeY: -1 up … 1 down. */
data class FaceState(
    val gazeX: Double = 0.0,
    val gazeY: Double = 0.0,
    val mood: Mood = Mood.LISTENING,
    val talk: Double = 0.0,
)
