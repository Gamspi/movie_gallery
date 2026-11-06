package dev.gamspi.videogallery.torrents.rutracker

import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.torrents.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RutrackerParserTest {
    private fun fixture(name: String) = javaClass.getResource("/torrents/$name")!!.readText()
    private val base = "https://rutracker.org/"

    @Test
    fun parsesRowsWithTopicIds_dropsRowsWithoutId() {
        val rows = RutrackerParser.parseSearch(fixture("rutracker_search.html"), base)
        assertEquals(
            listOf("1000001", "1000002", "1000003", "1000004", "1000005", "1000006"),
            rows.map { it.result.topicUrl!!.substringAfter("t=") },
        )
    }

    @Test
    fun firstRow_allFields() {
        val r = RutrackerParser.parseSearch(fixture("rutracker_search.html"), base).first()
        assertEquals("Фильмы HD Video", r.forum)
        with(r.result) {
            assertTrue(title.startsWith("Матрица / The Matrix"))
            assertEquals(ProviderId.Rutracker, provider)
            assertEquals(15461882265L, sizeBytes)
            assertEquals(321, seeders)
            assertEquals(12, leechers)
            assertEquals(Quality.FullHd, quality)
            assertEquals("", infoHash)
            assertEquals("", magnet)
            assertNull(torrentUrl)
            assertEquals("https://rutracker.org/forum/viewtopic.php?t=1000001", topicUrl)
        }
    }

    @Test
    fun sizeFromText_negativeSeedsClamped() {
        val r = RutrackerParser.parseSearch(fixture("rutracker_search.html"), base)[1].result
        assertEquals((1.37 * (1L shl 30)).toLong(), r.sizeBytes)
        assertEquals(0, r.seeders)
        assertEquals(0, r.leechers)
    }

    @Test
    fun missingFields_tolerated() {
        val r = RutrackerParser.parseSearch(fixture("rutracker_search.html"), base)[5]
        assertNull(r.forum)
        assertEquals(0L, r.result.sizeBytes)
        assertEquals(0, r.result.seeders)
        assertEquals(Quality.Uhd, r.result.quality)
    }

    @Test
    fun forumFilter_filmsOnly() {
        assertTrue(RutrackerParser.isFilmForum("Фильмы HD Video"))
        assertTrue(RutrackerParser.isFilmForum("Зарубежное кино"))
        assertTrue(RutrackerParser.isFilmForum("UHD Video"))
        assertTrue(RutrackerParser.isFilmForum("DVD Video"))
        assertTrue(RutrackerParser.isFilmForum("3D/Стерео Кино, Видео, TV и Спорт"))
        assertTrue(RutrackerParser.isFilmForum(null))
        assertFalse(RutrackerParser.isFilmForum("Зарубежные сериалы"))
        assertFalse(RutrackerParser.isFilmForum("Мультсериалы (HD Video)"))
        assertFalse(RutrackerParser.isFilmForum("Музыкальное видео"))
        assertFalse(RutrackerParser.isFilmForum("Трейлеры и дополнительные материалы к фильмам"))
        assertFalse(RutrackerParser.isFilmForum("Игры для PC"))
        assertFalse(RutrackerParser.isFilmForum("Аудиокниги"))
    }

    @Test
    fun loginPageDetection() {
        assertTrue(RutrackerParser.isLoginPage(fixture("rutracker_login.html")))
        assertTrue(RutrackerParser.isLoginPage(fixture("rutracker_login_captcha.html")))
        assertFalse(RutrackerParser.isLoginPage(fixture("rutracker_search.html")))
        assertFalse(RutrackerParser.isLoginPage(fixture("rutracker_topic.html")))
    }

    @Test
    fun magnetFromTopic_hashLowercased() {
        val m = RutrackerParser.parseMagnet(fixture("rutracker_topic.html"))!!
        assertEquals("0123456789abcdef0123456789abcdef01234567", m.infoHash)
        assertTrue(m.magnet.startsWith("magnet:?xt=urn:btih:0123456789ABCDEF"))
        assertTrue(m.magnet.contains("&tr=http"))
    }

    @Test
    fun topicWithoutMagnet_null() {
        assertNull(RutrackerParser.parseMagnet(fixture("rutracker_search.html")))
        assertNull(RutrackerParser.parseMagnet("<a href=\"magnet:?xt=urn:btih:short\">x</a>"))
    }

    @Test
    fun captchaDetection() {
        assertTrue(RutrackerSession.isCaptcha(fixture("rutracker_login_captcha.html")))
        assertFalse(RutrackerSession.isCaptcha(fixture("rutracker_login.html")))
    }

    @Test
    fun sessionCookie_parsing() {
        assertEquals("bb_session=0-1-Fake", RutrackerSession.sessionCookie("bb_session=0-1-Fake; expires=Fri; path=/forum/; HttpOnly"))
        assertNull(RutrackerSession.sessionCookie("bb_session=deleted; expires=Thu, 01-Jan-1970"))
        assertNull(RutrackerSession.sessionCookie("bb_ssl=1; path=/"))
    }
}
