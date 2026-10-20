package com.soloit.doubleentry.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.domain.reports.CashFlowCalculator
import com.soloit.doubleentry.domain.reports.CashFlowReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class CashFlowUiState(val period: ReportPeriod = ReportPeriod(), val report: CashFlowReport? = null)

class CashFlowViewModel(ledger: LedgerRepository, settings: SettingsRepository) : ViewModel() {
    val period = MutableStateFlow(ReportPeriod())

    val state: StateFlow<CashFlowUiState> = combine(period, settings.cashflow, ledger.changes) { p, s, _ -> p to s }
        .mapLatest { (p, s) ->
            val report = CashFlowCalculator.compute(
                accounts = ledger.getAccounts(),
                beginningBalances = ledger.getBalances(p.from.minusDays(1)),
                endingBalances = ledger.getBalances(p.to),
                transactions = ledger.getTransactions(p.from, p.to),
                groups = s.cashGroups,
                period = p.from..p.to,
            )
            CashFlowUiState(p, report)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CashFlowUiState())
}
