package dev.gamspi.videogallery.player

import androidx.lifecycle.SavedStateHandle
import dev.gamspi.videogallery.stream.FakeTorrentEngine
import dev.gamspi.videogallery.stream.StreamManager
import dev.gamspi.videogallery.stream.clearViaStore
import dev.gamspi.videogallery.stream.StreamServer
import dev.gamspi.videogallery.stream.StreamState
import dev.gamspi.videogallery.stream.TorrentFile
import dev.gamspi.videogallery.stream.TorrentCacheStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerViewModelTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun terminalStreamFailure_afterReady_exposesStorageReasonOnlyToOwnedTorrent() = runTest {
        val directory = Files.createTempDirectory("player-storage-failure").toFile()
        val free = AtomicLong(Long.MAX_VALUE)
        val hash = "a".repeat(40)
        val engine = FakeTorrentEngine().apply {
            files = listOf(TorrentFile(0, "a.mkv", 1024L))
            have += 0
        }
        val manager = StreamManager(engine, StreamServer(backgroundScope), directory, backgroundScope,
            cache = TorrentCacheStore(File(directory, "torrents"), freeBytes = free::get))
        try {
            manager.start("magnet:$hash", "Film")
            manager.state.first { it is StreamState.Ready }
            val owner = vm(key = "bt:$hash:0", manager = manager)
            val urlPlayer = vm(manager = manager)
            free.set(0)
            val failure = manager.state.first { it is StreamState.Failed } as StreamState.Failed
            assertTrue(failure.message.contains("Недостаточно свободного места"))
            owner.onError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED")
            assertEquals(failure.message, owner.terminalStreamError.first())
            assertNull(urlPlayer.terminalStreamError.first())
            manager.start("magnet:${"b".repeat(40)}", "Other")
            manager.state.first { it is StreamState.Failed }
            assertNull(owner.terminalStreamError.first())
        } finally {
            manager.stop()
            runCurrent()
            directory.deleteRecursively()
        }
    }

    private val store = FakePlaybackStore()
    private var now = 0L

    private fun vm(
        url: String = "http://x/a.mp4",
        key: String = "",
        manager: StreamManager = StreamManager(
            FakeTorrentEngine(),
            StreamServer(CoroutineScope(Dispatchers.Unconfined)),
            File("unused"),
            CoroutineScope(Dispatchers.Unconfined),
        ),
    ) = PlayerViewModel(
        SavedStateHandle(mapOf("url" to url, "title" to "T", "key" to key)),
        store,
        manager,
    ) { now }

    private fun track(lang: String?, label: String? = null) =
        AudioTrack(0, 0, lang, label, "aac", true)

    private fun vmImdb(imdb: String?) = PlayerViewModel(
        SavedStateHandle(mapOf("url" to "http://x/a.mp4", "title" to "T", "key" to "", "imdb" to imdb)),
        store,
        StreamManager(
            FakeTorrentEngine(),
            StreamServer(CoroutineScope(Dispatchers.Unconfined)),
            File("unused"),
            CoroutineScope(Dispatchers.Unconfined),
        ),
    ) { now }

    @Test
    fun imdbId_readAndBlankIsNull() {
        assertEquals("tt0133093", vmImdb("tt0133093").imdbId)
        assertNull(vmImdb("").imdbId)
        assertNull(vmImdb(null).imdbId)
    }

    @Test
    fun resumeOffer_fromStore() {
        store.positions["k"] = 120_000
        val vm = vm(key = "k")
        vm.onDuration(3_600_000)
        assertEquals(120_000L, vm.state.value.resumeOffer)
        vm.dismissResume()
        assertNull(vm.state.value.resumeOffer)
        vm.onDuration(3_600_000)
        assertNull(vm.state.value.resumeOffer)
    }

    @Test
    fun resumeOffer_nullWhenShort() {
        store.positions["k"] = 30_000
        val vm = vm(key = "k")
        vm.onDuration(3_600_000)
        assertNull(vm.state.value.resumeOffer)
    }

    @Test
    fun progress_throttled10s() {
        val vm = vm(key = "k")
        now = 0; vm.onProgress(1_000, 3_600_000)
        now = 2_000; vm.onProgress(3_000, 3_600_000)
        now = 5_000; vm.onProgress(6_000, 3_600_000)
        assertEquals(1, store.saveCount)
        now = 10_000; vm.onProgress(11_000, 3_600_000)
        assertEquals(2, store.saveCount)
        assertEquals(11_000L, store.positions["k"])
    }

    @Test
    fun exit_savesImmediately() {
        val vm = vm(key = "k")
        vm.onPauseOrExit(500_000, 3_600_000)
        assertEquals(500_000L, store.positions["k"])
    }

    @Test
    fun exit_finished_clears() {
        store.positions["k"] = 100_000
        val vm = vm(key = "k")
        vm.onPauseOrExit(3_500_000, 3_600_000)
        assertNull(store.positions["k"])
    }

    @Test
    fun exit_zero_keepsSaved() {
        store.positions["k"] = 100_000
        val vm = vm(key = "k")
        vm.onPauseOrExit(0, 3_600_000)
        assertEquals(100_000L, store.positions["k"])
        assertEquals(0, store.saveCount)
    }

    @Test
    fun audioSelected_savesPreference() {
        val vm = vm()
        vm.onAudioSelected(track("ru", "Дубляж"))
        assertEquals(AudioPreference("ru", "Дубляж"), store.preference)
        store.preference = null
        vm.onAudioSelected(track(null))
        assertNull(store.preference)
        vm.onAudioSelected(track("und"))
        assertNull(store.preference)
    }

    @Test
    fun initialAudio_usesPreference() {
        store.preference = AudioPreference("ru", null)
        val vm = vm()
        val ru = track("ru")
        assertEquals(ru, vm.chooseInitialAudio(listOf(track("en"), ru)))
    }

    @Test
    fun keyDefaultsToUrl() {
        val vm = vm(url = "http://x/a.mp4")
        vm.onPauseOrExit(500_000, 3_600_000)
        assertEquals(500_000L, store.positions["url:http://x/a.mp4"])
    }

    @Test
    fun clearError_resetsError() {
        val vm = vm()
        vm.onError("X")
        assertEquals("X", vm.state.value.error)
        vm.clearError()
        assertNull(vm.state.value.error)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun btKey_exposesStatusLine() = runTest {
        val engine = FakeTorrentEngine().apply { files = listOf(TorrentFile(0, "a.mkv", 10L * 1024 * 1024)) }
        val sm = StreamManager(engine, StreamServer(backgroundScope), File("unused"), backgroundScope)
        sm.start("magnet:abc", "Film")
        runCurrent()
        val bt = vm(key = "bt:abc:0", manager = sm)
        assertEquals(sm.statusLine.value, bt.statusLine.value)
        assertTrue(bt.statusLine.value!!.contains("пира"))
        sm.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun btKey_clearedStopsSession() = runTest {
        val engine = FakeTorrentEngine().apply { files = listOf(TorrentFile(0, "a.mkv", 10L * 1024 * 1024)) }
        val sm = StreamManager(engine, StreamServer(backgroundScope), File("unused"), backgroundScope)
        sm.start("magnet:abc", "Film")
        runCurrent()
        assertTrue(sm.state.value is StreamState.Buffering)
        vm(key = "bt:abc:0", manager = sm).clearViaStore()
        assertEquals(StreamState.Idle, sm.state.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun urlKey_noStatus_noStop() = runTest {
        val engine = FakeTorrentEngine().apply { files = listOf(TorrentFile(0, "a.mkv", 10L * 1024 * 1024)) }
        val sm = StreamManager(engine, StreamServer(backgroundScope), File("unused"), backgroundScope)
        sm.start("magnet:abc", "Film")
        runCurrent()
        val vm = vm(key = "", manager = sm)
        assertNull(vm.statusLine.value)
        vm.clearViaStore()
        assertTrue(sm.state.value is StreamState.Buffering)
        sm.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun btKey_oldVmCleared_afterNewSession_newSurvives() = runTest {
        val engine = FakeTorrentEngine().apply { files = listOf(TorrentFile(0, "a.mkv", 10L * 1024 * 1024)) }
        val sm = StreamManager(engine, StreamServer(backgroundScope), File("unused"), backgroundScope)
        sm.start("magnet:abc", "Film")
        runCurrent()
        val old = vm(key = "bt:abc:0", manager = sm)
        sm.start("magnet:def", "Film2")
        runCurrent()
        old.clearViaStore()
        assertTrue(sm.state.value is StreamState.Buffering)
        sm.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun warning_onlyExposesOwnedTorrentSession() = runTest {
        val engine = FakeTorrentEngine().apply { files = listOf(TorrentFile(0, "a.mkv", 10L * 1024 * 1024)) }
        val sm = StreamManager(engine, StreamServer(backgroundScope), File("unused"), backgroundScope,
            clock = { testScheduler.currentTime })
        sm.start("magnet:abc", "Film")
        runCurrent()
        val bt = vm(key = "bt:abc:0", manager = sm)
        val url = vm(manager = sm)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(sm.currentSessionId(), bt.stallWarning.first()?.sessionId)
        assertNull(url.stallWarning.first())
        url.chooseAnotherTorrent()
        assertTrue(sm.state.value is StreamState.Buffering)
        bt.chooseAnotherTorrent()
        assertEquals(StreamState.Idle, sm.state.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun oldPlaybackSignalsAndChooseAnother_doNotChangeNewSessionWarning() = runTest {
        val engine = FakeTorrentEngine().apply { files = listOf(TorrentFile(0, "a.mkv", 10L * 1024 * 1024)) }
        val sm = StreamManager(engine, StreamServer(backgroundScope), File("unused"), backgroundScope,
            clock = { testScheduler.currentTime })
        sm.start("magnet:abc", "Film")
        runCurrent()
        val old = vm(key = "bt:abc:0", manager = sm)
        sm.start("magnet:def", "Film2")
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        val warning = sm.stallWarning.value
        assertTrue(warning != null)
        assertNull(old.stallWarning.first())
        old.onPlaybackWaiting(true)
        old.onPlaybackWaiting(false)
        old.onPlaybackSeek()
        old.onPauseOrExit(0, 0)
        old.chooseAnotherTorrent()
        old.clearViaStore()
        assertEquals(warning, sm.stallWarning.value)
        assertTrue(sm.state.value is StreamState.Buffering)
        sm.stop()
    }
}
