package com.soloit.doubleentry.domain.reports

import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountGroup
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.indexOfGroupFor
import java.time.YearMonth

/** Monthly expense figures for one expense group, the other-expenses bucket, or one expense account. */
data class CostSeries(
    val key: String,
    val name: String,
    val monthly: List<Long>,
    val isGroup: Boolean,
    /** For a group, one series per expense account in it, in account number order. */
    val accounts: List<CostSeries> = emptyList(),
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

    const val OTHER_KEY = "other"

    /** Every month from [start] to [end] inclusive; swapped when given in reverse. */
    fun months(start: YearMonth, end: YearMonth): List<YearMonth> {
        val (from, to) = if (end < start) end to start else start to end
        return generateSequence(from) { it.plusMonths(1) }.takeWhile { it <= to }.toList()
    }

    /**
     * @param monthlyMovements per account id, the balance change for each month (normal side).
     * @param useGroups when true, groups with data come first (in configured order), followed by one
     * "Other expenses" series holding every expense account outside the groups; when false, one series per account.
     */
    fun compute(
        accounts: List<Account>,
        months: List<YearMonth>,
        monthlyMovements: Map<String, Map<YearMonth, Long>>,
        groups: List<AccountGroup>,
        useGroups: Boolean = true,
    ): CostTrendResult {
        val accountSeries = accounts.filter { it.type == AccountType.Expense }.sortedBy { it.number }.map { account ->
            val perMonth = monthlyMovements[account.id].orEmpty()
            CostSeries("acct:${account.number}", "${account.number} · ${account.name}", months.map { perMonth[it] ?: 0L }, isGroup = false) to account
        }
        if (!useGroups) return CostTrendResult(months, accountSeries.map { it.first })

        val byGroup = accountSeries.groupBy({ (_, account) -> groups.indexOfGroupFor(account.number) }, { it.first })
        fun groupSeries(key: String, name: String, members: List<CostSeries>) = CostSeries(
            key, name, months.indices.map { m -> members.sumOf { it.monthly[m] } }, isGroup = true, accounts = members,
        )
        val series = groups.indices.mapNotNull { gi -> byGroup[gi]?.let { groupSeries("group:$gi", groups[gi].name, it) } } +
            listOfNotNull(byGroup[-1]?.let { groupSeries(OTHER_KEY, "Other expenses", it) })

        return CostTrendResult(months, series)
    }
}
