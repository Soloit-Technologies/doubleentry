package com.soloit.doubleentry.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * The Realtime protocol against a scripted socket: what the client joins with, and that it keeps
 * a connection whatever the server does. Delivery from a real project is covered by
 * [LocalSupabaseContractTest].
 */
class RealtimeClientTest {
    private val server = MockWebServer().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val changes = Channel<Unit>(Channel.UNLIMITED)

    /** One accepted connection: the client's messages, and the socket to answer on. */
    private class Connection : WebSocketListener() {
        val messages = Channel<JsonObject>(Channel.UNLIMITED)
        val socket = kotlinx.coroutines.CompletableDeferred<WebSocket>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket.complete(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            messages.trySend(Json.parseToJsonElement(text).jsonObject)
        }

        // Like the real server, answer a close from the client.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        suspend fun join(): JsonObject = withTimeout(10.seconds) {
            var message: JsonObject
            do message = messages.receive() while (message.getValue("event").jsonPrimitive.content != "phx_join")
            message
        }

        suspend fun send(text: String) = socket.await().send(text)
    }

    private fun accept(): Connection = Connection().also { server.enqueue(MockResponse.Builder().webSocketUpgrade(it).build()) }

    private fun start(tokens: AccessTokenProvider, ledger: String = "ledger-1") {
        val config = SupabaseConfig(server.url("/").toString(), "anon", true)
        val client = RealtimeClient(config, OkHttpClient(), tokens, ledgerId = { ledger }) { changes.trySend(Unit) }
        scope.launch { client.run() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    @Test
    fun joinsTheLedgersChangesAndAsksForASyncOnEach() = runBlocking<Unit> {
        val connection = accept()
        start({ "token-1" })

        val join = connection.join()
        assertEquals("realtime:doubleentry-sync", join.getValue("topic").jsonPrimitive.content)
        val payload = join.getValue("payload").jsonObject
        assertEquals("token-1", payload.getValue("access_token").jsonPrimitive.content)
        val subscriptions = payload.getValue("config").jsonObject.getValue("postgres_changes").jsonArray.map { it.jsonObject }
        assertEquals(SyncTable.entries.map { it.remoteName }, subscriptions.map { it.getValue("table").jsonPrimitive.content })
        assertEquals(setOf("ledger_id=eq.ledger-1"), subscriptions.map { it.getValue("filter").jsonPrimitive.content }.toSet())

        connection.send("""{"topic":"realtime:doubleentry-sync","event":"postgres_changes","payload":{"data":{}},"ref":null}""")
        withTimeout(5.seconds) { changes.receive() }
    }

    @Test
    fun aRefusedJoinReconnectsWithAFreshToken() = runBlocking<Unit> {
        val refused = accept()
        val second = accept()
        val calls = AtomicInteger()
        start({ "token-${calls.incrementAndGet()}" })

        val ref = refused.join().getValue("ref").jsonPrimitive.content
        refused.send("""{"topic":"realtime:doubleentry-sync","event":"phx_reply","payload":{"status":"error","response":{"reason":"Invalid token"}},"ref":"$ref"}""")
        assertEquals("token-2", second.join().getValue("payload").jsonObject.getValue("access_token").jsonPrimitive.content)
    }

    @Test
    fun aClosedConnectionIsOpenedAgain() = runBlocking<Unit> {
        val first = accept()
        val second = accept()
        start({ "token" })

        first.join()
        first.socket.await().close(1001, "Server restarting")
        second.join()
        second.send("""{"topic":"realtime:doubleentry-sync","event":"postgres_changes","payload":{},"ref":null}""")
        withTimeout(5.seconds) { changes.receive() }
    }
}
