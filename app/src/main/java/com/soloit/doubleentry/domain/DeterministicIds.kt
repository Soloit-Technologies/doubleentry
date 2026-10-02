package com.soloit.doubleentry.domain

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Stable identifiers that must be byte-for-byte identical to the ones produced by the
 * original .NET app, so devices running either app converge on the same Supabase rows
 * instead of creating duplicates.
 *
 * .NET's `new Guid(byte[])` reads the first three fields little-endian, which differs from
 * the RFC 4122 big-endian layout used by [UUID]; the helpers below reproduce that layout.
 */
object DeterministicIds {

    private val planNamespace = UUID.fromString("3f8b0a3a-0a7e-4f6a-9b9b-1d6f8f6a0d10")
    private val writeoffNamespace = UUID.fromString("b6c2f1e4-3a7d-4c19-9f0b-2e5a8d7c4f11")
    private val recurringNamespace = UUID.fromString("5d0c7e2a-8f41-4b6e-a3c9-7b18e0f2d6a4")
    private val monthFormat = DateTimeFormatter.ofPattern("yyyy-MM")

    /** UUIDv5(planNamespace, number) — id of a default-plan account. */
    fun defaultPlanAccountId(number: Int): String {
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(rfcBytes(planNamespace) + number.toString().toByteArray(Charsets.UTF_8))
        val bytes = hash.copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        return uuidFromRfcBytes(bytes).toString()
    }

    /** Id of the depreciation posting for [assetId] and the month starting at [month]. */
    fun writeoffTransactionId(assetId: String, month: LocalDate): String =
        namespacedGuid("${compact(assetId)}:${month.format(monthFormat)}")

    /** Id of one line of a depreciation posting. */
    fun writeoffEntryId(transactionId: String, type: EntryType): String =
        namespacedGuid("${compact(transactionId)}:${type.name}")

    /**
     * Id of the posting of recurring rule [ruleId] on [date]. Devices that post the same occurrence
     * independently converge on one transaction.
     */
    fun recurringTransactionId(ruleId: String, date: LocalDate): String =
        namespacedGuid("${compact(ruleId)}:$date", recurringNamespace)

    /** Id of line [index] of a recurring posting. */
    fun recurringEntryId(transactionId: String, index: Int): String =
        namespacedGuid("${compact(transactionId)}:$index", recurringNamespace)

    /** Id of a synced user setting, derived from its key. */
    fun settingId(key: String): String =
        dotNetGuidFromBytes(sha256(key.toByteArray(Charsets.UTF_8)))

    private fun namespacedGuid(name: String, namespace: UUID = writeoffNamespace): String =
        dotNetGuidFromBytes(sha256(dotNetBytes(namespace) + name.toByteArray(Charsets.UTF_8)))

    private fun compact(id: String): String = id.lowercase().replace("-", "")

    private fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

    private fun rfcBytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

    private fun uuidFromRfcBytes(b: ByteArray): UUID {
        val buffer = ByteBuffer.wrap(b, 0, 16)
        return UUID(buffer.long, buffer.long)
    }

    /** Equivalent of .NET `Guid.TryWriteBytes`. */
    private fun dotNetBytes(uuid: UUID): ByteArray = swapToDotNet(rfcBytes(uuid))

    /** Equivalent of .NET `new Guid(bytes[0..16]).ToString()`. */
    private fun dotNetGuidFromBytes(bytes: ByteArray): String =
        uuidFromRfcBytes(swapToDotNet(bytes.copyOf(16))).toString()

    // The transformation is its own inverse: reverse fields 1–3, keep the last 8 bytes.
    private fun swapToDotNet(b: ByteArray): ByteArray = byteArrayOf(
        b[3], b[2], b[1], b[0],
        b[5], b[4],
        b[7], b[6],
        b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15],
    )
}

fun newId(): String = UUID.randomUUID().toString()
