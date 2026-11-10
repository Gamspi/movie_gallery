package dev.gamspi.videogallery.catalog

import dev.gamspi.videogallery.catalog.model.Episode
import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.settings.CatalogSource
import dev.gamspi.videogallery.settings.SettingsRepository

/** Routes every call to the repository of the currently selected catalog source. */
class SwitchingCatalogRepository(
    private val settings: SettingsRepository,
    private val tmdb: CatalogRepository,
    private val kinopoisk: CatalogRepository,
    private val poiskkino: CatalogRepository,
) : CatalogRepository {
    private val current: CatalogRepository
        get() = forSource(settings.catalogSource.value)

    override fun forSource(source: CatalogSource): CatalogRepository = when (source) {
        CatalogSource.Tmdb -> tmdb
        CatalogSource.Kinopoisk -> kinopoisk
        CatalogSource.PoiskKino -> poiskkino
    }

    override suspend fun popular(page: Int, kind: MediaKind): List<Movie> = current.popular(page, kind)
    override suspend fun upcoming(today: java.time.LocalDate, kind: MediaKind?): List<Movie> = current.upcoming(today, kind)
    override suspend fun genres(kind: MediaKind): List<Genre> = current.genres(kind)
    override suspend fun byGenre(genreId: Int, page: Int, kind: MediaKind): List<Movie> =
        current.byGenre(genreId, page, kind)
    override suspend fun search(query: String): List<Movie> = current.search(query)
    override suspend fun details(id: Int, kind: MediaKind): MovieDetails = current.details(id, kind)
    override suspend fun season(id: Int, number: Int): List<Episode> = current.season(id, number)
}
