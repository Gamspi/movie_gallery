package dev.gamspi.videogallery.player

import androidx.lifecycle.SavedStateHandle
import dev.gamspi.videogallery.settings.SubtitlePrefs
import dev.gamspi.videogallery.stream.FakeTorrentEngine
import dev.gamspi.videogallery.stream.StreamManager
import dev.gamspi.videogallery.stream.StreamServer
import dev.gamspi.videogallery.stream.clearViaStore
import dev.gamspi.videogallery.subs.*
import dev.gamspi.videogallery.translate.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerTranslationViewModelTest {
    private val en = RemoteSubtitle(7, "en", "release", true, 1)
    private val en2 = RemoteSubtitle(8, "en", "other", true, 1)
    private val hello = "1\n00:00:01,000 --> 00:00:02,000\nHello\n".toByteArray()
    private val world = "1\n00:00:01,000 --> 00:00:02,000\nWorld\n".toByteArray()
    private val fake = FakeSubtitleTranslator()
    private val cache = TranslationCache(InMemoryTranslationDao(), tx = { it() }, dispatcher = Dispatchers.Unconfined)

    @Before fun mainDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }

    /** n cues, cue i at i seconds with text "c<i>". */
    private fun srt(n: Int) = (0 until n).joinToString("\n") {
        "${it + 1}\n${time(it * 1000L)} --> ${time(it * 1000L + 900)}\nc$it\n"
    }.toByteArray()

    private fun time(ms: Long) =
        "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    private class FakeClient(val files: Map<Long, ByteArray>) : SubtitleClient {
        override suspend fun search(hash: String?, imdbId: String?, languages: List<String>, season: Int?, episode: Int?) =
            SubsResult.Ok(emptyList<RemoteSubtitle>())
        override suspend fun download(fileId: Long) = SubsResult.Ok(files.getValue(fileId))
    }

    private fun TestScope.vm(
        models: TranslationModels = FakeTranslationModels(),
        prefs: SubtitlePrefs = SubtitlePrefs(),
        files: Map<Long, ByteArray> = mapOf(7L to hello, 8L to world),
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ) = PlayerViewModel(
        SavedStateHandle(mapOf("url" to "http://test/movie.mkv")),
        FakePlaybackStore(),
        StreamManager(FakeTorrentEngine(), StreamServer(CoroutineScope(Dispatchers.Unconfined)),
            File("unused"), CoroutineScope(Dispatchers.Unconfined)),
        client = FakeClient(files), prefs = MutableStateFlow(prefs), clock = { 0 },
        models = models, translator = fake, cache = cache,
        translationDispatcher = dispatcher,
    )

    private fun PlayerViewModel.layer(l: Layer) = layers.value.getValue(l)

    /** Parsing runs on a real thread, so wait for the source track. */
    private suspend fun PlayerViewModel.remoteA(sub: RemoteSubtitle) {
        select(Layer.A, LayerSource.Remote(sub))
        layers.first { it.getValue(Layer.A).source == LayerSource.Remote(sub) && it.getValue(Layer.A).track != null }
    }

    private val none = emptyList<LayerSource.Translated>()

    @Test fun options_for_remote_source_and_no_chains() = runTest {
        val vm = vm()
        try {
            assertEquals(none, vm.translationOptions(Layer.B))
            vm.select(Layer.A, LayerSource.Off)
            assertEquals(none, vm.translationOptions(Layer.B))
            vm.select(Layer.A, LayerSource.Remote(en))
            assertEquals(listOf(LayerSource.Translated(Layer.A, TranslateLang.EN)), vm.translationOptions(Layer.B))
            // Source of A would be B, which has nothing.
            assertEquals(none, vm.translationOptions(Layer.A))
            vm.select(Layer.B, LayerSource.Translated(Layer.A, TranslateLang.EN))
            assertEquals(none, vm.translationOptions(Layer.A))
            // A chain request is ignored.
            vm.select(Layer.A, LayerSource.Translated(Layer.B, TranslateLang.RU))
            assertEquals(LayerSource.Remote(en), vm.layer(Layer.A).source)
            // Source already in the target language.
            vm.select(Layer.A, LayerSource.Remote(en.copy(language = "ru")))
            assertEquals(none, vm.translationOptions(Layer.B))
        } finally { vm.clearViaStore() }
    }

    @Test fun unknown_embedded_language_offers_both_directions() = runTest {
        for (language in listOf("und", null, "", "x!")) {
            for (target in listOf("ru", "de")) {
                val vm = vm(prefs = SubtitlePrefs(languageB = target))
                try {
                    val track = TextTrack(1, 0, language, null, true)
                    vm.onTextTracks(listOf(track))
                    vm.select(Layer.A, LayerSource.Embedded(track))
                    assertEquals(listOf(LayerSource.Translated(Layer.A, TranslateLang.EN),
                        LayerSource.Translated(Layer.A, TranslateLang.RU)), vm.translationOptions(Layer.B))
                } finally { vm.clearViaStore() }
            }
        }
    }

    @Test fun target_language_outside_pair_has_no_options() = runTest {
        val vm = vm(prefs = SubtitlePrefs(languageB = "de"))
        try {
            vm.select(Layer.A, LayerSource.Remote(en))
            assertEquals(none, vm.translationOptions(Layer.B))
            val de = TextTrack(1, 0, "de", null, true)
            vm.onTextTracks(listOf(de))
            vm.select(Layer.A, LayerSource.Embedded(de))
            assertEquals(none, vm.translationOptions(Layer.B))
        } finally { vm.clearViaStore() }
    }

    @Test fun model_download_then_translation() = runTest {
        val gate = CompletableDeferred<Unit>()
        val models = FakeTranslationModels(ModelState.NotDownloaded).apply {
            onEnsure = { gate.await(); state.value = ModelState.Ready }
        }
        val vm = vm(models)
        try {
            vm.remoteA(en)
            vm.select(Layer.B, LayerSource.Translated(Layer.A, TranslateLang.EN))
            runCurrent()
            assertEquals(1, models.ensureCalls)
            assertEquals("Загрузка модели…", vm.layer(Layer.B).note)
            assertTrue(fake.calls.isEmpty())
            gate.complete(Unit)
            advanceUntilIdle()
            assertNull(vm.layer(Layer.B).note)
            assertEquals("[Hello]", vm.textAt(Layer.B, 1_500))
            assertEquals(listOf(Triple("Hello", TranslateLang.EN, TranslateLang.RU)), fake.calls)
        } finally { vm.clearViaStore() }
    }

    @Test fun model_failure_shows_reason_and_retries_on_next_select() = runTest {
        val models = FakeTranslationModels(ModelState.NotDownloaded).apply {
            onEnsure = { state.value = ModelState.Failed(ModelFailure.NoNetwork) }
        }
        val vm = vm(models)
        try {
            vm.remoteA(en)
            vm.select(Layer.B, LayerSource.Translated(Layer.A, TranslateLang.EN))
            advanceUntilIdle()
            assertEquals(ModelFailure.NoNetwork.message(), vm.layer(Layer.B).note)
            assertNull(vm.textAt(Layer.B, 1_500))
            assertEquals(ModelFailure.NoNetwork.message(), vm.overlayText(Layer.B, 1_500))
            models.onEnsure = { models.state.value = ModelState.Ready }
            vm.select(Layer.B, LayerSource.Translated(Layer.A, TranslateLang.EN))
            advanceUntilIdle()
            assertEquals(2, models.ensureCalls)
            assertEquals("[Hello]", vm.textAt(Layer.B, 1_500))
        } finally { vm.clearViaStore() }
    }

    @Test fun shows_progress_note_until_first_cue() = runTest {
        val gate = CompletableDeferred<Unit>()
        fake.onCall = { gate.await() }
        val vm = vm()
        try {
            vm.remoteA(en)
            vm.select(Layer.B, LayerSource.Translated(Layer.A, TranslateLang.EN))
            runCurrent()
            assertEquals("Перевод…", vm.layer(Layer.B).note)
            gate.complete(Unit)
            advanceUntilIdle()
            assertNull(vm.layer(Layer.B).note)
        } finally { vm.clearViaStore() }
    }

    @Test fun translated_remote_uses_source_offset() = runTest {
        val vm = vm()
        try {
            vm.remoteA(en)
            vm.select(Layer.B, LayerSource.Translated(Layer.A, TranslateLang.EN))
            advanceUntilIdle()
            vm.shiftOffset(Layer.A, 500)
            vm.shiftOffset(Layer.B, 3_000)
            assertNull(vm.textAt(Layer.B, 1_200))
            assertEquals("[Hello]", vm.textAt(Layer.B, 1_700))
            assertEquals("Hello", vm.textAt(Layer.A, 1_700))
        } finally { vm.clearViaStore() }
    }

    @Test fun source_change_restarts_and_drops_late_results() = runTest {
        val gate = CompletableDeferred<Unit>()
        var cancelled = 0
        fake.onCall = {
            if (it == "Hello") try { gate.await() } catch (e: CancellationException) {
                cancelled++
                withContext(NonCancellable) { gate.await() }
            }
        }
        val vm = vm()
        try {
            vm.remoteA(en)
            vm.select(Layer.B, LayerSource.Translated(Layer.A, TranslateLang.EN))
            runCurrent()
            assertEquals(listOf("Hello"), fake.texts)
            vm.select(Layer.A, LayerSource.Remote(en2))
            runCurrent()
            assertEquals(1, cancelled)
            gate.complete(Unit)
            vm.layers.first { it.getValue(Layer.B).track?.cues?.map { c -> c.text } == listOf("[World]") }
            advanceUntilIdle()
            assertEquals(LayerSource.Translated(Layer.A, TranslateLang.EN), vm.layer(Layer.B).source)
            assertEquals(listOf("[World]"), vm.layer(Layer.B).track?.cues?.map { it.text })
            assertEquals("[World]", vm.textAt(Layer.B, 1_500))

            vm.select(Layer.A, LayerSource.Off)
            advanceUntilIdle()
            assertEquals("Нет субтитров для перевода", vm.layer(Layer.B).note)
            assertNull(vm.layer(Layer.B).track)
            assertNull(vm.textAt(Layer.B, 1_500))
        } finally { vm.clearViaStore() }
    }

    @Test fun cleared_vm_ignores_model_and_translation_results() = runTest {
        val modelGate = CompletableDeferred<Unit>()
        val models = FakeTranslationModels(ModelState.NotDownloaded).apply {
            onEnsure = { withContext(NonCancellable) { modelGate.await() }; state.value = ModelState.Ready }
        }
        val vm1 = vm(models)
        vm1.remoteA(en)
        vm1.select(Layer.B, LayerSource.Translated(Layer.A, TranslateLang.EN))
        runCurrent()
        val before1 = vm1.layers.value
        vm1.clearViaStore()
        modelGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(before1, vm1.layers.value)
        assertTrue(fake.calls.isEmpty())

        val gate = CompletableDeferred<Unit>()
        fake.onCall = { withContext(NonCancellable) { gate.await() } }
        val vm2 = vm()
        vm2.remoteA(en)
        vm2.select(Layer.B, LayerSource.Translated(Layer.A, TranslateLang.EN))
        runCurrent()
        assertEquals(listOf("Hello"), fake.texts)
        val before2 = vm2.layers.value
        vm2.clearViaStore()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(before2, vm2.layers.value)
    }

    @Test fun series_of_failures_shows_unavailable() = runTest {
        fake.failWhen = { true }
        val vm = vm(files = mapOf(7L to srt(TRANSLATION_MAX_FAILURES)))
        try {
            vm.remoteA(en)
            vm.select(Layer.B, LayerSource.Translated(Layer.A, TranslateLang.EN))
            advanceUntilIdle()
            assertEquals("Перевод недоступен", vm.layer(Layer.B).note)
        } finally { vm.clearViaStore() }
    }

    @Test fun starts_at_position_and_seeks_on_jump() = runTest {
        val gate = CompletableDeferred<Unit>()
        fake.onCall = { if (it == "c60") gate.await() }
        val vm = vm(files = mapOf(7L to srt(100)))
        try {
            vm.remoteA(en)
            runCurrent()
            vm.textAt(Layer.A, 60_000)
            vm.select(Layer.B, LayerSource.Translated(Layer.A, TranslateLang.EN))
            runCurrent()
            assertEquals(listOf("c60"), fake.texts)
            vm.textAt(Layer.B, 62_000)
            vm.textAt(Layer.B, 10_000)
            gate.complete(Unit)
            advanceUntilIdle()
            // The seek drops the rest of the first batch right after c60.
            assertEquals((listOf(60) + (10 until 60) + (61 until 100) + (0 until 10)).map { "c$it" }, fake.texts)
            assertEquals(100, fake.calls.size)
        } finally { vm.clearViaStore() }
    }

    @Test fun small_position_steps_do_not_reorder() = runTest {
        val gate = CompletableDeferred<Unit>()
        fake.onCall = { if (it == "c60") gate.await() }
        val vm = vm(files = mapOf(7L to srt(100)))
        try {
            vm.remoteA(en)
            runCurrent()
            vm.textAt(Layer.A, 60_000)
            vm.select(Layer.B, LayerSource.Translated(Layer.A, TranslateLang.EN))
            runCurrent()
            vm.textAt(Layer.B, 64_000)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(((60 until 100) + (0 until 60)).map { "c$it" }, fake.texts)
        } finally { vm.clearViaStore() }
    }

    @Test fun small_backward_jump_seeks() = runTest {
        val gate = CompletableDeferred<Unit>()
        fake.onCall = { if (it == "c60") gate.await() }
        val vm = vm(files = mapOf(7L to srt(100)))
        try {
            vm.remoteA(en)
            runCurrent()
            vm.textAt(Layer.A, 60_000)
            vm.select(Layer.B, LayerSource.Translated(Layer.A, TranslateLang.EN))
            runCurrent()
            vm.textAt(Layer.B, 58_000)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals((listOf(60, 58, 59) + (61 until 100) + (0 until 58)).map { "c$it" }, fake.texts)
        } finally { vm.clearViaStore() }
    }

    @Test fun backward_jitter_does_not_reorder() = runTest {
        val gate = CompletableDeferred<Unit>()
        fake.onCall = { if (it == "c60") gate.await() }
        val vm = vm(files = mapOf(7L to srt(100)))
        try {
            vm.remoteA(en)
            runCurrent()
            vm.textAt(Layer.A, 60_000)
            vm.select(Layer.B, LayerSource.Translated(Layer.A, TranslateLang.EN))
            runCurrent()
            // A seek to 59.5 s would start with c59, which is still on screen there.
            vm.textAt(Layer.B, 59_500)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(((60 until 100) + (0 until 60)).map { "c$it" }, fake.texts)
        } finally { vm.clearViaStore() }
    }

    @Test fun embedded_translation_follows_cues() = runTest {
        val gate = CompletableDeferred<Unit>()
        fake.onCall = { if (it == "One") gate.await() }
        val vm = vm()
        try {
            val track = TextTrack(1, 0, "en", null, true)
            vm.onTextTracks(listOf(track))
            vm.select(Layer.A, LayerSource.Embedded(track))
            vm.onEmbeddedText(Layer.A, "Hello")
            vm.select(Layer.B, LayerSource.Translated(Layer.A, TranslateLang.EN))
            advanceUntilIdle()
            assertNull(vm.layer(Layer.B).note)
            // The cue on screen before translation was ready is translated too.
            assertEquals("[Hello]", vm.translatedEmbedded.value[Layer.B])
            assertNull(vm.textAt(Layer.B, 1_500))

            vm.onEmbeddedText(Layer.A, "One")
            runCurrent()
            assertNull(vm.translatedEmbedded.value[Layer.B])
            vm.onEmbeddedText(Layer.A, "Two")
            runCurrent()
            assertEquals("[Two]", vm.translatedEmbedded.value[Layer.B])
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals("[Two]", vm.translatedEmbedded.value[Layer.B])

            vm.onEmbeddedText(Layer.A, null)
            runCurrent()
            assertNull(vm.translatedEmbedded.value[Layer.B])

            vm.select(Layer.A, LayerSource.Off)
            vm.onEmbeddedText(Layer.A, "Hello")
            advanceUntilIdle()
            assertNull(vm.translatedEmbedded.value[Layer.B])
            assertEquals("Нет субтитров для перевода", vm.layer(Layer.B).note)
        } finally { vm.clearViaStore() }
    }

    @Test fun overlay_shows_translating_only_without_ready_cue_at_position() = runTest {
        val gate = CompletableDeferred<Unit>()
        // The first batch (c0..c19) is published; c20 is held.
        fake.onCall = { if (it == "c20") gate.await() }
        val vm = vm(files = mapOf(7L to srt(25)))
        try {
            vm.remoteA(en)
            vm.select(Layer.B, LayerSource.Translated(Layer.A, TranslateLang.EN))
            advanceUntilIdle()
            assertEquals("[c0]", vm.overlayText(Layer.B, 500))
            // Between cues there is nothing to translate.
            assertNull(vm.overlayText(Layer.B, 950))
            assertEquals("Перевод…", vm.overlayText(Layer.B, 20_500))
            // The source layer offset decides the current cue.
            vm.shiftOffset(Layer.A, 500)
            assertEquals("[c19]", vm.overlayText(Layer.B, 20_200))
            assertEquals("Перевод…", vm.overlayText(Layer.B, 20_600))
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals("[c20]", vm.overlayText(Layer.B, 20_600))
        } finally { vm.clearViaStore() }
    }

    @Test fun overlay_for_embedded_translation_is_empty_until_translated() = runTest {
        val gate = CompletableDeferred<Unit>()
        fake.onCall = { gate.await() }
        val vm = vm()
        try {
            val track = TextTrack(1, 0, "en", null, true)
            vm.onTextTracks(listOf(track))
            vm.select(Layer.A, LayerSource.Embedded(track))
            vm.select(Layer.B, LayerSource.Translated(Layer.A, TranslateLang.EN))
            advanceUntilIdle()
            vm.onEmbeddedText(Layer.A, "Hello")
            runCurrent()
            assertEquals("Hello", vm.overlayText(Layer.A, 0, "Hello"))
            assertNull(vm.overlayText(Layer.B, 0))
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals("[Hello]", vm.overlayText(Layer.B, 0))
        } finally { vm.clearViaStore() }
    }

    @Test fun embedded_translation_runs_on_translation_dispatcher() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val seen = mutableListOf<Any?>()
        fake.onCall = { seen += currentCoroutineContext()[ContinuationInterceptor] }
        val vm = vm(dispatcher = dispatcher)
        try {
            val track = TextTrack(1, 0, "en", null, true)
            vm.onTextTracks(listOf(track))
            vm.select(Layer.A, LayerSource.Embedded(track))
            vm.select(Layer.B, LayerSource.Translated(Layer.A, TranslateLang.EN))
            advanceUntilIdle()
            vm.onEmbeddedText(Layer.A, "Hello")
            advanceUntilIdle()
            assertEquals("[Hello]", vm.translatedEmbedded.value[Layer.B])
            assertEquals(listOf<Any?>(dispatcher), seen)
        } finally { vm.clearViaStore() }
    }

    @Test fun new_embedded_cue_cancels_previous_translation() = runTest {
        val gate = CompletableDeferred<Unit>()
        var cancelled = 0
        fake.onCall = {
            if (it == "One") try { gate.await() } catch (e: CancellationException) {
                cancelled++
                throw e
            }
        }
        val vm = vm()
        try {
            val track = TextTrack(1, 0, "en", null, true)
            vm.onTextTracks(listOf(track))
            vm.select(Layer.A, LayerSource.Embedded(track))
            vm.select(Layer.B, LayerSource.Translated(Layer.A, TranslateLang.EN))
            advanceUntilIdle()
            vm.onEmbeddedText(Layer.A, "One")
            runCurrent()
            assertEquals(listOf("One"), fake.texts)
            vm.onEmbeddedText(Layer.A, "Two")
            runCurrent()
            assertEquals(1, cancelled)
            advanceUntilIdle()
            assertEquals("[Two]", vm.translatedEmbedded.value[Layer.B])
            // A cleared cue cancels the in-flight call too.
            fake.onCall = { if (it == "Three") try { gate.await() } catch (e: CancellationException) {
                cancelled++
                throw e
            } }
            vm.onEmbeddedText(Layer.A, "Three")
            runCurrent()
            vm.onEmbeddedText(Layer.A, null)
            runCurrent()
            assertEquals(2, cancelled)
            assertNull(vm.translatedEmbedded.value[Layer.B])
        } finally { vm.clearViaStore() }
    }
}
