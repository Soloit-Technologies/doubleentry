package com.soloit.doubleentry.data

import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Invoice
import com.soloit.doubleentry.domain.LedgerException
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.testing.TestLedger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class StatementImporterTest {
    private val t = TestLedger()
    private val day = LocalDate.of(2026, 9, 1)
    private lateinit var bank: Account
    private lateinit var payables: Account
    private lateinit var food: Account

    @After
    fun tearDown() = t.close()

    private suspend fun setUp(): Invoice {
        bank = t.account(1910, "Bank", AccountType.Asset)
        payables = t.account(2440, "Payables", AccountType.Liability)
        food = t.account(6010, "Groceries", AccountType.Expense)
        val power = t.account(5030, "Electricity", AccountType.Expense)
        val booking = TransactionDraft(day, "Vattenfall", "", listOf(DraftLine(power.id, 1_000_00, EntryType.Debit), DraftLine(payables.id, 1_000_00, EntryType.Credit)))
        return t.invoices.createInvoice(1_000_00, day, day.plusDays(30), booking)
    }

    private fun groceries(amount: Long) =
        TransactionDraft(day.plusDays(3), "ICA", "", listOf(DraftLine(food.id, amount, EntryType.Debit), DraftLine(bank.id, amount, EntryType.Credit)))

    @Test
    fun importsTransactionsAndInvoicePaymentsAndUndoesThemTogether() = runBlocking<Unit> {
        val invoice = setUp()
        val paidOn = day.plusDays(28)

        val result = t.statementImports.import(bank.id, listOf(groceries(250_00)), listOf(StatementInvoicePayment(invoice.id, paidOn, 1_000_00)))

        assertEquals(2, result.count)
        val paid = t.invoices.getInvoice(invoice.id)!!
        assertTrue(paid.isPaid)
        assertEquals(paidOn, paid.paidDate)
        assertEquals(bank.id, paid.paidFromAccountId)
        assertEquals(-1_250_00L, t.ledger.getBalances(paidOn)[bank.id])
        assertEquals(0L, t.ledger.getBalances(paidOn)[payables.id])

        t.statementImports.undo(result)

        val reopened = t.invoices.getInvoice(invoice.id)!!
        assertFalse(reopened.isPaid)
        assertNull(reopened.paymentTransactionId)
        assertEquals(1_000_00L, reopened.remaining)
        assertEquals(0L, t.ledger.getBalances(paidOn)[bank.id])
        assertEquals(1_000_00L, t.ledger.getBalances(paidOn)[payables.id])
    }

    @Test
    fun partPaymentsAreRecordedAsInstalmentsAndUndone() = runBlocking<Unit> {
        val invoice = setUp()

        val result = t.statementImports.import(
            bank.id,
            emptyList(),
            // Listed out of order: the earlier one is recorded first.
            listOf(StatementInvoicePayment(invoice.id, day.plusDays(20), 600_00), StatementInvoicePayment(invoice.id, day.plusDays(10), 300_00)),
        )
        val partly = t.invoices.getInvoice(invoice.id)!!
        assertFalse(partly.isPaid)
        assertEquals(listOf(300_00L, 600_00L), partly.partialPayments.map { it.amount })
        assertEquals(100_00L, partly.remaining)

        t.statementImports.undo(result)
        assertEquals(1_000_00L, t.invoices.getInvoice(invoice.id)!!.remaining)
        assertTrue(t.settings.livePrefix(SettingsRepository.Keys.INVOICE_PAYMENTS_PREFIX).isEmpty())
    }

    @Test
    fun nothingIsBookedWhenOneRowFails() = runBlocking<Unit> {
        val invoice = setUp()
        val before = t.liveTransactionCount()

        assertThrows(LedgerException::class.java) {
            runBlocking {
                t.statementImports.import(bank.id, listOf(groceries(250_00)), listOf(StatementInvoicePayment(invoice.id, day.plusDays(5), 1_500_00)))
            }
        }
        assertEquals(before, t.liveTransactionCount())
        assertEquals(1_000_00L, t.invoices.getInvoice(invoice.id)!!.remaining)
    }
}
