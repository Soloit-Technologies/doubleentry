package com.soloit.doubleentry.ui.transactions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.ReconciliationRepository
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.Transaction
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.ui.TransactionEditorRoute
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.AccountPickerSheet
import com.soloit.doubleentry.ui.components.ConfirmDialog
import com.soloit.doubleentry.ui.components.DateField
import com.soloit.doubleentry.ui.components.LineInput
import com.soloit.doubleentry.ui.components.Lines
import com.soloit.doubleentry.ui.components.LinesEditor
import com.soloit.doubleentry.ui.components.MoneyText
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.components.SectionHeader
import com.soloit.doubleentry.ui.components.StatusChip
import com.soloit.doubleentry.ui.theme.AppTheme
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
                    lines.load(tx.entries)
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
        if (untouched && keepsAccount) lines.load(tx.entries)
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

@Composable
fun TransactionEditorScreen(route: TransactionEditorRoute, onDone: () -> Unit) {
    val vm = appViewModel { TransactionEditorViewModel(it.ledger, it.reconciliations, route) }
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }
    var pickingLine by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(state.status.done) { if (state.status.done) onDone() }
    BackHandler(enabled = state.status.modified && !state.status.done) { confirmDiscard = true }
    val close = { if (state.status.modified) confirmDiscard = true else onDone() }

    val title = when {
        route.editId != null -> "Edit transaction"
        route.duplicateId != null -> "Duplicate transaction"
        else -> "New transaction"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Rounded.Close, "Close") } },
                actions = { TextButton(onClick = vm::save, enabled = state.canSave) { Text("Save") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.form.title,
                onValueChange = vm::setTitle,
                label = { Text("Title") },
                placeholder = { Text("e.g. Groceries at ICA") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.suggestions.isNotEmpty()) TitleSuggestions(state.suggestions, vm::applySuggestion, vm::dismissSuggestions)
            DateField("Date", state.form.date, vm::setDate, Modifier.fillMaxWidth())
            OutlinedTextField(
                value = state.form.description,
                onValueChange = vm::setDescription,
                label = { Text("Description (optional)") },
                minLines = 2,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            SectionHeader("Lines")
            LinesEditor(
                lines = state.lines,
                accounts = state.accountsById,
                onPickAccount = { pickingLine = it },
                onDebitChange = vm.lines::setDebit,
                onCreditChange = vm.lines::setCredit,
                onFocusDebit = vm.lines::autoFillDebit,
                onFocusCredit = vm.lines::autoFillCredit,
                onRemove = vm.lines::remove,
                onAdd = vm.lines::add,
            )
            state.reconciledWarnings.forEach { warning ->
                StatusChip(
                    "$warning; saving changes its reconciled balance",
                    AppTheme.finance.warningContainer,
                    AppTheme.finance.onWarningContainer,
                    icon = Icons.Rounded.Warning,
                )
            }
            state.status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = vm::save, enabled = state.canSave, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(if (route.editId != null) "Update transaction" else "Post transaction")
            }
        }
    }

    pickingLine?.let { key ->
        AccountPickerSheet(
            accounts = state.accounts,
            mostUsed = state.mostUsed,
            selectedId = state.lines.firstOrNull { it.key == key }?.accountId,
            onSelect = { account -> vm.lines.setAccount(key, account.id); pickingLine = null },
            onDismiss = { pickingLine = null },
            onCreateAccount = vm::createAccount,
        )
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            message = "Your unsaved changes will be lost.",
            confirmLabel = "Discard",
            destructive = true,
            onConfirm = { confirmDiscard = false; onDone() },
            onDismiss = { confirmDiscard = false },
        )
    }
}

/** Earlier transactions matching the typed title; picking one copies its lines. */
@Composable
private fun TitleSuggestions(suggestions: List<Transaction>, onPick: (Transaction) -> Unit, onDismiss: () -> Unit) {
    SectionCard(contentPadding = PaddingValues(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Copy from an earlier transaction",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Hide suggestions", Modifier.size(18.dp)) }
        }
        suggestions.forEach { tx ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(tx) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tx.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${tx.flowSummary()} · ${tx.date.formatMedium()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                MoneyText(tx.totalDebits, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
