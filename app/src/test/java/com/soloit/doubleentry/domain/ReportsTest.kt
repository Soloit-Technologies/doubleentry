package com.soloit.doubleentry.domain

import com.soloit.doubleentry.domain.reports.BalanceSheetCalculator
import com.soloit.doubleentry.domain.reports.CashFlowCalculator
import com.soloit.doubleentry.domain.reports.CostTrendCalculator
import com.soloit.doubleentry.domain.reports.IncomeStatementCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ReportsTest {
    private val cash = Account("cash", 1010, "Cash", AccountType.Asset)
    private val checking = Account("chk", 1910, "Checking", AccountType.Asset)
    private val savings = Account("sav", 1920, "Savings", AccountType.Asset)
    private val pension = Account("pen", 1940, "Pension", AccountType.Asset)
    private val mortgage = Account("mtg", 2350, "Mortgage", AccountType.Liability)
    private val equity = Account("eq", 2010, "Opening balance", AccountType.Equity)
    private val salary = Account("sal", 3010, "Salary", AccountType.Revenue)
    private val rent = Account("rent", 5010, "Rent", AccountType.Expense)
    private val food = Account("food", 6010, "Groceries", AccountType.Expense)
    private val tax = Account("tax", 8910, "Income tax", AccountType.Expense)
    private val accounts = listOf(cash, checking, savings, pension, mortgage, equity, salary, rent, food, tax)

    private fun tx(id: String, vararg entries: Triple<Account, Long, EntryType>) =
        Transaction(id, LocalDate.of(2026, 3, 10), id, "", entries.mapIndexed { i, (a, amt, t) -> Entry("$id-$i", a, amt, t) })

    @Test
    fun balanceSheetSplitsRetainedEarningsAndBalances() {
        val balances = mapOf("chk" to 50_000L, "mtg" to 20_000L, "eq" to 10_000L, "sal" to 30_000L, "rent" to 10_000L)
        val prior = mapOf("sal" to 12_000L, "rent" to 2_000L)
        val report = BalanceSheetCalculator.compute(
            LocalDate.of(2026, 3, 31), accounts, balances, prior,
            BalanceSheetSettings(assetGroups = listOf(AccountGroup("Bank", listOf("19")))),
        )
        assertEquals(50_000L, report.totalAssets)
        assertEquals(listOf("Bank"), report.assets.groups.map { it.title })
        assertEquals(listOf(1010), report.assets.ungrouped.map { it.number })
        val retained = report.equity.filter { it.accountId == null }.associate { it.name to it.amount }
        assertEquals(10_000L, retained["Retained earnings (prior to 2026)"])
        assertEquals(10_000L, retained["Retained earnings (2026)"])
        assertTrue(report.isBalanced)
    }

    @Test
    fun incomeStatementSubtotalsAndDeferredRevenue() {
        val movements = mapOf("sal" to 40_000L, "rent" to 10_000L, "food" to 5_000L, "tax" to 12_000L)
        val payslip = tx(
            "pay",
            Triple(checking, 25_000L, EntryType.Debit),
            Triple(pension, 3_000L, EntryType.Debit),
            Triple(tax, 12_000L, EntryType.Debit),
            Triple(salary, 40_000L, EntryType.Credit),
        )
        val report = IncomeStatementCalculator.compute(
            accounts, movements, listOf(payslip),
            IncomeStatementSettings(
                expenseGroups = listOf(AccountGroup("Living", listOf("5", "6"))),
                taxPrefixes = listOf("891"),
                deferredAssetPrefixes = listOf("194"),
            ),
        )
        assertEquals(40_000L, report.totalRevenue)
        assertEquals(27_000L, report.totalExpenses)
        assertEquals(13_000L, report.netIncome)
        assertEquals(28_000L, report.revenueNetOfIncomeTax)
        assertEquals(25_000L, report.ebt)
        assertEquals(3_000L, report.deferredFromRevenue)
        assertEquals(10_000L, report.spendableNetIncome)
        assertEquals(listOf("Living"), report.expenses.groups.map { it.title })
        assertEquals(listOf(8910), report.expenses.ungrouped.map { it.number })
    }

    @Test
    fun cashFlowNetsTransfersInsideAGroup() {
        val groups = listOf(AccountGroup("Bank", listOf("1910", "1920")), AccountGroup("Cash", listOf("1010")))
        val transactions = listOf(
            tx("salary", Triple(checking, 30_000L, EntryType.Debit), Triple(salary, 30_000L, EntryType.Credit)),
            tx("save", Triple(savings, 5_000L, EntryType.Debit), Triple(checking, 5_000L, EntryType.Credit)),
            tx("atm", Triple(cash, 1_000L, EntryType.Debit), Triple(checking, 1_000L, EntryType.Credit)),
            tx("rent", Triple(rent, 10_000L, EntryType.Debit), Triple(checking, 10_000L, EntryType.Credit)),
        )
        val report = CashFlowCalculator.compute(
            accounts,
            beginningBalances = mapOf("chk" to 2_000L, "sav" to 1_000L, "cash" to 0L),
            endingBalances = mapOf("chk" to 16_000L, "sav" to 6_000L, "cash" to 1_000L),
            transactions = transactions,
            groups = groups,
        )
        val bank = report.groups.first { it.name == "Bank" }
        assertEquals(30_000L, bank.inflow) // the savings transfer cancels out
        assertEquals(11_000L, bank.outflow) // ATM withdrawal + rent
        assertEquals(19_000L, bank.netFlow)
        assertEquals(1_000L, report.groups.first { it.name == "Cash" }.inflow)
        assertEquals(3_000L, report.beginningCash)
        assertEquals(23_000L, report.endingCash)
    }

    @Test
    fun costTrendsGroupsThenOther() {
        val months = CostTrendCalculator.months(YearMonth.of(2026, 3), YearMonth.of(2026, 1))
        assertEquals(listOf(YearMonth.of(2026, 1), YearMonth.of(2026, 2), YearMonth.of(2026, 3)), months)
        val result = CostTrendCalculator.compute(accounts, months, trendMovements, listOf(AccountGroup("Home", listOf("5"))))
        assertEquals(listOf("group:0", CostTrendCalculator.OTHER_KEY), result.series.map { it.key })
        assertEquals(listOf(10_000L, 10_000L, 0L), result.series[0].monthly)
        assertEquals("Other expenses", result.series[1].name)
        assertEquals(listOf(0L, 1_000L, 3_000L), result.series[1].monthly)
        assertEquals(listOf("acct:5010"), result.series[0].accounts.map { it.key })
        assertEquals(listOf("acct:6010", "acct:8910"), result.series[1].accounts.map { it.key })
        assertEquals(1_000L, result.series[1].accounts[1].total)
    }

    @Test
    fun costTrendsRawAccounts() {
        val months = CostTrendCalculator.months(YearMonth.of(2026, 1), YearMonth.of(2026, 3))
        val result = CostTrendCalculator.compute(accounts, months, trendMovements, listOf(AccountGroup("Home", listOf("5"))), useGroups = false)
        assertEquals(listOf("acct:5010", "acct:6010", "acct:8910"), result.series.map { it.key })
        assertEquals("6010 · Groceries", result.series[1].name)
        assertEquals(3_000L, result.series[1].latest)
    }

    private val trendMovements = mapOf(
        "rent" to mapOf(YearMonth.of(2026, 1) to 10_000L, YearMonth.of(2026, 2) to 10_000L),
        "food" to mapOf(YearMonth.of(2026, 3) to 3_000L),
        "tax" to mapOf(YearMonth.of(2026, 2) to 1_000L),
    )
}
