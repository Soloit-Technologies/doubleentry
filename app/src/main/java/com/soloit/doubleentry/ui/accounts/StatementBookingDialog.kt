package com.soloit.doubleentry.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.CostPeriod
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.ui.components.AccountPickerSheet
import com.soloit.doubleentry.ui.components.AmountTone
import com.soloit.doubleentry.ui.components.ConfirmDialog
import com.soloit.doubleentry.ui.components.LineInput
import com.soloit.doubleentry.ui.components.Lines
import com.soloit.doubleentry.ui.components.LinesEditor
import com.soloit.doubleentry.ui.components.MoneyText
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.components.SectionHeader
import com.soloit.doubleentry.ui.components.TextAndFigures
import com.soloit.doubleentry.ui.transactions.BookingLines
import kotlin.math.absoluteValue

/**
 * Full-screen editor for the voucher of one statement row. The line on the imported account is
 * fixed to the bank's amount; the rest can be split across any number of accounts. Nothing is
 * posted here: the booking is used when the statement is imported.
 */
@Composable
fun StatementBookingDialog(
    accountId: String,
    row: NewRowUi,
    accounts: List<Account>,
    mostUsed: List<Account>,
    onCreateAccount: suspend (number: Int, name: String, type: AccountType) -> Account,
    onDone: (lines: List<DraftLine>, description: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var modified by remember { mutableStateOf(false) }
    val booking = remember(row.row) { BookingLines(onUserChange = { modified = true }).also { it.set(initialLines(it, accountId, row)) } }
    val lines by booking.lines.collectAsState()
    var description by rememberSaveable(row.row) { mutableStateOf(row.description) }
    var picking by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val accountsById = remember(accounts) { accounts.associateBy { it.id } }
    val canSave = Lines.isBalanced(lines) && Lines.populatedCount(lines) >= 2

    fun done() {
        Lines.toDraft(lines).onSuccess { onDone(it, description.trim()) }.onFailure { error = it.message }
    }
    val close = { if (modified) confirmDiscard = true else onDismiss() }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The dialog draws behind the system bars; give them icons that show on its background.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val lightBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            window?.let {
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Booking") },
                    navigationIcon = { IconButton(onClick = close) { Icon(Icons.Rounded.Close, "Close") } },
                    actions = { TextButton(onClick = ::done, enabled = canSave) { Text("Done") } },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionCard(contentPadding = PaddingValues(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextAndFigures(figures = { MoneyText(row.amount, tone = AmountTone.Signed, showSign = true) }) {
                            Text(row.text.ifBlank { "No text" }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(row.date.formatMedium(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it; modified = true },
                    label = { Text("Description (optional)") },
                    minLines = 2,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                SectionHeader("Lines")
                LinesEditor(
                    lines = lines,
                    accounts = accountsById,
                    onPickAccount = { picking = it },
                    onDebitChange = { key, value -> booking.setDebit(key, value); error = null },
                    onCreditChange = { key, value -> booking.setCredit(key, value); error = null },
                    onFocusDebit = booking::autoFillDebit,
                    onFocusCredit = booking::autoFillCredit,
                    onRemove = booking::remove,
                    onAdd = booking::add,
                    lockedReason = "Set by the bank statement",
                    periodDate = row.date,
                    onPeriodChange = booking::setPeriod,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Button(onClick = ::done, enabled = canSave, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) { Text("Done") }
            }
        }

        picking?.let { key ->
            AccountPickerSheet(
                accounts = accounts.filter { it.id != accountId },
                mostUsed = mostUsed,
                selectedId = lines.firstOrNull { it.key == key }?.accountId,
                onSelect = { account -> booking.setAccount(key, account.id); error = null; picking = null },
                onDismiss = { picking = null },
                onCreateAccount = onCreateAccount,
            )
        }
        if (confirmDiscard) {
            ConfirmDialog(
                title = "Discard changes?",
                message = "The booking stays as it was.",
                confirmLabel = "Discard",
                destructive = true,
                onConfirm = { confirmDiscard = false; onDismiss() },
                onDismiss = { confirmDiscard = false },
            )
        }
    }
}

/**
 * The bank's line, locked, followed by the row's other lines; when there are fewer than two of
 * those, empty ones are added to split into.
 */
private fun initialLines(booking: BookingLines, accountId: String, row: NewRowUi): List<LineInput> {
    fun line(accountId: String?, cents: Long, type: EntryType, locked: Boolean = false, period: CostPeriod? = null) = booking.newLine(
        accountId = accountId,
        debit = if (type == EntryType.Debit) Money.toInput(cents) else "",
        credit = if (type == EntryType.Credit) Money.toInput(cents) else "",
        locked = locked,
    ).copy(period = period)
    val bank = line(accountId, row.amount.absoluteValue, if (row.amount > 0) EntryType.Debit else EntryType.Credit, locked = true)
    val others = row.lines.orEmpty().filter { it.accountId != accountId }.map { line(it.accountId, it.amount, it.type, period = it.period) }
    return listOf(bank) + others + List((2 - others.size).coerceAtLeast(0)) { booking.newLine() }
}
