package com.soloit.doubleentry.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.reports.CashFlowCalculator
import com.soloit.doubleentry.domain.reports.CashFlowReport
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.AmountTone
import com.soloit.doubleentry.ui.components.CardDivider
import com.soloit.doubleentry.ui.components.EmptyState
import com.soloit.doubleentry.ui.components.ExpandableGroup
import com.soloit.doubleentry.ui.components.LoadingState
import com.soloit.doubleentry.ui.components.MoneyText
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.components.SectionHeader
import com.soloit.doubleentry.ui.components.StatTile
import com.soloit.doubleentry.ui.theme.AppTheme
import com.soloit.doubleentry.ui.theme.tabular
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class CashFlowUiState(val period: ReportPeriod = ReportPeriod(), val report: CashFlowReport? = null)

class CashFlowViewModel(ledger: LedgerRepository, settings: SettingsRepository) : ViewModel() {
    val period = MutableStateFlow(ReportPeriod())

    val state: StateFlow<CashFlowUiState> = combine(period, settings.cashflow, ledger.changes) { p, s, _ -> p to s }
        .mapLatest { (p, s) ->
            val report = CashFlowCalculator.compute(
                accounts = ledger.getAccounts(),
                beginningBalances = ledger.getBalances(p.from.minusDays(1)),
                endingBalances = ledger.getBalances(p.to),
                transactions = ledger.getTransactions(p.from, p.to),
                groups = s.cashGroups,
            )
            CashFlowUiState(p, report)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CashFlowUiState())
}

@Composable
fun CashFlowReportPage(onOpenSettings: () -> Unit) {
    val vm = appViewModel { CashFlowViewModel(it.ledger, it.settings) }
    val state by vm.state.collectAsStateWithLifecycle()
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val finance = AppTheme.finance

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PeriodControls(state.period, { vm.period.value = it }, onOpenSettings) }
        val report = state.report
        if (report == null) {
            item { LoadingState() }
            return@LazyColumn
        }
        item {
            SectionCard(contentPadding = PaddingValues(20.dp), containerColor = MaterialTheme.colorScheme.primaryContainer) {
                val on = MaterialTheme.colorScheme.onPrimaryContainer
                Text("Cash at end of ${state.period.label}", style = MaterialTheme.typography.labelLarge, color = on.copy(alpha = 0.8f))
                MoneyText(report.endingCash, style = MaterialTheme.typography.displaySmall, color = on, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Money.format(report.beginningCash), style = MaterialTheme.typography.bodyMedium.tabular(), color = on)
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = on, modifier = Modifier.padding(horizontal = 8.dp))
                    MoneyText(report.netCashFlow, style = MaterialTheme.typography.bodyMedium, color = on, showSign = true, fontWeight = FontWeight.SemiBold)
                    Text(" net", style = MaterialTheme.typography.bodyMedium, color = on)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Inflows", report.totalInflows, Modifier.weight(1f), Icons.Rounded.SouthWest, finance.positive, AmountTone.Positive)
                StatTile("Outflows", report.totalOutflows, Modifier.weight(1f), Icons.Rounded.NorthEast, finance.negative, AmountTone.Negative)
            }
        }
        item { SectionHeader("Cash groups") }
        if (report.groups.isEmpty()) {
            item { EmptyState(Icons.Rounded.Savings, "No cash groups", "Define which accounts count as cash in the cash flow settings.") }
        } else {
            item {
                SectionCard {
                    report.groups.forEachIndexed { index, group ->
                        ExpandableGroup(
                            title = group.name,
                            total = group.ending,
                            expanded = expanded[group.name] == true,
                            onToggle = { expanded[group.name] = expanded[group.name] != true },
                            supporting = "In ${Money.format(group.inflow)} · Out ${Money.format(group.outflow)} · Net ${Money.formatSigned(group.netFlow)}",
                        ) {
                            if (group.accounts.isEmpty()) {
                                Text(
                                    "No accounts match this group's prefixes.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                            group.accounts.forEach { a ->
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${a.number} ${a.name}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                        MoneyText(a.ending, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Text(
                                        "Opening ${Money.format(a.beginning)} · In ${Money.format(a.inflow)} · Out ${Money.format(a.outflow)}",
                                        style = MaterialTheme.typography.bodySmall.tabular(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (index < report.groups.lastIndex) CardDivider()
                    }
                }
            }
        }
        item {
            Text(
                "Transfers between accounts in the same group cancel out; moving money between groups shows as an outflow from one and an inflow to the other.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
