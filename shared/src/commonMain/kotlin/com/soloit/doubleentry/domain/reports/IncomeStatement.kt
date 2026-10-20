package com.soloit.doubleentry.domain.reports

import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.IncomeStatementSettings
import com.soloit.doubleentry.domain.Transaction
import com.soloit.doubleentry.domain.matchesAnyPrefix
import kotlin.math.roundToLong

data class IncomeStatementReport(
    val revenue: ReportSection,
    val expenses: ReportSection,
    /** Expense accounts counted as income tax, largest first. */
    val taxRows: List<ReportRow>,
    /** Expense accounts counted as depreciation, largest first. */
    val depreciationRows: List<ReportRow>,
    /** Revenue routed straight into deferred (non-spendable) assets in the same transaction. */
    val deferredFromRevenue: Long,
    val hasTaxAccounts: Boolean,
    val hasDepreciationAccounts: Boolean,
    val hasDeferredAccounts: Boolean,
) {
    val totalRevenue: Long get() = revenue.total
    val totalExpenses: Long get() = expenses.total
    val taxExpenses: Long get() = taxRows.sumOf { it.amount }
    val depreciationExpenses: Long get() = depreciationRows.sumOf { it.amount }
    val netIncome: Long get() = totalRevenue - totalExpenses
    val spendableNetIncome: Long get() = netIncome - deferredFromRevenue
    /** Every expense except depreciation and income tax. */
    val operatingExpenses: Long get() = totalExpenses - depreciationExpenses - taxExpenses
    /** Earnings before tax and depreciation (EBTDA). Interest is not split out, so this is not EBITDA. */
    val earningsBeforeTaxAndDepreciation: Long get() = totalRevenue - operatingExpenses
    val ebt: Long get() = netIncome + taxExpenses

    /** Share of revenue that was not spent, or null without revenue. */
    val savingsRate: Float? get() = if (totalRevenue <= 0) null else netIncome.toFloat() / totalRevenue

    /**
     * The way from revenue to net income one step at a time, so every subtotal shows what it is made
     * of. Steps for tax, depreciation and deferred assets only appear when those accounts are set up.
     */
    val breakdown: List<BreakdownLine>
        get() = buildList {
            add(BreakdownLine(BreakdownLine.Kind.Start, "Revenue", totalRevenue))
            if (!hasTaxAccounts && !hasDepreciationAccounts) {
                add(BreakdownLine(BreakdownLine.Kind.Less, "Expenses", totalExpenses))
            } else {
                val excluded = listOfNotNull("depreciation".takeIf { hasDepreciationAccounts }, "income tax".takeIf { hasTaxAccounts })
                add(BreakdownLine(BreakdownLine.Kind.Less, "Operating expenses", operatingExpenses, "All expenses except ${excluded.joinToString(" and ")}"))
                val (label, note) = when {
                    hasTaxAccounts && hasDepreciationAccounts -> "Before tax and depreciation" to "EBTDA"
                    hasTaxAccounts -> "Before tax" to "EBT"
                    else -> "Before depreciation" to "EBDA"
                }
                add(BreakdownLine(BreakdownLine.Kind.Subtotal, label, earningsBeforeTaxAndDepreciation, note))
                if (hasDepreciationAccounts) {
                    add(BreakdownLine(BreakdownLine.Kind.Less, "Depreciation", depreciationExpenses, accounts = depreciationRows))
                    if (hasTaxAccounts) add(BreakdownLine(BreakdownLine.Kind.Subtotal, "Before tax", ebt, "EBT"))
                }
                if (hasTaxAccounts) add(BreakdownLine(BreakdownLine.Kind.Less, "Income tax", taxExpenses, accounts = taxRows))
            }
            add(BreakdownLine(BreakdownLine.Kind.Result, "Net income", netIncome))
            if (hasDeferredAccounts) {
                add(BreakdownLine(BreakdownLine.Kind.Less, "Routed to deferred assets", deferredFromRevenue, "e.g. salary paid straight into pension savings"))
                add(BreakdownLine(BreakdownLine.Kind.Result, "Spendable net income", spendableNetIncome))
            }
        }
}

/**
 * One step of [IncomeStatementReport.breakdown]. A [Kind.Less] amount is what gets subtracted, as a
 * positive number; [label] is unique within a breakdown, so it matches the same line of another period.
 */
data class BreakdownLine(
    val kind: Kind,
    val label: String,
    val amount: Long,
    val note: String? = null,
    /** The accounts behind a step that the report does not list on its own elsewhere. */
    val accounts: List<ReportRow> = emptyList(),
) {
    enum class Kind { Start, Less, Subtotal, Result }
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
        val depreciationRows = mutableListOf<ReportRow>()
        val taxRows = mutableListOf<ReportRow>()

        for (a in sorted) {
            if (a.type != AccountType.Revenue && a.type != AccountType.Expense) continue
            val amount = movements[a.id] ?: 0L
            if (amount == 0L) continue
            val row = ReportRow(a.number, a.name, amount, a.id)
            if (a.type == AccountType.Revenue) {
                revenueRows += a to row
            } else {
                expenseRows += a to row
                // An account matching both lists counts once, as tax, so the breakdown still adds up.
                if (matchesAnyPrefix(a.number, settings.taxPrefixes)) taxRows += row
                else if (matchesAnyPrefix(a.number, settings.depreciationPrefixes)) depreciationRows += row
            }
        }

        return IncomeStatementReport(
            revenue = ReportSection.build(revenueRows, settings.revenueGroups).sortedByAmountDescending(),
            expenses = ReportSection.build(expenseRows, settings.expenseGroups).sortedByAmountDescending(),
            taxRows = taxRows.sortedByDescending { it.amount },
            depreciationRows = depreciationRows.sortedByDescending { it.amount },
            deferredFromRevenue = deferredFromRevenue(sorted, transactions, settings.deferredAssetPrefixes),
            hasTaxAccounts = settings.taxPrefixes.any { it.isNotBlank() },
            hasDepreciationAccounts = settings.depreciationPrefixes.any { it.isNotBlank() },
            hasDeferredAccounts = settings.deferredAssetPrefixes.any { it.isNotBlank() },
        )
    }

    /**
     * The average month of a [months]-long span: [compute] over the whole span with every account's
     * movement divided by [months], so group and section totals still add up row by row.
     */
    fun computeMonthlyAverage(
        accounts: List<Account>,
        movements: Map<String, Long>,
        transactions: List<Transaction>,
        settings: IncomeStatementSettings,
        months: Int,
    ): IncomeStatementReport {
        fun average(cents: Long) = (cents.toDouble() / months).roundToLong()
        val report = compute(accounts, movements.mapValues { average(it.value) }, transactions, settings)
        return report.copy(deferredFromRevenue = average(report.deferredFromRevenue))
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
