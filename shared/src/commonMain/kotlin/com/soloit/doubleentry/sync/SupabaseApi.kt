package com.soloit.doubleentry.sync

import com.soloit.doubleentry.data.local.ServerClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
open class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The cloud service could not be reached; unsynced changes simply wait for the next attempt. */
class OfflineException(cause: Throwable? = null) :
    SyncException("We couldn't reach the cloud service. Please check your internet connection and try again.", cause)

/** The session can't be renewed: the user has to sign in again before anything syncs. */
class SessionExpiredException(cause: Throwable? = null) : SyncException("Your session has expired. Please sign in again.", cause)

/**
 * The server refused an upload because the account has no active cloud backup subscription.
 * Reading (and so restoring) what is already in the cloud still works.
 */
class CloudAccessRequiredException(cause: Throwable? = null) :
    SyncException("Cloud backup isn't active for this account, so changes aren't uploaded.", cause)

/** The Google Play purchase is already linked to a different DoubleEntry account. */
class PurchaseOwnedElsewhereException(cause: Throwable? = null) :
    SyncException("This Google Play subscription belongs to another DoubleEntry account. Sign in with that account to use it.", cause)

/** HTTP 404: the endpoint (table, function) doesn't exist on this project. */
class NotFoundException(message: String, cause: Throwable) : SyncException(message, cause)

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

/** One row of `rpc/cloud_access`: whether the signed-in user may upload, and why. */
@Serializable
data class CloudAccess(
    val active: Boolean = false,
    /** `google_play` or `complimentary`; null without any subscription. */
    val source: String? = null,
    @SerialName("base_plan_id") val basePlanId: String? = null,
    /** Google's subscription state, lower case: active, in_grace_period, canceled, on_hold, expired, … */
    val state: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("auto_renewing") val autoRenewing: Boolean = false,
) {
    val complimentary: Boolean get() = source == "complimentary"
    val expiresAtMillis: Long? get() = expiresAt?.let { runCatching { parseTimestamp(it) / 1000 }.getOrNull() }
}

/**
 * One row of `rpc/sync_control`: the operator's switch for stopping sync in every app ([paused]),
 * or in builds older than [minVersionCode]. [message] replaces the app's own explanation.
 */
@Serializable
data class SyncControl(
    val paused: Boolean = false,
    @SerialName("min_version_code") val minVersionCode: Int = 0,
    val message: String? = null,
)

/** One row of `rpc/list_ledgers`: a ledger the signed-in user is a member of. */
@Serializable
data class LedgerInfo(
    val id: String,
    val name: String,
    /** `owner`, `editor` or `viewer`. */
    val role: String,
    @SerialName("owner_email") val ownerEmail: String? = null,
    @SerialName("member_count") val memberCount: Int = 1,
) {
    val isOwner: Boolean get() = role == "owner"
}

/** One row of `rpc/list_members`. */
@Serializable
data class LedgerMember(
    @SerialName("user_id") val userId: String,
    val email: String? = null,
    val role: String,
)

/** One row of `rpc/ledger_access`: [role] is null when the user isn't a member (any more). */
@Serializable
data class LedgerAccess(
    val role: String? = null,
    /** May upload now: owner or editor, and the owner's backup subscription is active. */
    val writable: Boolean = false,
    @SerialName("owner_email") val ownerEmail: String? = null,
)

/** A code from `rpc/create_invite`, to send to the person invited. */
@Serializable
data class Invite(val code: String, @SerialName("expires_at") val expiresAt: String) {
    val expiresAtMillis: Long? get() = runCatching { parseTimestamp(expiresAt) / 1000 }.getOrNull()
}

