package dev.gamspi.videogallery.torrents.rutracker

import dev.gamspi.videogallery.torrents.ProviderUnavailableException
import dev.gamspi.videogallery.torrents.UnavailableReason
import dev.gamspi.videogallery.torrents.await
import java.nio.charset.Charset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal val CP1251: Charset = Charset.forName("windows-1251")

/** Decodes a rutracker page; the site serves windows-1251 unless the header says otherwise. */
internal suspend fun Response.rutrackerText(): String = withContext(Dispatchers.IO) {
    val charset = body.contentType()?.charset() ?: CP1251
    String(body.bytes(), charset)
}

class RutrackerCredentials(val username: String, val password: String) {
    override fun equals(other: Any?): Boolean =
        other is RutrackerCredentials && other.username == username && other.password == password

    override fun hashCode(): Int = username.hashCode() * 31 + password.hashCode()

    // Never expose credentials through logs or exception messages.
    override fun toString(): String = "RutrackerCredentials(***)"

    companion object {
        fun of(username: String?, password: String?): RutrackerCredentials? {
            val u = username?.trim().orEmpty()
            val p = password.orEmpty()
            return if (u.isEmpty() || p.isEmpty()) null else RutrackerCredentials(u, p)
        }
    }
}

/**
 * In-memory rutracker login. The `bb_session` cookie is sent as a manual Cookie header:
 * an OkHttp CookieJar would see the proxy host instead of rutracker.
 */
class RutrackerSession(
    client: OkHttpClient,
    private val baseUrl: () -> String,
    private val credentials: () -> RutrackerCredentials?,
) {
    // Redirects off so the Set-Cookie on login's 302 stays visible.
    private val loginClient = client.newBuilder().followRedirects(false).build()
    private val mutex = Mutex()

    private class State(val owner: Pair<String, RutrackerCredentials>, val cookie: String)

    @Volatile
    private var state: State? = null

    /** Cookie header value; logs in when there is no session for the current account. */
    suspend fun cookie(): String {
        val owner = owner()
        state?.takeIf { it.owner == owner }?.let { return it.cookie }
        return mutex.withLock {
            state?.takeIf { it.owner == owner }?.cookie ?: login(owner)
        }
    }

    /** Logs in again unless another caller already replaced [stale]. */
    suspend fun relogin(stale: String): String {
        val owner = owner()
        return mutex.withLock {
            val current = state?.takeIf { it.owner == owner }
            if (current != null && current.cookie != stale) current.cookie else login(owner)
        }
    }

    private fun owner(): Pair<String, RutrackerCredentials> {
        val creds = credentials() ?: throw ProviderUnavailableException(UnavailableReason.NoCredentials)
        return baseUrl() to creds
    }

    private suspend fun login(owner: Pair<String, RutrackerCredentials>): String {
        state = null
        val (base, creds) = owner
        val body = FormBody.Builder(CP1251)
            .add("login_username", creds.username)
            .add("login_password", creds.password)
            .add("login", "вход")
            .build()
        val request = Request.Builder().url(forumUrl(base, "login.php")).post(body).build()
        val cookie = try {
            loginClient.newCall(request).await().use { resp ->
                val session = resp.headers("Set-Cookie").firstNotNullOfOrNull(::sessionCookie)
                when {
                    session != null -> session
                    resp.code >= 500 || resp.code == 403 || resp.code == 429 ->
                        throw ProviderUnavailableException()
                    isCaptcha(resp.rutrackerText()) ->
                        throw ProviderUnavailableException(UnavailableReason.Captcha)
                    else -> throw ProviderUnavailableException(UnavailableReason.BadCredentials)
                }
            }
        } catch (e: ProviderUnavailableException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ProviderUnavailableException()
        }
        state = State(owner, cookie)
        return cookie
    }

    internal companion object {
        private val captchaRe = Regex("""cap_sid|cap_code_|captcha""", RegexOption.IGNORE_CASE)

        fun isCaptcha(html: String): Boolean = captchaRe.containsMatchIn(html)

        /** `bb_session=<value>` from a Set-Cookie header, ignoring deletions. */
        fun sessionCookie(header: String): String? {
            val pair = header.substringBefore(';').trim()
            if (!pair.startsWith("bb_session=")) return null
            val value = pair.removePrefix("bb_session=")
            return if (value.isBlank() || value == "deleted") null else pair
        }

        fun forumUrl(base: String, page: String): HttpUrl =
            base.toHttpUrl().newBuilder().addPathSegment("forum").addPathSegment(page).build()
    }
}
