package dev.gamspi.videogallery.catalog

import dev.gamspi.videogallery.settings.CatalogSource
import dev.gamspi.videogallery.settings.FakeSecretStore
import dev.gamspi.videogallery.settings.SettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SwitchingCatalogRepositoryTest {
    @Test
    fun delegatesBySource() = runTest {
        val settings = SettingsRepository(FakeSecretStore())
        val tmdb = FakeCatalogRepository()
        val kp = FakeCatalogRepository()
        val poiskkino = FakeCatalogRepository()
        val repo = SwitchingCatalogRepository(settings, tmdb, kp, poiskkino)

        settings.setCatalogSource(CatalogSource.Kinopoisk)
        repo.popular()
        assertEquals(1, kp.popularCalls)
        assertEquals(0, tmdb.popularCalls)

        settings.setCatalogSource(CatalogSource.Tmdb)
        repo.search("q")
        assertEquals(listOf("q"), tmdb.searchCalls)
        assertEquals(emptyList<String>(), kp.searchCalls)

        settings.setCatalogSource(CatalogSource.PoiskKino)
        repo.genres()
        assertEquals(1, poiskkino.genresCalls)
    }
}
