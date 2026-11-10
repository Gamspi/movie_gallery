package dev.gamspi.videogallery.catalog.tmdb

import dev.gamspi.videogallery.catalog.model.Episode
import dev.gamspi.videogallery.catalog.model.Genre
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.catalog.model.MovieDetails
import dev.gamspi.videogallery.catalog.model.SeasonInfo
import dev.gamspi.videogallery.catalog.model.positiveRating

private const val POSTER_BASE = "https://image.tmdb.org/t/p/w342"
private const val STILL_BASE = "https://image.tmdb.org/t/p/w300"
private const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280"

private fun yearOf(date: String?): Int? =
    date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt()

private fun movie(
    id: Int,
    title: String?,
    originalTitle: String?,
    releaseDate: String?,
    posterPath: String?,
    overview: String?,
    rating: Double?,
    kind: MediaKind = MediaKind.Movie,
): Movie {
    val t = title?.takeIf { it.isNotBlank() } ?: originalTitle.orEmpty()
    return Movie(
        id = id,
        title = t,
        originalTitle = originalTitle?.takeIf { it.isNotBlank() } ?: t,
        year = yearOf(releaseDate),
        posterUrl = posterPath?.takeIf { it.isNotEmpty() }?.let { POSTER_BASE + it },
        overview = overview.orEmpty(),
        rating = rating.positiveRating(),
        kind = kind,
        premiereDate = dev.gamspi.videogallery.catalog.model.releaseDate(releaseDate)?.toString(),
    )
}

fun MovieDto.toMovie(): Movie = movie(id, title, originalTitle, releaseDate, posterPath, overview, voteAverage)

fun MovieDto.toSeries(): Movie =
    movie(id, name, originalName, firstAirDate, posterPath, overview, voteAverage, MediaKind.Series)

/** search/multi entry: movies and series kept, people and unknown types dropped. */
fun MovieDto.toMultiResult(): Movie? = when (mediaType) {
    "movie" -> toMovie()
    "tv" -> toSeries()
    else -> null
}

fun GenreDto.toGenre(): Genre = Genre(id, name.orEmpty())

fun MovieDetailsDto.toDetails(): MovieDetails = MovieDetails(
    movie = movie(id, title, originalTitle, releaseDate, posterPath, overview, voteAverage),
    imdbId = externalIds?.imdbId?.takeIf { it.isNotEmpty() },
    runtimeMin = runtime?.takeIf { it > 0 },
    genres = genres.orEmpty().mapNotNull { it.name },
    backdropUrl = backdropPath?.takeIf { it.isNotEmpty() }?.let { BACKDROP_BASE + it },
)

fun MovieDetailsDto.toSeriesDetails(): MovieDetails = MovieDetails(
    movie = movie(id, name, originalName, firstAirDate, posterPath, overview, voteAverage, MediaKind.Series),
    imdbId = externalIds?.imdbId?.takeIf { it.isNotEmpty() },
    runtimeMin = episodeRunTime?.firstOrNull { it > 0 },
    genres = genres.orEmpty().mapNotNull { it.name },
    backdropUrl = backdropPath?.takeIf { it.isNotEmpty() }?.let { BACKDROP_BASE + it },
    // season 0 is "Specials"
    seasons = seasons.orEmpty().filter { it.seasonNumber > 0 }
        .map { SeasonInfo(it.seasonNumber, it.episodeCount ?: 0, it.airDate) },
)

fun SeasonDto.toEpisodes(season: Int): List<Episode> = episodes.orEmpty().map {
    Episode(
        season = it.seasonNumber ?: season,
        number = it.episodeNumber,
        title = it.name.orEmpty(),
        runtimeMin = it.runtime?.takeIf { r -> r > 0 },
        stillUrl = it.stillPath?.takeIf { p -> p.isNotEmpty() }?.let { p -> STILL_BASE + p },
        airDate = it.airDate,
    )
}
