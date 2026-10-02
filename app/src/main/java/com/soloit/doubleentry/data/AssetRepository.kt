package com.soloit.doubleentry.data

import androidx.room.withTransaction
import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.data.local.AssetEntity
import com.soloit.doubleentry.data.local.EntryEntity
import com.soloit.doubleentry.data.local.nowMicros
import com.soloit.doubleentry.data.local.toDomain
import com.soloit.doubleentry.domain.Asset
import com.soloit.doubleentry.domain.Depreciation
import com.soloit.doubleentry.domain.DeterministicIds
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.LedgerException
import com.soloit.doubleentry.domain.Transaction
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.domain.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * Capital assets and their automatic monthly depreciation postings. Posting ids are derived from
 * (asset, month) so devices that process the same month independently converge on one posting.
 */
class AssetRepository(
    private val db: AppDatabase,
    private val ledger: LedgerRepository,
    private val sync: SyncTrigger,
) {
    private val assetDao = db.assets()
    private val accountDao = db.accounts()
    private val transactionDao = db.transactions()
    private val entryDao = db.entries()
    private val writeoffLock = Mutex()

    val assets: Flow<List<Asset>> = assetDao.observeLive().map { list -> list.map { it.toDomain() } }

    suspend fun getAsset(id: String): Asset? = assetDao.liveById(id)?.toDomain()

    suspend fun createAsset(
        name: String,
        cost: Long,
        activationDate: LocalDate,
        usefulLifeMonths: Int,
        debitAccountId: String,
        creditAccountId: String,
    ): Asset {
        if (name.isBlank()) throw LedgerException("Asset name is required.")
        if (cost <= 0) throw LedgerException("Cost must be a positive amount.")
        if (usefulLifeMonths <= 0) throw LedgerException("Useful life (months) must be positive.")
        if (debitAccountId == creditAccountId) throw LedgerException("Debit and credit accounts must differ.")

        val entity = db.withTransaction {
            if (accountDao.byId(debitAccountId)?.deletedAt != null) throw LedgerException("The debit account no longer exists.")
            if (accountDao.byId(creditAccountId)?.deletedAt != null) throw LedgerException("The credit account no longer exists.")
            if (accountDao.byIds(listOf(debitAccountId, creditAccountId)).size != 2) throw LedgerException("Select both accounts.")
            AssetEntity(
                id = newId(),
                name = name.trim(),
                costCents = cost,
                activationDate = activationDate,
                usefulLifeMonths = usefulLifeMonths,
                debitAccountId = debitAccountId,
                creditAccountId = creditAccountId,
                lastWrittenOffMonth = null,
                amountWrittenOffCents = 0,
                updatedAt = nowMicros(),
            ).also { assetDao.upsert(it) }
        }
        sync.requestSync()
        return entity.toDomain()
    }

    /** Deletes the asset together with its depreciation postings (the only way to remove them). */
    suspend fun deleteAsset(id: String) {
        db.withTransaction {
            val asset = assetDao.liveById(id) ?: throw LedgerException("Asset not found.")
            for (tx in writeoffTransactionEntities(asset.toDomain())) ledger.softDeleteTransaction(tx)
            val now = nowMicros()
            assetDao.upsert(asset.copy(deletedAt = now, updatedAt = now, isDirty = true))
        }
        sync.requestSync()
    }

    /**
     * Posts every missing monthly write-off up to the most recent fully completed month.
     * Returns the number of rows changed (postings created or revived, assets advanced).
     */
    suspend fun processWriteoffs(today: LocalDate = LocalDate.now()): Int = writeoffLock.withLock {
        var changed = 0
        db.withTransaction {
            val lastCompleted = Depreciation.lastCompletedMonth(today)
            val accounts = accountDao.live().map { it.id }.toSet()
            for (entity in assetDao.live()) {
                if (entity.amountWrittenOffCents >= entity.costCents) continue
                if (entity.debitAccountId !in accounts || entity.creditAccountId !in accounts) continue

                val asset = entity.toDomain()
                val schedule = Depreciation.schedule(asset.activationDate, asset.cost, asset.usefulLifeMonths)
                val pending = schedule.filter { slot ->
                    (asset.lastWrittenOffMonth == null || slot.month > asset.lastWrittenOffMonth) && slot.month <= lastCompleted
                }
                var lastMonth = asset.lastWrittenOffMonth
                var written = asset.amountWrittenOff
                for (slot in pending) {
                    if (slot.amount <= 0) continue
                    if (ensurePosting(asset, slot)) changed++
                    lastMonth = slot.month
                    written += slot.amount
                }
                if (lastMonth != asset.lastWrittenOffMonth) {
                    assetDao.upsert(
                        entity.copy(lastWrittenOffMonth = lastMonth, amountWrittenOffCents = written, updatedAt = nowMicros(), isDirty = true),
                    )
                    changed++
                }
            }
        }
        if (changed > 0) sync.requestSync()
        changed
    }

    /**
     * Repairs depreciation: every due month gets exactly one live posting (deleted ones are revived,
     * missing ones re-created, legacy duplicates removed, legacy descriptions rewritten) and each
     * asset's totals are reconciled. Returns the number of postings revived, created, rewritten or removed.
     */
    suspend fun reconcileWriteoffs(today: LocalDate = LocalDate.now()): Int = writeoffLock.withLock {
        var repaired = 0
        var assetsChanged = false
        db.withTransaction {
            val lastCompleted = Depreciation.lastCompletedMonth(today)
            val accounts = accountDao.live().map { it.id }.toSet()
            for (entity in assetDao.live()) {
                if (entity.debitAccountId !in accounts || entity.creditAccountId !in accounts) continue
                val asset = entity.toDomain()
                var lastLive: LocalDate? = null
                var liveTotal = 0L
                for (slot in Depreciation.schedule(asset.activationDate, asset.cost, asset.usefulLifeMonths)) {
                    if (slot.month > lastCompleted) break
                    if (slot.amount <= 0) continue
                    if (ensurePosting(asset, slot)) repaired++

                    // Replace the old GUID-bearing description with the user-facing one.
                    val txId = DeterministicIds.writeoffTransactionId(asset.id, slot.month)
                    val legacyDescription = Depreciation.legacyDescription(asset.id, slot.month)
                    val canonical = transactionDao.byId(txId)
                    if (canonical != null && canonical.description == legacyDescription) {
                        transactionDao.upsert(canonical.copy(description = Depreciation.description(slot.month), updatedAt = nowMicros(), isDirty = true))
                        repaired++
                    }

                    for (duplicate in transactionDao.liveByDescription(legacyDescription, excludeId = txId)) {
                        ledger.softDeleteTransaction(duplicate.id)
                        repaired++
                    }
                    lastLive = slot.month
                    liveTotal += slot.amount
                }
                if (entity.lastWrittenOffMonth != lastLive || entity.amountWrittenOffCents != liveTotal) {
                    assetDao.upsert(entity.copy(lastWrittenOffMonth = lastLive, amountWrittenOffCents = liveTotal, updatedAt = nowMicros(), isDirty = true))
                    assetsChanged = true
                }
            }
        }
        if (repaired > 0 || assetsChanged) sync.requestSync()
        repaired
    }

    /** Live depreciation postings of an asset, newest first (including legacy ones found by marker). */
    suspend fun getWriteoffTransactions(assetId: String): List<Transaction> {
        val asset = getAsset(assetId) ?: return emptyList()
        return writeoffTransactionEntities(asset)
            .mapNotNull { ledger.getTransaction(it) }
            .sortedByDescending { it.date }
    }

    suspend fun isDepreciationTransaction(transactionId: String): Boolean = ledger.isDepreciationTransaction(transactionId)

    private suspend fun writeoffTransactionEntities(asset: Asset): Set<String> {
        val byId = Depreciation.writeoffIds(asset).chunked(500)
            .flatMap { transactionDao.byIds(it) }
            .filter { it.deletedAt == null }
            .map { it.id }
        val byMarker = transactionDao.liveByDescriptionSuffix(Depreciation.legacyAssetMarker(asset.id)).map { it.id }
        return (byId + byMarker).toSet()
    }

    /** Creates or revives the canonical posting for one schedule slot. Returns true when it changed anything. */
    private suspend fun ensurePosting(asset: Asset, slot: Depreciation.Slot): Boolean {
        val txId = DeterministicIds.writeoffTransactionId(asset.id, slot.month)
        val debitId = DeterministicIds.writeoffEntryId(txId, EntryType.Debit)
        val creditId = DeterministicIds.writeoffEntryId(txId, EntryType.Credit)
        val existing = transactionDao.byId(txId)
        when {
            existing == null -> {
                ledger.insertTransaction(
                    TransactionDraft(
                        id = txId,
                        date = Depreciation.postingDate(slot.month),
                        title = Depreciation.title(asset.name),
                        description = Depreciation.description(slot.month),
                        lines = listOf(
                            DraftLine(asset.debitAccountId, slot.amount, EntryType.Debit, id = debitId),
                            DraftLine(asset.creditAccountId, slot.amount, EntryType.Credit, id = creditId),
                        ),
                    ),
                )
                return true
            }
            existing.deletedAt != null -> {
                // A tombstone must be revived in place: re-inserting would collide on the primary key.
                val now = nowMicros()
                transactionDao.upsert(existing.copy(deletedAt = null, updatedAt = now, isDirty = true))
                entryDao.softDeleteOf(txId, now)
                entryDao.upsertAll(
                    listOf(
                        EntryEntity(debitId, txId, asset.debitAccountId, slot.amount, EntryType.Debit.ordinal, now),
                        EntryEntity(creditId, txId, asset.creditAccountId, slot.amount, EntryType.Credit.ordinal, now),
                    ),
                )
                return true
            }
            else -> return false
        }
    }
}
