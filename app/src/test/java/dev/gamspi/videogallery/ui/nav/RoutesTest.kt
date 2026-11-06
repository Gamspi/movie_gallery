package dev.gamspi.videogallery.ui.nav

import dev.gamspi.videogallery.history.PlaybackMeta
import dev.gamspi.videogallery.history.WatchEntry
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {
    private fun query(route: String): Map<String, String> = route.substringAfter('?').split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        k to URLDecoder.decode(v, "UTF-8")
    }

    private val meta = PlaybackMeta(
        movieId = 301, movieTitle = "Матрица & Co", movieYear = 1999, poster = "https://p/x.jpg?a=1&b=2",
        rating = 8.5, magnet = "magnet:?xt=urn:btih:abc&dn=A B", torrent = "https://t/f.torrent",
        imdb = "tt0133093", autoResume = false,
    )

    @Test fun meta_roundTripsThroughStreamRoute() {
        val route = streamRoute("Release 1080p+", meta)
        assertTrue(route.startsWith("stream?"))
        assertFalse(route.contains(' '))
        assertFalse(route.contains('+'))
        val args = query(route)
        assertEquals("Release 1080p+", args["title"])
        assertEquals(meta, PlaybackMeta.from(args::get))
    }

    @Test fun playerRoute_keepsUrlKeyAndMeta() {
        val args = query(playerRoute("http://127.0.0.1:1/v?x=1", "Film", "bt:abc:0", meta.copy(autoResume = true)))
        assertEquals("http://127.0.0.1:1/v?x=1", args["url"])
        assertEquals("bt:abc:0", args["key"])
        assertEquals("tt0133093", args["imdb"])
        assertEquals(meta.copy(autoResume = true), PlaybackMeta.from(args::get))
    }

    @Test fun missingArgs_giveDefaults() {
        assertEquals(PlaybackMeta(), PlaybackMeta.from { null })
        assertEquals(PlaybackMeta(), PlaybackMeta.from { "" })
        val nulls = PlaybackMeta(movieId = 5, movieTitle = "X")
        assertEquals(nulls, PlaybackMeta.from(query(streamRoute("r", nulls))::get))
    }

    @Test fun routePatterns_declareEveryMetaArg() {
        PlaybackMeta.ARGS.forEach {
            assertTrue(STREAM_ROUTE.contains("$it={$it}"))
            assertTrue(PLAYER_ROUTE.contains("$it={$it}"))
        }
    }

    @Test fun watchEntry_streamRoute_autoResumes() {
        val e = WatchEntry(7, "Фильм", 2001, "p", 7.0, "magnet:x", "", "tt7", "bt:x:0", 1_000, 10_000, 5)
        val args = query(e.streamRoute())
        assertEquals("Фильм", args["title"])
        assertEquals(PlaybackMeta(7, "Фильм", 2001, "p", 7.0, "magnet:x", "", "tt7", autoResume = true),
            PlaybackMeta.from(args::get))
    }
}
