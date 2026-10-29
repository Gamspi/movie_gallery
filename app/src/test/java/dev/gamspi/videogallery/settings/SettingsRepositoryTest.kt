package dev.gamspi.videogallery.settings

import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun setKey_trimsAndStores() {
        val store = FakeSecretStore()
        val repo = SettingsRepository(store)
        repo.setTmdbApiKey("  abc ")
        assertEquals("abc", repo.tmdbApiKey.value)
        assertEquals("abc", store.get("tmdb_api_key"))
    }

    @Test
    fun setKey_blankClears() {
        val store = FakeSecretStore()
        val repo = SettingsRepository(store)
        repo.setTmdbApiKey("abc")
        repo.setTmdbApiKey("   ")
        assertNull(repo.tmdbApiKey.value)
        assertNull(store.get("tmdb_api_key"))
    }

    @Test
    fun source_defaultsToKinopoisk() {
        val repo = SettingsRepository(FakeSecretStore())
        assertEquals(CatalogSource.Kinopoisk, repo.catalogSource.value)
    }

    @Test
    fun setSource_persists() {
        val store = FakeSecretStore()
        SettingsRepository(store).setCatalogSource(CatalogSource.Tmdb)
        assertEquals(CatalogSource.Tmdb, SettingsRepository(store).catalogSource.value)
    }

    @Test
    fun kinopoiskKey_trimsAndBlankClears() {
        val store = FakeSecretStore()
        val repo = SettingsRepository(store)
        repo.setKinopoiskApiKey("  kp ")
        assertEquals("kp", repo.kinopoiskApiKey.value)
        assertEquals("kp", store.get("kinopoisk_api_key"))
        repo.setKinopoiskApiKey("  ")
        assertNull(repo.kinopoiskApiKey.value)
        assertNull(store.get("kinopoisk_api_key"))
    }

    @Test
    fun activeCatalog_followsSourceAndKey() {
        val repo = SettingsRepository(FakeSecretStore(mapOf("tmdb_api_key" to "A", "kinopoisk_api_key" to "B")))
        assertEquals(ActiveCatalog(CatalogSource.Kinopoisk, "B"), repo.activeCatalog.value)
        repo.setCatalogSource(CatalogSource.Tmdb)
        assertEquals(ActiveCatalog(CatalogSource.Tmdb, "A"), repo.activeCatalog.value)
        repo.setTmdbApiKey("C")
        assertEquals(ActiveCatalog(CatalogSource.Tmdb, "C"), repo.activeCatalog.value)
    }

    @Test
    fun unknownStoredSource_fallsBackToKinopoisk() {
        val repo = SettingsRepository(FakeSecretStore(mapOf("catalog_source" to "Bogus")))
        assertEquals(CatalogSource.Kinopoisk, repo.catalogSource.value)
    }

    @Test
    fun initialValue_readFromStore() {
        val repo = SettingsRepository(FakeSecretStore(mapOf("tmdb_api_key" to "xyz")))
        assertEquals("xyz", repo.tmdbApiKey.value)
    }

    @Test
    fun update_emitsActiveCatalogOnce() = runBlocking {
        val repo = SettingsRepository(FakeSecretStore())
        repo.activeCatalog.test {
            assertEquals(ActiveCatalog(CatalogSource.Kinopoisk, null), awaitItem())
            repo.update(CatalogSource.Tmdb, "kp", "tm")
            assertEquals(ActiveCatalog(CatalogSource.Tmdb, "tm"), awaitItem())
            expectNoEvents()
        }
        assertEquals("kp", repo.kinopoiskApiKey.value)
    }
}
