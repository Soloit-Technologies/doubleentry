package com.soloit.doubleentry.desktop.ui.components

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.ScrollbarContainer
import io.github.composefluent.component.Text
import io.github.composefluent.component.rememberScrollbarAdapter
import kotlinx.coroutines.launch

/** One column: a fixed [width], or a share of the space left ([weight]). */
class TableColumn<T>(
    val title: String,
    val width: Dp? = null,
    val weight: Float = 1f,
    val alignEnd: Boolean = false,
    val cell: @Composable (T) -> Unit,
)

/** A text cell that stays on one line. */
@Composable
fun CellText(text: String, secondary: Boolean = false) {
    Text(
        text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = if (secondary) FluentTheme.colors.text.text.secondary else Color.Unspecified,
    )
}

@Composable
private fun <T> RowScope.Cells(columns: List<TableColumn<T>>, content: @Composable (TableColumn<T>) -> Unit) {
    columns.forEach { column ->
        val size = if (column.width != null) Modifier.width(column.width) else Modifier.weight(column.weight)
        Box(size.padding(horizontal = 8.dp), contentAlignment = if (column.alignEnd) Alignment.CenterEnd else Alignment.CenterStart) {
            content(column)
        }
    }
}

/**
 * A list of rows in columns, like a Windows details view: click selects, double-click or Enter
 * opens, the arrow keys move the selection, Delete deletes and right-click shows [contextMenu].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> DataTable(
    rows: List<T>,
    key: (T) -> Any,
    columns: List<TableColumn<T>>,
    modifier: Modifier = Modifier,
    selected: Any? = null,
    onSelect: (T) -> Unit = {},
    onOpen: (T) -> Unit = {},
    onDelete: ((T) -> Unit)? = null,
    contextMenu: (T) -> List<ContextMenuItem> = { emptyList() },
    state: LazyListState = rememberLazyListState(),
    footer: LazyListScope.() -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val selectedIndex = rows.indexOfFirst { key(it) == selected }

    // Keep the selection in view when it moves by keyboard or from elsewhere.
    LaunchedEffect(selectedIndex) {
        if (selectedIndex < 0) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == selectedIndex && it.offset >= 0 }) state.animateScrollToItem((selectedIndex - 2).coerceAtLeast(0))
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Cells(columns) { Text(it.title, style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.secondary, maxLines = 1) }
        }
        HorizontalDivider()
        ScrollbarContainer(adapter = rememberScrollbarAdapter(state), modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = state,
                contentPadding = PaddingValues(vertical = 4.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focus)
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || rows.isEmpty()) return@onKeyEvent false
                        val current = selectedIndex
                        when (event.key) {
                            Key.DirectionDown -> { onSelect(rows[(current + 1).coerceAtMost(rows.lastIndex)]); true }
                            Key.DirectionUp -> { onSelect(rows[(current - 1).coerceAtLeast(0)]); true }
                            Key.MoveHome -> { onSelect(rows.first()); true }
                            Key.MoveEnd -> { onSelect(rows.last()); true }
                            Key.Enter, Key.NumPadEnter -> { if (current >= 0) onOpen(rows[current]); current >= 0 }
                            Key.Delete -> { if (current >= 0 && onDelete != null) onDelete(rows[current]); current >= 0 && onDelete != null }
                            else -> false
                        }
                    },
            ) {
                itemsIndexed(rows, key = { _, row -> key(row) }) { _, row ->
                    val isSelected = key(row) == selected
                    ContextMenuArea(items = { contextMenu(row) }) {
                        TableRow(
                            selected = isSelected,
                            onClick = { onSelect(row); scope.launch { focus.requestFocus() } },
                            onDoubleClick = { onOpen(row) },
                        ) { Cells(columns) { it.cell(row) } }
                    }
                }
                footer()
            }
        }
    }
}

/** A row with the WinUI list look: subtle hover, accent pill on the selected one. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TableRow(
    selected: Boolean,
    onClick: () -> Unit,
    onDoubleClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val background = if (selected) FluentTheme.colors.subtleFill.secondary else Color.Transparent
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .hoverHighlight(interaction, selected)
            .combinedClickable(interactionSource = interaction, indication = null, onDoubleClick = onDoubleClick, onClick = onClick),
    ) {
        if (selected) {
            Box(
                Modifier.align(Alignment.CenterStart).width(3.dp).height(16.dp)
                    .background(FluentTheme.colors.fillAccent.default, RoundedCornerShape(2.dp)),
            )
        }
        Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun Modifier.hoverHighlight(interaction: MutableInteractionSource, selected: Boolean): Modifier {
    val hovered by interaction.collectIsHoveredAsState()
    return if (hovered && !selected) background(FluentTheme.colors.subtleFill.secondary) else this
}

/** A plain row of cells without selection, for totals and summaries under a table. */
@Composable
fun <T> SummaryRow(columns: List<TableColumn<T>>, modifier: Modifier = Modifier, cell: @Composable (TableColumn<T>) -> Unit) {
    Row(modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Cells(columns, cell)
    }
}
