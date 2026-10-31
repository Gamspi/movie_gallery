package dev.gamspi.videogallery.settings

import dev.gamspi.videogallery.torrents.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsViewModelTest {
    @Test
    fun save_persistsSourceAndBothKeys() {
        val repo = SettingsRepository(FakeSecretStore())
        val vm = SettingsViewModel(repo)
        vm.save(CatalogSource.Tmdb, "kp-key", "tm-key", emptyMap())
        assertEquals(CatalogSource.Tmdb, vm.catalogSource.value)
        assertEquals("kp-key", vm.kinopoiskApiKey.value)
        assertEquals("tm-key", vm.tmdbApiKey.value)
    }

    @Test
    fun save_persistsTrackers() {
        val store = FakeSecretStore()
        val vm = SettingsViewModel(SettingsRepository(store))
        vm.save(
            CatalogSource.Kinopoisk, "", "",
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
