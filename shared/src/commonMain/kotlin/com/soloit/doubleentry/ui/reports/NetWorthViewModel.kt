package com.soloit.doubleentry.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.domain.lastDay
import com.soloit.doubleentry.domain.reports.NetWorthTrend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth

/** How far back the net worth report looks from its end month. */
enum class NetWorthRange(val label: String) {
    Year("1 year"),
    ThreeYears("3 years"),
    All("All"),
    /** From a start month the user picks. */
    Custom("Custom"),
}

data class NetWorthUiState(
    val end: YearMonth = YearMonth.now(),
    val range: NetWorthRange = NetWorthRange.Year,
    /** First month of a [NetWorthRange.Custom] range. */
    val customStart: YearMonth = YearMonth.now().withMonth(1),
    /** Null while loading. */
    val trend: NetWorthTrend? = null,
)

class NetWorthViewModel(ledger: LedgerRepository) : ViewModel() {
    val end = MutableStateFlow(YearMonth.now())
    val range = MutableStateFlow(NetWorthRange.Year)
    val customStart = MutableStateFlow(YearMonth.now().withMonth(1))

    val state: StateFlow<NetWorthUiState> = combine(end, range, customStart, ledger.changes) { e, r, c, _ -> Triple(e, r, c) }
        .mapLatest { (pickedEnd, range, custom) ->
            // A custom range picked back to front is read the right way round.
            val end = if (range == NetWorthRange.Custom) maxOf(pickedEnd, custom) else pickedEnd
            val start = when (range) {
                NetWorthRange.Year -> end.minusMonths(11)
                NetWorthRange.ThreeYears -> end.minusMonths(35)
                // From the first transaction; a year when there is no history yet.
                NetWorthRange.All -> ledger.firstNetWorthMonth()?.coerceAtMost(end) ?: end.minusMonths(11)
                NetWorthRange.Custom -> minOf(pickedEnd, custom)
            }
            NetWorthUiState(end, range, custom, ledger.getNetWorthTrend(start, end.lastDay()))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetWorthUiState())
}
