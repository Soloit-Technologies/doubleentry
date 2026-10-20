package com.soloit.doubleentry.data

import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.data.local.UserSettingEntity
import com.soloit.doubleentry.data.local.nowMicros
import com.soloit.doubleentry.data.local.withTransaction
import com.soloit.doubleentry.domain.AccountGroup
import com.soloit.doubleentry.domain.BalanceSheetSettings
import com.soloit.doubleentry.domain.CashflowSettings
import com.soloit.doubleentry.domain.CostPeriodAccounts
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
    val costPeriodAccounts: Flow<CostPeriodAccounts> = values.map(::costPeriodAccountsFrom).distinctUntilChanged()

    suspend fun saveBalanceSheet(settings: BalanceSheetSettings) = set(*balanceSheetEntries(settings))

    suspend fun saveIncomeStatement(settings: IncomeStatementSettings) = set(*incomeStatementEntries(settings))

    suspend fun saveCashflow(settings: CashflowSettings) = set(
        Keys.CASH_GROUPS to encodeGroups(settings.cashGroups),
    )

    suspend fun saveInvoice(settings: InvoiceSettings) = set(
        Keys.INVOICE_CREDIT_ACCOUNT to settings.creditAccountNumber?.toString(),
        Keys.INVOICE_PAYMENT_ACCOUNTS to json.encodeToString(intList, settings.paymentAccountNumbers),
    )

    suspend fun saveCostPeriodAccounts(settings: CostPeriodAccounts) = set(*costPeriodAccountEntries(settings))

    suspend fun getCostPeriodAccounts(): CostPeriodAccounts =
        costPeriodAccountsFrom(dao.liveWithPrefix(Keys.COST_PERIOD_ACCOUNTS_PREFIX).associate { it.key to it.value })

    /**
     * Live values of every key starting with [prefix], keyed by the remainder of the key. Used for
     * per-item records (recurring rules, reconciliations, invoice instalments) that sync as settings
     * so they need no backend schema change and stay invisible to the .NET app.
     */
    fun observePrefix(prefix: String): Flow<Map<String, String>> =
        values.map { all -> all.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) } }.distinctUntilChanged()

    suspend fun livePrefix(prefix: String): Map<String, String> =
        dao.liveWithPrefix(prefix).associate { it.key.removePrefix(prefix) to it.value }

    suspend fun get(key: String): String? = dao.byKey(key)?.takeIf { it.deletedAt == null }?.value

    /** Stores [value] under [key]; `null` soft-deletes it. Safe to call inside an outer transaction. */
    suspend fun put(key: String, value: String?) = set(key to value)

    /** Soft-deletes [key] with an explicit [stamp], so it can be restored together with the rows deleted alongside it. */
    internal suspend fun softDelete(key: String, stamp: Long) {
        val row = dao.byKey(key) ?: return
        if (row.deletedAt == null) dao.upsert(row.copy(deletedAt = stamp, updatedAt = stamp, isDirty = true))
    }

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

        // Interim accounts of cost periods (Android only), by account number.
        const val COST_PERIOD_ACCOUNTS_PREFIX = "CostPeriod.Account."
        const val PREPAID_EXPENSES_ACCOUNT = COST_PERIOD_ACCOUNTS_PREFIX + "PrepaidExpenses"
        const val ACCRUED_EXPENSES_ACCOUNT = COST_PERIOD_ACCOUNTS_PREFIX + "AccruedExpenses"
        const val ACCRUED_INCOME_ACCOUNT = COST_PERIOD_ACCOUNTS_PREFIX + "AccruedIncome"
        const val DEFERRED_INCOME_ACCOUNT = COST_PERIOD_ACCOUNTS_PREFIX + "DeferredIncome"

        // Per-item prefixes (Android only). The suffix is the id of the rule, account or invoice.
        const val RECURRING_PREFIX = "Recurring."
        const val RECONCILIATION_PREFIX = "Reconciliation."
        const val INVOICE_PAYMENTS_PREFIX = "Invoice.Payments."
        /** Suffix is the id of the transaction whose lines are spread over periods. */
        const val COST_PERIOD_PREFIX = "CostPeriod.Transaction."

        /** Settings that describe ledger data rather than preferences; removed by "Delete all data". */
        val DATA_PREFIXES = listOf(RECURRING_PREFIX, RECONCILIATION_PREFIX, INVOICE_PAYMENTS_PREFIX, COST_PERIOD_PREFIX)
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

        internal fun balanceSheetEntries(settings: BalanceSheetSettings): Array<Pair<String, String>> = arrayOf(
            Keys.ASSET_GROUPS to encodeGroups(settings.assetGroups),
            Keys.LIABILITY_GROUPS to encodeGroups(settings.liabilityGroups),
        )

        internal fun costPeriodAccountEntries(settings: CostPeriodAccounts): Array<Pair<String, String?>> = arrayOf(
            Keys.PREPAID_EXPENSES_ACCOUNT to settings.prepaidExpenses?.toString(),
            Keys.ACCRUED_EXPENSES_ACCOUNT to settings.accruedExpenses?.toString(),
            Keys.ACCRUED_INCOME_ACCOUNT to settings.accruedIncome?.toString(),
            Keys.DEFERRED_INCOME_ACCOUNT to settings.deferredIncome?.toString(),
        )

        internal fun incomeStatementEntries(settings: IncomeStatementSettings): Array<Pair<String, String>> = arrayOf(
            Keys.REVENUE_GROUPS to encodeGroups(settings.revenueGroups),
            Keys.EXPENSE_GROUPS to encodeGroups(settings.expenseGroups),
            Keys.DEPRECIATION_PREFIXES to json.encodeToString(stringList, settings.depreciationPrefixes),
            Keys.TAX_PREFIXES to json.encodeToString(stringList, settings.taxPrefixes),
            Keys.DEFERRED_PREFIXES to json.encodeToString(stringList, settings.deferredAssetPrefixes),
        )

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

        internal fun costPeriodAccountsFrom(values: Map<String, String>) = CostPeriodAccounts(
            prepaidExpenses = values[Keys.PREPAID_EXPENSES_ACCOUNT]?.trim()?.toIntOrNull(),
            accruedExpenses = values[Keys.ACCRUED_EXPENSES_ACCOUNT]?.trim()?.toIntOrNull(),
            accruedIncome = values[Keys.ACCRUED_INCOME_ACCOUNT]?.trim()?.toIntOrNull(),
            deferredIncome = values[Keys.DEFERRED_INCOME_ACCOUNT]?.trim()?.toIntOrNull(),
        )

        internal fun invoiceFrom(values: Map<String, String>) = InvoiceSettings(
            creditAccountNumber = values[Keys.INVOICE_CREDIT_ACCOUNT]?.trim()?.toIntOrNull(),
            paymentAccountNumbers = decodeInts(values[Keys.INVOICE_PAYMENT_ACCOUNTS]),
        )
    }
}
