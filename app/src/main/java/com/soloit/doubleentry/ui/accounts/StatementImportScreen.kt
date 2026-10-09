package com.soloit.doubleentry.ui.accounts

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.AppPreferences
import com.soloit.doubleentry.data.InvoiceRepository
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.StatementImporter
import com.soloit.doubleentry.data.StatementInvoicePayment
import com.soloit.doubleentry.data.UndoManager
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.BankStatement
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.Invoice
import com.soloit.doubleentry.domain.LedgerException
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.StatementAnalysis
import com.soloit.doubleentry.domain.StatementColumns
import com.soloit.doubleentry.domain.StatementMatch
import com.soloit.doubleentry.domain.StatementMatcher
import com.soloit.doubleentry.domain.StatementRead
import com.soloit.doubleentry.domain.StatementTable
import com.soloit.doubleentry.domain.Transaction
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.ui.StatementImportRoute
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.AccountPickerSheet
import com.soloit.doubleentry.ui.components.AmountTone
import com.soloit.doubleentry.ui.components.EmptyState
import com.soloit.doubleentry.ui.components.GroupedItem
import com.soloit.doubleentry.ui.components.IconAvatar
import com.soloit.doubleentry.ui.components.LoadingState
import com.soloit.doubleentry.ui.components.MessageDialog
import com.soloit.doubleentry.ui.components.MoneyText
import com.soloit.doubleentry.ui.components.PickerAction
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.components.SectionHeader
import com.soloit.doubleentry.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlin.math.absoluteValue

/** An unpaid invoice, with the title of its booking and what is [left] to pay of it in this import. */
data class OpenInvoiceUi(val invoice: Invoice, val title: String, val left: Long = invoice.remaining)

/** A statement line that is not in the ledger yet, as the user is about to import it. */
data class NewRowUi(
    val row: Int,
    val date: LocalDate,
    val text: String,
    /** Cents; positive when money came into the account. */
    val amount: Long,
    val included: Boolean,
    /** Null until an account is guessed from an earlier booking or chosen. */
    val lines: List<DraftLine>?,
    /** The other side of the booking: "Groceries", "Salary +1" for several accounts, or the invoice it pays. */
    val counter: String?,
    val description: String = "",
    /** The invoice this row pays; it is then booked as that invoice's payment instead of with [lines]. */
    val invoice: OpenInvoiceUi? = null,
) {
    val isBooked: Boolean get() = lines != null || invoice != null

    /** Made in the booking editor (split or described), so a tap reopens the editor instead of the account picker. */
    val isDetailed: Boolean get() = (lines?.size ?: 0) > 2 || description.isNotBlank()
}

data class StatementImportUiState(
    val loading: Boolean = true,
    /** The file could not be read at all. */
    val error: String? = null,
    val fileName: String? = null,
    val account: Account? = null,
    val headers: List<String> = emptyList(),
    /** The first data row, shown under each column name when choosing columns. */
    val sample: List<String> = emptyList(),
    val columns: StatementColumns = StatementColumns(),
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val lineCount: Int = 0,
    val skipped: Int = 0,
    val newRows: List<NewRowUi> = emptyList(),
    val matched: List<StatementMatch> = emptyList(),
    val missing: List<Transaction> = emptyList(),
    /** The statement's balance on [to], when the file has a balance column. */
    val closingBalance: Long? = null,
    /** The ledger's balance on [to] once the ticked rows are imported, signed like the file (money in is positive). */
    val balanceAfterImport: Long? = null,
    val accounts: List<Account> = emptyList(),
    val mostUsed: List<Account> = emptyList(),
    val openInvoices: List<OpenInvoiceUi> = emptyList(),
    val importing: Boolean = false,
    val done: Boolean = false,
    val message: String? = null,
) {
    val toImport: List<NewRowUi> get() = newRows.filter { it.included }
    val unassigned: Int get() = toImport.count { !it.isBooked }
    val canImport: Boolean get() = !importing && toImport.isNotEmpty() && unassigned == 0

    /** Open invoices [row] can pay, less what the other ticked rows of this import already pay of them. */
    fun invoicesFor(row: NewRowUi): List<OpenInvoiceUi> {
        val accountId = account?.id ?: return emptyList()
        val paying = toImport.filter { it.row != row.row && it.invoice != null }
            .groupBy({ it.invoice!!.invoice.id }, { it.amount.absoluteValue })
        return openInvoices
            .map { it.copy(left = it.invoice.remaining - paying[it.invoice.id].orEmpty().sum()) }
            .filter { StatementMatcher.canPay(accountId, row.amount, it.invoice) && row.amount.absoluteValue <= it.left }
    }
}

