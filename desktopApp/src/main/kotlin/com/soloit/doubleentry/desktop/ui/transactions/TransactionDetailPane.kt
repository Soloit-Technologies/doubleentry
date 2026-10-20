package com.soloit.doubleentry.desktop.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soloit.doubleentry.desktop.ui.LocalApp
import com.soloit.doubleentry.desktop.ui.components.Card
import com.soloit.doubleentry.desktop.ui.components.EmptyState
import com.soloit.doubleentry.desktop.ui.components.HorizontalDivider
import com.soloit.doubleentry.desktop.ui.components.MoneyText
import com.soloit.doubleentry.desktop.ui.components.rememberViewModel
import com.soloit.doubleentry.desktop.ui.components.tabular
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.ui.transactions.TransactionDetailViewModel
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Button
import io.github.composefluent.component.Icon
import io.github.composefluent.component.Text
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Copy
import io.github.composefluent.icons.regular.Delete
import io.github.composefluent.icons.regular.Edit

/** The selected transaction: its lines and what can be done with it. */
@Composable
fun TransactionDetailPane(id: String?, onOpenAccount: (String) -> Unit) {
    if (id == null) {
        EmptyState("No transaction selected", "Select a transaction to see its lines. Double-click to edit it.")
        return
    }
    val app = LocalApp.current
    val core = app.core
    val vm = rememberViewModel(id) { TransactionDetailViewModel(core.ledger, core.recurring, core.undo, id) }
    val state by vm.state.collectAsState()
    val message by vm.message.collectAsState()
    LaunchedEffect(message) { message?.let { app.showError("Transaction", it); vm.message.value = null } }

    val tx = state.transaction
    if (tx == null) {
        if (!state.loading) EmptyState("Transaction not found", "It may have been deleted on another device.")
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tx.title.ifBlank { "(no title)" }, style = FluentTheme.typography.subtitle)
        Text(tx.date.formatMedium(), color = FluentTheme.colors.text.text.secondary)
        MoneyText(tx.totalDebits, style = FluentTheme.typography.title)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { app.editTransaction(id) }, disabled = state.isAutomatic) { Icon(Icons.Regular.Edit, null); Text("Edit") }
            Button(onClick = { app.duplicateTransaction(id) }) { Icon(Icons.Regular.Copy, null); Text("Duplicate") }
            Button(onClick = { vm.delete() }, disabled = state.isAutomatic) { Icon(Icons.Regular.Delete, null); Text("Delete") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("Account", style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.secondary, modifier = Modifier.weight(1f))
                    Text("Debit", style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.secondary, modifier = Modifier.width(90.dp))
                    Text("Credit", style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.secondary, modifier = Modifier.width(90.dp))
                }
                tx.entries.sortedBy { it.type }.forEach { entry ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenAccount(entry.account.id) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.account.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(entry.account.number.toString(), style = FluentTheme.typography.caption.tabular(), color = FluentTheme.colors.text.text.secondary)
                        }
                        Column(Modifier.width(90.dp)) { if (entry.type == EntryType.Debit) MoneyText(entry.amount) }
                        Column(Modifier.width(90.dp)) { if (entry.type == EntryType.Credit) MoneyText(entry.amount) }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("Total", style = FluentTheme.typography.bodyStrong, modifier = Modifier.weight(1f))
                    Column(Modifier.width(90.dp)) { MoneyText(tx.totalDebits, style = FluentTheme.typography.bodyStrong) }
                    Column(Modifier.width(90.dp)) { MoneyText(tx.totalCredits, style = FluentTheme.typography.bodyStrong) }
                }
            }
        }
        if (tx.description.isNotBlank()) {
            Text("Description", style = FluentTheme.typography.bodyStrong)
            Text(tx.description)
        }
        state.recurringRule?.let { Note("Posted by the recurring transaction “${it.title}” (${it.frequency.label.lowercase()}).") }
        if (state.isDepreciation) Note("A depreciation write-off. It changes with its asset.")
        state.periodSource?.let { Note("Moves a share of “${it.title}” (${it.date.formatMedium()}) into this month. It changes with that transaction.") }
        state.costPeriods?.let { Note("Some lines are spread over ${it.lines.flatMap { l -> l.shares }.map { s -> s.month }.distinct().size} months.") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Note(text: String) {
    Card(Modifier.fillMaxWidth()) { Text(text, modifier = Modifier.padding(12.dp), color = FluentTheme.colors.text.text.secondary) }
}
