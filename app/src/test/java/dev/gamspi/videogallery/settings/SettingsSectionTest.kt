package dev.gamspi.videogallery.settings

import dev.gamspi.videogallery.torrents.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSectionTest {
    @Test
    fun routesRoundTrip() {
        SettingsSection.entries.forEach { s ->
            assertEquals(s, sectionOf(settingsRoute(s).removePrefix("settings/")))
        }
        assertEquals("settings/catalog", settingsRoute(SettingsSection.Catalog))
        assertNull(sectionOf("nope"))
        assertNull(sectionOf(null))
    }

    @Test
    fun keysSectionPrecedesDebug() {
        assertEquals(
            listOf("Каталог", "Трекеры", "Хранение", "Субтитры", "Перевод", "Сеть", "Ключи", "Отладка"),
            SettingsSection.entries.map { it.title },
        )
        assertEquals("settings/keys", settingsRoute(SettingsSection.Keys))
        // Service screens save on Back; the list itself has nothing to save.
        assertFalse(SettingsSection.Keys.autosaves)
        assertTrue(SettingsSection.Catalog.autosaves)
        assertTrue(SettingsSection.Trackers.autosaves)
        // Network has only the proxy toggle, which applies immediately.
        assertFalse(SettingsSection.Network.autosaves)
    }

    @Test
    fun keyServicesOrderAndRoutes() {
        assertEquals(
            listOf("PoiskKino", "Кинопоиск", "TMDB") + ProviderId.entries.map { it.label } + listOf("OpenSubtitles", "Прокси"),
            KeyService.entries.map { it.title },
        )
        assertEquals(ProviderId.entries, KeyService.entries.mapNotNull { it.tracker })
        KeyService.entries.forEach { s ->
            assertEquals(s, keyServiceOf(keyServiceRoute(s).removePrefix("settings/keys/")))
        }
        assertEquals("settings/keys/{service}", KEY_SERVICE_ROUTE)
        assertEquals("settings/keys/proxy", keyServiceRoute(KeyService.Proxy))
        assertNull(keyServiceOf("nope"))
        assertNull(keyServiceOf(null))
    }
}