class StatementImportViewModel(
    private val ledger: LedgerRepository,
    private val invoices: InvoiceRepository,
    private val imports: StatementImporter,
    private val preferences: AppPreferences,
    private val undo: UndoManager,
    private val accountId: String,
    private val resolver: ContentResolver,
    private val uri: Uri,
) : ViewModel() {
    private class Loaded(val fileName: String?, val table: StatementTable)
    private class Analyzed(val read: StatementRead, val analysis: StatementAnalysis?, val ledgerBalance: Long, val openInvoices: List<OpenInvoiceUi>)
    private data class Progress(val importing: Boolean = false, val done: Boolean = false, val message: String? = null)

    /**
     * A booking made by the user for a row: [lines], or a payment of [invoiceId]. Dropped if the row's
     * amount changes (other columns chosen).
     */
    private data class Booking(val amount: Long, val lines: List<DraftLine>, val description: String = "", val invoiceId: String? = null)

    private val loaded = MutableStateFlow<Result<Loaded>?>(null)
    private val columns = MutableStateFlow(StatementColumns())
    private val excluded = MutableStateFlow<Set<Int>>(emptySet())
    /** Bookings made by the user, by row; they replace the guessed booking. */
    private val chosen = MutableStateFlow<Map<Int, Booking>>(emptyMap())
    private val progress = MutableStateFlow(Progress())
    private val mostUsed = MutableStateFlow<List<Account>>(emptyList())

    /** Re-run on every ledger change, so fixing a transaction elsewhere updates the comparison. */
    private val analyzed = combine(loaded, columns, ledger.changes) { l, c, _ -> l to c }.mapLatest { (l, c) ->
        val table = l?.getOrNull()?.table ?: return@mapLatest null
        val account = ledger.getAccount(accountId) ?: return@mapLatest null
        val read = BankStatement.read(table, c)
        if (read.lines.isEmpty()) return@mapLatest Analyzed(read, null, 0, emptyList())
        val transactions = ledger.getTransactions()
        val open = invoices.liveInvoices().filter { !it.isPaid }
        val analysis = StatementMatcher.analyze(account, read.lines, transactions, open)
        val balance = ledger.getBalances(analysis.to)[accountId] ?: 0
        val titles = transactions.associate { it.id to it.title }
        val openUi = open.map { OpenInvoiceUi(it, titles[it.bookingTransactionId].orEmpty().ifBlank { "Invoice" }) }
        // Balances are on the account's normal side; the file counts money in as positive.
        Analyzed(read, analysis, if (account.type.isDebitNormal) balance else -balance, openUi)
    }.flowOn(Dispatchers.Default)

    private val choices = combine(excluded, chosen, ::Pair)
    private val context = combine(progress, ledger.accounts, mostUsed, ::Triple)

    val state: StateFlow<StatementImportUiState> = combine(loaded, columns, analyzed, choices, context) { l, c, a, (ex, ch), (p, accounts, used) ->
        build(l, c, a, ex, ch, p, accounts, used)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatementImportUiState())

    init {
        viewModelScope.launch {
            val accounts = ledger.getAccounts().associateBy { it.id }
            mostUsed.value = ledger.mostUsedAccountIds().filter { it != accountId }.mapNotNull(accounts::get)
            val result = try {
                Result.success(withContext(Dispatchers.IO) { load() })
            } catch (e: LedgerException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(LedgerException("The selected file could not be read."))
            }
            result.getOrNull()?.let { columns.value = preferences.statementColumns(accountId, it.table.signature) ?: BankStatement.detectColumns(it.table) }
            loaded.value = result
        }
    }

    private fun load(): Loaded {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_FILE_BYTES) throw LedgerException("This file is too large to be a bank statement.")
            }
            out.toByteArray()
        } ?: throw LedgerException("The selected file could not be opened.")
        val table = try {
            BankStatement.table(bytes)
        } catch (e: Exception) {
            if (e is LedgerException) throw e
            throw LedgerException("The file could not be read as a CSV or Excel (.xlsx) file.")
        }
        if (table.rows.isEmpty()) throw LedgerException("The file has no rows to import.")
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        return Loaded(name, table)
    }

    private fun build(
        loaded: Result<Loaded>?,
        columns: StatementColumns,
        analyzed: Analyzed?,
        excluded: Set<Int>,
        chosen: Map<Int, Booking>,
        progress: Progress,
        accounts: List<Account>,
        mostUsed: List<Account>,
    ): StatementImportUiState {
        val file = loaded?.getOrElse { return StatementImportUiState(loading = false, error = it.message) } ?: return StatementImportUiState()
        val byId = accounts.associateBy { it.id }
        val analysis = analyzed?.analysis
        val openInvoices = analyzed?.openInvoices.orEmpty()
        val openById = openInvoices.associateBy { it.invoice.id }
        val newRows = analysis?.new.orEmpty().map { n ->
            val booking = chosen[n.line.row]?.takeIf { it.amount == n.line.amount }
            // A suggested payment applies until the user books the row otherwise.
            val invoice = (if (booking != null) booking.invoiceId else n.invoice?.id)?.let(openById::get)
            val lines = if (invoice != null) null else booking?.lines?.takeIf { it.isNotEmpty() } ?: n.guess?.lines
            val counter = when {
                invoice == null -> lines?.let { counterLabel(it, byId) }
                n.line.amount.absoluteValue < invoice.invoice.remaining -> "Part payment: ${invoice.title}"
                else -> "Pays invoice: ${invoice.title}"
            }
            NewRowUi(
                n.line.row, n.line.date, n.line.text, n.line.amount, n.line.row !in excluded, lines, counter,
                booking?.description.orEmpty(), invoice,
            )
        }.sortedWith(compareByDescending<NewRowUi> { it.date }.thenBy { it.row })
        val closing = analyzed?.read?.lines?.let(BankStatement::closingBalance)
        val afterImport = analyzed?.ledgerBalance?.plus(newRows.filter { it.included }.sumOf { it.amount })
        return StatementImportUiState(
            // Still loading while the first comparison for complete columns runs.
            loading = analyzed == null && columns.isComplete,
            fileName = file.fileName,
            account = byId[accountId],
            headers = file.table.headers,
            sample = file.table.rows.first(),
            columns = columns,
            from = analysis?.from,
            to = analysis?.to,
            lineCount = analyzed?.read?.lines?.size ?: 0,
            skipped = analyzed?.read?.skipped?.size ?: 0,
            newRows = newRows,
            matched = analysis?.matched.orEmpty().sortedWith(compareByDescending<StatementMatch> { it.line.date }.thenBy { it.line.row }),
            missing = analysis?.missing.orEmpty().sortedByDescending { it.date },
            closingBalance = closing,
            balanceAfterImport = afterImport.takeIf { closing != null },
            accounts = accounts,
            mostUsed = mostUsed,
            openInvoices = openInvoices,
            importing = progress.importing,
            done = progress.done,
            message = progress.message,
        )
    }

    private fun counterLabel(lines: List<DraftLine>, accounts: Map<String, Account>): String {
        val names = lines.filter { it.accountId != accountId }.map { accounts[it.accountId]?.name ?: "Unknown account" }.distinct()
        return when (names.size) {
            0 -> "—"
            1 -> names[0]
            else -> "${names[0]} +${names.size - 1}"
        }
    }

    fun setColumns(value: StatementColumns) { columns.value = value }

    fun toggle(row: Int) = excluded.update { if (row in it) it - row else it + row }

    /** Books [row] against [counterAccountId]; rows with the same text that have no account yet get it too. */
    fun choose(row: Int, counterAccountId: String) {
        val rows = state.value.newRows
        val target = rows.firstOrNull { it.row == row } ?: return
        val key = StatementMatcher.looseKey(target.text)
        val alike = if (key.isEmpty()) emptyList() else rows.filter { !it.isBooked && StatementMatcher.looseKey(it.text) == key }
        chosen.update { current ->
            current + (alike + target).associate { r ->
                r.row to Booking(r.amount, StatementMatcher.counterLines(accountId, r.amount, counterAccountId), current[r.row]?.description.orEmpty())
            }
        }
    }

    /** Books every ticked row that has no account yet against [counterAccountId]. */
    fun chooseForUnassigned(counterAccountId: String) {
        val rows = state.value.toImport.filter { !it.isBooked }
        chosen.update { it + rows.associate { r -> r.row to Booking(r.amount, StatementMatcher.counterLines(accountId, r.amount, counterAccountId)) } }
    }

    /** Uses [lines] (from the booking editor, balanced, with the bank's line) for [row]. */
    fun setBooking(row: Int, lines: List<DraftLine>, description: String) {
        val amount = state.value.newRows.firstOrNull { it.row == row }?.amount ?: return
        chosen.update { it + (row to Booking(amount, lines, description)) }
    }

    /** Books [row] as a payment (in full or in part) of [invoiceId]. */
    fun payInvoice(row: Int, invoiceId: String) {
        val amount = state.value.newRows.firstOrNull { it.row == row }?.amount ?: return
        chosen.update { it + (row to Booking(amount, emptyList(), invoiceId = invoiceId)) }
    }

    suspend fun createAccount(number: Int, name: String, type: AccountType): Account = ledger.createAccount(number, name, type)

    fun dismissMessage() = progress.update { it.copy(message = null) }

    fun import() {
        val s = state.value
        val table = loaded.value?.getOrNull()?.table ?: return
        if (!s.canImport) return
        progress.update { it.copy(importing = true, message = null) }
        viewModelScope.launch {
            try {
                val rows = s.toImport.sortedWith(compareBy({ it.date }, { it.row }))
                val drafts = rows.filter { it.invoice == null }.map {
                    TransactionDraft(it.date, it.text.ifBlank { "Bank transaction" }, it.description, it.lines!!)
                }
                val payments = rows.mapNotNull { r -> r.invoice?.let { StatementInvoicePayment(it.invoice.id, r.date, r.amount.absoluteValue) } }
                val result = imports.import(accountId, drafts, payments)
                preferences.setStatementColumns(accountId, table.signature, columns.value)
                undo.offer("Imported ${result.count} transaction${if (result.count == 1) "" else "s"}") { imports.undo(result) }
                progress.update { it.copy(importing = false, done = true) }
            } catch (e: Exception) {
                progress.update { it.copy(importing = false, message = e.message ?: "The statement could not be imported.") }
            }
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 10 * 1024 * 1024
    }
}

