package com.soloit.doubleentry.sync

import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.data.local.SyncDao
import com.soloit.doubleentry.data.local.SyncEntity
import com.soloit.doubleentry.data.local.deleteAllRows
import com.soloit.doubleentry.data.local.withTransaction
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
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
    /** Unsynced edits made on this device that lost to a later edit (or delete) of the same item on another device. */
    val replacedLocalChanges: Int,
)

data class PushResult(
    val uploaded: Int,
    /**
     * Edits the server refused because another device's later edit of the same item reached it
     * after this cycle's pull; the later edit replaced the local one. Also edits from before the
     * ledger was replaced on another device, which are dropped.
     */
    val replacedLocalChanges: Int,
)

/**
 * Row-level, last-edit-wins sync against PostgREST. Each row carries `modified_at`, when it was
 * edited (by the server's clock, see [com.soloit.doubleentry.data.local.ServerClock]), which
 * decides conflicts on both sides: a pull keeps the local row unless the server's edit is later,
 * and the server refuses an upload older than what it holds. `updated_at`, stamped by the server
 * when a row reaches it, is only the cursor for pulling what changed. Deletes are `deleted_at`
 * tombstones, and local rows track `is_dirty` for the push side.
 *
 * A transaction syncs as one row with its lines, so a version of a transaction always wins or
 * loses whole.
 */
