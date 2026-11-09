package dev.gamspi.videogallery.ui.nav

import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.history.PlaybackMeta
import dev.gamspi.videogallery.history.WatchEntry
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {
    @Test fun detailsAndReleaseRoutesIncludeKind() {
        assertEquals("movie/{id}?kind={kind}&source={source}", MOVIE_ROUTE)
        assertEquals("releases/{id}?kind={kind}&season={season}&episode={episode}&missing={missing}&source={source}", RELEASES_ROUTE)
        assertEquals("movie/7?kind=Movie", movieRoute(7))
        assertEquals("movie/7?kind=Series", movieRoute(7, MediaKind.Series))
        assertEquals("movie/7?kind=Series&source=Tmdb", movieRoute(7, MediaKind.Series, "Tmdb"))
        assertEquals("releases/7?kind=Movie&season=&episode=&source=Tmdb", releasesRoute(7, source = "Tmdb"))
        assertEquals("releases/7?kind=Movie&season=&episode=", releasesRoute(7))
        assertEquals("releases/7?kind=Series&season=3&episode=", releasesRoute(7, MediaKind.Series, 3))
        assertEquals("releases/7?kind=Series&season=3&episode=4", releasesRoute(7, MediaKind.Series, 3, 4))
        assertEquals("releases/7?kind=Series&season=3&episode=4&missing=1", releasesRoute(7, MediaKind.Series, 3, 4, missing = true))
    }

    @Test fun seriesMetaSurvivesStreamPlayerAndHistoryRoutes() {
        val series = meta.copy(kind = MediaKind.Series, source = "Tmdb", season = 3, episode = 2,
            seasonEpisodes = 8, seasonCount = 4, episodeTitle = "Серия & + ?")
        val streamed = PlaybackMeta.from(query(streamRoute("Сезон", series))::get)
        assertEquals(series, streamed)
        val sized = series.copy(seasonSizes = "10,8,12")
        assertEquals(sized, PlaybackMeta.from(query(streamRoute("Сезон", sized))::get))
        assertEquals(8, sized.sizeOf(2))
        assertNull(sized.sizeOf(4))
        assertNull(PlaybackMeta(seasonSizes = "x,,3").sizeOf(1))
        assertEquals(3, PlaybackMeta(seasonSizes = "x,,3").sizeOf(3))
        assertEquals(series, PlaybackMeta.from(query(playerRoute("url", "title", "key", streamed))::get))
        val entry = WatchEntry(301, "Сериал", positionKey = "bt:x:0", positionMs = 1, durationMs = 10,
            updatedAt = 1, kind = series.kind, source = series.source, season = series.season,
            episode = series.episode, seasonEpisodes = series.seasonEpisodes, seasonCount = series.seasonCount, seasonSizes = "6,10,8,12")
        val resumed = PlaybackMeta.from(query(entry.streamRoute())::get)
        assertEquals(MediaKind.Series, resumed.kind)
        assertEquals("Tmdb", resumed.source)
        assertEquals(3, resumed.season)
        assertEquals(2, resumed.episode)
        assertEquals(8, resumed.seasonEpisodes)
        assertEquals(4, resumed.seasonCount)
        assertEquals("6,10,8,12", resumed.seasonSizes)
        assertTrue(resumed.autoResume)
    }

    @Test fun unknownKindFallsBackToMovie() {
        assertEquals(MediaKind.Movie, PlaybackMeta.from { if (it == "kind") "future" else null }.kind)
    }

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
