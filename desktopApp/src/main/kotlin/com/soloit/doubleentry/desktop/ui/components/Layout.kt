package com.soloit.doubleentry.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.composefluent.FluentTheme
import java.awt.Cursor

/** A pane's surface: the layer color with a hairline border, as WinUI draws content areas. */
@Composable
fun Layer(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .background(FluentTheme.colors.background.layer.default, shape)
            .border(1.dp, FluentTheme.colors.stroke.card.default, shape),
        content = content,
    )
}

/** A card inside a pane. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .background(FluentTheme.colors.background.card.default, shape)
            .border(1.dp, FluentTheme.colors.stroke.card.default, shape),
        content = content,
    )
}

@Composable
fun HorizontalDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(FluentTheme.colors.stroke.divider.default))
}

/** The width of a pane the user can drag, between [min] and [max]. */
class PaneWidth(initial: Dp, val min: Dp, val max: Dp) {
    var value by mutableStateOf(initial)
}

@Composable
fun rememberPaneWidth(initial: Dp, min: Dp = 220.dp, max: Dp = 640.dp) = remember { PaneWidth(initial, min, max) }

/**
 * A splitter between panes: dragging it resizes [width]. [growsRight] is true for a pane on its
 * left (dragging right widens it), false for a pane on its right.
 */
@Composable
fun Splitter(width: PaneWidth, growsRight: Boolean) {
    val density = LocalDensity.current
    Box(
        Modifier
            .width(8.dp)
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    val change = with(density) { delta.toDp() } * (if (growsRight) 1f else -1f)
                    width.value = (width.value + change).coerceIn(width.min, width.max)
                },
            ),
    )
}
