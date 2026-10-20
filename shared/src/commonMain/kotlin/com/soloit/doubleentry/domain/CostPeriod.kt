package com.soloit.doubleentry.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Inclusive range of days that an expense or income line belongs to, independent of when it was paid. */
data class CostPeriod(val start: LocalDate, val end: LocalDate) {
    val days: Long get() = ChronoUnit.DAYS.between(start, end) + 1

    val months: List<YearMonth>
        get() = generateSequence(start.yearMonth) { it.plusMonths(1) }.takeWhile { it <= end.yearMonth }.toList()

    /** A whole calendar month, as made by the "previous/next month" shortcuts. */
    val wholeMonth: YearMonth?
        get() = start.yearMonth.takeIf { start.dayOfMonth == 1 && end == it.lastDay() }

    /** "October 2026" for a whole month, otherwise "15 Oct – 15 Dec 2026". */
    fun label(locale: Locale = Locale.getDefault()): String {
        wholeMonth?.let { return it.label(locale) }
        val sameYear = start.year == end.year
        val from = start.format(DateTimeFormatter.ofPattern(if (sameYear) "d MMM" else "d MMM yyyy", locale))
        val to = end.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
        return "$from – $to".replace(".", "")
    }

    companion object {
        fun month(month: YearMonth) = CostPeriod(month.firstDay(), month.lastDay())
    }
}

/**
 * Accrual accounting for lines whose cost or income belongs to other months than the transaction
 * date (periodisering). The part of a line that falls in the transaction's own month stays on its
 * account; the rest goes to an interim balance-sheet account and is moved back to the line's
 * account by one posting per month, dated on the month's last day:
 *
 * - months after the transaction: prepaid expenses / income received in advance,
 * - months before it: accrued expenses / accrued income.
 *
 * The amount is split by days, so 15 Oct – 15 Dec gives October 17 of 62 days.
 */
object Periodization {
    /** Upper bound so a mistyped year can't create thousands of postings. */
    const val MAX_MONTHS = 120

    data class Share(val month: YearMonth, val amount: Long)

    enum class Interim { PrepaidExpenses, AccruedExpenses, AccruedIncome, DeferredIncome }

    /** The postings and the lines that replace the period lines in the transaction itself. */
    data class Expansion(
        val sourceLines: List<DraftLine>,
        /** Lines of the posting for each month other than the transaction's, in month order. */
        val postings: List<Pair<YearMonth, List<DraftLine>>>,
        /** For every period line (by index in the draft), the lines it became in the transaction. */
        val parts: Map<Int, List<DraftLine>>,
    )

    fun canHavePeriod(type: AccountType): Boolean = type == AccountType.Expense || type == AccountType.Revenue

    fun validate(period: CostPeriod) {
        if (period.end < period.start) throw LedgerException("A period cannot end before it starts.")
        if (period.months.size > MAX_MONTHS) throw LedgerException("A period can span at most ${MAX_MONTHS / 12} years.")
    }

