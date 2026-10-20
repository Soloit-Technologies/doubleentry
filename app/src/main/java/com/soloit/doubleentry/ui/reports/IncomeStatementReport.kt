package com.soloit.doubleentry.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.reports.BreakdownLine
import com.soloit.doubleentry.domain.reports.IncomeStatementReport
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.AmountRow
import com.soloit.doubleentry.ui.components.AmountTone
import com.soloit.doubleentry.ui.components.CardDivider
import com.soloit.doubleentry.ui.components.LoadingState
import com.soloit.doubleentry.ui.components.MoneyText
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.components.SectionHeader
import com.soloit.doubleentry.ui.components.StatTile
import com.soloit.doubleentry.ui.components.changeLabel
import com.soloit.doubleentry.ui.theme.AppTheme
import kotlin.math.roundToInt

@Composable
fun IncomeStatementReportPage(onOpenSettings: () -> Unit) {
    val vm = appViewModel { IncomeStatementViewModel(it.ledger, it.settings) }
    val state by vm.state.collectAsStateWithLifecycle()
    val finance = AppTheme.finance

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PeriodControls(state.period, { vm.period.value = it }, onOpenSettings, state.compare, { vm.compare.value = it }) }
        val report = state.report
        if (report == null) {
            item { LoadingState() }
            return@LazyColumn
        }
        val previous = state.previous
        item { NetIncomeHero(report, state.period.label, previous, state.previousLabel) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Revenue", report.totalRevenue, Modifier.weight(1f), Icons.AutoMirrored.Rounded.TrendingUp, finance.revenue)
                StatTile("Expenses", report.totalExpenses, Modifier.weight(1f), Icons.AutoMirrored.Rounded.TrendingDown, finance.expense)
            }
        }
        val comparisonNote = when {
            previous != null && state.previousLabel != null -> "Changes are against ${state.previousLabel}."
            state.compare == CompareMode.TwelveMonthAverage -> "No earlier months with income or expenses to average yet."
            else -> null
        }
        if (comparisonNote != null) {
            item {
                Text(
                    comparisonNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        item { SectionHeader("Revenue") }
        item { ReportSectionCard(report.revenue, "Total revenue", "Other revenue", accent = finance.revenue, previous = previous?.revenue) }
        item { SectionHeader("Expenses") }
        item { ReportSectionCard(report.expenses, "Total expenses", "Other expenses", accent = finance.expense, previous = previous?.expenses) }
        item { SectionHeader("From revenue to net income") }
        item { BreakdownCard(report.breakdown, previous?.breakdown) }
    }
}

/**
 * [lines] one step per row, each marked with how it combines with the row above, so every subtotal
 * shows where it comes from. Steps with accounts behind them expand to list those accounts.
 */
@Composable
private fun BreakdownCard(lines: List<BreakdownLine>, previous: List<BreakdownLine>?) {
    val previousByLabel = remember(previous) { previous?.associateBy { it.label } }
    val previousAccounts = remember(previous) { previous?.flatMap { it.accounts }?.associate { it.accountId to it.amount } }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    SectionCard {
        lines.forEach { line ->
            if (line.kind == BreakdownLine.Kind.Result) CardDivider()
            val canExpand = line.accounts.isNotEmpty()
            val isExpanded = expanded[line.label] == true
            val emphasized = line.kind == BreakdownLine.Kind.Result
            AmountRow(
                label = line.label,
                cents = line.amount,
                supporting = line.note ?: if (canExpand) "${line.accounts.size} account${if (line.accounts.size == 1) "" else "s"}" else null,
                emphasized = emphasized,
                tone = if (line.kind == BreakdownLine.Kind.Less) AmountTone.Neutral else AmountTone.Signed,
                leading = {
                    Text(
                        when (line.kind) {
                            BreakdownLine.Kind.Start -> ""
                            BreakdownLine.Kind.Less -> "−"
                            BreakdownLine.Kind.Subtotal, BreakdownLine.Kind.Result -> "="
                        },
                        style = if (emphasized) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(12.dp),
                    )
                },
                onClick = if (canExpand) ({ expanded[line.label] = !isExpanded }) else null,
                previous = previousByLabel?.let { it[line.label]?.amount ?: 0L },
            )
            if (canExpand && isExpanded) {
                Column(Modifier.padding(start = 24.dp)) {
                    line.accounts.forEach { row -> ReportRowItem(row, null, previousAccounts?.let { it[row.accountId] ?: 0L }) }
                }
            }
        }
    }
}

@Composable
private fun NetIncomeHero(report: IncomeStatementReport, periodLabel: String, previous: IncomeStatementReport?, previousLabel: String?) {
    val finance = AppTheme.finance
    val positive = report.netIncome >= 0
    SectionCard(
        containerColor = if (positive) finance.positiveContainer else finance.negativeContainer,
        contentPadding = PaddingValues(20.dp),
    ) {
        val onColor = if (positive) finance.onPositiveContainer else finance.onNegativeContainer
        Text("Net income · $periodLabel", style = MaterialTheme.typography.labelLarge, color = onColor.copy(alpha = 0.8f))
        MoneyText(report.netIncome, style = MaterialTheme.typography.displaySmall, color = onColor, fontWeight = FontWeight.SemiBold, showSign = true)
        if (previous != null && previousLabel != null) {
            Text(
                "${changeLabel(report.netIncome, previous.netIncome)} vs $previousLabel (${Money.formatSigned(previous.netIncome)})",
                style = MaterialTheme.typography.bodyMedium,
                color = onColor,
            )
        }
        report.savingsRate?.let { rate ->
            Spacer(Modifier.height(12.dp))
            val kept = rate.coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(10.dp).clip(MaterialTheme.shapes.small).background(onColor.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxWidth(kept).fillMaxHeight().clip(MaterialTheme.shapes.small).background(onColor.copy(alpha = 0.7f)))
            }
            Spacer(Modifier.height(6.dp))
            Column {
                Text(
                    "You kept ${(rate * 100).roundToInt()}% of your revenue",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onColor,
                )
            }
        }
    }
}
