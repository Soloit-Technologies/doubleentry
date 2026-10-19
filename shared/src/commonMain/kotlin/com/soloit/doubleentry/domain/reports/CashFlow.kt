package com.soloit.doubleentry.domain.reports

import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountGroup
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Transaction
import com.soloit.doubleentry.domain.indexOfGroupFor
import com.soloit.doubleentry.domain.yearMonth
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class CashAccountRow(
    val accountId: String,
    val number: Int,
    val name: String,
    val beginning: Long,
    /** Gross debits (money in) — not netted against transfers. */
    val inflow: Long,
    /** Gross credits (money out). */
    val outflow: Long,
    val ending: Long,
) {
    val netFlow: Long get() = ending - beginning
}

data class CashGroupRow(
    val name: String,
    val beginning: Long,
    /** Inflow after netting each transaction within the group (internal transfers cancel out). */
    val inflow: Long,
    val outflow: Long,
    val ending: Long,
    val accounts: List<CashAccountRow>,
) {
    val netFlow: Long get() = ending - beginning
}

/** One step of the cash timeline: a day for short periods, otherwise a month. */
data class CashFlowPoint(
    val start: LocalDate,
    val end: LocalDate,
    val inflow: Long,
    val outflow: Long,
    /** Total cash at the end of this step. */
    val balance: Long,
) {
    val netFlow: Long get() = inflow - outflow
}

data class CashFlowReport(
    val groups: List<CashGroupRow>,
    val beginningCash: Long,
    val totalInflows: Long,
    val totalOutflows: Long,
    val timeline: List<CashFlowPoint> = emptyList(),
    /** Whether [timeline] steps are days (otherwise months). */
    val dailyTimeline: Boolean = true,
) {
    val netCashFlow: Long get() = totalInflows - totalOutflows
    val endingCash: Long get() = beginningCash + netCashFlow
}

object CashFlowCalculator {
    /**
     * @param beginningBalances balances the day before the period starts.
     * @param endingBalances balances at the last day of the period.
     * @param transactions transactions within the period.
     * @param period the reported dates; when given, [CashFlowReport.timeline] is filled in. A period that
     *   runs past [today] is charted only up to today (or the last transaction, if later).
     */
    fun compute(
        accounts: List<Account>,
        beginningBalances: Map<String, Long>,
        endingBalances: Map<String, Long>,
        transactions: List<Transaction>,
        groups: List<AccountGroup>,
        period: ClosedRange<LocalDate>? = null,
        today: LocalDate = LocalDate.now(),
    ): CashFlowReport {
        fun groupOf(account: Account): Int =
            if (account.type == AccountType.Asset) groups.indexOfGroupFor(account.number) else -1

        val cashAccounts = accounts.filter { groupOf(it) >= 0 }.sortedBy { it.number }
        val groupBeginning = LongArray(groups.size)
        val groupEnding = LongArray(groups.size)
        var beginningCash = 0L
        for (a in cashAccounts) {
            val gi = groupOf(a)
            val beg = beginningBalances[a.id] ?: 0L
            groupBeginning[gi] += beg
            groupEnding[gi] += endingBalances[a.id] ?: 0L
            beginningCash += beg
        }

        val groupInflows = LongArray(groups.size)
        val groupOutflows = LongArray(groups.size)
        val accountInflows = mutableMapOf<String, Long>()
        val accountOutflows = mutableMapOf<String, Long>()
        val dailyInflows = mutableMapOf<LocalDate, Long>()
        val dailyOutflows = mutableMapOf<LocalDate, Long>()

        for (tx in transactions) {
            val netPerGroup = mutableMapOf<Int, Long>()
            for (e in tx.entries) {
                val gi = groupOf(e.account)
                if (gi < 0) continue
                val delta = if (e.type == EntryType.Debit) e.amount else -e.amount
                netPerGroup[gi] = (netPerGroup[gi] ?: 0L) + delta
                if (e.type == EntryType.Debit) accountInflows.merge(e.account.id, e.amount, Long::plus)
                else accountOutflows.merge(e.account.id, e.amount, Long::plus)
            }
            for ((gi, net) in netPerGroup) {
                if (net > 0) {
                    groupInflows[gi] += net
                    dailyInflows.merge(tx.date, net, Long::plus)
                } else if (net < 0) {
                    groupOutflows[gi] += -net
                    dailyOutflows.merge(tx.date, -net, Long::plus)
                }
            }
        }

        val rows = groups.mapIndexed { gi, group ->
            CashGroupRow(
                name = group.name,
                beginning = groupBeginning[gi],
                inflow = groupInflows[gi],
                outflow = groupOutflows[gi],
                ending = groupEnding[gi],
                accounts = cashAccounts.filter { groupOf(it) == gi }.map { a ->
                    CashAccountRow(
                        accountId = a.id,
                        number = a.number,
                        name = a.name,
                        beginning = beginningBalances[a.id] ?: 0L,
                        inflow = accountInflows[a.id] ?: 0L,
                        outflow = accountOutflows[a.id] ?: 0L,
                        ending = endingBalances[a.id] ?: 0L,
                    )
                },
            )
        }

        val daily = period == null || ChronoUnit.DAYS.between(period.start, period.endInclusive) < DAILY_LIMIT_DAYS
        val timeline = period?.let {
            val lastTx = transactions.maxOfOrNull { tx -> tx.date }
            val end = if (it.start > today) it.endInclusive else minOf(it.endInclusive, maxOf(today, lastTx ?: today))
            timeline(it.start, end, daily, beginningCash, dailyInflows, dailyOutflows)
        }.orEmpty()

        return CashFlowReport(rows, beginningCash, groupInflows.sum(), groupOutflows.sum(), timeline, daily)
    }

    /** Periods shorter than this are charted day by day; longer ones month by month. */
    private const val DAILY_LIMIT_DAYS = 62L

    private fun timeline(
        from: LocalDate,
        to: LocalDate,
        daily: Boolean,
        beginningCash: Long,
        inflows: Map<LocalDate, Long>,
        outflows: Map<LocalDate, Long>,
    ): List<CashFlowPoint> {
        val points = mutableListOf<CashFlowPoint>()
        var balance = beginningCash
        var start = from
        while (start <= to) {
            val end = if (daily) start else minOf(start.yearMonth.atEndOfMonth(), to)
            var inflow = 0L
            var outflow = 0L
            var day = start
            while (day <= end) {
                inflow += inflows[day] ?: 0L
                outflow += outflows[day] ?: 0L
                day = day.plusDays(1)
            }
            balance += inflow - outflow
            points += CashFlowPoint(start, end, inflow, outflow, balance)
            start = end.plusDays(1)
        }
        return points
    }
}
