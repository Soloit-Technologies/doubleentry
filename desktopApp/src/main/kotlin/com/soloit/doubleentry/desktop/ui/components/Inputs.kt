package com.soloit.doubleentry.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.label
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Icon
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronLeft
import io.github.composefluent.icons.regular.ChevronRight
import io.github.composefluent.icons.regular.Search
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun SearchBox(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, focus: FocusRequester? = null) {
    TextField(
        value,
        onValueChange,
        modifier = modifier.then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
        singleLine = true,
        placeholder = { Text(placeholder) },
        trailing = { Icon(Icons.Regular.Search, contentDescription = null, modifier = Modifier.padding(end = 8.dp)) },
    )
}

/** Previous and next month around the month's name. */
@Composable
fun MonthSwitcher(month: YearMonth, onChange: (YearMonth) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        SubtleButton(onClick = { onChange(month.minusMonths(1)) }, iconOnly = true) { Icon(Icons.Regular.ChevronLeft, "Previous month") }
        Text(month.label(), modifier = Modifier.width(132.dp), textAlign = TextAlign.Center, style = FluentTheme.typography.bodyStrong)
        SubtleButton(onClick = { onChange(month.plusMonths(1)) }, iconOnly = true) { Icon(Icons.Regular.ChevronRight, "Next month") }
    }
}

private val shortDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
private val mediumDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/** Reads a typed date: ISO (2026-10-18), the system's short or medium format, or a day of [today]'s month. */
fun parseDate(text: String, today: LocalDate = LocalDate.now()): LocalDate? {
    val s = text.trim()
    if (s.isEmpty()) return null
    s.toIntOrNull()?.let { day -> return runCatching { today.withDayOfMonth(day) }.getOrNull() }
    for (format in listOf(DateTimeFormatter.ISO_LOCAL_DATE, shortDate, mediumDate)) {
        runCatching { return LocalDate.parse(s, format) }
    }
    return null
}

/**
 * A date typed on the keyboard; it applies when the field loses focus or on Enter. The up and
 * down arrow keys move it by a day, with Page Up and Page Down by a month.
 */
@Composable
fun DateField(value: LocalDate, onValueChange: (LocalDate) -> Unit, modifier: Modifier = Modifier, header: String? = null) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    var invalid by remember { mutableStateOf(false) }
    fun commit() {
        val parsed = parseDate(text)
        invalid = parsed == null
        if (parsed != null && parsed != value) onValueChange(parsed)
        if (parsed != null) text = parsed.toString()
    }
    fun step(next: LocalDate) {
        text = next.toString()
        onValueChange(next)
    }
    TextField(
        text,
        { text = it; invalid = false },
        modifier = modifier
            .onFocusChanged { if (!it.isFocused) commit() }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> { step(value.plusDays(1)); true }
                    Key.DirectionDown -> { step(value.minusDays(1)); true }
                    Key.PageUp -> { step(value.plusMonths(1)); true }
                    Key.PageDown -> { step(value.minusMonths(1)); true }
                    Key.Enter, Key.NumPadEnter -> { commit(); false }
                    else -> false
                }
            },
        singleLine = true,
        isClearable = false,
        header = header?.let { { Text(if (invalid) "$it · not a date" else it) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
}

/** Places a popup right under its anchor, as wide as the anchor (at least [minWidth] px). */
private class BelowAnchor(private val minWidth: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceAtMost((windowSize.width - maxOf(popupContentSize.width, minWidth)).coerceAtLeast(0))
        val below = anchorBounds.bottom + 2
        val y = if (below + popupContentSize.height > windowSize.height) anchorBounds.top - popupContentSize.height - 2 else below
        return IntOffset(x, y.coerceAtLeast(0))
    }
}

/** "1930 Checking Account" */
fun Account.display(): String = "$number $name"

