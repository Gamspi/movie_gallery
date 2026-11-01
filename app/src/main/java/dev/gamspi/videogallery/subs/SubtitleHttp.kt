package dev.gamspi.videogallery.subs

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** Keeps cancellation attached until the body has been consumed, not just the headers. */
internal suspend fun <T> Call.awaitBody(read: (Response) -> T): T = suspendCancellableCoroutine { cont ->
    val responseInUse = AtomicReference<Response?>()
    cont.invokeOnCancellation {
        cancel()
        responseInUse.get()?.let { runCatching { it.close() } }
    }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            responseInUse.set(response)
            try {
                response.use {
                    if (!cont.isActive) return
                    val result = read(response)
                    cont.resume(result) { _, _, _ -> }
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            } finally {
                responseInUse.compareAndSet(response, null)
            }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
}
