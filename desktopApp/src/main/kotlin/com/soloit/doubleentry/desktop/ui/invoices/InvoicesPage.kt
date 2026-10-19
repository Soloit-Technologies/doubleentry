package com.soloit.doubleentry.desktop.ui.invoices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.soloit.doubleentry.desktop.ui.AppColors
import com.soloit.doubleentry.desktop.ui.LocalApp
import com.soloit.doubleentry.desktop.ui.components.AccountPicker
import com.soloit.doubleentry.desktop.ui.components.Card
import com.soloit.doubleentry.desktop.ui.components.CellText
import com.soloit.doubleentry.desktop.ui.components.DataTable
import com.soloit.doubleentry.desktop.ui.components.DateField
import com.soloit.doubleentry.desktop.ui.components.DetailRow
import com.soloit.doubleentry.desktop.ui.components.EmptyState
import com.soloit.doubleentry.desktop.ui.components.Layer
import com.soloit.doubleentry.desktop.ui.components.MoneyText
import com.soloit.doubleentry.desktop.ui.components.PaneHeader
import com.soloit.doubleentry.desktop.ui.components.SearchBox
import com.soloit.doubleentry.desktop.ui.components.Segmented
import com.soloit.doubleentry.desktop.ui.components.Splitter
import com.soloit.doubleentry.desktop.ui.components.TableColumn
import com.soloit.doubleentry.desktop.ui.components.display
import com.soloit.doubleentry.desktop.ui.components.rememberPaneWidth
import com.soloit.doubleentry.desktop.ui.components.rememberViewModel
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.ui.invoices.DueTone
import com.soloit.doubleentry.ui.invoices.InvoiceDetailViewModel
import com.soloit.doubleentry.ui.invoices.InvoiceRowUi
import com.soloit.doubleentry.ui.invoices.InvoiceTab
import com.soloit.doubleentry.ui.invoices.InvoicesViewModel
import com.soloit.doubleentry.ui.invoices.dueStatus
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Button
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField
import java.time.LocalDate

@Composable
fun InvoicesPage() {
    val app = LocalApp.current
    val core = app.core
    val vm = rememberViewModel { InvoicesViewModel(core.invoices, core.ledger) }
    val state by vm.state.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    val detailWidth = rememberPaneWidth(400.dp, min = 320.dp)

    Row(Modifier.fillMaxSize().padding(end = 8.dp, bottom = 8.dp)) {
        Layer(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize()) {
                PaneHeader("Invoices", subtitle = "${Money.format(state.outstanding)} outstanding · ${state.overdueCount} overdue")
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Segmented(listOf("Unpaid (${state.unpaidCount})", "Paid (${state.paidCount})"), state.tab.ordinal, { vm.setTab(InvoiceTab.entries[it]) })
                    Box(Modifier.weight(1f))
                    SearchBox(state.query, vm::setQuery, "Search invoices", Modifier.width(280.dp), focus = app.searchFocus)
                }
                val columns = remember {
                    listOf(
                        TableColumn<InvoiceRowUi>("Invoice", weight = 1f) { CellText(it.title) },
                        TableColumn("Date", width = 110.dp) { CellText(it.invoice.invoiceDate.toString(), secondary = true) },
                        TableColumn("Status", width = 200.dp) { row ->
                            val status = row.invoice.dueStatus()
                            Text(status.text, color = toneColor(status.tone), maxLines = 1)
                        },
                        TableColumn("Amount", width = 120.dp, alignEnd = true) { MoneyText(it.invoice.amountDue) },
                        TableColumn("Left to pay", width = 120.dp, alignEnd = true) { MoneyText(it.invoice.remaining) },
                    )
                }
                DataTable(
                    rows = state.rows,
                    key = { it.invoice.id },
                    columns = columns,
                    modifier = Modifier.weight(1f).padding(top = 8.dp),
                    selected = selected,
                    onSelect = { selected = it.invoice.id },
                )
                if (!state.loading && state.rows.isEmpty()) {
                    Text(
                        "No invoices here. Invoices are created in the Android app for now; they appear here once synced.",
                        color = FluentTheme.colors.text.text.secondary,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
        Splitter(detailWidth, growsRight = false)
        Layer(Modifier.width(detailWidth.value).fillMaxHeight()) {
            val id = selected
            if (id == null) EmptyState("No invoice selected", "Select an invoice to see it and record a payment.") else InvoiceDetail(id, onClosed = { selected = null })
        }
    }
}

@Composable
private fun toneColor(tone: DueTone): Color = when (tone) {
    DueTone.Paid -> AppColors.positive
    DueTone.Overdue -> AppColors.negative
    DueTone.Soon -> FluentTheme.colors.system.caution
    DueTone.Later -> FluentTheme.colors.text.text.secondary
}

@Composable
private fun InvoiceDetail(id: String, onClosed: () -> Unit) {
    val app = LocalApp.current
    val core = app.core
    val vm = rememberViewModel(id) { InvoiceDetailViewModel(core.invoices, core.ledger, core.settings, core.undo, id) }
    val state by vm.state.collectAsState()
    val message by vm.message.collectAsState()
    val closed by vm.closed.collectAsState()
    LaunchedEffect(message) { message?.let { app.showError("Invoice", it); vm.message.value = null } }
    LaunchedEffect(closed) { if (closed) onClosed() }
    val invoice = state.invoice ?: return
    var payFrom by remember(id) { mutableStateOf<String?>(null) }
    var payDate by remember(id) { mutableStateOf(LocalDate.now()) }
    var payAmount by remember(id) { mutableStateOf("") }
    LaunchedEffect(state.paymentAccounts) { if (payFrom == null) payFrom = state.paymentAccounts.firstOrNull()?.id }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(state.booking?.title ?: "Invoice", style = FluentTheme.typography.subtitle)
        val status = invoice.dueStatus()
        Text(status.text, color = toneColor(status.tone))
        MoneyText(invoice.remaining, style = FluentTheme.typography.title)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                DetailRow("Amount", Money.format(invoice.amountDue))
                DetailRow("Invoice date", invoice.invoiceDate.formatMedium())
                DetailRow("Due", invoice.dueDate.formatMedium())
                invoice.paidDate?.let { DetailRow("Paid", it.formatMedium()) }
                state.paidFrom?.let { DetailRow("Paid from", it.display()) }
                invoice.partialPayments.forEach { DetailRow("Payment ${it.date.formatMedium()}", Money.format(it.amount)) }
            }
        }
        if (!invoice.isPaid) {
            Text("Record a payment", style = FluentTheme.typography.bodyStrong)
            AccountPicker(state.paymentAccounts, payFrom, { payFrom = it.id }, Modifier.fillMaxWidth(), header = "Paid from")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField(payDate, { payDate = it }, Modifier.weight(1f), header = "Date")
                TextField(payAmount, { payAmount = it }, Modifier.weight(1f), singleLine = true, header = { Text("Amount") }, placeholder = { Text(Money.toInput(invoice.remaining)) })
            }
            AccentButton(onClick = {
                val account = payFrom ?: return@AccentButton app.showError("Invoice", "Choose the account the invoice was paid from.")
                vm.pay(account, payDate, Money.parse(payAmount))
                payAmount = ""
            }) { Text("Record payment") }
        }
        Button(onClick = { vm.delete() }) { Text("Delete invoice") }
    }
}
