package com.soloit.doubleentry.ui.invoices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.InvoiceRepository
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.data.UndoManager
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.Invoice
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.Transaction
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.AccountPickerSheet
import com.soloit.doubleentry.ui.components.AmountField
import com.soloit.doubleentry.ui.components.AmountRow
import com.soloit.doubleentry.ui.components.ConfirmDialog
import com.soloit.doubleentry.ui.components.DateField
import com.soloit.doubleentry.ui.components.EmptyState
import com.soloit.doubleentry.ui.components.LoadingState
import com.soloit.doubleentry.ui.components.MessageDialog
import com.soloit.doubleentry.ui.components.MoneyText
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.components.SectionHeader
import com.soloit.doubleentry.ui.transactions.EntriesCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class InvoiceDetailUiState(
    val loading: Boolean = true,
    val invoice: Invoice? = null,
    val booking: Transaction? = null,
    val paidFrom: Account? = null,
    val paymentAccounts: List<Account> = emptyList(),
    val accounts: List<Account> = emptyList(),
)

class InvoiceDetailViewModel(
    private val invoices: InvoiceRepository,
    private val ledger: LedgerRepository,
    settings: SettingsRepository,
    private val undo: UndoManager,
    private val id: String,
) : ViewModel() {
    val message = MutableStateFlow<String?>(null)
    val closed = MutableStateFlow(false)

    val state: StateFlow<InvoiceDetailUiState> = combine(ledger.changes, settings.invoice) { _, s -> s }.mapLatest { s ->
        val invoice = invoices.getInvoice(id) ?: return@mapLatest InvoiceDetailUiState(loading = false)
        val accounts = ledger.getAccounts()
        // Pay from any account except the outstanding one, restricted to the configured set when there is one.
        val allowed = s.paymentAccountNumbers.toSet()
        InvoiceDetailUiState(
            loading = false,
            invoice = invoice,
            booking = ledger.getTransaction(invoice.bookingTransactionId),
            paidFrom = invoice.paidFromAccountId?.let { pid -> accounts.firstOrNull { it.id == pid } },
            paymentAccounts = accounts.filter { it.id != invoice.outstandingAccountId && (allowed.isEmpty() || it.number in allowed) },
            accounts = accounts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InvoiceDetailUiState())

    /** Pays [amount] (everything left when null or equal to it). */
    fun pay(accountId: String, date: LocalDate, amount: Long?) = run { invoices.recordPayment(id, accountId, date, amount) }
    fun updatePaidDate(date: LocalDate) = run { invoices.updatePaidDate(id, date) }
    fun delete() = run {
        val title = state.value.booking?.title
        val stamp = invoices.deleteInvoice(id)
        undo.offerRestore(if (title.isNullOrBlank()) "Invoice deleted" else "Deleted invoice \u201C$title\u201D", ledger, stamp)
        closed.value = true
    }

    private fun run(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: Exception) { message.value = e.message }
    }
}

