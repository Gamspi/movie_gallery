package dev.gamspi.videogallery.gallery

import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.CatalogRepository
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.settings.CatalogSource
import dev.gamspi.videogallery.watchlist.ListRecord
import dev.gamspi.videogallery.watchlist.ListTable
import dev.gamspi.videogallery.watchlist.isSeriesRecord
import dev.gamspi.videogallery.watchlist.text
import java.util.Locale
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class RecommendationKey(val kind: MediaKind, val id: Int)
internal fun Movie.recommendationKey() = RecommendationKey(kind, id)
internal fun ListRecord.mediaKind() = if (isSeriesRecord(this)) MediaKind.Series else MediaKind.Movie

internal const val FOR_YOU_SEEDS = 10
internal const val HOME_SEEDS = 2
private const val SCARCE_SEEDS = 3
private const val HIGH_RATING = 8

/** Live titles of the list, newest edit first. Deleted markers and episode/position rows are not titles. */
internal fun recommendationTitles(records: Collection<ListRecord>): List<ListRecord> = records
    .filter { it.table == ListTable.Titles && !it.deleted }
    .sortedWith(compareByDescending<ListRecord> { it.updatedAt }.thenBy { it.titleKey })

// my_rating already holds the rounded season average maintained by the list (withSeasonRating).
private fun ListRecord.highlyRated() = (text("my_rating").toDoubleOrNull() ?: 0.0) >= HIGH_RATING

/**
 * «Для вас» sources: up to [FOR_YOU_SEEDS] titles rated 8+. With fewer than three, unrated
 * watched/watching titles fill up to three; titles rated below 8 are never sources.
 */
internal fun forYouSeeds(records: Collection<ListRecord>): List<ListRecord> {
    val titles = recommendationTitles(records)
    val rated = titles.filter { it.highlyRated() }.take(FOR_YOU_SEEDS)
    if (rated.size >= SCARCE_SEEDS) return rated
    return rated + titles.filter {
        it.text("my_rating").isBlank() && it.text("status") in setOf("watched", "watching")
    }.take(SCARCE_SEEDS - rated.size)
}

/** «Похожие на „X“» sources: the two latest titles rated 8+, else the two latest watched. */
internal fun homeRecommendationSeeds(records: Collection<ListRecord>): List<ListRecord> {
    val titles = recommendationTitles(records)
    return titles.filter { it.highlyRated() }.ifEmpty { titles.filter { it.text("status") == "watched" } }.take(HOME_SEEDS)
}

/** Display name of a list title for the row header. */
internal fun recommendationSeedName(record: ListRecord): String =
    record.text("title_ru").ifBlank { record.text("title_en") }.ifBlank { "Без названия" }

/** The catalog id stored with the title when it was added from [source]. */
internal fun directRecommendationKey(record: ListRecord, source: CatalogSource): RecommendationKey? =
    record.text("catalog_id").toIntOrNull()?.takeIf { it > 0 && record.text("catalog_source") == source.name }
        ?.let { RecommendationKey(record.mediaKind(), it) }

internal fun recommendationImdb(record: ListRecord): String? =
    record.text("imdb_id").ifBlank { record.titleKey }.takeIf { Regex("tt\\d{5,10}").matches(it) }

private fun normalizedTitle(title: String): String =
    title.lowercase(Locale.ROOT).replace('ё', 'е').filter { it.isLetterOrDigit() }

/**
 * Offline exclusion for a foreign title whose match is not known yet: same type, same normalized
 * title and, when both sides know it, the same year.
 */
internal fun sameTitle(record: ListRecord, movie: Movie): Boolean {
    if (record.mediaKind() != movie.kind) return false
    val year = record.text("year").toIntOrNull()
    if (year != null && movie.year != null && year != movie.year) return false
    val names = listOf(record.text("title_ru"), record.text("title_en")).map(::normalizedTitle).filter { it.isNotEmpty() }
    return listOf(movie.title, movie.originalTitle).map(::normalizedTitle).any { it.isNotEmpty() && it in names }
}

/**
 * Merges per-source candidate lists. Each source casts at most one vote for a candidate (type + id),
 * even if its API repeats it. Order: more sources first, then higher catalog rating.
 */
internal fun mergeRecommendations(groups: List<List<Movie>>, excluded: Set<RecommendationKey>): List<Movie> {
    val candidates = groups.flatMap { group -> group.filter { it.id > 0 }
        .groupBy { it.recommendationKey() }.values.map { copies -> copies.maxBy { it.rating ?: 0.0 } } }
        .filter { it.recommendationKey() !in excluded }.groupBy { it.recommendationKey() }
    return candidates.values.sortedWith(compareByDescending<List<Movie>> { it.size }
        .thenByDescending { copies -> copies.maxOf { it.rating ?: 0.0 } }
        .thenBy { it.first().kind.ordinal }.thenBy { it.first().id })
        .map { copies -> copies.maxBy { it.rating ?: 0.0 } }
}

internal data class RecommendationBatch(val seed: ListRecord, val movies: List<Movie>)

private const val FOR_YOU_ROW = 20
private const val FOR_YOU_LIMIT = 60

