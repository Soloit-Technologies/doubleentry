package com.soloit.doubleentry.desktop.ui.accounts

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.unit.dp
import com.soloit.doubleentry.desktop.platform.FileDialogs
import com.soloit.doubleentry.desktop.ui.LocalApp
import com.soloit.doubleentry.desktop.ui.StatementImportRequest
import com.soloit.doubleentry.desktop.ui.components.CellText
import com.soloit.doubleentry.desktop.ui.components.DataTable
import com.soloit.doubleentry.desktop.ui.components.MoneyText
import com.soloit.doubleentry.desktop.ui.components.MonthSwitcher
import com.soloit.doubleentry.desktop.ui.components.PaneHeader
import com.soloit.doubleentry.desktop.ui.components.SearchBox
import com.soloit.doubleentry.desktop.ui.components.TableColumn
import com.soloit.doubleentry.desktop.ui.components.display
import com.soloit.doubleentry.desktop.ui.components.rememberViewModel
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.domain.label
import com.soloit.doubleentry.ui.accounts.AccountLedgerViewModel
import com.soloit.doubleentry.ui.accounts.LedgerRowUi
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Button
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.Icon
import io.github.composefluent.component.Text
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add
import io.github.composefluent.icons.regular.ArrowImport
import io.github.composefluent.icons.regular.Delete
import io.github.composefluent.icons.regular.Edit
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI
import java.time.LocalDate
import java.time.YearMonth

/** File types a bank statement comes in. */
val STATEMENT_EXTENSIONS = listOf("csv", "txt", "xlsx")

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun AccountLedgerPane(
    accountId: String,
    initialMonth: YearMonth,
    selectedTransaction: String?,
    onSelectTransaction: (String?) -> Unit,
    onEditAccount: () -> Unit,
) {
    val app = LocalApp.current
    val core = app.core
    val vm = rememberViewModel(accountId) { AccountLedgerViewModel(core.ledger, core.reconciliations, core.undo, accountId, initialMonth) }
    val state by vm.state.collectAsState()
    val message by vm.message.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var dropping by remember { mutableStateOf(false) }

    LaunchedEffect(message) { message?.let { app.showError("Account", it); vm.message.value = null } }

    fun import(file: File) {
        if (file.extension.lowercase() in STATEMENT_EXTENSIONS) {
            app.statementImport = StatementImportRequest(accountId, file)
        } else {
            app.showError("Import statement", "Drop a bank statement saved as CSV or Excel (.xlsx).")
        }
    }

    val dropTarget = remember(accountId) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dropping = true }
            override fun onExited(event: DragAndDropEvent) { dropping = false }
            override fun onEnded(event: DragAndDropEvent) { dropping = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dropping = false
                val files = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
                val file = files.firstOrNull()?.let { runCatching { File(URI(it)) }.getOrNull() } ?: return false
                import(file)
                return true
            }
        }
    }

    Box(
        Modifier.fillMaxSize().dragAndDropTarget(
            shouldStartDragAndDrop = { (it.dragData() as? DragData.FilesList) != null },
            target = dropTarget,
        ),
    ) {
        Column(Modifier.fillMaxSize()) {
            val account = state.account
            PaneHeader(
                account?.display() ?: "",
                subtitle = account?.let { a ->
                    listOfNotNull(
                        a.type.name,
                        if (state.searching) null else "${Money.format(state.opening)} → ${Money.format(state.closing)} in ${state.month.label()}",
                        state.reconciliation?.let { "reconciled through ${it.reconciliation.date.formatMedium()}" + if (it.inSync) "" else " (changed since)" },
                    ).joinToString(" · ")
                },
            ) {
                Button(onClick = { app.newTransaction(accountId, LocalDate.now().takeIf { YearMonth.from(it) == state.month } ?: state.month.atEndOfMonth()) }) {
                    Icon(Icons.Regular.Add, null); Text("Transaction")
                }
                Button(onClick = { FileDialogs.open(app.window, "Import bank statement", STATEMENT_EXTENSIONS)?.let(::import) }) {
                    Icon(Icons.Regular.ArrowImport, null); Text("Import")
                }
                Button(onClick = onEditAccount, iconOnly = true) { Icon(Icons.Regular.Edit, "Edit account") }
                Button(onClick = { scope.launch { if (vm.canDelete()) confirmDelete = true } }, iconOnly = true) { Icon(Icons.Regular.Delete, "Delete account") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!state.searching) MonthSwitcher(state.month, vm::setMonth)
                Box(Modifier.weight(1f))
                SearchBox(vm.query, { vm.query = it }, "Search every month", Modifier.widthIn(max = 280.dp).weight(1f, fill = false))
            }
            val columns = remember {
                listOf(
                    TableColumn<LedgerRowUi>("Date", width = 110.dp) { CellText(it.date.toString()) },
                    TableColumn("Title", weight = 1f) { CellText(it.title.ifBlank { "(no title)" }) },
                    TableColumn("Amount", width = 130.dp, alignEnd = true) { MoneyText(it.amount, signed = true) },
                    TableColumn("Balance", width = 130.dp, alignEnd = true) { MoneyText(it.balance) },
                )
            }
            DataTable(
                rows = state.rows,
                key = { it.transactionId },
                columns = columns,
                modifier = Modifier.weight(1f).padding(top = 8.dp),
                selected = selectedTransaction,
                onSelect = { onSelectTransaction(it.transactionId) },
                onOpen = { app.editTransaction(it.transactionId) },
                contextMenu = { row ->
                    listOf(
                        ContextMenuItem("Edit") { app.editTransaction(row.transactionId) },
                        ContextMenuItem("Duplicate") { app.duplicateTransaction(row.transactionId) },
                    )
                },
            )
            if (!state.loading && state.rows.isEmpty()) {
                Text(
                    if (state.searching) "Nothing matches the search." else "No transactions this month. Drop a bank statement here to import one.",
                    color = FluentTheme.colors.text.text.secondary,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
        if (dropping) {
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier.fillMaxSize().padding(8.dp)
                    .background(FluentTheme.colors.fillAccent.default.copy(alpha = 0.08f), shape)
                    .border(2.dp, FluentTheme.colors.fillAccent.default, shape),
                contentAlignment = Alignment.Center,
            ) { Text("Drop to import the statement into ${state.account?.name ?: "this account"}", style = FluentTheme.typography.subtitle) }
        }
    }

    if (confirmDelete) {
        ContentDialog(
            title = "Delete ${state.account?.name ?: "account"}?",
            visible = true,
            content = { Text("The account is removed from every device. You can undo this for a few seconds.") },
            primaryButtonText = "Delete",
            closeButtonText = "Cancel",
            onButtonClick = { button ->
                confirmDelete = false
                if (button == ContentDialogButton.Primary) vm.delete()
            },
        )
    }
}
