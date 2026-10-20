package com.soloit.doubleentry.data

import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Invoice
import com.soloit.doubleentry.domain.LedgerException
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.testing.TestLedger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InvoiceRepositoryTest {
    private val t = TestLedger()
    private val day = LocalDate.of(2026, 9, 1)
    private lateinit var bank: Account
    private lateinit var payables: Account
    private lateinit var power: Account

    @After
    fun tearDown() = t.close()

    private suspend fun electricityBill(amount: Long = 1_000_00): Invoice {
        bank = t.account(1910, "Bank", AccountType.Asset)
        payables = t.account(2440, "Payables", AccountType.Liability)
        power = t.account(5020, "Electricity", AccountType.Expense)
        val booking = TransactionDraft(day, "Electricity", "", listOf(DraftLine(power.id, amount, EntryType.Debit), DraftLine(payables.id, amount, EntryType.Credit)))
        return t.invoices.createInvoice(amount, day, day.plusDays(30), booking)
    }

    @Test
    fun instalmentsReduceWhatIsLeftUntilTheLastOneSettlesTheInvoice() = runBlocking<Unit> {
        val invoice = electricityBill()

        t.invoices.recordPayment(invoice.id, bank.id, day.plusDays(5), 300_00)
        var current = t.invoices.getInvoice(invoice.id)!!
        assertFalse(current.isPaid)
        assertEquals(300_00L, current.partiallyPaid)
        assertEquals(700_00L, current.remaining)
        assertEquals(listOf(300_00L), current.partialPayments.map { it.amount })
        assertEquals(bank.id, current.partialPayments.single().accountId)
        assertEquals(-300_00L, t.ledger.getBalances(day.plusDays(5))[bank.id])

        // Paying "everything" now pays only what is left, and marks the invoice as paid.
        t.invoices.markPaid(invoice.id, bank.id, day.plusDays(20))
        current = t.invoices.getInvoice(invoice.id)!!
        assertTrue(current.isPaid)
        assertEquals(0L, current.remaining)
        assertEquals(-1_000_00L, t.ledger.getBalances(day.plusDays(20))[bank.id])
        assertEquals(0L, t.ledger.getBalances(day.plusDays(20))[payables.id])
        assertTrue(t.ledger.getTransaction(current.paymentTransactionId!!)!!.title.startsWith("Final payment"))
    }

    @Test
    fun overpaymentIsRejected() = runBlocking<Unit> {
        val invoice = electricityBill()
        t.invoices.recordPayment(invoice.id, bank.id, day, 600_00)
        assertThrows(LedgerException::class.java) { runBlocking { t.invoices.recordPayment(invoice.id, bank.id, day, 500_00) } }
        assertThrows(LedgerException::class.java) { runBlocking { t.invoices.recordPayment(invoice.id, bank.id, day, 0) } }
    }

    @Test
    fun deletingAnInstalmentTransactionMakesItOutstandingAgain() = runBlocking<Unit> {
        val invoice = electricityBill()
        t.invoices.recordPayment(invoice.id, bank.id, day, 250_00)
        val paymentId = t.invoices.getInvoice(invoice.id)!!.partialPayments.single().transactionId

        t.ledger.deleteTransaction(paymentId)
        assertEquals(1_000_00L, t.invoices.getInvoice(invoice.id)!!.remaining)
    }

    @Test
    fun amountDueCannotDropBelowWhatWasPaid() = runBlocking<Unit> {
        val invoice = electricityBill()
        t.invoices.recordPayment(invoice.id, bank.id, day, 400_00)
        val smaller = TransactionDraft(day, "Electricity", "", listOf(DraftLine(power.id, 300_00, EntryType.Debit), DraftLine(payables.id, 300_00, EntryType.Credit)))
        assertThrows(LedgerException::class.java) {
            runBlocking { t.invoices.updateInvoice(invoice.id, 300_00, day, day.plusDays(30), smaller) }
        }
    }

    @Test
    fun deleteRemovesBookingAndPaymentsAndUndoRestoresAll() = runBlocking<Unit> {
        val invoice = electricityBill()
        t.invoices.recordPayment(invoice.id, bank.id, day, 100_00)
        val before = t.liveTransactionCount()

        val stamp = t.invoices.deleteInvoice(invoice.id)
        assertNull(t.invoices.getInvoice(invoice.id))
        assertEquals(0, t.liveTransactionCount())

        t.ledger.restoreDeleted(stamp)
        val restored = t.invoices.getInvoice(invoice.id)!!
        assertEquals(before, t.liveTransactionCount())
        assertEquals(900_00L, restored.remaining)
    }

    @Test
    fun invoiceFlowIncludesInstalments() = runBlocking<Unit> {
        val invoice = electricityBill()
        t.invoices.recordPayment(invoice.id, bank.id, day, 100_00)
        assertEquals(900_00L, t.invoices.invoices.first().single().remaining)
    }
}
