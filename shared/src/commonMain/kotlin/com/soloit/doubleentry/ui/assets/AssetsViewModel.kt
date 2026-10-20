package com.soloit.doubleentry.ui.assets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.AssetRepository
import com.soloit.doubleentry.domain.Asset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class AssetsUiState(
    val loading: Boolean = true,
    val query: String = "",
    val assets: List<Asset> = emptyList(),
    val totalCost: Long = 0,
    val totalValue: Long = 0,
)

class AssetsViewModel(private val repository: AssetRepository) : ViewModel() {
    private val query = MutableStateFlow("")

    val state: StateFlow<AssetsUiState> = combine(repository.assets, query) { assets, q ->
        AssetsUiState(
            loading = false,
            query = q,
            assets = if (q.isBlank()) assets else assets.filter { it.name.contains(q.trim(), ignoreCase = true) },
            totalCost = assets.sumOf { it.cost },
            totalValue = assets.sumOf { it.currentValue },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetsUiState())

    init {
        // Post any write-offs that became due so the values shown are current.
        viewModelScope.launch { runCatching { repository.processWriteoffs(LocalDate.now()) } }
    }

    fun setQuery(value: String) { query.value = value }
}
