package com.soloit.doubleentry.data

import com.soloit.doubleentry.domain.Invoice
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.formatMedium
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** The daily reminder about unpaid invoices: which ones, and what it says. Each app shows it its own way. */
object InvoiceReminder {
    /** Unpaid invoices due within [daysBefore] days of [today] (overdue ones included), earliest first. */
    fun select(invoices: List<Invoice>, today: LocalDate, daysBefore: Int): List<Invoice> =
        invoices.filter { !it.isPaid && it.remaining > 0 && it.dueDate <= today.plusDays(daysBefore.toLong()) }.sortedBy { it.dueDate }

    /** The headline for [due] (title to invoice). */
    fun title(due: List<Pair<String, Invoice>>, today: LocalDate): String {
        val overdue = due.count { it.second.dueDate < today }
        return when {
            overdue == due.size -> "${due.size} overdue invoice${plural(due.size)}"
            overdue > 0 -> "${due.size} invoice${plural(due.size)} to pay · $overdue overdue"
            else -> "${due.size} invoice${plural(due.size)} due soon"
        }
    }

    /** One line per invoice: its title, what's left to pay and when it's due. */
    fun lines(due: List<Pair<String, Invoice>>, today: LocalDate): List<String> =
        due.map { (name, invoice) -> "$name · ${Money.format(invoice.remaining)} · ${dueText(invoice.dueDate, today)}" }

    /** The unpaid invoices of [invoices] to remind about, each with the title of its booking. */
    suspend fun due(invoices: InvoiceRepository, ledger: LedgerRepository, today: LocalDate, daysBefore: Int): List<Pair<String, Invoice>> =
        select(invoices.liveInvoices(), today, daysBefore).map { invoice ->
            val title = ledger.getTransaction(invoice.bookingTransactionId)?.title?.takeIf { it.isNotBlank() } ?: "Invoice ${invoice.invoiceDate}"
            title to invoice
        }

    internal fun dueText(dueDate: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(today, dueDate)
        return when {
            days < -1 -> "${-days} days overdue"
            days == -1L -> "due yesterday"
            days == 0L -> "due today"
            days == 1L -> "due tomorrow"
            else -> "due ${dueDate.formatMedium()}"
        }
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
