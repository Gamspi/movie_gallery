package dev.gamspi.videogallery.watchlist

import dev.gamspi.videogallery.history.WatchEntry
import dev.gamspi.videogallery.catalog.model.MediaKind
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class HistoryImportTest {
    private fun entry() = WatchEntry(movieId=1,title="Фильм",positionKey="bt:hash:0",positionMs=500,durationMs=1000,updatedAt=10,source="Tmdb")
    @Test fun movieImportsMetadataAndPosition() {
        val rows=importWatchEntry(entry())
        assertEquals(listOf(ListTable.Titles,ListTable.Positions),rows.map { it.table })
        assertEquals("tmdb-movie-1",rows[0].titleKey)
        assertEquals("Фильм",rows[0].data.getValue("title_ru").jsonPrimitive.content)
        assertEquals("watching",rows[0].data.getValue("status").jsonPrimitive.content)
        assertEquals(500,rows[1].positionMs)
    }
    @Test fun unknownLegacySourceWithoutImdbIsSkipped() {
        assertTrue(importWatchEntry(entry().copy(source=null)).isEmpty())
        assertEquals("tt0111161",importWatchEntry(entry().copy(source=null,imdb="tt0111161"))[0].titleKey)
    }
    @Test fun finishedMovieAndEpisodeDiffer() {
        assertEquals("watched",importWatchEntry(entry().copy(positionMs=900))[0].data.getValue("status").jsonPrimitive.content)
        val rows=importWatchEntry(entry().copy(kind=MediaKind.Series,season=2,episode=3,positionMs=900))
        assertEquals("watching",rows[0].data.getValue("status").jsonPrimitive.content)
        assertTrue(rows.last().watched)
        assertEquals(2,rows.last().season)
        assertEquals(3,rows.last().episode)
    }
    @Test fun missingEpisodeDoesNotInventMoviePosition() {
        assertEquals(1,importWatchEntry(entry().copy(kind=MediaKind.Series)).size)
    }
    @Test fun kinopoiskAndPoiskkinoShareIds() {
        for(source in listOf("Kinopoisk","PoiskKino")) assertEquals("kp-1",importWatchEntry(entry().copy(source=source))[0].titleKey)
    }
}
