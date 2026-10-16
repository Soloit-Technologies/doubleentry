package com.soloit.doubleentry.sync

import com.soloit.doubleentry.data.local.EntryEntity
import com.soloit.doubleentry.data.local.TransactionEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun transactionRowCarriesItsLinesWithExactDecimals() {
        val header = TransactionEntity("t1", LocalDate.of(2026, 1, 1), "Rent", "", parseTimestamp("2026-01-01T00:00:00+00:00"))
        val line = EntryEntity("e1", "t1", "a1", 123_405, 1)
        val obj = json.encodeToJsonElement(TransactionRow.serializer(), TransactionRow.from(header, listOf(line), "l1")).jsonObject
        // updated_at is stamped by the server and never sent; modified_at is the edit time.
        assertEquals(setOf("id", "ledger_id", "date", "title", "description", "lines", "modified_at", "deleted_at"), obj.keys)
        assertEquals("2026-01-01T00:00:00Z", obj["modified_at"]!!.jsonPrimitive.content)
        val sentLine = obj["lines"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("id", "account_id", "amount", "entry_type"), sentLine.keys)
        assertEquals("1234.05", sentLine["amount"].toString())
        // deleted_at must be sent explicitly so a revived row clears its tombstone remotely.
        assertEquals("null", obj["deleted_at"].toString())
    }

    @Test
    fun readsServerRowsIntoCleanEntities() {
        val server = """
            {"id":"t1","ledger_id":"l1","user_id":"u1","date":"2026-02-28","title":"Rent","description":null,
             "lines":[{"id":"e","account_id":"a","amount":99.9,"entry_type":0}],
             "modified_at":"2026-03-01T07:00:00.000001+00:00","updated_at":"2026-03-01T08:00:00+00:00",
             "deleted_at":"2026-03-02T08:00:00+00:00","extra":1}
        """.trimIndent()
        val row = json.decodeFromString(TransactionRow.serializer(), server)
        val entity: TransactionEntity = row.toEntity()
        assertEquals(LocalDate.of(2026, 2, 28), entity.date)
        assertEquals("", entity.description)
        assertFalse(entity.isDirty)
        assertTrue(entity.deletedAt != null)
        // The local stamp is the edit time, not when the row reached the server.
        assertEquals(parseTimestamp("2026-03-01T07:00:00.000001+00:00"), entity.updatedAt)
        assertEquals(parseTimestamp("2026-03-01T08:00:00+00:00"), row.updatedAtMicros)
        assertEquals(listOf(EntryEntity("e", "t1", "a", 9_990L, 0)), row.lineEntities())
    }

    @Test
    fun theSamePostingHasTheSameContentWhateverItsLineIdsAndStamps() {
        val a = TransactionRow("t", "l1", "2026-01-01", "Rent", "", listOf(LineRow("x", "a1", 5, 0), LineRow("y", "a2", 5, 1)), "2026-01-01T00:00:00Z")
        val b = a.copy(ledgerId = "", description = null, lines = a.lines.reversed().map { it.copy(id = it.id + "2") }, modifiedAt = "2026-01-02T00:00:00Z", updatedAt = "2026-01-03T00:00:00Z")
        assertEquals(a.content(), b.content())
        assertNotEquals(a.content(), a.copy(lines = listOf(LineRow("x", "a1", 6, 0), LineRow("y", "a2", 6, 1))).content())
        assertNotEquals(a.content(), a.copy(deletedAt = "2026-01-02T00:00:00Z").content())
    }
}
