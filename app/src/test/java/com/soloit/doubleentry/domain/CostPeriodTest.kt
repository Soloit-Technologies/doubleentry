package com.soloit.doubleentry.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CostPeriodTest {
    private val insurance = CostPeriod(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 12, 15))
    private val types = mapOf("food" to AccountType.Expense, "bank" to AccountType.Asset, "rent" to AccountType.Revenue)
    private val interims = mapOf(
        Periodization.Interim.PrepaidExpenses to "prepaid",
        Periodization.Interim.AccruedExpenses to "accrued",
        Periodization.Interim.AccruedIncome to "accruedIncome",
        Periodization.Interim.DeferredIncome to "deferred",
    )

    @Test
    fun sharesFollowDays() {
        // 17 + 30 + 15 = 62 days.
        assertEquals(
            listOf(
                Periodization.Share(YearMonth.of(2026, 10), 170_00),
                Periodization.Share(YearMonth.of(2026, 11), 300_00),
                Periodization.Share(YearMonth.of(2026, 12), 150_00),
            ),
            Periodization.shares(620_00, insurance),
        )
    }

    @Test
    fun sharesAlwaysSumToTheAmount() {
        val year = CostPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
        for (amount in listOf(1L, 7L, 100_00L, 999_99L, 1_234_567L)) {
            assertEquals(amount, Periodization.shares(amount, year).sumOf { it.amount })
            assertEquals(amount, Periodization.shares(amount, insurance).sumOf { it.amount })
        }
        assertEquals(1, Periodization.shares(1, year).size)
    }

    @Test
    fun labels() {
        assertEquals("October 2026", CostPeriod.month(YearMonth.of(2026, 10)).label(java.util.Locale.ENGLISH))
        assertEquals("15 Oct – 15 Dec 2026", insurance.label(java.util.Locale.ENGLISH))
        assertEquals("15 Dec 2026 – 15 Jan 2027", CostPeriod(LocalDate.of(2026, 12, 15), LocalDate.of(2027, 1, 15)).label(java.util.Locale.ENGLISH))
    }

    @Test
    fun validation() {
        assertThrows(LedgerException::class.java) { Periodization.validate(CostPeriod(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1))) }
        assertThrows(LedgerException::class.java) { Periodization.validate(CostPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2036, 1, 1))) }
        Periodization.validate(insurance)
    }

    @Test
    fun prepaidExpenseIsMovedMonthByMonth() {
        val draft = TransactionDraft(
            LocalDate.of(2026, 9, 20), "Insurance", "",
            listOf(DraftLine("food", 620_00, EntryType.Debit, period = insurance), DraftLine("bank", 620_00, EntryType.Credit)),
        )
        val x = Periodization.expand(draft, types, interims)
        assertEquals(listOf(DraftLine("prepaid", 620_00, EntryType.Debit), DraftLine("bank", 620_00, EntryType.Credit)), x.sourceLines)
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 11), YearMonth.of(2026, 12)), x.postings.map { it.first })
        assertEquals(
            listOf(DraftLine("food", 170_00, EntryType.Debit), DraftLine("prepaid", 170_00, EntryType.Credit)),
            x.postings.first().second,
        )
    }

    @Test
    fun periodAroundTheDateSplitsIntoAccruedOwnAndPrepaid() {
        val draft = TransactionDraft(
            LocalDate.of(2026, 11, 3), "Insurance", "",
            listOf(DraftLine("food", 620_00, EntryType.Debit, period = insurance), DraftLine("bank", 620_00, EntryType.Credit)),
        )
        val x = Periodization.expand(draft, types, interims)
        assertEquals(
            listOf(
                DraftLine("accrued", 170_00, EntryType.Debit),
                DraftLine("food", 300_00, EntryType.Debit),
                DraftLine("prepaid", 150_00, EntryType.Debit),
                DraftLine("bank", 620_00, EntryType.Credit),
            ),
            x.sourceLines,
        )
        val october = x.postings.single { it.first == YearMonth.of(2026, 10) }.second
        assertEquals(listOf(DraftLine("food", 170_00, EntryType.Debit), DraftLine("accrued", 170_00, EntryType.Credit)), october)
        assertNull(x.postings.firstOrNull { it.first == YearMonth.of(2026, 11) })
    }

    @Test
    fun incomeUsesIncomeInterims() {
        val draft = TransactionDraft(
            LocalDate.of(2026, 10, 1), "Rent", "",
            listOf(
                DraftLine("bank", 2_000_00, EntryType.Debit),
                DraftLine("rent", 1_000_00, EntryType.Credit, period = CostPeriod.month(YearMonth.of(2026, 9))),
                DraftLine("rent", 1_000_00, EntryType.Credit, period = CostPeriod.month(YearMonth.of(2026, 11))),
            ),
        )
        val x = Periodization.expand(draft, types, interims)
        assertEquals(
            listOf(
                DraftLine("bank", 2_000_00, EntryType.Debit),
                DraftLine("accruedIncome", 1_000_00, EntryType.Credit),
                DraftLine("deferred", 1_000_00, EntryType.Credit),
            ),
            x.sourceLines,
        )
        assertEquals(
            listOf(DraftLine("rent", 1_000_00, EntryType.Credit), DraftLine("accruedIncome", 1_000_00, EntryType.Debit)),
            x.postings.first().second,
        )
    }

    @Test
    fun collapseRestoresTheLinesAsEntered() {
        val lines = listOf(
            DraftLine("bank", 620_00, EntryType.Credit),
            DraftLine("food", 620_00, EntryType.Debit, period = insurance),
        )
        val draft = TransactionDraft(LocalDate.of(2026, 11, 3), "Insurance", "", lines)
        val x = Periodization.expand(draft, types, interims)
        val collapsed = Periodization.collapse(x.sourceLines, listOf(lines[1] to x.parts.getValue(1)))
        assertEquals(lines, collapsed)
        // Parts that are no longer in the transaction can't be collapsed.
        assertNull(Periodization.collapse(listOf(DraftLine("bank", 620_00, EntryType.Credit)), listOf(lines[1] to x.parts.getValue(1))))
    }
}
