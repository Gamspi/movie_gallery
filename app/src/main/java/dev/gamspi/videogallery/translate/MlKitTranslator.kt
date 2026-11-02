package dev.gamspi.videogallery.translate

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Awaits a Task without blocking. These ML Kit calls take no cancellation token, so
 * cancellation only releases the caller; late results are ignored.
 */
internal suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

class MlKitTranslator : SubtitleTranslator {
    private val translators = HashMap<Pair<TranslateLang, TranslateLang>, Translator>()

    override val modelVersion: String = "mlkit-translate-17.0.3"

    override suspend fun translate(text: String, from: TranslateLang, to: TranslateLang): String =
        translatorFor(from, to).translate(text).awaitTask()

    @Synchronized
    private fun translatorFor(from: TranslateLang, to: TranslateLang): Translator =
        translators.getOrPut(from to to) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(from.tag)
                    .setTargetLanguage(to.tag)
                    .build(),
            )
        }

    /** Closes cached translators; called before model deletion. */
    @Synchronized
    fun closeAll() {
        translators.values.forEach { it.close() }
        translators.clear()
    }
}

class MlKitModelBackend(
    private val onDelete: () -> Unit,
    private val manager: RemoteModelManager = RemoteModelManager.getInstance(),
) : ModelBackend {
    private fun model(lang: TranslateLang) = TranslateRemoteModel.Builder(lang.tag).build()

    override suspend fun isDownloaded(lang: TranslateLang): Boolean =
        manager.isModelDownloaded(model(lang)).awaitTask()

    override suspend fun download(lang: TranslateLang) {
        try {
            manager.download(model(lang), DownloadConditions.Builder().build()).awaitTask()
        } catch (e: MlKitException) {
            throw ModelFailureException(mapCode(e.errorCode), e)
        }
    }

    override suspend fun delete(lang: TranslateLang) {
        onDelete()
        manager.deleteDownloadedModel(model(lang)).awaitTask()
    }

    private fun mapCode(code: Int): ModelFailure = when (code) {
        MlKitException.NETWORK_ISSUE -> ModelFailure.NoNetwork
        MlKitException.NOT_ENOUGH_SPACE -> ModelFailure.NoSpace
        else -> ModelFailure.Other
    }
}
