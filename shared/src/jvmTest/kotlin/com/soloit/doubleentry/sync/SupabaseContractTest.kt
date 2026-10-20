package com.soloit.doubleentry.sync

import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.data.local.nowMicros
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.testing.FakeBackend
import com.soloit.doubleentry.testing.LocalBackend
import com.soloit.doubleentry.testing.MemoryTokenStore
import com.soloit.doubleentry.testing.TestBackend
import com.soloit.doubleentry.testing.TestLedger
import com.soloit.doubleentry.testing.TestUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * What the sync code relies on the server for, checked against the in-memory fake (always) and a
 * real local Supabase stack (in CI, see [LocalSupabaseContractTest]). A test failing only on the
 * fake means the fake, and so every test built on it, no longer behaves like the real project.
 */
abstract class SupabaseContract {
    protected abstract val backend: TestBackend
    protected val api by lazy { SupabaseApi(backend.config, backend.client) }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private val start = nowMicros()
    private val day = LocalDate.of(2026, 9, 15)
    private val devices = mutableListOf<Device>()
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
        devices.forEach { it.t.close() }
    }

    private fun uuid() = UUID.randomUUID().toString()

    protected fun account(ledger: String, number: Int, modified: Long, id: String = uuid(), name: String = "Account $number") =
        AccountRow(id, ledger, number, name, AccountType.Asset.ordinal, formatTimestamp(modified))

    protected suspend fun upsert(user: TestUser, vararg rows: AccountRow): List<AccountRow> =
        decode(api.upsert(SyncTable.Accounts, JsonArray(rows.map { json.encodeToJsonElement(AccountRow.serializer(), it) }), user.token))

    protected suspend fun accounts(user: TestUser, ledger: String = user.ledgerId): List<AccountRow> =
        decode(api.select(SyncTable.Accounts, ledger, since = null, after = null, limit = 1000, user.token))

    private fun decode(rows: JsonArray): List<AccountRow> = json.decodeFromJsonElement(ListSerializer(AccountRow.serializer()), rows)

    private fun stored(table: String, ledger: String, id: String): JsonObject =
        backend.rows(table, ledger).single { it.getValue("id").jsonPrimitive.content == id }

    protected suspend inline fun <reified T : Throwable> failsWith(block: () -> Unit): T {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected ${T::class.simpleName}, got $error", error is T)
        return error as T
    }

    /** A device syncing [user]'s own ledger through [SyncEngine], as the app does. */
    protected inner class Device(name: String, user: TestUser, ledger: String = user.ledgerId) {
        val t = TestLedger(name)
        private val prefs = SyncPreferences(t.stores, MemoryTokenStore()).apply { userId = user.id; ledgerId = ledger }
        private val auth = AccessTokenProvider { user.token }
        val engine = SyncEngine(t.db, api, prefs)

        init {
            devices += this
        }

        suspend fun sync(): PullResult = engine.pullAll(auth).also { engine.pushAll(auth) }

        suspend fun replaceCloudLedger(atMicros: Long) = engine.replaceCloudLedger(atMicros, auth)

        suspend fun titles(): List<String> = t.ledger.getTransactions().map { it.title }.sorted()
    }

    @Test
    fun uploadsAreStampedAndAttributedByTheServer() = runBlocking<Unit> {
        val me = backend.newUser("me")
        val row = account(me.ledgerId, 1910, start)
        val back = upsert(me, row).single()
        assertEquals(row.id, back.id)
        // Edit times keep their microseconds: they decide conflicts.
        assertEquals(row.modifiedAtMicros, back.modifiedAtMicros)
        assertNotNull(back.updatedAt)
        assertEquals(me.id, stored("accounts", me.ledgerId, row.id).getValue("user_id").jsonPrimitive.content)
        assertEquals(listOf(back), accounts(me))
    }

    @Test
    fun anOlderEditIsLeftOutOfTheResponseAndTheNewerOneKept() = runBlocking<Unit> {
        val me = backend.newUser("me")
        val id = uuid()
        val first = upsert(me, account(me.ledgerId, 1910, start + 2_000, id, name = "Newer")).single()

        val other = account(me.ledgerId, 1920, start)
        val response = upsert(me, account(me.ledgerId, 1910, start + 1_000, id, name = "Older"), other)
        assertEquals(listOf(other.id), response.map { it.id })
        val kept = accounts(me).single { it.id == id }
        assertEquals("Newer", kept.name)
        // A refused edit doesn't move the pull cursor either.
        assertEquals(first.updatedAtMicros, kept.updatedAtMicros)
    }

    @Test
    fun theSameEditSentAgainIsAccepted() = runBlocking<Unit> {
        // A device whose response was lost sends the same edit again.
        val me = backend.newUser("me")
        val row = account(me.ledgerId, 1910, start)
        upsert(me, row)
        assertEquals(listOf(row.id), upsert(me, row).map { it.id })
    }

    @Test
    fun pagesContinueAfterTheLastRowWhenStampsTie() = runBlocking<Unit> {
        val me = backend.newUser("me")
        // One request: the real server stamps all of them alike.
        val sent = (1..7).map { account(me.ledgerId, 1900 + it, start) }
        upsert(me, *sent.toTypedArray())

        val read = mutableListOf<AccountRow>()
        var after: SupabaseApi.PageCursor? = null
        while (true) {
            val page = decode(api.select(SyncTable.Accounts, me.ledgerId, since = null, after = after, limit = 3, me.token))
            read += page
            if (page.size < 3) break
            after = SupabaseApi.PageCursor(formatTimestamp(page.last().updatedAtMicros), page.last().id)
        }
        assertEquals(sent.map { it.id }.sorted(), read.map { it.id }.sorted())
        assertEquals(read.sortedWith(compareBy({ it.updatedAtMicros }, { it.id })), read)

        // A watermark at the first stamp reads them all again.
        val since = formatTimestamp(read.minOf { it.updatedAtMicros })
        assertEquals(7, api.select(SyncTable.Accounts, me.ledgerId, since, after = null, limit = 1000, me.token).size)
        val ids = sent.take(2).map { it.id }
        assertEquals(ids.sorted(), decode(api.selectByIds(SyncTable.Accounts, me.ledgerId, ids, me.token)).map { it.id }.sorted())
    }

    @Test
    fun replacingALedgerDeletesOnlyItsOwnOlderRows() = runBlocking<Unit> {
        val me = backend.newUser("me")
        val other = backend.newUser("other")
        val old = account(me.ledgerId, 1910, start)
        val newer = account(me.ledgerId, 1920, start + 10_000_000)
        upsert(me, old, newer)
        // Ids are unique per ledger only: the other ledger may hold the same one.
        upsert(other, account(other.ledgerId, 1910, start, id = old.id))

        val at = start + 5_000_000
        api.tombstoneBefore(SyncTable.Accounts, me.ledgerId, formatTimestamp(at), me.token)
        val mine = accounts(me).associateBy { it.id }
        assertEquals(at, mine.getValue(old.id).deletedAt?.let(::parseTimestamp))
        assertEquals(at, mine.getValue(old.id).modifiedAtMicros)
        assertNull(mine.getValue(newer.id).deletedAt)
        assertNull(accounts(other).single().deletedAt)
        assertTrue(api.hasLiveRows(SyncTable.Accounts, me.ledgerId, me.token))

        // Nobody else can wipe it.
        api.tombstoneBefore(SyncTable.Accounts, me.ledgerId, formatTimestamp(start + 100_000_000), other.token)
        assertNull(accounts(me).single { it.id == newer.id }.deletedAt)
    }

    @Test
    fun aReplacedLedgerRefusesEditsFromBeforeTheReplacement() = runBlocking<Unit> {
        val me = backend.newUser("me")
        val other = backend.newUser("other")
        val existing = upsert(me, account(me.ledgerId, 1910, start)).single()
        val at = nowMicros()
        upsert(me, account(me.ledgerId, 1915, at - 1))
        failsWith<SyncException> { api.markLedgerReplaced(me.ledgerId, formatTimestamp(at), other.token) }
        api.markLedgerReplaced(me.ledgerId, formatTimestamp(at), me.token)

        // Rows edited before then are left out of the response like older edits: new ones and edits.
        val stale = existing.copy(name = "Stale", modifiedAt = formatTimestamp(at - 1), updatedAt = null)
        assertEquals(emptyList<AccountRow>(), upsert(me, account(me.ledgerId, 1920, at - 1), stale))
        assertEquals(listOf("Account 1910", "Account 1915"), accounts(me).map { it.name }.sorted())
        assertEquals(1, upsert(me, account(me.ledgerId, 1930, at + 1)).size)
        // The replacement's own tombstones carry its time.
        api.tombstoneBefore(SyncTable.Accounts, me.ledgerId, formatTimestamp(at), me.token)
        assertEquals(listOf(1930), accounts(me).filter { it.deletedAt == null }.map { it.number })

        // It never moves back, nor past now: the ledger mustn't lock everyone out.
        api.markLedgerReplaced(me.ledgerId, formatTimestamp(start), me.token)
        assertEquals(emptyList<AccountRow>(), upsert(me, account(me.ledgerId, 1940, at - 1)))
        api.markLedgerReplaced(me.ledgerId, formatTimestamp(nowMicros() + 3_600_000_000), me.token)
        assertEquals(1, upsert(me, account(me.ledgerId, 1950, nowMicros() + 1_000_000)).size)
    }

    @Test
    fun aDeviceOfflineDuringAReplacementDoesNotBringOldRowsBack() = runBlocking<Unit> {
        val me = backend.newUser("me")
        val a = Device("a", me)
        val bank = a.t.account(1910, "Bank", AccountType.Asset)
        val food = a.t.account(5010, "Food", AccountType.Expense)
        a.t.post(day, "Groceries", food, bank, 100_00)
        a.sync()
        val b = Device("b", me)
        b.sync()

        val accounts = b.t.ledger.getAccounts().associateBy { it.number }
        b.t.post(day, "Offline on B", accounts.getValue(5010), accounts.getValue(1910), 5_00)
        val before = nowMicros()
        a.t.ledger.deleteAllData()
        a.replaceCloudLedger(before)

        b.sync()
        assertEquals(emptyList<String>(), b.titles())
        assertEquals(0, b.t.db.syncStatus().pendingChanges().first())
        assertTrue(backend.rows("transactions", me.ledgerId).none { it["title"]?.jsonPrimitive?.content == "Offline on B" })
    }

    @Test
    fun anotherUsersLedgerCanBeNeitherReadNorWritten() = runBlocking<Unit> {
        val me = backend.newUser("me")
        val stranger = backend.newUser("stranger")
        upsert(me, account(me.ledgerId, 1910, start))

        assertEquals(emptyList<AccountRow>(), accounts(stranger, me.ledgerId))
        assertFalse(api.hasLiveRows(SyncTable.Accounts, me.ledgerId, stranger.token))
        failsWith<CloudAccessRequiredException> { upsert(stranger, account(me.ledgerId, 1920, start)) }
        assertEquals(1, accounts(me).size)
    }

    @Test
    fun withoutCloudAccessUploadsAreRefusedButReadsStillWork() = runBlocking<Unit> {
        val me = backend.newUser("me")
        upsert(me, account(me.ledgerId, 1910, start))
        assertTrue(api.cloudAccess(me.token).active)

        backend.setCloudAccess(me, active = false)
        assertFalse(api.cloudAccess(me.token).active)
        failsWith<CloudAccessRequiredException> { upsert(me, account(me.ledgerId, 1920, start)) }
        assertEquals(1, accounts(me).size)
    }

    @Test
    fun invitedMembersShareTheLedgerUntilRemoved() = runBlocking<Unit> {
        val owner = backend.newUser("owner")
        val guest = backend.newUser("guest")
        val late = backend.newUser("late")
        assertEquals(owner.ledgerId, api.ownLedger(owner.token))

        val notOwner = failsWith<SyncException> { api.createInvite(owner.ledgerId, "editor", guest.token) }
        assertEquals("Only the ledger's owner can do that.", notOwner.message)
        val invite = api.createInvite(owner.ledgerId, "editor", owner.token)
        val details = api.inviteDetails(invite.code, guest.token)
        assertEquals(owner.ledgerId, details.ledgerId)
        assertFalse(details.alreadyMember)
        assertEquals(owner.ledgerId, api.acceptInvite(invite.code, guest.token))
        assertEquals("That code has already been used. Ask for a new one.", failsWith<SyncException> { api.acceptInvite(invite.code, late.token) }.message)

        val access = api.ledgerAccess(owner.ledgerId, guest.token)
        assertEquals("editor", access.role)
        assertTrue(access.writable)
        val row = upsert(guest, account(owner.ledgerId, 1910, start)).single()
        assertEquals(listOf(row.id), accounts(owner).map { it.id })
        assertEquals(guest.id, stored("accounts", owner.ledgerId, row.id).getValue("user_id").jsonPrimitive.content)
        assertEquals(setOf("owner", "editor"), api.listMembers(owner.ledgerId, owner.token).map { it.role }.toSet())
        assertEquals(setOf(owner.ledgerId, guest.ledgerId), api.listLedgers(guest.token).map { it.id }.toSet())

        assertEquals("Only the ledger's owner can do that.", failsWith<SyncException> { api.renameLedger(owner.ledgerId, "Ours", guest.token) }.message)
        api.renameLedger(owner.ledgerId, "Household", owner.token)
        assertEquals("Household", api.listLedgers(guest.token).single { it.id == owner.ledgerId }.name)

        // The owner's subscription covers every member.
        backend.setCloudAccess(owner, active = false)
        assertFalse(api.ledgerAccess(owner.ledgerId, guest.token).writable)
        failsWith<CloudAccessRequiredException> { upsert(guest, account(owner.ledgerId, 1920, start)) }
        backend.setCloudAccess(owner, active = true)

        assertEquals("You own this ledger, so you can't leave it.", failsWith<SyncException> { api.removeMember(owner.ledgerId, owner.id, owner.token) }.message)
        api.removeMember(owner.ledgerId, guest.id, owner.token)
        assertNull(api.ledgerAccess(owner.ledgerId, guest.token).role)
        assertEquals(emptyList<AccountRow>(), accounts(guest, owner.ledgerId))
    }

    @Test
    fun deletingAnAccountTakesItsLedgerAndLeavesItsEditsElsewhere() = runBlocking<Unit> {
        val owner = backend.newUser("owner")
        val guest = backend.newUser("guest")
        api.acceptInvite(api.createInvite(owner.ledgerId, "editor", owner.token).code, guest.token)
        val shared = upsert(guest, account(owner.ledgerId, 1910, start)).single()
        upsert(guest, account(guest.ledgerId, 1910, start))

        api.deleteAccount(guest.token)
        assertEquals(emptyList<JsonObject>(), backend.rows("accounts", guest.ledgerId))
        assertEquals(JsonNull, stored("accounts", owner.ledgerId, shared.id)["user_id"])
        assertEquals(listOf(owner.ledgerId), api.listLedgers(owner.token).map { it.id })
        assertEquals(1, api.listMembers(owner.ledgerId, owner.token).size)
    }

    @Test
    fun transactionLinesKeepExactAmounts() = runBlocking<Unit> {
        val me = backend.newUser("me")
        val bank = account(me.ledgerId, 1910, start)
        val food = account(me.ledgerId, 5010, start)
        upsert(me, bank, food)
        val lines = listOf(
            LineRow(uuid(), food.id, 1, EntryType.Debit.ordinal),
            LineRow(uuid(), food.id, 10, EntryType.Debit.ordinal),
            LineRow(uuid(), food.id, 1_234_567_89, EntryType.Debit.ordinal),
            LineRow(uuid(), bank.id, 1_234_568_00, EntryType.Credit.ordinal),
        )
        val tx = TransactionRow(uuid(), me.ledgerId, day.toString(), "Rent", "", lines, formatTimestamp(start))
        api.upsert(SyncTable.Transactions, JsonArray(listOf(json.encodeToJsonElement(TransactionRow.serializer(), tx))), me.token)
        val back = json.decodeFromJsonElement(
            TransactionRow.serializer(),
            api.selectByIds(SyncTable.Transactions, me.ledgerId, listOf(tx.id), me.token).single(),
        )
        assertEquals(lines, back.lines)
    }

    @Test
    fun theServiceSwitchAndErrorReportsWork() = runBlocking<Unit> {
        // Read before every sync, also signed out.
        val control = api.syncControl()
        assertFalse(control.paused)
        assertEquals(0, control.minVersionCode)

        val me = backend.newUser("me")
        api.reportSyncError(SyncErrorReport.of(IllegalStateException("Contract test"), appVersion = 1, pendingChanges = 0), me.token)
    }

    @Test
    fun twoDevicesConvergeAndTheLaterEditWins() = runBlocking<Unit> {
        val me = backend.newUser("me")
        val a = Device("a", me)
        val bank = a.t.account(1910, "Bank", AccountType.Asset)
        val food = a.t.account(5010, "Food", AccountType.Expense)
        val id = a.t.post(day, "Groceries", food, bank, 100_00)
        a.sync()
        val b = Device("b", me)
        b.sync()
        assertEquals(listOf("Groceries"), b.titles())

        fun draft(title: String) = TransactionDraft(day, title, "", listOf(DraftLine(food.id, 100_00, EntryType.Debit), DraftLine(bank.id, 100_00, EntryType.Credit)))
        a.t.ledger.updateTransaction(id, draft("From A"))
        b.t.ledger.updateTransaction(id, draft("From B"))
        a.sync()
        assertEquals(0, b.sync().replacedLocalChanges)
        a.sync()
        assertEquals(listOf("From B"), a.titles())
        assertEquals(listOf("From B"), b.titles())
        assertEquals(2, a.t.ledger.getTransaction(id)!!.entries.size)
    }

    @Test
    fun aPullReadsMoreThanOnePage() = runBlocking<Unit> {
        // More rows than one page (1000), which is also the server's max_rows.
        val me = backend.newUser("me")
        val a = Device("a", me)
        val bank = a.t.account(1910, "Bank", AccountType.Asset)
        val food = a.t.account(5010, "Food", AccountType.Expense)
        repeat(1_100) { a.t.post(day, "Posting $it", food, bank, 1_00) }
        a.sync()

        val b = Device("b", me)
        b.sync()
        assertEquals(1_100, b.t.liveTransactionCount())
    }

    @Test
    fun aReplacedLedgerReachesADeviceThatSyncedHalfway() = runBlocking<Unit> {
        val me = backend.newUser("me")
        val a = Device("a", me)
        a.t.account(1910, "Bank", AccountType.Asset)
        a.t.settings.put(SettingsRepository.Keys.TAX_PREFIXES, "89")
        a.sync()
        val b = Device("b", me)
        b.sync()

        // Delete all on A: the cloud's rows are deleted, B syncs, and only then A's upload arrives.
        val before = nowMicros()
        a.t.ledger.deleteAllData()
        for (table in SyncTable.entries.reversed()) api.tombstoneBefore(table, me.ledgerId, formatTimestamp(before), me.token)
        b.sync()
        assertNull(b.t.settings.get(SettingsRepository.Keys.TAX_PREFIXES))
        a.replaceCloudLedger(before)

        b.sync()
        assertEquals("89", b.t.settings.get(SettingsRepository.Keys.TAX_PREFIXES))
    }
}

