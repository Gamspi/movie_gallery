package dev.gamspi.videogallery.gallery

import app.cash.turbine.test
import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.FakeCatalogRepository
import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.Movie
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
        SettingsRepository(FakeSecretStore(if (key == null) emptyMap() else mapOf("tmdb_api_key" to key)))

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
        val vm = GalleryViewModel(repo, settings())
        vm.state.test {
            assertEquals(GalleryUiState.Loading, awaitItem())
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
        val vm = GalleryViewModel(repo, settings())
        vm.state.test {
            assertEquals(GalleryUiState.Loading, awaitItem())
            val content = awaitItem() as GalleryUiState.Content
            assertEquals(listOf("Популярное", "G1", "G3"), content.rows.map { it.title })
        }
    }

    @Test
    fun load_missingKey_errorMissingKey() = runTest {
        repo.popularResult = { throw CatalogException.MissingApiKey }
        val vm = GalleryViewModel(repo, settings(null))
        vm.state.test {
            assertEquals(GalleryUiState.Loading, awaitItem())
            assertEquals(GalleryUiState.Error(ErrorKind.MissingKey), awaitItem())
        }
    }

    @Test
    fun load_network_thenRetry_content() = runTest {
        happyRepo(1)
        var fail = true
        repo.popularResult = { if (fail) throw CatalogException.Network else listOf(movie(1)) }
        val vm = GalleryViewModel(repo, settings())
        vm.state.test {
            assertEquals(GalleryUiState.Loading, awaitItem())
            assertEquals(GalleryUiState.Error(ErrorKind.Network), awaitItem())
            fail = false
            vm.retry()
            assertEquals(GalleryUiState.Loading, awaitItem())
            assertTrue(awaitItem() is GalleryUiState.Content)
        }
        assertEquals(2, repo.popularCalls)
        assertEquals(2, repo.genresCalls)
    }

    @Test
    fun load_genresUnauthorized_errorBadKey() = runTest {
        repo.popularResult = { listOf(movie(1)) }
        repo.genresResult = { throw CatalogException.Unauthorized }
        val vm = GalleryViewModel(repo, settings())
        vm.state.test {
            assertEquals(GalleryUiState.Loading, awaitItem())
            assertEquals(GalleryUiState.Error(ErrorKind.BadKey), awaitItem())
        }
    }

    @Test
    fun load_emptyGenreRow_skipped() = runTest {
        happyRepo(2)
        repo.byGenreResult = { if (it == 1) emptyList() else listOf(movie(it)) }
        val vm = GalleryViewModel(repo, settings())
        vm.state.test {
            assertEquals(GalleryUiState.Loading, awaitItem())
            val content = awaitItem() as GalleryUiState.Content
            assertEquals(listOf("Популярное", "G2"), content.rows.map { it.title })
        }
    }

    @Test
    fun keyChanged_reloads() = runTest {
        happyRepo(1)
        repo.popularResult = { throw CatalogException.MissingApiKey }
        val settings = settings(null)
        val vm = GalleryViewModel(repo, settings)
        vm.state.test {
            assertEquals(GalleryUiState.Loading, awaitItem())
            assertEquals(GalleryUiState.Error(ErrorKind.MissingKey), awaitItem())
            repo.popularResult = { listOf(movie(1)) }
            settings.setTmdbApiKey("new")
            assertEquals(GalleryUiState.Loading, awaitItem())
            assertTrue(awaitItem() is GalleryUiState.Content)
        }
    }

    @Test
    fun httpAndParse_mapToOther() {
        assertEquals(ErrorKind.Other, CatalogException.Http(500).toErrorKind())
        assertEquals(ErrorKind.Other, CatalogException.Parse.toErrorKind())
        assertEquals(ErrorKind.BadKey, CatalogException.Unauthorized.toErrorKind())
    }
}
