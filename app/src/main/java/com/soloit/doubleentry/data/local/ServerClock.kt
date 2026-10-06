package com.soloit.doubleentry.data.local

import kotlin.math.abs

/**
 * How far this device's clock is behind the sync server's. Local edits are stamped with device
 * time but compared with the server's `updated_at` (last write wins), so a phone whose clock is
 * minutes off would win or lose conflicts it shouldn't. The offset is measured from the HTTP
 * `Date` header of every Supabase response and added by [nowMicros].
 */
object ServerClock {
    @Volatile var offsetMicros: Long = 0L

    /** Offsets this small are within the `Date` header's one-second resolution plus latency: ignored. */
    private const val THRESHOLD_MILLIS = 2_000L

    /** Round trips slower than this say too little about when the server stamped its `Date`. */
    private const val MAX_ROUND_TRIP_MILLIS = 5_000L

    /**
     * Returns the new offset in microseconds for a response whose `Date` header says
     * [serverDateMillis] (whole seconds), sent at [sentAtMillis] and received at [receivedAtMillis]
     * by the device clock, or null when the current offset should stay.
     */
    fun estimate(serverDateMillis: Long, sentAtMillis: Long, receivedAtMillis: Long, currentMicros: Long = offsetMicros): Long? {
        if (sentAtMillis <= 0 || receivedAtMillis < sentAtMillis || receivedAtMillis - sentAtMillis > MAX_ROUND_TRIP_MILLIS) return null
        // The header truncates to the second, so the server's moment lies somewhere in [date, date + 1 s).
        val measured = serverDateMillis + 500 - (sentAtMillis + receivedAtMillis) / 2
        val offsetMillis = if (abs(measured) < THRESHOLD_MILLIS) 0L else measured
        // Small wobbles around a known offset are measurement noise; keep stamps steady.
        if (abs(offsetMillis * 1_000 - currentMicros) < THRESHOLD_MILLIS * 1_000 / 2) return null
        return offsetMillis * 1_000
    }
}
