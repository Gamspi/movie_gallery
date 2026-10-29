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
}
