package com.soloit.doubleentry.sync

import androidx.room.withTransaction
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.local.AccountEntity
import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.data.local.AssetEntity
import com.soloit.doubleentry.data.local.EntryEntity
import com.soloit.doubleentry.data.local.InvoiceEntity
import com.soloit.doubleentry.data.local.SyncDao
import com.soloit.doubleentry.data.local.SyncEntity
import com.soloit.doubleentry.data.local.TransactionEntity
import com.soloit.doubleentry.data.local.UserSettingEntity
import com.soloit.doubleentry.data.local.nowMicros
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun interface AccessTokenProvider {
    suspend fun accessToken(forceRefresh: Boolean): String
}

data class LocalSummary(
    /** Anything besides an untouched default plan. */
    val hasData: Boolean,
    /** Changes not yet uploaded, without untouched default-plan rows. */
    val unsyncedChanges: Int,
    /** How many of those are deletions of accounts, transactions, assets or invoices. */
    val unsyncedDeletions: Int,
)

data class PullResult(
    /** Local rows were added or changed. */
    val changed: Boolean,
    /**
     * Unsynced edits made on this device that lost to a newer edit (or delete) of the same item
     * on another device. Transaction lines are not counted separately from their transaction.
     */
    val replacedLocalChanges: Int,
)

/**
 * Row-level, last-write-wins sync against PostgREST. Each row carries a server-stamped
 * `updated_at` and a `deleted_at` tombstone; local rows track `is_dirty` for the push side.
 */
