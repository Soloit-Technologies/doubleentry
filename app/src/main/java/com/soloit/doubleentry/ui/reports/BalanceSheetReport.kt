package com.soloit.doubleentry.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.AmountRow
import com.soloit.doubleentry.ui.components.CardDivider
import com.soloit.doubleentry.ui.components.DatePickerModal
import com.soloit.doubleentry.ui.components.LoadingState
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.components.SectionHeader
import com.soloit.doubleentry.ui.components.StatTile
import com.soloit.doubleentry.ui.components.StatusChip
import com.soloit.doubleentry.ui.theme.AppTheme
import java.time.LocalDate

@Composable
fun BalanceSheetReportPage(onOpenSettings: () -> Unit) {
    val vm = appViewModel { BalanceSheetViewModel(it.ledger, it.settings) }
    val state by vm.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var showZero by remember { mutableStateOf(false) }
    val finance = AppTheme.finance
    val today = LocalDate.now()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = { picking = true }, label = { Text("As of ${state.asOf.formatMedium()}") }, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                SettingsShortcut(onOpenSettings)
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val presets = listOf(
                    "Today" to today,
                    "End of last month" to today.withDayOfMonth(1).minusDays(1),
                    "End of last year" to LocalDate.of(today.year - 1, 12, 31),
                )
                presets.forEach { (label, date) ->
                    item { FilterChip(selected = state.asOf == date, onClick = { vm.asOf.value = date }, label = { Text(label) }) }
                }
                item { FilterChip(selected = showZero, onClick = { showZero = !showZero }, label = { Text("Show empty accounts") }) }
            }
        }
        val report = state.report
        if (report == null) {
            item { LoadingState() }
            return@LazyColumn
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Assets", report.totalAssets, Modifier.weight(1f), Icons.Rounded.AccountBalanceWallet, finance.asset)
                StatTile("Liabilities", report.totalLiabilities, Modifier.weight(1f), Icons.Rounded.CreditCard, finance.liability)
                StatTile("Equity", report.totalEquity, Modifier.weight(1f), Icons.Rounded.PieChart, finance.equity)
            }
        }
        item {
            if (report.isBalanced) {
                StatusChip("Balanced: assets = liabilities + equity", finance.positiveContainer, finance.onPositiveContainer, icon = Icons.Rounded.CheckCircle)
            } else {
                StatusChip("Out of balance — check for incomplete transactions", finance.warningContainer, finance.onWarningContainer, icon = Icons.Rounded.ErrorOutline)
            }
        }
        item { SectionHeader("Assets") }
        item { ReportSectionCard(report.assets, "Total assets", "Other assets", showZero, finance.asset) }
        item { SectionHeader("Liabilities") }
        item { ReportSectionCard(report.liabilities, "Total liabilities", "Other liabilities", showZero, finance.liability) }
        item { SectionHeader("Equity") }
        item {
            SectionCard {
                report.equity.filter { showZero || it.amount != 0L }.forEach { row ->
                    AmountRow(row.name, row.amount, supporting = row.number?.let { "#$it" })
                }
                CardDivider()
                AmountRow("Total equity", report.totalEquity, emphasized = true, leading = { AccentDot(finance.equity) })
            }
        }
        item {
            SectionCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                AmountRow("Total liabilities + equity", report.totalLiabilitiesAndEquity, emphasized = true)
            }
        }
        item {
            Text(
                "Retained earnings are your accumulated income minus expenses, split into previous years and ${report.asOf.year}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )
        }
    }

    if (picking) {
        DatePickerModal(state.asOf, onDismiss = { picking = false }, onConfirm = { vm.asOf.value = it; picking = false })
    }
}
