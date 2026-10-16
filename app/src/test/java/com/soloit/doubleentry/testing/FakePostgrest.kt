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
 * with `updated_at=gte.`, the `or=(…)` page cursor, `id=in.(…)` and `deleted_at=is.null`, ordered by
 * (updated_at, id) with a limit; `POST ?on_conflict=ledger_id,id` upserts that merge by
 * (ledger_id, id) like the primary key; and `PATCH`es filtered by `modified_at=lt.`. Every `GET`
 * and `PATCH` must name its ledger (`ledger_id=eq.`). Like the server triggers, a write older than
 * the stored row (by `modified_at`) is refused, and every other write stamps `updated_at`, each
 * strictly later, and `user_id` with the writer. A token from [signIn] reaches only the ledgers
 * its user is a member of (like row-level security): their own ([ledgerIdFor], returned by
 * `rpc/own_ledger`) and any added to [members]; the plain token "token" reaches everything.
 * Uploads need the ledger owner's cloud access ([withoutAccess] lists users who have none), read
 * with `rpc/cloud_access` and `rpc/ledger_access`. The sharing functions (invites, members,
 * renaming) work like the migration's, with the same refusals.
 */
class FakePostgrest {
    private val tables = mutableMapOf<String, MutableMap<String, JsonObject>>()
    private var clock = 0L

    /** Users without a backup subscription: their uploads are refused like row-level security does. */
    val withoutAccess = mutableSetOf<String>()

    /** Ledgers users were added to, besides their own, by user id; they're editors there. */
    val members = mutableMapOf<String, MutableSet<String>>()

    /** Ledger names; unnamed ledgers are "My ledger". */
    val ledgerNames = mutableMapOf<String, String>()

    /** Open invites: code to ledger. */
    private val invites = mutableMapOf<String, String>()
    private val usedInvites = mutableSetOf<String>()
    private val emails = mutableMapOf<String, String>()

    /** Number of upserted rows, per table. */
    val writes = mutableMapOf<String, Int>()

    /** Called after each page a `GET` returns, with the table — e.g. to change rows while a pull is paging. */
    var afterPage: ((String) -> Unit)? = null

    fun rows(table: String): List<JsonObject> = tables[table].orEmpty().values.toList()

    fun liveRows(table: String, ledgerId: String? = null): List<JsonObject> =
        rows(table).filter { it["deleted_at"] is JsonNull || it["deleted_at"] == null }
            .filter { ledgerId == null || it["ledger_id"]?.jsonPrimitive?.content == ledgerId }

    /** Stores [row] as is, `updated_at` included — e.g. a slow upload that commits an old stamp late. */
    @Synchronized
    fun insertRaw(table: String, row: JsonObject) {
        tables.getOrPut(table) { linkedMapOf() }[key(row)] = row
    }

    /**
     * Password sign-in accepts anything; the user id is derived from the email. Google sign-in
     * (`grant_type=id_token`) treats the ID token as the email of the Google account.
     */
    fun userIdFor(email: String) = "user-" + email.substringBefore('@')

    /** The own ledger of the account with [email]. */
    fun ledgerIdFor(email: String) = "ledger-" + email.substringBefore('@')

    private fun ownLedger(user: String) = "ledger-" + user.removePrefix("user-")

    private fun ownerOf(ledger: String) = "user-" + ledger.removePrefix("ledger-")

    private fun ledgersOf(user: String) = setOf(ownLedger(user)) + members[user].orEmpty()

    private fun membersOf(ledger: String) = listOf(ownerOf(ledger)) + members.filterValues { ledger in it }.keys.sorted()

    private fun roleOf(user: String, ledger: String) = when {
        ownLedger(user) == ledger -> "owner"
        ledger in members[user].orEmpty() -> "editor"
        else -> null
    }

