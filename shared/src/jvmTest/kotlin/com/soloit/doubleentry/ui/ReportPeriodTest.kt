package com.soloit.doubleentry.ui

import com.soloit.doubleentry.ui.components.changeLabel
import com.soloit.doubleentry.ui.reports.CompareMode
import com.soloit.doubleentry.ui.reports.PeriodMode
import com.soloit.doubleentry.ui.reports.ReportPeriod
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

class ReportPeriodTest {
    private lateinit var saved: Locale

    @Before
    fun setUp() {
        saved = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() = Locale.setDefault(saved)

    @Test
    fun yearToDateRunsFromJanuaryThroughTheSelectedMonth() {
        val ytd = ReportPeriod(PeriodMode.YearToDate, YearMonth.of(2026, 9))
        assertEquals(LocalDate.of(2026, 1, 1), ytd.from)
        assertEquals(LocalDate.of(2026, 9, 30), ytd.to)
        assertEquals("Jan–Sep 2026", ytd.label)
    }

    @Test
    fun comparisonsPickTheMatchingEarlierPeriod() {
        val month = ReportPeriod(PeriodMode.Month, YearMonth.of(2026, 1))
        assertEquals(YearMonth.of(2025, 12), month.comparedWith(CompareMode.PreviousPeriod)!!.month)
        assertEquals(YearMonth.of(2025, 1), month.comparedWith(CompareMode.LastYear)!!.month)
        assertNull(month.comparedWith(CompareMode.None))

        val ytd = ReportPeriod(PeriodMode.YearToDate, YearMonth.of(2026, 9))
        val previous = ytd.comparedWith(CompareMode.PreviousPeriod)!!
        assertEquals(LocalDate.of(2025, 1, 1) to LocalDate.of(2025, 9, 30), previous.from to previous.to)

        val year = ReportPeriod(PeriodMode.Year, year = 2026)
        assertEquals(2025, year.comparedWith(CompareMode.PreviousPeriod)!!.year)
        assertEquals(2025, year.comparedWith(CompareMode.LastYear)!!.year)
    }

    @Test
    fun switchingModesKeepsThePointInTime() {
        val month = ReportPeriod(PeriodMode.Month, YearMonth.of(2024, 5), year = 2026)
        assertEquals(2024, month.withMode(PeriodMode.Year).year)
        assertEquals(YearMonth.of(2024, 5), month.withMode(PeriodMode.YearToDate).month)
        val pastYear = ReportPeriod(PeriodMode.Year, YearMonth.of(2026, 1), year = 2023)
        assertEquals(YearMonth.of(2023, 12), pastYear.withMode(PeriodMode.Month).month)
    }

    @Test
    fun changeLabelShowsDeltaAndPercent() {
        assertEquals("+50.00 (+50%)", changeLabel(150_00, 100_00))
        assertEquals("−25.00 (−25%)", changeLabel(75_00, 100_00))
        assertEquals("+10.00 (+50%)", changeLabel(-10_00, -20_00))
        assertEquals("+10.00", changeLabel(10_00, 0))
        assertEquals("No change", changeLabel(5, 5))
    }
}
