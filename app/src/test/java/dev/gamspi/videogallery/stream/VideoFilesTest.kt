package dev.gamspi.videogallery.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFilesTest {
    @Test fun filtersByExtension_caseInsensitive() {
        val files = listOf(
            TorrentFile(0, "a/Movie.MKV", 10),
            TorrentFile(1, "a/readme.txt", 1),
            TorrentFile(2, "b.Mp4", 5),
            TorrentFile(3, "c.rar", 100),
        )
        assertEquals(listOf(0, 2), videoFiles(files).map { it.index })
    }

    @Test fun sortsBySizeDesc() {
        val files = listOf(
            TorrentFile(0, "s.avi", 5),
            TorrentFile(1, "b.mkv", 50),
            TorrentFile(2, "m.mp4", 20),
        )
        assertEquals(listOf(1, 2, 0), videoFiles(files).map { it.index })
    }

    @Test fun noVideo_empty() {
        assertTrue(videoFiles(listOf(TorrentFile(0, "a.rar", 9), TorrentFile(1, "b.iso", 8))).isEmpty())
    }
}
