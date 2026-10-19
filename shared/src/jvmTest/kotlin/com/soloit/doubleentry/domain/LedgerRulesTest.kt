package com.soloit.doubleentry.domain

import com.soloit.doubleentry.ui.components.LineInput
import com.soloit.doubleentry.ui.components.Lines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LedgerRulesTest {

    @Test
    fun validatorRequiresTitleAndBalance() {
        val balanced = listOf(DraftLine("a", 100, EntryType.Debit), DraftLine("b", 100, EntryType.Credit))
        TransactionValidator.validate("Rent", balanced)
        assertThrows(LedgerException::class.java) { TransactionValidator.validate(" ", balanced) }
        assertThrows(LedgerException::class.java) { TransactionValidator.validate("x", emptyList()) }
        assertThrows(LedgerException::class.java) {
            TransactionValidator.validate("x", listOf(DraftLine("a", 100, EntryType.Debit), DraftLine("b", 90, EntryType.Credit)))
        }
        assertThrows(LedgerException::class.java) {
            TransactionValidator.validate("x", listOf(DraftLine("a", 0, EntryType.Debit), DraftLine("b", 0, EntryType.Credit)))
        }
    }

    @Test
    fun prefixMatchingSkipsBlanks() {
        assertTrue(matchesAnyPrefix(1910, listOf("19")))
        assertFalse(matchesAnyPrefix(1910, listOf("", " ")))
        assertEquals(listOf("19", "1930"), parsePrefixes(" 19, ,1930 "))
        val groups = listOf(AccountGroup("Bank", listOf("19")), AccountGroup("Cash", listOf("1010")))
        assertEquals(1, groups.indexOfGroupFor(1010))
        assertEquals(-1, groups.indexOfGroupFor(2010))
    }

    @Test
    fun outstandingDetectionPrefersPayables() {
        val types = mapOf("exp" to AccountType.Expense, "pay" to AccountType.Liability, "bank" to AccountType.Asset)
        val purchase = listOf(DraftLine("exp", 500, EntryType.Debit), DraftLine("pay", 500, EntryType.Credit))
        assertEquals(OutstandingDetector.Outstanding("pay", true), OutstandingDetector.detect(purchase, types, 500))

        val receivable = listOf(DraftLine("bank", 500, EntryType.Debit), DraftLine("rev", 500, EntryType.Credit))
        assertEquals(OutstandingDetector.Outstanding("bank", false), OutstandingDetector.detect(receivable, types + ("rev" to AccountType.Revenue), 500))

        val ambiguous = listOf(DraftLine("exp", 500, EntryType.Debit), DraftLine("exp2", 500, EntryType.Credit))
        assertThrows(LedgerException::class.java) {
            OutstandingDetector.detect(ambiguous, mapOf("exp" to AccountType.Expense, "exp2" to AccountType.Expense), 400)
        }
    }

    @Test
    fun autoFillCompletesTheMissingSide() {
        Locale.setDefault(Locale.US)
        val lines = listOf(LineInput(0, "a", debit = "100.00"), LineInput(1, "b", credit = "40.00"), LineInput(2, "c"))
        val filled = Lines.autoFill(lines, key = 2, debitSide = false)
        assertEquals("60.00", filled[2].credit)
        // Filling the debit side would unbalance further, so nothing happens.
        assertEquals(lines, Lines.autoFill(lines, key = 2, debitSide = true))
        // Not every other line has an amount yet.
        val incomplete = listOf(LineInput(0, "a", debit = "100"), LineInput(1, "b"), LineInput(2, "c"))
        assertEquals(incomplete, Lines.autoFill(incomplete, key = 2, debitSide = false))
    }

    @Test
    fun lineValidationMessages() {
        Locale.setDefault(Locale.US)
        val ok = Lines.toDraft(listOf(LineInput(0, "a", debit = "10"), LineInput(1, "b", credit = "10")))
        assertTrue(ok.isSuccess)
        assertEquals(2, ok.getOrThrow().size)

        val missingAccount = Lines.toDraft(listOf(LineInput(0, null, debit = "10"), LineInput(1, "b", credit = "10")))
        assertEquals("Choose an account for every line with an amount.", missingAccount.exceptionOrNull()?.message)

        val unbalanced = Lines.toDraft(listOf(LineInput(0, "a", debit = "10"), LineInput(1, "b", credit = "9")))
        assertTrue(unbalanced.exceptionOrNull()!!.message!!.startsWith("Debits"))

        val oneSided = Lines.toDraft(listOf(LineInput(0, "a", debit = "10"), LineInput(1, "b", debit = "10")))
        assertEquals("The entry must have at least one debit and one credit.", oneSided.exceptionOrNull()?.message)
    }
}
