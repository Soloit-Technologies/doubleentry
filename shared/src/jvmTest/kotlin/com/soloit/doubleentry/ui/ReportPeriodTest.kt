package com.soloit.doubleentry.ui

import com.soloit.doubleentry.ui.components.changeLabel
import com.soloit.doubleentry.ui.reports.AverageWindow
import com.soloit.doubleentry.ui.reports.CompareMode
import com.soloit.doubleentry.ui.reports.PeriodMode
import com.soloit.doubleentry.ui.reports.ReportPeriod
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun twelveMonthAverageCoversTheYearBeforeASingleMonth() {
        val month = ReportPeriod(PeriodMode.Month, YearMonth.of(2026, 3))
        val full = month.averageWindow(historyStart = YearMonth.of(2020, 1))!!
        assertEquals(AverageWindow(YearMonth.of(2025, 3), YearMonth.of(2026, 2)), full)
        assertEquals(12, full.months)
        assertEquals("12-month avg. (Mar 2025–Feb 2026)", full.label)
        assertNull(ReportPeriod(PeriodMode.YearToDate, YearMonth.of(2026, 3)).averageWindow(YearMonth.of(2020, 1)))
        assertTrue(CompareMode.TwelveMonthAverage in CompareMode.availableFor(PeriodMode.Month))
        assertFalse(CompareMode.TwelveMonthAverage in CompareMode.availableFor(PeriodMode.Year))
    }

    @Test
    fun twelveMonthAverageStartsWhereTheHistoryDoes() {
        val october = ReportPeriod(PeriodMode.Month, YearMonth.of(2026, 10))
        val since = YearMonth.of(2026, 1)
        val partial = october.averageWindow(since)!!
        assertEquals(9, partial.months)
        assertEquals("9-month avg. (Jan–Sep 2026)", partial.label)
        assertEquals("1-month avg. (Jan 2026)", ReportPeriod(PeriodMode.Month, YearMonth.of(2026, 2)).averageWindow(since)!!.label)
        assertNull(ReportPeriod(PeriodMode.Month, since).averageWindow(since))
        assertNull(october.averageWindow(historyStart = null))
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
