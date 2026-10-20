package com.soloit.doubleentry.desktop.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.soloit.doubleentry.desktop.ui.AppColors
import com.soloit.doubleentry.desktop.ui.LocalApp
import com.soloit.doubleentry.desktop.ui.StatementImportRequest
import com.soloit.doubleentry.desktop.ui.WindowContent
import com.soloit.doubleentry.desktop.ui.components.AccountPicker
import com.soloit.doubleentry.desktop.ui.components.CellText
import com.soloit.doubleentry.desktop.ui.components.DataTable
import com.soloit.doubleentry.desktop.ui.components.EmptyState
import com.soloit.doubleentry.desktop.ui.components.Layer
import com.soloit.doubleentry.desktop.ui.components.MoneyText
import com.soloit.doubleentry.desktop.ui.components.TableColumn
import com.soloit.doubleentry.desktop.ui.components.rememberViewModel
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.StatementColumns
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.ui.accounts.NewRowUi
import com.soloit.doubleentry.ui.accounts.StatementFile
import com.soloit.doubleentry.ui.accounts.StatementImportUiState
import com.soloit.doubleentry.ui.accounts.StatementImportViewModel
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Button
import io.github.composefluent.component.CheckBox
import io.github.composefluent.component.ComboBox
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.Text
import java.awt.Dimension
import java.io.File
import java.io.InputStream

/** A statement file on disk. */
class DiskStatementFile(private val file: File) : StatementFile {
    override val name: String get() = file.name
    override fun open(): InputStream? = file.takeIf { it.isFile }?.inputStream()
}

/**
 * Importing a bank statement, in a wide window of its own: every new line of the file in a table,
 * each booked against an account (guessed from earlier bookings with the same text), ticked to import.
 */
@Composable
fun StatementImportWindow(request: StatementImportRequest, onClose: () -> Unit) {
    val core = LocalApp.current.core
    val vm = rememberViewModel(request) {
        StatementImportViewModel(core.ledger, core.invoices, core.statementImports, core.preferences, core.undo, request.accountId, DiskStatementFile(request.file))
    }

    DialogWindow(
        onCloseRequest = onClose,
        title = "Import ${request.file.name}",
        state = rememberDialogState(position = WindowPosition(Alignment.Center), size = DpSize(1100.dp, 760.dp)),
        onPreviewKeyEvent = { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onClose(); true } else false },
    ) {
        window.minimumSize = Dimension(800, 520)
        WindowContent(window, FluentTheme.colors.darkMode) { StatementImport(vm, onClose) }
    }
}

/** The import's table and controls; [StatementImportWindow] puts them in a window. */
@Composable
fun StatementImport(vm: StatementImportViewModel, onClose: () -> Unit) {
    val state by vm.state.collectAsState()
    LaunchedEffect(state.done) { if (state.done) onClose() }
    when {
        state.loading -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { ProgressRing() }
        state.error != null -> EmptyState("The file can't be imported", state.error.orEmpty()) { Button(onClick = onClose) { Text("Close") } }
        else -> ImportContent(state, vm, onClose)
    }
}