/** Accounts whose number starts with [query] first, then those whose name contains it. */
fun List<Account>.matching(query: String): List<Account> {
    val q = query.trim()
    if (q.isEmpty()) return sortedBy { it.number }
    val byNumber = filter { it.number.toString().startsWith(q) }.sortedBy { it.number }
    val words = q.lowercase().split(' ').filter { it.isNotBlank() }
    val byName = filter { a -> a !in byNumber && words.all { w -> a.display().lowercase().contains(w) } }.sortedBy { it.number }
    return byNumber + byName
}

/**
 * Chooses an account by typing its number or part of its name: matches drop down under the field,
 * the arrow keys move through them and Enter or Tab takes the highlighted one.
 */
@Composable
fun AccountPicker(
    accounts: List<Account>,
    selectedId: String?,
    onSelect: (Account) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Account",
    header: String? = null,
    enabled: Boolean = true,
    focus: FocusRequester = remember { FocusRequester() },
) {
    val selected = accounts.firstOrNull { it.id == selectedId }
    var text by remember(selected) { mutableStateOf(selected?.display().orEmpty()) }
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var highlight by remember { mutableIntStateOf(0) }
    val matches = if (editing) accounts.matching(text) else accounts.matching("")
    val listState = rememberLazyListState()
    LaunchedEffect(highlight) { if (matches.isNotEmpty()) listState.scrollToItem((highlight - 3).coerceAtLeast(0)) }

    fun choose(account: Account) {
        onSelect(account)
        text = account.display()
        editing = false
        focused = false
    }

    Box(modifier) {
        TextField(
            text,
            { text = it; editing = true; highlight = 0; focused = true },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused) { editing = false; text = selected?.display().orEmpty() }
                }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || !focused) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> { highlight = (highlight + 1).coerceAtMost(matches.lastIndex); true }
                        Key.DirectionUp -> { highlight = (highlight - 1).coerceAtLeast(0); true }
                        Key.Enter, Key.NumPadEnter -> { matches.getOrNull(highlight)?.let(::choose); editing }
                        Key.Tab -> { if (editing) matches.getOrNull(highlight)?.let(::choose); false }
                        Key.Escape -> { if (editing) { editing = false; text = selected?.display().orEmpty(); true } else false }
                        else -> false
                    }
                },
            enabled = enabled,
            singleLine = true,
            isClearable = false,
            header = header?.let { { Text(it) } },
            placeholder = { Text(placeholder) },
            keyboardActions = KeyboardActions(),
        )
        if (focused && editing && matches.isNotEmpty()) {
            val shape = RoundedCornerShape(8.dp)
            Popup(popupPositionProvider = remember { BelowAnchor(320) }, properties = PopupProperties(focusable = false)) {
                Column(
                    Modifier
                        .width(360.dp)
                        .heightIn(max = 320.dp)
                        .background(FluentTheme.colors.background.solid.base, shape)
                        .border(1.dp, FluentTheme.colors.stroke.surface.flyout, shape)
                        .padding(4.dp),
                ) {
                    LazyColumn(state = listState) {
                        itemsIndexed(matches, key = { _, a -> a.id }) { index, account ->
                            TableRow(selected = index == highlight, onClick = { choose(account) }) {
                                Text(account.number.toString(), modifier = Modifier.width(56.dp), color = FluentTheme.colors.text.text.secondary)
                                Text(account.name, modifier = Modifier.weight(1f), maxLines = 1)
                                Text(account.type.name, style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.tertiary)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A choice between a few options, as a Windows segmented control. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    io.github.composefluent.component.SegmentedControl(modifier) {
        options.forEachIndexed { index, label ->
            io.github.composefluent.component.SegmentedButton(
                checked = index == selected,
                onCheckedChanged = { onSelect(index) },
                position = when (index) {
                    0 -> io.github.composefluent.component.SegmentedItemPosition.Start
                    options.lastIndex -> io.github.composefluent.component.SegmentedItemPosition.End
                    else -> io.github.composefluent.component.SegmentedItemPosition.Center
                },
                text = { Text(label) },
            )
        }
    }
}
