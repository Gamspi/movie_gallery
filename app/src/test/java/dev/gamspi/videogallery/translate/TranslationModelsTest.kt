package dev.gamspi.videogallery.translate

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationModelsTest {
    private class FakeBackend : ModelBackend {
        val downloaded = mutableSetOf<TranslateLang>()
        val downloadCalls = mutableListOf<TranslateLang>()
        var gate: CompletableDeferred<Unit>? = null
        var failure: Exception? = null

        override suspend fun isDownloaded(lang: TranslateLang) = lang in downloaded
        override suspend fun download(lang: TranslateLang) {
            downloadCalls += lang
            gate?.await()
            failure?.let { throw it }
            downloaded += lang
        }
        override suspend fun delete(lang: TranslateLang) { downloaded -= lang }
    }

    private fun TestScope.models(backend: FakeBackend) =
        TranslationModelsImpl(backend, CoroutineScope(StandardTestDispatcher(testScheduler)))

    @Test fun parallel_ensure_downloads_once() = runTest {
        val backend = FakeBackend().apply { gate = CompletableDeferred() }
        val models = models(backend)
        val a = async { models.ensureDownloaded() }
        val b = async { models.ensureDownloaded() }
        runCurrent()
        assertEquals(ModelState.Downloading(null), models.state.value)
        backend.gate!!.complete(Unit)
        advanceUntilIdle()
        a.await(); b.await()
        assertEquals(TranslateLang.entries, backend.downloadCalls)
        assertEquals(ModelState.Ready, models.state.value)
    }

    @Test fun network_error_fails_then_retry_downloads_again() = runTest {
        val backend = FakeBackend().apply { failure = IOException("offline") }
        val models = models(backend)
        val first = async { models.ensureDownloaded() }
        advanceUntilIdle()
        first.await()
        assertEquals(ModelState.Failed(ModelFailure.NoNetwork), models.state.value)
        backend.failure = null
        val second = async { models.ensureDownloaded() }
        advanceUntilIdle()
        second.await()
        assertEquals(ModelState.Ready, models.state.value)
        assertTrue(backend.downloadCalls.size >= 2)
    }

    @Test fun classified_failure_is_kept() = runTest {
        val backend = FakeBackend().apply { failure = ModelFailureException(ModelFailure.NoSpace) }
        val models = models(backend)
        val call = async { models.ensureDownloaded() }
        advanceUntilIdle()
        call.await()
        assertEquals(ModelState.Failed(ModelFailure.NoSpace), models.state.value)
    }

    @Test fun delete_resets_to_not_downloaded() = runTest {
        val backend = FakeBackend()
        val models = models(backend)
        val call = async { models.ensureDownloaded() }
        advanceUntilIdle()
        call.await()
        assertEquals(ModelState.Ready, models.state.value)
        val del = async { models.delete() }
        advanceUntilIdle()
        del.await()
        assertEquals(ModelState.NotDownloaded, models.state.value)
        assertTrue(backend.downloaded.isEmpty())
    }

    @Test fun caller_cancel_does_not_cancel_shared_download() = runTest {
        val backend = FakeBackend().apply { gate = CompletableDeferred() }
        val models = models(backend)
        val caller = async { models.ensureDownloaded() }
        runCurrent()
        caller.cancel()
        runCurrent()
        backend.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(ModelState.Ready, models.state.value)
    }

    @Test fun existing_models_give_ready_state() = runTest {
        val backend = FakeBackend().apply { downloaded += TranslateLang.entries }
        val models = models(backend)
        advanceUntilIdle()
        assertEquals(ModelState.Ready, models.state.value)
    }

    @Test fun instant_download_is_not_overwritten_by_downloading_state() = runTest {
        val backend = FakeBackend()
        // Unconfined: the job body runs as soon as it is started, like a fast real thread.
        val models = TranslationModelsImpl(backend, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        models.ensureDownloaded()
        assertEquals(ModelState.Ready, models.state.value)
    }

    @Test fun instant_failure_is_not_overwritten_by_downloading_state() = runTest {
        val backend = FakeBackend().apply { failure = IOException("offline") }
        val models = TranslationModelsImpl(backend, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        models.ensureDownloaded()
        assertEquals(ModelState.Failed(ModelFailure.NoNetwork), models.state.value)
    }

    @Test fun translate_lang_mapping() {
        assertEquals(TranslateLang.EN, translateLang("eng"))
        assertEquals(TranslateLang.RU, translateLang("ru"))
        assertNull(translateLang("und"))
        assertNull(translateLang(null))
        assertEquals(TranslateLang.RU, TranslateLang.EN.other())
    }

    @Test fun failure_messages_are_russian() {
        assertEquals("Нет модели перевода: нужна сеть для загрузки", ModelFailure.NoNetwork.message())
    }
}
