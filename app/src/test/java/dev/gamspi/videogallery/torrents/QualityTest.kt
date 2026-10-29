package dev.gamspi.videogallery.torrents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityTest {
    @Test fun uhd() = assertEquals(Quality.Uhd, parseQuality("The.Matrix.1999.2160p.UHD"))
    @Test fun fullHd() = assertEquals(Quality.FullHd, parseQuality("Матрица 1999 BDRip 1080p"))
    @Test fun hd() = assertEquals(Quality.Hd, parseQuality("720p WEB"))
    @Test fun none() = assertNull(parseQuality("DVDRip"))
    @Test fun noWordBoundary() = assertNull(parseQuality("x1080p5"))
}
