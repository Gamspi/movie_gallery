package dev.gamspi.videogallery.settings

/** Subtitle size as a percent of [SUB_BASE_SP]; 100 % is the former "Medium" (36 sp) reduced by 40 %. */
const val SUB_BASE_SP = 36f * 0.6f
const val SUB_SCALE_MIN = 50
const val SUB_SCALE_MAX = 200
const val SUB_SCALE_STEP = 10
const val SUB_SCALE_DEFAULT = 100

/** Clamps to the allowed range and snaps to the step. */
fun normalizeSubScale(percent: Int): Int {
    val clamped = percent.coerceIn(SUB_SCALE_MIN, SUB_SCALE_MAX)
    val snapped = SUB_SCALE_MIN + Math.round((clamped - SUB_SCALE_MIN) / SUB_SCALE_STEP.toFloat()) * SUB_SCALE_STEP
    return snapped.coerceAtMost(SUB_SCALE_MAX)
}

/** Moves the scale by [steps] steps, staying within the range. */
fun stepSubScale(percent: Int, steps: Int): Int = normalizeSubScale(percent + steps * SUB_SCALE_STEP)

/** Reads a stored size: a percent, or a legacy size name (Small/Medium/Large); null when unknown. */
fun parseSubScale(raw: String?): Int? = when (val value = raw?.trim()) {
    null, "" -> null
    "Small" -> 80
    "Medium" -> 100
    "Large" -> 130
    else -> value.toIntOrNull()?.let(::normalizeSubScale)
}

fun formatSubScale(percent: Int): String = "$percent %"

enum class SubColor(val argb: Long, val label: String) {
    White(0xFFFFFFFF, "Белый"),
    Yellow(0xFFFFEB3B, "Жёлтый"),
    Cyan(0xFF80D8FF, "Голубой"),
}

data class LayerStyle(
    val scalePercent: Int = SUB_SCALE_DEFAULT,
    val color: SubColor = SubColor.White,
    val offsetMs: Long = 0,
) {
    val fontSp: Float get() = SUB_BASE_SP * scalePercent / 100f
}

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
