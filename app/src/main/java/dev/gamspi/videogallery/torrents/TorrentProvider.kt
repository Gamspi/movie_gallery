package dev.gamspi.videogallery.torrents

import dev.gamspi.videogallery.catalog.model.MovieDetails

/** Why a provider gave no results; shown to the user per provider. */
enum class UnavailableReason { Unavailable, Captcha, BadCredentials, NoCredentials }

class ProviderUnavailableException(
    val reason: UnavailableReason = UnavailableReason.Unavailable,
) : Exception()

interface TorrentProvider {
    val id: ProviderId
    suspend fun search(movie: MovieDetails, season: Int? = null): List<TorrentResult>

    /** Fills in infoHash and magnet for results found without them; throws when impossible. */
    suspend fun resolve(result: TorrentResult): TorrentResult =
        if (result.magnet.isNotBlank()) result else throw ProviderUnavailableException()
}
