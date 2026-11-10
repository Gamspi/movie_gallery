package dev.gamspi.videogallery.gallery

import dev.gamspi.videogallery.catalog.CatalogException
import dev.gamspi.videogallery.catalog.FakeCatalogRepository
import dev.gamspi.videogallery.catalog.model.MediaKind
import dev.gamspi.videogallery.catalog.model.Movie
import dev.gamspi.videogallery.settings.CatalogSource
import dev.gamspi.videogallery.watchlist.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun recommendationRecord(id: Int, rating: Int? = 8, status: String = "watched",
    source: String = "Tmdb", imdb: String? = null, series: Boolean = false, time: Long = id.toLong()): ListRecord =
    ListRecord(ListTable.Titles, imdb ?: "tmdb-${if (series) "tv" else "movie"}-$id", time, data = buildJsonObject {
        put("catalog_source", source); put("catalog_id", id); put("status", status)
        put("type", if (series) "tv_show" else "movie"); put("title_ru", "Тайтл $id")
        rating?.let { put("my_rating", it) }; imdb?.let { put("imdb_id", it) }
    })

@OptIn(ExperimentalCoroutinesApi::class)
class RecommendationsTest {
    private fun movie(id: Int, rating: Double = 7.0, kind: MediaKind = MediaKind.Movie) =
        Movie(id, "M$id", "", 2020, null, "", rating, kind)

    @Test fun sourcesUseRecentHighRatingsAndAtMostTen() {
        val records = (1..15).map { recommendationRecord(it) } + recommendationRecord(20, 7)
        assertEquals((15 downTo 6).toList(), forYouSeeds(records).map { it.text("catalog_id").toInt() })
        assertEquals(listOf("15", "14"), homeRecommendationSeeds(records).map { it.text("catalog_id") })
    }

    @Test fun scarceRatingsFillOnlyToThreeWithUnratedWatchingOrWatched() {
        val high = recommendationRecord(1)
        val watching = recommendationRecord(2, null, "watching")
        val watched = recommendationRecord(3, null)
        val rejected = listOf(recommendationRecord(4, 7), recommendationRecord(5, null, "unwatched"),
            recommendationRecord(6, null, "dropped"), recommendationRecord(7, null, "dismissed"))
        assertEquals(listOf(high, watched, watching), forYouSeeds(rejected + listOf(high, watching, watched)))
        assertEquals(listOf(high), homeRecommendationSeeds(rejected + listOf(high, watching, watched)))
    }

    @Test fun noHighRatingsHomeUsesLastWatchedEvenWithLowRating() {
        val low = recommendationRecord(1, 3)
        val unrated = recommendationRecord(2, null)
        val watching = recommendationRecord(3, null, "watching")
        assertEquals(listOf(unrated, low), homeRecommendationSeeds(listOf(low, watching, unrated)))
        assertEquals(listOf(watching, unrated), forYouSeeds(listOf(low, watching, unrated)))
        assertTrue(forYouSeeds(listOf(low)).isEmpty())
        assertTrue(homeRecommendationSeeds(emptyList()).isEmpty())
    }

    @Test fun seriesUsesStoredRoundedSeasonAverage() {
        val series = recommendationRecord(1, null, series = true)
        val rated = series.copy(data = withSeasonRating(withSeasonRating(series.data, 1, 7, false), 2, 9, false))
        assertEquals(listOf(rated), forYouSeeds(listOf(rated)))
        assertTrue(forYouSeeds(listOf(rated.copy(data = withSeasonRating(rated.data, 2, 5, false)))).isEmpty())
    }

    @Test fun deletedAndNonTitlesAreNeverSourcesOrExclusions() {
        val row = recommendationRecord(1)
        assertTrue(recommendationTitles(listOf(row.copy(deleted = true), row.copy(table = ListTable.Episodes))).isEmpty())
    }

    @Test fun mergeCountsDistinctSourcesBeforeRatingAndKeepsTypeIdentity() {
        val shared = movie(1, 6.0)
        val high = movie(2, 9.0)
        val tv = movie(1, 8.0, MediaKind.Series)
        val groups = listOf(listOf(shared, shared, high, tv), listOf(shared, movie(3, 8.5)))
        assertEquals(listOf(shared, high, movie(3, 8.5), tv), mergeRecommendations(groups, emptySet()))
        assertEquals(listOf(high, movie(3, 8.5), tv), mergeRecommendations(groups, setOf(shared.recommendationKey())))
    }