class SyncEngine(
    private val db: AppDatabase,
    private val api: SupabaseApi,
    private val prefs: SyncPreferences,
) {
    // encodeDefaults + explicit nulls: every row sends every column (bulk upserts need uniform
    // keys, and `deleted_at: null` must be sent to revive a tombstone).
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    /**
     * Pulls every table, then advances all watermarks together — committing a parent watermark
     * while a child table failed would skip parents on the next run.
     */
    suspend fun pullAll(auth: AccessTokenProvider): PullResult {
        val watermarks = mutableMapOf<SyncTable, String>()
        var changed = false
        var replaced = 0
        // Which version of each pulled transaction header won; its lines must win with it.
        val headersFromServer = mutableSetOf<String>()
        val headersKeptLocal = mutableSetOf<String>()
        for (table in SyncTable.entries) {
            val result = when (table) {
                SyncTable.Accounts -> pull(table, AccountRow.serializer(), db.accounts(), auth) { it.toEntity() }
                SyncTable.Transactions -> pull(
                    table, TransactionRow.serializer(), db.transactions(), auth,
                    onDecided = { fromServer, keptLocal ->
                        headersFromServer += fromServer.map { it.id }
                        headersKeptLocal += keptLocal.map { it.id }
                    },
                ) { it.toEntity() }
                SyncTable.Entries -> pull(
                    table, EntryRow.serializer(), db.entries(), auth, countReplaced = false,
                    onDecided = { fromServer, _ -> dropLosingLocalLines(headersFromServer, fromServer) },
                    adjust = { entries -> dropLosingServerLines(headersKeptLocal, entries) },
                ) { it.toEntity() }
                SyncTable.Assets -> pull(table, AssetRow.serializer(), db.assets(), auth) { it.toEntity() }
                SyncTable.Invoices -> pull(table, InvoiceRow.serializer(), db.invoices(), auth) { it.toEntity() }
                SyncTable.Settings -> pull(table, UserSettingRow.serializer(), db.settings(), auth, beforeWrite = { rows ->
                    // A key may exist locally under a different id; the unique key index must not block the server row.
                    rows.forEach { db.settings().deleteOtherWithKey(it.key, it.id) }
                }) { it.toEntity() }
            }
            if (result.applied > 0) changed = true
            replaced += result.replacedLocal
            result.watermark?.let { watermarks[table] = it }
        }
        prefs.setWatermarks(watermarks)
        return PullResult(changed, replaced)
    }

    /** What this device holds, for deciding how a sign-in combines it with the cloud. */
    suspend fun localSummary(): LocalSummary {
        val dao = db.syncStatus()
        return LocalSummary(
            hasData = dao.hasUserData(LedgerRepository.SEED_TIMESTAMP),
            unsyncedChanges = dao.userChanges(LedgerRepository.SEED_TIMESTAMP),
            unsyncedDeletions = dao.userDeletions(),
        )
    }

    /** True when the signed-in account already holds accounts or transactions. */
    suspend fun cloudHasData(auth: AccessTokenProvider): Boolean =
        authorized(auth) { token -> api.hasLiveRows(SyncTable.Accounts, token) || api.hasLiveRows(SyncTable.Transactions, token) }

    /** Queues every live local row for upload (rows synced with another account aren't dirty). */
    suspend fun markAllForUpload() = db.withTransaction {
        with(db.syncStatus()) {
            markAccountsDirty()
            markTransactionsDirty()
            markEntriesDirty()
            markAssetsDirty()
            markInvoicesDirty()
            markSettingsDirty()
        }
    }

    /** Deletes the local database without leaving tombstones, so nothing reaches the cloud. */
    suspend fun wipeLocal() = withContext(Dispatchers.IO) { db.clearAllTables() }

    /**
     * Soft-deletes every cloud row last changed before [beforeMicros], children first, including
     * rows this device never pulled. Rows changed later on another device are newer and survive.
     */
    suspend fun wipeCloudBefore(beforeMicros: Long, auth: AccessTokenProvider) {
        val before = formatTimestamp(beforeMicros)
        val deletedAt = formatTimestamp(nowMicros())
        for (table in SyncTable.entries.reversed()) {
            authorized(auth) { token -> api.tombstoneBefore(table, before, deletedAt, token) }
        }
    }

    /** Pushes dirty rows parent → child and clears their dirty flag with the server timestamp. */
    suspend fun pushAll(auth: AccessTokenProvider): Int {
        val userId = prefs.userId ?: throw SyncException("Please sign in again to sync.")
        return push(SyncTable.Accounts, db.accounts(), auth) { json.encodeToJsonElement(AccountRow.serializer(), AccountRow.from(it, userId)) } +
            push(SyncTable.Transactions, db.transactions(), auth) { json.encodeToJsonElement(TransactionRow.serializer(), TransactionRow.from(it, userId)) } +
            push(SyncTable.Entries, db.entries(), auth) { json.encodeToJsonElement(EntryRow.serializer(), EntryRow.from(it, userId)) } +
            push(SyncTable.Assets, db.assets(), auth) { json.encodeToJsonElement(AssetRow.serializer(), AssetRow.from(it, userId)) } +
            push(SyncTable.Invoices, db.invoices(), auth) { json.encodeToJsonElement(InvoiceRow.serializer(), InvoiceRow.from(it, userId)) } +
            push(SyncTable.Settings, db.settings(), auth) { json.encodeToJsonElement(UserSettingRow.serializer(), UserSettingRow.from(it, userId)) }
    }

    /**
     * Lines belong to their header. Editing a transaction replaces its lines with new rows, so when
     * two devices edited the same transaction, row-level last-write-wins would keep both line sets
     * (doubling the amounts). Instead the version whose header wins keeps its lines:
     *
     * - [dropLosingServerLines]: the local header won and its lines were replaced locally, so new
     *   lines from the server for that transaction are stored as tombstones (and pushed as such).
     * - [dropLosingLocalLines]: the server's header won and came with new lines, so locally replaced
     *   lines that the server doesn't know are tombstoned. (A header-only change on the other device,
     *   such as a new date, brings no lines and leaves the local lines alone.)
     *
     * Lines with deterministic ids (depreciation, recurring postings) exist on both sides with the
     * same ids and are left to the normal row-level rule.
     */
    private suspend fun dropLosingServerLines(headersKeptLocal: Set<String>, incoming: List<EntryEntity>): List<EntryEntity> {
        if (headersKeptLocal.isEmpty()) return incoming
        val entries = db.entries()
        val known = entries.byIds(incoming.map { it.id }).map { it.id }.toSet()
        val replacedLocally = mutableMapOf<String, Boolean>()
        val now = nowMicros()
        return incoming.map { e ->
            val losing = e.deletedAt == null && e.id !in known && e.transactionId in headersKeptLocal &&
                replacedLocally.getOrPut(e.transactionId) { entries.liveDirtyOf(e.transactionId).isNotEmpty() }
            if (losing) e.copy(deletedAt = now, updatedAt = now, isDirty = true) else e
        }
    }

    private suspend fun dropLosingLocalLines(headersFromServer: Set<String>, incoming: List<EntryRow>) {
        if (headersFromServer.isEmpty()) return
        val entries = db.entries()
        val incomingIds = incoming.map { it.id }.toSet()
        val replacedRemotely = incoming.filter { it.deletedAt == null }.map { it.transactionId }.toSet()
        val now = nowMicros()
        for (transactionId in headersFromServer intersect replacedRemotely) {
            val losing = entries.liveDirtyOf(transactionId).filter { it.id !in incomingIds }
            if (losing.isNotEmpty()) entries.upsertAll(losing.map { it.copy(deletedAt = now, updatedAt = now, isDirty = true) })
        }
    }

    private class TablePull(val applied: Int, val watermark: String?, val replacedLocal: Int)

    private suspend fun <R : SyncRow, E : SyncEntity> pull(
        table: SyncTable,
        serializer: KSerializer<R>,
        dao: SyncDao<E>,
        auth: AccessTokenProvider,
        /** Whether local edits overwritten by this table's rows count as [PullResult.replacedLocalChanges]. */
        countReplaced: Boolean = true,
        beforeWrite: suspend (List<R>) -> Unit = {},
        /** Called inside the write transaction with the rows applied from the server and the ones where the local row was kept. */
        onDecided: suspend (fromServer: List<R>, keptLocal: List<R>) -> Unit = { _, _ -> },
        /** Last chance to change the entities before they are written. */
        adjust: suspend (List<E>) -> List<E> = { it },
        toEntity: (R) -> E,
    ): TablePull {
        val watermark = prefs.watermark(table)
        // Re-read a window before the watermark: the server stamps `updated_at` when a write
        // starts, so a slow upload can commit rows older than a watermark already passed.
        // Rows seen before are as new as the local copy and are skipped below.
        val since = watermark?.let { formatTimestamp(parseTimestamp(it) - WATERMARK_OVERLAP_MICROS) }
        val rows = mutableListOf<R>()
        var offset = 0
        while (true) {
            val page = authorized(auth) { token -> api.select(table, since, offset, PAGE_SIZE, token) }
            rows += json.decodeFromJsonElement(ListSerializer(serializer), page)
            if (page.size < PAGE_SIZE) break
            offset += page.size
        }
        if (rows.isEmpty()) return TablePull(0, null, 0)

        var applied = 0
        var replacedLocal = 0
        db.withTransaction {
            val fromServer = mutableListOf<R>()
            val keptLocal = mutableListOf<R>()
            for (chunk in rows.chunked(CHUNK)) {
                val existing = dao.byIds(chunk.map { it.id }.distinct()).associateBy { it.id }
                // Last write wins: keep the local row when it is as new as (or newer than) the server's.
                val (newer, older) = chunk.partition { row -> existing[row.id]?.let { it.updatedAt < row.updatedAtMicros } ?: true }
                keptLocal += older
                if (newer.isEmpty()) continue
                val incoming = newer.map(toEntity)
                if (countReplaced) replacedLocal += incoming.count { existing[it.id]?.isEditLostTo(it) == true }
                beforeWrite(newer)
                dao.upsertAll(adjust(incoming))
                fromServer += newer
                applied += newer.size
            }
            onDecided(fromServer, keptLocal)
        }
        val newest = rows.maxOf { it.updatedAtMicros }
        val previous = watermark?.let(::parseTimestamp) ?: Long.MIN_VALUE
        return TablePull(applied, formatTimestamp(maxOf(newest, previous)), replacedLocal)
    }

    /**
     * True when this local row holds an edit not yet pushed that [server] (a newer version from
     * another device) is about to overwrite with different content. Seeded default-plan rows and
     * identical results (deterministic depreciation or recurring postings made on both devices)
     * are not lost edits.
     */
    private fun SyncEntity.isEditLostTo(server: SyncEntity): Boolean =
        isDirty && updatedAt > LedgerRepository.SEED_TIMESTAMP && content() != server.content()

    private suspend fun <E : SyncEntity> push(
        table: SyncTable,
        dao: SyncDao<E>,
        auth: AccessTokenProvider,
        toJson: (E) -> kotlinx.serialization.json.JsonElement,
    ): Int {
        val dirty = dao.dirty()
        for (chunk in dirty.chunked(CHUNK)) {
            val response = authorized(auth) { token -> api.upsert(table, JsonArray(chunk.map(toJson)), token) }
            val local = chunk.associateBy { it.id }
            db.withTransaction {
                for (element in response) {
                    val row = element.jsonObject
                    val id = row["id"]?.jsonPrimitive?.content ?: continue
                    val serverUpdatedAt = row["updated_at"]?.jsonPrimitive?.content?.let(::parseTimestamp) ?: continue
                    val sent = local[id] ?: continue
                    dao.markClean(id, sent.updatedAt, serverUpdatedAt)
                }
            }
        }
        return dirty.size
    }

    /** Runs [block] with a valid token, refreshing the session once if the server answers 401. */
    private suspend fun <T> authorized(auth: AccessTokenProvider, block: suspend (String) -> T): T = try {
        block(auth.accessToken(forceRefresh = false))
    } catch (e: UnauthorizedException) {
        try {
            block(auth.accessToken(forceRefresh = true))
        } catch (again: UnauthorizedException) {
            throw SessionExpiredException(again)
        }
    }

    /** The row without its sync bookkeeping (whether it is deleted still counts, not when). */
    private fun SyncEntity.content(): SyncEntity {
        val deleted = deletedAt?.let { 0L }
        return when (this) {
            is AccountEntity -> copy(updatedAt = 0, deletedAt = deleted, isDirty = false)
            is TransactionEntity -> copy(updatedAt = 0, deletedAt = deleted, isDirty = false)
            is EntryEntity -> copy(updatedAt = 0, deletedAt = deleted, isDirty = false)
            is AssetEntity -> copy(updatedAt = 0, deletedAt = deleted, isDirty = false)
            is InvoiceEntity -> copy(updatedAt = 0, deletedAt = deleted, isDirty = false)
            is UserSettingEntity -> copy(updatedAt = 0, deletedAt = deleted, isDirty = false)
            else -> this
        }
    }

    private companion object {
        const val PAGE_SIZE = 1000

        /** Far longer than any upload transaction can run (Supabase's statement timeout is seconds). */
        const val WATERMARK_OVERLAP_MICROS = 5 * 60 * 1_000_000L
        const val CHUNK = 500
    }
}
