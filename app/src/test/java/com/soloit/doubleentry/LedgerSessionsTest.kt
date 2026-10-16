package com.soloit.doubleentry

import androidx.room.Room
import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.sync.SupabaseApi
import com.soloit.doubleentry.sync.SupabaseConfig
import com.soloit.doubleentry.sync.SyncAttention
import com.soloit.doubleentry.sync.SyncEngine
import com.soloit.doubleentry.sync.SyncException
import com.soloit.doubleentry.sync.SyncManager
import com.soloit.doubleentry.sync.SyncPreferences
import com.soloit.doubleentry.sync.TokenStore
import com.soloit.doubleentry.testing.FakePostgrest
import com.soloit.doubleentry.testing.TestLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** Sharing a ledger: invites, opening ledgers on a device, leaving, removal, through a fake Supabase. */
@RunWith(RobolectricTestRunner::class)
class LedgerSessionsTest {
    private val server = FakePostgrest()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val devices = mutableListOf<Device>()
    private val day = LocalDate.of(2026, 10, 1)

    private class MemoryTokens : TokenStore {
        private val values = mutableMapOf<String, String>()
        override fun get(name: String) = values[name]
        override fun put(name: String, value: String?) {
            if (value == null) values.remove(name) else values[name] = value
        }
        override fun clear() = values.clear()
    }

    /** A phone of [email]'s, wired like AppContainer, with in-memory databases. */
    private inner class Device(val email: String) {
        val t = TestLedger(email.substringBefore('@'))
        private val tokens = MemoryTokens()
        private val config = SupabaseConfig("https://project.supabase.co", "anon", true)
        private val api = SupabaseApi(config, server.client)
        private val databases = mutableMapOf<String, AppDatabase>()

        private fun prefs(slot: String?) = SyncPreferences(t.context, tokens, slot)

        val sync = SyncManager(config, prefs(null), api, SyncEngine(t.db, api, prefs(null)), scope, t.db.syncStatus().pendingChanges())
        val sessions = LedgerSessions(
            sync, api, scope, ::prefs,
            openDatabase = { slot -> slot?.let { databases.getOrPut(it) { Room.inMemoryDatabaseBuilder(t.context, AppDatabase::class.java).build() } } ?: t.db },
            deleteDatabase = { databases.remove(it) },
            initial = LedgerSession(null, t.db, sync),
        )

        init {
            devices += this
        }

        val open: LedgerSession get() = sessions.active.value

        val ownLedger: String get() = server.ledgerIdFor(email)

        suspend fun signIn() = assertNull(sync.signIn(email, "pw"))

        /** Posts [title] in the open ledger, creating the two accounts it needs the first time. */
        suspend fun post(title: String) {
            val ledger = open.ledger
            val accounts = ledger.getAccounts()
            val bank = accounts.firstOrNull { it.number == 1910 } ?: ledger.createAccount(1910, "Bank", AccountType.Asset)
            val food = accounts.firstOrNull { it.number == 5010 } ?: ledger.createAccount(5010, "Food", AccountType.Expense)
            ledger.post(TransactionDraft(day, title, "", listOf(DraftLine(food.id, 10_00, EntryType.Debit), DraftLine(bank.id, 10_00, EntryType.Credit))))
        }

        suspend fun titles(): Set<String> = open.ledger.getTransactions().map { it.title }.toSet()
    }

    @After
    fun tearDown() {
        scope.cancel()
        devices.forEach { it.t.close() }
    }

    private fun cloudTitles(ledger: String): Set<String> =
        server.liveRows("transactions", ledger).map { it.getValue("title").jsonPrimitive.content }.toSet()

    /** Anna (with a ledger) invites Ben (with his own), and Ben joins on his phone. */
    private suspend fun annaSharesWithBen(): Pair<Device, Device> {
        val anna = Device("anna@example.com")
        anna.post("Groceries")
        anna.signIn()
        val ben = Device("ben@example.com")
        ben.post("Ben's own")
        ben.signIn()
        val code = anna.sync.createInvite(anna.ownLedger).code
        ben.sessions.join(code)
        return anna to ben
    }

    @Test
    fun aMemberJoinsWithACodeAndBothEditTheSameLedger() = runBlocking<Unit> {
        val anna = Device("anna@example.com")
        anna.post("Groceries")
        anna.signIn()
        anna.sync.renameLedger(anna.ownLedger, "Home")
        val code = anna.sync.createInvite(anna.ownLedger).code

        val ben = Device("ben@example.com")
        ben.post("Ben's own")
        ben.signIn()
        val details = ben.sync.inviteDetails(code)
        assertEquals("Home", details.ledgerName)
        assertEquals("anna@example.com", details.ownerEmail)

        ben.sessions.join(code)
        assertEquals(anna.ownLedger, ben.open.slot)
        assertEquals(setOf("Groceries"), ben.titles())
        assertTrue(ben.sync.status.value.sharedLedger)
        assertEquals("Home", ben.sync.status.value.ledger?.name)
        assertEquals(true, ben.sync.status.value.ledgerAccess?.writable)

        ben.post("Wine")
        ben.sync.syncNow()
        anna.sync.syncNow()
        assertEquals(setOf("Groceries", "Wine"), anna.titles())
        assertEquals(listOf("anna@example.com", "ben@example.com"), anna.sync.members(anna.ownLedger).map { it.email })
        // Ben's own ledger is untouched, in the cloud and on his phone.
        assertEquals(setOf("Ben's own"), cloudTitles(ben.ownLedger))
        ben.sessions.open(ben.ownLedger)
        assertFalse(ben.sync.status.value.sharedLedger)
        assertEquals(setOf("Ben's own"), ben.titles())
        // The shared copy stays on the phone for switching back.
        ben.sessions.open(anna.ownLedger)
        assertEquals(setOf("Groceries", "Wine"), ben.titles())
    }

