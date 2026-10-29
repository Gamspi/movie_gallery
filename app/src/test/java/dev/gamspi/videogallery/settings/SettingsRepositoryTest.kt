package dev.gamspi.videogallery.settings

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
    fun initialValue_readFromStore() {
        val repo = SettingsRepository(FakeSecretStore(mapOf("tmdb_api_key" to "xyz")))
        assertEquals("xyz", repo.tmdbApiKey.value)
    }
}
