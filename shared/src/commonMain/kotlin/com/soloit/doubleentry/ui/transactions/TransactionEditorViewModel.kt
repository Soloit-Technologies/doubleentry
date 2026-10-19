package com.soloit.doubleentry.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.ReconciliationRepository
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.Transaction
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.ui.TransactionEditorRoute
import com.soloit.doubleentry.ui.components.LineInput
import com.soloit.doubleentry.ui.components.Lines
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TransactionForm(
    val title: String = "",
    val date: LocalDate = LocalDate.now(),
    val description: String = "",
)

data class EditorStatus(
    val loading: Boolean = false,
    val modified: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

data class TransactionEditorUiState(
    val isEdit: Boolean,
    val form: TransactionForm = TransactionForm(),
    val lines: List<LineInput> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val mostUsed: List<Account> = emptyList(),
    val status: EditorStatus = EditorStatus(),
    /** Earlier transactions whose title matches what is being typed (new transactions only). */
    val suggestions: List<Transaction> = emptyList(),
    /** Reconciled accounts whose reconciled period this transaction changes. */
    val reconciledWarnings: List<String> = emptyList(),
) {
    val accountsById: Map<String, Account> get() = accounts.associateBy { it.id }
    val canSave: Boolean
        get() = !status.saving && form.title.isNotBlank() && Lines.isBalanced(lines) &&
            Lines.populatedCount(lines) >= 2 && (!isEdit || status.modified)
}

@OptIn(FlowPreview::class)
class TransactionEditorViewModel(
    private val ledger: LedgerRepository,
    reconciliations: ReconciliationRepository,
    private val route: TransactionEditorRoute,
) : ViewModel() {
    private val form = MutableStateFlow(TransactionForm())
    private val status = MutableStateFlow(EditorStatus(loading = route.editId != null || route.duplicateId != null))
    private val mostUsed = MutableStateFlow<List<Account>>(emptyList())
    val lines = BookingLines(onUserChange = ::markModified)

    /** The transaction as it was before editing; its date and accounts count for reconciliation too. */
    private var original: Transaction? = null

    /** Title typed by the user that suggestions are looked up for; null after a suggestion was picked. */
    private val suggestionQuery = MutableStateFlow<String?>(null)
    private val suggestions = suggestionQuery.debounce(150).mapLatest { q ->
        if (q == null || q.isBlank() || route.editId != null) emptyList() else ledger.suggestByTitle(q, limit = 4)
    }

    private val warnings = combine(form, lines.lines, reconciliations.statuses, ledger.accounts) { f, l, statuses, accounts ->
        val names = accounts.associate { it.id to it.name }
        val touched = l.mapNotNull { it.accountId }.map { it to f.date } +
            original?.let { tx -> tx.entries.map { it.account.id to tx.date } }.orEmpty()
        touched.filter { (accountId, date) -> statuses[accountId]?.let { date <= it.reconciliation.date } == true }
            .map { it.first }.distinct()
            .map { id -> "${names[id] ?: "An account"} is reconciled through ${statuses.getValue(id).reconciliation.date.formatMedium()}" }
    }

    private val base = combine(form, lines.lines, ledger.accounts, mostUsed, status) { f, l, accounts, used, s ->
        TransactionEditorUiState(route.editId != null, f, l, accounts, used, s)
    }

    val state: StateFlow<TransactionEditorUiState> = combine(base, suggestions, warnings, suggestionQuery) { b, sug, warn, q ->
        // Picking or dismissing clears the query; hide the list at once instead of after the debounce.
        b.copy(suggestions = if (q == null) emptyList() else sug, reconciledWarnings = warn)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TransactionEditorUiState(isEdit = route.editId != null))

    init {
        viewModelScope.launch {
            val accounts = ledger.getAccounts().associateBy { it.id }
            mostUsed.value = ledger.mostUsedAccountIds().mapNotNull(accounts::get)
            val sourceId = route.editId ?: route.duplicateId
            if (sourceId != null) {
                ledger.getTransaction(sourceId)?.let { tx ->
                    if (route.editId != null) original = tx
                    form.value = TransactionForm(tx.title, tx.date, tx.description)
                    lines.loadDraft(ledger.editableLines(tx))
                }
            } else {
                route.date?.let { date -> form.update { it.copy(date = LocalDate.parse(date)) } }
                route.accountId?.let { lines.set(listOf(lines.newLine(accountId = it), lines.newLine())) }
            }
            status.update { it.copy(loading = false) }
        }
    }

    private fun markModified() = status.update { it.copy(modified = true, error = null) }

    fun setTitle(value: String) {
        form.update { it.copy(title = value) }
        suggestionQuery.value = value
        markModified()
    }

    /**
     * Fills the form from an earlier transaction: its title, and its lines and description when the
     * user hasn't entered any yet (an account prefilled by the route doesn't count, as long as the
     * earlier transaction uses it too). The date stays as chosen.
     */
    fun applySuggestion(tx: Transaction) {
        suggestionQuery.value = null
        form.update { it.copy(title = tx.title, description = it.description.ifBlank { tx.description }) }
        val untouched = lines.current.none { it.hasAmount || (it.accountId != null && it.accountId != route.accountId) }
        val keepsAccount = route.accountId == null || tx.entries.any { it.account.id == route.accountId }
        if (untouched && keepsAccount) {
            // Periods are tied to the earlier transaction's dates, so its spread lines are copied whole.
            viewModelScope.launch { lines.loadDraft(ledger.editableLines(tx).map { it.copy(period = null) }) }
        }
        markModified()
    }

    fun dismissSuggestions() { suggestionQuery.value = null }
    fun setDate(value: LocalDate) { form.update { it.copy(date = value) }; markModified() }
    fun setDescription(value: String) { form.update { it.copy(description = value) }; markModified() }

    suspend fun createAccount(number: Int, name: String, type: AccountType): Account = ledger.createAccount(number, name, type)

    fun save() {
        val f = form.value
        val draftLines = Lines.toDraft(lines.current).getOrElse { e ->
            status.update { it.copy(error = e.message) }
            return
        }
        status.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val draft = TransactionDraft(f.date, f.title, f.description, draftLines)
                if (route.editId != null) ledger.updateTransaction(route.editId, draft) else ledger.post(draft)
                status.update { it.copy(saving = false, done = true) }
            } catch (e: Exception) {
                status.update { it.copy(saving = false, error = e.message) }
            }
        }
    }
}
