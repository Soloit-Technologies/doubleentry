package com.soloit.doubleentry.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.domain.label
import com.soloit.doubleentry.domain.monthAbbreviation
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/** ‹  October 2026 ▾  › — previous/next buttons around a tappable label. */
@Composable
fun PeriodSelector(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    onLabelClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous") }
            TextButton(
                onClick = { onLabelClick?.invoke() },
                enabled = onLabelClick != null,
                modifier = Modifier.weight(1f),
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                if (onLabelClick != null) Icon(Icons.Rounded.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next") }
        }
    }
}

@Composable
fun MonthSelector(month: YearMonth, onMonthChange: (YearMonth) -> Unit, modifier: Modifier = Modifier) {
    var picking by rememberSaveable { mutableStateOf(false) }
    PeriodSelector(
        label = month.label(),
        onPrevious = { onMonthChange(month.minusMonths(1)) },
        onNext = { onMonthChange(month.plusMonths(1)) },
        onLabelClick = { picking = true },
        modifier = modifier,
    )
    if (picking) {
        MonthPickerDialog(month, onDismiss = { picking = false }, onConfirm = { onMonthChange(it); picking = false })
    }
}

@Composable
fun YearSelector(year: Int, onYearChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    PeriodSelector(
        label = year.toString(),
        onPrevious = { onYearChange(year - 1) },
        onNext = { onYearChange(year + 1) },
        onLabelClick = { onYearChange(LocalDate.now().year) },
        modifier = modifier,
    )
}

/** Year stepper above a 3×4 grid of months (2×6 with a large font). */
@Composable
fun MonthPickerDialog(initial: YearMonth, onDismiss: () -> Unit, onConfirm: (YearMonth) -> Unit, title: String = "Choose month") {
    var year by rememberSaveable { mutableIntStateOf(initial.year) }
    val columns = if (isLargeFont()) 2 else 3
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { year-- }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous year") }
                    Text(year.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { year++ }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next year") }
                }
                for (row in 0 until 12 / columns) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (col in 0 until columns) {
                            val month = YearMonth.of(year, row * columns + col + 1)
                            val selected = month == initial
                            val text = month.monthAbbreviation()
                            val padding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                            val label = @Composable { Text(text, maxLines = 1, autoSize = fitToWidth(MaterialTheme.typography.labelLarge)) }
                            if (selected) {
                                FilledTonalButton(onClick = { onConfirm(month) }, modifier = Modifier.weight(1f), contentPadding = padding) { label() }
                            } else {
                                OutlinedButton(onClick = { onConfirm(month) }, modifier = Modifier.weight(1f), contentPadding = padding) { label() }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(YearMonth.now()) }) { Text("This month") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun Long.fromPickerMillis(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
fun DatePickerModal(initial: LocalDate, onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.toPickerMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { onConfirm(it.fromPickerMillis()) } ?: onDismiss() }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}

/** Read-only text field that opens a date picker when tapped. */
@Composable
fun DateField(
    label: String,
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is PressInteraction.Release) open = true }
    }
    OutlinedTextField(
        value = date.formatMedium(),
        onValueChange = {},
        readOnly = true,
        enabled = enabled,
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Rounded.CalendarMonth, null) },
        interactionSource = interaction,
        singleLine = true,
        modifier = modifier,
    )
    if (open) DatePickerModal(date, onDismiss = { open = false }, onConfirm = { onDateChange(it); open = false })
}

/**
 * Text field for money amounts with a decimal keyboard. Focusing it selects the whole amount,
 * so typing replaces a prefilled value instead of appending to it.
 */
@Composable
fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    supportingText: String? = null,
) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // The tap that focuses the field moves the cursor to the tap point right after focus is
    // gained; this swallows that one move so the select-all survives.
    var keepSelection by remember { mutableStateOf(false) }
    if (field.text != value) {
        val allSelected = field.selection.min == 0 && field.selection.max == field.text.length
        field = TextFieldValue(value, if (allSelected) TextRange(0, value.length) else TextRange(value.length))
    }
    LaunchedEffect(keepSelection) { keepSelection = false }
    OutlinedTextField(
        value = field,
        onValueChange = { new ->
            val text = new.text.filter { it.isDigit() || it in ".,- " }
            if (keepSelection && text == field.text && new.selection.collapsed) {
                keepSelection = false
                return@OutlinedTextField
            }
            keepSelection = false
            field = if (text == new.text) new else TextFieldValue(text, TextRange(text.length))
            if (text != value) onValueChange(text)
        },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        enabled = enabled,
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
        supportingText = supportingText?.let { { Text(it) } },
        modifier = modifier.onFocusChanged {
            if (it.isFocused) {
                field = field.copy(selection = TextRange(0, field.text.length))
                keepSelection = true
            }
        },
    )
}

@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    // A large font leaves too little room per segment, so the options wrap as chips instead.
    if (isLargeFont()) {
        FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
            }
        }
        return
    }
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label(option), maxLines = 1, autoSize = fitToWidth(MaterialTheme.typography.labelLarge)) }
        }
    }
}

@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Rounded.Close, "Clear search") }
        },
        singleLine = true,
        shape = CircleShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
    )
}

/**
 * Scrolls the on-screen item keyed [key] to the top of the list. Used when a search field below a
 * summary card gets focus, so its results show between it and the keyboard.
 */
suspend fun LazyListState.scrollItemToTop(key: Any) {
    layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let { animateScrollToItem(it.index) }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

/** Simple informational dialog with a single button. */
@Composable
fun MessageDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
fun FieldRow(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) = Icon(icon, null, Modifier.size(18.dp))
