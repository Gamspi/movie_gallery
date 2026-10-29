package dev.gamspi.videogallery.releases

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.FakeCatalogRepository
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.settings.FakeSecretStore
import dev.gamspi.videogallery.settings.SettingsRepository
import dev.gamspi.videogallery.settings.TrackerConfig
import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.torrents.ProviderUnavailableException
import dev.gamspi.videogallery.torrents.Quality
import dev.gamspi.videogallery.torrents.TorrentProvider
import dev.gamspi.videogallery.torrents.TorrentResult
import dev.gamspi.videogallery.torrents.TorrentSearch
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
        val block: suspend () -> List<TorrentResult>,
    ) : TorrentProvider {
        override suspend fun search(movie: MovieDetails) = block()
    }

    private fun settings() = SettingsRepository(FakeSecretStore())

    private fun vm(
        settings: SettingsRepository = settings(),
        vararg providers: TorrentProvider,
    ) = ReleasesViewModel(
        repo,
        TorrentSearch({ providers.toList() }),
        settings,
        SavedStateHandle(mapOf("id" to 5)),
    )

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
            assertEquals(null, c.filter)
            assertFalse(c.noProviders)
        }
        assertEquals(listOf(5), repo.detailsCalls)
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
            vm.setFilter(Quality.FullHd)
            val c = awaitItem() as ReleasesUiState.Content
            assertEquals(Quality.FullHd, c.filter)
            assertEquals(listOf("a"), c.visible.map { it.infoHash })
            vm.setFilter(null)
            assertEquals(3, (awaitItem() as ReleasesUiState.Content).visible.size)
        }
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
}
