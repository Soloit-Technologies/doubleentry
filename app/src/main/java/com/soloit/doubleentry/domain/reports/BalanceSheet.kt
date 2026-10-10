package com.soloit.doubleentry.domain.reports

import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountGroup
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.BalanceSheetSettings
import com.soloit.doubleentry.domain.indexOfGroupFor
import java.time.LocalDate

/** A report row. [accountId] is null for computed rows such as retained earnings. */
data class ReportRow(
    val number: Int?,
    val name: String,
    val amount: Long,
    val accountId: String? = null,
)

data class ReportGroup(
    val title: String,
    val rows: List<ReportRow>,
) {
    val total: Long get() = rows.sumOf { it.amount }
}

/** Accounts of one type, split into configured groups plus everything left over. */
data class ReportSection(
    val groups: List<ReportGroup>,
    val ungrouped: List<ReportRow>,
) {
    val total: Long get() = groups.sumOf { it.total } + ungrouped.sumOf { it.amount }
    val isEmpty: Boolean get() = groups.isEmpty() && ungrouped.isEmpty()
    val hasGroups: Boolean get() = groups.isNotEmpty()

    /** Largest first: groups by total, rows within each group and the ungrouped rows by amount. */
    fun sortedByAmountDescending(): ReportSection = ReportSection(
        groups = groups.map { it.copy(rows = it.rows.sortedByDescending { r -> r.amount }) }.sortedByDescending { it.total },
        ungrouped = ungrouped.sortedByDescending { it.amount },
    )

    companion object {
        /** Rows matching a group go to the first matching group; empty groups are omitted. */
        fun build(rows: List<Pair<Account, ReportRow>>, groups: List<AccountGroup>): ReportSection {
            if (groups.isEmpty()) return ReportSection(emptyList(), rows.map { it.second })
            val buckets = List(groups.size) { mutableListOf<ReportRow>() }
            val ungrouped = mutableListOf<ReportRow>()
            for ((account, row) in rows) {
                val index = groups.indexOfGroupFor(account.number)
                if (index >= 0) buckets[index] += row else ungrouped += row
            }
            val built = groups.mapIndexedNotNull { i, g -> if (buckets[i].isEmpty()) null else ReportGroup(g.name, buckets[i]) }
            return ReportSection(built, ungrouped)
        }
    }
}

data class BalanceSheetReport(
    val asOf: LocalDate,
    val assets: ReportSection,
    val liabilities: ReportSection,
    val equity: List<ReportRow>,
) {
    val totalAssets: Long get() = assets.total
    val totalLiabilities: Long get() = liabilities.total
    val totalEquity: Long get() = equity.sumOf { it.amount }
    val totalLiabilitiesAndEquity: Long get() = totalLiabilities + totalEquity
    val isBalanced: Boolean get() = totalAssets == totalLiabilitiesAndEquity
}

object BalanceSheetCalculator {
    /**
     * @param balances balances of every account as of [asOf], on each account's normal side.
     * @param priorYearEndBalances balances as of 31 December of the previous year; used to split
     *   retained earnings into prior years and the current year.
     */
    fun compute(
        asOf: LocalDate,
        accounts: List<Account>,
        balances: Map<String, Long>,
        priorYearEndBalances: Map<String, Long>,
        settings: BalanceSheetSettings,
    ): BalanceSheetReport {
        val sorted = accounts.sortedBy { it.number }
        fun row(a: Account) = a to ReportRow(a.number, a.name, balances[a.id] ?: 0L, a.id)

        val assets = ReportSection.build(sorted.filter { it.type == AccountType.Asset }.map(::row), settings.assetGroups)
        val liabilities = ReportSection.build(sorted.filter { it.type == AccountType.Liability }.map(::row), settings.liabilityGroups)
        val equity = sorted.filter { it.type == AccountType.Equity }.map { row(it).second }.toMutableList()

        var priorRevenue = 0L
        var priorExpenses = 0L
        var currentRevenue = 0L
        var currentExpenses = 0L
        for (a in sorted) {
            val balance = balances[a.id] ?: 0L
            val prior = priorYearEndBalances[a.id] ?: 0L
            when (a.type) {
                AccountType.Revenue -> { priorRevenue += prior; currentRevenue += balance - prior }
                AccountType.Expense -> { priorExpenses += prior; currentExpenses += balance - prior }
                else -> Unit
            }
        }
        val priorEarnings = priorRevenue - priorExpenses
        val currentEarnings = currentRevenue - currentExpenses
        if (priorEarnings != 0L) equity += ReportRow(null, "Retained earnings (prior to ${asOf.year})", priorEarnings)
        if (currentEarnings != 0L) equity += ReportRow(null, "Retained earnings (${asOf.year})", currentEarnings)

        return BalanceSheetReport(asOf, assets, liabilities, equity)
    }
}
