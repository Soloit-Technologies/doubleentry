package com.soloit.doubleentry.domain.reports

import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountGroup
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Transaction
import com.soloit.doubleentry.domain.indexOfGroupFor

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

data class CashFlowReport(
    val groups: List<CashGroupRow>,
    val beginningCash: Long,
    val totalInflows: Long,
    val totalOutflows: Long,
) {
    val netCashFlow: Long get() = totalInflows - totalOutflows
    val endingCash: Long get() = beginningCash + netCashFlow
}

object CashFlowCalculator {
    /**
     * @param beginningBalances balances the day before the period starts.
     * @param endingBalances balances at the last day of the period.
     * @param transactions transactions within the period.
     */
    fun compute(
        accounts: List<Account>,
        beginningBalances: Map<String, Long>,
        endingBalances: Map<String, Long>,
        transactions: List<Transaction>,
        groups: List<AccountGroup>,
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
                if (net > 0) groupInflows[gi] += net else if (net < 0) groupOutflows[gi] += -net
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

        return CashFlowReport(rows, beginningCash, groupInflows.sum(), groupOutflows.sum())
    }
}
