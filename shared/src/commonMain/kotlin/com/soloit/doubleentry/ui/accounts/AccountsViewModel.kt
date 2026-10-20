package com.soloit.doubleentry.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.ReconciliationRepository
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.ReconciliationStatus
import com.soloit.doubleentry.domain.firstDay
import com.soloit.doubleentry.domain.lastDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth

data class AccountBalanceUi(val account: Account, val opening: Long, val closing: Long) {
    val change: Long get() = closing - opening
}

data class AccountSectionUi(val type: AccountType, val rows: List<AccountBalanceUi>) {
    val total: Long get() = rows.sumOf { it.closing }
}

data class AccountsUiState(
    val loading: Boolean = true,
    val startupComplete: Boolean = false,
    val month: YearMonth = YearMonth.now(),
    val query: String = "",
    val hasAccounts: Boolean = false,
    val sections: List<AccountSectionUi> = emptyList(),
    val netWorth: Long = 0,
    val netWorthChange: Long = 0,
    val income: Long = 0,
    val spending: Long = 0,
    val seeding: Boolean = false,
    /** Reconciled accounts by id, with whether they still match their statement. */
    val reconciliations: Map<String, ReconciliationStatus> = emptyMap(),
)

class AccountsViewModel(
    private val ledger: LedgerRepository,
    reconciliations: ReconciliationRepository,
    startupComplete: StateFlow<Boolean>,
) : ViewModel() {
    private val month = MutableStateFlow(YearMonth.now())
    private val query = MutableStateFlow("")
    private val seeding = MutableStateFlow(false)

    private data class Loaded(val month: YearMonth, val rows: List<AccountBalanceUi>)

    private val loaded = combine(month, ledger.changes) { m, _ -> m }.mapLatest { m ->
        val opening = ledger.getBalances(m.firstDay().minusDays(1))
        val closing = ledger.getBalances(m.lastDay())
        Loaded(m, ledger.getAccounts().map { AccountBalanceUi(it, opening[it.id] ?: 0, closing[it.id] ?: 0) })
    }

    val state: StateFlow<AccountsUiState> = combine(loaded, query, startupComplete, seeding, reconciliations.statuses) { data, q, startup, seed, recs ->
        fun sum(type: AccountType, pick: (AccountBalanceUi) -> Long) = data.rows.filter { it.account.type == type }.sumOf(pick)
        val netClosing = sum(AccountType.Asset) { it.closing } - sum(AccountType.Liability) { it.closing }
        val netOpening = sum(AccountType.Asset) { it.opening } - sum(AccountType.Liability) { it.opening }
        val search = q.trim()
        val visible = data.rows.filter {
            search.isEmpty() || it.account.name.contains(search, ignoreCase = true) || it.account.number.toString().contains(search)
        }
        AccountsUiState(
            loading = false,
            startupComplete = startup,
            month = data.month,
            query = q,
            hasAccounts = data.rows.isNotEmpty(),
            sections = AccountType.entries.mapNotNull { t -> visible.filter { it.account.type == t }.takeIf { it.isNotEmpty() }?.let { AccountSectionUi(t, it) } },
            netWorth = netClosing,
            netWorthChange = netClosing - netOpening,
            income = sum(AccountType.Revenue) { it.change },
            spending = sum(AccountType.Expense) { it.change },
            seeding = seed,
            reconciliations = recs,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    fun setMonth(value: YearMonth) { month.value = value }

    fun setQuery(value: String) { query.value = value }

    fun seedDefaultPlan() {
        if (seeding.value) return
        seeding.value = true
        viewModelScope.launch {
            try { ledger.seedDefaultPlan() } finally { seeding.value = false }
        }
    }
}
