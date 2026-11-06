package dev.gamspi.videogallery.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleSettingsTest {
    @Test fun baseSizeIsFortyPercentSmallerThanOldMedium() {
        assertEquals(21.6f, LayerStyle().fontSp, 0.001f)
        assertEquals(43.2f, LayerStyle(scalePercent = 200).fontSp, 0.001f)
        assertEquals(10.8f, LayerStyle(scalePercent = 50).fontSp, 0.001f)
    }

    @Test fun stepClampsToRange() {
        assertEquals(110, stepSubScale(100, 1))
        assertEquals(90, stepSubScale(100, -1))
        assertEquals(50, stepSubScale(50, -1))
        assertEquals(200, stepSubScale(200, 1))
        assertEquals(200, stepSubScale(190, 5))
    }

    @Test fun parseMapsLegacyNamesAndNumbers() {
        assertEquals(80, parseSubScale("Small"))
        assertEquals(100, parseSubScale("Medium"))
        assertEquals(130, parseSubScale("Large"))
        assertEquals(150, parseSubScale("150"))
        assertEquals(50, parseSubScale("-5"))
        assertEquals(200, parseSubScale("250"))
        assertEquals(60, parseSubScale("64"))
        assertNull(parseSubScale(null))
        assertNull(parseSubScale(" "))
        assertNull(parseSubScale("Giant"))
    }

    @Test fun formatShowsPercent() = assertEquals("120 %", formatSubScale(120))
}
