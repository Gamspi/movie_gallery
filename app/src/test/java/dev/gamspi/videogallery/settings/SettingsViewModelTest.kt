package dev.gamspi.videogallery.settings

import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.stream.TorrentCacheStore
import java.nio.file.Files
import dev.gamspi.videogallery.translate.CacheKey
import dev.gamspi.videogallery.translate.FakeTranslationModels
import dev.gamspi.videogallery.translate.InMemoryTranslationDao
import dev.gamspi.videogallery.translate.ModelFailure
import dev.gamspi.videogallery.translate.ModelState
import dev.gamspi.videogallery.translate.TranslateLang
import dev.gamspi.videogallery.translate.TranslationCache
import dev.gamspi.videogallery.translate.message
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsViewModelTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun clearTorrentCache_reportsBusyThenSuccess() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val directory = Files.createTempDirectory("settings-torrent-cache").toFile()
        try {
            val cache = TorrentCacheStore(directory)
            val vm = SettingsViewModel(SettingsRepository(FakeSecretStore()), cache)
            vm.clearTorrentCache()
            assertEquals(true, vm.torrentCacheClear.value.busy)
            val result = vm.torrentCacheClear.first { !it.busy }
            assertEquals("Сохранённые торренты очищены. Активная раздача сохранена.", result.message)
        } finally {
            directory.deleteRecursively()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun clearTorrentCache_reportsFailureWithoutTechnicalDetails() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = SettingsViewModel(SettingsRepository(FakeSecretStore()))
            vm.clearTorrentCache()
            val result = vm.torrentCacheClear.first { !it.busy }
            assertEquals("Не удалось очистить сохранённые торренты. Попробуйте ещё раз.", result.message)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun translationCache() =
        TranslationCache(InMemoryTranslationDao(), tx = { it() }, dispatcher = Dispatchers.Unconfined)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun downloadModels_callsEnsureAndStatusFollowsModelState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val gate = CompletableDeferred<Unit>()
            val models = FakeTranslationModels(ModelState.NotDownloaded).apply {
                onEnsure = { gate.await(); state.value = ModelState.Ready }
            }
            val vm = SettingsViewModel(SettingsRepository(FakeSecretStore()), null, models, translationCache())
            runCurrent()
            assertEquals("Модели не скачаны", vm.modelStatus.value)
            vm.downloadModels()
            runCurrent()
            assertEquals(1, models.ensureCalls)
            assertEquals("Загрузка…", vm.modelStatus.value)
            // A second press while downloading does not start another download.
            vm.downloadModels()
            runCurrent()
            assertEquals(1, models.ensureCalls)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals("Модели готовы", vm.modelStatus.value)
            models.state.value = ModelState.Failed(ModelFailure.NoSpace)
            runCurrent()
            assertEquals(ModelFailure.NoSpace.message(), vm.modelStatus.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun deleteModels_deletesModelsAndClearsCache() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val models = FakeTranslationModels(ModelState.Ready)
            val cache = translationCache()
            val key = CacheKey(7, TranslateLang.RU, "v1")
            cache.put(key, mapOf(0 to "Привет"))
            val vm = SettingsViewModel(SettingsRepository(FakeSecretStore()), null, models, cache)
            vm.deleteModels()
            advanceUntilIdle()
            assertEquals(1, models.deleteCalls)
            assertEquals("Модели не скачаны", vm.modelStatus.value)
            assertEquals(emptyMap<Int, String>(), cache.get(key))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun torrentStorage_controlsPersistImmediatelyAndClamp() {
        val store = FakeSecretStore()
        val vm = SettingsViewModel(SettingsRepository(store))
        vm.toggleTorrentRetention()
        vm.changeTorrentLimit(-20)
        assertEquals(TorrentStoragePrefs(true, 1), SettingsRepository(store).torrentStoragePrefs.value)
        vm.changeTorrentLimit(Int.MAX_VALUE)
        assertEquals(100, vm.torrentStoragePrefs.value.limitGiB)
        vm.toggleTorrentRetention()
        assertEquals(false, SettingsRepository(store).torrentStoragePrefs.value.keepAfterPlayback)
    }

    @Test
    fun saveCatalogSourceAndKeys_persist() {
        val store = FakeSecretStore()
        val repo = SettingsRepository(store)
        val vm = SettingsViewModel(repo)
        assertEquals(true, vm.saveCatalogSource(CatalogSource.PoiskKino))
        assertEquals(true, vm.saveCatalogKeys(" pk-key ", "kp-key", "tm-key"))
        assertEquals(CatalogSource.PoiskKino, vm.catalogSource.value)
        assertEquals("pk-key", vm.poiskkinoApiKey.value)
        assertEquals("kp-key", vm.kinopoiskApiKey.value)
        assertEquals("tm-key", vm.tmdbApiKey.value)
        val reopened = SettingsRepository(store)
        assertEquals(ActiveCatalog(CatalogSource.PoiskKino, "pk-key"), reopened.activeCatalog.value)
        assertEquals("kp-key", reopened.kinopoiskApiKey.value)
        assertEquals("tm-key", reopened.tmdbApiKey.value)
    }

    @Test
    fun saveCatalogSourceDoesNotTouchKeys() {
        val store = FakeSecretStore(mapOf("poiskkino_api_key" to "pk", "tmdb_api_key" to "tm"))
        val vm = SettingsViewModel(SettingsRepository(store))
        assertEquals(true, vm.saveCatalogSource(CatalogSource.Tmdb))
        assertEquals("Tmdb", store.get("catalog_source"))
        assertEquals("pk", store.get("poiskkino_api_key"))
        assertEquals(ActiveCatalog(CatalogSource.Tmdb, "tm"), SettingsRepository(store).activeCatalog.value)
    }

    @Test
    fun saveCatalogKeysDoesNotTouchSourceOrOthers() {
        val store = FakeSecretStore(mapOf("tracker_Yts_url" to "https://old.example/", "opensubtitles_key" to "os"))
        val vm = SettingsViewModel(SettingsRepository(store))
        assertEquals(true, vm.saveCatalogKeys("pk", "kp", "tm"))
        assertEquals("pk", store.get("poiskkino_api_key"))
        assertEquals("tm", store.get("tmdb_api_key"))
        assertEquals(null, store.get("catalog_source"))
        assertEquals("https://old.example/", store.get("tracker_Yts_url"))
        assertEquals("os", store.get("opensubtitles_key"))
    }

    @Test
    fun saveTrackerEnabledKeepsUrls() {
        val store = FakeSecretStore(mapOf("tracker_Yts_url" to "https://old.example/"))
        val vm = SettingsViewModel(SettingsRepository(store))
        assertEquals(true, vm.saveTrackerEnabled(mapOf(ProviderId.Yts to true)))
        assertEquals(TrackerConfig(true, "https://old.example/"), vm.tracker(ProviderId.Yts).value)
        assertEquals("true", store.get("tracker_Yts_enabled"))
        assertEquals("https://old.example/", store.get("tracker_Yts_url"))
    }

    @Test
    fun saveTrackerUrlsKeepsEnabledFlags() {
        val store = FakeSecretStore(mapOf("poiskkino_api_key" to "pk", "tracker_Yts_enabled" to "true"))
        val vm = SettingsViewModel(SettingsRepository(store))
        assertEquals(true, vm.saveTrackerUrls(mapOf(ProviderId.Yts to "yts.example")))
        assertEquals(TrackerConfig(true, "https://yts.example/"), vm.tracker(ProviderId.Yts).value)
        assertEquals("https://yts.example/", store.get("tracker_Yts_url"))
        assertEquals("true", store.get("tracker_Yts_enabled"))
        assertEquals("pk", store.get("poiskkino_api_key"))
        assertEquals(null, store.get("catalog_source"))
    }

    @Test
    fun saveRutrackerPersists() {
        val store = FakeSecretStore()
        val vm = SettingsViewModel(SettingsRepository(store))
        assertEquals(true, vm.saveRutracker(" fake_user ", "fake_pass"))
        assertEquals("fake_user", store.get("rutracker_username"))
        assertEquals("fake_pass", store.get("rutracker_password"))
        assertEquals("fake_user", vm.rutrackerUser)
    }

    @Test
    fun saveOpenSubtitlesPersists() {
        val store = FakeSecretStore()
        val vm = SettingsViewModel(SettingsRepository(store))
        assertEquals(true, vm.saveOpenSubtitles(" k ", " u ", "p w"))
        assertEquals("k", store.get("opensubtitles_key"))
        assertEquals("u", store.get("opensubtitles_user"))
        assertEquals("p w", store.get("opensubtitles_password"))
        assertEquals(null, store.get("catalog_source"))
    }

    @Test
    fun networkSaveAndToggle() {
        val store = FakeSecretStore()
        val vm = SettingsViewModel(SettingsRepository(store))
        assertEquals(true, vm.toggleProxy())
        assertEquals(true, vm.proxyEnabled.value)
        assertEquals(true, vm.saveNetwork(" https://w.dev ", " k "))
        assertEquals("https://w.dev", store.get("proxy_url"))
        assertEquals("k", store.get("proxy_key"))
        assertEquals(true, vm.toggleProxy())
        assertEquals("false", store.get("proxy_enabled"))
    }

    @Test
    fun saveCatalogFailureReturnsFalse() {
        val store = object : SecretStore {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String?) = throw IllegalStateException("boom")
        }
        val vm = SettingsViewModel(SettingsRepository(store))
        assertEquals(false, vm.saveCatalogSource(CatalogSource.PoiskKino))
        assertEquals(false, vm.saveCatalogKeys("a", "b", "c"))
        assertEquals(false, vm.saveCatalogKey(KeyService.Tmdb, "a"))
        assertEquals(false, vm.saveTrackerEnabled(mapOf(ProviderId.Yts to true)))
        assertEquals(false, vm.saveTrackerUrls(mapOf(ProviderId.Yts to "x")))
        assertEquals(false, vm.saveRutracker("a", "b"))
        assertEquals(false, vm.saveOpenSubtitles("a", "b", "c"))
        assertEquals(false, vm.saveNetwork("a", "b"))
        assertEquals(false, vm.toggleProxy())
    }

    @Test
    fun saveCatalogKeyWritesOnlyThatKey() {
        val store = FakeSecretStore(
            mapOf("poiskkino_api_key" to "pk", "tmdb_api_key" to "tm", "opensubtitles_key" to "os", "proxy_key" to "px"),
        )
        val vm = SettingsViewModel(SettingsRepository(store))
        assertEquals(true, vm.saveCatalogKey(KeyService.Kinopoisk, " kp "))
        assertEquals("kp", store.get("kinopoisk_api_key"))
        assertEquals("kp", vm.kinopoiskApiKey.value)
        assertEquals("pk", store.get("poiskkino_api_key"))
        assertEquals("tm", store.get("tmdb_api_key"))
        assertEquals("os", store.get("opensubtitles_key"))
        assertEquals("px", store.get("proxy_key"))
        assertEquals(null, store.get("catalog_source"))
        assertEquals(true, vm.saveCatalogKey(KeyService.Tmdb, "tm2"))
        assertEquals("tm2", vm.tmdbApiKey.value)
        assertEquals("pk", vm.poiskkinoApiKey.value)
        assertEquals(true, vm.saveCatalogKey(KeyService.PoiskKino, ""))
        assertEquals(null, store.get("poiskkino_api_key"))
        assertEquals("kp", store.get("kinopoisk_api_key"))
        // Non-catalog services are rejected without writing.
        assertEquals(false, vm.saveCatalogKey(KeyService.Proxy, "x"))
        assertEquals("px", store.get("proxy_key"))
    }

    @Test
    fun saveSingleTrackerUrlKeepsOtherTrackers() {
        val store = FakeSecretStore(mapOf("tracker_Rutor_url" to "https://rutor.example/"))
        val vm = SettingsViewModel(SettingsRepository(store))
        assertEquals(true, vm.saveTrackerUrls(mapOf(ProviderId.Apibay to "bay.example")))
        assertEquals("https://bay.example/", store.get("tracker_Apibay_url"))
        assertEquals("https://rutor.example/", store.get("tracker_Rutor_url"))
        assertEquals(null, store.get("tracker_Yts_url"))
    }

    @Test
    fun isKeySetReflectsFilledValues() {
        val store = FakeSecretStore()
        val vm = SettingsViewModel(SettingsRepository(store))
        assertEquals(false, vm.isKeySet(KeyService.PoiskKino))
        assertEquals(false, vm.isKeySet(KeyService.Rutracker))
        assertEquals(false, vm.isKeySet(KeyService.OpenSubtitles))
        assertEquals(false, vm.isKeySet(KeyService.Proxy))
        // Tracker addresses fall back to defaults, so they always count as set.
        assertEquals(true, vm.isKeySet(KeyService.Rutor))
        vm.saveCatalogKey(KeyService.PoiskKino, "pk")
        assertEquals(true, vm.isKeySet(KeyService.PoiskKino))
        assertEquals(false, vm.isKeySet(KeyService.Tmdb))
        vm.saveRutracker("fake_user", "")
        assertEquals(true, vm.isKeySet(KeyService.Rutracker))
        vm.saveOpenSubtitles("k", "", "")
        assertEquals(true, vm.isKeySet(KeyService.OpenSubtitles))
        vm.saveNetwork("https://w.dev", "")
        assertEquals(false, vm.isKeySet(KeyService.Proxy))
        vm.saveNetwork("https://w.dev", "k")
        assertEquals(true, vm.isKeySet(KeyService.Proxy))
    }

    @Test
    fun cycleLanguage_advancesAndWraps() {
        val vm = SettingsViewModel(SettingsRepository(FakeSecretStore()))
        vm.cycleLanguage(SubLayer.A)
        assertEquals("ru", vm.subtitlePrefs.value.languageA)
        repeat(6) { vm.cycleLanguage(SubLayer.A) }
        assertEquals("en", vm.subtitlePrefs.value.languageA)
        assertEquals("ru", vm.subtitlePrefs.value.languageB)
    }

    @Test
    fun changeOffset_stepsLayerB() {
        val vm = SettingsViewModel(SettingsRepository(FakeSecretStore()))
        vm.changeOffset(SubLayer.B, 500)
        vm.changeOffset(SubLayer.B, 500)
        vm.changeOffset(SubLayer.B, -500)
        assertEquals(500L, vm.subtitlePrefs.value.styleB.offsetMs)
        assertEquals(0L, vm.subtitlePrefs.value.styleA.offsetMs)
    }

    @Test
    fun changeScale_stepsClampsAndPersists() {
        val store = FakeSecretStore()
        val vm = SettingsViewModel(SettingsRepository(store))
        vm.changeScale(SubLayer.A, 1)
        assertEquals(110, vm.subtitlePrefs.value.styleA.scalePercent)
        assertEquals(100, vm.subtitlePrefs.value.styleB.scalePercent)
        assertEquals(110, SettingsRepository(store).subtitlePrefs.value.styleA.scalePercent)
        repeat(20) { vm.changeScale(SubLayer.B, -1) }
        assertEquals(50, vm.subtitlePrefs.value.styleB.scalePercent)
        repeat(30) { vm.changeScale(SubLayer.B, 1) }
        assertEquals(200, vm.subtitlePrefs.value.styleB.scalePercent)
    }

    @Test
    fun changeOffset_clampsToTenSeconds() {
        val vm = SettingsViewModel(SettingsRepository(FakeSecretStore()))
        repeat(30) { vm.changeOffset(SubLayer.A, 500) }
        assertEquals(10_000L, vm.subtitlePrefs.value.styleA.offsetMs)
        repeat(50) { vm.changeOffset(SubLayer.A, -500) }
        assertEquals(-10_000L, vm.subtitlePrefs.value.styleA.offsetMs)
    }
}
