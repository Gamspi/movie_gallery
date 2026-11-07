package dev.gamspi.videogallery.torrents

import org.junit.Assert.assertEquals
import org.junit.Test

class SeasonMatchTest {
    @Test fun rank0_bracketedSeason() = assertEquals(0, seasonRank("Тьма / Dark [S02] (2019)", 2))
    @Test fun rank0_russianSeason() = assertEquals(0, seasonRank("Тьма (2 сезон)", 2))
    @Test fun rank0_sRange() = assertEquals(0, seasonRank("Dark S01-S03", 2))
    @Test fun rank0_russianRange() = assertEquals(0, seasonRank("Тьма 1-3 сезон", 3))
    @Test fun rank0_seasonWord() = assertEquals(0, seasonRank("Dark Season 2 1080p", 2))
    @Test fun rank0_seasonFirst() = assertEquals(0, seasonRank("Тьма Сезон 2", 2))
    @Test fun rank2_otherSeason() = assertEquals(2, seasonRank("Тьма (1 сезон)", 2))
    @Test fun rank2_rangeWithout() = assertEquals(2, seasonRank("Dark S01-S02", 3))
    @Test fun rank1_noInfo() = assertEquals(1, seasonRank("Тьма WEB-DL", 2))
    @Test fun rank1_codecsAreNotSeasons() = assertEquals(1, seasonRank("Dark DTS5 x264 WEB-DL 1080p", 2))
}
