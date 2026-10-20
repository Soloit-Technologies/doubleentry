package com.soloit.doubleentry.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.CostPeriodAccounts
import com.soloit.doubleentry.domain.Periodization.Interim
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.assets.AccountSelector
import com.soloit.doubleentry.ui.components.AccountPickerSheet
import com.soloit.doubleentry.ui.components.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CostPeriodSettingsUiState(
    val accounts: List<Account> = emptyList(),
    val chosen: CostPeriodAccounts = CostPeriodAccounts(),
) {
    /** The chosen account; null while none is chosen. */
    fun accountFor(interim: Interim): Account? =
        chosen.numberFor(interim)?.let { number -> accounts.firstOrNull { it.number == number } }
}

class CostPeriodSettingsViewModel(private val settings: SettingsRepository, ledger: LedgerRepository) : ViewModel() {
    private val chosen = MutableStateFlow(CostPeriodAccounts())
    val saved = MutableStateFlow(0)

    /** Interim accounts sit on the balance sheet. */
    val state: StateFlow<CostPeriodSettingsUiState> = combine(ledger.accounts, chosen) { a, c ->
        CostPeriodSettingsUiState(a.filter { it.type == AccountType.Asset || it.type == AccountType.Liability }, c)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CostPeriodSettingsUiState())

    init {
        showStored(settings.costPeriodAccounts, { chosen.value }) { chosen.value = it }
    }

    fun set(interim: Interim, number: Int?) = chosen.update {
        when (interim) {
            Interim.PrepaidExpenses -> it.copy(prepaidExpenses = number)
            Interim.AccruedExpenses -> it.copy(accruedExpenses = number)
            Interim.AccruedIncome -> it.copy(accruedIncome = number)
            Interim.DeferredIncome -> it.copy(deferredIncome = number)
        }
    }

    fun save() = viewModelScope.launch {
        settings.saveCostPeriodAccounts(chosen.value)
        saved.update { it + 1 }
    }
}

private val Interim.title: String
    get() = when (this) {
        Interim.PrepaidExpenses -> "Prepaid expenses"
        Interim.AccruedExpenses -> "Accrued expenses"
        Interim.AccruedIncome -> "Accrued income"
        Interim.DeferredIncome -> "Income received in advance"
    }

private val Interim.explanation: String
    get() = when (this) {
        Interim.PrepaidExpenses -> "Paid now, cost in a later month (asset)"
        Interim.AccruedExpenses -> "Cost in an earlier month, paid now (liability)"
        Interim.AccruedIncome -> "Earned in an earlier month, received now (asset)"
        Interim.DeferredIncome -> "Received now, earned in a later month (liability)"
    }

@Composable
fun CostPeriodSettingsScreen(onBack: () -> Unit) {
    val vm = appViewModel { CostPeriodSettingsViewModel(it.settings, it.ledger) }
    val state by vm.state.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var picking by remember { mutableStateOf<Interim?>(null) }
    LaunchedEffect(saved) { if (saved > 0) snackbar.showSnackbar("Cost period accounts saved") }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Cost periods") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { TextButton(onClick = { vm.save() }) { Text("Save") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp)) {
            item {
                Text(
                    "An expense or income line can belong to other months than its transaction date, such as insurance " +
                        "paid in September for October to December. The part outside the transaction's month is held on " +
                        "one of these accounts and moved to the line's account by a posting at the end of each month.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Interim.entries.forEach { interim ->
                item(key = interim.name) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(interim.title)
                        Text(interim.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AccountSelector(
                            interim.title,
                            "Needed to spread lines over this kind of period",
                            state.accountFor(interim),
                        ) { picking = interim }
                    }
                }
            }
        }
    }

    picking?.let { interim ->
        AccountPickerSheet(
            title = interim.title,
            accounts = state.accounts,
            selectedId = state.accountFor(interim)?.id,
            onSelect = { vm.set(interim, it.number); picking = null },
            onDismiss = { picking = null },
        )
    }
}
