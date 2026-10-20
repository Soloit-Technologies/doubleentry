package com.soloit.doubleentry.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RecurrenceTest {
    private fun rule(frequency: Frequency, start: LocalDate, end: LocalDate? = null, lastPosted: LocalDate? = null, paused: Boolean = false) =
        RecurringRule("r", "Rent", "", listOf(DraftLine("a", 100, EntryType.Debit), DraftLine("b", 100, EntryType.Credit)), frequency, start, end, lastPosted, paused)

    @Test
    fun monthlyOccurrencesClampToShortMonthsAndRecover() {
        val r = rule(Frequency.Monthly, LocalDate.of(2026, 1, 31))
        assertEquals(
            listOf(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30)),
            Recurrence.occurrences(r, LocalDate.of(2026, 4, 30)).toList(),
        )
    }

    @Test
    fun weeklyQuarterlyAndYearlySteps() {
        val start = LocalDate.of(2026, 1, 5)
        assertEquals(LocalDate.of(2026, 1, 19), Recurrence.occurrence(rule(Frequency.Weekly, start), 2))
        assertEquals(LocalDate.of(2026, 7, 5), Recurrence.occurrence(rule(Frequency.Quarterly, start), 2))
        assertEquals(LocalDate.of(2028, 2, 29), Recurrence.occurrence(rule(Frequency.Yearly, LocalDate.of(2024, 2, 29)), 4))
        assertEquals(LocalDate.of(2025, 2, 28), Recurrence.occurrence(rule(Frequency.Yearly, LocalDate.of(2024, 2, 29)), 1))
    }

    @Test
    fun dueSkipsWhatWasPostedAndRespectsEndDateAndPause() {
        val start = LocalDate.of(2026, 1, 1)
        val today = LocalDate.of(2026, 6, 15)
        assertEquals(6, Recurrence.due(rule(Frequency.Monthly, start), today).size)
        assertEquals(
            listOf(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 1)),
            Recurrence.due(rule(Frequency.Monthly, start, lastPosted = LocalDate.of(2026, 4, 1)), today),
        )
        assertEquals(3, Recurrence.due(rule(Frequency.Monthly, start, end = LocalDate.of(2026, 3, 31)), today).size)
        assertTrue(Recurrence.due(rule(Frequency.Monthly, start, paused = true), today).isEmpty())
        assertTrue(Recurrence.due(rule(Frequency.Monthly, LocalDate.of(2026, 7, 1)), today).isEmpty())
    }

    @Test
    fun dueIsCappedPerRun() {
        val r = rule(Frequency.Weekly, LocalDate.of(2000, 1, 1))
        assertEquals(Recurrence.MAX_PER_RUN, Recurrence.due(r, LocalDate.of(2026, 1, 1)).size)
    }

    @Test
    fun nextIsTheFirstUnpostedDateFromToday() {
        val r = rule(Frequency.Monthly, LocalDate.of(2026, 1, 10), lastPosted = LocalDate.of(2026, 9, 10))
        assertEquals(LocalDate.of(2026, 10, 10), Recurrence.next(r, LocalDate.of(2026, 10, 2)))
        // Today's occurrence still counts as upcoming until it is posted.
        assertEquals(LocalDate.of(2026, 10, 10), Recurrence.next(r.copy(lastPostedDate = null), LocalDate.of(2026, 10, 10)))
        assertNull(Recurrence.next(r.copy(endDate = LocalDate.of(2026, 9, 30)), LocalDate.of(2026, 10, 2)))
    }

    @Test
    fun recurringIdsAreStablePerRuleAndDate() {
        val id = DeterministicIds.recurringTransactionId("6f1c2b4e-0000-4000-8000-000000000001", LocalDate.of(2026, 10, 1))
        assertEquals(id, DeterministicIds.recurringTransactionId("6F1C2B4E-0000-4000-8000-000000000001", LocalDate.of(2026, 10, 1)))
        assertNotEquals(id, DeterministicIds.recurringTransactionId("6f1c2b4e-0000-4000-8000-000000000001", LocalDate.of(2026, 11, 1)))
        assertNotEquals(DeterministicIds.recurringEntryId(id, 0), DeterministicIds.recurringEntryId(id, 1))
        // A different namespace than depreciation, so ids can never collide with write-offs.
        assertNotEquals(id, DeterministicIds.writeoffTransactionId("6f1c2b4e-0000-4000-8000-000000000001", LocalDate.of(2026, 10, 1)))
    }
}
