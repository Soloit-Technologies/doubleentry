package com.soloit.doubleentry.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.domain.reports.BalanceSheetCalculator
import com.soloit.doubleentry.domain.reports.BalanceSheetReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

data class BalanceSheetUiState(val asOf: LocalDate = LocalDate.now(), val report: BalanceSheetReport? = null)

class BalanceSheetViewModel(ledger: LedgerRepository, settings: SettingsRepository) : ViewModel() {
    val asOf = MutableStateFlow(LocalDate.now())

    val state: StateFlow<BalanceSheetUiState> = combine(asOf, settings.balanceSheet, ledger.changes) { d, s, _ -> d to s }
        .mapLatest { (date, s) ->
            val report = BalanceSheetCalculator.compute(
                asOf = date,
                accounts = ledger.getAccounts(),
                balances = ledger.getBalances(date),
                priorYearEndBalances = ledger.getBalances(LocalDate.of(date.year - 1, 12, 31)),
                settings = s,
            )
            BalanceSheetUiState(date, report)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BalanceSheetUiState())
}
