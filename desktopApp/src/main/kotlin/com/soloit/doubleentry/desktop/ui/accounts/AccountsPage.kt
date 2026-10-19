package com.soloit.doubleentry.desktop.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.soloit.doubleentry.desktop.ui.LocalApp
import com.soloit.doubleentry.desktop.ui.components.Card
import com.soloit.doubleentry.desktop.ui.components.EmptyState
import com.soloit.doubleentry.desktop.ui.components.Layer
import com.soloit.doubleentry.desktop.ui.components.MoneyText
import com.soloit.doubleentry.desktop.ui.components.MonthSwitcher
import com.soloit.doubleentry.desktop.ui.components.PaneHeader
import com.soloit.doubleentry.desktop.ui.components.SearchBox
import com.soloit.doubleentry.desktop.ui.components.Splitter
import com.soloit.doubleentry.desktop.ui.components.TableRow
import com.soloit.doubleentry.desktop.ui.components.rememberPaneWidth
import com.soloit.doubleentry.desktop.ui.components.rememberViewModel
import com.soloit.doubleentry.desktop.ui.components.tabular
import com.soloit.doubleentry.desktop.ui.transactions.TransactionDetailPane
import com.soloit.doubleentry.ui.accounts.AccountsUiState
import com.soloit.doubleentry.ui.accounts.AccountsViewModel
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Button
import io.github.composefluent.component.Icon
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.Text
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add
import io.github.composefluent.icons.regular.Checkmark
import io.github.composefluent.icons.regular.Warning

/** Accounts | the selected account's ledger | the selected transaction. */
@Composable
fun AccountsPage() {
    val app = LocalApp.current
    val core = app.core
    val vm = rememberViewModel { AccountsViewModel(core.ledger, core.reconciliations, core.startupComplete) }
    val state by vm.state.collectAsState()
    var selectedAccount by remember { mutableStateOf<String?>(null) }
    var selectedTransaction by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<AccountEditRequest?>(null) }
    val listWidth = rememberPaneWidth(320.dp, max = 520.dp)
    val detailWidth = rememberPaneWidth(340.dp, min = 280.dp)

    val rows = state.sections.flatMap { it.rows }
    LaunchedEffect(rows) {
        if (rows.none { it.account.id == selectedAccount }) selectedAccount = rows.firstOrNull()?.account?.id
    }

    if (!state.loading && !state.hasAccounts && state.startupComplete) {
        Layer(Modifier.fillMaxSize().padding(end = 8.dp, bottom = 8.dp)) {
            EmptyState(
                "No accounts yet",
                "Start with a ready-made account plan for personal finances, or create your accounts one by one.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccentButton(onClick = vm::seedDefaultPlan, disabled = state.seeding) { Text("Use the default account plan") }
                    Button(onClick = { editing = AccountEditRequest(null) }) { Text("New account") }
                }
            }
        }
    } else {
        Row(Modifier.fillMaxSize().padding(end = 8.dp, bottom = 8.dp)) {
            Layer(Modifier.width(listWidth.value).fillMaxHeight()) {
                AccountList(state, vm, selectedAccount, onSelect = { selectedAccount = it; selectedTransaction = null }, onNew = { editing = AccountEditRequest(null) })
            }
            Splitter(listWidth, growsRight = true)
            Layer(Modifier.weight(1f).fillMaxHeight()) {
                val id = selectedAccount
                if (id == null) {
                    EmptyState("No account selected", "Choose an account to see its transactions.")
                } else {
                    AccountLedgerPane(
                        accountId = id,
                        initialMonth = state.month,
                        selectedTransaction = selectedTransaction,
                        onSelectTransaction = { selectedTransaction = it },
                        onEditAccount = { editing = AccountEditRequest(id) },
                    )
                }
            }
            Splitter(detailWidth, growsRight = false)
            Layer(Modifier.width(detailWidth.value).fillMaxHeight()) {
                TransactionDetailPane(selectedTransaction, onOpenAccount = { selectedAccount = it; selectedTransaction = null })
            }
        }
    }

    editing?.let { request -> AccountEditorDialog(request.accountId, onDismiss = { editing = null }) }
}

/** The account editor: a new account ([accountId] null) or an existing one. */
data class AccountEditRequest(val accountId: String?)

@Composable
private fun AccountList(state: AccountsUiState, vm: AccountsViewModel, selected: String?, onSelect: (String) -> Unit, onNew: () -> Unit) {
    val app = LocalApp.current
    Column(Modifier.fillMaxSize()) {
        PaneHeader("Accounts") {
            Button(onClick = onNew, iconOnly = true) { Icon(Icons.Regular.Add, "New account") }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MonthSwitcher(state.month, vm::setMonth, Modifier.align(Alignment.CenterHorizontally))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Net worth · ${state.month.month.name.lowercase().replaceFirstChar { it.uppercase() }}", style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.secondary)
                    MoneyText(state.netWorth, style = FluentTheme.typography.title)
                    MoneyText(state.netWorthChange, signed = true, style = FluentTheme.typography.caption)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Figure("Income", state.income, Modifier.weight(1f))
                        Figure("Spending", state.spending, Modifier.weight(1f))
                        Figure("Saved", state.income - state.spending, Modifier.weight(1f))
                    }
                }
            }
            SearchBox(state.query, vm::setQuery, "Search accounts", Modifier.fillMaxWidth(), focus = app.searchFocus)
        }
        Spacer(Modifier.height(8.dp))
        if (state.loading) {
            ProgressRing(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState()) {
            state.sections.forEach { section ->
                item(key = "header-${section.type}") {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)) {
                        Text(section.type.pluralLabel, style = FluentTheme.typography.bodyStrong, modifier = Modifier.weight(1f))
                        MoneyText(section.total, style = FluentTheme.typography.bodyStrong)
                    }
                }
                items(section.rows, key = { it.account.id }) { row ->
                    TableRow(selected = row.account.id == selected, onClick = { onSelect(row.account.id) }) {
                        Text(row.account.number.toString(), modifier = Modifier.width(52.dp), color = FluentTheme.colors.text.text.secondary, style = FluentTheme.typography.body.tabular())
                        Text(row.account.name, modifier = Modifier.weight(1f), maxLines = 1)
                        state.reconciliations[row.account.id]?.let { status ->
                            Icon(
                                if (status.inSync) Icons.Regular.Checkmark else Icons.Regular.Warning,
                                contentDescription = if (status.inSync) "Reconciled" else "Changed since reconciled",
                                modifier = Modifier.padding(end = 6.dp).width(14.dp),
                                tint = FluentTheme.colors.text.text.secondary,
                            )
                        }
                        MoneyText(row.closing)
                    }
                }
            }
        }
    }
}

@Composable
private fun Figure(label: String, cents: Long, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.secondary)
        MoneyText(cents, style = FluentTheme.typography.bodyStrong)
    }
}
