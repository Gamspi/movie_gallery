package dev.gamspi.videogallery.gallery

import app.cash.turbine.test
import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.FakeCatalogRepository
import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.history.FakeWatchHistoryStore
import dev.gamspi.videogallery.history.WatchEntry
import dev.gamspi.videogallery.settings.CatalogSource
import dev.gamspi.videogallery.settings.FakeSecretStore
import dev.gamspi.videogallery.settings.SettingsRepository
import dev.gamspi.videogallery.ui.components.ErrorKind
import dev.gamspi.videogallery.ui.components.toErrorKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {
    private val repo = FakeCatalogRepository()

    private fun movie(id: Int) = Movie(id, "M$id", "O$id", 2020, null, "")
    private fun settings(key: String? = "k") =
        SettingsRepository(FakeSecretStore(mapOf("catalog_source" to "Tmdb") + if (key == null) emptyMap() else mapOf("tmdb_api_key" to key)))

    // Existing movie scenarios run on the Movies tab (the default tab is All).
    private fun newVm(repo: FakeCatalogRepository, settings: SettingsRepository, history: FakeWatchHistoryStore) =
        GalleryViewModel(repo, settings, history).also { it.selectTab(HomeTab.Movies) }

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun happyRepo(genreCount: Int = 10) {
        repo.popularResult = { listOf(movie(1)) }
        repo.genresResult = { (1..genreCount).map { Genre(it, "G$it") } }
        repo.byGenreResult = { listOf(movie(100 + it)) }
    }

    @Test
    fun load_success_popularFirstThenGenres() = runTest {
        happyRepo(10)
        val vm = newVm(repo, settings(), FakeWatchHistoryStore())
        vm.state.test {
            assertEquals(GalleryUiState.Loading(initial = true), awaitItem())
            val content = awaitItem() as GalleryUiState.Content
            assertEquals("Популярное", content.rows[0].title)
            assertEquals(9, content.rows.size)
            assertEquals("G1", content.rows[1].title)
        }
    }

    @Test
    fun load_genreRowFails_rowSkipped() = runTest {
        happyRepo(3)
        repo.byGenreResult = { if (it == 2) throw CatalogException.Network else listOf(movie(it)) }
        val vm = newVm(repo, settings(), FakeWatchHistoryStore())
        vm.state.test {
            assertEquals(GalleryUiState.Loading(initial = true), awaitItem())
            val content = awaitItem() as GalleryUiState.Content
            assertEquals(listOf("Популярное", "G1", "G3"), content.rows.map { it.title })
        }
    }

    @Test
    fun load_missingKey_errorMissingKey() = runTest {
        repo.popularResult = { throw CatalogException.MissingApiKey }
        val vm = newVm(repo, settings(null), FakeWatchHistoryStore())
        vm.state.test {
            assertEquals(GalleryUiState.Loading(initial = true), awaitItem())
            assertEquals(GalleryUiState.Error(ErrorKind.MissingKey), awaitItem())
        }
    }

    @Test
    fun load_network_thenRetry_content() = runTest {
        happyRepo(1)
        var fail = true
        repo.popularResult = { if (fail) throw CatalogException.Network else listOf(movie(1)) }
        val vm = newVm(repo, settings(), FakeWatchHistoryStore())
        vm.state.test {
            assertEquals(GalleryUiState.Loading(initial = true), awaitItem())
            assertEquals(GalleryUiState.Error(ErrorKind.Network), awaitItem())
            fail = false
            vm.retry()
            assertEquals(GalleryUiState.Loading(initial = false), awaitItem())
            assertTrue(awaitItem() is GalleryUiState.Content)
        }
        assertEquals(2, repo.popularCalls)
        assertEquals(2, repo.genresCalls)
    }

    @Test
    fun load_genresUnauthorized_errorBadKey() = runTest {
        repo.popularResult = { listOf(movie(1)) }
        repo.genresResult = { throw CatalogException.Unauthorized }
        val vm = newVm(repo, settings(), FakeWatchHistoryStore())
        vm.state.test {
            assertEquals(GalleryUiState.Loading(initial = true), awaitItem())
            assertEquals(GalleryUiState.Error(ErrorKind.BadKey), awaitItem())
        }
    }

    @Test
    fun load_emptyGenreRow_skipped() = runTest {
        happyRepo(2)
        repo.byGenreResult = { if (it == 1) emptyList() else listOf(movie(it)) }
        val vm = newVm(repo, settings(), FakeWatchHistoryStore())
        vm.state.test {
            assertEquals(GalleryUiState.Loading(initial = true), awaitItem())
            val content = awaitItem() as GalleryUiState.Content
            assertEquals(listOf("Популярное", "G2"), content.rows.map { it.title })
        }
    }

    @Test
    fun keyChanged_reloads() = runTest {
        happyRepo(1)
        repo.popularResult = { throw CatalogException.MissingApiKey }
        val settings = settings(null)
        val vm = newVm(repo, settings, FakeWatchHistoryStore())
        vm.state.test {
            assertEquals(GalleryUiState.Loading(initial = true), awaitItem())
            assertEquals(GalleryUiState.Error(ErrorKind.MissingKey), awaitItem())
            repo.popularResult = { listOf(movie(1)) }
            settings.setTmdbApiKey("new")
            assertEquals(GalleryUiState.Loading(initial = false), awaitItem())
            assertTrue(awaitItem() is GalleryUiState.Content)
        }
    }

    @Test
    fun sourceChanged_reloads() = runTest {
        happyRepo(1)
        val settings = settings()
        val vm = newVm(repo, settings, FakeWatchHistoryStore())
        vm.state.test {
            assertEquals(GalleryUiState.Loading(initial = true), awaitItem())
            assertTrue(awaitItem() is GalleryUiState.Content)
            settings.setCatalogSource(CatalogSource.Kinopoisk)
            assertEquals(GalleryUiState.Loading(initial = false), awaitItem())
            assertTrue(awaitItem() is GalleryUiState.Content)
        }
        assertEquals(2, repo.popularCalls)
    }

    @Test
    fun initialLoadIsMarkedInitial() = runTest {
        happyRepo(1)
        val vm = newVm(repo, settings(), FakeWatchHistoryStore())
        vm.state.test {
            assertEquals(GalleryUiState.Loading(initial = true), awaitItem())
            assertTrue(awaitItem() is GalleryUiState.Content)
        }
    }

    @Test
    fun retryLoadIsNotInitial() = runTest {
        happyRepo(1)
        val vm = newVm(repo, settings(), FakeWatchHistoryStore())
        vm.state.test {
            assertEquals(GalleryUiState.Loading(initial = true), awaitItem())
            assertTrue(awaitItem() is GalleryUiState.Content)
            vm.retry()
            assertEquals(GalleryUiState.Loading(initial = false), awaitItem())
            assertTrue(awaitItem() is GalleryUiState.Content)
        }
    }

    @Test
    fun history_exposedSeparately_updatesWithoutReloadingRows() = runTest {
        happyRepo(1)
        val history = FakeWatchHistoryStore()
        val vm = newVm(repo, settings(), history)
        vm.state.test {
            awaitItem()
            val content = awaitItem() as GalleryUiState.Content
            history.upsert(WatchEntry(7, "M7", positionKey = "k", positionMs = 10, durationMs = 100, updatedAt = 1))
            assertEquals(listOf(7), vm.history.value.map { it.movieId })
            expectNoEvents()
            assertEquals(content, vm.state.value)
        }
    }

    @Test
    fun quotaExceeded_mapsToKind() {
        assertEquals(ErrorKind.QuotaExceeded, CatalogException.QuotaExceeded.toErrorKind())
    }

    @Test
    fun httpAndParse_mapToOther() {
        assertEquals(ErrorKind.Other, CatalogException.Http(500).toErrorKind())
        assertEquals(ErrorKind.Other, CatalogException.Parse.toErrorKind())
        assertEquals(ErrorKind.BadKey, CatalogException.Unauthorized.toErrorKind())
    }

    private fun series(id: Int) = Movie(id, "S$id", "", 2021, null, "", kind = MediaKind.Series)

    private fun kindRepo() {
        repo.popularFor = { if (it == MediaKind.Series) listOf(series(1)) else listOf(movie(1), movie(2)) }
        repo.genresFor = {
            if (it == MediaKind.Series) listOf(Genre(50, "Драма"), Genre(51, "Только сериалы"))
            else listOf(Genre(10, "Драма"), Genre(11, "Комедия"))
        }
        repo.byGenreFor = { kind, id -> if (kind == MediaKind.Series) listOf(series(id)) else listOf(movie(id)) }
    }

    @Test
    fun moviesTabKeepsCurrentRows() = runTest {
        kindRepo()
        val vm = GalleryViewModel(repo, settings(), FakeWatchHistoryStore())
        vm.selectTab(HomeTab.Movies)
        vm.state.test {
            awaitItem()
            val content = awaitItem() as GalleryUiState.Content
            assertEquals(listOf("Популярное", "Драма", "Комедия"), content.rows.map { it.title })
            assertEquals(listOf(1, 2), content.rows[0].movies.map { it.id })
            assertTrue(repo.kindCalls.all { it == MediaKind.Movie })
        }
    }

    @Test
    fun seriesTabUsesSeriesCatalog() = runTest {
        kindRepo()
        val vm = GalleryViewModel(repo, settings(), FakeWatchHistoryStore())
        vm.selectTab(HomeTab.Series)
        vm.state.test {
            awaitItem()
            val content = awaitItem() as GalleryUiState.Content
            assertEquals(listOf("Популярные сериалы", "Драма", "Только сериалы"), content.rows.map { it.title })
            assertTrue(content.rows.all { r -> r.movies.all { it.kind == MediaKind.Series } })
            assertTrue(repo.kindCalls.all { it == MediaKind.Series })
        }
    }

    @Test
    fun allTabInterleavesByGenreName() = runTest {
        kindRepo()
        val vm = GalleryViewModel(repo, settings(), FakeWatchHistoryStore())
        assertEquals(HomeTab.All, vm.tab.value)
        vm.state.test {
            awaitItem()
            val content = awaitItem() as GalleryUiState.Content
            assertEquals(listOf("Популярное", "Драма", "Комедия"), content.rows.map { it.title })
            assertEquals(listOf(MediaKind.Movie, MediaKind.Series, MediaKind.Movie), content.rows[0].movies.map { it.kind })
            assertEquals(listOf(MediaKind.Movie, MediaKind.Series), content.rows[1].movies.map { it.kind })
            assertEquals(listOf(MediaKind.Movie), content.rows[2].movies.map { it.kind })
        }
    }

    @Test
    fun interleave_alternatesAndAppendsTail() {
        assertEquals(listOf<Any>(1, "a", 2, 3), interleave(listOf<Any>(1, 2, 3), listOf<Any>("a")))
        assertEquals(listOf(1, 2), interleave(emptyList(), listOf(1, 2)))
    }

    @Test
    fun selectTabReloadsAndCachesPerTab() = runTest {
        kindRepo()
        val vm = GalleryViewModel(repo, settings(), FakeWatchHistoryStore())
        vm.selectTab(HomeTab.Movies)
        vm.state.test {
            awaitItem()
            assertTrue(awaitItem() is GalleryUiState.Content)
            vm.selectTab(HomeTab.Series)
            assertEquals(GalleryUiState.Loading(initial = false), awaitItem())
            assertTrue(awaitItem() is GalleryUiState.Content)
            val calls = repo.kindCalls.size
            vm.selectTab(HomeTab.Movies)
            assertTrue(awaitItem() is GalleryUiState.Content)
            vm.selectTab(HomeTab.Series)
            assertTrue(awaitItem() is GalleryUiState.Content)
            assertEquals(calls, repo.kindCalls.size)
        }
    }
}
