package com.soloit.doubleentry.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountGroup
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.BalanceSheetSettings
import com.soloit.doubleentry.domain.CashflowSettings
import com.soloit.doubleentry.domain.IncomeStatementSettings
import com.soloit.doubleentry.domain.matchesAnyPrefix
import com.soloit.doubleentry.domain.parsePrefixes
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.components.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An editable group row; [prefixes] is the raw comma separated text. */
data class GroupDraft(val key: Long, val name: String, val prefixes: String)

/** Editable list of groups shared by the report settings screens. */
class GroupDrafts {
    private var nextKey = 0L
    val items = MutableStateFlow<List<GroupDraft>>(emptyList())

    fun load(groups: List<AccountGroup>) { items.value = groups.map { GroupDraft(nextKey++, it.name, it.prefixes.joinToString(", ")) } }
    fun add() = items.update { it + GroupDraft(nextKey++, "", "") }
    fun remove(key: Long) = items.update { list -> list.filterNot { it.key == key } }
    fun setName(key: Long, value: String) = items.update { list -> list.map { if (it.key == key) it.copy(name = value) else it } }
    fun setPrefixes(key: Long, value: String) = items.update { list -> list.map { if (it.key == key) it.copy(prefixes = value) else it } }

    /** Groups without a name are dropped, as in the original app. */
    fun toGroups(): List<AccountGroup> = items.value.filter { it.name.isNotBlank() }.map { AccountGroup(it.name.trim(), parsePrefixes(it.prefixes)) }
}

abstract class ReportSettingsViewModel(ledger: LedgerRepository) : ViewModel() {
    val accounts: StateFlow<List<Account>> = ledger.accounts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val saved = MutableStateFlow(0)
    protected fun save(block: suspend () -> Unit) = viewModelScope.launch { block(); saved.update { it + 1 } }
}

class BalanceSheetSettingsViewModel(private val settings: SettingsRepository, ledger: LedgerRepository) : ReportSettingsViewModel(ledger) {
    val assets = GroupDrafts()
    val liabilities = GroupDrafts()

    init {
        viewModelScope.launch {
            val s = settings.balanceSheet.first()
            assets.load(s.assetGroups)
            liabilities.load(s.liabilityGroups)
        }
    }

    fun save() = save { settings.saveBalanceSheet(BalanceSheetSettings(assets.toGroups(), liabilities.toGroups())) }
}

class IncomeStatementSettingsViewModel(private val settings: SettingsRepository, ledger: LedgerRepository) : ReportSettingsViewModel(ledger) {
    val revenue = GroupDrafts()
    val expenses = GroupDrafts()
    val depreciation = MutableStateFlow("")
    val tax = MutableStateFlow("")
    val deferred = MutableStateFlow("")

    init {
        viewModelScope.launch {
            val s = settings.incomeStatement.first()
            revenue.load(s.revenueGroups)
            expenses.load(s.expenseGroups)
            depreciation.value = s.depreciationPrefixes.joinToString(", ")
            tax.value = s.taxPrefixes.joinToString(", ")
            deferred.value = s.deferredAssetPrefixes.joinToString(", ")
        }
    }

    fun save() = save {
        settings.saveIncomeStatement(
            IncomeStatementSettings(
                revenueGroups = revenue.toGroups(),
                expenseGroups = expenses.toGroups(),
                depreciationPrefixes = parsePrefixes(depreciation.value),
                taxPrefixes = parsePrefixes(tax.value),
                deferredAssetPrefixes = parsePrefixes(deferred.value),
            ),
        )
    }
}

class CashflowSettingsViewModel(private val settings: SettingsRepository, ledger: LedgerRepository) : ReportSettingsViewModel(ledger) {
    val groups = GroupDrafts()

    init {
        viewModelScope.launch { groups.load(settings.cashflow.first().cashGroups) }
    }

    fun resetToDefaults() = groups.load(CashflowSettings.DEFAULT_CASH_GROUPS)

    fun save() = save { settings.saveCashflow(CashflowSettings(groups.toGroups())) }
}

