package dev.gamspi.videogallery.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultingSecretStoreTest {
    private val defaults = mapOf("k" to "fallback")

    @Test
    fun storedValueWins() {
        val store = DefaultingSecretStore(FakeSecretStore(mapOf("k" to "stored")), defaults)
        assertEquals("stored", store.get("k"))
    }

    @Test
    fun missingOrBlankFallsBackToDefault() {
        val inner = FakeSecretStore()
        val store = DefaultingSecretStore(inner, defaults)
        assertEquals("fallback", store.get("k"))
        inner.put("k", "  ")
        assertEquals("fallback", store.get("k"))
        inner.put("k", "")
        assertEquals("fallback", store.get("k"))
    }

    @Test
    fun absentEverywhereIsNull() {
        val store = DefaultingSecretStore(FakeSecretStore(), defaults)
        assertNull(store.get("other"))
    }

    @Test
    fun putDelegatesAndNeverWritesDefaults() {
        val inner = FakeSecretStore()
        val store = DefaultingSecretStore(inner, defaults)
        store.get("k")
        assertNull(inner.get("k"))
        store.put("k", "new")
        assertEquals("new", inner.get("k"))
        store.put("k", null)
        assertNull(inner.get("k"))
        assertEquals("fallback", store.get("k"))
    }

    @Test
    fun envDefaultsSkipBlankAndMapKeys() {
        val map = buildEnvDefaults(
            kinopoisk = "kp", tmdb = "", poiskkino = " ", osKey = "osk",
            osUser = "", osPassword = "", proxyUrl = "https://p", proxyKey = "",
            rutrackerUser = "ru", rutrackerPassword = "",
        )
        assertEquals(
            mapOf(
                SettingsRepository.KEY_KINOPOISK to "kp",
                SettingsRepository.KEY_OS_KEY to "osk",
                SettingsRepository.KEY_PROXY_URL to "https://p",
                SettingsRepository.KEY_RUTRACKER_USER to "ru",
            ),
            map,
        )
    }

    @Test
    fun repositoryReadsFallbackForEmptyStore() {
        val store = DefaultingSecretStore(
            FakeSecretStore(),
            mapOf(SettingsRepository.KEY_TMDB to "tm"),
        )
        assertEquals("tm", SettingsRepository(store).tmdbApiKey.value)
    }
}
