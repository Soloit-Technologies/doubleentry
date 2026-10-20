package com.soloit.doubleentry.ui.components

import com.soloit.doubleentry.domain.Money
import kotlin.math.abs
import kotlin.math.roundToInt

/** How [current] differs from [previous]: the signed amount, and the percentage when there is a base. */
fun changeLabel(current: Long, previous: Long): String {
    val delta = current - previous
    if (delta == 0L) return "No change"
    val percent = if (previous != 0L) " (${if (delta > 0) "+" else "−"}${(abs(delta.toDouble()) * 100 / abs(previous.toDouble())).roundToInt()}%)" else ""
    return Money.formatSigned(delta) + percent
}
