package com.soloit.doubleentry.sync

import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.data.local.nowMicros
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.testing.FakePostgrest
import com.soloit.doubleentry.testing.FakePostgrest.Failure
import com.soloit.doubleentry.testing.TestLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * Sync when requests fail part-way: dropped connections, responses lost after the server applied
 * the request, server errors and expired sessions. Whatever fails, the next sync must end with
 * every device and the cloud holding the same data, nothing lost and nothing twice.
 */
@RunWith(RobolectricTestRunner::class)
class SyncFailureTest {
    private val server = FakePostgrest()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val devices = mutableListOf<Device>()
    private val day = LocalDate.of(2026, 9, 15)
    private val ledger get() = server.ledgerIdFor("me@example.com")

    private class MemoryTokens : TokenStore {
        private val values = mutableMapOf<String, String>()
        override fun get(name: String) = values[name]
        override fun put(name: String, value: String?) {
            if (value == null) values.remove(name) else values[name] = value
        }
        override fun clear() = values.clear()
    }

    private inner class Device(name: String) {
        val t = TestLedger(name)
        val prefs = SyncPreferences(t.context, MemoryTokens())
        private val config = SupabaseConfig("https://project.supabase.co", "anon", true)
        private val api = SupabaseApi(config, server.client)
        val sync = SyncManager(config, prefs, api, SyncEngine(t.db, api, prefs), scope, t.db.syncStatus().pendingChanges())
        val status get() = sync.status.value

        init {
            devices += this
        }

        suspend fun ledgerWithOneTransaction(title: String): String {
            val bank = t.account(1910, "Bank", AccountType.Asset)
            val food = t.account(5010, "Food", AccountType.Expense)
            return t.post(day, title, food, bank, 100_00)
        }

        suspend fun post(title: String, amount: Long = 1_00): String {
            val accounts = t.ledger.getAccounts()
            val id = t.post(day, title, accounts.single { it.number == 5010 }, accounts.single { it.number == 1910 }, amount)
            // The status learns the pending count asynchronously; attention and error reports read it from there.
            val count = pending()
            withTimeout(5_000) { sync.status.first { it.pendingChanges == count } }
            return id
        }

        suspend fun titles(): List<String> = t.ledger.getTransactions().map { it.title }.sorted()

        suspend fun pending(): Int = t.db.syncStatus().pendingChanges().first()
    }

    @After
    fun tearDown() {
        scope.cancel()
        devices.forEach { it.t.close() }
    }

    private fun cloudTitles(): List<String> = server.liveRows("transactions", ledger).map { it.getValue("title").jsonPrimitive.content }.sorted()

    private fun uploadsTo(table: String): (Request) -> Boolean = { it.method == "POST" && it.url.encodedPath == "/rest/v1/$table" }

    private fun readsOf(table: String): (Request) -> Boolean = { it.method == "GET" && it.url.encodedPath == "/rest/v1/$table" }

    @Test
    fun aLostUploadResponseIsSentAgainWithoutDuplicates() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("First")
        assertNull(a.sync.signIn("me@example.com", "pw"))
        a.post("Second")
        a.post("Third")

        // The server stores the upload, but the phone never hears back.
        server.fail(Failure.ResponseLost, matches = uploadsTo("transactions"))
        assertFalse(a.sync.syncNow())
        assertTrue(a.status.offline)
        assertEquals(listOf("First", "Second", "Third"), cloudTitles())
        assertEquals(2, a.pending())

        // Sending the same edits again is harmless: no copies, and no conflict to report.
        assertTrue(a.sync.syncNow())
        assertEquals(0, a.pending())
        assertEquals(3, server.liveRows("transactions", ledger).size)
        assertEquals(0, a.status.replacedChanges)

