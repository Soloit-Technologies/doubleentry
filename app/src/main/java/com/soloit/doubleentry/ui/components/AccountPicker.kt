package com.soloit.doubleentry.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import kotlinx.coroutines.launch

/**
 * Modal sheet for choosing an account: search by name or number, filter by type, quick access to
 * the most used accounts and (optionally) creating a new account inline.
 */
@Composable
fun AccountPickerSheet(
    accounts: List<Account>,
    onSelect: (Account) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Choose account",
    mostUsed: List<Account> = emptyList(),
    selectedId: String? = null,
    onCreateAccount: (suspend (number: Int, name: String, type: AccountType) -> Account)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<AccountType?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }

    val types = remember(accounts) { AccountType.entries.filter { t -> accounts.any { it.type == t } } }
    val search = query.trim()
    val filtered = accounts
        .filter { filter == null || it.type == filter }
        .filter { search.isEmpty() || it.number.toString().startsWith(search) || it.name.contains(search, ignoreCase = true) }
        .sortedBy { it.number }
    val quick = if (search.isEmpty()) mostUsed.filter { filter == null || it.type == filter } else emptyList()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxHeight(0.92f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (onCreateAccount != null) {
                    TextButton(onClick = { creating = true }) {
                        Icon(Icons.Rounded.Add, null)
                        Text("New")
                    }
                }
            }
            SearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Search name or number",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (types.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
                    }
                    items(types) { type ->
                        FilterChip(
                            selected = filter == type,
                            onClick = { filter = if (filter == type) null else type },
                            label = { Text(type.name) },
                            leadingIcon = { SmallIcon(type.icon()) },
                            colors = FilterChipDefaults.filterChipColors(selectedLeadingIconColor = type.color()),
                        )
                    }
                }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp, top = 8.dp)) {
                if (quick.isNotEmpty()) {
                    item { PickerHeader("Most used") }
                    items(quick, key = { "q" + it.id }) { AccountPickerRow(it, it.id == selectedId) { onSelect(it) } }
                    item { PickerHeader("All accounts") }
                }
                items(filtered, key = { it.id }) { AccountPickerRow(it, it.id == selectedId) { onSelect(it) } }
                if (filtered.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Rounded.SearchOff,
                            title = "No matching accounts",
                            message = if (onCreateAccount != null) "Try another search or create a new account." else "Try another search.",
                        )
                    }
                }
            }
        }
    }

    if (creating && onCreateAccount != null) {
        NewAccountDialog(
            initialType = filter ?: AccountType.Expense,
            onDismiss = { creating = false },
            onCreate = { number, name, type ->
                val account = onCreateAccount(number, name, type)
                creating = false
                onSelect(account)
            },
        )
    }
}

@Composable
private fun PickerHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun AccountPickerRow(account: Account, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(account.name) },
        supportingContent = { Text("#${account.number} · ${account.type.name}") },
        leadingContent = { AccountTypeAvatar(account.type) },
        trailingContent = if (selected) ({ Icon(Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.primary) }) else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp),
    )
}

/** Inline account creation used from pickers. */
@Composable
fun NewAccountDialog(
    initialType: AccountType,
    onDismiss: () -> Unit,
    onCreate: suspend (number: Int, name: String, type: AccountType) -> Unit,
) {
    var number by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(initialType) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val parsedNumber = number.trim().toIntOrNull()?.takeIf { it > 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = number,
                    onValueChange = { v -> number = v.filter(Char::isDigit) },
                    label = { Text("Number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.name) }, leadingIcon = { SmallIcon(t.icon()) })
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && parsedNumber != null && name.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            onCreate(parsedNumber!!, name.trim(), type)
                        } catch (e: Exception) {
                            error = e.message ?: "Could not create the account."
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
