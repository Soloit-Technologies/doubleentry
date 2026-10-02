package com.soloit.doubleentry.testing

import com.soloit.doubleentry.data.local.microsToInstant
import com.soloit.doubleentry.data.local.nowMicros
import com.soloit.doubleentry.sync.parseTimestamp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.time.ZoneOffset

/**
 * In-memory stand-in for the PostgREST endpoints the sync engine uses: `GET` with
 * `updated_at=gte.` + order + offset/limit paging, and `POST ?on_conflict=id` upserts that merge
 * by id and stamp `updated_at` like the server trigger. Every write gets a strictly later stamp.
 */
class FakePostgrest {
    private val tables = mutableMapOf<String, MutableMap<String, JsonObject>>()
    private var clock = 0L

    /** Number of upserted rows, per table. */
    val writes = mutableMapOf<String, Int>()

    fun rows(table: String): List<JsonObject> = tables[table].orEmpty().values.toList()

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain -> handle(chain) }).build()

    @Synchronized
    private fun handle(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val table = request.url.pathSegments.last()
        val rows = tables.getOrPut(table) { linkedMapOf() }
        val body = when (request.method) {
            "GET" -> {
                val since = request.url.queryParameter("updated_at")?.removePrefix("gte.")?.let(::parseTimestamp)
                val offset = request.url.queryParameter("offset")?.toInt() ?: 0
                val limit = request.url.queryParameter("limit")?.toInt() ?: Int.MAX_VALUE
                JsonArray(
                    rows.values
                        .filter { since == null || micros(it) >= since }
                        .sortedWith(compareBy({ micros(it) }, { it.getValue("id").jsonPrimitive.content }))
                        .drop(offset)
                        .take(limit),
                )
            }
            "POST" -> {
                val sent = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonArray
                JsonArray(
                    sent.map { element ->
                        val row = element.jsonObject
                        val id = row.getValue("id").jsonPrimitive.content
                        val stored = JsonObject((rows[id].orEmpty()) + row + ("updated_at" to JsonPrimitive(stamp(tick()))))
                        rows[id] = stored
                        writes[table] = (writes[table] ?: 0) + 1
                        stored
                    },
                )
            }
            else -> error("Unexpected ${request.method}")
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (request.method == "POST") 201 else 200)
            .message("OK")
            .body(body.toString().toResponseBody("application/json".toMediaType()))
            .build()
    }

    /** The server clock: real time, but never the same value twice. */
    private fun tick(): Long {
        clock = maxOf(clock + 1, nowMicros())
        return clock
    }

    private fun micros(row: JsonObject): Long = parseTimestamp(row.getValue("updated_at").jsonPrimitive.content)

    /** Postgres-style `timestamptz` text with an explicit offset. */
    private fun stamp(micros: Long): String = microsToInstant(micros).atOffset(ZoneOffset.UTC).toString()
}
