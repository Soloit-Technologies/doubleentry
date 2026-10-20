package com.soloit.doubleentry.ui.assets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.AssetRepository
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.UndoManager
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.Asset
import com.soloit.doubleentry.domain.Transaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class AssetDetailUiState(
    val loading: Boolean = true,
    val asset: Asset? = null,
    val debitAccount: Account? = null,
    val creditAccount: Account? = null,
    val writeoffs: List<Transaction> = emptyList(),
)

class AssetDetailViewModel(
    private val assets: AssetRepository,
    private val ledger: LedgerRepository,
    private val undo: UndoManager,
    private val id: String,
) : ViewModel() {
    val message = MutableStateFlow<String?>(null)
    val deleted = MutableStateFlow(false)

    val state: StateFlow<AssetDetailUiState> = ledger.changes.mapLatest {
        val asset = assets.getAsset(id) ?: return@mapLatest AssetDetailUiState(loading = false)
        AssetDetailUiState(
            loading = false,
            asset = asset,
            debitAccount = ledger.getAccount(asset.debitAccountId),
            creditAccount = ledger.getAccount(asset.creditAccountId),
            writeoffs = assets.getWriteoffTransactions(id),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetDetailUiState())

    init {
        viewModelScope.launch { runCatching { assets.processWriteoffs(LocalDate.now()) } }
    }

    fun delete() = viewModelScope.launch {
        try {
            val name = state.value.asset?.name ?: "asset"
            val stamp = assets.deleteAsset(id)
            undo.offerRestore("Deleted $name", ledger, stamp)
            deleted.value = true
        } catch (e: Exception) {
            message.value = e.message
        }
    }
}
