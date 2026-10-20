package com.soloit.doubleentry.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.AccountTypeAvatar
import com.soloit.doubleentry.ui.components.GroupedItem
import com.soloit.doubleentry.ui.components.isLargeFont

private val typeDescriptions = mapOf(
    AccountType.Asset to "Things you own: cash, bank accounts, investments, property.",
    AccountType.Liability to "What you owe: loans, mortgages, credit cards, unpaid bills.",
    AccountType.Equity to "Your net worth and opening balances.",
    AccountType.Revenue to "Money coming in: salary, interest, dividends, gifts.",
    AccountType.Expense to "Money going out: rent, food, transport, subscriptions.",
)

@Composable
fun AccountEditorScreen(accountId: String?, onDone: () -> Unit) {
    val vm = appViewModel { AccountEditorViewModel(it.ledger, accountId) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEdit) "Edit account" else "New account") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Rounded.Close, "Close") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // A large font needs more room for the number than a fixed width gives, so the fields stack.
            val stacked = isLargeFont()
            val fields = @Composable { numberModifier: Modifier, nameModifier: Modifier ->
                OutlinedTextField(
                    value = state.number,
                    onValueChange = vm::setNumber,
                    label = { Text("Number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = numberModifier,
                )
                OutlinedTextField(
                    value = state.name,
                    onValueChange = vm::setName,
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = nameModifier,
                )
            }
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { fields(Modifier.fillMaxWidth(), Modifier.fillMaxWidth()) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { fields(Modifier.width(120.dp), Modifier.weight(1f)) }
            }
            Text("Type", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Column {
                AccountType.entries.forEachIndexed { index, type ->
                    GroupedItem(index, AccountType.entries.size) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = state.type == type, onClick = { vm.setType(type) }, role = Role.RadioButton)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AccountTypeAvatar(type)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(type.name, style = MaterialTheme.typography.bodyLarge)
                                Text(typeDescriptions.getValue(type), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            RadioButton(selected = state.type == type, onClick = null)
                        }
                    }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = vm::save, enabled = state.canSave, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.isEdit) "Save changes" else "Create account")
            }
        }
    }
}
