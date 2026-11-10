package dev.gamspi.videogallery.catalog

import dev.gamspi.videogallery.catalog.model.MediaKind
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

        repo.popular(2, MediaKind.Series)
        repo.season(7, 3)
        assertEquals(MediaKind.Series, poiskkino.kindCalls.last())
        assertEquals(listOf(7 to 3), poiskkino.seasonCalls)
        val today = java.time.LocalDate.of(2026, 11, 10)
        repo.upcoming(today, MediaKind.Series)
        assertEquals(listOf(MediaKind.Series), poiskkino.upcomingCalls)
        settings.setCatalogSource(CatalogSource.Tmdb)
        repo.upcoming(today)
        assertEquals(listOf<MediaKind?>(null), tmdb.upcomingCalls)
        repo.forSource(CatalogSource.Kinopoisk).upcoming(today, MediaKind.Movie)
        assertEquals(listOf(MediaKind.Movie), kp.upcomingCalls)
    }
}
