package com.soloit.doubleentry.ui.settings

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.InvoiceSettings
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.assets.AccountSelector
import com.soloit.doubleentry.ui.components.AccountPickerSheet
import com.soloit.doubleentry.ui.components.AccountTypeAvatar
import com.soloit.doubleentry.ui.components.GroupedItem
import com.soloit.doubleentry.ui.components.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InvoiceSettingsUiState(
    val accounts: List<Account> = emptyList(),
    val creditAccountNumber: Int? = null,
    val paymentAccountNumbers: Set<Int> = emptySet(),
) {
    val creditAccount: Account? get() = accounts.firstOrNull { it.number == creditAccountNumber }
}

class InvoiceSettingsViewModel(private val settings: SettingsRepository, ledger: LedgerRepository) : ViewModel() {
    private val credit = MutableStateFlow<Int?>(null)
    private val payment = MutableStateFlow<Set<Int>>(emptySet())
    val saved = MutableStateFlow(0)

    /** Invoices are settled through balance-sheet accounts, so only assets and liabilities are offered. */
    val state: StateFlow<InvoiceSettingsUiState> = combine(ledger.accounts, credit, payment) { a, c, p ->
        InvoiceSettingsUiState(a.filter { it.type == AccountType.Asset || it.type == AccountType.Liability }, c, p)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, InvoiceSettingsUiState())

    private fun form() = InvoiceSettings(credit.value, payment.value.sorted())

    init {
        showStored(settings.invoice, ::form) { s ->
            credit.value = s.creditAccountNumber
            payment.value = s.paymentAccountNumbers.toSet()
        }
    }

    fun setCredit(number: Int?) { credit.value = number }
    fun togglePayment(number: Int) = payment.update { if (number in it) it - number else it + number }

    fun save() = viewModelScope.launch {
        settings.saveInvoice(form())
        saved.update { it + 1 }
    }
}

@Composable
fun InvoiceSettingsScreen(onBack: () -> Unit) {
    val vm = appViewModel { InvoiceSettingsViewModel(it.settings, it.ledger) }
    val state by vm.state.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(saved) { if (saved > 0) snackbar.showSnackbar("Invoice settings saved") }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Invoice defaults") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { TextButton(onClick = { vm.save() }) { Text("Save") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp)) {
            item { SectionHeader("Credit account") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Every new invoice credits this account (usually Accounts Payable) with the amount due, as a locked line.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AccountSelector("Credit account", "None — build the booking rows manually", state.creditAccount) { picking = true }
                    if (state.creditAccountNumber != null) TextButton(onClick = { vm.setCredit(null) }) { Text("Clear") }
                }
            }
            item { SectionHeader("Pay from") }
            item {
                Text(
                    "Accounts offered when marking an invoice as paid. Select none to allow every account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            itemsIndexed(state.accounts, key = { _, a -> a.id }) { index, account ->
                val checked = account.number in state.paymentAccountNumbers
                GroupedItem(index, state.accounts.size) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = checked, onValueChange = { vm.togglePayment(account.number) }, role = Role.Checkbox)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AccountTypeAvatar(account.type, size = 36)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(account.name, style = MaterialTheme.typography.bodyLarge)
                            Text("#${account.number}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(checked = checked, onCheckedChange = null)
                    }
                }
            }
        }
    }

    if (picking) {
        AccountPickerSheet(
            title = "Invoice credit account",
            accounts = state.accounts,
            selectedId = state.creditAccount?.id,
            onSelect = { vm.setCredit(it.number); picking = false },
            onDismiss = { picking = false },
        )
    }
}
