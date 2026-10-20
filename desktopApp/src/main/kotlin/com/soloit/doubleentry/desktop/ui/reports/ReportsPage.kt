package com.soloit.doubleentry.desktop.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.soloit.doubleentry.desktop.ui.LocalApp
import com.soloit.doubleentry.desktop.ui.components.Card
import com.soloit.doubleentry.desktop.ui.components.DateField
import com.soloit.doubleentry.desktop.ui.components.HorizontalDivider
import com.soloit.doubleentry.desktop.ui.components.Layer
import com.soloit.doubleentry.desktop.ui.components.MoneyText
import com.soloit.doubleentry.desktop.ui.components.Segmented
import com.soloit.doubleentry.desktop.ui.components.rememberViewModel
import com.soloit.doubleentry.desktop.ui.components.tabular
import com.soloit.doubleentry.domain.reports.BreakdownLine
import com.soloit.doubleentry.domain.reports.ReportRow
import com.soloit.doubleentry.domain.reports.ReportSection
import com.soloit.doubleentry.ui.components.changeLabel
import com.soloit.doubleentry.ui.reports.BalanceSheetViewModel
import com.soloit.doubleentry.ui.reports.CashFlowViewModel
import com.soloit.doubleentry.ui.reports.CompareMode
import com.soloit.doubleentry.ui.reports.IncomeStatementViewModel
import com.soloit.doubleentry.ui.reports.PeriodMode
import com.soloit.doubleentry.ui.reports.ReportPeriod
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.ComboBox
import io.github.composefluent.component.Icon
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.SelectorBarItem
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronLeft
import io.github.composefluent.icons.regular.ChevronRight

private val reports = listOf("Balance sheet", "Income statement", "Cash flow")

@Composable
fun ReportsPage() {
    var tab by remember { mutableIntStateOf(0) }
    Layer(Modifier.fillMaxSize().padding(end = 8.dp, bottom = 8.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                reports.forEachIndexed { index, label ->
                    SelectorBarItem(selected = tab == index, onSelectedChange = { tab = index }, text = { Text(label) })
                }
            }
            when (tab) {
                0 -> BalanceSheet()
                1 -> IncomeStatement()
                else -> CashFlow()
            }
        }
    }
}

/** A report laid out like a printed statement: centered, at most a page wide. */
@Composable
private fun ReportDocument(controls: @Composable RowScope.() -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 900.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { controls() }
            content()
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** One line of a report: a label and one figure per column. */
@Composable
private fun Line(label: String, amounts: List<Long?>, number: Int? = null, strong: Boolean = false, indent: Boolean = false) {
    val style: TextStyle = if (strong) FluentTheme.typography.bodyStrong else FluentTheme.typography.body
    Row(Modifier.fillMaxWidth().padding(start = if (indent) 28.dp else 12.dp, end = 12.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (number != null) Text(number.toString(), style = style.tabular(), color = FluentTheme.colors.text.text.secondary, modifier = Modifier.width(56.dp))
        Text(label, style = style, modifier = Modifier.weight(1f), maxLines = 1)
        amounts.forEach { amount ->
            Box(Modifier.width(140.dp), contentAlignment = Alignment.CenterEnd) { if (amount != null) MoneyText(amount, style = style) }
        }
    }
}

@Composable
private fun ColumnHeads(heads: List<String>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Spacer(Modifier.weight(1f))
        heads.forEach { Text(it, modifier = Modifier.width(140.dp), style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.secondary, textAlign = androidx.compose.ui.text.style.TextAlign.End) }
    }
}

/** Rows of [section], grouped as the report settings say, in a card with a total. */
@Composable
private fun SectionCard(title: String, section: ReportSection, total: String, compare: ReportSection? = null) {
    val previous = compare?.let { c -> (c.groups.flatMap { it.rows } + c.ungrouped).associateBy { it.accountId ?: it.name } }
    fun figures(row: ReportRow): List<Long?> = if (previous == null) listOf(row.amount) else listOf(row.amount, previous[row.accountId ?: row.name]?.amount ?: 0)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Line(title, emptyList(), strong = true)
            section.groups.forEach { group ->
                Line(group.title, emptyList(), strong = false)
                group.rows.forEach { Line(it.name, figures(it), number = it.number, indent = true) }
                Line("Total ${group.title.lowercase()}", if (previous == null) listOf(group.total) else listOf(group.total, compare.groups.firstOrNull { it.title == group.title }?.total ?: 0), strong = true, indent = true)
            }
            section.ungrouped.forEach { Line(it.name, figures(it), number = it.number) }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Line(total, if (compare == null) listOf(section.total) else listOf(section.total, compare.total), strong = true)
        }
    }
}

@Composable
private fun BalanceSheet() {
    val core = LocalApp.current.core
    val vm = rememberViewModel { BalanceSheetViewModel(core.ledger, core.settings) }
    val state by vm.state.collectAsState()
    ReportDocument(controls = {
        DateField(state.asOf, { vm.asOf.value = it }, Modifier.width(170.dp), header = "As of")
    }) {
        val report = state.report ?: return@ReportDocument ProgressRing()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Figure("Assets", report.totalAssets, Modifier.weight(1f))
            Figure("Liabilities", report.totalLiabilities, Modifier.weight(1f))
            Figure("Equity", report.totalEquity, Modifier.weight(1f))
        }
        if (!report.isBalanced) {
            Text("Assets and liabilities plus equity differ by ${com.soloit.doubleentry.domain.Money.format(report.totalAssets - report.totalLiabilitiesAndEquity)}.", color = FluentTheme.colors.system.caution)
        }
        SectionCard("Assets", report.assets, "Total assets")
        SectionCard("Liabilities", report.liabilities, "Total liabilities")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Line("Equity", emptyList(), strong = true)
                report.equity.forEach { Line(it.name, listOf(it.amount), number = it.number) }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Line("Total equity", listOf(report.totalEquity), strong = true)
                Line("Total liabilities and equity", listOf(report.totalLiabilitiesAndEquity), strong = true)
            }
        }
    }
}

