package dev.gamspi.videogallery.settings

enum class SubSize(val sp: Int, val label: String) {
    Small(28, "Мелкий"),
    Medium(36, "Средний"),
    Large(46, "Крупный"),
}

enum class SubColor(val argb: Long, val label: String) {
    White(0xFFFFFFFF, "Белый"),
    Yellow(0xFFFFEB3B, "Жёлтый"),
    Cyan(0xFF80D8FF, "Голубой"),
}

data class LayerStyle(
    val size: SubSize = SubSize.Medium,
    val color: SubColor = SubColor.White,
    val offsetMs: Long = 0,
)

data class SubtitlePrefs(
    val languageA: String = "en",
    val languageB: String = "ru",
    val styleA: LayerStyle = LayerStyle(),
    val styleB: LayerStyle = LayerStyle(color = SubColor.Yellow),
)

/** Which of the two subtitle layers a setting applies to. */
enum class SubLayer { A, B }

/** Language codes the settings button cycles through, in order. */
val SUB_LANGUAGES = listOf("en", "ru", "de", "fr", "es", "it", "ja")

const val SUB_OFFSET_STEP_MS = 500L
const val SUB_OFFSET_MAX_MS = 10_000L
