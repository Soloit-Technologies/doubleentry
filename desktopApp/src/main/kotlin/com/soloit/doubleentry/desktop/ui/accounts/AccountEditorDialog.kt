package com.soloit.doubleentry.desktop.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.soloit.doubleentry.desktop.ui.LocalApp
import com.soloit.doubleentry.desktop.ui.components.rememberViewModel
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.ui.accounts.AccountEditorViewModel
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.ComboBox
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField

@Composable
fun AccountEditorDialog(accountId: String?, onDismiss: () -> Unit) {
    val core = LocalApp.current.core
    val vm = rememberViewModel(accountId) { AccountEditorViewModel(core.ledger, accountId) }
    val state by vm.state.collectAsState()
    LaunchedEffect(state.done) { if (state.done) onDismiss() }
    ContentDialog(
        title = if (state.isEdit) "Edit account" else "New account",
        visible = true,
        content = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(state.number, vm::setNumber, Modifier.fillMaxWidth(), singleLine = true, header = { Text("Number") })
                TextField(state.name, vm::setName, Modifier.fillMaxWidth(), singleLine = true, header = { Text("Name") })
                ComboBox(
                    header = "Type",
                    items = AccountType.entries.map { it.name },
                    selected = state.type.ordinal,
                    onSelectionChange = { index, _ -> vm.setType(AccountType.entries[index]) },
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let { Text(it, color = FluentTheme.colors.system.critical) }
            }
        },
        primaryButtonText = "Save",
        closeButtonText = "Cancel",
        onButtonClick = { button -> if (button == ContentDialogButton.Primary) vm.save() else onDismiss() },
    )
}
