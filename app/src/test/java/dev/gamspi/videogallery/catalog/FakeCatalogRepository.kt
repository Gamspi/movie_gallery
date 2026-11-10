package dev.gamspi.videogallery.catalog

import dev.gamspi.videogallery.catalog.model.Episode
import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails

/** Configurable fake. Set a result or an exception per method; calls are recorded. */
class FakeCatalogRepository : CatalogRepository {
    var recommendationsResult: suspend (Int, MediaKind) -> List<Movie> = { _, _ -> emptyList() }
    var findByImdbResult: suspend (String, MediaKind) -> Movie? = { _, _ -> null }
    val recommendationCalls = mutableListOf<Pair<Int, MediaKind>>()
    val imdbCalls = mutableListOf<Pair<String, MediaKind>>()
    override suspend fun recommendations(id: Int, kind: MediaKind): List<Movie> {
        recommendationCalls += id to kind
        return recommendationsResult(id, kind)
    }
    override suspend fun findByImdb(imdbId: String, kind: MediaKind): Movie? {
        imdbCalls += imdbId to kind
        return findByImdbResult(imdbId, kind)
    }
    var upcomingResult: suspend (java.time.LocalDate, MediaKind?) -> List<Movie> = { _, _ -> emptyList() }
    val upcomingCalls = mutableListOf<MediaKind?>()
    override suspend fun upcoming(today: java.time.LocalDate, kind: MediaKind?): List<Movie> {
        upcomingCalls += kind
        return upcomingResult(today, kind)
    }
    var popularResult: () -> List<Movie> = { emptyList() }
    var genresResult: () -> List<Genre> = { emptyList() }
    var byGenreResult: (Int) -> List<Movie> = { emptyList() }
    var popularFor: ((MediaKind) -> List<Movie>)? = null
    var genresFor: ((MediaKind) -> List<Genre>)? = null
    var byGenreFor: ((MediaKind, Int) -> List<Movie>)? = null
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
        return popularFor?.invoke(kind) ?: popularResult()
    }

    override suspend fun genres(kind: MediaKind): List<Genre> {
        genresCalls++
        kindCalls += kind
        return genresFor?.invoke(kind) ?: genresResult()
    }

    override suspend fun byGenre(genreId: Int, page: Int, kind: MediaKind): List<Movie> {
        byGenreCalls += genreId
        kindCalls += kind
        return byGenreFor?.invoke(kind, genreId) ?: byGenreResult(genreId)
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
