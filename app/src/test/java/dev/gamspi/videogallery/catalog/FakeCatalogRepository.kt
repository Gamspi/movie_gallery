package dev.gamspi.videogallery.catalog

import dev.gamspi.videogallery.catalog.model.Episode
import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails

/** Configurable fake. Set a result or an exception per method; calls are recorded. */
class FakeCatalogRepository : CatalogRepository {
    var popularResult: () -> List<Movie> = { emptyList() }
    var genresResult: () -> List<Genre> = { emptyList() }
    var byGenreResult: (Int) -> List<Movie> = { emptyList() }
    var searchResult: (String) -> List<Movie> = { emptyList() }
    var seasonResult: (Int, Int) -> List<Episode> = { _, _ -> emptyList() }
    var detailsResult: (Int) -> MovieDetails = { error("detailsResult not configured") }

    var popularCalls = 0
    var genresCalls = 0
    val byGenreCalls = mutableListOf<Int>()
    val searchCalls = mutableListOf<String>()
    val detailsCalls = mutableListOf<Int>()
    val kindCalls = mutableListOf<MediaKind>()
    val seasonCalls = mutableListOf<Pair<Int, Int>>()

    override suspend fun popular(page: Int, kind: MediaKind): List<Movie> {
        popularCalls++
        kindCalls += kind
        return popularResult()
    }

    override suspend fun genres(kind: MediaKind): List<Genre> {
        genresCalls++
        kindCalls += kind
        return genresResult()
    }

    override suspend fun byGenre(genreId: Int, page: Int, kind: MediaKind): List<Movie> {
        byGenreCalls += genreId
        kindCalls += kind
        return byGenreResult(genreId)
    }

    override suspend fun search(query: String): List<Movie> {
        searchCalls += query
        return searchResult(query)
    }

    override suspend fun details(id: Int, kind: MediaKind): MovieDetails {
        detailsCalls += id
        kindCalls += kind
        return detailsResult(id)
    }

    override suspend fun season(id: Int, number: Int): List<Episode> {
        seasonCalls += id to number
        return seasonResult(id, number)
    }
}
