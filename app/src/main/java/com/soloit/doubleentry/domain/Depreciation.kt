package com.soloit.doubleentry.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Straight-line depreciation schedule, identical to the .NET implementation: the activation
 * month is prorated by the remaining days (inclusive), following months use the rounded monthly
 * amount and the final month absorbs the rounding so the schedule sums exactly to the cost.
 */
object Depreciation {

    /** [month] is the first day of the period being written off. */
    data class Slot(val month: LocalDate, val amount: Long)

    private val monthFormat = DateTimeFormatter.ofPattern("yyyy-MM")

    fun schedule(activation: LocalDate, cost: Long, usefulLifeMonths: Int): List<Slot> {
        if (usefulLifeMonths <= 0 || cost <= 0) return emptyList()

        // Rounding cents to whole cents == rounding units to two decimals (half away from zero).
        // A monthly amount that rounds to zero would never finish the schedule, so use one cent.
        val monthly = BigDecimal.valueOf(cost)
            .divide(BigDecimal.valueOf(usefulLifeMonths.toLong()), 0, RoundingMode.HALF_UP)
            .toLong()
            .coerceAtLeast(1)

        val daysInMonth = activation.lengthOfMonth()
        val remainingDays = daysInMonth - activation.dayOfMonth + 1
        val firstAmount = BigDecimal.valueOf(monthly * remainingDays)
            .divide(BigDecimal.valueOf(daysInMonth.toLong()), 0, RoundingMode.HALF_UP)
            .toLong()

        val slots = mutableListOf<Slot>()
        var month = activation.withDayOfMonth(1)
        var cumulative = 0L

        if (firstAmount > 0) {
            val capped = minOf(firstAmount, cost)
            slots += Slot(month, capped)
            cumulative += capped
        } else {
            slots += Slot(month, 0)
        }

        while (cumulative < cost) {
            month = month.plusMonths(1)
            val amount = minOf(monthly, cost - cumulative)
            slots += Slot(month, amount)
            cumulative += amount
        }
        return slots
    }

    /** The most recent fully completed month (first day) relative to [today]. */
    fun lastCompletedMonth(today: LocalDate): LocalDate = today.withDayOfMonth(1).minusMonths(1)

    /** Postings are dated on the last day of the month they cover. */
    fun postingDate(month: LocalDate): LocalDate = month.withDayOfMonth(month.lengthOfMonth())

    fun title(assetName: String): String = "Depreciation: $assetName"

    /** User-facing description; postings are identified by their deterministic id, not by text. */
    fun description(month: LocalDate): String = "Monthly depreciation for ${month.format(monthFormat)}"

    /** Per-asset suffix of the legacy description, used to find legacy postings with random ids. */
    fun legacyAssetMarker(assetId: String): String = "(Asset #${assetId.lowercase()})"

    /** Description written by older versions, still matched when repairing legacy postings. */
    fun legacyDescription(assetId: String, month: LocalDate): String =
        "Monthly writeoff for ${month.format(monthFormat)} ${legacyAssetMarker(assetId)}"

    /** Deterministic ids of every non-zero posting in the asset's full schedule. */
    fun writeoffIds(asset: Asset): Set<String> =
        schedule(asset.activationDate, asset.cost, asset.usefulLifeMonths)
            .filter { it.amount > 0 }
            .mapTo(mutableSetOf()) { DeterministicIds.writeoffTransactionId(asset.id, it.month) }
}