    private fun emailOf(user: String) = emails[user] ?: (user.removePrefix("user-") + "@example.com")

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain -> handle(chain) }).build()

    @Synchronized
    private fun handle(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if ("auth" in request.url.pathSegments) return respond(request, 200, signIn(request))
        val user = request.header("Authorization")?.removePrefix("Bearer ")?.takeIf { it.startsWith("t-") }?.removePrefix("t-")
        if ("rpc" in request.url.pathSegments) return rpc(request, request.url.pathSegments.last(), user)
        if (request.url.encodedPath == "/functions/v1/delete-account") {
            // The account goes with its ledger; its memberships of other ledgers end.
            val me = checkNotNull(user) { "delete-account needs a signed-in user" }
            tables.values.forEach { rows -> rows.values.removeAll { it["ledger_id"]?.jsonPrimitive?.content == ownLedger(me) } }
            members.remove(me)
            return respond(request, 200, buildJsonObject { put("deleted", true) })
        }
        val table = request.url.pathSegments.last()
        val rows = tables.getOrPut(table) { linkedMapOf() }
        val reachable = user?.let(::ledgersOf)
        fun ledger(row: JsonObject) = row["ledger_id"]?.jsonPrimitive?.content
        val visible = rows.values.filter { reachable == null || ledger(it) in reachable }
        val url = request.url
        val ledgerFilter = url.queryParameter("ledger_id")?.removePrefix("eq.")
        if (request.method != "POST") checkNotNull(ledgerFilter) { "Reads and bulk updates name their ledger" }
        // Writing needs the owner's subscription, whoever writes.
        if (user != null && request.method != "GET" && ledgerFilter != null && ownerOf(ledgerFilter) in withoutAccess) return refused(request)
        val onlyLive = url.queryParameter("deleted_at") == "is.null"
        val updatedSince = url.queryParameter("updated_at")?.let { parseTimestamp(it.removePrefix("gte.")) }
        val modifiedBefore = url.queryParameter("modified_at")?.let { parseTimestamp(it.removePrefix("lt.")) }
        val ids = url.queryParameter("id")?.removePrefix("in.(")?.removeSuffix(")")?.split(",")?.toSet()
        val after = url.queryParameter("or")?.let { cursor ->
            val match = checkNotNull(CURSOR.matchEntire(cursor)) { "Unexpected filter $cursor" }
            val (gt, eq, id) = match.destructured
            check(gt == eq)
            parseTimestamp(gt) to id
        }
        fun matches(row: JsonObject): Boolean {
            if (ledgerFilter != null && ledger(row) != ledgerFilter) return false
            if (onlyLive && row["deleted_at"].let { it != null && it !is JsonNull }) return false
            if (updatedSince != null && micros(row) < updatedSince) return false
            if (modifiedBefore != null && modified(row) >= modifiedBefore) return false
            if (ids != null && id(row) !in ids) return false
            if (after != null) {
                val (afterMicros, afterId) = after
                if (micros(row) < afterMicros || (micros(row) == afterMicros && id(row) <= afterId)) return false
            }
            return true
        }
        val body = when (request.method) {
            "GET" -> {
                check(url.queryParameter("offset") == null) { "Pages continue after a row, not at an offset" }
                val limit = url.queryParameter("limit")?.toInt() ?: Int.MAX_VALUE
                JsonArray(
                    visible
                        .filter(::matches)
                        .sortedWith(compareBy({ micros(it) }, { id(it) }))
                        .take(limit),
                ).also { afterPage?.invoke(table) }
            }
            "POST" -> {
                val sent = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonArray
                if (reachable != null && sent.any { ledger(it.jsonObject).let { l -> l !in reachable || ownerOf(l!!) in withoutAccess } }) {
                    return refused(request)
                }
                JsonArray(
                    sent.mapNotNull { element ->
                        val row = element.jsonObject
                        check(request.url.queryParameter("on_conflict") == "ledger_id,id") { "Upserts must target the (ledger_id, id) key" }
                        check("modified_at" in row && "updated_at" !in row) { "Uploads carry modified_at; updated_at is the server's" }
                        val existing = rows[key(row)]
                        // The keep_newer_edit trigger: an older edit is left out of the response.
                        if (existing != null && modified(row) < modified(existing)) return@mapNotNull null
                        val stored = JsonObject(
                            existing.orEmpty() + row + ("updated_at" to JsonPrimitive(stamp(tick()))) + ("user_id" to (user?.let(::JsonPrimitive) ?: JsonNull)),
                        )
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
                    val changed = JsonObject(row + changes)
                    if (modified(changed) < modified(row)) continue
                    rows[key(row)] = JsonObject(changed + ("updated_at" to JsonPrimitive(stamp)))
                }
                JsonArray(emptyList())
            }
            else -> error("Unexpected ${request.method}")
        }
        return respond(request, if (request.method == "POST") 201 else 200, body)
    }

    private fun refused(request: okhttp3.Request) =
        respond(request, 403, buildJsonObject { put("code", "42501"); put("message", "new row violates row-level security policy") })

    /** Raises like the migration's functions do: P0001 with a machine-readable message. */
    private fun raise(request: okhttp3.Request, message: String) =
        respond(request, 400, buildJsonObject { put("code", "P0001"); put("message", message) })

    private fun rpc(request: okhttp3.Request, name: String, user: String?): Response {
        val args = request.body?.let { Json.parseToJsonElement(Buffer().also(it::writeTo).readUtf8()).jsonObject } ?: JsonObject(emptyMap())
        fun arg(key: String) = args.getValue(key).jsonPrimitive.content
        val me = user ?: "user-anonymous"
        fun ledgerRow(ledger: String, role: String) = buildJsonObject {
            put("id", ledger)
            put("name", ledgerNames[ledger] ?: "My ledger")
            put("role", role)
            put("owner_email", emailOf(ownerOf(ledger)))
            put("member_count", membersOf(ledger).size)
        }
        return when (name) {
            "own_ledger" -> respond(request, 200, JsonPrimitive(ownLedger(checkNotNull(user) { "own_ledger needs a signed-in user" })))
            "cloud_access" -> {
                val allowed = user == null || user !in withoutAccess
                respond(request, 200, JsonArray(listOf(buildJsonObject { put("active", allowed); put("source", if (allowed) "complimentary" else null) })))
            }
            "ledger_access" -> {
                val ledger = arg("ledger")
                val role = roleOf(me, ledger)
                respond(request, 200, JsonArray(listOf(buildJsonObject {
                    put("role", role)
                    put("writable", role != null && ownerOf(ledger) !in withoutAccess)
                    put("owner_email", role?.let { emailOf(ownerOf(ledger)) })
                })))
            }
            "list_ledgers" -> respond(request, 200, JsonArray(ledgersOf(me).sorted().map { ledgerRow(it, roleOf(me, it)!!) }))
            "list_members" -> {
                val ledger = arg("ledger")
                if (roleOf(me, ledger) == null) return raise(request, "not_member")
                respond(request, 200, JsonArray(membersOf(ledger).map { member ->
                    buildJsonObject { put("user_id", member); put("email", emailOf(member)); put("role", roleOf(member, ledger)) }
                }))
            }
            "create_invite" -> {
                val ledger = arg("ledger")
                if (roleOf(me, ledger) != "owner") return raise(request, "not_owner")
                val code = "CODE-%04d".format(invites.size + usedInvites.size + 1)
                invites[code] = ledger
                respond(request, 200, JsonArray(listOf(buildJsonObject { put("code", code); put("expires_at", stamp(nowMicros() + 7 * 86_400_000_000L)) })))
            }
            "invite_details", "accept_invite" -> {
                val code = arg("code").uppercase()
                if (code in usedInvites) return raise(request, "invite_used")
                val ledger = invites[code] ?: return raise(request, "invite_not_found")
                val member = roleOf(me, ledger) != null
                if (name == "invite_details") {
                    respond(request, 200, JsonArray(listOf(buildJsonObject {
                        put("ledger_id", ledger)
                        put("ledger_name", ledgerNames[ledger] ?: "My ledger")
                        put("owner_email", emailOf(ownerOf(ledger)))
                        put("role", "editor")
                        put("already_member", member)
                    })))
                } else {
                    if (!member) {
                        invites.remove(code)
                        usedInvites += code
                        members.getOrPut(me) { mutableSetOf() } += ledger
                    }
                    respond(request, 200, JsonPrimitive(ledger))
                }
            }
            "remove_member" -> {
                val ledger = arg("ledger")
                val member = arg("member")
                if (member == me) {
                    if (roleOf(me, ledger) == "owner") return raise(request, "owner_cannot_leave")
                } else if (roleOf(me, ledger) != "owner") {
                    return raise(request, "not_owner")
                }
                members[member]?.remove(ledger)
                respond(request, 200, JsonNull)
            }
            "rename_ledger" -> {
                val ledger = arg("ledger")
                if (roleOf(me, ledger) != "owner") return raise(request, "not_owner")
                ledgerNames[ledger] = arg("name")
                respond(request, 200, JsonNull)
            }
            else -> error("Unexpected rpc/$name")
        }
    }

    private fun signIn(request: okhttp3.Request): JsonObject {
        val sent = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
        val email = (sent["email"] ?: sent.getValue("id_token")).jsonPrimitive.content
        val id = userIdFor(email)
        emails[id] = email
        return buildJsonObject {
            put("access_token", "t-$id")
            put("refresh_token", "r-$id")
            put("expires_in", 3600)
            put("user", buildJsonObject { put("id", id); put("email", email) })
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

    /** The primary key: ids are unique per ledger only. */
    private fun key(row: JsonObject) = row["ledger_id"]?.jsonPrimitive?.content + "/" + row.getValue("id").jsonPrimitive.content

    private fun micros(row: JsonObject): Long = parseTimestamp(row.getValue("updated_at").jsonPrimitive.content)

    private fun modified(row: JsonObject): Long = parseTimestamp(row.getValue("modified_at").jsonPrimitive.content)

    private fun id(row: JsonObject): String = row.getValue("id").jsonPrimitive.content

    /** Postgres-style `timestamptz` text with an explicit offset. */
    private fun stamp(micros: Long): String = microsToInstant(micros).atOffset(ZoneOffset.UTC).toString()

    private companion object {
        val CURSOR = Regex("""\(updated_at\.gt\."([^"]+)",and\(updated_at\.eq\."([^"]+)",id\.gt\.([^)]+)\)\)""")
    }
}
