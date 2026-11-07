package dev.gamspi.videogallery.player

import dev.gamspi.videogallery.stream.EpisodeFile
import dev.gamspi.videogallery.stream.EpisodeRef
import org.junit.Assert.assertEquals
import org.junit.Test

class NextEpisodeTest {
    private fun files(vararg refs: Pair<Int, Int>) =
        refs.mapIndexed { i, (s, e) -> EpisodeFile(EpisodeRef(s, e), i) }

    @Test fun nextEpisodeInFilesPlays() {
        val step = afterEpisode(EpisodeRef(2, 5), files(2 to 5, 2 to 6), 10, 3)
        assertEquals(NextStep.PlayFile(EpisodeRef(2, 6)), step)
    }

    @Test fun seasonEndPlaysNextSeasonWhenInFiles() {
        val step = afterEpisode(EpisodeRef(2, 10), files(2 to 10, 3 to 1), 10, 3)
        assertEquals(NextStep.PlayFile(EpisodeRef(3, 1)), step)
    }

    @Test fun seasonEndWithoutFilePicksNextSeasonRelease() {
        val step = afterEpisode(EpisodeRef(2, 10), files(2 to 10), 10, 3)
        assertEquals(NextStep.PickRelease(EpisodeRef(3, 1)), step)
    }

    @Test fun midSeasonWithoutFilePicksRelease() {
        val step = afterEpisode(EpisodeRef(2, 5), files(2 to 5), 10, 3)
        assertEquals(NextStep.PickRelease(EpisodeRef(2, 6)), step)
    }

    @Test fun lastEpisodeOfLastSeasonIsDone() {
        assertEquals(NextStep.SeriesDone, afterEpisode(EpisodeRef(3, 10), files(3 to 10), 10, 3))
    }

    @Test fun unknownCountersWithoutFileIsDone() {
        assertEquals(NextStep.SeriesDone, afterEpisode(EpisodeRef(2, 5), files(2 to 5), null, null))
    }
}
