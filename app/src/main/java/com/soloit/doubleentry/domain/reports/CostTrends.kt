package com.soloit.doubleentry.domain.reports

import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountGroup
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.indexOfGroupFor
import java.time.YearMonth

/** Monthly expense figures for one expense group or one ungrouped expense account. */
data class CostSeries(
    val key: String,
    val name: String,
    val monthly: List<Long>,
    val isGroup: Boolean,
) {
    val total: Long get() = monthly.sum()
    val latest: Long get() = monthly.lastOrNull() ?: 0L
    val perMonth: Long get() = if (monthly.isEmpty()) 0L else total / monthly.size
    val peak: Long get() = monthly.maxOrNull() ?: 0L
}

data class CostTrendResult(
    val months: List<YearMonth>,
    val series: List<CostSeries>,
)

object CostTrendCalculator {

    /** Every month from [start] to [end] inclusive; swapped when given in reverse. */
    fun months(start: YearMonth, end: YearMonth): List<YearMonth> {
        val (from, to) = if (end < start) end to start else start to end
        return generateSequence(from) { it.plusMonths(1) }.takeWhile { it <= to }.toList()
    }

    /**
     * @param monthlyMovements per account id, the balance change for each month (normal side).
     * Groups with data come first (in configured order), followed by ungrouped accounts.
     */
    fun compute(
        accounts: List<Account>,
        months: List<YearMonth>,
        monthlyMovements: Map<String, Map<YearMonth, Long>>,
        groups: List<AccountGroup>,
    ): CostTrendResult {
        val expenseAccounts = accounts.filter { it.type == AccountType.Expense }.sortedBy { it.number }
        val groupTotals = List(groups.size) { LongArray(months.size) }
        val groupHasData = BooleanArray(groups.size)
        val ungrouped = mutableListOf<CostSeries>()

        for (account in expenseAccounts) {
            val perMonth = monthlyMovements[account.id].orEmpty()
            val monthly = months.map { perMonth[it] ?: 0L }
            val gi = groups.indexOfGroupFor(account.number)
            if (gi >= 0) {
                monthly.forEachIndexed { m, v -> groupTotals[gi][m] += v }
                groupHasData[gi] = true
            } else {
                ungrouped += CostSeries("acct:${account.number}", "${account.number} · ${account.name}", monthly, isGroup = false)
            }
        }

        val series = groups.indices
            .filter { groupHasData[it] }
            .map { CostSeries("group:$it", groups[it].name, groupTotals[it].toList(), isGroup = true) } + ungrouped

        return CostTrendResult(months, series)
    }
}
