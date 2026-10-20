package com.soloit.doubleentry.ui.recurring

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.RecurringRepository
import com.soloit.doubleentry.data.UndoManager
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.Entry
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Frequency
import com.soloit.doubleentry.domain.Recurrence
import com.soloit.doubleentry.domain.RecurringRule
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.domain.relativeDayLabel
import com.soloit.doubleentry.ui.RecurringEditorRoute
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.AccountPickerSheet
import com.soloit.doubleentry.ui.components.ConfirmDialog
import com.soloit.doubleentry.ui.components.DateField
import com.soloit.doubleentry.ui.components.EmptyState
import com.soloit.doubleentry.ui.components.GroupedItem
import com.soloit.doubleentry.ui.components.IconAvatar
import com.soloit.doubleentry.ui.components.LineInput
import com.soloit.doubleentry.ui.components.Lines
import com.soloit.doubleentry.ui.components.LinesEditor
import com.soloit.doubleentry.ui.components.LoadingState
import com.soloit.doubleentry.ui.components.MoneyText
import com.soloit.doubleentry.ui.components.ScreenPadding
import com.soloit.doubleentry.ui.components.SectionHeader
import com.soloit.doubleentry.ui.components.SegmentedChoice
import com.soloit.doubleentry.ui.components.TextAndFigures
import com.soloit.doubleentry.ui.components.isLargeFont
import com.soloit.doubleentry.ui.transactions.BookingLines
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

// ---- List -------------------------------------------------------------------------------------

data class RecurringRowUi(val rule: RecurringRule, val flow: String, val next: LocalDate?)

data class RecurringListUiState(val loading: Boolean = true, val rows: List<RecurringRowUi> = emptyList())

