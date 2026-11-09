package dev.gamspi.videogallery.watchlist

import dev.gamspi.videogallery.subs.awaitBody
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

interface ListApi {
    suspend fun push(rows: List<ListRecord>)
    suspend fun changes(since: Long): ListPage
}

data class ListPage(val rows: List<ListRecord>, val next: Long, val more: Boolean)

/** The client is dedicated to the list service and never follows redirects with its key. */
class HttpListApi(
    private val base: HttpUrl,
    private val key: String,
    client: OkHttpClient,
) : ListApi {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    init {
        require(base.isHttps || base.host == "localhost" || base.host == "127.0.0.1")
        require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null)
        require(key.isNotBlank() && key.all { it.code in 33..126 })
    }

    override suspend fun push(rows: List<ListRecord>) = withContext(Dispatchers.IO) {
        require(rows.size <= 500)
        rows.forEach { it.validated() }
        val body = buildJsonObject {
            ListTable.entries.forEach { table -> put(table.wireName(), JsonArray(rows.filter { it.table == table }.map { it.toWire() })) }
        }.toString()
        require(body.toByteArray().size <= 1048576)
        val result = request("batch", body = body)
        val applied = result.getValue("applied").jsonPrimitive.int
        require(applied in 0..rows.size && result.getValue("seq").jsonPrimitive.long >= 0)
    }

    override suspend fun changes(since: Long): ListPage = withContext(Dispatchers.IO) {
        require(since >= 0)
        val result = request("changes", since)
        val next = result.getValue("next").jsonPrimitive.long
        val more = result.getValue("more").jsonPrimitive.boolean
        require(next >= since && (!more || next > since))
        val seqs = mutableSetOf<Long>()
        val rows = ListTable.entries.flatMap { table ->
            result.getValue(table.wireName()).jsonArray.map { element ->
                val row = element.jsonObject
                val seq = row.getValue("seq").jsonPrimitive.long
                require(seq > since && seq <= next && seqs.add(seq))
                row.toRecord(table).validated()
            }
        }
        require(rows.size <= 100 && (rows.isNotEmpty() || next == since))
        ListPage(rows, next, more)
    }

    private suspend fun request(path: String, since: Long? = null, body: String? = null): JsonObject {
        val url = base.newBuilder().addPathSegments("v1/$path").apply {
            if (since != null) { addQueryParameter("since", since.toString()); addQueryParameter("limit", "100") }
        }.build()
        val request = Request.Builder().url(url).header("X-VG-List-Key", key).apply {
            if (body != null) post(body.toRequestBody("application/json".toMediaType()))
        }.build()
        return http.newCall(request).awaitBody { response ->
            if (!response.isSuccessful) throw IOException("Ошибка сервера списка: ${response.code}")
            val source = response.body.source()
            if (source.request(2_097_153)) throw IOException("Слишком большой ответ списка")
            Json.parseToJsonElement(source.readUtf8()).jsonObject
        }
    }
}

private fun ListTable.wireName(): String = name.lowercase()

private fun ListRecord.toWire() = buildJsonObject {
    put(if (table == ListTable.Titles) "key" else "title_key", titleKey)
    put("updated_at", updatedAt); put("deleted", deleted)
    when (table) {
        ListTable.Titles -> put("data", data)
        else -> {
            put("season", season); put("episode", episode)
            if (table == ListTable.Episodes) put("watched", watched)
            else { put("position_ms", positionMs); put("duration_ms", durationMs) }
        }
    }
}

private fun JsonObject.toRecord(table: ListTable): ListRecord = ListRecord(
    table = table,
    titleKey = getValue(if (table == ListTable.Titles) "key" else "title_key").jsonPrimitive.content,
    updatedAt = getValue("updated_at").jsonPrimitive.long,
    deleted = getValue("deleted").jsonPrimitive.boolean,
    data = if (table == ListTable.Titles) getValue("data").jsonObject else JsonObject(emptyMap()),
    season = if (table == ListTable.Titles) 0 else getValue("season").jsonPrimitive.int,
    episode = if (table == ListTable.Titles) 0 else getValue("episode").jsonPrimitive.int,
    watched = if (table == ListTable.Episodes) getValue("watched").jsonPrimitive.boolean else false,
    positionMs = if (table == ListTable.Positions) getValue("position_ms").jsonPrimitive.long else 0,
    durationMs = if (table == ListTable.Positions) getValue("duration_ms").jsonPrimitive.long else 0,
)
