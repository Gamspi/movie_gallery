package dev.gamspi.videogallery.translate

class FakeSubtitleTranslator(
    override val modelVersion: String = "fake-1",
    private val transform: (String) -> String = { "[$it]" },
) : SubtitleTranslator {
    val calls = mutableListOf<Triple<String, TranslateLang, TranslateLang>>()

    /** Texts that make translate throw. */
    var failWhen: (String) -> Boolean = { false }

    /** Runs after a call is recorded; may suspend to hold the caller. */
    var onCall: suspend (String) -> Unit = {}

    val texts: List<String> get() = calls.map { it.first }

    override suspend fun translate(text: String, from: TranslateLang, to: TranslateLang): String {
        calls += Triple(text, from, to)
        onCall(text)
        if (failWhen(text)) throw IllegalStateException("fake failure: $text")
        return transform(text)
    }
}
