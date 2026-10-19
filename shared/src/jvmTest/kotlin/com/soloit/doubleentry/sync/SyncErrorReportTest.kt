package com.soloit.doubleentry.sync

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SyncErrorReportTest {
    @Test
    fun keepsWhatWentWrongAndDropsRowContent() {
        val technical = IOException(
            "POST /rest/v1/transactions: HTTP 400. " +
                """{"code":"23514","details":"Failing row contains (Rent, 12000.00).","hint":null,"message":"violates check constraint \"x\""}""",
        )
        val report = SyncErrorReport.of(SyncException("We couldn't sync your data right now.", technical), appVersion = 13, pendingChanges = 2)
        assertEquals("SyncException < IOException", report.kind)
        assertTrue(report.detail, "POST /rest/v1/transactions: HTTP 400. code=23514 message=violates check constraint \"x\"" in report.detail)
        assertTrue(report.detail, "SyncErrorReportTest.kt:" in report.detail)
        assertFalse(report.detail, "Rent" in report.detail)
    }

    @Test
    fun dropsTheJsonAParserChokedOn() {
        val error = SerializationException("Unexpected JSON token at offset 12: Expected quotation mark JSON input: [{\"title\":\"Rent\"")
        assertEquals("Unexpected JSON token at offset 12: Expected quotation mark", SyncErrorReport.scrub(error.message!!))
    }

    @Test
    fun expectedStatesAreNotReported() {
        assertFalse(SyncErrorReport.isReportable(OfflineException()))
        assertFalse(SyncErrorReport.isReportable(SessionExpiredException()))
        assertFalse(SyncErrorReport.isReportable(CloudAccessRequiredException()))
        assertTrue(SyncErrorReport.isReportable(IllegalStateException("Rows from the server carry updated_at")))
    }
}
