package com.soloit.doubleentry.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.FilterChip
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
import com.soloit.doubleentry.domain.monthAbbreviation
import com.soloit.doubleentry.domain.shortLabel
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

enum class PeriodMode(val label: String) { Month("Month"), YearToDate("Year to date"), Year("Year") }

/** What a report is compared with. */
enum class CompareMode(val label: String) { None("No comparison"), PreviousPeriod("Previous period"), LastYear("Same period last year") }

/** A month, the year up to and including a month, or a whole calendar year. */
data class ReportPeriod(val mode: PeriodMode = PeriodMode.Month, val month: YearMonth = YearMonth.now(), val year: Int = LocalDate.now().year) {
    val from: LocalDate
        get() = when (mode) {
            PeriodMode.Month -> month.firstDay()
            PeriodMode.YearToDate -> LocalDate.of(month.year, 1, 1)
            PeriodMode.Year -> LocalDate.of(year, 1, 1)
        }
    val to: LocalDate
        get() = when (mode) {
            PeriodMode.Month, PeriodMode.YearToDate -> month.lastDay()
            PeriodMode.Year -> LocalDate.of(year, 12, 31)
        }
    val label: String
        get() = when (mode) {
            PeriodMode.Month -> month.label()
            PeriodMode.YearToDate -> if (month.monthValue == 1) month.label() else "${YearMonth.of(month.year, 1).monthAbbreviation()}–${month.shortLabel()}"
            PeriodMode.Year -> year.toString()
        }

    /** The period immediately before this one (for a year to date: the same months a year earlier). */
    fun previous(): ReportPeriod = when (mode) {
        PeriodMode.Month -> copy(month = month.minusMonths(1))
        PeriodMode.YearToDate -> copy(month = month.minusYears(1))
        PeriodMode.Year -> copy(year = year - 1)
    }

    fun lastYear(): ReportPeriod = when (mode) {
        PeriodMode.Month, PeriodMode.YearToDate -> copy(month = month.minusYears(1))
        PeriodMode.Year -> copy(year = year - 1)
    }

    fun comparedWith(compare: CompareMode): ReportPeriod? = when (compare) {
        CompareMode.None -> null
        CompareMode.PreviousPeriod -> previous()
        CompareMode.LastYear -> lastYear()
    }

    /** Switches mode while keeping roughly the same point in time. */
    fun withMode(next: PeriodMode): ReportPeriod = when (next) {
        PeriodMode.Year -> copy(mode = next, year = month.year)
        else -> if (mode == PeriodMode.Year) {
            val now = YearMonth.now()
            copy(mode = next, month = if (year == now.year) now else YearMonth.of(year, 12))
        } else {
            copy(mode = next)
        }
    }
}

/**
 * Period mode toggle plus the matching period stepper, with a shortcut to the report's settings.
 * When [onCompareChange] is given, a comparison selector is shown too.
 */
@Composable
fun PeriodControls(
    period: ReportPeriod,
    onChange: (ReportPeriod) -> Unit,
    onOpenSettings: () -> Unit,
    compare: CompareMode = CompareMode.None,
    onCompareChange: ((CompareMode) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SegmentedChoice(
                options = PeriodMode.entries,
                selected = period.mode,
                onSelect = { onChange(period.withMode(it)) },
                label = { if (it == PeriodMode.YearToDate) "YTD" else it.label },
                modifier = Modifier.weight(1f),
            )
            SettingsShortcut(onOpenSettings)
        }
        if (period.mode == PeriodMode.Year) {
            YearSelector(period.year, { onChange(period.copy(year = it)) })
        } else {
            MonthSelector(period.month, { onChange(period.copy(month = it)) })
        }
        if (onCompareChange != null) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompareMode.entries.forEach { mode ->
                    // In year mode "previous period" and "last year" are the same thing.
                    if (mode == CompareMode.LastYear && period.mode == PeriodMode.Year) return@forEach
                    FilterChip(
                        selected = compare == mode || (period.mode == PeriodMode.Year && mode == CompareMode.PreviousPeriod && compare == CompareMode.LastYear),
                        onClick = { onCompareChange(mode) },
                        label = { Text(if (mode == CompareMode.PreviousPeriod && period.mode == PeriodMode.Year) "Previous year" else mode.label) },
                    )
                }
            }
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
    /** The same section for a comparison period, matched by group title and account. */
    previous: ReportSection? = null,
) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    fun visible(rows: List<ReportRow>) = if (showZero) rows else rows.filter { it.amount != 0L }
    val previousRows = remember(previous) {
        previous?.let { p -> (p.groups.flatMap { it.rows } + p.ungrouped).filter { it.accountId != null }.associate { it.accountId to it.amount } }
    }
    val previousGroups = remember(previous) { previous?.groups?.associate { it.title to it.total } }
    fun previousOf(row: ReportRow): Long? = previousRows?.let { it[row.accountId] ?: 0L }

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
                previous = previousGroups?.let { it[group.title] ?: 0L },
            ) {
                rows.forEach { row -> ReportRowItem(row, onRowClick, previousOf(row)) }
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
            ungrouped.forEach { row -> ReportRowItem(row, onRowClick, previousOf(row)) }
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
        AmountRow(totalLabel, section.total, emphasized = true, leading = { AccentDot(accent) }, previous = previous?.total)
    }
}

@Composable
fun AccentDot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
fun ReportRowItem(row: ReportRow, onClick: ((ReportRow) -> Unit)?, previous: Long? = null) {
    AmountRow(
        label = row.name,
        cents = row.amount,
        supporting = row.number?.let { "#$it" },
        onClick = if (onClick != null && row.accountId != null) ({ onClick(row) }) else null,
        previous = previous,
    )
}
