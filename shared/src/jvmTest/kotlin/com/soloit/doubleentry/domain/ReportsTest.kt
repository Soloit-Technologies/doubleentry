package com.soloit.doubleentry.domain

import com.soloit.doubleentry.domain.reports.BalanceSheetCalculator
import com.soloit.doubleentry.domain.reports.CashFlowCalculator
import com.soloit.doubleentry.domain.reports.CostTrendCalculator
import com.soloit.doubleentry.domain.reports.IncomeStatementCalculator
import com.soloit.doubleentry.domain.reports.NetWorthCalculator
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
    fun incomeStatementListsLargestExpensesFirst() {
        val movements = mapOf("rent" to 8_000L, "food" to 9_000L, "tax" to 20_000L)
        val ungrouped = IncomeStatementCalculator.compute(accounts, movements, emptyList(), IncomeStatementSettings())
        assertEquals(listOf(8910, 6010, 5010), ungrouped.expenses.ungrouped.map { it.number })

        val grouped = IncomeStatementCalculator.compute(
            accounts, movements, emptyList(),
            IncomeStatementSettings(expenseGroups = listOf(AccountGroup("Living", listOf("5", "6")), AccountGroup("Tax", listOf("89")))),
        )
        assertEquals(listOf("Tax", "Living"), grouped.expenses.groups.map { it.title })
        assertEquals(listOf(6010, 5010), grouped.expenses.groups[1].rows.map { it.number })
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
    fun cashFlowTimelineRunsUpToTodayAndSwitchesToMonthsForLongPeriods() {
        val groups = listOf(AccountGroup("Bank", listOf("1910", "1920")), AccountGroup("Cash", listOf("1010")))
        val transactions = listOf(
            tx("salary", Triple(checking, 30_000L, EntryType.Debit), Triple(salary, 30_000L, EntryType.Credit)),
            tx("atm", Triple(cash, 1_000L, EntryType.Debit), Triple(checking, 1_000L, EntryType.Credit)),
            tx("rent", Triple(rent, 10_000L, EntryType.Debit), Triple(checking, 10_000L, EntryType.Credit)),
        )
        fun report(from: LocalDate, to: LocalDate, today: LocalDate) = CashFlowCalculator.compute(
            accounts, mapOf("chk" to 3_000L), emptyMap(), transactions, groups, from..to, today,
        )

        val march = report(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), today = LocalDate.of(2026, 3, 15))
        assertTrue(march.dailyTimeline)
        assertEquals(15, march.timeline.size)
        val day10 = march.timeline[9]
        assertEquals(LocalDate.of(2026, 3, 10), day10.start)
        assertEquals(31_000L, day10.inflow) // moving money between groups counts on both sides
        assertEquals(11_000L, day10.outflow)
        assertEquals(3_000L, march.timeline[8].balance)
        assertEquals(23_000L, march.timeline.last().balance)

        val year = report(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), today = LocalDate.of(2027, 1, 5))
        assertTrue(!year.dailyTimeline)
        assertEquals(12, year.timeline.size)
        assertEquals(LocalDate.of(2026, 3, 31), year.timeline[2].end)
        assertEquals(20_000L, year.timeline[2].netFlow)
        assertEquals(23_000L, year.timeline.last().balance)
    }

    @Test
    fun netWorthTrendAccumulatesAssetsMinusLiabilitiesMonthByMonth() {
        val months = CostTrendCalculator.months(YearMonth.of(2026, 1), YearMonth.of(2026, 3))
        val trend = NetWorthCalculator.compute(
            accounts,
            months,
            openingBalances = mapOf("chk" to 10_000L, "mtg" to 50_000L, "sal" to 99_000L),
            monthlyMovements = mapOf(
                "chk" to mapOf(YearMonth.of(2026, 1) to 3_000L, YearMonth.of(2026, 3) to -1_000L),
                "sav" to mapOf(YearMonth.of(2026, 2) to 2_000L),
                "mtg" to mapOf(YearMonth.of(2026, 2) to -500L),
                "rent" to mapOf(YearMonth.of(2026, 2) to 7_000L),
            ),
        )
        assertEquals(months, trend.points.map { it.month })
        assertEquals(listOf(13_000L, 15_000L, 14_000L), trend.points.map { it.assets })
        assertEquals(listOf(50_000L, 49_500L, 49_500L), trend.points.map { it.liabilities })
        assertEquals(listOf(-37_000L, -34_500L, -35_500L), trend.points.map { it.netWorth })
        assertEquals(-40_000L, trend.opening)
        assertEquals(4_500L, trend.change)
        assertEquals(3_000L, trend.changeAt(0))
        assertEquals(-1_000L, trend.changeAt(2))
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
        assertEquals(listOf(10_000L, 11_000L, 3_000L), result.total.monthly)
        assertEquals(null, result.totalExcludingTax)
    }

    @Test
    fun costTrendsTotalExcludingTax() {
        val months = CostTrendCalculator.months(YearMonth.of(2026, 1), YearMonth.of(2026, 3))
        val result = CostTrendCalculator.compute(accounts, months, trendMovements, emptyList(), useGroups = false, taxPrefixes = listOf("89"))
        assertEquals(listOf(10_000L, 11_000L, 3_000L), result.total.monthly)
        assertEquals(listOf(10_000L, 10_000L, 3_000L), result.totalExcludingTax?.monthly)
        assertEquals(listOf(CostTrendCalculator.TOTAL_KEY, CostTrendCalculator.TOTAL_EXCLUDING_TAX_KEY), result.overall.map { it.key })
        assertEquals(null, result.cashOperatingExpenses)
    }

    @Test
    fun costTrendsCashOperatingExpenses() {
        val months = CostTrendCalculator.months(YearMonth.of(2026, 1), YearMonth.of(2026, 3))
        val result = CostTrendCalculator.compute(
            accounts, months, trendMovements, emptyList(), useGroups = false, taxPrefixes = listOf("89"), depreciationPrefixes = listOf("50"),
        )
        assertEquals(listOf(0L, 0L, 3_000L), result.cashOperatingExpenses?.monthly)
        assertEquals(
            listOf(CostTrendCalculator.TOTAL_KEY, CostTrendCalculator.TOTAL_EXCLUDING_TAX_KEY, CostTrendCalculator.CASH_OPERATING_KEY),
            result.overall.map { it.key },
        )
    }

    @Test
    fun costTrendsRawAccounts() {
        val months = CostTrendCalculator.months(YearMonth.of(2026, 1), YearMonth.of(2026, 3))
        val result = CostTrendCalculator.compute(accounts, months, trendMovements, listOf(AccountGroup("Home", listOf("5"))), useGroups = false)
        assertEquals(listOf("acct:5010", "acct:6010", "acct:8910"), result.series.map { it.key })
        assertEquals("6010 · Groceries", result.series[1].name)
        assertEquals(3_000L, result.series[1].latest)
    }

    @Test
    fun defaultPlanReportGroupsCoverEveryAccount() {
        val groupsByType = mapOf(
            AccountType.Asset to DefaultAccountPlan.balanceSheetSettings.assetGroups,
            AccountType.Liability to DefaultAccountPlan.balanceSheetSettings.liabilityGroups,
            AccountType.Revenue to DefaultAccountPlan.incomeStatementSettings.revenueGroups,
            AccountType.Expense to DefaultAccountPlan.incomeStatementSettings.expenseGroups,
        )
        for (entry in DefaultAccountPlan.accounts) {
            val groups = groupsByType[entry.type] ?: continue
            assertTrue("${entry.number} ${entry.name} is in no group", groups.indexOfGroupFor(entry.number) >= 0)
        }
    }

    private val trendMovements = mapOf(
        "rent" to mapOf(YearMonth.of(2026, 1) to 10_000L, YearMonth.of(2026, 2) to 10_000L),
        "food" to mapOf(YearMonth.of(2026, 3) to 3_000L),
        "tax" to mapOf(YearMonth.of(2026, 2) to 1_000L),
    )
}
