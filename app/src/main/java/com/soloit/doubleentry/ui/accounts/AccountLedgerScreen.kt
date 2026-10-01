package com.soloit.doubleentry.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.firstDay
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.domain.lastDay
import com.soloit.doubleentry.ui.AccountLedgerRoute
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.AccountTypeAvatar
import com.soloit.doubleentry.ui.components.AmountTone
import com.soloit.doubleentry.ui.components.ConfirmDialog
import com.soloit.doubleentry.ui.components.EmptyState
import com.soloit.doubleentry.ui.components.GroupedItem
import com.soloit.doubleentry.ui.components.LoadingState
import com.soloit.doubleentry.ui.components.MessageDialog
import com.soloit.doubleentry.ui.components.MoneyText
import com.soloit.doubleentry.ui.components.MonthSelector
import com.soloit.doubleentry.ui.components.ScreenPadding
import com.soloit.doubleentry.ui.components.SearchField
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.theme.tabular
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class LedgerRowUi(val transactionId: String, val date: LocalDate, val title: String, val amount: Long, val balance: Long)

data class AccountLedgerUiState(
    val loading: Boolean = true,
    val account: Account? = null,
    val month: YearMonth = YearMonth.now(),
    val query: String = "",
    val opening: Long = 0,
    val closing: Long = 0,
    val rows: List<LedgerRowUi> = emptyList(),
)

class AccountLedgerViewModel(private val ledger: LedgerRepository, private val accountId: String, initial: YearMonth) : ViewModel() {
    private val month = MutableStateFlow(initial)
    private val query = MutableStateFlow("")
    val message = MutableStateFlow<String?>(null)
    val deleted = MutableStateFlow(false)

    private val loaded = combine(month, ledger.changes) { m, _ -> m }.mapLatest { m ->
        val account = ledger.getAccount(accountId) ?: return@mapLatest AccountLedgerUiState(loading = false, month = m)
        val opening = ledger.getBalances(m.firstDay().minusDays(1))[accountId] ?: 0
        var running = opening
        val rows = ledger.getTransactions(m.firstDay(), m.lastDay(), accountId).map { tx ->
            val amount = tx.signedAmountFor(account)
            running += amount
            LedgerRowUi(tx.id, tx.date, tx.title, amount, running)
        }
        AccountLedgerUiState(loading = false, account = account, month = m, opening = opening, closing = running, rows = rows.reversed())
    }

    val state: StateFlow<AccountLedgerUiState> = combine(loaded, query) { s, q ->
        s.copy(query = q, rows = if (q.isBlank()) s.rows else s.rows.filter { it.title.contains(q.trim(), ignoreCase = true) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountLedgerUiState(month = initial))

    fun setMonth(value: YearMonth) { month.value = value }
    fun setQuery(value: String) { query.value = value }

    /** Returns false (and shows why) when the account can't be deleted. */
    suspend fun canDelete(): Boolean {
        if (ledger.accountHasTransactions(accountId)) {
            message.value = "This account has transactions. Delete or move them before deleting the account."
            return false
        }
        return true
    }

    fun delete() = viewModelScope.launch {
        try {
            ledger.deleteAccount(accountId)
            deleted.value = true
        } catch (e: Exception) {
            message.value = e.message
        }
    }
}

@Composable
fun AccountLedgerScreen(
    route: AccountLedgerRoute,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenTransaction: (String) -> Unit,
) {
    val vm = appViewModel { AccountLedgerViewModel(it.ledger, route.accountId, YearMonth.of(route.year, route.month)) }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var requestDelete by remember { mutableStateOf(false) }

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
                            text = { Text("Delete account") },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                            onClick = { menu = false; requestDelete = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = ScreenPadding) {
            item { MonthSelector(state.month, vm::setMonth, Modifier.padding(bottom = 12.dp)) }
            if (state.loading) {
                item { LoadingState() }
                return@LazyColumn
            }
            item {
                SectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
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
            item { SearchField(state.query, vm::setQuery, "Search transactions", Modifier.padding(vertical = 12.dp)) }
            if (state.rows.isEmpty()) {
                item { EmptyState(Icons.AutoMirrored.Rounded.ReceiptLong, "No transactions", "Nothing was booked on this account in the selected month.") }
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

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete account?",
            message = "Delete '${state.account?.number} ${state.account?.name}'? This cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { confirmDelete = false; vm.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
    message?.let { MessageDialog("Can't delete account", it) { vm.message.value = null } }
}