class RecurringListViewModel(recurring: RecurringRepository, ledger: LedgerRepository) : ViewModel() {
    val state: StateFlow<RecurringListUiState> = combine(recurring.rules, ledger.accounts) { rules, accounts ->
        val names = accounts.associate { it.id to it.name }
        val today = LocalDate.now()
        RecurringListUiState(
            loading = false,
            rows = rules.map { rule ->
                fun side(type: EntryType) = rule.lines.filter { it.type == type }.map { names[it.accountId] ?: "?" }.distinct()
                    .let { if (it.size > 1) "${it[0]} +${it.size - 1}" else it.firstOrNull() ?: "—" }
                RecurringRowUi(rule, "${side(EntryType.Credit)} → ${side(EntryType.Debit)}", if (rule.paused) null else Recurrence.next(rule, today))
            }.sortedWith(compareBy<RecurringRowUi> { it.next == null }.thenBy { it.next }),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecurringListUiState())
}

@Composable
fun RecurringListScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit) {
    val vm = appViewModel { RecurringListViewModel(it.recurring, it.ledger) }
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recurring transactions") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Recurring") })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = ScreenPadding) {
            when {
                state.loading -> item { LoadingState() }
                state.rows.isEmpty() -> item {
                    EmptyState(
                        Icons.Rounded.EventRepeat,
                        "Nothing recurring yet",
                        "Rent, salary and subscriptions can post themselves. Add one here, or open a transaction and tap Repeat.",
                    )
                }
                else -> {
                    item {
                        Text(
                            "Due transactions are posted automatically when the app opens.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 12.dp),
                        )
                    }
                    itemsIndexed(state.rows, key = { _, r -> r.rule.id }) { index, row ->
                        GroupedItem(index, state.rows.size, onClick = { onOpen(row.rule.id) }) { RecurringRow(row) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecurringRow(row: RecurringRowUi) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconAvatar(
            if (row.rule.paused) Icons.Rounded.PauseCircle else Icons.Rounded.Repeat,
            if (row.rule.paused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(14.dp))
        TextAndFigures(figures = { MoneyText(row.rule.amount, fontWeight = FontWeight.Medium) }) {
            Text(row.rule.title, style = MaterialTheme.typography.bodyLarge, maxLines = if (isLargeFont()) 2 else 1, overflow = TextOverflow.Ellipsis)
            Text(row.flow, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    row.rule.paused -> "Paused · ${Recurrence.summary(row.rule)}"
                    row.next == null -> "Ended · ${Recurrence.summary(row.rule)}"
                    else -> "Next ${row.next.relativeDayLabel()} · ${row.rule.frequency.label}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---- Editor -----------------------------------------------------------------------------------

data class RecurringForm(
    val title: String = "",
    val description: String = "",
    val frequency: Frequency = Frequency.Monthly,
    val startDate: LocalDate = LocalDate.now(),
    val endDate: LocalDate? = null,
    val paused: Boolean = false,
    /** For a rule made from a transaction: that transaction counts as the first occurrence. */
    val postedThrough: LocalDate? = null,
)

data class RecurringEditorUiState(
    val isEdit: Boolean,
    val loading: Boolean = true,
    val form: RecurringForm = RecurringForm(),
    val lines: List<LineInput> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val modified: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
) {
    val accountsById: Map<String, Account> get() = accounts.associateBy { it.id }
    val canSave: Boolean get() = !saving && form.title.isNotBlank() && Lines.isBalanced(lines) && Lines.populatedCount(lines) >= 2

    /** Rule as it would be saved, for previews; null while the lines are incomplete. */
    fun preview(id: String): RecurringRule? {
        val draft = Lines.toDraft(lines).getOrNull() ?: return null
        return RecurringRule(id, form.title, form.description, draft, form.frequency, form.startDate, form.endDate, form.postedThrough, form.paused)
    }
}

class RecurringEditorViewModel(
    private val ledger: LedgerRepository,
    private val recurring: RecurringRepository,
    private val undo: UndoManager,
    route: RecurringEditorRoute,
) : ViewModel() {
    val ruleId: String = route.ruleId ?: RecurringRepository.newRuleId()
    private val isEdit = route.ruleId != null
    private val form = MutableStateFlow(RecurringForm())
    private val status = MutableStateFlow(RecurringEditorUiState(isEdit = isEdit))
    val lines = BookingLines(onUserChange = { status.update { it.copy(modified = true, error = null) } })

    val state: StateFlow<RecurringEditorUiState> = combine(form, lines.lines, ledger.accounts, status) { f, l, accounts, s ->
        s.copy(form = f, lines = l, accounts = accounts)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RecurringEditorUiState(isEdit = isEdit))

    init {
        viewModelScope.launch {
            when {
                route.ruleId != null -> recurring.getRule(route.ruleId)?.let { rule ->
                    form.value = RecurringForm(rule.title, rule.description, rule.frequency, rule.startDate, rule.endDate, rule.paused, rule.lastPostedDate)
                    lines.load(rule.lines.mapNotNull { line ->
                        ledger.getAccount(line.accountId)?.let { Entry("", it, line.amount, line.type) }
                    })
                }
                route.fromTransactionId != null -> ledger.getTransaction(route.fromTransactionId)?.let { tx ->
                    form.value = RecurringForm(tx.title, tx.description, Frequency.Monthly, tx.date, postedThrough = tx.date)
                    // A rule has no periods: spread lines repeat as plain lines on their own account.
                    lines.loadDraft(ledger.editableLines(tx).map { it.copy(period = null) })
                }
            }
            status.update { it.copy(loading = false) }
        }
    }

    private fun edit(transform: (RecurringForm) -> RecurringForm) {
        form.update(transform)
        status.update { it.copy(modified = true, error = null) }
    }

    fun setTitle(value: String) = edit { it.copy(title = value) }
    fun setDescription(value: String) = edit { it.copy(description = value) }
    fun setFrequency(value: Frequency) = edit { it.copy(frequency = value) }
    fun setStartDate(value: LocalDate) = edit { it.copy(startDate = value) }
    fun setEndDate(value: LocalDate?) = edit { it.copy(endDate = value) }
    fun setPaused(value: Boolean) = edit { it.copy(paused = value) }

    suspend fun createAccount(number: Int, name: String, type: AccountType): Account = ledger.createAccount(number, name, type)

    fun save() {
        val rule = state.value.preview(ruleId) ?: run {
            status.update { it.copy(error = Lines.toDraft(lines.current).exceptionOrNull()?.message ?: "Complete the lines first.") }
            return
        }
        status.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                recurring.save(rule)
                status.update { it.copy(saving = false, done = true) }
            } catch (e: Exception) {
                status.update { it.copy(saving = false, error = e.message) }
            }
        }
    }

    fun delete() = viewModelScope.launch {
        val deleted = recurring.delete(ruleId)
        if (deleted != null) undo.offer("Stopped “${deleted.title}”") { recurring.restore(deleted) }
        status.update { it.copy(done = true) }
    }
}

@Composable
fun RecurringEditorScreen(route: RecurringEditorRoute, onDone: () -> Unit) {
    val vm = appViewModel { RecurringEditorViewModel(it.ledger, it.recurring, it.undo, route) }
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickingLine by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(state.done) { if (state.done) onDone() }
    BackHandler(enabled = state.modified && !state.done) { confirmDiscard = true }
    val close = { if (state.modified) confirmDiscard = true else onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEdit) "Edit recurring" else "New recurring") },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Rounded.Close, "Close") } },
                actions = {
                    if (state.isEdit) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, "Stop recurring") }
                    TextButton(onClick = vm::save, enabled = state.canSave && (state.modified || !state.isEdit)) { Text("Save") }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.form.title,
                onValueChange = vm::setTitle,
                label = { Text("Title") },
                placeholder = { Text("e.g. Rent") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.form.description,
                onValueChange = vm::setDescription,
                label = { Text("Description (optional)") },
                minLines = 1,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader("Schedule")
            SegmentedChoice(Frequency.entries, state.form.frequency, vm::setFrequency, { it.label })
            DateField(if (state.isEdit) "Starts" else "First date", state.form.startDate, vm::setStartDate, Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ends on a date", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = state.form.endDate != null,
                    onCheckedChange = { on -> vm.setEndDate(if (on) state.form.startDate.plusYears(1) else null) },
                )
            }
            state.form.endDate?.let { end -> DateField("Last date", end, vm::setEndDate, Modifier.fillMaxWidth()) }
            if (state.isEdit) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Paused", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Nothing is posted while paused; dates passed meanwhile are posted when resumed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.form.paused, onCheckedChange = vm::setPaused)
                }
            }
            SchedulePreview(state, vm.ruleId)

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
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = vm::save, enabled = state.canSave && (state.modified || !state.isEdit), modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(if (state.isEdit) "Update" else "Start recurring")
            }
        }
    }

    pickingLine?.let { key ->
        AccountPickerSheet(
            accounts = state.accounts,
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
    if (confirmDelete) {
        ConfirmDialog(
            title = "Stop recurring?",
            message = "No more transactions will be posted. Transactions it already posted are kept.",
            confirmLabel = "Stop",
            destructive = true,
            onConfirm = { confirmDelete = false; vm.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** What saving will do: how many past dates get posted now, and when the next one is. */
@Composable
private fun SchedulePreview(state: RecurringEditorUiState, ruleId: String) {
    val today = LocalDate.now()
    // For an existing rule only dates after its last posting count; the stored position is kept on save.
    val rule = (state.preview(ruleId) ?: RecurringRule(ruleId, state.form.title, "", emptyList(), state.form.frequency, state.form.startDate, state.form.endDate, state.form.postedThrough, state.form.paused))
    val due = Recurrence.due(rule, today).size
    val next = Recurrence.next(rule, today)
    val text = buildString {
        append(Recurrence.summary(rule)).append(". ")
        when {
            rule.paused -> append("Paused.")
            due > 0 -> append("Saving posts $due transaction${if (due == 1) "" else "s"} for dates up to today")
                .append(next?.let { ", then the next on ${it.formatMedium()}." } ?: ".")
            next != null -> append("Next on ${next.formatMedium()}.")
            else -> append("No more dates.")
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