/** The contract against the in-memory fake: always runs. */
class FakeSupabaseContractTest : SupabaseContract() {
    override val backend = FakeBackend()
}

/**
 * The contract against a real local Supabase stack with every migration applied. Skipped unless
 * SUPABASE_TEST_URL is set; see supabase/README.md (Testing against a local stack).
 */
class LocalSupabaseContractTest : SupabaseContract() {
    private val local = LocalBackend.fromEnvironment()
    override val backend: TestBackend get() = checkNotNull(local)

    @org.junit.Before
    fun requireStack() = assumeTrue("SUPABASE_TEST_URL isn't set", local != null)

    @Test
    fun realtimeAnnouncesChangesToTheLedgerItWatches() = runBlocking<Unit> {
        val owner = backend.newUser("owner")
        val me = backend.newUser("me")
        api.acceptInvite(api.createInvite(owner.ledgerId, "editor", owner.token).code, me.token)

        val events = Channel<Unit>(Channel.UNLIMITED)
        val realtime = RealtimeClient(backend.config, backend.client, { me.token }, ledgerId = { me.ledgerId }) { events.trySend(Unit) }
        scope.launch { realtime.run() }

        // The subscription takes a moment; keep writing until the first event arrives.
        val first = withTimeoutOrNull(30.seconds) {
            var number = 1000
            while (true) {
                upsert(me, account(me.ledgerId, number++, nowMicros()))
                if (withTimeoutOrNull(1.seconds) { events.receive() } != null) break
            }
        }
        assertNotNull("No Realtime event within 30 seconds", first)
        withTimeoutOrNull(2.seconds) { while (true) events.receive() }

        // A change to another ledger the user can see isn't this one's business.
        upsert(me, account(owner.ledgerId, 1910, nowMicros()))
        assertNull(withTimeoutOrNull(3.seconds) { events.receive() })
        upsert(me, account(me.ledgerId, 1, nowMicros()))
        assertNotNull(withTimeoutOrNull(10.seconds) { events.receive() })
    }

    @Test
    fun realtimeAnnouncesAnotherMembersChangesToASharedLedger() = runBlocking<Unit> {
        val owner = backend.newUser("owner")
        val me = backend.newUser("me")
        api.acceptInvite(api.createInvite(owner.ledgerId, "editor", owner.token).code, me.token)

        val events = Channel<Unit>(Channel.UNLIMITED)
        val realtime = RealtimeClient(backend.config, backend.client, { me.token }, ledgerId = { owner.ledgerId }) { events.trySend(Unit) }
        scope.launch { realtime.run() }

        // Once subscribed, the client asks for a catch-up sync by itself.
        assertNotNull("No catch-up event within 30 seconds", withTimeoutOrNull(30.seconds) { events.receive() })
        withTimeoutOrNull(2.seconds) { while (true) events.receive() }

        // Only the owner writes: the member's phone must hear about it without syncing.
        upsert(owner, account(owner.ledgerId, 1000, nowMicros()))
        assertNotNull("No Realtime event for the owner's change", withTimeoutOrNull(10.seconds) { events.receive() })
    }
}
