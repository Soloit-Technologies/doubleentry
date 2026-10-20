package com.soloit.doubleentry.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Puts [stored] in a settings form and keeps it there as it changes: a sync can bring another
 * device's edit while the form is open. Once the form has unsaved edits it's left alone, and Save
 * overwrites. [form] reads what the form holds, in the stored shape; [show] fills the form.
 */
fun <T> ViewModel.showStored(stored: Flow<T>, form: () -> T, show: (T) -> Unit) {
    viewModelScope.launch {
        var shown: Any? = Unset
        stored.collect { value ->
            if (shown === Unset || form() == shown) {
                show(value)
                // As the form reads it back, which can be normalized (trimmed, sorted).
                shown = form()
            } else if (form() == value) {
                // Just saved: the form is the stored value again.
                shown = value
            }
        }
    }
}

private object Unset
