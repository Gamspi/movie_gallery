package dev.gamspi.videogallery.player

import androidx.lifecycle.SavedStateHandle
import dev.gamspi.videogallery.settings.SubtitlePrefs
import dev.gamspi.videogallery.stream.FakeTorrentEngine
import dev.gamspi.videogallery.stream.StreamManager
import dev.gamspi.videogallery.stream.StreamServer
import dev.gamspi.videogallery.stream.clearViaStore
import dev.gamspi.videogallery.subs.*
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSubtitleViewModelTest {
    private val remote = RemoteSubtitle(7, "ru", "release", true, 1)
    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nПривет\n".toByteArray()

    @Before fun mainDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }

    private class FakeClient : SubtitleClient {
        var searchCall: suspend () -> SubsResult<List<RemoteSubtitle>> = { SubsResult.Ok(emptyList()) }
        var downloadCall: suspend (Long) -> SubsResult<ByteArray> = { SubsResult.Ok(byteArrayOf()) }
        override suspend fun search(hash: String?, imdbId: String?, languages: List<String>, season: Int?, episode: Int?) = searchCall()
        override suspend fun download(fileId: Long) = downloadCall(fileId)
    }

    private fun vm(
        client: SubtitleClient,
        prefs: SubtitlePrefs = SubtitlePrefs(),
        hashFetcher: suspend (String) -> String? = { "hash" },
        store: FakePlaybackStore = FakePlaybackStore(),
        args: Map<String, String> = mapOf("url" to "http://test/movie.mkv", "imdb" to "tt123"),
    ) = PlayerViewModel(
        SavedStateHandle(args),
        store,
        StreamManager(FakeTorrentEngine(), StreamServer(CoroutineScope(Dispatchers.Unconfined)),
            File("unused"), CoroutineScope(Dispatchers.Unconfined)),
        client = client, prefs = MutableStateFlow(prefs), hashFetcher = hashFetcher, clock = { 0 },
    )

    @Test fun autoselect_downloads_remote_for_B() = runTest {
        val client = FakeClient().apply {
            searchCall = { SubsResult.Ok(listOf(remote)) }
            downloadCall = { SubsResult.Ok(srt) }
        }
        val vm = vm(client)
        try {
            vm.onTextTracks(listOf(TextTrack(1, 0, "en", null, true)))
            vm.layers.first { it.getValue(Layer.B).track != null }
            assertEquals("Привет", vm.textAt(Layer.B, 1_500))
            assertEquals((1 to 0) to null, vm.embeddedSlots.value)
        } finally { vm.clearViaStore() }
    }

    @Test fun search_errors_leave_embedded_playing_and_show_reason() = runTest {
        for ((error, note) in listOf(
            SubsResult.NoKey to "нужен ключ OpenSubtitles",
            SubsResult.BadKey to "ключ OpenSubtitles неверный",
            SubsResult.LimitReached to "лимит OpenSubtitles на сегодня",
        )) {
            val vm = vm(FakeClient().apply { searchCall = { error } })
            try {
                vm.onTextTracks(listOf(TextTrack(1, 0, "en", null, true)))
                runCurrent()
                assertEquals(note, vm.layers.value.getValue(Layer.B).note)
                assertTrue(vm.layers.value.getValue(Layer.A).source is LayerSource.Embedded)
            } finally { vm.clearViaStore() }
        }
    }

    @Test fun offset_applies_to_remote_and_clamps() = runTest {
        val vm = vm(FakeClient().apply { downloadCall = { SubsResult.Ok(srt) } })
        try {
            vm.select(Layer.B, LayerSource.Remote(remote))
            vm.layers.first { it.getValue(Layer.B).track != null }
            vm.shiftOffset(Layer.B, 500)
            assertNull(vm.textAt(Layer.B, 1_200))
            assertEquals("Привет", vm.textAt(Layer.B, 1_700))
            repeat(100) { vm.shiftOffset(Layer.B, 500) }
            assertEquals(10_000L, vm.layers.value.getValue(Layer.B).offsetMs)
            vm.shiftOffset(Layer.B, Long.MIN_VALUE)
            assertEquals(-10_000L, vm.layers.value.getValue(Layer.B).offsetMs)
        } finally { vm.clearViaStore() }
    }

    @Test fun late_search_does_not_override_manual_off() = runTest {
        val response = CompletableDeferred<SubsResult<List<RemoteSubtitle>>>()
        var downloads = 0
        val vm = vm(FakeClient().apply {
            searchCall = { response.await() }
            downloadCall = { downloads++; SubsResult.Ok(srt) }
        })
        try {
            vm.onTextTracks(emptyList())
            runCurrent()
            vm.select(Layer.B, LayerSource.Off)
            response.complete(SubsResult.Ok(listOf(remote)))
            runCurrent()
            assertEquals(LayerSource.Off, vm.layers.value.getValue(Layer.B).source)
            assertEquals(0, downloads)
            assertEquals(listOf(remote), vm.remoteOptions.value)
        } finally { vm.clearViaStore() }
    }

    @Test fun switching_source_cancels_download_and_rejects_late_result() = runTest {
        val response = CompletableDeferred<Unit>()
        var cancelled = false
        val vm = vm(FakeClient().apply {
            downloadCall = {
                try {
                    response.await()
                    SubsResult.Ok(srt)
                } catch (_: CancellationException) {
                    cancelled = true
                    withContext(NonCancellable) { response.await(); SubsResult.Ok(srt) }
                }
            }
        })
        vm.select(Layer.B, LayerSource.Remote(remote))
        runCurrent()
        vm.select(Layer.B, LayerSource.Off)
        runCurrent()
        assertTrue(cancelled)
        response.complete(Unit)
        runCurrent()
        assertEquals(LayerSource.Off, vm.layers.value.getValue(Layer.B).source)
        assertNull(vm.layers.value.getValue(Layer.B).track)
        vm.clearViaStore()
    }

    @Test fun clearViaStore_cancels_download_without_state_write() = runTest {
        var cancelled = false
        val vm = vm(FakeClient().apply {
            downloadCall = { try { awaitCancellation() } finally { cancelled = true } }
        })
        vm.select(Layer.B, LayerSource.Remote(remote))
        runCurrent()
        val before = vm.layers.value
        vm.clearViaStore()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(before, vm.layers.value)
    }

    @Test fun clearViaStore_cancels_search_without_state_write() = runTest {
        var cancelled = false
        val vm = vm(FakeClient().apply {
            searchCall = { try { awaitCancellation() } finally { cancelled = true } }
        })
        vm.onTextTracks(emptyList())
        runCurrent()
        val before = vm.layers.value
        vm.clearViaStore()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(before, vm.layers.value)
        assertTrue(vm.remoteOptions.value.isEmpty())
    }

    @Test fun clearViaStore_cancels_hash_without_starting_search() = runTest {
        var cancelled = false
        var searches = 0
        val vm = vm(FakeClient().apply {
            searchCall = { searches++; SubsResult.Ok(emptyList()) }
        }, hashFetcher = { try { awaitCancellation() } finally { cancelled = true } })
        vm.onTextTracks(emptyList())
        runCurrent()
        val before = vm.layers.value
        vm.clearViaStore()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(0, searches)
        assertEquals(before, vm.layers.value)
    }

    @Test fun fallback_error_is_only_shown_for_missing_language() = runTest {
        val en = remote.copy(language = "en")
        val vm = vm(FakeClient().apply {
            searchCall = { SubsResult.Ok(listOf(en), languageNotes = mapOf("rus" to "ключ OpenSubtitles неверный")) }
            downloadCall = { SubsResult.Ok(srt) }
        })
        try {
            vm.onTextTracks(emptyList())
            vm.layers.first { it.getValue(Layer.A).track != null }
            assertEquals(LayerSource.Remote(en), vm.layers.value.getValue(Layer.A).source)
            assertNull(vm.layers.value.getValue(Layer.A).note)
            assertEquals("Привет", vm.textAt(Layer.A, 1_500))
            assertEquals(LayerSource.None, vm.layers.value.getValue(Layer.B).source)
            assertEquals("ключ OpenSubtitles неверный", vm.layers.value.getValue(Layer.B).note)
        } finally { vm.clearViaStore() }
    }

    @Test fun same_language_uses_distinct_group_or_remote_for_B() = runTest {
        val vm = vm(FakeClient().apply {
            searchCall = { SubsResult.Ok(listOf(remote.copy(language = "en"))) }
            downloadCall = { SubsResult.Ok(srt) }
        }, SubtitlePrefs(languageA = "en", languageB = "en"))
        try {
            val embedded = TextTrack(1, 0, "eng", null, true)
            vm.onTextTracks(listOf(embedded))
            vm.layers.first { it.getValue(Layer.B).track != null }
            assertEquals(LayerSource.Embedded(embedded), vm.layers.value.getValue(Layer.A).source)
            assertTrue(vm.layers.value.getValue(Layer.B).source is LayerSource.Remote)
        } finally { vm.clearViaStore() }
    }

    @Test fun manual_embedded_selection_releases_other_layer_group() = runTest {
        val vm = vm(FakeClient())
        try {
            val embedded = TextTrack(1, 0, "en", null, true)
            vm.onTextTracks(listOf(embedded))
            vm.select(Layer.B, LayerSource.Embedded(embedded))
            assertEquals(LayerSource.None, vm.layers.value.getValue(Layer.A).source)
            assertEquals(LayerSource.Embedded(embedded), vm.layers.value.getValue(Layer.B).source)
            assertEquals(null to (1 to 0), vm.embeddedSlots.value)
            runCurrent()
            assertEquals(null to (1 to 0), vm.embeddedSlots.value)
        } finally { vm.clearViaStore() }
    }

    @Test fun changeSubtitleScale_updatesLayerAndPersists() = runTest {
        val prefs = MutableStateFlow(SubtitlePrefs())
        val saved = mutableListOf<SubtitlePrefs>()
        val vm = PlayerViewModel(
            SavedStateHandle(mapOf("url" to "http://test/movie.mkv")),
            FakePlaybackStore(),
            StreamManager(FakeTorrentEngine(), StreamServer(CoroutineScope(Dispatchers.Unconfined)),
                File("unused"), CoroutineScope(Dispatchers.Unconfined)),
            prefs = prefs,
            saveSubtitlePrefs = { saved += it; prefs.value = it },
            clock = { 0 },
        )
        try {
            vm.changeSubtitleScale(Layer.B, 1)
            assertEquals(110, vm.subtitlePrefs.value.styleB.scalePercent)
            assertEquals(100, vm.subtitlePrefs.value.styleA.scalePercent)
            repeat(10) { vm.changeSubtitleScale(Layer.A, -1) }
            assertEquals(50, vm.subtitlePrefs.value.styleA.scalePercent)
            // Steps past the limit do not write again.
            assertEquals(1 + 5, saved.size)
        } finally { vm.clearViaStore() }
    }

    private val titleKey = "imdb:Movie:tt123"

    @Test fun manual_choice_is_saved_and_restored_by_a_new_screen() = runTest {
        val store = FakePlaybackStore()
        val plain = TextTrack(1, 0, "en", null, true)
        val full = TextTrack(2, 0, "en", "Full", true)
        val first = vm(FakeClient(), store = store)
        try {
            first.onTextTracks(listOf(plain, full))
            runCurrent()
            // Auto selection is not stored.
            assertTrue(store.subtitleChoices.isEmpty())
            first.select(Layer.A, LayerSource.Embedded(full))
            first.select(Layer.B, LayerSource.Off)
            assertEquals(SubtitleChoice.Embedded("en", "Full"), store.subtitleChoices[titleKey to Layer.A])
            assertEquals(SubtitleChoice.Off, store.subtitleChoices[titleKey to Layer.B])
        } finally { first.clearViaStore() }
        val second = vm(FakeClient().apply { searchCall = { SubsResult.Ok(listOf(remote)) } }, store = store)
        try {
            second.onTextTracks(listOf(plain, full))
            runCurrent()
            assertEquals(LayerSource.Embedded(full), second.layers.value.getValue(Layer.A).source)
            assertEquals(LayerSource.Off, second.layers.value.getValue(Layer.B).source)
        } finally { second.clearViaStore() }
    }

    @Test fun restored_remote_upgrades_once_results_arrive_and_then_stays() = runTest {
        val store = FakePlaybackStore()
        store.subtitleChoices[titleKey to Layer.B] = SubtitleChoice.Remote("ru", 8, "other")
        val response = CompletableDeferred<SubsResult<List<RemoteSubtitle>>>()
        val wanted = remote.copy(fileId = 8, release = "other")
        val vm = vm(FakeClient().apply {
            searchCall = { response.await() }
            downloadCall = { SubsResult.Ok(srt) }
        }, store = store)
        try {
            val ru = TextTrack(1, 0, "ru", null, true)
            vm.onTextTracks(listOf(ru))
            runCurrent()
            // Until search results arrive the auto choice plays.
            assertEquals(LayerSource.Embedded(ru), vm.layers.value.getValue(Layer.B).source)
            response.complete(SubsResult.Ok(listOf(remote, wanted)))
            runCurrent()
            assertEquals(LayerSource.Remote(wanted), vm.layers.value.getValue(Layer.B).source)
            vm.onTextTracks(listOf(ru))
            assertEquals(LayerSource.Remote(wanted), vm.layers.value.getValue(Layer.B).source)
        } finally { vm.clearViaStore() }
    }

    @Test fun restored_off_is_not_overridden_by_late_search() = runTest {
        val store = FakePlaybackStore()
        store.subtitleChoices[titleKey to Layer.B] = SubtitleChoice.Off
        var downloads = 0
        val vm = vm(FakeClient().apply {
            searchCall = { SubsResult.Ok(listOf(remote)) }
            downloadCall = { downloads++; SubsResult.Ok(srt) }
        }, store = store)
        try {
            vm.onTextTracks(emptyList())
            runCurrent()
            assertEquals(LayerSource.Off, vm.layers.value.getValue(Layer.B).source)
            assertEquals(0, downloads)
        } finally { vm.clearViaStore() }
    }

    @Test fun missing_embedded_choice_falls_back_to_auto() = runTest {
        val store = FakePlaybackStore()
        store.subtitleChoices[titleKey to Layer.A] = SubtitleChoice.Embedded("de", "Forced")
        val vm = vm(FakeClient(), store = store)
        try {
            val en = TextTrack(1, 0, "en", null, true)
            vm.onTextTracks(listOf(en))
            runCurrent()
            assertEquals(LayerSource.Embedded(en), vm.layers.value.getValue(Layer.A).source)
        } finally { vm.clearViaStore() }
    }

    @Test fun restored_embedded_respects_group_of_other_layer() = runTest {
        val store = FakePlaybackStore()
        val en = TextTrack(1, 0, "en", null, true)
        store.subtitleChoices[titleKey to Layer.A] = SubtitleChoice.Embedded("en", null)
        store.subtitleChoices[titleKey to Layer.B] = SubtitleChoice.Embedded("en", null)
        val vm = vm(FakeClient(), store = store)
        try {
            vm.onTextTracks(listOf(en))
            runCurrent()
            assertEquals(LayerSource.Embedded(en), vm.layers.value.getValue(Layer.A).source)
            assertFalse(vm.layers.value.getValue(Layer.B).source is LayerSource.Embedded)
        } finally { vm.clearViaStore() }
    }

    @Test fun series_choice_is_shared_by_episodes() = runTest {
        val store = FakePlaybackStore()
        fun episode(n: Int) = mapOf("url" to "http://test/e$n.mkv", "key" to "bt:hash:$n", "movieId" to "9",
            "source" to "tmdb", "kind" to "Series", "season" to "1", "episode" to "$n")
        val first = vm(FakeClient(), store = store, args = episode(1))
        try {
            first.onTextTracks(emptyList())
            first.select(Layer.A, LayerSource.Off)
        } finally { first.clearViaStore() }
        assertEquals(SubtitleChoice.Off, store.subtitleChoices["tmdb:Series:9" to Layer.A])
        val next = vm(FakeClient(), store = store, args = episode(2))
        try {
            next.onTextTracks(listOf(TextTrack(1, 0, "en", null, true)))
            assertEquals(LayerSource.Off, next.layers.value.getValue(Layer.A).source)
        } finally { next.clearViaStore() }
    }
}
