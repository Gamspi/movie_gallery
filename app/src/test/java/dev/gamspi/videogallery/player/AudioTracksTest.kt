package dev.gamspi.videogallery.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioTracksTest {
    private fun t(
        index: Int,
        lang: String? = "rus",
        label: String? = null,
        codec: String? = "ac3",
        supported: Boolean = true,
    ) = AudioTrack(0, index, lang, label, codec, supported)

    @Test fun displayName_languageLabelCodec() =
        assertEquals("Rus, MVO LostFilm · AC3", t(0, "rus", "MVO LostFilm", "ac3").displayName(1))

    @Test fun displayName_twoLetterLanguage_threeLetter() {
        assertEquals("Rus · AC3", t(0, "ru", null, "ac3").displayName(1))
        assertEquals("Eng · AC3", t(0, "en", null, "ac3").displayName(1))
        assertEquals("Rus · AC3", t(0, "rus", null, "ac3").displayName(1))
    }

    @Test fun displayName_noLanguageNoLabel_ordinal() =
        assertEquals("Дорожка 2", t(1, "und", null, null).displayName(2))

    @Test fun displayName_nullLanguageEmptyLabel_ordinal() =
        assertEquals("Дорожка 3", t(2, null, "", null).displayName(3))

    @Test fun displayName_unsupported_suffix() =
        assertEquals("Rus · DTS — не поддерж.", t(0, "rus", null, "dts", false).displayName(1))

    @Test fun choose_exactLanguageAndLabel() {
        val a = t(0, "rus", "Dub")
        val b = t(1, "rus", "MVO")
        assertEquals(b, chooseAudio(listOf(a, b), AudioPreference("RUS", "mvo")))
    }

    @Test fun choose_sameLanguageOtherLabel() {
        val a = t(0, "eng", "Orig")
        val b = t(1, "rus", "Dub")
        assertEquals(b, chooseAudio(listOf(a, b), AudioPreference("rus", "MVO")))
    }

    @Test fun choose_preferredOnlyUnsupported_takesSupportedSameLanguage() {
        val dts = t(0, "rus", "MVO", "dts", false)
        val ac3 = t(1, "rus", "Dub")
        assertEquals(ac3, chooseAudio(listOf(dts, ac3), AudioPreference("rus", "MVO")))
    }

    @Test fun choose_noMatch_null() =
        assertNull(chooseAudio(listOf(t(0, "eng")), AudioPreference("rus", null)))

    @Test fun choose_noPreference_null() =
        assertNull(chooseAudio(listOf(t(0, "rus")), null))
}
