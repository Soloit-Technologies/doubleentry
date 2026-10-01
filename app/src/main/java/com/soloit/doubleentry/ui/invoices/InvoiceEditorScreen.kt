package com.soloit.doubleentry.ui.invoices

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.InvoiceRepository
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.AccountPickerSheet
import com.soloit.doubleentry.ui.components.AmountField
import com.soloit.doubleentry.ui.components.ConfirmDialog
import com.soloit.doubleentry.ui.components.DateField
import com.soloit.doubleentry.ui.components.LineInput
import com.soloit.doubleentry.ui.components.Lines
import com.soloit.doubleentry.ui.components.LinesEditor
import com.soloit.doubleentry.ui.components.SectionHeader
import com.soloit.doubleentry.ui.transactions.BookingLines
import com.soloit.doubleentry.ui.transactions.EditorStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class InvoiceForm(
    val amountDue: String = "",
    val invoiceDate: LocalDate = LocalDate.now(),
    val dueDate: LocalDate = LocalDate.now().plusDays(30),
    val title: String = "",
    val description: String = "",
) {
    val amountCents: Long? get() = Money.parse(amountDue)?.takeIf { it > 0 }
}

data class InvoiceEditorUiState(
    val isEdit: Boolean,
    val form: InvoiceForm = InvoiceForm(),
    val lines: List<LineInput> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val mostUsed: List<Account> = emptyList(),
    val status: EditorStatus = EditorStatus(),
) {
    val accountsById: Map<String, Account> get() = accounts.associateBy { it.id }
    val canSave: Boolean
        get() = !status.saving && form.title.isNotBlank() && form.amountCents != null && Lines.isBalanced(lines) &&
            Lines.populatedCount(lines) >= 2 && (!isEdit || status.modified)
}

class InvoiceEditorViewModel(
    private val invoices: InvoiceRepository,
    private val ledger: LedgerRepository,
    private val settings: SettingsRepository,
    private val editId: String?,
) : ViewModel() {
    private val form = MutableStateFlow(InvoiceForm())
    private val status = MutableStateFlow(EditorStatus(loading = true))
    private val mostUsed = MutableStateFlow<List<Account>>(emptyList())
    val lines = BookingLines(onUserChange = ::markModified)

    /** Key of the app-managed line that always credits the configured invoice account. */
    private var lockedKey: Long? = null

    val state: StateFlow<InvoiceEditorUiState> = combine(form, lines.lines, ledger.accounts, mostUsed, status) { f, l, a, used, s ->
        InvoiceEditorUiState(editId != null, f, l, a, used, s)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, InvoiceEditorUiState(isEdit = editId != null))

    init {
        viewModelScope.launch {
            val accounts = ledger.getAccounts()
            val byId = accounts.associateBy { it.id }
            mostUsed.value = ledger.mostUsedAccountIds().mapNotNull(byId::get)
            val creditNumber = settings.invoice.first().creditAccountNumber
            val creditAccount = creditNumber?.let { n -> accounts.firstOrNull { it.number == n } }

            if (editId != null) {
                val invoice = invoices.getInvoice(editId)
                val booking = invoice?.let { ledger.getTransaction(it.bookingTransactionId) }
                if (invoice != null) {
                    form.value = InvoiceForm(Money.toInput(invoice.amountDue), invoice.invoiceDate, invoice.dueDate, booking?.title.orEmpty(), booking?.description.orEmpty())
                }
                if (booking != null) {
                    lines.load(booking.entries)
                    // Keep the configured credit line locked when editing, without adding a second one.
                    creditAccount?.let { acc ->
                        lines.current.firstOrNull { it.accountId == acc.id && it.creditCents != null }?.let { match ->
                            lockedKey = match.key
                            lines.set(lines.current.map { if (it.key == match.key) it.copy(locked = true) else it })
                        }
                    }
                }
            } else if (creditAccount != null) {
                // Replace the first empty line with a locked credit line so a spare row remains for the debit side.
                val locked = lines.newLine(accountId = creditAccount.id, locked = true)
                lockedKey = locked.key
                val current = lines.current
                val placeholder = current.indexOfFirst { it.accountId == null && !it.locked }
                lines.set(if (placeholder >= 0) current.toMutableList().also { it[placeholder] = locked } else current + locked)
            }
            status.update { it.copy(loading = false) }
        }
    }

    private fun markModified() = status.update { it.copy(modified = true, error = null) }

    fun setAmountDue(value: String) {
        form.update { it.copy(amountDue = value) }
        lockedKey?.let { key ->
            val cents = Money.parse(value)?.takeIf { it > 0 }
            lines.set(lines.current.map { if (it.key == key) it.copy(credit = cents?.let(Money::toInput).orEmpty()) else it })
        }
        markModified()
    }

    fun setInvoiceDate(value: LocalDate) { form.update { it.copy(invoiceDate = value) }; markModified() }
    fun setDueDate(value: LocalDate) { form.update { it.copy(dueDate = value) }; markModified() }
    fun dueIn(days: Long) = setDueDate(form.value.invoiceDate.plusDays(days))
    fun setTitle(value: String) { form.update { it.copy(title = value) }; markModified() }
    fun setDescription(value: String) { form.update { it.copy(description = value) }; markModified() }

    suspend fun createAccount(number: Int, name: String, type: AccountType): Account = ledger.createAccount(number, name, type)

    fun save() {
        val f = form.value
        val amount = f.amountCents ?: return status.update { it.copy(error = "Enter a valid amount due greater than zero.") }
        if (f.dueDate < f.invoiceDate) return status.update { it.copy(error = "The due date cannot be before the invoice date.") }
        val draftLines = Lines.toDraft(lines.current).getOrElse { e -> return status.update { it.copy(error = e.message) } }
        status.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val booking = TransactionDraft(f.invoiceDate, f.title, f.description, draftLines)
                if (editId != null) invoices.updateInvoice(editId, amount, f.invoiceDate, f.dueDate, booking)
                else invoices.createInvoice(amount, f.invoiceDate, f.dueDate, booking)
                status.update { it.copy(saving = false, done = true) }
            } catch (e: Exception) {
                status.update { it.copy(saving = false, error = e.message) }
            }
        }
    }
}

@Composable
fun InvoiceEditorScreen(editId: String?, onDone: () -> Unit) {
    val vm = appViewModel { InvoiceEditorViewModel(it.invoices, it.ledger, it.settings, editId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }
    var pickingLine by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(state.status.done) { if (state.status.done) onDone() }
    BackHandler(enabled = state.status.modified && !state.status.done) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editId != null) "Edit invoice" else "New invoice") },
                navigationIcon = { IconButton(onClick = { if (state.status.modified) confirmDiscard = true else onDone() }) { Icon(Icons.Rounded.Close, "Close") } },
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
                placeholder = { Text("e.g. Electricity – September") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            AmountField(state.form.amountDue, vm::setAmountDue, "Amount due", Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DateField("Invoice date", state.form.invoiceDate, vm::setInvoiceDate, Modifier.weight(1f))
                DateField("Due date", state.form.dueDate, vm::setDueDate, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Due in", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(10L, 30L, 60L).forEach { days -> AssistChip(onClick = { vm.dueIn(days) }, label = { Text("$days days") }) }
            }
            OutlinedTextField(
                value = state.form.description,
                onValueChange = vm::setDescription,
                label = { Text("Description (optional)") },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            SectionHeader("Booking")
            Text(
                "How the invoice is booked, e.g. debit an expense and credit Accounts Payable. Paying it later settles the payable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            state.status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = vm::save, enabled = state.canSave, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(if (editId != null) "Update invoice" else "Save invoice")
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