@Composable
fun InvoiceDetailScreen(invoiceId: String, onBack: () -> Unit, onEdit: () -> Unit, onOpenTransaction: (String) -> Unit) {
    val vm = appViewModel { InvoiceDetailViewModel(it.invoices, it.ledger, it.settings, it.undo, invoiceId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val closed by vm.closed.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var choosingAccount by remember { mutableStateOf(false) }
    var paymentDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    // Blank means "everything that is left".
    var paymentAmount by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(closed) { if (closed) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Invoice") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    val invoice = state.invoice
                    if (invoice != null) {
                        if (!invoice.isPaid) IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "Edit invoice") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, "Delete invoice") }
                    }
                },
            )
        },
    ) { padding ->
        val invoice = state.invoice
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            invoice == null -> EmptyState(Icons.AutoMirrored.Rounded.ReceiptLong, "Invoice not found", "It may have been deleted on another device.", Modifier.padding(padding))
            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionCard(contentPadding = PaddingValues(20.dp)) {
                    Text(state.booking?.title ?: "Invoice", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    MoneyText(invoice.amountDue, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                    if (!invoice.isPaid && invoice.partiallyPaid > 0) {
                        Text(
                            "${Money.format(invoice.partiallyPaid)} paid · ${Money.format(invoice.remaining)} left",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (invoice.partiallyPaid.toFloat() / invoice.amountDue).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    DueStatusChip(invoice.dueStatus())
                    state.booking?.description?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                SectionCard {
                    InfoRow("Invoice date", invoice.invoiceDate.formatMedium())
                    InfoRow("Due date", invoice.dueDate.formatMedium())
                    if (invoice.isPaid) {
                        invoice.paidDate?.let { InfoRow("Paid on", it.formatMedium()) }
                        InfoRow("Paid from", state.paidFrom?.display ?: "—")
                    }
                }

                if (invoice.partialPayments.isNotEmpty()) {
                    SectionHeader("Instalments")
                    SectionCard {
                        invoice.partialPayments.forEach { p ->
                            AmountRow(
                                label = p.date.formatMedium(),
                                cents = p.amount,
                                supporting = state.accounts.firstOrNull { it.id == p.accountId }?.let { "From ${it.name}" },
                                onClick = { onOpenTransaction(p.transactionId) },
                            )
                        }
                    }
                }

                if (!invoice.isPaid) {
                    val amount = paymentAmount.takeIf { it.isNotBlank() }?.let { Money.parse(it) }
                    val invalidAmount = paymentAmount.isNotBlank() && (amount == null || amount <= 0 || amount > invoice.remaining)
                    val partial = amount != null && amount < invoice.remaining
                    SectionHeader("Pay")
                    SectionCard(contentPadding = PaddingValues(16.dp)) {
                        Text(
                            "Posts a payment from the account you choose. Pay everything that is left to mark the invoice as paid, " +
                                "or enter a smaller amount to record an instalment.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        AmountField(
                            paymentAmount,
                            { paymentAmount = it },
                            "Amount (${Money.format(invoice.remaining)} left)",
                            Modifier.fillMaxWidth(),
                            isError = invalidAmount,
                            supportingText = when {
                                invalidAmount -> "Enter at most ${Money.format(invoice.remaining)}"
                                paymentAmount.isBlank() -> "Leave empty to pay everything that is left"
                                else -> null
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                        DateField("Payment date", LocalDate.parse(paymentDate), { paymentDate = it.toString() }, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { choosingAccount = true }, enabled = !invalidAmount, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Payments, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (partial) "Record instalment…" else "Mark as paid…")
                        }
                    }
                } else {
                    SectionHeader("Payment")
                    SectionCard(contentPadding = PaddingValues(16.dp)) {
                        DateField("Payment date", invoice.paidDate ?: LocalDate.now(), vm::updatePaidDate, Modifier.fillMaxWidth())
                        invoice.paymentTransactionId?.let { paymentId ->
                            TextButton(onClick = { onOpenTransaction(paymentId) }) { Text("View payment transaction") }
                        }
                    }
                }

                state.booking?.let { booking ->
                    EntriesCard(booking)
                    TextButton(onClick = { onOpenTransaction(booking.id) }) { Text("View booking transaction") }
                }
            }
        }
    }

    if (choosingAccount) {
        if (state.paymentAccounts.isEmpty()) {
            MessageDialog("Mark as paid", "There are no other accounts to pay from. Create a cash or bank account first.") { choosingAccount = false }
        } else {
            AccountPickerSheet(
                title = "Pay from",
                accounts = state.paymentAccounts,
                onSelect = { account ->
                    choosingAccount = false
                    vm.pay(account.id, LocalDate.parse(paymentDate), paymentAmount.takeIf { it.isNotBlank() }?.let { Money.parse(it) })
                    paymentAmount = ""
                },
                onDismiss = { choosingAccount = false },
            )
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete invoice?",
            message = "This deletes the invoice and reverses its booking and payment transactions.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { confirmDelete = false; vm.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
    message?.let { MessageDialog("Something went wrong", it) { vm.message.value = null } }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
