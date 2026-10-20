package com.soloit.doubleentry.ui.accounts

import androidx.lifecycle.ViewModel
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
import com.soloit.doubleentry.domain.StatementAnalysis
import com.soloit.doubleentry.domain.StatementColumns
import com.soloit.doubleentry.domain.StatementMatch
import com.soloit.doubleentry.domain.StatementMatcher
import com.soloit.doubleentry.domain.StatementRead
import com.soloit.doubleentry.domain.StatementTable
import com.soloit.doubleentry.domain.Transaction
import com.soloit.doubleentry.domain.TransactionDraft
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
import java.io.InputStream
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

    /** Made in the booking editor (split, described or spread over a period), so a tap reopens the editor instead of the account picker. */
    val isDetailed: Boolean get() = (lines?.size ?: 0) > 2 || description.isNotBlank() || lines.orEmpty().any { it.period != null }
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

/** A bank statement file the user chose. */
interface StatementFile {
    /** The file's name as the user sees it, when known. */
    val name: String?

    /** Opens the file for reading, or returns null when it can't be opened. */
    fun open(): InputStream?
}

class StatementImportViewModel(
    private val ledger: LedgerRepository,
    private val invoices: InvoiceRepository,
    private val imports: StatementImporter,
    private val preferences: AppPreferences,
    private val undo: UndoManager,
    private val accountId: String,
    private val file: StatementFile,
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
        val bytes = file.open()?.use { input ->
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
        return Loaded(runCatching { file.name }.getOrNull(), table)
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
        val label = when (names.size) {
            0 -> "—"
            1 -> names[0]
            else -> "${names[0]} +${names.size - 1}"
        }
        val period = lines.firstNotNullOfOrNull { it.period }
        return if (period != null) "$label · ${period.label()}" else label
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
