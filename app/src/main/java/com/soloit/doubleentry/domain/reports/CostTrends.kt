package com.soloit.doubleentry.domain.reports

import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountGroup
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.indexOfGroupFor
import com.soloit.doubleentry.domain.matchesAnyPrefix
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
    /** Every expense account summed per month. */
    val total: CostSeries = CostSeries(CostTrendCalculator.TOTAL_KEY, CostTrendCalculator.TOTAL_NAME, months.map { 0L }, isGroup = false),
    /** Every expense account except the tax accounts, summed per month; null when no tax accounts are configured. */
    val totalExcludingTax: CostSeries? = null,
    /** Every expense account except depreciation and tax, summed per month; null when no depreciation accounts are configured. */
    val cashOperatingExpenses: CostSeries? = null,
) {
    /** The overall series offered alongside the groups or accounts. */
    val overall: List<CostSeries> get() = listOfNotNull(total, totalExcludingTax, cashOperatingExpenses)
}

object CostTrendCalculator {

    const val OTHER_KEY = "other"
    const val TOTAL_KEY = "total"
    const val TOTAL_NAME = "All expenses"
    const val TOTAL_EXCLUDING_TAX_KEY = "total-ex-tax"
    const val TOTAL_EXCLUDING_TAX_NAME = "All expenses excl. tax"
    const val CASH_OPERATING_KEY = "cash-operating"
    const val CASH_OPERATING_NAME = "Cash operating expenses"

    /** Every month from [start] to [end] inclusive; swapped when given in reverse. */
    fun months(start: YearMonth, end: YearMonth): List<YearMonth> {
        val (from, to) = if (end < start) end to start else start to end
        return generateSequence(from) { it.plusMonths(1) }.takeWhile { it <= to }.toList()
    }

    /**
     * @param monthlyMovements per account id, the balance change for each month (normal side).
     * @param useGroups when true, groups with data come first (in configured order), followed by one
     * "Other expenses" series holding every expense account outside the groups; when false, one series per account.
     * @param taxPrefixes tax expense accounts, left out of the overall series excluding tax and of cash operating expenses.
     * @param depreciationPrefixes depreciation expense accounts, left out of cash operating expenses.
     */
    fun compute(
        accounts: List<Account>,
        months: List<YearMonth>,
        monthlyMovements: Map<String, Map<YearMonth, Long>>,
        groups: List<AccountGroup>,
        useGroups: Boolean = true,
        taxPrefixes: List<String> = emptyList(),
        depreciationPrefixes: List<String> = emptyList(),
    ): CostTrendResult {
        val accountSeries = accounts.filter { it.type == AccountType.Expense }.sortedBy { it.number }.map { account ->
            val perMonth = monthlyMovements[account.id].orEmpty()
            CostSeries("acct:${account.number}", "${account.number} · ${account.name}", months.map { perMonth[it] ?: 0L }, isGroup = false) to account
        }
        fun overall(key: String, name: String, members: List<CostSeries>) =
            CostSeries(key, name, months.indices.map { m -> members.sumOf { it.monthly[m] } }, isGroup = false)
        val total = overall(TOTAL_KEY, TOTAL_NAME, accountSeries.map { it.first })
        val totalExcludingTax = if (taxPrefixes.none { it.isNotBlank() }) null else overall(
            TOTAL_EXCLUDING_TAX_KEY, TOTAL_EXCLUDING_TAX_NAME,
            accountSeries.filterNot { (_, account) -> matchesAnyPrefix(account.number, taxPrefixes) }.map { it.first },
        )
        // With only tax accounts configured this would repeat the series excluding tax, so it needs depreciation accounts.
        val cashOperating = if (depreciationPrefixes.none { it.isNotBlank() }) null else overall(
            CASH_OPERATING_KEY, CASH_OPERATING_NAME,
            accountSeries.filterNot { (_, account) ->
                matchesAnyPrefix(account.number, depreciationPrefixes) || matchesAnyPrefix(account.number, taxPrefixes)
            }.map { it.first },
        )
        if (!useGroups) return CostTrendResult(months, accountSeries.map { it.first }, total, totalExcludingTax, cashOperating)

        val byGroup = accountSeries.groupBy({ (_, account) -> groups.indexOfGroupFor(account.number) }, { it.first })
        fun groupSeries(key: String, name: String, members: List<CostSeries>) = CostSeries(
            key, name, months.indices.map { m -> members.sumOf { it.monthly[m] } }, isGroup = true, accounts = members,
        )
        val series = groups.indices.mapNotNull { gi -> byGroup[gi]?.let { groupSeries("group:$gi", groups[gi].name, it) } } +
            listOfNotNull(byGroup[-1]?.let { groupSeries(OTHER_KEY, "Other expenses", it) })

        return CostTrendResult(months, series, total, totalExcludingTax, cashOperating)
    }
}