@Composable
private fun ImportContent(state: StatementImportUiState, vm: StatementImportViewModel, onClose: () -> Unit) {
    var selected by remember { mutableStateOf<Int?>(null) }
    var bulkAccount by remember { mutableStateOf<String?>(null) }
    val others = state.accounts.filter { it.id != state.account?.id }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.fileName ?: "Bank statement", style = FluentTheme.typography.subtitle)
                val period = if (state.from != null && state.to != null) "${state.from!!.formatMedium()} – ${state.to!!.formatMedium()} · " else ""
                Text(
                    "Into ${state.account?.name.orEmpty()} · $period${state.lineCount} lines · ${state.matched.size} already in the ledger · ${state.newRows.size} new" +
                        if (state.skipped > 0) " · ${state.skipped} rows skipped" else "",
                    color = FluentTheme.colors.text.text.secondary,
                )
            }
            BalanceCheck(state)
        }
        Columns(state, vm::setColumns)

        Layer(Modifier.weight(1f).fillMaxWidth()) {
            val columns = remember(others) {
                listOf(
                    TableColumn<NewRowUi>("", width = 44.dp) { row -> CheckBox(row.included, onCheckStateChange = { vm.toggle(row.row) }) },
                    TableColumn("Date", width = 110.dp) { CellText(it.date.toString()) },
                    TableColumn("Text", weight = 1.2f) { CellText(it.text) },
                    TableColumn("Amount", width = 120.dp, alignEnd = true) { MoneyText(it.amount, signed = true) },
                    TableColumn("Booked against", weight = 1f) { row ->
                        when {
                            row.invoice != null -> CellText("Pays ${row.invoice!!.title}")
                            row.isDetailed -> CellText(row.counter.orEmpty())
                            else -> {
                                val counterId = row.lines?.firstOrNull { it.accountId != state.account?.id }?.accountId
                                AccountPicker(others, counterId, { vm.choose(row.row, it.id) }, Modifier.fillMaxWidth(), placeholder = "Choose an account")
                            }
                        }
                    },
                )
            }
            DataTable(
                rows = state.newRows,
                key = { it.row },
                columns = columns,
                modifier = Modifier.fillMaxSize(),
                selected = selected,
                onSelect = { selected = it.row },
                onOpen = { vm.toggle(it.row) },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.unassigned > 0) {
                Text("${state.unassigned} ticked line${if (state.unassigned == 1) "" else "s"} without an account. Book them all against", color = FluentTheme.colors.system.caution)
                AccountPicker(others, bulkAccount, { bulkAccount = it.id; vm.chooseForUnassigned(it.id) }, Modifier.width(280.dp))
            }
            Spacer(Modifier.weight(1f))
            state.message?.let { Text(it, color = FluentTheme.colors.system.critical) }
            Button(onClick = onClose) { Text("Cancel") }
            AccentButton(onClick = vm::import, disabled = !state.canImport) {
                Text(if (state.importing) "Importing…" else "Import ${state.toImport.size} transaction${if (state.toImport.size == 1) "" else "s"}")
            }
        }
    }
}

/** Whether the ledger agrees with the statement's own balance after the import. */
@Composable
private fun BalanceCheck(state: StatementImportUiState) {
    val closing = state.closingBalance ?: return
    val after = state.balanceAfterImport ?: return
    Column(horizontalAlignment = Alignment.End) {
        if (closing == after) {
            Text("Matches the statement's balance", color = AppColors.positive)
        } else {
            Text("Differs from the statement by ${Money.format(after - closing)}", color = FluentTheme.colors.system.caution)
        }
        Text("Statement balance ${Money.format(closing)}", style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.secondary)
    }
}

/** Which column of the file holds what. */
@Composable
private fun Columns(state: StatementImportUiState, onChange: (StatementColumns) -> Unit) {
    val options = listOf("—") + state.headers.mapIndexed { i, h -> h.ifBlank { state.sample.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "Column ${i + 1}" } }
    fun pick(label: String, value: Int?, set: (Int?) -> StatementColumns) = Triple(label, value, set)
    val c = state.columns
    val pickers = listOf(
        pick("Date", c.date) { c.copy(date = it) },
        pick("Text", c.text) { c.copy(text = it) },
        pick("Amount", c.amount) { c.copy(amount = it) },
        pick("Money out", c.moneyOut) { c.copy(moneyOut = it) },
        pick("Money in", c.moneyIn) { c.copy(moneyIn = it) },
        pick("Balance", c.balance) { c.copy(balance = it) },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        pickers.forEach { (label, value, set) ->
            ComboBox(
                header = label,
                items = options,
                selected = (value?.plus(1)) ?: 0,
                onSelectionChange = { i, _ -> onChange(set(if (i == 0) null else i - 1)) },
                modifier = Modifier.weight(1f),
            )
        }
        CheckBox(c.reversed, label = "Money out is positive", onCheckStateChange = { onChange(c.copy(reversed = it)) })
    }
}
