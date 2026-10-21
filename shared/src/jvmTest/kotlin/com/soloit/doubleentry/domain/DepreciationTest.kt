package com.soloit.doubleentry.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Reference schedules were computed with the original .NET AssetService.ComputeSchedule. */
class DepreciationTest {

    private fun check(activation: LocalDate, cost: Long, months: Int, count: Int, first: Long, second: Long, lastMonth: String, last: Long) {
        val s = Depreciation.schedule(activation, cost, months)
        assertEquals(count, s.size)
        assertEquals(first, s[0].amount)
        assertEquals(second, s[1].amount)
        assertEquals(lastMonth, s.last().month.toString().take(7))
        assertEquals(last, s.last().amount)
        assertEquals(cost, s.sumOf { it.amount })
        assertTrue(s.all { it.month.dayOfMonth == 1 })
    }

    @Test
    fun midMonthActivationIsProrated() =
        check(LocalDate.of(2026, 1, 15), 1_000_000, 36, count = 37, first = 15_233, second = 27_778, lastMonth = "2029-01", last = 12_537)

    @Test
    fun lastDayActivation() =
        check(LocalDate.of(2025, 3, 31), 10_000, 3, count = 4, first = 108, second = 3_333, lastMonth = "2025-06", last = 3_226)

    @Test
    fun firstDayActivationAbsorbsRoundingInLastMonth() =
        check(LocalDate.of(2026, 2, 1), 100_000, 7, count = 7, first = 14_286, second = 14_286, lastMonth = "2026-08", last = 14_284)

    @Test
    fun zeroFirstMonthIsKeptAsEmptySlot() =
        check(LocalDate.of(2025, 12, 31), 40, 30, count = 41, first = 0, second = 1, lastMonth = "2029-04", last = 1)

    @Test
    fun tinyMonthlyAmountStillTerminates() {
        // The .NET version loops forever here (monthly rounds to 0.00); we write off at least one cent.
        val s = Depreciation.schedule(LocalDate.of(2024, 2, 29), 5, 12)
        assertEquals(5L, s.sumOf { it.amount })
    }

    @Test
    fun invalidInputsGiveEmptySchedule() {
        assertTrue(Depreciation.schedule(LocalDate.now(), 0, 12).isEmpty())
        assertTrue(Depreciation.schedule(LocalDate.now(), 100, 0).isEmpty())
    }

    @Test
    fun descriptionsMatchDotNetFormat() {
        assertEquals("Monthly depreciation for 2026-03", Depreciation.description(LocalDate.of(2026, 3, 1)))
        assertEquals(LocalDate.of(2024, 2, 29), Depreciation.postingDate(LocalDate.of(2024, 2, 1)))
        assertEquals(LocalDate.of(2026, 2, 1), Depreciation.lastCompletedMonth(LocalDate.of(2026, 3, 15)))
    }
}
