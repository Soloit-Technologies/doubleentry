package com.soloit.doubleentry.domain.reports

import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import java.time.YearMonth

/** Asset and liability totals at the end of [month]. */
data class NetWorthPoint(val month: YearMonth, val assets: Long, val liabilities: Long) {
    val netWorth: Long get() = assets - liabilities
}

/** Net worth month by month, oldest first. [opening] is the net worth just before the first month. */
data class NetWorthTrend(val points: List<NetWorthPoint>, val opening: Long = 0L) {
    val latest: NetWorthPoint? get() = points.lastOrNull()

    /** Change over all the months, from [opening] to the last point. */
    val change: Long get() = (points.lastOrNull()?.netWorth ?: opening) - opening

    /** Change during the month at [index]. */
    fun changeAt(index: Int): Long = points[index].netWorth - if (index == 0) opening else points[index - 1].netWorth
}

object NetWorthCalculator {
    /**
     * @param openingBalances balances of every account just before the first of [months], on each
     *   account's normal side.
     * @param monthlyMovements per account id, the balance change for each month (normal side).
     */
    fun compute(
        accounts: List<Account>,
        months: List<YearMonth>,
        openingBalances: Map<String, Long>,
        monthlyMovements: Map<String, Map<YearMonth, Long>>,
    ): NetWorthTrend {
        val assets = accounts.filter { it.type == AccountType.Asset }
        val liabilities = accounts.filter { it.type == AccountType.Liability }
        var assetTotal = assets.sumOf { openingBalances[it.id] ?: 0L }
        var liabilityTotal = liabilities.sumOf { openingBalances[it.id] ?: 0L }
        val opening = assetTotal - liabilityTotal
        val points = months.map { month ->
            assetTotal += assets.sumOf { monthlyMovements[it.id]?.get(month) ?: 0L }
            liabilityTotal += liabilities.sumOf { monthlyMovements[it.id]?.get(month) ?: 0L }
            NetWorthPoint(month, assetTotal, liabilityTotal)
        }
        return NetWorthTrend(points, opening)
    }
}
