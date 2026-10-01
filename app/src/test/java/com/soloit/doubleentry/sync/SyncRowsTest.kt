package com.soloit.doubleentry.sync

import com.soloit.doubleentry.data.local.EntryEntity
import com.soloit.doubleentry.data.local.TransactionEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The JSON sent to and read from PostgREST must match the columns in supabase/migrations. */
class SyncRowsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    @Test
    fun parsesPostgrestTimestampsWithMicroseconds() {
        val a = parseTimestamp("2026-10-01T12:34:56.123456+00:00")
        val b = parseTimestamp("2026-10-01T14:34:56.123456+02:00")
        assertEquals(a, b)
        assertEquals("2026-10-01T12:34:56.123456Z", formatTimestamp(a))
        assertEquals(parseTimestamp("2026-10-01T12:34:56+00:00") + 500_000, parseTimestamp("2026-10-01T12:34:56.5+00:00"))
    }

    @Test
    fun entryRowUsesSnakeCaseAndExactDecimals() {
        val entity = EntryEntity("e1", "t1", "a1", 123_405, 1, parseTimestamp("2026-01-01T00:00:00+00:00"))
        val obj = json.encodeToJsonElement(EntryRow.serializer(), EntryRow.from(entity, "u1")).jsonObject
        assertEquals(setOf("id", "user_id", "transaction_id", "account_id", "amount", "entry_type", "updated_at", "deleted_at"), obj.keys)
        assertEquals("1234.05", obj["amount"].toString())
        // deleted_at must be sent explicitly so a revived row clears its tombstone remotely.
        assertEquals("null", obj["deleted_at"].toString())
    }

    @Test
    fun readsServerRowsIntoCleanEntities() {
        val server = """
            {"id":"t1","user_id":"u1","date":"2026-02-28","title":"Rent","description":null,
             "updated_at":"2026-03-01T08:00:00.000001+00:00","deleted_at":"2026-03-02T08:00:00+00:00","extra":1}
        """.trimIndent()
        val entity: TransactionEntity = json.decodeFromString(TransactionRow.serializer(), server).toEntity()
        assertEquals(LocalDate.of(2026, 2, 28), entity.date)
        assertEquals("", entity.description)
        assertFalse(entity.isDirty)
        assertTrue(entity.deletedAt != null)

        val entry = json.decodeFromString(EntryRow.serializer(), """{"id":"e","transaction_id":"t","account_id":"a","amount":99.9,"entry_type":0,"updated_at":"2026-03-01T08:00:00+00:00"}""")
        assertEquals(9_990L, entry.toEntity().amountCents)
    }
}
