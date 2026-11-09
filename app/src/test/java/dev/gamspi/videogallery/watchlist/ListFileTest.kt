package dev.gamspi.videogallery.watchlist

import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListFileTest {
    @Test fun atomicFileRestartsAndRecoversBackup() = runTest {
        val directory = Files.createTempDirectory("list-store-test").toFile()
        try {
            val file = File(directory, "list.json")
            val row = ListRecord(ListTable.Titles, "kp-1", 1)
            LocalListStore.fromFile(file).edit(row)
            assertEquals(row, LocalListStore.fromFile(file).pending().single())
            val backup = File(directory, "list.json.bak")
            check(file.renameTo(backup))
            file.writeText("partial write")
            assertEquals(row, LocalListStore.fromFile(file).pending().single())
            assertFalse(backup.exists())
        } finally { directory.deleteRecursively() }
    }
}
