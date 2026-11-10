package dev.gamspi.videogallery.catalog

import dev.gamspi.videogallery.catalog.model.Episode
import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails

/** All failures are thrown as [CatalogException]. */
interface CatalogRepository {
    /** Titles similar to [id] in this catalog; unsupported catalogs return an empty list. */
    suspend fun recommendations(id: Int, kind: MediaKind): List<Movie> = emptyList()
    /** This catalog's title for an IMDb id, preferring [kind]; null when not found. */
    suspend fun findByImdb(imdbId: String, kind: MediaKind): Movie? = null
    /** Null kind mixes films and series. Unsupported catalogs return no row, without extra requests. */
    suspend fun upcoming(today: java.time.LocalDate, kind: MediaKind? = null): List<Movie> = emptyList()
    suspend fun popular(page: Int = 1, kind: MediaKind = MediaKind.Movie): List<Movie>
    suspend fun genres(kind: MediaKind = MediaKind.Movie): List<Genre>
    suspend fun byGenre(genreId: Int, page: Int = 1, kind: MediaKind = MediaKind.Movie): List<Movie>
    /** Movies and series mixed. */
    suspend fun search(query: String): List<Movie>
    suspend fun details(id: Int, kind: MediaKind = MediaKind.Movie): MovieDetails
    /** Series only. */
    suspend fun season(id: Int, number: Int): List<Episode>
    /** Repository of one catalog regardless of the selected one; single-source repositories return themselves. */
    fun forSource(source: dev.gamspi.videogallery.settings.CatalogSource): CatalogRepository = this
}