/** Picker target that books every ticked row without an account at once. */
private const val ALL_UNASSIGNED = -1

@Composable
fun StatementImportScreen(route: StatementImportRoute, onBack: () -> Unit, onOpenTransaction: (String) -> Unit) {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val vm = appViewModel {
        StatementImportViewModel(it.ledger, it.invoices, it.statementImports, it.preferences, it.undo, route.accountId, resolver, Uri.parse(route.uri))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<Int?>(null) }
    var editingBooking by remember { mutableStateOf<Int?>(null) }
    var choosingInvoice by remember { mutableStateOf<Int?>(null) }
    var editingColumns by rememberSaveable { mutableStateOf(false) }
    var showMatched by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.done) { if (state.done) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Import statement")
                        state.account?.let {
                            Text(it.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { TextButton(onClick = vm::import, enabled = state.canImport) { Text("Import") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading) {
                item { LoadingState() }
                return@LazyColumn
            }
            state.error?.let { error ->
                item { EmptyState(Icons.Rounded.ErrorOutline, "Couldn't read the file", error) }
                return@LazyColumn
            }
            item { FileSummary(state) }
            item {
                ColumnsCard(
                    state = state,
                    editing = editingColumns || !state.columns.isComplete,
                    onToggleEditing = { editingColumns = !editingColumns },
                    onChange = vm::setColumns,
                )
            }
            if (state.from == null) {
                item {
                    Text(
                        if (state.columns.isComplete) "No rows could be read with these columns." else "Choose the columns that hold the date and the amount.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                return@LazyColumn
            }
            if (state.missing.isNotEmpty()) {
                item {
                    Banner(
                        icon = Icons.Rounded.Warning,
                        container = AppTheme.finance.warningContainer,
                        content = AppTheme.finance.onWarningContainer,
                        title = "${state.missing.size} transaction${if (state.missing.size == 1) " is" else "s are"} not in the statement",
                        text = "Booked on this account between ${state.from!!.formatMedium()} and ${state.to!!.formatMedium()}, but no row " +
                            "in the file matches. Check whether they are duplicates, mistakes or belong to another account.",
                    )
                }
                itemsIndexed(state.missing, key = { _, tx -> "missing-" + tx.id }) { index, tx ->
                    LedgerRow(
                        index = index,
                        count = state.missing.size,
                        title = tx.title,
                        subtitle = tx.date.formatMedium(),
                        amount = StatementMatcher.cashEffect(tx, route.accountId),
                        onClick = { onOpenTransaction(tx.id) },
                    )
                }
            }
            state.closingBalance?.let { closing -> item { BalanceCheck(state.to!!, closing, state.balanceAfterImport!!) } }

            item { SectionHeader("New (${state.newRows.size})") }
            if (state.newRows.isEmpty()) {
                item { EmptyState(Icons.Rounded.CheckCircle, "Nothing new", "Every row in the file is already in the ledger.") }
            } else {
                item {
                    Text(
                        "Accounts are guessed from the latest booking with the same text, and a row that pays an open invoice " +
                            "in full is linked to it. Tap a row to choose another account, pay an invoice or split it across " +
                            "several accounts; untick rows you don't want.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                itemsIndexed(state.newRows, key = { _, r -> "new-" + r.row }) { index, row ->
                    NewRow(
                        index, state.newRows.size, row,
                        onToggle = { vm.toggle(row.row) },
                        onClick = { if (row.isDetailed) editingBooking = row.row else picking = row.row },
                    )
                }
                item { ImportFooter(state, onChooseForUnassigned = { picking = ALL_UNASSIGNED }, onImport = vm::import) }
            }

            if (state.matched.isNotEmpty()) {
                item {
                    SectionHeader("Already in the ledger (${state.matched.size})") {
                        IconButton(onClick = { showMatched = !showMatched }) {
                            Icon(if (showMatched) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (showMatched) "Hide" else "Show")
                        }
                    }
                }
                if (showMatched) {
                    itemsIndexed(state.matched, key = { _, m -> "matched-" + m.line.row }) { index, match ->
                        val tx = match.transaction
                        // Say how it was booked only where that differs from the bank's row.
                        val bookedAs = listOfNotNull(
                            tx.title.takeIf { StatementMatcher.textKey(it) != StatementMatcher.textKey(match.line.text) }?.let { "as $it" },
                            tx.date.takeIf { it != match.line.date }?.let { "on ${it.formatMedium()}" },
                        )
                        LedgerRow(
                            index = index,
                            count = state.matched.size,
                            title = match.line.text,
                            subtitle = match.line.date.formatMedium() + if (bookedAs.isEmpty()) "" else " · booked ${bookedAs.joinToString(" ")}",
                            amount = match.line.amount,
                            onClick = { onOpenTransaction(tx.id) },
                        )
                    }
                }
            }
        }
    }

    picking?.let { target ->
        val row = state.newRows.firstOrNull { it.row == target }
        AccountPickerSheet(
            title = if (row != null) "Book “${row.text}” against" else "Book ${state.unassigned} rows against",
            accounts = state.accounts.filter { it.id != route.accountId },
            mostUsed = state.mostUsed,
            selectedId = row?.lines?.filter { it.accountId != route.accountId }?.singleOrNull()?.accountId,
            onSelect = { account ->
                if (row != null) vm.choose(row.row, account.id) else vm.chooseForUnassigned(account.id)
                picking = null
            },
            onDismiss = { picking = null },
            onCreateAccount = vm::createAccount,
            actions = row?.let { r ->
                listOfNotNull(
                    PickerAction("Pay an invoice…", Icons.AutoMirrored.Rounded.ReceiptLong) {
                        picking = null
                        choosingInvoice = r.row
                    }.takeIf { state.invoicesFor(r).isNotEmpty() },
                    PickerAction("Split across several accounts…", Icons.AutoMirrored.Rounded.CallSplit) {
                        picking = null
                        editingBooking = r.row
                    },
                )
            }.orEmpty(),
        )
    }
    choosingInvoice?.let { target ->
        state.newRows.firstOrNull { it.row == target }?.let { row ->
            InvoiceChooserSheet(
                row = row,
                invoices = state.invoicesFor(row),
                onSelect = { vm.payInvoice(row.row, it); choosingInvoice = null },
                onDismiss = { choosingInvoice = null },
            )
        }
    }
    editingBooking?.let { target ->
        state.newRows.firstOrNull { it.row == target }?.let { row ->
            StatementBookingDialog(
                accountId = route.accountId,
                row = row,
                accounts = state.accounts,
                mostUsed = state.mostUsed,
                onCreateAccount = vm::createAccount,
                onDone = { lines, description -> vm.setBooking(row.row, lines, description); editingBooking = null },
                onDismiss = { editingBooking = null },
            )
        }
    }
    state.message?.let { MessageDialog("Import failed", it, vm::dismissMessage) }
}

@Composable
private fun FileSummary(state: StatementImportUiState) {
    SectionCard(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(state.fileName ?: "Bank statement", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.from != null && state.to != null) {
                    Text(
                        "${state.from.formatMedium()} – ${state.to.formatMedium()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.lineCount > 0) {
            Text(
                "${state.lineCount} transaction${if (state.lineCount == 1) "" else "s"}: ${state.newRows.size} new, " +
                    "${state.matched.size} already in the ledger.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (state.skipped > 0) {
            Text(
                "${state.skipped} row${if (state.skipped == 1) "" else "s"} without a date or amount (such as pending card purchases) " +
                    "${if (state.skipped == 1) "was" else "were"} skipped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** The columns the rows are read with: a one-line summary, or a picker per field while editing. */
@Composable
private fun ColumnsCard(state: StatementImportUiState, editing: Boolean, onToggleEditing: () -> Unit, onChange: (StatementColumns) -> Unit) {
    val c = state.columns
    fun name(index: Int?) = index?.let { state.headers.getOrNull(it) }
    SectionCard(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Columns", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (c.isComplete) TextButton(onClick = onToggleEditing) { Text(if (editing) "Done" else "Change") }
        }
        if (!editing) {
            val amount = name(c.amount) ?: "${name(c.moneyOut)} / ${name(c.moneyIn)}"
            Text(
                listOfNotNull(
                    "Date: ${name(c.date)}",
                    name(c.text)?.let { "Text: $it" },
                    "Amount: $amount",
                    name(c.balance)?.let { "Balance: $it" },
                    if (c.reversed) "signs reversed" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ColumnDropdown("Date", state, c.date, noneLabel = null) { onChange(c.copy(date = it)) }
            ColumnDropdown("Text", state, c.text, noneLabel = "None") { onChange(c.copy(text = it)) }
            ColumnDropdown("Amount", state, c.amount, noneLabel = "Separate columns for money out and in") { onChange(c.copy(amount = it)) }
            if (c.amount == null) {
                ColumnDropdown("Money out", state, c.moneyOut, noneLabel = "None") { onChange(c.copy(moneyOut = it)) }
                ColumnDropdown("Money in", state, c.moneyIn, noneLabel = "None") { onChange(c.copy(moneyIn = it)) }
            }
            ColumnDropdown("Balance (optional)", state, c.balance, noneLabel = "None") { onChange(c.copy(balance = it)) }
            Row(
                Modifier.fillMaxWidth().toggleable(value = c.reversed, role = Role.Switch, onValueChange = { onChange(c.copy(reversed = it)) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Reverse signs", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "For statements that show payments as positive amounts, like many credit cards.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = c.reversed, onCheckedChange = null)
            }
        }
    }
}

/** A column picker listing every header with its value in the first row. */
@Composable
private fun ColumnDropdown(label: String, state: StatementImportUiState, selected: Int?, noneLabel: String?, onSelect: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.let { state.headers.getOrNull(it) } ?: noneLabel.orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (noneLabel != null) DropdownMenuItem(text = { Text(noneLabel) }, onClick = { onSelect(null); expanded = false })
            state.headers.forEachIndexed { i, header ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(header, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            state.sample.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    },
                    onClick = { onSelect(i); expanded = false },
                )
            }
        }
    }
}

/** Open invoices a row can pay, those it pays in full first. */
@Composable
private fun InvoiceChooserSheet(row: NewRowUi, invoices: List<OpenInvoiceUi>, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val amount = row.amount.absoluteValue
    val sorted = invoices.sortedWith(compareBy<OpenInvoiceUi>({ it.left != amount }, { it.invoice.dueDate }))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Which invoice does “${row.text}” pay?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
        Text(
            "${Money.format(amount)} on ${row.date.formatMedium()}. Less than an invoice's remaining amount is recorded as a part payment.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            items(sorted, key = { it.invoice.id }) { open ->
                val invoice = open.invoice
                ListItem(
                    headlineContent = { Text(open.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            "Due ${invoice.dueDate.formatMedium()} · ${Money.format(open.left)} left" +
                                if (open.left == amount) " · paid in full" else " · part payment",
                        )
                    },
                    leadingContent = { IconAvatar(Icons.AutoMirrored.Rounded.ReceiptLong, MaterialTheme.colorScheme.primary) },
                    trailingContent = if (row.invoice?.invoice?.id == invoice.id) {
                        { Icon(Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onSelect(invoice.id) }.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** Whether the ledger will agree with the statement's closing balance once the ticked rows are imported. */
@Composable
private fun BalanceCheck(date: LocalDate, closing: Long, afterImport: Long) {
    val difference = afterImport - closing
    val finance = AppTheme.finance
    val matches = difference == 0L
    Banner(
        icon = if (matches) Icons.Rounded.Verified else Icons.Rounded.Warning,
        container = if (matches) finance.positiveContainer else finance.warningContainer,
        content = if (matches) finance.onPositiveContainer else finance.onWarningContainer,
        title = if (matches) "Balance matches the statement" else "Balance differs from the statement",
        text = "On ${date.formatMedium()} the statement shows ${Money.format(closing)}; after the import the ledger shows " +
            Money.format(afterImport) +
            if (matches) "." else " (${Money.formatSigned(difference)}). Rows left out, transactions missing from the statement " +
                "or an opening balance that differs from the bank's can explain it.",
    )
}

@Composable
private fun Banner(icon: ImageVector, container: Color, content: Color, title: String, text: String) {
    Surface(shape = MaterialTheme.shapes.large, color = container, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = content)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = content)
                Text(text, style = MaterialTheme.typography.bodySmall, color = content)
            }
        }
    }
}

@Composable
private fun NewRow(index: Int, count: Int, row: NewRowUi, onToggle: () -> Unit, onClick: () -> Unit) {
    GroupedItem(index, count, onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = row.included, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f).alpha(if (row.included) 1f else 0.6f)) {
                Text(row.text.ifBlank { "No text" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${row.date.formatMedium()} · ${row.counter ?: "Choose account"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!row.isBooked && row.included) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            MoneyText(row.amount, tone = AmountTone.Signed, showSign = true, modifier = Modifier.alpha(if (row.included) 1f else 0.6f))
        }
    }
}

@Composable
private fun LedgerRow(index: Int, count: Int, title: String, subtitle: String, amount: Long, onClick: () -> Unit) {
    GroupedItem(index, count, onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            MoneyText(amount, tone = AmountTone.Signed, showSign = true)
        }
    }
}

@Composable
private fun ImportFooter(state: StatementImportUiState, onChooseForUnassigned: () -> Unit, onImport: () -> Unit) {
    val n = state.toImport.size
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.unassigned > 0) {
            val rows = "${state.unassigned} row${if (state.unassigned == 1) "" else "s"}"
            Text(
                "Choose an account for $rows, or untick ${if (state.unassigned == 1) "it" else "them"}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            OutlinedButton(onClick = onChooseForUnassigned, modifier = Modifier.fillMaxWidth()) { Text("Choose account for $rows…") }
        }
        Button(onClick = onImport, enabled = state.canImport, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.importing) "Importing…" else "Import $n transaction${if (n == 1) "" else "s"}")
        }
    }
}
