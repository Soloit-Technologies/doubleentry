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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.domain.reports.IncomeStatementCalculator
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
import com.soloit.doubleentry.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlin.math.roundToInt

data class IncomeStatementUiState(val period: ReportPeriod = ReportPeriod(), val report: IncomeStatementReport? = null)

class IncomeStatementViewModel(ledger: LedgerRepository, settings: SettingsRepository) : ViewModel() {
    val period = MutableStateFlow(ReportPeriod())

    val state: StateFlow<IncomeStatementUiState> = combine(period, settings.incomeStatement, ledger.changes) { p, s, _ -> p to s }
        .mapLatest { (p, s) ->
            val needsTransactions = s.deferredAssetPrefixes.any { it.isNotBlank() }
            val report = IncomeStatementCalculator.compute(
                accounts = ledger.getAccounts(),
                movements = ledger.getMovements(p.from, p.to),
                transactions = if (needsTransactions) ledger.getTransactions(p.from, p.to) else emptyList(),
                settings = s,
            )
            IncomeStatementUiState(p, report)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IncomeStatementUiState())
}

@Composable
fun IncomeStatementReportPage(onOpenSettings: () -> Unit) {
    val vm = appViewModel { IncomeStatementViewModel(it.ledger, it.settings) }
    val state by vm.state.collectAsStateWithLifecycle()
    val finance = AppTheme.finance

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PeriodControls(state.period, { vm.period.value = it }, onOpenSettings) }
        val report = state.report
        if (report == null) {
            item { LoadingState() }
            return@LazyColumn
        }
        item { NetIncomeHero(report, state.period.label) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Revenue", report.totalRevenue, Modifier.weight(1f), Icons.AutoMirrored.Rounded.TrendingUp, finance.revenue)
                StatTile("Expenses", report.totalExpenses, Modifier.weight(1f), Icons.AutoMirrored.Rounded.TrendingDown, finance.expense)
            }
        }
        item { SectionHeader("Revenue") }
        item { ReportSectionCard(report.revenue, "Total revenue", "Other revenue", accent = finance.revenue) }
        item { SectionHeader("Expenses") }
        item { ReportSectionCard(report.expenses, "Total expenses", "Other expenses", accent = finance.expense) }
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
                AmountRow("Net income", report.netIncome, emphasized = true, tone = AmountTone.Signed)
                if (report.hasDeferredAccounts) {
                    AmountRow("Less: routed to deferred assets", -report.deferredFromRevenue, supporting = "e.g. salary paid straight into pension savings")
                    AmountRow("Spendable net income", report.spendableNetIncome, emphasized = true, tone = AmountTone.Signed)
                }
            }
        }
    }
}

@Composable
private fun NetIncomeHero(report: IncomeStatementReport, periodLabel: String) {
    val finance = AppTheme.finance
    val positive = report.netIncome >= 0
    SectionCard(
        containerColor = if (positive) finance.positiveContainer else finance.negativeContainer,
        contentPadding = PaddingValues(20.dp),
    ) {
        val onColor = if (positive) finance.onPositiveContainer else finance.onNegativeContainer
        Text("Net income · $periodLabel", style = MaterialTheme.typography.labelLarge, color = onColor.copy(alpha = 0.8f))
        MoneyText(report.netIncome, style = MaterialTheme.typography.displaySmall, color = onColor, fontWeight = FontWeight.SemiBold, showSign = true)
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
