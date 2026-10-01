package com.soloit.doubleentry.domain

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

val LocalDate.yearMonth: YearMonth get() = YearMonth.from(this)

fun YearMonth.firstDay(): LocalDate = atDay(1)

fun YearMonth.lastDay(): LocalDate = atEndOfMonth()

private fun String.capitalized(locale: Locale): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

/** "October 2026" (localized, capitalized). */
fun YearMonth.label(locale: Locale = Locale.getDefault()): String =
    format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).capitalized(locale)

/** "Oct 2026". */
fun YearMonth.shortLabel(locale: Locale = Locale.getDefault()): String =
    format(DateTimeFormatter.ofPattern("LLL yyyy", locale)).replace(".", "").capitalized(locale)

/** "Oct" — used on chart axes. */
fun YearMonth.monthAbbreviation(locale: Locale = Locale.getDefault()): String =
    format(DateTimeFormatter.ofPattern("LLL", locale)).replace(".", "").capitalized(locale)

fun LocalDate.formatMedium(locale: Locale = Locale.getDefault()): String =
    format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

/** ISO `yyyy-MM-dd`, used for persistence and Supabase. */
fun LocalDate.iso(): String = toString()

/** "Today", "Yesterday" or e.g. "Wednesday, 1 Oct". */
fun LocalDate.relativeDayLabel(today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): String = when (this) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    today.plusDays(1) -> "Tomorrow"
    else -> {
        val pattern = if (year == today.year) "EEEE, d MMM" else "EEE, d MMM yyyy"
        format(DateTimeFormatter.ofPattern(pattern, locale)).capitalized(locale)
    }
}
