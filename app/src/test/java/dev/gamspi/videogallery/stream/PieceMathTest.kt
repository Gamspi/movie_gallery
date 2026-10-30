package dev.gamspi.videogallery.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PieceMathTest {
    private val mib = 1024L * 1024

    @Test fun piecesFor_withinFile_offsetAware() {
        val span = FileSpan(offset = 1000, length = 10_000, pieceLength = 256)
        assertEquals(3..4, span.piecesFor(0, 255))
    }

    @Test fun piecesFor_zeroLength_empty() {
        val span = FileSpan(offset = 1000, length = 0, pieceLength = 256)
        assertTrue(span.readinessPieces().isEmpty())
    }

    @Test fun piecesFor_oneByteFile() {
        val span = FileSpan(offset = 1000, length = 1, pieceLength = 256)
        assertEquals(3..3, span.piecesFor(0, 0))
        assertEquals(setOf(3), span.readinessPieces())
    }

    @Test fun piecesFor_oddOffset_crossesBoundary() {
        val pl = 256
        val offset = 1000L
        val span = FileSpan(offset = offset, length = 10_000, pieceLength = pl)
        val start = pl - (offset % pl) - 1
        assertEquals(2, span.piecesFor(start, start + 1).count())
    }

    @Test fun piecesFor_inclusiveEnd() {
        val span = FileSpan(offset = 1000, length = 10_000, pieceLength = 256)
        assertEquals(3..4, span.piecesFor(0, 279)) // last byte 1279 is still piece 4
        assertEquals(3..5, span.piecesFor(0, 280)) // last byte 1280 starts piece 5
    }

    @Test fun readiness_headAndTail() {
        val span = FileSpan(0, 100 * mib, pieceLength = mib.toInt())
        val expected = (0..4).toSet() + (95..99).toSet()
        assertEquals(expected, span.readinessPieces())
    }

    @Test fun readiness_capsHeadAt20MB() {
        val span = FileSpan(0, 1024 * mib, pieceLength = mib.toInt())
        val expected = (0..19).toSet() + (1019..1023).toSet()
        assertEquals(expected, span.readinessPieces())
    }

    @Test fun readiness_smallFile_noOverflow() {
        val span = FileSpan(offset = 10 * mib, length = 3 * mib, pieceLength = mib.toInt())
        val pieces = span.readinessPieces()
        assertTrue(pieces.isNotEmpty())
        assertTrue(pieces.all { it in 10..12 })
        assertEquals(setOf(10, 11, 12), pieces)
    }

    @Test fun percent_emptyIs100() = assertEquals(100, readinessPercent(emptySet()) { false })

    @Test fun percent_counts() {
        assertEquals(50, readinessPercent(setOf(1, 2, 3, 4)) { it <= 2 })
        assertEquals(0, readinessPercent(setOf(1, 2)) { false })
        assertEquals(100, readinessPercent(setOf(1, 2)) { true })
    }
}
