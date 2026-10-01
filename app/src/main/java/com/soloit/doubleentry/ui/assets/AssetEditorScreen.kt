package com.soloit.doubleentry.ui.assets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.AssetRepository
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.Depreciation
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.shortLabel
import com.soloit.doubleentry.domain.yearMonth
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.AccountPickerSheet
import com.soloit.doubleentry.ui.components.AccountTypeAvatar
import com.soloit.doubleentry.ui.components.AmountField
import com.soloit.doubleentry.ui.components.DateField
import com.soloit.doubleentry.ui.components.IconAvatar
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.components.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class AssetForm(
    val name: String = "",
    val cost: String = "",
    val activationDate: LocalDate = LocalDate.now(),
    val usefulLifeMonths: String = "",
    val debitAccountId: String? = null,
    val creditAccountId: String? = null,
    val error: String? = null,
    val saving: Boolean = false,
    val done: Boolean = false,
) {
    val costCents: Long? get() = Money.parse(cost)?.takeIf { it > 0 }
    val months: Int? get() = usefulLifeMonths.trim().toIntOrNull()?.takeIf { it > 0 }
    val canSave: Boolean
        get() = !saving && name.isNotBlank() && costCents != null && months != null &&
            debitAccountId != null && creditAccountId != null && debitAccountId != creditAccountId
}

data class AssetEditorUiState(val form: AssetForm = AssetForm(), val accounts: List<Account> = emptyList()) {
    private val byId get() = accounts.associateBy { it.id }
    val debitAccount: Account? get() = form.debitAccountId?.let(byId::get)
    val creditAccount: Account? get() = form.creditAccountId?.let(byId::get)

    /** Depreciation expense accounts, or every account when none exist. */
    val debitChoices: List<Account> get() = accounts.filter { it.type == AccountType.Expense }.ifEmpty { accounts }

    /** Asset accounts, or every account when none exist. */
    val creditChoices: List<Account> get() = accounts.filter { it.type == AccountType.Asset }.ifEmpty { accounts }

    val preview: List<Depreciation.Slot>
        get() {
            val cost = form.costCents ?: return emptyList()
            val months = form.months ?: return emptyList()
            return Depreciation.schedule(form.activationDate, cost, months)
        }
}

class AssetEditorViewModel(private val assets: AssetRepository, ledger: LedgerRepository) : ViewModel() {
    private val form = MutableStateFlow(AssetForm())

    val state: StateFlow<AssetEditorUiState> = combine(form, ledger.accounts) { f, a -> AssetEditorUiState(f, a) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AssetEditorUiState())

    fun update(transform: (AssetForm) -> AssetForm) = form.update { transform(it).copy(error = null) }

    fun save() {
        val f = form.value
        if (!f.canSave) return
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                assets.createAsset(f.name, f.costCents!!, f.activationDate, f.months!!, f.debitAccountId!!, f.creditAccountId!!)
                // Back-dated assets get their past write-offs right away.
                assets.processWriteoffs(LocalDate.now())
                form.update { it.copy(saving = false, done = true) }
            } catch (e: Exception) {
                form.update { it.copy(saving = false, error = e.message) }
            }
        }
    }
}

private enum class PickerTarget { Debit, Credit }

@Composable
fun AssetEditorScreen(onDone: () -> Unit) {
    val vm = appViewModel { AssetEditorViewModel(it.assets, it.ledger) }
    val state by vm.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<PickerTarget?>(null) }
    val form = state.form
    LaunchedEffect(form.done) { if (form.done) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New asset") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Rounded.Close, "Close") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = form.name,
                onValueChange = { v -> vm.update { it.copy(name = v) } },
                label = { Text("Name") },
                placeholder = { Text("e.g. Car, Sofa, Laptop") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AmountField(form.cost, { v -> vm.update { it.copy(cost = v) } }, "Purchase price", Modifier.weight(1f))
                OutlinedTextField(
                    value = form.usefulLifeMonths,
                    onValueChange = { v -> vm.update { it.copy(usefulLifeMonths = v.filter(Char::isDigit)) } },
                    label = { Text("Useful life (months)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    supportingText = form.months?.let { { Text(formatYears(it)) } },
                    modifier = Modifier.weight(1f),
                )
            }
            DateField("In use since", form.activationDate, { d -> vm.update { it.copy(activationDate = d) } }, Modifier.fillMaxWidth())

            SectionHeader("Accounts")
            AccountSelector("Depreciation (debit)", "Usually a depreciation expense account", state.debitAccount) { picking = PickerTarget.Debit }
            AccountSelector("Asset (credit)", "The account holding the asset's value", state.creditAccount) { picking = PickerTarget.Credit }

            val preview = state.preview
            if (preview.isNotEmpty()) {
                SectionHeader("Schedule preview")
                SectionCard(contentPadding = PaddingValues(16.dp)) {
                    val first = preview.first { it.amount > 0 }
                    val regular = preview.getOrNull(1)?.amount ?: first.amount
                    Text("About ${Money.format(regular)} per month", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "First write-off ${Money.format(first.amount)} for ${first.month.yearMonth.shortLabel()} (prorated), " +
                            "last in ${preview.last().month.yearMonth.shortLabel()}. Posted automatically at each month end.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (form.debitAccountId != null && form.debitAccountId == form.creditAccountId) {
                Text("Debit and credit accounts must differ.", color = MaterialTheme.colorScheme.error)
            }
            form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = vm::save, enabled = form.canSave, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) { Text("Save asset") }
        }
    }

    picking?.let { target ->
        AccountPickerSheet(
            title = if (target == PickerTarget.Debit) "Depreciation account" else "Asset account",
            accounts = if (target == PickerTarget.Debit) state.debitChoices else state.creditChoices,
            selectedId = if (target == PickerTarget.Debit) form.debitAccountId else form.creditAccountId,
            onSelect = { account ->
                vm.update { if (target == PickerTarget.Debit) it.copy(debitAccountId = account.id) else it.copy(creditAccountId = account.id) }
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
internal fun AccountSelector(label: String, hint: String, account: Account?, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (account != null) AccountTypeAvatar(account.type) else IconAvatar(Icons.Rounded.UnfoldMore, MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(account?.display ?: "Choose account", style = MaterialTheme.typography.bodyLarge)
                if (account == null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.UnfoldMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