    @Test fun repeatedCandidateWithinOneSourceDoesNotOutrankAnotherSource() {
        val repeated = movie(1, 5.0)
        assertEquals(listOf(movie(2, 9.0), repeated), mergeRecommendations(listOf(listOf(repeated, repeated), listOf(movie(2, 9.0))), emptySet()))
    }

    @Test fun directKeysRequireMatchingCatalogAndPositiveId() {
        assertEquals(RecommendationKey(MediaKind.Series, 2), directRecommendationKey(recommendationRecord(2, series = true), CatalogSource.Tmdb))
        assertNull(directRecommendationKey(recommendationRecord(2), CatalogSource.PoiskKino))
        assertNull(directRecommendationKey(recommendationRecord(-1), CatalogSource.Tmdb))
        assertNull(recommendationImdb(recommendationRecord(2, imdb = "bad")))
    }

    @Test fun resolvesForeignSeedsAndExclusionsCachesMissesAndSkipsForeignWithoutImdb() = runTest {
        val repo = FakeCatalogRepository()
        val source = recommendationRecord(1)
        val foreign = recommendationRecord(2, source = "PoiskKino", imdb = "tt00002")
        val dismissed = recommendationRecord(3, null, "dismissed", "PoiskKino", "tt00003")
        val dropped = recommendationRecord(4, null, "dropped")
        val noImdb = recommendationRecord(5, source = "PoiskKino")
        val missing = recommendationRecord(6, source = "PoiskKino", imdb = "tt00006")
        val removed = recommendationRecord(7).copy(deleted = true)
        repo.findByImdbResult = { imdb, _ -> when (imdb) { "tt00002" -> movie(22); "tt00003" -> movie(33); else -> null } }
        repo.recommendationsResult = { _, _ -> listOf(movie(1), movie(22), movie(33), movie(4), movie(7), movie(99)) }
        val loader = RecommendationLoader(repo, CatalogSource.Tmdb)
        val records = listOf(source, foreign, dismissed, dropped, noImdb, missing, removed)
        repeat(2) {
            val batches = loader.load(records, forYouSeeds(records))
            assertEquals(2, batches.size)
            assertTrue(batches.all { it.movies.map { m -> m.id } == listOf(7, 99) })
        }
        assertEquals(setOf("tt00002", "tt00003", "tt00006"), repo.imdbCalls.map { it.first }.toSet())
        assertEquals(3, repo.imdbCalls.size)
        assertEquals(listOf(1, 22), repo.recommendationCalls.map { it.first }.sorted())
    }

    @Test fun listEditRefiltersCachedCandidatesAndDeletedMarkerRestoresThem() = runTest {
        val repo = FakeCatalogRepository().apply { recommendationsResult = { _, _ -> listOf(movie(2)) } }
        val loader = RecommendationLoader(repo, CatalogSource.Tmdb)
        val seed = recommendationRecord(1)
        val hidden = recommendationRecord(2, null, "dismissed")
        assertEquals(1, loader.load(listOf(seed), listOf(seed)).single().movies.size)
        assertTrue(loader.load(listOf(seed, hidden), listOf(seed)).single().movies.isEmpty())
        assertEquals(1, loader.load(listOf(seed, hidden.copy(deleted = true)), listOf(seed)).single().movies.size)
        assertEquals(1, repo.recommendationCalls.size)
    }

    @Test fun failedRowIsSkippedButCancellationPropagates() = runTest {
        val repo = FakeCatalogRepository().apply { recommendationsResult = { id, _ ->
            if (id == 1) throw CatalogException.Network else listOf(movie(9))
        } }
        val records = listOf(recommendationRecord(1), recommendationRecord(2))
        val loader = RecommendationLoader(repo, CatalogSource.Tmdb)
        assertEquals(listOf(0, 1), loader.load(records, records).map { it.movies.size })
        repo.recommendationsResult = { _, _ -> throw CancellationException() }
        try { RecommendationLoader(repo, CatalogSource.Tmdb).load(records, records); fail("Cancellation expected") }
        catch (_: CancellationException) { }
    }

    @Test fun failedExclusionLookupFallsBackToTitleAndYear() = runTest {
        val repo = FakeCatalogRepository().apply {
            findByImdbResult = { _, _ -> throw CatalogException.Network }
            recommendationsResult = { _, _ -> listOf(movie(22).copy(title = "Тайтл 2", year = 2001),
                movie(23).copy(title = "Тайтл 2", year = 1990), movie(24)) }
        }
        val listed = recommendationRecord(2, null, "dismissed", "PoiskKino", "tt00002")
        val records = listOf(recommendationRecord(1), listed.copy(data = JsonObject(listed.data + ("year" to JsonPrimitive(2001)))))
        val batch = RecommendationLoader(repo, CatalogSource.Tmdb).load(records, listOf(records.first())).single()
        assertEquals(listOf(23, 24), batch.movies.map { it.id }.sorted())
        assertEquals(1, repo.recommendationCalls.size)
    }

