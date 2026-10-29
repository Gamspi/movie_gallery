package dev.gamspi.videogallery.torrents.rutor

import dev.gamspi.videogallery.torrents.ProviderId
import dev.gamspi.videogallery.torrents.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RutorParserTest {
    private fun fixture() = javaClass.getResource("/torrents/rutor_search.html")!!.readText()

    private fun row(size: String, cls: String = "gai", magnet: Boolean = true) =
        """<table><tr class="$cls"><td>09&nbsp;Июл&nbsp;26</td><td >""" +
            (if (magnet) """<a href="magnet:?xt=urn:btih:ABCDEF0123456789abcdef0123456789abcdef01&dn=x"><img alt="M"></a>""" else "") +
            """<a href="/torrent/1/x">Фильм (1999) 720p</a></td><td align="right">$size</td>""" +
            """<td align="center"><span class="green"><img alt="S">&nbsp;3</span>&nbsp;<span class="red">&nbsp;4</span></td></tr></table>"""

    @Test
    fun parsesRows_titleHashSizeSeedsLeech() {
        val r = RutorParser.parse(fixture())
        assertEquals(4, r.size)
        val first = r[0]
        assertEquals(
            "Матрица. Квадрология / The Matrix. Quadrology / 1999-2021 / UHD BDRemux 2160p | 4K | HDR | Dolby Vision P8 | D",
            first.title,
        )
        assertEquals(ProviderId.Rutor, first.provider)
        assertEquals("f99edb3d16451a80283463eda2a9e8ea1fccacc9", first.infoHash)
        assertTrue(first.magnet.startsWith("magnet:?xt=urn:btih:f99edb3d16451a80283463eda2a9e8ea1fccacc9"))
        assertEquals((265.94 * 1024 * 1024 * 1024).toLong(), first.sizeBytes)
        assertEquals(14, first.seeders)
        assertEquals(9, first.leechers)
        assertEquals(Quality.Uhd, first.quality)
        // tum row with comments cell
        assertEquals("ae0cf422fa0bc9669ad605ea564999a8fbe5aa4b", r[1].infoHash)
        assertEquals(Quality.FullHd, r[1].quality)
        assertEquals(1, r[1].leechers)
        // gai row without comments cell
        assertEquals("8241ba8d49adb5c5d1b2f260944976c78dbaee05", r[2].infoHash)
        assertEquals((29.22 * 1024 * 1024 * 1024).toLong(), r[2].sizeBytes)
        assertEquals(5, r[2].seeders)
        assertEquals(2, r[2].leechers)
        // tum row without comments cell
        assertEquals("61065ea115b7cc3e8db9fb5ab1f6f327f08bd1c9", r[3].infoHash)
        assertEquals(16, r[3].seeders)
        assertEquals(66, r[3].leechers)
    }

    @Test
    fun parsesSizeUnits() {
        fun size(s: String) = RutorParser.parse(row(s)).single().sizeBytes
        assertEquals((700.5 * 1024 * 1024).toLong(), size("700.5&nbsp;MB"))
        assertEquals((1.46 * 1024 * 1024 * 1024).toLong(), size("1.46&nbsp;GB"))
        assertEquals((265.94 * 1024 * 1024 * 1024).toLong(), size("265.94&nbsp;GB"))
        assertEquals(2048L, size("2&nbsp;kB"))
    }

    @Test
    fun skipsRowsWithoutMagnet() {
        assertTrue(RutorParser.parse(row("1&nbsp;GB", magnet = false)).isEmpty())
        // fixture has 5 rows, the last without magnet
        assertEquals(4, RutorParser.parse(fixture()).size)
    }

    @Test
    fun normalizesHashToLowerCase() {
        assertEquals("abcdef0123456789abcdef0123456789abcdef01", RutorParser.parse(row("1&nbsp;GB")).single().infoHash)
    }
}
