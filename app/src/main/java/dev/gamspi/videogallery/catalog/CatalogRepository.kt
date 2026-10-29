package dev.gamspi.videogallery.catalog

import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails

/** All failures are thrown as [CatalogException]. */
interface CatalogRepository {
    suspend fun popular(page: Int = 1): List<Movie>
    suspend fun genres(): List<Genre>
    suspend fun byGenre(genreId: Int, page: Int = 1): List<Movie>
    suspend fun search(query: String): List<Movie>
    suspend fun details(id: Int): MovieDetails
}
