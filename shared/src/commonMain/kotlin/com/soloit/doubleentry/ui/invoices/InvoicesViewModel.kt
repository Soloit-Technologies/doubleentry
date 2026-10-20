package com.soloit.doubleentry.ui.invoices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.InvoiceRepository
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.domain.Invoice
import com.soloit.doubleentry.domain.formatMedium
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class InvoiceRowUi(val invoice: Invoice, val title: String)

enum class InvoiceTab { Unpaid, Paid }

data class InvoicesUiState(
    val loading: Boolean = true,
    val tab: InvoiceTab = InvoiceTab.Unpaid,
    val query: String = "",
    val rows: List<InvoiceRowUi> = emptyList(),
    val unpaidCount: Int = 0,
    val paidCount: Int = 0,
    val outstanding: Long = 0,
    val overdueCount: Int = 0,
)

enum class DueTone { Paid, Overdue, Soon, Later }

data class DueStatus(val text: String, val tone: DueTone)

fun Invoice.dueStatus(today: LocalDate = LocalDate.now()): DueStatus {
    if (isPaid) return DueStatus(paidDate?.let { "Paid ${it.formatMedium()}" } ?: "Paid", DueTone.Paid)
    val days = ChronoUnit.DAYS.between(today, dueDate)
    return when {
        days < 0 -> DueStatus("Overdue by ${-days} day${if (days == -1L) "" else "s"}", DueTone.Overdue)
        days == 0L -> DueStatus("Due today", DueTone.Soon)
        days <= 7 -> DueStatus("Due in $days day${if (days == 1L) "" else "s"}", DueTone.Soon)
        else -> DueStatus("Due ${dueDate.formatMedium()}", DueTone.Later)
    }
}

class InvoicesViewModel(invoices: InvoiceRepository, private val ledger: LedgerRepository) : ViewModel() {
    private val tab = MutableStateFlow(InvoiceTab.Unpaid)
    private val query = MutableStateFlow("")

    private val rows = combine(invoices.invoices, ledger.changes) { list, _ -> list }.mapLatest { list ->
        list.map { inv ->
            val title = ledger.getTransaction(inv.bookingTransactionId)?.title?.takeIf { it.isNotBlank() } ?: "Invoice ${inv.invoiceDate}"
            InvoiceRowUi(inv, title)
        }
    }

    val state: StateFlow<InvoicesUiState> = combine(rows, tab, query) { all, t, q ->
        val today = LocalDate.now()
        val unpaid = all.filter { !it.invoice.isPaid }
        InvoicesUiState(
            loading = false,
            tab = t,
            query = q,
            rows = all
                .filter { it.invoice.isPaid == (t == InvoiceTab.Paid) }
                .filter { q.isBlank() || it.title.contains(q.trim(), ignoreCase = true) }
                .let { if (t == InvoiceTab.Paid) it.sortedByDescending { r -> r.invoice.paidDate ?: r.invoice.dueDate } else it },
            unpaidCount = unpaid.size,
            paidCount = all.size - unpaid.size,
            outstanding = unpaid.sumOf { it.invoice.remaining },
            overdueCount = unpaid.count { it.invoice.dueDate < today },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InvoicesUiState())

    fun setTab(value: InvoiceTab) { tab.value = value }
    fun setQuery(value: String) { query.value = value }
}