    /**
     * Splits [amount] over the months of [period] in proportion to their days. Shares are the
     * differences of rounded running totals, so they always sum to [amount]. Empty shares are left out.
     */
    fun shares(amount: Long, period: CostPeriod): List<Share> {
        val total = BigDecimal.valueOf(period.days)
        var daysSoFar = 0L
        var allocated = 0L
        return period.months.mapNotNull { month ->
            val from = maxOf(period.start, month.firstDay())
            val to = minOf(period.end, month.lastDay())
            daysSoFar += ChronoUnit.DAYS.between(from, to) + 1
            val cumulative = BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(daysSoFar)).divide(total, 0, RoundingMode.HALF_UP).toLong()
            val share = cumulative - allocated
            allocated = cumulative
            if (share != 0L) Share(month, share) else null
        }
    }

    /** The interim account a share in [month] passes through when the transaction is dated [date]; null in its own month. */
    fun interimFor(type: AccountType, month: YearMonth, date: LocalDate): Interim? {
        val own = date.yearMonth
        val expense = type != AccountType.Revenue
        return when {
            month == own -> null
            month > own -> if (expense) Interim.PrepaidExpenses else Interim.DeferredIncome
            else -> if (expense) Interim.AccruedExpenses else Interim.AccruedIncome
        }
    }

    /** Interim accounts [lines] need when posted on [date]. */
    fun interimsNeeded(lines: List<DraftLine>, types: Map<String, AccountType>, date: LocalDate): Set<Interim> =
        lines.flatMap { line ->
            val period = line.period ?: return@flatMap emptyList()
            val type = types[line.accountId] ?: AccountType.Expense
            shares(line.amount, period).mapNotNull { interimFor(type, it.month, date) }
        }.toSet()

    /**
     * Expands the period lines of [draft]. [interims] maps every interim kind the draft needs (see
     * [interimsNeeded]) to an account id. Postings move each month's share from the interim account
     * to the line's account, on the line's own side.
     */
    fun expand(draft: TransactionDraft, types: Map<String, AccountType>, interims: Map<Interim, String>): Expansion {
        val sourceLines = mutableListOf<DraftLine>()
        val parts = mutableMapOf<Int, List<DraftLine>>()
        val byMonth = sortedMapOf<YearMonth, MutableList<DraftLine>>()
        draft.lines.forEachIndexed { index, line ->
            val period = line.period
            if (period == null) {
                sourceLines += line
                return@forEachIndexed
            }
            val type = types[line.accountId] ?: AccountType.Expense
            val opposite = if (line.type == EntryType.Debit) EntryType.Credit else EntryType.Debit
            // Amount per account in the transaction: the line's own account for its own month, interims for the rest.
            val totals = linkedMapOf<String, Long>()
            for (share in shares(line.amount, period)) {
                val interim = interimFor(type, share.month, draft.date)
                if (interim == null) {
                    totals.merge(line.accountId, share.amount, Long::plus)
                } else {
                    val interimId = interims.getValue(interim)
                    totals.merge(interimId, share.amount, Long::plus)
                    byMonth.getOrPut(share.month) { mutableListOf() } += listOf(
                        DraftLine(line.accountId, share.amount, line.type),
                        DraftLine(interimId, share.amount, opposite),
                    )
                }
            }
            val lineParts = totals.map { (accountId, amount) -> DraftLine(accountId, amount, line.type) }
            parts[index] = lineParts
            sourceLines += lineParts
        }
        return Expansion(sourceLines, byMonth.map { (month, lines) -> month to lines.toList() }, parts)
    }

    /**
     * The inverse of [expand] for editing: removes the [parts] each period line became from
     * [entries] and puts the period line back where its first part was. Returns null when the
     * transaction no longer contains the parts (for example after an edit on an older app version).
     */
    fun collapse(entries: List<DraftLine>, periodLines: List<Pair<DraftLine, List<DraftLine>>>): List<DraftLine>? {
        val remaining = entries.toMutableList<DraftLine?>()
        val placed = mutableMapOf<Int, DraftLine>()
        for ((line, lineParts) in periodLines) {
            var first: Int? = null
            for (part in lineParts) {
                val at = remaining.indices.firstOrNull { i ->
                    remaining[i]?.let { it.accountId == part.accountId && it.amount == part.amount && it.type == part.type } == true
                } ?: return null
                remaining[at] = null
                if (first == null || at < first) first = at
            }
            // A line without parts (amount too small to split) is kept at the end.
            placed[first ?: (entries.size + placed.size)] = line
        }
        val result = mutableListOf<DraftLine>()
        for (i in 0 until entries.size + periodLines.size) {
            placed[i]?.let { result += it }
            remaining.getOrNull(i)?.let { result += it }
        }
        return result
    }
}

/**
 * Interim accounts for [Periodization], by account number (like the invoice settings). Only the
 * default plan presets them: lines can only be spread over months whose interim account has been chosen.
 */
data class CostPeriodAccounts(
    val prepaidExpenses: Int? = null,
    val accruedExpenses: Int? = null,
    val accruedIncome: Int? = null,
    val deferredIncome: Int? = null,
) {
    fun numberFor(interim: Periodization.Interim): Int? = when (interim) {
        Periodization.Interim.PrepaidExpenses -> prepaidExpenses
        Periodization.Interim.AccruedExpenses -> accruedExpenses
        Periodization.Interim.AccruedIncome -> accruedIncome
        Periodization.Interim.DeferredIncome -> deferredIncome
    }
}