@Composable
private fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    onSave: () -> Unit,
    savedCount: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(savedCount) { if (savedCount > 0) snackbar.showSnackbar("Settings saved") }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { TextButton(onClick = onSave) { Text("Save") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun GroupEditor(drafts: GroupDrafts, accounts: List<Account>, type: AccountType, addLabel: String) {
    val items by drafts.items.collectAsStateWithLifecycle()
    val candidates = accounts.filter { it.type == type }
    items.forEach { group ->
        SectionCard(contentPadding = PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = group.name,
                    onValueChange = { drafts.setName(group.key, it) },
                    label = { Text("Group name") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { drafts.remove(group.key) }) { Icon(Icons.Rounded.DeleteOutline, "Remove group") }
            }
            Spacer(Modifier.padding(top = 8.dp))
            PrefixField(group.prefixes, { drafts.setPrefixes(group.key, it) }, "Account number prefixes", candidates)
        }
    }
    OutlinedButton(onClick = drafts::add, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.Add, null)
        Spacer(Modifier.width(8.dp))
        Text(addLabel)
    }
}

/** Comma separated prefix input that previews which accounts it matches. */
@Composable
private fun PrefixField(value: String, onValueChange: (String) -> Unit, label: String, candidates: List<Account>) {
    val matches = candidates.filter { matchesAnyPrefix(it.number, parsePrefixes(value)) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text("e.g. 19, 1930") },
        singleLine = true,
        supportingText = {
            Text(
                when {
                    value.isBlank() -> "Comma separated, e.g. 19 matches 1910, 1920 …"
                    matches.isEmpty() -> "No matching accounts"
                    else -> "Matches ${matches.size}: " + matches.take(4).joinToString { "${it.number} ${it.name}" } + if (matches.size > 4) " …" else ""
                },
                maxLines = 2,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun BalanceSheetSettingsScreen(onBack: () -> Unit) {
    val vm = appViewModel { BalanceSheetSettingsViewModel(it.settings, it.ledger) }
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    SettingsScaffold("Balance sheet", onBack, vm::save, saved) {
        Hint("Group accounts on the balance sheet by account-number prefix. Accounts that match no group are listed under \"Other\". Leave empty to list every account.")
        SectionHeader("Asset groups")
        GroupEditor(vm.assets, accounts, AccountType.Asset, "Add asset group")
        SectionHeader("Liability groups")
        GroupEditor(vm.liabilities, accounts, AccountType.Liability, "Add liability group")
    }
}

@Composable
fun IncomeStatementSettingsScreen(onBack: () -> Unit) {
    val vm = appViewModel { IncomeStatementSettingsViewModel(it.settings, it.ledger) }
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val depreciation by vm.depreciation.collectAsStateWithLifecycle()
    val tax by vm.tax.collectAsStateWithLifecycle()
    val deferred by vm.deferred.collectAsStateWithLifecycle()
    val expenseAccounts = accounts.filter { it.type == AccountType.Expense }
    SettingsScaffold("Income statement", onBack, vm::save, saved) {
        Hint("Groups apply to the income statement; expense groups are also used by the cost trends report.")
        SectionHeader("Revenue groups")
        GroupEditor(vm.revenue, accounts, AccountType.Revenue, "Add revenue group")
        SectionHeader("Expense groups")
        GroupEditor(vm.expenses, accounts, AccountType.Expense, "Add expense group")
        SectionHeader("Special accounts")
        SectionCard(contentPadding = PaddingValues(12.dp)) {
            PrefixField(depreciation, { vm.depreciation.value = it }, "Depreciation accounts", expenseAccounts)
            Hint("Excluded from \"expenses before depreciation\", EBDA and EBITDA.")
            Spacer(Modifier.padding(top = 8.dp))
            PrefixField(tax, { vm.tax.value = it }, "Tax accounts", expenseAccounts)
            Hint("Excluded from EBT and EBITDA, and used for \"revenue net of income tax\".")
            Spacer(Modifier.padding(top = 8.dp))
            PrefixField(deferred, { vm.deferred.value = it }, "Deferred (non-spendable) asset accounts", accounts.filter { it.type == AccountType.Asset })
            Hint("For example locked pension savings. Revenue paid straight into these accounts is subtracted to show your spendable net income.")
        }
    }
}

@Composable
fun CashflowSettingsScreen(onBack: () -> Unit) {
    val vm = appViewModel { CashflowSettingsViewModel(it.settings, it.ledger) }
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    SettingsScaffold("Cash flow", onBack, vm::save, saved) {
        Hint("Asset accounts matching these groups count as cash. Transfers inside a group cancel out; moves between groups are shown as flows.")
        SectionHeader("Cash groups")
        GroupEditor(vm.groups, accounts, AccountType.Asset, "Add cash group")
        TextButton(onClick = vm::resetToDefaults) { Text("Reset to default groups") }
    }
}
