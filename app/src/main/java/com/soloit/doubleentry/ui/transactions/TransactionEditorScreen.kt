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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.domain.Transaction
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.ui.TransactionEditorRoute
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.AccountPickerSheet
import com.soloit.doubleentry.ui.components.ConfirmDialog
import com.soloit.doubleentry.ui.components.DateField
import com.soloit.doubleentry.ui.components.Lines
import com.soloit.doubleentry.ui.components.LinesEditor
import com.soloit.doubleentry.ui.components.MoneyText
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.components.SectionHeader
import com.soloit.doubleentry.ui.components.StatusChip
import com.soloit.doubleentry.ui.theme.AppTheme

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
                periodDate = state.form.date,
                onPeriodChange = vm.lines::setPeriod,
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
