package com.soloit.doubleentry.sync

import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Frequency
import com.soloit.doubleentry.domain.RecurringRule
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.testing.FakePostgrest
import com.soloit.doubleentry.testing.TestLedger
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
    private val a = Device("a")
    private val b = Device("b")
    private val day = LocalDate.of(2026, 9, 15)

    private inner class Device(name: String) {
        val t = TestLedger(name)
        private val prefs = SyncPreferences(t.context).apply { userId = "user-1" }
        private val engine = SyncEngine(t.db, SupabaseApi(SupabaseConfig("https://project.supabase.co", "anon", true), server.client), prefs)
        private val auth = AccessTokenProvider { "token" }

        /** One cycle as SyncManager runs it: pull, then push. */
        suspend fun sync() {
            engine.pullAll(auth)
            engine.pushAll(auth)
        }

        suspend fun account(number: Int): Account = t.ledger.getAccounts().single { it.number == number }
    }

    @After
    fun tearDown() {
        a.t.close()
        b.t.close()
    }

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
        b.sync()
        a.sync()
        assertEquals("From B", a.t.ledger.getTransaction(id)!!.title)
        assertEquals("From B", b.t.ledger.getTransaction(id)!!.title)
        assertEquals(2, a.t.ledger.getTransaction(id)!!.entries.size)
    }

    @Test
    fun anEarlierOfflineEditLosesTogetherWithItsLines() = runBlocking<Unit> {
        val id = sharedLedger()
        fun draft(title: String, amount: Long, food: Account, bank: Account) =
            TransactionDraft(day, title, "", listOf(DraftLine(food.id, amount, EntryType.Debit), DraftLine(bank.id, amount, EntryType.Credit)))

        // B edits offline first; A edits later and syncs before B comes back online.
        b.t.ledger.updateTransaction(id, draft("From B", 200_00, b.account(5010), b.account(1910)))
        a.t.ledger.updateTransaction(id, draft("From A", 300_00, a.account(5010), a.account(1910)))
        a.sync()
        b.sync()
        a.sync()

        for (device in listOf(a, b)) {
            val tx = device.t.ledger.getTransaction(id)!!
            assertEquals("From A", tx.title)
            assertEquals(listOf(300_00L, 300_00L), tx.entries.map { it.amount })
        }
    }

    @Test
    fun aHeaderOnlyChangeElsewhereKeepsLocallyReplacedLines() = runBlocking<Unit> {
        val id = sharedLedger()
        b.t.ledger.updateTransaction(
            id,
            TransactionDraft(day, "Groceries", "", listOf(DraftLine(b.account(5010).id, 250_00, EntryType.Debit), DraftLine(b.account(1910).id, 250_00, EntryType.Credit))),
        )
        // Meanwhile A only moves the date (as changing an invoice's payment date does).
        val header = a.t.db.transactions().byId(id)!!
        a.t.db.transactions().upsert(header.copy(date = day.plusDays(1), updatedAt = com.soloit.doubleentry.data.local.nowMicros(), isDirty = true))
        a.sync()
        b.sync()
        a.sync()

        for (device in listOf(a, b)) {
            val tx = device.t.ledger.getTransaction(id)!!
            assertEquals(day.plusDays(1), tx.date)
            assertEquals(listOf(250_00L, 250_00L), tx.entries.map { it.amount })
        }
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
        b.sync()
        a.sync()

        assertEquals(3, a.t.liveTransactionCount())
        assertEquals(3, b.t.liveTransactionCount())
        assertEquals(3, server.rows("transactions").size)
        assertEquals(6, server.rows("transaction_entries").size)
        assertEquals(-18_100_00L, b.t.ledger.getBalances(october)[b.account(1910).id])
        assertTrue(b.t.recurring.getRule("rent")!!.lastPostedDate == october)
    }
}