    @Test
    fun changesMadeBeforeSwitchingAreUploadedFirst() = runBlocking<Unit> {
        val (anna, ben) = annaSharesWithBen()
        ben.sync.setEnabled(false)
        ben.post("Made while paused")
        ben.sync.setEnabled(true)
        // Realtime and the sync request run in the background; switching uploads in any case.
        ben.sessions.open(ben.ownLedger)
        assertEquals(setOf("Groceries", "Made while paused"), cloudTitles(anna.ownLedger))
    }

    @Test
    fun theOwnersSubscriptionCoversItsMembers() = runBlocking<Unit> {
        val (anna, ben) = annaSharesWithBen()
        server.withoutAccess += server.userIdFor("ben@example.com")
        ben.post("Ben without a subscription")
        ben.sync.syncNow()
        assertEquals(setOf("Groceries", "Ben without a subscription"), cloudTitles(anna.ownLedger))

        // Anna's ends: Ben's changes wait on his phone.
        server.withoutAccess += server.userIdFor("anna@example.com")
        ben.post("Waiting")
        ben.sync.syncNow()
        assertNull(ben.sync.status.value.lastError)
        assertTrue(ben.sync.status.value.notBackedUp)
        assertEquals(SyncAttention.NotBackedUp, ben.sync.status.value.attention)
        assertFalse("Waiting" in cloudTitles(anna.ownLedger))

        server.withoutAccess.clear()
        ben.sync.syncNow()
        assertTrue("Waiting" in cloudTitles(anna.ownLedger))
    }

    @Test
    fun aRemovedMemberIsToldAndCanRemoveTheCopy() = runBlocking<Unit> {
        val (anna, ben) = annaSharesWithBen()
        anna.sync.removeMember(anna.ownLedger, server.userIdFor("ben@example.com"))
        ben.sync.syncNow()
        assertTrue(ben.sync.status.value.lostLedgerAccess)
        assertEquals(SyncAttention.LostAccess, ben.sync.status.value.attention)

        ben.sessions.removeFromDevice(anna.ownLedger)
        assertNull(ben.open.slot)
        assertEquals(setOf("Ben's own"), ben.titles())
        assertEquals(emptyList<Any>(), ben.sessions.sharedOnDevice.value)
        assertFalse(ben.sync.status.value.lostLedgerAccess)
    }

    @Test
    fun leavingRemovesTheLedgerFromTheDeviceAndTheMemberList() = runBlocking<Unit> {
        val (anna, ben) = annaSharesWithBen()
        ben.sessions.leave(anna.ownLedger)
        assertNull(ben.open.slot)
        assertEquals(setOf("Ben's own"), ben.titles())
        assertEquals(listOf("anna@example.com"), anna.sync.members(anna.ownLedger).map { it.email })
        assertEquals(listOf(ben.ownLedger), ben.sync.refreshLedgers().map { it.id })
    }

    @Test
    fun signingOutRemovesSharedLedgersButKeepsTheOwn() = runBlocking<Unit> {
        val (anna, ben) = annaSharesWithBen()
        ben.sessions.signOut()
        assertNull(ben.open.slot)
        assertEquals(setOf("Ben's own"), ben.titles())
        assertEquals(emptyList<Any>(), ben.sessions.sharedOnDevice.value)

        // Signing in again brings the own ledger back without asking; the shared one opens on request.
        ben.signIn()
        assertEquals(setOf("Ben's own"), ben.titles())
        ben.sessions.open(anna.ownLedger)
        assertEquals(setOf("Groceries"), ben.titles())
    }

    @Test
    fun invitesAreTheOwnersToSendAndWorkOnce() = runBlocking<Unit> {
        val (anna, ben) = annaSharesWithBen()
        val notOwner = runCatching { ben.sync.createInvite(anna.ownLedger) }.exceptionOrNull()
        assertEquals("Only the ledger's owner can do that.", (notOwner as SyncException).message)

        val cleo = Device("cleo@example.com")
        cleo.signIn()
        val wrong = runCatching { cleo.sync.inviteDetails("NOPE") }.exceptionOrNull()
        assertEquals("That code isn't right. Check it and try again.", wrong?.message)

        val code = anna.sync.createInvite(anna.ownLedger).code
        cleo.sessions.join(code)
        val dana = Device("dana@example.com")
        dana.signIn()
        val used = runCatching { dana.sessions.join(code) }.exceptionOrNull()
        assertEquals("That code has already been used. Ask for a new one.", used?.message)
        assertNull(dana.open.slot)
    }

    @Test
    fun deletingTheAccountRemovesSharedLedgersFromTheDevice() = runBlocking<Unit> {
        val (_, ben) = annaSharesWithBen()
        ben.sessions.deleteAccount()
        assertNull(ben.open.slot)
        assertEquals(emptyList<Any>(), ben.sessions.sharedOnDevice.value)
        assertEquals(setOf("Ben's own"), ben.titles())
    }

    @Test
    fun theOwnerCannotLeaveTheirLedger() = runBlocking<Unit> {
        val (anna, _) = annaSharesWithBen()
        val error = runCatching { anna.sessions.leave(anna.ownLedger) }.exceptionOrNull()
        assertEquals("You own this ledger, so you can't leave it.", error?.message)
    }
}
