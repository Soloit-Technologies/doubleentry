package com.soloit.doubleentry.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.domain.reports.CostSeries
import com.soloit.doubleentry.domain.reports.CostTrendCalculator
import com.soloit.doubleentry.domain.reports.CostTrendResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth

enum class TrendRange(val label: String) { Last12("12 months"), ThisYear("This year"), Custom("Custom") }

enum class TrendGrouping(val label: String) { Groups("My groups"), Accounts("Accounts") }

data class TrendQuery(val range: TrendRange = TrendRange.Last12, val grouping: TrendGrouping = TrendGrouping.Groups, val customStart: YearMonth = YearMonth.now().withMonth(1), val customEnd: YearMonth = YearMonth.now()) {
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
    val expanded: Set<String> = emptySet(),
) {
    val topCosts: List<CostSeries> get() = result?.series.orEmpty().filter { it.total != 0L }.sortedByDescending { it.total }
}

class CostTrendsViewModel(ledger: LedgerRepository, settings: SettingsRepository) : ViewModel() {
    val query = MutableStateFlow(TrendQuery())
    val selectedKey = MutableStateFlow<String?>(null)
    val selectedMonth = MutableStateFlow<Int?>(null)
    val expanded = MutableStateFlow<Set<String>>(emptySet())

    private val result = combine(query, settings.incomeStatement, ledger.changes) { q, s, _ -> q to s }.mapLatest { (q, s) ->
        val (start, end) = q.months
        val months = CostTrendCalculator.months(start, end)
        CostTrendCalculator.compute(ledger.getAccounts(), months, ledger.getMonthlyMovements(months.first(), months.last()), s.expenseGroups, q.grouping == TrendGrouping.Groups, s.taxPrefixes, s.depreciationPrefixes)
    }

    val state: StateFlow<CostTrendsUiState> = combine(query, result, selectedKey, selectedMonth, expanded) { q, r, key, month, open ->
        val base = CostTrendsUiState(query = q, result = r, expanded = open)
        // A selected account inside a group is charted in its group's colour; with nothing picked, the overall trend shows.
        val parent = r.series.firstOrNull { it.key == key || it.accounts.any { a -> a.key == key } }
        val series = parent?.let { p -> p.accounts.firstOrNull { it.key == key } ?: p } ?: r.overall.firstOrNull { it.key == key } ?: r.total
        base.copy(
            selected = series,
            selectedIndex = r.series.indexOf(parent ?: series).coerceAtLeast(0),
            selectedMonth = month?.takeIf { it in r.months.indices } ?: r.months.lastIndex.takeIf { it >= 0 },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CostTrendsUiState())

    fun setRange(range: TrendRange) { query.value = query.value.copy(range = range); selectedMonth.value = null }
    fun setGrouping(grouping: TrendGrouping) { query.value = query.value.copy(grouping = grouping); selectedKey.value = null; expanded.value = emptySet() }
    fun setCustomStart(value: YearMonth) { query.value = query.value.copy(customStart = value); selectedMonth.value = null }
    fun setCustomEnd(value: YearMonth) { query.value = query.value.copy(customEnd = value); selectedMonth.value = null }
    fun select(series: CostSeries) { selectedKey.value = series.key }
    fun toggleExpanded(series: CostSeries) { expanded.value = expanded.value.let { if (series.key in it) it - series.key else it + series.key } }
    fun selectMonth(index: Int) { selectedMonth.value = index }
}
