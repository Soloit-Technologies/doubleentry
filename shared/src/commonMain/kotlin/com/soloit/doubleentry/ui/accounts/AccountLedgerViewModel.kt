package com.soloit.doubleentry.ui.accounts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.ReconciliationRepository
import com.soloit.doubleentry.data.UndoManager
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.ReconciliationStatus
import com.soloit.doubleentry.domain.firstDay
import com.soloit.doubleentry.domain.lastDay
import com.soloit.doubleentry.ui.transactions.searchText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class LedgerRowUi(
    val transactionId: String,
    val date: LocalDate,
    val title: String,
    val amount: Long,
    val balance: Long,
    val searchText: String = "",
)

data class AccountLedgerUiState(
    val loading: Boolean = true,
    val account: Account? = null,
    val month: YearMonth = YearMonth.now(),
    val opening: Long = 0,
    val closing: Long = 0,
    val rows: List<LedgerRowUi> = emptyList(),
    /** A search covers every month of this account. */
    val searching: Boolean = false,
    val reconciliation: ReconciliationStatus? = null,
    val accounts: List<Account> = emptyList(),
)

class AccountLedgerViewModel(
    private val ledger: LedgerRepository,
    private val reconciliations: ReconciliationRepository,
    private val undo: UndoManager,
    private val accountId: String,
    initial: YearMonth,
) : ViewModel() {
    private val month = MutableStateFlow(initial)

    /** Compose state, not part of [state], so the search field never lags behind typing. */
    var query by mutableStateOf("")
    val message = MutableStateFlow<String?>(null)
    val deleted = MutableStateFlow(false)

    /** Every row of this account with its running balance, loaded only while a search is active. */
    private val everything = combine(snapshotFlow { query.isNotBlank() }.distinctUntilChanged(), ledger.changes) { active, _ -> active }
        .mapLatest { active ->
            if (!active) return@mapLatest null
            val account = ledger.getAccount(accountId) ?: return@mapLatest emptyList()
            var running = 0L
            ledger.getTransactions(accountId = accountId).map { tx ->
                val amount = tx.signedAmountFor(account)
                running += amount
                LedgerRowUi(tx.id, tx.date, tx.title, amount, running, tx.searchText())
            }.reversed()
        }

    private val loaded = combine(month, ledger.changes) { m, _ -> m }.mapLatest { m ->
        val account = ledger.getAccount(accountId) ?: return@mapLatest AccountLedgerUiState(loading = false, month = m)
        val opening = ledger.getBalances(m.firstDay().minusDays(1))[accountId] ?: 0
        var running = opening
        val rows = ledger.getTransactions(m.firstDay(), m.lastDay(), accountId).map { tx ->
            val amount = tx.signedAmountFor(account)
            running += amount
            LedgerRowUi(tx.id, tx.date, tx.title, amount, running)
        }
        AccountLedgerUiState(loading = false, account = account, month = m, opening = opening, closing = running, rows = rows.reversed())
    }

    private val status = reconciliations.statuses.map { it[accountId] }

    val state: StateFlow<AccountLedgerUiState> = combine(loaded, snapshotFlow { query }, everything, status, ledger.accounts) { s, q, all, rec, accounts ->
        val search = q.trim()
        val base = s.copy(reconciliation = rec, accounts = accounts)
        if (search.isEmpty()) {
            base
        } else {
            base.copy(
                loading = s.loading || all == null,
                rows = all.orEmpty().filter { it.searchText.contains(search, ignoreCase = true) },
                searching = true,
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountLedgerUiState(month = initial))

    fun setMonth(value: YearMonth) { month.value = value }

    /** Returns false (and shows why) when the account can't be deleted. */
    suspend fun canDelete(): Boolean {
        if (ledger.accountHasTransactions(accountId)) {
            message.value = "This account has transactions. Delete or move them before deleting the account."
            return false
        }
        return true
    }

    fun delete() = viewModelScope.launch {
        try {
            val name = state.value.account?.name ?: "account"
            val stamp = ledger.deleteAccount(accountId)
            undo.offerRestore("Deleted $name", ledger, stamp)
            deleted.value = true
        } catch (e: Exception) {
            message.value = e.message
        }
    }

    suspend fun bookBalance(date: LocalDate): Long = reconciliations.bookBalance(accountId, date)

    fun reconcile(date: LocalDate, statementBalance: Long) = run { reconciliations.reconcile(accountId, date, statementBalance) }

    fun adjustAndReconcile(date: LocalDate, statementBalance: Long, counterAccountId: String) =
        run { reconciliations.adjustAndReconcile(accountId, date, statementBalance, counterAccountId, "Reconciliation adjustment") }

    fun clearReconciliation() = run { reconciliations.clear(accountId) }

    private fun run(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: Exception) { message.value = e.message }
    }
}
