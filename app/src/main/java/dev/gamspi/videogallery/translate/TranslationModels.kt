package dev.gamspi.videogallery.translate

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ModelFailure { NoNetwork, NoSpace, Other }

sealed interface ModelState {
    data object NotDownloaded : ModelState

    /** progress is null: ML Kit has no progress callback. */
    data class Downloading(val progress: Int?) : ModelState
    data object Ready : ModelState
    data class Failed(val reason: ModelFailure) : ModelState
}

fun ModelFailure.message(): String = when (this) {
    ModelFailure.NoNetwork -> "Нет модели перевода: нужна сеть для загрузки"
    ModelFailure.NoSpace -> "Недостаточно места для модели перевода"
    ModelFailure.Other -> "Не удалось скачать модель перевода"
}

/** Combined state of both language models; Ready only when both are on the device. */
interface TranslationModels {
    val state: StateFlow<ModelState>
    suspend fun ensureDownloaded()
    suspend fun delete()
}

/** Failure with an already classified reason; thrown by backends. */
class ModelFailureException(val reason: ModelFailure, cause: Throwable? = null) : Exception(reason.name, cause)

/** Thin layer over RemoteModelManager so the manager logic is testable. */
interface ModelBackend {
    suspend fun isDownloaded(lang: TranslateLang): Boolean
    suspend fun download(lang: TranslateLang)
    suspend fun delete(lang: TranslateLang)
}

class TranslationModelsImpl(
    private val backend: ModelBackend,
    private val scope: CoroutineScope,
) : TranslationModels {
    private val _state = MutableStateFlow<ModelState>(ModelState.NotDownloaded)
    override val state: StateFlow<ModelState> = _state.asStateFlow()
    private val lock = Any()
    private var job: Job? = null

    init {
        // Pick up models that are already on the device.
        scope.launch { refresh() }
    }

    private suspend fun refresh() {
        val ready = try {
            TranslateLang.entries.all { backend.isDownloaded(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        synchronized(lock) {
            if (job?.isActive != true && ready && _state.value == ModelState.NotDownloaded) {
                _state.value = ModelState.Ready
            }
        }
    }

    override suspend fun ensureDownloaded() {
        val running = synchronized(lock) {
            job?.takeIf { it.isActive } ?: scope.launch { download() }.also {
                job = it
                _state.value = ModelState.Downloading(null)
            }
        }
        // Caller cancellation only stops waiting; the shared download continues.
        running.join()
    }

    private suspend fun download() {
        try {
            for (lang in TranslateLang.entries) {
                if (!backend.isDownloaded(lang)) backend.download(lang)
            }
            _state.value = ModelState.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = ModelState.Failed(classify(e))
        }
    }

    private fun classify(e: Exception): ModelFailure = when (e) {
        is ModelFailureException -> e.reason
        is IOException -> ModelFailure.NoNetwork
        else -> ModelFailure.Other
    }

    override suspend fun delete() {
        val running = synchronized(lock) { job }
        running?.cancel()
        running?.join()
        for (lang in TranslateLang.entries) backend.delete(lang)
        _state.value = ModelState.NotDownloaded
    }
}
