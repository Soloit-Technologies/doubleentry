package com.soloit.doubleentry.desktop.ui.transactions

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.soloit.doubleentry.desktop.ui.LocalApp
import com.soloit.doubleentry.desktop.ui.components.CellText
import com.soloit.doubleentry.desktop.ui.components.DataTable
import com.soloit.doubleentry.desktop.ui.components.Layer
import com.soloit.doubleentry.desktop.ui.components.MoneyText
import com.soloit.doubleentry.desktop.ui.components.MonthSwitcher
import com.soloit.doubleentry.desktop.ui.components.PaneHeader
import com.soloit.doubleentry.desktop.ui.components.SearchBox
import com.soloit.doubleentry.desktop.ui.components.Splitter
import com.soloit.doubleentry.desktop.ui.components.TableColumn
import com.soloit.doubleentry.desktop.ui.components.rememberPaneWidth
import com.soloit.doubleentry.desktop.ui.components.rememberViewModel
import com.soloit.doubleentry.domain.relativeDayLabel
import com.soloit.doubleentry.ui.transactions.TransactionRowUi
import com.soloit.doubleentry.ui.transactions.TransactionsViewModel
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Icon
import io.github.composefluent.component.Text
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add

/** Every transaction of a month (or matching a search) | the selected one. */
@Composable
fun TransactionsPage() {
    val app = LocalApp.current
    val core = app.core
    val vm = rememberViewModel { TransactionsViewModel(core.ledger) }
    val state by vm.state.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    var openAccount by remember { mutableStateOf<String?>(null) }
    val detailWidth = rememberPaneWidth(380.dp, min = 300.dp)
    val rows = state.days.flatMap { it.second }

    Row(Modifier.fillMaxSize().padding(end = 8.dp, bottom = 8.dp)) {
        Layer(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize()) {
                PaneHeader(
                    "Activity",
                    subtitle = if (state.searching) "${state.count} matching" else "${state.count} transactions",
                ) {
                    AccentButton(onClick = { app.newTransaction() }) { Icon(Icons.Regular.Add, null); Text("New transaction") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!state.searching) MonthSwitcher(state.month, vm::setMonth)
                    Box(Modifier.weight(1f))
                    SearchBox(vm.query, { vm.query = it }, "Search all transactions", Modifier.width(300.dp), focus = app.searchFocus)
                }
                val columns = remember {
                    listOf(
                        TableColumn<TransactionRowUi>("Date", width = 150.dp) { CellText(it.date.relativeDayLabel(), secondary = true) },
                        TableColumn("Title", weight = 1f) { CellText(it.title.ifBlank { "(no title)" }) },
                        TableColumn("From → To", weight = 1f) { CellText(it.flow, secondary = true) },
                        TableColumn("Amount", width = 130.dp, alignEnd = true) { MoneyText(it.amount) },
                    )
                }
                DataTable(
                    rows = rows,
                    key = { it.id },
                    columns = columns,
                    modifier = Modifier.weight(1f).padding(top = 8.dp),
                    selected = selected,
                    onSelect = { selected = it.id },
                    onOpen = { app.editTransaction(it.id) },
                    contextMenu = { row ->
                        listOf(
                            ContextMenuItem("Edit") { app.editTransaction(row.id) },
                            ContextMenuItem("Duplicate") { app.duplicateTransaction(row.id) },
                        )
                    },
                )
                if (!state.loading && rows.isEmpty()) {
                    Text(
                        if (state.searching) "Nothing matches the search." else "No transactions this month. Press Ctrl+N to add one.",
                        color = FluentTheme.colors.text.text.secondary,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
        Splitter(detailWidth, growsRight = false)
        Layer(Modifier.width(detailWidth.value).fillMaxHeight()) {
            TransactionDetailPane(selected, onOpenAccount = { openAccount = it })
        }
    }
}