@Composable
private fun Figure(label: String, cents: Long, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.secondary)
            MoneyText(cents, style = FluentTheme.typography.subtitle)
        }
    }
}

/** Month, year to date or year, with the stepper for the period. */
@Composable
private fun RowScope.PeriodControls(period: ReportPeriod, onChange: (ReportPeriod) -> Unit) {
    Segmented(PeriodMode.entries.map { it.label }, period.mode.ordinal, { onChange(period.withMode(PeriodMode.entries[it])) })
    SubtleButton(onClick = { onChange(period.previous()) }, iconOnly = true) { Icon(Icons.Regular.ChevronLeft, "Previous period") }
    Text(period.label, style = FluentTheme.typography.bodyStrong, modifier = Modifier.widthIn(min = 120.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    SubtleButton(onClick = { onChange(next(period)) }, iconOnly = true) { Icon(Icons.Regular.ChevronRight, "Next period") }
}

private fun next(period: ReportPeriod): ReportPeriod = when (period.mode) {
    PeriodMode.Month -> period.copy(month = period.month.plusMonths(1))
    PeriodMode.YearToDate -> period.copy(month = period.month.plusYears(1))
    PeriodMode.Year -> period.copy(year = period.year + 1)
}

@Composable
private fun IncomeStatement() {
    val core = LocalApp.current.core
    val vm = rememberViewModel { IncomeStatementViewModel(core.ledger, core.settings) }
    val state by vm.state.collectAsState()
    ReportDocument(controls = {
        PeriodControls(state.period) { vm.period.value = it }
        Spacer(Modifier.weight(1f))
        val modes = CompareMode.availableFor(state.period.mode)
        ComboBox(
            items = modes.map { it.label },
            selected = modes.indexOf(state.compare),
            onSelectionChange = { index, _ -> vm.compare.value = modes[index] },
            modifier = Modifier.width(240.dp),
        )
    }) {
        val report = state.report ?: return@ReportDocument ProgressRing()
        val previous = state.previous
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Figure("Income", report.totalRevenue, Modifier.weight(1f))
            Figure("Expenses", report.totalExpenses, Modifier.weight(1f))
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Net income", style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.secondary)
                    MoneyText(report.netIncome, style = FluentTheme.typography.subtitle, signed = true)
                    if (previous != null) Text("${changeLabel(report.netIncome, previous.netIncome)} vs ${state.previousLabel}", style = FluentTheme.typography.caption)
                }
            }
        }
        if (previous != null) ColumnHeads(listOf(state.period.label, state.previousLabel.orEmpty()))
        SectionCard("Income", report.revenue, "Total income", previous?.revenue)
        SectionCard("Expenses", report.expenses, "Total expenses", previous?.expenses)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                val previousLines = previous?.breakdown?.associateBy { it.label }
                report.breakdown.forEach { line ->
                    val sign = when (line.kind) {
                        BreakdownLine.Kind.Start -> ""
                        BreakdownLine.Kind.Less -> "− "
                        BreakdownLine.Kind.Subtotal, BreakdownLine.Kind.Result -> "= "
                    }
                    val label = sign + line.label + (line.note?.let { " ($it)" }.takeIf { line.kind == BreakdownLine.Kind.Subtotal }.orEmpty())
                    val amounts = if (previousLines == null) listOf(line.amount) else listOf(line.amount, previousLines[line.label]?.amount ?: 0L)
                    Line(label, amounts, strong = line.kind == BreakdownLine.Kind.Result)
                }
                report.savingsRate?.let { Line("Saved of income: ${(it * 100).toInt()} %", emptyList()) }
            }
        }
    }
}

@Composable
private fun CashFlow() {
    val core = LocalApp.current.core
    val vm = rememberViewModel { CashFlowViewModel(core.ledger, core.settings) }
    val state by vm.state.collectAsState()
    ReportDocument(controls = { PeriodControls(state.period) { vm.period.value = it } }) {
        val report = state.report ?: return@ReportDocument ProgressRing()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Figure("Money in", report.totalInflows, Modifier.weight(1f))
            Figure("Money out", report.totalOutflows, Modifier.weight(1f))
            Figure("Net", report.totalInflows - report.totalOutflows, Modifier.weight(1f))
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                ColumnHeadsWide(listOf("Beginning", "In", "Out", "Ending"))
                report.groups.forEach { group ->
                    Line(group.name, listOf(group.beginning, group.inflow, group.outflow, group.ending), strong = true)
                    if (group.accounts.size > 1 || group.accounts.firstOrNull()?.name != group.name) {
                        group.accounts.forEach { Line(it.name, listOf(it.beginning, it.inflow, it.outflow, it.ending), number = it.number, indent = true) }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Line("Cash", listOf(report.beginningCash, report.totalInflows, report.totalOutflows, report.beginningCash + report.totalInflows - report.totalOutflows), strong = true)
            }
        }
    }
}

@Composable
private fun ColumnHeadsWide(heads: List<String>) = ColumnHeads(heads)