/** One row of `rpc/invite_details`: what an invite code leads to. */
@Serializable
data class InviteDetails(
    @SerialName("ledger_id") val ledgerId: String,
    @SerialName("ledger_name") val ledgerName: String,
    @SerialName("owner_email") val ownerEmail: String? = null,
    val role: String,
    @SerialName("already_member") val alreadyMember: Boolean = false,
)

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
    /** Called with a newly measured [ServerClock] offset so it can be kept across restarts. */
    private val onClockOffset: (Long) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    enum class Operation { SignIn, GoogleSignIn, Session, Sync, Account, Purchase, Ledger }

    /**
     * `POST /auth/v1/token?grant_type=password`. Only the local stack and the tests sign in with email;
     * the hosted project has email sign-in switched off.
     */
    suspend fun signIn(email: String, password: String): AuthTokenResponse {
        val body = buildJsonObject { put("email", email); put("password", password) }
        return auth("password", body.toString(), Operation.SignIn)
    }

    /** `POST /auth/v1/token?grant_type=refresh_token` */
    suspend fun refresh(refreshToken: String): AuthTokenResponse {
        val body = buildJsonObject { put("refresh_token", refreshToken) }
        return auth("refresh_token", body.toString(), Operation.Session)
    }

    /**
     * `POST /auth/v1/token?grant_type=id_token` with a Google ID token: signs in, creating the
     * account the first time. [nonce] is the raw nonce whose SHA-256 the token carries.
     */
    suspend fun signInWithGoogle(idToken: String, nonce: String): AuthTokenResponse {
        val body = buildJsonObject { put("provider", "google"); put("id_token", idToken); put("nonce", nonce) }
        return auth("id_token", body.toString(), Operation.GoogleSignIn)
    }

    /**
     * Where a browser sign-in with [provider] starts: `GET /auth/v1/authorize`. Supabase returns
     * to [redirectTo] with `?code=`, which [exchangeCode] trades for a session (PKCE, [Pkce]).
     * [redirectTo] must be on the project's list of redirect URLs.
     */
    fun authorizeUrl(provider: String, redirectTo: String, codeChallenge: String): String =
        "${config.baseUrl}/auth/v1/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("provider", provider)
            .addQueryParameter("redirect_to", redirectTo)
            .addQueryParameter("code_challenge", codeChallenge)
            .addQueryParameter("code_challenge_method", "s256")
            .build()
            .toString()

    /** `POST /auth/v1/token?grant_type=pkce`: signs in with the code a browser sign-in returned, creating the account the first time. */
    suspend fun exchangeCode(authCode: String, codeVerifier: String): AuthTokenResponse {
        val body = buildJsonObject { put("auth_code", authCode); put("code_verifier", codeVerifier) }
        return auth("pkce", body.toString(), Operation.GoogleSignIn)
    }

    private fun authRequest(path: String, body: String) = Request.Builder()
        .url("${config.baseUrl}/auth/v1/$path")
        .header("apikey", config.anonKey)
        .post(body.toRequestBody(jsonMedia))
        .build()

    /**
     * `POST /rest/v1/rpc/cloud_access`: the signed-in user's backup subscription. A project without
     * the function (from before paid backup) doesn't restrict uploads, and says so.
     */
    suspend fun cloudAccess(accessToken: String): CloudAccess {
        val text = try {
            execute(postJson("rest/v1/rpc/cloud_access", "{}", accessToken), Operation.Sync)
        } catch (e: NotFoundException) {
            return CloudAccess(active = true, source = "complimentary")
        }
        val rows = json.parseToJsonElement(text) as JsonArray
        return rows.firstOrNull()?.let { json.decodeFromJsonElement(CloudAccess.serializer(), it) } ?: CloudAccess()
    }

    /**
     * `POST /rest/v1/rpc/sync_control`, signed in or not: whether the server has stopped sync. A
     * project without the function never stops it.
     */
    suspend fun syncControl(): SyncControl {
        val text = try {
            execute(postJson("rest/v1/rpc/sync_control", "{}", config.anonKey), Operation.Sync)
        } catch (e: NotFoundException) {
            return SyncControl()
        }
        return json.decodeFromString(ListSerializer(SyncControl.serializer()), text).firstOrNull() ?: SyncControl()
    }

    /**
     * `POST /rest/v1/rpc/own_ledger`: the id of the signed-in user's own ledger, which the server
     * makes the first time it's asked for.
     */
    suspend fun ownLedger(accessToken: String): String {
        val text = execute(postJson("rest/v1/rpc/own_ledger", "{}", accessToken), Operation.Sync)
        return (json.parseToJsonElement(text) as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw SyncException(friendlyMessage(Operation.Sync, 200))
    }

    /** `POST /rest/v1/rpc/list_ledgers`: the ledgers the signed-in user is a member of, oldest first. */
    suspend fun listLedgers(accessToken: String): List<LedgerInfo> = rpcRows("list_ledgers", "{}", LedgerInfo.serializer(), accessToken)

    /** `POST /rest/v1/rpc/list_members`: who is in [ledgerId], the owner first. */
    suspend fun listMembers(ledgerId: String, accessToken: String): List<LedgerMember> =
        rpcRows("list_members", buildJsonObject { put("ledger", ledgerId) }.toString(), LedgerMember.serializer(), accessToken)

    /** `POST /rest/v1/rpc/ledger_access`: the signed-in user's role in [ledgerId] and whether they may upload to it. */
    suspend fun ledgerAccess(ledgerId: String, accessToken: String): LedgerAccess =
        rpcRows("ledger_access", buildJsonObject { put("ledger", ledgerId) }.toString(), LedgerAccess.serializer(), accessToken).firstOrNull()
            ?: LedgerAccess()

    /** `POST /rest/v1/rpc/create_invite`: a single-use code that makes its holder a member of [ledgerId] (owner only). */
    suspend fun createInvite(ledgerId: String, role: String, accessToken: String): Invite =
        rpcRows("create_invite", buildJsonObject { put("ledger", ledgerId); put("role", role) }.toString(), Invite.serializer(), accessToken).single()

    /** `POST /rest/v1/rpc/invite_details`: what [code] is an invite to; fails when it can't be used. */
    suspend fun inviteDetails(code: String, accessToken: String): InviteDetails =
        rpcRows("invite_details", buildJsonObject { put("code", code) }.toString(), InviteDetails.serializer(), accessToken).single()

    /** `POST /rest/v1/rpc/accept_invite`: joins the ledger [code] is for and returns its id. */
    suspend fun acceptInvite(code: String, accessToken: String): String {
        val text = execute(postJson("rest/v1/rpc/accept_invite", buildJsonObject { put("code", code) }.toString(), accessToken), Operation.Ledger)
        return (json.parseToJsonElement(text) as JsonPrimitive).content
    }

    /** `POST /rest/v1/rpc/remove_member`: the owner removes [userId] from [ledgerId], or a member removes themselves. */
    suspend fun removeMember(ledgerId: String, userId: String, accessToken: String) {
        execute(postJson("rest/v1/rpc/remove_member", buildJsonObject { put("ledger", ledgerId); put("member", userId) }.toString(), accessToken), Operation.Ledger)
    }

    /** `POST /rest/v1/rpc/rename_ledger` (owner only). */
    suspend fun renameLedger(ledgerId: String, name: String, accessToken: String) {
        execute(postJson("rest/v1/rpc/rename_ledger", buildJsonObject { put("ledger", ledgerId); put("name", name) }.toString(), accessToken), Operation.Ledger)
    }

    private suspend fun <T> rpcRows(name: String, body: String, serializer: KSerializer<T>, accessToken: String): List<T> {
        val text = execute(postJson("rest/v1/rpc/$name", body, accessToken), Operation.Ledger)
        return json.decodeFromString(ListSerializer(serializer), text)
    }

    /** `POST /functions/v1/play-verify`: the server checks a Google Play purchase with Google and records it. */
    suspend fun verifyPurchase(purchaseToken: String, accessToken: String) {
        val body = buildJsonObject { put("purchaseToken", purchaseToken) }.toString()
        execute(postJson("functions/v1/play-verify", body, accessToken), Operation.Purchase)
    }

    /** `POST /rest/v1/sync_error_reports`: files [report] under the signed-in user. */
    suspend fun reportSyncError(report: SyncErrorReport, accessToken: String) {
        val request = Request.Builder()
            .url("${config.baseUrl}/rest/v1/sync_error_reports")
            .header("apikey", config.anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Prefer", "return=minimal")
            .post(json.encodeToString(SyncErrorReport.serializer(), report).toRequestBody(jsonMedia))
            .build()
        execute(request, Operation.Sync)
    }

    /** `POST /functions/v1/delete-account`: deletes the account and everything it stored in the cloud. */
    suspend fun deleteAccount(accessToken: String) {
        execute(postJson("functions/v1/delete-account", "{}", accessToken), Operation.Account)
    }

    private fun postJson(path: String, body: String, accessToken: String) = Request.Builder()
        .url("${config.baseUrl}/$path")
        .header("apikey", config.anonKey)
        .header("Authorization", "Bearer $accessToken")
        .post(body.toRequestBody(jsonMedia))
        .build()

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
     * Upserts rows by primary key and returns the rows the server stored:
     * `POST /rest/v1/{table}?on_conflict=ledger_id,id` with `Prefer: resolution=merge-duplicates,return=representation`.
     * Ids are unique per ledger only (content-derived ids repeat across ledgers). A row is left out of
     * the response when the server already holds a newer edit of it (its `modified_at` is later);
     * the server keeps that edit.
     */
    suspend fun upsert(table: SyncTable, rows: JsonArray, accessToken: String): JsonArray {
        val request = Request.Builder()
            .url("${config.baseUrl}/rest/v1/${table.remoteName}?on_conflict=ledger_id,id")
            .header("apikey", config.anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Prefer", "resolution=merge-duplicates,return=representation")
            .post(rows.toString().toRequestBody(jsonMedia))
            .build()
        return json.parseToJsonElement(execute(request, Operation.Sync)) as JsonArray
    }

    /** Where a pull continues: after the row that reached the server at [updatedAt] (ISO-8601) with [id]. */
    data class PageCursor(val updatedAt: String, val id: String)

    /**
     * Reads one page of [ledgerId]'s rows that reached the server at or after [since], in that order:
     * `GET /rest/v1/{table}?select=*&ledger_id=eq.{ledgerId}&order=updated_at.asc,id.asc&limit=…&updated_at=gte.{since}`, and
     * for the next pages `&or=(updated_at.gt.{t},and(updated_at.eq.{t},id.gt.{id}))` after the last row read.
     *
     * Continuing after a row (rather than skipping an offset) matters because a row changed while a
     * pull is paging moves to the end: with an offset, the rows behind it would shift into pages
     * already read and be skipped. The id breaks ties between rows a bulk upsert stamped alike.
     */
    suspend fun select(table: SyncTable, ledgerId: String, since: String?, after: PageCursor?, limit: Int, accessToken: String): JsonArray {
        val url = "${config.baseUrl}/rest/v1/${table.remoteName}".toHttpUrl().newBuilder()
            .addQueryParameter("select", "*")
            .addQueryParameter("ledger_id", "eq.$ledgerId")
            .addQueryParameter("order", "updated_at.asc,id.asc")
            .addQueryParameter("limit", limit.toString())
            .apply { if (since != null) addQueryParameter("updated_at", "gte.$since") }
            .apply {
                // Values with `.` or `:` must be quoted inside or=(…).
                if (after != null) addQueryParameter("or", "(updated_at.gt.\"${after.updatedAt}\",and(updated_at.eq.\"${after.updatedAt}\",id.gt.${after.id}))")
            }
            .build()
        return get(url.toString(), accessToken)
    }

    /** Reads [ledgerId]'s rows with [ids]: `GET /rest/v1/{table}?select=*&ledger_id=eq.{ledgerId}&id=in.(…)`. */
    suspend fun selectByIds(table: SyncTable, ledgerId: String, ids: List<String>, accessToken: String): JsonArray {
        val url = "${config.baseUrl}/rest/v1/${table.remoteName}".toHttpUrl().newBuilder()
            .addQueryParameter("select", "*")
            .addQueryParameter("ledger_id", "eq.$ledgerId")
            .addQueryParameter("id", "in.(${ids.joinToString(",")})")
            .build()
        return get(url.toString(), accessToken)
    }

    private suspend fun get(url: String, accessToken: String): JsonArray {
        val request = Request.Builder()
            .url(url)
            .header("apikey", config.anonKey)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return json.parseToJsonElement(execute(request, Operation.Sync)) as JsonArray
    }

    /** True when [ledgerId] has at least one live row in [table]: `GET /rest/v1/{table}?select=id&ledger_id=eq.{ledgerId}&deleted_at=is.null&limit=1`. */
    suspend fun hasLiveRows(table: SyncTable, ledgerId: String, accessToken: String): Boolean {
        val url = "${config.baseUrl}/rest/v1/${table.remoteName}".toHttpUrl().newBuilder()
            .addQueryParameter("select", "id")
            .addQueryParameter("ledger_id", "eq.$ledgerId")
            .addQueryParameter("deleted_at", "is.null")
            .addQueryParameter("limit", "1")
            .build()
        return get(url.toString(), accessToken).isNotEmpty()
    }

    /**
     * `POST /rest/v1/rpc/mark_ledger_replaced`: from now on the server refuses rows of [ledgerId]
     * edited before [at] (ISO-8601), such as an offline device's, which [tombstoneBefore] can't reach.
     * A project without the function (from before it) only gets the tombstones.
     */
    suspend fun markLedgerReplaced(ledgerId: String, at: String, accessToken: String) {
        val body = buildJsonObject { put("ledger", ledgerId); put("as_of", at) }.toString()
        try {
            execute(postJson("rest/v1/rpc/mark_ledger_replaced", body, accessToken), Operation.Sync)
        } catch (e: NotFoundException) {
            return
        }
    }

    /**
     * Deletes, as of [at] (ISO-8601), every live row of [ledgerId] last edited before then, including
     * rows this device has never pulled. The filter matters: row-level security would allow every
     * ledger the user may write to:
     * `PATCH /rest/v1/{table}?ledger_id=eq.{ledgerId}&deleted_at=is.null&modified_at=lt.{at}` with `{"deleted_at": at, "modified_at": at}`.
     */
    suspend fun tombstoneBefore(table: SyncTable, ledgerId: String, at: String, accessToken: String) {
        val url = "${config.baseUrl}/rest/v1/${table.remoteName}".toHttpUrl().newBuilder()
            .addQueryParameter("ledger_id", "eq.$ledgerId")
            .addQueryParameter("deleted_at", "is.null")
            .addQueryParameter("modified_at", "lt.$at")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("apikey", config.anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Prefer", "return=minimal")
            .patch(buildJsonObject { put("deleted_at", at); put("modified_at", at) }.toString().toRequestBody(jsonMedia))
            .build()
        execute(request, Operation.Sync)
    }

    private suspend fun execute(request: Request, op: Operation): String {
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw OfflineException(e)
        }
        response.use {
            observeClock(it)
            val body = it.body.string()
            if (it.isSuccessful) return body
            val technical = IOException("${request.method} ${request.url.encodedPath}: HTTP ${it.code}. $body")
            if (it.code == 401 && op in AUTHORIZED) throw UnauthorizedException(technical)
            if (op == Operation.Session && it.code in 400..499 && it.code != 408 && it.code != 429) throw SessionExpiredException(technical)
            // Row-level security (42501) refuses uploads without an active subscription.
            if (it.code == 403 && op == Operation.Sync && "42501" in body) throw CloudAccessRequiredException(technical)
            if (it.code == 409 && op == Operation.Purchase) throw PurchaseOwnedElsewhereException(technical)
            val code = errorCode(body)
            if (it.code == 404) throw NotFoundException(friendlyMessage(op, it.code, code), technical)
            throw SyncException(friendlyMessage(op, it.code, code), technical)
        }
    }

    private fun observeClock(response: Response) {
        val date = response.headers.getDate("Date") ?: return
        ServerClock.estimate(date.time, response.sentRequestAtMillis, response.receivedResponseAtMillis)?.let { offset ->
            ServerClock.offsetMicros = offset
            onClockOffset(offset)
        }
    }

    /**
     * The machine-readable reason for a failure: GoTrue's `error_code` (e.g. `weak_password`); for
     * PostgREST, the message our functions raise (P0001, e.g. `invite_used`), else the SQL state.
     */
    private fun errorCode(body: String): String? = runCatching {
        val error = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject ?: return@runCatching null
        fun field(name: String) = (error[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        field("error_code") ?: if (field("code") == "P0001") field("message") else field("code")
    }.getOrNull()

    companion object {
        private val AUTHORIZED = setOf(Operation.Sync, Operation.Account, Operation.Purchase, Operation.Ledger)

        fun friendlyMessage(op: Operation, status: Int, errorCode: String? = null): String = when {
            errorCode == "weak_password" -> "Choose a longer password: at least 8 characters."
            errorCode == "email_address_invalid" || errorCode == "validation_failed" -> "Please check the email address."
            errorCode == "otp_expired" -> "That code is wrong or has expired. Check the latest email, or send a new code."
            errorCode == "over_email_send_rate_limit" -> "Too many emails were sent just now. Please wait a minute and try again."
            errorCode == "signup_disabled" -> "New accounts can't be created right now."
            errorCode == "provider_disabled" -> "Sign in with Google isn't set up for this cloud service yet."
            errorCode == "invite_not_found" -> "That code isn't right. Check it and try again."
            errorCode == "invite_used" -> "That code has already been used. Ask for a new one."
            errorCode == "invite_expired" -> "That code has expired. Ask for a new one."
            errorCode == "not_owner" -> "Only the ledger's owner can do that."
            errorCode == "owner_cannot_leave" -> "You own this ledger, so you can't leave it."
            errorCode == "not_member" -> "You're no longer a member of that ledger."
            errorCode == "23514" && op == Operation.Ledger -> "Choose a name of 1 to 100 characters."
            status >= 500 -> "The cloud service is temporarily unavailable. Please try again in a few minutes."
            status == 408 || status == 429 -> "The cloud service is busy right now. Please wait a moment and try again."
            op == Operation.SignIn -> "We couldn't sign you in. Please check your email and password, then try again."
            op == Operation.GoogleSignIn -> "We couldn't sign you in with Google. Please try again."
            op == Operation.Account -> "We couldn't delete your account right now. Please try again later."
            op == Operation.Purchase -> "We couldn't confirm your purchase yet. It will be checked again the next time the app opens."
            op == Operation.Session -> "Your session has expired. Please sign in again."
            op == Operation.Ledger -> "We couldn't reach your shared ledgers right now. Please try again later."
            else -> "We couldn't sync your data right now. Please try again later."
        }

        fun userMessage(error: Throwable): String = when (error) {
            is SyncException -> error.message ?: "Sync failed."
            is IOException -> OfflineException().message!!
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
