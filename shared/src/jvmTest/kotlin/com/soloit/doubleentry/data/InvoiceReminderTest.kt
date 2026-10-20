package com.soloit.doubleentry.data

import com.soloit.doubleentry.domain.Invoice
import com.soloit.doubleentry.domain.InvoicePayment
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class InvoiceReminderTest {
    private val today = LocalDate.of(2026, 10, 2)

    private fun invoice(id: String, due: LocalDate, paid: Boolean = false, partial: Long = 0) = Invoice(
        id = id,
        amountDue = 100_00,
        invoiceDate = due.minusDays(30),
        dueDate = due,
        bookingTransactionId = "b-$id",
        outstandingAccountId = "payables",
        outstandingIsCredit = true,
        isPaid = paid,
        paidDate = null,
        paymentTransactionId = null,
        paidFromAccountId = null,
        partialPayments = if (partial > 0) listOf(InvoicePayment("p-$id", due, partial, "bank")) else emptyList(),
    )

    @Test
    fun selectsUnpaidInvoicesDueSoonOrOverdueEarliestFirst() {
        val invoices = listOf(
            invoice("later", today.plusDays(10)),
            invoice("soon", today.plusDays(3)),
            invoice("overdue", today.minusDays(4)),
            invoice("paid", today.plusDays(1), paid = true),
            invoice("settled-by-instalments", today, partial = 100_00),
        )
        assertEquals(listOf("overdue", "soon"), InvoiceReminder.select(invoices, today, daysBefore = 3).map { it.id })
        assertEquals(listOf("overdue"), InvoiceReminder.select(invoices, today, daysBefore = 1).map { it.id })
    }

    @Test
    fun dueTextIsRelative() {
        assertEquals("due today", InvoiceReminder.dueText(today, today))
        assertEquals("due tomorrow", InvoiceReminder.dueText(today.plusDays(1), today))
        assertEquals("due yesterday", InvoiceReminder.dueText(today.minusDays(1), today))
        assertEquals("4 days overdue", InvoiceReminder.dueText(today.minusDays(4), today))
    }
}
