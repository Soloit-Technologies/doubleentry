package com.soloit.doubleentry.desktop.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.soloit.doubleentry.desktop.ui.AppColors
import com.soloit.doubleentry.desktop.ui.LocalApp
import com.soloit.doubleentry.desktop.ui.WindowContent
import com.soloit.doubleentry.desktop.ui.components.AccountPicker
import com.soloit.doubleentry.desktop.ui.components.Card
import com.soloit.doubleentry.desktop.ui.components.DateField
import com.soloit.doubleentry.desktop.ui.components.MoneyText
import com.soloit.doubleentry.desktop.ui.components.rememberViewModel
import com.soloit.doubleentry.desktop.ui.components.tabular
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.ui.TransactionEditorRoute
import com.soloit.doubleentry.ui.components.Lines
import com.soloit.doubleentry.ui.transactions.TransactionEditorViewModel
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Button
import io.github.composefluent.component.Icon
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add
import io.github.composefluent.icons.regular.Dismiss
import java.awt.Dimension

/**
 * Entering or editing a transaction, in a window of its own. Keyboard first: Tab moves through
 * the fields, an account is picked by typing its number or name, an empty amount fills in what
 * balances the entry, Ctrl+Enter saves and Esc closes.
 */
@Composable
fun TransactionEditorWindow(route: TransactionEditorRoute, onClose: () -> Unit) {
    val app = LocalApp.current
    val core = app.core
    val vm = rememberViewModel(route) { TransactionEditorViewModel(core.ledger, core.reconciliations, route) }
    val state by vm.state.collectAsState()
    val title = when {
        route.editId != null -> "Edit transaction"
        route.duplicateId != null -> "Duplicate transaction"
        else -> "New transaction"
    }
    DialogWindow(
        onCloseRequest = onClose,
        title = title,
        state = rememberDialogState(position = WindowPosition(Alignment.Center), size = DpSize(820.dp, 640.dp)),
        onPreviewKeyEvent = { e ->
            when {
                e.type != KeyEventType.KeyDown -> false
                e.key == Key.Escape -> { onClose(); true }
                e.isCtrlPressed && (e.key == Key.Enter || e.key == Key.S) -> { if (state.canSave) vm.save(); true }
                else -> false
            }
        },
    ) {
        window.minimumSize = Dimension(640, 480)
        WindowContent(window, FluentTheme.colors.darkMode) { TransactionEditor(vm, onClose) }
    }
}

/** The editor's fields and buttons; [TransactionEditorWindow] puts them in a window. */
@Composable
fun TransactionEditor(vm: TransactionEditorViewModel, onClose: () -> Unit) {
    val state by vm.state.collectAsState()
    LaunchedEffect(state.status.done) { if (state.status.done) onClose() }
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { titleFocus.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    TextField(
                        state.form.title,
                        vm::setTitle,
                        Modifier.fillMaxWidth().focusRequester(titleFocus).testTag("title"),
                        singleLine = true,
                        header = { Text("Title") },
                    )
                }
                DateField(state.form.date, vm::setDate, Modifier.width(170.dp), header = "Date")
            }
            if (state.suggestions.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(6.dp)) {
                        Text("Earlier transactions", style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.secondary, modifier = Modifier.padding(6.dp))
                        state.suggestions.forEach { tx ->
                            SubtleButton(onClick = { vm.applySuggestion(tx) }, modifier = Modifier.fillMaxWidth()) {
                                Text(tx.title, modifier = Modifier.weight(1f))
                                Text(tx.date.formatMedium(), color = FluentTheme.colors.text.text.secondary)
                                MoneyText(tx.totalDebits, modifier = Modifier.width(100.dp))
                            }
                        }
                    }
                }
            }
            TextField(state.form.description, vm::setDescription, Modifier.fillMaxWidth(), header = { Text("Description (optional)") }, maxLines = 3)

            Text("Lines", style = FluentTheme.typography.bodyStrong)
            Row(Modifier.padding(horizontal = 4.dp)) {
                Text("Account", modifier = Modifier.weight(1f), style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.secondary)
                Text("Debit", modifier = Modifier.width(140.dp).padding(start = 8.dp), style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.secondary)
                Text("Credit", modifier = Modifier.width(140.dp).padding(start = 8.dp), style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.secondary)
                Spacer(Modifier.width(40.dp))
            }
            state.lines.forEachIndexed { index, line ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountPicker(
                        state.accounts,
                        line.accountId,
                        onSelect = { vm.lines.setAccount(line.key, it.id) },
                        modifier = Modifier.weight(1f).testTag("account-$index"),
                        enabled = !line.locked,
                    )
                    AmountField(line.debit, { vm.lines.setDebit(line.key, it) }, onFocus = { vm.lines.autoFillDebit(line.key) }, enabled = !line.locked, tag = "debit-$index")
                    AmountField(line.credit, { vm.lines.setCredit(line.key, it) }, onFocus = { vm.lines.autoFillCredit(line.key) }, enabled = !line.locked, tag = "credit-$index")
                    SubtleButton(onClick = { vm.lines.remove(line.key) }, iconOnly = true, disabled = line.locked || state.lines.size <= 2) {
                        Icon(Icons.Regular.Dismiss, "Remove line")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = vm.lines::add) { Icon(Icons.Regular.Add, null); Text("Add line") }
                Spacer(Modifier.weight(1f))
                Balance(state.lines)
            }
            state.reconciledWarnings.forEach { Text(it, color = FluentTheme.colors.system.caution) }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.status.error.orEmpty(), color = FluentTheme.colors.system.critical, modifier = Modifier.weight(1f))
            Text("Ctrl+Enter to save", style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.tertiary)
            Button(onClick = onClose) { Text("Cancel") }
            AccentButton(onClick = vm::save, disabled = !state.canSave) { Text("Save") }
        }
    }
}

@Composable
private fun AmountField(value: String, onValueChange: (String) -> Unit, onFocus: () -> Unit, enabled: Boolean, tag: String) {
    TextField(
        value,
        onValueChange,
        modifier = Modifier.width(140.dp).testTag(tag).onFocusChanged { if (it.isFocused) onFocus() },
        enabled = enabled,
        singleLine = true,
        isClearable = false,
        placeholder = { Text("0.00", style = FluentTheme.typography.body.tabular()) },
    )
}

@Composable
private fun Balance(lines: List<com.soloit.doubleentry.ui.components.LineInput>) {
    val (debits, credits) = Lines.totals(lines)
    if (debits == 0L && credits == 0L) return
    val text = when {
        debits == credits -> "Balanced · ${Money.format(debits)}"
        debits > credits -> "Debits exceed credits by ${Money.format(debits - credits)}"
        else -> "Credits exceed debits by ${Money.format(credits - debits)}"
    }
    Text(text, color = if (debits == credits) AppColors.positive else FluentTheme.colors.system.caution, style = FluentTheme.typography.body.tabular())
}
