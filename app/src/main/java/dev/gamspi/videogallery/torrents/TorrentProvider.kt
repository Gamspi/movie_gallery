package dev.gamspi.videogallery.torrents

import dev.gamspi.videogallery.catalog.model.MovieDetails

class ProviderUnavailableException : Exception()

interface TorrentProvider {
    val id: ProviderId
    suspend fun search(movie: MovieDetails): List<TorrentResult>
}
