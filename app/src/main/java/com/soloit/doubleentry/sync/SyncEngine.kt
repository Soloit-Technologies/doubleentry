package com.soloit.doubleentry.sync

import androidx.room.withTransaction
import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.data.local.SyncDao
import com.soloit.doubleentry.data.local.SyncEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun interface AccessTokenProvider {
    suspend fun accessToken(forceRefresh: Boolean): String
}

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
     * while a child table failed would skip parents on the next run. Returns true when rows changed.
     */
    suspend fun pullAll(auth: AccessTokenProvider): Boolean {
        val watermarks = mutableMapOf<SyncTable, String>()
        var changed = false
        for (table in SyncTable.entries) {
            val (applied, watermark) = when (table) {
                SyncTable.Accounts -> pull(table, AccountRow.serializer(), db.accounts(), auth) { it.toEntity() }
                SyncTable.Transactions -> pull(table, TransactionRow.serializer(), db.transactions(), auth) { it.toEntity() }
                SyncTable.Entries -> pull(table, EntryRow.serializer(), db.entries(), auth) { it.toEntity() }
                SyncTable.Assets -> pull(table, AssetRow.serializer(), db.assets(), auth) { it.toEntity() }
                SyncTable.Invoices -> pull(table, InvoiceRow.serializer(), db.invoices(), auth) { it.toEntity() }
                SyncTable.Settings -> pull(table, UserSettingRow.serializer(), db.settings(), auth, beforeWrite = { rows ->
                    // A key may exist locally under a different id; the unique key index must not block the server row.
                    rows.forEach { db.settings().deleteOtherWithKey(it.key, it.id) }
                }) { it.toEntity() }
            }
            if (applied > 0) changed = true
            watermark?.let { watermarks[table] = it }
        }
        prefs.setWatermarks(watermarks)
        return changed
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

    private suspend fun <R : SyncRow, E : SyncEntity> pull(
        table: SyncTable,
        serializer: KSerializer<R>,
        dao: SyncDao<E>,
        auth: AccessTokenProvider,
        beforeWrite: suspend (List<R>) -> Unit = {},
        toEntity: (R) -> E,
    ): Pair<Int, String?> {
        val since = prefs.watermark(table)
        val rows = mutableListOf<R>()
        var offset = 0
        while (true) {
            val page = authorized(auth) { token -> api.select(table, since, offset, PAGE_SIZE, token) }
            rows += json.decodeFromJsonElement(ListSerializer(serializer), page)
            if (page.size < PAGE_SIZE) break
            offset += page.size
        }
        if (rows.isEmpty()) return 0 to null

        var applied = 0
        db.withTransaction {
            for (chunk in rows.chunked(CHUNK)) {
                val existing = dao.byIds(chunk.map { it.id }.distinct()).associateBy { it.id }
                // Last write wins: keep the local row when it is as new as (or newer than) the server's.
                val newer = chunk.filter { row -> existing[row.id]?.let { it.updatedAt < row.updatedAtMicros } ?: true }
                if (newer.isEmpty()) continue
                beforeWrite(newer)
                dao.upsertAll(newer.map(toEntity))
                applied += newer.size
            }
        }
        val watermark = formatTimestamp(rows.maxOf { it.updatedAtMicros })
        return applied to watermark
    }

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
            throw SyncException("Your session has expired. Please sign in again.", again)
        }
    }

    private companion object {
        const val PAGE_SIZE = 1000
        const val CHUNK = 500
    }
}
