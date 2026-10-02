package com.soloit.doubleentry.data

import androidx.room.withTransaction
import com.soloit.doubleentry.data.local.AccountEntity
import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.data.local.AssetEntity
import com.soloit.doubleentry.data.local.InvoiceEntity
import com.soloit.doubleentry.data.local.nowMicros
import com.soloit.doubleentry.domain.AccountGroup
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.BalanceSheetSettings
import com.soloit.doubleentry.domain.CashflowSettings
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Frequency
import com.soloit.doubleentry.domain.IncomeStatementSettings
import com.soloit.doubleentry.domain.InvoiceSettings
import com.soloit.doubleentry.domain.LedgerException
import com.soloit.doubleentry.domain.Reconciliation
import com.soloit.doubleentry.domain.RecurringRule
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.domain.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Portable JSON export/import, format-compatible with the .NET app (schema version 7 and the
 * older 2–6 on import). Transactions and accounts get fresh ids on import, so invoices refer to
 * transactions by index and to accounts by number.
 */
class BackupService(
    private val db: AppDatabase,
    private val ledger: LedgerRepository,
    private val settings: SettingsRepository,
    private val sync: SyncTrigger,
) {
    suspend fun export(output: OutputStream) = withContext(Dispatchers.IO) {
        val accounts = db.accounts().live()
        val accountNumbers = accounts.associate { it.id to it.number }
        val transactions = ledger.getTransactions().sortedWith(compareBy({ it.date }, { it.id }))
        val transactionIndex = transactions.withIndex().associate { (i, t) -> t.id to i }

        val assets = db.assets().live().sortedBy { it.id }.mapNotNull { a ->
            AssetDto(
                name = a.name,
                cost = a.costCents,
                activationDate = a.activationDate.toString(),
                usefulLifeMonths = a.usefulLifeMonths,
                debitAccountNumber = accountNumbers[a.debitAccountId] ?: return@mapNotNull null,
                creditAccountNumber = accountNumbers[a.creditAccountId] ?: return@mapNotNull null,
                lastWrittenOffMonth = a.lastWrittenOffMonth?.toString(),
                amountWrittenOff = a.amountWrittenOffCents,
            )
        }

        val exportedInvoiceIds = mutableListOf<String>()
        val invoices = db.invoices().live().sortedWith(compareBy({ it.dueDate }, { it.id })).mapNotNull { i ->
            InvoiceDto(
                amountDue = i.amountDueCents,
                invoiceDate = i.invoiceDate.toString(),
                dueDate = i.dueDate.toString(),
                bookingTransactionIndex = transactionIndex[i.bookingTransactionId] ?: return@mapNotNull null,
                outstandingAccountNumber = accountNumbers[i.outstandingAccountId] ?: return@mapNotNull null,
                outstandingIsCredit = i.outstandingIsCredit,
                isPaid = i.isPaid,
                paidDate = i.paidDate?.toString(),
                paymentTransactionIndex = i.paymentTransactionId?.let { transactionIndex[it] },
                paidFromAccountNumber = i.paidFromAccountId?.let { accountNumbers[it] },
            ).also { exportedInvoiceIds += i.id }
        }

        val backup = LedgerBackup(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            exportedAt = OffsetDateTime.now(ZoneOffset.UTC).toString(),
            accounts = accounts.map { AccountDto(it.number, it.name, AccountType.fromOrdinal(it.type)) },
            transactions = transactions.map { t ->
                TransactionDto(
                    date = t.date.toString(),
                    title = t.title,
                    description = t.description,
                    entries = t.entries.sortedBy { it.id }.map { EntryDto(it.account.number, it.amount, it.type) },
                )
            },
            assets = assets,
            invoices = invoices,
            extensions = mapOf(
                "settings" to json.encodeToJsonElement(SettingsDto.serializer(), currentSettings()),
                "android" to json.encodeToJsonElement(AndroidDataDto.serializer(), androidData(accountNumbers, transactionIndex, exportedInvoiceIds)),
            ),
        )
        output.write(json.encodeToString(LedgerBackup.serializer(), backup).toByteArray(Charsets.UTF_8))
        output.flush()
    }

    /** Replaces all ledger data with the backup's contents. */
    suspend fun import(input: InputStream) {
        val backup = withContext(Dispatchers.IO) {
            val text = input.readBytes().toString(Charsets.UTF_8)
            if (text.isBlank()) throw LedgerException("The selected file is empty.")
            try {
                json.decodeFromString(LedgerBackup.serializer(), text)
            } catch (e: SerializationException) {
                throw LedgerException("The selected file is not a valid DoubleEntry backup.")
            } catch (e: IllegalArgumentException) {
                throw LedgerException("The selected file is not a valid DoubleEntry backup.")
            }
        }
        if (backup.schemaVersion !in 2..CURRENT_SCHEMA_VERSION) {
            throw LedgerException("Unsupported backup schema version ${backup.schemaVersion}. Expected $CURRENT_SCHEMA_VERSION.")
        }

        // Schema 2 stored the title in the description field.
        val transactions = if (backup.schemaVersion == 2) {
            backup.transactions.map { if (it.title.isNullOrBlank()) it.copy(title = it.description.orEmpty(), description = "") else it }
        } else {
            backup.transactions
        }

        val knownNumbers = backup.accounts.map { it.number }.toSet()
        transactions.flatMap { it.entries }.firstOrNull { it.accountNumber !in knownNumbers }?.let {
            throw LedgerException("Backup references unknown account #${it.accountNumber}.")
        }

        db.withTransaction {
            val now = nowMicros()
            db.invoices().softDeleteAll(now)
            db.assets().softDeleteAll(now)
            db.entries().softDeleteAll(now)
            db.transactions().softDeleteAll(now)
            db.accounts().softDeleteAll(now)
            SettingsRepository.Keys.DATA_PREFIXES.forEach { db.settings().softDeleteWithPrefix(it, now) }

            val numberToId = mutableMapOf<Int, String>()
            for (a in backup.accounts) {
                val id = newId()
                db.accounts().upsert(AccountEntity(id, a.number, a.name, a.type.ordinal, now))
                numberToId[a.number] = id
            }

            val indexToTransactionId = mutableMapOf<Int, String>()
            transactions.forEachIndexed { index, t ->
                val date = parseDate(t.date, "transaction #${index + 1}")
                val draft = TransactionDraft(
                    date = date,
                    title = t.title.orEmpty(),
                    description = t.description.orEmpty(),
                    lines = t.entries.map { DraftLine(numberToId.getValue(it.accountNumber), it.amount, it.entryType) },
                )
                indexToTransactionId[index] = try {
                    ledger.insertTransaction(draft)
                } catch (e: LedgerException) {
                    throw LedgerException("Transaction #${index + 1} ('${draft.title}' on $date) is invalid: ${e.message}")
                }
            }

            for (a in backup.assets) {
                db.assets().upsert(
                    AssetEntity(
                        id = newId(),
                        name = a.name,
                        costCents = a.cost,
                        activationDate = parseDate(a.activationDate, "asset '${a.name}'"),
                        usefulLifeMonths = a.usefulLifeMonths,
                        debitAccountId = numberToId[a.debitAccountNumber]
                            ?: throw LedgerException("Asset '${a.name}' references unknown debit account #${a.debitAccountNumber}."),
                        creditAccountId = numberToId[a.creditAccountNumber]
                            ?: throw LedgerException("Asset '${a.name}' references unknown credit account #${a.creditAccountNumber}."),
                        lastWrittenOffMonth = a.lastWrittenOffMonth?.let { parseDate(it, "asset '${a.name}'") },
                        amountWrittenOffCents = a.amountWrittenOff,
                        updatedAt = now,
                    ),
                )
            }

            val indexToInvoiceId = mutableMapOf<Int, String>()
            backup.invoices.forEachIndexed { index, inv ->
                val invoiceId = newId().also { indexToInvoiceId[index] = it }
                db.invoices().upsert(
                    InvoiceEntity(
                        id = invoiceId,
                        amountDueCents = inv.amountDue,
                        invoiceDate = parseDate(inv.invoiceDate, "invoice"),
                        dueDate = parseDate(inv.dueDate, "invoice"),
                        bookingTransactionId = indexToTransactionId[inv.bookingTransactionIndex]
                            ?: throw LedgerException("Invoice references unknown booking transaction #${inv.bookingTransactionIndex}."),
                        outstandingAccountId = numberToId[inv.outstandingAccountNumber]
                            ?: throw LedgerException("Invoice references unknown outstanding account #${inv.outstandingAccountNumber}."),
                        outstandingIsCredit = inv.outstandingIsCredit,
                        isPaid = inv.isPaid,
                        paidDate = inv.paidDate?.let { parseDate(it, "invoice") },
                        paymentTransactionId = inv.paymentTransactionIndex?.let {
                            indexToTransactionId[it] ?: throw LedgerException("Invoice references unknown payment transaction #$it.")
                        },
                        paidFromAccountId = inv.paidFromAccountNumber?.let {
                            numberToId[it] ?: throw LedgerException("Invoice references unknown paid-from account #$it.")
                        },
                        updatedAt = now,
                    ),
                )
            }

            backup.extensions?.get("android")?.let { element ->
                runCatching { json.decodeFromJsonElement(AndroidDataDto.serializer(), element) }.getOrNull()
                    ?.let { applyAndroidData(it, numberToId, indexToTransactionId, indexToInvoiceId) }
            }
        }

        backup.extensions?.get("settings")?.let { element ->
            runCatching { json.decodeFromJsonElement(SettingsDto.serializer(), element) }.getOrNull()?.let { applySettings(it) }
        }
        sync.requestSync()
    }

    /** Android-only records (synced as settings), with ids replaced by account numbers and list indexes. */
    private suspend fun androidData(
        accountNumbers: Map<String, Int>,
        transactionIndex: Map<String, Int>,
        invoiceIds: List<String>,
    ): AndroidDataDto {
        val recurring = settings.livePrefix(SettingsRepository.Keys.RECURRING_PREFIX).mapNotNull { (id, raw) ->
            val rule = RecurringRepository.decode(id, raw) ?: return@mapNotNull null
            RecurringDto(
                title = rule.title,
                description = rule.description,
                entries = rule.lines.map { EntryDto(accountNumbers[it.accountId] ?: return@mapNotNull null, it.amount, it.type) },
                frequency = rule.frequency.name,
                startDate = rule.startDate.toString(),
                endDate = rule.endDate?.toString(),
                lastPostedDate = rule.lastPostedDate?.toString(),
                paused = rule.paused,
            )
        }.sortedBy { it.title }
        val reconciliations = settings.livePrefix(SettingsRepository.Keys.RECONCILIATION_PREFIX).mapNotNull { (accountId, raw) ->
            val mark = ReconciliationRepository.decode(accountId, raw) ?: return@mapNotNull null
            ReconciliationDto(accountNumbers[accountId] ?: return@mapNotNull null, mark.date.toString(), mark.statementBalance)
        }.sortedBy { it.accountNumber }
        val payments = settings.livePrefix(SettingsRepository.Keys.INVOICE_PAYMENTS_PREFIX).mapNotNull { (invoiceId, raw) ->
            val index = invoiceIds.indexOf(invoiceId).takeIf { it >= 0 } ?: return@mapNotNull null
            val ids = runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrNull().orEmpty()
            InvoicePaymentsDto(index, ids.mapNotNull { transactionIndex[it] }).takeIf { it.transactionIndexes.isNotEmpty() }
        }.sortedBy { it.invoiceIndex }
        return AndroidDataDto(recurring, reconciliations, payments)
    }

    private suspend fun applyAndroidData(
        data: AndroidDataDto,
        numberToId: Map<Int, String>,
        indexToTransactionId: Map<Int, String>,
        indexToInvoiceId: Map<Int, String>,
    ) {
        for (r in data.recurring) {
            val lines = r.entries.map { DraftLine(numberToId[it.accountNumber] ?: return@map null, it.amount, it.entryType) }
            if (lines.any { it == null }) continue
            val rule = RecurringRule(
                id = RecurringRepository.newRuleId(),
                title = r.title,
                description = r.description,
                lines = lines.filterNotNull(),
                frequency = Frequency.entries.firstOrNull { it.name == r.frequency } ?: Frequency.Monthly,
                startDate = parseDate(r.startDate, "recurring transaction '${r.title}'"),
                endDate = r.endDate?.let { parseDate(it, "recurring transaction '${r.title}'") },
                lastPostedDate = r.lastPostedDate?.let { parseDate(it, "recurring transaction '${r.title}'") },
                paused = r.paused,
            )
            settings.put(RecurringRepository.PREFIX + rule.id, RecurringRepository.encode(rule))
        }
        for (r in data.reconciliations) {
            val accountId = numberToId[r.accountNumber] ?: continue
            val mark = Reconciliation(accountId, parseDate(r.date, "reconciliation of #${r.accountNumber}"), r.statementBalance)
            settings.put(ReconciliationRepository.PREFIX + accountId, ReconciliationRepository.encode(mark))
        }
        for (p in data.invoicePayments) {
            val invoiceId = indexToInvoiceId[p.invoiceIndex] ?: continue
            val ids = p.transactionIndexes.mapNotNull { indexToTransactionId[it] }
            settings.put(SettingsRepository.Keys.INVOICE_PAYMENTS_PREFIX + invoiceId, json.encodeToString(ListSerializer(String.serializer()), ids))
        }
    }

    private suspend fun currentSettings(): SettingsDto {
        val bs = settings.balanceSheet.first()
        val cf = settings.cashflow.first()
        val inc = settings.incomeStatement.first()
        val inv = settings.invoice.first()
        return SettingsDto(
            balanceSheetAssetGroups = bs.assetGroups.map(::GroupDto),
            balanceSheetLiabilityGroups = bs.liabilityGroups.map(::GroupDto),
            cashflowCashAccountGroups = cf.cashGroups.map(::GroupDto),
            incomeStatementRevenueGroups = inc.revenueGroups.map(::GroupDto),
            incomeStatementExpenseGroups = inc.expenseGroups.map(::GroupDto),
            incomeStatementDepreciationPrefixes = inc.depreciationPrefixes,
            incomeStatementTaxPrefixes = inc.taxPrefixes,
            incomeStatementDeferredAssetPrefixes = inc.deferredAssetPrefixes,
            invoiceCreditAccountNumber = inv.creditAccountNumber,
            invoicePaymentAccountNumbers = inv.paymentAccountNumbers,
        )
    }

    private suspend fun applySettings(dto: SettingsDto) {
        val bs = settings.balanceSheet.first()
        settings.saveBalanceSheet(
            BalanceSheetSettings(
                assetGroups = dto.balanceSheetAssetGroups?.map { it.toDomain() } ?: bs.assetGroups,
                liabilityGroups = dto.balanceSheetLiabilityGroups?.map { it.toDomain() } ?: bs.liabilityGroups,
            ),
        )
        dto.cashflowCashAccountGroups?.let { groups -> settings.saveCashflow(CashflowSettings(groups.map { it.toDomain() })) }
        val inc = settings.incomeStatement.first()
        settings.saveIncomeStatement(
            IncomeStatementSettings(
                revenueGroups = dto.incomeStatementRevenueGroups?.map { it.toDomain() } ?: inc.revenueGroups,
                expenseGroups = dto.incomeStatementExpenseGroups?.map { it.toDomain() } ?: inc.expenseGroups,
                depreciationPrefixes = dto.incomeStatementDepreciationPrefixes ?: inc.depreciationPrefixes,
                taxPrefixes = dto.incomeStatementTaxPrefixes ?: inc.taxPrefixes,
                deferredAssetPrefixes = dto.incomeStatementDeferredAssetPrefixes ?: inc.deferredAssetPrefixes,
            ),
        )
        val inv = settings.invoice.first()
        settings.saveInvoice(
            InvoiceSettings(
                creditAccountNumber = dto.invoiceCreditAccountNumber,
                paymentAccountNumbers = dto.invoicePaymentAccountNumbers ?: inv.paymentAccountNumbers,
            ),
        )
    }

    private fun parseDate(raw: String, what: String): LocalDate =
        runCatching { LocalDate.parse(raw.take(10)) }.getOrElse { throw LedgerException("Invalid date '$raw' in $what.") }

    @Serializable
    data class LedgerBackup(
        val schemaVersion: Int,
        val exportedAt: String? = null,
        val accounts: List<AccountDto> = emptyList(),
        val transactions: List<TransactionDto> = emptyList(),
        val assets: List<AssetDto> = emptyList(),
        val invoices: List<InvoiceDto> = emptyList(),
        val extensions: Map<String, JsonElement>? = null,
    )

    @Serializable
    data class AccountDto(
        val number: Int,
        val name: String,
        @Serializable(with = AccountTypeSerializer::class) val type: AccountType,
    )

    @Serializable
    data class TransactionDto(
        val date: String,
        val title: String? = "",
        val description: String? = "",
        val entries: List<EntryDto> = emptyList(),
    )

    @Serializable
    data class EntryDto(
        val accountNumber: Int,
        @Serializable(with = DecimalCentsSerializer::class) val amount: Long,
        @Serializable(with = EntryTypeSerializer::class) val entryType: EntryType,
    )

    @Serializable
    data class AssetDto(
        val name: String,
        @Serializable(with = DecimalCentsSerializer::class) val cost: Long,
        val activationDate: String,
        val usefulLifeMonths: Int,
        val debitAccountNumber: Int,
        val creditAccountNumber: Int,
        val lastWrittenOffMonth: String? = null,
        @Serializable(with = DecimalCentsSerializer::class) val amountWrittenOff: Long = 0,
    )

    @Serializable
    data class InvoiceDto(
        @Serializable(with = DecimalCentsSerializer::class) val amountDue: Long,
        val invoiceDate: String,
        val dueDate: String,
        val bookingTransactionIndex: Int,
        val outstandingAccountNumber: Int,
        val outstandingIsCredit: Boolean,
        val isPaid: Boolean = false,
        val paidDate: String? = null,
        val paymentTransactionIndex: Int? = null,
        val paidFromAccountNumber: Int? = null,
    )

    @Serializable
    data class GroupDto(val name: String = "", val prefixes: List<String> = emptyList()) {
        constructor(group: AccountGroup) : this(group.name, group.prefixes)

        fun toDomain() = AccountGroup(name, prefixes)
    }

    @Serializable
    data class SettingsDto(
        val balanceSheetAssetGroups: List<GroupDto>? = null,
        val balanceSheetLiabilityGroups: List<GroupDto>? = null,
        val cashflowCashAccountGroups: List<GroupDto>? = null,
        val incomeStatementRevenueGroups: List<GroupDto>? = null,
        val incomeStatementExpenseGroups: List<GroupDto>? = null,
        val incomeStatementDepreciationPrefixes: List<String>? = null,
        val incomeStatementTaxPrefixes: List<String>? = null,
        val incomeStatementDeferredAssetPrefixes: List<String>? = null,
        val invoiceCreditAccountNumber: Int? = null,
        val invoicePaymentAccountNumbers: List<Int>? = null,
    )

    /** Data only the Android app knows about; the .NET app ignores unknown extensions. */
    @Serializable
    data class AndroidDataDto(
        val recurring: List<RecurringDto> = emptyList(),
        val reconciliations: List<ReconciliationDto> = emptyList(),
        val invoicePayments: List<InvoicePaymentsDto> = emptyList(),
    )

    @Serializable
    data class RecurringDto(
        val title: String,
        val description: String = "",
        val entries: List<EntryDto> = emptyList(),
        val frequency: String,
        val startDate: String,
        val endDate: String? = null,
        val lastPostedDate: String? = null,
        val paused: Boolean = false,
    )

    @Serializable
    data class ReconciliationDto(
        val accountNumber: Int,
        val date: String,
        @Serializable(with = DecimalCentsSerializer::class) val statementBalance: Long,
    )

    /** Instalments of the invoice at [invoiceIndex], as indexes into the transaction list. */
    @Serializable
    data class InvoicePaymentsDto(val invoiceIndex: Int, val transactionIndexes: List<Int>)

    companion object {
        const val CURRENT_SCHEMA_VERSION = 7

        internal val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            encodeDefaults = true
        }
    }
}
