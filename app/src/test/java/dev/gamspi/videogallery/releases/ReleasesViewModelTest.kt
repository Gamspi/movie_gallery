package dev.gamspi.videogallery.releases

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.FakeCatalogRepository
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.history.PlaybackMeta
import dev.gamspi.videogallery.settings.FakeSecretStore
import dev.gamspi.videogallery.settings.SettingsRepository
import dev.gamspi.videogallery.settings.TrackerConfig
import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.torrents.ProviderUnavailableException
import dev.gamspi.videogallery.torrents.Quality
import dev.gamspi.videogallery.torrents.TorrentProvider
import dev.gamspi.videogallery.torrents.TorrentResult
import dev.gamspi.videogallery.torrents.TorrentSearch
import dev.gamspi.videogallery.torrents.UnavailableReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import dev.gamspi.videogallery.ui.components.ErrorKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReleasesViewModelTest {
    private val repo = FakeCatalogRepository()
    private val movie = Movie(5, "Матрица", "The Matrix", 1999, null, "")
    private val details = MovieDetails(movie, null, null, emptyList(), null)

    private fun r(hash: String, seeds: Int, q: Quality?, p: ProviderId = ProviderId.Apibay) =
        TorrentResult("t$hash", p, 1L, seeds, 0, q, hash, "m")

    private class Fake(
        override val id: ProviderId,
        val resolver: (suspend (TorrentResult) -> TorrentResult)? = null,
        val block: suspend () -> List<TorrentResult>,
    ) : TorrentProvider {
        override suspend fun search(movie: MovieDetails, season: Int?) = block()
        override suspend fun resolve(result: TorrentResult): TorrentResult =
            resolver?.invoke(result) ?: super.resolve(result)
    }

    private fun topic(id: String) = TorrentResult(
        "rt$id", ProviderId.Rutracker, 1L, 4, 0, null, "", "", topicUrl = "https://rt.test/forum/viewtopic.php?t=$id",
    )

    private fun settings() = SettingsRepository(FakeSecretStore())

    private fun vm(
        settings: SettingsRepository = settings(),
        vararg providers: TorrentProvider,
    ) = ReleasesViewModel(
        repo,
        TorrentSearch({ providers.toList() }),
        settings,
        SavedStateHandle(mapOf("id" to 5)),
    ).also { it.io = Dispatchers.Main }

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        repo.detailsResult = { details }
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loads_detailsThenSearch_content() = runTest {
        val p = Fake(ProviderId.Apibay) { listOf(r("a", 5, Quality.FullHd), r("b", 9, null)) }
        val vm = vm(settings(), p)
        vm.state.test {
            assertEquals(ReleasesUiState.Loading, awaitItem())
            val c = awaitItem() as ReleasesUiState.Content
            assertEquals("Матрица", c.movieTitle)
            assertEquals(listOf("b", "a"), c.all.map { it.infoHash })
            assertEquals(ReleaseFilter(), c.filter)
            assertFalse(c.noProviders)
        }
        assertEquals(listOf(5), repo.detailsCalls)
    }

    @Test
    fun content_carriesImdbId() = runTest {
        repo.detailsResult = { details.copy(imdbId = "tt0133093") }
        val vm = vm(settings(), Fake(ProviderId.Apibay) { emptyList() })
        vm.state.test {
            assertEquals(ReleasesUiState.Loading, awaitItem())
            assertEquals("tt0133093", (awaitItem() as ReleasesUiState.Content).imdbId)
        }
    }

    @Test
    fun content_buildsPlaybackMetaForRelease() = runTest {
        repo.detailsResult = {
            details.copy(movie = movie.copy(posterUrl = "p", rating = 8.5), imdbId = "tt0133093")
        }
        val vm = vm(settings(), Fake(ProviderId.Apibay) { emptyList() })
        vm.state.test {
            assertEquals(ReleasesUiState.Loading, awaitItem())
            val c = awaitItem() as ReleasesUiState.Content
            val meta = c.metaFor(r("a", 1, null).copy(magnet = "magnet:a", torrentUrl = "https://t/a.torrent"))
            assertEquals(PlaybackMeta(5, "Матрица", 1999, "p", 8.5, "magnet:a", "https://t/a.torrent",
                "tt0133093", source = "PoiskKino"), meta)
            assertFalse(meta.autoResume)
        }
    }

    @Test
    fun seriesRouteKeepsKindSeasonAndSource() = runTest {
        repo.detailsResult = { details.copy(movie = movie.copy(kind = MediaKind.Series)) }
        val provider = object : TorrentProvider {
            override val id = ProviderId.Apibay
            override suspend fun search(movie: MovieDetails, season: Int?): List<TorrentResult> {
                assertEquals(MediaKind.Series, movie.movie.kind)
                assertEquals(3, season)
                return listOf(r("a", 1, null))
            }
        }
        val vm = ReleasesViewModel(repo, TorrentSearch({ listOf(provider) }), settings(),
            SavedStateHandle(mapOf("id" to 5, "kind" to "Series", "season" to "3")))
        vm.state.test {
            assertEquals(ReleasesUiState.Loading, awaitItem())
            val content = awaitItem() as ReleasesUiState.Content
            val meta = content.metaFor(content.all.single())
            assertEquals(MediaKind.Series, meta.kind)
            assertEquals(3, meta.season)
            assertEquals("PoiskKino", meta.source)
        }
        assertEquals(listOf(MediaKind.Series), repo.kindCalls)
    }

    private fun seriesVm(
        vararg results: TorrentResult,
        extra: Map<String, Any> = emptyMap(),
    ): ReleasesViewModel {
        repo.detailsResult = {
            details.copy(
                movie = movie.copy(kind = MediaKind.Series),
                seasons = listOf(
                    dev.gamspi.videogallery.catalog.model.SeasonInfo(1, 8),
                    dev.gamspi.videogallery.catalog.model.SeasonInfo(2, 10),
                ),
            )
        }
        return ReleasesViewModel(
            repo, TorrentSearch({ listOf(Fake(ProviderId.Apibay) { results.toList() }) }), settings(),
            SavedStateHandle(mapOf("id" to 5, "kind" to "Series", "season" to "2") + extra),
        )
    }

    @Test
    fun seasonSortsBySeasonRankThenSeeds() = runTest {
        fun t(title: String, seeds: Int, hash: String) = r(hash, seeds, null).copy(title = title)
        val vm = seriesVm(
            t("Show S01", 90, "a"), t("Show", 50, "b"), t("Show S02", 5, "c"), t("Show S02 1080p", 7, "d"),
        )
        vm.state.test {
            assertEquals(ReleasesUiState.Loading, awaitItem())
            val c = awaitItem() as ReleasesUiState.Content
            assertEquals(listOf("Show S02 1080p", "Show S02", "Show", "Show S01"), c.all.map { it.title })
        }
    }

    @Test
    fun seasonFilterWorksOnTopOfSorting() = runTest {
        fun t(title: String, seeds: Int, hash: String, q: Quality) = r(hash, seeds, q).copy(title = title)
        val vm = seriesVm(t("Show S02", 5, "a", Quality.Hd), t("Show S02 1080p", 7, "b", Quality.FullHd), t("Show", 50, "c", Quality.FullHd))
        vm.state.test {
            awaitItem()
            awaitItem()
            vm.setQuality(Quality.FullHd)
            val c = awaitItem() as ReleasesUiState.Content
            assertEquals(listOf("Show S02 1080p", "Show"), c.visible.map { it.title })
        }
    }

    @Test
    fun seriesMetaCarriesEpisodeAndCounts() = runTest {
        val vm = seriesVm(r("a", 3, null), extra = mapOf("episode" to "4"))
        vm.state.test {
            awaitItem()
            val c = awaitItem() as ReleasesUiState.Content
            val meta = c.metaFor(c.all.single())
            assertEquals(MediaKind.Series, meta.kind)
            assertEquals("PoiskKino", meta.source)
            assertEquals(2, meta.season)
            assertEquals(4, meta.episode)
            assertEquals(10, meta.seasonEpisodes)
            assertEquals(2, meta.seasonCount)
        }
    }

    @Test
    fun filter_showsOnlyQuality() = runTest {
        val p = Fake(ProviderId.Apibay) {
            listOf(r("a", 5, Quality.FullHd), r("b", 9, Quality.Hd), r("c", 1, null))
        }
        val vm = vm(settings(), p)
        vm.state.test {
            assertEquals(ReleasesUiState.Loading, awaitItem())
            awaitItem()
            vm.setQuality(Quality.FullHd)
            val c = awaitItem() as ReleasesUiState.Content
            assertEquals(Quality.FullHd, c.filter.quality)
            assertEquals(listOf("a"), c.visible.map { it.infoHash })
            vm.setQuality(null)
            assertEquals(3, (awaitItem() as ReleasesUiState.Content).visible.size)
        }
    }

    private val savedFilter = ReleaseFilter(Quality.Hd, setOf(ProviderId.Rutor), showDead = true, showOverLimit = true)

    private fun TestScope.vmWithSaved(remember: Boolean, s: SettingsRepository = settings()): Pair<ReleasesViewModel, SettingsRepository> {
        s.saveReleaseFilter(savedFilter)
        s.setRememberReleaseFilter(remember)
        return vm(s, Fake(ProviderId.Apibay) { listOf(r("a", 5, Quality.Hd)) }) to s
    }

    @Test
    fun rememberOff_startsWithDefaultFilter() = runTest {
        val (vm, _) = vmWithSaved(false)
        assertEquals(ReleaseFilter(), loaded(vm).filter)
    }

    @Test
    fun rememberOn_restoresSavedFilter() = runTest {
        val (vm, _) = vmWithSaved(true)
        assertEquals(savedFilter, loaded(vm).filter)
    }

    @Test
    fun rememberOn_savesFilterOnChange() = runTest {
        val s = settings()
        s.setRememberReleaseFilter(true)
        val vm = vm(s, Fake(ProviderId.Apibay) { listOf(r("a", 5, Quality.Hd)) })
        loaded(vm)
        vm.setQuality(Quality.Uhd)
        vm.toggleProvider(ProviderId.Yts)
        vm.toggleDead()
        vm.toggleOverLimit()
        advanceUntilIdle()
        assertEquals(ReleaseFilter(Quality.Uhd, setOf(ProviderId.Yts), true, true), s.savedReleaseFilter())
    }

    @Test
    fun rememberOff_doesNotSaveFilter() = runTest {
        val s = settings()
        val vm = vm(s, Fake(ProviderId.Apibay) { listOf(r("a", 5, Quality.Hd)) })
        loaded(vm)
        vm.setQuality(Quality.Uhd)
        vm.toggleDead()
        advanceUntilIdle()
        assertEquals(ReleaseFilter(), s.savedReleaseFilter())
    }

    @Test
    fun content_carriesStorageLimit() = runTest {
        val vm = vm(settings(), Fake(ProviderId.Apibay) { emptyList() })
        assertEquals(10L * 1024 * 1024 * 1024, loaded(vm).limitBytes)
    }

    @Test
    fun unavailablePassedThrough() = runTest {
        val a = Fake(ProviderId.Rutor) { throw ProviderUnavailableException() }
        val b = Fake(ProviderId.Apibay) { listOf(r("x", 3, null)) }
        val vm = vm(settings(), a, b)
        vm.state.test {
            assertEquals(ReleasesUiState.Loading, awaitItem())
            val c = awaitItem() as ReleasesUiState.Content
            assertEquals(listOf(ProviderId.Rutor), c.unavailable)
            assertEquals(1, c.all.size)
        }
    }

    @Test
    fun detailsError_errorKind() = runTest {
        repo.detailsResult = { throw CatalogException.Network }
        val vm = vm(settings())
        vm.state.test {
            assertEquals(ReleasesUiState.Loading, awaitItem())
            assertEquals(ReleasesUiState.Error(ErrorKind.Network), awaitItem())
        }
    }

    @Test
    fun retry_researches() = runTest {
        var fail = true
        repo.detailsResult = { if (fail) throw CatalogException.Network else details }
        val p = Fake(ProviderId.Apibay) { listOf(r("a", 1, null)) }
        val vm = vm(settings(), p)
        vm.state.test {
            assertEquals(ReleasesUiState.Loading, awaitItem())
            assertEquals(ReleasesUiState.Error(ErrorKind.Network), awaitItem())
            fail = false
            vm.retry()
            assertEquals(ReleasesUiState.Loading, awaitItem())
            assertTrue(awaitItem() is ReleasesUiState.Content)
        }
        assertEquals(listOf(5, 5), repo.detailsCalls)
    }

    @Test
    fun noEnabledProviders_flagged() = runTest {
        val s = settings()
        s.setTrackers(ProviderId.entries.associateWith { TrackerConfig(false, "https://x.test/") })
        val vm = vm(s)
        vm.state.test {
            assertEquals(ReleasesUiState.Loading, awaitItem())
            val c = awaitItem() as ReleasesUiState.Content
            assertTrue(c.noProviders)
            assertTrue(c.all.isEmpty())
        }
    }

    private fun TestScope.loaded(vm: ReleasesViewModel): ReleasesUiState.Content {
        advanceUntilIdle()
        return vm.state.value as ReleasesUiState.Content
    }

    @Test
    fun resolve_fetchesMagnet_showsIndicator_thenPlays() = runTest {
        val gate = CompletableDeferred<Unit>()
        val p = Fake(ProviderId.Rutracker, resolver = {
            gate.await()
            it.copy(infoHash = "h1", magnet = "magnet:?xt=urn:btih:h1")
        }) { listOf(topic("1")) }
        val vm = vm(settings(), p)
        val c = loaded(vm)
        vm.resolve(c.all.single())
        // runCurrent: advancing time would trip the resolve timeout.
        runCurrent()
        assertEquals("https://rt.test/forum/viewtopic.php?t=1", (vm.state.value as ReleasesUiState.Content).resolving)
        assertNull(vm.play.value)
        gate.complete(Unit)
        runCurrent()
        val after = vm.state.value as ReleasesUiState.Content
        assertNull(after.resolving)
        assertNull(after.message)
        val play = vm.play.value!!
        assertEquals("magnet:?xt=urn:btih:h1", play.release.magnet)
        assertEquals("magnet:?xt=urn:btih:h1", play.meta.magnet)
        assertEquals("rt1", play.release.title)
        vm.onPlayHandled()
        assertNull(vm.play.value)
    }

    @Test
    fun resolve_failure_showsMessage_staysOnContent() = runTest {
        val p = Fake(ProviderId.Rutracker, resolver = { throw ProviderUnavailableException() }) { listOf(topic("1")) }
        val vm = vm(settings(), p)
        val c = loaded(vm)
        vm.resolve(c.all.single())
        advanceUntilIdle()
        val after = vm.state.value as ReleasesUiState.Content
        assertEquals(RESOLVE_ERROR, after.message)
        assertEquals("Не удалось открыть раздачу", after.message)
        assertNull(after.resolving)
        assertEquals(1, after.all.size)
        assertNull(vm.play.value)
    }

    @Test
    fun releaseWithMagnet_playsWithoutResolving() = runTest {
        var resolveCalls = 0
        val p = Fake(ProviderId.Apibay, resolver = { resolveCalls++; it }) { listOf(r("a", 1, null)) }
        val vm = vm(settings(), p)
        val c = loaded(vm)
        vm.resolve(c.all.single())
        assertEquals("m", vm.play.value!!.meta.magnet)
        assertEquals(0, resolveCalls)
    }

    @Test
    fun retry_cancelsPendingResolve() = runTest {
        val gate = CompletableDeferred<Unit>()
        val p = Fake(ProviderId.Rutracker, resolver = { gate.await(); it.copy(magnet = "magnet:x") }) {
            listOf(topic("1"))
        }
        val vm = vm(settings(), p)
        vm.resolve(loaded(vm).all.single())
        runCurrent()
        vm.retry()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull((vm.state.value as ReleasesUiState.Content).resolving)
        assertNull(vm.play.value)
    }

    @Test
    fun unavailableReasonsPassedThrough() = runTest {
        val a = Fake(ProviderId.Rutracker) { throw ProviderUnavailableException(UnavailableReason.BadCredentials) }
        val vm = vm(settings(), a)
        val c = loaded(vm)
        assertEquals(listOf(ProviderId.Rutracker), c.unavailable)
        assertEquals(mapOf(ProviderId.Rutracker to UnavailableReason.BadCredentials), c.reasons)
    }

    @Test fun missingNoteShownOnlyWhenFlagged() = runTest {
        val flagged = ReleasesViewModel(
            repo, TorrentSearch({ emptyList() }), settings(),
            SavedStateHandle(mapOf("id" to 5, "kind" to "Series", "season" to "2", "episode" to "5", "missing" to "1")),
        )
        advanceUntilIdle()
        assertEquals("В раздаче нет S02E05", flagged.missingNote.value)
        val plain = ReleasesViewModel(
            repo, TorrentSearch({ emptyList() }), settings(),
            SavedStateHandle(mapOf("id" to 5, "kind" to "Series", "season" to "2", "episode" to "5")),
        )
        assertNull(plain.missingNote.value)
    }

    @Test fun markEpisodeMissing_turnsNoteOn() = runTest {
        val vm = ReleasesViewModel(
            repo, TorrentSearch({ emptyList() }), settings(),
            SavedStateHandle(mapOf("id" to 5, "kind" to "Series", "season" to "2", "episode" to "1")),
        )
        advanceUntilIdle()
        assertNull(vm.missingNote.value)
        vm.markEpisodeMissing()
        advanceUntilIdle()
        assertEquals("В раздаче нет S02E01", vm.missingNote.value)
    }

    @Test fun markEpisodeMissing_withoutEpisode_staysNull() = runTest {
        val vm = ReleasesViewModel(
            repo, TorrentSearch({ emptyList() }), settings(),
            SavedStateHandle(mapOf("id" to 5, "kind" to "Series", "season" to "2")),
        )
        vm.markEpisodeMissing()
        advanceUntilIdle()
        assertNull(vm.missingNote.value)
    }
}