    @Test fun titleMatchNeedsSameKindAndKnownYearsToAgree() {
        val record = recommendationRecord(1).let { it.copy(data = JsonObject(it.data + ("year" to JsonPrimitive(2010)))) }
        assertTrue(sameTitle(record, movie(5).copy(title = "тайтл  1!", year = 2010)))
        assertTrue(sameTitle(record, movie(5).copy(title = "Other", originalTitle = "Тайтл 1", year = null)))
        assertFalse(sameTitle(record, movie(5).copy(title = "Тайтл 1", year = 2011)))
        assertFalse(sameTitle(record, movie(5, kind = MediaKind.Series).copy(title = "Тайтл 1", year = 2010)))
        assertFalse(sameTitle(record, movie(5).copy(title = "Тайтл 10", year = 2010)))
    }

    @Test fun unknownExclusionsAreLookedUpInBoundedBatchesAcrossLoads() = runTest {
        val repo = FakeCatalogRepository().apply { recommendationsResult = { _, _ -> listOf(movie(99)) } }
        val seed = recommendationRecord(1, time = 1000)
        val foreign = (1..25).map { recommendationRecord(100 + it, null, "unwatched", "PoiskKino", "tt%05d".format(it)) }
        val loader = RecommendationLoader(repo, CatalogSource.Tmdb)
        val records = listOf(seed) + foreign
        loader.load(records, listOf(seed))
        assertEquals(20, repo.imdbCalls.size)
        loader.load(records, listOf(seed))
        assertEquals(25, repo.imdbCalls.size)
        loader.load(records, listOf(seed))
        assertEquals(25, repo.imdbCalls.size)
        assertEquals(25, repo.imdbCalls.map { it.first }.toSet().size)
    }

    @Test fun diskMatchesAreSharedByLoadersOfTheSameCatalogOnly() = runTest {
        var text: String? = null
        val store = ImdbMatchStore({ text }, { text = it }, StandardTestDispatcher(testScheduler))
        val repo = FakeCatalogRepository().apply {
            findByImdbResult = { imdb, _ -> if (imdb == "tt00002") movie(22) else null }
            recommendationsResult = { _, _ -> listOf(movie(9)) }
        }
        val records = listOf(recommendationRecord(2, source = "PoiskKino", imdb = "tt00002"),
            recommendationRecord(3, null, "dismissed", "PoiskKino", "tt00003"))
        RecommendationLoader(repo, CatalogSource.Tmdb, store).load(records, records.take(1))
        assertEquals(2, repo.imdbCalls.size)
        val fresh = ImdbMatchStore({ text }, { text = it }, StandardTestDispatcher(testScheduler))
        RecommendationLoader(repo, CatalogSource.Tmdb, fresh).load(records, records.take(1))
        assertEquals(2, repo.imdbCalls.size)
        assertEquals(listOf(22, 22), repo.recommendationCalls.map { it.first })
        RecommendationLoader(repo, CatalogSource.Kinopoisk, fresh).load(records, records.take(1))
        assertEquals(4, repo.imdbCalls.size)
        assertFalse(text!!.contains("KEY"))
    }

    @Test fun storeExpiresMissesSoonerThanHitsAndSurvivesDamagedFile() = runTest {
        var now = 0L
        var text: String? = "not json"
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = ImdbMatchStore({ text }, { text = it }, dispatcher) { now }
        assertNull(store.get(CatalogSource.Tmdb, "tt00001", MediaKind.Movie))
        store.put(CatalogSource.Tmdb, "tt00001", MediaKind.Movie, RecommendationKey(MediaKind.Series, 5))
        store.put(CatalogSource.Tmdb, "tt00002", MediaKind.Movie, null)
        assertEquals(ImdbMatch(RecommendationKey(MediaKind.Series, 5)), store.get(CatalogSource.Tmdb, "tt00001", MediaKind.Movie))
        assertEquals(ImdbMatch(null), store.get(CatalogSource.Tmdb, "tt00002", MediaKind.Movie))
        assertNull(store.get(CatalogSource.Tmdb, "tt00001", MediaKind.Series))
        now = 8L * 86_400_000
        val reloaded = ImdbMatchStore({ text }, { text = it }, dispatcher) { now }
        assertNull(reloaded.get(CatalogSource.Tmdb, "tt00002", MediaKind.Movie))
        assertNotNull(reloaded.get(CatalogSource.Tmdb, "tt00001", MediaKind.Movie))
    }

