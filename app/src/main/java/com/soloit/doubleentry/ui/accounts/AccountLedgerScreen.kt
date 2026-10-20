package com.soloit.doubleentry.ui.accounts

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.ReconciliationStatus
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.domain.lastDay
import com.soloit.doubleentry.ui.AccountLedgerRoute
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.AccountPickerSheet
import com.soloit.doubleentry.ui.components.AccountTypeAvatar
import com.soloit.doubleentry.ui.components.AmountField
import com.soloit.doubleentry.ui.components.AmountTone
import com.soloit.doubleentry.ui.components.ConfirmDialog
import com.soloit.doubleentry.ui.components.DateField
import com.soloit.doubleentry.ui.components.EmptyState
import com.soloit.doubleentry.ui.components.GroupedItem
import com.soloit.doubleentry.ui.components.LoadingState
import com.soloit.doubleentry.ui.components.MessageDialog
import com.soloit.doubleentry.ui.components.MoneyText
import com.soloit.doubleentry.ui.components.MonthSelector
import com.soloit.doubleentry.ui.components.ScreenPadding
import com.soloit.doubleentry.ui.components.SearchField
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.theme.AppTheme
import com.soloit.doubleentry.ui.theme.tabular
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun AccountLedgerScreen(
    route: AccountLedgerRoute,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    /** Starts a new transaction on this account, dated within the month being viewed. */
    onNewTransaction: (LocalDate) -> Unit,
    /** Opens the bank statement file the user picked for import into this account. */
    onImportStatement: (Uri) -> Unit,
) {
    val vm = appViewModel { AccountLedgerViewModel(it.ledger, it.reconciliations, it.undo, route.accountId, YearMonth.of(route.year, route.month)) }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var reconciling by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var requestDelete by remember { mutableStateOf(false) }
    val statementPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onImportStatement) }

    val listState = rememberLazyListState()
    var focused by rememberSaveable { mutableStateOf(route.focusTransactionId == null) }
    // Coming from a transaction: scroll its row into view once, the first time the month has loaded.
    LaunchedEffect(state.rows, state.loading) {
        if (focused || state.loading || state.searching) return@LaunchedEffect
        val index = state.rows.indexOfFirst { it.transactionId == route.focusTransactionId }
        if (index < 0) return@LaunchedEffect
        focused = true
        // Search field, month selector, balance card, reconciliation banner and spacer come before the rows.
        val header = 4 + (if (state.reconciliation != null) 1 else 0)
        listState.animateScrollToItem(header + index)
    }

    LaunchedEffect(deleted) { if (deleted) onBack() }
    LaunchedEffect(requestDelete) {
        if (requestDelete) {
            confirmDelete = vm.canDelete()
            requestDelete = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.account?.name ?: "Account", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        state.account?.let {
                            Text("#${it.number} · ${it.type.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "Edit account") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Import bank statement…") },
                            leadingIcon = { Icon(Icons.Rounded.UploadFile, null) },
                            onClick = { menu = false; statementPicker.launch(STATEMENT_TYPES) },
                        )
                        DropdownMenuItem(
                            text = { Text("Reconcile with statement…") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.FactCheck, null) },
                            onClick = { menu = false; reconciling = true },
                        )
                        if (state.reconciliation != null) {
                            DropdownMenuItem(
                                text = { Text("Clear reconciliation") },
                                leadingIcon = { Icon(Icons.Rounded.RemoveDone, null) },
                                onClick = { menu = false; vm.clearReconciliation() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete account") },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                            onClick = { menu = false; requestDelete = true },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    // Today in the current month; otherwise the month's last day, so the new row shows up here.
                    val today = LocalDate.now()
                    onNewTransaction(if (YearMonth.from(today) == state.month) today else state.month.lastDay())
                },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Transaction") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = ScreenPadding) {
            item { SearchField(vm.query, { vm.query = it }, "Search all months", Modifier.padding(bottom = 12.dp)) }
            // A search covers every month, so the month selector and that month's balances step aside.
            if (!state.searching) item { MonthSelector(state.month, vm::setMonth) }
            if (state.loading) {
                item { LoadingState() }
                return@LazyColumn
            }
            if (!state.searching) item {
                SectionCard(
                    modifier = Modifier.padding(top = 12.dp),contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        state.account?.let { AccountTypeAvatar(it.type, size = 48) }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Closing balance", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MoneyText(state.closing, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.padding(top = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column {
                            Text("Opening", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MoneyText(state.opening, style = MaterialTheme.typography.bodyMedium)
                        }
                        Column {
                            Text("Change", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MoneyText(state.closing - state.opening, style = MaterialTheme.typography.bodyMedium, tone = AmountTone.Signed, showSign = true)
                        }
                    }
                }
            }
            state.reconciliation?.let { status -> item { ReconciliationBanner(status, onClick = { reconciling = true }) } }
            item { Spacer(Modifier.height(12.dp)) }
            if (state.rows.isEmpty()) {
                item {
                    if (state.searching) {
                        EmptyState(Icons.AutoMirrored.Rounded.ReceiptLong, "No matches", "No transaction on this account matches in any month.")
                    } else {
                        EmptyState(Icons.AutoMirrored.Rounded.ReceiptLong, "No transactions", "Nothing was booked on this account in the selected month.")
                    }
                }
            } else if (state.searching) {
                item {
                    Text(
                        "${state.rows.size} match${if (state.rows.size == 1) "" else "es"} in all months",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
            }
            itemsIndexed(state.rows, key = { _, r -> r.transactionId }) { index, row ->
                GroupedItem(index, state.rows.size, onClick = { onOpenTransaction(row.transactionId) }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(row.date.formatMedium(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            MoneyText(row.amount, tone = AmountTone.Signed, showSign = true, fontWeight = FontWeight.Medium)
                            Text(
                                com.soloit.doubleentry.domain.Money.format(row.balance),
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (reconciling) {
        state.account?.let { account ->
            ReconcileDialog(
                account = account,
                current = state.reconciliation,
                counterAccounts = state.accounts.filter { it.id != account.id },
                bookBalance = vm::bookBalance,
                onReconcile = { date, balance -> reconciling = false; vm.reconcile(date, balance) },
                onAdjust = { date, balance, counter -> reconciling = false; vm.adjustAndReconcile(date, balance, counter) },
                onDismiss = { reconciling = false },
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete account?",
            message = "Delete '${state.account?.number} ${state.account?.name}'?",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { confirmDelete = false; vm.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
    message?.let { MessageDialog("Something went wrong", it) { vm.message.value = null } }
}

/** CSV/TSV and .xlsx exports; banks and browsers label them inconsistently, so any text or spreadsheet-like type. */
private val STATEMENT_TYPES = arrayOf(
    "text/*",
    "application/csv",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/octet-stream",
)

/** Shows whether the account still matches the statement it was reconciled against. */
@Composable
private fun ReconciliationBanner(status: ReconciliationStatus, onClick: () -> Unit) {
    val finance = AppTheme.finance
    val date = status.reconciliation.date.formatMedium()
    val (container, content) = if (status.inSync) {
        finance.positiveContainer to finance.onPositiveContainer
    } else {
        finance.warningContainer to finance.onWarningContainer
    }
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = container, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (status.inSync) Icons.Rounded.Verified else Icons.Rounded.Warning, null, tint = content)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (status.inSync) "Reconciled through $date" else "Changed since reconciliation",
                    style = MaterialTheme.typography.titleSmall,
                    color = content,
                )
                Text(
                    if (status.inSync) {
                        "Matches the statement balance of ${Money.format(status.reconciliation.statementBalance)}."
                    } else {
                        "The balance on $date is now ${Money.format(status.bookBalance)}, " +
                            "${Money.formatSigned(status.drift)} against the statement. Tap to reconcile again."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = content,
                )
            }
        }
    }
}

/**
 * Compare the ledger with a bank statement: enter the statement date and closing balance. When
 * they match the account is marked reconciled; otherwise the difference can be posted against
 * another account (fees, interest) in the same step.
 */
@Composable
private fun ReconcileDialog(
    account: Account,
    current: ReconciliationStatus?,
    counterAccounts: List<Account>,
    bookBalance: suspend (LocalDate) -> Long,
    onReconcile: (LocalDate, Long) -> Unit,
    onAdjust: (LocalDate, Long, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var statement by rememberSaveable { mutableStateOf("") }
    var book by remember { mutableStateOf<Long?>(null) }
    var pickingCounter by remember { mutableStateOf(false) }
    val parsedDate = LocalDate.parse(date)
    LaunchedEffect(date) { book = bookBalance(parsedDate) }
    val statementCents = Money.parse(statement)
    val difference = if (book != null && statementCents != null) statementCents - book!! else null

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Rounded.FactCheck, null) },
        title = { Text("Reconcile ${account.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                current?.let {
                    Text(
                        "Last reconciled through ${it.reconciliation.date.formatMedium()} at ${Money.format(it.reconciliation.statementBalance)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DateField("Statement date", parsedDate, { date = it.toString() }, Modifier.fillMaxWidth())
                AmountField(statement, { statement = it }, "Statement balance", Modifier.fillMaxWidth(), imeAction = ImeAction.Done)
                Row {
                    Text("Ledger balance", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(book?.let { Money.format(it) } ?: "…", style = MaterialTheme.typography.bodyMedium.tabular())
                }
                if (difference != null) {
                    Row {
                        Text("Difference", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            if (difference == 0L) "None" else Money.formatSigned(difference),
                            style = MaterialTheme.typography.bodyMedium.tabular(),
                            color = if (difference == 0L) AppTheme.finance.positive else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (difference != 0L) {
                        Text(
                            "Look for missing or mistyped transactions on or before this date, or post the difference as an adjustment.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                difference == null -> TextButton(onClick = {}, enabled = false) { Text("Reconcile") }
                difference == 0L -> TextButton(onClick = { onReconcile(parsedDate, statementCents!!) }) { Text("Mark reconciled") }
                else -> TextButton(onClick = { pickingCounter = true }) { Text("Post adjustment…") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickingCounter && statementCents != null) {
        AccountPickerSheet(
            title = "Book the difference against",
            accounts = counterAccounts,
            onSelect = { counter -> pickingCounter = false; onAdjust(parsedDate, statementCents, counter.id) },
            onDismiss = { pickingCounter = false },
        )
    }
}
