package dev.gamspi.videogallery.torrents

import dev.gamspi.videogallery.catalog.model.MovieDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

data class SearchOutcome(
    val results: List<TorrentResult>,
    val unavailable: List<ProviderId>,
    /** Reason per unavailable provider. */
    val reasons: Map<ProviderId, UnavailableReason> = emptyMap(),
)

class TorrentSearch(
    private val providers: () -> List<TorrentProvider>,
    private val timeoutMs: Long = 15_000,
    /** Providers asked to resolve selected results; independent of the enabled set. */
    private val resolvers: () -> List<TorrentProvider> = providers,
) {
    suspend fun search(movie: MovieDetails, season: Int? = null): SearchOutcome = coroutineScope {
        val answers = providers().map { p ->
            async {
                try {
                    Answer(p.id, withTimeout(timeoutMs) { p.search(movie, season) }, null)
                } catch (e: TimeoutCancellationException) {
                    Answer(p.id, null, UnavailableReason.Unavailable)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ProviderUnavailableException) {
                    Answer(p.id, null, e.reason)
                } catch (e: Exception) {
                    Answer(p.id, null, UnavailableReason.Unavailable)
                }
            }
        }.awaitAll()
        val results = answers.flatMap { it.results.orEmpty() }
            .groupBy { it.key }
            .map { (_, v) -> v.maxBy { it.seeders } }
            .sortedByDescending { it.seeders }
        val failed = answers.filter { it.results == null }
        SearchOutcome(results, failed.map { it.id }, failed.associate { it.id to it.reason!! })
    }

    /** Returns [result] with a magnet; throws [ProviderUnavailableException] on failure or timeout. */
    suspend fun resolve(result: TorrentResult): TorrentResult {
        if (!result.needsResolve) return result
        val provider = resolvers().firstOrNull { it.id == result.provider }
            ?: throw ProviderUnavailableException()
        val resolved = try {
            withTimeout(timeoutMs) { provider.resolve(result) }
        } catch (e: TimeoutCancellationException) {
            throw ProviderUnavailableException()
        }
        if (resolved.magnet.isBlank()) throw ProviderUnavailableException()
        return resolved
    }

    private class Answer(val id: ProviderId, val results: List<TorrentResult>?, val reason: UnavailableReason?)
}