class SyncEngine(
    private val db: AppDatabase,
    private val api: SupabaseApi,
    private val prefs: SyncPreferences,
) {
    // encodeDefaults + explicit nulls: every row sends every column (bulk upserts need uniform
    // keys, and `deleted_at: null` must be sent to revive a tombstone).
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    private val tables: List<Table<*, *>> = listOf(
        Table(SyncTable.Accounts, AccountRow.serializer(), db.accounts(), AccountRow::toEntity) { rows, ledgerId ->
            rows.map { AccountRow.from(it, ledgerId) }
        },
        Table(
            SyncTable.Transactions, TransactionRow.serializer(), db.transactions(), TransactionRow::toEntity,
            write = { rows ->
                db.transactions().upsertAll(rows.map { it.toEntity() })
                db.entries().deleteOf(rows.map { it.id })
                db.entries().upsertAll(rows.flatMap { it.lineEntities() })
            },
        ) { rows, ledgerId ->
            val lines = db.entries().allOf(rows.map { it.id }).groupBy { it.transactionId }
            rows.map { TransactionRow.from(it, lines[it.id].orEmpty(), ledgerId) }
        },
        Table(SyncTable.Assets, AssetRow.serializer(), db.assets(), AssetRow::toEntity) { rows, ledgerId ->
            rows.map { AssetRow.from(it, ledgerId) }
        },
        Table(SyncTable.Invoices, InvoiceRow.serializer(), db.invoices(), InvoiceRow::toEntity) { rows, ledgerId ->
            rows.map { InvoiceRow.from(it, ledgerId) }
        },
        Table(
            SyncTable.Settings, UserSettingRow.serializer(), db.settings(), UserSettingRow::toEntity,
            write = { rows ->
                // A key may exist locally under a different id; the unique key index must not block the server row.
                rows.forEach { db.settings().deleteOtherWithKey(it.key, it.id) }
                db.settings().upsertAll(rows.map { it.toEntity() })
            },
        ) { rows, ledgerId ->
            rows.map { UserSettingRow.from(it, ledgerId) }
        },
    )

    /**
     * Pulls every table, then advances all watermarks together, so a failure part-way re-reads
     * every table next time (applying a row twice changes nothing).
     */
    suspend fun pullAll(auth: AccessTokenProvider): PullResult {
        val ledgerId = ledgerId()
        val watermarks = mutableMapOf<SyncTable, String>()
        var changed = false
        var replaced = 0
        for (table in tables) {
            val result = table.pull(auth, ledgerId)
            if (result.applied > 0) changed = true
            replaced += result.replacedLocal
            result.watermark?.let { watermarks[table.remote] = it }
        }
        prefs.setWatermarks(watermarks)
        return PullResult(changed, replaced)
    }

    /** Pushes dirty rows parent → child and clears their dirty flag. */
    suspend fun pushAll(auth: AccessTokenProvider): PushResult {
        val ledgerId = ledgerId()
        var uploaded = 0
        var replaced = 0
        for (table in tables) {
            val result = table.push(auth, ledgerId)
            uploaded += result.uploaded
            replaced += result.replacedLocalChanges
        }
        return PushResult(uploaded, replaced)
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

    /** True when the cloud copy of [ledgerId] already holds accounts or transactions. */
    suspend fun cloudHasData(auth: AccessTokenProvider, ledgerId: String): Boolean = authorized(auth) { token ->
        api.hasLiveRows(SyncTable.Accounts, ledgerId, token) || api.hasLiveRows(SyncTable.Transactions, ledgerId, token)
    }

    /** Queues every live local row for upload (rows synced with another account aren't dirty). */
    suspend fun markAllForUpload() = db.withTransaction {
        with(db.syncStatus()) {
            markAccountsDirty()
            markTransactionsDirty()
            markAssetsDirty()
            markInvoicesDirty()
            markSettingsDirty()
        }
    }

    /** Deletes the local database without leaving tombstones, so nothing reaches the cloud. */
    suspend fun wipeLocal() = db.deleteAllRows()

    /**
     * Makes this device's ledger replace the cloud's as of [atMicros]: every cloud row last edited
     * before then is deleted, children first, including rows this device never pulled. The rows
     * this device still holds from before then are restamped just after [atMicros] and uploaded,
     * so they survive. Rows edited later, here or on another device, are newer and stay. The server
     * also refuses rows edited before then that arrive later (from a device that was offline).
     *
     * The restamp must be strictly later than the deletions: a device that syncs between the two
     * steps holds the deletion, and would keep it over a row stamped the same (an equal stamp is
     * the same edit).
     */
    suspend fun replaceCloudLedger(atMicros: Long, auth: AccessTokenProvider): PushResult {
        val kept = atMicros + 1
        db.withTransaction {
            with(db.syncStatus()) {
                restampAccounts(kept)
                restampTransactions(kept)
                restampAssets(kept)
                restampInvoices(kept)
                restampSettings(kept)
            }
        }
        val at = formatTimestamp(atMicros)
        val ledgerId = ledgerId()
        authorized(auth) { token -> api.markLedgerReplaced(ledgerId, at, token) }
        for (table in tables.reversed()) {
            authorized(auth) { token -> api.tombstoneBefore(table.remote, ledgerId, at, token) }
        }
        return pushAll(auth)
    }

    /** The cloud ledger this device's database syncs with. */
    private fun ledgerId(): String = prefs.ledgerId ?: throw SyncException("Please sign in again to sync.")

    private class TablePull(val applied: Int, val watermark: String?, val replacedLocal: Int)

    private class Applied(val applied: Int, val replacedLocal: Int)

    /** How one table's local rows map to its PostgREST rows, and how it pulls and pushes them. */
    private inner class Table<R : SyncRow, E : SyncEntity>(
        val remote: SyncTable,
        private val serializer: KSerializer<R>,
        private val dao: SyncDao<E>,
        private val toEntity: (R) -> E,
        /** Stores server rows that won. */
        private val write: suspend (List<R>) -> Unit = { rows -> dao.upsertAll(rows.map(toEntity)) },
        /** The server rows for local rows. */
        private val toRows: suspend (List<E>, ledgerId: String) -> List<R>,
    ) {
        suspend fun pull(auth: AccessTokenProvider, ledgerId: String): TablePull {
            val watermark = prefs.watermark(remote)
            // Re-read a window before the watermark: the server stamps `updated_at` when a write
            // starts, so a slow upload can commit rows older than a watermark already passed.
            // Rows seen before are as new as the local copy and are skipped when applied.
            val since = watermark?.let { formatTimestamp(parseTimestamp(it) - WATERMARK_OVERLAP_MICROS) }
            val rows = mutableListOf<R>()
            var after: SupabaseApi.PageCursor? = null
            while (true) {
                val page = decode(authorized(auth) { token -> api.select(remote, ledgerId, since, after, PAGE_SIZE, token) })
                rows += page
                if (page.size < PAGE_SIZE) break
                val last = page.last()
                after = SupabaseApi.PageCursor(formatTimestamp(last.updatedAtMicros), last.id)
            }
            if (rows.isEmpty()) return TablePull(0, null, 0)

            // A row changed during the pull can be read twice; its later version comes last.
            val result = apply(rows.associateBy { it.id }.values.toList())
            val newest = rows.maxOf { it.updatedAtMicros }
            val previous = watermark?.let(::parseTimestamp) ?: Long.MIN_VALUE
            return TablePull(result.applied, formatTimestamp(maxOf(newest, previous)), result.replacedLocal)
        }

        suspend fun push(auth: AccessTokenProvider, ledgerId: String): PushResult {
            val dirty = dao.dirty()
            var replaced = 0
            for (chunk in dirty.chunked(CHUNK)) {
                val rows = toRows(chunk, ledgerId)
                val sent = JsonArray(rows.map { json.encodeToJsonElement(serializer, it) })
                val response = authorized(auth) { token -> api.upsert(remote, sent, token) }
                val stored = response.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }.toSet()
                db.withTransaction {
                    for (entity in chunk) if (entity.id in stored) dao.markClean(entity.id, entity.updatedAt)
                }
                // Left out of the response: another device's later edit reached the server after
                // this cycle's pull, and the server kept it. Take it now.
                val refused = chunk.filter { it.id !in stored }
                for (part in refused.chunked(ID_CHUNK)) {
                    val current = decode(authorized(auth) { token -> api.selectByIds(remote, ledgerId, part.map { it.id }, token) })
                    replaced += apply(current).replacedLocal
                    // Not on the server at all: edited before the ledger was replaced elsewhere
                    // (while this device was offline), so the replacement removed it. Here too.
                    val found = current.map { it.id }.toSet()
                    val dropped = part.filter { it.id !in found }
                    if (dropped.isEmpty()) continue
                    db.withTransaction { dropped.forEach { dao.discard(it.id, it.updatedAt) } }
                    replaced += dropped.count { it.updatedAt > LedgerRepository.SEED_TIMESTAMP }
                }
            }
            return PushResult(dirty.size, replaced)
        }

        /** Stores the server rows whose edit is later than the local row's, in one database transaction. */
        private suspend fun apply(rows: List<R>): Applied {
            var applied = 0
            var replaced = 0
            db.withTransaction {
                for (chunk in rows.chunked(CHUNK)) {
                    val local = dao.byIds(chunk.map { it.id }).associateBy { it.id }
                    // Last edit wins; an equal stamp is the same edit.
                    val newer = chunk.filter { row -> local[row.id]?.let { it.updatedAt < row.modifiedAtMicros } ?: true }
                    if (newer.isEmpty()) continue
                    replaced += countLostEdits(newer, local)
                    write(newer)
                    applied += newer.size
                }
            }
            return Applied(applied, replaced)
        }

        /**
         * How many unsynced local edits [incoming] overwrites with different content. Seeded
         * default-plan rows and identical results (deterministic depreciation or recurring
         * postings made on both devices) are not lost edits.
         */
        private suspend fun countLostEdits(incoming: List<R>, local: Map<String, E>): Int {
            val edited = incoming.mapNotNull { row -> local[row.id]?.takeIf { it.isDirty && it.updatedAt > LedgerRepository.SEED_TIMESTAMP } }
            if (edited.isEmpty()) return 0
            val localContent = toRows(edited, "").associate { it.id to it.content() }
            return incoming.count { row -> localContent[row.id]?.let { it != row.content() } == true }
        }

        private fun decode(page: JsonArray): List<R> = json.decodeFromJsonElement(ListSerializer(serializer), page)
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

    private companion object {
        const val PAGE_SIZE = 1000

        /** Far longer than any upload transaction can run (Supabase's statement timeout is seconds). */
        const val WATERMARK_OVERLAP_MICROS = 5 * 60 * 1_000_000L
        const val CHUNK = 500

        /** Ids per `id=in.(…)` request, keeping the URL short. */
        const val ID_CHUNK = 100
    }
}