/** «Для вас» rows: candidates merged across sources, best first, in rows of [FOR_YOU_ROW] cards. */
internal fun forYouRows(batches: List<RecommendationBatch>): List<MovieRow> =
    mergeRecommendations(batches.map { it.movies }, emptySet()).take(FOR_YOU_LIMIT).chunked(FOR_YOU_ROW)
        .mapIndexed { i, movies -> MovieRow(if (i == 0) "Подобрано по вашим оценкам" else "Ещё для вас", movies, "for-you-$i") }

/** «Похожие на» rows go after «Популярное» and «Скоро», before the genre rows. */
internal fun withRelatedRows(rows: List<MovieRow>, related: List<MovieRow>): List<MovieRow> {
    val at = if (rows.getOrNull(1)?.title == "Скоро") 2 else 1
    return rows.take(at) + related + rows.drop(at)
}

/**
 * Loads similar titles for list sources in one catalog. Owned by one catalog/credential epoch;
 * caches hold only public ids and catalog cards. Catalog responses are also kept by the HTTP cache.
 */
internal class RecommendationLoader(
    private val catalog: CatalogRepository,
    private val source: CatalogSource,
    private val matches: ImdbMatchStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Cached<T>(val time: Long, val value: T)
    private sealed interface Match {
        data class Known(val key: RecommendationKey?) : Match
        data object Unknown : Match
    }

    private val mappings = ConcurrentHashMap<Pair<String, MediaKind>, Cached<RecommendationKey?>>()
    private val similar = ConcurrentHashMap<RecommendationKey, Cached<List<Movie>>>()
    private val permits = Semaphore(PARALLEL)

    private suspend fun cached(record: ListRecord): Match {
        directRecommendationKey(record, source)?.let { return Match.Known(it) }
        // Own catalog without a usable id, or a foreign title without IMDb: nothing to look up.
        if (record.text("catalog_source") == source.name) return Match.Known(null)
        val imdb = recommendationImdb(record) ?: return Match.Known(null)
        val key = imdb to record.mediaKind()
        mappings[key]?.takeIf { now() - it.time < DAY_MS }?.let { return Match.Known(it.value) }
        matches?.get(source, imdb, key.second)?.let {
            mappings[key] = Cached(now(), it.key)
            return Match.Known(it.key)
        }
        return Match.Unknown
    }

    /** Throws [CatalogException]; the result (also a miss) is cached in memory and on disk. */
    private suspend fun lookup(record: ListRecord): RecommendationKey? {
        val imdb = recommendationImdb(record) ?: return null
        val kind = record.mediaKind()
        val result = catalog.findByImdb(imdb, kind)?.recommendationKey()
        mappings[imdb to kind] = Cached(now(), result)
        matches?.put(source, imdb, kind, result)
        return result
    }

    suspend fun load(records: Collection<ListRecord>, seeds: List<ListRecord>): List<RecommendationBatch> = coroutineScope {
        if (seeds.isEmpty()) return@coroutineScope emptyList()
        val failures: MutableList<CatalogException> = Collections.synchronizedList(mutableListOf())
        val seedIds = seeds.map { it.identity }.toSet()
        // Sources may use the network: there are at most FOR_YOU_SEEDS of them.
        val seedKeys = seeds.map { seed -> async {
            permits.withPermit {
                seed to when (val match = cached(seed)) {
                    is Match.Known -> match.key
                    Match.Unknown -> try { lookup(seed) } catch (e: CatalogException) { failures += e; null }
                }
            }
        } }.awaitAll()
        // Every other live title is excluded too. Known matches cost nothing; a bounded number of
        // unknown ones is looked up per load, the rest are excluded by title until they are matched.
        val excluded = seedKeys.mapNotNull { it.second }.toMutableSet()
        val unknown = mutableListOf<ListRecord>()
        for (record in recommendationTitles(records)) {
            if (record.identity in seedIds) continue
            when (val match = cached(record)) {
                is Match.Known -> match.key?.let(excluded::add)
                Match.Unknown -> unknown += record
            }
        }
        val looked = unknown.take(EXCLUSION_LOOKUPS).map { record -> async {
            permits.withPermit {
                record to try { Match.Known(lookup(record)) } catch (_: CatalogException) { Match.Unknown }
            }
        } }.awaitAll().toMap()
        looked.values.forEach { (it as? Match.Known)?.key?.let(excluded::add) }
        val byTitle = unknown.filter { looked[it] !is Match.Known }

        val unique = seedKeys.mapNotNull { (seed, key) -> key?.let { seed to it } }.distinctBy { it.second }
        val answered = AtomicBoolean(false)
        val batches = unique.map { (seed, key) -> async {
            permits.withPermit {
                try {
                    val movies = similar[key]?.takeIf { now() - it.time < HOUR_MS }?.value
                        ?: catalog.recommendations(key.id, key.kind).also { similar[key] = Cached(now(), it) }
                    answered.set(true)
                    val fresh = movies.filter { movie -> byTitle.none { sameTitle(it, movie) } }
                    RecommendationBatch(seed, mergeRecommendations(listOf(fresh), excluded))
                } catch (e: CatalogException) {
                    failures += e
                    RecommendationBatch(seed, emptyList())
                }
            }
        } }.awaitAll()
        // Nothing answered at all (no key, quota, network): report it instead of an empty screen.
        if (!answered.get()) failures.firstOrNull()?.let { throw it }
        batches
    }

    private companion object {
        const val PARALLEL = 3
        const val EXCLUSION_LOOKUPS = 20
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
    }
}
