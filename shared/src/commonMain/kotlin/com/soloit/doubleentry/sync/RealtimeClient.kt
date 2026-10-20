package com.soloit.doubleentry.sync

import com.soloit.doubleentry.platform.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Minimal Supabase Realtime (Phoenix channels) client. It subscribes to `postgres_changes` on
 * every synced table of the [ledgerId] this device syncs and calls [onChange] for each event, so
 * edits from other devices and members arrive within seconds. RLS limits events to ledgers the
 * signed-in user is a member of.
 *
 * Events sent while no connection was subscribed are lost, so [onChange] is also called each time
 * the subscription becomes live: the sync it asks for catches up on whatever was missed.
 */
class RealtimeClient(
    private val config: SupabaseConfig,
    private val http: OkHttpClient,
    private val tokens: AccessTokenProvider,
    private val ledgerId: () -> String?,
    private val onChange: () -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ref = AtomicInteger(0)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    /** The network is back: stop waiting out the backoff and connect now. */
    fun wake() {
        wakeups.trySend(Unit)
    }

    /** Keeps a connection open (reconnecting with backoff) until the calling coroutine is cancelled. */
    suspend fun run() {
        var backoff = 2.seconds
        while (currentCoroutineContext().isActive) {
            val token = runCatching { tokens.accessToken(forceRefresh = false) }.getOrNull()
            if (token == null) {
                waitOrWake(1.minutes)
                continue
            }
            val started = System.currentTimeMillis()
            runCatching { connectOnce(token) }.onFailure { Log.d(TAG, "Realtime connection ended", it) }
            if (System.currentTimeMillis() - started > 60_000) backoff = 2.seconds
            backoff = if (waitOrWake(backoff)) 2.seconds else (backoff * 2).coerceAtMost(5.minutes)
        }
    }

    /** Waits [time], or less if [wake] is called. Returns true when woken. */
    private suspend fun waitOrWake(time: Duration): Boolean = withTimeoutOrNull(time) { wakeups.receive() } != null

    private suspend fun connectOnce(initialToken: String) = coroutineScope {
        val closed = CompletableDeferred<Unit>()
        val url = config.baseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/realtime/v1/websocket?apikey=${config.anonKey}&vsn=1.0.0"

        val socket = http.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(joinMessage(initialToken, ledgerId()))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                    val status = runCatching { message["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content }.getOrNull()
                    when (message["event"]?.jsonPrimitive?.content) {
                        "postgres_changes" -> onChange()
                        "phx_error", "phx_close" -> webSocket.close(1000, null)
                        // A rejected join (e.g. expired token) leaves a silent socket; reconnect instead.
                        "phx_reply" -> if (status == "error") webSocket.close(1000, null)
                        // After the join, the server reports whether the postgres_changes subscription
                        // took. Once it has, catch up on what changed while not subscribed; if it
                        // failed, the channel stays joined but silent, so reconnect.
                        "system" -> when (status) {
                            "ok" -> onChange()
                            "error" -> webSocket.close(1000, null)
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // Acknowledge a server-initiated close so onClosed fires and the loop reconnects.
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closed.completeExceptionally(t)
                }
            },
        )

        val heartbeat = launch {
            while (isActive) {
                delay(25.seconds)
                socket.send(message("phoenix", "heartbeat") {})
            }
        }
        // Tokens expire after about an hour, and the server closes the channel when its token does.
        // accessToken() renews one only shortly before it expires, so ask often enough to catch that.
        val tokenRefresh = launch {
            var current = initialToken
            while (isActive) {
                delay(TOKEN_CHECK_INTERVAL)
                val next = runCatching { tokens.accessToken(forceRefresh = false) }.getOrNull() ?: continue
                if (next != current) {
                    current = next
                    socket.send(message(TOPIC, "access_token") { put("access_token", next) })
                }
            }
        }

        try {
            closed.await()
        } finally {
            heartbeat.cancel()
            tokenRefresh.cancel()
            socket.close(1000, null)
        }
    }

    private fun joinMessage(token: String, ledger: String?): String = message(TOPIC, "phx_join") {
        putJsonObject("config") {
            putJsonObject("broadcast") { put("ack", false); put("self", false) }
            putJsonObject("presence") { put("key", "") }
            putJsonArray("postgres_changes") {
                for (table in SyncTable.entries) {
                    addJsonObject {
                        put("event", "*")
                        put("schema", "public")
                        put("table", table.remoteName)
                        if (ledger != null) put("filter", "ledger_id=eq.$ledger")
                    }
                }
            }
            put("private", false)
        }
        put("access_token", token)
    }

    private fun message(topic: String, event: String, payload: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String {
        val id = ref.incrementAndGet().toString()
        return buildJsonObject {
            put("topic", topic)
            put("event", event)
            putJsonObject("payload", payload)
            put("ref", id)
            if (event == "phx_join") put("join_ref", id)
        }.toString()
    }

    private companion object {
        const val TAG = "RealtimeClient"
        const val TOPIC = "realtime:doubleentry-sync"
        val TOKEN_CHECK_INTERVAL = 20.seconds
    }
}
