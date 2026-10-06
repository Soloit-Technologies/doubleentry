package com.soloit.doubleentry.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Opens a database created from the exported version 1 schema with the current version. */
@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file = context.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs(); delete() }

    @After
    fun tearDown() {
        file.delete()
    }

    private fun createVersion1(fill: SQLiteDatabase.() -> Unit) {
        val schema = Json.parseToJsonElement(File("schemas/com.soloit.doubleentry.data.local.AppDatabase/1.json").readText())
            .jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach {
                    db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.fill()
            db.version = 1
        }
    }

    @Test
    fun linesKeepWhatTheirTransactionShows() = runBlocking<Unit> {
        createVersion1 {
            execSQL("INSERT INTO accounts VALUES ('a', 1910, 'Bank', 0, 10, NULL, 0)")
            execSQL("INSERT INTO transactions VALUES ('live', '2026-09-01', 'Live', '', 20, NULL, 0)")
            execSQL("INSERT INTO transactions VALUES ('deleted', '2026-09-02', 'Deleted', '', 30, 30, 0)")
            // Synced lines, a line replaced by an edit, and one edited line not pushed yet.
            execSQL("INSERT INTO transaction_entries VALUES ('l1', 'live', 'a', 100, 0, 10, NULL, 0)")
            execSQL("INSERT INTO transaction_entries VALUES ('old', 'live', 'a', 999, 1, 15, 15, 0)")
            execSQL("INSERT INTO transaction_entries VALUES ('l2', 'live', 'a', 100, 1, 20, NULL, 1)")
            // The lines a transaction was deleted with share its stamp.
            execSQL("INSERT INTO transaction_entries VALUES ('d1', 'deleted', 'a', 50, 0, 30, 30, 0)")
            execSQL("INSERT INTO transaction_entries VALUES ('d2', 'deleted', 'a', 50, 1, 30, 30, 0)")
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val lines = db.entries().allOf(listOf("live", "deleted")).map { it.id }.toSet()
            assertEquals(setOf("l1", "l2", "d1", "d2"), lines)
            // Unsynced line changes travel with their transaction now.
            assertTrue(db.transactions().byId("live")!!.isDirty)
            assertFalse(db.transactions().byId("deleted")!!.isDirty)
        } finally {
            db.close()
        }
    }
}
