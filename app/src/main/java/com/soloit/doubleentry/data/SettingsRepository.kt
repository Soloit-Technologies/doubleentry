package com.soloit.doubleentry.data

import androidx.room.withTransaction
import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.data.local.UserSettingEntity
import com.soloit.doubleentry.data.local.nowMicros
import com.soloit.doubleentry.domain.AccountGroup
import com.soloit.doubleentry.domain.BalanceSheetSettings
import com.soloit.doubleentry.domain.CashflowSettings
import com.soloit.doubleentry.domain.DeterministicIds
import com.soloit.doubleentry.domain.IncomeStatementSettings
import com.soloit.doubleentry.domain.InvoiceSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Report and invoice configuration stored as synced rows in `user_settings`. Keys and JSON
 * payloads are identical to the .NET app (System.Text.Json, PascalCase record properties),
 * so both apps can read each other's settings.
 */
class SettingsRepository(
    private val db: AppDatabase,
    private val sync: SyncTrigger,
) {
    private val dao = db.settings()

    private val values: Flow<Map<String, String>> =
        dao.observeLive().map { rows -> rows.associate { it.key to it.value } }

    val balanceSheet: Flow<BalanceSheetSettings> = values.map(::balanceSheetFrom).distinctUntilChanged()
    val incomeStatement: Flow<IncomeStatementSettings> = values.map(::incomeStatementFrom).distinctUntilChanged()
    val cashflow: Flow<CashflowSettings> = values.map(::cashflowFrom).distinctUntilChanged()
    val invoice: Flow<InvoiceSettings> = values.map(::invoiceFrom).distinctUntilChanged()

    suspend fun saveBalanceSheet(settings: BalanceSheetSettings) = set(
        Keys.ASSET_GROUPS to encodeGroups(settings.assetGroups),
        Keys.LIABILITY_GROUPS to encodeGroups(settings.liabilityGroups),
    )

    suspend fun saveIncomeStatement(settings: IncomeStatementSettings) = set(
        Keys.REVENUE_GROUPS to encodeGroups(settings.revenueGroups),
        Keys.EXPENSE_GROUPS to encodeGroups(settings.expenseGroups),
        Keys.DEPRECIATION_PREFIXES to json.encodeToString(stringList, settings.depreciationPrefixes),
        Keys.TAX_PREFIXES to json.encodeToString(stringList, settings.taxPrefixes),
        Keys.DEFERRED_PREFIXES to json.encodeToString(stringList, settings.deferredAssetPrefixes),
    )

    suspend fun saveCashflow(settings: CashflowSettings) = set(
        Keys.CASH_GROUPS to encodeGroups(settings.cashGroups),
    )

    suspend fun saveInvoice(settings: InvoiceSettings) = set(
        Keys.INVOICE_CREDIT_ACCOUNT to settings.creditAccountNumber?.toString(),
        Keys.INVOICE_PAYMENT_ACCOUNTS to json.encodeToString(intList, settings.paymentAccountNumbers),
    )

    /** Writes several keys in one transaction; `null` soft-deletes a key so the deletion syncs. */
    private suspend fun set(vararg entries: Pair<String, String?>) {
        var changed = false
        db.withTransaction {
            for ((key, value) in entries) {
                val row = dao.byKey(key)
                val now = nowMicros()
                when {
                    value == null -> if (row != null && row.deletedAt == null) {
                        dao.upsert(row.copy(deletedAt = now, updatedAt = now, isDirty = true)); changed = true
                    }
                    row == null -> {
                        dao.upsert(UserSettingEntity(DeterministicIds.settingId(key), key, value, now)); changed = true
                    }
                    row.deletedAt == null && row.value == value -> Unit
                    else -> {
                        dao.upsert(row.copy(value = value, deletedAt = null, updatedAt = now, isDirty = true)); changed = true
                    }
                }
            }
        }
        if (changed) sync.requestSync()
    }

    object Keys {
        const val ASSET_GROUPS = "BalanceSheet.AssetGroups"
        const val LIABILITY_GROUPS = "BalanceSheet.LiabilityGroups"
        const val CASH_GROUPS = "Cashflow.CashAccountGroups"
        const val REVENUE_GROUPS = "IncomeStatement.RevenueGroups"
        const val EXPENSE_GROUPS = "IncomeStatement.ExpenseGroups"
        const val DEPRECIATION_PREFIXES = "IncomeStatement.DepreciationPrefixes"
        const val TAX_PREFIXES = "IncomeStatement.TaxPrefixes"
        const val DEFERRED_PREFIXES = "IncomeStatement.DeferredAssetPrefixes"
        const val INVOICE_CREDIT_ACCOUNT = "Invoice.CreditAccountNumber"
        const val INVOICE_PAYMENT_ACCOUNTS = "Invoice.PaymentAccountNumbers"
    }

    /** Mirrors the .NET `record GroupDto(string Name, List<string> Prefixes)`. */
    @Serializable
    private data class GroupJson(
        @SerialName("Name") val name: String = "",
        @SerialName("Prefixes") val prefixes: List<String> = emptyList(),
    )

    companion object {
        // encodeDefaults keeps "Prefixes":[] in the payload; .NET would otherwise read null.
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
        private val groupList = ListSerializer(GroupJson.serializer())
        private val stringList = ListSerializer(String.serializer())
        private val intList = ListSerializer(Int.serializer())

        internal fun encodeGroups(groups: List<AccountGroup>): String =
            json.encodeToString(groupList, groups.map { GroupJson(it.name, it.prefixes) })

        internal fun decodeGroups(raw: String?): List<AccountGroup>? =
            raw?.takeIf { it.isNotBlank() }?.let {
                runCatching { json.decodeFromString(groupList, it).map { g -> AccountGroup(g.name, g.prefixes) } }.getOrNull()
            }

        private fun decodeStrings(raw: String?): List<String> =
            raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(stringList, it) }.getOrNull() }.orEmpty()

        private fun decodeInts(raw: String?): List<Int> =
            raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(intList, it) }.getOrNull() }.orEmpty()

        internal fun balanceSheetFrom(values: Map<String, String>) = BalanceSheetSettings(
            assetGroups = decodeGroups(values[Keys.ASSET_GROUPS]).orEmpty(),
            liabilityGroups = decodeGroups(values[Keys.LIABILITY_GROUPS]).orEmpty(),
        )

        internal fun incomeStatementFrom(values: Map<String, String>) = IncomeStatementSettings(
            revenueGroups = decodeGroups(values[Keys.REVENUE_GROUPS]).orEmpty(),
            expenseGroups = decodeGroups(values[Keys.EXPENSE_GROUPS]).orEmpty(),
            depreciationPrefixes = decodeStrings(values[Keys.DEPRECIATION_PREFIXES]),
            taxPrefixes = decodeStrings(values[Keys.TAX_PREFIXES]),
            deferredAssetPrefixes = decodeStrings(values[Keys.DEFERRED_PREFIXES]),
        )

        /** Unset or unreadable cash groups fall back to the built-in defaults (as in the .NET app). */
        internal fun cashflowFrom(values: Map<String, String>) = CashflowSettings(
            cashGroups = decodeGroups(values[Keys.CASH_GROUPS]) ?: CashflowSettings.DEFAULT_CASH_GROUPS,
        )

        internal fun invoiceFrom(values: Map<String, String>) = InvoiceSettings(
            creditAccountNumber = values[Keys.INVOICE_CREDIT_ACCOUNT]?.trim()?.toIntOrNull(),
            paymentAccountNumbers = decodeInts(values[Keys.INVOICE_PAYMENT_ACCOUNTS]),
        )
    }
}
