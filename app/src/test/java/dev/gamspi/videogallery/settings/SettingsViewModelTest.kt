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
    fun saveCatalog_persistsSourceAndThreeKeys() {
        val store = FakeSecretStore()
        val repo = SettingsRepository(store)
        val vm = SettingsViewModel(repo)
        assertEquals(true, vm.saveCatalog(CatalogSource.PoiskKino, " pk-key ", "kp-key", "tm-key"))
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
    fun saveCatalogPersistsOnlyCatalog() {
        val store = FakeSecretStore(mapOf("tracker_Yts_url" to "https://old.example/", "opensubtitles_key" to "os"))
        val vm = SettingsViewModel(SettingsRepository(store))
        assertEquals(true, vm.saveCatalog(CatalogSource.Tmdb, "pk", "kp", "tm"))
        assertEquals("pk", store.get("poiskkino_api_key"))
        assertEquals("tm", store.get("tmdb_api_key"))
        assertEquals("Tmdb", store.get("catalog_source"))
        assertEquals("https://old.example/", store.get("tracker_Yts_url"))
        assertEquals("os", store.get("opensubtitles_key"))
    }

    @Test
    fun saveTrackersPersistsOnlyTrackers() {
        val store = FakeSecretStore(mapOf("poiskkino_api_key" to "pk"))
        val vm = SettingsViewModel(SettingsRepository(store))
        assertEquals(true, vm.saveTrackers(mapOf(ProviderId.Yts to TrackerConfig(true, "yts.example"))))
        assertEquals(TrackerConfig(true, "https://yts.example/"), vm.tracker(ProviderId.Yts).value)
        assertEquals("https://yts.example/", store.get("tracker_Yts_url"))
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
        assertEquals(false, vm.saveCatalog(CatalogSource.PoiskKino, "a", "b", "c"))
        assertEquals(false, vm.saveTrackers(mapOf(ProviderId.Yts to TrackerConfig(true, "x"))))
        assertEquals(false, vm.saveOpenSubtitles("a", "b", "c"))
        assertEquals(false, vm.saveNetwork("a", "b"))
        assertEquals(false, vm.toggleProxy())
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
