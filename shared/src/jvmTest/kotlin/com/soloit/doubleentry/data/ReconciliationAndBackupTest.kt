package com.soloit.doubleentry.data

import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Frequency
import com.soloit.doubleentry.domain.LedgerException
import com.soloit.doubleentry.domain.RecurringRule
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.testing.TestLedger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

class ReconciliationAndBackupTest {
    private val t = TestLedger("a")
    private val day = LocalDate.of(2026, 9, 30)

    @After
    fun tearDown() = t.close()

    @Test
    fun reconcilingRequiresAMatchAndLaterEditsShowAsDrift() = runBlocking<Unit> {
        val bank = t.account(1910, "Bank", AccountType.Asset)
        val salary = t.account(3010, "Salary", AccountType.Revenue)
        val food = t.account(5010, "Food", AccountType.Expense)
        t.post(day.minusDays(10), "Salary", bank, salary, 1_000_00)

        assertThrows(LedgerException::class.java) { runBlocking { t.reconciliations.reconcile(bank.id, day, 999_00) } }
        t.reconciliations.reconcile(bank.id, day, 1_000_00)
        assertTrue(t.reconciliations.getStatus(bank.id)!!.inSync)

        // A transaction after the reconciled date doesn't matter; one inside the period does.
        t.post(day.plusDays(1), "Later", food, bank, 50_00)
        assertTrue(t.reconciliations.statuses.first().getValue(bank.id).inSync)
        t.post(day.minusDays(2), "Forgotten", food, bank, 20_00)
        val status = t.reconciliations.statuses.first().getValue(bank.id)
        assertFalse(status.inSync)
        assertEquals(-20_00L, status.drift)
    }

    @Test
    fun adjustmentPostsTheDifferenceOnTheRightSides() = runBlocking<Unit> {
        val bank = t.account(1910, "Bank", AccountType.Asset)
        val card = t.account(2890, "Credit card", AccountType.Liability)
        val fees = t.account(6570, "Bank fees", AccountType.Expense)
        val interest = t.account(8310, "Interest", AccountType.Revenue)

        // Statement shows less money than the ledger: bank fees (credit bank, debit fees).
        t.reconciliations.adjustAndReconcile(bank.id, day, -45_00, fees.id, "Fees")
        assertEquals(-45_00L, t.ledger.getBalances(day)[bank.id])
        assertEquals(45_00L, t.ledger.getBalances(day)[fees.id])

        // A liability that grew: credit the card, debit the fee account.
        t.reconciliations.adjustAndReconcile(card.id, day, 10_00, fees.id, "Card fee")
        assertEquals(10_00L, t.ledger.getBalances(day)[card.id])

        // An asset that grew: debit the bank, credit interest.
        t.reconciliations.adjustAndReconcile(bank.id, day.plusDays(1), 5_00, interest.id, "Interest")
        assertEquals(5_00L, t.ledger.getBalances(day.plusDays(1))[bank.id])
        assertTrue(t.reconciliations.getStatus(bank.id)!!.inSync)
    }

    @Test
    fun backupRoundTripKeepsRecurringRulesReconciliationsAndInstalments() = runBlocking<Unit> {
        val bank = t.account(1910, "Bank", AccountType.Asset)
        val payables = t.account(2440, "Payables", AccountType.Liability)
        val rent = t.account(5010, "Rent", AccountType.Expense)
        val salary = t.account(3010, "Salary", AccountType.Revenue)
        t.post(day.minusDays(20), "Salary", bank, salary, 20_000_00)
        t.recurring.save(
            RecurringRule(
                "rule", "Rent", "", listOf(DraftLine(rent.id, 9_000_00, EntryType.Debit), DraftLine(bank.id, 9_000_00, EntryType.Credit)),
                Frequency.Monthly, LocalDate.of(2026, 9, 25),
            ),
            today = day,
        )
        val booking = TransactionDraft(day, "Plumber", "", listOf(DraftLine(rent.id, 2_000_00, EntryType.Debit), DraftLine(payables.id, 2_000_00, EntryType.Credit)))
        val invoice = t.invoices.createInvoice(2_000_00, day, day.plusDays(30), booking)
        t.invoices.recordPayment(invoice.id, bank.id, day, 500_00)
        t.reconciliations.reconcile(bank.id, day, 10_500_00)

        val out = ByteArrayOutputStream()
        t.backup.export(out)

        TestLedger("b").use { other ->
            other.backup.import(ByteArrayInputStream(out.toByteArray()))
            assertEquals(t.liveTransactionCount(), other.liveTransactionCount())

            val rule = other.recurring.getRules().single()
            assertEquals("Rent", rule.title)
            assertEquals(LocalDate.of(2026, 9, 25), rule.lastPostedDate)
            // The imported rule continues where the original left off instead of re-posting September.
            assertEquals(0, other.recurring.process(day))

            val bankB = other.ledger.getAccounts().single { it.number == 1910 }
            assertTrue(other.reconciliations.getStatus(bankB.id)!!.inSync)
            assertEquals(1_500_00L, other.invoices.liveInvoices().single().remaining)
        }
    }
}
