package com.soloit.doubleentry.testing

import com.soloit.doubleentry.data.local.microsToInstant
import com.soloit.doubleentry.data.local.nowMicros
import com.soloit.doubleentry.sync.parseTimestamp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * In-memory stand-in for the Supabase endpoints the sync engine uses: password sign-in, `GET`
 * with `updated_at=gte.`/`deleted_at=is.null` + order + offset/limit paging, `POST ?on_conflict=id`
 * upserts that merge by (user_id, id) like the primary key, and filtered `PATCH`es. Writes stamp `updated_at` like the server
 * trigger, every one strictly later. A token from [signIn] sees only its user's rows (like
 * row-level security); the plain token "token" sees everything.
 */
class FakePostgrest {
    private val tables = mutableMapOf<String, MutableMap<String, JsonObject>>()
    private var clock = 0L

    /** Number of upserted rows, per table. */
    val writes = mutableMapOf<String, Int>()

    fun rows(table: String): List<JsonObject> = tables[table].orEmpty().values.toList()

    fun liveRows(table: String, userId: String? = null): List<JsonObject> =
        rows(table).filter { it["deleted_at"] is JsonNull || it["deleted_at"] == null }
            .filter { userId == null || it["user_id"]?.jsonPrimitive?.content == userId }

    /** Stores [row] as is, `updated_at` included — e.g. a slow upload that commits an old stamp late. */
    @Synchronized
    fun insertRaw(table: String, row: JsonObject) {
        tables.getOrPut(table) { linkedMapOf() }[key(row)] = row
    }

    /** Password sign-in accepts anything; the user id is derived from the email. */
    fun userIdFor(email: String) = "user-" + email.substringBefore('@')

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain -> handle(chain) }).build()

    @Synchronized
    private fun handle(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if ("auth" in request.url.pathSegments) return respond(request, 200, signIn(request))
        val table = request.url.pathSegments.last()
        val rows = tables.getOrPut(table) { linkedMapOf() }
        val user = request.header("Authorization")?.removePrefix("Bearer ")?.takeIf { it.startsWith("t-") }?.removePrefix("t-")
        val visible = rows.values.filter { user == null || it["user_id"]?.jsonPrimitive?.content == user }
        val updatedAt = request.url.queryParameter("updated_at")
        val onlyLive = request.url.queryParameter("deleted_at") == "is.null"
        fun matches(row: JsonObject): Boolean {
            if (onlyLive && row["deleted_at"].let { it != null && it !is JsonNull }) return false
            return when {
                updatedAt == null -> true
                updatedAt.startsWith("gte.") -> micros(row) >= parseTimestamp(updatedAt.removePrefix("gte."))
                updatedAt.startsWith("lt.") -> micros(row) < parseTimestamp(updatedAt.removePrefix("lt."))
                else -> error("Unexpected filter $updatedAt")
            }
        }
        val body = when (request.method) {
            "GET" -> {
                val offset = request.url.queryParameter("offset")?.toInt() ?: 0
                val limit = request.url.queryParameter("limit")?.toInt() ?: Int.MAX_VALUE
                JsonArray(
                    visible
                        .filter(::matches)
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
                        check(request.url.queryParameter("on_conflict") == "user_id,id") { "Upserts must target the (user_id, id) key" }
                        val stored = JsonObject((rows[key(row)].orEmpty()) + row + ("updated_at" to JsonPrimitive(stamp(tick()))))
                        rows[key(row)] = stored
                        writes[table] = (writes[table] ?: 0) + 1
                        stored
                    },
                )
            }
            "PATCH" -> {
                val changes = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
                val stamp = stamp(tick())
                for (row in visible.filter(::matches)) {
                    rows[key(row)] = JsonObject(row + changes + ("updated_at" to JsonPrimitive(stamp)))
                }
                JsonArray(emptyList())
            }
            else -> error("Unexpected ${request.method}")
        }
        return respond(request, if (request.method == "POST") 201 else 200, body)
    }

    private fun signIn(request: okhttp3.Request): JsonObject {
        val sent = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
        val id = userIdFor(sent.getValue("email").jsonPrimitive.content)
        return buildJsonObject {
            put("access_token", "t-$id")
            put("refresh_token", "r-$id")
            put("expires_in", 3600)
            put("user", buildJsonObject { put("id", id) })
        }
    }

    private fun respond(request: okhttp3.Request, code: Int, body: kotlinx.serialization.json.JsonElement): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("OK")
        .body(body.toString().toResponseBody("application/json".toMediaType()))
        .build()

    /** The server clock: real time, but never the same value twice. */
    private fun tick(): Long {
        clock = maxOf(clock + 1, nowMicros())
        return clock
    }

    /** The primary key: ids are unique per user only. */
    private fun key(row: JsonObject) = row["user_id"]?.jsonPrimitive?.content + "/" + row.getValue("id").jsonPrimitive.content

    private fun micros(row: JsonObject): Long = parseTimestamp(row.getValue("updated_at").jsonPrimitive.content)

    /** Postgres-style `timestamptz` text with an explicit offset. */
    private fun stamp(micros: Long): String = microsToInstant(micros).atOffset(ZoneOffset.UTC).toString()
}
