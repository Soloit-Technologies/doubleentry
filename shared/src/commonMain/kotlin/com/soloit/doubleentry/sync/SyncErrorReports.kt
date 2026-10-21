package com.soloit.doubleentry.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One row of `sync_error_reports`: an unexpected sync failure, without any ledger data. [kind] is
 * the chain of exception classes, [detail] their technical messages with row content removed
 * (for a refused request, which one it was), then where in this app's code it happened.
 */
@Serializable
data class SyncErrorReport(
    @SerialName("app_version") val appVersion: Int,
    val kind: String,
    val detail: String,
    @SerialName("pending_changes") val pendingChanges: Int,
    /**
     * Identifies the failure for sending it only once in a while: [kind] and the messages, without
     * the code locations, which differ between occurrences depending on where coroutines resumed.
     */
    @Transient val fingerprint: String = "",
) {
    companion object {
        /**
         * Expected states, shown to the user and not bugs: no connection, an ended session, no
         * subscription, a full ledger.
         */
        fun isReportable(error: Throwable): Boolean =
            error !is OfflineException && error !is SessionExpiredException && error !is CloudAccessRequiredException &&
                error !is LedgerFullException

        fun of(error: Throwable, appVersion: Int, pendingChanges: Int): SyncErrorReport {
            val chain = generateSequence(error) { it.cause }.take(5).toList()
            val messages = chain.mapNotNull { it.message?.let(::scrub) }.distinct()
            val frames = chain.flatMap { it.stackTrace.asList() }
                .filter { it.className.startsWith("com.soloit.") && it.lineNumber > 0 }
                .distinct()
                .take(8)
                .joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }
            val kind = chain.joinToString(" < ") { it.javaClass.simpleName }.take(200)
            return SyncErrorReport(
                appVersion = appVersion,
                kind = kind,
                detail = (messages + frames).joinToString("\n").take(2000),
                pendingChanges = pendingChanges,
                fingerprint = (listOf(kind) + messages).joinToString("\n"),
            )
        }

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Keeps what says what went wrong and drops what could hold the user's data: PostgREST's
         * `details` (e.g. "Failing row contains (…)") and the JSON a parser choked on.
         */
        internal fun scrub(message: String): String {
            val bodyStart = message.indexOf('{')
            val text = if (bodyStart >= 0) {
                val body = runCatching { json.parseToJsonElement(message.substring(bodyStart)) as? JsonObject }.getOrNull()
                val kept = body?.let { b ->
                    listOf("code", "error_code", "message", "hint")
                        .mapNotNull { key -> (b[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { "$key=$it" } }
                        .joinToString(" ")
                }
                message.substring(0, bodyStart) + (kept ?: "<body>")
            } else {
                message
            }
            return text.substringBefore("JSON input:").substringBefore("Failing row contains").trim().take(300)
        }
    }
}