        val b = Device("b")
        assertNull(b.sync.signIn("me@example.com", "pw"))
        assertEquals(listOf("First", "Second", "Third"), b.titles())
    }

    @Test
    fun anUploadFailingHalfwaySendsTheRestNextTime() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Posting 0")
        assertNull(a.sync.signIn("me@example.com", "pw"))
        // More than one upload chunk (500 rows).
        repeat(700) { a.post("Posting ${it + 1}") }

        // The first chunk arrives, the connection drops on the second.
        server.fail(Failure.Dropped, skip = 1, matches = uploadsTo("transactions"))
        assertFalse(a.sync.syncNow())
        assertEquals(501, server.liveRows("transactions", ledger).size)
        assertEquals(200, a.pending())

        assertTrue(a.sync.syncNow())
        assertEquals(0, a.pending())
        assertEquals(701, server.liveRows("transactions", ledger).size)
        assertEquals(a.titles(), cloudTitles())
    }

    @Test
    fun aPullFailingPartWayReadsEverythingAgainNextTime() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Before")
        a.t.settings.put(SettingsRepository.Keys.TAX_PREFIXES, "89")
        assertNull(a.sync.signIn("me@example.com", "pw"))

        // B reads accounts and transactions, then loses the connection on a later table.
        server.fail(Failure.Dropped, matches = readsOf("assets"))
        val b = Device("b")
        assertNull(b.sync.signIn("me@example.com", "pw"))
        assertTrue(b.status.lastError != null)
        assertNull(b.prefs.watermark(SyncTable.Accounts))
        assertNull(b.prefs.watermark(SyncTable.Transactions))

        a.post("After")
        a.sync.syncNow()
        assertTrue(b.sync.syncNow())
        assertEquals(listOf("After", "Before"), b.titles())
        assertEquals(2, b.t.ledger.getAccounts().size)
        assertEquals("89", b.t.settings.get(SettingsRepository.Keys.TAX_PREFIXES))
        assertTrue(b.prefs.watermark(SyncTable.Transactions) != null)
    }

    @Test
    fun serverErrorsAreShownAndTheChangesWait() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Synced")
        assertNull(a.sync.signIn("me@example.com", "pw"))
        a.post("Waiting")

        server.fail(Failure.Unavailable, times = Int.MAX_VALUE)
        assertFalse(a.sync.syncNow())
        assertFalse(a.status.offline)
        assertEquals(SyncAttention.Failing, a.status.attention)
        assertEquals(1, a.pending())

        server.heal()
        assertTrue(a.sync.syncNow())
        assertNull(a.status.attention)
        assertEquals(listOf("Synced", "Waiting"), cloudTitles())
    }

    @Test
    fun offlineChangesWaitAndUploadWhenTheConnectionReturns() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Synced")
        assertNull(a.sync.signIn("me@example.com", "pw"))
        a.post("Offline")

        server.fail(Failure.Dropped, times = Int.MAX_VALUE)
        assertFalse(a.sync.syncNow())
        assertTrue(a.status.offline)
        assertEquals(SyncAttention.Offline, a.status.attention)

        server.heal()
        assertTrue(a.sync.syncNow())
        assertFalse(a.status.offline)
        assertEquals(listOf("Offline", "Synced"), cloudTitles())
        // Being offline is expected, not a bug to report.
        assertEquals(emptyList<Any>(), server.errorReports)
    }

    @Test
    fun anExpiredAccessTokenIsRenewedWithoutAskingTheUser() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Synced")
        assertNull(a.sync.signIn("me@example.com", "pw"))
        a.post("Later")

        server.expireAccessTokens()
        assertTrue(a.sync.syncNow())
        assertEquals(1, server.refreshes)
        assertNull(a.status.attention)
        assertEquals(listOf("Later", "Synced"), cloudTitles())
    }

    @Test
    fun concurrentRenewalsNeverReuseTheSingleUseRefreshToken() = runBlocking<Unit> {
        val a = Device("a")
        assertNull(a.sync.signIn("me@example.com", "pw"))
        server.expireAccessTokens()

        // Sync, Realtime and the subscription check can all hit a 401 at once.
        val tokens = (1..5).map { scope.async { a.sync.accessToken(forceRefresh = true) } }.awaitAll()
        assertEquals(5, tokens.toSet().size)
        assertTrue(a.sync.syncNow())
        assertFalse(a.status.needsSignIn)
    }

    @Test
    fun anEndedSessionAsksToSignInAgainAndKeepsTheChanges() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Synced")
        assertNull(a.sync.signIn("me@example.com", "pw"))
        a.post("Kept")

        server.revokeSessions()
        assertFalse(a.sync.syncNow())
        assertTrue(a.status.needsSignIn)
        assertEquals(SyncAttention.SignInAgain, a.status.attention)
        assertEquals(1, a.pending())

        a.sync.signIn("me@example.com", "pw")
        assertFalse(a.status.needsSignIn)
        assertEquals(0, a.pending())
        assertEquals(listOf("Kept", "Synced"), cloudTitles())
    }

    @Test
    fun aReplacedLedgerReachesADeviceThatSyncedWhileItWasHalfUploaded() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Old")
        a.t.settings.put(SettingsRepository.Keys.TAX_PREFIXES, "89")
        assertNull(a.sync.signIn("me@example.com", "pw"))
        val b = Device("b")
        assertNull(b.sync.signIn("me@example.com", "pw"))

        // Delete all: the old cloud rows are deleted, then the upload of what A kept is cut off.
        val before = nowMicros()
        a.t.ledger.deleteAllData()
        // Before the request: replaceCloudLedger starts a sync in the background too.
        server.fail(Failure.Dropped, times = Int.MAX_VALUE, matches = { it.method == "POST" && "rpc" !in it.url.pathSegments })
        a.sync.replaceCloudLedger(before)
        assertFalse(a.sync.syncNow())

        // B syncs in between (Realtime tells it about the deletions right away) and sees them.
        assertTrue(b.sync.syncNow())
        assertEquals(emptyList<String>(), b.titles())

        server.heal()
        assertTrue(a.sync.syncNow())
        assertNull(a.prefs.cloudWipeBeforeMicros)
        assertTrue(b.sync.syncNow())
        // What A kept must reach B, although B already holds the deletion of the same rows.
        assertEquals("89", b.t.settings.get(SettingsRepository.Keys.TAX_PREFIXES))
        assertEquals(a.t.ledger.getAccounts().map { it.id }.sorted(), b.t.ledger.getAccounts().map { it.id }.sorted())
    }

    @Test
    fun aRefusedUploadIsReportedOnceWithoutTheRowsContent() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Synced")
        assertNull(a.sync.signIn("me@example.com", "pw"))
        a.post("Salary from Acme AB", 25_000_00)

        server.fail(Failure.Rejected, times = 2, matches = uploadsTo("transactions"))
        assertFalse(a.sync.syncNow())
        withTimeout(5_000) { while (server.errorReports.isEmpty()) delay(10) }
        val report = server.errorReports.single()
        assertEquals(server.userIdFor("me@example.com"), report.getValue("user_id").jsonPrimitive.content)
        assertEquals(1, report.getValue("pending_changes").jsonPrimitive.content.toInt())
        val detail = report.getValue("detail").jsonPrimitive.content
        assertTrue(detail, "POST /rest/v1/transactions: HTTP 400. code=23514" in detail && "SupabaseApi.kt:" in detail)
        assertFalse(detail, "Acme" in detail || "25000" in detail)

        // The same failure on the next cycle isn't sent again.
        assertFalse(a.sync.syncNow())
        delay(200)
        assertEquals(1, server.errorReports.size)
    }
}
