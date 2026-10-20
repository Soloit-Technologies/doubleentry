package com.soloit.doubleentry.ui.reports

import com.soloit.doubleentry.domain.firstDay
import com.soloit.doubleentry.domain.label
import com.soloit.doubleentry.domain.lastDay
import com.soloit.doubleentry.domain.monthAbbreviation
import com.soloit.doubleentry.domain.shortLabel
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class PeriodMode(val label: String) { Month("Month"), YearToDate("Year to date"), Year("Year") }

/** What a report is compared with. */
enum class CompareMode(val label: String) {
    None("No comparison"),
    PreviousPeriod("Previous period"),
    LastYear("Same period last year"),

    /** The average month of the twelve months before the selected one; only for single months. */
    TwelveMonthAverage("12-month average");

    companion object {
        fun availableFor(mode: PeriodMode): List<CompareMode> =
            if (mode == PeriodMode.Month) entries else entries - TwelveMonthAverage
    }
}

/** A month, the year up to and including a month, or a whole calendar year. */
data class ReportPeriod(val mode: PeriodMode = PeriodMode.Month, val month: YearMonth = YearMonth.now(), val year: Int = LocalDate.now().year) {
    val from: LocalDate
        get() = when (mode) {
            PeriodMode.Month -> month.firstDay()
            PeriodMode.YearToDate -> LocalDate.of(month.year, 1, 1)
            PeriodMode.Year -> LocalDate.of(year, 1, 1)
        }
    val to: LocalDate
        get() = when (mode) {
            PeriodMode.Month, PeriodMode.YearToDate -> month.lastDay()
            PeriodMode.Year -> LocalDate.of(year, 12, 31)
        }
    val label: String
        get() = when (mode) {
            PeriodMode.Month -> month.label()
            PeriodMode.YearToDate -> if (month.monthValue == 1) month.label() else "${YearMonth.of(month.year, 1).monthAbbreviation()}–${month.shortLabel()}"
            PeriodMode.Year -> year.toString()
        }

    /** The period immediately before this one (for a year to date: the same months a year earlier). */
    fun previous(): ReportPeriod = when (mode) {
        PeriodMode.Month -> copy(month = month.minusMonths(1))
        PeriodMode.YearToDate -> copy(month = month.minusYears(1))
        PeriodMode.Year -> copy(year = year - 1)
    }

    fun lastYear(): ReportPeriod = when (mode) {
        PeriodMode.Month, PeriodMode.YearToDate -> copy(month = month.minusYears(1))
        PeriodMode.Year -> copy(year = year - 1)
    }

    fun comparedWith(compare: CompareMode): ReportPeriod? = when (compare) {
        CompareMode.None -> null
        CompareMode.PreviousPeriod -> previous()
        CompareMode.LastYear -> lastYear()
        CompareMode.TwelveMonthAverage -> null
    }

    /**
     * The months averaged for [CompareMode.TwelveMonthAverage]: the twelve before the selected month,
     * starting no earlier than [historyStart] so months before the ledger had data don't count as zero.
     * Null outside month mode, or when there is no earlier month with data.
     */
    fun averageWindow(historyStart: YearMonth?): AverageWindow? {
        if (mode != PeriodMode.Month || historyStart == null) return null
        val last = month.minusMonths(1)
        val first = maxOf(month.minusMonths(12), historyStart)
        return if (first > last) null else AverageWindow(first, last)
    }

    /** Switches mode while keeping roughly the same point in time. */
    fun withMode(next: PeriodMode): ReportPeriod = when (next) {
        PeriodMode.Year -> copy(mode = next, year = month.year)
        else -> if (mode == PeriodMode.Year) {
            val now = YearMonth.now()
            copy(mode = next, month = if (year == now.year) now else YearMonth.of(year, 12))
        } else {
            copy(mode = next)
        }
    }
}

/** A run of whole months, [first] through [last], averaged into one month. */
data class AverageWindow(val first: YearMonth, val last: YearMonth) {
    val months: Int get() = (ChronoUnit.MONTHS.between(first, last) + 1).toInt()
    val from: LocalDate get() = first.firstDay()
    val to: LocalDate get() = last.lastDay()

    /** "12-month avg. (Oct 2025–Sep 2026)", "3-month avg. (Jan–Mar 2026)", "1-month avg. (Jan 2026)". */
    val label: String
        get() {
            val range = when {
                first == last -> last.shortLabel()
                first.year == last.year -> "${first.monthAbbreviation()}–${last.shortLabel()}"
                else -> "${first.shortLabel()}–${last.shortLabel()}"
            }
            return "$months-month avg. ($range)"
        }
}
