package com.soloit.doubleentry.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.CostPeriodSummary
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.RecurringRepository
import com.soloit.doubleentry.data.UndoManager
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.RecurringRule
import com.soloit.doubleentry.domain.Transaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TransactionDetailUiState(
    val loading: Boolean = true,
    val transaction: Transaction? = null,
    val isDepreciation: Boolean = false,
    /** The recurring rule that posted this transaction, if any. */
    val recurringRule: RecurringRule? = null,
    /** How this transaction's lines are spread over periods, if they are. */
    val costPeriods: CostPeriodSummary? = null,
    /** When this is a period posting: the transaction it moves a share of. */
    val periodSource: Transaction? = null,
    val accounts: Map<String, Account> = emptyMap(),
) {
    /** Postings made by the app are changed through what made them. */
    val isAutomatic: Boolean get() = isDepreciation || periodSource != null
}

class TransactionDetailViewModel(
    private val ledger: LedgerRepository,
    private val recurring: RecurringRepository,
    private val undo: UndoManager,
    private val id: String,
) : ViewModel() {
    val message = MutableStateFlow<String?>(null)
    val deleted = MutableStateFlow(false)

    val state: StateFlow<TransactionDetailUiState> = ledger.changes.mapLatest {
        val tx = ledger.getTransaction(id)
        TransactionDetailUiState(
            loading = false,
            transaction = tx,
            isDepreciation = ledger.isDepreciationTransaction(id),
            recurringRule = recurring.ruleForTransaction(id),
            costPeriods = tx?.let { ledger.getCostPeriods(it) },
            periodSource = ledger.periodSourceOf(id)?.let { ledger.getTransaction(it) },
            accounts = ledger.getAccounts().associateBy { it.id },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionDetailUiState())

    /** Deletes at once; the root snackbar offers to undo it. */
    fun delete() = viewModelScope.launch {
        try {
            val title = state.value.transaction?.title
            val stamp = ledger.deleteTransaction(id)
            undo.offerRestore(if (title.isNullOrBlank()) "Transaction deleted" else "Deleted \u201C$title\u201D", ledger, stamp)
            deleted.value = true
        } catch (e: Exception) {
            message.value = e.message
        }
    }
}
