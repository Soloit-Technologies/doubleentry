package com.soloit.doubleentry.ui.invoices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.InvoiceRepository
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.data.UndoManager
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.Invoice
import com.soloit.doubleentry.domain.Transaction
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
