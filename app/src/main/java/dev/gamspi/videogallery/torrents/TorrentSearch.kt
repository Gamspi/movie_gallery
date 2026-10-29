package dev.gamspi.videogallery.torrents

import dev.gamspi.videogallery.catalog.model.MovieDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

data class SearchOutcome(val results: List<TorrentResult>, val unavailable: List<ProviderId>)

class TorrentSearch(
    private val providers: () -> List<TorrentProvider>,
    private val timeoutMs: Long = 15_000,
) {
    suspend fun search(movie: MovieDetails): SearchOutcome = coroutineScope {
        val answers = providers().map { p ->
            async {
                try {
                    p to withTimeout(timeoutMs) { p.search(movie) }
                } catch (e: TimeoutCancellationException) {
                    p to null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    p to null
                }
            }
        }.awaitAll()
        val results = answers.flatMap { it.second.orEmpty() }
            .groupBy { it.infoHash.lowercase() }
            .map { (_, v) -> v.maxBy { it.seeders } }
            .sortedByDescending { it.seeders }
        SearchOutcome(results, answers.filter { it.second == null }.map { it.first.id })
    }
}
