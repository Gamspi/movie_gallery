package dev.gamspi.videogallery.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeFilesTest {
    private val big = 500L * 1024 * 1024
    private fun f(i: Int, path: String, size: Long = big) = TorrentFile(i, path, size)
    private fun refs(l: List<EpisodeFile>) = l.map { it.ref.season to it.ref.episode }

    @Test fun mixedNamingStyles() {
        val r = episodeFiles(
            listOf(
                f(0, "Dark.S02E05.1080p.mkv"),
                f(1, "Season 2/Dark - 2x06.mkv"),
                f(2, "Тьма/Серия 7.avi"),
                f(3, "E08.mkv"),
            ),
            defaultSeason = 2,
        )
        assertEquals(listOf(2 to 5, 2 to 6, 2 to 7, 2 to 8), refs(r))
        assertEquals(listOf(0, 1, 2, 3), r.map { it.fileIndex })
    }

    @Test fun bareNumberNamesUseSeasonFolder() {
        val r = episodeFiles(
            listOf(
                f(0, "Сезон 2/01.mkv"),
                f(1, "Show/Season 2/02 .avi"),
                f(2, "S02/3.mkv"),
                f(3, "2 сезон/04.mkv"),
            ),
            defaultSeason = 1,
        )
        assertEquals(listOf(2 to 1, 2 to 2, 2 to 3, 2 to 4), refs(r))
    }

    @Test fun bareNumberWithoutSeasonFolderIsSkipped() {
        assertTrue(episodeFiles(listOf(f(0, "01.mkv"), f(1, "Extras/02.mkv")), 1).isEmpty())
    }

    @Test fun skipsSampleAndSmallFiles() {
        val r = episodeFiles(
            listOf(
                f(0, "sample.mkv"),
                f(1, "Dark.S02E01.sample.mkv"),
                f(2, "Dark.S02E02.mkv", 10L * 1024 * 1024),
                f(3, "Dark.S02E03.mkv"),
            ),
            2,
        )
        assertEquals(listOf(2 to 3), refs(r))
    }

    @Test fun packOfThreeSeasons() {
        val files = (1..3).flatMap { s -> (1..10).map { e -> s to e } }.mapIndexed { i, (s, e) ->
            f(i, "Dark/S%02d/Dark.S%02dE%02d.mkv".format(s, s, e))
        }
        val r = episodeFiles(files.shuffled(), 1)
        assertEquals(30, r.size)
        assertEquals(setOf(1, 2, 3), r.map { it.ref.season }.toSet())
        assertEquals(r.map { it.ref }.sorted(), r.map { it.ref })
    }

    @Test fun fileWithoutNumberIsEmpty() {
        assertTrue(episodeFiles(listOf(f(0, "Dark.1080p.mkv")), 1).isEmpty())
    }

    @Test fun duplicateRefKeepsLargest() {
        val r = episodeFiles(listOf(f(0, "Dark.S01E01.mkv", big), f(1, "alt/Dark.S01E01.mkv", big * 2)), 1)
        assertEquals(listOf(1), r.map { it.fileIndex })
    }

    @Test fun resolutionIsNotEpisode() {
        assertTrue(episodeFiles(listOf(f(0, "Dark.1920x1080.mkv")), 1).isEmpty())
    }

    @Test fun folderSeasonRussian() {
        val r = episodeFiles(listOf(f(0, "Тьма/Сезон 3/Серия 05.mkv")), 1)
        assertEquals(listOf(3 to 5), refs(r))
    }

    @Test fun refOrdering() {
        assertTrue(EpisodeRef(1, 9) < EpisodeRef(2, 1))
    }
}
