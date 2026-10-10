package com.soloit.doubleentry.data

import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.CostPeriod
import com.soloit.doubleentry.domain.CostPeriodAccounts
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.LedgerException
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.testing.TestLedger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
class CostPeriodsTest {
    private val t = TestLedger()
    private val paid = LocalDate.of(2026, 9, 20)
    private val insurance = CostPeriod(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 12, 15))

    @After
    fun tearDown() = t.close()

    /** The accounts most tests use; [interims] adds prepaid and accrued expense accounts and chooses them in settings. */
    private suspend fun accounts(interims: Boolean = true): Triple<Account, Account, Account> {
        if (interims) {
            t.account(1700, "Prepaid Expenses", AccountType.Asset)
            t.account(2420, "Accrued Expenses", AccountType.Liability)
            t.settings.saveCostPeriodAccounts(CostPeriodAccounts(prepaidExpenses = 1700, accruedExpenses = 2420))
        }
        return Triple(
            t.account(1930, "Checking", AccountType.Asset),
            t.account(8510, "Home Insurance", AccountType.Expense),
            t.account(3110, "Salary", AccountType.Revenue),
        )
    }

    private fun insuranceDraft(bankId: String, expenseId: String, date: LocalDate = paid, period: CostPeriod? = insurance) =
        TransactionDraft(date, "Insurance", "", listOf(DraftLine(expenseId, 620_00, EntryType.Debit, period = period), DraftLine(bankId, 620_00, EntryType.Credit)))

    private suspend fun expenseIn(month: YearMonth, accountId: String) =
        t.ledger.getMovements(month.atDay(1), month.atEndOfMonth())[accountId] ?: 0L

    @Test
    fun spreadsTheExpenseOverItsMonths() = runBlocking<Unit> {
        val (bank, expense, _) = accounts()
        val id = t.ledger.post(insuranceDraft(bank.id, expense.id))

        assertEquals(0L, expenseIn(YearMonth.of(2026, 9), expense.id))
        assertEquals(170_00L, expenseIn(YearMonth.of(2026, 10), expense.id))
        assertEquals(300_00L, expenseIn(YearMonth.of(2026, 11), expense.id))
        assertEquals(150_00L, expenseIn(YearMonth.of(2026, 12), expense.id))
        assertEquals(-620_00L, t.ledger.getBalances(paid)[bank.id])

        // The prepaid account is empty once the period is over.
        val prepaid = t.ledger.getAccounts().single { it.number == 1700 }
        assertEquals(620_00L, t.ledger.getBalances(paid)[prepaid.id])
        assertEquals(0L, t.ledger.getBalances(LocalDate.of(2026, 12, 31))[prepaid.id])
        assertEquals(4, t.liveTransactionCount())

        val tx = t.ledger.getTransaction(id)!!
        assertEquals(listOf(DraftLine(expense.id, 620_00, EntryType.Debit, period = insurance), DraftLine(bank.id, 620_00, EntryType.Credit)), t.ledger.editableLines(tx))
        val summary = t.ledger.getCostPeriods(tx)!!
        assertEquals(listOf(170_00L, 300_00L, 150_00L), summary.lines.single().shares.map { it.amount })

        val posting = summary.lines.single().shares.first().postingId!!
        assertEquals(id, t.ledger.periodSourceOf(posting))
        assertNull(t.ledger.periodSourceOf(id))
        assertEquals(LocalDate.of(2026, 10, 31), t.ledger.getTransaction(posting)!!.date)
    }

    @Test
    fun consumedLastMonthIsAccrued() = runBlocking<Unit> {
        val (bank, expense, _) = accounts()
        t.ledger.post(insuranceDraft(bank.id, expense.id, date = LocalDate.of(2026, 10, 5), period = CostPeriod.month(YearMonth.of(2026, 9))))
        assertEquals(620_00L, expenseIn(YearMonth.of(2026, 9), expense.id))
        assertEquals(0L, expenseIn(YearMonth.of(2026, 10), expense.id))
        val accrued = t.ledger.getAccounts().single { it.number == 2420 }
        assertEquals(620_00L, t.ledger.getBalances(LocalDate.of(2026, 9, 30))[accrued.id])
        assertEquals(0L, t.ledger.getBalances(LocalDate.of(2026, 10, 5))[accrued.id])
    }

    @Test
    fun configuredInterimAccountsAreUsed() = runBlocking<Unit> {
        val (bank, expense, _) = accounts(interims = false)
        val mine = t.account(1790, "My prepaid", AccountType.Asset)
        t.settings.saveCostPeriodAccounts(CostPeriodAccounts(prepaidExpenses = 1790))
        t.ledger.post(insuranceDraft(bank.id, expense.id))
        assertEquals(620_00L, t.ledger.getBalances(paid)[mine.id])
        assertTrue(t.ledger.getAccounts().none { it.number == 1700 })

        t.settings.saveCostPeriodAccounts(CostPeriodAccounts(prepaidExpenses = 1799))
        assertThrows(LedgerException::class.java) { runBlocking { t.ledger.post(insuranceDraft(bank.id, expense.id)) } }
    }

    @Test
    fun interimAccountsMustBeChosen() = runBlocking<Unit> {
        val (bank, expense, _) = accounts(interims = false)
        // Accounts with the usual numbers aren't used until they are chosen.
        t.account(1700, "Prepaid Expenses", AccountType.Asset)
        // Paid in November: October is accrued and December prepaid, so both accounts are needed.
        val error = assertThrows(LedgerException::class.java) {
            runBlocking { t.ledger.post(insuranceDraft(bank.id, expense.id, date = LocalDate.of(2026, 11, 3))) }
        }
        assertEquals("Choose accounts for prepaid expenses and accrued expenses in Settings > Cost periods.", error.message)
        assertTrue(t.ledger.getAccounts().none { it.number == 2420 })
        assertEquals(0, t.liveTransactionCount())
    }

    @Test
    fun onlyExpenseAndIncomeLinesTakeAPeriod() = runBlocking<Unit> {
        val (bank, expense, _) = accounts()
        val draft = TransactionDraft(paid, "Odd", "", listOf(DraftLine(expense.id, 1_00, EntryType.Debit), DraftLine(bank.id, 1_00, EntryType.Credit, period = insurance)))
        assertThrows(LedgerException::class.java) { runBlocking { t.ledger.post(draft) } }
        assertEquals(0, t.liveTransactionCount())
    }

    @Test
    fun editingUpdatesAndDropsPostings() = runBlocking<Unit> {
        val (bank, expense, _) = accounts()
        val id = t.ledger.post(insuranceDraft(bank.id, expense.id))
        val december = t.ledger.getCostPeriods(t.ledger.getTransaction(id)!!)!!.lines.single().shares.last().postingId!!

        // Shorter period: December's posting goes away, the others are rewritten in place.
        val shorter = CostPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 30))
        t.ledger.updateTransaction(id, insuranceDraft(bank.id, expense.id, period = shorter))
        assertNull(t.ledger.getTransaction(december))
        assertEquals(3, t.liveTransactionCount())
        assertEquals(315_08L, expenseIn(YearMonth.of(2026, 10), expense.id))
        assertEquals(0L, expenseIn(YearMonth.of(2026, 12), expense.id))

        // Without a period it is an ordinary transaction again.
        t.ledger.updateTransaction(id, insuranceDraft(bank.id, expense.id, period = null))
        assertEquals(1, t.liveTransactionCount())
        assertEquals(620_00L, expenseIn(YearMonth.of(2026, 9), expense.id))
        assertNull(t.ledger.getCostPeriods(t.ledger.getTransaction(id)!!))
    }

    @Test
    fun deletingRemovesPostingsAndUndoRestoresThem() = runBlocking<Unit> {
        val (bank, expense, _) = accounts()
        val id = t.ledger.post(insuranceDraft(bank.id, expense.id))
        val posting = t.ledger.getCostPeriods(t.ledger.getTransaction(id)!!)!!.lines.single().shares.first().postingId!!

        assertThrows(LedgerException::class.java) { runBlocking { t.ledger.deleteTransaction(posting) } }
        assertThrows(LedgerException::class.java) { runBlocking { t.ledger.updateTransaction(posting, insuranceDraft(bank.id, expense.id, period = null)) } }

        val stamp = t.ledger.deleteTransaction(id)
        assertEquals(0, t.liveTransactionCount())
        t.ledger.restoreDeleted(stamp)
        assertEquals(4, t.liveTransactionCount())
        assertNotNull(t.ledger.getCostPeriods(t.ledger.getTransaction(id)!!))
    }

    @Test
    fun backupKeepsPeriodsLinked() = runBlocking<Unit> {
        val (bank, expense, _) = accounts()
        // Paid in November: October is accrued, November stays, December is prepaid.
        t.ledger.post(insuranceDraft(bank.id, expense.id, date = LocalDate.of(2026, 11, 3)))
        val out = ByteArrayOutputStream()
        t.backup.export(out)

        TestLedger("other").use { other ->
            other.backup.import(ByteArrayInputStream(out.toByteArray()))
            val restored = other.ledger.getTransactions()
            assertEquals(3, restored.size)
            val source = restored.single { other.ledger.periodSourceOf(it.id) == null }
            val summary = other.ledger.getCostPeriods(source)!!
            val expenseId = other.ledger.getAccounts().single { it.number == 8510 }.id
            assertEquals(listOf(DraftLine(expenseId, 620_00, EntryType.Debit, period = insurance)), other.ledger.editableLines(source).filter { it.period != null })
            val postings = summary.lines.single().shares.mapNotNull { it.postingId }
            assertEquals(2, postings.size)
            postings.forEach { assertEquals(source.id, other.ledger.periodSourceOf(it)) }

            // Still a working period: deleting the transaction takes its postings along.
            other.ledger.deleteTransaction(source.id)
            assertEquals(0, other.liveTransactionCount())
        }
    }

    @Test
    fun statementImportSpreadsAndUndoes() = runBlocking<Unit> {
        val (bank, expense, _) = accounts()
        val result = t.statementImports.import(bank.id, listOf(insuranceDraft(bank.id, expense.id)), emptyList())
        assertEquals(4, t.liveTransactionCount())
        t.statementImports.undo(result)
        assertEquals(0, t.liveTransactionCount())
    }
}
