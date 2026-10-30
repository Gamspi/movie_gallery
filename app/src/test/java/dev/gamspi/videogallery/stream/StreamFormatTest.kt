package dev.gamspi.videogallery.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamFormatTest {
    @Test fun speed_kb() = assertEquals("850 КБ/с", formatSpeed(870400))
    @Test fun speed_mb() = assertEquals("2,1 МБ/с", formatSpeed(2202010))

    @Test fun peers_plural() {
        val expected = mapOf(
            1 to "1 пир", 2 to "2 пира", 5 to "5 пиров", 11 to "11 пиров",
            0 to "0 пиров", 12 to "12 пиров", 101 to "101 пир", 111 to "111 пиров",
            21 to "21 пир", 22 to "22 пира", 25 to "25 пиров",
        )
        expected.forEach { (n, text) -> assertEquals(text, peersText(n)) }
    }

    @Test fun statusLine_withAndWithoutPercent() {
        assertEquals("↓ 2,1 МБ/с · 34 пира", statusLine(2202010, 34, null))
        assertEquals("↓ 850 КБ/с · 5 пиров · загружено 37 %", statusLine(870400, 5, 37))
    }

    @Test fun contentType_known_and_default() {
        assertEquals("video/x-matroska", contentTypeOf("a/b.MKV"))
        assertEquals("video/mp4", contentTypeOf("b.mp4"))
        assertEquals("application/octet-stream", contentTypeOf("b.xyz"))
    }
}
