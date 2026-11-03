package dev.gamspi.videogallery.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
