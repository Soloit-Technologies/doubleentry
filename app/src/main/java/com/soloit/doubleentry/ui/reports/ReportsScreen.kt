package com.soloit.doubleentry.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.soloit.doubleentry.domain.firstDay
import com.soloit.doubleentry.domain.lastDay
import com.soloit.doubleentry.domain.label
import com.soloit.doubleentry.domain.reports.ReportRow
import com.soloit.doubleentry.domain.reports.ReportSection
import com.soloit.doubleentry.ui.components.AmountRow
import com.soloit.doubleentry.ui.components.CardDivider
import com.soloit.doubleentry.ui.components.ExpandableGroup
import com.soloit.doubleentry.ui.components.MonthSelector
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.components.SegmentedChoice
import com.soloit.doubleentry.ui.components.YearSelector
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

private val reportTabs = listOf("Balance sheet", "Income", "Cash flow", "Cost trends")

@Composable
fun ReportsScreen(
    onOpenSettings: () -> Unit,
    onOpenBalanceSheetSettings: () -> Unit,
    onOpenIncomeSettings: () -> Unit,
    onOpenCashflowSettings: () -> Unit,
) {
    val pager = rememberPagerState { reportTabs.size }
    val scope = rememberCoroutineScope()
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Reports") },
                    actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "Settings") } },
                )
                PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 16.dp, divider = {}) {
                    reportTabs.forEachIndexed { index, title ->
                        Tab(
                            selected = pager.currentPage == index,
                            onClick = { scope.launch { pager.animateScrollToPage(index) } },
                            text = { Text(title) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().padding(padding), key = { it }) { page ->
            when (page) {
                0 -> BalanceSheetReportPage(onOpenBalanceSheetSettings)
                1 -> IncomeStatementReportPage(onOpenIncomeSettings)
                2 -> CashFlowReportPage(onOpenCashflowSettings)
                else -> CostTrendsReportPage(onOpenIncomeSettings)
            }
        }
    }
}

enum class PeriodMode { Month, Year }

/** A month or a whole calendar year. */
data class ReportPeriod(val mode: PeriodMode = PeriodMode.Month, val month: YearMonth = YearMonth.now(), val year: Int = LocalDate.now().year) {
    val from: LocalDate get() = if (mode == PeriodMode.Month) month.firstDay() else LocalDate.of(year, 1, 1)
    val to: LocalDate get() = if (mode == PeriodMode.Month) month.lastDay() else LocalDate.of(year, 12, 31)
    val label: String get() = if (mode == PeriodMode.Month) month.label() else year.toString()
}

/** Month/Year toggle plus the matching period stepper, with a shortcut to the report's settings. */
@Composable
fun PeriodControls(period: ReportPeriod, onChange: (ReportPeriod) -> Unit, onOpenSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SegmentedChoice(
                options = PeriodMode.entries,
                selected = period.mode,
                onSelect = { onChange(period.copy(mode = it, year = if (it == PeriodMode.Year) period.month.year else period.year)) },
                label = { it.name },
                modifier = Modifier.weight(1f),
            )
            SettingsShortcut(onOpenSettings)
        }
        if (period.mode == PeriodMode.Month) {
            MonthSelector(period.month, { onChange(period.copy(month = it)) })
        } else {
            YearSelector(period.year, { onChange(period.copy(year = it)) })
        }
    }
}

@Composable
fun SettingsShortcut(onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.padding(start = 8.dp)) { Icon(Icons.Rounded.Tune, "Report settings") }
}

/**
 * Renders a report section: configured groups as collapsible blocks, ungrouped rows below them,
 * and a total. Zero rows are hidden unless [showZero].
 */
@Composable
fun ReportSectionCard(
    section: ReportSection,
    totalLabel: String,
    otherLabel: String,
    showZero: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary,
    onRowClick: ((ReportRow) -> Unit)? = null,
) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    fun visible(rows: List<ReportRow>) = if (showZero) rows else rows.filter { it.amount != 0L }

    SectionCard {
        section.groups.forEach { group ->
            val rows = visible(group.rows)
            if (rows.isEmpty() && !showZero) return@forEach
            ExpandableGroup(
                title = group.title,
                total = group.total,
                expanded = expanded[group.title] == true,
                onToggle = { expanded[group.title] = expanded[group.title] != true },
                supporting = "${group.rows.size} account${if (group.rows.size == 1) "" else "s"}",
            ) {
                rows.forEach { row -> ReportRowItem(row, onRowClick) }
            }
            CardDivider()
        }
        val ungrouped = visible(section.ungrouped)
        if (ungrouped.isNotEmpty()) {
            if (section.hasGroups) {
                Text(
                    otherLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            ungrouped.forEach { row -> ReportRowItem(row, onRowClick) }
            CardDivider()
        }
        if (section.isEmpty) {
            Text(
                "Nothing to show for this period.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            CardDivider()
        }
        AmountRow(totalLabel, section.total, emphasized = true, leading = { AccentDot(accent) })
    }
}

@Composable
fun AccentDot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
fun ReportRowItem(row: ReportRow, onClick: ((ReportRow) -> Unit)?) {
    AmountRow(
        label = row.name,
        cents = row.amount,
        supporting = row.number?.let { "#$it" },
        onClick = if (onClick != null && row.accountId != null) ({ onClick(row) }) else null,
    )
}
