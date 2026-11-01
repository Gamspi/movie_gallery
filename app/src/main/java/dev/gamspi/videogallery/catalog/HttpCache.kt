package dev.gamspi.videogallery.catalog

import java.io.File
import java.security.MessageDigest
import okhttp3.Cache
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

private const val IDENTITY_HEADER = "X-VideoGallery-Catalog-Identity"
private const val KEY_HEADER = "X-API-KEY"

private data class CatalogCredential(val key: String, val inHeader: Boolean, val origin: HttpUrl)

/** One disk cache shared by clients built with newBuilder(). Credentials never enter its metadata. */
fun cachingClient(cacheDir: File, maxBytes: Long = 20L * 1024 * 1024): OkHttpClient = OkHttpClient.Builder()
    .cache(Cache(File(cacheDir, "http"), maxBytes))
    .addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code != 200) {
            // Some error responses are cacheable by default or advertise an unsafe max-age.
            response.newBuilder().header("Cache-Control", "no-store").build()
        } else {
            val detail = Regex("/(?:3/movie|api/v2\\.2/films|v1\\.[45]/movie)/[0-9]+/?")
                .matches(chain.request().url.encodedPath)
            val vary = response.headers.values("Vary").flatMap { it.split(',') }
                .map { it.trim() }.filter { it.isNotEmpty() && !it.equals(KEY_HEADER, true) }
                .plus(IDENTITY_HEADER).distinctBy { it.lowercase() }.joinToString(", ")
            response.newBuilder()
                .header("Cache-Control", "public, max-age=${if (detail) 86400 else 3600}")
                .header("Vary", vary)
                .removeHeader("Pragma")
                .build()
        }
    }
    .build()

/** Capture a per-request key before the cache; inject it only on the network side of the cache. */
fun OkHttpClient.withCatalogCredentials(source: String): OkHttpClient = newBuilder()
    // Canonical API URLs need no redirects; Location must not reintroduce credentials before cache.
    .followRedirects(false)
    .followSslRedirects(false)
    .addInterceptor { chain ->
        val incoming = chain.request()
        val headerKey = incoming.header(KEY_HEADER)
        val key = headerKey ?: incoming.url.queryParameter("api_key")
        val safeUrl = incoming.url.newBuilder().removeAllQueryParameters("api_key").build()
        val identity = MessageDigest.getInstance("SHA-256")
            .digest((source + "\u0000" + key.orEmpty()).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val logical = incoming.newBuilder().url(safeUrl)
            .removeHeader(KEY_HEADER)
            .header(IDENTITY_HEADER, identity)
            .tag(CatalogCredential::class.java, key?.let { CatalogCredential(it, headerKey != null, safeUrl) })
            .build()
        chain.proceed(logical)
    }
    .addNetworkInterceptor { chain ->
        val logical = chain.request()
        val credential = logical.tag(CatalogCredential::class.java)
        val wire = logical.newBuilder().apply {
            if (credential != null && credential.origin.sameOrigin(logical.url)) {
                if (credential.inHeader) header(KEY_HEADER, credential.key)
                else url(logical.url.newBuilder().addQueryParameter("api_key", credential.key).build())
            }
        }.build()
        // Cache.put uses response.request for URL and Vary metadata; restore the keyless request.
        chain.proceed(wire).newBuilder().request(logical).build()
    }
    .build()

private fun HttpUrl.sameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port
