package com.soloit.doubleentry.domain

import java.time.LocalDate

enum class Frequency(val label: String, val noun: String) {
    Weekly("Weekly", "week"),
    Monthly("Monthly", "month"),
    Quarterly("Quarterly", "quarter"),
    Yearly("Yearly", "year"),
}

/**
 * A transaction that is posted automatically on a schedule (rent, salary, subscriptions …).
 * Occurrences are computed from [startDate] (so the 31st clamps to shorter months and recovers),
 * and every occurrence after [lastPostedDate] up to today is posted with a deterministic id.
 */
data class RecurringRule(
    val id: String,
    val title: String,
    val description: String,
    val lines: List<DraftLine>,
    val frequency: Frequency,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    /** Date of the most recent occurrence that was posted (or skipped because it already existed). */
    val lastPostedDate: LocalDate? = null,
    val paused: Boolean = false,
) {
    val amount: Long get() = lines.filter { it.type == EntryType.Debit }.sumOf { it.amount }
}

object Recurrence {
    /** Upper bound on occurrences posted in one run, so a misconfigured rule can't flood the ledger. */
    const val MAX_PER_RUN = 500

    /** The [n]th occurrence (0-based), always computed from the start date. */
    fun occurrence(rule: RecurringRule, n: Int): LocalDate = when (rule.frequency) {
        Frequency.Weekly -> rule.startDate.plusWeeks(n.toLong())
        Frequency.Monthly -> rule.startDate.plusMonths(n.toLong())
        Frequency.Quarterly -> rule.startDate.plusMonths(3L * n)
        Frequency.Yearly -> rule.startDate.plusYears(n.toLong())
    }

    /** All occurrences up to and including [through] (and the rule's end date). */
    fun occurrences(rule: RecurringRule, through: LocalDate): Sequence<LocalDate> {
        val limit = rule.endDate?.let { minOf(it, through) } ?: through
        return generateSequence(0) { it + 1 }.map { occurrence(rule, it) }.takeWhile { it <= limit }
    }

    /** Occurrences that should be posted on [today]: after the last posted one, up to today. */
    fun due(rule: RecurringRule, today: LocalDate): List<LocalDate> {
        if (rule.paused) return emptyList()
        return occurrences(rule, today)
            .filter { rule.lastPostedDate == null || it > rule.lastPostedDate }
            .take(MAX_PER_RUN)
            .toList()
    }

    /** The next occurrence that has not been posted yet, or null when the rule has ended. */
    fun next(rule: RecurringRule, today: LocalDate): LocalDate? {
        val after = listOfNotNull(rule.lastPostedDate, today.minusDays(1)).max()
        return generateSequence(0) { it + 1 }
            .map { occurrence(rule, it) }
            .takeWhile { rule.endDate == null || it <= rule.endDate }
            .firstOrNull { it > after }
    }

    fun validate(rule: RecurringRule) {
        if (rule.title.isBlank()) throw LedgerException("Title is required.")
        if (rule.endDate != null && rule.endDate < rule.startDate) throw LedgerException("The end date cannot be before the start date.")
        TransactionValidator.validate(TransactionDraft(rule.startDate, rule.title, rule.description, rule.lines))
    }

    fun summary(rule: RecurringRule): String = buildString {
        append(rule.frequency.label)
        when (rule.frequency) {
            Frequency.Weekly -> append(" on ").append(rule.startDate.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() })
            Frequency.Monthly, Frequency.Quarterly -> append(" on day ").append(rule.startDate.dayOfMonth)
            Frequency.Yearly -> append(" on ").append(rule.startDate.formatMedium())
        }
        rule.endDate?.let { append(" until ").append(it.formatMedium()) }
    }
}
