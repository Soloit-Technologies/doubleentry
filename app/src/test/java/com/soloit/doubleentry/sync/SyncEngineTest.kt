package com.soloit.doubleentry.sync

import com.soloit.doubleentry.data.local.nowMicros
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Frequency
import com.soloit.doubleentry.domain.RecurringRule
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.testing.FakePostgrest
import com.soloit.doubleentry.testing.TestLedger
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** Two devices syncing through one (fake) Supabase project. */
@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {
    private val server = FakePostgrest()
    private val devices = mutableListOf<Device>()
    private val a = Device("a")
    private val b = Device("b")
    private val day = LocalDate.of(2026, 9, 15)

    /** A device syncing [ledger]; signed in as [user] (row-level security applies) or with the plain token. */
    private inner class Device(name: String, user: String? = null, ledger: String = "ledger-1") {
        val t = TestLedger(name)
        private val prefs = SyncPreferences(t.context).apply { userId = user ?: "user-1"; ledgerId = ledger }
        private val engine = SyncEngine(t.db, SupabaseApi(SupabaseConfig("https://project.supabase.co", "anon", true), server.client), prefs)
        private val auth = AccessTokenProvider { user?.let { "t-$it" } ?: "token" }

        init {
            devices += this
        }

        /** One cycle as SyncManager runs it: pull, then push. Returns what the pull reported. */
        suspend fun sync(): PullResult = engine.pullAll(auth).also { engine.pushAll(auth) }

        /** Only the download half of a cycle. */
        suspend fun pull(): PullResult = engine.pullAll(auth)

        /** Only the upload half of a cycle. */
        suspend fun push(): PushResult = engine.pushAll(auth)

        suspend fun pending(): Int = t.db.syncStatus().pendingChanges().first()

        suspend fun account(number: Int): Account = t.ledger.getAccounts().single { it.number == number }

        suspend fun replaceCloudLedger(atMicros: Long) = engine.replaceCloudLedger(atMicros, auth)
    }

    @After
    fun tearDown() {
        devices.forEach { it.t.close() }
    }

    private fun draft(title: String, amount: Long, food: Account, bank: Account) =
        TransactionDraft(day, title, "", listOf(DraftLine(food.id, amount, EntryType.Debit), DraftLine(bank.id, amount, EntryType.Credit)))

    /** Device A creates a small ledger and both devices sync it. */
    private suspend fun sharedLedger(): String {
        val bank = a.t.account(1910, "Bank", AccountType.Asset)
        val food = a.t.account(5010, "Food", AccountType.Expense)
        val id = a.t.post(day, "Groceries", food, bank, 100_00)
        a.sync()
        b.sync()
        return id
    }

    @Test
    fun transactionsReplicateWithTheirLines() = runBlocking<Unit> {
        val id = sharedLedger()
        val tx = b.t.ledger.getTransaction(id)!!
        assertEquals("Groceries", tx.title)
        assertEquals(2, tx.entries.size)
        assertEquals(-100_00L, b.t.ledger.getBalances(day)[b.account(1910).id])
        // Nothing is dirty after a push, so a second cycle sends nothing.
        val writesBefore = server.writes.values.sum()
        a.sync()
        assertEquals(writesBefore, server.writes.values.sum())
    }

    @Test
    fun editsDeletesAndUndoReplicate() = runBlocking<Unit> {
        val id = sharedLedger()
        val bank = a.account(1910)
        val food = a.account(5010)
        a.t.ledger.updateTransaction(id, TransactionDraft(day, "Groceries ICA", "", listOf(DraftLine(food.id, 150_00, EntryType.Debit), DraftLine(bank.id, 150_00, EntryType.Credit))))
        a.sync()
        b.sync()
        assertEquals("Groceries ICA", b.t.ledger.getTransaction(id)!!.title)
        assertEquals(-150_00L, b.t.ledger.getBalances(day)[b.account(1910).id])

        val stamp = b.t.ledger.deleteTransaction(id)
        b.sync()
        a.sync()
        assertNull(a.t.ledger.getTransaction(id))

        b.t.ledger.restoreDeleted(stamp)
        b.sync()
        a.sync()
        val restored = a.t.ledger.getTransaction(id)!!
        assertEquals(listOf(150_00L, 150_00L), restored.entries.map { it.amount })
    }

    @Test
    fun theLaterEditWins() = runBlocking<Unit> {
        val id = sharedLedger()
        fun draft(title: String, food: Account, bank: Account) =
            TransactionDraft(day, title, "", listOf(DraftLine(food.id, 100_00, EntryType.Debit), DraftLine(bank.id, 100_00, EntryType.Credit)))

        a.t.ledger.updateTransaction(id, draft("From A", a.account(5010), a.account(1910)))
        a.sync()
        // B edits after A's change reached the server, then syncs: B's newer edit is kept and spreads.
        b.t.ledger.updateTransaction(id, draft("From B", b.account(5010), b.account(1910)))
        assertEquals(0, b.sync().replacedLocalChanges)
        assertEquals(0, a.sync().replacedLocalChanges)
        assertEquals("From B", a.t.ledger.getTransaction(id)!!.title)
        assertEquals("From B", b.t.ledger.getTransaction(id)!!.title)
        assertEquals(2, a.t.ledger.getTransaction(id)!!.entries.size)
    }

    @Test
    fun anEarlierOfflineEditLosesTogetherWithItsLines() = runBlocking<Unit> {
        val id = sharedLedger()
        // B edits offline first; A edits later and syncs before B comes back online.
        b.t.ledger.updateTransaction(id, draft("From B", 200_00, b.account(5010), b.account(1910)))
        a.t.ledger.updateTransaction(id, draft("From A", 300_00, a.account(5010), a.account(1910)))
        a.sync()
        // B's edit is discarded, and B is told so — once for the transaction, not per line.
        assertEquals(1, b.sync().replacedLocalChanges)
        assertEquals(0, a.sync().replacedLocalChanges)

        for (device in listOf(a, b)) {
            val tx = device.t.ledger.getTransaction(id)!!
            assertEquals("From A", tx.title)
            assertEquals(listOf(300_00L, 300_00L), tx.entries.map { it.amount })
        }
    }

    @Test
    fun aLaterEditReachingTheServerFirstStillWins() = runBlocking<Unit> {
        val id = sharedLedger()
        // Both edit offline: A first, B later. A comes online first, B afterwards.
        a.t.ledger.updateTransaction(id, draft("From A", 200_00, a.account(5010), a.account(1910)))
        b.t.ledger.updateTransaction(id, draft("From B", 300_00, b.account(5010), b.account(1910)))
        a.sync()
        // B's edit is the later one, although A's reached the server first. (A's had been
        // uploaded, so A lost no unsynced edit.)
        assertEquals(0, b.sync().replacedLocalChanges)
        assertEquals(0, a.sync().replacedLocalChanges)

        for (device in listOf(a, b)) {
            val tx = device.t.ledger.getTransaction(id)!!
            assertEquals("From B", tx.title)
            assertEquals(listOf(300_00L, 300_00L), tx.entries.map { it.amount })
        }
    }

    @Test
    fun aLaterEditElsewhereReplacesTheWholeTransaction() = runBlocking<Unit> {
        val id = sharedLedger()
        b.t.ledger.updateTransaction(id, draft("Groceries", 250_00, b.account(5010), b.account(1910)))
        // Later, A only moves the date (as changing an invoice's payment date does).
        val header = a.t.db.transactions().byId(id)!!
        a.t.db.transactions().upsert(header.copy(date = day.plusDays(1), updatedAt = com.soloit.doubleentry.data.local.nowMicros(), isDirty = true))
        a.sync()
        // A transaction wins or loses whole: never A's date with B's lines.
        assertEquals(1, b.sync().replacedLocalChanges)
        a.sync()

        for (device in listOf(a, b)) {
            val tx = device.t.ledger.getTransaction(id)!!
            assertEquals(day.plusDays(1), tx.date)
            assertEquals(listOf(100_00L, 100_00L), tx.entries.map { it.amount })
        }
    }

    @Test
    fun anEditArrivingAfterALaterOneIsRefusedAndReplaced() = runBlocking<Unit> {
        val id = sharedLedger()
        // A pulled before B's later edit reached the server, and uploads only afterwards.
        a.t.ledger.updateTransaction(id, draft("From A", 200_00, a.account(5010), a.account(1910)))
        b.t.ledger.updateTransaction(id, draft("From B", 300_00, b.account(5010), b.account(1910)))
        b.sync()
        val push = a.push()

        // The server kept B's edit, and A took it instead of overwriting it.
        assertEquals(1, push.replacedLocalChanges)
        val stored = server.liveRows("transactions").single { it.getValue("id").jsonPrimitive.content == id }
        assertEquals("From B", stored.getValue("title").jsonPrimitive.content)
        val tx = a.t.ledger.getTransaction(id)!!
        assertEquals("From B", tx.title)
        assertEquals(listOf(300_00L, 300_00L), tx.entries.map { it.amount })
        assertEquals(0, a.pending())
    }

    @Test
    fun membersOfALedgerSyncItWithoutTouchingTheirOtherLedgers() = runBlocking<Unit> {
        server.members["user-ben"] = mutableSetOf("ledger-anna")
        val anna = Device("anna", user = "user-anna", ledger = "ledger-anna")
        val ben = Device("ben", user = "user-ben", ledger = "ledger-anna")
        val bensOwn = Device("ben-own", user = "user-ben", ledger = "ledger-ben")
        bensOwn.t.account(1910, "Ben's bank", AccountType.Asset)
        bensOwn.sync()

        val bank = anna.t.account(1910, "Bank", AccountType.Asset)
        val food = anna.t.account(5010, "Food", AccountType.Expense)
        val id = anna.t.post(day, "Groceries", food, bank, 100_00)
        anna.sync()
        ben.sync()
        ben.t.ledger.updateTransaction(id, draft("Groceries and wine", 150_00, ben.account(5010), ben.account(1910)))
        ben.sync()
        anna.sync()
        assertEquals("Groceries and wine", anna.t.ledger.getTransaction(id)!!.title)
        // Same account numbers in both of Ben's ledgers, kept apart.
        assertEquals(listOf("Ben's bank"), bensOwn.t.ledger.getAccounts().map { it.name })

        // Replacing the shared ledger deletes only its rows, though Ben may write to both.
        val before = nowMicros()
        ben.t.ledger.deleteAllData()
        ben.replaceCloudLedger(before)
        assertEquals(0, server.liveRows("accounts", "ledger-anna").size)
        assertEquals(1, server.liveRows("accounts", "ledger-ben").size)
        anna.sync()
        assertEquals(emptyList<Account>(), anna.t.ledger.getAccounts())
    }

    @Test
    fun rowsChangedWhileAPullIsPagingAreNotSkipped() = runBlocking<Unit> {
        // More than one page of settings rows, stamped one microsecond apart.
        val base = parseTimestamp("2026-09-01T00:00:00Z")
        fun setting(i: Int, updatedAt: Long) = JsonObject(
            mapOf(
                "id" to JsonPrimitive("00000000-0000-4000-8000-%012d".format(i)),
                "ledger_id" to JsonPrimitive("ledger-1"),
                "key" to JsonPrimitive("Test.$i"),
                "value" to JsonPrimitive("v"),
                "modified_at" to JsonPrimitive(formatTimestamp(base + i)),
                "updated_at" to JsonPrimitive(formatTimestamp(updatedAt)),
                "deleted_at" to JsonNull,
            ),
        )
        val count = 1500
        for (i in 0 until count) server.insertRaw("user_settings", setting(i, base + i))
        // After the first page, another device changes a row from that page: it moves to the end.
        var changed = false
        server.afterPage = { table ->
            if (table == "user_settings" && !changed) {
                changed = true
                server.insertRaw("user_settings", setting(5, base + count + 1))
            }
        }
        b.sync()
        assertEquals(count, b.t.db.settings().liveWithPrefix("Test.").size)
    }

    @Test
    fun recurringPostingsFromTwoDevicesConvergeWithoutDuplicates() = runBlocking<Unit> {
        sharedLedger()
        val rule = RecurringRule(
            "rent", "Rent", "",
            listOf(DraftLine(a.account(5010).id, 9_000_00, EntryType.Debit), DraftLine(a.account(1910).id, 9_000_00, EntryType.Credit)),
            Frequency.Monthly, LocalDate.of(2026, 9, 25),
        )
        a.t.recurring.save(rule, today = LocalDate.of(2026, 9, 25))
        a.sync()
        b.sync()
        assertEquals(2, b.t.liveTransactionCount())

        // Both devices are offline on 25 October and post the occurrence independently.
        val october = LocalDate.of(2026, 10, 25)
        assertEquals(1, a.t.recurring.process(october))
        assertEquals(1, b.t.recurring.process(october))
        a.sync()
        // The same posting made on both devices is not a lost edit.
        assertEquals(0, b.sync().replacedLocalChanges)
        assertEquals(0, a.sync().replacedLocalChanges)

        assertEquals(3, a.t.liveTransactionCount())
        assertEquals(3, b.t.liveTransactionCount())
        assertEquals(3, server.rows("transactions").size)
        assertEquals(6, server.rows("transactions").sumOf { it.getValue("lines").jsonArray.size })
        assertEquals(-18_100_00L, b.t.ledger.getBalances(october)[b.account(1910).id])
        assertTrue(b.t.recurring.getRule("rent")!!.lastPostedDate == october)
    }

    @Test
    fun anEditDeletedElsewhereIsReported() = runBlocking<Unit> {
        val id = sharedLedger()
        b.t.ledger.updateTransaction(
            id,
            TransactionDraft(day, "Edited on B", "", listOf(DraftLine(b.account(5010).id, 120_00, EntryType.Debit), DraftLine(b.account(1910).id, 120_00, EntryType.Credit))),
        )
        a.t.ledger.deleteTransaction(id)
        a.sync()
        assertEquals(1, b.sync().replacedLocalChanges)
        assertNull(b.t.ledger.getTransaction(id))
    }

    @Test
    fun editsMadeOfflineBeforeALedgerWasReplacedAreDropped() = runBlocking<Unit> {
        sharedLedger()
        // B, offline, adds a transaction; then A replaces the ledger (as an import does).
        b.t.post(day, "Offline on B", b.account(5010), b.account(1910), 5_00)
        val before = nowMicros()
        a.t.ledger.deleteAllData()
        val cash = a.t.account(1930, "Cash", AccountType.Asset)
        val rent = a.t.account(5020, "Rent", AccountType.Expense)
        a.t.post(day, "Imported", rent, cash, 900_00)
        a.replaceCloudLedger(before)

        b.pull()
        // The transaction referred to accounts the replaced ledger no longer has: it never comes back.
        assertEquals(1, b.push().replacedLocalChanges)
        assertEquals(listOf("Imported"), b.t.ledger.getTransactions().map { it.title })
        assertEquals(0, b.pending())
        assertEquals(listOf("Imported"), server.liveRows("transactions").map { it.getValue("title").jsonPrimitive.content })

        // B's edits from after the replacement still upload.
        b.t.post(day, "Later on B", b.account(5020), b.account(1930), 1_00)
        b.sync()
        a.sync()
        assertEquals(listOf("Imported", "Later on B"), a.t.ledger.getTransactions().map { it.title }.sorted())
    }

    @Test
    fun pendingChangesCountTransactionsOnceAndClearAfterPush() = runBlocking<Unit> {
        val bank = a.t.account(1910, "Bank", AccountType.Asset)
        val food = a.t.account(5010, "Food", AccountType.Expense)
        a.t.post(day, "Groceries", food, bank, 100_00)
        // Two accounts and one transaction (its two lines don't count separately).
        assertEquals(3, a.pending())
        a.sync()
        assertEquals(0, a.pending())
    }
}
