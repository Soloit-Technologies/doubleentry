package com.soloit.doubleentry.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.label
import com.soloit.doubleentry.domain.monthAbbreviation
import com.soloit.doubleentry.domain.reports.NetWorthTrend
import com.soloit.doubleentry.domain.shortLabel
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.AmountTone
import com.soloit.doubleentry.ui.components.CardDivider
import com.soloit.doubleentry.ui.components.EmptyState
import com.soloit.doubleentry.ui.components.FigureRow
import com.soloit.doubleentry.ui.components.LoadingState
import com.soloit.doubleentry.ui.components.MoneyText
import com.soloit.doubleentry.ui.components.MonthPickerDialog
import com.soloit.doubleentry.ui.components.MonthSelector
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.components.SectionHeader
import com.soloit.doubleentry.ui.components.StatTile
import com.soloit.doubleentry.ui.components.TextAndFigures
import com.soloit.doubleentry.ui.components.TrendLineChart
import com.soloit.doubleentry.ui.theme.tabular
import java.time.YearMonth

/**
 * Net worth month by month. [openAt], when set, moves the report to that end month (e.g. coming
 * from the Accounts page) and is then handed back through [onOpened].
 */
@Composable
fun NetWorthReportPage(openAt: YearMonth?, onOpened: () -> Unit) {
    val vm = appViewModel { NetWorthViewModel(it.ledger) }
    val state by vm.state.collectAsStateWithLifecycle()
    var pickingStart by rememberSaveable { mutableStateOf(false) }
    var pickingEnd by rememberSaveable { mutableStateOf(false) }
    if (pickingStart) {
        MonthPickerDialog(state.customStart, onDismiss = { pickingStart = false }, onConfirm = { vm.customStart.value = it; pickingStart = false }, title = "From month")
    }
    if (pickingEnd) {
        MonthPickerDialog(state.end, onDismiss = { pickingEnd = false }, onConfirm = { vm.end.value = it; pickingEnd = false }, title = "To month")
    }
    LaunchedEffect(openAt) {
        if (openAt != null) {
            vm.end.value = openAt
            onOpened()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Chips rather than segments, so the four ranges wrap instead of squeezing at large font sizes.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NetWorthRange.entries.forEach { range ->
                        FilterChip(selected = state.range == range, onClick = { vm.range.value = range }, label = { Text(range.label) })
                    }
                }
                if (state.range == NetWorthRange.Custom) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickingStart = true }, modifier = Modifier.weight(1f)) { Text("From ${state.customStart.shortLabel()}") }
                        OutlinedButton(onClick = { pickingEnd = true }, modifier = Modifier.weight(1f)) { Text("To ${state.end.shortLabel()}") }
                    }
                } else {
                    MonthSelector(state.end, { vm.end.value = it })
                }
            }
        }
        val trend = state.trend
        if (trend == null) {
            item { LoadingState() }
            return@LazyColumn
        }
        if (trend.points.all { it.assets == 0L && it.liabilities == 0L }) {
            item {
                EmptyState(
                    Icons.AutoMirrored.Rounded.ShowChart,
                    "No net worth yet",
                    "Net worth shows up here once you record transactions on asset or liability accounts.",
                )
            }
            return@LazyColumn
        }
        val latest = trend.points.last()
        item {
            SectionCard(contentPadding = PaddingValues(20.dp), containerColor = MaterialTheme.colorScheme.primaryContainer) {
                val on = MaterialTheme.colorScheme.onPrimaryContainer
                Text("Net worth at end of ${state.end.label()}", style = MaterialTheme.typography.labelLarge, color = on.copy(alpha = 0.8f))
                MoneyText(latest.netWorth, style = MaterialTheme.typography.displaySmall, color = on, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MoneyText(trend.change, style = MaterialTheme.typography.bodyMedium, color = on, showSign = true, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Text("over ${trend.points.size} months", style = MaterialTheme.typography.bodyMedium, color = on, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
        item {
            FigureRow(spacing = 8.dp) { item ->
                StatTile("Assets", latest.assets, item, Icons.Rounded.Savings)
                StatTile("Liabilities", latest.liabilities, item, Icons.Rounded.AccountBalance, MaterialTheme.colorScheme.tertiary)
            }
        }
        item(key = "chart") { NetWorthChartCard(trend) }
        item { SectionHeader("By month") }
        item {
            SectionCard {
                // Newest first: the month you are looking at is the one you most likely care about.
                val indices = trend.points.indices.reversed()
                indices.forEach { i ->
                    val point = trend.points[i]
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextAndFigures(
                            figures = {
                                MoneyText(point.netWorth, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                MoneyText(trend.changeAt(i), style = MaterialTheme.typography.bodySmall, tone = AmountTone.Signed, showSign = true)
                            },
                        ) { Text(point.month.label(), style = MaterialTheme.typography.bodyMedium) }
                    }
                    if (i != indices.last) CardDivider()
                }
            }
        }
    }
}

/** The trend chart; tapping a month shows its net worth, change and split. */
@Composable
private fun NetWorthChartCard(trend: NetWorthTrend) {
    val points = trend.points
    var selected by remember(trend) { mutableIntStateOf(points.lastIndex) }
    val index = selected.coerceIn(0, points.lastIndex)
    val point = points[index]
    // Month names alone repeat once the range spans more than a year.
    val labels = remember(trend) { points.map { if (points.size > 12) it.month.shortLabel() else it.month.monthAbbreviation() } }

    SectionCard(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            TextAndFigures(
                figures = {
                    MoneyText(point.netWorth, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    MoneyText(trend.changeAt(index), style = MaterialTheme.typography.bodySmall, tone = AmountTone.Signed, showSign = true)
                },
            ) { Text(point.month.label(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
        }
        Spacer(Modifier.height(8.dp))
        TrendLineChart(
            values = points.map { it.netWorth },
            labels = labels,
            color = MaterialTheme.colorScheme.primary,
            selectedIndex = index,
            onSelect = { selected = it },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Assets ${Money.format(point.assets)} · Liabilities ${Money.format(point.liabilities)}",
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
