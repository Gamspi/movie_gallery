package dev.gamspi.videogallery.catalog

import dev.gamspi.videogallery.catalog.model.Episode
import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails

/** All failures are thrown as [CatalogException]. */
interface CatalogRepository {
    suspend fun popular(page: Int = 1, kind: MediaKind = MediaKind.Movie): List<Movie>
    suspend fun genres(kind: MediaKind = MediaKind.Movie): List<Genre>
    suspend fun byGenre(genreId: Int, page: Int = 1, kind: MediaKind = MediaKind.Movie): List<Movie>
    /** Movies and series mixed. */
    suspend fun search(query: String): List<Movie>
    suspend fun details(id: Int, kind: MediaKind = MediaKind.Movie): MovieDetails
    /** Series only. */
    suspend fun season(id: Int, number: Int): List<Episode>
}
