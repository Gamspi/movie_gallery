package dev.gamspi.videogallery.details

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.FakeCatalogRepository
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.history.FakeSeriesStore
import dev.gamspi.videogallery.history.FakeWatchHistoryStore
import dev.gamspi.videogallery.history.SeasonRelease
import dev.gamspi.videogallery.history.WatchEntry
import dev.gamspi.videogallery.history.seriesKey
import dev.gamspi.videogallery.catalog.model.Episode
import dev.gamspi.videogallery.catalog.model.SeasonInfo
import dev.gamspi.videogallery.stream.EpisodeFile
import dev.gamspi.videogallery.stream.EpisodeRef
import org.junit.Assert.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import dev.gamspi.videogallery.settings.CatalogSource
import dev.gamspi.videogallery.settings.FakeSecretStore
import dev.gamspi.videogallery.settings.SettingsRepository
import dev.gamspi.videogallery.ui.components.ErrorKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsViewModelTest {
    private val repo = FakeCatalogRepository()
    private val history = FakeWatchHistoryStore()
    private val seriesStore = FakeSeriesStore()

    private fun details(id: Int) = MovieDetails(
        movie = Movie(id, "M$id", "O$id", 1999, null, "overview"),
        imdbId = "tt$id",
        runtimeMin = 139,
        genres = listOf("Драма"),
        backdropUrl = null,
    )

    private fun settings(key: String? = "k") =
        SettingsRepository(FakeSecretStore(mapOf("catalog_source" to "Tmdb") + if (key == null) emptyMap() else mapOf("tmdb_api_key" to key)))

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadsSeriesKindFromRoute() = runTest {
        repo.detailsResult = { details(it) }
        val vm = DetailsViewModel(repo, settings(), history, seriesStore, SavedStateHandle(mapOf("id" to 550, "kind" to "Series")))
        vm.state.test {
            assertEquals(DetailsUiState.Loading, awaitItem())
            assertEquals(DetailsUiState.Content(details(550), selectedSeason = 1, series = true), awaitItem())
        }
        assertEquals(listOf(MediaKind.Series), repo.kindCalls)
    }

    @Test
    fun loads_details_by_id() = runTest {
        repo.detailsResult = { details(it) }
        val vm = DetailsViewModel(repo, settings(), history, seriesStore, SavedStateHandle(mapOf("id" to 550)))
        vm.state.test {
            assertEquals(DetailsUiState.Loading, awaitItem())
            assertEquals(DetailsUiState.Content(details(550)), awaitItem())
        }
        assertEquals(listOf(550), repo.detailsCalls)
        assertEquals(listOf(MediaKind.Movie), repo.kindCalls)
    }

    @Test
    fun error_then_retry() = runTest {
        var fail = true
        repo.detailsResult = { if (fail) throw CatalogException.Network else details(it) }
        val vm = DetailsViewModel(repo, settings(), history, seriesStore, SavedStateHandle(mapOf("id" to 7)))
        vm.state.test {
            assertEquals(DetailsUiState.Loading, awaitItem())
            assertEquals(DetailsUiState.Error(ErrorKind.Network), awaitItem())
            fail = false
            vm.retry()
            assertEquals(DetailsUiState.Loading, awaitItem())
            assertEquals(DetailsUiState.Content(details(7)), awaitItem())
        }
        assertEquals(listOf(7, 7), repo.detailsCalls)
    }

    @Test
    fun keyChanged_reloads() = runTest {
        repo.detailsResult = { throw CatalogException.MissingApiKey }
        val settings = settings(null)
        val vm = DetailsViewModel(repo, settings, history, seriesStore, SavedStateHandle(mapOf("id" to 3)))
        vm.state.test {
            assertEquals(DetailsUiState.Loading, awaitItem())
            assertEquals(DetailsUiState.Error(ErrorKind.MissingKey), awaitItem())
            repo.detailsResult = { details(it) }
            settings.setTmdbApiKey("new")
            assertEquals(DetailsUiState.Loading, awaitItem())
            assertEquals(DetailsUiState.Content(details(3)), awaitItem())
        }
        assertEquals(listOf(3, 3), repo.detailsCalls)
    }

    @Test
    fun sourceChanged_reloads() = runTest {
        repo.detailsResult = { details(it) }
        val settings = settings()
        val vm = DetailsViewModel(repo, settings, history, seriesStore, SavedStateHandle(mapOf("id" to 3)))
        vm.state.test {
            assertEquals(DetailsUiState.Loading, awaitItem())
            assertEquals(DetailsUiState.Content(details(3)), awaitItem())
            settings.setCatalogSource(CatalogSource.Kinopoisk)
            assertEquals(DetailsUiState.Loading, awaitItem())
            assertEquals(DetailsUiState.Content(details(3)), awaitItem())
        }
        assertEquals(listOf(3, 3), repo.detailsCalls)
    }

    private fun seriesDetails(id: Int = 9) = MovieDetails(
        movie = Movie(id, "S$id", "O$id", 2020, "poster", "ov", kind = MediaKind.Series),
        imdbId = "tt$id", runtimeMin = null, genres = emptyList(), backdropUrl = null,
        seasons = listOf(SeasonInfo(0, 0), SeasonInfo(1, 8), SeasonInfo(2, 10), SeasonInfo(3, 0)),
    )

    private fun episodes(season: Int) = (1..3).map { Episode(season, it, "E$it", 40, null) }

    private fun seriesVm(id: Int = 9) = DetailsViewModel(
        repo, settings(), history, seriesStore, SavedStateHandle(mapOf("id" to id, "kind" to "Series")),
    ).also {
        repo.detailsResult = { seriesDetails(id) }
        repo.seasonResult = { _, n -> episodes(n) }
    }

    private fun entry(season: Int, episode: Int, source: String? = "Tmdb") = WatchEntry(
        movieId = 9, title = "S9", positionKey = "k", positionMs = 10, durationMs = 100, updatedAt = 1,
        kind = MediaKind.Series, source = source, season = season, episode = episode,
    )

    @Test
    fun seriesLoadsFirstSeasonWithoutHistoryAndHidesUnreleased() = runTest {
        val vm = seriesVm()
        advanceUntilIdle()
        val c = vm.state.value as DetailsUiState.Content
        assertEquals(listOf(1, 2), c.seasons.map { it.number })
        assertEquals(1, c.selectedSeason)
        assertEquals(episodes(1), c.episodes)
        assertEquals(EpisodeRef(1, 1), c.resume)
        assertFalse(c.started)
        assertEquals(listOf(9 to 1), repo.seasonCalls)
    }

    @Test
    fun seriesLoadsFirstUnfinishedSeason() = runTest {
        history.upsert(entry(2, 3))
        val vm = seriesVm()
        advanceUntilIdle()
        val c = vm.state.value as DetailsUiState.Content
        assertEquals(2, c.selectedSeason)
        assertEquals(episodes(2), c.episodes)
        assertEquals(listOf(9 to 2), repo.seasonCalls)
    }

    @Test
    fun resumeFromHistoryShowsEpisode() = runTest {
        history.upsert(entry(2, 3))
        seriesStore.saveProgress(seriesKey("Tmdb", 9), EpisodeRef(1, 1), 0.5f)
        val vm = seriesVm()
        advanceUntilIdle()
        val c = vm.state.value as DetailsUiState.Content
        assertEquals(EpisodeRef(2, 3), c.resume)
        assertTrue(c.started)
        assertEquals(mapOf(EpisodeRef(1, 1) to 0.5f), c.progress)
    }

    @Test
    fun resumeAfterFinishedEpisodeIsNext() = runTest {
        seriesStore.saveProgress(seriesKey("Tmdb", 9), EpisodeRef(1, 8), 0.95f)
        val vm = seriesVm()
        advanceUntilIdle()
        assertEquals(EpisodeRef(2, 1), (vm.state.value as DetailsUiState.Content).resume)
    }

    @Test
    fun selectSeasonLoadsEpisodes() = runTest {
        val vm = seriesVm()
        advanceUntilIdle()
        vm.selectSeason(2)
        assertTrue((vm.state.value as DetailsUiState.Content).episodesLoading)
        advanceUntilIdle()
        val c = vm.state.value as DetailsUiState.Content
        assertEquals(2, c.selectedSeason)
        assertEquals(episodes(2), c.episodes)
    }

    private fun savedRelease(vararg refs: EpisodeRef) = SeasonRelease(
        "magnet:x", "https://t/x.torrent", "Release S01",
        refs.mapIndexed { i, r -> EpisodeFile(r, i) },
    )

    @Test
    fun playTargetUsesSavedReleaseWhenEpisodeInFiles() = runTest {
        seriesStore.saveRelease(seriesKey("Tmdb", 9), 1, savedRelease(EpisodeRef(1, 1), EpisodeRef(1, 2)))
        val vm = seriesVm()
        advanceUntilIdle()
        val t = vm.playTarget(EpisodeRef(1, 2)) as PlayTarget.Stream
        assertEquals("Release S01", t.releaseTitle)
        val m = t.meta
        assertTrue(m.autoResume)
        assertEquals("magnet:x", m.magnet)
        assertEquals(MediaKind.Series, m.kind)
        assertEquals("Tmdb", m.source)
        assertEquals(1, m.season)
        assertEquals(2, m.episode)
        assertEquals(8, m.seasonEpisodes)
        assertEquals(2, m.seasonCount)
        assertEquals("E2", m.episodeTitle)
        assertEquals(9, m.movieId)
    }

    @Test
    fun playTargetOpensReleasesWhenNotInFiles() = runTest {
        seriesStore.saveRelease(seriesKey("Tmdb", 9), 1, savedRelease(EpisodeRef(1, 1)))
        val vm = seriesVm()
        advanceUntilIdle()
        assertEquals(PlayTarget.PickRelease(1), vm.playTarget(EpisodeRef(1, 5)))
        assertEquals(PlayTarget.PickRelease(2), vm.playTarget(EpisodeRef(2, 1)))
    }

    @Test
    fun episodeLoadFailureKeepsHeader() = runTest {
        val vm = seriesVm()
        repo.seasonResult = { _, _ -> throw CatalogException.Network }
        advanceUntilIdle()
        val c = vm.state.value as DetailsUiState.Content
        assertEquals(ErrorKind.Network, c.episodesError)
        assertTrue(c.episodes.isEmpty())
        assertEquals(seriesDetails(), c.details)
        repo.seasonResult = { _, n -> episodes(n) }
        vm.retryEpisodes()
        advanceUntilIdle()
        val ok = vm.state.value as DetailsUiState.Content
        assertNull(ok.episodesError)
        assertEquals(episodes(1), ok.episodes)
    }

    @Test
    fun seriesWithoutSeasonListProbesSeasonOne() = runTest {
        val vm = seriesVm()
        repo.detailsResult = { seriesDetails(it).copy(seasons = emptyList()) }
        advanceUntilIdle()
        val c = vm.state.value as DetailsUiState.Content
        assertTrue(c.series)
        assertEquals(listOf(1), c.seasons.map { it.number })
        assertEquals(episodes(1), c.episodes)
        assertEquals(EpisodeRef(1, 1), c.resume)
    }

    @Test
    fun seriesWithoutSeasonsAndEpisodesStaysSeriesWithSeasonOnePick() = runTest {
        val vm = seriesVm()
        repo.detailsResult = { seriesDetails(it).copy(seasons = emptyList()) }
        repo.seasonResult = { _, _ -> emptyList() }
        advanceUntilIdle()
        val c = vm.state.value as DetailsUiState.Content
        assertTrue(c.series)
        assertTrue(c.seasons.isEmpty())
        assertEquals(1, c.selectedSeason)
        assertNull(c.resume)
        assertNull(vm.releaseEpisode(1))
    }

    @Test
    fun formatRuntime_cases() {
        assertEquals("2 ч 19 мин", formatRuntime(139))
        assertEquals("45 мин", formatRuntime(45))
        assertEquals("2 ч", formatRuntime(120))
        assertNull(formatRuntime(null))
        assertNull(formatRuntime(0))
    }
}
