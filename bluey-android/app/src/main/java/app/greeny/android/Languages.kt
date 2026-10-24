package app.greeny.android

/** A language he can speak. [english] is used in his instructions, [native] is shown on the buttons. */
data class Lang(val code: String, val tag: String, val native: String, val english: String)

object Languages {
    val all = listOf(
        Lang("de", "de-DE", "Deutsch", "German"),
        Lang("en", "en-US", "English", "English"),
        Lang("es", "es-ES", "Español", "Spanish"),
        Lang("fr", "fr-FR", "Français", "French"),
        Lang("it", "it-IT", "Italiano", "Italian"),
        Lang("pt", "pt-BR", "Português", "Portuguese"),
        Lang("nl", "nl-NL", "Nederlands", "Dutch"),
        Lang("pl", "pl-PL", "Polski", "Polish"),
        Lang("tr", "tr-TR", "Türkçe", "Turkish"),
        Lang("ru", "ru-RU", "Русский", "Russian"),
        Lang("uk", "uk-UA", "Українська", "Ukrainian"),
        Lang("ar", "ar-SA", "العربية", "Arabic"),
        Lang("ja", "ja-JP", "日本語", "Japanese"),
        Lang("ko", "ko-KR", "한국어", "Korean"),
        Lang("zh", "zh-CN", "中文", "Chinese"),
        Lang("hi", "hi-IN", "हिन्दी", "Hindi"),
    )

    fun byCode(code: String?): Lang = all.firstOrNull { it.code == code } ?: all[0]
}
