package dev.gamspi.videogallery.catalog

import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails

/** Configurable fake. Set a result or an exception per method; calls are recorded. */
class FakeCatalogRepository : CatalogRepository {
    var popularResult: () -> List<Movie> = { emptyList() }
    var genresResult: () -> List<Genre> = { emptyList() }
    var byGenreResult: (Int) -> List<Movie> = { emptyList() }
    var searchResult: (String) -> List<Movie> = { emptyList() }
    var detailsResult: (Int) -> MovieDetails = { error("detailsResult not configured") }

    var popularCalls = 0
    var genresCalls = 0
    val byGenreCalls = mutableListOf<Int>()
    val searchCalls = mutableListOf<String>()
    val detailsCalls = mutableListOf<Int>()

    override suspend fun popular(page: Int): List<Movie> {
        popularCalls++
        return popularResult()
    }

    override suspend fun genres(): List<Genre> {
        genresCalls++
        return genresResult()
    }

    override suspend fun byGenre(genreId: Int, page: Int): List<Movie> {
        byGenreCalls += genreId
        return byGenreResult(genreId)
    }

    override suspend fun search(query: String): List<Movie> {
        searchCalls += query
        return searchResult(query)
    }

    override suspend fun details(id: Int): MovieDetails {
        detailsCalls += id
        return detailsResult(id)
    }
}
