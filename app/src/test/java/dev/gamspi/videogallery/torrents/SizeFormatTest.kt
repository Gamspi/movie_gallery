package dev.gamspi.videogallery.torrents

import org.junit.Assert.assertEquals
import org.junit.Test

class SizeFormatTest {
    @Test fun megabytes() = assertEquals("700 МБ", formatSize(734003200L))
    @Test fun gigabytes() = assertEquals("1,9 ГБ", formatSize(1992277407L))
}
