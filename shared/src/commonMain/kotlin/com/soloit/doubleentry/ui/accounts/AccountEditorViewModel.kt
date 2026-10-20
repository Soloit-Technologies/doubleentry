package com.soloit.doubleentry.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.domain.AccountType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountEditorUiState(
    val isEdit: Boolean,
    val number: String = "",
    val name: String = "",
    val type: AccountType = AccountType.Asset,
    val error: String? = null,
    val saving: Boolean = false,
    val done: Boolean = false,
) {
    val canSave: Boolean get() = !saving && (number.trim().toIntOrNull() ?: 0) > 0 && name.isNotBlank()
}

class AccountEditorViewModel(private val ledger: LedgerRepository, private val accountId: String?) : ViewModel() {
    private val _state = MutableStateFlow(AccountEditorUiState(isEdit = accountId != null))
    val state = _state.asStateFlow()

    init {
        if (accountId != null) viewModelScope.launch {
            ledger.getAccount(accountId)?.let { a -> _state.update { it.copy(number = a.number.toString(), name = a.name, type = a.type) } }
        }
    }

    fun setNumber(value: String) = _state.update { it.copy(number = value.filter(Char::isDigit), error = null) }
    fun setName(value: String) = _state.update { it.copy(name = value, error = null) }
    fun setType(value: AccountType) = _state.update { it.copy(type = value) }

    fun save() {
        val s = _state.value
        val number = s.number.trim().toIntOrNull()
        if (number == null || number <= 0) {
            _state.update { it.copy(error = "Please enter a valid positive account number.") }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                if (accountId != null) ledger.updateAccount(accountId, number, s.name, s.type) else ledger.createAccount(number, s.name, s.type)
                _state.update { it.copy(saving = false, done = true) }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.message) }
            }
        }
    }
}
