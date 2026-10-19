package com.soloit.doubleentry.desktop.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * A shared screen ViewModel that lives as long as this place in the UI shows the same [keys]
 * (the open ledger, the account shown); it's cleared when they change or the UI leaves.
 */
@Composable
inline fun <reified VM : ViewModel> rememberViewModel(vararg keys: Any?, crossinline create: () -> VM): VM {
    val store = remember(*keys) { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    return remember(store) { ViewModelProvider.create(store, viewModelFactory { initializer { create() } })[VM::class] }
}
