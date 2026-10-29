package dev.gamspi.videogallery.search

import app.cash.turbine.test
import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.FakeCatalogRepository
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.ui.components.ErrorKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val repo = FakeCatalogRepository()

    private fun movie(id: Int) = Movie(id, "M$id", "O$id", 2020, null, "")

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun blankQuery_idle_noApiCall() = runTest {
        val vm = SearchViewModel(repo)
        vm.state.test {
            assertEquals(SearchUiState.Idle, awaitItem())
            vm.onQueryChange("   ")
            advanceTimeBy(500)
            expectNoEvents()
        }
        assertEquals(0, repo.searchCalls.size)
    }

    @Test
    fun fastTyping_onlyLastQuerySent() = runTest {
        repo.searchResult = { listOf(movie(1)) }
        val vm = SearchViewModel(repo)
        vm.state.test {
            assertEquals(SearchUiState.Idle, awaitItem())
            vm.onQueryChange("м")
            advanceTimeBy(100)
            vm.onQueryChange("ма")
            advanceTimeBy(100)
            vm.onQueryChange("мат")
            advanceTimeBy(500)
            assertEquals(SearchUiState.Loading, awaitItem())
            assertEquals(SearchUiState.Results(listOf(movie(1))), awaitItem())
        }
        assertEquals(listOf("мат"), repo.searchCalls)
    }

    @Test
    fun noResults_empty() = runTest {
        repo.searchResult = { emptyList() }
        val vm = SearchViewModel(repo)
        vm.state.test {
            assertEquals(SearchUiState.Idle, awaitItem())
            vm.onQueryChange("zzz")
            advanceTimeBy(500)
            assertEquals(SearchUiState.Loading, awaitItem())
            assertEquals(SearchUiState.Empty, awaitItem())
        }
    }

    @Test
    fun results_shown() = runTest {
        repo.searchResult = { listOf(movie(1), movie(2)) }
        val vm = SearchViewModel(repo)
        vm.state.test {
            assertEquals(SearchUiState.Idle, awaitItem())
            vm.onQueryChange(" матрица ")
            advanceTimeBy(500)
            assertEquals(SearchUiState.Loading, awaitItem())
            assertEquals(SearchUiState.Results(listOf(movie(1), movie(2))), awaitItem())
        }
        assertEquals(listOf("матрица"), repo.searchCalls)
    }

    @Test
    fun network_error_thenRetry() = runTest {
        var fail = true
        repo.searchResult = { if (fail) throw CatalogException.Network else listOf(movie(1)) }
        val vm = SearchViewModel(repo)
        vm.state.test {
            assertEquals(SearchUiState.Idle, awaitItem())
            vm.onQueryChange("abc")
            advanceTimeBy(500)
            assertEquals(SearchUiState.Loading, awaitItem())
            assertEquals(SearchUiState.Error(ErrorKind.Network), awaitItem())
            fail = false
            vm.retry()
            advanceTimeBy(1)
            assertEquals(SearchUiState.Loading, awaitItem())
            assertEquals(SearchUiState.Results(listOf(movie(1))), awaitItem())
        }
        assertEquals(listOf("abc", "abc"), repo.searchCalls)
    }

    @Test
    fun clearedQuery_backToIdle() = runTest {
        repo.searchResult = { listOf(movie(1)) }
        val vm = SearchViewModel(repo)
        vm.state.test {
            assertEquals(SearchUiState.Idle, awaitItem())
            vm.onQueryChange("abc")
            advanceTimeBy(500)
            assertEquals(SearchUiState.Loading, awaitItem())
            awaitItem()
            vm.onQueryChange("")
            advanceTimeBy(500)
            assertEquals(SearchUiState.Idle, awaitItem())
        }
        assertEquals(listOf("abc"), repo.searchCalls)
    }
}
