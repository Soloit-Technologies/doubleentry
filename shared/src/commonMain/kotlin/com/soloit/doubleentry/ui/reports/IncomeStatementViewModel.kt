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

data class IncomeStatementUiState(
    val period: ReportPeriod = ReportPeriod(),
    val report: IncomeStatementReport? = null,
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
            val other = p.comparedWith(c)
            IncomeStatementUiState(
                period = p,
                report = compute(p, accounts, s),
                compare = c,
                previous = other?.let { compute(it, accounts, s) },
                previousLabel = other?.label,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IncomeStatementUiState())

    private suspend fun compute(p: ReportPeriod, accounts: List<Account>, s: IncomeStatementSettings): IncomeStatementReport {
        val needsTransactions = s.deferredAssetPrefixes.any { it.isNotBlank() }
        return IncomeStatementCalculator.compute(
            accounts = accounts,
            movements = ledger.getMovements(p.from, p.to),
            transactions = if (needsTransactions) ledger.getTransactions(p.from, p.to) else emptyList(),
            settings = s,
        )
    }
}
