package dev.gamspi.videogallery.details

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.FakeCatalogRepository
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsViewModelTest {
    private val repo = FakeCatalogRepository()

    private fun details(id: Int) = MovieDetails(
        movie = Movie(id, "M$id", "O$id", 1999, null, "overview"),
        imdbId = "tt$id",
        runtimeMin = 139,
        genres = listOf("Драма"),
        backdropUrl = null,
    )

    private fun settings(key: String? = "k") =
        SettingsRepository(FakeSecretStore(if (key == null) emptyMap() else mapOf("tmdb_api_key" to key)))

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loads_details_by_id() = runTest {
        repo.detailsResult = { details(it) }
        val vm = DetailsViewModel(repo, settings(), SavedStateHandle(mapOf("id" to 550)))
        vm.state.test {
            assertEquals(DetailsUiState.Loading, awaitItem())
            assertEquals(DetailsUiState.Content(details(550)), awaitItem())
        }
        assertEquals(listOf(550), repo.detailsCalls)
    }

    @Test
    fun error_then_retry() = runTest {
        var fail = true
        repo.detailsResult = { if (fail) throw CatalogException.Network else details(it) }
        val vm = DetailsViewModel(repo, settings(), SavedStateHandle(mapOf("id" to 7)))
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
        val vm = DetailsViewModel(repo, settings, SavedStateHandle(mapOf("id" to 3)))
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
    fun formatRuntime_cases() {
        assertEquals("2 ч 19 мин", formatRuntime(139))
        assertEquals("45 мин", formatRuntime(45))
        assertEquals("2 ч", formatRuntime(120))
        assertNull(formatRuntime(null))
        assertNull(formatRuntime(0))
    }
}
