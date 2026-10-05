package com.soloit.doubleentry.data

import com.soloit.doubleentry.domain.AccountGroup
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.BalanceSheetSettings
import com.soloit.doubleentry.domain.DefaultAccountPlan
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.LedgerException
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.testing.TestLedger
import kotlinx.coroutines.flow.first
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
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class LedgerRepositoryTest {
    private val t = TestLedger()
    private val day = LocalDate.of(2026, 9, 15)

    @After
    fun tearDown() = t.close()

    @Test
    fun postedTransactionsDriveBalancesAndMovements() = runBlocking<Unit> {
        val bank = t.account(1910, "Bank", AccountType.Asset)
        val salary = t.account(3010, "Salary", AccountType.Revenue)
        val food = t.account(5010, "Food", AccountType.Expense)
        t.post(day, "Salary", bank, salary, 30_000_00)
        t.post(day.plusDays(1), "Groceries", food, bank, 1_250_50)

        val balances = t.ledger.getBalances(day.plusDays(1))
        assertEquals(28_749_50L, balances[bank.id])
        assertEquals(30_000_00L, balances[salary.id])
        assertEquals(1_250_50L, balances[food.id])
        assertEquals(30_000_00L, t.ledger.getBalances(day)[bank.id])
        assertEquals(-1_250_50L, t.ledger.getMovements(day.plusDays(1), day.plusDays(1))[bank.id])
    }

    @Test
    fun unbalancedTransactionsAreRejected() = runBlocking<Unit> {
        val bank = t.account(1910, "Bank", AccountType.Asset)
        val food = t.account(5010, "Food", AccountType.Expense)
        val draft = TransactionDraft(day, "Bad", "", listOf(DraftLine(food.id, 100, EntryType.Debit), DraftLine(bank.id, 90, EntryType.Credit)))
        assertThrows(LedgerException::class.java) { runBlocking { t.ledger.post(draft) } }
        assertEquals(0, t.liveTransactionCount())
    }

    @Test
    fun updateReplacesLinesAndKeepsId() = runBlocking<Unit> {
        val bank = t.account(1910, "Bank", AccountType.Asset)
        val food = t.account(5010, "Food", AccountType.Expense)
        val id = t.post(day, "Groceries", food, bank, 100_00)
        t.ledger.updateTransaction(id, TransactionDraft(day, "Groceries (corrected)", "", listOf(DraftLine(food.id, 120_00, EntryType.Debit), DraftLine(bank.id, 120_00, EntryType.Credit))))

        val tx = t.ledger.getTransaction(id)!!
        assertEquals("Groceries (corrected)", tx.title)
        assertEquals(listOf(120_00L, 120_00L), tx.entries.map { it.amount })
        assertEquals(-120_00L, t.ledger.getBalances(day)[bank.id])
    }

    @Test
    fun deleteAndRestoreBringsBackTheTransactionButNotEarlierEditedLines() = runBlocking<Unit> {
        val bank = t.account(1910, "Bank", AccountType.Asset)
        val food = t.account(5010, "Food", AccountType.Expense)
        val id = t.post(day, "Groceries", food, bank, 100_00)
        // An edit tombstones the original lines; undoing a later delete must not revive them.
        t.ledger.updateTransaction(id, TransactionDraft(day, "Groceries", "", listOf(DraftLine(food.id, 80_00, EntryType.Debit), DraftLine(bank.id, 80_00, EntryType.Credit))))

        val stamp = t.ledger.deleteTransaction(id)
        assertNull(t.ledger.getTransaction(id))
        assertEquals(0L, t.ledger.getBalances(day)[bank.id])

        val restored = t.ledger.restoreDeleted(stamp)
        assertEquals(3, restored) // the transaction and its two current lines
        val tx = t.ledger.getTransaction(id)!!
        assertEquals(listOf(80_00L, 80_00L), tx.entries.map { it.amount })
        assertEquals(-80_00L, t.ledger.getBalances(day)[bank.id])
    }

    @Test
    fun deletedAccountCanBeRestored() = runBlocking<Unit> {
        val spare = t.account(1990, "Spare", AccountType.Asset)
        val stamp = t.ledger.deleteAccount(spare.id)
        assertNull(t.ledger.getAccount(spare.id))
        t.ledger.restoreDeleted(stamp)
        assertNotNull(t.ledger.getAccount(spare.id))
    }

    @Test
    fun suggestionsAreNewestFirstDistinctByTitleAndEscapeWildcards() = runBlocking<Unit> {
        val bank = t.account(1910, "Bank", AccountType.Asset)
        val food = t.account(5010, "Food", AccountType.Expense)
        val rent = t.account(5020, "Rent", AccountType.Expense)
        t.post(day, "Groceries ICA", food, bank, 100_00)
        t.post(day.plusDays(3), "Groceries ICA", food, bank, 300_00)
        t.post(day.plusDays(1), "Groceries Coop", food, bank, 200_00)
        t.post(day, "Rent 100% paid", rent, bank, 9_000_00)

        val groceries = t.ledger.suggestByTitle("groc")
        assertEquals(listOf("Groceries ICA", "Groceries Coop"), groceries.map { it.title })
        // The newest "Groceries ICA" is suggested, with its lines.
        assertEquals(300_00L, groceries.first().totalDebits)

        assertEquals(listOf("Rent 100% paid"), t.ledger.suggestByTitle("100%").map { it.title })
        assertTrue(t.ledger.suggestByTitle("1_0").isEmpty())
        assertTrue(t.ledger.suggestByTitle("  ").isEmpty())
    }

    @Test
    fun deleteAllDataAlsoRemovesRecurringRulesAndReconciliationsButKeepsReportSettings() = runBlocking<Unit> {
        t.settings.put(SettingsRepository.Keys.RECURRING_PREFIX + "r1", "{}")
        t.settings.put(SettingsRepository.Keys.RECONCILIATION_PREFIX + "a1", "{}")
        t.settings.put(SettingsRepository.Keys.INVOICE_CREDIT_ACCOUNT, "2440")

        t.ledger.deleteAllData()

        assertTrue(t.settings.livePrefix(SettingsRepository.Keys.RECURRING_PREFIX).isEmpty())
        assertTrue(t.settings.livePrefix(SettingsRepository.Keys.RECONCILIATION_PREFIX).isEmpty())
        assertEquals("2440", t.settings.get(SettingsRepository.Keys.INVOICE_CREDIT_ACCOUNT))
    }

    @Test
    fun seedingDefaultPlanAddsReportGroupsWithoutOverwritingExistingOnes() = runBlocking<Unit> {
        val custom = BalanceSheetSettings(assetGroups = listOf(AccountGroup("Mine", listOf("19"))))
        t.settings.saveBalanceSheet(custom)

        t.ledger.seedDefaultPlan()

        assertEquals(custom, t.settings.balanceSheet.first())
        assertEquals(DefaultAccountPlan.incomeStatementSettings, t.settings.incomeStatement.first())
    }
}