    @Test fun everySourceFailingReportsTheError() = runTest {
        val repo = FakeCatalogRepository().apply { recommendationsResult = { _, _ -> throw CatalogException.Unauthorized } }
        val records = listOf(recommendationRecord(1), recommendationRecord(2))
        try { RecommendationLoader(repo, CatalogSource.Tmdb).load(records, records); fail("Failure expected") }
        catch (e: CatalogException) { assertEquals(CatalogException.Unauthorized, e) }
        // Sources that cannot be matched at all are an empty result, not an error.
        val foreign = listOf(recommendationRecord(3, source = "PoiskKino"))
        assertTrue(RecommendationLoader(repo, CatalogSource.Tmdb).load(foreign, foreign).isEmpty())
    }

    @Test fun relatedRowsGoAfterPopularAndUpcomingBeforeGenres() {
        val popular = MovieRow("Популярное", listOf(movie(1)))
        val upcoming = MovieRow("Скоро", listOf(movie(2)))
        val genre = MovieRow("Драма", listOf(movie(3)))
        val related = listOf(MovieRow("Похожие на „A“", listOf(movie(4)), "similar:a"),
            MovieRow("Похожие на „B“", listOf(movie(5)), "similar:b"))
        assertEquals(listOf(popular, upcoming) + related + genre, withRelatedRows(listOf(popular, upcoming, genre), related))
        assertEquals(listOf(popular) + related + genre, withRelatedRows(listOf(popular, genre), related))
        assertEquals(listOf(popular, genre), withRelatedRows(listOf(popular, genre), emptyList()))
    }

    @Test fun forYouRowsMergeSourcesAndSplitIntoRows() {
        val seed = recommendationRecord(1)
        val shared = movie(1, 5.0)
        val batches = listOf(RecommendationBatch(seed, (2..70).map { movie(it) } + shared), RecommendationBatch(seed, listOf(shared)))
        val rows = forYouRows(batches)
        assertEquals(listOf("for-you-0", "for-you-1", "for-you-2"), rows.map { it.key })
        assertEquals(listOf(20, 20, 20), rows.map { it.movies.size })
        assertEquals(shared, rows.first().movies.first())
        assertEquals("Подобрано по вашим оценкам", rows.first().title)
        assertTrue(forYouRows(emptyList()).isEmpty())
    }

    @Test fun seedNameFallsBackToEnglishAndPlaceholder() {
        val record = recommendationRecord(1)
        assertEquals("Тайтл 1", recommendationSeedName(record))
        val noRu = record.copy(data = JsonObject(record.data - "title_ru" + ("title_en" to JsonPrimitive("Title"))))
        assertEquals("Title", recommendationSeedName(noRu))
        assertEquals("Без названия", recommendationSeedName(record.copy(data = JsonObject(record.data - "title_ru"))))
    }

    @Test fun parallelismIsBoundedAndCancellingLoadStopsPendingWork() = runTest {
        var running = 0
        var peak = 0
        val repo = FakeCatalogRepository().apply { recommendationsResult = { _, _ ->
            running++; peak = maxOf(peak, running)
            try { awaitCancellation() } finally { running-- }
        } }
        val records = (1..10).map { recommendationRecord(it) }
        val task = launch { RecommendationLoader(repo, CatalogSource.Tmdb).load(records, records) }
        runCurrent()
        assertEquals(3, peak)
        task.cancelAndJoin()
        assertEquals(0, running)
        assertEquals(3, repo.recommendationCalls.size)
    }

    @Test fun expiredCacheReloadsAndDuplicateResolvedSeedsCastOnlyOneVote() = runTest {
        var now = 0L
        val repo = FakeCatalogRepository().apply {
            recommendationsResult = { _, _ -> listOf(movie(9)) }
            findByImdbResult = { _, _ -> movie(1) }
        }
        val records = listOf(recommendationRecord(1), recommendationRecord(2, source = "PoiskKino", imdb = "tt00002"))
        val loader = RecommendationLoader(repo, CatalogSource.Tmdb) { now }
        assertEquals(1, loader.load(records, records).size)
        now = 3_600_001
        loader.load(records, records)
        assertEquals(2, repo.recommendationCalls.size)
        assertEquals(1, repo.imdbCalls.size)
    }
}
