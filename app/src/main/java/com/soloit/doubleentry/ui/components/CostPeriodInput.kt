package com.soloit.doubleentry.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soloit.doubleentry.domain.CostPeriod
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.Periodization
import com.soloit.doubleentry.domain.label
import com.soloit.doubleentry.domain.shortLabel
import com.soloit.doubleentry.domain.yearMonth
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The period a line's cost or income belongs to. Unset, it offers to choose one; set, it shows
 * the period and how [amount] is split over its months.
 */
@Composable
fun CostPeriodInput(period: CostPeriod?, amount: Long?, date: LocalDate, onChange: (CostPeriod?) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 4.dp)) {
        if (period == null) {
            TextButton(onClick = { choosing = true }) {
                Icon(Icons.Rounded.DateRange, null, Modifier.size(18.dp))
                Text("Belongs to another period", Modifier.padding(start = 8.dp))
            }
        } else {
            InputChip(
                selected = true,
                onClick = { choosing = true },
                label = { Text(period.label()) },
                leadingIcon = { Icon(Icons.Rounded.DateRange, null, Modifier.size(InputChipDefaults.IconSize)) },
                trailingIcon = {
                    Icon(Icons.Rounded.Close, "Remove period", Modifier.size(InputChipDefaults.IconSize).clickable { onChange(null) })
                },
            )
            if (amount != null) {
                Text(
                    sharesSummary(amount, period),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (choosing) {
        CostPeriodChooser(
            period = period,
            date = date,
            onChoose = { onChange(it); choosing = false },
            onDismiss = { choosing = false },
        )
    }
}

/** "Oct 2026 170.00 · Nov 2026 300.00 · Dec 2026 150.00", shortened for long periods. */
private fun sharesSummary(amount: Long, period: CostPeriod): String {
    val shares = Periodization.shares(amount, period)
    val shown = shares.take(if (shares.size > 4) 3 else 4).joinToString(" · ") { "${it.month.shortLabel()} ${Money.format(it.amount)}" }
    return if (shares.size > 4) "$shown · … ${shares.size} months" else shown
}

@Composable
private fun CostPeriodChooser(period: CostPeriod?, date: LocalDate, onChoose: (CostPeriod) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf(false) }
    val month = date.yearMonth
    if (custom) {
        RangePickerModal(period ?: CostPeriod.month(month), onDismiss = onDismiss, onConfirm = onChoose)
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Belongs to") },
        text = {
            Column {
                Text(
                    "The cost or income is moved to these months by automatic postings, through a prepaid or accrued account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ChooserOption("Previous month", month.minusMonths(1).label()) { onChoose(CostPeriod.month(month.minusMonths(1))) }
                ChooserOption("Next month", month.plusMonths(1).label()) { onChoose(CostPeriod.month(month.plusMonths(1))) }
                ChooserOption("Date range…", "Split by days, e.g. an insurance period") { custom = true }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChooserOption(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RangePickerModal(initial: CostPeriod, onDismiss: () -> Unit, onConfirm: (CostPeriod) -> Unit) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial.start.toPickerMillis(),
        initialSelectedEndDateMillis = initial.end.toPickerMillis(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val start = state.selectedStartDateMillis?.fromPickerMillis() ?: return@TextButton
                    // Only a start chosen means a single day.
                    onConfirm(CostPeriod(start, state.selectedEndDateMillis?.fromPickerMillis() ?: start))
                },
                enabled = state.selectedStartDateMillis != null,
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier.weight(1f),
            title = { Text("Select period", Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) },
            headline = { RangeHeadline(state.selectedStartDateMillis?.fromPickerMillis(), state.selectedEndDateMillis?.fromPickerMillis()) },
        )
    }
}

/**
 * The selected range on one line, as the period chip shows it. The default headline is inset for
 * the full-screen picker and wraps in a dialog.
 */
@Composable
private fun RangeHeadline(start: LocalDate?, end: LocalDate?) {
    val format = DateTimeFormatter.ofPattern("d MMM yyyy", LocalLocale.current.platformLocale)
    val text = when {
        start == null -> "Start date – End date"
        end == null -> "${start.format(format).replace(".", "")} – End date"
        else -> CostPeriod(start, end).label()
    }
    Text(
        text,
        modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = MaterialTheme.typography.headlineSmall.fontSize),
    )
}
