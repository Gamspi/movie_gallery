package dev.gamspi.videogallery.catalog

import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.settings.CatalogSource
import dev.gamspi.videogallery.settings.SettingsRepository

/** Routes every call to the repository of the currently selected catalog source. */
class SwitchingCatalogRepository(
    private val settings: SettingsRepository,
    private val tmdb: CatalogRepository,
    private val kinopoisk: CatalogRepository,
) : CatalogRepository {
    private val current: CatalogRepository
        get() = if (settings.catalogSource.value == CatalogSource.Tmdb) tmdb else kinopoisk

    override suspend fun popular(page: Int): List<Movie> = current.popular(page)
    override suspend fun genres(): List<Genre> = current.genres()
    override suspend fun byGenre(genreId: Int, page: Int): List<Movie> = current.byGenre(genreId, page)
    override suspend fun search(query: String): List<Movie> = current.search(query)
    override suspend fun details(id: Int): MovieDetails = current.details(id)
}
