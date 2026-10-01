package com.soloit.doubleentry.sync

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** A failure whose [message] is written for end users; technical detail is kept in [cause]. */
class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown for HTTP 401 so the caller can refresh the session once and retry. */
class UnauthorizedException(cause: Throwable) : IOException("Unauthorized", cause)

@Serializable
data class AuthTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 0,
    val user: AuthUser? = null,
)

@Serializable
data class AuthUser(val id: String? = null, val email: String? = null)

/**
 * Thin client for Supabase Auth (GoTrue) and the PostgREST data API. Every request the app sends
 * to Supabase lives in this file; the tables, policies and triggers it relies on are defined in
 * `supabase/migrations`.
 */
class SupabaseApi(
    private val config: SupabaseConfig,
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    enum class Operation { SignIn, Session, Sync }

    /** `POST /auth/v1/token?grant_type=password` */
    suspend fun signIn(email: String, password: String): AuthTokenResponse {
        val body = buildJsonObject { put("email", email); put("password", password) }
        return auth("password", body.toString(), Operation.SignIn)
    }

    /** `POST /auth/v1/token?grant_type=refresh_token` */
    suspend fun refresh(refreshToken: String): AuthTokenResponse {
        val body = buildJsonObject { put("refresh_token", refreshToken) }
        return auth("refresh_token", body.toString(), Operation.Session)
    }

    private suspend fun auth(grantType: String, body: String, op: Operation): AuthTokenResponse {
        val request = Request.Builder()
            .url("${config.baseUrl}/auth/v1/token?grant_type=$grantType")
            .header("apikey", config.anonKey)
            .post(body.toRequestBody(jsonMedia))
            .build()
        val text = execute(request, op)
        return json.decodeFromString(AuthTokenResponse.serializer(), text)
    }

    /**
     * Upserts rows by primary key and returns the stored rows (with server-stamped `updated_at`):
     * `POST /rest/v1/{table}?on_conflict=id` with `Prefer: resolution=merge-duplicates,return=representation`.
     */
    suspend fun upsert(table: SyncTable, rows: JsonArray, accessToken: String): JsonArray {
        val request = Request.Builder()
            .url("${config.baseUrl}/rest/v1/${table.remoteName}?on_conflict=id")
            .header("apikey", config.anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Prefer", "resolution=merge-duplicates,return=representation")
            .post(rows.toString().toRequestBody(jsonMedia))
            .build()
        return json.parseToJsonElement(execute(request, Operation.Sync)) as JsonArray
    }

    /**
     * Reads one page of rows changed at or after [since], oldest first:
     * `GET /rest/v1/{table}?select=*&order=updated_at.asc,id.asc&limit=…&offset=…&updated_at=gte.{since}`.
     *
     * `gte` (not `gt`) plus offset paging is deliberate: the server trigger stamps many rows of a
     * bulk upsert with the same `updated_at`, and a strict cursor would skip siblings.
     */
    suspend fun select(table: SyncTable, since: String?, offset: Int, limit: Int, accessToken: String): JsonArray {
        val url = "${config.baseUrl}/rest/v1/${table.remoteName}".toHttpUrl().newBuilder()
            .addQueryParameter("select", "*")
            .addQueryParameter("order", "updated_at.asc,id.asc")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .apply { if (since != null) addQueryParameter("updated_at", "gte.$since") }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("apikey", config.anonKey)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return json.parseToJsonElement(execute(request, Operation.Sync)) as JsonArray
    }

    private suspend fun execute(request: Request, op: Operation): String {
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw SyncException("We couldn't reach the cloud service. Please check your internet connection and try again.", e)
        }
        response.use {
            val body = it.body.string()
            if (it.isSuccessful) return body
            val technical = IOException("${request.method} ${request.url.encodedPath}: HTTP ${it.code}. $body")
            if (it.code == 401 && op == Operation.Sync) throw UnauthorizedException(technical)
            throw SyncException(friendlyMessage(op, it.code), technical)
        }
    }

    companion object {
        fun friendlyMessage(op: Operation, status: Int): String = when {
            status >= 500 -> "The cloud service is temporarily unavailable. Please try again in a few minutes."
            status == 408 || status == 429 -> "The cloud service is busy right now. Please wait a moment and try again."
            op == Operation.SignIn -> "We couldn't sign you in. Please check your email and password, then try again."
            op == Operation.Session -> "Your session has expired. Please sign in again."
            else -> "We couldn't sync your data right now. Please try again later."
        }

        fun userMessage(error: Throwable): String = when (error) {
            is SyncException -> error.message ?: "Sync failed."
            is IOException -> "We couldn't reach the cloud service. Please check your internet connection."
            else -> "We couldn't sync your data right now. Please try again later."
        }
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
    })
}
