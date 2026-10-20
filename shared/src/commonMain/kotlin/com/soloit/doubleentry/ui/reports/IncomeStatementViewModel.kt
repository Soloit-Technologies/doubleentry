package com.soloit.doubleentry.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.IncomeStatementSettings
import com.soloit.doubleentry.domain.reports.IncomeStatementCalculator
import com.soloit.doubleentry.domain.reports.IncomeStatementReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth

data class IncomeStatementUiState(
    val period: ReportPeriod = ReportPeriod(),
    val report: IncomeStatementReport? = null,
    /** The comparison in effect: what was picked, unless the period mode doesn't offer it. */
    val compare: CompareMode = CompareMode.None,
    /** The report for the comparison period, when comparing. */
    val previous: IncomeStatementReport? = null,
    val previousLabel: String? = null,
)

class IncomeStatementViewModel(private val ledger: LedgerRepository, settings: SettingsRepository) : ViewModel() {
    val period = MutableStateFlow(ReportPeriod())
    val compare = MutableStateFlow(CompareMode.None)

    val state: StateFlow<IncomeStatementUiState> = combine(period, compare, settings.incomeStatement, ledger.changes) { p, c, s, _ -> Triple(p, c, s) }
        .mapLatest { (p, c, s) ->
            val accounts = ledger.getAccounts()
            val effective = if (c in CompareMode.availableFor(p.mode)) c else CompareMode.None
            val (previous, previousLabel) = if (effective == CompareMode.TwelveMonthAverage) {
                val window = p.averageWindow(ledger.firstIncomeStatementDate()?.let(YearMonth::from))
                window?.let { computeAverage(it, accounts, s) } to window?.label
            } else {
                val other = p.comparedWith(effective)
                other?.let { compute(it.from, it.to, accounts, s) } to other?.label
            }
            IncomeStatementUiState(
                period = p,
                report = compute(p.from, p.to, accounts, s),
                compare = effective,
                previous = previous,
                previousLabel = previousLabel,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IncomeStatementUiState())

    private suspend fun compute(from: LocalDate, to: LocalDate, accounts: List<Account>, s: IncomeStatementSettings): IncomeStatementReport =
        IncomeStatementCalculator.compute(accounts, ledger.getMovements(from, to), transactionsFor(from, to, s), s)

    private suspend fun computeAverage(w: AverageWindow, accounts: List<Account>, s: IncomeStatementSettings): IncomeStatementReport =
        IncomeStatementCalculator.computeMonthlyAverage(accounts, ledger.getMovements(w.from, w.to), transactionsFor(w.from, w.to, s), s, w.months)

    private suspend fun transactionsFor(from: LocalDate, to: LocalDate, s: IncomeStatementSettings) =
        if (s.deferredAssetPrefixes.any { it.isNotBlank() }) ledger.getTransactions(from, to) else emptyList()
}
