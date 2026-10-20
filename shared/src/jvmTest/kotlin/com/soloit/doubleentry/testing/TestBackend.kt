package com.soloit.doubleentry.testing

import com.soloit.doubleentry.sync.SupabaseApi
import com.soloit.doubleentry.sync.SupabaseConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/** A signed-in test user: [token] is a valid access token, [ledgerId] their own ledger. */
data class TestUser(val id: String, val email: String, val token: String, val ledgerId: String)

/**
 * A Supabase project for tests: [FakeBackend] (in memory) or [LocalBackend] (a real local stack).
 * The contract tests run against both, which checks the fake the other sync tests rely on
 * against the real server.
 */
interface TestBackend {
    val config: SupabaseConfig
    val client: OkHttpClient

    /** Creates a user, signs them in and makes their own ledger; [cloudAccess] lets them upload. */
    suspend fun newUser(name: String, cloudAccess: Boolean = true): TestUser

    fun setCloudAccess(user: TestUser, active: Boolean)

    /** Every row of [ledgerId] in [table], deleted ones included, as the service role sees them. */
    fun rows(table: String, ledgerId: String): List<JsonObject>
}

class FakeBackend(val server: FakePostgrest = FakePostgrest()) : TestBackend {
    override val config = SupabaseConfig("https://project.supabase.co", "anon", true)
    override val client: OkHttpClient get() = server.client
    private val api = SupabaseApi(config, client)

    override suspend fun newUser(name: String, cloudAccess: Boolean): TestUser {
        val email = "$name@example.com"
        val session = api.signIn(email, "password")
        val token = session.accessToken!!
        return TestUser(session.user!!.id!!, email, token, api.ownLedger(token)).also { setCloudAccess(it, cloudAccess) }
    }

    override fun setCloudAccess(user: TestUser, active: Boolean) {
        if (active) server.withoutAccess -= user.id else server.withoutAccess += user.id
    }

    override fun rows(table: String, ledgerId: String) = server.rows(table).filter { it["ledger_id"]?.jsonPrimitive?.content == ledgerId }
}

/**
 * A local Supabase stack (`supabase start`) with every migration applied. Users are made with the
 * admin API, named uniquely per instance so test runs don't meet.
 */
class LocalBackend private constructor(private val url: String, anonKey: String, private val serviceKey: String) : TestBackend {
    override val config = SupabaseConfig(url, anonKey, true)
    override val client = OkHttpClient()
    private val api = SupabaseApi(config, client)
    private val run = UUID.randomUUID().toString().take(8)
    private val jsonMedia = "application/json".toMediaType()

    override suspend fun newUser(name: String, cloudAccess: Boolean): TestUser {
        val email = "contract-$run-$name@example.com"
        val password = "Password-$run"
        admin(
            Request.Builder().url("$url/auth/v1/admin/users").post(
                buildJsonObject { put("email", email); put("password", password); put("email_confirm", true) }.toString().toRequestBody(jsonMedia),
            ),
        )
        val session = api.signIn(email, password)
        val token = session.accessToken!!
        return TestUser(session.user!!.id!!, email, token, api.ownLedger(token)).also { setCloudAccess(it, cloudAccess) }
    }

    override fun setCloudAccess(user: TestUser, active: Boolean) {
        if (active) {
            val row = buildJsonObject {
                put("user_id", user.id)
                put("source", "complimentary")
                put("state", "active")
                put("expires_at", JsonNull)
            }
            admin(
                Request.Builder().url("$url/rest/v1/cloud_subscriptions?on_conflict=user_id")
                    .header("Prefer", "resolution=merge-duplicates")
                    .post(row.toString().toRequestBody(jsonMedia)),
            )
        } else {
            admin(Request.Builder().url("$url/rest/v1/cloud_subscriptions?user_id=eq.${user.id}").delete())
        }
    }

    override fun rows(table: String, ledgerId: String): List<JsonObject> {
        val query = "$url/rest/v1/$table".toHttpUrl().newBuilder()
            .addQueryParameter("select", "*")
            .addQueryParameter("ledger_id", "eq.$ledgerId")
            .build()
        return (Json.parseToJsonElement(admin(Request.Builder().url(query).get())) as JsonArray).map { it.jsonObject }
    }

    /** Runs [request] as the service role, which row-level security doesn't apply to. */
    private fun admin(request: Request.Builder): String {
        val response = client.newCall(request.header("apikey", serviceKey).header("Authorization", "Bearer $serviceKey").build()).execute()
        return response.use {
            val body = it.body.string()
            check(it.isSuccessful) { "${it.request.method} ${it.request.url}: HTTP ${it.code} $body" }
            body
        }
    }

    companion object {
        /**
         * The stack named by SUPABASE_TEST_URL, SUPABASE_TEST_ANON_KEY and SUPABASE_TEST_SERVICE_KEY
         * (`supabase status -o env` prints them as API_URL, ANON_KEY and SERVICE_ROLE_KEY); null when unset.
         */
        fun fromEnvironment(): LocalBackend? {
            val url = System.getenv("SUPABASE_TEST_URL")?.takeIf { it.isNotBlank() } ?: return null
            return LocalBackend(url.trimEnd('/'), System.getenv("SUPABASE_TEST_ANON_KEY")!!, System.getenv("SUPABASE_TEST_SERVICE_KEY")!!)
        }
    }
}
