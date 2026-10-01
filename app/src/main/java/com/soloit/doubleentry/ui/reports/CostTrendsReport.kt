package com.soloit.doubleentry.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.domain.monthAbbreviation
import com.soloit.doubleentry.domain.reports.CostSeries
import com.soloit.doubleentry.domain.reports.CostTrendCalculator
import com.soloit.doubleentry.domain.reports.CostTrendResult
import com.soloit.doubleentry.domain.shortLabel
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.EmptyState
import com.soloit.doubleentry.ui.components.LoadingState
import com.soloit.doubleentry.ui.components.MoneyText
import com.soloit.doubleentry.ui.components.MonthPickerDialog
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.components.SectionHeader
import com.soloit.doubleentry.ui.components.SegmentedChoice
import com.soloit.doubleentry.ui.components.TrendLineChart
import com.soloit.doubleentry.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth

enum class TrendRange(val label: String) { Last12("12 months"), ThisYear("This year"), Custom("Custom") }

data class TrendQuery(val range: TrendRange = TrendRange.Last12, val customStart: YearMonth = YearMonth.now().withMonth(1), val customEnd: YearMonth = YearMonth.now()) {
    val months: Pair<YearMonth, YearMonth>
        get() {
            val now = YearMonth.now()
            return when (range) {
                TrendRange.Last12 -> now.minusMonths(11) to now
                TrendRange.ThisYear -> now.withMonth(1) to now
                TrendRange.Custom -> customStart to customEnd
            }
        }
}

data class CostTrendsUiState(
    val query: TrendQuery = TrendQuery(),
    val result: CostTrendResult? = null,
    val selected: CostSeries? = null,
    val selectedIndex: Int = 0,
    val selectedMonth: Int? = null,
) {
    val topCosts: List<CostSeries> get() = result?.series.orEmpty().filter { it.total != 0L }.sortedByDescending { it.total }
}

class CostTrendsViewModel(ledger: LedgerRepository, settings: SettingsRepository) : ViewModel() {
    val query = MutableStateFlow(TrendQuery())
    val selectedKey = MutableStateFlow<String?>(null)
    val selectedMonth = MutableStateFlow<Int?>(null)

    private val result = combine(query, settings.incomeStatement, ledger.changes) { q, s, _ -> q to s }.mapLatest { (q, s) ->
        val (start, end) = q.months
        val months = CostTrendCalculator.months(start, end)
        CostTrendCalculator.compute(ledger.getAccounts(), months, ledger.getMonthlyMovements(months.first(), months.last()), s.expenseGroups)
    }

    val state: StateFlow<CostTrendsUiState> = combine(query, result, selectedKey, selectedMonth) { q, r, key, month ->
        val base = CostTrendsUiState(query = q, result = r)
        val series = r.series.firstOrNull { it.key == key } ?: base.topCosts.firstOrNull() ?: r.series.firstOrNull()
        base.copy(
            selected = series,
            selectedIndex = r.series.indexOf(series).coerceAtLeast(0),
            selectedMonth = month?.takeIf { it in r.months.indices } ?: r.months.lastIndex.takeIf { it >= 0 },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CostTrendsUiState())

    fun setRange(range: TrendRange) { query.value = query.value.copy(range = range); selectedMonth.value = null }
    fun setCustomStart(value: YearMonth) { query.value = query.value.copy(customStart = value); selectedMonth.value = null }
    fun setCustomEnd(value: YearMonth) { query.value = query.value.copy(customEnd = value); selectedMonth.value = null }
    fun select(series: CostSeries) { selectedKey.value = series.key }
    fun selectMonth(index: Int) { selectedMonth.value = index }
}

@Composable
fun CostTrendsReportPage(onOpenSettings: () -> Unit) {
    val vm = appViewModel { CostTrendsViewModel(it.ledger, it.settings) }
    val state by vm.state.collectAsStateWithLifecycle()
    var pickingStart by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }
    val palette = AppTheme.finance.chart

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SegmentedChoice(TrendRange.entries, state.query.range, vm::setRange, { it.label }, Modifier.weight(1f))
                SettingsShortcut(onOpenSettings)
            }
        }
        if (state.query.range == TrendRange.Custom) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickingStart = true }, modifier = Modifier.weight(1f)) { Text("From ${state.query.customStart.shortLabel()}") }
                    OutlinedButton(onClick = { pickingEnd = true }, modifier = Modifier.weight(1f)) { Text("To ${state.query.customEnd.shortLabel()}") }
                }
            }
        }
        val result = state.result
        val series = state.selected
        when {
            result == null -> item { LoadingState() }
            series == null || state.topCosts.isEmpty() -> item {
                EmptyState(Icons.AutoMirrored.Rounded.ShowChart, "No expenses in this period", "Costs booked on expense accounts will show up here month by month.")
            }
            else -> {
                item {
                    Text(
                        "${result.months.first().shortLabel()} – ${result.months.last().shortLabel()}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                item {
                    val color = palette[state.selectedIndex % palette.size]
                    SectionCard(contentPadding = PaddingValues(16.dp)) {
                        SeriesPicker(result.series, series, vm::select)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Figure("Total", series.total, Modifier.weight(1f))
                            Figure("Avg / month", series.perMonth, Modifier.weight(1f))
                            val monthIndex = state.selectedMonth ?: result.months.lastIndex
                            Figure(result.months[monthIndex].shortLabel(), series.monthly[monthIndex], Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(12.dp))
                        TrendLineChart(
                            values = series.monthly,
                            labels = result.months.map { it.monthAbbreviation() },
                            color = color,
                            selectedIndex = state.selectedMonth,
                            onSelect = vm::selectMonth,
                        )
                    }
                }
                item { SectionHeader("Top costs") }
                val top = state.topCosts
                val max = top.first().total.coerceAtLeast(1)
                itemsIndexed(top, key = { _, s -> s.key }) { rank, s ->
                    val color = palette[result.series.indexOf(s) % palette.size]
                    TopCostRow(rank + 1, s, s.total.toFloat() / max, color, selected = s.key == series.key) { vm.select(s) }
                }
            }
        }
    }

    if (pickingStart) {
        MonthPickerDialog(state.query.customStart, onDismiss = { pickingStart = false }, onConfirm = { vm.setCustomStart(it); pickingStart = false }, title = "From month")
    }
    if (pickingEnd) {
        MonthPickerDialog(state.query.customEnd, onDismiss = { pickingEnd = false }, onConfirm = { vm.setCustomEnd(it); pickingEnd = false }, title = "To month")
    }
}

@Composable
private fun SeriesPicker(all: List<CostSeries>, selected: CostSeries, onSelect: (CostSeries) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(selected.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            all.sortedBy { it.name.lowercase() }.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.name) },
                    trailingIcon = { MoneyText(s.total, style = MaterialTheme.typography.bodySmall) },
                    onClick = { onSelect(s); open = false },
                )
            }
        }
    }
}

@Composable
private fun Figure(label: String, cents: Long, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        MoneyText(cents, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TopCostRow(rank: Int, series: CostSeries, share: Float, color: androidx.compose.ui.graphics.Color, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                    Surface(shape = CircleShape, color = color.copy(alpha = 0.18f), modifier = Modifier.size(24.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text("$rank", style = MaterialTheme.typography.labelSmall, color = color) }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(series.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (series.isGroup) "Group · " else "") + "avg ${com.soloit.doubleentry.domain.Money.formatWhole(series.perMonth)} / month",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MoneyText(series.total, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { share.coerceIn(0f, 1f) },
                color = color,
                trackColor = color.copy(alpha = 0.12f),
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            )
        }
    }
}
