package com.soloit.doubleentry.domain.reports

import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.IncomeStatementSettings
import com.soloit.doubleentry.domain.Transaction
import com.soloit.doubleentry.domain.matchesAnyPrefix

data class IncomeStatementReport(
    val revenue: ReportSection,
    val expenses: ReportSection,
    val taxExpenses: Long,
    val depreciationExpenses: Long,
    /** Revenue routed straight into deferred (non-spendable) assets in the same transaction. */
    val deferredFromRevenue: Long,
    val hasTaxAccounts: Boolean,
    val hasDepreciationAccounts: Boolean,
    val hasDeferredAccounts: Boolean,
) {
    val totalRevenue: Long get() = revenue.total
    val totalExpenses: Long get() = expenses.total
    val netIncome: Long get() = totalRevenue - totalExpenses
    val spendableNetIncome: Long get() = netIncome - deferredFromRevenue
    val revenueNetOfIncomeTax: Long get() = totalRevenue - taxExpenses
    val cashOperatingExpenses: Long get() = totalExpenses - depreciationExpenses - taxExpenses
    val ebitda: Long get() = totalRevenue - cashOperatingExpenses
    val expensesBeforeDepreciation: Long get() = totalExpenses - depreciationExpenses
    val ebda: Long get() = totalRevenue - expensesBeforeDepreciation
    val ebt: Long get() = totalRevenue - (totalExpenses - taxExpenses)
    val showEbitda: Boolean get() = hasTaxAccounts || hasDepreciationAccounts

    /** Share of revenue that was not spent, or null without revenue. */
    val savingsRate: Float? get() = if (totalRevenue <= 0) null else netIncome.toFloat() / totalRevenue
}

object IncomeStatementCalculator {
    /**
     * @param movements the change of every account's balance over the period (normal side).
     * @param transactions transactions within the period, used for the deferred-revenue subtotal.
     */
    fun compute(
        accounts: List<Account>,
        movements: Map<String, Long>,
        transactions: List<Transaction>,
        settings: IncomeStatementSettings,
    ): IncomeStatementReport {
        val sorted = accounts.sortedBy { it.number }
        val revenueRows = mutableListOf<Pair<Account, ReportRow>>()
        val expenseRows = mutableListOf<Pair<Account, ReportRow>>()
        var depreciation = 0L
        var tax = 0L

        for (a in sorted) {
            if (a.type != AccountType.Revenue && a.type != AccountType.Expense) continue
            val amount = movements[a.id] ?: 0L
            if (amount == 0L) continue
            val row = ReportRow(a.number, a.name, amount, a.id)
            if (a.type == AccountType.Revenue) {
                revenueRows += a to row
            } else {
                expenseRows += a to row
                if (matchesAnyPrefix(a.number, settings.depreciationPrefixes)) depreciation += amount
                if (matchesAnyPrefix(a.number, settings.taxPrefixes)) tax += amount
            }
        }

        return IncomeStatementReport(
            revenue = ReportSection.build(revenueRows, settings.revenueGroups).sortedByAmountDescending(),
            expenses = ReportSection.build(expenseRows, settings.expenseGroups).sortedByAmountDescending(),
            taxExpenses = tax,
            depreciationExpenses = depreciation,
            deferredFromRevenue = deferredFromRevenue(sorted, transactions, settings.deferredAssetPrefixes),
            hasTaxAccounts = settings.taxPrefixes.any { it.isNotBlank() },
            hasDepreciationAccounts = settings.depreciationPrefixes.any { it.isNotBlank() },
            hasDeferredAccounts = settings.deferredAssetPrefixes.any { it.isNotBlank() },
        )
    }

    /** Per transaction: min(debits to deferred assets, credits to revenue). */
    private fun deferredFromRevenue(accounts: List<Account>, transactions: List<Transaction>, prefixes: List<String>): Long {
        if (prefixes.none { it.isNotBlank() }) return 0L
        val deferredIds = accounts.filter { it.type == AccountType.Asset && matchesAnyPrefix(it.number, prefixes) }.map { it.id }.toSet()
        if (deferredIds.isEmpty()) return 0L
        val revenueIds = accounts.filter { it.type == AccountType.Revenue }.map { it.id }.toSet()

        return transactions.sumOf { tx ->
            var deferredDebits = 0L
            var revenueCredits = 0L
            for (e in tx.entries) {
                if (e.type == EntryType.Debit && e.account.id in deferredIds) deferredDebits += e.amount
                else if (e.type == EntryType.Credit && e.account.id in revenueIds) revenueCredits += e.amount
            }
            minOf(deferredDebits, revenueCredits)
        }
    }
}
