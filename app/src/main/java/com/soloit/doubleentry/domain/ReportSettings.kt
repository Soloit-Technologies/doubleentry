package com.soloit.doubleentry.domain

data class BalanceSheetSettings(
    val assetGroups: List<AccountGroup> = emptyList(),
    val liabilityGroups: List<AccountGroup> = emptyList(),
)

data class IncomeStatementSettings(
    val revenueGroups: List<AccountGroup> = emptyList(),
    val expenseGroups: List<AccountGroup> = emptyList(),
    /** Prefixes of depreciation expense accounts (excluded from EBDA/EBITDA subtotals). */
    val depreciationPrefixes: List<String> = emptyList(),
    /** Prefixes of tax expense accounts (excluded from EBT/EBITDA subtotals). */
    val taxPrefixes: List<String> = emptyList(),
    /** Prefixes of non-spendable asset accounts, e.g. locked pension savings. */
    val deferredAssetPrefixes: List<String> = emptyList(),
)

data class CashflowSettings(
    val cashGroups: List<AccountGroup> = DEFAULT_CASH_GROUPS,
) {
    companion object {
        val DEFAULT_CASH_GROUPS = listOf(
            AccountGroup("Cash & Bank", listOf("191", "193", "194")),
            AccountGroup("Investments", listOf("195", "197")),
        )
    }
}

data class InvoiceSettings(
    /** Account number every new invoice credits (locked line), or null for manual booking. */
    val creditAccountNumber: Int? = null,
    /** Account numbers allowed as payment sources; empty means any other account. */
    val paymentAccountNumbers: List<Int> = emptyList(),
)
