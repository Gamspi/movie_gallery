package dev.gamspi.videogallery.translate

class FakeSubtitleTranslator(
    override val modelVersion: String = "fake-1",
    private val transform: (String) -> String = { "[$it]" },
) : SubtitleTranslator {
    val calls = mutableListOf<Triple<String, TranslateLang, TranslateLang>>()

    override suspend fun translate(text: String, from: TranslateLang, to: TranslateLang): String {
        calls += Triple(text, from, to)
        return transform(text)
    }
}
