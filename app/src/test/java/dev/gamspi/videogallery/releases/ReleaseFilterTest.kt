package dev.gamspi.videogallery.releases

import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.torrents.Quality
import dev.gamspi.videogallery.torrents.TorrentResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseFilterTest {
    private val limit = 1000L

    private fun r(
        name: String,
        seeds: Int = 5,
        size: Long = 100,
        q: Quality? = null,
        p: ProviderId = ProviderId.Rutor,
    ) = TorrentResult(name, p, size, seeds, 0, q, name, "m")

    private fun List<TorrentResult>.names(f: ReleaseFilter) = applyFilter(f, limit).map { it.title }

    @Test
    fun deadHiddenByDefault() {
        val all = listOf(r("live"), r("dead", seeds = 0))
        assertEquals(listOf("live"), all.names(ReleaseFilter()))
        assertEquals(listOf("live", "dead"), all.names(ReleaseFilter(showDead = true)))
    }

    @Test
    fun overLimitHiddenByDefault() {
        val all = listOf(r("fit", size = 1000), r("big", size = 1001))
        assertEquals(listOf("fit"), all.names(ReleaseFilter()))
        assertEquals(listOf("fit", "big"), all.names(ReleaseFilter(showOverLimit = true)))
    }

    @Test
    fun unknownSizeCountsAsFitting() {
        val all = listOf(r("unknown", size = 0), r("neg", size = -1))
        assertEquals(listOf("unknown", "neg"), all.names(ReleaseFilter()))
        assertEquals(0, all.overLimitCount(ReleaseFilter(), limit))
    }

    @Test
    fun providerHiding() {
        val all = listOf(r("a", p = ProviderId.Rutor), r("b", p = ProviderId.Yts))
        assertEquals(listOf("b"), all.names(ReleaseFilter(hiddenProviders = setOf(ProviderId.Rutor))))
    }

    @Test
    fun qualityCombinesWithOthers() {
        val all = listOf(
            r("a", q = Quality.Hd),
            r("b", q = Quality.Hd, seeds = 0),
            r("c", q = Quality.Uhd),
            r("d", q = Quality.Hd, p = ProviderId.Yts),
        )
        val f = ReleaseFilter(Quality.Hd, setOf(ProviderId.Yts))
        assertEquals(listOf("a"), all.names(f))
    }

    @Test
    fun providerCounts_useOtherFilters_andKeepEntriesOrder() {
        val all = listOf(
            r("a", p = ProviderId.Yts),
            r("b", p = ProviderId.Rutor),
            r("c", p = ProviderId.Rutor, seeds = 0),
            r("d", p = ProviderId.Rutor, q = Quality.Hd),
        )
        val counts = all.providerCounts(ReleaseFilter(hiddenProviders = setOf(ProviderId.Rutor)), limit)
        assertEquals(listOf(ProviderId.Rutor, ProviderId.Yts), counts.keys.toList())
        // Hidden provider still shows how many it would add; dead is excluded.
        assertEquals(2, counts.getValue(ProviderId.Rutor))
        assertEquals(1, counts.getValue(ProviderId.Yts))
        assertEquals(1, all.providerCounts(ReleaseFilter(Quality.Hd), limit).getValue(ProviderId.Rutor))
    }

    @Test
    fun deadAndOverLimitCounts_respectQualityAndProvider() {
        val all = listOf(
            r("a", seeds = 0, q = Quality.Hd),
            r("b", seeds = 0, q = Quality.Uhd),
            r("c", seeds = 0, q = Quality.Hd, p = ProviderId.Yts),
            r("d", size = 5000, q = Quality.Hd),
            r("e", size = 5000, q = Quality.Uhd),
        )
        val f = ReleaseFilter(Quality.Hd, setOf(ProviderId.Yts))
        assertEquals(1, all.deadCount(f))
        assertEquals(1, all.overLimitCount(f, limit))
        assertEquals(3, all.deadCount(ReleaseFilter()))
        assertEquals(2, all.overLimitCount(ReleaseFilter(), limit))
    }

    @Test
    fun summary_noFilter_showsAll() {
        assertEquals("Все раздачи", filterSummary(listOf(r("a")), 1, ReleaseFilter()))
    }

    @Test
    fun summary_combinesParts_andOmitsAbsentProviders() {
        val all = listOf(r("a", p = ProviderId.Rutor), r("b", p = ProviderId.Yts))
        val f = ReleaseFilter(
            Quality.FullHd,
            setOf(ProviderId.Yts, ProviderId.Rutor, ProviderId.entries.first { it != ProviderId.Yts && it != ProviderId.Rutor }),
            showDead = true,
            showOverLimit = true,
        )
        // Order follows ProviderId.entries; the third hidden provider is absent from results.
        val names = ProviderId.entries.filter { it == ProviderId.Rutor || it == ProviderId.Yts }
        val expected = "1080p · без " + names.joinToString(", ") { it.label } +
            " · + мёртвые · + больше лимита · скрыто 2"
        assertEquals(expected, filterSummary(all, 0, f))
    }

    @Test
    fun summary_hiddenCountOnly() {
        assertEquals("скрыто 1", filterSummary(listOf(r("a"), r("dead", seeds = 0)), 1, ReleaseFilter()))
    }
}
