package dev.gamspi.videogallery.catalog.kinopoisk

import dev.gamspi.videogallery.catalog.model.Episode
import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.catalog.model.SeasonInfo
import dev.gamspi.videogallery.catalog.model.positiveRating

private val EXCLUDED_GENRES = setOf(
    "для взрослых", "новости", "реальное тв", "ток-шоу", "церемония", "концерт", "игра",
)

private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }

private fun String.capitalized(): String = replaceFirstChar { it.uppercaseChar() }

private fun movie(
    id: Int,
    nameRu: String?,
    nameEn: String?,
    nameOriginal: String?,
    year: Int?,
    posterUrl: String?,
    posterUrlPreview: String?,
    overview: String?,
    rating: Double?,
    kind: MediaKind = MediaKind.Movie,
): Movie {
    val title = nameRu.nonBlank() ?: nameOriginal.nonBlank() ?: nameEn.nonBlank() ?: ""
    return Movie(
        id = id,
        title = title,
        originalTitle = nameOriginal.nonBlank() ?: title,
        year = year,
        posterUrl = posterUrlPreview.nonBlank() ?: posterUrl.nonBlank(),
        overview = overview.orEmpty(),
        rating = rating.positiveRating(),
        kind = kind,
    )
}

/** Films and series are shown; an absent type counts as a film, anything else is dropped. */
fun FilmDto.kind(): MediaKind? = when (type) {
    null, "FILM" -> MediaKind.Movie
    "TV_SERIES", "MINI_SERIES", "TV_SHOW" -> MediaKind.Series
    else -> null
}

fun FilmDto.toMovie(kind: MediaKind = MediaKind.Movie): Movie =
    movie(kinopoiskId, nameRu, nameEn, nameOriginal, year, posterUrl, posterUrlPreview, null, ratingKinopoisk, kind)

/** Season 0 (specials) is dropped. */
fun SeasonsDto.toSeasonInfos(): List<SeasonInfo> = items.orEmpty()
    .filter { (it.number ?: 0) > 0 }
    .map { SeasonInfo(it.number!!, it.episodes.orEmpty().size) }

fun SeasonsDto.toEpisodes(season: Int): List<Episode> = items.orEmpty()
    .filter { it.number == season }
    .flatMap { it.episodes.orEmpty() }
    .mapNotNull { e ->
        val n = e.episodeNumber ?: return@mapNotNull null
        Episode(season, n, e.nameRu.nonBlank() ?: e.nameEn.nonBlank() ?: "Серия $n", null, null, e.releaseDate)
    }

fun GenreDto.toGenreOrNull(): Genre? {
    val name = genre.nonBlank() ?: return null
    if (name.lowercase() in EXCLUDED_GENRES) return null
    val genreId = id ?: return null
    return Genre(genreId, name.capitalized())
}

fun FilmDetailsDto.toDetails(kind: MediaKind = MediaKind.Movie, seasons: List<SeasonInfo> = emptyList()): MovieDetails =
    MovieDetails(
    movie = movie(kinopoiskId, nameRu, nameEn, nameOriginal, year, posterUrl, posterUrlPreview, description,
        ratingKinopoisk, kind),
    imdbId = imdbId.nonBlank(),
    runtimeMin = filmLength?.takeIf { it > 0 },
    genres = genres.orEmpty().mapNotNull { it.genre.nonBlank()?.capitalized() },
    backdropUrl = coverUrl.nonBlank(),
    seasons = seasons,
)
