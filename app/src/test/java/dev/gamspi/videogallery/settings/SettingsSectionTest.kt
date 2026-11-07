package dev.gamspi.videogallery.settings

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
    fun keysSectionPrecedesDebugAndAutosaves() {
        assertEquals(
            listOf("Каталог", "Трекеры", "Хранение", "Субтитры", "Перевод", "Сеть", "Ключи", "Отладка"),
            SettingsSection.entries.map { it.title },
        )
        assertEquals("settings/keys", settingsRoute(SettingsSection.Keys))
        assertTrue(SettingsSection.Keys.autosaves)
        assertTrue(SettingsSection.Catalog.autosaves)
        assertTrue(SettingsSection.Trackers.autosaves)
        // Network has only the proxy toggle, which applies immediately.
        assertFalse(SettingsSection.Network.autosaves)
    }
}
