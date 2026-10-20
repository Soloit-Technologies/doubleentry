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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.domain.Money
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
        if (previous != null && state.previousLabel != null) {
            item {
                Text(
                    "Changes are against ${state.previousLabel}.",
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
        item { SectionHeader("Key figures") }
        item {
            SectionCard {
                if (report.hasTaxAccounts) AmountRow("Revenue net of income tax", report.revenueNetOfIncomeTax)
                if (report.hasDepreciationAccounts) {
                    AmountRow("Expenses before depreciation", report.expensesBeforeDepreciation)
                    AmountRow("EBDA", report.ebda, supporting = "Earnings before depreciation & amortization", tone = AmountTone.Signed)
                }
                if (report.showEbitda) {
                    AmountRow("Cash operating expenses", report.cashOperatingExpenses, supporting = "Excluding depreciation and taxes")
                    AmountRow("EBITDA", report.ebitda, supporting = "Earnings before interest, taxes, D&A", tone = AmountTone.Signed)
                }
                if (report.hasTaxAccounts) AmountRow("EBT", report.ebt, supporting = "Earnings before tax", tone = AmountTone.Signed)
                if (report.hasTaxAccounts || report.showEbitda) CardDivider()
                AmountRow("Net income", report.netIncome, emphasized = true, tone = AmountTone.Signed, previous = previous?.netIncome)
                if (report.hasDeferredAccounts) {
                    AmountRow("Less: routed to deferred assets", -report.deferredFromRevenue, supporting = "e.g. salary paid straight into pension savings")
                    AmountRow("Spendable net income", report.spendableNetIncome, emphasized = true, tone = AmountTone.Signed, previous = previous?.spendableNetIncome)
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
