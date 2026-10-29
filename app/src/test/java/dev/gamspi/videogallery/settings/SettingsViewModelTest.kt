package dev.gamspi.videogallery.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsViewModelTest {
    @Test
    fun save_persistsSourceAndBothKeys() {
        val repo = SettingsRepository(FakeSecretStore())
        val vm = SettingsViewModel(repo)
        vm.save(CatalogSource.Tmdb, "kp-key", "tm-key")
        assertEquals(CatalogSource.Tmdb, vm.catalogSource.value)
        assertEquals("kp-key", vm.kinopoiskApiKey.value)
        assertEquals("tm-key", vm.tmdbApiKey.value)
    }
}
