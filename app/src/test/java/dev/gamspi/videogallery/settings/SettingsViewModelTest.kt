package dev.gamspi.videogallery.settings

import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.stream.TorrentCacheStore
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
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
    fun save_persistsSourceAndThreeKeys() {
        val store = FakeSecretStore()
        val repo = SettingsRepository(store)
        val vm = SettingsViewModel(repo)
        vm.save(CatalogSource.PoiskKino, " pk-key ", "kp-key", "tm-key")
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
    fun save_persistsTrackers() {
        val store = FakeSecretStore()
        val vm = SettingsViewModel(SettingsRepository(store))
        vm.save(
            CatalogSource.Kinopoisk, "", "", "",
            mapOf(ProviderId.Yts to TrackerConfig(true, "yts.example")),
        )
        assertEquals(TrackerConfig(true, "https://yts.example/"), vm.tracker(ProviderId.Yts).value)
        assertEquals("https://yts.example/", store.get("tracker_Yts_url"))
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
    fun changeOffset_clampsToTenSeconds() {
        val vm = SettingsViewModel(SettingsRepository(FakeSecretStore()))
        repeat(30) { vm.changeOffset(SubLayer.A, 500) }
        assertEquals(10_000L, vm.subtitlePrefs.value.styleA.offsetMs)
        repeat(50) { vm.changeOffset(SubLayer.A, -500) }
        assertEquals(-10_000L, vm.subtitlePrefs.value.styleA.offsetMs)
    }
}
