package dev.gamspi.videogallery.net

import java.net.ProtocolException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Cloudflare Worker endpoint; [key] goes only into the X-VG-Key header. */
class ProxyConfig(val baseUrl: String, val key: String?) {
    override fun equals(other: Any?): Boolean =
        other is ProxyConfig && other.baseUrl == baseUrl && other.key == key

    override fun hashCode(): Int = baseUrl.hashCode() * 31 + key.hashCode()

    // Never expose the key through logs or exception messages.
    override fun toString(): String = "ProxyConfig(baseUrl=$baseUrl, key=${if (key.isNullOrEmpty()) "none" else "***"})"
}

/**
 * Rewrites requests to blocked hosts onto the worker: `<base>/<host><path>?<query>`.
 * Install with [proxiedBy]: the network part hides worker redirects from OkHttp's own follow-up
 * logic (which runs after application interceptors and would go direct), and this interceptor
 * re-proxies each hop itself.
 */
class ProxyInterceptor(private val config: () -> ProxyConfig?) : Interceptor {
    /** Network-side guard; must be installed on the same client. */
    val redirectGuard: Interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val location = response.header(LOCATION)
        if (chain.request().tag(Proxied::class.java) != null && response.code in REDIRECT_CODES && location != null) {
            response.newBuilder().removeHeader(LOCATION).header(HIDDEN_LOCATION, location).build()
        } else {
            response
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        // Snapshot once per call so a toggle mid-chain cannot mix modes.
        val cfg = config()
        var hops = 0
        while (true) {
            val proxied = cfg?.let { rewrite(request, it) } ?: return chain.proceed(request)
            val response = chain.proceed(proxied)
            val restored = response.restoreFor(request)
            val next = followUp(chain, request, restored) ?: return restored
            restored.close()
            if (++hops > MAX_FOLLOW_UPS) throw ProtocolException("Too many follow-up requests: $hops")
            request = next
        }
    }

    /** Returns the redirect target request if OkHttp itself would follow it. */
    private fun followUp(chain: Interceptor.Chain, request: Request, response: Response): Request? {
        if (response.code !in REDIRECT_CODES || !chain.followRedirects) return null
        val target = response.header(LOCATION)?.toHttpUrlOrNull() ?: return null
        if (target.scheme != request.url.scheme && !chain.followSslRedirects) return null
        val builder = request.newBuilder().url(target)
        val method = request.method
        if (method != "GET" && method != "HEAD") {
            if (response.code == 307 || response.code == 308) {
                builder.method(method, request.body)
            } else {
                builder.method("GET", null)
                    .removeHeader("Transfer-Encoding")
                    .removeHeader("Content-Length")
                    .removeHeader("Content-Type")
            }
        }
        if (target.host != request.url.host) builder.removeHeader("Authorization")
        return builder.build()
    }

    /** Worker response as seen from the upstream: original request, absolute upstream Location. */
    private fun Response.restoreFor(original: Request): Response {
        val builder = newBuilder().request(original)
        val raw = header(HIDDEN_LOCATION) ?: header(LOCATION)
        if (raw != null) {
            builder.removeHeader(HIDDEN_LOCATION)
                .header(LOCATION, original.url.resolve(raw)?.toString() ?: raw)
        }
        return builder.build()
    }

    private class Proxied

    companion object {
        private const val KEY_HEADER = "X-VG-Key"
        private const val LOCATION = "Location"
        private const val HIDDEN_LOCATION = "X-VG-Upstream-Location"
        private const val MAX_FOLLOW_UPS = 10
        private val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
        private val PROXIED_HOSTS = listOf("api.themoviedb.org", "image.tmdb.org", "rutor.info", "rutracker.org")
        private val PROXIED = Proxied()

        internal fun isProxiedHost(host: String): Boolean =
            PROXIED_HOSTS.any { host == it || host.endsWith(".$it") }

        /** Worker URL for [url], or null when the request must go direct. */
        internal fun proxiedUrl(url: HttpUrl, baseUrl: String): HttpUrl? {
            if (url.scheme != "http" && url.scheme != "https") return null
            if (url.port != HttpUrl.defaultPort(url.scheme) || !isProxiedHost(url.host)) return null
            val query = url.encodedQuery?.let { "?$it" }.orEmpty()
            return "${baseUrl.trimEnd('/')}/${url.host}${url.encodedPath}$query".toHttpUrlOrNull()
        }

        internal fun rewrite(request: Request, config: ProxyConfig): Request? {
            val target = proxiedUrl(request.url, config.baseUrl) ?: return null
            return request.newBuilder()
                .url(target)
                .tag(Proxied::class.java, PROXIED)
                .apply { if (!config.key.isNullOrEmpty()) header(KEY_HEADER, config.key) }
                .build()
        }
    }
}

/** Installs both halves of [proxy]; add before other application interceptors. */
fun OkHttpClient.Builder.proxiedBy(proxy: ProxyInterceptor): OkHttpClient.Builder =
    addInterceptor(proxy).addNetworkInterceptor(proxy.redirectGuard)
