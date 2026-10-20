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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.monthAbbreviation
import com.soloit.doubleentry.domain.reports.CashFlowPoint
import com.soloit.doubleentry.domain.reports.CashFlowReport
import com.soloit.doubleentry.domain.shortLabel
import com.soloit.doubleentry.domain.yearMonth
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.AmountTone
import com.soloit.doubleentry.ui.components.CardDivider
import com.soloit.doubleentry.ui.components.EmptyState
import com.soloit.doubleentry.ui.components.ExpandableGroup
import com.soloit.doubleentry.ui.components.FlowBarChart
import com.soloit.doubleentry.ui.components.LoadingState
import com.soloit.doubleentry.ui.components.MoneyText
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.components.SectionHeader
import com.soloit.doubleentry.ui.components.StatTile
import com.soloit.doubleentry.ui.components.TrendLineChart
import com.soloit.doubleentry.ui.theme.AppTheme
import com.soloit.doubleentry.ui.theme.tabular
import java.time.format.DateTimeFormatter

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
        if (report.timeline.isNotEmpty()) {
            item(key = "timeline") { CashTimelineCard(report) }
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

@Composable
private fun CashTimelineCard(report: CashFlowReport) {
    val finance = AppTheme.finance
    val timeline = report.timeline
    var selected by remember(timeline) { mutableStateOf(timeline.lastIndex) }
    val point = timeline[selected.coerceIn(0, timeline.lastIndex)]
    val labels = remember(timeline) {
        if (report.dailyTimeline) timeline.map { it.start.dayOfMonth.toString() } else timeline.map { it.start.yearMonth.monthAbbreviation() }
    }

    SectionCard(contentPadding = PaddingValues(16.dp)) {
        Text(point.label(report.dailyTimeline), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TimelineFigure("Cash", point.balance, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            TimelineFigure("In", point.inflow, finance.positive, Modifier.weight(1f))
            TimelineFigure("Out", point.outflow, finance.negative, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        TrendLineChart(
            values = timeline.map { it.balance },
            labels = labels,
            color = MaterialTheme.colorScheme.primary,
            selectedIndex = selected,
            onSelect = { selected = it },
        )
        Spacer(Modifier.height(4.dp))
        FlowBarChart(
            inflows = timeline.map { it.inflow },
            outflows = timeline.map { it.outflow },
            labels = labels,
            inColor = finance.positive,
            outColor = finance.negative,
            selectedIndex = selected,
            onSelect = { selected = it },
        )
    }
}

private val dayLabel = DateTimeFormatter.ofPattern("EEE d MMM")

private fun CashFlowPoint.label(daily: Boolean): String =
    if (daily) start.format(dayLabel) else start.yearMonth.shortLabel()

@Composable
private fun TimelineFigure(label: String, cents: Long, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
        MoneyText(cents, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}
