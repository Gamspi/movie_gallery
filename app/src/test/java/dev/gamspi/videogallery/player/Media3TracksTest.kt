package dev.gamspi.videogallery.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Media3TracksTest {
    @Test fun codecName_mapsCommonMimes() {
        assertEquals("AC3", codecName("audio/ac3"))
        assertEquals("EAC3", codecName("audio/eac3"))
        assertEquals("DTS", codecName("audio/vnd.dts"))
        assertEquals("DTS", codecName("audio/vnd.dts.hd"))
        assertEquals("TrueHD", codecName("audio/true-hd"))
        assertEquals("AAC", codecName("audio/mp4a-latm"))
        assertEquals("opus", codecName("audio/opus"))
        assertNull(codecName(null))
    }
}
