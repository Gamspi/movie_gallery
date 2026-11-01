package dev.gamspi.videogallery.subs

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

sealed interface SubsResult<out T> {
    data class Ok<T>(val value: T, val languageNotes: Map<String, String> = emptyMap()) : SubsResult<T>
    data object NoKey : SubsResult<Nothing>
    data object BadKey : SubsResult<Nothing>
    data object LimitReached : SubsResult<Nothing>
    data class Failed(val message: String) : SubsResult<Nothing>
}

data class RemoteSubtitle(
    val fileId: Long,
    val language: String,
    val release: String,
    val hashMatch: Boolean,
    val downloads: Int,
)

interface SubtitleSettingsSource {
    val openSubtitlesKey: String?
    val openSubtitlesUser: String?
    val openSubtitlesPassword: String?
}

private const val USER_AGENT = "VideoGallery v1.0"
private val JSON = Json { ignoreUnknownKeys = true }

/** [baseUrl] is the REST root including the version path, e.g. https://api.opensubtitles.com/api/v1/ */
class OpenSubtitlesClient(
    baseUrl: String,
    private val settings: SubtitleSettingsSource,
    private val http: OkHttpClient,
) : SubtitleClient {
    private val api: OpenSubtitlesApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(
            http.newBuilder().addInterceptor { chain ->
                // key is read per request so a changed key applies without restart
                val key = settings.openSubtitlesKey.orEmpty()
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Api-Key", key)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .build(),
                )
            }.build(),
        )
        .addConverterFactory(JSON.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(OpenSubtitlesApi::class.java)

    private val tokenLock = Mutex()
    private var token: String? = null
    private var tokenOwner: Pair<String, String>? = null

    override suspend fun search(
        hash: String?,
        imdbId: String?,
        languages: List<String>,
    ): SubsResult<List<RemoteSubtitle>> {
        val imdb = imdbId?.removePrefix("tt")?.trimStart('0')?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        val fileHash = hash?.takeIf { it.isNotBlank() }
        val requested = languages.map { it.trim().lowercase(java.util.Locale.ROOT) }
            .filter { subtitleLanguage(it) != null }.distinct().sorted()
        if ((fileHash == null && imdb == null) || requested.isEmpty()) return SubsResult.Ok(emptyList())
        if (fileHash == null) return searchRequest(null, imdb, requested)

        val exactResult = searchRequest(fileHash, null, requested)
        if (exactResult !is SubsResult.Ok) return exactResult
        val exact = exactResult.value.filter { it.hashMatch }
        val missing = requested.filter { language -> exact.none { sameLanguage(language, it.language) } }
        if (missing.isEmpty() || imdb == null) return SubsResult.Ok(exact)

        currentCoroutineContext().ensureActive()
        return when (val fallback = searchRequest(null, imdb, missing)) {
            is SubsResult.Ok -> SubsResult.Ok(sortSubtitles(exact + fallback.value))
            // Keep the usable language and report why the other language could not be fetched.
            else -> if (exact.isNotEmpty()) {
                SubsResult.Ok(exact, missing.associateWith { searchFailureNote(fallback) })
            } else fallback
        }
    }

    private fun searchFailureNote(result: SubsResult<*>): String = when (result) {
        SubsResult.NoKey -> "нужен ключ OpenSubtitles"
        SubsResult.BadKey -> "ключ OpenSubtitles неверный"
        SubsResult.LimitReached -> "лимит OpenSubtitles на сегодня"
        else -> "ошибка загрузки"
    }

    private suspend fun searchRequest(
        hash: String?,
        imdb: String?,
        languages: List<String>,
    ): SubsResult<List<RemoteSubtitle>> = guarded {
        val response = api.search(hash, imdb, languages.joinToString(","), if (hash != null) "only" else null)
        val list = response.data.mapNotNull { item ->
            val a = item.attributes
            if (languages.none { sameLanguage(it, a.language) }) return@mapNotNull null
            val file = a.files.firstOrNull() ?: return@mapNotNull null
            RemoteSubtitle(file.fileId, a.language.orEmpty(), a.release.orEmpty(), a.moviehashMatch, a.downloadCount)
        }
        SubsResult.Ok(sortSubtitles(list))
    }

    private fun sortSubtitles(list: List<RemoteSubtitle>): List<RemoteSubtitle> = list
        .sortedWith(compareByDescending<RemoteSubtitle> { it.hashMatch }.thenByDescending { it.downloads })
        .distinctBy { it.fileId }

    override suspend fun download(fileId: Long): SubsResult<ByteArray> = guarded {
        val auth = loginToken()?.let { "Bearer $it" }
        val response = try {
            api.download(DownloadRequestDto(fileId), auth)
        } catch (e: HttpException) {
            if (e.code() != 401 || auth == null) throw e
            // token expired: drop it, log in once more, retry once
            invalidateToken()
            api.download(DownloadRequestDto(fileId), loginToken()?.let { "Bearer $it" })
        }
        val link = response.link
        // remaining counts downloads left after this one, so a link in hand is still usable
        if (link.isNullOrBlank()) {
            return@guarded if ((response.remaining ?: 0) <= 0) SubsResult.LimitReached
            else SubsResult.Failed("no download link")
        }
        SubsResult.Ok(fetchBytes(link))
    }

    private suspend fun fetchBytes(link: String): ByteArray {
        val request = Request.Builder().url(link).header("User-Agent", USER_AGENT).build()
        return http.newCall(request).awaitBody { response ->
            if (!response.isSuccessful) throw IOException("subtitle file HTTP ${response.code}")
            response.body.bytes()
        }
    }

    /** Cached bearer token, or null when no credentials are configured. */
    private suspend fun loginToken(): String? {
        val user = settings.openSubtitlesUser
        val password = settings.openSubtitlesPassword
        if (user.isNullOrBlank() || password.isNullOrBlank()) return null
        return tokenLock.withLock {
            val owner = user to password
            if (token == null || tokenOwner != owner) {
                token = try {
                    api.login(LoginRequestDto(user, password)).token
                } catch (e: HttpException) {
                    if (e.code() == 401 || e.code() == 403) throw LoginRejectedException()
                    throw e
                }
                tokenOwner = owner
            }
            token
        }
    }

    private suspend fun invalidateToken() = tokenLock.withLock { token = null }

    private suspend fun <T> guarded(block: suspend () -> SubsResult<T>): SubsResult<T> {
        val key = settings.openSubtitlesKey
        if (key.isNullOrBlank()) return SubsResult.NoKey
        if (!key.all { it in '!'..'~' }) return SubsResult.BadKey
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: LoginRejectedException) {
            SubsResult.Failed("неверный логин или пароль OpenSubtitles")
        } catch (e: HttpException) {
            when (e.code()) {
                401, 403 -> SubsResult.BadKey
                406, 429 -> SubsResult.LimitReached
                else -> SubsResult.Failed("HTTP ${e.code()}")
            }
        } catch (e: IOException) {
            SubsResult.Failed(e.message ?: "network error")
        } catch (e: SerializationException) {
            SubsResult.Failed("bad response")
        } catch (e: IllegalArgumentException) {
            SubsResult.Failed("bad response")
        }
    }
}

private class LoginRejectedException : Exception("OpenSubtitles login rejected")
