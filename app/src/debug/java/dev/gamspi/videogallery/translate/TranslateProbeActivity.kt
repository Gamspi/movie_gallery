package dev.gamspi.videogallery.translate

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Gate probe: adb --es command download|status|translate|delete. Logs tag TranslateProbe, no secrets. */
class TranslateProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val manager = RemoteModelManager.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        run(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        run(intent)
    }

    private fun run(command: Intent) {
        val name = command.getStringExtra("command")
        scope.launch {
            try {
                when (name) {
                    "download" -> download()
                    "status" -> status()
                    "translate" -> translateAll()
                    "delete" -> delete()
                    else -> Log.e(TAG, "unknown command")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "$name failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun model(code: String) = TranslateRemoteModel.Builder(code).build()

    private suspend fun download() {
        for (code in LANGS) {
            val start = SystemClock.elapsedRealtime()
            manager.download(model(code), DownloadConditions.Builder().build()).await()
            Log.d(TAG, "downloaded $code in ${SystemClock.elapsedRealtime() - start} ms")
        }
        status()
    }

    private suspend fun status() {
        val models = manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
        Log.d(TAG, "downloaded models: ${models.map { it.language }.sorted()}")
    }

    private suspend fun delete() {
        for (code in LANGS) {
            manager.deleteDownloadedModel(model(code)).await()
            Log.d(TAG, "deleted $code")
        }
        status()
    }

    private suspend fun translateAll() {
        translateDirection(TranslateLanguage.ENGLISH, TranslateLanguage.RUSSIAN, EN)
        translateDirection(TranslateLanguage.RUSSIAN, TranslateLanguage.ENGLISH, RU)
    }

    private suspend fun translateDirection(from: String, to: String, phrases: List<String>) {
        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build(),
        )
        try {
            // No download call here: models must already be on the device (offline check).
            val initStart = SystemClock.elapsedRealtime()
            translator.translate(phrases[0]).await()
            Log.d(TAG, "$from->$to warmup ${SystemClock.elapsedRealtime() - initStart} ms")
            var total = 0L
            phrases.forEachIndexed { index, phrase ->
                val start = SystemClock.elapsedRealtime()
                val result = translator.translate(phrase).await()
                val elapsed = SystemClock.elapsedRealtime() - start
                total += elapsed
                Log.d(TAG, "$from->$to #$index $elapsed ms len=${phrase.length} outLen=${result.length}")
            }
            Log.d(TAG, "$from->$to average ${total / phrases.size} ms over ${phrases.size}")
        } finally {
            translator.close()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    private companion object {
        const val TAG = "TranslateProbe"
        val LANGS = listOf(TranslateLanguage.ENGLISH, TranslateLanguage.RUSSIAN)
        val EN = listOf(
            "Where are you going?", "I don't know what happened.", "Get out of here, now!",
            "We have to find the others before dark.", "Did you hear that noise?",
            "It's not what it looks like.", "Please, give me one more chance.",
            "The train leaves at eight o'clock.", "I've been waiting for you all night.",
            "What do you want from me?", "Stay behind me and keep quiet.",
            "He said he would call, but he never did.", "That's the best idea I've heard today.",
            "Nobody knows where the money went.", "Can you tell me the way to the station?",
            "I told you this would happen.", "Are you out of your mind?",
            "Let's talk about it in the morning.", "She smiled and closed the door.",
            "There is no time to explain.",
        )
        val RU = listOf(
            "Куда ты идёшь?", "Я не знаю, что произошло.", "Убирайся отсюда, сейчас же!",
            "Нам нужно найти остальных до темноты.", "Ты слышал этот шум?",
            "Это не то, чем кажется.", "Пожалуйста, дай мне ещё один шанс.",
            "Поезд отправляется в восемь часов.", "Я ждал тебя всю ночь.",
            "Что ты от меня хочешь?", "Держись за мной и молчи.",
            "Он сказал, что позвонит, но так и не позвонил.", "Это лучшая идея, что я слышал сегодня.",
            "Никто не знает, куда делись деньги.", "Подскажите, как пройти к вокзалу?",
            "Я же говорил, что так будет.", "Ты с ума сошёл?",
            "Давай поговорим об этом утром.", "Она улыбнулась и закрыла дверь.",
            "Нет времени объяснять.",
        )
    }
}
